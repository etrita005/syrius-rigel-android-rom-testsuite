# 组件/默认应用管控（ASR-0019/0087/0089/0094）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 实现机制 |
|---|---|---|---|
| ASR-0019 | 应用运行 | 禁用/启用**应用组件**（组件级或整应用级） | 公开 `PackageManager.setComponentEnabledSetting(ComponentName, int, int)`（组件级）/ `setApplicationEnabledSetting(String, int, int)`（整应用级），签名权限 `CHANGE_COMPONENT_ENABLED_STATE`（平台签名 uid=1000 自动授予，manifest 既有声明）；写后 `getComponentEnabledSetting`/`getApplicationEnabledSetting` 读回核对 |
| ASR-0087 | 默认应用 | 设置**默认短信应用** | `RoleManager`（@SystemApi）设置 `android.app.role.SMS` 角色持有者；本 ROM 无同步 `setRoleHolder`，落地为异步 `addRoleHolderAsUser(String, String, int, UserHandle, Executor, Consumer<Boolean>)` 反射调用 + 回调等待 + `getRoleHolders` 读回核对 |
| ASR-0089 | 默认应用 | 设置默认拨号应用（通话应用） | 同上，角色 `android.app.role.DIALER` |
| ASR-0094 | 默认应用 | 设置默认 Assistant | 同上，角色 `android.app.role.ASSISTANT`（本 ROM 无合格应用，见 2.4/8 节） |

**归属**：按需求文档归属列，四项均为「Launcher（MDM）+ 系统 API」（依赖平台签名 uid=1000：CHANGE_COMPONENT_ENABLED_STATE / MODIFY_PHONE_STATE 签名权限，平台签名自动授予；manifest 既有声明，无新增权限）。**无需 ROM 改动**。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署；bootloader 锁定，不可改动 system 分区。测试前设备基线（2026-08-06 实测）：`dumpsys role` 的 `android.app.role.SMS` 持有者 `com.android.mms`、`android.app.role.DIALER` 持有者 `com.android.dialer`、`android.app.role.ASSISTANT` 无持有者；`Settings.Secure.assistant` 为空；testapp 组件与整应用均默认启用（DEFAULT）。

## 2. 技术选型与可行性核验

### 2.1 ASR-0019 应用组件禁用/启用（PackageManager 公开 API）

- **组件级**：`PackageManager.setComponentEnabledSetting(ComponentName, COMPONENT_ENABLED_STATE_DISABLED/ENABLED, DONT_KILL_APP)`；**整应用级**：`setApplicationEnabledSetting(packageName, ...)`（用户需求指定的 API，即本仓库既有输入法按包禁用/启用 `InputMethodManagerHelper` 所用方法，Sheet1 原"部分完成"项仅覆盖输入法场景，本批补齐通用组件级接口）；
- 权限：两方法对 targetSdk≥24 的目标包均要求调用方持有 `CHANGE_COMPONENT_ENABLED_STATE`（签名权限，manifest 第 27 行既有声明，平台签名自动授予，`dumpsys package com.hmdm.launcher` granted=true 核验）；device owner 身份非必需；
- 效果（本 ROM 真机实测）：组件被禁用后 `am start` 显式启动该 Activity 返回 `Error type 3`（ActivityNotFoundException），`resolveActivity` 返回 null；整应用被禁用后该应用所有组件不可启动/广播接收器不再投递；`DONT_KILL_APP` 下运行中的进程不被终止；
- 查询：`getComponentEnabledSetting`/`getApplicationEnabledSetting` 返回设置态（DEFAULT=0/ENABLED=1/DISABLED=2/DISABLED_USER=3/DISABLED_UNTIL_USED=4）；DEFAULT 态经 manifest 默认（ApplicationInfo.enabled / ActivityInfo 等）解析真实有效态；
- 持久化：PMS 持久化于 `/data/system/users/0/package-restrictions.xml`，整机重启保持（真机实测：重启前禁用的组件重启后仍为禁用）；
- **Launcher 自保护**：目标包为 Launcher 自身（`com.hmdm.launcher`）时确定性拒绝（`self-protection`），防止误禁用导致设备失去管控通道。

### 2.2 默认应用角色通道（RoleManager @SystemApi，重点核验）

