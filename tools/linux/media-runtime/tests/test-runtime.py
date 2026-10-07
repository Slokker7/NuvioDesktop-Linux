#!/usr/bin/env python3
"""Adversarial regression tests on a disposable copy of a completed runtime.

Run inside the builder (patchelf/binutils required). An optional old no-SPDIF
runtime directly exercises the permanent passthrough test's failure path.
"""
import argparse
import copy
import importlib.util
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

TOOLS = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS / 'scripts'))
from artifact_contract import seal, digest, INVENTORY, VALIDATED
from runtime_features import validate_playback_report

spec = importlib.util.spec_from_file_location('smoke', TOOLS / 'scripts/smoke-test.py')
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


def playback_mutations(baseline):
    """Mutate evidence independently of the production validator's decisions."""
    missing = object()

    def changed(path, value):
        report = copy.deepcopy(baseline)
        parent = report
        for key in path[:-1]:
            parent = parent[key]
        if value is missing:
            parent.pop(path[-1])
        else:
            parent[path[-1]] = value
        return report

    original = copy.deepcopy(baseline)
    original['software']['h264'].update(ffmpeg_decoded_frames=5, mpv_output_frames=1, result='FAIL')
    original['result'] = 'PASS'
    yield 'original_reproducer', original
    for section in ('software', 'audio', 'passthrough'):
        for label, value in (('missing', missing), ('null', None), ('array', [])):
            yield section + '_' + label, changed((section,), value)
        for name in baseline[section]:
            for label, value in (('missing', missing), ('null', None), ('array', [])):
                yield section + '_' + name + '_' + label, changed((section, name), value)
            for label, value in (('missing_result', missing), ('failed', 'FAIL')):
                yield section + '_' + name + '_' + label, changed((section, name, 'result'), value)

    for key in ('mpv_output_frames', 'ffmpeg_decoded_frames'):
        for label, value in (('missing', missing), ('zero', 0), ('one', 1), ('four', 4),
                             ('negative', -1), ('string', '5'), ('float', 5.0),
                             ('bool', True), ('null', None), ('object', {})):
            yield key + '_' + label, changed(('software', 'h264', key), value)
    for name in ('h264-high10', 'high10_auto_fallback'):
        yield name + '_short', changed(('software', name, 'mpv_output_frames'), 4)
        yield name + '_wrong_depth', changed(('software', name, 'stream', 'pix_fmt'), 'yuv420p')
    for key, value in (('hwdec', 'no'), ('decoding', 'hardware'), ('reached_eof', False),
                       ('reached_eof', 1), ('hwdec', missing)):
        label = 'missing' if value is missing else str(value)
        yield 'fallback_' + key + '_' + label, changed(('software', 'high10_auto_fallback', key), value)
    yield 'video_stream_null', changed(('software', 'av1', 'stream'), None)
    yield 'video_wrong_codec', changed(('software', 'hevc', 'stream', 'codec_name'), 'h264')

    for label, value in (('missing', missing), ('zero', 0), ('short', 47999), ('negative', -1),
                         ('string', '49152'), ('float', 49152.0), ('bool', True), ('null', None)):
        yield 'pcm_samples_' + label, changed(('audio', 'aac', 'pcm_samples'), value)
    for key, value in (('sample_format', 'float'), ('sample_format', 's32'),
                       ('sample_format', missing), ('sample_rate', 44100),
                       ('sample_rate', '48000'), ('channels', 2), ('channels', True)):
        label = 'missing' if value is missing else str(value)
        yield 'audio_' + key + '_' + label, changed(('audio', 'ac3', key), value)

    for name in ('ac3', 'eac3'):
        for label, key, value in (
            ('no_eof', 'reached_eof', False), ('missing_eof', 'reached_eof', missing),
            ('numeric_eof', 'reached_eof', 1), ('wrong_format', 'format', 'pcm'),
            ('missing_progress', 'minimum_position', missing), ('zero', 'minimum_position', 0),
            ('short', 'minimum_position', 0.78), ('null', 'minimum_position', None),
            ('string', 'minimum_position', '0.99'), ('bool', 'minimum_position', True),
            ('nan', 'minimum_position', float('nan')), ('infinite', 'minimum_position', float('inf'))):
            yield name + '_passthrough_' + label, changed(('passthrough', name, key), value)
    yield 'summary_fail', changed(('result',), 'FAIL')
    yield 'summary_missing', changed(('result',), missing)
    yield 'root_array', []
    yield 'root_null', None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('runtime', type=pathlib.Path)
    parser.add_argument('--without-spdif', type=pathlib.Path)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    source = args.runtime.resolve(strict=True)
    results = {}
    with tempfile.TemporaryDirectory(prefix='nuvio-mutations-') as temp:
        temp = pathlib.Path(temp)
        root = temp / 'runtime with spaces'

        def audit():
            p = subprocess.run([sys.executable, str(TOOLS / 'scripts/audit-runtime.py'), str(root)],
                               capture_output=True, text=True, timeout=60)
            return p.returncode, p.stdout + p.stderr

        def reset():
            if root.exists(): shutil.rmtree(root)
            shutil.copytree(source, root, symlinks=True)

        def marker(action):
            build = json.loads((root / 'share/nuvio-media-runtime/build-info.json').read_text())
            return subprocess.run([sys.executable, str(TOOLS / 'scripts/artifact_contract.py'),
                                   str(root), action, '--recipe-hash', build['recipe_sha256'],
                                   '--builder-image-id', build['builder_image_id']],
                                  capture_output=True, text=True, timeout=60)

        def mutation(name, change):
            reset()
            change()
            # Attacks must survive a regenerated inventory, not merely hit SHA checks.
            seal(root)
            code, output = audit()
            if code == 0: raise AssertionError('Audit accepted mutation: ' + name)
            results[name] = 'REJECTED'

        reset()
        code, output = audit()
        if code: raise AssertionError(output)
        results['relocated_baseline'] = 'PASS'
        baseline = json.loads((root / 'share/nuvio-media-runtime/capabilities.json').read_text())
        validate_playback_report(baseline)
        for action in ('--mark-validated', '--validated'):
            p = marker(action)
            if p.returncode: raise AssertionError(p.stdout + p.stderr)
        results['playback_baseline'] = 'PASS: semantic validator, audit, marker and host'

        # Unknown informational metadata is allowed, without replacing required tests.
        enriched = copy.deepcopy(baseline)
        enriched['note'] = 'additional metadata'
        enriched['software']['h264']['stream']['note'] = 'additional metadata'
        enriched['software']['future_probe'] = {'note': 'additional metadata'}
        capability_path = root / 'share/nuvio-media-runtime/capabilities.json'
        capability_path.write_text(json.dumps(enriched))
        seal(root)
        validate_playback_report(enriched)
        code, output = audit()
        if code: raise AssertionError(output)
        for action in ('--mark-validated', '--validated'):
            p = marker(action)
            if p.returncode: raise AssertionError(p.stdout + p.stderr)
        results['playback_extra_metadata'] = 'PASS: all entry points'

        semantic_results = {}
        reset()
        for name, report in playback_mutations(baseline):
            capability_path.write_text(json.dumps(report))
            seal(root)
            # Supply a marker bound to the *mutated* inventory. Host rejection
            # must be semantic, not an incidental stale-hash/missing-marker failure.
            (root / VALIDATED).write_text(json.dumps(dict(
                inventory_sha256=digest(root / INVENTORY), result='PASS')) + '\n')
            code, output = audit()
            outcomes = {'audit': code}
            if code == 0 or 'Invalid playback report:' not in output:
                raise AssertionError('Audit failed to reject playback evidence: ' + name + '\n' + output)
            for action in ('--validated', '--mark-validated'):
                p = marker(action)
                outcomes[action] = p.returncode
                if p.returncode == 0 or 'Invalid playback report:' not in p.stdout + p.stderr:
                    raise AssertionError(action + ' failed to reject ' + name + '\n' + p.stdout + p.stderr)
            if (root / VALIDATED).exists() or (root / VALIDATED).is_symlink():
                raise AssertionError('Failed validation left a stale marker: ' + name)
            semantic_results[name] = outcomes
        results['playback_semantic_mutations'] = semantic_results

        lib = root / 'lib/libmpv.so.2.5.0'
        mutation('absolute_runpath', lambda: subprocess.run(['patchelf', '--set-rpath', '/usr/lib', str(lib)], check=True))
        mutation('absolute_needed', lambda: subprocess.run(['patchelf', '--add-needed', '/usr/lib/libz.so.1', str(lib)], check=True))
        mutation('mandatory_cuda', lambda: subprocess.run(['patchelf', '--add-needed', 'libcuda.so.1', str(lib)], check=True))
        mutation('escaping_symlink', lambda: (root / 'lib/escape').symlink_to('/outside'))
        mutation('missing_private_dso', lambda: (root / 'lib/libdav1d.so.7.0.0').unlink())
        libc = next(pathlib.Path('/lib').glob('**/libc.so.6'))
        mutation('bundled_libc', lambda: shutil.copyfile(libc, root / 'lib/libc.so.6'))
        for text in ('Homebrew', '/home/developer/build', '/tmp/development'):
            mutation('path_' + text, lambda text=text: (root / 'lib/pkgconfig/mpv.pc').write_text(text))
        mutation('unexpected_executable', lambda: shutil.copy2(root / 'bin/ffprobe', root / 'bin/unexpected'))

        def substitute():
            (root / 'bin/mpv').unlink()
            (root / 'bin/mpv').symlink_to('ffprobe')
        mutation('mpv_symlink_substitution', substitute)
        mutation('ffprobe_executable_substitution', lambda: shutil.copy2(root / 'bin/ffmpeg', root / 'bin/ffprobe'))
        mutation('library_symlink_substitution', lambda: replace_link(root / 'lib/libmpv.so', 'libass.so.9'))
        for filename, change in [
            ('compile-features.json', lambda j: j['mpv'].update(HAVE_DRM='0')),
            ('capabilities.json', lambda j: j['compiled'].update(spdif_muxer=False)),
        ]:
            def mutate(filename=filename, change=change):
                path = root / 'share/nuvio-media-runtime' / filename
                data = json.loads(path.read_text()); change(data); path.write_text(json.dumps(data))
            mutation(filename, mutate)
        def hardware_claim():
            path = root / 'share/nuvio-media-runtime/capabilities.json'
            data = json.loads(path.read_text())
            data['validated']['NVIDIA'] = 'VALIDATED'
            path.write_text(json.dumps(data))
        mutation('unsupported_hardware_claim', hardware_claim)

        reset()
        # A real mpv is deliberately limited to one frame: PNG output count must
        # reject it even though decoding starts and exit status remains zero.
        player = root / 'bin/mpv'
        player.rename(root / 'bin/mpv-real')
        player.write_text('#!/bin/sh\nexec "$(dirname -- "$0")/mpv-real" "$@" --frames=1\n')
        player.chmod(0o755)
        with tempfile.TemporaryDirectory(dir=temp) as directory:
            try: smoke.video(root, pathlib.Path(directory), 'h264', smoke.VECTORS['vectors']['h264'])
            except ValueError as exc:
                if 'output frame count mismatch' not in str(exc): raise
                results['one_frame_playback'] = 'REJECTED'
            else: raise AssertionError('One-frame playback incorrectly passed')
        # The positive passthrough case always runs, independently of metadata.
        with tempfile.TemporaryDirectory(dir=temp) as directory:
            for codec in ('ac3', 'eac3'):
                smoke.audio(source, pathlib.Path(directory), codec, smoke.VECTORS['audio_vectors'][codec], True)
        results['passthrough_positive'] = 'PASS'
        if args.without_spdif:
            with tempfile.TemporaryDirectory(dir=temp) as directory:
                try:
                    smoke.audio(args.without_spdif.resolve(), pathlib.Path(directory), 'ac3',
                                smoke.VECTORS['audio_vectors']['ac3'], True)
                except (ValueError, RuntimeError, subprocess.TimeoutExpired):
                    results['passthrough_without_muxer'] = 'REJECTED'
                else: raise AssertionError('Missing SPDIF muxer incorrectly passed')
    args.output.write_text(json.dumps(results, indent=2, sort_keys=True) + '\n')
    print(json.dumps(results, indent=2, sort_keys=True))


def replace_link(path, target):
    path.unlink()
    path.symlink_to(target)


if __name__ == '__main__':
    main()
