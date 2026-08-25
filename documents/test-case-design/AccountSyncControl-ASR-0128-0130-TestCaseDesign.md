# 账户同步管控（ASR-0128/0130）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner，testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root && dumpsys duraspeed addwhitelist com.hmdm.testapp`（MTK DuraSpeed 白名单，防 manifest receiver 广播被丢弃——force-stop 会把 testapp 加入 suppress list，广播 result=0；测试期间以 root `kill` 替代 `am force-stop` 重启进程，suppress 残留时整机重启清空）、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`（IPC 与 UI 按钮等效，同一 `TestActions.execute()` 引擎）；也可在 testapp UI 的 "Account & backup" 页面点按对应按钮（ASR-0128/0130 分区）；
- 对照命令：`adb shell dumpsys content | grep "Auto sync"`（主开关系统侧状态，按用户 `u0=` 行）、`adb shell dumpsys account` / `pm list accounts`（账户列表）、`adb shell dumpsys content | grep -A2 "Sync adapters"`（同步适配器列表）；
- 恢复基线（测试开始时记录、结束时恢复）：主开关 `Auto sync: u0=true`（出厂默认）、无账户、无同步适配器；
- 本 ROM 特性（2026-08-07 核验）：无 GMS（无 Google 账户类型、无同步适配器），`dumpsys account` 显示 `Accounts: 0`、Sync adapters 段为空——**ASR-0130 的所有用例在无账户下执行，命令如实返回 accounts=[] 与 note；带 Google 账户的真实逐项同步切换需 GMS 设备验收**（见需求文档"硬件受限测试说明"）；主开关状态不是 Settings 键（`settings get global master_sync_automatically` 返回 null），由 SyncManager 持久化，系统对照经 `dumpsys content` "Auto sync" 行。

## 2. 测试用例表

### 2.1 ASR-0128 自动同步（SetAutoSync / IsAutoSync，ContentResolver.setMasterSyncAutomatically）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0128-01 基线查询 | `./send_test_command.sh IsAutoSync` | success=true；enabled=true；masterSyncAutomatically=true（出厂默认） |
| TC-0128-02 禁用自动同步 | `./send_test_command.sh SetAutoSync enabled=false` | success=true；masterSyncAutomatically=false；读回一致 |
| TC-0128-03 查询状态 | `./send_test_command.sh IsAutoSync` | success=true；enabled=false；masterSyncAutomatically=false |
| TC-0128-04 系统对照 | `adb shell dumpsys content | grep "Auto sync"` | `Auto sync: u0=false`，与命令返回一致 |
| TC-0128-05 重复禁用（幂等） | `./send_test_command.sh SetAutoSync enabled=false` | success=true；状态保持 enabled=false，无异常 |
| TC-0128-06 恢复启用 | `./send_test_command.sh SetAutoSync enabled=true` | success=true；masterSyncAutomatically=true；读回一致；`dumpsys content` 对照 `u0=true` |
| TC-0128-07 缺参 | `./send_test_command.sh SetAutoSync` | 返回 `missing parameter: enabled`，不 crash |
| TC-0128-08 非法参数 | `./send_test_command.sh SetAutoSync enabled=abc` | 任意非 `true` 字符串按 `Bundle.getBoolean` 语义解析为 false（执行禁用方向），命令不 crash；`IsAutoSync` 查询如实显示 enabled=false |

