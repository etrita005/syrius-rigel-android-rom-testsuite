# 权限/AppOps 管控（ASR-0043/0044/0045/0046/0149）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 实现机制 |
|---|---|---|---|
| ASR-0043 | 权限 | 禁用/启用指定应用的**画中画**能力 | `AppOpsManager.setMode(OP_PICTURE_IN_PICTURE, uid, pkg, MODE_IGNORED/ALLOWED)`，框架在应用请求进入画中画时消费该 op |
| ASR-0044 | 权限 | 禁用/启用指定应用的**写设置**（WRITE_SETTINGS）能力 | `AppOpsManager.setMode(OP_WRITE_SETTINGS, ...)`；SettingsProvider 对写 Settings.System/Global 的调用执行该 op 检查（本 ROM 实测拒绝时抛 SecurityException 或记录 Reject） |
| ASR-0045 | 权限 | **授予/取消绑定通知监听服务**的权限（免交互） | `NotificationManager.setNotificationListenerAccessGranted(ComponentName, boolean)`（@hide，需 MODIFY_PHONE_STATE 签名权限），本 ROM 存在该方法（channel=nm 实测通过）；缺失 ROM 回退直写 `Settings.Secure.enabled_notification_listeners`（NMS 持有该键的 SettingsObserver） |
| ASR-0149 | WLAN | 是否有使用 WLAN 权限的**应用黑名单** | 策略模式（0=关闭，2=黑名单）+ 黑名单（整体替换），对名单内应用 `setMode(OP_CHANGE_WIFI_STATE, IGNORED)`、名单外应用 `setMode(ALLOWED)`；WifiService 的 setWifiEnabled/addNetwork 等操作消费该 op |
| ASR-0046 | 权限 | **授予特定包 USB 权限**（免交互） | `UsbManager.grantPermission(UsbDevice, int uid)`（@hide，需 MANAGE_USB 签名权限），授权记录持久化于框架 UsbSettings（/data/system/usb_device_manager.xml） |

**归属**：按需求文档归属列，五项均为「Launcher（MDM）+ 系统 API」（依赖平台签名 uid=1000：MANAGE_APP_OPS_MODES / MODIFY_PHONE_STATE / MANAGE_USB 签名权限，平台签名自动授予；manifest 新增声明 MANAGE_USB 与 MODIFY_PHONE_STATE）。**无需 ROM 改动**。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署；bootloader 锁定，不可改动 system 分区。测试前设备基线（2026-08-06 实测）：testapp 的 WRITE_SETTINGS/PICTURE_IN_PICTURE/CHANGE_WIFI_STATE 均为默认（允许）状态、无通知监听服务授权、`enabled_notification_listeners` 为空、无 USB host 设备连接（`host_connected=false`，`dumpsys usb` 的 device_manager.host_connected=false）。

## 2. 技术选型与可行性核验

### 2.1 AppOps 通道与字符串 op 关键性（重点）

AOSP 13 的 `AppOpsManager` 公开 API（API 29+）提供 `setMode(String op, int uid, String packageName, int mode)`（SDK 中标注 @hide 但方法本身 public，可反射调用；与既有 `NotificationPolicyManager` OP_POST_NOTIFICATION 通道同模式）。引擎**仅使用字符串 op 变体**：

`setMode(String, int, String, int)` —— 客户端内部经 `opToCode(op)` 用 **ROM 自身 op 表**解析数值 code。

**本 ROM 核验结论（2026-08-06，重点，经 framework dex 反编译核验）**：

