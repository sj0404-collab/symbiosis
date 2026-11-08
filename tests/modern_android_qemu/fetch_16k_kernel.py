#!/usr/bin/env python3
"""Fetch only a fingerprinted official Android 15 16K kernel from its SDK zip.

Only this kernel is checked, not the digest of the entire 1.78 GB archive. The
ZIP library verifies its entry CRC, then we verify the pinned SHA-256 below.
"""
import hashlib
import io
import sys
from urllib.request import Request, urlopen
import zipfile

URL = 'https://dl.google.com/android/repository/sys-img/google_apis/arm64-v8a-ps16k-35_r05.zip'
SIZE = 1778788352
SHA256 = 'e8ac73e75665e94ae692c782cdde9a5f98f905d47eee4d9fde48892510232a58'


class RemoteZip(io.RawIOBase):
    def __init__(self):
        self.pos = 0

    def readable(self):
        return True

    def seekable(self):
        return True

    def tell(self):
        return self.pos

    def seek(self, offset, whence=0):
        self.pos = offset if whence == 0 else self.pos + offset if whence == 1 else SIZE + offset
        if self.pos < 0:
            raise ValueError('negative seek')
        return self.pos

    def read(self, length=-1):
        if self.pos >= SIZE:
            return b''
        length = SIZE - self.pos if length < 0 else min(length, SIZE - self.pos)
        if length == 0:
            return b''
        start, end = self.pos, self.pos + length - 1
        request = Request(URL, headers={'Range': f'bytes={start}-{end}', 'Accept-Encoding': 'identity'})
        with urlopen(request, timeout=60) as response:
            if response.status != 206 or response.headers.get('Content-Range') != f'bytes {start}-{end}/{SIZE}':
                raise RuntimeError('server returned an unexpected range')
            data = response.read(length + 1)
        if len(data) != length:
            raise RuntimeError('truncated or oversized response')
        self.pos += length
        return data


def main():
    with zipfile.ZipFile(RemoteZip()) as archive:
        kernel = archive.read('arm64-v8a/kernel-ranchu')
    actual = hashlib.sha256(kernel).hexdigest()
    if actual != SHA256:
        raise SystemExit('16K_KERNEL_SHA256_MISMATCH ' + actual)
    with open(sys.argv[1], 'wb') as output:
        output.write(kernel)
    print('16K_KERNEL_SHA256_OK', actual)


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('usage: fetch_16k_kernel.py output-file')
    main()
