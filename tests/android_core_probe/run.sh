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

# A separate, disposable Activity exercises the actual Android presenter and
# coordinator with synthetic requests. It does not call the real ARM cores.
adb shell am force-stop "$APP"
ui_log="$PROBE_WORK/ui-logcat.txt"
adb logcat -T 1 -v time > "$ui_log" 2>&1 &
ui_logpid=$!
adb shell am start -n "$APP/.UiProbeActivity" || true
ui_outcome=timeout
for ((i=0; i<45; i++)); do
  if grep -q 'UI_PROBE_OK' "$ui_log"; then ui_outcome=pass; break; fi
  if grep -qE 'UI_PROBE_FAIL|FATAL EXCEPTION' "$ui_log"; then ui_outcome=fail; break; fi
  sleep 1
done
adb shell am force-stop "$APP" || true
kill "$ui_logpid" 2>/dev/null || true
wait "$ui_logpid" 2>/dev/null || true
printf '\n=== synthetic Android UI outcome: %s ===\n' "$ui_outcome"
grep -E 'SymUiProbe|FATAL EXCEPTION|AndroidRuntime' "$ui_log" | tail -80 || true
if [ "$ui_outcome" != pass ]; then failed=1; fi

for core in sym space; do
  adb shell am force-stop "$APP"
  # These belong only to the disposable probe, not the user's core journal.
  adb shell run-as "$APP" rm -f files/probe-crash.txt files/kenji-stdout.txt files/kenji-jni.txt
  # A fresh process is essential: two NativeAOT cores must not share state.
  log="$PROBE_WORK/$core-logcat.txt"
  adb logcat -T 1 -v time > "$log" 2>&1 &
  logpid=$!
  adb shell am start -n "$APP/.ProbeActivity" --es core "$core" || true
  outcome=timeout
  for ((i=0; i<90; i++)); do
    if grep -q "PROBE_OK core=$core " "$log"; then outcome=pass; break; fi
    if grep -q 'PROBE_FAIL' "$log"; then outcome=fail; break; fi
    if grep -q 'Fatal signal.*iosis.coreprobe' "$log"; then outcome=native-crash; break; fi
    sleep 1
  done
  adb shell am force-stop "$APP" || true
  for name in probe-crash.txt kenji-stdout.txt kenji-jni.txt; do
    adb exec-out run-as "$APP" cat "files/$name" > "$PROBE_WORK/$core-$name" 2>/dev/null || true
    if [ -s "$PROBE_WORK/$core-$name" ]; then
      printf '\n=== %s %s ===\n' "$core" "$name"
      tail -100 "$PROBE_WORK/$core-$name"
    fi
  done
  kill "$logpid" 2>/dev/null || true
  wait "$logpid" 2>/dev/null || true
  printf '\n=== %s outcome: %s ===\n' "$core" "$outcome"
  grep -E 'SymCoreProbe|SymbiosisKenji|RyujinxLog|FATAL EXCEPTION|Fatal signal|linker.*(error|cannot)' "$log" | tail -120 || true
  if [ "$outcome" != pass ]; then failed=1; fi
done
exit "$failed"
