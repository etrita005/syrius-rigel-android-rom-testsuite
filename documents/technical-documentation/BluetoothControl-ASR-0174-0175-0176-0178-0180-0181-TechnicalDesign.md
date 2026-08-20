# 蓝牙管控（ASR-0174/0175/0176/0178/0180/0181）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 实现机制 |
|---|---|---|---|
| ASR-0180 | 蓝牙 | 查询/设置是否禁止蓝牙设备可被发现模式 | `BluetoothAdapter.setScanMode` 扫描模式强制纠正 + `ACTION_SCAN_MODE_CHANGED` 回调持续执行 |
| ASR-0181 | 蓝牙 | 查询/设置是否禁止蓝牙设备有限可被发现模式 | 同上（Android 无独立有限可发现扫描模式常量，与 ASR-0180 共用同一强制执行） |
| ASR-0174 | 蓝牙 | 设置蓝牙页面是否可用 | `setComponentEnabledSetting` 禁用/恢复 `Settings$BluetoothSettingsActivity`（与 ASR-0214 VPN 设置入口同模式） |
| ASR-0175 | 蓝牙 | 蓝牙连接黑白名单 | Launcher 侧策略引擎（模式 0/1/2 + 设备地址/名称名单）＋ 蓝牙回调（ACL 连接/配对完成）断连与解除配对策略 |
| ASR-0176 | 蓝牙 | 禁止/允许蓝牙传输文件 | 禁用/恢复 `com.android.bluetooth` 全部 BluetoothOpp 组件 |
| ASR-0178 | 蓝牙 | 禁止/允许蓝牙外设通话 | `AudioManager.setCommunicationDevice` 通话路由强制（耳听筒/扬声器）+ 停止/禁止 SCO |

**归属**：按需求文档归属列，ASR-0174/0175/0176/0180/0181 为「Launcher（MDM）」，ASR-0178 为「Launcher（MDM）+ 系统 API」（落地为公开 `AudioManager.setCommunicationDevice`，API 31+，本 ROM framework.jar dex 核验存在）。本批次**无新增 manifest 权限、无 shell、无 ROM 改动、不修改 `device_admin.xml`**：Launcher manifest 既有 `BLUETOOTH_CONNECT`（signature|appop|privileged）、`CHANGE_COMPONENT_ENABLED_STATE`、`READ_PHONE_STATE` 声明，平台签名 uid=1000 自动授予；蓝牙相关类位于本 ROM `/apex/com.android.btservices/javalib/framework-bluetooth.jar`（boot classpath，应用可直接调用）。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署；bootloader 锁定。测试设备基线（2026-08-07 实测）：蓝牙出厂关闭（`dumpsys bluetooth_manager` enabled=false、state OFF）、无已配对设备；Settings 蓝牙页入口组件双枚——页面本体 `com.android.settings/.Settings$BluetoothSettingsActivity`（launcher SHORTCUT 入口）与本 ROM 的 ACTION `android.settings.BLUETOOTH_SETTINGS` 首选解析 `Settings$BluetoothBroadcastActivity`（禁用后 ACTION 回退到"已连接的设备"总览页 `Settings$ConnectedDeviceDashboardActivity`）；`com.android.bluetooth` 的 OPP 组件 9 个（7 activity + 2 receiver，**本 ROM Bluetooth APK manifest 无 `BluetoothOppService`**，aapt2/XML 核验），其中 6 个 manifest `android:enabled="false"` 出厂禁用；framework-bluetooth.jar 含 `BluetoothAdapter.setScanMode/getScanMode/cancelDiscovery`、`BluetoothDevice.removeBond()/disconnect()`、AudioManager `setCommunicationDevice/getCommunicationDevice/getAvailableCommunicationDevices/clearCommunicationDevice`（dex 核验）；**`BluetoothAdapter.setScanMode` 需 `BLUETOOTH_PRIVILEGED`（signature\|privileged，uid=1000 持有、普通应用无）**；**`BluetoothAdapter.getConnectedDevices(int)` 在本 ROM 被 MTK fork 删除**（BluetoothManager 亦委托同缺失方法，运行时 NoSuchMethodException/InvocationTargetException 核验）。

## 2. 技术选型与可行性核验

### 2.1 本 ROM 蓝牙框架特性（2026-08-07 真机/反编译核验）

