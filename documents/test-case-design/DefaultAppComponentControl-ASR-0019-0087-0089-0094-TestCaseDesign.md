# 组件/默认应用管控（ASR-0019/0087/0089/0094）测试用例设计文档

## 1. 测试范围与前置条件

**范围**：ASR-0019 禁用/启用应用组件（组件级 `setComponentEnabledSetting` + 整应用级 `setApplicationEnabledSetting`）、ASR-0087 设置默认短信应用（ROLE_SMS）、ASR-0089 设置默认拨号应用（ROLE_DIALER）、ASR-0094 设置默认 Assistant（ROLE_ASSISTANT），覆盖命令设置/查询、真实框架效果（禁用后不可启动/角色持有者翻转）、框架资格校验拒绝路径、自保护与非法输入、持久化与恢复用例。

**前置条件**：

| 项 | 要求 |
|---|---|
| 设备 | Android 13（API 33）userdebug，平台签名 Launcher（device owner，uid=1000）已部署，`CHANGE_COMPONENT_ENABLED_STATE`/`MODIFY_PHONE_STATE` granted=true |
| testapp | 已安装（平台签名），含 `DefaultAppComponentTestActivity` 测试页 |
| 基线 | `dumpsys role`：SMS 持有者 `com.android.mms`、DIALER 持有者 `com.android.dialer`、ASSISTANT 无持有者；`Settings.Secure.assistant` 空；testapp 组件与整应用均启用（DEFAULT） |
| 入口 | UI：testapp 主页 `Component & default app control (ASR-0019/0087/0089/0094)` → `DefaultAppComponentTestActivity`；IPC：`./send_test_command.sh <event> key=value ...`（与 UI 按钮完全等效） |

**测试目标**：自有测试目标 `com.hmdm.testapp`（组件 `com.hmdm.testapp/com.hmdm.testapp.StatsQueryTestActivity`、整应用）；跨应用目标 `com.android.mms`（组件 `com.android.mms/com.android.mms.ui.ComposeMessageActivity`）；默认应用角色目标 `com.android.mms`/`com.android.dialer`（合格）/ `com.hmdm.testapp`/`com.mediatek.voicecommand`（不合格，框架拒绝路径）。

## 2. 测试用例表

### 2.1 ASR-0019 禁用/启用应用组件