Sheet1 P1 规划路径为 `RoleManager.setRoleHolder` @SystemApi 反射。**本 ROM（MTK fork）核验结论（2026-08-06，运行时反射核验）**：

- `RoleManager.setRoleHolder(String, String, int)`（AOSP 12+ 同步 API）**本 ROM 不存在**；`setRoleHolderAsUser` 亦不存在；
- 本 ROM 实际方法集（`getDeclaredMethods` 运行时 dump 核验）：`addRoleHolderAsUser(String, String, int, UserHandle, Executor, Consumer<Boolean>)`（异步 + 回调）、`removeRoleHolderAsUser(...)`、`clearRoleHoldersAsUser(...)`、`setBrowserRoleHolder(String, int)`（仅浏览器角色）、`getSmsRoleHolder(int)`、`getRoleHolders(String)`、`getRoleHoldersAsUser(String, UserHandle)`、`setBypassingRoleQualification(boolean)`（调试辅助，未采用）等——即 R 风格异步 API 集，无 T 风格同步 setter；
- **引擎落地**：反射 `addRoleHolderAsUser`，`Executor` 传新建单线程池、回调以 `CountDownLatch`（20 秒超时）同步等待，调用方（平台签名 uid=1000，持 MODIFY_PHONE_STATE）为框架**可信调用方**，角色直接应用**无确认弹窗**（真机实测无 UI 弹窗、无前台 Activity 变化）；`UserHandle` 参数**必须非空**（本 ROM `RoleManager.addRoleHolderAsUser` 内 `Objects.requireNonNull(user)`，传 null 抛 NPE——初版实现曾传 null 触发，已修正为 `UserHandle.of(当前用户)`，经 `UserHandle.of(int)` 反射构造——本项目 stripped SDK 不暴露该方法）；
- 查询：反射 `getRoleHolders(String)`（回退 `getRoleHoldersAsUser(String, UserHandle)`），与 `dumpsys role` 的 holders 段对照一致；
- 持久化：角色变更由 RoleManagerService 落盘 `/data/system/roles/roles.xml`，整机重启保持（框架职责，Launcher 不直写文件）。

### 2.3 框架角色资格校验（重点核验）

RoleManagerService 对角色持有者有**包级资格校验**（`RoleControllerServiceImpl`），不满足时拒绝变更（真机实测 logcat）：

| 角色 | 资格要求（本 ROM 实测） | 本机合格包 |
|---|---|---|
| `android.app.role.SMS` | 需含接收 `android.provider.Telephony.SMS_DELIVER` 的组件且持 `BROADCAST_SMS` 权限（缺一即拒："not qualified ... due to missing RequiredComponent{mIntentFilterData=...SMS_DELIVER..., mPermission='android.permission.BROADCAST_SMS'}"） | `com.android.mms`（唯一） |
| `android.app.role.DIALER` | 需具备默认拨号器资格（拨号 Activity + telephony 能力） | `com.android.dialer`（唯一；`com.mediatek.autodialer` 不合格） |
| `android.app.role.ASSISTANT` | 需含 assist service 或 assist activity（"missing service and missing activity"） | **无任何合格包**（`com.mediatek.voicecommand`/`voiceunlock` 均不合格） |

不满足资格时：`addRoleHolderAsUser` 回调收到 `false`（`accepted=false`，无异常抛出），角色持有者不变——命令如实返回 `success:false`（附 accepted=false + 原持有者），不误报成功。

### 2.4 ASR-0094 本机环境限制

本机无任何可成为 Assistant 的应用（无 GMS、`com.mediatek.voicecommand` 等 MTK 语音应用无 assist service/activity 组件），ASSISTANT 角色恒为空持有者。因此：**查询路径（空持有者）与框架拒绝路径（设置不合格包被拒）在本机可完整验证；设置到合格 Assistant 应用的路径需具备合格助理应用（含 assist 组件）的设备验收**——计入需求文档"硬件受限测试说明"（详见 8 节）。

### 2.5 系统配置声明

无 `device_admin.xml` 变更；无 manifest 新增权限（`CHANGE_COMPONENT_ENABLED_STATE` 与 `MODIFY_PHONE_STATE` 均为既有声明）；角色状态由框架持久化（roles.xml），组件状态由 PMS 持久化（package-restrictions.xml）；无需重启 framework。