- **蓝牙客户端类位置**：`android.bluetooth.*` 不在 framework.jar，位于 `/apex/com.android.btservices/javalib/framework-bluetooth.jar`（boot classpath 可加载，`BluetoothAdapter.getDefaultAdapter()` 等既有命令路径不变）；
- **扫描模式**：`BluetoothAdapter.setScanMode(int)`/`getScanMode()` 存在（服务端 `AdapterService$AdapterServiceBinder.setScanMode(int, AttributionSource)`，framework-bluetooth.jar dex 核验）。**AOSP 语义**：一般可发现与有限可发现共用同一扫描模式常量 `SCAN_MODE_CONNECTABLE_DISCOVERABLE`（Settings 可见性开关以 120 秒时限打开该模式；配对/发现流程中的"有限可发现"亦为同一模式），Android 公开 API 无独立常量区分；
- **本 ROM 无 `BluetoothOppService`**：Bluetooth APK（`/apex/com.android.btservices/app/Bluetooth@TP1A.220624.014/Bluetooth.apk`）AndroidManifest.xml 服务清单（aapt2 dump 核验）只有 AdapterService/A2dpService/HeadsetService/PanService 等，无 `com.android.bluetooth.opp.BluetoothOppService`；OPP 入口组件为 `BluetoothOppReceiver`（`android.btopp.intent.action.*` 接收端）、`BluetoothOppHandoverReceiver`（NFC handover）、`BluetoothOppLauncherActivity`（`ACTION_SEND/SEND_MULTIPLE` 分享入口 + `android.btopp.intent.action.OPEN`）及传输确认 UI 系列；
- **音频路由**：AudioManager `setCommunicationDevice`（API 31+）在本 ROM framework.jar 存在（IAudioService.setCommunicationDevice 四组实现 dex 核验），`getAvailableCommunicationDevices()` 返回 `List<AudioDeviceInfo>`；
- **项目 SDK 差异**：本项目 compileSdk 33 的 android.jar 为**裁剪版**——`BluetoothAdapter.setScanMode(int)`、`getConnectedDevices(int)`、`BluetoothProfile.HID_HOST/PAN/A2DP_SINK` 常量、`AudioManager.ACTION_MODE_CHANGED` 均不在其中（javap 核验），引擎对这些调用一律**反射**（ROM 端存在性已 dex 核验），不依赖裁剪 SDK。

### 2.2 ASR-0180/0181 可被发现模式禁止（扫描模式强制）

**语义**：禁止后设备不得进入可被发现状态；允许后恢复正常。Android 层面"可被发现"= 扫描模式 `SCAN_MODE_CONNECTABLE_DISCOVERABLE`（23）。ASR-0180（一般可发现）与 ASR-0181（有限可发现——即设置页 120 秒可见窗口与配对流程中的临时可发现）在框架层是同一模式常量，故两条需求**共用同一强制执行**：任一禁止标志生效时，将扫描模式拉回 `SCAN_MODE_CONNECTABLE`（21，保持可连接但不可被发现）并取消进行中的发现（`cancelDiscovery`）。

**强制执行**（"查询/设置"语义下的持续纠正，与 WLAN 断连策略同模式）：

- 动态注册 `ACTION_SCAN_MODE_CHANGED` 接收器：任何一方（用户在设置页打开可见性、第三方应用调用 setScanMode/startDiscovery）将扫描模式切为 DISCOVERABLE 时，引擎立即拉回 CONNECTABLE（约 1 秒内纠正，logcat `enforceDiscoverable(SCAN_MODE_CHANGED): discoverable scan mode reverted to CONNECTABLE`）；
- **权限要点**：`setScanMode` 在本 ROM 需 `BLUETOOTH_PRIVILEGED`（signature\|privileged）——Launcher（uid=1000）granted=true 可执行（真机 ok=true），普通应用被 SecurityException 拒绝（testapp 探测如实上报，回滚验证以设置页可见性开关真实路径为准）；`getScanMode` 需 `BLUETOOTH_SCAN`（Launcher SYSTEM_FIXED 自动授予）；
- `ACTION_STATE_CHANGED`（STATE_ON）：蓝牙打开时框架将扫描模式复位为 CONNECTABLE，引擎无需动作，但保留一次强制执行保证一致；
- 两个标志独立持久化（`discoverableForbidden`/`limitedDiscoverableForbidden`），任一为 true 即执行；
- 查询返回真实扫描模式（none/connectable/discoverable）与蓝牙开关状态。

### 2.3 ASR-0174 蓝牙页面可用（组件禁用）

与 ASR-0214 VPN 设置入口同模式：`setComponentEnabledSetting` 禁用蓝牙设置页入口组件，写后 `getComponentEnabledSetting` 逐个读回核对；启用恢复 DEFAULT——**引擎只恢复自己禁用的组件**（SharedPreferences `bluetooth_policy.launcherDisabledPageComponents` 记录"本引擎禁用集合"，禁用时写入、恢复时清除），外部禁用（用户/adb/其他管理器）不被覆盖（代码审查后修复：原 prev 集合语义在标志关闭期间会覆盖外部禁用）。**本 ROM 入口为双组件**：页面本体 `Settings$BluetoothSettingsActivity`（launcher SHORTCUT 入口）与 ACTION `android.settings.BLUETOOTH_SETTINGS` 首选解析的 `Settings$BluetoothBroadcastActivity`（MTK trampoline）——两者均禁用使页面经所有入口不可达；**禁用后 ACTION 回退到"已连接的设备"总览页 `Settings$ConnectedDeviceDashboardActivity`**（父级入口含热点/网络等其他设置，不禁用，文档化）；页面可用性判定以页面组件直启抛 ActivityNotFoundException 为准（真机验证路径）。组件状态由 PMS 持久化（package-restrictions.xml），重启保持。

### 2.4 ASR-0176 禁止蓝牙传输文件（BluetoothOpp 组件禁用）