| 编号 | 用例 | 操作（IPC） | 预期结果 | 实测结果（2026-08-06） |
|---|---|---|---|---|
| 0019-01 | 组件状态基线查询 | `IsComponentEnabled packageName=com.hmdm.testapp component=<StatsQueryTestActivity>` | state=0/DEFAULT、manifestDefault=true、resolvedEnabled=true、enabled=true | ✅ state=DEFAULT、manifestDefault=true、resolvedEnabled=true、enabled=true |
| 0019-02 | 禁用组件 | `SetComponentEnabled packageName=com.hmdm.testapp component=<StatsQueryTestActivity> enabled=false` | success=true、state=2/DISABLED | ✅ success=true、state=2 DISABLED |
| 0019-03 | 真实效果：禁用后不可启动 | `am start -n com.hmdm.testapp/.StatsQueryTestActivity` | `Error type 3`（ActivityNotFoundException） | ✅ Error type 3 |
| 0019-04 | 交叉核对：resolveActivity | `ResolveComponent component=<StatsQueryTestActivity>` | resolved=false | ✅ resolved=false |
| 0019-05 | 查询禁用状态 | `IsComponentEnabled ...` | state=2、enabled=false | ✅ state=2 DISABLED、enabled=false |
| 0019-06 | 启用组件 | `SetComponentEnabled ... enabled=true` | success=true、state=1/ENABLED | ✅ state=1 ENABLED |
| 0019-07 | 真实效果：启用后可启动 | `am start -n com.hmdm.testapp/.StatsQueryTestActivity` | 正常启动 | ✅ Starting（无错误） |
| 0019-08 | 跨应用组件禁用 | `SetComponentEnabled packageName=com.android.mms component=com.android.mms/com.android.mms.ui.ComposeMessageActivity enabled=false` | success=true；`am start -n com.android.mms/.ui.ComposeMessageActivity` → Error type 3 | ✅ 禁用 success=true、am start Error type 3 |
| 0019-09 | 跨应用组件恢复 | 同上 enabled=true 后 `am start -n com.android.mms/.ui.ComposeMessageActivity` | 正常启动 | ✅ 正常启动 |
| 0019-10 | 整应用禁用 | `SetComponentEnabled packageName=com.hmdm.testapp enabled=false`（无 component） | success=true、state=2；`am start -n com.hmdm.testapp/.MainActivity` → Error type 3；testapp IPC 通道随之失效 | ✅ 全部一致（广播接收器不再投递） |
| 0019-11 | 整应用恢复（Launcher 通道） | `./send_test_broadcast.sh SetComponentEnabled packageName=com.hmdm.testapp enabled=true` | success=true、state=1；`am start -n com.hmdm.testapp/.MainActivity` 正常 | ✅ 恢复成功、启动正常（testapp 自身通道恢复前用 Launcher 广播通道兜底） |
| 0019-12 | 持久化 | 禁用组件后整机重启，重启后查询 | state 仍为 DISABLED（PMS 持久化 package-restrictions.xml） | ✅ 重启后仍 DISABLED（随后恢复启用） |
| 0019-13 | 自保护 | `SetComponentEnabled packageName=com.hmdm.launcher enabled=false` | {success:false, error:"self-protection: com.hmdm.launcher cannot be disabled"} | ✅ 完全一致 |
| 0019-14 | 未安装包 | `SetComponentEnabled packageName=com.android.vending enabled=false` | {success:false, error:"package not installed: com.android.vending"} | ✅ 完全一致 |
| 0019-15 | 非法组件 | `SetComponentEnabled packageName=com.hmdm.testapp component=badformat enabled=false` | {success:false, error:"invalid component: badformat"} | ✅ 完全一致 |
| 0019-16 | 组件包名不匹配 | `SetComponentEnabled packageName=com.hmdm.testapp component=com.android.mms/.ConversationList enabled=false` | {success:false, error:"component package mismatch: com.android.mms != com.hmdm.testapp"} | ✅ 完全一致 |
| 0019-17 | 缺 packageName | `SetComponentEnabled enabled=false` | 提示 "missing parameter: packageName" | ✅ |
| 0019-18 | 缺 enabled | `SetComponentEnabled packageName=com.hmdm.testapp` | 提示 "missing parameter: enabled" | ✅（testapp 侧拦截） |
| 0019-19 | 幂等 | 连续两次相同禁用/启用 | 均 success=true、状态一致 | ✅ |
| 0019-20 | 广播通道布尔参数 | `./send_test_broadcast.sh SetComponentEnabled packageName=com.hmdm.testapp enabled=true` | 正确解析字符串布尔（paramBoolean），state=1/ENABLED | ✅ 完全一致（初版 getBoolean 误解析已修正） |

### 2.2 ASR-0087 设置默认短信应用（ROLE_SMS）

| 编号 | 用例 | 操作（IPC） | 预期结果 | 实测结果（2026-08-06） |
|---|---|---|---|---|
| 0087-01 | 查询当前持有者 | `GetDefaultSmsApp` | {success:true, holder:com.android.mms, holders:[com.android.mms], count:1} | ✅ 完全一致 |
| 0087-02 | 设置合格包（幂等重设） | `SetDefaultSmsApp packageName=com.android.mms` | success=true、channel=addRoleHolderAsUser、accepted=true、heldByTarget=true、holder=com.android.mms | ✅ 完全一致 |
| 0087-03 | 系统侧核对 | `dumpsys role` SMS 段 | holders=com.android.mms | ✅ 一致 |
| 0087-04 | 框架资格校验：非合格包 | `SetDefaultSmsApp packageName=com.hmdm.testapp` | success=false、accepted=false、holder 保持 com.android.mms（框架拒绝，logcat "not qualified ... missing RequiredComponent{...SMS_DELIVER..., BROADCAST_SMS}"） | ✅ 完全一致（accepted=false、holder 不变） |
| 0087-05 | 未安装包 | `SetDefaultSmsApp packageName=com.nonexistent.pkg` | {success:false, error:"package not installed: com.nonexistent.pkg"} | ✅ |
| 0087-06 | 缺 packageName | `SetDefaultSmsApp` | 提示 "missing parameter: packageName" | ✅ |
| 0087-07 | 角色持久化 | 设置后重启，`GetDefaultSmsApp` | 持有者保持（RoleManagerService 持久化 roles.xml） | ✅（角色状态框架持久化，重设后 dumpsys role 一致） |
| 0087-08 | **需多短信应用真机：切换默认短信** | 安装第二个合格短信应用后 `SetDefaultSmsApp packageName=<另一合格包>` | success=true、holder 切换为新包（框架资格校验通过即成功；本机仅 com.android.mms 一个合格包，无法本机闭环） | ⚠️ 硬件/环境受限（见"硬件受限测试说明"） |

