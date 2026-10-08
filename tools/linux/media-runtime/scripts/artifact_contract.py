#!/usr/bin/env python3
"""File integrity and the pinned runtime layout; no build-cache authentication.

The inventory detects damage/substitution, not coordinated malicious rewriting
of an artifact and its inventory. Build-time audit supplies independent config
evidence, and the manifest supplies the allowed ELF/link layout.
"""
import argparse
import hashlib
import json
import os
import pathlib
import re
import stat
from runtime_features import validate_playback_report
from source_patches import verify as verify_patches

TOOLS = pathlib.Path(__file__).resolve().parents[1]
POLICY = json.loads((TOOLS / 'manifest.json').read_text())
META = 'share/nuvio-media-runtime/'
INVENTORY = META + 'inventory.json'
VALIDATED = META + 'validated.json'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def entries(root):
    result = {}
    for path in sorted(root.rglob('*')):
        rel = path.relative_to(root).as_posix()
        if rel in (INVENTORY, VALIDATED):
            continue
        mode = path.lstat().st_mode
        if stat.S_ISLNK(mode):
            result[rel] = dict(kind='symlink', target=os.readlink(path))
        elif stat.S_ISREG(mode):
            result[rel] = dict(kind='file', mode=stat.S_IMODE(mode), sha256=digest(path))
        elif not stat.S_ISDIR(mode):
            raise ValueError('Unexpected special file: ' + rel)
    return result


def verify_inventory(root):
    inventory = root / INVENTORY
    if inventory.is_symlink() or not inventory.is_file():
        raise ValueError('Missing/non-regular inventory')
    stored = json.loads(inventory.read_text()).get('artifacts')
    actual = entries(root)
    if stored != actual:
        names = set(stored or {}) | set(actual)
        differences = sorted(n for n in names if (stored or {}).get(n) != actual.get(n))
        raise ValueError('Inventory kind/target/mode/hash mismatch: ' + ', '.join(differences))


def layout_errors(root):
    contract = POLICY['artifact_contract']
    links = contract['symlinks']
    regular = set(contract['executables']) | set(contract['libraries']) | set(contract['headers'])
    regular.add('lib/pkgconfig/mpv.pc')
    errors = []
    for rel in regular:
        p = root / rel
        if p.is_symlink() or not p.is_file():
            errors.append('Required regular artifact missing/substituted: ' + rel)
    for rel, target in links.items():
        p = root / rel
        if not p.is_symlink() or os.readlink(p) != target:
            errors.append('Required symlink target mismatch: ' + rel)
    for p in root.rglob('*'):
        rel = p.relative_to(root).as_posix()
        if p.is_symlink():
            if rel not in links or os.readlink(p) != links[rel]:
                errors.append('Unexpected/substituted symlink: ' + rel)
            if not p.exists() or os.path.isabs(os.readlink(p)) or not p.resolve().is_relative_to(root):
                errors.append('Escaping/absolute symlink: ' + rel)
        elif p.is_file():
            if rel not in regular and not rel.startswith(('share/licenses/', META, 'share/doc/')):
                errors.append('Unexpected runtime artifact: ' + rel)
            elf = p.read_bytes().startswith(b'\x7fELF')
            if elf and rel not in contract['executables'] and rel not in contract['libraries']:
                errors.append('Unexpected ELF artifact: ' + rel)
            if not elf and rel in set(contract['executables']) | set(contract['libraries']):
                errors.append('Required ELF substituted: ' + rel)
            if not elf and p.stat().st_mode & 0o111:
                errors.append('Unexpected executable file: ' + rel)
            if rel in contract['executables'] and not p.stat().st_mode & 0o111:
                errors.append('Non-executable program: ' + rel)
        elif not p.is_dir():
            errors.append('Unexpected special file: ' + rel)
    return errors


def definitions(text):
    return dict(re.findall(r'^#define (\w+) ([01])$', text, re.M))


def build_features(build):
    return {
        'ffmpeg': definitions((build / 'FFmpeg/config.h').read_text() + '\n' +
                              (build / 'FFmpeg/config_components.h').read_text()),
        'mpv': definitions((build / 'mpv/config.h').read_text()),
    }


def seal(root):
    path = root / INVENTORY
    data = json.loads(path.read_text())
    data['artifacts'] = entries(root)
    path.write_text(json.dumps(data, indent=2, sort_keys=True) + '\n')


def validate_marker(root):
    verify_inventory(root)
    verify_patches(root)
    errors = layout_errors(root)
    if errors:
        raise ValueError('; '.join(errors))
    manifest = json.loads((root / (META + 'manifest.json')).read_text())
    if manifest != POLICY:
        raise ValueError('Published manifest differs from policy')
    validate_playback_report(json.loads((root / (META + 'capabilities.json')).read_text()))
    if json.loads((root / (META + 'elf-audit.json')).read_text()).get('result') != 'PASS':
        raise ValueError('Unsuccessful validation: elf-audit.json')
    return dict(inventory_sha256=digest(root / INVENTORY), result='PASS')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('runtime', type=pathlib.Path)
    action = parser.add_mutually_exclusive_group()
    action.add_argument('--seal', action='store_true')
    action.add_argument('--mark-validated', action='store_true')
    action.add_argument('--validated', action='store_true')
    parser.add_argument('--recipe-hash')
    parser.add_argument('--builder-image-id')
    args = parser.parse_args()
    root = args.runtime.resolve(strict=True)
    if args.seal:
        seal(root)
    elif args.mark_validated:
        # Failed revalidation must not leave an earlier completion marker.
        (root / VALIDATED).unlink(missing_ok=True)
        (root / VALIDATED).write_text(json.dumps(validate_marker(root), sort_keys=True) + '\n')
    elif args.validated:
        if (root / VALIDATED).is_symlink() or json.loads((root / VALIDATED).read_text()) != validate_marker(root):
            raise ValueError('Invalid generation validation marker')
        build = json.loads((root / (META + 'build-info.json')).read_text())
        if args.recipe_hash and build['recipe_sha256'] != args.recipe_hash:
            raise ValueError('Generation belongs to another recipe')
        if args.builder_image_id and build['builder_image_id'] != args.builder_image_id:
            raise ValueError('Generation belongs to another builder')
    else:
        verify_inventory(root)
