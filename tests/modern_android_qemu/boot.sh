#!/usr/bin/env bash
# Exploratory full-system boot of an unmodified official Android image.
# This is not the production APK or a game. No synthetic success markers.
set -Eeuo pipefail
W=${1:?work directory required}
I="$W/image/arm64-v8a"
mkdir -p "$W/logs"
for tool in qemu-system-aarch64 adb sgdisk losetup mkfs.ext4; do
  if ! command -v "$tool" >/dev/null; then
    echo "MISSING_REQUIRED_TOOL $tool" | tee -a "$W/logs/environment.txt"
    exit 1
  fi
done
# Fresh, test-only blank disk, with a genuine GPT userdata partition. The
# official image's encryptionkey.img supplies the separate metadata partition.
truncate -s 3G "$W/userdata.img"
sgdisk --new=1:2048:0 --change-name=1:userdata "$W/userdata.img" > "$W/logs/disk-setup.txt"
LOOP=$(sudo losetup --find --show --partscan "$W/userdata.img")
cleanup() {
  if [ -n "${QEMU_PID:-}" ]; then kill "$QEMU_PID" 2>/dev/null || true; wait "$QEMU_PID" 2>/dev/null || true; fi
  sudo losetup -d "$LOOP" 2>/dev/null || true
}
trap cleanup EXIT
sudo mkfs.ext4 -q -F "${LOOP}p1"
sudo losetup -d "$LOOP"
LOOP=''
qemu-system-aarch64 --version | head -1 | tee "$W/logs/environment.txt"
printf 'IMAGE=official android-35 google_apis arm64-v8a revision 9\nMODE=full-system TCG; no APK, no game\n' | tee -a "$W/logs/environment.txt"
# Host port is loopback-only. A normal AVD typically uses a qemu pipe for ADB;
# plain QEMU does not implement it, so TCP may not connect. This experiment must
# report that limitation rather than faking Android boot or test results.
qemu-system-aarch64 -M virt -cpu max -m 3072 -smp 2 -nographic -no-reboot \
  -kernel "$W/kernel.raw" -initrd "$I/ramdisk.img" \
  -append 'console=ttyAMA0 androidboot.hardware=ranchu androidboot.qemu=1' \
  -drive "file=$I/system.img,format=raw,if=none,id=system,readonly=on" -device virtio-blk-device,drive=system \
  -drive "file=$I/vendor.img,format=raw,if=none,id=vendor,readonly=on" -device virtio-blk-device,drive=vendor \
  -drive "file=$I/encryptionkey.img,format=raw,if=none,id=metadata,snapshot=on" -device virtio-blk-device,drive=metadata \
  -drive "file=$W/userdata.img,format=raw,if=none,id=userdata" -device virtio-blk-device,drive=userdata \
  -device virtio-rng-device -device virtio-gpu-device \
  -netdev user,id=net0,hostfwd=tcp:127.0.0.1:5557-:5555 -device virtio-net-device,netdev=net0 \
  > "$W/logs/serial.txt" 2>&1 &
QEMU_PID=$!
ready=0
for ((i=0;i<120;i++)); do
  if ! kill -0 "$QEMU_PID" 2>/dev/null; then echo 'QEMU_EXITED_BEFORE_ADB' | tee -a "$W/logs/environment.txt"; break; fi
  timeout 4s adb connect 127.0.0.1:5557 >> "$W/logs/adb.txt" 2>&1 || true
  state=$(timeout 4s adb -s 127.0.0.1:5557 get-state 2>/dev/null || true)
  if [ "$state" = device ]; then
    boot=$(timeout 4s adb -s 127.0.0.1:5557 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
    if [ "$boot" = 1 ]; then ready=1; break; fi
  fi
  sleep 5
done
printf 'ANDROID_ADB_BOOT_COMPLETED=%s\n' "$ready" | tee -a "$W/logs/environment.txt"
if [ "$ready" = 1 ]; then
  adb -s 127.0.0.1:5557 shell getprop ro.build.version.sdk | tee -a "$W/logs/environment.txt"
  adb -s 127.0.0.1:5557 shell getprop ro.product.cpu.abilist | tee -a "$W/logs/environment.txt"
  adb -s 127.0.0.1:5557 logcat -d -t 10000 > "$W/logs/logcat.txt" || true
else
  echo 'Android ADB boot not established: inspect serial.txt; no core or game result.' | tee -a "$W/logs/environment.txt"
  tail -120 "$W/logs/serial.txt"
  exit 1
fi
