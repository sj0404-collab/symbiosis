#!/usr/bin/env bash
# Disposable probe log classifier. Process death is not attributed to a
# particular fault: a separate tombstone/log is needed for that conclusion.
probe_terminal_failure() {
  local log=$1
  if grep -qE 'PROBE_FAIL|FATAL EXCEPTION' "$log"; then
    echo fail
  elif grep -qE 'Fatal signal.*iosis\.coreprobe' "$log"; then
    echo native-crash
  elif grep -qE 'ActivityManager.*Process dev\.symbiosis\.coreprobe \(pid [0-9]+\) has died' "$log"; then
    echo process-death
  else
    return 1
  fi
}