- **本 ROM（MTK fork AOSP 13）重排了部分 AppOps op 数值码**：`OP_WRITE_SETTINGS = 23`（AOSP 13 标准为 22，framework.jar `AppOpsManager` 常量表核验），`OP_PICTURE_IN_PICTURE = 67`、`OP_CHANGE_WIFI_STATE = 71` 与 AOSP 一致；
- 初版实现曾采用「整型变体优先」时，`setMode(1, ...)` 在服务端**静默不生效**（AppOpsService.setMode → getOpLocked 未命中/未创建，`dumpsys appops` 状态仍为 default；services.jar `AppOpsService.setMode` 反编译核验该无异常静默返回路径），读回核对失败（readBack=3 MODE_DEFAULT）；且硬编码数值码随厂商重排不可靠（整型回退已在最终实现中**移除**——字符串变体为 API 29+ 公开方法，本项目目标 API 33，无整型回退需求）；
- 改为**仅字符串变体**后，op code 由 ROM 表解析，WRITE_SETTINGS/CHANGE_WIFI_STATE/PICTURE_IN_PICTURE 三项全部正常落地（写后 `unsafeCheckOpNoThrow` 读回一致）。**该设计对 stock AOSP 与厂商重排 ROM 均正确**；
- 查询通道 `unsafeCheckOpNoThrow(String, int, String)`（Q+，M+ 回退 `checkOpNoThrow`）同样按字符串解析，与设置通道一致。

### 2.2 ASR-0043 画中画（OP_PICTURE_IN_PICTURE）

- 禁用（disabled=true）：`setMode(op, uid, pkg, MODE_IGNORED)`；启用：`MODE_ALLOWED`；
- 效果：框架在应用 `enterPictureInPictureMode` 时检查该 op（ActivityTaskManager 消费），op 被忽略时进入请求被拒；
- 查询：`unsafeCheckOpNoThrow == MODE_IGNORED` 即禁用；
- 验证辅助：testapp 新增支持画中画的测试页（`android:supportsPictureInPicture`），UI 按钮 `Probe: Try Enter Picture-in-Picture` 真实调用 `enterPictureInPictureMode`（需 Activity 上下文，IPC 通道返回提示信息）；设备/ROM 不支持画中画时探测结果仅供参考，op 读回为准。

### 2.3 ASR-0044 写设置（OP_WRITE_SETTINGS）

- 机制与 2.2 相同（禁用=IGNORED、启用=ALLOWED）；
- 效果（本 ROM 真机实测）：op 被忽略时，testapp 写 `Settings.System` 抛出 `SecurityException: com.hmdm.testapp was not granted this permission: android.permission.WRITE_SETTINGS.`（`dumpsys appops` 同步记录 Reject 条目）；op 允许时写入成功（`dumpsys appops` 记录 Access 条目）；
- **本 ROM 附加限制（验证辅助适配，记录于文档供对照）**：MtkSettingsProvider 对 targetSdk>22 且非特权应用写入**自定义（非 PUBLIC_SETTINGS/PRIVATE_SETTINGS 名单内）System 键**直接抛 `IllegalArgumentException("You cannot keep your settings in the secure settings.")`（`warnOrThrowForUndesiredSecureSettingsMutationForTargetSdk` 方法，dex 反编译核验）——与 WRITE_SETTINGS op 无关。因此 testapp 的 `TryWriteSettings` 探测采用公开键 `screen_brightness` 的**当前值原值回写**（无状态变更的等价写操作），以绕过该键校验、纯净验证 op 通道。

### 2.4 ASR-0045 通知监听服务（NotificationManager @hide 绑定）

- **主通道（本 ROM 实测生效，channel=nm）**：反射 `NotificationManager.setNotificationListenerAccessGranted(ComponentName, boolean)`（@hide，MODIFY_PHONE_STATE 签名权限）——真机 logcat 核验 NMS 侧输出 `NotificationListeners: Allowing notification listener <组件> (userSet: true)`，且 testapp 的 `TestNotificationListenerService.onListenerConnected()` 真实回调（服务真实绑定）；
- **回退通道（channel=settings）**：直写 `Settings.Secure.enabled_notification_listeners`（冒号分隔组件列表，追加/移除目标，保留其他条目），NMS 持有该键 SettingsObserver 立即重评估（与无障碍批次双通道模式一致）；**列表条目统一做组件名规范化**（`unflattenFromString → flattenToShortString`，兼容本 ROM NMS 落键的全格式 `pkg/pkg.Class` 与引擎短格式 `pkg/.Class` 混存）；**回退通道读回以设置键内容为准**（NMS 状态经异步观察者更新，写后立即查询公开 API 会读到旧状态），nm 通道读回以公开 API 为准；本 ROM 主通道存在，回退通道未触发；
- 查询：公开 API `isNotificationListenerAccessGranted(ComponentName)` + 设置键列表附报（规范化后）；
- 持久化：NMS 将授权结果异步持久化到 `enabled_notification_listeners`（本 ROM 实测为**全格式**组件名，写入/清除均异步，约 1~3 秒内落键），整机重启后保持；
- 参数：`component` 支持全格式（`pkg/pkg.Class`）与短格式（`pkg/.Class`），`ComponentName.unflattenFromString` 解析，非法组件返回 error。

