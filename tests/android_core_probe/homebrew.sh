#!/usr/bin/env bash
# CI only: test a public open-source homebrew NRO, not a commercial game.
set -euo pipefail
: "${PROBE_WORK:?}" "${ANDROID_SERIAL:?}"
APP=dev.symbiosis.coreprobe
sample=${HOMEBREW_SAMPLE:-sdl}
case "$sample" in
 sdl) url='https://github.com/vgmoose/sdl-hello-world/releases/download/1.1/sdl-hello.nro'; sha=2c591bf8030f38567d8a1076b97203a98003f4faf2fbd9fbf7b1ced115f120b9 ;;
 snake) url='https://github.com/CompSciOrBust/Cpp-Snake-NX/releases/download/1.1.0/Cpp-Snake-NX.nro'; sha=982ff14c538bb94eb6d18411efe71e885006cf3e944da270d18cd2fa88442f16 ;;
 matrix|sdl-surface|swkbd|swkbd-response|swkbd-ui|swkbd-stop|swkbd-stop-keep|swkbd-stop-hold|swkbd-stop-keep-release|swkbd-exit|swkbd-overlap|swkbd-message|swkbd-message-stop|swkbd-exit-race) url=; sha= ;;
 *) echo 'Unknown pinned NRO sample' >&2; exit 1 ;;