## 3. 命令接口定义（SystemApiInterface.onEvent）

### 3.1 新增命令（本批次，8 条）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetComponentEnabled` | packageName（必填）、component（可选 `pkg/class`，缺省=整应用）、enabled（必填） | Map：{success, packageName, component, state, stateName, enabled} 或 {success:false, error} | ASR-0019 |
| `IsComponentEnabled` | packageName（必填）、component（可选，缺省=整应用） | Map：{success, packageName, component, state, stateName, enabled, manifestDefault?, resolvedEnabled?} | ASR-0019 |
| `SetDefaultSmsApp` | packageName（必填） | Map：{success, role, packageName, channel, accepted, holder, holders, heldByTarget} 或 {success:false, error} | ASR-0087 |
| `GetDefaultSmsApp` | 无 | Map：{success, role, holder, holders, count} | ASR-0087 |
| `SetDefaultDialerApp` | packageName（必填） | 同 SetDefaultSmsApp（ROLE_DIALER） | ASR-0089 |
| `GetDefaultDialerApp` | 无 | 同 GetDefaultSmsApp（ROLE_DIALER） | ASR-0089 |
| `SetDefaultAssistant` | packageName（必填） | 同 SetDefaultSmsApp（ROLE_ASSISTANT） | ASR-0094 |
| `GetDefaultAssistant` | 无 | 同 GetDefaultSmsApp（ROLE_ASSISTANT） | ASR-0094 |

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> p = new HashMap<>();
p.put("packageName", "com.hmdm.testapp");
p.put("component", "com.hmdm.testapp/com.hmdm.testapp.StatsQueryTestActivity");
p.put("enabled", false);
Map result = api.onEvent("SetComponentEnabled", p);
// {"RESULT":{"component":"com.hmdm.testapp/.StatsQueryTestActivity","state":2,"stateName":"DISABLED",
//   "packageName":"com.hmdm.testapp","success":true,"enabled":false}}

Map p2 = new HashMap<>();
p2.put("packageName", "com.android.mms");
Map result2 = api.onEvent("SetDefaultSmsApp", p2);
// {"RESULT":{"heldByTarget":true,"role":"android.app.role.SMS","holders":["com.android.mms"],
//   "success":true,"channel":"addRoleHolderAsUser","accepted":true,"holder":"com.android.mms",
//   "packageName":"com.android.mms"}}
```

**广播通道**（TestBroadcast，与现有命令一致）：

```bash
adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast \
  -a ON_SQA_INTERFACE_TEST --es event SetComponentEnabled \
  --es param '{"packageName":"com.android.mms","component":"com.android.mms/com.android.mms.ui.ComposeMessageActivity","enabled":false}'
