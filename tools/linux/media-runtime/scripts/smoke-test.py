#!/usr/bin/env python3
"""Offline decoded-output tests; optional displayed hardware progress checks."""
import argparse
import base64
import hashlib
import json
import pathlib
import re
import struct
import tempfile
from runtime_features import (command, query_features, check_features,
                              validate_playback_report, PLAYBACK_VECTORS)

TOOLS = pathlib.Path(__file__).resolve().parents[1]
VECTORS = PLAYBACK_VECTORS
SCRIPT = TOOLS / 'tests/playback-progress.lua'


def fixture(directory, name, vector, extension='mkv'):
    data = base64.b64decode(vector['base64'], validate=True)
    if hashlib.sha256(data).hexdigest() != vector['sha256']:
        raise ValueError('Corrupt fixture: ' + name)
    path = directory / (name + '.' + extension)
    path.write_bytes(data)
    return path


def video(root, directory, name, vector, hwdec='no'):
    source = fixture(directory, name, vector)
    probe = json.loads(command([root / 'bin/ffprobe', '-v', 'error', '-show_entries',
        'stream=codec_name,profile,pix_fmt,width,height,r_frame_rate,bits_per_raw_sample',
        '-of', 'json', source]))['streams'][0]
    if probe != vector['stream']:
        raise ValueError('Fixture codec/profile/depth mismatch: ' + name)
    expected = vector['expected_frames']
    decode = command([root / 'bin/ffmpeg', '-hide_banner', '-v', 'error', '-nostats',
        '-progress', 'pipe:1', '-i', source, '-map', '0:v:0', '-frames:v', str(expected), '-f', 'null', '-'])
    if not re.search(r'^frame=' + str(expected) + '$', decode, re.M):
        raise ValueError('FFmpeg decoded frame count mismatch: ' + name)
    frames = directory / (name + '-frames-' + hwdec)
    frames.mkdir()
    playback = command([root / 'bin/mpv', '--no-config', '--vo=image',
        '--vo-image-format=png', '--vo-image-outdir=' + str(frames), '--ao=null',
        '--hwdec=' + hwdec, '--frames=' + str(expected), '--msg-level=vd=debug', source])
    images = sorted(frames.glob('*.png'))
    if len(images) != expected:
        raise ValueError(f'mpv output frame count mismatch: {name}: {len(images)} != {expected}')
    # Count *decodable* output images, not files or decoder-initialization logs.
    decoded = command([root / 'bin/ffmpeg', '-v', 'error', '-nostats', '-progress', 'pipe:1',
        '-i', frames / '%08d.png', '-f', 'null', '-'])
    if not re.search(r'^frame=' + str(expected) + '$', decoded, re.M):
        raise ValueError('Invalid mpv image output: ' + name)
    if 'Using software decoding' not in playback or 'Exiting... (End of file)' not in playback:
        raise ValueError('Software playback did not reach clean EOF: ' + name)
    return dict(stream=probe, ffmpeg_decoded_frames=expected, mpv_output_frames=len(images),
                hwdec=hwdec, decoding='software', reached_eof=True, result='PASS')


def progress(output, minimum):
    matches = re.findall(r'NUVIO_PROGRESS (\{[^\n]+\})', output)
    if len(matches) != 1:
        raise ValueError('Missing/unexpected playback progress event')
    state = json.loads(matches[0])
    if state['reason'] != 'eof' or state.get('error') or state['position'] < minimum:
        raise ValueError('Insufficient playback progress or failed EOF: ' + repr(state))
    return state


def pcm_samples(path):
    # mpv writes WAVE_FORMAT_EXTENSIBLE, unsupported by Python 3.10's wave
    # module in the pinned builder. Read the PCM format/data chunks directly.
    data = path.read_bytes()
    if data[:4] != b'RIFF' or data[8:12] != b'WAVE' or len(data) != 8 + struct.unpack_from('<I', data, 4)[0]:
        raise ValueError('Invalid PCM RIFF header')
    offset, fmt, samples = 12, None, None
    while offset + 8 <= len(data):
        kind, size = struct.unpack_from('<4sI', data, offset)
        offset += 8
        chunk = data[offset:offset + size]
        if len(chunk) != size:
            raise ValueError('Truncated PCM chunk')
        if kind == b'fmt ': fmt = chunk
        if kind == b'data': samples = chunk
        offset += size + size % 2
    if fmt is None or len(fmt) < 16 or samples is None:
        raise ValueError('Missing PCM format/data')
    tag, channels, rate, byte_rate, align, bits = struct.unpack_from('<HHIIHH', fmt)
    if tag == 0xfffe:
        if len(fmt) < 40 or struct.unpack_from('<HH', fmt, 16) != (22, 16) or fmt[24:40] != bytes.fromhex('0100000000001000800000aa00389b71'):
            raise ValueError('Unsupported extensible PCM format')
    elif tag != 1:
        raise ValueError('Non-PCM audio output')
    if (channels, rate, byte_rate, align, bits) != (1, 48000, 96000, 2, 16):
        raise ValueError('Incorrect PCM output format')
    if len(samples) % align or not any(samples):
        raise ValueError('Invalid/empty decoded PCM')
    return len(samples) // align


