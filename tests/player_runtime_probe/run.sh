#!/usr/bin/env bash
# Diagnostic-only Android container on native ARM Linux; this is NOT the
# official Android Emulator or a physical phone. No commercial game, keys or release APK.
set -Eeuo pipefail
: "${PLAY_WORK:?}" "${ANDROID_SERIAL:?}"
W=$PLAY_WORK
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
# This APK was assembled in a disposable runner from actual patched app sources
# with exactly one debug-only launcher added. It is never a release artifact.
package=$(cat "$W/package.txt")
case "$package" in *[!a-zA-Z0-9._]*|'') echo 'BAD_PACKAGE'; exit 1;; esac
adb install -r "$W/player-debug.apk"
adb push "$W/core_sym.so" /data/local/tmp/player-core.so >/dev/null
adb push "$W/swkbd.nro" /data/local/tmp/player-swkb.nro >/dev/null
adb shell run-as "$package" mkdir -p files/engines
adb shell run-as "$package" cp /data/local/tmp/player-core.so files/engines/libkenji.so
adb shell run-as "$package" chmod 400 files/engines/libkenji.so
adb shell run-as "$package" cp /data/local/tmp/player-swkb.nro files/swkbd-probe.nro
# Let the actual main app perform its normal initialization before asking its
# real intent factory to capture Eden input settings. No intent data is faked.
adb shell monkey -p "$package" -c android.intent.category.LAUNCHER 1 >/dev/null || true
sleep 10
adb shell am start -W -n "$package/dev.symbiosis.playerruntime.DiagnosticLauncher" | tee "$W/player-launch.txt"
log="$W/player-logcat.txt"
player_pid=$(adb shell pidof "$package:kenji" | tr -d '\r' || true)
main_pid=$(adb shell pidof "$package" | tr -d '\r' || true)
[ -n "$player_pid" ] || { echo 'PLAYER_PROCESS_NOT_RUNNING'; exit 1; }
printf 'PLAYER_PID=%s MAIN_PID=%s\n' "$player_pid" "$main_pid" | tee -a "$W/redroid-status.txt"
app_crashed() {
  grep -qE "Fatal signal.*pid $player_pid( |\))|^[0-9-]+ +[0-9:.]+ +$player_pid +[0-9]+ [FE] .*FATAL EXCEPTION|REAL_PLAYER_LAUNCH_FAILED" "$log"
}
# A durable snapshot of system logs, without clearing any existing core log.
adb logcat -v threadtime > "$log" 2>&1 &
logpid=$!
trap 'kill "$logpid" 2>/dev/null || true; cleanup' EXIT
wait_log() {
  local pattern=$1
  for ((n=0;n<90;n++)); do
    grep -qE "$pattern" "$log" && return 0
    app_crashed && return 1
    sleep 1
  done
  return 1
}
# Get real accessibility node coordinates. The launcher does not call the
# presenter's answer function; presses go through Android's input system.
click_text() {
  local target=$1
  adb shell uiautomator dump /sdcard/player-ui.xml >/dev/null
  adb exec-out cat /sdcard/player-ui.xml > "$W/player-ui.xml"
  local coords
  coords=$(python3 - "$W/player-ui.xml" "$target" <<'PY'
import re,sys,xml.etree.ElementTree as ET
root=ET.parse(sys.argv[1]); target=sys.argv[2].casefold()
for e in root.iter('node'):
    if e.get('text','').casefold() == target:
        m=re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', e.get('bounds',''))
        if m:
            x1,y1,x2,y2=map(int,m.groups());print((x1+x2)//2,(y1+y2)//2);break
else: sys.exit(1)
PY
  )
  adb shell input tap $coords
}
wait_log 'SWKBD_SHOW_BEGIN' || { echo 'PLAYER_NO_KEYBOARD_BEGIN'; exit 1; }
# The actual dialog may arrive just after the guest marker.
clicked=0
for ((n=0;n<20;n++)); do
  if click_text 'ОК' || click_text 'OK'; then clicked=1; break; fi
  sleep 1
done
[ "$clicked" = 1 ] || { echo 'PLAYER_KEYBOARD_NOT_VISIBLE'; exit 1; }
wait_log 'SWKBD_SECOND_BEGIN' || { echo 'PLAYER_FIRST_ANSWER_NOT_RECEIVED'; exit 1; }
clicked=0
for ((n=0;n<20;n++)); do
  if click_text 'Отмена' || click_text 'Cancel'; then clicked=1; break; fi
  sleep 1
done
[ "$clicked" = 1 ] || { echo 'PLAYER_SECOND_KEYBOARD_NOT_VISIBLE'; exit 1; }
wait_log 'SWKBD_GUEST_DONE' || { echo 'PLAYER_GUEST_DID_NOT_COMPLETE'; exit 1; }
grep -qE 'SWKBD_SHOW_RETURN rc=0x00000000 .*text=Seed' "$log" || { echo 'PLAYER_FIRST_ANSWER_WRONG'; exit 1; }
grep -qE 'SWKBD_SECOND_RETURN .*cancel_match=1' "$log" || { echo 'PLAYER_CANCEL_WRONG'; exit 1; }
# Exercise the real Exit button and verify :kenji process disappears.
click_text 'Выйти' || { echo 'PLAYER_EXIT_BUTTON_NOT_VISIBLE'; exit 1; }
for ((n=0;n<45;n++)); do
  pid=$(adb shell pidof "$package:kenji" | tr -d '\r' || true)
  [ -z "$pid" ] && break
  sleep 1
done
[ -z "${pid:-}" ] || { echo 'PLAYER_EXIT_PROCESS_STILL_ALIVE'; exit 1; }
! app_crashed || { echo 'PLAYER_FATAL_OBSERVED'; exit 1; }
echo 'PLAYER_REAL_UI_AND_EXIT_OK' | tee -a "$W/redroid-status.txt"
