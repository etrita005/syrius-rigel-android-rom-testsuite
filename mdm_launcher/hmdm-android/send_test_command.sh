#!/bin/bash

# ============================================================================
# Send a test command to the testapp over IPC
# (adb shell -> com.hmdm.testapp.TestCommandReceiver).
#
# The IPC entry points are exactly equivalent to the buttons in the test
# activities (they execute the same TestActions engine), so the whole app can
# be tested without touching the UI.
#
# Usage:
#   ./send_test_command.sh <event> [key=value ...]
#
# Examples:
#   ./send_test_command.sh GetConnectionStatus
#   ./send_test_command.sh ListEvents
#   ./send_test_command.sh SetNotificationsPolicyMode mode=1
#   ./send_test_command.sh SetNotificationsWhitelist 'packageNames=["com.hmdm.testapp"]'
#   ./send_test_command.sh SetCameraDisabled disabled=true
#   ./send_test_command.sh TryOpenCamera
#   ./send_test_command.sh SetLogBufferSize size=1M buffer=main
#   ./send_test_command.sh SetLogLevel tag=HYX_MDM_TEST level=V
#
# Value typing: values that are JSON arrays/objects, booleans or pure numbers
# pass through unquoted; everything else is sent as a string.
#
# The result is returned in two ways:
#   1. "Broadcast completed: result=0, data=<json>" printed by `am broadcast`
#   2. a logcat line with tag HYX-TESTAPP-CMD (printed below the broadcast)
#
# Device: use ADB=/path/to/adb ./send_test_command.sh ... to override.
# ============================================================================

ADB=${ADB:-/home/alex/Android/Sdk/platform-tools/adb}

if [ $# -lt 1 ]; then
  echo "Usage: $0 <event> [key=value ...]" >&2
  echo "Run '$0 ListEvents' to see all supported events." >&2
  exit 1
fi

EVENT=$1
shift

json="{"
first=true
for kv in "$@"; do
  key=${kv%%=*}
  value=${kv#*=}
  case "$value" in
    '['*']'|'{'*'}'|'true'|'false') typed="$value" ;;
    ''|*[!0-9]*) typed="\"$value\"" ;;
    *) typed="$value" ;;
  esac
  if [ "$first" = true ]; then
    first=false
  else
    json+=","
  fi
  json+="\"$key\":$typed"
done
json+="}"

echo "==> event=$EVENT param=$json"
"$ADB" shell "am broadcast -n com.hmdm.testapp/.TestCommandReceiver -a com.hmdm.testapp.CMD --es event $EVENT --es param '$json'"
echo "==> last HYX-TESTAPP-CMD log line:"
"$ADB" logcat -d -s HYX-TESTAPP-CMD:V | grep -F -- "$EVENT" | tail -n 1
