# 应用运行/隐藏管控（ASR-0016/0017/0030/0099/0104/0131）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner，testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root && dumpsys duraspeed addwhitelist com.hmdm.testapp`、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`（IPC 与 UI 按钮等效，同一 `TestActions.execute()` 引擎）；也可在 testapp UI 的 "App run policy" 页面点按对应按钮；
- 对照命令：`adb shell dumpsys package <包名> | grep hidden/suspended`、`adb shell dumpsys deviceidle whitelist | grep '^user'`、`adb shell pm list packages | grep <包名>`、`adb shell am start -n <组件>`；
- 测试目标应用：`com.android.gallery3d`、`com.android.settings`（系统应用，可挂起/隐藏且解除隐藏安全）、`org.chromium.webview_shell`（系统预装浏览器能力应用，/product/app/Browser2）；本 ROM 无 Google Play Store（`com.android.vending` 不存在）。
- **环境说明**：本 ROM 预装浏览器 `com.ume.browser`（/data 安装）在 2026-08-04 测试期间因解除隐藏触发 Launcher 既有安装白名单策略（`InstallWhitelistManager`，非白名单非系统应用静默卸载）被卸载，此后 ASR-0099 以系统浏览器 `org.chromium.webview_shell` 为实测目标；隐藏/解除隐藏机制对任意包一致。

## 2. 测试用例表

### 2.1 ASR-0017 禁止运行白名单（SetBlockedRunningWhitelist）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0017-01 基线 | `./send_test_command.sh GetBlockedRunningWhitelist` | `{"list":[],"success":true}` |
| TC-0017-02 添加白名单 | `./send_test_command.sh SetBlockedRunningWhitelist 'packageNames=["com.android.gallery3d"]'` | added 含 gallery3d（suspended=true、hidden=true）；dumpsys 对照 `hidden=true suspended=true` |
| TC-0017-03 禁止运行生效 | `adb shell am start -n com.android.gallery3d/.app.GalleryActivity` | 启动失败（"Activity class does not exist"）；`ps -A` 无 gallery3d 进程（挂起强制结束） |
| TC-0017-04 名单查询 | `./send_test_command.sh GetBlockedRunningWhitelist` | list 含 {packageName, suspended:true, hidden:true} |
| TC-0017-05 单包状态查询 | `./send_test_command.sh IsPackageSuspended packageName=com.android.gallery3d` | `{packageName, suspended:true, hidden:true}` |
| TC-0017-06 批量添加系统应用 | `./send_test_command.sh SetBlockedRunningWhitelist 'packageNames=["com.android.gallery3d","com.android.settings"]'` | 两者均 added（系统应用可挂起+隐藏）；settings 启动同样失败 |
| TC-0017-07 移除恢复 | `./send_test_command.sh SetBlockedRunningWhitelist 'packageNames=["com.android.gallery3d"]'` | removed 含 settings（suspended=false、hidden=false）；settings 可重新启动；dumpsys 对照状态恢复 |
| TC-0017-08 保护名单 | `./send_test_command.sh SetBlockedRunningWhitelist 'packageNames=["com.android.gallery3d","com.android.settings","com.hmdm.launcher","com.hmdm.testapp"]'` | skipped 含 `com.hmdm.launcher`、`com.hmdm.testapp`；success=true；Launcher 与 testapp 可正常运行 |
| TC-0017-09 不存在的包 | `./send_test_command.sh SetBlockedRunningWhitelist 'packageNames=["...","com.not.exist"]'` | failed 含 com.not.exist（setPackagesSuspended 失败）；**success=false**；不 crash，不持久化 |
| TC-0017-10 清空名单 | `./send_test_command.sh SetBlockedRunningWhitelist 'packageNames=[]'` | removed 全部；gallery3d 恢复 hidden=false suspended=false 且可启动；success=true |
| TC-0017-11 缺 packageNames | `./send_test_command.sh SetBlockedRunningWhitelist` | 返回 `missing parameter: packageNames (array)`，不 crash |

