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
  if [ -n "${guest_server_pid:-}" ]; then kill "$guest_server_pid" 2>/dev/null || true; fi
  rm -f "$W/ci-test-key.pem" "$W/ci-test-cert.pem" "$W/ci-wrong-key.pem" "$W/ci-wrong-ca.pem" "$W/ci-leaf-key.pem" "$W/ci-leaf.pem" "$W/ci-test-cert.der"
  rm -rf "$W/ci-pki"
  if [ "$container_started" = 1 ]; then
    timeout 20s adb -s "$ANDROID_SERIAL" logcat -d -v threadtime > "$W/redroid-logcat.txt" 2>&1 || true
    if [ -n "${package:-}" ]; then
      timeout 10s adb -s "$ANDROID_SERIAL" exec-out run-as "$package" cat "/sdcard/Android/data/$package/files/kenji/kenji-stdout.txt" > "$W/player-native-stdout.txt" 2>&1 || true
    fi
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
# Disposable A/B on the same Android ARM host, not a game or Mali test. The
# reference binaries are 4 KiB aligned and must never be used on a 16K host.
if [ "${PLAYER_COMPARE_SPACE:-0}" = 1 ] && [ -d "$W/ffmpeg-perf" ]; then
  page=$(getconf PAGESIZE)
  echo "FFMPEG_PERF_HOST_PAGE_SIZE=$page" | tee "$W/player-ffmpeg-perf.txt"
  if [ "$page" = 4096 ]; then
    adb push "$W/ffmpeg-perf" /data/local/tmp/ffmpeg-perf >/dev/null
    adb shell chmod 755 /data/local/tmp/ffmpeg-perf/ffmpeg-perf
    for variant in official current current official official current; do
      printf 'FFMPEG_PERF_VARIANT=%s ' "$variant" | tee -a "$W/player-ffmpeg-perf.txt"
      adb shell "LD_LIBRARY_PATH=/data/local/tmp/ffmpeg-perf/$variant /data/local/tmp/ffmpeg-perf/ffmpeg-perf" \
        | tr -d '\r' | tee -a "$W/player-ffmpeg-perf.txt"
    done
    echo 'FFMPEG_PERF_NOT_A_GAME_OR_MALI_MEASUREMENT' | tee -a "$W/player-ffmpeg-perf.txt"
  else
    echo 'FFMPEG_PERF_REFERENCE_SKIPPED_NOT_4K_HOST' | tee -a "$W/player-ffmpeg-perf.txt"
  fi
fi
# This APK was assembled in a disposable runner from actual patched app sources
# with exactly one debug-only launcher added. It is never a release artifact.
package=$(cat "$W/package.txt")
case "$package" in *[!a-zA-Z0-9._]*|'') echo 'BAD_PACKAGE'; exit 1;; esac
adb install -r "$W/player-debug.apk"
adb push "$W/core_sym.so" /data/local/tmp/player-core.so >/dev/null
if [ "${PLAYER_COMPARE_SPACE:-0}" = 1 ]; then
  adb push "$W/core_space.so" /data/local/tmp/player-space.so >/dev/null
fi
adb push "$W/swkbd.nro" /data/local/tmp/player-swkb.nro >/dev/null
adb push "$W/graphics.nro" /data/local/tmp/player-graphics.nro >/dev/null
adb push "$W/shader.nro" /data/local/tmp/player-shader.nro >/dev/null
if [ "${PLAYER_GUEST_TLS:-0}" = 1 ]; then
  adb push "$W/guest-tls.nro" /data/local/tmp/player-guest-tls.nro >/dev/null
fi
if [ "${PLAYER_GUEST_NETWORK:-0}" = 1 ]; then
  adb push "$W/guest-network.nro" /data/local/tmp/player-guest-network.nro >/dev/null
fi
if [ "${PLAYER_HID_LONG:-0}" = 1 ]; then
  adb push "$W/hid-long.nro" /data/local/tmp/player-hid-long.nro >/dev/null
fi
if [ "${PLAYER_GUEST_ACCOUNT:-0}" = 1 ]; then
  adb push "$W/guest-account.nro" /data/local/tmp/player-guest-account.nro >/dev/null
fi
if [ "${PLAYER_TLS_PARALLEL:-0}" = 1 ]; then
  adb push "$W/tls-parallel.nro" /data/local/tmp/player-tls-parallel.nro >/dev/null
fi
adb shell run-as "$package" mkdir -p files/engines
adb shell run-as "$package" cp /data/local/tmp/player-core.so files/engines/libkenji.so
adb shell run-as "$package" chmod 400 files/engines/libkenji.so
primary_hash=$(adb exec-out run-as "$package" cat files/engines/libkenji.so | sha256sum | cut -d' ' -f1)
if [ "${PLAYER_REFERENCE_CORE:-0}" = 1 ]; then
  [ "${PLAYER_COMPARE_SPACE:-0}" != 1 ] || { echo 'REFERENCE_AND_SPACE_COMPARISON_UNSUPPORTED'; exit 1; }
  source_hash=$(cat "$W/source-core-sha256.txt")
  [[ "$source_hash" =~ ^[0-9a-f]{64}$ ]] && [ "$primary_hash" = "$source_hash" ] || { echo 'SOURCE_CORE_HASH_MISMATCH'; exit 1; }
  primary_core=source-research
  if [ "${PLAYER_REFERENCE_TLS_PATCH:-0}" = 1 ]; then primary_core="$primary_core-ssl-patch"; fi
  if [ "${PLAYER_REFERENCE_SSL_COUNT_PATCH:-0}" = 1 ]; then primary_core="$primary_core-ssl-count-patch"; fi
  if [ "${PLAYER_REFERENCE_NETWORK_PATCH:-0}" = 1 ]; then primary_core="$primary_core-network-patch"; fi
  if [ "${PLAYER_REFERENCE_MISSING_INTERFACE:-0}" = 1 ]; then primary_core="$primary_core-missing-interface-fixture"; fi
  if [ "${PLAYER_REFERENCE_ZERO_INTERFACE:-0}" = 1 ]; then primary_core="$primary_core-zero-interface-fixture"; fi
  if [ "${PLAYER_REFERENCE_VALID_INTERFACE:-0}" = 1 ]; then primary_core="$primary_core-valid-interface-fixture"; fi
else
  [ "$primary_hash" = 969431b3962408dd02ce38bd2cd4b2a954d11e5212ddef3f65c80a0b419918b8 ] || { echo 'SYM_CORE_HASH_MISMATCH'; exit 1; }
  primary_core=sym
fi
echo "PLAYER_PRIMARY_CORE $primary_core sha256=$primary_hash" | tee -a "$W/redroid-status.txt"
adb shell run-as "$package" cp /data/local/tmp/player-swkb.nro files/swkbd-probe.nro
adb shell run-as "$package" cp /data/local/tmp/player-graphics.nro files/graphics-probe.nro
adb shell run-as "$package" cp /data/local/tmp/player-shader.nro files/shader-probe.nro
if [ "${PLAYER_GUEST_TLS:-0}" = 1 ]; then
  adb shell run-as "$package" cp /data/local/tmp/player-guest-tls.nro files/guest-tls-probe.nro
fi
if [ "${PLAYER_GUEST_NETWORK:-0}" = 1 ]; then
  adb shell run-as "$package" cp /data/local/tmp/player-guest-network.nro files/guest-network-probe.nro
  adb shell run-as "$package" mkdir -p files/kenji/sdcard
fi
if [ "${PLAYER_HID_LONG:-0}" = 1 ]; then
  adb shell run-as "$package" cp /data/local/tmp/player-hid-long.nro files/hid-long-probe.nro
fi
if [ "${PLAYER_GUEST_ACCOUNT:-0}" = 1 ]; then
  adb shell run-as "$package" cp /data/local/tmp/player-guest-account.nro files/guest-account-probe.nro
  adb shell run-as "$package" mkdir -p files/kenji/sdcard
fi
if [ "${PLAYER_TLS_PARALLEL:-0}" = 1 ]; then
  adb shell run-as "$package" cp /data/local/tmp/player-tls-parallel.nro files/tls-parallel-probe.nro
