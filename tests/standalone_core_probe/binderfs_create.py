#!/usr/bin/env python3
"""Allocate a real binderfs device via the Linux binder-control ioctl.

Used only on a disposable CI VM when binderfs is genuinely supported and
mounted. Does not emulate Binder, service manager, Android services or JNI.
"""
import argparse
import fcntl
import os
from pathlib import Path
import re
import stat
import struct
import sys

# Linux uapi: _IOWR('b', 1, struct binderfs_device), char name[256], u32 major/minor.
BINDER_CTL_ADD = 0xC1086201
DEVICE_STRUCT = struct.Struct('=256sII')


def allocate(control, name):
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,255}', name):
        raise ValueError('invalid binder device name')
    encoded = name.encode('ascii')
    buf = bytearray(DEVICE_STRUCT.pack(encoded, 0, 0))
    try:
        with open(control, 'rb', buffering=0) as fd:
            fcntl.ioctl(fd.fileno(), BINDER_CTL_ADD, buf, True)
    except FileExistsError:
        # A fresh binderfs mount can already contain the kernel's default
        # binder device. Reuse it only if the node itself is a char device,
        # not a symlink or ordinary file. Caller must ensure this is the
        # disposable, freshly mounted binderfs.
        node = os.stat(Path(control).parent / name, follow_symlinks=False)
        if not stat.S_ISCHR(node.st_mode) or os.major(node.st_rdev) == 0:
            raise ValueError('existing binder node is not a character device')
        return os.major(node.st_rdev), os.minor(node.st_rdev)
    actual, major, minor = DEVICE_STRUCT.unpack(buf)
    if actual.split(b'\0', 1)[0] != encoded or major == 0:
        raise ValueError('invalid binder ioctl result')
    return major, minor


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mountpoint', type=Path)
    args = parser.parse_args()
    try:
        major, minor = allocate(args.mountpoint / 'binder-control', 'binder')
        print(f'BINDERFS_READY binder major={major} minor={minor}')
        return 0
    except (OSError, ValueError) as exc:
        print(f'BINDERFS_CREATE_FAIL {exc}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
