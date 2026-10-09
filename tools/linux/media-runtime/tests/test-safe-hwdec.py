#!/usr/bin/env python3
"""Backport/source checks; optional displayed auto and explicit-backend checks.

CPU decoded-output/EOF coverage lives in smoke-test.py and its audited report.
The optional hardware tier needs a compatible host and a local HEVC fixture;
it never requires NVIDIA activation in ordinary CPU CI.
"""
import argparse
import importlib.util
import json
import pathlib
import re
import sys
import tempfile

TOOLS = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS / 'scripts'))
from source_patches import declared, verify, check_source
from runtime_features import (command, query_features, validate_playback_report,
                              hwdec_candidates, selected_hwdec, validate_hwdec_policy)

COMMIT = 'd20d108d94e288263a536dfaac1eda995b9a434e'


def parser_controls():
    """Permanent boundary cases; real unpatched playback is tested separately."""
    cases = [
        ('auto_software', 'auto', [], 'no', True),
        ('auto_nvdec', 'auto', ['h264-nvdec'], 'nvdec', True),
        ('auto_vaapi', 'auto', ['hevc-vaapi'], 'vaapi', True),
        ('copy_skips_direct', 'auto-copy', ['hevc-nvdec', 'hevc-nvdec-copy'], 'nvdec-copy', True),
        ('copy_software', 'auto-copy', ['h264-vaapi', 'h264-vaapi-copy'], 'no', True),
        ('explicit_vulkan', 'vulkan', ['hevc-vulkan'], 'vulkan', True),
        ('explicit_vulkan_copy', 'vulkan-copy', ['hevc-vulkan-copy'], 'vulkan-copy', True),
        ('renderer_only', 'auto', [], 'no', True),
        ('h264_vulkan_device_failure', 'auto', ['h264-vulkan'], 'no', False),
        ('hevc_vulkan_device_failure', 'auto', ['hevc-vulkan'], 'no', False),
        ('h264_vulkan_copy_device_failure', 'auto', ['h264-vulkan-copy'], 'no', False),
        ('hevc_vulkan_copy_device_failure', 'auto', ['hevc-vulkan-copy'], 'no', False),
        ('bare_vulkan', 'auto', ['vulkan'], 'no', False),
        ('bare_vulkan_copy', 'auto', ['vulkan-copy'], 'no', False),
        ('copy_vulkan', 'auto-copy', ['h264-vulkan'], 'no', False),
        ('copy_vulkan_copy', 'auto-copy', ['hevc-vulkan-copy'], 'no', False),
        ('copy_selected_direct', 'auto-copy', ['h264-nvdec'], 'nvdec', False),
        ('selected_vulkan', 'auto', [], 'vulkan', False),
    ]
    results = {}
    for label, mode, candidates, backend, accepted in cases:
        output = '[vo/gpu-next] Vulkan renderer gpu-api=vulkan gpu-context=x11vk\n'
        output += '[vo/gpu-next] Looking at hwdec h264-vulkan...\n'  # Not a decoder event.
        for name in candidates:
            output += '[vd] Looking at hwdec ' + name + '...\n'
            if backend == 'no':
                output += '[vd] Could not create device.\n'
        output += ('[vd] Using software decoding.\n' if backend == 'no' else
                   ('Using' if label == 'auto_nvdec' else '[vd] Using') +
                   ' hardware decoding (' + backend + ').\n')
        observed = hwdec_candidates(output)
        if observed != candidates or selected_hwdec(output) != backend:
            raise AssertionError('Incorrect hwdec observation: ' + label)
        try:
            validate_hwdec_policy(mode, observed, backend)
        except ValueError:
            if accepted:
                raise
            results[label] = 'REJECTED'
        else:
            if not accepted:
                raise AssertionError('Unsafe policy accepted: ' + label)
            results[label] = 'PASS'
    # Device readiness is not successful decoding, and hardware may fall back.
    output = ('[vd] Trying hardware decoding via h264-nvdec.\n'
              '[vd] Using hardware decoding (nvdec).\n[vd] Using software decoding.\n')
    if hwdec_candidates(output) or selected_hwdec(output) != 'no':
        raise AssertionError('Device readiness/earlier backend confused with final outcome')
    results['later_software_fallback'] = 'PASS'
    return results