fi
# Generate a disposable local test certificate/key on this isolated runner.
# Neither is included in the APK or uploaded in test artifacts.
if [ "${PLAYER_EXPECT_TLS_CANDIDATE:-0}" = 1 ] || [ "${PLAYER_GUEST_TLS:-0}" = 1 ]; then
  openssl req -x509 -newkey rsa:2048 -nodes -keyout "$W/ci-test-key.pem" -out "$W/ci-test-cert.pem" \
    -days 1 -subj '/CN=localhost' -addext 'subjectAltName=DNS:localhost' \
    -addext 'basicConstraints=critical,CA:TRUE' -addext 'keyUsage=critical,keyCertSign,cRLSign,digitalSignature,keyEncipherment' >/dev/null 2>&1
  adb push "$W/ci-test-key.pem" /data/local/tmp/player-ci-test-key.pem >/dev/null
  adb push "$W/ci-test-cert.pem" /data/local/tmp/player-ci-test-cert.pem >/dev/null
  adb shell chmod 644 /data/local/tmp/player-ci-test-key.pem /data/local/tmp/player-ci-test-cert.pem
  adb shell run-as "$package" cp /data/local/tmp/player-ci-test-key.pem files/ci-test-key.pem
  adb shell run-as "$package" cp /data/local/tmp/player-ci-test-cert.pem files/ci-test-cert.pem
  if [ "${PLAYER_GUEST_TLS:-0}" = 1 ]; then
    adb shell run-as "$package" mkdir -p files/kenji/sdcard
    adb shell run-as "$package" cp /data/local/tmp/player-ci-test-cert.pem files/kenji/sdcard/ci-test-cert.pem
    # Independent wrong CA, non-CA leaf and DER representation of the actual CA.
    openssl req -x509 -newkey rsa:2048 -nodes -keyout "$W/ci-wrong-key.pem" -out "$W/ci-wrong-ca.pem" \
      -days 1 -subj '/CN=unrelated-test-root' -addext 'basicConstraints=critical,CA:TRUE' >/dev/null 2>&1
    openssl req -x509 -newkey rsa:2048 -nodes -keyout "$W/ci-leaf-key.pem" -out "$W/ci-leaf.pem" \
      -days 1 -subj '/CN=not-an-authority' -addext 'basicConstraints=critical,CA:FALSE' >/dev/null 2>&1
    openssl x509 -in "$W/ci-test-cert.pem" -outform DER -out "$W/ci-test-cert.der"
    bash tests/guest_tls_probe/generate_chain_fixture.sh "$W/ci-pki" "$W/ci-test-cert.pem" "$W/ci-test-key.pem"
    for name in ci-wrong-ca.pem ci-leaf.pem ci-test-cert.der; do
      adb push "$W/$name" "/data/local/tmp/player-$name" >/dev/null
      adb shell chmod 644 "/data/local/tmp/player-$name"
      adb shell run-as "$package" cp "/data/local/tmp/player-$name" "files/kenji/sdcard/$name"
      adb shell rm "/data/local/tmp/player-$name"
    done
  fi
  adb shell rm /data/local/tmp/player-ci-test-key.pem /data/local/tmp/player-ci-test-cert.pem
  if [ "${PLAYER_GUEST_TLS:-0}" != 1 ]; then rm -f "$W/ci-test-key.pem" "$W/ci-test-cert.pem"; fi