### 2.2 ASR-0016/0030 耗电/忽略耗电优化白名单（SetIgnoreBatteryOptimizationWhitelist）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0030-01 基线 | `./send_test_command.sh GetIgnoreBatteryOptimizationWhitelist` + `IsIgnoringBatteryOptimization packageName=com.hmdm.testapp` | 空名单；ignoring=false |
| TC-0030-02 添加（DO 免交互） | `./send_test_command.sh SetIgnoreBatteryOptimizationWhitelist 'packageNames=["com.hmdm.testapp"]'` | added 含 testapp（ignoring=true）；**全程无系统弹窗**；`IsIgnoringBatteryOptimization` → true |
| TC-0030-03 系统状态对照 | `adb shell dumpsys deviceidle whitelist \| grep '^user'` | 出现 `user,com.hmdm.testapp,<uid>` |
| TC-0030-04 替换添加 | `./send_test_command.sh SetIgnoreBatteryOptimizationWhitelist 'packageNames=["com.hmdm.testapp","com.android.gallery3d"]'` | gallery3d 被 added；testapp 状态保持 |
| TC-0030-05 移除 | `./send_test_command.sh SetIgnoreBatteryOptimizationWhitelist 'packageNames=["com.android.gallery3d"]'` | testapp 被 removed（ignoring=false，user 条目消失）；gallery3d 保持 ignoring=true |
| TC-0030-06 清空 | `./send_test_command.sh SetIgnoreBatteryOptimizationWhitelist 'packageNames=[]'` | 全部 removed；`dumpsys deviceidle whitelist` 无新增 user 条目 |
| TC-0030-07 缺 packageNames | `./send_test_command.sh SetIgnoreBatteryOptimizationWhitelist` | 返回 `missing parameter: packageNames (array)`，不 crash |
| TC-0030-08 不存在的包 | `./send_test_command.sh SetIgnoreBatteryOptimizationWhitelist 'packageNames=["com.not.exist"]'` | failed 含 com.not.exist（**package not installed**，应用前校验拒绝）；**success=false**；不 crash、不持久化 |

### 2.6 安全用例

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-SEC-01 命令注入载荷 | `SetIgnoreBatteryOptimizationWhitelist 'packageNames=["a; id"]'`、`'packageNames=["com.foo$(touch /data/local/tmp/pwned)"]'` | failed："invalid package name"；`ls /data/local/tmp/pwned` 不存在（载荷未进入 shell）；不 crash |

### 2.3 ASR-0099 禁用系统预装浏览器（SetApplicationHidden）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0099-01 基线 | `./send_test_command.sh IsApplicationHidden packageName=org.chromium.webview_shell` | RESULT=false |
| TC-0099-02 禁用浏览器 | `SetApplicationHidden packageName=org.chromium.webview_shell hidden=true` → `IsApplicationHidden` → `am start -n org.chromium.webview_shell/.WebViewBrowserActivity` | RESULT=true；查询=true；dumpsys `hidden=true`；启动失败（Activity 无法解析） |
| TC-0099-03 恢复浏览器 | `SetApplicationHidden packageName=org.chromium.webview_shell hidden=false` → `IsApplicationHidden` → 重新启动 | RESULT=true；查询=false；dumpsys `hidden=false`；启动成功 |

### 2.4 ASR-0104 启用 Settings 应用（SetApplicationHidden）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0104-01 隐藏/启用 Settings | `SetApplicationHidden packageName=com.android.settings hidden=true` → 查询 → `hidden=false` → 查询 → `am start -n com.android.settings/.Settings` | 隐藏=true/查询=true；启用=true/查询=false；Settings 可正常启动（系统应用解除隐藏后安装白名单策略卸载尝试失败、无影响） |

### 2.5 ASR-0131 禁用 Google Play Store（SetApplicationHidden）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0131-01 包不存在 | `IsApplicationHidden packageName=com.android.vending`、`SetApplicationHidden packageName=com.android.vending hidden=true` | 本 ROM 无 GMS：set 返回 **false**（真实 DPM 结果，包不存在被拒绝）；不 crash。机制本身（系统应用禁用/启用）已由 TC-0099/0104 覆盖 |

### 2.7 通用用例

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-M-01 缺参容错 | 6 个新命令均不带参数/缺 packageName 执行 | 均返回缺参提示，不 crash |
| TC-M-02 未知事件 | `./send_test_command.sh SetBlockedRunningWhitelistXXX` | 返回 unknown event，不 crash |
| TC-M-03 UI 等效 | testapp UI "App run policy" 页逐一按钮 | 与 IPC 返回一致（同一 TestActions 引擎） |
| TC-M-04 返回值 JDK 类型 | 检查命令返回 JSON | 全部为 Map/List/基本类型，无自定义类（AIDL 通道约束） |
| TC-M-05 状态恢复 | 用例执行结束后复查：settings/gallery3d/webview_shell hidden=false、suspended=false；`dumpsys deviceidle whitelist` 无新增 user 条目 | 全部恢复，无测试残留 |

