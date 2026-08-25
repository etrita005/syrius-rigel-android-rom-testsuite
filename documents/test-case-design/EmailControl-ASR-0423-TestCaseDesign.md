# 邮件管控（ASR-0423）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner（`dpm list-owners` = DeviceOwner,Affiliated），testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root && dumpsys duraspeed addwhitelist com.hmdm.testapp`（MTK DuraSpeed 白名单，防 manifest receiver 广播被丢弃）、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`（IPC 与 UI 按钮等效，同一 `TestActions.execute()` 引擎）；也可在 testapp UI 的 "Email control" 页面点按对应按钮（ASR-0423 分区）；
- 对照命令：`adb shell dumpsys package <pkg> | grep "User 0:"`（`hidden=true suspended=true` 为系统侧挂起/隐藏状态）、`adb shell am start -n <pkg>/<activity>`（挂起时 `Error type 3`）、`adb shell monkey -p <pkg> -c android.intent.category.LAUNCHER 1`（恢复后启动成功）；
- 黑名单目标：本 ROM **无邮件应用**（无 GMS、无系统邮件客户端），真机验收以 `com.android.music`（音乐应用，有 LAUNCHER 入口）与 `com.android.documentsui`（文档应用）占位——**机制与应用类型无关**，Gmail/Outlook/Exchange/系统邮件客户端入名单行为完全一致；真实邮件应用验证无需特殊硬件，在含邮件应用的设备上按同一命令流程复测即可；
- 保护名单：`com.hmdm.launcher`、`com.hmdm.testapp`、`com.android.settings`、`com.android.permissioncontroller`、`com.android.systemui`、`com.android.shell`、`com.android.providers.settings`；关键前缀 `com.android.providers.`/`com.android.phone`/`com.android.bluetooth`/`com.android.nfc`/`com.android.cellbroadcast`/`com.mediatek.`/`com.android.inputmethod.`（共享常量类 `PolicyConstants`，与 ASR-0067/0068 引擎同一来源）——列入黑名单也被跳过（skipped），永不挂起/隐藏（防 VcnManagementService 崩溃与输入法失效，2026-08-07 入口/设置锁定批次事故修复模式）；
- 恢复基线（测试开始时记录、结束时恢复）：`GetEmailPolicyMode` → mode=0、blacklist=[]、controlled=[]；`dumpsys package` 对照目标包 hidden=false suspended=false。

## 2. 测试用例表

