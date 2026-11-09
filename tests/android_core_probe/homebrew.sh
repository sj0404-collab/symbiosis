#!/usr/bin/env bash
# CI only: test a public open-source homebrew NRO, not a commercial game.
set -euo pipefail
: "${PROBE_WORK:?}" "${ANDROID_SERIAL:?}"
APP=dev.symbiosis.coreprobe
nro="$PROBE_WORK/test-homebrew.nro"
curl -fsSL --retry 3 'https://github.com/vgmoose/sdl-hello-world/releases/download/1.1/sdl-hello.nro' -o "$nro"
echo '2c591bf8030f38567d8a1076b97203a98003f4faf2fbd9fbf7b1ced115f120b9  '"$nro" | sha256sum -c -
adb push "$nro" /data/local/tmp/symbiosis-ci-homebrew.nro >/dev/null
adb shell "cat /data/local/tmp/symbiosis-ci-homebrew.nro | run-as $APP sh -c 'cat > files/homebrew.nro'"
adb shell rm /data/local/tmp/symbiosis-ci-homebrew.nro
failed=0
for core in sym space; do
  adb shell am force-stop "$APP"
  adb shell run-as "$APP" rm -f files/engine-crash.txt
  log="$PROBE_WORK/$core-homebrew-logcat.txt"
  adb logcat -c # disposable Android only
  adb logcat -T 1 -v time > "$log" 2>&1 & logpid=$!
  adb shell am start -n "$APP/.EngineStageActivity" --es core "$core" --ez homebrew true || true
  outcome=timeout
  for ((i=0; i<120; i++)); do
    if grep -qE 'ENGINE_FAIL|FATAL EXCEPTION|Fatal signal|ActivityManager.*Process dev\.symbiosis\.coreprobe \(pid [0-9]+\) has died' "$log"; then outcome=fail; break; fi
    starts=$(grep -c "ENGINE_STAGE core=$core START " "$log" || true)
    if [ "$starts" -gt 1 ]; then outcome=process-restarted; break; fi
    if grep -q "HOMEBREW_FRAME_CALLBACK core=$core " "$log"; then
      sleep 4
      if grep -qE 'ENGINE_FAIL|FATAL EXCEPTION|Fatal signal|ActivityManager.*Process dev\.symbiosis\.coreprobe \(pid [0-9]+\) has died' "$log"; then outcome=fail; else outcome=frame-callback-observed; fi
      break
    fi
    sleep 1
  done
  adb exec-out run-as "$APP" cat files/engine-crash.txt > "$PROBE_WORK/$core-homebrew-probe-crash.txt" 2>/dev/null || true
  # Screenshot is diagnostic evidence, not bundled in APK; retained only if a
  # frame callback was observed. A callback alone is not proof of image content.
  if [ "$outcome" = frame-callback-observed ]; then
    adb exec-out screencap -p > "$PROBE_WORK/$core-homebrew-screen.png" || true
  fi
  adb shell am force-stop "$APP" || true
  kill "$logpid" 2>/dev/null || true; wait "$logpid" 2>/dev/null || true
  echo "=== open-source NRO $core outcome=$outcome (no firmware or keys) ==="
  grep -E 'SymEngineStage|SymbiosisKenji|RyujinxLog|FATAL EXCEPTION|Fatal signal|ActivityManager.*Process dev\.symbiosis\.coreprobe' "$log" | tail -180 || true
  tail -60 "$PROBE_WORK/$core-homebrew-probe-crash.txt" || true
  [ "$outcome" = frame-callback-observed ] || failed=1
done
exit "$failed"
