#!/usr/bin/env bash
# Diagnostic-only Android container on native ARM Linux; this is NOT the
# official Android Emulator or a physical phone. No game, keys or release APK.
set -Eeuo pipefail
: "${PROBE_WORK:?}" "${ANDROID_SERIAL:?}"
W=$PROBE_WORK
B=/tmp/redroid-binderfs
# Published 64-bit-only Android 15 ARM64 image: the ARM host kernel cannot
# execute the 32-bit service from the multi-ABI variant. This does not skip or
# fake BoringSSL self-tests; the image must execute its real 64-bit checks.
IMAGE=redroid/redroid@sha256:dc2024a999dd0acb1112a23cddc9537ec8cb60a0dbe5a87ce9fbd75a2f5e94d2
mkdir -p "$W" "$B"
mounted=0
container_started=0
cleanup() {
  if [ "$container_started" = 1 ]; then
    timeout 20s adb -s "$ANDROID_SERIAL" logcat -d -v threadtime > "$W/redroid-logcat.txt" 2>&1 || true
    docker inspect -f 'state={{.State.Status}} exit={{.State.ExitCode}} oom={{.State.OOMKilled}} error={{.State.Error}}' symbiosis-redroid > "$W/redroid-container-state.txt" 2>&1 || true
    docker logs --tail 2500 symbiosis-redroid > "$W/redroid-container.txt" 2>&1 || true
    sudo dmesg --ctime | tail -400 > "$W/redroid-kernel.txt" 2>&1 || true
    docker rm -f symbiosis-redroid >/dev/null 2>&1 || true
  fi
  adb disconnect "$ANDROID_SERIAL" >/dev/null 2>&1 || true
  if [ "$mounted" = 1 ]; then sudo umount "$B" || true; fi
}
trap cleanup EXIT
[ "$(uname -m)" = aarch64 ] || { echo 'HOST_NOT_ARM64'; exit 1; }
uname -a | tee "$W/redroid-host.txt"
grep -m1 '^Features' /proc/cpuinfo | tee -a "$W/redroid-host.txt" || true
# Actual kernel binderfs devices, never fake Binder responses.
sudo modprobe binder_linux devices=binder,hwbinder,vndbinder || true
grep -qw binder /proc/filesystems || { echo 'BINDERFS_UNAVAILABLE'; exit 1; }
sudo mount -t binder binder "$B"
mounted=1
sudo python3 - "$B" <<'PY'
import sys
from pathlib import Path
sys.path.insert(0, 'tests/standalone_core_probe')
from binderfs_create import allocate
path = Path(sys.argv[1])
for name in ('binder', 'hwbinder', 'vndbinder'):
    major, minor = allocate(path / 'binder-control', name)
    import os
    fd = os.open(path / name, os.O_RDWR | os.O_CLOEXEC)
    os.close(fd)
    print('ACTUAL_BINDERFS_DEVICE_OPEN_OK', name, major, minor)
PY
sudo chmod 666 "$B/binder" "$B/hwbinder" "$B/vndbinder"
# This is a pinned upstream Redroid Android image; not a Google image.
# Its privilege is needed for Android init; the isolated runner has no
# write-scoped repo token, checkout credentials, user games, or private keys.
# ADB is bound to loopback only and is never published to the network.
docker pull --platform linux/arm64 "$IMAGE"
# Keep the stopped container until logs are captured; cleanup removes it.
docker run -d --name symbiosis-redroid --privileged \
  --mount "type=bind,src=$B/binder,dst=/dev/binder" \
  --mount "type=bind,src=$B/hwbinder,dst=/dev/hwbinder" \
  --mount "type=bind,src=$B/vndbinder,dst=/dev/vndbinder" \
  --mount "type=bind,src=$B,dst=/dev/binderfs" -p 127.0.0.1:5558:5555 \
  "$IMAGE" androidboot.use_memfd=1 androidboot.redroid_gpu_mode=guest >/dev/null