fi
# Let the actual main app perform its normal initialization before asking its
# real intent factory to capture Eden input settings. No intent data is faked.
adb shell monkey -p "$package" -c android.intent.category.LAUNCHER 1 >/dev/null || true
sleep 10
log="$W/player-logcat.txt"
# A durable snapshot of system logs, without clearing any existing core log.
adb logcat -v threadtime > "$log" 2>&1 &
logpid=$!
trap 'kill "$logpid" 2>/dev/null || true; cleanup' EXIT
# On a fresh install, the launcher activity itself may create the main
# process; do not require a PID before it has been started.
main_pid=''
player_pid=''
app_crashed() {
  [ -n "$player_pid" ] && grep -qE "Fatal signal.*pid $player_pid( |\))|^[0-9-]+ +[0-9:.]+ +$player_pid +[0-9]+ [FE] .*FATAL EXCEPTION" "$log"
}
wait_log() {
  local pattern=$1
  for ((n=0;n<90;n++)); do
    # One grep avoids SIGPIPE from grep -q under pipefail on verbose logcat.
    grep -qE "^[0-9-]+ +[0-9:.]+ +$player_pid +[0-9]+ .*$pattern" "$log" && return 0
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
exit_player() {
  click_text 'Меню' && click_text 'Выйти из игры' && click_text 'Выйти'
}
# Locate the actual Plus hitbox printed by the debug-only geometry observer,
# then inject a physical-style Android hold. Verify state transitions in the
# guest, not just the view or JNI caller. Never call the overlay or core APIs.
press_plus() {
  local orientation=$1 before_press before_release coords
  for ((n=0;n<20;n++)); do
    coords=$(grep -E "^[0-9-]+ +[0-9:.]+ +$player_pid +[0-9]+ .*CI_ONLY_PLUS_BOUNDS" "$log" | tail -1 | \
      sed -nE 's/.*CI_ONLY_PLUS_BOUNDS ([0-9]+),([0-9]+),([0-9]+),([0-9]+).*/\1 \2 \3 \4/p' || true)
    [ -n "$coords" ] && break
    sleep 1
  done
  [ -n "$coords" ] || { echo "PLUS_BOUNDS_MISSING orientation=$orientation"; return 1; }
  read -r left top right bottom <<< "$coords"
  [ "$right" -gt "$left" ] && [ "$bottom" -gt "$top" ] || return 1
  local x=$(((left+right)/2)) y=$(((top+bottom)/2))
  before_press=$(grep -cE "^[0-9-]+ +[0-9:.]+ +$player_pid +[0-9]+ .*SDL_GUEST_PLUS value=1 connected=1" "$log" || true)
  before_release=$(grep -cE "^[0-9-]+ +[0-9:.]+ +$player_pid +[0-9]+ .*SDL_GUEST_PLUS value=0 connected=1" "$log" || true)
  echo "PLUS_TAP orientation=$orientation bounds=$coords center=$x,$y" | tee -a "$W/redroid-status.txt"
  adb shell input swipe "$x" "$y" "$x" "$y" 900
  for ((n=0;n<20;n++)); do
    pressed=$(grep -cE "^[0-9-]+ +[0-9:.]+ +$player_pid +[0-9]+ .*SDL_GUEST_PLUS value=1 connected=1" "$log" || true)
    released=$(grep -cE "^[0-9-]+ +[0-9:.]+ +$player_pid +[0-9]+ .*SDL_GUEST_PLUS value=0 connected=1" "$log" || true)
    if [ "$pressed" -gt "$before_press" ] && [ "$released" -gt "$before_release" ]; then
      echo "PLUS_GUEST_PRESS_RELEASE_OK orientation=$orientation" | tee -a "$W/redroid-status.txt"
      return 0
    fi
    app_crashed && return 1
    sleep 1
  done
  echo "PLUS_GUEST_TRANSITION_MISSING orientation=$orientation"
  return 1
}
# Install the actual signed Pages version in the disposable Android app. The
# following real player sessions must then load that version, not the legacy file.
if [ "${PLAYER_CORE_UPDATE:-0}" = 1 ]; then
  [[ "${PLAYER_CORE_UPDATE_EXPECTED_SHA:-}" =~ ^[0-9a-f]{64}$ ]] || { echo 'BAD_CORE_UPDATE_EXPECTED_SHA'; exit 1; }
  if [ "$PLAYER_CORE_UPDATE_EXPECTED_SHA" = d781048671e4cef1cde2ec15db8fe29b949df9949d83569ceaf776ad12901590 ]; then
    # Restore signed version 1 into the isolated test app before installing v2.
    # The app itself verifies its real signature and core hash before the update.
    curl -fsSL --retry 3 https://raw.githubusercontent.com/sj0404-collab/symbiosis/91495c32ed963ce46a06883a150267fb17b344ef/docs/core-test/current.txt -o "$W/old-signed-manifest.txt"
    adb push "$W/old-signed-manifest.txt" /data/local/tmp/player-old-manifest.txt >/dev/null
    adb shell chmod 644 /data/local/tmp/player-old-manifest.txt
    olddir=files/engines/core-test-updates/1-969431b3962408dd02ce38bd2cd4b2a954d11e5212ddef3f65c80a0b419918b8
    adb shell run-as "$package" mkdir -p "$olddir"
    adb shell run-as "$package" cp /data/local/tmp/player-core.so "$olddir/libkenji.so"
    adb shell run-as "$package" chmod 400 "$olddir/libkenji.so"
    adb shell run-as "$package" cp /data/local/tmp/player-old-manifest.txt "$olddir/manifest.txt"
    adb shell rm /data/local/tmp/player-old-manifest.txt
  fi
  adb shell am start -W -n "$package/dev.symbiosis.playerruntime.DiagnosticLauncher" --es test_kind core-update \
    --es ci_expected_core_sha "$PLAYER_CORE_UPDATE_EXPECTED_SHA" > "$W/core-update-launch.txt"
  update_ok=0
  for ((n=0;n<180;n++)); do
    if adb shell run-as "$package" test -f files/ci-core-update-ok.txt; then update_ok=1; break; fi
    if adb shell run-as "$package" test -f files/ci-core-update-fail.txt; then break; fi
    sleep 1
  done
  if [ "$update_ok" != 1 ]; then
    adb exec-out run-as "$package" cat files/ci-core-update-fail.txt > "$W/core-update-failure.txt" 2>&1 || true
    echo 'SIGNED_CORE_UPDATE_FAILED' | tee -a "$W/redroid-status.txt"; exit 1
  fi
  adb exec-out run-as "$package" cat files/ci-core-update-ok.txt | tee -a "$W/redroid-status.txt"
  primary_core="signed-update-$PLAYER_CORE_UPDATE_EXPECTED_SHA"
fi

# Three isolated sessions exercise the real player and prove that the same
# main process can launch again after the previous :kenji process has exited.
# Mode 2 is the normal default; 0/1 are explicit real config values, not
# asserted equivalent to the related upstream enum without binary evidence.
for mode in 2 0 1; do
  echo "PLAYER_ATTEMPT mode=$mode"
  adb shell am start -W -n "$package/dev.symbiosis.playerruntime.DiagnosticLauncher" \
    --es test_config "memory=$mode" | tee "$W/player-launch-$mode.txt"
  player_pid=$(adb shell pidof "$package:kenji" | tr -d '\r' || true)
  this_main=$(adb shell pidof "$package" | tr -d '\r' || true)
  [ -n "$this_main" ] || { echo "PLAYER_MAIN_PROCESS_NOT_RUNNING mode=$mode"; exit 1; }
  [ -n "$main_pid" ] || main_pid=$this_main
  [ "$this_main" = "$main_pid" ] || { echo "PLAYER_MAIN_PROCESS_CHANGED mode=$mode"; exit 1; }
  [ -n "$player_pid" ] || { echo "PLAYER_PROCESS_NOT_RUNNING mode=$mode"; exit 1; }
  printf 'PLAYER_PID=%s MAIN_PID=%s MODE=%s\n' "$player_pid" "$main_pid" "$mode" | tee -a "$W/redroid-status.txt"
  if [ "${PLAYER_CORE_UPDATE:-0}" = 1 ]; then
    # This is written by the real player process after its session lock and
    # verified selection, not merely by the diagnostic update launcher.
    observed=0
    for ((n=0;n<30;n++)); do
      if adb exec-out run-as "$package" cat files/core-update-events.txt 2>/dev/null | grep -q 'Запуск игрока: версия'; then observed=1; break; fi
      sleep 1
    done
    [ "$observed" = 1 ] || { echo 'PLAYER_DID_NOT_SELECT_SIGNED_CORE'; exit 1; }
    echo 'PLAYER_SELECTED_SIGNED_CORE' | tee -a "$W/redroid-status.txt"
  fi
  wait_log 'CI_FFMPEG_PROBE_RESULT=0' || { echo "PLAYER_FFMPEG_IN_PROCESS_FAILED mode=$mode"; exit 1; }
  wait_log 'CI_FFMPEG_PRIVATE_DECODE version=59.37.100 h264=1 vp8=1 frame=16x16' || {
    echo "PLAYER_FFMPEG_DECODE_MISSING mode=$mode"; exit 1;
  }
  echo "PLAYER_FFMPEG_H264_PRIVATE_DECODE_OK mode=$mode" | tee -a "$W/redroid-status.txt"
  if [ "${PLAYER_EXPECT_TLS_CANDIDATE:-0}" = 1 ]; then
    wait_log 'CI_ONLY_TLS_OPENSSL_VERSION=0x3050009[0-9a-f] .*libcrypto.so' || {
      echo "PLAYER_CANDIDATE_OPENSSL_NOT_OBSERVED mode=$mode"; exit 1;
    }
    grep -E "^[0-9-]+ +[0-9:.]+ +$player_pid +[0-9]+ .*CI_ONLY_TLS_OPENSSL_VERSION=" "$log" | tee -a "$W/redroid-status.txt"
    echo "PLAYER_CANDIDATE_OPENSSL_LOADED mode=$mode (not proof of .NET TLS usage)" | tee -a "$W/redroid-status.txt"
    wait_log 'CI_ONLY_TLS_IN_PROCESS good=0 verify=0 wrong_host=5 wrong_verify=62' || {
      echo "PLAYER_IN_PROCESS_TLS_FAILED mode=$mode"; exit 1;
    }
    echo "PLAYER_IN_PROCESS_TLS_VERIFIED mode=$mode (not proof of .NET TLS usage)" | tee -a "$W/redroid-status.txt"
  fi
  wait_log 'SWKBD_SHOW_BEGIN' || { echo "PLAYER_NO_KEYBOARD_BEGIN mode=$mode"; exit 1; }
  clicked=0
  for ((n=0;n<20;n++)); do
    if click_text 'ОК' || click_text 'OK'; then clicked=1; break; fi
    sleep 1
  done
  [ "$clicked" = 1 ] || { echo "PLAYER_KEYBOARD_NOT_VISIBLE mode=$mode"; exit 1; }
  wait_log 'SWKBD_SECOND_BEGIN' || { echo "PLAYER_FIRST_ANSWER_NOT_RECEIVED mode=$mode"; exit 1; }
  clicked=0
  for ((n=0;n<20;n++)); do
    if click_text 'Отмена' || click_text 'Cancel'; then clicked=1; break; fi
    sleep 1
  done
  [ "$clicked" = 1 ] || { echo "PLAYER_SECOND_KEYBOARD_NOT_VISIBLE mode=$mode"; exit 1; }
  wait_log 'SWKBD_GUEST_DONE' || { echo "PLAYER_GUEST_DID_NOT_COMPLETE mode=$mode"; exit 1; }
  wait_log 'SWKBD_SHOW_RETURN rc=0x00000000 .*text=Seed' || { echo "PLAYER_FIRST_ANSWER_WRONG mode=$mode"; exit 1; }
  wait_log 'SWKBD_SECOND_RETURN .*cancel_match=1' || { echo "PLAYER_CANCEL_WRONG mode=$mode"; exit 1; }
  # Back must open our real menu rather than terminate the playing process.
  adb shell input keyevent KEYCODE_BACK
  click_text 'Продолжить' || { echo "PLAYER_BACK_MENU_NOT_VISIBLE mode=$mode"; exit 1; }
  [ "$(adb shell pidof "$package:kenji" | tr -d '\r' || true)" = "$player_pid" ] || { echo "PLAYER_BACK_ENDED_SESSION mode=$mode"; exit 1; }
  echo "PLAYER_BACK_MENU_ALIVE mode=$mode" | tee -a "$W/redroid-status.txt"
  exit_player || { echo "PLAYER_EXIT_BUTTON_NOT_VISIBLE mode=$mode"; exit 1; }
  for ((n=0;n<45;n++)); do
    pid=$(adb shell pidof "$package:kenji" | tr -d '\r' || true)
    [ -z "$pid" ] && break
    sleep 1
  done
  [ -z "${pid:-}" ] || { echo "PLAYER_EXIT_PROCESS_STILL_ALIVE mode=$mode"; exit 1; }
  ! app_crashed || { echo "PLAYER_FATAL_OBSERVED mode=$mode"; exit 1; }
  parent_after=$(adb shell pidof "$package" | tr -d '\r' || true)
  [ "$parent_after" = "$main_pid" ] || { echo "PLAYER_MAIN_PROCESS_CHANGED mode=$mode"; exit 1; }
  echo "PLAYER_REAL_UI_AND_EXIT_OK mode=$mode" | tee -a "$W/redroid-status.txt"
done

# Exercise the default quiet request on the real signed core. This checks that
# the player survives and asks for the API, not that every binary log source is silent.
if [ "${PLAYER_CORE_UPDATE:-0}" = 1 ] && [ "${PLAYER_HID_LONG:-0}" = 1 ]; then
  # CI-only retained log with an old failure. After the player starts we update
  # its mtime to reproduce the dangerous condition: file freshness alone does
  # not make an old SvcBreak current. Do not alter any user files or games.
  printf '%s\n' '00:00:15.200 |E| KernelSvc Break: SvcBreak reason=0x0 tid=46 pc=0x139553C0' > "$W/ci-retained-panic.log"
  adb push "$W/ci-retained-panic.log" /data/local/tmp/ci-retained-panic.log >/dev/null
  adb shell chmod 644 /data/local/tmp/ci-retained-panic.log
  adb shell run-as "$package" mkdir -p files/kenji/Logs
  adb shell run-as "$package" cp /data/local/tmp/ci-retained-panic.log files/kenji/Logs/ci-retained-panic.log
  adb shell am start -W -n "$package/dev.symbiosis.playerruntime.DiagnosticLauncher" \
    --es test_config 'memory=2' --es test_kind hid-long --ez ci_quiet_log true > "$W/quiet-launch.txt"
  quiet_pid=$(adb shell pidof "$package:kenji" | tr -d '\r' || true)
  [ -n "$quiet_pid" ] || { echo 'QUIET_PLAYER_NOT_RUNNING'; exit 1; }
  quiet_seen=0
  for ((n=0;n<40;n++)); do
    if grep -qE "^[0-9-]+ +[0-9:.]+ +$quiet_pid +[0-9]+ .*log_policy=errors api=true" "$log"; then quiet_seen=1; break; fi
    app_crashed && break
    sleep 1
  done
  [ "$quiet_seen" = 1 ] || { echo 'QUIET_POLICY_NOT_OBSERVED'; exit 1; }
  # Force this pre-existing record to be selected as the newest file; the
  # future mtime makes the test deterministic even with other concurrent logs.
  adb shell run-as "$package" touch -t 203001010000 files/kenji/Logs/ci-retained-panic.log
  observed=0
  for ((n=0;n<30;n++)); do
    if grep -qE "^[0-9-]+ +[0-9:.]+ +$quiet_pid +[0-9]+ .*LOG_OBSERVATION_NO_AUTO_STOP kind=SvcBreak" "$log"; then observed=1; break; fi
    app_crashed && break
    sleep 1
  done
  [ "$observed" = 1 ] || { echo 'OLD_LOG_NOT_OBSERVED'; exit 1; }
  sleep 10
  [ "$(adb shell pidof "$package:kenji" | tr -d '\r' || true)" = "$quiet_pid" ] || { echo 'QUIET_PLAYER_DIED'; exit 1; }
  ! app_crashed || { echo 'QUIET_PLAYER_FATAL'; exit 1; }
  echo 'PLAYER_RETAINED_OLD_PANIC_DID_NOT_STOP_PLAYER' | tee -a "$W/redroid-status.txt"
  echo 'PLAYER_QUIET_LOG_POLICY_REQUESTED_AND_ALIVE' | tee -a "$W/redroid-status.txt"
  exit_player || { echo 'QUIET_PLAYER_EXIT_NOT_VISIBLE'; exit 1; }
  for ((n=0;n<45;n++)); do
    pid=$(adb shell pidof "$package:kenji" | tr -d '\r' || true)
    [ -z "$pid" ] && break
    sleep 1
  done
  [ -z "${pid:-}" ] || { echo 'QUIET_PLAYER_EXIT_STUCK'; exit 1; }
  adb shell run-as "$package" rm -f files/kenji/Logs/ci-retained-panic.log
  adb shell rm -f /data/local/tmp/ci-retained-panic.log
fi

# Long-lived HID observations are synthetic and never represent a real game.
if [ "${PLAYER_HID_LONG:-0}" = 1 ]; then
  hid_failed=0
  for hid_core in sym space; do
    if [ "$hid_core" = space ]; then
      [ "${PLAYER_COMPARE_SPACE:-0}" = 1 ] || break
      [ -z "$(adb shell pidof "$package:kenji" | tr -d '\r' || true)" ] || { echo 'HID_CORE_SWAP_WHILE_RUNNING'; exit 1; }
      adb shell run-as "$package" rm files/engines/libkenji.so
      adb shell run-as "$package" cp /data/local/tmp/player-space.so files/engines/libkenji.so
      adb shell run-as "$package" chmod 400 files/engines/libkenji.so
      hash=$(adb exec-out run-as "$package" cat files/engines/libkenji.so | sha256sum | cut -d' ' -f1)
      [ "$hash" = d781048671e4cef1cde2ec15db8fe29b949df9949d83569ceaf776ad12901590 ] || { echo 'HID_SPACE_HASH_MISMATCH'; exit 1; }
      hid_label=space
    else hid_label=$primary_core; fi
    echo "PLAYER_HID_LONG_ATTEMPT core=$hid_label" | tee -a "$W/redroid-status.txt"
    adb shell am start -W -n "$package/dev.symbiosis.playerruntime.DiagnosticLauncher" \
      --es test_config 'memory=2' --es test_kind hid-long > "$W/hid-long-launch-$hid_core.txt"
    player_pid=$(adb shell pidof "$package:kenji" | tr -d '\r' || true)
    [ -n "$player_pid" ] || { echo 'HID_LONG_PLAYER_NOT_RUNNING'; exit 1; }
    outcome=timeout
    for ((n=0;n<90;n++)); do
      if grep -qE "^[0-9-]+ +[0-9:.]+ +$player_pid +[0-9]+ .*HID_LONG_COMPLETE" "$log"; then outcome=complete; break; fi
      if grep -qE "^[0-9-]+ +[0-9:.]+ +$player_pid +[0-9]+ .*(Unhandled exception in guest thread|HID_LONG_ERROR)" "$log" || app_crashed; then outcome=crash; break; fi
      sleep 1
    done
    grep -E "^[0-9-]+ +[0-9:.]+ +$player_pid +[0-9]+ .*(HID_LONG_|Unhandled exception in guest thread)" "$log" > "$W/hid-long-guest-$hid_core.txt" || true
    cat "$W/hid-long-guest-$hid_core.txt" >> "$W/redroid-status.txt"
    if [ "$outcome" = complete ] && python3 tests/player_runtime_probe/check_hid_long_evidence.py "$log" "$player_pid" >> "$W/hid-long-guest-$hid_core.txt" 2>&1; then
      outcome=verified
    else
      [ "$outcome" != complete ] || outcome=evidence_mismatch
      hid_failed=1
    fi
    echo "PLAYER_HID_LONG_RESULT core=$hid_label outcome=$outcome" | tee -a "$W/redroid-status.txt"
    exit_player || true
    for ((n=0;n<30;n++)); do
      pid=$(adb shell pidof "$package:kenji" | tr -d '\r' || true)
      [ -z "$pid" ] && break; sleep 1
    done
    if [ -n "${pid:-}" ]; then
      echo "HID_LONG_FORCE_STOP core=$hid_label" | tee -a "$W/redroid-status.txt"
      hid_failed=1; adb shell am force-stop "$package"
      adb shell monkey -p "$package" -c android.intent.category.LAUNCHER 1 >/dev/null || true
      sleep 5; main_pid=$(adb shell pidof "$package" | tr -d '\r' || true)
    fi
  done
  if [ "${PLAYER_COMPARE_SPACE:-0}" = 1 ]; then
    adb shell run-as "$package" rm files/engines/libkenji.so
    adb shell run-as "$package" cp /data/local/tmp/player-core.so files/engines/libkenji.so
    adb shell run-as "$package" chmod 400 files/engines/libkenji.so
    hash=$(adb exec-out run-as "$package" cat files/engines/libkenji.so | sha256sum | cut -d' ' -f1)
    [ "$hash" = 969431b3962408dd02ce38bd2cd4b2a954d11e5212ddef3f65c80a0b419918b8 ] || { echo 'HID_SYM_RESTORE_HASH_MISMATCH'; exit 1; }
  fi
  [ "$hid_failed" = 0 ] || { echo 'HID_LONG_ONE_OR_MORE_CORES_NOT_VERIFIED'; exit 1; }
fi

# Synthetic account IPC, two modes in independent processes, not user game.
if [ "${PLAYER_GUEST_ACCOUNT:-0}" = 1 ]; then
  account_failed=0
  for account_core in sym space; do
    if [ "$account_core" = space ]; then
      [ "${PLAYER_COMPARE_SPACE:-0}" = 1 ] || break
      [ -z "$(adb shell pidof "$package:kenji" | tr -d '\r' || true)" ] || { echo 'ACCOUNT_CORE_SWAP_WHILE_RUNNING'; exit 1; }
      adb shell run-as "$package" rm files/engines/libkenji.so
      adb shell run-as "$package" cp /data/local/tmp/player-space.so files/engines/libkenji.so
      adb shell run-as "$package" chmod 400 files/engines/libkenji.so
      hash=$(adb exec-out run-as "$package" cat files/engines/libkenji.so | sha256sum | cut -d' ' -f1)
      [ "$hash" = d781048671e4cef1cde2ec15db8fe29b949df9949d83569ceaf776ad12901590 ] || { echo 'ACCOUNT_SPACE_HASH_MISMATCH'; exit 1; }
      account_label=space
    else account_label=$primary_core; fi
    for mode in normal pid; do
      name="$account_core-$mode"
      printf '%s\n' "$mode" > "$W/guest-account-mode.txt"
      adb push "$W/guest-account-mode.txt" /data/local/tmp/player-account-mode.txt >/dev/null
      adb shell chmod 644 /data/local/tmp/player-account-mode.txt
      adb shell run-as "$package" cp /data/local/tmp/player-account-mode.txt files/kenji/sdcard/ci-account-mode.txt
      adb shell rm /data/local/tmp/player-account-mode.txt
      echo "PLAYER_ACCOUNT_ATTEMPT core=$account_label mode=$mode" | tee -a "$W/redroid-status.txt"
      adb shell am start -W -n "$package/dev.symbiosis.playerruntime.DiagnosticLauncher" \
        --es test_config 'memory=2' --es test_kind account > "$W/guest-account-launch-$name.txt"
      player_pid=''
      for ((n=0;n<15;n++)); do
        player_pid=$(adb shell pidof "$package:kenji" | tr -d '\r' || true)
        [ -n "$player_pid" ] && break
        sleep 1
      done
      outcome=timeout
      if [ -n "$player_pid" ]; then
        for ((n=0;n<65;n++)); do
          if grep -qE "^[0-9-]+ +[0-9:.]+ +$player_pid +[0-9]+ .*ACCOUNT_PROBE_COMPLETE mode=$mode" "$log"; then outcome=complete; break; fi
          if grep -qE "^[0-9-]+ +[0-9:.]+ +$player_pid +[0-9]+ .*Unhandled exception in guest thread" "$log" || app_crashed; then outcome=crash; break; fi
          if grep -qE "^[0-9-]+ +[0-9:.]+ +$player_pid +[0-9]+ .*ACCOUNT_PROBE_ERROR" "$log"; then outcome=guest_error; break; fi
          sleep 1
        done
        grep -E "^[0-9-]+ +[0-9:.]+ +$player_pid +[0-9]+ .*(ACCOUNT_PROBE_|Unhandled exception in guest thread|ServiceAcc.*Stubbed)" "$log" > "$W/guest-account-guest-$name.txt" || true
        cat "$W/guest-account-guest-$name.txt" >> "$W/redroid-status.txt"
      else outcome=process_missing; fi
      if [ "$outcome" = complete ]; then
        if python3 tests/player_runtime_probe/check_guest_account_evidence.py "$log" "$player_pid" "$mode" >> "$W/guest-account-guest-$name.txt" 2>&1; then outcome=verified; else outcome=evidence_mismatch; fi
      fi
      echo "PLAYER_ACCOUNT_RESULT core=$account_label mode=$mode outcome=$outcome" | tee -a "$W/redroid-status.txt"
      [ "$outcome" = verified ] || account_failed=1
      exit_player || true
      pid=''
      for ((n=0;n<30;n++)); do
        pid=$(adb shell pidof "$package:kenji" | tr -d '\r' || true)
        [ -z "$pid" ] && break; sleep 1
      done
      if [ -n "$pid" ]; then
        echo "ACCOUNT_FORCE_STOP core=$account_label mode=$mode" | tee -a "$W/redroid-status.txt"
        account_failed=1; adb shell am force-stop "$package"
        adb shell monkey -p "$package" -c android.intent.category.LAUNCHER 1 >/dev/null || true
        sleep 5; main_pid=$(adb shell pidof "$package" | tr -d '\r' || true)
      fi
    done
  done
  if [ "${PLAYER_COMPARE_SPACE:-0}" = 1 ]; then
    adb shell run-as "$package" rm files/engines/libkenji.so
    adb shell run-as "$package" cp /data/local/tmp/player-core.so files/engines/libkenji.so
    adb shell run-as "$package" chmod 400 files/engines/libkenji.so
    hash=$(adb exec-out run-as "$package" cat files/engines/libkenji.so | sha256sum | cut -d' ' -f1)
    [ "$hash" = 969431b3962408dd02ce38bd2cd4b2a954d11e5212ddef3f65c80a0b419918b8 ] || { echo 'ACCOUNT_SYM_RESTORE_HASH_MISMATCH'; exit 1; }
  fi
  [ "$account_failed" = 0 ] || { echo 'ACCOUNT_ONE_OR_MORE_CASES_NOT_VERIFIED'; exit 1; }
fi

# Orthogonal guest BSD socket matrix: no TLS, remote IP is loopback only.
# All combinations are attempted even after a guest exception; no failure is
# turned into a green result and each attempt runs in a fresh :kenji process.
if [ "${PLAYER_GUEST_NETWORK:-0}" = 1 ]; then
  net_failure=0
  adb reverse tcp:19444 tcp:19444
  for net_core in sym space; do
    if [ "$net_core" = space ]; then
      [ "${PLAYER_COMPARE_SPACE:-0}" = 1 ] || break
      [ -z "$(adb shell pidof "$package:kenji" | tr -d '\r' || true)" ] || { echo 'NETWORK_CORE_SWAP_WHILE_RUNNING'; exit 1; }
      adb shell run-as "$package" rm files/engines/libkenji.so
      adb shell run-as "$package" cp /data/local/tmp/player-space.so files/engines/libkenji.so
      adb shell run-as "$package" chmod 400 files/engines/libkenji.so
      hash=$(adb exec-out run-as "$package" cat files/engines/libkenji.so | sha256sum | cut -d' ' -f1)
      [ "$hash" = d781048671e4cef1cde2ec15db8fe29b949df9949d83569ceaf776ad12901590 ] || { echo 'NETWORK_SPACE_HASH_MISMATCH'; exit 1; }
      net_label=space
    else net_label=$primary_core; fi
    for internet in false true; do
      for bind_mode in auto bound; do
        name="$net_core-$internet-$bind_mode"
        printf '%s\n' "$bind_mode" > "$W/guest-network-mode.txt"
        adb push "$W/guest-network-mode.txt" /data/local/tmp/player-net-mode.txt >/dev/null
        adb shell chmod 644 /data/local/tmp/player-net-mode.txt
        adb shell run-as "$package" cp /data/local/tmp/player-net-mode.txt files/kenji/sdcard/ci-net-mode.txt
        adb shell rm /data/local/tmp/player-net-mode.txt
        server_log="$W/guest-network-server-$name.txt"
        python3 tests/guest_network_probe/server.py 19444 > "$server_log" 2>&1 &
        guest_server_pid=$!
        for ((n=0;n<15;n++)); do
          grep -q 'LOCAL_NET_READY' "$server_log" && break
          kill -0 "$guest_server_pid" 2>/dev/null || { echo 'GUEST_NET_SERVER_NOT_RUNNING'; exit 1; }
          sleep 1
        done
        grep -q 'LOCAL_NET_READY' "$server_log" || { echo 'GUEST_NET_SERVER_NOT_READY'; exit 1; }
        echo "PLAYER_GUEST_NET_ATTEMPT core=$net_label internet=$internet bind=$bind_mode" | tee -a "$W/redroid-status.txt"
        adb shell am start -W -n "$package/dev.symbiosis.playerruntime.DiagnosticLauncher" \
          --es test_config "internet=$internet" --es test_kind network > "$W/guest-network-launch-$name.txt"
        player_pid=''
        for ((n=0;n<15;n++)); do
          player_pid=$(adb shell pidof "$package:kenji" | tr -d '\r' || true)
          [ -n "$player_pid" ] && break
          sleep 1
        done
        outcome=timeout
        if [ -n "$player_pid" ]; then
          for ((n=0;n<45;n++)); do
            if grep -qE "^[0-9-]+ +[0-9:.]+ +$player_pid +[0-9]+ .*GUEST_NET_EXCHANGE_OK" "$log"; then outcome=guest_exchange; break; fi
            if grep -qE "^[0-9-]+ +[0-9:.]+ +$player_pid +[0-9]+ .*Unhandled exception in guest thread" "$log" || app_crashed; then outcome=crash; break; fi
            if grep -qE "^[0-9-]+ +[0-9:.]+ +$player_pid +[0-9]+ .*GUEST_NET_ERROR" "$log"; then outcome=guest_error; break; fi
            sleep 1
          done
          grep -E "^[0-9-]+ +[0-9:.]+ +$player_pid +[0-9]+ .*(GUEST_NET_|CI_ONLY_RESOLVED_INTERNET|Unhandled exception in guest thread|EnsureNetworkInterfaceBound)" "$log" > "$W/guest-network-guest-$name.txt" || true
          cat "$W/guest-network-guest-$name.txt" >> "$W/redroid-status.txt"
        else outcome=process_missing; fi
        if [ "$outcome" = guest_exchange ]; then
          for ((n=0;n<10;n++)); do grep -q '^LOCAL_NET_EXCHANGE_OK$' "$server_log" && break; sleep 1; done
          if python3 tests/player_runtime_probe/check_guest_network_evidence.py "$log" "$player_pid" "$bind_mode" "$internet" "$server_log" >> "$W/guest-network-guest-$name.txt" 2>&1; then
            outcome=verified_exchange
          else outcome=evidence_mismatch; fi
        fi
        if [ "${PLAYER_REFERENCE_MISSING_INTERFACE:-0}" = 1 ] && [ "$bind_mode" = auto ] && [ "$outcome" = guest_error ]; then
          for ((n=0;n<10;n++)); do
            grep -qE "^[0-9-]+ +[0-9:.]+ +$player_pid +[0-9]+ .*GUEST_NET_DONE" "$log" && break
            sleep 1
          done
          # Ensure the server did not accept a connection on an unintended adapter.
          sleep 1
          if python3 tests/player_runtime_probe/check_guest_network_evidence.py "$log" "$player_pid" invalid-auto "$internet" "$server_log" >> "$W/guest-network-guest-$name.txt" 2>&1; then
            outcome=verified_missing_interface_error
          else outcome=invalid_interface_evidence_mismatch; fi
        fi
        if [ "${PLAYER_REFERENCE_VALID_INTERFACE:-0}:$bind_mode:$outcome" = 1:auto:verified_exchange ]; then
          if grep -Eq "^[0-9-]+ +[0-9:.]+ +$player_pid +[0-9]+ .*CI_EXACT_INTERFACE_BOUND address=127\.0\.0\.1 id=.+" "$log"; then
            echo 'CI_EXACT_INTERFACE_BOUND_CONFIRMED' >> "$W/guest-network-guest-$name.txt"
          else outcome=valid_interface_evidence_missing; fi
        fi
        echo "PLAYER_GUEST_NET_RESULT core=$net_label internet=$internet bind=$bind_mode outcome=$outcome" | tee -a "$W/redroid-status.txt"
        # Disabled Internet may legitimately reject the request, but this
        # diagnostic's green outcome requires independent exchange evidence.
        # An expected policy rejection stays recorded as a non-pass observation.
        case "$outcome" in
          verified_exchange) [ "${PLAYER_REFERENCE_MISSING_INTERFACE:-0}:$bind_mode" != 1:auto ] || net_failure=1 ;;
          verified_missing_interface_error) [ "${PLAYER_REFERENCE_MISSING_INTERFACE:-0}:$bind_mode" = 1:auto ] || net_failure=1 ;;
          *) net_failure=1 ;;
        esac
        kill "$guest_server_pid" 2>/dev/null || true
        wait "$guest_server_pid" 2>/dev/null || true
        guest_server_pid=''
        exit_player || true
        pid=''
        for ((n=0;n<30;n++)); do
          pid=$(adb shell pidof "$package:kenji" | tr -d '\r' || true)
          [ -z "$pid" ] && break; sleep 1
        done
        if [ -n "$pid" ]; then
          echo "GUEST_NET_FORCE_STOP core=$net_label internet=$internet bind=$bind_mode (not a clean exit)" | tee -a "$W/redroid-status.txt"
          net_failure=1
          adb shell am force-stop "$package"
          adb shell monkey -p "$package" -c android.intent.category.LAUNCHER 1 >/dev/null || true
          sleep 5
          main_pid=$(adb shell pidof "$package" | tr -d '\r' || true)
        fi
      done
    done
  done
  adb reverse --remove tcp:19444
  if [ "${PLAYER_COMPARE_SPACE:-0}" = 1 ]; then
    adb shell run-as "$package" rm files/engines/libkenji.so
    adb shell run-as "$package" cp /data/local/tmp/player-core.so files/engines/libkenji.so
    adb shell run-as "$package" chmod 400 files/engines/libkenji.so
    hash=$(adb exec-out run-as "$package" cat files/engines/libkenji.so | sha256sum | cut -d' ' -f1)
    [ "$hash" = 969431b3962408dd02ce38bd2cd4b2a954d11e5212ddef3f65c80a0b419918b8 ] || { echo 'NETWORK_SYM_RESTORE_HASH_MISMATCH'; exit 1; }
  fi
  [ "$net_failure" = 0 ] || { echo 'GUEST_NET_MATRIX_HAS_FAILURES' | tee -a "$W/redroid-status.txt"; exit 1; }
  echo 'GUEST_NET_MATRIX_COMPLETED_WITHOUT_UNEXPECTED_FAILURE' | tee -a "$W/redroid-status.txt"
