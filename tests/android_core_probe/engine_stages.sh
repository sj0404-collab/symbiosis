#!/usr/bin/env bash
# Native ARM Android CI only. Real core and bridge, no game, keys or firmware.
set -euo pipefail
: "${PROBE_WORK:?}" "${ANDROID_SERIAL:?}"
APP=dev.symbiosis.coreprobe
limit=${PROBE_WAIT_SECONDS:-180}
[[ "$limit" =~ ^[0-9]+$ ]] && (( limit >= 1 && limit <= 600 )) || exit 1
failed=0
for core in sym space; do
  adb shell am force-stop "$APP"
  adb shell run-as "$APP" rm -f files/engine-crash.txt
  log="$PROBE_WORK/$core-engine-logcat.txt"
  adb logcat -c # disposable CI instance, never the user's device
  adb logcat -T 1 -v time > "$log" 2>&1 & logpid=$!
  adb shell am start -n "$APP/.EngineStageActivity" --es core "$core" || true
  outcome=timeout
  for ((i=0; i<limit; i++)); do
    if grep -qE 'ENGINE_FAIL|FATAL EXCEPTION|Fatal signal|ActivityManager.*Process dev\.symbiosis\.coreprobe \(pid [0-9]+\) has died' "$log"; then outcome=fail; break; fi
    starts=$(grep -c "ENGINE_STAGE core=$core START " "$log" || true)
    if [ "$starts" -gt 1 ]; then outcome=process-restarted; break; fi
    if grep -q "ENGINE_STAGES_OK core=$core " "$log"; then
      sleep 4
      if grep -qE 'ENGINE_FAIL|FATAL EXCEPTION|Fatal signal|ActivityManager.*Process dev\.symbiosis\.coreprobe \(pid [0-9]+\) has died' "$log"; then outcome=fail; else outcome=pass; fi
      break
    fi
    sleep 1
  done
  adb exec-out run-as "$APP" cat files/engine-crash.txt > "$PROBE_WORK/$core-engine-probe-crash.txt" 2>/dev/null || true
  adb shell am force-stop "$APP" || true
  kill "$logpid" 2>/dev/null || true
  wait "$logpid" 2>/dev/null || true
  echo "=== real engine stages $core outcome=$outcome (no game, firmware or frames) ==="
  grep -E 'SymEngineStage|SymbiosisKenji|RyujinxLog|FATAL EXCEPTION|Fatal signal|ActivityManager.*Process dev\.symbiosis\.coreprobe' "$log" | tail -120 || true
  tail -40 "$PROBE_WORK/$core-engine-probe-crash.txt" || true
  [ "$outcome" = pass ] || failed=1
done
exit "$failed"