**方案决策**：需求文档原文"禁用 BluetoothOpp 组件"。本 ROM 无 `BluetoothOppService`，落地为禁用 com.android.bluetooth 包内全部 `.opp.*` 组件（**9 个**：接收端 `BluetoothOppReceiver`、NFC handover `BluetoothOppHandoverReceiver`、分享入口/`OPEN` 接收 `BluetoothOppLauncherActivity`、传输 UI `BluetoothOppTransferActivity`、`BluetoothOppIncomingFileConfirmActivity`、`BluetoothOppBtEnableActivity`、`BluetoothOppBtEnablingActivity`、`BluetoothOppBtErrorActivity`、`BluetoothOppTransferHistory`；**其中 6 个 manifest `android:enabled="false"` 出厂禁用**——组件枚举必须带 `MATCH_DISABLED_COMPONENTS`，否则 getPackageInfo 只返回 3 个启用组件，初版真机核验发现）：

- 运行时经 `getPackageInfo(GET_ACTIVITIES|GET_RECEIVERS|MATCH_DISABLED_COMPONENTS)` 动态枚举 `.opp.*` 组件（避免硬编码列表随 ROM 漂移；失败回退内置 9 组件列表）；
- 禁用=逐个 `setComponentEnabledSetting(DISABLED, DONT_KILL_APP)`，写后逐个读回核对（结果含每组件状态列表）；
- 启用=恢复 DEFAULT（**引擎只恢复自己禁用的组件**——禁用时写入 `launcherDisabledOppComponents` 集合、恢复时清除；外部显式禁用与 manifest 出厂禁用组件不被触碰，不越权恢复用户/系统原状态；代码审查后修复：原 prev 集合语义在标志关闭期间会覆盖外部禁用）；
- 效果验证：`ResolveBluetoothShare` 探测——`ACTION_SEND` + package=com.android.bluetooth 解析命中 `BluetoothOppLauncherActivity`（基线 resolved=true），禁用后 resolved=false（真机闭环）；
- 状态由 PMS 持久化，重启保持；标志持久化 SharedPreferences `bluetooth_policy.fileTransferDisabled`，进程重启/开机经 syncPolicy 重新武装。

### 2.5 ASR-0175 蓝牙连接黑白名单（断连 + 配对策略）

**方案决策**：DPM 无蓝牙连接名单能力（AOSP 13 仅 `DISALLOW_BLUETOOTH` 总开关），按 Sheet1 P2 规划落地为 **Launcher 侧策略引擎 + 蓝牙回调断连策略**（与 ASR-0147/0148 WLAN 名单同风格）：

- **策略模型**：模式 0=关闭 / 1=白名单（仅名单内设备可保持连接/配对）/ 2=黑名单（名单内设备被断开/解除配对）；名单分**设备地址**（`AA:BB:CC:DD:EE:FF`，大写归一化）与**设备名称**（别名优先 `getAlias()`，回退 `getName()`）两个维度，任一维度违规即触发（白名单模式下地址或名称任一命中即放行）；名单整体替换持久化 SharedPreferences `bluetooth_access_policy`；
- **断连策略（连接）**：动态注册 `ACTION_ACL_CONNECTED`/`ACTION_ACL_DISCONNECTED` 接收器维护**连接缓存**（地址→设备，回调零阻塞更新）+ 命令即时评估（`ApplyBluetoothAccessPolicy`/模式变更/名单变更）——遍历已连接设备（**本 ROM `BluetoothAdapter.getConnectedDevices(int)` 被 MTK fork 删除**（运行时 NoSuchMethodException/InvocationTargetException 核验），改经 **profile 代理**枚举：`getProfileProxy`（A2DP/HEADSET/HID_HOST/PAN/A2DP_SINK/LE_AUDIO 六 profile）+ 代理公开 `getConnectedDevices()`，地址去重；**代理回调投递到主 looper，枚举必须在工作线程执行并 bounded join**——初版在主线程 `latch.await` 造成 ApiService.onCreate 死锁超时（服务 exec 20s 超时、MainActivity ANR、进程崩溃，/data/anr 栈核验），改 4 秒 bounded future，主线程路径直接读连接缓存；回调路径经 ACL 事件增量维护缓存），违规设备经隐藏 `BluetoothDevice.disconnect()` 反射断开（framework-bluetooth.jar 核验存在，TEST-API，平台签名 uid=1000 不受 hidden API 限制；断全部 profile 连接）；框架自动重连会再次触发 ACL 回调再次断开，构成持续执行闭环；
- **配对策略（配对）**：动态注册 `ACTION_BOND_STATE_CHANGED`（BONDED）——违规设备经隐藏 `BluetoothDevice.removeBond()` 反射解除配对（Android 无"阻止配对发生"的标准接口，ASR-0177 不在本批次范围；本需求"配对策略"落地为配对完成即解除的纠正语义，与断连策略同模式，文档化）；
- **权限**：S+ 接收 `ACTION_ACL_CONNECTED`/`ACTION_BOND_STATE_CHANGED` 广播与调用蓝牙 API 需 `BLUETOOTH_CONNECT`（平台签名自动授予）；
- 查询返回模式、四份名单、当前已连接/已配对设备清单与 enforcement 结果。

### 2.6 ASR-0178 禁止蓝牙外设通话（SCO 路由强制）

**语义**：禁止后通话（含语音通信）音频不得路由到蓝牙外设（耳机/车载）；允许后恢复正常自动路由。

**落地**：标志 `scoCallForbidden` 持久化 SharedPreferences `bluetooth_policy`。生效时：