fi

# Run both pinned cores, including after a guest exception. Explicit bind is
# a diagnostic-only guest operation; it does not repair general interface
# selection. Forced cleanup after a failed case is reported, never a pass.
if [ "${PLAYER_GUEST_TLS:-0}" = 1 ]; then
  guest_failed=0
  tls_bind_mode=bound
  if [ "${PLAYER_GUEST_TLS_AUTO_BIND:-0}" = 1 ]; then tls_bind_mode=auto; fi
  printf '%s\n' "$tls_bind_mode" > "$W/guest-tls-mode.txt"
  adb push "$W/guest-tls-mode.txt" /data/local/tmp/player-tls-mode.txt >/dev/null
  adb shell chmod 644 /data/local/tmp/player-tls-mode.txt
  adb shell run-as "$package" cp /data/local/tmp/player-tls-mode.txt files/kenji/sdcard/ci-tls-mode.txt
  adb shell rm /data/local/tmp/player-tls-mode.txt
  count_evidence=none
  if [ "${PLAYER_SSL_COUNT:-0}" = 1 ]; then
    [ "${PLAYER_REFERENCE_CORE:-0}:${PLAYER_REFERENCE_TLS_PATCH:-0}" = 1:1 ] || { echo 'SSL_COUNT_BAD_RESEARCH_CONFIGURATION'; exit 1; }
    printf 'enabled\n' > "$W/guest-ssl-count-mode.txt"
    adb push "$W/guest-ssl-count-mode.txt" /data/local/tmp/player-ssl-count-mode.txt >/dev/null
    adb shell chmod 644 /data/local/tmp/player-ssl-count-mode.txt
    adb shell run-as "$package" cp /data/local/tmp/player-ssl-count-mode.txt files/kenji/sdcard/ci-ssl-count.txt
    adb shell rm /data/local/tmp/player-ssl-count-mode.txt
    count_evidence=count
  else
    adb shell run-as "$package" rm -f files/kenji/sdcard/ci-ssl-count.txt
  fi
  adb reverse tcp:19443 tcp:19443
  for guest_core in sym space; do
    if [ "$guest_core" = space ]; then
      [ "${PLAYER_COMPARE_SPACE:-0}" = 1 ] || break
      adb shell run-as "$package" rm files/engines/libkenji.so
      adb shell run-as "$package" cp /data/local/tmp/player-space.so files/engines/libkenji.so
      adb shell run-as "$package" chmod 400 files/engines/libkenji.so
      hash=$(adb exec-out run-as "$package" cat files/engines/libkenji.so | sha256sum | cut -d' ' -f1)
      [ "$hash" = d781048671e4cef1cde2ec15db8fe29b949df9949d83569ceaf776ad12901590 ] || { echo 'GUEST_TLS_SPACE_HASH_MISMATCH'; exit 1; }
    fi
    server_log="$W/guest-tls-server-$guest_core.txt"
    python3 tests/guest_tls_probe/server.py "$W/ci-test-cert.pem" "$W/ci-test-key.pem" 19443 13 "$W/ci-pki" > "$server_log" 2>&1 &
    guest_server_pid=$!
    for ((n=0;n<20;n++)); do
      grep -q 'LOCAL_TLS_SERVER_READY' "$server_log" && break
      kill -0 "$guest_server_pid" 2>/dev/null || { echo 'GUEST_TLS_SERVER_NOT_RUNNING'; exit 1; }
      sleep 1
    done
    grep -q 'LOCAL_TLS_SERVER_READY' "$server_log" || { echo 'GUEST_TLS_SERVER_NOT_READY'; exit 1; }
    if [ "$guest_core" = sym ]; then guest_label=$primary_core; else guest_label=$guest_core; fi
    echo "PLAYER_GUEST_TLS_ATTEMPT core=$guest_label internet=true bind=$tls_bind_mode" | tee -a "$W/redroid-status.txt"
    adb shell am start -W -n "$package/dev.symbiosis.playerruntime.DiagnosticLauncher" \
      --es test_config 'internet=true' --es test_kind tls | tee "$W/player-guest-tls-launch-$guest_core.txt"
    player_pid=$(adb shell pidof "$package:kenji" | tr -d '\r' || true)
    [ -n "$player_pid" ] || { echo 'GUEST_TLS_PLAYER_NOT_RUNNING'; exit 1; }
    guest_result=0
    for ((n=0;n<90;n++)); do
      if grep -qE "^[0-9-]+ +[0-9:.]+ +$player_pid +[0-9]+ .*GUEST_TLS_COMPLETE" "$log"; then guest_result=1; break; fi
      if grep -qE "^[0-9-]+ +[0-9:.]+ +$player_pid +[0-9]+ .*(GUEST_TLS_ERROR|Unhandled exception in guest thread)" "$log" || app_crashed; then break; fi
      sleep 1
    done
    grep -E "^[0-9-]+ +[0-9:.]+ +$player_pid +[0-9]+ .*(GUEST_TLS_|GUEST_SSL_COUNT|ServiceSsl .*Stubbed|Unhandled exception in guest thread|System\.Security\.Authentication)" "$log" | tee -a "$W/redroid-status.txt" || true
    if [ "$guest_result" = 1 ]; then
      # Wait for the endpoint to observe all thirteen client connections.
      for ((n=0;n<10;n++)); do
        grep -qE 'LOCAL_TLS_HANDSHAKE_OK 12 |LOCAL_TLS_HANDSHAKE_OR_IO_FAILED 12 ' "$server_log" && break
        sleep 1
      done
      if [ "${PLAYER_REFERENCE_TLS_PATCH:-0}" = 1 ]; then
        python3 tests/player_runtime_probe/check_guest_tls_evidence.py "$log" "$player_pid" "$server_log" "$tls_bind_mode" "$count_evidence" | tee -a "$W/redroid-status.txt" || guest_result=0
      else
        echo 'GUEST_TLS_NO_INSTRUMENTED_POLICY_EVIDENCE'
        guest_result=0
      fi
    fi
    if [ "$guest_result" = 1 ]; then
      echo "PLAYER_GUEST_TLS_LOCAL_SERVICE_OK core=$guest_label" | tee -a "$W/redroid-status.txt"
    else
      echo "PLAYER_GUEST_TLS_NOT_VERIFIED core=$guest_label" | tee -a "$W/redroid-status.txt"
      guest_failed=1
    fi
    cat "$server_log"
    kill "$guest_server_pid" 2>/dev/null || true
    wait "$guest_server_pid" 2>/dev/null || true
    guest_server_pid=''
    # Stop even when the test fails, so the next pinned binary is isolated.
    exit_player || true
    for ((n=0;n<30;n++)); do
      pid=$(adb shell pidof "$package:kenji" | tr -d '\r' || true)
      [ -z "$pid" ] && break; sleep 1
    done
    if [ -n "${pid:-}" ]; then
      echo "GUEST_TLS_FORCE_STOP core=$guest_label (not a clean exit)" | tee -a "$W/redroid-status.txt"
      guest_failed=1
      adb shell am force-stop "$package"
      adb shell monkey -p "$package" -c android.intent.category.LAUNCHER 1 >/dev/null || true
      sleep 5
      main_pid=$(adb shell pidof "$package" | tr -d '\r' || true)
    fi
  done
  adb reverse --remove tcp:19443
  if [ "${PLAYER_COMPARE_SPACE:-0}" = 1 ]; then
    adb shell run-as "$package" rm files/engines/libkenji.so
    adb shell run-as "$package" cp /data/local/tmp/player-core.so files/engines/libkenji.so
    adb shell run-as "$package" chmod 400 files/engines/libkenji.so
    hash=$(adb exec-out run-as "$package" cat files/engines/libkenji.so | sha256sum | cut -d' ' -f1)
    [ "$hash" = 969431b3962408dd02ce38bd2cd4b2a954d11e5212ddef3f65c80a0b419918b8 ] || { echo 'GUEST_TLS_SYM_RESTORE_HASH_MISMATCH'; exit 1; }
  fi
  [ "$guest_failed" = 0 ] || { echo 'GUEST_TLS_ONE_OR_MORE_CORES_NOT_VERIFIED'; exit 1; }
