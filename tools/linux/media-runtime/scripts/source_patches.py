#!/usr/bin/env python3
"""Apply declared upstream patches and preserve independently checked provenance."""
import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess

TOOLS = pathlib.Path(__file__).resolve().parents[1]
POLICY = json.loads((TOOLS / 'manifest.json').read_text())
META = 'share/nuvio-media-runtime'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def declared():
    records = []
    for source in POLICY['sources']:
        for patch in source['patches']:
            path = TOOLS / patch['file']
            if not path.resolve().is_relative_to(TOOLS / 'patches') or path.is_symlink():
                raise ValueError('Invalid source patch path: ' + patch['file'])
            if digest(path) != patch['sha256']:
                raise ValueError('Source patch hash mismatch: ' + patch['file'])
            if not patch['upstream_commit'] or not patch['upstream_url'] or not patch['reason']:
                raise ValueError('Missing upstream patch provenance')
            records.append(dict(component=source['name'], version=source['version'], **patch))
    return records


def check_source(source_dir, record, phase):
    component = source_dir / record['component']
    for name, hashes in record['source_files'].items():
        path = component / name
        if not path.resolve().is_relative_to(component.resolve()) or digest(path) != hashes[phase + '_sha256']:
            raise ValueError('Patched source mismatch: ' + record['component'] + '/' + name)


def apply(source_dir):
    for record in declared():
        check_source(source_dir, record, 'before')
        subprocess.run(['patch', '--batch', '--forward', '--fuzz=0', '-p1',
                        '-i', str(TOOLS / record['file'])],
                       cwd=source_dir / record['component'], check=True)
        check_source(source_dir, record, 'after')


def capture(root, source_dir):
    records = declared()
    metadata = root / META
    for record in records:
        check_source(source_dir, record, 'after')
        target = metadata / record['file']
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(TOOLS / record['file'], target)
    (metadata / 'applied-patches.json').write_text(json.dumps(records, indent=2, sort_keys=True) + '\n')


def verify(root, source_dir=None):
    records = declared()
    metadata = root / META
    if json.loads((metadata / 'applied-patches.json').read_text()) != records:
        raise ValueError('Applied patch provenance differs from canonical manifest')
    for record in records:
        if digest(metadata / record['file']) != record['sha256']:
            raise ValueError('Exported source patch hash mismatch: ' + record['file'])
        if source_dir is not None:
            check_source(source_dir, record, 'after')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source_dir', type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_dir.resolve(strict=True))
