#!/usr/bin/env python3
"""Read-only audit of trusted, project-built ELF files; fail closed on unknown deps."""
import argparse, fnmatch, hashlib, json, os, pathlib, re, subprocess, sys
from artifact_contract import build_features, definitions, layout_errors, verify_inventory

TOOLS = pathlib.Path(__file__).resolve().parents[1]
POLICY = json.loads((TOOLS / 'manifest.json').read_text())
FORBIDDEN = (b'linuxbrew', b'homebrew', b'cellar', b'/home/', b'/var/home/', b'/tmp/', b'/work/', b'/recipes/')

def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT, env={k:v for k,v in os.environ.items() if k not in ('LD_LIBRARY_PATH','LD_PRELOAD')})

def version(value):
    return tuple(map(int, value.split('.')))

def audit(root, build_dir=None):
    root = root.resolve(strict=True)
    errors, records, sonames = [], [], {}
    embedded_manifest = root / 'share/nuvio-media-runtime/manifest.json'
    if not embedded_manifest.is_file() or json.loads(embedded_manifest.read_text()) != POLICY:
        errors.append('Artifact manifest differs from current canonical policy')
    inventory_path = root / 'share/nuvio-media-runtime/inventory.json'
    inventory = json.loads(inventory_path.read_text()) if inventory_path.is_file() else {}
    if not inventory: errors.append('Missing artifact inventory')
    errors.extend(layout_errors(root))
    try: verify_inventory(root)
    except (ValueError, OSError) as exc: errors.append(str(exc))
    metadata = root / 'share/nuvio-media-runtime'
    features = json.loads((metadata / 'compile-features.json').read_text())
    captured = {name: definitions((metadata / 'config' / (name + '.h')).read_text()) for name in ('ffmpeg', 'mpv')}
    if features != captured: errors.append('Compile-feature metadata differs from captured configuration')
    if build_dir is not None and features != build_features(build_dir):
        errors.append('Compile-feature metadata differs from actual build configuration')
    if inventory.get('sources') != POLICY['sources'] or inventory.get('platform') != POLICY['host_runtime'] or inventory.get('optional_drivers') != POLICY['optional_drivers']:
        errors.append('Inventory source/dependency policy differs from canonical manifest')
    for required in POLICY['required_runtime_files']:
        if not (root / required).is_file(): errors.append('Missing ' + required)
    for path in sorted(root.rglob('*')):
        rel = path.relative_to(root).as_posix()
        if path.is_symlink():
            if not path.exists() or os.path.isabs(os.readlink(path)) or not path.resolve().is_relative_to(root):
                errors.append('Escaping/absolute symlink: ' + rel)
            continue
        if not path.is_file(): continue
        data = path.read_bytes()
        # Reports describe test environments, not ELF loading. Never place raw
        # resolved host/build paths in them; report generation normalizes paths.
        scan_data = data
        portable_templates = 0
        if data.startswith(b'\x7fELF'):
            for source in POLICY['sources']:
                for template in source.get('portable_temp_templates', []):
                    if any(fnmatch.fnmatch(path.name, pattern) for pattern in template['files']):
                        literal = (template['directory'] + '/' + template['name']).encode() + b'\0'
                        portable_templates += scan_data.count(literal)
                        scan_data = scan_data.replace(literal, b'PORTABLE_TEMP_FILENAME\0')
        for token in FORBIDDEN:
            if token in scan_data.lower(): errors.append(f'Forbidden path/token in {rel}: {token.decode()}')
        if path.suffix in ('.la', '.cmake', '.a') or path.name in ('meson-info', 'meson-private'):
            errors.append('Unexpected development/build artifact: ' + rel)
        if not data.startswith(b'\x7fELF'): continue
        header = run('readelf', '-h', str(path))
        if 'Advanced Micro Devices X86-64' not in header: errors.append('Wrong architecture: ' + rel)
        if not re.search(r'Class:\s+ELF64', header): errors.append('Wrong ELF class: ' + rel)
        if not re.search(r'Type:\s+DYN', header): errors.append('Unexpected ELF kind: ' + rel)
        dynamic = run('readelf', '-d', str(path))
        needed = re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic)
        soname = re.findall(r'\(SONAME\).*?\[(.*?)\]', dynamic)
        contract = POLICY['artifact_contract']
        if rel in contract['libraries'] and soname != [contract['libraries'][rel]['soname']]:
            errors.append('Unexpected library SONAME: ' + rel)
        if rel in contract['executables'] and soname:
            errors.append('Executable substituted with library: ' + rel)
        rpaths = re.findall(r'\((?:RUNPATH|RPATH)\).*?\[(.*?)\]', dynamic)
        expected = '$ORIGIN/../lib' if rel.startswith('bin/') else '$ORIGIN'
        if rpaths != [expected] or '(RPATH)' in dynamic:
            errors.append(f'Invalid RUNPATH for {rel}: {rpaths}')
        for dep in needed:
            if '/' in dep or re.search(r'lib(cuda|nvcuvid|nvidia|cudart|npp)', dep):
                errors.append(f'Mandatory driver/absolute dependency: {rel}: {dep}')
        if rel.startswith('lib/') and (not soname or re.search(r'lib(c\.so|m\.so|pthread|dl\.so|rt\.so|stdc\+\+|gcc_s|cuda|nvcuvid|nvidia)', soname[0])):
            errors.append('Forbidden/unversioned bundled library: ' + rel)
        if soname:
            if soname[0] in sonames: errors.append('Duplicate SONAME: ' + soname[0])
            sonames[soname[0]] = rel
        owner = contract['libraries'].get(rel, {}).get('component', contract['executables'].get(rel))
        owners = [owner] if owner else []
        if rel.startswith('lib/') and len(owners) != 1: errors.append('Unknown/ambiguous private artifact owner: ' + rel)
        info = run('readelf', '--version-info', str(path))
        unknown_glibc = set(re.findall(r'\bGLIBC_([A-Za-z][A-Za-z_0-9]*)', info))
        if unknown_glibc: errors.append(f'{rel}: unsupported GLIBC version tags: {sorted(unknown_glibc)}')
        glibc = sorted(set(re.findall(r'\bGLIBC_(\d+(?:\.\d+)+)', info)), key=version)
        glibcxx = sorted(set(re.findall(r'\bGLIBCXX_(\d+(?:\.\d+)+)', info)), key=version)
        for versions, ceiling, label in ((glibc,POLICY['baseline']['max_glibc'],'GLIBC'), (glibcxx,POLICY['baseline']['max_glibcxx'],'GLIBCXX')):
            if versions and version(versions[-1]) > version(ceiling): errors.append(f'{rel}: {label}_{versions[-1]} exceeds {ceiling}')
        records.append(dict(file=rel, portable_temp_template_occurrences=portable_templates, sha256=hashlib.sha256(data).hexdigest(), component=owner, soname=soname[0] if soname else None, needed=needed, runpath=rpaths, max_glibc=glibc[-1] if glibc else None, max_glibcxx=glibcxx[-1] if glibcxx else None))
    allowed = set(POLICY['host_runtime']['allowed_needed'])
    for rel in inventory.get('artifacts', {}):
        if not (root / rel).is_file(): errors.append('Inventoried artifact missing: ' + rel)
    # Do not execute ldd or artifact programs after structural/integrity failure.
    structurally_valid = not errors
    for record in records if structurally_valid else []:
        classifications = {}
        for dep in record['needed']:
            classifications[dep] = 'private-built' if dep in sonames else 'host-runtime' if dep in allowed else 'UNKNOWN'
            if classifications[dep] == 'UNKNOWN': errors.append(f"Unclassified dependency {record['file']}: {dep}")
        record['dependency_classification'] = classifications
        try: resolved = run('ldd', str(root / record['file']))
        except subprocess.CalledProcessError as exc:
            errors.append(f"ldd failed: {record['file']}: {exc.returncode}"); continue
        if 'not found' in resolved: errors.append('Unresolved dependency: ' + record['file'])
        # Audit the complete loader closure, including transitive platform libs.
        for token in (b'linuxbrew', b'homebrew', b'cellar'):
            if token.decode() in resolved.lower(): errors.append('Contaminated loader closure: ' + record['file'])
        closure = {}
        for dep, location in re.findall(r'^\s*(\S+) => (.*?) \(0x[0-9a-f]+\)\s*$', resolved, re.M):
            if location == 'not': continue
            p = pathlib.Path(location).resolve()
            if dep in sonames and p != (root / sonames[dep]).resolve(): errors.append('Private library resolved outside runtime: ' + dep)
            closure[dep] = ('runtime/' + p.relative_to(root).as_posix()) if p.is_relative_to(root) else ('platform/' + p.name)
        record['resolved_closure'] = closure
    if structurally_valid:
        from runtime_features import query_features, check_features, validate_playback_report
        try:
            capabilities = json.loads((metadata / 'capabilities.json').read_text())
            validate_playback_report(capabilities)
            actual = query_features(root)
            check_features(features, actual)
            if capabilities.get('compiled') != actual or capabilities.get('config') != features:
                errors.append('Capability metadata disagrees with binary/config evidence')
            if capabilities.get('hardware'):
                errors.append('Invalid default CPU capability report')
            if capabilities.get('validated') != {name: 'NOT YET HARDWARE VALIDATED' for name in ('NVIDIA', 'AMD', 'Intel')}:
                errors.append('Default CPU report claims unsupported hardware validation')
        except (ValueError, RuntimeError, subprocess.SubprocessError) as exc:
            errors.append('Capability verification failed: ' + str(exc))
    report = dict(result='PASS' if not errors else 'FAIL', architecture=POLICY['architecture'], baseline=POLICY['baseline'], elf=records, errors=sorted(set(errors)))
    print(json.dumps(report, indent=2, sort_keys=True))
    return not errors

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('runtime', type=pathlib.Path)
    parser.add_argument('--build-dir', type=pathlib.Path, help='Independent fresh build config, required during generation')
    args = parser.parse_args()
    sys.exit(0 if audit(args.runtime, args.build_dir) else 1)
