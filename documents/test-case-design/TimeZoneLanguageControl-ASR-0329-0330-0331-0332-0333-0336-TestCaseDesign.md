# 时间/时区/语言管控（ASR-0329/0330/0331/0332/0333/0336）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner，testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root`、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`；
- 对照命令：`adb shell date`、`adb shell settings get system system_locales`、`adb shell getprop persist.sys.timezone`；
- 恢复基线（本机）：时间与宿主机同步、时区 Asia/Shanghai、语言 en-US、自动时区开、日期时间限制 false；
- 本 ROM 特性（2026-08-13 核验）：① 命令参数名——`DisallowConfigDataTime` 用 `disallow`、`SetTimeZone` 用 `timezoneId`、`SetTime` 用 `time`；② SetTime 需 manifest SET_TIME 声明（已补）；③ SetLanguage 需完整语言标签（zh-CN/en-US，"en" 无 country 返回 false）；④ 切时区前先关自动时区（防覆盖）；⑤ SetTime 持久化——测试后必须恢复。

## 2. 测试用例表

### 2.1 ASR-0329/0330 是否允许修改时间（DisallowConfigDataTime / IsDisallowConfigDataTime）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0329-01 基线 | `./send_test_command.sh IsDisallowConfigDataTime` | false |
| TC-0329-02 禁止 | `./send_test_command.sh DisallowConfigDataTime disallow=true`；`IsDisallowConfigDataTime` | RESULT=true；Is=true（DISALLOW_CONFIG_DATE_TIME；设置页日期时间受限） |
| TC-0329-03 恢复 | `DisallowConfigDataTime disallow=false`；`IsDisallowConfigDataTime` | true；Is=false（往返一致，无残留） |
| TC-0329-04 缺参 | `./send_test_command.sh DisallowConfigDataTime` | 返回 `missing parameter: disallow`，不 crash |

### 2.2 ASR-0331 设置系统时间（SetTime）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0331-01 记录原时间 | `./send_test_command.sh SystemTimeLocal`；`adb shell date` | 记录基线（与宿主机同步） |
| TC-0331-02 设置 | `./send_test_command.sh SetTime time=<基线+120000>`；等待 2s；`SystemTimeLocal`；`adb shell date` | RESULT=true（SET_TIME 声明已补，2026-08-13）；系统时间跳变 +120s（探针/date 一致） |
| TC-0331-03 恢复 | `SetTime time=<宿主机当前毫秒>`；`adb shell date` | 与宿主机时间一致（恢复基线） |
| TC-0331-04 缺参 | `./send_test_command.sh SetTime` | 返回 `missing parameter: time`，不 crash |

### 2.3 ASR-0332 改变时区（SetTimeZone）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0332-01 基线 | `./send_test_command.sh GetTimeZoneLocal` | Asia/Shanghai |
| TC-0332-02 关自动时区 | `./send_test_command.sh SetAutoTimeZoneEnabled enabled=false`；`IsAutoTimeZoneEnabled` | true；false |
| TC-0332-03 切换 | `./send_test_command.sh SetTimeZone timezoneId=Etc/UTC`；`GetTimeZoneLocal`；`adb shell date` | RESULT=true；UTC（时钟随切换跳变可见——真机记录 00:49→16:49） |
| TC-0332-04 恢复 | `SetTimeZone timezoneId=Asia/Shanghai`；`SetAutoTimeZoneEnabled enabled=true`；`GetTimeZoneLocal`；`IsAutoTimeZoneEnabled` | Asia/Shanghai；true（基线恢复） |

### 2.4 ASR-0333 是否允许用户手动修改时区（Set/IsAutoTimeZoneEnabled + 限制）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0333-01 基线 | `./send_test_command.sh IsAutoTimeZoneEnabled` | true（本机基线） |
| TC-0333-02 关闭 | `SetAutoTimeZoneEnabled enabled=false`；`IsAutoTimeZoneEnabled` | true；false |
| TC-0333-03 恢复 | `SetAutoTimeZoneEnabled enabled=true`；`IsAutoTimeZoneEnabled` | true（基线） |
| TC-0333-04 限制联动 | `DisallowConfigDataTime disallow=true` → `IsDisallowConfigDataTime=true` → `disallow=false` 恢复 | 往返一致（与 0329 共用限制） |

### 2.5 ASR-0336 修改系统语言（SetLanguage / GetLanguage）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0336-01 基线 | `./send_test_command.sh GetLanguage`；`adb shell settings get system system_locales` | en/US（本机基线） |
| TC-0336-02 切换 | `./send_test_command.sh SetLanguage language=zh-CN`；`GetLanguage` | RESULT=true；{language:zh, country:CN}（系统语言切换生效，2026-08-13 实测） |
| TC-0336-03 单语言无地区（边界） | `SetLanguage language=en`（无 country） | RESULT=false（本 ROM Locale 构造限制，如实记录） |
| TC-0336-04 恢复 | `SetLanguage language=en-US`；`GetLanguage` | true；{en,US}（基线恢复） |
| TC-0336-05 缺参 | `./send_test_command.sh SetLanguage` | 返回 `missing parameter: language`，不 crash |

### 2.6 恢复基线（测试结束）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-B15-01 恢复 | `SetTime time=<宿主机毫秒>`；`SetTimeZone timezoneId=Asia/Shanghai`；`SetAutoTimeZoneEnabled enabled=true`；`DisallowConfigDataTime disallow=false`；`SetLanguage language=en-US`；`adb shell dpm list-owners` | 时间/时区/语言/自动时区/限制全部基线；DO 在位 |

## 3. 硬件受限测试说明

- ASR-0329/0330/0331/0332/0333/0336 无硬件依赖；上述用例全部在本机（Android 13 / API 33 userdebug，平台签名 + device owner）执行通过（2026-08-13）。
- SetTime/SetTimeZone/SetLanguage 均为持久化系统设置，测试结束后已全部恢复基线；语言切换期间 testapp 界面文本随系统语言变化属预期（IPC 通道不受影响）。
