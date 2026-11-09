#!/usr/bin/env bash
# Experimental, unsupported cross-architecture configuration. The actual
# Android Emulator backend and unchanged official Android 15 ARM system image
# run under TCG; no CPU features, Android results, or core responses are faked.
set -Eeuo pipefail
: "${ANDROID_HOME:?}" "${ANDROID_AVD_HOME:?}" "${ANDROID_SERIAL:?}" "${PROBE_WORK:?}"
mkdir -p "$ANDROID_AVD_HOME/probe.avd" "$PROBE_WORK"
cat > "$ANDROID_AVD_HOME/probe.ini" <<EOF
avd.ini.encoding=UTF-8
path=$ANDROID_AVD_HOME/probe.avd
path.rel=avd/probe.avd
target=android-35
EOF
cat > "$ANDROID_AVD_HOME/probe.avd/config.ini" <<'EOF'
AvdId=probe
PlayStore.enabled=false
abi.type=arm64-v8a
hw.cpu.arch=arm64
hw.cpu.ncore=2
hw.ramSize=4096
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
hw.lcd.density=320
hw.lcd.height=1280
hw.lcd.width=720
hw.mainKeys=no
hw.keyboard=yes
hw.sdCard=no
image.sysdir.1=system-images/android-35/google_apis/arm64-v8a/
tag.id=google_apis
tag.display=Google APIs
disk.dataPartition.size=3G
EOF
# The revision 9 image contains data templates, but no userdata.img. A fresh
# formatted raw disk is the disposable guest's data volume, not a release asset.
truncate -s 3G "$ANDROID_AVD_HOME/probe.avd/userdata-qemu.img"
/sbin/mkfs.ext4 -q -F "$ANDROID_AVD_HOME/probe.avd/userdata-qemu.img"
E=${EMULATOR_ROOT:-/tmp/android-emulator-pinned/emulator}
if [ "${EMULATOR_NATIVE_ARM:-0}" = 1 ]; then
  # Separate third-party AOSP build, pinned by SHA; ARM host TCG, not KVM.
  emulator_command=("$E/emulator")
  qemu_args=()
else
  # Official x86 launcher refuses ARM API35; direct backend is unsupported.
  emulator_command=("$E/qemu/linux-x86_64/qemu-system-aarch64-headless")
  qemu_args=(-qemu -machine virt -cpu max)
fi
# Retain actual Android goldfish/qemud and host SwiftShader, not a real phone.
env LD_LIBRARY_PATH="$E/lib64:$E/lib64/gles_swiftshader${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
  "${emulator_command[@]}" -avd probe -port 5560 \
  -no-window -no-audio -no-boot-anim -no-snapshot -no-metrics -show-kernel -accel off \
  -gpu swiftshader_indirect -memory 4096 -cores 2 -verbose \
  "${qemu_args[@]}" > "$PROBE_WORK/emulator.log" 2>&1 &
emu_pid=$!
cleanup() {
  kill "$emu_pid" 2>/dev/null || true
  wait "$emu_pid" 2>/dev/null || true
  timeout 10s adb kill-server >/dev/null 2>&1 || true
}
trap cleanup EXIT
booted=0
early_attempted=0
start=$SECONDS
deadline=$((start + 1200))
while (( SECONDS < deadline )); do
  if ! kill -0 "$emu_pid" 2>/dev/null; then
    echo 'EMULATOR_EXITED_BEFORE_BOOT' | tee "$PROBE_WORK/boot-status.txt"
    tail -120 "$PROBE_WORK/emulator.log"
    exit 1
  fi
  value=$(timeout 8s adb -s "$ANDROID_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
  if [ "$value" = 1 ]; then booted=1; break; fi
  if (( early_attempted == 0 && SECONDS - start >= 300 )) && timeout 10s adb -s "$ANDROID_SERIAL" shell true >/dev/null 2>&1; then
    early_attempted=1
    echo 'ADB_SHELL_READY_BUT_ANDROID_NOT_FULLY_BOOTED: testing standalone only'
    set +e
    timeout 600s python3 tests/standalone_core_probe/run.py "$PROBE_WORK" --preload-tls
    early_rc=$?
    set -e
    printf 'EARLY_STANDALONE_EXIT=%s (ANDROID NOT FULLY BOOTED)\n' "$early_rc" | tee -a "$PROBE_WORK/boot-status.txt"
  fi
  sleep 5
done
printf 'ANDROID_BOOT_COMPLETED=%s\n' "$booted" | tee -a "$PROBE_WORK/boot-status.txt"
if [ "$booted" != 1 ]; then
  timeout 15s adb devices -l >> "$PROBE_WORK/boot-status.txt" 2>&1 || true
  timeout 20s adb -s "$ANDROID_SERIAL" logcat -d -v threadtime > "$PROBE_WORK/boot-logcat.txt" 2>&1 || true
  # An early responsive shell can still support a useful independent loader
  # test, but must never be reported as a fully booted Android system.
  if (( early_attempted == 0 )) && timeout 10s adb -s "$ANDROID_SERIAL" shell true >/dev/null 2>&1; then
    set +e
    timeout 900s python3 tests/standalone_core_probe/run.py "$PROBE_WORK" --preload-tls
    early_rc=$?
    set -e
    printf 'EARLY_STANDALONE_EXIT=%s (ANDROID NOT FULLY BOOTED)\n' "$early_rc" | tee -a "$PROBE_WORK/boot-status.txt"
  fi
  tail -120 "$PROBE_WORK/emulator.log"
  exit 1
fi
adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tee -a "$PROBE_WORK/boot-status.txt"
adb -s "$ANDROID_SERIAL" shell getprop ro.product.cpu.abilist | tee -a "$PROBE_WORK/boot-status.txt"
adb -s "$ANDROID_SERIAL" shell uname -a | tee "$PROBE_WORK/cpuinfo.txt"
adb -s "$ANDROID_SERIAL" shell cat /proc/cpuinfo >> "$PROBE_WORK/cpuinfo.txt"
# Independent JNI first; then separately a disposable Activity harness. The
# real Android bridge tests have no games, firmware, keys, or release APK.
set +e
timeout 900s python3 tests/standalone_core_probe/run.py "$PROBE_WORK" --preload-tls
standalone_rc=$?
if [ "${PROBE_STANDALONE_ONLY:-0}" = 1 ]; then
  activity_rc=NOT_ATTEMPTED
else
  timeout 600s bash tests/android_core_probe/run.sh
  activity_rc=$?
fi
set -e
printf 'STANDALONE_EXIT=%s ACTIVITY_EXIT=%s\n' "$standalone_rc" "$activity_rc" | tee -a "$PROBE_WORK/boot-status.txt"
if [ "$standalone_rc" != 0 ] || { [ "$activity_rc" != 0 ] && [ "$activity_rc" != NOT_ATTEMPTED ]; }; then exit 1; fi
