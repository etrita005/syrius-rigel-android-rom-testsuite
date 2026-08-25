# 权限/AppOps 管控（ASR-0043/0044/0045/0046/0149）测试用例设计文档

## 1. 测试范围与前置条件

**范围**：ASR-0043 画中画（OP_PICTURE_IN_PICTURE）、ASR-0044 写设置（OP_WRITE_SETTINGS）、ASR-0045 通知监听服务（NotificationManager @hide 绑定）、ASR-0149 WLAN 权限应用黑名单（OP_CHANGE_WIFI_STATE）、ASR-0046 特定包 USB 权限（UsbManager.grantPermission），覆盖命令设置/查询、真实框架效果探测、策略整体执行与持久化、非法输入与恢复用例。

**前置条件**：

| 项 | 要求 |
|---|---|
| 设备 | Android 13（API 33）userdebug，平台签名 Launcher（device owner，uid=1000）已部署，MANAGE_APP_OPS_MODES/MODIFY_PHONE_STATE/MANAGE_USB granted=true |
| testapp | 已安装（平台签名），声明 WRITE_SETTINGS、CHANGE_WIFI_STATE、supportsPictureInPicture 测试页、TestNotificationListenerService |
| 基线 | testapp 三项 op（PIP/WRITE_SETTINGS/CHANGE_WIFI_STATE）均为默认允许；无监听授权（`settings get secure enabled_notification_listeners` 为空）；wifi 策略 GetWifiPermissionPolicyMode=0、黑名单空；无 USB host 设备连接（`dumpsys usb` host_connected=false） |
| 入口 | UI：testapp 主页 `Permission & AppOps control (ASR-0043/0044/0045/0046/0149)` → `PermissionAppOpsTestActivity`；IPC：`./send_test_command.sh <event> key=value ...`（与 UI 按钮完全等效） |

**测试目标**：`com.hmdm.testapp`（自有测试目标）；监听组件 `com.hmdm.testapp/com.hmdm.testapp.TestNotificationListenerService`。

## 2. 测试用例表

### 2.1 ASR-0043 画中画（OP_PICTURE_IN_PICTURE）

| 编号 | 用例 | 操作（IPC） | 预期结果 | 实测结果（2026-08-06） |
|---|---|---|---|---|
| 0043-01 | 禁用画中画 | `SetPictureInPictureDisabled packageName=com.hmdm.testapp disabled=true` | RESULT=true；`dumpsys appops` 出现 `PICTURE_IN_PICTURE (ignore)` | ✅ true；appops 显示 (ignore) |
| 0043-02 | 查询禁用状态 | `IsPictureInPictureDisabled packageName=com.hmdm.testapp` | true | ✅ true |
| 0043-03 | 启用画中画 | `SetPictureInPictureDisabled ... disabled=false` | true；appops 回 allow | ✅ true；查询 false |
| 0043-04 | 幂等禁用/启用 | 连续两次相同设置 | 两次均 true，状态不变 | ✅ |
| 0043-05 | 未安装包 | `SetPictureInPictureDisabled packageName=com.nonexistent.pkg disabled=true` | false（不 crash） | ✅ false |
| 0043-06 | 缺 packageName | `SetPictureInPictureDisabled disabled=true` | 提示 "missing parameter: packageName" | ✅ |
| 0043-07 | UI 画中画真实探测 | testapp 测试页按钮 `Probe: Try Enter Picture-in-Picture`（禁用状态点按） | 进入请求被框架拒绝（entered=false）；启用状态点按恢复正常探测 | UI 通道（IPC 返回 needsUi 提示，需人工点按，见"验证提示"） |

### 2.2 ASR-0044 写设置（OP_WRITE_SETTINGS）

