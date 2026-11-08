#!/usr/bin/env bash
# A kernel-only experiment. This is not Android userspace, an APK, or a game.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
python3 "$ROOT/tests/modern_android_qemu/fetch_16k_kernel.py" "$W/kernel.gz"
gzip -dc "$W/kernel.gz" > "$W/kernel"
mkdir "$W/init"
aarch64-linux-gnu-gcc -static -O2 -Wall -Wextra -Werror \
  "$ROOT/tests/modern_android_qemu/kernel16_init.c" -o "$W/init/init"
(cd "$W/init" && find . -print0 | cpio --null -o --format=newc 2>/dev/null | gzip -9 > "$W/init.gz")
timeout 60s qemu-system-aarch64 -M virt -cpu max -m 1024 -smp 2 -nographic -nic none -no-reboot \
  -kernel "$W/kernel" -initrd "$W/init.gz" -append 'console=ttyAMA0 rdinit=/init' > "$W/kernel.log" 2>&1
sed -n '/Linux version/p;/REAL_16K_PROBE/p;/reboot: Power down/p' "$W/kernel.log"
grep -Eq 'REAL_16K_PROBE AT_PAGESZ=16384 SYSCONF=16384 HWCAP=0x[0-9a-f]+ REQUIRED=0x1182 MISSING=0x0' "$W/kernel.log"