- 通话会话判定：`AudioManager.getMode()` ∈ {`MODE_IN_CALL`(2), `MODE_IN_COMMUNICATION`(3)} 视为通话中（无需 READ_PHONE_STATE 即可判定，不依赖订阅）；
- 强制路由：从 `getAvailableCommunicationDevices()` 选取**非蓝牙**设备（优先 `TYPE_BUILTIN_EARPIECE`（听筒），其次 `TYPE_BUILTIN_SPEAKER`（扬声器），再次任意非 BT 设备）→ `setCommunicationDevice(target)`；同时 `stopBluetoothSco()` + `setBluetoothScoOn(false)` 停止/禁止 SCO 链路；**路由失败（异常/返回 false）如实上报 success=false**（代码审查后修复：原实现吞异常仍报 success=true）；**仅当引擎实际强制过路由（scoRouteForced）时才 clearCommunicationDevice**（代码审查后修复：原实现标志未启用时每次 boot/广播也清除用户配置的通信设备偏好）；
- **持续执行**：动态注册 `ACTION_SCO_AUDIO_STATE_CHANGED`（SCO 建立即停止并重路由）、`android.media.action.MODE_CHANGED`（通话进入/退出即执行）、`ACTION_PHONE_STATE_CHANGED`（冗余通道）、`ACTION_STATE_CHANGED`（蓝牙打开时若通话中重路由）；标志解除时 `clearCommunicationDevice()` 恢复自动路由；
- 返回上报 audioMode/callActive/scoActive/routedTo/communicationDeviceAfter 供核对；
- **本机限制**：本机无 SIM（`gsm.sim.state`=ABSENT,ABSENT）且无蓝牙耳机——通话会话无法真实建立，机制级验证（无通话时设置/清除路由、标志持久化、SCO/模式回调通道注册）在本机完成，真实通话中路由效果需插 SIM + 蓝牙耳机真机（见需求文档"硬件受限测试说明"）。

### 2.7 系统配置声明

- 本批次写入的系统状态：**组件启用状态**（`/data/system/users/0/package-restrictions.xml`，PMS 持久化）——`Settings$BluetoothSettingsActivity` + `Settings$BluetoothBroadcastActivity`（ASR-0174）与 com.android.bluetooth 全部 9 个 OPP 组件（ASR-0176）经 `setComponentEnabledSetting` 禁用/恢复，写后逐个读回核对，重启保持；
- **蓝牙扫描模式**（运行时态，不落盘）：ASR-0180/0181 经 `setScanMode` 强制，蓝牙服务自行维护；标志持久化于 Launcher SharedPreferences `bluetooth_policy`（discoverableForbidden/limitedDiscoverableForbidden/pageDisabled/fileTransferDisabled/scoCallForbidden + 恢复用 launcherDisabledPageComponents/launcherDisabledOppComponents + scoRouteForced（是否强制过路由）），进程重启（ApiService.onCreate）/开机（BootCompletedReceiver）经 syncPolicy 重新武装；
- **通话路由**（运行时态，不落盘）：ASR-0178 经 AudioManager 强制，标志持久化同上；
- 策略名单持久化于 Launcher SharedPreferences `bluetooth_access_policy`（mode/addressWhitelist/addressBlacklist/nameWhitelist/nameBlacklist）；
- **不修改** `device_admin.xml`、不写 Settings 键、**无需重启 framework**。

## 3. 命令接口定义（SystemApiInterface.onEvent）