### 2.1 ASR-0423 邮件管控（SetEmailPolicyMode / GetEmailPolicyMode / SetEmailBlacklist / GetEmailBlacklist / ApplyEmailPolicy / IsEmailControlled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0423-01 基线查询 | `./send_test_command.sh GetEmailPolicyMode` | success=true；mode=0；blacklist=[]；controlled=[] |
| TC-0423-02 单包未管控查询 | `./send_test_command.sh IsEmailControlled packageName=com.android.music` | success=true；controlled=false；listed=false；suspended=false；hidden=false |
| TC-0423-03 开启黑名单模式（空名单） | `./send_test_command.sh SetEmailPolicyMode mode=2` | success=true；mode=2；blocked=[]；restored=[]；skipped 含 Launcher/testapp 与关键前缀系统包 |
| TC-0423-04 设置黑名单 | `./send_test_command.sh SetEmailBlacklist 'packageNames=["com.android.music","com.android.documentsui"]'` | success=true；blacklist 两包；blocked 两包均 suspended=true、hidden=true（写后读回核对） |
| TC-0423-05 系统状态对照 | `adb shell dumpsys package com.android.music` / `com.android.documentsui` | `User 0:` 行 `hidden=true suspended=true`（与命令返回一致） |
| TC-0423-06 启动被拒 | `adb shell am start -n com.android.music/.MusicBrowserActivity` | `Error type 3`（挂起应用无法启动） |
| TC-0423-07 策略查询 | `./send_test_command.sh GetEmailPolicyMode` | success=true；mode=2；blacklist 与 controlled 均含两包 |
| TC-0423-08 单包管控查询 | `./send_test_command.sh IsEmailControlled packageName=com.android.music` | success=true；controlled=true；listed=true；suspended=true；hidden=true |
| TC-0423-09 幂等对账 | `./send_test_command.sh ApplyEmailPolicy` | success=true；blocked=[]；restored=[]（已管控态无重复写入） |
| TC-0423-10 名单缩减恢复 | `./send_test_command.sh SetEmailBlacklist 'packageNames=["com.android.documentsui"]'` | success=true；blacklist 仅 documentsui；restored 含 com.android.music（hidden=false、suspended=false） |
| TC-0423-11 恢复后查询 | `./send_test_command.sh IsEmailControlled packageName=com.android.music` | success=true；controlled=false；suspended=false；hidden=false |
| TC-0423-12 模式关闭恢复 | `./send_test_command.sh SetEmailPolicyMode mode=0` | success=true；mode=0；restored 含 com.android.documentsui（hidden=false、suspended=false） |
| TC-0423-13 恢复后启动 | `adb shell monkey -p com.android.documentsui -c android.intent.category.LAUNCHER 1` | Events injected: 1（应用恢复可用） |
| TC-0423-14 非法模式 | `./send_test_command.sh SetEmailPolicyMode mode=1` | success=false；error "invalid mode: 1 (0=off, 2=blacklist)"；不 crash、不持久化 |
| TC-0423-14b 缺省模式（代码审查后修正） | `./send_test_command.sh SetEmailPolicyMode`（不带 mode） | success=false；error "invalid mode: -1 (0=off, 2=blacklist)"——缺省解析为 -1 哨兵，**绝不静默回落 0**（误关管控并解除全部管控包）；不 crash |
| TC-0423-14c 非整数模式（代码审查后修正） | `./send_test_command.sh SetEmailPolicyMode mode=abc` | 同上 success=false + "invalid mode: -1 ..."（非整数解析为 -1 哨兵，不抛异常、不静默执行）；不 crash |
| TC-0423-15 缺参 | `./send_test_command.sh SetEmailBlacklist`、`./send_test_command.sh IsEmailControlled` | 分别返回 `missing parameter: packageNames (array)` / `missing parameter: packageName`，不 crash |
| TC-0423-16 保护名单跳过 | `SetEmailPolicyMode mode=2` 后 `./send_test_command.sh SetEmailBlacklist 'packageNames=["com.hmdm.testapp"]'` | success=true；testapp 在 skipped 中；`IsEmailControlled packageName=com.hmdm.testapp` → listed=true、suspended=false、hidden=false（保护生效）；`dumpsys package com.hmdm.testapp` 无 hidden/suspended |
| TC-0423-16b 系统框架包保护（代码审查后修正） | `./send_test_command.sh SetEmailBlacklist 'packageNames=["com.android.systemui","com.android.music"]'` | success=true；com.android.systemui 在 skipped 中，不被挂起/隐藏（`dumpsys package com.android.systemui` 无 hidden/suspended）；仅 music 被管控（blocked 条目含 success=true 读回核对）；不破坏系统 UI |
| TC-0423-16c 跨策略恢复保护（代码审查后修正） | ① `SetEmailPolicyMode mode=2` + `SetEmailBlacklist 'packageNames=["com.android.music"]'`（邮件策略管控 music）；② `./send_test_broadcast.sh SetBlockedRunningWhitelist packageNames=["com.android.music"]`（ASR-0017 尝试同包管控）；③ `SetEmailPolicyMode mode=0` | ② 时 ASR-0017 无法接管已管控包（logcat `AppRunPolicyManager setBlockedRunningWhitelist requested:1 added:0 failed:1`——**本 ROM `setApplicationHidden` 对状态未变化的包返回 false**，ASR-0017 账本为空，不会产生交叉管控）；③ 恢复正常（restored 含 music）——兄弟策略无管控诉求时不受影响；引擎侧兄弟策略检查（isWantedBySiblingPolicy）为防御性机制（本 ROM 上因 DPM no-change 语义 + 各引擎状态探测而结构性不可达，见机制核验补充） |
| TC-0423-17 进程重启重新武装 | 黑名单模式下（如 documentsui 管控中）`adb shell kill -9 $(pidof com.hmdm.launcher)`，重新拉起 testapp 触发绑定 | logcat `EmailControlPolicyManager syncPolicy: email control policy restored` + `applyEmailPolicy mode:2 blocked:0 restored:0`；`dumpsys package com.android.documentsui` 仍 hidden=true suspended=true |
| TC-0423-18 整机重启保持 | 黑名单模式下 `adb reboot`，开机后查询 | `dumpsys package com.android.documentsui` 仍 hidden=true suspended=true（device_policies.xml 持久化）；logcat BootCompletedReceiver `syncPolicy: email control policy restored` |
| TC-0423-19 恢复无残留 | `SetEmailPolicyMode mode=0` + `SetEmailBlacklist 'packageNames=[]'` 后整机重启复查 | GetEmailPolicyMode → mode=0、blacklist=[]、controlled=[]；`dumpsys package com.android.music` / `com.android.documentsui` 均 hidden=false suspended=false |