### 2.2 ASR-0130 谷歌账户自动同步（SetGoogleAccountAutoSync / IsGoogleAccountAutoSync，setSyncAutomatically）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0130-01 基线查询 | `./send_test_command.sh IsGoogleAccountAutoSync` | success=true；enabled=false（无账户空集合下"未启用"语义）；accounts=[]；note 说明无 Google 账户 |
| TC-0130-02 禁用谷歌账户自动同步 | `./send_test_command.sh SetGoogleAccountAutoSync enabled=false` | success=true；enabled=false；updatedPairs=0（无账户无可操作对象）；note 如实上报 |
| TC-0130-03 查询状态 | `./send_test_command.sh IsGoogleAccountAutoSync` | success=true；enabled=false；accounts=[] |
| TC-0130-04 启用谷歌账户自动同步 | `./send_test_command.sh SetGoogleAccountAutoSync enabled=true` | success=true；updatedPairs=0；note；不 crash（有账户时全部（账户,authority）对置 true 并读回核对） |
| TC-0130-05 查询状态（空集合语义） | `./send_test_command.sh IsGoogleAccountAutoSync` | success=true；enabled=false（无账户→未启用，与设置方向语义自洽） |
| TC-0130-06 缺参 | `./send_test_command.sh SetGoogleAccountAutoSync` | 返回 `missing parameter: enabled`，不 crash |
| TC-0130-07 非法参数 | `./send_test_command.sh SetGoogleAccountAutoSync enabled=abc` | 命令不 crash（布尔解析边界同 TC-0128-08 说明） |

### 2.3 通用用例

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-M-01 缺参容错 | 2 个 Set 命令均不带参数执行 | 均返回缺参提示，不 crash（2 个查询命令无参数） |
| TC-M-02 未知事件 | `./send_test_command.sh SetAutoSyncXXX` | 返回 unknown event，不 crash |
| TC-M-03 UI 等效 | testapp UI "Account & backup" 页 ASR-0128/0130 分区按钮 | 按钮存在（uiautomator dump 可见 "Set Auto Sync Enabled/Disabled"、"Query Auto Sync State"、"Set Google Account Sync Enabled/Disabled"、"Query Google Account Sync State"）；点按 Query 按钮触发与 IPC 完全相同的 `IsAutoSync` 调用（logcat `ApiBinder onEventReceived event:IsAutoSync` + `MdmApiClient` 结果一致——同一 TestActions 引擎） |
| TC-M-04 返回值 JDK 类型 | 检查命令返回 JSON | 全部为 Map/List/基本类型，无自定义类（AIDL 通道约束） |
| TC-M-05 状态恢复 | 用例执行结束后复查主开关与账户 | `dumpsys content` Auto sync 恢复 `u0=true`；无账户；无测试残留 |

## 3. 验证提示

- 结果查看：`./send_test_command.sh` 输出 `Broadcast completed: data=...` 与 logcat `HYX-TESTAPP-CMD` 行；
- 主开关系统对照：`adb shell dumpsys content | grep "Auto sync"`（`u0=true/false` 按用户逐行；命令返回的 masterSyncAutomatically 与之一致）；
- 账户/适配器对照：`adb shell dumpsys account`（Accounts: 0）、`dumpsys content | grep -A2 "Sync adapters"`（本 ROM 为空）；
- 布尔参数说明：`send_test_command.sh` 对 `true`/`false` 透传为 Boolean；数字串（如 `1`）与任意字符串按 `Bundle.getBoolean` 语义处理（非 `true` 均为 false），命令侧不 crash（与既有 `SetBackupDisabled` 等命令行为一致）；
- WRITE_SYNC_SETTINGS 权限对照：`adb shell dumpsys package com.hmdm.launcher | grep WRITE_SYNC_SETTINGS`（应显示 granted=true——`setSyncAutomatically` 服务端强制该校验，manifest 未声明时在 GMS 设备上会抛 SecurityException，本机无账户不触发）。

## 4. 实测结果（2026-08-07，Android 13 / API 33 userdebug，平台签名 + device owner）

全部用例经 `send_test_command.sh` IPC 通道执行（与 UI 按钮共用 TestActions 引擎）。**设备基线（实测）**：`Auto sync: u0=true`（出厂默认）、无账户（`Accounts: 0`）、无同步适配器。测试环境记录：部署后 `am force-stop` 使 testapp 被 MTK DuraSpeed 加入 suppress list（广播 result=0、receiver 不执行）——以 root `kill` 重启进程 + `dumpsys duraspeed addwhitelist com.hmdm.testapp` + 整机重启清空 suppress 后恢复（与 2026-08-05 无障碍批次同环境问题）。