### 3.1 新增命令（本批次，18 条）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetBlueOpen` | open（boolean，必填） | Launcher 既有实现（BluetoothHelper.enable/disable），本批次 testapp 补齐事件通道 | ASR-0171/0172（测试辅助） |
| `IsBlueOpen` | 无 | Launcher 既有实现 | ASR-0171/0172（测试辅助） |
| `SetDiscoverableForbidden` | disabled（boolean，必填） | Map：{success, discoverableForbidden, limitedDiscoverableForbidden, forbidden, bluetoothEnabled, scanMode, scanModeCorrected, 适配器状态} 或 {error} | ASR-0180 |
| `IsDiscoverableForbidden` | 无 | Map：{success, discoverableForbidden, 适配器状态} | ASR-0180 |
| `SetLimitedDiscoverableForbidden` | disabled（boolean，必填） | Map：{success, limitedDiscoverableForbidden, discoverableForbidden, 扫描模式纠正结果, 适配器状态} 或 {error} | ASR-0181 |
| `IsLimitedDiscoverableForbidden` | 无 | Map：{success, limitedDiscoverableForbidden, 适配器状态} | ASR-0181 |
| `SetBluetoothPageDisabled` | disabled（boolean，必填） | Map：{success, pageDisabled, components:[{component, state}]（页面本体 + ACTION 首选入口双组件）} 或 {error} | ASR-0174 |
| `IsBluetoothPageDisabled` | 无 | Map：{success, pageDisabled, components:[{component, state}]} | ASR-0174 |
| `SetBluetoothFileTransferDisabled` | disabled（boolean，必填） | Map：{success, fileTransferDisabled, components:[{component, state}]} 或 {error} | ASR-0176 |
| `IsBluetoothFileTransferDisabled` | 无 | Map：{success, fileTransferDisabled, components:[...]} | ASR-0176 |
| `SetScoCallDisabled` | disabled（boolean，必填） | Map：{success, scoCallForbidden, audioMode, callActive, scoActive, communicationDeviceBefore, applied, routedTo, communicationDeviceAfter} 或 {error} | ASR-0178 |
| `IsScoCallDisabled` | 无 | Map：{success, scoCallForbidden, audioMode, scoActive, communicationDevice} | ASR-0178 |
| `SetBluetoothAccessPolicy` | mode（int，必填 0/1/2） | Map：{success, mode, modeName, 四份名单, 当前设备, enforcement} 或 {error} | ASR-0175 |
| `GetBluetoothAccessPolicy` | 无 | Map：{success, mode, modeName, 四份名单, 当前设备} | ASR-0175 |
| `SetBluetoothAddressWhitelist` | addresses（String 数组 `AA:BB:CC:DD:EE:FF`，必填，整体替换） | Map：{success, whitelist, 当前设备} 或 {error} | ASR-0175 |
| `SetBluetoothAddressBlacklist` | addresses（String 数组，必填，整体替换） | Map：{success, blacklist, 当前设备} 或 {error} | ASR-0175 |
| `SetBluetoothNameWhitelist` | names（String 数组，必填，整体替换） | Map：{success, whitelist, 当前设备} 或 {error} | ASR-0175 |
| `SetBluetoothNameBlacklist` | names（String 数组，必填，整体替换） | Map：{success, blacklist, 当前设备} 或 {error} | ASR-0175 |
| `ApplyBluetoothAccessPolicy` | 无 | Map：{success, mode, bluetoothEnabled, connectedDevices, bondedDevices, disconnected, unbonded, 当前设备} | ASR-0175（立即执行评估） |
| `GetBluetoothStatus` | 无 | Map：{success, 五项策略标志, 适配器状态（supported/enabled/scanMode/discovering/bondedDevices）, 音频状态（audioMode/scoActive/communicationDevice）, bluetoothPageComponent/bluetoothPageState, oppComponents:[...]} | 辅助 |

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> p = new HashMap<>();
p.put("disabled", true);
Map result = api.onEvent("SetDiscoverableForbidden", p);
// {"RESULT":{"scanModeCorrected":false,"success":true,"limitedDiscoverableForbidden":false,
//   "forbidden":true,"discoverableForbidden":true,"bluetoothEnabled":true,"scanMode":"connectable",
//   "bluetoothSupported":true,"bondedDevices":[],"discovering":false}}

Map<String, Object> w = new HashMap<>();
w.put("mode", 1);
w.put("addresses", Arrays.asList("AA:BB:CC:DD:EE:FF"));   // 先 SetBluetoothAddressWhitelist 再 SetBluetoothAccessPolicy
Map result2 = api.onEvent("SetBluetoothAccessPolicy", w);
// {"RESULT":{"mode":1,...,"enforcement":{"bluetoothEnabled":true,"connectedDevices":[],"bondedDevices":[],...}}}
```

**广播通道**（TestBroadcast，与现有命令一致）：

```bash
adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast \
  -a ON_SQA_INTERFACE_TEST --es event SetDiscoverableForbidden \
  --es param '{"disabled":true}'
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/
│   ├── BluetoothPolicyManager.java        # 新增：ASR-0180/0181 扫描模式强制 + ASR-0174 页面禁用 + ASR-0176 OPP 组件禁用 + ASR-0178 SCO 路由强制（SharedPreferences 持久化 + 动态广播回调 + syncPolicy）
│   └── BluetoothAccessPolicyManager.java  # 新增：ASR-0175 连接/配对黑白名单引擎（模式 0/1/2 + 地址/名称名单 + ACL/配对回调断连/解配 + syncPolicy）
├── service/command/blue/
│   ├── SetDiscoverableForbidden.java / IsDiscoverableForbidden.java          # 新增：ASR-0180
│   ├── SetLimitedDiscoverableForbidden.java / IsLimitedDiscoverableForbidden.java  # 新增：ASR-0181
│   ├── SetBluetoothPageDisabled.java / IsBluetoothPageDisabled.java          # 新增：ASR-0174
│   ├── SetBluetoothFileTransferDisabled.java / IsBluetoothFileTransferDisabled.java # 新增：ASR-0176
│   ├── SetScoCallDisabled.java / IsScoCallDisabled.java                      # 新增：ASR-0178
│   ├── SetBluetoothAccessPolicy.java / GetBluetoothAccessPolicy.java         # 新增：ASR-0175
│   ├── SetBluetoothAddressWhitelist.java / SetBluetoothAddressBlacklist.java # 新增：ASR-0175
│   ├── SetBluetoothNameWhitelist.java / SetBluetoothNameBlacklist.java       # 新增：ASR-0175
│   ├── ApplyBluetoothAccessPolicy.java    # 新增：ASR-0175 立即评估
│   └── GetBluetoothStatus.java            # 新增：辅助状态
└── service/ApiBinder.java         # 注册 18 个新命令（blue 包改为通配导入）
    service/ApiService.java        # onCreate 增加 BluetoothPolicyManager/BluetoothAccessPolicyManager syncPolicy
broadcast/BootCompletedReceiver.java  # BOOT_COMPLETED 增加两个 syncPolicy

