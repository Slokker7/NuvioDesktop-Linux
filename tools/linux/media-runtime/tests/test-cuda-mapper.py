#!/usr/bin/env python3
"""Run actual mpv mapper creation with test-only CUDA/format stubs, without a GPU.

Run inside the builder after mpv compilation. Reuse its exact headers/compiler
flags and allocator objects; section GC discards unrelated device/renderer code.
--expect-broken is a negative control against a pre-backport build, not a skip.
"""
import argparse
import hashlib
import json
import pathlib
import shlex
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('build_dir', type=pathlib.Path)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    parser.add_argument('--expect-broken', action='store_true')
    args = parser.parse_args()
    build = args.build_dir.resolve(strict=True)
    commands = json.loads((build / 'compile_commands.json').read_text())
    entry = next(x for x in commands if x['file'].endswith('/hwdec_cuda.c'))
    flags = shlex.split(entry['command'])
    command = []
    index = 0
    while index < len(flags):
        if flags[index] in ('-o', '-c', '-MQ', '-MF'):
            index += 2
            continue
        if flags[index] != '-MD':
            command.append(flags[index])
        index += 1
    objects = sorted((build / 'libmpv.so.2.5.0.p').glob('ta_*.o'))
    if len(objects) != 3:
        raise ValueError('Expected the three actual mpv allocator objects')
    harness = pathlib.Path(__file__).with_name('cuda-mapper-failure.c')
    with tempfile.TemporaryDirectory(prefix='nuvio-cuda-mapper-') as temporary:
        binary = pathlib.Path(temporary) / 'test'
        subprocess.run(command + ['-ffunction-sections', '-fdata-sections',
            '-Wl,--gc-sections', str(harness), *map(str, objects), '-o', str(binary)],
            cwd=entry['directory'], check=True, timeout=60)
        result = subprocess.run([str(binary)], capture_output=True, text=True, timeout=10)
    cases = [json.loads(line) for line in result.stdout.splitlines()]
    if len(cases) != 4 or [c['failed_plane'] for c in cases] != [-1, 0, 1, 2]:
        raise AssertionError('Missing native cases: ' + result.stdout + result.stderr)
    for case in cases:
        plane = case['failed_plane']
        expected_rejection = plane >= 0 and not args.expect_broken
        if (case['mapper_rejected'] != expected_rejection or not case['cleanup_ok'] or
                case['init_calls'] != (3 if plane < 0 else plane + 1)):
            raise AssertionError('Unexpected native behavior: ' + repr(case))
    if result.returncode != (1 if args.expect_broken else 0):
        raise AssertionError('Unexpected native exit: ' + str(result.returncode))
    source = (pathlib.Path(entry['directory']) / entry['file']).resolve()
    obj = build / entry['output']
    report = dict(result='PASS', negative_control=args.expect_broken, cases=cases,
                  source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
                  compiled_object_sha256=hashlib.sha256(obj.read_bytes()).hexdigest())
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
    print(json.dumps(report, indent=2, sort_keys=True))


if __name__ == '__main__':
    main()