| 用例 | 实测结果 |
|---|---|
| TC-0128-01 | 通过：`{"masterSyncAutomatically":true,"success":true,"enabled":true}` |
| TC-0128-02 | 通过：`{"masterSyncAutomatically":false,"success":true,"enabled":false}`（读回一致） |
| TC-0128-03 | 通过：`{"masterSyncAutomatically":false,"success":true,"enabled":false}` |
| TC-0128-04 | 通过：禁用后 `dumpsys content` 显示 `Auto sync: u0=false`，与命令返回一致；恢复后 `u0=true` |
| TC-0128-05 | 通过：success=true，幂等无异常，状态保持 disabled |
| TC-0128-06 | 通过：`{"masterSyncAutomatically":true,"success":true,"enabled":true}`；`dumpsys content` 对照 `u0=true`（双向切换均读回核对通过） |
| TC-0128-07 | 通过：`missing parameter: enabled` |
| TC-0128-08 | 通过：`enabled=abc` 解析为 false（执行禁用方向返回 success=true），不 crash；`IsAutoSync` 如实显示 enabled=false |
| TC-0130-01 | 通过：`{"note":"no google accounts on device (no GMS)","accounts":[],"success":true,"enabled":false}` |
| TC-0130-02 | 通过：success=true，enabled=false，updatedPairs=0，note（"no google accounts on device (no GMS), nothing to toggle"） |
| TC-0130-03 | 通过：`{"note":"no google accounts on device (no GMS)","accounts":[],"success":true,"enabled":false}` |
| TC-0130-04 | 通过：success=true，updatedPairs=0，note，不 crash |
| TC-0130-05 | 通过：success=true，enabled=false（无账户空集合语义，如实上报） |
| TC-0130-06 | 通过：`missing parameter: enabled` |
| TC-0130-07 | 通过：`enabled=abc` 解析为 false，不 crash |
| TC-M-01 | 通过：2 个 Set 命令缺参均返回缺参提示，不 crash（2 个查询命令无参数） |
| TC-M-02 | 通过：`SetAutoSyncXXX` → `unknown event: SetAutoSyncXXX (try ListEvents)` |
| TC-M-03 | 通过：按钮渲染（uiautomator dump 见 8 个同步按钮）；点按 "Query Auto Sync State" 触发 `IsAutoSync`（logcat `ApiBinder onEventReceived event:IsAutoSync` + `MdmApiClient: call IsAutoSync result: {RESULT={masterSyncAutomatically=true, success=true, enabled=true}}`），与 IPC 返回一致 |
| TC-M-04 | 通过：返回值全 JDK Map/List/String/Boolean/Integer 类型 |
| TC-M-05 | 通过：恢复基线——`dumpsys content` `Auto sync: u0=true`、无账户、无残留 |

**机制核验补充（2026-08-07）**：① 主开关非 Settings 键（`settings get global master_sync_automatically` / secure 变体均 null），系统对照通道为 `dumpsys content` "Auto sync" 行；② `WRITE_SYNC_SETTINGS` 为 `setSyncAutomatically` 的强制前置——manifest 新增声明后 `dumpsys package com.hmdm.launcher` 显示 granted=true（本机无账户不实际触发该校验，代码审查 + 权限核验兜底）；③ 整机重启后主开关状态与重启前一致（SyncManager 持久化）。

**部署注意**：本批次不修改 `device_admin.xml`（公开 ContentResolver 接口），**无需重启 framework**；manifest 新增 `WRITE_SYNC_SETTINGS`（normal），`adb install -r` 重装 Launcher 后需重新拉起 HOME 并重启 testapp 进程重建 AIDL 绑定；force-stop 触发 DuraSpeed suppress 时以 root kill / 整机重启恢复（见前置条件）。