def cpu_controls(smoke, root, unpatched=None, logs=None):
    """GPU-free real playback. Optional unpatched 0.41 is an executable control,
    not fabricated text or a declaration-only provenance mutation.
    """
    results = {}
    if unpatched is not None:
        version = command([unpatched / 'bin/mpv', '--version']).splitlines()[0]
        if not re.match(r'^mpv v?0\.41\.0(?:\s|$)', version):
            raise ValueError('Negative control must be real mpv 0.41.0')
    with tempfile.TemporaryDirectory(prefix='nuvio-hwdec-cpu-') as temporary:
        directory = pathlib.Path(temporary)
        for name in ('h264', 'hevc'):
            for mode in ('auto', 'auto-copy'):
                for label, runtime in [('patched', root)] + ([('unpatched', unpatched)] if unpatched else []):
                    key = label + '_' + name + '_' + mode
                    work = directory / key
                    work.mkdir()
                    log = (logs / (key + '.log')) if logs else work / 'playback.log'
                    try:
                        state = smoke.video(runtime, work, name, smoke.VECTORS['vectors'][name], mode, log)
                    except ValueError as exc:
                        if label != 'unpatched' or str(exc) != 'Safe automatic selection considered Vulkan':
                            raise
                        output = log.read_text()
                        # video() already checked five decodable images/software EOF
                        # before policy rejection. Prove the original pre-Trying blind spot.
                        if '[vd] Trying hardware decoding via ' in output:
                            raise AssertionError('Negative control requires GPU-free device failure')
                        failed = re.findall(r'^\[vd\] Looking at hwdec ([a-z0-9_-]+)\.\.\.\n'
                                            r'(?:(?!\[vd\] Looking at hwdec).)*?'
                                            r'\[vd\] Could not create device\.', output, re.M | re.S)
                        required = [name + '-vulkan-copy']
                        if mode == 'auto':
                            required.append(name + '-vulkan')
                        if not all(candidate in failed for candidate in required):
                            raise AssertionError('Missing real Vulkan device-creation failure: ' + key)
                        frames = len(list(work.glob('*-frames-*/*.png')))
                        if (selected_hwdec(output) != 'no' or 'Exiting... (End of file)' not in output or
                                frames != smoke.VECTORS['vectors'][name]['expected_frames']):
                            raise AssertionError('Negative control did not complete software playback: ' + key)
                        results[key] = dict(result='REJECTED', backend=selected_hwdec(output),
                                            hwdec_candidates=hwdec_candidates(output),
                                            failed_devices=failed, mpv_output_frames=frames, reached_eof=True)
                    else:
                        if label == 'unpatched':
                            raise AssertionError('Unpatched runtime passed safe-auto policy: ' + key)
                        results[key] = state
    return results