testapp/
├── src/main/java/com/hmdm/testapp/
│   ├── BluetoothTestActivity.java         # 新增：测试页（开关/状态/可发现/页面/传输/SCO/名单 + 框架探测）
│   ├── BluetoothVerifier.java             # 新增：TrySetDiscoverable（模拟设置页可见性开关并观察回滚）/TryOpenBluetoothSettings/ResolveBluetoothShare/GetBluetoothStateLocal
│   ├── TestActions.java                   # 新增 25 个事件与事件目录
│   ├── MainActivity.java                  # 增加 "Bluetooth" 入口
│   └── AndroidManifest.xml                # 注册 BluetoothTestActivity；新增 BLUETOOTH/BLUETOOTH_ADMIN(maxSdk30)/BLUETOOTH_CONNECT/BLUETOOTH_SCAN 权限
└── src/main/res/layout/activity_bluetooth_test.xml  # 新增测试页布局（32 按钮 + 6 分组）
```

## 5. 执行逻辑

### 5.1 ASR-0180/0181（SetDiscoverableForbidden / SetLimitedDiscoverableForbidden）

```
1. 缺 disabled → {error}
2. 持久化对应标志 → ensureReceiver（进程内注册一次动态广播接收器）
3. enforceDiscoverable()：任一标志为 true 且蓝牙开启时——
   a. getScanMode() == SCAN_MODE_CONNECTABLE_DISCOVERABLE → 反射 setScanMode(SCAN_MODE_CONNECTABLE)（裁剪 SDK 无该方法，ROM 端存在已核验），scanModeCorrected 上报
   b. isDiscovering() → cancelDiscovery()
4. 返回 {success, 标志, forbidden, bluetoothEnabled, scanMode, scanModeCorrected, bondedDevices, ...}
5. 回调持续执行：ACTION_SCAN_MODE_CHANGED（切为可发现即拉回）/ ACTION_STATE_CHANGED（STATE_ON 时补一次强制）
```

### 5.2 ASR-0174（SetBluetoothPageDisabled）

```
1. 缺 disabled → {error}；组件解析失败/不存在 → {success:false, error:"bluetooth settings component not found"}
2. 禁用：先备份原禁用态（prevPageDisabled）再 setComponentEnabledSetting(DISABLED, DONT_KILL_APP)
   启用：恢复 DEFAULT（原禁用态还原为 DISABLED）
3. 读回 getComponentEnabledSetting 核对（disabled/default/enabled/disabled-user）
4. 标志持久化 pageDisabled；进程重启/开机经 syncPolicy 重新 applyPageState
```

### 5.3 ASR-0176（SetBluetoothFileTransferDisabled）

```
1. 缺 disabled → {error}
2. 禁用：先记录已处于禁用态的组件（prevOppDisabled）→ 逐个 setComponentEnabledSetting(DISABLED)（仅实际存在的组件）
   启用：对非 prevOppDisabled 的组件恢复 DEFAULT
3. 逐个读回核对，返回 components 列表（component/state）
4. 标志持久化 fileTransferDisabled；syncPolicy 重新 applyOppState
```

### 5.4 ASR-0175（SetBluetoothAccessPolicy / 名单 / ApplyBluetoothAccessPolicy）

```
SetBluetoothAccessPolicy：
  1. mode 非 0~2 → {error}
  2. 持久化 mode → ensureReceiver → enforce()
  3. 返回 {success, mode, modeName, 四份名单, 当前设备, enforcement}

名单命令（SetBluetoothAddressWhitelist/Blacklist、SetBluetoothNameWhitelist/Blacklist）：
  1. 缺数组 → {error}；条目逐个校验（地址：AA:BB:CC:DD:EE:FF 格式大写归一化；名称：非空/≤248 字节/无控制字符），任一非法 → 整单拒绝 {error}
  2. 整体替换持久化 → ensureReceiver → enforce()
  3. 返回 {success, whitelist|blacklist, 当前设备}

enforce()（回调与命令共用）：
  1. 蓝牙未启用 → {bluetoothEnabled:false, disconnected:0, unbonded:0}
  2. 连接维度：getConnectedDevices（六 profile 反射调用，地址去重）逐个 violationReason → 违规 → 反射 BluetoothDevice.disconnect()，记录 disconnected 列表
  3. 配对维度（模式非 0 时）：getBondedDevices() 逐个 violationReason → 违规 → 反射 removeBond()，记录 unbonded 列表
  4. violationReason：白名单——地址与名称均不在名单 → "device not in whitelist"；黑名单——地址命中 → "device address in blacklist"、名称命中 → "device name in blacklist"
  5. 回调：ACTION_ACL_CONNECTED → enforce（违规连接被断开，框架重连再次触发，持续闭环）；ACTION_BOND_STATE_CHANGED(BONDED) → enforce（违规配对被解除）；ACTION_STATE_CHANGED(STATE_ON) → enforce
```

### 5.5 ASR-0178（SetScoCallDisabled）

```
1. 缺 disabled → {error}
2. 持久化 scoCallForbidden → ensureReceiver → applyScoPolicy()
3. applyScoPolicy()：
   a. 标志解除 → clearCommunicationDevice()（恢复自动路由）
   b. 标志生效且通话中（getMode() ∈ {MODE_IN_CALL, MODE_IN_COMMUNICATION}）：
      - getAvailableCommunicationDevices() 选非蓝牙设备（耳听筒 TYPE_BUILTIN_EARPIECE 优先 → 扬声器 TYPE_BUILTIN_SPEAKER → 首个非 BT 设备）
      - setCommunicationDevice(target) + stopBluetoothSco() + setBluetoothScoOn(false)
      - 返回 routedTo/communicationDeviceAfter
   c. 标志生效但无通话 → {applied:false, note:"no active call/communication session"}