fi

# Two separate guest contexts with different trust stores. Both TCP sockets
# connect and both guests reach a barrier before either TLS attempt begins.
# This does not test concurrent mutation/removal or close during a handshake.
if [ "${PLAYER_TLS_PARALLEL:-0}" = 1 ]; then
  [ "${PLAYER_GUEST_TLS:-0}:${PLAYER_REFERENCE_CORE:-0}:${PLAYER_REFERENCE_TLS_PATCH:-0}:${PLAYER_REFERENCE_NETWORK_PATCH:-0}" = 1:1:1:1 ] || { echo 'PARALLEL_TLS_BAD_RESEARCH_CONFIGURATION'; exit 1; }
  adb reverse tcp:19445 tcp:19445
  server_log="$W/tls-parallel-server.txt"
  python3 tests/guest_tls_parallel_probe/server.py "$W/ci-test-cert.pem" "$W/ci-test-key.pem" 19445 > "$server_log" 2>&1 &
  guest_server_pid=$!
  for ((n=0;n<20;n++)); do
    grep -q 'PARALLEL_SERVER_READY' "$server_log" && break
    kill -0 "$guest_server_pid" 2>/dev/null || { echo 'PARALLEL_SERVER_NOT_RUNNING'; exit 1; }
    sleep 1
  done
  grep -q 'PARALLEL_SERVER_READY' "$server_log" || { echo 'PARALLEL_SERVER_NOT_READY'; exit 1; }
  echo "PLAYER_TLS_PARALLEL_ATTEMPT core=$primary_core" | tee -a "$W/redroid-status.txt"
  adb shell am start -W -n "$package/dev.symbiosis.playerruntime.DiagnosticLauncher" \
    --es test_config 'internet=true' --es test_kind tls-parallel > "$W/tls-parallel-launch.txt"
  player_pid=$(adb shell pidof "$package:kenji" | tr -d '\r' || true)
  [ -n "$player_pid" ] || { echo 'PARALLEL_PLAYER_NOT_RUNNING'; exit 1; }
  outcome=timeout
  for ((n=0;n<90;n++)); do
    if grep -qE "^[0-9-]+ +[0-9:.]+ +$player_pid +[0-9]+ .*TLS_PARALLEL_COMPLETE" "$log"; then outcome=complete; break; fi
    if grep -qE "^[0-9-]+ +[0-9:.]+ +$player_pid +[0-9]+ .*(TLS_PARALLEL_ERROR|Unhandled exception in guest thread)" "$log" || app_crashed; then outcome=guest_error; break; fi
    sleep 1
  done
  grep -E "^[0-9-]+ +[0-9:.]+ +$player_pid +[0-9]+ .*(TLS_PARALLEL_|CI_TLS_STORE_|CI_ONLY_TLS_(POLICY|CUSTOM_CHAIN)|Unhandled exception in guest thread)" "$log" > "$W/tls-parallel-guest.txt" || true
  cat "$W/tls-parallel-guest.txt" >> "$W/redroid-status.txt"
  if [ "$outcome" = complete ]; then
    for ((n=0;n<10;n++)); do grep -q 'PARALLEL_SERVER_DONE' "$server_log" && break; sleep 1; done
    good_sha=$(openssl x509 -in "$W/ci-test-cert.pem" -outform DER | sha256sum | cut -d' ' -f1)
    wrong_sha=$(openssl x509 -in "$W/ci-wrong-ca.pem" -outform DER | sha256sum | cut -d' ' -f1)
    if python3 tests/player_runtime_probe/check_tls_parallel_evidence.py "$log" "$player_pid" "$server_log" "$good_sha" "$wrong_sha" >> "$W/tls-parallel-guest.txt" 2>&1; then outcome=verified; else outcome=evidence_mismatch; fi
  fi
  echo "PLAYER_TLS_PARALLEL_RESULT core=$primary_core outcome=$outcome" | tee -a "$W/redroid-status.txt"
  kill "$guest_server_pid" 2>/dev/null || true
  wait "$guest_server_pid" 2>/dev/null || true
  guest_server_pid=''
  adb reverse --remove tcp:19445
  exit_player || true
  for ((n=0;n<30;n++)); do
    pid=$(adb shell pidof "$package:kenji" | tr -d '\r' || true)
    [ -z "$pid" ] && break; sleep 1
  done
  [ -z "${pid:-}" ] || { echo 'PARALLEL_PROCESS_DID_NOT_EXIT'; exit 1; }
  [ "$outcome" = verified ] || { echo 'PARALLEL_TLS_NOT_VERIFIED'; exit 1; }
