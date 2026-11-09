#!/usr/bin/env bash
# CI only: test a public open-source homebrew NRO, not a commercial game.
set -euo pipefail
: "${PROBE_WORK:?}" "${ANDROID_SERIAL:?}"
APP=dev.symbiosis.coreprobe
sample=${HOMEBREW_SAMPLE:-sdl}
case "$sample" in
 sdl) url='https://github.com/vgmoose/sdl-hello-world/releases/download/1.1/sdl-hello.nro'; sha=2c591bf8030f38567d8a1076b97203a98003f4faf2fbd9fbf7b1ced115f120b9 ;;
 snake) url='https://github.com/CompSciOrBust/Cpp-Snake-NX/releases/download/1.1.0/Cpp-Snake-NX.nro'; sha=982ff14c538bb94eb6d18411efe71e885006cf3e944da270d18cd2fa88442f16 ;;
 matrix) url=; sha= ;;
 *) echo 'Unknown pinned NRO sample' >&2; exit 1 ;;
esac
if [ "$sample" = matrix ]; then
  # Built from this repository in the separate isolated CI build job.
  nro="$PROBE_WORK/hid-matrix.nro"
  test -s "$nro"; sha256sum "$nro"
else
  nro="$PROBE_WORK/test-homebrew.nro"
  curl -fsSL --retry 3 "$url" -o "$nro"
  printf '%s  %s\n' "$sha" "$nro" | sha256sum -c -
fi
adb push "$nro" /data/local/tmp/symbiosis-ci-homebrew.nro >/dev/null
adb shell "cat /data/local/tmp/symbiosis-ci-homebrew.nro | run-as $APP sh -c 'cat > files/homebrew.nro'"
adb shell rm /data/local/tmp/symbiosis-ci-homebrew.nro
failed=0
for core in sym space; do
  adb shell am force-stop "$APP"
  adb shell run-as "$APP" rm -f files/engine-crash.txt
  log="$PROBE_WORK/$core-$sample-homebrew-logcat.txt"
  adb logcat -c # disposable Android only
  # The NRO's busy game-over loop can produce >100 MB of verbose guest IPC
  # logs in seconds. Keep lifecycle, error and explicit probe markers instead.
  adb logcat -T 1 -v time 'SymEngineStage:V' 'SymbiosisKenji:V' \
    'RyujinxLog:I' 'AndroidRuntime:E' 'libc:F' 'ActivityManager:I' '*:S' > "$log" 2>&1 & logpid=$!
  input_probe=false
  [ "$sample" = snake ] && input_probe=true
  adb shell am start -n "$APP/.EngineStageActivity" --es core "$core" --ez homebrew true --ez input_probe "$input_probe" || true
  outcome=timeout
  for ((i=0; i<120; i++)); do
    if grep -qE 'ENGINE_FAIL|FATAL EXCEPTION|Fatal signal|ActivityManager.*Process dev\.symbiosis\.coreprobe \(pid [0-9]+\) has died' "$log"; then outcome=fail; break; fi
    if (( i % 3 == 0 )) && timeout 5s adb exec-out run-as "$APP" cat files/engine-crash.txt 2>/dev/null | grep '^crash signal=' >/dev/null; then outcome=native-crash; break; fi
    starts=$(grep -c "ENGINE_STAGE core=$core START " "$log" || true)
    if [ "$starts" -gt 1 ]; then outcome=process-restarted; break; fi
    if grep -q "HOMEBREW_FRAME_CALLBACK core=$core " "$log"; then
      if [ "$sample" = matrix ]; then
        for ((j=0; j<20; j++)); do
          grep -q 'HID_MATRIX_COMPLETE count=12' "$log" && break
          sleep 1
        done
        if ! grep -q 'HID_MATRIX_COMPLETE count=12' "$log"; then outcome=matrix-incomplete; break; fi
        if [ "$(grep -oE 'HID_MATRIX index=[0-9]+' "$log" | sort -u | wc -l)" -ne 12 ]; then
          outcome=matrix-incomplete; break
        fi
      fi
      if [ "$input_probe" = true ]; then
        # Before/after images only; input delivery needs independent observation.
        timeout 10s adb exec-out screencap -p > "$PROBE_WORK/$core-$sample-before-screen.png" || true
        for ((j=0; j<15; j++)); do
          grep -q "HOMEBREW_INPUT_SENT core=$core " "$log" && break
          sleep 1
        done
        if ! grep -q "HOMEBREW_INPUT_SENT core=$core " "$log"; then outcome=input-not-sent; break; fi
      fi
      sleep 4
      if grep -qE 'ENGINE_FAIL|FATAL EXCEPTION|Fatal signal|ActivityManager.*Process dev\.symbiosis\.coreprobe \(pid [0-9]+\) has died' "$log"; then
        outcome=fail
      elif timeout 5s adb exec-out run-as "$APP" cat files/engine-crash.txt 2>/dev/null | grep '^crash signal=' >/dev/null; then
        outcome=native-crash
      else outcome=frame-callback-observed; fi
      break
    fi
    sleep 1
  done
  adb exec-out run-as "$APP" cat files/engine-crash.txt > "$PROBE_WORK/$core-$sample-homebrew-probe-crash.txt" 2>/dev/null || true
  # Retain a screenshot even on an inconclusive input result if frames were
  # observed. It is evidence to inspect, not an automatic pass.
  if grep -q "HOMEBREW_FRAME_CALLBACK core=$core " "$log"; then
    timeout 10s adb exec-out screencap -p > "$PROBE_WORK/$core-$sample-homebrew-screen.png" || true
  fi
  adb shell am force-stop "$APP" || true
  kill "$logpid" 2>/dev/null || true; wait "$logpid" 2>/dev/null || true
  echo "=== open-source NRO $sample $core outcome=$outcome (no firmware or keys) ==="
  grep -E 'SymEngineStage|SymbiosisKenji|RyujinxLog|FATAL EXCEPTION|Fatal signal|ActivityManager.*Process dev\.symbiosis\.coreprobe' "$log" | tail -180 || true
  tail -60 "$PROBE_WORK/$core-$sample-homebrew-probe-crash.txt" || true
  [ "$outcome" = frame-callback-observed ] || failed=1
done
exit "$failed"