### 2.5 ASR-0149 WLAN 权限应用黑名单（OP_CHANGE_WIFI_STATE）

策略模型（SharedPreferences `wifi_permission_policy`，与通知/无障碍策略同模式）：

| 字段 | 键 | 说明 |
|---|---|---|
| 模式 | `mode` | 0=关闭（停止执行，已生效状态保留），2=黑名单 |
| 黑名单 | `blacklist`（StringSet） | 黑名单模式下列入的应用被禁用 WLAN 权限 |

执行机制：

1. **立即执行**：设置模式（mode=2）或名单变更时，对全部已安装应用整体比对——名单内 → `MODE_IGNORED`，名单外 → `MODE_ALLOWED`（整体替换语义，与通知黑白名单一致；Launcher 自身包跳过，避免破坏自身 WLAN 控制命令）。**本 ROM 附加发现（2026-08-06 实测）**：其 WifiService 等执行点按**原始 op 模式**判断（`unsafeCheckOpNoThrow` 语义），`MODE_DEFAULT` 状态被视为拒绝（实测 `cmd appops set ... default` 后 setWifiEnabled 仍返回 false）——因此名单外应用**必须显式写 MODE_ALLOWED**（不能依赖框架默认），整体比对语义为 ROM 兼容所必需；已处于目标状态的包**跳过写入**（避免冗余写入 /data/system/appops.xml），uid 复用循环内已取得的 ApplicationInfo.uid（避免逐包 PackageManager 查询）；
2. **新装应用**：PackageChangedReceiver 的 ACTION_PACKAGE_ADDED/REPLACED 处理中追加 `applyWifiPermissionToPackage`（黑名单模式激活时按名单套用，已处于目标状态跳过）；
3. **手动执行**：`ApplyWifiPermissionPolicy` 命令立即整体重比对（返回实际变更包列表）；
4. **持久化**：策略名单持久化于 Launcher SharedPreferences（上表）；AppOps 模式本身由框架持久化于 `/data/system/appops.xml`——**无需进程重启/开机重新武装**（与状态类批次不同）；
5. **效果（本 ROM 真机实测）**：op=IGNORED 时 testapp 的 `WifiManager.setWifiEnabled` 返回 false（本 ROM WifiService 对该 op 采用记录 Reject 而非抛异常，`dumpsys appops` 出现 `rejectTime`），`addNetwork` 返回 -1（`addNetworkSucceeded=false`）；op=ALLOWED 时 setWifiEnabled 返回 true、addNetwork 成功（netId 有效）并清理。

### 2.6 ASR-0046 特定包 USB 权限（UsbManager.grantPermission）

- 参数：`packageName`（必填，授权目标）+ `deviceName`（可选设备选择器：UsbDevice 名称精确匹配 / `vendorId:productId`（支持 0x 十六进制与十进制）/ `first`（默认，取第一台）；缺省等价 first）；
- 通道：反射 `UsbManager.grantPermission(UsbDevice, int uid)`（@hide，MANAGE_USB 签名权限，manifest 新增声明）；UsbService 仅对**已连接**设备落授权（设备未连接时静默不授权），授权持久化于框架（usb_device_manager.xml）；
- 读回：尽力解析 `dumpsys usb` 的 "Device permissions" 段（设备名 + `UID <uid>` 条目），格式因 ROM 而异时返回 null（不误报）；**端到端权威验证**由目标包自身 `UsbManager.hasPermission(UsbDevice)` 完成（testapp 的 `CheckUsbPermission` 探测）——本批次部署机器无 USB host 设备（host_connected=false），真实授权路径为硬件受限项（见需求文档"硬件受限测试说明"），无设备时命令优雅返回 `{success:false, error:"no matching USB device attached", devices:[]}`；
- 辅助命令 `GetUsbDeviceList`：枚举当前已连接 USB host 设备（名称/vid/pid），供调用方选择授权目标。

