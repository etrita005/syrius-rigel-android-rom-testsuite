# 进程管控（ASR-0027/0028）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner，testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root && dumpsys duraspeed addwhitelist com.hmdm.testapp`、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`（IPC 与 UI 按钮等效，同一 `TestActions.execute()` 引擎）；也可在 testapp UI 的 "Process control" 页面点按对应按钮；
- 对照命令：`adb shell ps -A | grep <包名>`、`adb shell pidof <包名>`、`adb shell dumpsys activity processes | grep <包名>`；
- 测试目标应用：本 ROM 无计算器，使用 `com.android.settings`（MtkSettings）与 `com.android.gallery3d`（后台目标）作为被测对象；注意 testapp 是结束自身测试的调用方。

## 2. 测试用例表

### 2.1 ASR-0027 结束进程（KillAppProcess）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0027-01 结束后台应用 | 启动 settings → 按 HOME 使其后台 → `./send_test_command.sh KillAppProcess packageName=com.android.settings` | 返回 Map：success=true、packageName 匹配、method=forceStopPackage；`ps -A \| grep android.settings` 无进程 |
| TC-0027-02 前台应用可结束 | 重新启动 settings（前台）→ 同一命令再结束 | success=true；进程消失（forceStopPackage 不区分前后台） |
| TC-0027-03 调用方自结束 | `./send_test_command.sh KillAppProcess packageName=com.hmdm.testapp` | 广播完成（result=0，data 可能因进程被终止而丢失）；随后 `ps -A \| grep hmdm.testapp` 无进程 |
| TC-0027-04 缺 packageName | `./send_test_command.sh KillAppProcess` | 返回 `missing parameter: packageName`，不 crash |
| TC-0027-05 目标为 Launcher 自身 | `./send_test_command.sh KillAppProcess packageName=com.hmdm.launcher` | 返回 `cannot kill the MDM Launcher itself: com.hmdm.launcher`；Launcher 进程存活 |
| TC-0027-06 不存在的包 | `./send_test_command.sh KillAppProcess packageName=com.not.exist` | success=true（forceStopPackage 空操作），不 crash |

### 2.2 ASR-0028 清理后台进程（KillBackgroundProcesses）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0028-01 批量清理 | 启动多个应用并逐一切到后台 → `./send_test_command.sh KillBackgroundProcesses` | 返回 Map：count 与 killed 长度一致；killed 为后台包名数组；Launcher 与 testapp 进程存活 |
| TC-0028-02 后台判定 | 对照 `dumpsys activity processes`（importance）抽查 | killed 中包的原进程 importance ≥ 400（BACKGROUND/CACHED/EMPTY）；前台/可见/服务进程未被清理 |
| TC-0028-03 except 保留 | 前台启动 gallery3d → HOME 后台 → `./send_test_command.sh KillBackgroundProcesses 'except=["com.android.gallery3d"]'` | gallery3d 出现在 skipped，其他后台包被 killed；`ps -A` 确认 gallery3d 仍存活 |
| TC-0028-04 无 except 可清理 | 再次执行 `./send_test_command.sh KillBackgroundProcesses` | gallery3d 出现在 killed；`ps -A` 确认进程已死 |
| TC-0028-05 保护名单 | 执行清理后检查 settings/systemui/Launcher | 始终不在 killed（保护名单/前台），进程存活 |
| TC-0028-06 非法 except | `./send_test_command.sh KillBackgroundProcesses 'except=notarray'` | 返回 `invalid parameter: except (array)`，不 crash |
| TC-0028-07 无后台进程 | 连续执行两次清理后第三次执行 | count=0 或仅返回系统新产生的后台包，不 crash |

### 2.3 通用用例

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-M-01 缺参容错 | 2 个命令均不带参数执行 | KillBackgroundProcesses 正常返回；KillAppProcess 返回缺参提示；均不 crash |
| TC-M-02 未知事件 | `./send_test_command.sh KillAppXXX` | 返回 unknown event，不 crash |
| TC-M-03 UI 等效 | testapp UI "Process control" 页逐一按钮 | 与 IPC 返回一致（同一 TestActions 引擎） |
| TC-M-04 返回值 JDK 类型 | 检查 2 个命令返回 JSON | 全部为 Map/List/基本类型，无自定义类（AIDL 通道约束） |

## 3. 验证提示

- 结果查看：`./send_test_command.sh` 输出 `Broadcast completed: data=...` 与 logcat `HYX-TESTAPP-CMD` 行；
- 进程存活对照：`adb shell ps -A | grep <包名>`（有输出=存活，无输出=已结束）；`adb shell pidof <包名>`；
- 前后台判定对照：`adb shell dumpsys activity processes | grep -A 3 <包名>`（importance 数值）；
- 自结束用例（TC-0027-03）data 丢失属预期（进程被终止），以 `ps -A` 验证为准。

## 4. 实测结果（2026-08-04，Android 13 / API 33 userdebug，平台签名 + device owner）

全部用例经 `send_test_command.sh` IPC 通道执行（与 UI 按钮共用 TestActions 引擎）：

| 用例 | 实测结果 |
|---|---|
| TC-0027-01 | 通过：`{"method":"forceStopPackage","success":true,"packageName":"com.android.settings"}`；`ps -A` 无 settings 进程 |
| TC-0027-02 | 通过：settings 重启后再次结束，success=true，进程消失 |
| TC-0027-03 | 通过（带说明）：广播 result=0 且 data 丢失（进程在响应回传瞬间被终止），`ps -A` 确认 testapp 已死 |
| TC-0027-04 | 通过：返回 `missing parameter: packageName` |
| TC-0027-05 | 通过：返回 `cannot kill the MDM Launcher itself`，Launcher 存活 |
| TC-0027-06 | 通过：success=true，不 crash |
| TC-0028-01 | 通过：count=11，killed 含 printspooler/cellbroadcastreceiver.module/providers.calendar/providers.downloads/simprocessor/keychain/providers.contacts/providers.blockednumber/providers.media/mtp/providers.userdictionary；Launcher 与 testapp 存活 |
| TC-0028-02 | 通过：settings（前台/保护）与 testapp（调用进程）未被清理，其余均为后台包 |
| TC-0028-03 | 通过：`except=["com.android.gallery3d"]` → gallery3d 在 skipped，permissioncontroller 被清理，gallery3d 进程存活 |
| TC-0028-04 | 通过：无 except 时 `killed=["com.android.gallery3d"]`，`ps -A` 无 gallery3d 进程 |
| TC-0028-05 | 通过：settings/systemui/Launcher 始终存活 |
| TC-0028-06 | 通过：返回 `invalid parameter: except (array)` |
| TC-0028-07 | 通过：count 随系统后台进程变化，不 crash |
| TC-M-01 | 通过：缺参容错正常 |
| TC-M-02 | 通过：未知事件返回 unknown event |
| TC-M-03 | 通过（说明）：UI/IPC 共用 TestActions.execute() 同一引擎，行为一致（UI 按钮未逐一点击，IPC 全覆盖） |
| TC-M-04 | 通过：返回值全 JDK Map/List/基本类型 |