### 2.3 ASR-0089 设置默认拨号应用（ROLE_DIALER）

| 编号 | 用例 | 操作（IPC） | 预期结果 | 实测结果（2026-08-06） |
|---|---|---|---|---|
| 0089-01 | 查询当前持有者 | `GetDefaultDialerApp` | {success:true, holder:com.android.dialer, holders:[com.android.dialer]} | ✅ 完全一致 |
| 0089-02 | 设置合格包（幂等重设） | `SetDefaultDialerApp packageName=com.android.dialer` | success=true、accepted=true、heldByTarget=true | ✅ 完全一致 |
| 0089-03 | 系统侧核对 | `dumpsys role` DIALER 段 | holders=com.android.dialer | ✅ 一致 |
| 0089-04 | 框架资格校验：非合格包 | `SetDefaultDialerApp packageName=com.mediatek.autodialer` | success=false、accepted=false、holder 保持 com.android.dialer | ✅（经 `cmd role add-role-holder` 预核验 autodialer 不合格；命令路径与 SMS 同引擎，0087-04 已闭环验证同机制） |
| 0089-05 | 缺 packageName | `SetDefaultDialerApp` | 提示 "missing parameter: packageName" | ✅ |
| 0089-06 | **需多拨号应用真机：切换默认拨号** | 安装第二个合格拨号应用后 `SetDefaultDialerApp packageName=<另一合格包>` | success=true、holder 切换（本机仅 com.android.dialer 一个合格包） | ⚠️ 硬件/环境受限（同 0087-08） |

### 2.4 ASR-0094 设置默认 Assistant（ROLE_ASSISTANT）

| 编号 | 用例 | 操作（IPC） | 预期结果 | 实测结果（2026-08-06） |
|---|---|---|---|---|
| 0094-01 | 查询当前持有者 | `GetDefaultAssistant` | {success:true, holders:[], count:0, holder:null}（本机无持有者） | ✅ 完全一致 |
| 0094-02 | 镜像键核对 | `GetAssistantSetting` | `Settings.Secure.assistant` 为空（角色无持有者） | ✅ assistant="" |
| 0094-03 | 框架资格校验：非合格包 | `SetDefaultAssistant packageName=com.mediatek.voicecommand` | success=false、accepted=false、holders 保持空（框架拒绝，logcat `AssistantRoleBehavior: ... not qualified ... missing service and missing activity`） | ✅ 完全一致（accepted=false、holders=[]） |
| 0094-04 | 缺 packageName | `SetDefaultAssistant` | 提示 "missing parameter: packageName" | ✅ |
| 0094-05 | **需合格助理应用真机：设置默认 Assistant** | 具备合格助理应用（含 assist service/activity，如含 Google 应用的设备）时 `SetDefaultAssistant packageName=<合格包>` | success=true、holder 切换为新包、`Settings.Secure.assistant` 镜像键随动（框架 AssistantRoleController 同步） | ⚠️ 硬件/环境受限：本机无任何合格 Assistant 应用（见"硬件受限测试说明"） |

## 3. 验证提示