```

> 注：`send_test_broadcast.sh` 将全部参数值序列化为字符串，`enabled` 经 `Bundle` 到达为 String——引擎提供 `paramBoolean`（兼容 Boolean 与 "true"/"false" 字符串）解析，广播通道与 AIDL 通道行为一致。

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/
│   └── DefaultAppPolicyManager.java   # 新增：组件/默认应用引擎
│       （ASR-0019 setComponentEnabled/isComponentEnabled：
│        setComponentEnabledSetting（组件级）/setApplicationEnabledSetting（整应用级），
│        DONT_KILL_APP，写后读回核对，DEFAULT 态 manifest 解析，
│        Launcher 自保护；组件名解析与包名一致性校验；
│        ASR-0087/0089/0094 setRoleHolder/getRoleState：
│        反射 addRoleHolderAsUser（本 ROM 无同步 setRoleHolder，运行时方法集核验）
│        + CountDownLatch 20s 回调等待 + getRoleHolders 读回核对，
│        UserHandle.of 反射构造（非空必需），框架资格校验失败如实上报）
├── service/command/default_app/
│   ├── SetComponentEnabled.java       # 新增：ASR-0019 设置（paramBoolean 兼容广播字符串布尔）
│   ├── IsComponentEnabled.java        # 新增：ASR-0019 查询
│   ├── SetDefaultSmsApp.java          # 新增：ASR-0087 设置
│   ├── GetDefaultSmsApp.java          # 新增：ASR-0087 查询
│   ├── SetDefaultDialerApp.java       # 新增：ASR-0089 设置
│   ├── GetDefaultDialerApp.java       # 新增：ASR-0089 查询
│   ├── SetDefaultAssistant.java       # 新增：ASR-0094 设置
│   └── GetDefaultAssistant.java       # 新增：ASR-0094 查询
└── service/ApiBinder.java             # 注册 8 个新命令
app/src/main/AndroidManifest.xml       # 无变更（CHANGE_COMPONENT_ENABLED_STATE / MODIFY_PHONE_STATE 既有声明）

testapp/
├── src/main/java/com/hmdm/testapp/
│   ├── DefaultAppVerifier.java                # 新增：本地效果探测
│   │   （TryStartComponent：显式启动 Activity 组件——禁用时 ActivityNotFoundException，
│   │    ASR-0019 端到端证明；ResolveComponent：resolveActivity 交叉核对；
│   │    GetAssistantSetting：读 Settings.Secure.assistant——ASSISTANT 角色持有者镜像键，ASR-0094 交叉核对）
│   ├── DefaultAppComponentTestActivity.java   # 新增：测试页（17 个按钮）
│   ├── TestActions.java                       # 新增 11 个事件（含参数校验）与事件目录
│   └── MainActivity.java                      # 增加 "Component & default app control" 入口
├── src/main/res/layout/activity_default_app_component_test.xml  # 新增测试页布局
└── src/main/AndroidManifest.xml     # 注册 DefaultAppComponentTestActivity
```

## 5. 执行逻辑

```
setComponentEnabled(packageName, component?, enabled):
  1. 缺 packageName → {success:false, error:"missing parameter: packageName"}
  2. packageName == Launcher 自身 → {success:false, error:"self-protection: ..."}
     （防止误禁用导致设备失去管控通道）
  3. 包未安装（getApplicationEnabledSetting 抛 IllegalArgumentException）→
     {success:false, error:"package not installed: ..."}
  4. component 非空：ComponentName.unflattenFromString 解析失败 →
       {success:false, error:"invalid component: ..."}；
     包名与 packageName 不一致 → {success:false, error:"component package mismatch: ... != ..."}
  5. target = enabled ? ENABLED : DISABLED；带 DONT_KILL_APP
     - component 非空 → pm.setComponentEnabledSetting(component, target, DONT_KILL_APP)
     - 否则 → pm.setApplicationEnabledSetting(packageName, target, DONT_KILL_APP)
  6. 读回（getComponentEnabledSetting/getApplicationEnabledSetting），== target → success
  7. 返回 {success, packageName, component?, state, stateName, enabled}

isComponentEnabled(packageName, component?):
  1. 同 1/3/4 校验
  2. 读回设置态 + stateName；enabled = (ENABLED | DISABLED_UNTIL_USED)
  3. 设置态为 DEFAULT 时经 manifest 默认解析（整应用：ApplicationInfo.enabled；
     组件：getActivityInfo/getReceiverInfo/getServiceInfo/getProviderInfo 依次尝试），
     附报 manifestDefault/resolvedEnabled 并回写 enabled
  4. 返回 {success, packageName, component?, state, stateName, enabled, ...}

setRoleHolder(roleName, packageName)（ASR-0087/0089/0094 共用）:
  1. roleName 不在 {SMS, DIALER, ASSISTANT} → {success:false, error:"unsupported role: ..."}
  2. 缺 packageName → error；包未安装 → error
  3. 反射查 addRoleHolderAsUser(String, String, int, UserHandle, Executor, Consumer)：
     不存在 → error + 方法集 dump（诊断辅助）；存在 → channel=addRoleHolderAsUser
  4. invoke(roleName, packageName, 当前用户, UserHandle.of(当前用户)（反射构造，非空必需）,
     单线程 Executor, Consumer<Boolean> 回调 → latch)
  5. latch.await(20s)：超时/中断 → accepted=false；回调 false（框架资格校验拒绝）→ accepted=false
  6. getRoleHolders(roleName)（反射，回退 getRoleHoldersAsUser）读回：
     held = holders.contains(packageName)；success = accepted && held
  7. 返回 {success, role, packageName, channel, accepted, holder, holders, heldByTarget, error?}
```