4. 回调：ACTION_SCO_AUDIO_STATE_CHANGED / MODE_CHANGED / PHONE_STATE_CHANGED / STATE_ON → 重新 applyScoPolicy
```

**安全设计**：本批次命令参数为字符串/整数/布尔/数组；名单与标志仅进入蓝牙服务 Binder 接口、PMS 组件状态与 SharedPreferences，**无字符串进入 shell / 系统命令**，无命令注入面；地址格式与名称长度校验在 Launcher 侧完成；不记录任何凭据类信息。**IPC 访问门禁（代码审查后修复）**：ApiBinder.onEvent 增加调用方校验——仅系统 uid（1000）/shell（2000）/root 或平台签名包（checkSignatures 与 com.hmdm.launcher 匹配，如 testapp）可派发命令，其余调用方直接拒绝（`permission denied: caller uid ...`）；testapp TestCommandReceiver 增加 `android.permission.DUMP` 权限门禁（与 Launcher TestBroadcast 同模式，adb shell 通道不受影响）。

## 6. 权限与归属

- ASR-0180/0181：公开/反射蓝牙 API（`setScanMode`/`getScanMode`/`cancelDiscovery`，裁剪 SDK 缺失符号反射），**本 ROM `setScanMode` 需 `BLUETOOTH_PRIVILEGED`**（signature\|privileged，uid=1000 自动持有；manifest 无需新增声明——该权限对系统 uid 隐式授予）；`BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`（Launcher SYSTEM_FIXED granted=true 核验）；
- ASR-0175：`BLUETOOTH_CONNECT`（signature\|appop\|privileged，manifest 既有声明）+ 隐藏方法反射（`disconnect`/`removeBond`/`getConnectedDevices`（@hide 或 ROM 缺失，改 profile 代理公开接口），ROM 端 dex 核验存在）；S+ 接收蓝牙回调广播需同权限；
- ASR-0174/0176：公开 `PackageManager.setComponentEnabledSetting`，`CHANGE_COMPONENT_ENABLED_STATE`（signature，manifest 既有声明）；
- ASR-0178：公开 `AudioManager.setCommunicationDevice`（API 31+，无特殊权限）；`READ_PHONE_STATE`（manifest 既有声明）覆盖 PHONE_STATE 广播接收通道；
- **无新增 manifest 权限、无 uses-policy 声明、无需重启 framework**；不修改 AIDL / lib 模块；
- 策略引擎为 Launcher 常驻行为（动态广播接收器），与既有策略引擎同生命周期模式：ApiService.onCreate + BootCompletedReceiver 重新武装。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺 disabled/mode/名单数组参数 | 命令返回 {error：...}，不 crash；testapp 侧同样拦截 |
| mode 越界或非数字 | {error："invalid mode: ... (0=off, 1=whitelist, 2=blacklist)"}，不写入 |
| 地址非 AA:BB:CC:DD:EE:FF 格式；名称空/超 248 字节/含控制字符 | {error}，整单拒绝，不写入 |
| 蓝牙未启用/无蓝牙硬件 | 可发现/名单命令如实上报 bluetoothEnabled=false（不 crash）；setScanMode 反射仅在启用时调用 |
| 组件不存在（设置页/OPP 组件） | 设置页：{success:false, error:"bluetooth settings component not found"}；OPP：该组件跳过（components 列表不含），其余继续 |
| 恢复原状态 | OPP 组件/设置页禁用前已处于禁用态的组件记录于 prevOppDisabled/prevPageDisabled，恢复时保持禁用（不越权恢复） |
| 扫描模式反射失败（ROM 差异） | scanModeCorrected=false 如实上报，不伪装成功 |
| 白名单模式 + 空名单 | 语义=全部设备禁止连接/配对（合法配置，文档化）；恢复需换名单或 mode=0 |
| 黑名单模式命中已连接设备 | 立即断开；框架自动重连被回调反复断开（持续执行闭环，logcat 可核验） |
| 黑名单命中已配对设备 | 配对完成即被 removeBond 解除（配对纠正语义；无阻止配对发生的标准接口，文档化） |
| 用户在设置页手动打开可见性 | ACTION_SCAN_MODE_CHANGED 回调约 1 秒内拉回 CONNECTABLE（持续执行闭环） |
| 通话路由无可用非蓝牙设备 | {success:false, error:"no non-Bluetooth communication device available"}，如实上报 |
| 标志解除 | clearCommunicationDevice() 恢复自动路由；OPP/页面组件恢复 DEFAULT/原态 |
| 进程重启/开机 | SharedPreferences 持久化 + ApiService.onCreate/BootCompletedReceiver 经 syncPolicy 重新注册接收器并重新应用全部策略 |
| 本机无 SIM/无蓝牙外设 | SCO 通话路由与名单断连/解配的真实效果无法闭环，命令契约/机制路径在本机全量验证（见需求文档"硬件受限测试说明"） |
| 裁剪 SDK 差异 | setScanMode/getConnectedDevices 等经反射调用（ROM 端存在性 dex 核验），编译期不依赖裁剪 SDK 缺失符号 |

## 8. 真机验证记录（2026-08-07，Android 13 / API 33 userdebug，平台签名 + device owner）

（命令级用例结果见测试用例设计文档，此处记录机制核验关键结论）

| 核验项 | 结果 |
|---|---|
| 设备基线 | 蓝牙出厂关闭（enabled=false/state OFF），无已配对设备；Settings 蓝牙页入口双组件（`Settings$BluetoothSettingsActivity` + ACTION 首选 `Settings$BluetoothBroadcastActivity`）；com.android.bluetooth OPP 组件 9 个（**无 BluetoothOppService**，APK manifest aapt2 核验；6 个 manifest 出厂禁用） |
| **框架通道核验** | framework-bluetooth.jar（/apex/com.android.btservices/javalib）含 setScanMode/getScanMode/cancelDiscovery、BluetoothDevice.removeBond()/disconnect()（TEST-API 隐藏）；framework.jar 含 AudioManager.setCommunicationDevice 全链路（AudioManager/IAudioService/Stub/Proxy）；**setScanMode 需 BLUETOOTH_PRIVILEGED（signature\|privileged，uid=1000 granted=true，普通应用拒绝）**；**BluetoothAdapter.getConnectedDevices(int) 被 MTK fork 删除**（BluetoothManager 委托同缺失方法，运行时核验）——枚举改 profile 代理；裁剪 SDK 缺 setScanMode/getConnectedDevices/HID_HOST/PAN/A2DP_SINK/ACTION_MODE_CHANGED——引擎全部反射 |
| ASR-0180/0181 可发现禁止 | 蓝牙开启后经**真实用户路径**闭环：Settings REQUEST_DISCOVERABLE 对话框 ALLOW（`input tap`）→ 扫描模式短暂 discoverable → logcat `enforceDiscoverable(SCAN_MODE_CHANGED): discoverable scan mode reverted to CONNECTABLE, ok=true` 约 1 秒内回滚；禁止标志生效时设置命令立即纠正（**可发现状态下 SetLimitedDiscoverableForbidden → scanModeCorrected=true**）；允许后对话框路径保持 discoverable（120 秒窗口不被回滚）；两标志独立持久化与进程重启重新武装；testapp `TrySetDiscoverable` 因缺 BLUETOOTH_PRIVILEGED 被 SecurityException 拒绝（如实上报，探测以对话框路径为准）；`TryStartDiscovery` 被框架拒绝（discoveryStarted=false，普通应用不可发现） |
| ASR-0174 页面禁用 | 双组件禁用后逐个读回 disabled、dumpsys disabledComponents 对照一致；**ACTION 回退到"已连接的设备"总览页（本 ROM 特性，父级入口不禁用）**；页面组件直启 `Settings$BluetoothSettingsActivity` 抛 ActivityNotFoundException（pageBlocked=true）；启用恢复两组件 default、直启恢复（pageStarted=true）；原禁用态按组件还原（TC-0174-06 单周期闭环） |
| ASR-0176 传输禁止 | 9 个 OPP 组件禁用后逐个读回 disabled（含 manifest 出厂禁用的 6 个，枚举带 MATCH_DISABLED_COMPONENTS）、`ResolveBluetoothShare` 分享解析失效（resolved=false）；启用恢复 default、解析恢复；**预先 pm disable 的组件恢复时保持禁用（prevOppDisabled 还原）**；PMS 持久化 |
| ASR-0175 名单策略 | 模式/名单设置与持久化（重装后 mode=1 与四名单保持）、非法 mode/地址格式/名单整单拒绝、`ApplyBluetoothAccessPolicy` 评估（本机无已连接/已配对设备，evaluation 空列表如实上报）、蓝牙关闭容错（disconnected=0/unbonded=0）、ACL/BOND/STATE 回调注册与连接缓存维护、进程重启 syncPolicy 重新武装；**真实断连/解配效果需蓝牙设备真机**（见需求文档"硬件受限测试说明"） |
| ASR-0178 SCO 路由 | 标志设置/查询闭环、无通话时 applied=false 如实上报（note=no active call/communication session）、`clearCommunicationDevice` 恢复路径（note=automatic routing restored）、getAvailableCommunicationDevices 设备枚举（本机含内置听筒 type=1）、SCO/MODE/PHONE_STATE 回调通道注册；**通话中真实路由效果需插 SIM + 蓝牙耳机真机**（见需求文档"硬件受限测试说明"） |
| **开发期修复缺陷** | ① SharedPreferences 键类型冲突：单组件版 `prevPageDisabled` 存 boolean，双组件版改读 StringSet → ClassCastException 使 ApiService.onCreate 崩溃（dropbox data_app_crash 栈核验，改键名 `prevPageDisabledComponents` 规避陈旧类型值）；② profile 代理枚举初版在主线程 latch.await → 回调投递主 looper 死锁 → ApiService.onCreate 超时/MainActivity ANR（/data/anr 栈核验），改工作线程 + 4 秒 bounded future + 主线程走 ACL 连接缓存 |
| 测试后设备恢复 | 全部策略标志关闭、名单清空模式 0、OPP/页面组件恢复（Settings 与 com.android.bluetooth disabledComponents 均 0 条）、蓝牙恢复出厂关闭态（enabled=false/state OFF）、无残留 |
