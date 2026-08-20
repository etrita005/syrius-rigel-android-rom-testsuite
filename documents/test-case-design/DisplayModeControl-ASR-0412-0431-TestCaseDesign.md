# 显示模式管控：自动休眠开关与一直全屏（ASR-0412/0431）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner（`dpm list-owners` = DeviceOwner,Affiliated），testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root && dumpsys duraspeed addwhitelist com.hmdm.testapp`（MTK DuraSpeed 白名单；**`am force-stop com.hmdm.testapp` 会使 testapp 进入 DuraSpeed suppress list、广播被静默丢弃——整机重启清空恢复**，测试期间避免 force-stop）；
- 测试通道：`./send_test_command.sh <event> key=value ...`（IPC 与 UI 按钮等效，同一 `TestActions.execute()` 引擎）；也可在 testapp UI 的 "Volume / display locks" 页点按；
- 对照命令：`adb shell settings get system screen_off_timeout`、`adb shell dumpsys device_policy`（status bar disabled 状态）、`adb shell settings get system navigation_visible`、`adb shell dumpsys window` / `dumpsys statusbar`（状态栏可见性）、`adb shell dumpsys activity`；
- 恢复基线（测试开始时记录、结束时恢复）：screen_off_timeout 恢复原值（建议 30000 或测试前值）、全屏关闭（状态栏/导航栏显示）、导航栏恢复原值。

## 2. 测试用例表

### 2.1 ASR-0412 自动休眠开关（SetAutoSleepDisabled / IsAutoSleepDisabled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0412-01 基线查询 | `./send_test_command.sh IsAutoSleepDisabled` | disabled=false；screenOffTimeoutMs 为当前值（如 30000）；neverSleepEffective=false |
| TC-0412-02 禁用自动休眠 | `./send_test_command.sh SetAutoSleepDisabled disabled=true` | success=true；screenOffTimeoutMs=2147483647；neverSleepEffective=true；`settings get system screen_off_timeout`=2147483647 |
| TC-0412-03 查询回读 | IsAutoSleepDisabled | disabled=true；screenOffTimeoutMs=2147483647 |
| TC-0412-04 熄屏行为（间接） | 禁用后保持亮屏等待超过原超时时长（如原 30s 则等待 60s） | 屏幕不自动熄灭（无超时熄屏；可 `dumpsys power` 观察 wakefulness 保持 Awake） |
| TC-0412-05 启用自动休眠 | `./send_test_command.sh SetAutoSleepDisabled disabled=false` | success=true；screenOffTimeoutMs 恢复捕获值（如 30000） |
| TC-0412-06 重启保持 | disabled=true 后整机重启 | IsAutoSleepDisabled → disabled=true、screenOffTimeoutMs=2147483647（Settings 键持久化 + syncPolicy 重新武装） |
| TC-0412-07 缺参 | 不带 disabled | testapp 侧 missing parameter：disabled |

### 2.2 ASR-0431 一直全屏（SetAlwaysFullscreen / IsAlwaysFullscreen）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0431-01 基线查询 | `./send_test_command.sh IsAlwaysFullscreen` | fullscreen=false；statusBarDisabled=false；navigationVisible=1 |
| TC-0431-02 开启全屏 | `./send_test_command.sh SetAlwaysFullscreen fullscreen=true` | success=true；statusBarDisabled=true；navigationVisible=0；`settings get system navigation_visible`=0；`dumpsys device_policy` status bar disabled 状态 |
| TC-0431-03 状态栏/导航栏隐藏 | 观察屏幕（testapp 前台） | 状态栏与导航栏消失（全屏） |
| TC-0431-04 查询回读 | IsAlwaysFullscreen | fullscreen=true；statusBarDisabled=true；navigationVisible=0 |
| TC-0431-05 关闭全屏 | `./send_test_command.sh SetAlwaysFullscreen fullscreen=false` | success=true；statusBarDisabled=false；navigationVisible 恢复 1；状态栏/导航栏重新显示 |
| TC-0431-06 重启保持 | fullscreen=true 后整机重启 | IsAlwaysFullscreen → fullscreen=true、statusBarDisabled=true、navigationVisible=0（DPM 策略 + Settings 键持久化；syncPolicy 幂等重放） |
| TC-0431-07 缺参 | 不带 fullscreen | testapp 侧 missing parameter：fullscreen |

### 2.3 通用用例

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-M-01 未知事件 | `./send_test_command.sh SetAutoSleepDisabledXXX` | 返回 unknown event，不 crash |
| TC-M-02 UI 等效 | testapp UI "Volume / display locks" 页点按各按钮 | 页面 resumed；与 IPC 共用 TestActions 引擎；结果一致 |
| TC-M-03 返回值类型 | 检查命令返回 JSON | 全部为 Map/List/基本类型 |
| TC-M-04 事件目录 | `./send_test_command.sh ListEvents` | 目录含本批次 4 个新事件 |

## 3. 验证提示

- 结果查看：`./send_test_command.sh` 输出 `Broadcast completed: data=...` 与 logcat `HYX-TESTAPP-CMD` 行（result 码恒为 -1 以 data 为准；result=0 无日志先查 DuraSpeed suppress_list）；
- 超时核验：`settings get system screen_off_timeout`；`dumpsys power` 的 `mWakefulness=`（Awake）与 `mScreenOffTimeoutSetting`；
- 全屏核验：`settings get system navigation_visible` + `dumpsys device_policy` 的 status bar disabled 行 + 屏幕实况（uiautomator dump 观察系统栏消失/恢复）；
- 注意：全屏用例执行期间系统栏隐藏属预期；恢复用例务必执行（fullscreen=false）避免遗留。

## 4. 实测结果（2026-08-11，Android 13 / API 33 userdebug，平台签名 + device owner）

（部署完成后经 `send_test_command.sh` IPC 通道执行并回填）

| 用例 | 实测结果 |
|---|---|
| TC-0412-01 ~ 07 | 通过（02 禁用 timeout=2147483647 + settings 键对照；03 查询 neverSleepEffective=true；05 恢复 1800000；06 整机重启保持；07 缺参路径） |
| TC-0431-01 ~ 07 | 通过（导航栏侧）：02 开启 navigationVisible=0 + settings 键对照；03 导航栏隐藏实况；04 查询；05 恢复 1；06 重启保持；**状态栏侧本 ROM 无机制**（setStatusBarDisabled 被 fork DPMS 丢弃、policy_control 不被 SystemUI 消费，dumpsys window StatusBar isVisible 恒 true），命令如实附注 note，按用户决策标记部分完成（导航栏侧）；07 缺参路径 |
| TC-M-01 ~ 04 | 通过（未知事件/返回值类型/事件目录/UI 页正常） |

**机制核验补充**：screen_off_timeout 为 Settings.System 持久化键（SettingsProvider 存储，重启保持）；status bar disabled 为 DPM 策略（device_policies.xml 持久化）；navigation_visible 为本 ROM MTK 导航栏可见性键（ASR-0349 批次真机核验路径）。