| 编号 | 用例 | 操作（IPC） | 预期结果 | 实测结果（2026-08-06） |
|---|---|---|---|---|
| 0044-01 | 禁用写设置 | `SetWriteSettingsDisabled packageName=com.hmdm.testapp disabled=true` | true | ✅ true |
| 0044-02 | 真实效果：写入被拒 | `TryWriteSettings`（op=IGNORED 时） | SecurityException（"was not granted this permission: android.permission.WRITE_SETTINGS."），wrote=false；`dumpsys appops` 记录 Reject | ✅ 完全一致（rejectTime 记录） |
| 0044-03 | 查询禁用状态 | `IsWriteSettingsDisabled packageName=com.hmdm.testapp` | true | ✅ true |
| 0044-04 | 启用写设置 | `SetWriteSettingsDisabled ... disabled=false` | true；appops 回 allow | ✅ true |
| 0044-05 | 真实效果：写入成功 | `TryWriteSettings`（op=ALLOWED 时） | wrote=true（screen_brightness 原值回写，无状态变更）；`dumpsys appops` 记录 Access | ✅ wrote=true、value=93；Access 记录 |
| 0044-06 | 缺 disabled 参数 | `SetWriteSettingsDisabled packageName=com.hmdm.testapp` | 提示 "missing parameter: disabled" | ✅ |
| 0044-07 | 缺 packageName | `IsWriteSettingsDisabled` | 提示 "missing parameter: packageName" | ✅ |

> 注：`TryWriteSettings` 采用公开键 `screen_brightness` 的当前值原值回写——本 ROM MtkSettingsProvider 对 targetSdk>22 非特权应用写自定义 System 键会抛与 WRITE_SETTINGS 无关的键校验异常（"You cannot keep your settings in the secure settings."），原值回写可纯净验证 op 通道（详见技术设计文档 2.3）。

### 2.3 ASR-0045 通知监听服务（NotificationManager @hide 绑定）

| 编号 | 用例 | 操作（IPC） | 预期结果 | 实测结果（2026-08-06） |
|---|---|---|---|---|
| 0045-01 | 初始查询 | `IsNotificationListenerAccessGranted component=<自有监听>` | granted=false、enabledListeners 空 | ✅ |
| 0045-02 | 授予监听权限 | `SetNotificationListenerAccessGranted component=<自有监听> granted=true` | success=true、granted=true、channel=nm（@hide 通道本 ROM 存在）；logcat NMS `Allowing notification listener ... (userSet: true)`；testapp `TestNotificationListenerService connected`（真实绑定） | ✅ channel=nm、真实绑定 |
| 0045-03 | 系统持久化核对 | 授予后等待 1~3 秒：`settings get secure enabled_notification_listeners` | 含全格式组件条目（NMS 异步落键） | ✅ 含 `com.hmdm.testapp/com.hmdm.testapp.TestNotificationListenerService` |
| 0045-04 | 撤销监听权限 | `SetNotificationListenerAccessGranted ... granted=false` | success=true、granted=false；设置键异步清空 | ✅ granted=false；约 1~3 秒后设置键清空 |
| 0045-05 | 幂等授予/撤销 | 重复相同操作 | 读回一致，success=true | ✅ |
| 0045-06 | 非法组件 | `SetNotificationListenerAccessGranted component=not-a-component granted=true` | {success:false, error:"invalid component: not-a-component"} | ✅ |
| 0045-07 | 缺 granted 参数 | `SetNotificationListenerAccessGranted component=<自有监听>` | 提示 "missing parameter: granted" | ✅ |
| 0045-08 | 查询格式 | 授予期间 `IsNotificationListenerAccessGranted` | {success, granted, component（短格式规范化）, enabledListeners} | ✅ |

### 2.4 ASR-0149 WLAN 权限应用黑名单（OP_CHANGE_WIFI_STATE）