def audio(root, directory, name, vector, passthrough=False):
    source = fixture(directory, name, vector, vector['extension'])
    probe = json.loads(command([root / 'bin/ffprobe', '-v', 'error', '-show_entries',
        'stream=codec_name,sample_rate,channels', '-of', 'json', source]))['streams'][0]
    if probe != dict(codec_name=name, sample_rate=str(vector['sample_rate']), channels=vector['channels']):
        raise ValueError('Audio fixture mismatch: ' + name)
    args = [root / 'bin/mpv', '--no-config', '--vo=null', '--script=' + str(SCRIPT)]
    if passthrough:
        # Keep null-sink buffering small so observed position includes the final
        # packets instead of ending while most progress is still queued in AO.
        output = command(args + ['--ao=null', '--audio-buffer=0', '--ao-null-buffer=0.01',
                                 '--audio-spdif=' + name, source], timeout=10)
        state = progress(output, vector['minimum_position'])
        if not re.search(r'AO: \[null\].*spdif-' + name, output):
            raise ValueError('mpv did not initialize passthrough output: ' + name)
        return dict(result='PASS', format='spdif-' + name, reached_eof=True,
                    minimum_position=vector['minimum_position'])
    pcm = directory / (name + '.wav')
    command(args + ['--ao=pcm', '--ao-pcm-file=' + str(pcm), '--audio-format=s16',
                   '--audio-samplerate=48000', '--audio-channels=mono', source], timeout=10)
    samples = pcm_samples(pcm)
    if samples < vector['min_samples']:
        raise ValueError('Insufficient decoded PCM: ' + name)
    return dict(result='PASS', pcm_samples=samples, sample_rate=48000, channels=1,
                sample_format='s16')


def hardware(root, directory, name, vector, hwdec='auto'):
    source = fixture(directory, name, vector)
    output = command([root / 'bin/mpv', '--no-config', '--vo=gpu-next', '--gpu-api=vulkan',
        '--gpu-context=x11vk', '--ao=null', '--hwdec=' + hwdec, '--script=' + str(SCRIPT),
        '--msg-level=vd=debug', source], timeout=20)
    state = progress(output, vector['minimum_position'])
    if 'VO: [gpu-next]' not in output:
        raise ValueError('No displayed GPU video output')
    match = re.search(r'Using hardware decoding \(([^)]+)\)', output)
    backend = match.group(1) if match else 'software fallback'
    if match and state['hwdec'] != backend:
        raise ValueError('Hardware log/property mismatch')
    # Time-pos is a progress observation, not an exact rendered-frame counter.
    return dict(result='PASS', backend=backend, minimum_position=vector['minimum_position'], reached_eof=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('runtime', type=pathlib.Path)
    parser.add_argument('--hardware', action='store_true')
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    root = args.runtime.resolve(strict=True)
    config = json.loads((root / 'share/nuvio-media-runtime/compile-features.json').read_text())
    compiled = query_features(root)
    check_features(config, compiled)
    report = dict(compiled=compiled, config=config, software={}, audio={}, passthrough={}, hardware={},
                  validated={name: 'NOT YET HARDWARE VALIDATED' for name in ('NVIDIA', 'AMD', 'Intel')})
    with tempfile.TemporaryDirectory(prefix='nuvio-media-smoke-') as tmp:
        directory = pathlib.Path(tmp)
        for name, vector in sorted(VECTORS['vectors'].items()):
            report['software'][name] = video(root, directory, name, vector)
            if name == 'h264-high10':
                report['software']['high10_auto_fallback'] = video(root, directory, name, vector, 'auto')
            elif args.hardware:
                report['hardware'][name] = hardware(root, directory, name, vector)
        for name, vector in sorted(VECTORS['audio_vectors'].items()):
            report['audio'][name] = audio(root, directory, name, vector)
            if name in ('ac3', 'eac3'):
                report['passthrough'][name] = audio(root, directory, name, vector, True)
    if args.hardware:
        backends = {r['backend'] for r in report['hardware'].values()}
        if backends == {'software fallback'}:
            raise ValueError('No real hardware backend validated')
        if any('nvdec' in b or 'cuda' in b for b in backends):
            report['validated']['NVIDIA'] = 'VALIDATED on this host only'
    report['result'] = 'PASS'
    validate_playback_report(report)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
    print('PASS: decoded video output, PCM samples and bounded SPDIF playback' +
          ('; displayed hardware progress validated' if args.hardware else '; hardware UNTESTED'))


if __name__ == '__main__':
    main()
