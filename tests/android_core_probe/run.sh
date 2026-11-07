#!/usr/bin/env bash
# Runs only in the disposable CI Android emulator. Never reads user data.
set -euo pipefail
: "${PROBE_WORK:?}"
APP=dev.symbiosis.coreprobe
adb wait-for-device
printf 'Android properties (environment, not proof of ARM support):\n'
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abilist
adb shell getprop ro.dalvik.vm.native.bridge
adb install -r "$PROBE_WORK/core-probe.apk"
failed=0
for core in sym space; do
  adb shell am force-stop "$APP"
  # A fresh process is essential: two NativeAOT cores must not share state.
  log="$PROBE_WORK/$core-logcat.txt"
  adb logcat -T 1 -v time > "$log" 2>&1 &
  logpid=$!
  adb shell am start -W -n "$APP/.ProbeActivity" --es core "$core" || true
  outcome=timeout
  for ((i=0; i<90; i++)); do
    if grep -q "PROBE_OK core=$core " "$log"; then outcome=pass; break; fi
    if grep -q 'PROBE_FAIL' "$log"; then outcome=fail; break; fi
    sleep 1
  done
  adb shell am force-stop "$APP" || true
  kill "$logpid" 2>/dev/null || true
  wait "$logpid" 2>/dev/null || true
  printf '\n=== %s outcome: %s ===\n' "$core" "$outcome"
  grep -E 'SymCoreProbe|SymbiosisKenji|RyujinxLog|FATAL EXCEPTION|Fatal signal|linker.*(error|cannot)' "$log" | tail -120 || true
  if [ "$outcome" != pass ]; then failed=1; fi
done
exit "$failed"
