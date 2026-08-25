# 蓝牙管控（ASR-0174/0175/0176/0178/0180/0181）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner；testapp（`com.hmdm.testapp`，平台签名部署）已安装；
- 部署准备：`adb root`、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`（IPC 与 UI 按钮等效，同一 `TestActions.execute()` 引擎）；也可在 testapp UI 的 "Bluetooth" 页面点按对应按钮；
- 蓝牙前置：测试开始时开启蓝牙（`./send_test_command.sh SetBlueOpen open=true`）；
- 对照命令：`adb shell dumpsys bluetooth_manager`（开关状态）、`adb shell dumpsys package com.android.settings | grep -i bluetooth`（页面组件）、`adb shell cmd package dump com.android.bluetooth | grep -i opp`（OPP 组件）、`adb shell logcat -d | grep BluetoothPolicyManager` / `grep BluetoothAccessPolicyManager`（引擎执行日志）；
- 恢复基线（测试开始时记录、结束时恢复）：蓝牙出厂关闭态、五项策略标志全部关闭、名单清空模式 0、OPP/设置页组件 default；
- 本 ROM 特性（2026-08-07 核验）：① 蓝牙客户端类位于 `/apex/com.android.btservices/javalib/framework-bluetooth.jar`（boot classpath）；② 一般可发现与有限可发现共用扫描模式常量 `SCAN_MODE_CONNECTABLE_DISCOVERABLE`（ASR-0180/0181 同一强制执行）；③ **本 ROM 无 `BluetoothOppService`，OPP 组件为 9 个**（7 activity + 2 receiver，含 `BluetoothOppTransferHistory`；aapt2 核验），且**其中 6 个在 manifest 中 `android:enabled="false"`**（Receiver/TransferActivity/IncomingFileConfirmActivity/BtErrorActivity/TransferHistory/HandoverReceiver 出厂即禁用，仅 LauncherActivity/BtEnableActivity/BtEnablingActivity 启用）——组件枚举须带 `MATCH_DISABLED_COMPONENTS`；④ **`BluetoothAdapter.setScanMode` 需 `BLUETOOTH_PRIVILEGED`**（signature|privileged，仅 uid=1000/系统应用持有，testapp 探测调用被拒属预期，回滚验证以设置页可见性开关路径为准）；⑤ **本 ROM `BluetoothAdapter` 无 `getConnectedDevices(int)`**（MTK fork 删除，BluetoothManager 亦委托同缺失方法），引擎改经 profile 代理（`getProfileProxy` + 代理 `getConnectedDevices()`，其回调投递到主 looper——枚举必须在工作线程执行，否则 ApiService.onCreate 死锁超时）；⑥ 蓝牙设置页入口双组件：页面本体 `Settings$BluetoothSettingsActivity` + 本 ROM 的 ACTION `android.settings.BLUETOOTH_SETTINGS` 首选解析 `Settings$BluetoothBroadcastActivity`（禁用后 ACTION 回退到"已连接的设备"总览页 `Settings$ConnectedDeviceDashboardActivity`——总览页属父级入口不禁用，页面可用性以页面组件直启为准）；⑦ 项目 compileSdk 33 的 android.jar 为裁剪版（`BluetoothAdapter.setScanMode`/`getConnectedDevices`/`BluetoothProfile.HID_HOST/PAN/A2DP_SINK`/`AudioManager.ACTION_MODE_CHANGED` 缺失，javap 核验），引擎对这些调用全部反射，命令行为不受影响；⑧ 本机无 SIM、无蓝牙外设——SCO 通话路由与名单断连/解配的真实效果为受限验证（见需求文档"硬件受限测试说明"），命令契约与机制路径在本机全量验证；⑨ testapp 直读蓝牙状态/调 setScanMode 需运行时授权 `pm grant com.hmdm.testapp android.permission.BLUETOOTH_SCAN android.permission.BLUETOOTH_CONNECT`（本 ROM 两者为 dangerous 级，平台签名不自动授予）。

## 2. 测试用例表

### 2.1 ASR-0180 禁止可被发现模式（Set/IsDiscoverableForbidden）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0180-01 基线查询 | 蓝牙开启后 `./send_test_command.sh GetBluetoothStatus` | success=true；discoverableForbidden=false；bluetoothEnabled=true；scanMode=connectable（蓝牙开启默认态） |
| TC-0180-02 设置禁止 | `./send_test_command.sh SetDiscoverableForbidden disabled=true` | success=true；discoverableForbidden=true；scanModeCorrected=false（当前本就 connectable）；`IsDiscoverableForbidden` 复查 true |
| TC-0180-03 探测回滚 | 禁止状态下 `./send_test_command.sh TrySetDiscoverable`（testapp 模拟设置页可见性开关） | testapp 调用 setScanMode 被拒（`SecurityException: Need BLUETOOTH PRIVILEGED`——本 ROM setScanMode 需 signature\|privileged 权限，testapp 不持有，如实上报属预期）；**回滚验证以真实用户路径为准**：`am start -a android.bluetooth.adapter.action.REQUEST_DISCOVERABLE --ei android.bluetooth.adapter.extra.DISCOVERABLE_DURATION 120` → uiautomator 定位 ALLOW → `input tap` → 扫描模式短暂变为 discoverable 后约 1 秒内被拉回 connectable，logcat `enforceDiscoverable(SCAN_MODE_CHANGED): discoverable scan mode reverted to CONNECTABLE, ok=true` |
| TC-0180-04 取消发现 | 同上 REQUEST_DISCOVERABLE 对话框路径 | 对话框确认后扫描模式被回滚（同上）；`GetBluetoothStateLocal` 复查 scanMode=connectable、discovering=false |
| TC-0180-05 恢复允许 | `./send_test_command.sh SetDiscoverableForbidden disabled=false` | success=true；discoverableForbidden=false；重复 REQUEST_DISCOVERABLE 对话框确认后扫描模式保持 discoverable（120 秒可见窗口，不被回滚，无新增 enforceDiscoverable 日志） |
| TC-0180-06 缺参 | `./send_test_command.sh SetDiscoverableForbidden` | 返回 `missing parameter: disabled`，不 crash |
| TC-0180-07 蓝牙关闭容错 | 蓝牙关闭时设置禁止 | success=true；bluetoothEnabled=false；scanModeCorrected=false，不 crash |

### 2.2 ASR-0181 禁止有限可被发现模式（Set/IsLimitedDiscoverableForbidden）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0181-01 设置禁止 | `./send_test_command.sh SetLimitedDiscoverableForbidden disabled=true` | success=true；limitedDiscoverableForbidden=true；与 ASR-0180 共用强制（任一标志生效）；**当前已处于可发现状态时立即纠正：scanModeCorrected=true、scanMode=connectable** |
| TC-0181-02 探测回滚 | 禁止状态下 REQUEST_DISCOVERABLE 对话框 ALLOW 路径（同 TC-0180-03；`TryStartDiscovery` 探测在本 ROM 被框架拒绝 discoveryStarted=false——普通应用 startDiscovery 返回 false，如实上报） | 对话框确认后约 1 秒内扫描模式被拉回 connectable（logcat `enforceDiscoverable(SCAN_MODE_CHANGED)`） |
| TC-0181-03 独立标志查询 | `./send_test_command.sh IsLimitedDiscoverableForbidden` | success=true；limitedDiscoverableForbidden=true；discoverableForbidden 不受影响（本例为 false） |
| TC-0181-04 恢复允许 | `SetLimitedDiscoverableForbidden disabled=false` | success=true；`TrySetDiscoverable` 保持 discoverable |
| TC-0181-05 缺参 | `./send_test_command.sh SetLimitedDiscoverableForbidden` | 返回 `missing parameter: disabled`，不 crash |
| TC-0181-06 与 0180 叠加 | 两标志同时禁止时 `TrySetDiscoverable` | 回滚生效；关闭其一（0180）仍回滚（0181 仍生效） |

### 2.3 ASR-0174 蓝牙页面可用（Set/IsBluetoothPageDisabled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0174-01 基线 | `./send_test_command.sh IsBluetoothPageDisabled` | success=true；pageDisabled=false；readBack=default |
| TC-0174-02 禁用页面 | `./send_test_command.sh SetBluetoothPageDisabled disabled=true` | success=true；pageDisabled=true；components 两个条目（`Settings$BluetoothSettingsActivity` 页面本体 + `Settings$BluetoothBroadcastActivity` 本 ROM 的 ACTION 首选入口）readBack 均 disabled；`dumpsys package com.android.settings` disabledComponents 含两组件 |
| TC-0174-03 入口探测 | 禁用状态下 `./send_test_command.sh TryOpenBluetoothSettings` | actionStarted=true（**本 ROM ACTION_BLUETOOTH_SETTINGS 首选入口被禁用后回退到"已连接的设备"总览页 `Settings$ConnectedDeviceDashboardActivity`**——父级入口不禁用，如实上报）；**pageStarted=false、pageBlocked=true**（页面组件直启 `Settings$BluetoothSettingsActivity` 抛 ActivityNotFoundException，页面不可用判定以此为准）；`am start -n com.android.settings/.Settings$BluetoothSettingsActivity` 同样失败 |
| TC-0174-04 启用恢复 | `./send_test_command.sh SetBluetoothPageDisabled disabled=false` | success=true；pageDisabled=false；components readBack 均 default（原禁用态还原）；`TryOpenBluetoothSettings` pageStarted=true |
| TC-0174-05 缺参 | `./send_test_command.sh SetBluetoothPageDisabled` | 返回 `missing parameter: disabled`，不 crash |
| TC-0174-06 幂等 | 连续两次 disabled=true / disabled=false | 第二次 success=true，状态不变，不 crash；完整禁用→启用单周期后两组件均恢复 default |

### 2.4 ASR-0176 禁止蓝牙传输文件（Set/IsBluetoothFileTransferDisabled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0176-01 基线 | `./send_test_command.sh IsBluetoothFileTransferDisabled` | success=true；fileTransferDisabled=false；components 列表含 9 个 OPP 组件（aapt2 核验，含 BluetoothOppTransferHistory），其中 3 个 state=enabled（LauncherActivity/BtEnableActivity/BtEnablingActivity）、6 个 state=default（**本 ROM manifest 出厂 `android:enabled="false"`**：Receiver/HandoverReceiver/TransferActivity/IncomingFileConfirmActivity/BtErrorActivity/TransferHistory） |
| TC-0176-02 分享解析基线 | `./send_test_command.sh ResolveBluetoothShare` | resolved=true；component 含 `com.android.bluetooth/.opp.BluetoothOppLauncherActivity` |
| TC-0176-03 禁止传输 | `./send_test_command.sh SetBluetoothFileTransferDisabled disabled=true` | success=true；fileTransferDisabled=true；components 全部 state=disabled（9/9） |
| TC-0176-04 组件核对 | `adb shell dumpsys package com.android.bluetooth \| grep -A25 disabledComponents:` | 9 个 OPP 组件全部进入 disabledComponents（PMS 持久化，重启后保持） |
| TC-0176-05 分享解析失效 | 禁止状态下 `./send_test_command.sh ResolveBluetoothShare` | resolved=false（SEND 不再解析到 BluetoothOppLauncherActivity） |
| TC-0176-06 允许恢复 | `./send_test_command.sh SetBluetoothFileTransferDisabled disabled=false` | success=true；components 全部恢复 default；`ResolveBluetoothShare` resolved=true |
| TC-0176-07 缺参 | `./send_test_command.sh SetBluetoothFileTransferDisabled` | 返回 `missing parameter: disabled`，不 crash |
| TC-0176-08 原禁用态还原 | 预先 `pm disable com.android.bluetooth/.opp.BluetoothOppReceiver` → 禁止 → 允许 | 恢复后 BluetoothOppReceiver 仍为 disabled（prevOppDisabled 还原），其余 default |

### 2.5 ASR-0178 禁止蓝牙外设通话（Set/IsScoCallDisabled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0178-01 基线 | `./send_test_command.sh IsScoCallDisabled` | success=true；scoCallForbidden=false；audioMode 返回当前值（0）；scoActive=false |
| TC-0178-02 设置禁止（无通话） | `./send_test_command.sh SetScoCallDisabled disabled=true` | success=true；scoCallForbidden=true；callActive=false；applied=false；note=no active call/communication session |
| TC-0178-03 通信设备枚举 | `./send_test_command.sh GetBluetoothStatus` | communicationDevice 字段存在（内置听筒/扬声器），不 crash |
| TC-0178-04 恢复允许 | `./send_test_command.sh SetScoCallDisabled disabled=false` | success=true；scoCallForbidden=false；**本机从未强制过路由：note=`sco forbid flag cleared, nothing was forced before`（scoRouteForced 门禁，不执行 clearCommunicationDevice、不触碰用户配置的通信设备）**；实际强制过路由后清除路径：note=`automatic routing restored` |
| TC-0178-05 缺参 | `./send_test_command.sh SetScoCallDisabled` | 返回 `missing parameter: disabled`，不 crash |
| TC-0178-06 回调通道注册 | 禁止状态下 logcat | `bluetooth policy receiver registered`；SCO_AUDIO_STATE_CHANGED/MODE_CHANGED/PHONE_STATE_CHANGED 注册成功无异常 |

### 2.6 ASR-0175 蓝牙连接黑白名单（Set/GetBluetoothAccessPolicy、名单、Apply）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0175-01 基线查询 | `./send_test_command.sh GetBluetoothAccessPolicy` | success=true；mode=0；modeName=off；四份名单为空；bluetoothEnabled=true；connectedDevices/bondedDevices 如实返回（本机为空） |
| TC-0175-02 非法 mode | `./send_test_command.sh SetBluetoothAccessPolicy mode=5` | error `invalid mode: 5 (0=off, 1=whitelist, 2=blacklist)`，不写入 |
| TC-0175-03 缺参 | `./send_test_command.sh SetBluetoothAccessPolicy` | 返回 `missing or invalid parameter: mode ...`，不 crash |
| TC-0175-04 白名单模式 | `SetBluetoothAddressWhitelist 'addresses=["AA:BB:CC:DD:EE:FF"]'` 后 `SetBluetoothAccessPolicy mode=1` | 两个命令均 success=true；modeName=whitelist；whitelist 含条目（**校验点：Set 命令返回键名必须为 whitelist**）；enforcement 上报当前设备评估结果 |
| TC-0175-05 黑名单模式 | `SetBluetoothAddressBlacklist 'addresses=["AA:BB:CC:DD:EE:FF"]'` 后 `SetBluetoothAccessPolicy mode=2` | success=true；modeName=blacklist；blacklist 含条目；enforcement 上报 |
| TC-0175-06 名称名单 | `SetBluetoothNameWhitelist 'names=["HYX-MDM-TEST-DEVICE"]'` | success=true；nameWhitelist 含条目 |
| TC-0175-07 非法地址格式 | `./send_test_command.sh SetBluetoothAddressBlacklist 'addresses=["not-a-mac"]'` | error `invalid address entries (expected AA:BB:CC:DD:EE:FF): [not-a-mac]`，整单拒绝、原名单不变 |
| TC-0175-08 缺名单参数 | `./send_test_command.sh SetBluetoothAddressWhitelist` | 返回 `missing parameter: addresses (array)`，不 crash |
| TC-0175-09 非法名称 | `./send_test_command.sh SetBluetoothNameWhitelist 'names=[""]'` | error `invalid name entries (empty or longer than 248 bytes): []`，整单拒绝 |
| TC-0175-10 立即评估 | `./send_test_command.sh ApplyBluetoothAccessPolicy` | success=true；mode 为当前值；bluetoothEnabled=true；connectedDevices/bondedDevices 列表；disconnected/unbonded 空（本机无设备） |
| TC-0175-11 蓝牙关闭容错 | 蓝牙关闭时 ApplyBluetoothAccessPolicy | success=true；bluetoothEnabled=false；disconnected=0；unbonded=0，不 crash |
| TC-0175-12 持久化 | 设置 mode=1 + 名单后重装 Launcher（进程重启） | `GetBluetoothAccessPolicy` 复查 mode=1 与名单保持；接收器经 syncPolicy 重新注册（logcat `bluetooth access policy receiver registered` + `syncPolicy: mode=1`） |
| TC-0175-13 恢复 | 名单清空 + mode=0 | success=true；modeName=off；四份名单空 |

### 2.7 通用用例

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-M-01 缺参容错 | 各 Set 命令缺参执行（SetDiscoverableForbidden/SetLimitedDiscoverableForbidden/SetBluetoothPageDisabled/SetBluetoothFileTransferDisabled/SetScoCallDisabled/SetBluetoothAccessPolicy/四个名单命令） | 均返回缺参提示，不 crash |
| TC-M-02 未知事件 | `./send_test_command.sh SetDiscoverableXXX` | 返回 unknown event，不 crash |
| TC-M-03 事件目录 | `./send_test_command.sh ListEvents` | 目录含 25 个新事件（logcat 行超长被截断属 logcat 限制，以单事件可调用为准） |
| TC-M-04 UI 等效 | testapp UI "Bluetooth" 页逐一按钮（`dumpsys activity top` 核对 32 个按钮 + 6 分组标题渲染） | 按钮与 IPC 事件一一对应（同一 TestActions 引擎）；页面正常渲染 |
| TC-M-05 返回值 JDK 类型 | 检查命令返回 JSON | 全部为 Map/List/基本类型，无自定义类（AIDL 通道约束；BluetoothDevice/AudioDeviceInfo 对象仅存在于 Launcher 进程内，输出为字符串/整数列表） |
| TC-M-06 状态恢复 | 用例执行结束后复查 | 五项策略标志全部 false、名单空模式 0、OPP/设置页组件 default、蓝牙恢复出厂关闭态，无测试残留 |

## 3. 验证提示

- 结果查看：`./send_test_command.sh` 输出 `Broadcast completed: data=...` 与 logcat `HYX-TESTAPP-CMD` 行；
- 蓝牙开关对照：`adb shell dumpsys bluetooth_manager`（`enabled: false/true`、`state: OFF/ON`）；
- 扫描模式对照：`adb shell dumpsys bluetooth_manager | grep -iE "scan"` 或 testapp `GetBluetoothStateLocal`（none/connectable/discoverable）；
- 引擎执行日志：`adb shell logcat -d | grep BluetoothPolicyManager`（`enforceDiscoverable(SCAN_MODE_CHANGED): discoverable scan mode reverted to CONNECTABLE`、`applyOppState: disabled com.android.bluetooth/.opp...`、`applyScoPolicy(...)`）；`grep BluetoothAccessPolicyManager`（`enforce(ACL_CONNECTED): violation=...`、`syncPolicy: mode=...`）；
- 组件状态对照：`adb shell cmd package dump com.android.bluetooth | grep -iE "opp.BluetoothOpp(Receiver|Launcher)"`（enabled=2=DISABLED / 0=DEFAULT）、`adb shell dumpsys package com.android.settings | grep -A2 BluetoothSettingsActivity`；
- 标志持久化文件（排查用）：`/data/data/com.hmdm.launcher/shared_prefs/bluetooth_policy.xml`、`/data/data/com.hmdm.launcher/shared_prefs/bluetooth_access_policy.xml`；
- 数值参数说明：mode 支持数值与数字字符串；布尔参数 `true`/`false` 透传为 Boolean；
- UI 注意：本机（银星 ROM）通知栏偶发遮挡（`mCurrentFocus=NotificationShade`），UI 渲染核对用 `dumpsys activity top` 的 ViewHierarchy（按钮 id/文本齐全即通过），uiautomator dump 被 SystemUI 窗口覆盖时不代表应用异常。

## 4. 实测结果（2026-08-07，Android 13 / API 33 userdebug，平台签名 + device owner）

全部用例经 `send_test_command.sh` IPC 通道执行（与 UI 按钮共用 TestActions 引擎），关键系统状态经 adb 对照。**设备基线（实测）**：蓝牙出厂关闭（enabled=false、state OFF）；无已配对设备；无任何蓝牙策略。

| 用例 | 实测结果 |
|---|---|
| TC-0180-01 | 通过：`{"bluetoothEnabled":true,"scanMode":"connectable","discoverableForbidden":false,"bondedDevices":[]}` |
| TC-0180-02 | 通过：`{"success":true,"forbidden":true,"scanModeCorrected":false,"scanMode":"connectable","discoverableForbidden":true}`；Is 复查 true |
| TC-0180-03 | 通过：`TrySetDiscoverable` 如实上报 `SecurityException: Need BLUETOOTH PRIVILEGED permission`（本 ROM setScanMode 需 signature\|privileged，testapp 不持有）；真实路径 REQUEST_DISCOVERABLE 对话框 ALLOW（`input tap` 575,881）→ 扫描模式短暂 discoverable 后被回滚，logcat `enforceDiscoverable(SCAN_MODE_CHANGED): discoverable scan mode reverted to CONNECTABLE, ok=true` |
| TC-0180-04 | 通过：对话框路径同 TC-0180-03；`GetBluetoothStateLocal` 复查 `"scanMode":"connectable","discovering":false` |
| TC-0180-05 | 通过：允许后对话框 ALLOW → `"scanMode":"discoverable"` 保持（120 秒可见窗口），无新增 enforceDiscoverable 日志 |
| TC-0180-06 | 通过：`missing parameter: disabled` |
| TC-0180-07 | 通过：蓝牙关闭时设置 `{"success":true,"bluetoothEnabled":false,"scanModeCorrected":false}`，不 crash |
| TC-0181-01 | 通过：**可发现状态下设置禁止 → `"scanModeCorrected":true,"scanMode":"connectable"`（立即纠正）** |
| TC-0181-02 | 通过：对话框 ALLOW 后被回滚（logcat `enforceDiscoverable(SCAN_MODE_CHANGED)` ok=true）；`TryStartDiscovery` 本 ROM 返回 `"discoveryStarted":false`（框架拒绝普通应用 discovery，如实上报） |
| TC-0181-03 | 通过：`{"limitedDiscoverableForbidden":true,"discoverableForbidden":false}`（独立标志） |
| TC-0181-04 | 通过：两标志关闭后 success=true |
| TC-0181-05 | 通过：`missing parameter: disabled` |
| TC-0181-06 | 通过：两标志同时禁止时对话框路径仍回滚 connectable；关闭 0180 后 0181 仍生效 |
| TC-0174-01 | 通过：`{"pageDisabled":false,"components":[{...BluetoothSettingsActivity,"state":"enabled"},{...BluetoothBroadcastActivity,"state":"enabled"}]}` |
| TC-0174-02 | 通过：`{"pageDisabled":true,"components":[{...BluetoothSettingsActivity,"state":"disabled"},{...BluetoothBroadcastActivity,"state":"disabled"}]}`；dumpsys disabledComponents 含两组件 |
| TC-0174-03 | 通过：`{"actionStarted":true,"pageStarted":false,"pageBlocked":true}`——ACTION 回退到"已连接的设备"总览页（本 ROM 特性，父级入口不禁用），**页面组件直启被拒（ActivityNotFoundException）** |
| TC-0174-04 | 通过：components 两条目均 `"state":"default"`；probe `"pageStarted":true` |
| TC-0174-05 | 通过：`missing parameter: disabled` |
| TC-0174-06 | 通过：完整禁用→启用单周期两组件均恢复 default（幂等） |
| TC-0176-01 | 通过：基线 components 9 个：3 个 `"state":"enabled"` + 6 个 `"state":"default"`（**本 ROM manifest 出厂禁用**，aapt2 核验） |
| TC-0176-02 | 通过：`{"resolved":true,"component":"com.android.bluetooth/com.android.bluetooth.opp.BluetoothOppLauncherActivity"}` |
| TC-0176-03 | 通过：9/9 组件 `"state":"disabled"` |
| TC-0176-04 | 通过：dumpsys disabledComponents 含全部 9 个 OPP 组件 |
| TC-0176-05 | 通过：`{"resolved":false}`（分享解析失效） |
| TC-0176-06 | 通过：9/9 恢复 `"state":"default"`；`ResolveBluetoothShare` 恢复 resolved=true |
| TC-0176-07 | 通过：`missing parameter: disabled` |
| TC-0176-08 | 通过：预先 `pm disable BluetoothOppReceiver` → 禁止 → 允许：BluetoothOppReceiver 保持 `"state":"disabled"`（prevOppDisabled 还原），其余 8 个恢复 default |
| TC-0178-01 | 通过：`{"scoCallForbidden":false,"audioMode":0,"communicationDevice":"type=1,product=Android","scoActive":false}` |
| TC-0178-02 | 通过：`{"scoCallForbidden":true,"callActive":false,"applied":false,"note":"no active call/communication session"}`（如实上报） |
| TC-0178-03 | 通过：GetBluetoothStatus 含 `"communicationDevice":"type=1,product=Android"`（内置听筒） |
| TC-0178-04 | 通过：`{"scoCallForbidden":false,"note":"sco forbid flag cleared, nothing was forced before"}`（代码审查后：仅强制过路由才 clearCommunicationDevice，本机从未强制故走新 note） |
| TC-0178-05 | 通过：`missing parameter: disabled` |
| TC-0178-06 | 通过：logcat `BluetoothPolicyManager bluetooth policy receiver registered`（SCO_AUDIO_STATE_CHANGED/MODE_CHANGED/PHONE_STATE_CHANGED 注册无异常） |
| TC-0175-01 | 通过：`{"mode":0,"modeName":"off",四名单空,"bluetoothEnabled":true,"connectedDevices":[],"bondedDevices":[]}` |
| TC-0175-02 | 通过：`{"error":"invalid mode: 5 (0=off, 1=whitelist, 2=blacklist)","success":false}` |
| TC-0175-03 | 通过：`missing parameter: mode (0=off, 1=whitelist, 2=blacklist)` |
| TC-0175-04 | 通过：地址白名单 `{"success":true,"whitelist":["AA:BB:CC:DD:EE:FF"]}`（**键名 whitelist 正确**）；mode=1 → `"modeName":"whitelist"`、enforcement 上报 |
| TC-0175-05 | 通过：地址黑名单 `{"success":true,"blacklist":["AA:BB:CC:DD:EE:FF"]}`；mode=2 → `"modeName":"blacklist"` |
| TC-0175-06 | 通过：`{"success":true,"whitelist":["HYX-MDM-TEST-DEVICE"]}`（nameWhitelist） |
| TC-0175-07 | 通过：`{"error":"invalid address entries (expected AA:BB:CC:DD:EE:FF): [not-a-mac]","success":false}` |
| TC-0175-08 | 通过：`missing parameter: addresses (array)` |
| TC-0175-09 | 通过：`{"error":"invalid name entries (empty or longer than 248 bytes): []","success":false}` |
| TC-0175-10 | 通过：`{"mode":2,"connected":false,"disconnected":[],"unbonded":[],"connectedDevices":[],"bondedDevices":[]}` |
| TC-0175-11 | 通过：蓝牙关闭时 `{"bluetoothEnabled":false,"disconnected":0,"unbonded":0}`，不 crash |
| TC-0175-12 | 通过：mode=1 + 名单 → Launcher 重装（进程重启）→ 复查 `"mode":1`、addressWhitelist/nameWhitelist 保持；logcat `BluetoothAccessPolicyManager syncPolicy: mode=1` + `bluetooth access policy receiver registered` |
| TC-0175-13 | 通过：四名单清空 + mode=0 → `"mode":0`，四名单空 |
| TC-M-01 | 通过：10 个 Set 命令缺参均返回缺参提示，不 crash |
| TC-M-02 | 通过：`unknown event: SetDiscoverableXXX (try ListEvents)` |
| TC-M-03 | 通过：ListEvents 返回含全部新事件（logcat 行 4KB 截断为 logcat 限制，单事件可调用验证全过） |
| TC-M-04 | 通过（dumpsys activity top）：BluetoothTestActivity 渲染 32 个按钮 + 6 个分组标题（本机通知栏偶发遮挡为环境现象） |
| TC-M-05 | 通过：返回值全 JDK Map/List/基本类型（AIDL 通道约束） |
| TC-M-06 | 通过：五项策略标志全部 false、名单空 mode=0、OPP/页面组件恢复（Settings disabledComponents 0 条、com.android.bluetooth disabledComponents 0 条）、蓝牙恢复出厂关闭态（enabled=false/state OFF） |

**实现决策记录（2026-08-07 真机核验）**：① 可发现模式（ASR-0180/0181）在 Android 层面共用同一扫描模式常量 `SCAN_MODE_CONNECTABLE_DISCOVERABLE`（一般可发现与设置页 120 秒可见窗口/配对流程的有限可发现无独立常量），两条需求独立持久化标志、共用"扫描模式强制拉回 CONNECTABLE + ACTION_SCAN_MODE_CHANGED 回调持续执行"引擎；**本 ROM `BluetoothAdapter.setScanMode` 需 `BLUETOOTH_PRIVILEGED`（signature\|privileged）**——testapp 探测调用被 SecurityException 拒绝（如实上报），回滚验证以真实用户路径（Settings REQUEST_DISCOVERABLE 对话框 ALLOW）闭环：设置页可见性开关打开→SCAN_MODE_CHANGED→约 1 秒内拉回（logcat ok=true 证据）；② **本 ROM 无 `BluetoothOppService` 且 6/9 个 OPP 组件 manifest 出厂禁用**（Bluetooth APK aapt2 核验），ASR-0176 落地为禁用全部 9 个 `.opp.*` 组件（枚举带 MATCH_DISABLED_COMPONENTS），分享解析（SEND→BluetoothOppLauncherActivity）失效闭环；③ **本 ROM `BluetoothAdapter` 无 `getConnectedDevices(int)`**（MTK fork 删除，BluetoothManager 委托同缺失方法，运行时反射 NoSuchMethodException/InvocationTargetException 核验）——ASR-0175 连接枚举改经 profile 代理（`getProfileProxy` + 代理 `getConnectedDevices()`）；**代理回调投递到主 looper，枚举必须在工作线程执行**（初版在主线程 latch.await 造成 ApiService.onCreate 死锁超时，改 4 秒 bounded future + 主线程走 ACL 回调维护的连接缓存）；④ 蓝牙设置页入口双组件（页面本体 + 本 ROM ACTION 首选入口 BluetoothBroadcastActivity，禁用后 ACTION 回退到"已连接的设备"总览页——父级入口不禁用），页面可用性以页面组件直启 ActivityNotFoundException 为准；⑤ 开发期修复缺陷：**SharedPreferences 键类型冲突**——单组件版 `prevPageDisabled` 存 boolean，双组件版改读 StringSet 触发 ClassCastException 使 ApiService.onCreate 崩溃（改键名 `prevPageDisabledComponents` 规避陈旧类型值）；⑥ 项目 compileSdk 33 的 android.jar 为裁剪版（javap 核验缺失 setScanMode/getConnectedDevices/HID_HOST/PAN/A2DP_SINK/ACTION_MODE_CHANGED），引擎全部反射（ROM 端 dex 核验存在）；⑦ testapp 蓝牙探测需运行时授权 `pm grant ... BLUETOOTH_SCAN/BLUETOOTH_CONNECT`（本 ROM dangerous 级，平台签名不自动授予）。

**部署注意**：本批次不修改 `device_admin.xml`、无新增 manifest 权限、**无需重启 framework**；`adb install -r` 重装 Launcher 会结束其进程且不会自动重启，需重新拉起 HOME（`am start -a android.intent.action.MAIN -c android.intent.category.HOME`）后 testapp 进程亦需重启（root `kill <pid>` 后 `am start`，勿用 force-stop——MTK DuraSpeed 会抑制其 manifest receiver）以重建 AIDL 绑定。
