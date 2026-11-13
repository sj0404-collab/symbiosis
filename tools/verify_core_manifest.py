#!/usr/bin/env python3
"""Verify the test-channel manifest with an explicit public DER key."""
import argparse
import base64
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--manifest', type=Path, required=True)
    p.add_argument('--public-key-der', type=Path, required=True)
    args = p.parse_args()
    raw = args.manifest.read_bytes()
    if not 0 < len(raw) <= 2048 or not raw.endswith(b'\n'):
        raise ValueError('invalid size or line ending')
    lines = raw.decode('ascii').splitlines(keepends=True)
    if len(lines) != 5 or any(not x.endswith('\n') or '\r' in x for x in lines):
        raise ValueError('invalid lines')
    if lines[0] != 'SYMBIOSIS-KENJI-1\n': raise ValueError('invalid format')
    sequence = re.fullmatch(r'sequence=([1-9][0-9]{0,18})\n', lines[1])
    size = re.fullmatch(r'bytes=([1-9][0-9]{0,18})\n', lines[2])
    sha = re.fullmatch(r'sha256=([0-9a-f]{64})\n', lines[3])
    sig = re.fullmatch(r'signature=([A-Za-z0-9+/]+={0,2})\n', lines[4])
    if not all((sequence, size, sha, sig)): raise ValueError('invalid fields')
    n, b = int(sequence.group(1)), int(size.group(1))
    if n > 2**63-1 or not 1_000_000 <= b <= 256*1024*1024: raise ValueError('out of range')
    signature = base64.b64decode(sig.group(1), validate=True)
    if base64.b64encode(signature).decode() != sig.group(1): raise ValueError('noncanonical base64')
    with tempfile.TemporaryDirectory() as temp:
        tmp = Path(temp)
        pub, payload, sign = (tmp / n for n in ('pub.pem', 'payload', 'signature'))
        subprocess.run(['openssl', 'pkey', '-pubin', '-inform', 'DER', '-in', str(args.public_key_der), '-pubout', '-out', str(pub)], check=True, capture_output=True)
        payload.write_bytes(''.join(lines[:4]).encode('ascii')); sign.write_bytes(signature)
        subprocess.run(['openssl', 'dgst', '-sha256', '-verify', str(pub), '-signature', str(sign), str(payload)], check=True, capture_output=True)
    print(json.dumps({'sequence': n, 'bytes': b, 'sha256': sha.group(1)}))

if __name__ == '__main__':
    try: main()
    except (OSError, ValueError, subprocess.CalledProcessError):
        print('manifest verification failed', file=sys.stderr); sys.exit(1)
