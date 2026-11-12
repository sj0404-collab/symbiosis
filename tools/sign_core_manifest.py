#!/usr/bin/env python3
"""Create a signed TEST-channel core manifest; no publishing or key generation.

The private key must be provided by the operator, outside the repository.
A signed manifest does not imply ABI or game compatibility.
"""
import argparse
import base64
import hashlib
from pathlib import Path
import subprocess
import sys

MIN_BYTES = 1_000_000
MAX_BYTES = 256 * 1024 * 1024
MAX_SEQUENCE = 2**63 - 1


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--sequence', type=int, required=True)
    parser.add_argument('--core', type=Path, required=True)
    parser.add_argument('--private-key', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not 0 < args.sequence <= MAX_SEQUENCE:
        parser.error('sequence must be a positive signed 64-bit integer')
    if not args.core.is_file() or not args.private_key.is_file():
        parser.error('core and private key must be existing files')
    if args.output.resolve() in (args.core.resolve(), args.private_key.resolve()):
        parser.error('output must not overwrite the core or signing key')
    digest = hashlib.sha256()
    size = 0
    with args.core.open('rb') as f:
        while chunk := f.read(1024 * 1024):
            size += len(chunk)
            if size > MAX_BYTES:
                parser.error('core is too large')
            digest.update(chunk)
    if size < MIN_BYTES:
        parser.error('core is too small')
    payload = (f'SYMBIOSIS-KENJI-1\nsequence={args.sequence}\n'
               f'bytes={size}\nsha256={digest.hexdigest()}\n').encode('ascii')
    signature = subprocess.run(
        ['openssl', 'dgst', '-sha256', '-sign', str(args.private_key)],
        input=payload, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True,
    ).stdout
    if len(signature) not in range(256, 513):
        raise ValueError('expected an RSA signature from a 2048–4096-bit key')
    output = payload + b'signature=' + base64.b64encode(signature) + b'\n'
    args.output.write_bytes(output)
    print(f'manifest: sequence={args.sequence}, bytes={size}, sha256={digest.hexdigest()}')
    print('signed metadata only; upload and compatibility validation are separate steps')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError) as e:
        print('manifest not created:', type(e).__name__, file=sys.stderr)
        sys.exit(1)
