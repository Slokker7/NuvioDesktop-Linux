#!/usr/bin/env python3
"""Generate licenses and inventory from the exact verified build (builder only)."""
import glob, hashlib, json, pathlib, shutil, subprocess, sys
from artifact_contract import build_features, seal
root = pathlib.Path(sys.argv[1])
tools = pathlib.Path(__file__).resolve().parents[1]
manifest = json.loads((tools / 'manifest.json').read_text())
metadata = root / 'share/nuvio-media-runtime'
shutil.copyfile(tools / 'manifest.json', metadata / 'manifest.json')
for source in manifest['sources']:
    if source['classification'] != 'private-built' and not source.get('incorporated_data') and not source.get('incorporated_code'): continue
    destination = root / 'share/licenses' / source['name']
    destination.mkdir()
    matched = []
    for pattern in source['license_files']:
        matched.extend(glob.glob('/work/src/' + source['name'] + '/' + pattern))
    if not matched: raise RuntimeError('Missing license text: ' + source['name'])
    for item in sorted(set(matched)):
        src = pathlib.Path(item)
        if src.is_file():
            relative = src.relative_to('/work/src/' + source['name'])
            dst = destination / relative
            dst.parent.mkdir(parents=True, exist_ok=True)
            if src.suffix in ('.h', '.c'):
                # SDK inline loaders are compiled into private binaries. Preserve
                # their free-software notices, not entire SDK/driver payloads.
                text = src.read_text()
                if not text.startswith('/*') or '*/' not in text: raise RuntimeError('Missing header notice: ' + str(relative))
                dst.write_text(text[:text.index('*/') + 2] + '\n')
            else:
                shutil.copyfile(src, dst)
features = build_features(pathlib.Path('/work/build'))
# Retain the boolean build definitions without development paths. The build
# audit compares these and the JSON to the actual fresh configuration headers.
(metadata / 'config').mkdir()
for component, values in features.items():
    (metadata / 'config' / (component + '.h')).write_text(''.join(
        f'#define {key} {value}\n' for key, value in sorted(values.items())))
(metadata / 'compile-features.json').write_text(json.dumps(features, indent=2, sort_keys=True) + '\n')
builder_data = {}
for source in manifest['sources']:
    for file in source.get('incorporated_data', []):
        builder_data[source['name'] + '/' + file] = dict(sha256=hashlib.sha256(pathlib.Path('/work/src/' + source['name'] + '/' + file).read_bytes()).hexdigest(), provenance='byte-identical to snapshot package input and pinned upstream source')
inventory = dict(incorporated_builder_data=builder_data, sources=manifest['sources'], platform=manifest['host_runtime'], optional_drivers=manifest['optional_drivers'], artifacts={})
(metadata / 'inventory.json').write_text(json.dumps(inventory, indent=2, sort_keys=True) + '\n')
def version(*args):
    return subprocess.check_output(args, text=True).splitlines()[0]
build = dict(builder=manifest['builder'], builder_image_id=pathlib.Path('/work/builder-image-id').read_text().strip(), recipe_sha256=pathlib.Path('/work/recipe-hash').read_text().strip(), source_date_epoch=manifest['source_date_epoch'], compiler=version(manifest['builder']['cc'],'--version'), linker=version('ld','--version'), meson=version('python3','/work/src/meson/meson.py','--version'), ninja=version('ninja','--version'), make=version('make','--version'), pkg_config=version('pkg-config','--version'), cflags='-O2 -fPIC', cxxflags='-O2 -fPIC', ldflags='--enable-new-dtags; temporary private build RUNPATH removed at export', cppflags='private prefix include directory', library_path='private build prefix only', pkg_config_libdir='private prefix; platform multiarch and shared pkg-config directories', environment_overrides='inherited include/pkg-config and loader overrides cleared', path_remapping='fixed build root remapped to relative source paths', locale='C', timezone='UTC', install_prefix='/usr', final_runpath='ORIGIN-relative')
(metadata / 'build-info.json').write_text(json.dumps(build, indent=2, sort_keys=True) + '\n')
shutil.copyfile('/builder-packages.tsv', metadata / 'builder-packages.tsv')
seal(root)