fi

# The genuine Activity receives a CI-only broadcast to call its real leave()
# while a real keyboard dialog is visible. The delay applies only to the
# disposable test copy and creates a controlled pending-cancellation race.
lock_probe() {
  local want=$1 before after
  before=$(grep -cE "^[0-9-]+ +[0-9:.]+ +$main_pid +[0-9]+ .*CI_LOCK result=$want pid=$main_pid" "$log" || true)
  adb shell am broadcast -n "$package/dev.symbiosis.playerruntime.LockProbeReceiver" >/dev/null
  for ((n=0;n<10;n++)); do
    after=$(grep -cE "^[0-9-]+ +[0-9:.]+ +$main_pid +[0-9]+ .*CI_LOCK result=$want pid=$main_pid" "$log" || true)
    if [ "$after" -gt "$before" ]; then
      echo "PLAYER_CROSS_PROCESS_LOCK_$want" | tee -a "$W/redroid-status.txt"; return 0
    fi
    sleep 1
  done
  echo "PLAYER_CROSS_PROCESS_LOCK_MISMATCH expected=$want"; return 1
}
for core in sym space; do
  if [ "$core" = space ]; then
    [ "${PLAYER_COMPARE_SPACE:-0}" = 1 ] || break
    [ -z "$(adb shell pidof "$package:kenji" | tr -d '\r' || true)" ] || { echo 'CORE_SWAP_WHILE_RUNNING'; exit 1; }
    adb shell run-as "$package" rm files/engines/libkenji.so
    adb shell run-as "$package" cp /data/local/tmp/player-space.so files/engines/libkenji.so
    adb shell run-as "$package" chmod 400 files/engines/libkenji.so
    hash=$(adb exec-out run-as "$package" cat files/engines/libkenji.so | sha256sum | cut -d' ' -f1)
    [ "$hash" = d781048671e4cef1cde2ec15db8fe29b949df9949d83569ceaf776ad12901590 ] || { echo 'SPACE_CORE_HASH_MISMATCH'; exit 1; }
    echo "PLAYER_TEST_CORE space sha256=$hash" | tee -a "$W/redroid-status.txt"
  else echo "PLAYER_TEST_CORE $primary_core sha256=$primary_hash" | tee -a "$W/redroid-status.txt"; fi
  if [ "$core" = sym ]; then core_label=$primary_core; else core_label=$core; fi
