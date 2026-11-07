#!/usr/bin/env python3
"""Test-only receiver for Android liblog's logdw Unix datagrams.

Captures messages on a disposable Linux runner with no logd. Does not emulate
Android properties, change runtime/core responses, or run in an APK.
"""
import os
from pathlib import Path
import socket
import struct
import sys


def describe(packet):
    # Android 8 logd writer header: id (u8), tid (u16), sec/nsec (u32 each).
    if len(packet) < 12:
        return f'MALFORMED_LOG size={len(packet)} hex={packet.hex()}'
    log_id, tid, sec, nsec = struct.unpack_from('<BHII', packet)
    payload = packet[11:]
    priority = payload[0]
    parts = payload[1:].split(b'\0', 2)
    tag = parts[0].decode('utf-8', errors='replace') if parts else ''
    message = parts[1].decode('utf-8', errors='replace') if len(parts) > 1 else ''
    return f'LOG id={log_id} tid={tid} time={sec}.{nsec:09d} priority={priority} tag={tag!r} message={message!r}'


def main():
    if len(sys.argv) not in (2, 3):
        raise SystemExit('usage: capture_android_log.py /path/to/logdw [pid-file]')
    path = Path(sys.argv[1])
    if path.exists():
        raise SystemExit('refusing to replace an existing log socket')
    with socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM) as receiver:
        receiver.bind(str(path))
        os.chmod(path, 0o666)
        if len(sys.argv) == 3:
            Path(sys.argv[2]).write_text(str(os.getpid()), encoding='ascii')
        print('LOG_RECEIVER_READY', flush=True)
        while True:
            data = receiver.recv(65536)
            print(describe(data), flush=True)


if __name__ == '__main__':
    main()