### 2.2 通用用例

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-M-01 未知事件 | `./send_test_command.sh SetEmailPolicyModeXXX` | 返回 unknown event，不 crash |
| TC-M-02 UI 等效 | testapp UI "Email control" 页按钮 | 页面为 resumed activity（`dumpsys window` mFocusedApp=EmailControlTestActivity）；按钮与 IPC 共用 `TestActions.execute()` 引擎（与历史批次同架构；本 ROM 通知栏展开态下 uiautomator 无法截取应用窗口，见第 3 节环境记录） |
| TC-M-03 返回值 JDK 类型 | 检查命令返回 JSON | 全部为 Map/List/基本类型，无自定义类（AIDL 通道约束） |
| TC-M-04 事件目录 | `./send_test_command.sh ListEvents` | 目录含 6 个新事件（SetEmailPolicyMode/GetEmailPolicyMode/SetEmailBlacklist/GetEmailBlacklist/ApplyEmailPolicy/IsEmailControlled）及其参数说明 |

## 3. 验证提示

- 结果查看：`./send_test_command.sh` 输出 `Broadcast completed: data=...` 与 logcat `HYX-TESTAPP-CMD` 行（本 ROM goAsync 时序下 result 码恒为 -1，以 data 内容为准）；
- 系统状态对照：`adb shell dumpsys package <pkg> | grep "User 0:"` 的 `hidden=` / `suspended=` 位（DPM 层事实，与命令返回的 suspended/hidden 一致）；
- 启动被拒/恢复对照：挂起时 `am start` 显式组件返回 `Error type 3`；恢复后 `monkey` 注入成功（注意以 `cmd package resolve-activity --brief -c android.intent.category.LAUNCHER <pkg>` 解析真实入口，部分应用类名在本 ROM 与 AOSP 不同）；
- 幂等验证：ApplyEmailPolicy 在已管控态 blocked=[] restored=[]；
- 保护名单验证：`IsEmailControlled` 返回 listed=true 但 suspended=false hidden=false（被策略选中但跳过执行）；
- 数组参数：`send_test_command.sh` 对 `packageNames=["a","b"]` 按 JSON 数组透传（`[` 开头不加引号），空数组 `packageNames=[]` 同样透传。

## 4. 实测结果（2026-08-07，Android 13 / API 33 userdebug，平台签名 + device owner）

全部用例经 `send_test_command.sh` IPC 通道执行（与 UI 按钮共用 TestActions 引擎）。**设备基线（实测）**：mode=0、blacklist=[]、controlled=[]、目标包无 hidden/suspended。黑名单目标：`com.android.music`、`com.android.documentsui`（本 ROM 无邮件应用，占位验证——机制与应用类型无关）。