### 2.7 系统配置声明

无 `device_admin.xml` 变更；manifest 新增声明 `MANAGE_USB`、`MODIFY_PHONE_STATE`（签名权限，平台签名自动授予，`dumpsys package` granted=true 核验）；无需重启 framework。

## 3. 命令接口定义（SystemApiInterface.onEvent）

### 3.1 新增命令（本批次，13 条）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetPictureInPictureDisabled` | packageName（必填）、disabled（必填） | Boolean（写后读回核对） | ASR-0043 |
| `IsPictureInPictureDisabled` | packageName（必填） | Boolean | ASR-0043 |
| `SetWriteSettingsDisabled` | packageName（必填）、disabled（必填） | Boolean | ASR-0044 |
| `IsWriteSettingsDisabled` | packageName（必填） | Boolean | ASR-0044 |
| `SetNotificationListenerAccessGranted` | component（必填，pkg/class）、granted（必填） | Map：{success, granted, component, channel, enabledListeners} 或 {success:false, error} | ASR-0045 |
| `IsNotificationListenerAccessGranted` | component（必填） | Map：{success, granted, component, enabledListeners} 或 {success:false, error} | ASR-0045 |
| `SetWifiPermissionBlacklist` | packageNames（String 数组，整体替换） | Boolean（+ 黑名单模式激活时立即整体执行） | ASR-0149 |
| `GetWifiPermissionBlacklist` | 无 | List<String> | ASR-0149 |
| `SetWifiPermissionPolicyMode` | mode（必填，0/2） | Boolean（mode=2 时立即整体执行） | ASR-0149 |
| `GetWifiPermissionPolicyMode` | 无 | Integer | ASR-0149 |
| `ApplyWifiPermissionPolicy` | 无 | List<String>（本次实际变更包列表，可为空） | ASR-0149 |
| `GrantUsbPermission` | packageName（必填）、deviceName（可选：名称 / vid:pid / first） | Map：{success, packageName, uid, deviceName, deviceKey, granted, devices} 或 {success:false, error, devices} | ASR-0046 |
| `GetUsbDeviceList` | 无 | List<Map>：{deviceName, vendorId, productId} | ASR-0046（验证辅助） |

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> p = new HashMap<>();
p.put("packageName", "com.hmdm.testapp");
p.put("disabled", true);
Map result = api.onEvent("SetWriteSettingsDisabled", p);
// {"RESULT":true}（写后 unsafeCheckOpNoThrow 读回 MODE_IGNORED 核对）

Map p2 = new HashMap<>();
p2.put("component", "com.hmdm.testapp/com.hmdm.testapp.TestNotificationListenerService");
p2.put("granted", true);
Map result2 = api.onEvent("SetNotificationListenerAccessGranted", p2);
// {"RESULT":{"channel":"nm","component":"com.hmdm.testapp/.TestNotificationListenerService",
//   "enabledListeners":[],"granted":true,"success":true}}
```

**广播通道**（TestBroadcast，与现有命令一致）：

```bash
adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast \
  -a ON_SQA_INTERFACE_TEST --es event SetWifiPermissionPolicyMode \
  --es param '{"mode":2}'
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/
│   └── AppOpsPolicyManager.java        # 新增：AppOps/监听/USB 管控引擎
│       （setAppOpMode 双通道反射：字符串 op 变体优先 + 整型回退，读回核对；
│        ASR-0043/0044 单项 op 开关；ASR-0149 策略模式+黑名单整体比对执行、
│        SharedPreferences wifi_permission_policy 持久化；
│        ASR-0045 反射 setNotificationListenerAccessGranted（channel=nm）
│        回退直写 enabled_notification_listeners（channel=settings）；
│        ASR-0046 反射 grantPermission + dumpsys usb 尽力读回 + 设备枚举）
├── service/command/appops/
│   ├── SetPictureInPictureDisabled.java      # 新增：ASR-0043 设置
│   ├── IsPictureInPictureDisabled.java       # 新增：ASR-0043 查询
│   ├── SetWriteSettingsDisabled.java         # 新增：ASR-0044 设置
│   ├── IsWriteSettingsDisabled.java          # 新增：ASR-0044 查询
│   ├── SetNotificationListenerAccessGranted.java  # 新增：ASR-0045 设置
│   ├── IsNotificationListenerAccessGranted.java   # 新增：ASR-0045 查询
│   ├── SetWifiPermissionBlacklist.java       # 新增：ASR-0149 黑名单
│   ├── GetWifiPermissionBlacklist.java       # 新增：ASR-0149 黑名单查询
│   ├── SetWifiPermissionPolicyMode.java      # 新增：ASR-0149 模式
│   ├── GetWifiPermissionPolicyMode.java      # 新增：ASR-0149 模式查询
│   ├── ApplyWifiPermissionPolicy.java        # 新增：ASR-0149 立即执行
│   ├── GrantUsbPermission.java               # 新增：ASR-0046 授权
│   └── GetUsbDeviceList.java                 # 新增：ASR-0046 设备枚举
├── service/ApiBinder.java            # 注册 13 个新命令
└── broadcast/PackageChangedReceiver.java  # 新装/替换应用时追加 applyWifiPermissionToPackage
app/src/main/AndroidManifest.xml       # 新增 MANAGE_USB、MODIFY_PHONE_STATE 声明