def source_policy(source_dir):
    text = (source_dir / 'mpv/video/decode/vd_lavc.c').read_text()
    table = text.split('const struct autoprobe_info hwdec_autoprobe_info[] = {', 1)[1].split('};', 1)[0]
    entries = dict(re.findall(r'\{"([^"]+)",\s*([^}]+)\}', table))
    for name in ('vulkan', 'vulkan-copy'):
        if entries.get(name, '').strip() != 'HWDEC_FLAG_AUTO':
            raise ValueError('Vulkan explicit support/auto whitelist regression: ' + name)
    return [name for name, flags in entries.items() if 'HWDEC_FLAG_WHITELIST' in flags]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('runtime', type=pathlib.Path)
    parser.add_argument('--source-dir', type=pathlib.Path)
    parser.add_argument('--hardware-fixture', type=pathlib.Path)
    parser.add_argument('--unpatched-runtime', type=pathlib.Path,
                        help='Real mpv 0.41 negative control; run in a GPU-free builder')
    parser.add_argument('--log-dir', type=pathlib.Path, help='Retain real CPU-control logs')
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    if args.hardware_fixture and args.unpatched_runtime:
        parser.error('Run GPU-free negative controls separately from displayed hardware tests')
    if args.log_dir:
        args.log_dir.mkdir(parents=True, exist_ok=True)
    root = args.runtime.resolve(strict=True)
    records = declared()
    mpv = [r for r in records if r['component'] == 'mpv' and r['upstream_commit'] == COMMIT]
    if len(mpv) != 1 or mpv[0]['version'] != '0.41.0' or mpv[0]['upstream_commit'] != COMMIT:
        raise ValueError('Missing/wrong mpv safety backport')
    verify(root, args.source_dir)
    report = dict(result='PASS', upstream_commit=COMMIT, explicit_modes=[], hardware={},
                  parser_controls=parser_controls())
    if args.source_dir:
        report['source_whitelist'] = source_policy(args.source_dir)
        # A copied declaration alone must not authenticate unpatched source.
        with tempfile.TemporaryDirectory(prefix='nuvio-unapplied-patch-') as temporary:
            source = pathlib.Path(temporary)
            relative = 'video/decode/vd_lavc.c'
            original = (args.source_dir / 'mpv' / relative).read_text()
            for name in ('vulkan', 'vulkan-copy'):
                path = source / 'mpv' / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                reverted = re.sub(r'(\{"' + name + r'",\s*HWDEC_FLAG_AUTO)(\},)',
                                  r'\1 | HWDEC_FLAG_WHITELIST\2', original)
                if reverted == original:
                    raise ValueError('Negative source fixture did not change')
                path.write_text(reverted)
                try:
                    check_source(source, mpv[0], 'after')
                except ValueError:
                    pass
                else:
                    raise ValueError('Unapplied whitelist removal passed: ' + name)
        report['unapplied_patch_controls'] = 'REJECTED: each whitelist removal required'
    modes = query_features(root)['mpv_hwdec_modes']
    for name in ('vulkan', 'vulkan-copy'):
        if not any(x.startswith(name + ' ') for x in modes):
            raise ValueError('Explicit backend missing: ' + name)
        report['explicit_modes'].append(name)
    capabilities = root / 'share/nuvio-media-runtime/capabilities.json'
    validate_playback_report(json.loads(capabilities.read_text()))
    spec = importlib.util.spec_from_file_location('smoke', TOOLS / 'scripts/smoke-test.py')
    smoke = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(smoke)
    if not args.hardware_fixture:
        report['cpu_controls'] = cpu_controls(smoke, root,
            args.unpatched_runtime.resolve(strict=True) if args.unpatched_runtime else None, args.log_dir)
        report['unpatched_runtime_control'] = 'TESTED' if args.unpatched_runtime else 'NOT REQUESTED'
    if args.hardware_fixture:
        for mode in ('auto', 'auto-copy', 'vulkan', 'vulkan-copy'):
            output = command([root / 'bin/mpv', '--no-config', '--vo=gpu-next',
                '--gpu-api=vulkan', '--gpu-context=x11vk', '--ao=null', '--length=2',
                '--hwdec=' + mode, '--script=' + str(TOOLS / 'tests/playback-progress.lua'),
                '--msg-level=vd=debug', args.hardware_fixture.resolve()], timeout=30)
            state = smoke.progress(output, 1.0)
            backend = state['hwdec']
            candidates = hwdec_candidates(output)
            if selected_hwdec(output) != backend:
                raise ValueError('Hardware log/property mismatch')
            validate_hwdec_policy(mode, candidates, backend)
            if mode in ('vulkan', 'vulkan-copy') and backend != mode:
                raise ValueError('Explicit backend did not activate on diagnostic host: ' + mode)
            if 'VO: [gpu-next]' not in output:
                raise ValueError('Missing displayed video output')
            report['hardware'][mode] = dict(backend=backend, progress=state, hwdec_candidates=candidates)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
    print(json.dumps(report, indent=2, sort_keys=True))


if __name__ == '__main__':
    main()