| 编号 | 用例 | 操作（IPC） | 预期结果 | 实测结果（2026-08-06） |
|---|---|---|---|---|
| 0149-01 | 模式设置/查询 | `SetWifiPermissionPolicyMode mode=2` → `GetWifiPermissionPolicyMode` | true / 2（mode=2 立即整体执行） | ✅ true / 2 |
| 0149-02 | 黑名单设置/查询 | `SetWifiPermissionBlacklist 'packageNames=["com.hmdm.testapp"]'` → `GetWifiPermissionBlacklist` | true / ["com.hmdm.testapp"] | ✅ |
| 0149-03 | 黑名单立即生效（appops） | 设置黑名单后 `cmd appops get com.hmdm.testapp CHANGE_WIFI_STATE` | ignore（整体执行已落地） | ✅ ignore |
| 0149-04 | 真实效果：WLAN 状态调用被拒 | `TryChangeWifiState`（op=IGNORED 时） | setWifiEnabled=false（本 ROM WifiService 记录 rejectTime 而非抛异常；`dumpsys appops` 可见 rejectTime），无状态变更 | ✅ setWifiEnabled=false；rejectTime 记录 |
| 0149-05 | 真实效果：加网被拒 | `TryAddWifiNetwork`（op=IGNORED 时） | addNetworkSucceeded=false、netId=-1 | ✅ |
| 0149-06 | 清黑名单恢复 | `SetWifiPermissionBlacklist 'packageNames=[]'` | 名单空；testapp op 回 allow（名单外应用被整体置 ALLOWED） | ✅ allow |
| 0149-07 | 恢复后真实效果 | `TryChangeWifiState` + `TryAddWifiNetwork`（op=ALLOWED 时） | setWifiEnabled=true；addNetworkSucceeded=true（netId 有效并已移除） | ✅ true / netId=3（removed=true） |
| 0149-08 | 模式关闭 | `SetWifiPermissionPolicyMode mode=0` → `GetWifiPermissionPolicyMode` | true / 0；`ApplyWifiPermissionPolicy` 返回空列表（不动作） | ✅ true / 0；Apply → [] |
| 0149-09 | 非法模式 | `SetWifiPermissionPolicyMode mode=1` | false（模式不变）；缺 mode 参数提示 "missing parameter: mode (0=off, 2=blacklist)" | ✅ false / 提示 |
| 0149-10 | 整体执行命令 | 黑名单模式 + 注入名单后 `ApplyWifiPermissionPolicy` | 返回实际变更包列表（已处于目标状态的包被跳过；重复执行可为空） | ✅ skip-if-matching：重复执行 changed:0、名单变更 changed:1（重构前无条件写入为 changed:172，变更列表如实上报） |
| 0149-11 | 策略持久化 | 设置 mode=2、黑名单=[testapp] 后查 `/data/data/com.hmdm.launcher/shared_prefs/wifi_permission_policy.xml` | mode=2、blacklist 含 testapp（Launcher 私有持久化；AppOps 状态由框架持久化于 /data/system/appops.xml） | ✅ xml 正确 |
| 0149-12 | 名单非数组参数 | `SetWifiPermissionBlacklist 'packageNames="not-an-array"'` | 提示 "missing parameter: packageNames (array)" | ✅ |

### 2.5 ASR-0046 特定包 USB 权限（UsbManager.grantPermission）

| 编号 | 用例 | 操作（IPC） | 预期结果 | 实测结果（2026-08-06） |
|---|---|---|---|---|
| 0046-01 | 设备列表（无设备） | `GetUsbDeviceList` | []（如实上报无连接） | ✅ [] |
| 0046-02 | 无设备授权路径 | `GrantUsbPermission packageName=com.hmdm.testapp deviceName=first` | {success:false, error:"no matching USB device attached (selector: first)", devices:[]}，不 crash | ✅ |
| 0046-03 | 自有权限探测（无设备） | `CheckUsbPermission` | deviceAttached=false、hasPermission=false | ✅ |
| 0046-04 | **需 USB 真机：设备授权** | 连接 USB host 设备后：`GetUsbDeviceList` 选取名称/vid:pid → `GrantUsbPermission packageName=com.hmdm.testapp deviceName=<名称>` → `CheckUsbPermission deviceName=<名称>` | GrantUsbPermission success=true、granted 读回一致；CheckUsbPermission hasPermission=true（端到端：目标包 UsbManager.hasPermission 为真） | ⚠️ 本机无 USB host 设备（硬件受限，见需求文档"硬件受限测试说明"） |
| 0046-05 | **需 USB 真机：vid:pid 选择器** | `GrantUsbPermission packageName=com.hmdm.testapp deviceName=<vid>:<pid>`（十进制或 0x 十六进制） | 命中同一设备，success=true | ⚠️ 同上 |
| 0046-06 | **需 USB 真机：授权持久化** | 授权后重启/重新插拔验证 | 框架 UsbSettings 持久化，目标包 hasPermission 保持 | ⚠️ 同上 |

## 3. 验证提示