for delay in false true; do
  echo "PLAYER_PENDING_UI_STOP_ATTEMPT core=$core_label delay=$delay"
  adb shell am start -W -n "$package/dev.symbiosis.playerruntime.DiagnosticLauncher" \
    --es test_config 'memory=2' --ez ci_test_stop_probe true --ez ci_delay_cancel "$delay" \
    | tee "$W/player-stop-launch-$delay.txt"
  player_pid=$(adb shell pidof "$package:kenji" | tr -d '\r' || true)
  [ -n "$player_pid" ] || { echo 'STOP_PLAYER_NOT_RUNNING'; exit 1; }
  if [ "${PLAYER_EXPECT_TLS_CANDIDATE:-0}" = 1 ]; then
    wait_log 'CI_ONLY_TLS_OPENSSL_VERSION=0x3050009[0-9a-f] .*libcrypto.so' || { echo "PLAYER_TLS_VERSION_MISSING core=$core_label"; exit 1; }
    wait_log 'CI_ONLY_TLS_IN_PROCESS good=0 verify=0 wrong_host=5 wrong_verify=62' || { echo "PLAYER_TLS_IN_PROCESS_MISSING core=$core_label"; exit 1; }
    echo "PLAYER_TLS_3_5_PROCESS_OK core=$core_label" | tee -a "$W/redroid-status.txt"
  fi
  wait_log 'CI_STOP_RECEIVER_READY' || { echo 'STOP_RECEIVER_MISSING'; exit 1; }
  wait_log 'SWKBD_SHOW_BEGIN' || { echo 'STOP_NO_GUEST_KEYBOARD'; exit 1; }
  wait_log 'CI_UI_PRESENTED type=2' || { echo 'STOP_UI_NOT_PRESENTED'; exit 1; }
  lock_probe busy
  adb shell am broadcast -a dev.symbiosis.CI_STOP_PENDING_UI -p "$package" >/dev/null
  wait_log 'CI_STOP_TRIGGERED' || { echo 'STOP_TRIGGER_NOT_RECEIVED'; exit 1; }
  if [ "$delay" = true ]; then
    wait_log 'CI_CANCEL_DELAY_BEGIN' || { echo 'STOP_DELAY_NOT_OBSERVED'; exit 1; }
    lock_probe busy
  fi
  for ((n=0;n<60;n++)); do
    pid=$(adb shell pidof "$package:kenji" | tr -d '\r' || true)
    [ -z "$pid" ] && break
    app_crashed && break
    sleep 1
  done
  [ -z "${pid:-}" ] || { echo "STOP_PLAYER_STILL_ALIVE delay=$delay"; exit 1; }
  ! app_crashed || { echo "STOP_PLAYER_FATAL delay=$delay"; exit 1; }
  [ "$(adb shell pidof "$package" | tr -d '\r' || true)" = "$main_pid" ] || { echo 'STOP_PARENT_CHANGED'; exit 1; }
  python3 tests/player_runtime_probe/check_stop_order.py "$log" "$player_pid" "$delay" | tee -a "$W/redroid-status.txt"
  lock_probe acquired
  echo "PLAYER_PENDING_UI_STOP_AND_LOCK_OK core=$core_label delay=$delay" | tee -a "$W/redroid-status.txt"
done
done
# In compare mode, keep the hash-pinned SymbEK binary for SDL and shader
# checks below. The previous run already verified these for Symbiosis.
if [ "${PLAYER_COMPARE_SPACE:-0}" = 1 ]; then
  echo 'PLAYER_GRAPHICS_TEST_CORE space' | tee -a "$W/redroid-status.txt"
else
  echo "PLAYER_GRAPHICS_TEST_CORE $primary_core" | tee -a "$W/redroid-status.txt"
fi

# Real player + self-authored modern SDL frame. Callback instrumentation is
# inserted only in the disposable debug tree; logging itself proves no semantics.
echo 'PLAYER_GRAPHICS_ATTEMPT'
# Start from a defined real device rotation. This changes Android settings on
# the disposable container only, not an app/user configuration.
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb shell am start -W -n "$package/dev.symbiosis.playerruntime.DiagnosticLauncher" \
  --es test_config 'memory=2' --es test_kind graphics | tee "$W/player-launch-graphics.txt"