testapp/
├── src/main/java/com/hmdm/testapp/
│   ├── TestNotificationListenerService.java  # 新增：测试用通知监听服务（onListenerConnected/Disconnected 打点）
│   ├── AppOpsVerifier.java                   # 新增：本地效果探测（写设置/改 WLAN/进画中画/USB 权限）
│   ├── PermissionAppOpsTestActivity.java     # 新增：测试页（19 个按钮）
│   ├── TestActions.java                      # 新增 18 个事件（含参数校验）与事件目录
│   └── MainActivity.java                     # 增加 "Permission & AppOps control" 入口
├── src/main/res/layout/activity_permission_appops_test.xml  # 新增测试页布局
└── src/main/AndroidManifest.xml  # 新增 TestNotificationListenerService（BIND_NOTIFICATION_LISTENER_SERVICE）、
                                 # WRITE_SETTINGS 声明、测试页 supportsPictureInPicture
```

## 5. 执行逻辑

```
setAppOpMode(op, packageName, mode, [uid]):
  1. 参数/安装校验（空包名、包未安装 → false；uid 变体供整体比对循环复用）
  2. 反射 setMode(String, uid, pkg, mode)（仅字符串变体，code 由 ROM 表解析）
  3. unsafeCheckOpNoThrow(op, uid, pkg) 读回，== mode → true

SetPictureInPictureDisabled/SetWriteSettingsDisabled(packageName, disabled):
  1. target = disabled ? MODE_IGNORED : MODE_ALLOWED
  2. setAppOpMode(...) → 布尔结果

SetWifiPermissionPolicyMode(mode):
  1. 非 0/2 → false（模式不变；缺参默认 -1 同样被拒，不会误关策略）
  2. 持久化 mode；mode=2 → applyWifiPermissionPolicy()

SetWifiPermissionBlacklist(packageNames):
  1. 整体替换持久化（去重、空项剔除）
  2. mode=2 → applyWifiPermissionPolicy()

applyWifiPermissionPolicy():
  1. mode != 2 → 返回空列表（不动作）
  2. 枚举全部已安装应用（跳过 Launcher 自身），复用 ApplicationInfo.uid
  3. 名单内 → IGNORED；名单外 → ALLOWED；当前状态已为目标状态 → 跳过
  4. 逐包 setAppOpMode 并收集成功变更的包；返回变更包列表

SetNotificationListenerAccessGranted(component, granted):
  1. 组件解析失败 → {success:false, error:"invalid component: ..."}
  2. 反射 NotificationManager.setNotificationListenerAccessGranted(ComponentName, granted)
     （成功 channel=nm；失败回退直写 enabled_notification_listeners，
     条目组件名规范化（全/短格式等价），channel=settings）
  3. 读回：nm 通道以公开 isNotificationListenerAccessGranted 为准；
     settings 通道以设置键内容为准（NMS 异步观察者更新，公开 API 读回滞后）
  4. 附报 enabledListeners（设置键解析，规范化后）

