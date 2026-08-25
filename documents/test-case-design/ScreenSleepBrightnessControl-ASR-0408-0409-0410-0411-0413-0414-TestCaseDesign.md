# 屏幕/休眠/亮度管控（ASR-0408/0409/0410/0411/0413/0414）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner，testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root`、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`；
- 对照命令：`adb shell settings get global stay_on_while_plugged_in`、`adb shell settings get system screen_off_timeout`、`adb shell settings get system screen_brightness`、`adb shell dumpsys device_policy`；
- 恢复基线（本机）：stayOn=0、timeout=60000、brightness=87、限制均 false；
- 参数名：stayAwakeWhileCharging/timeout/brightness/disallow（命令实际参数）；
- 本 ROM 特性（2026-08-13 核验）：设置均持久化（SettingsProvider），测试后必须恢复基线。

## 2. 测试用例表

### 2.1 ASR-0408 屏幕常亮（Set/IsStayAwakeWhileCharging）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0408-01 基线 | `./send_test_command.sh IsStayAwakeWhileCharging`；`ScreenStateLocal`；`adb shell settings get global stay_on_while_plugged_in` | false；stayOnWhilePluggedIn=0 |
| TC-0408-02 开启 | `./send_test_command.sh SetStayAwakeWhileCharging stayAwakeWhileCharging=true`；`IsStayAwakeWhileCharging`；`ScreenStateLocal` | RESULT=true；Is=true；探针 stayOnWhilePluggedIn=3（充电保持常亮） |
| TC-0408-03 恢复 | `SetStayAwakeWhileCharging stayAwakeWhileCharging=false`；`IsStayAwakeWhileCharging` | true；false（基线） |

### 2.2 ASR-0409/0413 自动熄屏/休眠时长（SetSleepTimeOut / GetSleepTimeout）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0409-01 基线 | `./send_test_command.sh GetSleepTimeout`；`ScreenStateLocal` | 60000（本机） |
| TC-0409-02 设置 | `./send_test_command.sh SetSleepTimeOut timeout=120000`；`GetSleepTimeout`；`ScreenStateLocal`；`adb shell settings get system screen_off_timeout` | RESULT=true；Get=120000；探针/settings 一致（三方对照） |
| TC-0409-03 恢复 | `SetSleepTimeOut timeout=60000`；`GetSleepTimeout` | 60000（基线） |
| TC-0409-04 缺参 | `./send_test_command.sh SetSleepTimeOut` | 返回 `missing parameter: timeout`，不 crash |

### 2.3 ASR-0410 设置屏幕亮度（SetBrightness / GetBrightness）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0410-01 基线 | `./send_test_command.sh GetBrightness`；`ScreenStateLocal` | 87（本机） |
| TC-0410-02 设置 | `./send_test_command.sh SetBrightness brightness=150`；`GetBrightness`；`ScreenStateLocal` | RESULT=true；Get=150；探针一致（亮度即时变化） |
| TC-0410-03 恢复 | `SetBrightness brightness=87`；`GetBrightness` | 87（基线） |
| TC-0410-04 缺参 | `./send_test_command.sh SetBrightness` | 返回 `missing parameter: brightness`，不 crash |

### 2.4 ASR-0411/0414 用户限制（Set/IsDisallowUserConfigBrightness / Set/IsDisallowUserConfigSleepTimeout）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0411-01 基线 | `./send_test_command.sh IsDisallowUserConfigBrightness` | false |
| TC-0411-02 禁止 | `./send_test_command.sh SetDisallowUserConfigBrightness disallow=true`；`IsDisallowUserConfigBrightness` | RESULT=true；Is=true（DISALLOW_CONFIG_BRIGHTNESS） |
| TC-0411-03 恢复 | `SetDisallowUserConfigBrightness disallow=false`；`IsDisallowUserConfigBrightness` | true；false |
| TC-0414-01~03 | 同 0411 系列（Set/IsDisallowUserConfigSleepTimeout，DISALLOW_CONFIG_SCREEN_TIMEOUT） | 往返一致 |

### 2.5 恢复基线（测试结束）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-B19-01 恢复 | `SetStayAwakeWhileCharging stayAwakeWhileCharging=false`；`SetSleepTimeOut timeout=60000`；`SetBrightness brightness=87`；`SetDisallowUserConfigBrightness disallow=false`；`SetDisallowUserConfigSleepTimeout disallow=false`；`adb shell dpm list-owners`；`GetConnectionStatus` | 全部基线；DO 在位；bound=true |

## 3. 硬件受限测试说明

- ASR-0408/0409/0410/0411/0413/0414 无硬件依赖；上述用例全部在本机（Android 13 / API 33 userdebug，平台签名 + device owner）执行通过（2026-08-13）。
- 亮度/超时/常亮设置为持久化系统设置，测试结束后已全部恢复基线。