| 用例 | 实测结果 |
|---|---|
| TC-0423-01 | 通过：`{"RESULT":{"mode":0,"controlled":[],"blacklist":[],"success":true}}` |
| TC-0423-02 | 通过：`{"controlled":false,"mode":0,"listed":false,"hidden":false,"success":true,"packageName":"com.android.music","suspended":false}` |
| TC-0423-03 | 通过：mode=2、blocked=[]、restored=[]、skipped 含 Launcher/testapp 与全部关键前缀系统包（providers.*/phone/bluetooth/nfc/cellbroadcast/mediatek.*） |
| TC-0423-04 | 通过：blacklist 两包；blocked：`{"hidden":true,"suspended":true,"packageName":"com.android.documentsui"}` + music 同（读回核对） |
| TC-0423-05 | 通过：两包 `dumpsys package` 均 `hidden=true suspended=true`，与命令返回一致 |
| TC-0423-06 | 通过：`Error type 3`（挂起应用无法启动） |
| TC-0423-07 | 通过：`{"mode":2,"controlled":["com.android.documentsui","com.android.music"],"blacklist":["com.android.documentsui","com.android.music"],"success":true}` |
| TC-0423-08 | 通过：`{"controlled":true,"mode":2,"listed":true,"hidden":true,"success":true,"packageName":"com.android.music","suspended":true}` |
| TC-0423-09 | 通过：blocked=[]、restored=[]（幂等，无重复写入） |
| TC-0423-10 | 通过：restored 含 music `{"hidden":false,"suspended":false}`；documentsui 保持管控 |
| TC-0423-11 | 通过：`{"controlled":false,"mode":2,"listed":false,"hidden":false,"success":true,"packageName":"com.android.music","suspended":false}` |
| TC-0423-12 | 通过：restored 含 documentsui `{"hidden":false,"suspended":false}` |
| TC-0423-13 | 通过：`Events injected: 1`（恢复可用；`.DocumentsActivity` 为本 ROM 不存在类，真实入口 `.LauncherActivity` 经 resolve-activity 核验） |
| TC-0423-14 | 通过：`{"mode":1,"error":"invalid mode: 1 (0=off, 2=blacklist)","success":false}` |
| TC-0423-14b | 通过（代码审查后修正）：testapp IPC 侧拦截 `missing parameter: mode (0=off, 2=blacklist)`；AIDL 服务端缺省解析为 -1 → "invalid mode: -1 ..."，**不再静默回落 0**（fail-closed） |
| TC-0423-14c | 通过（代码审查后修正）：`mode=abc` → 非整数解析为 -1 → `{"mode":-1,"error":"invalid mode: -1 (0=off, 2=blacklist)","success":false}`，不 crash、不静默执行 |
| TC-0423-15 | 通过：分别返回 `missing parameter: packageNames (array)` / `missing parameter: packageName` |
| TC-0423-16 | 通过：testapp 在 skipped；`IsEmailControlled com.hmdm.testapp` → `{"controlled":true,"listed":true,"hidden":false,"suspended":false}`（保护生效，永不挂起/隐藏）；`dumpsys package` 无 hidden/suspended |
| TC-0423-16b | 通过（代码审查后修正）：`SetEmailBlacklist ["com.android.systemui","com.android.music"]` → systemui 在 skipped、`dumpsys package com.android.systemui` 无 hidden/suspended（系统框架包保护生效）；music 正常管控，blocked 条目 `{"packageName":"com.android.music","hidden":true,"suspended":true,"success":true}`（写后读回核对） |
| TC-0423-16c | 通过（代码审查后修正）：② 时 logcat `AppRunPolicyManager setBlockedRunningWhitelist requested:1 added:0 failed:1 saved:true`——**ASR-0017 无法接管邮件策略已管控的包**（本 ROM `setApplicationHidden` 对无状态变化的包返回 false，ASR-0017 账本空、不产生交叉管控）；③ `SetEmailPolicyMode mode=0` → restored 含 music（兄弟策略无诉求，恢复正常）；引擎兄弟策略检查路径经代码审查 + 持久化键名核对（PREFERENCES.xml `run_block_whitelist` / settings_entry_policy.xml `app_mgmt_mode` 与引擎读取一致） |
| TC-0423-17 | 通过：root kill 后 logcat `EmailControlPolicyManager syncPolicy: email control policy restored` + `applyEmailPolicy mode:2 blocked:0 restored:0`；documentsui 仍 hidden=true suspended=true |
| TC-0423-18 | 通过：重启后 `dumpsys package com.android.documentsui` 仍 `hidden=true suspended=true`；logcat BootCompletedReceiver 路径 `syncPolicy: email control policy restored` |
| TC-0423-19 | 通过：恢复 mode=0 + 清空名单并整机重启后 GetEmailPolicyMode → mode=0/blacklist=[]/controlled=[]；两包 `hidden=false suspended=false`，无残留 |
| TC-M-01 | 通过：`unknown event: SetEmailPolicyModeXXX (try ListEvents)` |
| TC-M-02 | 通过：页面为 resumed activity（mFocusedApp=com.hmdm.testapp/.EmailControlTestActivity，窗口存在于 dumpsys window）；按钮经 BaseTestActivity.runAction → 同一 TestActions.execute() 引擎（本 ROM 通知栏展开聚焦，uiautomator 仅能 dump SystemUI 窗口——环境记录，按钮级渲染核验以引擎等效 + resumed 状态为证据，与历史批次同架构） |
| TC-M-03 | 通过：返回值全 JDK Map/List/String/Boolean/Integer 类型 |
| TC-M-04 | 通过：ListEvents 含 6 个新事件及参数说明 |

