#!/usr/bin/env python3
"""Isolated session-bus contract/lifecycle tests. Usage: python3 this.py /path/to/probe.
Requires PyGObject and dbus-daemon; never connects to or changes the real desktop bus.
"""
import os
import socket
import subprocess
import sys
import tempfile
import threading
import time
from gi.repository import Gio, GLib

for key in ('DISPLAY', 'WAYLAND_DISPLAY', 'NUVIO_RUN_LIVE_DISPLAY_TESTS'):
    os.environ.pop(key, None)

# Stall authentication on a private socket. Exercise both the production idle
# open/query/close path (two opens) and the helper used by playback inhibition.
for args, expected, connections in [([], ['-1'] * 4, 2), (['--connection'], ['unavailable'], 1)]:
    with tempfile.TemporaryDirectory(prefix='nuvio-idle-auth-') as directory:
        address = directory + '/bus'
        server = socket.socket(socket.AF_UNIX)
        server.bind(address)
        server.listen()
        server.settimeout(0.1)
        stop = threading.Event()
        accepted = []
        def stall():
            while not stop.is_set():
                try:
                    connection, _ = server.accept()
                    accepted.append(connection)  # Never reply; close only after the client returns.
                except socket.timeout:
                    pass
        stalled = threading.Thread(target=stall)
        stalled.start()
        try:
            start = time.monotonic()
            result = subprocess.run([sys.argv[1], *args], text=True, capture_output=True, check=True,
                timeout=connections * 1.5 + 2,
                env=dict(os.environ, DBUS_SESSION_BUS_ADDRESS='unix:path=' + address, XDG_SESSION_TYPE='wayland'))
            elapsed = time.monotonic() - start
            assert result.stdout.split() == expected, result
            assert len(accepted) == connections, len(accepted)
            assert connections * 1.3 <= elapsed < connections * 1.5 + 2, elapsed
            print(f'PASS authentication timeout {args or "idle lifecycle"}: {elapsed:.2f}s', flush=True)
        finally:
            stop.set()
            stalled.join(timeout=5)
            for connection in accepted:
                connection.close()
            server.close()

SAVER = 'org.freedesktop.ScreenSaver'
MUTTER = 'org.gnome.Mutter.IdleMonitor'
XML = '''<node>
<interface name="org.freedesktop.ScreenSaver"><method name="GetSessionIdleTime"><arg type="u" direction="out"/></method></interface>
<interface name="org.gnome.Mutter.IdleMonitor"><method name="GetIdletime"><arg type="t" direction="out"/></method></interface>
</node>'''
info = Gio.DBusNodeInfo.new_for_xml(XML)
loop = GLib.MainLoop()
thread = threading.Thread(target=loop.run, daemon=True)
thread.start()
try:
    for mode in ['standard', 'legacy', 'mutter', 'missing', 'denied', 'timeout', 'lost', 'overflow', 'busloss']:
        daemon = subprocess.Popen(['dbus-daemon', '--session', '--nofork', '--print-address=1'], stdout=subprocess.PIPE, text=True)
        address = daemon.stdout.readline().strip()
        bus = Gio.DBusConnection.new_for_address_sync(address,
            Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION, None, None)
        bus.set_exit_on_close(False)
        calls = []
        def method(connection, sender, path, interface, name, args, invocation):
            calls.append((interface, path, name))
            if mode == 'timeout':
                return  # Client timeout is bounded; no reply.
            if mode == 'lost' and len(calls) > 1:
                invocation.return_dbus_error('org.freedesktop.DBus.Error.Failed', 'service lost')
            elif mode == 'denied':
                invocation.return_dbus_error('org.freedesktop.DBus.Error.AccessDenied', 'denied')
            elif interface == SAVER and (mode in ('standard', 'lost', 'busloss') or (mode == 'legacy' and path == '/ScreenSaver')):
                invocation.return_value(GLib.Variant('(u)', (42,)))
            elif interface == MUTTER and mode in ('mutter', 'overflow'):
                invocation.return_value(GLib.Variant('(t)', (123456 if mode == 'mutter' else 2**64 - 1,)))
            else:
                invocation.return_dbus_error('org.freedesktop.DBus.Error.NotSupported', 'not implemented')
        try:
            if mode != 'missing':
                for name in [SAVER, MUTTER]:
                    bus.call_sync('org.freedesktop.DBus', '/org/freedesktop/DBus', 'org.freedesktop.DBus',
                        'RequestName', GLib.Variant('(su)', (name, 0)), None, Gio.DBusCallFlags.NONE, 1000, None)
                for path, interface in [('/org/freedesktop/ScreenSaver', info.interfaces[0]),
                    ('/ScreenSaver', info.interfaces[0]), ('/org/gnome/Mutter/IdleMonitor/Core', info.interfaces[1])]:
                    bus.register_object(path, interface, method, None, None)
            env = dict(os.environ, DBUS_SESSION_BUS_ADDRESS=address, XDG_SESSION_TYPE='wayland')
            if mode == 'standard':
                connected = subprocess.run([sys.argv[1], '--connection'], env=env, text=True,
                    capture_output=True, timeout=5, check=True)
                assert connected.stdout.strip() == 'connected', connected
            if mode == 'busloss':
                process = subprocess.Popen([sys.argv[1], '--interactive'], env=env, text=True,
                    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                first = process.stdout.readline()
                daemon.terminate()
                daemon.wait(timeout=5)
                stdout, stderr = process.communicate('\n\n', timeout=5)
                assert process.returncode == 0, stderr
                values = list(map(int, (first + stdout).split()))
            else:
                result = subprocess.run([sys.argv[1]], env=env, text=True, capture_output=True, timeout=12, check=True)
                values = list(map(int, result.stdout.split()))
            expected = {'standard': [42000]*4, 'legacy': [42000]*4, 'mutter': [123456]*4,
                'lost': [42000, -1, -1, -1], 'busloss': [42000, -1, -1, -1]}.get(mode, [-1]*4)
            assert values == expected, (mode, values)
            assert all(call[2] in ('GetSessionIdleTime', 'GetIdletime') for call in calls)
            print(f'PASS {mode}: {values}', flush=True)
        finally:
            if not bus.is_closed():
                bus.close_sync(None)
            daemon.terminate()
            daemon.wait(timeout=5)
finally:
    loop.quit()
    thread.join(timeout=5)
