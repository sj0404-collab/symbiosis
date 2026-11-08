#!/usr/bin/env python3
"""Capture the real userdebug Android serial console and enable test ADB TCP.

Only the disposable image is configured, with the host port bound to loopback.
No Android service, TLS response, core, or boot-completion result is replaced.
"""
import socket
import sys
import time
from pathlib import Path

sock_path, output = sys.argv[1:]
deadline = time.monotonic() + 30
while True:
    try:
        conn = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        conn.connect(sock_path)
        break
    except OSError:
        conn.close()
        if time.monotonic() >= deadline:
            raise
        time.sleep(0.1)

# The image's ordinary userdebug shell supports su 0; commands only configure
# network/ADB on the disposable guest.  A command echo is not proof of success.
commands = (b"\nsu 0 setprop service.adb.tcp.port 5555; "
            b"su 0 ip link set eth0 up; "
            b"su 0 ip addr add 10.0.2.15/24 dev eth0; "
            b"su 0 ip rule add pref 1000 to 10.0.2.0/24 lookup main; "
            b"echo SERIAL_CONFIG_COMMANDS_RETURNED\n")
seen = b''
sent = False
conn.sendall(b'\n')
with Path(output).open('wb') as log:
    while True:
        chunk = conn.recv(65536)
        if not chunk:
            break
        log.write(chunk)
        log.flush()
        if not sent:
            seen = (seen + chunk)[-8192:]
            if b'console:/ $' in seen or b'console:/ #' in seen:
                conn.sendall(commands)
                sent = True