container_started=1
booted=0
deadline=$((SECONDS + 600))
while (( SECONDS < deadline )); do
  if [ "$(docker inspect -f '{{.State.Running}}' symbiosis-redroid 2>/dev/null || true)" != true ]; then
    echo 'ANDROID_CONTAINER_EXITED_BEFORE_BOOT' | tee "$W/redroid-status.txt"
    exit 1
  fi
  timeout 5s adb connect "$ANDROID_SERIAL" >/dev/null 2>&1 || true
  value=$(timeout 8s adb -s "$ANDROID_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
  if [ "$value" = 1 ]; then booted=1; break; fi
  sleep 5
done
printf 'ANDROID_BOOT_COMPLETED=%s\n' "$booted" | tee "$W/redroid-status.txt"
if [ "$booted" != 1 ]; then
  timeout 10s adb devices -l | tee -a "$W/redroid-status.txt" || true
  exit 1
fi
for property in ro.build.version.sdk ro.product.cpu.abilist ro.dalvik.vm.native.bridge ro.build.fingerprint; do
  printf '%s=' "$property" | tee -a "$W/redroid-status.txt"
  timeout 15s adb -s "$ANDROID_SERIAL" shell getprop "$property" | tr -d '\r' | tee -a "$W/redroid-status.txt"
done
abi=$(adb -s "$ANDROID_SERIAL" shell getprop ro.product.cpu.abilist | tr -d '\r')
case ",$abi," in *,arm64-v8a,*) ;; *) echo 'ANDROID_ARM64_ABI_NOT_PRESENT' | tee -a "$W/redroid-status.txt"; exit 1 ;; esac
(timeout 25s adb -s "$ANDROID_SERIAL" shell dumpsys SurfaceFlinger || true) > "$W/redroid-surfaceflinger.txt" 2>&1
# Loader/JNI runs separately from the disposable test-only Activity APK.
set +e
timeout 900s python3 tests/standalone_core_probe/run.py "$W" --preload-tls
standalone_rc=$?
timeout 900s bash tests/android_core_probe/run.sh
activity_rc=$?
# Real, further engine calls run only after the preceding independent tests.
# Missing firmware/keys may legitimately block them; do not invent success.
engine_rc=not-run
if [ "$standalone_rc" = 0 ] && [ "$activity_rc" = 0 ]; then
  timeout 900s bash tests/android_core_probe/engine_stages.sh
  engine_rc=$?
fi
sdl_rc=not-run
snake_rc=not-run
matrix_rc=not-run
sdl_surface_rc=not-run
if [ "$engine_rc" = 0 ]; then
  HOMEBREW_SAMPLE=sdl timeout 600s bash tests/android_core_probe/homebrew.sh
  sdl_rc=$?
  HOMEBREW_SAMPLE=snake timeout 600s bash tests/android_core_probe/homebrew.sh
  snake_rc=$?
  HOMEBREW_SAMPLE=matrix timeout 600s bash tests/android_core_probe/homebrew.sh
  matrix_rc=$?
  HOMEBREW_SAMPLE=sdl-surface timeout 600s bash tests/android_core_probe/homebrew.sh
  sdl_surface_rc=$?
fi
set -e
printf 'STANDALONE_EXIT=%s ACTIVITY_EXIT=%s ENGINE_EXIT=%s SDL_EXIT=%s SNAKE_EXIT=%s MATRIX_EXIT=%s SDL_SURFACE_EXIT=%s\n' "$standalone_rc" "$activity_rc" "$engine_rc" "$sdl_rc" "$snake_rc" "$matrix_rc" "$sdl_surface_rc" | tee -a "$W/redroid-status.txt"
if [ "$standalone_rc" != 0 ] || [ "$activity_rc" != 0 ] || [ "$engine_rc" != 0 ] || [ "$sdl_rc" != 0 ] || [ "$snake_rc" != 0 ] || [ "$matrix_rc" != 0 ] || [ "$sdl_surface_rc" != 0 ]; then exit 1; fi