player_pid=$(adb shell pidof "$package:kenji" | tr -d '\r' || true)
[ -n "$player_pid" ] || { echo 'GRAPHICS_PLAYER_NOT_RUNNING'; exit 1; }
[ "$(adb shell pidof "$package" | tr -d '\r' || true)" = "$main_pid" ] || { echo 'GRAPHICS_PARENT_CHANGED'; exit 1; }
wait_log 'SDL_SURFACE_PRESENT_OK frame=0' || { echo 'GRAPHICS_GUEST_NO_FIRST_PRESENT'; exit 1; }
frame_ok=0
for attempt in 1 2 3 4 5; do
  adb exec-out screencap -p > "$W/player-frame-$attempt.png"
  if python3 tests/player_runtime_probe/check_frame.py "$W/player-frame-$attempt.png" | tee -a "$W/redroid-status.txt"; then
    frame_ok=1; break
  fi
  sleep 1
done
[ "$frame_ok" = 1 ] || { echo 'GRAPHICS_BLUE_FRAME_NOT_VISIBLE'; exit 1; }
wait_log 'SDL_SURFACE_PRESENT_OK frame=59' || { echo 'GRAPHICS_GUEST_NO_SECOND_PRESENT'; exit 1; }
wait_log 'SDL_GUEST_PLUS value=0 connected=1' || { echo 'PLUS_GUEST_NOT_CONNECTED'; exit 1; }
press_plus portrait || { echo 'PLUS_PORTRAIT_FAILED'; exit 1; }
# Rotate during a running real guest, not before the player starts. Require a
# landscape screenshot with the guest's coloured surface, and later guest
# presents; do not count portrait screenshots as rotation success.
echo 'PLAYER_ROTATION_REQUEST 90' | tee -a "$W/redroid-status.txt"
adb shell settings put system user_rotation 1
rotated=0
for attempt in $(seq 1 12); do
  adb exec-out screencap -p > "$W/player-frame-rotated-$attempt.png"
  if python3 tests/player_runtime_probe/check_frame.py "$W/player-frame-rotated-$attempt.png" --landscape | tee -a "$W/redroid-status.txt"; then
    rotated=1; break
  fi
  app_crashed && { echo 'GRAPHICS_ROTATION_FATAL'; exit 1; }
  sleep 1
done
[ "$rotated" = 1 ] || { echo 'GRAPHICS_ROTATION_OR_FRAME_FAILED'; exit 1; }
wait_log 'SDL_SURFACE_PRESENT_OK frame=599' || { echo 'GRAPHICS_NO_CONTINUED_PRESENT'; exit 1; }
adb exec-out screencap -p > "$W/player-frame-rotated-late.png"
python3 tests/player_runtime_probe/check_frame.py "$W/player-frame-rotated-late.png" --landscape | tee -a "$W/redroid-status.txt" || { echo 'GRAPHICS_ROTATED_LATE_FRAME_FAILED'; exit 1; }
echo 'GRAPHICS_ROTATED_FRAME_VISIBLE' | tee -a "$W/redroid-status.txt"
press_plus landscape || { echo 'PLUS_LANDSCAPE_FAILED'; exit 1; }
# Exit and the overlay must fit beside the actual system navigation strip.
adb shell uiautomator dump /sdcard/player-ui.xml >/dev/null
adb exec-out cat /sdcard/player-ui.xml > "$W/player-ui.xml"
python3 tests/player_runtime_probe/check_exit_insets.py "$W/player-ui.xml" | tee -a "$W/redroid-status.txt"
! app_crashed || { echo 'GRAPHICS_PLAYER_FATAL'; exit 1; }
# Callback absence is reported, not invented or made a pass requirement.
grep -E "^[0-9-]+ +[0-9:.]+ +$player_pid +[0-9]+ .*CI_ONLY_GRAPHICS" "$log" | tee -a "$W/redroid-status.txt" || true
exit_player || { echo 'GRAPHICS_EXIT_BUTTON_NOT_VISIBLE'; exit 1; }
for ((n=0;n<45;n++)); do
  pid=$(adb shell pidof "$package:kenji" | tr -d '\r' || true)
  [ -z "$pid" ] && break
  sleep 1
done
[ -z "${pid:-}" ] || { echo 'GRAPHICS_PLAYER_EXIT_STILL_ALIVE'; exit 1; }
! app_crashed || { echo 'GRAPHICS_PLAYER_EXIT_FATAL'; exit 1; }
[ "$(adb shell pidof "$package" | tr -d '\r' || true)" = "$main_pid" ] || { echo 'GRAPHICS_PARENT_DIED'; exit 1; }
echo 'GRAPHICS_VISIBLE_FRAME_AND_EXIT_OK' | tee -a "$W/redroid-status.txt"

# Shader-backed GPU draw: the pinned official example supplies authentic
# Maxwell vertex/fragment shaders compiled by devkitPro uam, not SDL's CPU
# framebuffer. Use the same user-data directory for both cache-enabled
# launches, then an explicit cache-disabled launch. Do not delete caches.
for setting in true true false; do
  case_count=$(( ${case_count:-0} + 1 ))
  echo "SHADER_ATTEMPT case=$case_count shaderCache=$setting" | tee -a "$W/redroid-status.txt"
  adb shell run-as "$package" find files/kenji -type f 2>/dev/null | sort > "$W/shader-files-before-$case_count.txt" || true
  adb shell am start -W -n "$package/dev.symbiosis.playerruntime.DiagnosticLauncher" \
    --es test_config "shaderCache=$setting" --es test_kind shader | tee "$W/shader-launch-$case_count.txt"
  player_pid=$(adb shell pidof "$package:kenji" | tr -d '\r' || true)
  [ -n "$player_pid" ] || { echo "SHADER_PLAYER_NOT_RUNNING case=$case_count"; exit 1; }
  [ "$(adb shell pidof "$package" | tr -d '\r' || true)" = "$main_pid" ] || { echo 'SHADER_PARENT_CHANGED'; exit 1; }
  wait_log "CI_ONLY_RESOLVED_SHADER_CACHE value=$setting" || { echo "SHADER_CONFIG_DID_NOT_REACH_BRIDGE case=$case_count"; exit 1; }
  wait_log 'DEKO_CI_ROMFS_OK' || { echo "SHADER_ROMFS_NOT_READY case=$case_count"; exit 1; }
  wait_log 'DEKO_CI_DEVICE_CREATED' || { echo "SHADER_GUEST_DEVICE_NOT_READY case=$case_count"; exit 1; }
  wait_log 'DEKO_CI_SHADER_INITIALIZED' || { echo "SHADER_GUEST_MODULE_NOT_READY case=$case_count"; exit 1; }
  wait_log 'DEKO_CI_FIRST_PRESENT_RETURNED' || { echo "SHADER_GUEST_NO_PRESENT case=$case_count"; exit 1; }
  visible=0
  for attempt in $(seq 1 15); do
    adb exec-out screencap -p > "$W/shader-frame-$case_count-$attempt.png"
    if python3 tests/player_runtime_probe/check_triangle.py "$W/shader-frame-$case_count-$attempt.png" | tee -a "$W/redroid-status.txt"; then
      visible=1; break
    fi
    app_crashed && { echo "SHADER_PLAYER_FATAL case=$case_count"; exit 1; }
    sleep 1
  done
  [ "$visible" = 1 ] || { echo "SHADER_TRIANGLE_NOT_VISIBLE case=$case_count"; exit 1; }
  wait_log 'DEKO_CI_60_PRESENTS_RETURNED' || { echo "SHADER_GUEST_NO_CONTINUED_PRESENT case=$case_count"; exit 1; }
  ! app_crashed || { echo "SHADER_PLAYER_FATAL case=$case_count"; exit 1; }
  exit_player || { echo "SHADER_EXIT_NOT_VISIBLE case=$case_count"; exit 1; }
  for ((n=0;n<45;n++)); do
    pid=$(adb shell pidof "$package:kenji" | tr -d '\r' || true)
    [ -z "$pid" ] && break
    sleep 1
  done
  [ -z "${pid:-}" ] || { echo "SHADER_PLAYER_EXIT_STILL_ALIVE case=$case_count"; exit 1; }
  ! app_crashed || { echo "SHADER_EXIT_FATAL case=$case_count"; exit 1; }
  [ "$(adb shell pidof "$package" | tr -d '\r' || true)" = "$main_pid" ] || { echo 'SHADER_PARENT_DIED'; exit 1; }
  adb shell run-as "$package" find files/kenji -type f 2>/dev/null | sort > "$W/shader-files-after-$case_count.txt" || true
  echo "SHADER_TRIANGLE_AND_EXIT_OK case=$case_count shaderCache=$setting" | tee -a "$W/redroid-status.txt"
done

# After Space graphics, restore production core only when no player is alive.
if [ "${PLAYER_COMPARE_SPACE:-0}" = 1 ]; then
  [ -z "$(adb shell pidof "$package:kenji" | tr -d '\r' || true)" ] || { echo 'CORE_RESTORE_WHILE_RUNNING'; exit 1; }
  adb shell run-as "$package" rm files/engines/libkenji.so
  adb shell run-as "$package" cp /data/local/tmp/player-core.so files/engines/libkenji.so
  adb shell run-as "$package" chmod 400 files/engines/libkenji.so
  hash=$(adb exec-out run-as "$package" cat files/engines/libkenji.so | sha256sum | cut -d' ' -f1)
  [ "$hash" = 969431b3962408dd02ce38bd2cd4b2a954d11e5212ddef3f65c80a0b419918b8 ] || { echo 'SYM_CORE_RESTORE_MISMATCH'; exit 1; }
  echo 'PLAYER_TEST_CORE_SYM_RESTORED' | tee -a "$W/redroid-status.txt"
fi