GrantUsbPermission(packageName, deviceName):
  1. 包未安装 → {success:false, error:"package not installed: ..."}
  2. getDeviceList() 按 名称/vid:pid/first 选择；无匹配 → {success:false, error:"no matching USB
     device attached", devices:[...]}
  3. 反射 UsbManager.grantPermission(UsbDevice, uid)
  4. 尽力解析 dumpsys usb Device permissions 段读回（格式不可解析 → granted=null）
  5. 返回 {success, packageName, uid, deviceName, deviceKey, granted, devices}
```

**安全设计**：op 字符串均为编译期常量（android: 前缀，不来自调用方输入）；组件名经 `ComponentName.unflattenFromString` 解析后仅用于框架调用与集合比较，不进入 shell/系统命令；名单仅存 Launcher 私有 SharedPreferences；USB 设备选择仅在 getDeviceList() 返回集合内匹配，无外部输入注入面。

## 6. 权限与归属

- `setMode(String, int, String, int)`：MANAGE_APP_OPS_MODES 签名权限（manifest 既有声明，平台签名自动授予，`dumpsys package` granted=true 核验）；
- `setNotificationListenerAccessGranted`：MODIFY_PHONE_STATE 签名权限（**本批次新增声明**，granted=true 核验）；
- `UsbManager.grantPermission`：MANAGE_USB 签名权限（**本批次新增声明**，granted=true 核验）；
- 无需 device owner 身份、无 shell、无 ROM 改动；不修改 AIDL / lib 模块；
- testapp 新增 WRITE_SETTINGS 声明（写设置探测目标）与 TestNotificationListenerService（BIND_NOTIFICATION_LISTENER_SERVICE 系统保护权限，框架授予）。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺 packageName/disabled/granted/component/mode 参数 | 命令返回 false / {error:"missing parameter: ..."}（testapp 侧同样拦截并返回提示） |
| 包未安装 | setAppOpMode 返回 false；GrantUsbPermission 返回 {success:false, error:"package not installed: ..."} |
| 非法组件格式（非 pkg/class） | {success:false, error:"invalid component: ..."}，不写设置 |
| **ROM op 数值码重排（本 ROM OP_WRITE_SETTINGS=23 ≠ AOSP 22）** | 引擎仅用字符串变体，code 经 ROM 自身表解析，实测三项 op 均正常落地；整型变体已移除（硬编码数值码随厂商重排不可靠，初版整型优先时 setMode(1) 服务端静默不生效） |
| 缺 mode 参数（SetWifiPermissionPolicyMode） | 默认 -1 被管理器拒绝（false，模式不变）——不会把缺参误当作 mode=0 关闭策略 |
| **本 ROM WifiService/SettingsProvider 按原始模式判断** | `MODE_DEFAULT` 状态被视为拒绝（实测 `cmd appops set ... default` 后 setWifiEnabled 仍 false）；故名单外应用必须显式写 ALLOWED，整体比对语义为 ROM 兼容所必需 |
| WLAN 策略重复执行 | 已处于目标状态的包跳过写入（避免冗余写入 /data/system/appops.xml），uid 复用循环内 ApplicationInfo.uid |
| 本 ROM WifiService 对 CHANGE_WIFI_STATE 拒绝不抛异常 | op=IGNORED 时 setWifiEnabled 返回 false、addNetwork 返回 -1，dumpsys appops 记录 rejectTime——探测结果按"布尔结果 + 是否有 SecurityException"双重上报，文档以 op 读回 + rejectTime 为权威证据 |
| SettingsProvider 拒绝自定义 System 键（targetSdk>22 非特权应用） | 与 WRITE_SETTINGS op 无关的 ROM 键校验；TryWriteSettings 探测改用公开键 screen_brightness 原值回写，纯净验证 op 通道 |
| NMS 异步持久化 enabled_notification_listeners | 授权后立即读回可能尚未落键（本 ROM 约 1~3 秒），查询以公开 API isNotificationListenerAccessGranted 为准、设置键仅附报；撤权后键异步清空 |
| 无 USB host 设备 | GrantUsbPermission 返回 {success:false, error:"no matching USB device attached", devices:[]}，不 crash；CheckUsbPermission 返回 deviceAttached=false |
| 重复设置/幂等 | 状态已匹配时 setAppOpMode 读回核对一致仍返回 true |
| 策略持久化丢失（Launcher 数据清空） | mode 回 0（不执行），名单为空；AppOps 模式由框架持久化不受影响 |
| 反射/直写均失败（极端情况） | setAppOpMode 返回 false；监听授权 success=false + 读回如实上报 |

## 8. 真机验证记录（2026-08-06，Android 13 / API 33 userdebug，平台签名 + device owner）

（命令级用例结果见测试用例设计文档，此处记录机制核验关键结论）

| 核验项 | 结果 |
|---|---|
| 设备基线 | testapp 三项 op 均默认允许、无监听授权、无 USB host 设备（host_connected=false） |
| **op 数值码核验** | 初版整型变体 setMode(1) 对 WRITE_SETTINGS 静默不生效（readBack=3），services.jar/framework.jar dex 反编译确认本 ROM `OP_WRITE_SETTINGS=23`（AOSP=22）；改字符串变体优先后三项 op 全部落地（readBack=0/1 一致） |
| ASR-0043 禁用/启用 | disabled=true → RESULT true、`dumpsys appops` PICTURE_IN_PICTURE (ignore)；disabled=false → (allow)；非安装包 → false |
| ASR-0044 禁用效果 | op=IGNORED 时 TryWriteSettings 抛 SecurityException（"was not granted this permission: android.permission.WRITE_SETTINGS."），dumpsys appops 记录 Reject |
| ASR-0044 启用效果 | op=ALLOWED 时 TryWriteSettings 原值回写 screen_brightness 成功（wrote=true），dumpsys appops 记录 Access |
| ASR-0045 授予 | channel=nm（@hide 方法本 ROM 存在）；logcat `Allowing notification listener ... (userSet: true)` + testapp `TestNotificationListenerService connected`（真实绑定）；读回 granted=true；NMS 异步落键 `enabled_notification_listeners`（全格式） |
| ASR-0045 撤销 | granted=false 读回一致；设置键异步清空；非法组件 → {success:false, error} |
| ASR-0149 黑名单 | 黑名单=[testapp]+mode=2 → `cmd appops get ... CHANGE_WIFI_STATE` = ignore；TryChangeWifiState → setWifiEnabled=false（本 ROM 记录 rejectTime 不抛异常）；TryAddWifiNetwork → addNetworkSucceeded=false（netId=-1） |
| ASR-0149 恢复 | 清空黑名单 → op 回 allow；setWifiEnabled=true、addNetwork 成功（netId=3 并已移除） |
| **本 ROM DEFAULT 状态语义** | `cmd appops set com.hmdm.testapp CHANGE_WIFI_STATE default` 后 TryChangeWifiState 仍被拒（setWifiEnabled=false）、`allow` 后恢复 true——执行点按原始模式判断（与 MtkSettingsProvider 写设置检查同语义），名单外应用必须显式 ALLOWED（验证后已恢复 allow） |
| ASR-0149 策略机制 | 整体执行 skip-if-matching：模式切换/名单变更重复执行时已处于目标状态的包被跳过（changed 仅含实际变更包；重构前无条件写入为 changed:172，重构后重复执行 changed:0、名单变更 changed:1）；mode=1 非法拒绝（RESULT false 且模式不变）；mode=0 后 ApplyWifiPermissionPolicy 返回空列表（不动作）；策略持久化 wifi_permission_policy.xml（mode=0、blacklist 空） |
| ASR-0046 无设备路径 | GetUsbDeviceList → []；GrantUsbPermission → {success:false, error:"no matching USB device attached", devices:[]}；CheckUsbPermission → deviceAttached=false（真实授权需 USB 设备，硬件受限项） |
| 错误路径 | 缺参、非法组件、未安装包均返回对应 false/error，不 crash |
| 测试后设备恢复 | wifi 策略 mode=0、黑名单清空；PIP/WRITE_SETTINGS op 回 allow；监听授权撤销、设置键清空；无残留 |