**安全设计**：角色名称为编译期常量（`android.app.role.SMS/DIALER/ASSISTANT`，不来自调用方输入）；组件名经 `ComponentName.unflattenFromString` 解析并校验包名一致后才用于框架调用；Launcher 自身不可禁用（自保护）；无 shell/无系统文件写入面。

## 6. 权限与归属

- `setComponentEnabledSetting`/`setApplicationEnabledSetting`：`CHANGE_COMPONENT_ENABLED_STATE` 签名权限（manifest 既有声明，平台签名自动授予，`dumpsys package` granted=true 核验）；
- `RoleManager.addRoleHolderAsUser`（@SystemApi）：`MODIFY_PHONE_STATE` 签名权限（manifest 既有声明——通知监听批次已加，平台签名自动授予）；框架侧为可信调用方（免确认弹窗）；
- 无需 device owner 身份、无 shell、无 ROM 改动；不修改 AIDL / lib 模块；
- testapp 无新增权限声明（探测用 `resolveActivity`/`startActivity`/读 Settings.Secure 均为普通权限）。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺 packageName/enabled 参数 | {error:"missing parameter: ..."}（testapp 侧同样拦截并返回提示） |
| 目标为 Launcher 自身 | {success:false, error:"self-protection: com.hmdm.launcher cannot be disabled"} |
| 包未安装 | {success:false, error:"package not installed: ..."} |
| 非法组件格式（非 pkg/class） | {success:false, error:"invalid component: ..."} |
| 组件包名与 packageName 不一致 | {success:false, error:"component package mismatch: ..."} |
| 广播通道布尔参数为字符串（send_test_broadcast.sh 全值字符串化） | 引擎 `paramBoolean` 兼容 Boolean 与 "true"/"false" 字符串（初版 getBoolean 对 String 值返回默认 false，曾致广播通道禁用命令被误解析，已修正） |
| **本 ROM 无同步 setRoleHolder** | 引擎反射 R 风格异步 addRoleHolderAsUser（运行时方法集 dump 核验）；stock AOSP 13 如有 setRoleHolder 亦不依赖——统一走 addRoleHolderAsUser 通道（Q+ 均存在） |
| **本 ROM addRoleHolderAsUser 要求 UserHandle 非空** | `Objects.requireNonNull(user)` NPE 实测；引擎传 `UserHandle.of(当前用户)`（反射构造），不传 null |
| 回调超时/中断 | accepted=false，命令返回 success=false + channel（不悬死） |
| **框架角色资格校验拒绝**（目标包不合格，如 testapp 设 SMS、voicecommand 设 Assistant） | 回调 false（无异常），holder 不变；命令如实返回 {success:false, accepted:false, holder:原持有者}（logcat 框架侧 "not qualified for ... due to missing ..." 可对照） |
| 目标已是当前持有者（幂等） | 回调 true、读回一致，success=true |
| ASSISTANT 角色无任何合格包（本机） | 查询返回空持有者；设置任何包均被框架资格校验拒绝（success=false）——需具备合格助理应用的设备验收（硬件受限项） |
| 组件/应用已处于目标状态 | 读回一致仍返回 success=true（幂等） |
| 组件状态持久化 | PMS 持久化 package-restrictions.xml，重启保持（实测） |
| 反射链路全部失败（极端 ROM） | 方法集 dump 后返回 {success:false, error}，不 crash |

## 8. 真机验证记录（2026-08-06，Android 13 / API 33 userdebug，平台签名 + device owner）

（命令级用例结果见测试用例设计文档，此处记录机制核验关键结论）