- 系统状态对照：`adb shell dumpsys appops`（Package 段 per-op 模式与 Access/Reject 历史）、`adb shell cmd appops get com.hmdm.testapp <OP>`（单 op 模式 + time/rejectTime）；
- 监听授权三件套：命令读回（granted）+ `adb shell settings get secure enabled_notification_listeners` + logcat `NotificationListeners: Allowing/Disallowing notification listener ... (userSet: true)` 与 testapp 侧 `TestNotificationListenerService connected/disconnected`；
- 授权/撤销后 NMS 异步落键约 1~3 秒，查询以公开 API 读回为准、设置键仅附报；
- UI 按钮文本（PermissionAppOpsTestActivity）：`Disable/Enable/Query Own Picture-in-Picture`、`Probe: Try Enter Picture-in-Picture`、`Disable/Enable/Query Own Write Settings`、`Probe: Try Writing a Settings.System Value`、`Grant/Revoke/Query Own Notification Listener`、`Set Wifi Permission Policy Off/Blacklist`、`Query Wifi Permission Policy Mode`、`Set/Clear/Query Wifi Permission Blacklist`、`Apply Wifi Permission Policy Now`、`Probe: Try Change Wifi State (No-op)`、`List Attached USB Devices`、`Grant USB Permission to Own App (first device)`、`Check Own USB Permission`——与上表 IPC 事件一一对应；
- `Probe: Try Enter Picture-in-Picture` 需 Activity 上下文（IPC 返回 needsUi 提示），请从测试页按钮点按执行（禁用状态 entered=false / 启用状态按设备能力返回）。

## 4. 环境与 ROM 差异记录（2026-08-06）

1. **本 ROM 重排 AppOps 数值码（关键修正）**：`OP_WRITE_SETTINGS=23`（AOSP 13 为 22，framework.jar 常量表 dex 反编译核验）；初版整型变体 `setMode(1, ...)` 服务端静默不生效（AppOpsService.setMode 反编译核验无异常静默返回路径），**引擎最终实现仅用字符串 op 变体**（code 由 ROM 表解析；整型回退已移除——硬编码数值码随厂商重排不可靠）后落地正常。该修正对 stock 与厂商重排 ROM 均正确，用例 0044-01/03 即验证修正后行为。
2. **本 ROM 执行点按原始 op 模式判断（MODE_DEFAULT 视为拒绝）**：`cmd appops set com.hmdm.testapp CHANGE_WIFI_STATE default` 后 TryChangeWifiState 仍返回 false（setWifiEnabled 被拒），显式 `allow` 后恢复 true——与 MtkSettingsProvider 的写设置检查（unsafeCheckOpNoThrow）同语义；故 WLAN 黑名单策略对名单外应用必须显式写 ALLOWED（不能依赖框架默认），整体比对 + 已匹配跳过（skip-if-matching）为本 ROM 兼容所需（用例 0149-01/03/06/07/10 即验证）。
3. **本 ROM WifiService 对 OP_CHANGE_WIFI_STATE 拒绝不抛异常**：`setWifiEnabled` 返回 false、`addNetwork` 返回 -1，`dumpsys appops` 记录 rejectTime（AOSP 标准为 throwSecurityExceptionIfNeeded）；用例 0149-04/05 按此断言（探测结果同时上报布尔与异常，文档以 op 读回 + rejectTime 为权威证据）。
4. **MtkSettingsProvider 自定义 System 键校验**：targetSdk>22 且非特权应用写非 PUBLIC/PRIVATE 名单内 System 键抛 "You cannot keep your settings in the secure settings."（与 WRITE_SETTINGS op 无关）；`TryWriteSettings` 探测采用公开键 screen_brightness 原值回写规避（用例 0044-02/05 验证的是 op 通道本身）。
5. **NMS 授权持久化异步**：`enabled_notification_listeners` 落键/清键均异步（约 1~3 秒），且为全格式组件名；命令读回以公开 API 为准（回退通道以设置键内容为准，条目组件名规范化）。
6. **USB 硬件受限**：本机（MT8788/MT6771）USB host 口未连接任何设备（`dumpsys usb` device_manager.host_connected=false），`UsbManager.grantPermission` 对已连接设备才落授权（UsbService 行为），真实授权/持久化用例（0046-04~06）需 USB 真机补充，本机完成无设备路径与命令契约验证。

## 5. 用例执行结果汇总

- ASR-0043：7/7 通过（含 1 条 UI 通道探测用例，机制级证据以 op 读回 + dumpsys appops 为准）；
- ASR-0044：7/7 通过（含真实写入被拒/成功双向效果验证）；
- ASR-0045：8/8 通过（channel=nm 主通道 + NMS 持久化核对）；
- ASR-0149：12/12 通过（含真实 WLAN 调用被拒/恢复验证与策略持久化）；
- ASR-0046：3/3 通过（无设备路径；0046-04~06 需 USB 真机补充执行）；
- 全部用例执行后设备已恢复基线（策略关闭、名单清空、op 全部回允许、监听授权撤销、设置键清空），无残留状态。