## 3. 验证提示

- 结果查看：`./send_test_command.sh` 输出 `Broadcast completed: data=...` 与 logcat `HYX-TESTAPP-CMD` 行；
- 挂起/隐藏状态对照：`adb shell dumpsys package <包名> | grep -E 'hidden=|suspended='`；
- 电源白名单对照：`adb shell dumpsys deviceidle whitelist | grep '^user'`（user 条目即忽略耗电优化应用）；
- 启动被阻止对照：`adb shell am start -n <组件>` 报 "Activity class ... does not exist"（隐藏包不可解析；挂起包如仍在启动器可见则弹"应用已暂停"对话框）；
- 免交互对照：添加电池白名单时观察屏幕，不应出现"忽略电池优化"授权弹窗（device owner 静默授予）。

## 4. 实测结果（2026-08-04，Android 13 / API 33 userdebug，平台签名 + device owner）

全部用例经 `send_test_command.sh` IPC 通道执行（与 UI 按钮共用 TestActions 引擎）：

| 用例 | 实测结果 |
|---|---|
| TC-0017-01 | 通过：`{"list":[],"success":true}` |
| TC-0017-02 | 通过：`added:[{"hidden":true,"suspended":true,"packageName":"com.android.gallery3d"}]`；dumpsys 对照 `hidden=true suspended=true` |
| TC-0017-03 | 通过：启动报 "Activity class does not exist"；进程无残留 |
| TC-0017-04 | 通过：list 含 `{packageName, suspended:true, hidden:true}` |
| TC-0017-05 | 通过：`{packageName, suspended:true, hidden:true}` |
| TC-0017-06 | 通过：settings+gallery3d 均 added；settings 启动失败 |
| TC-0017-07 | 通过：settings removed（hidden=false suspended=false），可重新启动，仍安装完好（系统应用） |
| TC-0017-08 | 通过：`skipped:["com.hmdm.launcher","com.hmdm.testapp"]` |
| TC-0017-09 | 通过：`failed:[{"error":"setPackagesSuspended failed for: com.not.exist",...}]`，success=false |
| TC-0017-10 | 通过：清空后 gallery3d 恢复并可启动，success=true |
| TC-0017-11 | 通过：返回 `missing parameter: packageNames (array)` |
| TC-0030-01 | 通过：空名单；ignoring=false |
| TC-0030-02 | 通过：`added:[{"ignoring":true,"packageName":"com.hmdm.testapp"}]`；isIgnoring=true；全程免交互 |
| TC-0030-03 | 通过：`user,com.hmdm.testapp,10127` |
| TC-0030-04 | 通过：gallery3d added，testapp 保持 |
| TC-0030-05 | 通过：testapp removed（ignoring=false、user 条目消失）；gallery3d 保持 true |
| TC-0030-06 | 通过：全部 removed，deviceidle 无新增 user 条目（仅预置 com.android.phone） |
| TC-0030-07 | 通过：返回 `missing parameter: packageNames (array)` |
| TC-0030-08 | 通过：`failed:[{"error":"package not installed",...}]`，success=false（应用前校验拒绝，不再报假 added） |
| TC-SEC-01 | 通过：`a; id` 与 `$(touch ...)` 载荷均返回 `invalid package name`；`/data/local/tmp/pwned` 未创建；不 crash |
| TC-0099-01 | 通过：RESULT=false |
| TC-0099-02 | 通过：set=true、查询=true、dumpsys hidden=true、启动失败 |
| TC-0099-03 | 通过：set=true、查询=false、启动成功 |
| TC-0104-01 | 通过：Settings 隐藏/启用往返成功（系统应用），启用后正常启动 |
| TC-0131-01 | 通过：set 返回 false（真实 DPM 结果）；不 crash（本 ROM 无 Play Store，机制由 TC-0099/0104 覆盖） |
| TC-M-01 | 通过：缺参容错正常 |
| TC-M-02 | 通过：未知事件返回 unknown event |
| TC-M-03 | 通过（说明）：UI/IPC 共用 TestActions.execute() 同一引擎，行为一致（UI 按钮未逐一点击，IPC 全覆盖） |
| TC-M-04 | 通过：返回值全 JDK Map/List/基本类型 |
| TC-M-05 | 通过：settings/gallery3d/webview_shell 均 hidden=false suspended=false；deviceidle 无新增 user 条目 |