| 核验项 | 结果 |
|---|---|
| 设备基线 | SMS 持有者 com.android.mms、DIALER 持有者 com.android.dialer、ASSISTANT 无持有者；Settings.Secure.assistant 空；testapp 组件/整应用均 DEFAULT 启用 |
| **RoleManager 方法集核验（关键修正）** | 运行时 `getDeclaredMethods` dump：**无 setRoleHolder / setRoleHolderAsUser**（Sheet1 规划路径在本 ROM 不存在）；实际为 R 风格 `addRoleHolderAsUser(String, String, int, UserHandle, Executor, Consumer<Boolean>)` 等异步 API；初版 findSetRoleHolderMethod 返回 null 报 "not found"，改 addRoleHolderAsUser 后落地 |
| **addRoleHolderAsUser UserHandle 非空** | 初版传 null → `NullPointerException: user cannot be null`（RoleManager.java:361 requireNonNull）；改传 `UserHandle.of(当前用户)` 后成功 |
| ASR-0087 设置/查询 | SetDefaultSmsApp com.android.mms → channel=addRoleHolderAsUser、accepted=true、heldByTarget=true、success=true；GetDefaultSmsApp → holder=com.android.mms；`dumpsys role` SMS 段对照一致 |
| ASR-0089 设置/查询 | SetDefaultDialerApp com.android.dialer → success=true；GetDefaultDialerApp → holder=com.android.dialer |
| ASR-0094 查询/拒绝路径 | GetDefaultAssistant → holders=[]（空）；SetDefaultAssistant com.mediatek.voicecommand → 框架资格校验拒绝（accepted=false、logcat `AssistantRoleBehavior: ... not qualified ... missing service and missing activity`、`RoleControllerServiceImpl: Package does not qualify`），success=false、holder 不变 |
| 框架资格校验（非合格包） | SetDefaultSmsApp com.hmdm.testapp → 回调 false，logcat `Role: com.hmdm.testapp not qualified for android.app.role.SMS due to missing RequiredComponent{...SMS_DELIVER..., BROADCAST_SMS}`，holder 保持 com.android.mms |
| 免确认弹窗 | 可信调用方（MODIFY_PHONE_STATE）直接应用，无 UI 弹窗（实测无前台 Activity 变化） |
| ASR-0019 组件级 | 禁用 testapp StatsQueryTestActivity → state=DISABLED、am start `Error type 3`、ResolveComponent resolved=false；启用 → state=ENABLED、am start 正常 |
| ASR-0019 跨应用组件级 | 禁用 com.android.mms 的 ComposeMessageActivity → am start Error type 3；启用 → 正常（跨应用组件管控成立） |
| ASR-0019 整应用级 | 禁用 com.hmdm.testapp（setApplicationEnabledSetting）→ state=DISABLED、am start Error type 3、testapp 广播接收器不再投递（IPC 失效）；经 Launcher 广播通道恢复 enabled=true 后 am start 正常（恢复路径验证） |
| ASR-0019 DEFAULT 态解析 | 基线查询 state=0/DEFAULT、manifestDefault=true、resolvedEnabled=true、enabled=true（manifest 默认解析正确） |
| ASR-0019 持久化 | 组件禁用状态经 PMS 持久化，整机重启后保持（重启前禁用的组件重启后仍 DISABLED，恢复流程正常） |
| ASR-0019 自保护/错误路径 | Launcher 自身 → self-protection 拒绝；未安装包/非法组件/包名不匹配/缺参 → 对应 error，不 crash |
| 广播通道布尔 | send_test_broadcast.sh 全值字符串化，`paramBoolean` 兼容后广播通道 enabled=true/false 均正确解析（初版 getBoolean 误把 "true" 当 false，已修正并复测） |
| 测试后设备恢复 | 所有禁用组件/整应用恢复启用；角色持有者保持基线（SMS=com.android.mms、DIALER=com.android.dialer、ASSISTANT=空）；无残留 |

> **本机环境限制（硬件受限项，详见需求文档"硬件受限测试说明"）**：① ASR-0094 设置到**合格 Assistant 应用**的路径本机无法闭环（本机无任何含 assist service/activity 的应用，任何包均被框架资格校验拒绝——设置/拒绝路径与查询路径已完整验证，真实设为默认助理需具备合格助理应用的设备）；② ASR-0087/0089 本机仅 `com.android.mms`/`com.android.dialer` 唯一合格包，设置路径以"重设当前持有者（幂等成功）+ 非合格包拒绝"闭环验证；在含多个合格短信/拨号应用的设备上"切换到另一合格应用"为同机制路径（框架资格校验通过即成功），需补充设备验收。
