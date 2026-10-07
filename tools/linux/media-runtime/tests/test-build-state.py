#!/usr/bin/env python3
"""Exercise the real host orchestrator with deterministic container fault injection.

This complements, not replaces, real clean builds and interruption tests. The
fake producer never compiles: it asserts a fresh workspace, then copies an already
validated runtime, fails, or stops halfway through export. No network is used.
"""
import argparse
import hashlib
import json
import os
import pathlib
import shutil
import signal
import subprocess
import tempfile
import time

TOOLS = pathlib.Path(__file__).resolve().parents[1]
FAKE = '''#!/usr/bin/env python3
import json,os,pathlib,shutil,sys,time
args=sys.argv[1:]; source=pathlib.Path(os.environ['TEST_RUNTIME'])
if args[:2]==['image','exists']:sys.exit(0)
if args[:2]==['image','inspect']:
 print(json.loads((source/'share/nuvio-media-runtime/build-info.json').read_text())['builder_image_id']);sys.exit(0)
if args[0]!='run':raise RuntimeError('Unexpected podman command')
mount=next(a for a in args if a.startswith('type=bind,src=') and a.endswith(',dst=/work'))
job=pathlib.Path(mount[len('type=bind,src='):-len(',dst=/work')])
for name in ('src','build','prefix','tools','stage','runtime'):
 assert not (job/name).exists(), 'Reused mutable build state: '+name
pathlib.Path(os.environ['TEST_JOB']).write_text(str(job))
mode=os.environ.get('TEST_MODE','ok')
if mode=='fail':sys.exit(17)
if mode=='interrupt':
 (job/'runtime/bin').mkdir(parents=True);shutil.copy2(source/'bin/mpv',job/'runtime/bin/mpv')
 pathlib.Path(os.environ['TEST_READY']).touch()
 time.sleep(60);sys.exit(19)
shutil.copytree(source,job/'runtime',symlinks=True)
if mode=='invalid':(job/'runtime/bin/mpv').unlink()
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('runtime', type=pathlib.Path)
    parser.add_argument('downloads', type=pathlib.Path)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    results = {}
    with tempfile.TemporaryDirectory(prefix='nuvio-build-state-') as temporary:
        tmp = pathlib.Path(temporary)
        tools = tmp / 'recipes'
        shutil.copytree(TOOLS, tools, ignore=shutil.ignore_patterns('__pycache__'))
        work = tmp / 'work';work.mkdir()
        shutil.copytree(args.downloads, work / 'downloads')
        fake = tmp / 'bin';fake.mkdir();(fake / 'podman').write_text(FAKE);(fake / 'podman').chmod(0o755)
        env = dict(os.environ, PATH=str(fake) + os.pathsep + os.environ['PATH'],
                   TEST_RUNTIME=str(args.runtime.resolve()), TEST_JOB=str(tmp/'job'), TEST_READY=str(tmp/'ready'))
        command = ['bash', str(tools/'build.sh'), '--work-dir', str(work), '--clean']

        def build(mode='ok'):
            p = subprocess.run(command, env=dict(env, TEST_MODE=mode), capture_output=True, text=True, timeout=30)
            return p
        first = build()
        if first.returncode:raise AssertionError(first.stdout + first.stderr)
        target = (work/'runtime').resolve()
        initial = (target/'share/nuvio-media-runtime/inventory.json').read_bytes()
        for name, relative in [('libmpv','prefix/lib/libmpv.so'), ('dependency','prefix/lib/libass.so'), ('source','src/mpv/player.c')]:
            previous = pathlib.Path((tmp/'job').read_text());path=previous/relative;path.parent.mkdir(parents=True,exist_ok=True);path.write_text('mutated cached state')
            p=build()
            if p.returncode:raise AssertionError(p.stdout+p.stderr)
            current=(work/'runtime').resolve()
            assert current.parent != previous
            assert (current/'share/nuvio-media-runtime/inventory.json').read_bytes()==initial
            results['cache_'+name]='PASS: fresh attempt, old state not consumed'
        target=(work/'runtime').resolve()
        for mode in ('fail','invalid'):
            assert build(mode).returncode != 0
            assert (work/'runtime').resolve()==target
            results[mode+'_publication']='PASS: previous generation preserved'
        p=subprocess.Popen(command,env=dict(env,TEST_MODE='interrupt'),stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,start_new_session=True)
        try:
            deadline=time.monotonic()+20
            while not (tmp/'ready').exists() and time.monotonic()<deadline:
                if p.poll() is not None:raise AssertionError('Producer exited before export')
                time.sleep(.02)
            assert (tmp/'ready').exists()
            os.killpg(p.pid,signal.SIGTERM);p.wait(timeout=5)
        finally:
            if p.poll() is None:os.killpg(p.pid,signal.SIGKILL);p.wait()
        assert (work/'runtime').resolve()==target
        for name in ('bin/mpv','share/nuvio-media-runtime/inventory.json','share/nuvio-media-runtime/elf-audit.json'):
            assert (work/'runtime'/name).is_file()
        results['interrupted_export']='PASS: previous generation preserved'
        assert build().returncode==0
        results['recovery']='PASS'
        target=(work/'runtime').resolve()
        manifest=tools/'manifest.json';original=manifest.read_text()
        for change in ('option','version','hash'):
            data=json.loads(original);mpv=next(x for x in data['sources'] if x['name']=='mpv')
            if change=='option':mpv['options'].append('-Dtests=true')
            elif change=='version':mpv['version']='0.41.0-review'
            else:mpv['sha256']='0'*64;mpv['url']='https://127.0.0.1:1/invalid-source'
            manifest.write_text(json.dumps(data,indent=2)+'\n')
            # The fake producer offers the old runtime; the actual publication
            # contract must reject it under the new recipe/manifest identity.
            assert build().returncode!=0
            assert (work/'runtime').resolve()==target
            results['manifest_'+change]='REJECTED: old generation cannot become canonical'
            manifest.write_text(original)
    args.output.write_text(json.dumps(results,indent=2,sort_keys=True)+'\n')
    print(json.dumps(results,indent=2,sort_keys=True))


if __name__ == '__main__':main()
