#!/bin/bash


# 用法示例:
# ./send_broadcast.sh toSilentInstall apkFilePath=/sdcard/app-release.apk packageName=com.syriusrobotics.platform.launcher

if [ $# -lt 1 ]; then
  echo "Usage: $0 event paramKey=paramValue [paramKey=paramValue ...]"
  exit 1
fi

EVENT=$1
shift

json="{"
first=true

for kv in "$@"; do
  key=${kv%%=*}
  value=${kv#*=}
  # 简单转义双引号
  value_escaped=$(echo "$value" | sed 's/"/\\"/g')
  if [ "$first" = true ]; then
    first=false
  else
    json+=","
  fi
  json+="\"$key\":\"$value_escaped\""
done

json+="}"

adb shell "am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast -a ON_SQA_INTERFACE_TEST --es event $EVENT --es param '$json'"