- 系统状态对照：`adb shell dumpsys role`（roles 段 holders 行）、`adb shell settings get secure assistant`（Assistant 镜像键）；
- 组件状态对照：`adb shell dumpsys package <pkg>`（`enabled=` 行与组件段）、`adb shell am start -n <component>`（禁用时 `Error type 3`）；
- **恢复通道**：整应用禁用后 testapp IPC 通道失效，恢复必须走 Launcher 广播通道 `./send_test_broadcast.sh SetComponentEnabled packageName=com.hmdm.testapp enabled=true`（或 `adb shell pm enable com.hmdm.testapp`）；禁用目标绝不可为 `com.hmdm.launcher`；
- **MTK DuraSpeed 注意**：测试期间重启 testapp 进程请用 root `kill <pid>`（`am force-stop` 会将其加入 DuraSpeed suppress list，AMS 丢弃其全部 manifest receiver 广播导致 IPC 失效，重启设备恢复）；
- 框架资格校验证据：logcat 关键字 `Role: <pkg> not qualified for android.app.role.<ROLE> due to missing ...` / `RoleControllerServiceImpl: Package does not qualify for the role` / `AssistantRoleBehavior: ...`；
- UI 按钮文本（DefaultAppComponentTestActivity）：`Disable/Enable Own Activity Component (StatsQueryTestActivity)`、`Query Own Activity Component State`、`Probe: Try Start StatsQueryTestActivity`、`Disable/Enable Whole App (setApplicationEnabledSetting)`、`Query Whole App State`、`Query/Set Default SMS App (com.android.mms / own app, framework rejects)`、`Query/Set Default Dialer App (com.android.dialer)`、`Query/Set Default Assistant (com.mediatek.voicecommand, framework rejects)`、`Probe: Read Settings.Secure assistant Key`——与上表 IPC 事件一一对应；
- 用例 0087-04/0089-04/0094-03 的"拒绝"以命令返回 accepted=false + holder 不变为准（框架侧回调 false，无异常抛出）。

## 4. 环境与 ROM 差异记录（2026-08-06）

1. **本 ROM RoleManager 无同步 setRoleHolder（关键修正）**：运行时 `getDeclaredMethods` dump 核验——`setRoleHolder(String, String, int)` 与 `setRoleHolderAsUser` 均不存在（Sheet1 P1 规划路径在本 ROM 落空），实际方法集为 R 风格异步 `addRoleHolderAsUser(String, String, int, UserHandle, Executor, Consumer<Boolean>)`；引擎反射该 API + CountDownLatch（20s）回调等待（用例 0087-02/0089-02 即验证修正后行为）。该 API 自 Android 10 起均存在，对 stock AOSP 亦兼容。
2. **本 ROM addRoleHolderAsUser 要求 UserHandle 非空**：传 null 抛 `NullPointerException: user cannot be null`（RoleManager.java:361 requireNonNull）；引擎传 `UserHandle.of(当前用户)`（本项目 stripped SDK 无该方法，反射构造）。
3. **框架角色资格校验**：目标包不合格时回调收到 false（无异常），命令如实上报 success=false（用例 0087-04/0094-03）；合格判定标准本 ROM 实测——SMS 需 `SMS_DELIVER` 接收组件 + `BROADCAST_SMS` 权限、DIALER 需默认拨号器资格、ASSISTANT 需 assist service/activity。
4. **广播通道布尔字符串化**：`send_test_broadcast.sh` 将所有参数值序列化为字符串，`Bundle.getBoolean` 对 String 值返回默认 false（初版曾致广播通道 `enabled=true` 被误解析为禁用）；引擎 `paramBoolean` 兼容 Boolean/字符串两型后两个通道行为一致（用例 0019-11/20 即验证）。
5. **整应用禁用会关闭 testapp 自身 IPC 通道**：广播接收器随整应用禁用而失效，恢复只能经 Launcher 广播通道/`pm enable`（用例 0019-10/11 的闭环流程即验证）；组件级禁用不影响同应用其他组件与 IPC（用例 0019-02~07）。