esac
if [ "$sample" = matrix ] || [ "$sample" = sdl-surface ] || [ "$sample" = swkbd ] || [ "$sample" = swkbd-response ] || [ "$sample" = swkbd-ui ] || [ "$sample" = swkbd-stop ] || [ "$sample" = swkbd-stop-keep ] || [ "$sample" = swkbd-stop-hold ] || [ "$sample" = swkbd-stop-keep-release ] || [ "$sample" = swkbd-exit ] || [ "$sample" = swkbd-overlap ] || [ "$sample" = swkbd-message ] || [ "$sample" = swkbd-message-stop ] || [ "$sample" = swkbd-exit-race ]; then
  # Built from this repository in the separate isolated CI build job.
  case "$sample" in
    matrix) nro="$PROBE_WORK/hid-matrix.nro" ;;
    sdl-surface) nro="$PROBE_WORK/sdl-surface.nro" ;;
    swkbd|swkbd-response|swkbd-ui|swkbd-stop|swkbd-stop-keep|swkbd-stop-hold|swkbd-stop-keep-release|swkbd-exit|swkbd-exit-race) nro="$PROBE_WORK/swkbd.nro" ;;
    swkbd-overlap) nro="$PROBE_WORK/swkbd-overlap.nro" ;;
    swkbd-message|swkbd-message-stop) nro="$PROBE_WORK/swkbd-message.nro" ;;
  esac
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
  adb shell run-as "$APP" rm -f files/engine-crash.txt files/input-stage.txt files/stop-request.txt files/teardown-stage.txt files/exit-request.txt files/lock-probe.txt
  log="$PROBE_WORK/$core-$sample-homebrew-logcat.txt"
  adb logcat -c # disposable Android only
  # The NRO's busy game-over loop can produce >100 MB of verbose guest IPC
  # logs in seconds. Keep lifecycle, error and explicit probe markers instead.
  adb logcat -T 1 -v time 'SymEngineStage:V' 'SymbiosisKenji:V' \
    'RyujinxLog:I' 'AndroidRuntime:E' 'libc:F' 'ActivityManager:I' '*:S' > "$log" 2>&1 & logpid=$!
  input_probe=false
  [ "$sample" = snake ] || [ "$sample" = matrix ] && input_probe=true
  ui_setup=false
  ui_response=false
  ui_real=false
  ui_stop=false
  stop_probe=false
  keep_core=false
  keep_window=false
  process_exit=false
  race_stop=false
  ui_overlap=false
  activity=.EngineStageActivity
  process_name="$APP"
  if [ "$sample" = swkbd ] || [ "$sample" = swkbd-response ] || [ "$sample" = swkbd-ui ] || [ "$sample" = swkbd-stop ] || [ "$sample" = swkbd-stop-keep ] || [ "$sample" = swkbd-stop-hold ] || [ "$sample" = swkbd-stop-keep-release ] || [ "$sample" = swkbd-exit ] || [ "$sample" = swkbd-overlap ] || [ "$sample" = swkbd-message ] || [ "$sample" = swkbd-message-stop ] || [ "$sample" = swkbd-exit-race ]; then ui_setup=true; fi
  if [ "$sample" = swkbd-response ] || [ "$sample" = swkbd-ui ] || [ "$sample" = swkbd-stop ] || [ "$sample" = swkbd-stop-keep ] || [ "$sample" = swkbd-stop-hold ] || [ "$sample" = swkbd-stop-keep-release ] || [ "$sample" = swkbd-exit ] || [ "$sample" = swkbd-overlap ] || [ "$sample" = swkbd-message ] || [ "$sample" = swkbd-message-stop ] || [ "$sample" = swkbd-exit-race ]; then ui_response=true; fi
  if [ "$sample" = swkbd-ui ] || [ "$sample" = swkbd-stop ] || [ "$sample" = swkbd-stop-keep ] || [ "$sample" = swkbd-stop-hold ] || [ "$sample" = swkbd-stop-keep-release ] || [ "$sample" = swkbd-exit ] || [ "$sample" = swkbd-overlap ] || [ "$sample" = swkbd-message ] || [ "$sample" = swkbd-message-stop ] || [ "$sample" = swkbd-exit-race ]; then ui_real=true; fi
  if [ "$sample" = swkbd-stop ] || [ "$sample" = swkbd-stop-keep ] || [ "$sample" = swkbd-stop-hold ] || [ "$sample" = swkbd-stop-keep-release ] || [ "$sample" = swkbd-exit ] || [ "$sample" = swkbd-exit-race ]; then ui_stop=true; stop_probe=true; fi
  [ "$sample" = swkbd-message-stop ] && ui_stop=true
  if [ "$sample" = swkbd-stop-keep ] || [ "$sample" = swkbd-stop-keep-release ] || [ "$sample" = swkbd-exit ] || [ "$sample" = swkbd-exit-race ]; then keep_core=true; fi
  if [ "$sample" = swkbd-stop-keep ] || [ "$sample" = swkbd-stop-hold ] || [ "$sample" = swkbd-exit ] || [ "$sample" = swkbd-exit-race ]; then keep_window=true; fi
  if [ "$sample" = swkbd-overlap ] || [ "$sample" = swkbd-message ] || [ "$sample" = swkbd-message-stop ]; then ui_overlap=true; fi
  if [ "$sample" = swkbd-exit ] || [ "$sample" = swkbd-exit-race ]; then process_exit=true; activity=.IsolatedEngineStageActivity; process_name="$APP:kenji"; fi
  [ "$sample" = swkbd-exit-race ] && race_stop=true
  adb shell am start -n "$APP/$activity" --es core "$core" --ez homebrew true --ez input_probe "$input_probe" --ez ui_setup "$ui_setup" --ez ui_response "$ui_response" --ez ui_real "$ui_real" --ez ui_stop "$ui_stop" --ez stop_probe "$stop_probe" --ez keep_core "$keep_core" --ez keep_window "$keep_window" --ez process_exit "$process_exit" --ez ui_overlap "$ui_overlap" --ez race_stop "$race_stop" || true
  outcome=timeout
  for ((i=0; i<120; i++)); do
    if grep -qE 'ENGINE_FAIL|SDL_SURFACE_FAIL|Unhandled exception in guest thread|FATAL EXCEPTION|Fatal signal|ActivityManager.*Process dev\.symbiosis\.coreprobe \(pid [0-9]+\) has died' "$log"; then outcome=fail; break; fi
    if (( i % 3 == 0 )) && timeout 5s adb exec-out run-as "$APP" cat files/engine-crash.txt 2>/dev/null | grep '^crash signal=' >/dev/null; then outcome=native-crash; break; fi
    starts=$(grep -c "ENGINE_STAGE core=$core START " "$log" || true)
    if [ "$starts" -gt 1 ]; then outcome=process-restarted; break; fi
    if grep -q "HOMEBREW_FRAME_CALLBACK core=$core " "$log"; then
      if [ "$sample" = matrix ]; then
        for ((j=0; j<20; j++)); do
          grep -q 'HID_MATRIX_COMPLETE count=35' "$log" && break
          sleep 1
        done
        if ! grep -q 'HID_MATRIX_COMPLETE count=35' "$log"; then outcome=matrix-incomplete; break; fi
        if [ "$(grep -oE 'HID_MATRIX index=[0-9]+' "$log" | sort -u | wc -l)" -ne 35 ]; then
          outcome=matrix-incomplete; break
        fi
      fi
      if [ "$sample" = swkbd-message-stop ]; then
        for ((j=0; j<40; j++)); do
          grep -q 'MIX_BOTH_DONE' "$log" && break
          grep -qE 'MIX_SETUP_FAILED|UI_REAL_FAIL|ENGINE_FAIL|Fatal signal|FATAL EXCEPTION' "$log" && break
          sleep 1
        done
        if grep -q 'MIX_BOTH_DONE' "$log" &&
           grep -q 'MIX_MESSAGE_RETURN rc=0x00000000' "$log" &&
           grep -q 'MIX_KEYBOARD_RETURN rc=0x00005d59 text=' "$log" &&
           grep -q 'UI_OVERLAP_STOP callbacks=2' "$log" &&
           [ "$(grep -c 'UI_REAL_RESPONSE accepted=false result=true' "$log" || true)" = 1 ] &&
           ! grep -qE 'UI_REAL_RESPONSE accepted=true|UI_REAL_FAIL|ENGINE_FAIL|Fatal signal|FATAL EXCEPTION' "$log"; then
          outcome=frame-callback-observed
        else outcome=mixed-cancel-incomplete; fi
        break
      fi
      if [ "$sample" = swkbd-message ]; then
        for ((j=0; j<40; j++)); do
          grep -q 'MIX_BOTH_DONE' "$log" && break
          grep -qE 'MIX_SETUP_FAILED|UI_REAL_FAIL|ENGINE_FAIL|Fatal signal|FATAL EXCEPTION' "$log" && break
          sleep 1
        done
        if grep -q 'MIX_BOTH_DONE' "$log" &&
           grep -q 'MIX_KEYBOARD_RETURN rc=0x00000000 text=AnswerB' "$log" &&
           grep -q 'MIX_MESSAGE_RETURN rc=0x00000000' "$log" &&
           [ "$(grep -c 'UI_OVERLAP_CALLBACK' "$log" || true)" = 2 ] &&
           [ "$(grep -c 'UI_OVERLAP_CLICK' "$log" || true)" = 2 ] &&
           [ "$(grep -c 'UI_REAL_RESPONSE accepted=true result=true' "$log" || true)" = 1 ] &&
           ! grep -qE 'UI_REAL_FAIL|ENGINE_FAIL|Fatal signal|FATAL EXCEPTION' "$log"; then
          if grep -m1 'UI_OVERLAP_CLICK ' "$log" | grep -q 'seed=MESSAGE callbacks=2'; then outcome=frame-callback-observed
          else outcome=mixed-serialized; fi
        elif grep -q 'MIX_BOTH_DONE' "$log"; then outcome=mixed-wrong-response
        else outcome=mixed-incomplete; fi
        break
      fi
      if [ "$sample" = swkbd-overlap ]; then
        for ((j=0; j<40; j++)); do
          grep -q 'OVERLAP_BOTH_DONE' "$log" && break
          grep -qE 'OVERLAP_SETUP_FAILED|UI_REAL_FAIL|ENGINE_FAIL|Fatal signal|FATAL EXCEPTION' "$log" && break
          sleep 1
        done
        if grep -q 'OVERLAP_BOTH_DONE' "$log" &&
           grep -q 'OVERLAP_SHOW_RETURN id=0 rc=0x00000000 text=AnswerA' "$log" &&
           grep -q 'OVERLAP_SHOW_RETURN id=1 rc=0x00000000 text=AnswerB' "$log" &&
           [ "$(grep -c 'UI_OVERLAP_CALLBACK' "$log" || true)" = 2 ] &&
           [ "$(grep -c 'UI_OVERLAP_CLICK' "$log" || true)" = 2 ] &&
           ! grep -qE 'UI_REAL_FAIL|ENGINE_FAIL|Fatal signal|FATAL EXCEPTION' "$log"; then
          if grep -m1 'UI_OVERLAP_CLICK ' "$log" | grep -q 'callbacks=2'; then outcome=frame-callback-observed
          else outcome=overlap-serialized; fi
        elif grep -q 'OVERLAP_BOTH_DONE' "$log"; then outcome=overlap-wrong-response
        else outcome=overlap-incomplete; fi
        break
      fi
      if [ "$sample" = swkbd ] || [ "$sample" = swkbd-response ] || [ "$sample" = swkbd-ui ] || [ "$sample" = swkbd-stop ] || [ "$sample" = swkbd-stop-keep ] || [ "$sample" = swkbd-stop-hold ] || [ "$sample" = swkbd-stop-keep-release ] || [ "$sample" = swkbd-exit ] || [ "$sample" = swkbd-exit-race ]; then
        # swkbd: unchanged production callback; swkbd-response: explicitly
        # separate test-only callback sends a real JNI response.
        for ((j=0; j<25; j++)); do
          grep -q 'SWKBD_GUEST_DONE' "$log" && break
          [ "$race_stop" = true ] && grep -q 'TEARDOWN_COMPLETE core=' "$log" && break
          grep -qE 'ENGINE_FAIL|Unhandled exception in guest thread|Fatal signal' "$log" && break
          sleep 1
        done
        if { [ "$sample" = swkbd-stop ] || [ "$sample" = swkbd-stop-keep ] || [ "$sample" = swkbd-stop-hold ] || [ "$sample" = swkbd-stop-keep-release ] || [ "$sample" = swkbd-exit ] || [ "$sample" = swkbd-exit-race ]; } && grep -q 'UI_REAL_STOP_REQUESTED' "$log" &&
           grep -q 'UI_REAL_RESPONSE accepted=false result=true' "$log" &&
           { [ "$race_stop" = true ] || { grep -q 'SWKBD_SHOW_RETURN rc=0x00005d59 ' "$log" && grep -q 'SWKBD_GUEST_DONE' "$log"; }; }; then
          observed_pid=$(adb shell pidof "$process_name" | tr -d '\r' || true)
          if [ "$race_stop" != true ]; then adb shell run-as "$APP" touch files/stop-request.txt; fi
          teardown_stage=''
          for ((j=0; j<45; j++)); do
            teardown_stage=$(timeout 5s adb exec-out run-as "$APP" cat files/teardown-stage.txt 2>/dev/null | tr -d '\r' || true)
            printf '%s\n' "$teardown_stage" | grep -q '^TEARDOWN_COMPLETE$' && break
            current_pid=$(adb shell pidof "$process_name" | tr -d '\r' || true)
            [ -n "$observed_pid" ] && [ "$current_pid" != "$observed_pid" ] && break
            grep -qE 'ENGINE_FAIL|Fatal signal|FATAL EXCEPTION' "$log" && break
            sleep 1
          done
          printf 'TEARDOWN_DURABLE core=%s pid=%s stages=\n%s\n' "$core" "$observed_pid" "$teardown_stage" | tee "$PROBE_WORK/$core-$sample-teardown-stage.txt"
          if printf '%s\n' "$teardown_stage" | grep -q '^TEARDOWN_COMPLETE$' && [ -n "$observed_pid" ]; then
            if [ "$process_exit" = true ]; then
              # Main process contends with the same real OS lease before and after
              # intentional death of :kenji; the parent must survive unchanged.
              adb shell am start -n "$APP/.LockProbeActivity" >/dev/null
              before=''
              for ((j=0; j<15; j++)); do
                before=$(adb shell run-as "$APP" cat files/lock-probe.txt 2>/dev/null | tr -d '\r' || true)
                [ -n "$before" ] && break; sleep 1
              done
              parent_pid=$(adb shell pidof "$APP" | tr -d '\r' || true)
              adb shell run-as "$APP" touch files/exit-request.txt
              current_pid="$observed_pid"
              for ((j=0; j<20; j++)); do
                current_pid=$(adb shell pidof "$process_name" | tr -d '\r' || true)
                [ "$current_pid" != "$observed_pid" ] && break; sleep 1
              done
              teardown_stage=$(adb shell run-as "$APP" cat files/teardown-stage.txt 2>/dev/null | tr -d '\r' || true)
              adb shell run-as "$APP" rm -f files/lock-probe.txt
              adb shell am start -n "$APP/.LockProbeActivity" >/dev/null
              after=''
              for ((j=0; j<15; j++)); do
                after=$(adb shell run-as "$APP" cat files/lock-probe.txt 2>/dev/null | tr -d '\r' || true)
                [ -n "$after" ] && break; sleep 1
              done
              parent_after=$(adb shell pidof "$APP" | tr -d '\r' || true)
              printf 'ISOLATED_EXIT core=%s lock_before=%s lock_after=%s parent=%s parent_after=%s child=%s child_after=%s\n' "$core" "$before" "$after" "$parent_pid" "$parent_after" "$observed_pid" "$current_pid" | tee "$PROBE_WORK/$core-isolated-exit.txt"
              if { [ "$race_stop" != true ] || { printf '%s\n' "$teardown_stage" | grep -q '^TEARDOWN_RACING_UI_REPLY$' && printf '%s\n' "$teardown_stage" | grep -q '^TEARDOWN_POST_CLOSE_CALLBACKS_0$' && ! grep -q 'UI_CALLBACK_AFTER_CLOSE' "$log"; }; } && [ "$before" = busy ] && [ "$after" = acquired ] && [ -n "$parent_pid" ] && [ "$parent_pid" = "$parent_after" ] && [ -z "$current_pid" ] && printf '%s\n' "$teardown_stage" | grep -q '^TEARDOWN_PROCESS_EXIT_REQUESTED$'; then
                outcome=frame-callback-observed
              else outcome=isolated-exit-incomplete; fi
            else
              sleep 15
              current_pid=$(adb shell pidof "$process_name" | tr -d '\r' || true)
              if [ "$current_pid" = "$observed_pid" ] && ! grep -qE 'ENGINE_FAIL|Fatal signal|FATAL EXCEPTION|Process dev\.symbiosis\.coreprobe .*has died' "$log"; then
                outcome=frame-callback-observed
              else outcome=teardown-process-died; fi
            fi
          else outcome=teardown-incomplete; fi
        elif [ "$sample" = swkbd-response ] &&
           grep -q 'UI_TEST_CALLBACK ' "$log" &&
           grep -q 'UI_TEST_RESPONSE_RETURN result=true' "$log" &&
           grep -q 'SWKBD_SHOW_RETURN rc=0x00000000 module=0 description=0 text=CI_Arena' "$log" &&
           grep -q 'SWKBD_GUEST_DONE' "$log"; then
          outcome=frame-callback-observed
        elif [ "$sample" = swkbd-ui ] &&
           grep -q 'UI_REAL_COPIED ' "$log" && grep -q 'UI_REAL_DIALOG ' "$log" &&
           grep -q 'UI_REAL_VALIDATION_OK' "$log" && grep -q 'UI_REAL_CLICK_OK' "$log" &&
           grep -q 'UI_REAL_CANCEL_OK' "$log" && grep -q 'UI_REAL_RESPONSE accepted=true result=true' "$log" &&
           grep -q 'UI_REAL_RESPONSE accepted=false result=true' "$log" &&
           grep -q 'SWKBD_SECOND_RETURN .*cancel_match=1' "$log" &&
           grep -q 'SWKBD_SHOW_RETURN rc=0x00000000 module=0 description=0 ' "$log" &&
           grep -q 'SWKBD_HEX len=16 value=d09fd180d0b8d0b2d0b5d182f09f8eae' "$log" &&
           grep -q 'SWKBD_GUEST_DONE' "$log" && ! grep -q 'UI_REAL_FAIL' "$log"; then
          outcome=frame-callback-observed
        elif grep -q 'SWKBD_SHOW_RETURN' "$log"; then outcome=swkbd-result-observed
        elif grep -q 'SWKBD_SHOW_BEGIN' "$log"; then outcome=swkbd-unanswered
        elif grep -q 'SWKBD_CREATE' "$log"; then outcome=swkbd-create-failed
        else outcome=swkbd-not-reached; fi
        break
      fi
      if [ "$sample" = sdl-surface ]; then
        for ((j=0; j<20; j++)); do
          grep -q 'SDL_SURFACE_QUIT_RETURNED' "$log" && break
          grep -q 'SDL_SURFACE_FAIL' "$log" && break
          sleep 1
        done
        if grep -q 'SDL_SURFACE_FAIL' "$log" || ! grep -q 'SDL_SURFACE_PRESENT_OK frame=59' "$log" || ! grep -q 'SDL_SURFACE_QUIT_RETURNED' "$log"; then
          outcome=surface-present-or-close-incomplete; break
        fi
      fi
      if [ "$input_probe" = true ]; then
        # Before/after images only; input delivery needs independent observation.
        timeout 10s adb exec-out screencap -p > "$PROBE_WORK/$core-$sample-before-screen.png" || true
        input_stage=''
        for ((j=0; j<40; j++)); do
          input_stage=$(timeout 5s adb exec-out run-as "$APP" cat files/input-stage.txt 2>/dev/null | tr -d '\r' || true)
          [ "$input_stage" = AFTER_update_released ] && break
          sleep 1
        done
        printf 'INPUT_STAGE_FILE core=%s value=%s\n' "$core" "$input_stage" | tee "$PROBE_WORK/$core-$sample-input-stage.txt"
        # Written only after the release/update calls return; guest delivery
        # is a separate assertion and no missing logcat line implies success.
        if [ "$input_stage" != AFTER_update_released ]; then outcome=input-not-sent; break; fi
        if [ "$sample" = matrix ]; then
          for ((j=0; j<10; j++)); do
            if awk '/HID_BUTTONS plus=1/{pressed=1} pressed && /HID_BUTTONS plus=0/{ok=1} END{exit !ok}' "$log"; then break; fi
            sleep 1
          done
          if ! awk '/HID_BUTTONS plus=1/{pressed=1} pressed && /HID_BUTTONS plus=0/{ok=1} END{exit !ok}' "$log"; then
            outcome=input-not-observed-by-guest; break
          fi
        fi
      fi
      sleep 4
      if grep -qE 'ENGINE_FAIL|SDL_SURFACE_FAIL|Unhandled exception in guest thread|FATAL EXCEPTION|Fatal signal|ActivityManager.*Process dev\.symbiosis\.coreprobe \(pid [0-9]+\) has died' "$log"; then
        outcome=fail
      elif timeout 5s adb exec-out run-as "$APP" cat files/engine-crash.txt 2>/dev/null | grep '^crash signal=' >/dev/null; then
        outcome=native-crash
      else outcome=frame-callback-observed; fi
      break
    fi
    sleep 1
  done
  if [ "$outcome" = input-not-sent ]; then
    # The test observer may be stuck inside a real core call. Collect an actual
    # native backtrace before force-stop rather than guessing or declaring pass.
    pid=$(timeout 5s adb shell pidof "$APP" 2>/dev/null | tr -d '\r' | awk '{print $1}' || true)
    printf 'outcome=%s pid=%s\n' "$outcome" "$pid" > "$PROBE_WORK/$core-$sample-input-hang-state.txt"
    if [[ "$pid" =~ ^[0-9]+$ ]]; then
      # Android debuggerd requires root. Only this disposable local Redroid
      # container is queried, never a phone or a user process.
      timeout 25s docker exec -u 0 symbiosis-redroid debuggerd -b "$pid" > "$PROBE_WORK/$core-$sample-input-hang-backtrace.txt" 2>&1 || true
      timeout 5s adb shell "cat /proc/$pid/status" >> "$PROBE_WORK/$core-$sample-input-hang-state.txt" 2>&1 || true
    fi
  fi
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