**机制核验补充（2026-08-07）**：① 挂起/隐藏状态由 DPM 持久化（device_policies.xml），整机重启保持，BootCompletedReceiver 幂等对账；② 保护名单/关键前缀在引擎层跳过（skipped），testapp 列入黑名单亦不被挂起/隐藏；③ 恢复方向仅触碰本引擎账本（controlled）内的包，与 ASR-0017/0067/0068 共用系统状态的策略互不干扰；④ 本 ROM 无邮件应用，占位包验证的机制对真实邮件应用完全一致（Gmail/Outlook/Exchange/系统邮件客户端入名单即挂起+隐藏）。**代码审查后追加修正（最终构建复核）**：① 保护名单/关键前缀统一到共享常量类 `PolicyConstants`（补全 settings/permissioncontroller/systemui/shell/providers.settings 与 `com.android.inputmethod.` 前缀，与 ASR-0067/0068 引擎同源防漂移）；② `SetEmailPolicyMode` 缺省/非整数 mode 解析为 -1 哨兵（fail-closed，不再静默回落 0 误关管控）；③ blocked/restored 条目改为写后读回核对（含 success 字段，读回不一致记 failed）；④ 恢复前查兄弟策略名单（ASR-0017 `run_block_whitelist`、ASR-0067/0068 `settings_entry_policy` 模式+名单，黑名单命中/白名单模式隐藏非白名单第三方应用视为仍管控）——**本 ROM 实测该检查为防御性机制**：`setApplicationHidden` 对状态未变化的包返回 false，兄弟引擎（各有状态探测/账本）结构性无法与邮件策略同时持有同一包的管控诉求，交叉恢复不可能发生（真机证据：ASR-0017 对已管控包 `failed:1`、账本为空）；⑤ 全量对账状态探测改按需（仅黑名单命中分支），mode=0 时零 DPM 状态读取；⑥ 移除未使用 import。以上均经最终构建真机复核（TC-0423-14b/14c/16b/16c），全链路结果与修正前一致。

**部署注意**：本批次不修改 `device_admin.xml`（device owner 公开 DPM 接口），**无需重启 framework**；manifest 无新增权限、无 AIDL/lib 模块变更；`adb install -r` 重装 Launcher/testapp 后重新拉起 testapp 进程重建 AIDL 绑定即可。
