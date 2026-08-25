# MDM 测试 App（testapp）使用指南

## 1. 文档目的

本文档说明 `mdm_launcher/hmdm-android/testapp` 的使用方法。该测试 App 是 MDM Launcher（`com.hmdm.launcher`，平台签名 + device owner）各需求功能的真机验证工具，具有**双重入口**：

1. **UI 入口**：按功能集合划分的测试 Activity（测试界面），人工点按验证；
2. **IPC 入口**：adb shell 广播命令，与 UI 按钮**完全等效**，供大模型/脚本自动化测试，无需模拟点击。

IPC 与 UI 共用同一测试引擎 `TestActions.execute()`，因此两者行为、参数、返回值完全一致。

**适用范围**：目标平台 Android 13（API 33）userdebug ROM，Launcher 需已安装、已设为 device owner 且 testapp 已安装（普通应用，无需平台签名）。

## 2. App 结构

### 2.1 测试界面（Activity）

| Activity | 功能集合 | 对应需求 |
|---|---|---|
| `MainActivity` | 主页：连接状态探测、功能入口导航、发送测试通知 | - |
| `NotificationTestActivity` | 通知管控：按包启用/禁用、黑白名单、策略模式、锁屏通知、状态栏通知、自身测试通知 | ASR-0053/0054/0055/0057/0059 |
| `CameraMicTestActivity` | 摄像头/麦克风管控：策略设置/查询、真实硬件验证 | ASR-0207/0369 |
| `LogControlTestActivity` | 日志缓冲区大小与日志级别：设置/查询、日志发射 | ASR-0189/0190 |
| `NetworkTestActivity` | 网络黑白名单：域名/IP 名单与策略模式、防火墙状态、DNS/TCP/HTTP 真实网络验证 | ASR-0135/0136 |
| `StatsQueryTestActivity` | 统计查询：应用流量、应用耗电、运行时长、正在运行进程、崩溃/ANR 记录 | ASR-0139/0372/0021-0023 |
| `ProcessControlTestActivity` | 进程管控：结束指定应用进程、清理后台进程 | ASR-0027/0028 |
| `AppRunPolicyTestActivity` | 应用运行/隐藏管控：禁止运行白名单、耗电/忽略耗电优化白名单、指定应用隐藏/启用 | ASR-0016/0017/0030/0099/0104/0131 |
| `SystemSettingsTestActivity` | 全局/安全设置管控：captive portal 弹窗、不保留活动、模拟定位、定位模式、字体大小、全面屏手势导航、BACK 键、安卓小动画 | ASR-0166/0204/0205/0314/0345/0346/0407/0426 |
| `LockScreenPolicyTestActivity` | 锁屏策略：强认证超时、密码更改宽限期、连续数字序列长度上限 | ASR-0359/0363/0365 |
| `AccountBackupTestActivity` | 账户/备份/同步：系统备份、谷歌账户、Google 备份和恢复、自动同步主开关、谷歌账户自动同步 | ASR-0124/0129/0132/0128/0130 |
| `AccessibilityScreenshotUpdateTestActivity` | 无障碍快捷方式、截屏（系统/手动）、在线 FOTA 策略 | ASR-0078/0185/0186/0444 |
| `AccessibilityTestActivity` | 无障碍服务控制：自有/第三方无障碍服务免交互激活注销、可使用服务白名单/黑名单策略 | ASR-0074/0075 |
| `WifiControlTestActivity` | WLAN 强管控：AP 配置锁定、SSID 白名单、手动添加网络、编辑 WLAN、最低安全级别、WLAN 直连、热点配置；真实框架执行验证 | ASR-0150/0152/0153/0155/0158/0160/0168 |
| `WifiAccessTestActivity` | WLAN 配置/接入管控：配置网络（开放/WPA2/企业）、删除热点、已保存列表、SSID 黑白名单、MAC 黑白名单、自动连接策略；真实框架执行验证 | ASR-0144/0145/0147/0148/0151/0161 |
| `DeviceStateTestActivity` | 定位/导航栏/飞行模式/NFC/移动数据：定位开关与强制打开、**被动定位允许/禁止/查询**、飞行模式开关与强制打开、导航栏显示/隐藏、NFC 开关与强制打开、移动数据开关/强制关闭/强制开启/状态锁定 | ASR-0275/0276/0277/0279/0310/0311/0313/0315/0316/0317/0319/0320/0321/0349 |
| `TetheringTestActivity` | 热点/网络共享：WiFi 热点开关、USB/蓝牙共享静默开关、网络共享总开关、USB/WLAN/蓝牙共享禁止策略、用户共享设置限制 | ASR-0167/0169/0398-0405 |
| `PermissionAppOpsTestActivity` | 权限/AppOps：画中画、写设置、通知监听服务、WLAN 权限黑名单、USB 权限授权（含真实效果探测） | ASR-0043/0044/0045/0046/0149 |
| `DeviceAdminTestActivity` | 设备管理：自有 admin 免交互激活/注销/强制激活（含本地 isAdminActive 端到端探测）、DeviceOwner 设置/删除/查询、ProfileOwner 设置/删除/查询 | ASR-0080/0081/0083/0085 |
| `DefaultAppComponentTestActivity` | 组件/默认应用：应用组件禁用/启用（组件级与整应用级）、默认短信/拨号/Assistant 设置与查询（含真实启动探测与框架资格校验拒绝路径） | ASR-0019/0087/0089/0094 |
| `VpnTestActivity` | VPN 管控：profile 配置（PPTP/L2TP/IPSec XAUTH 快捷添加）、列表、删除、启动（验证辅助）、断开、禁用/启用（always-on lockdown + 隐藏设置）、VPN 设置入口与 TRANSPORT_VPN 网络探测 | ASR-0214/0215/0216/0217/0218 |
| `DataStorageUserTestActivity` | 数据/存储/截屏/用户：数据探针与备份/恢复、缓存探针与清缓存、截屏、存储卷清单/卸载 USB/格式化 SD、用户创建/删除/清单（Launcher API 与本地探针） | ASR-0125/0127/0187/0197/0326/0385/0386 |
| `PowerDozeTestActivity` | 电源/Doze/语音助手：唤醒/休眠设备、电源状态查询、Doze 禁止/恢复与查询、Doze 白名单设置/清空/查询（含包名输入）、语音助手禁用/恢复/查询 | ASR-0415/0416/0373/0374/0420 |
| `EthernetTestActivity` | 有线网卡：DHCP/静态 IP 配置（含接口选择与静态参数输入）、配置查询、以太网启停与查询 | ASR-0424 |
| `BluetoothTestActivity` | 蓝牙管控：开关与整体状态、可发现/有限可发现禁止（含模拟可见性开关的探测回滚）、蓝牙页面禁用（含入口探测）、文件传输禁止（含分享解析探测）、SCO 通话禁止、连接黑白名单（模式/地址/名称名单）；真实框架执行验证 | ASR-0174/0175/0176/0178/0180/0181 |
| `WallpaperTestActivity` | 壁纸管控：设置桌面/锁屏/双目标壁纸（本地生成纯色测试图，支持多色与显式 base64）、壁纸状态查询、本地 WallpaperManager 交叉核对 | ASR-0183/0184 |
| `InfoQueryTestActivity` | 设备信息查询：文件属性、root 状态、VPN 服务状态、号码归属地、Cell ID、SIM 联系人、用户列表、WebView Provider 信息（Launcher API + 本地探针交叉核对） | ASR-0108/0110/0219/0262/0265/0290/0387/0442 |
| `EntrySettingsTestActivity` | 入口/设置锁定：应用权限页入口、指定应用权限页、通知管理界面与用户修改锁（通知/状态栏/锁屏）、通知白名单防关闭、应用管理页面与白/黑名单可见性策略、已开启辅助功能列表、无障碍 UI 入口、默认短信/拨号/Assistant/浏览器设置锁、USB 设置/USB 调试设置锁、APN 设置项、飞行模式修改锁、语言切换锁、禁用扬声器（含本地 resolve/组件状态/原始值/音频状态探测） | ASR-0048/0049/0058/0062-0068/0076/0077/0086/0088/0093/0100/0199/0203/0308/0322/0334/0335/0370 |
| `EmailControlTestActivity` | 邮件管控：策略模式开关（0=off/2=blacklist）、黑名单设置（UI 为空名单，具体名单走 IPC）、黑名单查询、全量对账、单包管控状态查询 | ASR-0423 |
| `AppPolicyTestActivity` | 应用安装/卸载策略：卸载白/黑名单（添加/移除/查询）、安装策略模式（0/1/2）与安装黑名单正则、保活开关、应用存活检测、MANAGE_EXTERNAL_STORAGE 授予/取消、桌面图标隐藏/显示、PackageInfo 查询 | ASR-0006/0007/0010/0015/0029/0040/0072/0073 |
| `DefaultIntentTestActivity` | 默认意图：默认桌面修改锁、默认视频播放器设置/查询/清除、指定文件类型默认应用设置/查询/清除 | ASR-0091/0095/0102 |
| `UsbStorageTestActivity` | USB/存储/SIM：USB 数据传输锁、USB 外接存储锁、数据漫游、SD 卡挂载锁（各含禁用/启用/查询） | ASR-0191/0196/0273/0325 |
| `VolumeDisplayTestActivity` | 音量/显示锁：用户音量设置锁、音量物理键锁、媒体/通知/闹钟音量修改锁、自动休眠开关、一直全屏 | ASR-0390/0391/0393/0395/0397/0412/0431 |
| `OtaTestActivity` | OTA 接口组：检查版本/下载 FOTA 包、SD 卡/本地 OTA 策略开关、A/B 引擎应用（回调驱动）、取消/暂停/恢复、回调广播消费日志、A/B 槽位查询/切换 | ASR-0446/0448/0449/0450/0451/0452 |
| `PermissionActivity` | 运行时权限请求（无界面内容，请求后自动结束） | - |

主页按钮文本：

- `Query connection status (GetZenMode probe)`
- `Notification control (ASR-0053/0054/0055/0057/0059)`
- `Camera & microphone control (ASR-0207/0369)`
- `Log buffer size & log level (ASR-0189/0190)`
- `Network access control (ASR-0135/0136)`
- `Stats query: traffic/battery/runtime (ASR-0139/0372/0021-0023)`
- `Process control: kill app / background (ASR-0027/0028)`
- `App run policy: blocked run / battery whitelist (ASR-0016/0017/0030/0099/0104/0131)`
- `System settings: captive portal / animations / font scale (ASR-0166/0204/0205/0314/0345/0407/0426)`
- `Lock screen policy: strong auth / expiration / digits (ASR-0359/0363/0365)`
- `Account & backup: backup / Google accounts (ASR-0124/0129/0132)`
- `Accessibility shortcut / screenshots / update policy (ASR-0078/0185/0186/0444)`
- `Accessibility service control: activate / whitelist / blacklist (ASR-0074/0075)`
- `WLAN control: lockdown / whitelist / security level (ASR-0150/0152/0153/0155/0158/0160/0168)`
- `WLAN config & access: add/remove / SSID+MAC lists / auto-connect (ASR-0144/0145/0147/0148/0151/0161)`
- `Device state: mobile data / location / passive location / airplane / nav bar / NFC (ASR-0275/0276/0277/0279/0310/0311/0313/0315/0316/0317/0319/0320/0321/0349)`
- `Hotspot & sharing: hotspot / USB / Bluetooth / forbid policies (ASR-0167/0169/0398-0405)`
- `Permission & AppOps: PiP / write settings / listener / wifi / USB (ASR-0043/0044/0045/0046/0149)`
- `Device admin: activate / deactivate / DO / PO (ASR-0080/0081/0083/0085)`
- `Component & default app: enable/disable / SMS / dialer / assistant (ASR-0019/0087/0089/0094)`
- `VPN control: disable / configure / delete / list / disconnect (ASR-0214/0215/0216/0217/0218)`
- `Data / storage / screenshot / user: backup / cache / SD / user (ASR-0125/0127/0187/0197/0326/0385/0386)`
- `Power / Doze / voice assistant: wake & sleep / Doze toggle & whitelist / assistant (ASR-0415/0416/0373/0374/0420)`
- `Wired NIC: ethernet config DHCP / static / enable / query (ASR-0424)`
- `Bluetooth: discoverable forbid / page / file transfer / SCO / access list (ASR-0174/0175/0176/0178/0180/0181)`
- `Wallpaper: set home / lock / both (ASR-0183/0184)`
- `Info queries: file / root / vpn / attribution / cell / SIM contacts / users / webview (ASR-0108/0110/0219/0262/0265/0290/0387/0442)`
- `Entry/settings locking: permission/notification/app-mgmt/accessibility/default-app/USB/APN/airplane/language/speaker (ASR-0048/0049/0058/0062-0068/0076/0077/0086/0088/0093/0100/0199/0203/0308/0322/0334/0335/0370)`
- `Email control: blacklist suspend + hide (ASR-0423)`
- `App install/uninstall policy: uninstall lists / install blacklist / keep-alive / alive / storage perm / icon / PackageInfo (ASR-0006/0007/0010/0015/0029/0040/0072/0073)`
- `Default intent: launcher lock / video player / file type (ASR-0091/0095/0102)`
- `USB / storage / SIM: USB data & external storage / SD mount / roaming (ASR-0191/0196/0273/0325)`
- `Volume / display locks: volume settings & keys / stream locks / auto sleep / fullscreen (ASR-0390/0391/0393/0395/0397/0412/0431)`
- `OTA interface group: check/download FOTA / local OTA policy / cancel / suspend / resume / callbacks / slots (ASR-0446/0448/0449/0450/0451/0452)`
- `Send own test notification`
- `Request POST_NOTIFICATIONS permission`

### 2.2 IPC 机制

- 广播接收器：`com.hmdm.testapp/.TestCommandReceiver`（exported），action `com.hmdm.testapp.CMD`；
- 参数通过 extra 传递：`--es event <事件名>`、`--es param '<JSON>'`；
- 接收器执行与 UI 按钮相同的 `TestActions.execute()`，结果以两种方式返回：
  1. `am broadcast` 输出：`Broadcast completed: result=0, data="<JSON>"`；
  2. logcat 单行日志：tag `HYX-TESTAPP-CMD`，格式 `<event> => <JSON>`。

进程内 `MdmApiClient` 为单例（`ApiHolder`），连接 Launcher ApiService 后进程生命周期内常驻，IPC 调用无需重新绑定。

## 3. 编译与安装

```bash
cd mdm_launcher/hmdm-android
export ANDROID_HOME=/home/alex/Android/Sdk
./gradlew :testapp:assembleDebug

adb install -r testapp/build/outputs/apk/debug/testapp-debug.apk
```

testapp 为普通应用，直接 `adb install -r` 即可（Launcher 安装白名单已含 `com\.hmdm\..*`，不会被自动卸载）。

## 4. IPC 用法

### 4.1 基本命令

```bash
adb shell am broadcast -n com.hmdm.testapp/.TestCommandReceiver \
  -a com.hmdm.testapp.CMD --es event <event> --es param '<json>'
```

示例：

```bash
adb shell am broadcast -n com.hmdm.testapp/.TestCommandReceiver \
  -a com.hmdm.testapp.CMD --es event SetNotificationsPolicyMode \
  --es param '{"mode":1}'
```

### 4.2 包装脚本（推荐）

`mdm_launcher/hmdm-android/send_test_command.sh` 自动构造 JSON 并打印结果（logcat 行）：

```bash
./send_test_command.sh <event> [key=value ...]

# 示例
./send_test_command.sh GetConnectionStatus
./send_test_command.sh ListEvents
./send_test_command.sh SetNotificationsPolicyMode mode=1
./send_test_command.sh SetNotificationsWhitelist 'packageNames=["com.hmdm.testapp"]'
./send_test_command.sh SetCameraDisabled disabled=true
./send_test_command.sh TryOpenCamera
./send_test_command.sh SetLogBufferSize size=1M buffer=main
./send_test_command.sh SetLogLevel tag=HYX_MDM_TEST level=V
```

**参数类型规则**：值按内容自动定型——JSON 数组/对象（`[...]`/`{...}`）、布尔（`true`/`false`）、纯数字（`123`）透传；其余（含空值）作为字符串。如需自定义 adb 路径：`ADB=/path/to/adb ./send_test_command.sh ...`。

### 4.3 事件目录

运行 `./send_test_command.sh ListEvents` 可获取完整的自描述事件目录。全部事件与对应 UI 按钮如下：

**连接探测**

| 事件 | 参数 | 说明 | 对应 UI |
|---|---|---|---|
| `GetConnectionStatus` | 无 | 探测 Launcher API 连接（bound 状态 + GetZenMode） | Query connection status |
| `ListEvents` | 无 | 返回全部事件目录（event/params/description） | - |

**通知管控（ASR-0053/0054/0055/0057/0059）**

| 事件 | 参数 | 说明 | 对应 UI |
|---|---|---|---|
| `SetNotificationsEnabledForPackage` | `packageName`（String，必填）、`enabled`（Boolean，必填） | 按包启用/禁用通知 | Enable/Disable target package notifications |
| `IsNotificationsEnabledForPackage` | `packageName`（必填） | 查询按包通知状态 | Query target package notification status |
| `SetNotificationsWhitelist` | `packageNames`（String 数组，整体替换） | 设置白名单 | Set whitelist |
| `GetNotificationsWhitelist` | 无 | 获取白名单 | Get whitelist |
| `SetNotificationsBlacklist` | `packageNames`（数组，整体替换） | 设置黑名单 | Set blacklist |
| `GetNotificationsBlacklist` | 无 | 获取黑名单 | Get blacklist |
| `SetNotificationsPolicyMode` | `mode`（0=off，1=whitelist，2=blacklist） | 设置策略模式并立即应用 | Set policy mode (apply now) |
| `GetNotificationsPolicyMode` | 无 | 获取策略模式 | Get policy mode |
| `ApplyNotificationsPolicy` | 无 | 将策略应用到所有包 | Apply policy to all apps |
| `SetLockscreenNotificationsDisabled` | `disabled`（Boolean） | 禁用/启用锁屏通知 | Disable/Enable lockscreen notifications |
| `IsLockscreenNotificationsDisabled` | 无 | 查询锁屏通知状态 | Query lockscreen notification status |
| `SetStatusBarNotificationsDisabled` | `disabled`（Boolean） | 禁用/启用状态栏通知（`cmd statusbar send-disable-flag notification-icons/none`，隐藏/恢复状态栏通知图标，写后 `dumpsys statusbar` mDisabled1 位核对；标志持久化，重启后重新武装；不影响通知发送与面板展示） | Disable/Enable status bar notification icons |
| `IsStatusBarNotificationsDisabled` | 无 | 查询状态栏通知状态 | Query status bar notification state |
| `SetBackKeyDisabled` | `disabled`（Boolean） | 禁用/启用 BACK 键（反射 `StatusBarManager.disable(DISABLE_BACK=0x400000)`，三键导航的 BACK 按键隐藏禁用、恢复显示，写后 `dumpsys statusbar` mDisabled1 位读回核验（短重试），核验通过才持久化 `back_key_policy`（意图 + 最近核验位），进程重启/开机重新武装；全面屏手势模式的返回手势不受控，属本 ROM 限制，命令附 navigation_mode 供判断） | Disable/Enable BACK key |
| `IsBackKeyDisabled` | 无 | 查询 BACK 键状态（持久化意图 + 最近写路径核验的实时位缓存，查询不执行 dumpsys） | Query BACK key state |
| `SendTestNotification` | 无 | 发送本 App 的测试通知（id=1001，channel=testapp_channel） | Send own test notification |
| `RequestPostNotificationsPermission` | 无 | UI 专属，返回引导：用 4.4 节命令 | Request POST_NOTIFICATIONS permission |

**摄像头/麦克风（ASR-0207/0369）**

| 事件 | 参数 | 说明 | 对应 UI |
|---|---|---|---|
| `SetCameraDisabled` | `disabled`（Boolean） | 禁用/启用摄像头 | Set Camera Disabled / Set Camera Enabled |
| `IsCameraDisabled` | 无 | 查询摄像头策略状态 | Query Camera Status |
| `TryOpenCamera` | 无 | 真实打开摄像头验证策略（最多等待 5 秒） | Try Open Camera |
| `SetMicrophoneDisabled` | `disabled`（Boolean） | 禁用/启用麦克风 | Set Microphone Disabled / Set Microphone Enabled |
| `IsMicrophoneDisabled` | 无 | 查询麦克风策略状态 | Query Microphone Status |
| `TryRecordAudio` | 无 | 真实录音 1 秒验证策略 | Try Record Audio |
| `RequestSensorPermissions` | 无 | UI 专属，返回引导：用 4.4 节命令 | Request CAMERA/RECORD_AUDIO permissions |

**日志管控（ASR-0189/0190）**

| 事件 | 参数 | 说明 | 对应 UI |
|---|---|---|---|
| `SetLogBufferSize` | `size`（String，必填，如 `16M`/`1M`/`512K`/字节数）、`buffer`（可选，main/system/crash/all，默认 all；传空等价于省略） | 设置日志缓冲区大小 | Set Log Buffer Size |
| `GetLogBufferSize` | 无 | 获取各缓冲区大小信息 | Get Log Buffer Sizes |
| `SetLogLevel` | `tag`（String，必填）、`level`（可选，V/D/I/W/E/F/S，空/R 恢复默认） | 设置 tag 日志级别 | Set Log Level |
| `GetLogLevel` | `tag`（必填） | 查询 tag 日志级别 | Get Log Level |
| `EmitTestLogs` | `tag`（必填） | 用指定 tag 发射 V/D 日志，供过滤验证 | Emit V/D logs with tag |

**网络黑白名单（ASR-0135/0136）**

| 事件 | 参数 | 说明 | 对应 UI |
|---|---|---|---|
| `SetDomainWhitelist` | `domains`（String 数组，整体替换） | 设置域名白名单 | Set domain whitelist |
| `GetDomainWhitelist` | 无 | 获取域名白名单 | Get domain whitelist |
| `SetDomainBlacklist` | `domains`（数组，整体替换） | 设置域名黑名单 | Set domain blacklist |
| `GetDomainBlacklist` | 无 | 获取域名黑名单 | Get domain blacklist |
| `SetDomainPolicyMode` | `mode`（0=off，1=whitelist，2=blacklist） | 设置域名策略模式并立即生效（触发防火墙 VPN 启停） | Set domain policy mode (apply now) |
| `GetDomainPolicyMode` | 无 | 获取域名策略模式 | Get domain policy mode |
| `SetIpWhitelist` | `ips`（数组，精确地址/CIDR/`*`，整体替换） | 设置 IP 白名单 | Set IP whitelist |
| `GetIpWhitelist` | 无 | 获取 IP 白名单 | Get IP whitelist |
| `SetIpBlacklist` | `ips`（数组，整体替换） | 设置 IP 黑名单 | Set IP blacklist |
| `GetIpBlacklist` | 无 | 获取 IP 黑名单 | Get IP blacklist |
| `SetIpPolicyMode` | `mode`（0/1/2） | 设置 IP 策略模式并立即生效 | Set IP policy mode (apply now) |
| `GetIpPolicyMode` | 无 | 获取 IP 策略模式 | Get IP policy mode |
| `GetNetworkFirewallStatus` | 无 | 策略 + VPN 状态 + 拦截计数（vpnRunning/vpnStatus/dnsBlocked/packetsBlocked/packetsForwarded） | Get firewall status |
| `TestDnsLookup` | `host`（必填） | 经防火墙 VPN 真实解析域名（拦截时 REFUSED 秒失败） | Test DNS lookup |
| `TestTcpConnect` | `host`（必填）、`port`（可选，默认 80） | 经防火墙 VPN TCP 连接（IP 拦截时超时） | Test TCP connect |
| `TestHttpGet` | `url`（必填） | 经防火墙 VPN HTTP GET，返回 status 或异常类型 | Test HTTP GET |

说明：网络黑白名单由 Launcher 本地 VPN 防火墙执行（见 `documents/technical-documentation/NetworkAccessControl-ASR-0135-0136-TechnicalDesign.md`），本 App 流量全部经过该防火墙；Launcher 自身被排除不受管控。

**统计查询（ASR-0139/0372/0021-0023）**

| 事件 | 参数 | 说明 | 对应 UI |
|---|---|---|---|
| `QueryAppTraffic` | `packageName`（可选）、`days`（可选，默认 1，上限 90）、`network`（可选，all/wifi/mobile，默认 all）、`limit`（可选，默认 30，上限 100） | 查询各应用收发流量（rxBytes/txBytes，按总量降序） | Query per-app traffic |
| `QueryAppBattery` | `packageName`（可选）、`limit`（可选，默认 20） | 查询各应用估计耗电（powerMah/percent，来自系统 batterystats 估计段） | Query per-app battery drain |
| `QueryAppRuntime` | `packageName`（可选）、`days`（可选，默认 7，上限 90）、`limit`（可选，默认 30） | 查询各应用前台运行时长（ms，按降序） | Query per-app foreground runtime |
| `QueryRunningApps` | 无 | 查询当前正在运行的进程列表（pid/importance/lru/packages） | Query running app processes |
| `QueryAppCrashInfo` | `packageName`（可选过滤）、`limit`（可选，默认 20） | 查询崩溃/ANR/watchdog 历史（来自 DropBox） | Query crash/ANR history |

说明：统计查询由 Launcher 侧实现（见 `documents/technical-documentation/StatsQuery-ASR-0139-0372-0021-0023-TechnicalDesign.md`）；流量与运行时统计窗口依赖系统 netstats/usage 轮询，重启后近期数据可能为 0；耗电数据为系统计算的估计值（与设置页一致）。

**进程管控（ASR-0027/0028）**

| 事件 | 参数 | 说明 | 对应 UI |
|---|---|---|---|
| `KillAppProcess` | `packageName`（String，必填） | 结束指定应用进程（含前台；经平台签名隐藏接口 forceStopPackage） | End app process |
| `KillBackgroundProcesses` | `except`（可选，String 数组，需保留的包名） | 批量清理后台/缓存/空进程（importance ≥ BACKGROUND），Launcher 自身与系统关键包永不清理 | Clean background processes |

说明：进程管控由 Launcher 侧实现（见 `documents/technical-documentation/ProcessControl-ASR-0027-0028-TechnicalDesign.md`）；命令内部先延时 500ms 再执行结束，保证调用链自身可先收到结果；若结束对象是 testapp 自身，广播结果 data 可能丢失（进程已被终止），以 `ps -A` 验证为准。

**应用运行/隐藏管控（ASR-0016/0017/0030/0099/0104/0131）**

| 事件 | 参数 | 说明 | 对应 UI |
|---|---|---|---|
| `SetBlockedRunningWhitelist` | `packageNames`（String 数组，整体替换） | 设置禁止运行白名单：名单内应用挂起（setPackagesSuspended）+ 隐藏（setApplicationHidden）；Launcher/testapp 受保护不可加入 | Add/Remove to blocked-running whitelist |
| `GetBlockedRunningWhitelist` | 无 | 获取禁止运行白名单（含实时 suspended/hidden 状态） | Get blocked-running whitelist |
| `IsPackageSuspended` | `packageName`（必填） | 查询单包挂起+隐藏状态 | Query suspended state |
| `SetIgnoreBatteryOptimizationWhitelist` | `packageNames`（数组，整体替换） | 设置忽略耗电优化白名单（ASR-0016 耗电应用免清理同引擎），DO 授予免交互，经 `cmd deviceidle whitelist` 落地 | Add/Remove to battery whitelist |
| `GetIgnoreBatteryOptimizationWhitelist` | 无 | 获取白名单（含实时 isIgnoringBatteryOptimizations 状态） | Get battery whitelist |
| `IsIgnoringBatteryOptimization` | `packageName`（必填） | 查询单包是否忽略耗电优化 | Query ignoring state |
| `SetApplicationHidden` | `packageName`（必填）、`hidden`（必填） | 隐藏/解除隐藏指定应用，返回真实 DPM 结果（包不存在时 false） | Disable/Enable 指定应用按钮 |
| `IsApplicationHidden` | `packageName`（必填） | 查询应用隐藏状态 | Query hidden state |

说明：应用运行/隐藏管控由 Launcher 侧实现（见 `documents/technical-documentation/AppRunControl-ASR-0016-0017-0030-0099-0104-0131-TechnicalDesign.md`）。ASR-0099 实测目标为本 ROM 系统浏览器能力应用 `org.chromium.webview_shell`（/product/app/Browser2；原预装浏览器 `com.ume.browser` 已因既有安装白名单策略被卸载）；ASR-0131 目标 `com.android.vending` 本 ROM 不存在（set 返回 false）。**注意**：解除隐藏非白名单、非系统应用会触发 Launcher 既有安装白名单策略的静默卸载（系统应用无影响）。

**全局/安全设置（ASR-0166/0204/0205/0314/0345/0407/0426）**

| 事件 | 参数 | 说明 | 对应 UI |
|---|---|---|---|
| `SetCaptivePortalDisabled` | `disabled`（Boolean，必填） | 禁止/允许 WIFI captive portal 弹窗（写 `captive_portal_mode` + `captive_portal_detection_enabled`，写后读回核对） | Disable/Enable Captive Portal |
| `IsCaptivePortalDisabled` | 无 | 查询 captive portal 状态（两键值 + disabled 派生） | Query Captive Portal State |
| `SetAlwaysFinishActivitiesDisabled` | `disabled`（必填） | 禁止/允许"不保留活动"开发者选项（true → `always_finish_activities=0`） | Forbid/Allow 'Don't keep activities' |
| `IsAlwaysFinishActivitiesDisabled` | 无 | 查询"不保留活动"状态（键值 + disabled 派生） | Query Always-Finish-Activities State |
| `SetMockLocationDisabled` | `disabled`（必填） | 禁止/允许模拟定位（true → `mock_location=0`） | Forbid/Allow Mock Locations |
| `IsMockLocationDisabled` | 无 | 查询模拟定位状态 | Query Mock Location State |
| `SetLocationMode` | `mode`（int，必填，0=关闭 1=仅设备 2=省电 3=高精度） | 设置定位模式（`location_mode`；越界返回 invalid mode） | Set Location Mode |
| `GetLocationMode` | 无 | 查询定位模式 | Get Location Mode |
| `SetFontScale` | `scale`（float/数字字符串，必填，0.5~2.0；预置档 0.85/1.0/1.15/1.30） | 设置字体大小（写 `Settings.System.font_scale`，框架 ATMS 观察者即时全系统生效；越界/非数值返回 invalid scale） | Set Font Scale |
| `GetFontScale` | 无 | 查询当前字体大小（scale + presets 预置档） | Get Font Scale |
| `SetGestureNavigationDisabled` | `disabled`（必填） | 禁用/启用全面屏手势导航（经 `cmd overlay` 切换导航栏运行时 overlay，SystemUI 回写 `navigation_mode`；轮询等待生效） | Disable/Enable Gesture Navigation |
| `IsGestureNavigationDisabled` | 无 | 查询手势导航状态（`navigation_mode==0` 即已禁用，附两个 overlay 状态） | Query Gesture Navigation State |
| `SetAnimationsDisabled` | `disabled`（必填） | 禁用/启用安卓小动画（true → 三键动画缩放全 0；false → 全 1.0） | Disable/Enable Animations |
| `IsAnimationsDisabled` | 无 | 查询动画状态（三键缩放值 + 全 0 判定） | Query Animation State |

说明：全局/安全设置由 Launcher 侧实现（见 `documents/technical-documentation/SystemSettingsControl-ASR-0166-0204-0205-0314-0345-0426-TechnicalDesign.md` 与 `documents/technical-documentation/FontSizeControl-ASR-0407-TechnicalDesign.md`）。ASR-0345 本 ROM 实测：`navigation_mode` 是导航栏 overlay 状态的镜像值（gestural↔2、threebutton↔0），命令经 `cmd overlay` 切换 `com.android.internal.systemui.navbar.{threebutton,gestural}` 生效；直接写该设置无效。SetLocationMode 的 `mode` 参数兼容数值与数字字符串（负数经脚本会以字符串传递）。**ASR-0407 字体大小（2026-08-11 真机核验）**：本 ROM 与 Settings 应用同键同路径——MtkSettings `FontSizeData.commit` 写 `Settings$System.putFloat("font_scale")`（预置档 `entryvalues_font_size=["0.85","1.0","1.15","1.30"]`），system_server `ActivityTaskManagerService$SettingObserver` 监听该键并经 `updateFontScaleIfNeeded`/`updatePersistentConfiguration` 全系统生效；生效证据：`dumpsys activity` 的 `mGlobalConfiguration` 首字段 fontScale 与设置值逐档一致。

**锁屏策略（ASR-0359/0363/0365）**

| 事件 | 参数 | 说明 | 对应 UI |
|---|---|---|---|
| `SetStrongAuthTimeout` | `timeoutMs`（long，必填，0 ~ 2592000000=30 天；0=屏幕熄灭后立即要求强认证） | 设置锁屏强认证超时（device owner `setRequiredStrongAuthTimeout`，API 26+，写后读回核对） | Set Strong Auth Timeout |
| `GetStrongAuthTimeout` | 无 | 查询锁屏强认证超时（ms） | Get Strong Auth Timeout |
| `SetPasswordExpirationTimeout` | `timeoutMs`（long，必填，0 ~ 2592000000=30 天；0=永不过期） | 设置密码更改宽限期（`setPasswordExpirationTimeout`，写后读回核对） | Set Password Expiration Timeout |
| `GetPasswordExpirationTimeout` | 无 | 查询密码更改宽限期与到期时刻（expiration，0=无） | Get Password Expiration Timeout |
| `SetConsecutiveDigitsLimit` | `limit`（int，必填，0~16；0=不限制） | 设置连续数字序列长度上限（近似实现：映射为 `setPasswordQuality`+`setPasswordMinimumLength`，见设计文档 2.4） | Set Consecutive Digits Limit |
| `GetConsecutiveDigitsLimit` | 无 | 查询有效上限（由当前 DPM 策略反向映射）与 quality/minLength/activePasswordSufficient | Get Consecutive Digits Limit |

说明：锁屏策略由 Launcher 侧实现（见 `documents/technical-documentation/LockScreenPolicy-ASR-0359-0363-0365-TechnicalDesign.md`）。三项均为 device owner 的公开 DPM 接口，无隐藏 API/无 shell。ASR-0363 为近似实现：AOSP 无连续数字内容校验 API，limit 映射为密码质量+最短长度组合（limit≤2→alphanumeric/6，3~4→alphanumeric/4，5~7→numeric/8，8~16→numeric/6，0→恢复默认）；查询返回当前策略区间上界（如 numeric/6 → 16），并以 quality/minLength 如实反映系统策略。策略真实生效可用 `adb shell locksettings set-password 1234` 验证（弱密码被拒）。**本 ROM 限制**：① ASR-0359 非 0 且 <3600000ms 的超时会被 ROM 提升为 1 小时（命令读回核对后如实报 success=false）；② ASR-0365 需 `device_admin.xml` 声明 `<expire-password/>`（本批次已加），修改后须重启 framework/整机生效；③ ASR-0363 密码策略激活期间 `locksettings clear` 会被拒绝，恢复流程须先 `SetConsecutiveDigitsLimit limit=0` 再清密码。

**账户/备份/同步（ASR-0124/0129/0132/0128/0130）**

| 事件 | 参数 | 说明 | 对应 UI |
|---|---|---|---|
| `SetBackupDisabled` | `disabled`（Boolean，必填） | 禁用/启用系统备份（device owner `setBackupServiceEnabled`，API 31+；写后 `isBackupServiceEnabled` 读回核对，附报 backup_enabled 键） | Set Backup Disabled / Set Backup Enabled |
| `IsBackupDisabled` | 无 | 查询系统备份限制状态（disabled/restriction/backupServiceEnabled） | Query Backup State |
| `SetGoogleAccountsDisabled` | `disabled`（必填） | 禁用/启用谷歌（任意）账户：加 `DISALLOW_MODIFY_ACCOUNTS` 限制 + 立即删除全部存量账户（removedAccounts 上报删除数） | Set Google Accounts Disabled / Set Google Accounts Enabled |
| `IsGoogleAccountsDisabled` | 无 | 查询账户限制状态与当前账户列表（accounts=type:name 列表） | Query Google Accounts State |
| `SetBackupRestoreDisabled` | `disabled`（必填） | 禁用/启用 Google 备份和恢复（写 `backup_enabled` + `backup_auto_restore`，先试 `dpm.setSecureSetting`，AOSP 白名单外回退平台签名直写，channel 如实上报） | Set Backup & Restore Disabled / Set Backup & Restore Enabled |
| `IsBackupRestoreDisabled` | 无 | 查询备份恢复键状态（backup_enabled/backup_auto_restore，disabled 派生） | Query Backup & Restore State |
| `SetAutoSync` | `enabled`（Boolean，必填） | 启用/禁用全局自动同步主开关（公开 `ContentResolver.setMasterSyncAutomatically`，写后读回核对；主开关状态由 SyncManager 持久化，非 Settings 键） | Set Auto Sync Enabled / Set Auto Sync Disabled |
| `IsAutoSync` | 无 | 查询全局自动同步主开关状态（masterSyncAutomatically） | Query Auto Sync State |
| `SetGoogleAccountAutoSync` | `enabled`（必填） | 启用/禁用谷歌账户自动同步（对全部 `com.google` 账户 × 全部同步 authority 逐对 `setSyncAutomatically`，写后读回核对；无 Google 账户时 updatedPairs=0 + note 如实上报） | Set Google Account Sync Enabled / Set Google Account Sync Disabled |
| `IsGoogleAccountAutoSync` | 无 | 查询谷歌账户自动同步状态（accounts 逐账户逐 authority 的 syncAutomatically；无账户→enabled=false + note） | Query Google Account Sync State |

说明：账户/备份/同步由 Launcher 侧实现（见 `documents/technical-documentation/AccountBackupControl-ASR-0124-0129-0132-TechnicalDesign.md` 与 `AccountSyncControl-ASR-0128-0130-TechnicalDesign.md`）。ASR-0124 经 device owner 公开接口 `setBackupServiceEnabled`/`isBackupServiceEnabled`（API 31+；**DISALLOW_BACKUP 已被 AOSP 13 移出有效用户限制集合，真机实测静默丢弃，故未采用**）；ASR-0129 为 device owner 公开 DPM 限制接口（add/clearUserRestriction），无隐藏 API/无 shell，无需 `device_admin.xml` 新声明；ASR-0132 的 `dpm.setSecureSetting` 在 AOSP 13 仅白名单少数键（不含备份键），命令自动回退平台签名 uid=1000 直写 Settings.Secure（channel=settings），写后读回核对。**ASR-0128/0130 为公开 `ContentResolver` 同步接口（无 device owner/无平台签名要求）**：0128=全局主开关（`setMasterSyncAutomatically`，非 Settings 键，系统对照 `dumpsys content` "Auto sync" 行）；0130=逐账户 syncAutomatically（`setSyncAutomatically`，manifest 新增 `WRITE_SYNC_SETTINGS` normal 权限——服务端强制校验，本批次已声明并 granted=true 核验）。**本 ROM 现状**：无 GMS，设备无 Google 账户、无同步适配器（账户列表恒为空、updatedPairs=0、查询 enabled=false + note 为正常预期；带账户的真实逐项切换需 GMS 设备验收）。

**无障碍快捷方式/截屏/系统升级策略（ASR-0078/0185/0186/0444）**

| 事件 | 参数 | 说明 | 对应 UI |
|---|---|---|---|
| `SetAccessibilityShortcutDisabled` | `disabled`（Boolean，必填） | 禁用/启用无障碍快捷方式：禁用清空 `accessibility_shortcut`/`accessibility_shortcut_target_service(s)` 三键并备份原值，启用恢复备份（无备份键保持不动）；先试 `dpm.setSecureSetting`，AOSP 白名单外回退平台签名直写，channel/channels 如实上报，写后读回核对 | Set Accessibility Shortcut Disabled / Enabled |
| `IsAccessibilityShortcutDisabled` | 无 | 查询无障碍快捷方式状态（三键值 + disabled 派生：全空=已禁用） | Query Accessibility Shortcut State |
| `SetScreenshotsDisabled` | `disabled`（必填） | 禁用/启用截屏（device owner `setScreenCaptureDisabled`，FLAG_SECURE 阻断系统截屏、物理键截屏与 adb screencap；ASR-0185/0186 共用，返回 Boolean） | Set Screenshots Disabled / Enabled |
| `IsScreenshotsDisabled` | 无 | 查询截屏策略状态（返回 Boolean） | Query Screenshots State |
| `SetOnlineFotaDisabled` | `disabled`（必填） | 禁用/启用在线 FOTA：禁用= `setSystemUpdatePolicy(createPostponeInstallPolicy())`（POSTPONE），启用=清除策略回自动；写后读回核对 | Set Online FOTA Disabled / Enabled |
| `IsOnlineFotaDisabled` | 无 | 查询系统更新策略（policySet/policyType/policyTypeName/维护窗口/冻结期；disabled=策略存在且非自动安装） | Query Online FOTA State |

说明：本组由 Launcher 侧实现（见 `documents/technical-documentation/AccessibilityScreenshotUpdatePolicy-ASR-0078-0185-0186-0444-TechnicalDesign.md`）。**本 ROM 差异**：① ASR-0078 的 `dpm.setSecureSetting` 在 AOSP 13 仅白名单少数键（不含 accessibility_shortcut），命令自动回退平台签名直写（channel=settings）；② ASR-0444 本 ROM 为 fork 版 SystemUpdatePolicy：`TYPE_POSTPONE=3`（AOSP 标准为 2，另有 `TYPE_INSTALL_AUTOMATIC=1`/`TYPE_INSTALL_WINDOWED=2`/`TYPE_PAUSE=4`），命令返回 policyType=3、policyTypeName=postpone 为正常值，引擎运行时反射解析 ROM 常量，与 `dumpsys device_policy` 的 System Update Policy 段对照一致为准；③ 截屏禁用后 `adb shell screencap` 输出 0 字节（物理键截屏同路径被阻断，adb 无法模拟电源+音量下和弦，以 dumpsys `disableScreenCapture=true` + screencap 0 字节为证据）。

**OTA 接口组（ASR-0446/0448/0449/0450/0451/0452）**

| 事件 | 参数 | 说明 | 对应 UI |
|---|---|---|---|
| `FotaCheckUpdate` | `config`（JSON 字符串，必填；格式同 FotaStart：name/url/ab_install_type/ab_config.property_files） | 检查可用系统版本：下载 OTA 包元数据（payload_metadata.bin/payload_properties.txt/compatibility.zip）并经反射 `UpdateEngine.verifyPayloadMetadata` 签名校验 + `RecoverySystem.verifyPackageCompatibility` 兼容性校验，返回版本信息（versionName/payloadProperties）与校验结果（虚假包如实 verifyPayloadMetadata=false） | Check FOTA Update（config 走 IPC） |
| `FotaDownloadUpdate` | `config`（必填） | 下载完整 FOTA 包至 `filesDir/ota/fota_package.zip` 并校验（zip 内提取校验文件，同上） | Download FOTA Package（config 走 IPC） |
| `SetLocalOtaEnabled` | `enabled`（Boolean，必填） | 设置 SD 卡/本地 OTA 策略开关：关闭时 FotaStart（content:///file://）拒绝（-104 local ota disabled by policy）、FotaApply（file://）拒绝；SharedPreferences 持久化，写后读回核对 | Set Local OTA Enabled / Disabled |
| `IsLocalOtaEnabled` | 无 | 查询本地 OTA 策略状态 | Query Local OTA Policy |
| `FotaApply` | `config`（必填） | 经 ExportUpdaterService（A/B UpdateEngine）开始应用 OTA Payload；四个引擎回调（updater_state/engine_status/engine_complete/progress）经广播 `com.hmdm.launcher.ACTION_OTA_STATUS` 持续上报（ASR-0451 回调通道）；file:// 配置受本地 OTA 策略门禁 | Apply FOTA (A/B engine)（config 走 IPC） |
| `FotaCancel` | 无 | 取消正在应用的 OTA Payload（UpdateEngine.cancel），随操作结果返回当前升级状态（state/stateText） | Cancel FOTA |
| `FotaSuspend` | 无 | 暂停 OTA Payload 应用过程（状态机 RUNNING→PAUSED，仅底层引擎支持时生效），返回当前状态 | Suspend FOTA |
| `FotaResume` | 无 | 恢复暂停的 OTA Payload 应用（PAUSED→RUNNING），返回当前状态 | Resume FOTA |
| `GetSlotInfo` | 无 | 查询 A/B 槽位：currentSlot/currentSlotSuffix/nextBootSlot、各槽位 bootable/markedSuccessful（反射 BootControl，ro.boot.slot 属性附报；无 HAL 如实 supported=false） | Get Slot Info |
| `SetSwitchSlotOnReboot` | `config`（必填） | 下次启动切换 A/B 槽位（ExportUpdaterService setSwitchSlotOnReboot，SWITCH_SLOT_ON_REBOOT 属性重放 payload；真实切换需已应用 OTA） | Set Switch Slot On Reboot（config 走 IPC） |
| `GetOtaCallbackLog` | 无 | 返回 testapp 已接收的 OTA 回调广播日志（count + events：callbackType/value/time，updater_state 附 valueText 映射） | Get OTA Callback Log |
| `ClearOtaCallbackLog` | 无 | 清空 OTA 回调广播日志（返回 cleared 条数） | Clear OTA Callback Log |

说明：本组由 Launcher 侧实现（见 `documents/technical-documentation/OtaUpdateControl-ASR-0446-0448-0449-0450-0451-0452-TechnicalDesign.md`）。要点：① ASR-0446 的校验为"如实上报"口径——虚假/伪造 OTA 包 verifyPayloadMetadata=false 为预期（未通过签名校验的包不可用），真实供应商签名包需真机验证 true；② ASR-0451 回调消费 = 业务应用注册 `com.hmdm.launcher.ACTION_OTA_STATUS` 广播接收器（ExportUpdaterService 四回调统一中继，extras `callbackType`+`value`；FotaStart 事件为 code/msg 格式可区分）；③ FotaCancel/FotaSuspend/FotaResume/FotaApply/SetSwitchSlotOnReboot 依赖 ExportUpdaterService（首次调用自动绑定，绑定失败如实 success=false/dispatched=false）；④ 槽位切换不会在无已应用 OTA 时真实换槽，SetSwitchSlotOnReboot 用例验证命令契约与 dispatch；⑤ 假 OTA 包测试经 `adb reverse tcp:8080 tcp:8080` + 宿主机 HTTP 服务提供。

**无障碍服务控制（ASR-0074/0075）**

| 事件 | 参数 | 说明 | 对应 UI |
|---|---|---|---|
| `SetAccessibilityServiceEnabled` | `component`（`pkg/class`，必填）、`enabled`（Boolean，必填） | 免交互激活/注销指定无障碍服务：激活=列表+目标并打开总开关，注销=移除目标（注销最后一个服务时总开关置 0）；先反射 `AccessibilityManager.setEnabledAccessibilityServiceList`（**本 ROM 无该方法，恒回退**平台签名直写 Settings.Secure，channel 如实上报），写后读回核对；被黑白名单策略禁止的激活被确定性拒绝 | Enable/Disable Own Accessibility Service（自有服务） |
| `IsAccessibilityServiceEnabled` | `component`（必填） | 查询指定服务是否已启用 | Query Own Accessibility Service |
| `GetAccessibilityServiceState` | 无 | 完整状态：总开关、已启用列表、已安装列表、策略模式与名单 | Query Full Accessibility State |
| `SetAccessibilityServicePolicyMode` | `mode`（int，必填，0=off，1=whitelist，2=blacklist） | 设置可使用服务策略模式并立即执行（白名单=仅名单内可启用，黑名单=名单内禁止启用） | Set Policy Mode Off/Whitelist/Blacklist |
| `GetAccessibilityServicePolicyMode` | 无 | 查询策略模式与名单 | Query Policy Mode |
| `SetAccessibilityServiceWhitelist` | `components`（数组，整体替换） | 替换白名单（白名单模式激活时立即执行） | Set/Clear Whitelist |
| `GetAccessibilityServiceWhitelist` | 无 | 查询白名单 | Query Whitelist |
| `SetAccessibilityServiceBlacklist` | `components`（数组，整体替换） | 替换黑名单（黑名单模式激活时立即执行） | Set/Clear Blacklist |
| `GetAccessibilityServiceBlacklist` | 无 | 查询黑名单 | Query Blacklist |
| `ApplyAccessibilityServicePolicy` | 无 | 立即执行当前策略（对已启用列表做一次纠正） | Apply Policy Now |

说明：本组由 Launcher 侧实现（见 `documents/technical-documentation/AccessibilityServiceControl-ASR-0074-0075-TechnicalDesign.md`）。**本 ROM 差异**：① `AccessibilityManager.setEnabledAccessibilityServiceList`（Sheet1 P1 规划路径）在本 ROM 不存在（2/3 参数均经 dex 反编译 + 运行期反射核验为 NoSuchMethodException），命令恒回退平台签名 uid=1000 直写 `Settings.Secure.enabled_accessibility_services`/`accessibility_enabled`（channel=settings 为预期值，非缺陷），AccessibilityManagerService 的设置观察者自动完成服务绑定，免交互激活生效（`dumpsys accessibility` Bound/Enabled services 核对）；② 策略持续执行：Launcher 注册 Settings.Secure 观察者（300ms 防抖），用户在设置页切换服务被约 1 秒内回滚（launcher 日志 `settings change observed`/`applyPolicy removed` 可核验），进程重启/开机经 syncPolicy 重新武装；③ 组件名格式：设置页写全格式（`pkg/pkg.Class`）、引擎写短格式（`pkg/.Class`），引擎统一规范化，注入验证两种格式均可命中；④ **MTK DuraSpeed 部署注意**：对 testapp 执行 `am force-stop` 会触发 DuraSpeed 将其加入 suppress list（`dumpsys duraspeed suppress_list`），此后 AMS 丢弃其全部 manifest receiver 广播（IPC 失效）；重启设备即恢复，测试期间重启 testapp 进程请用 root `kill <pid>` 而非 force-stop。

**WLAN 强管控（ASR-0150/0152/0153/0155/0158/0160/0168）**

| 事件 | 参数 | 说明 | 对应 UI |
|---|---|---|---|
| `SetApConfigLockdown` | `disabled`（Boolean，必填） | 禁止/允许用户修改 AP 配置（`DISALLOW_CONFIG_WIFI`，ASR-0141/0155 同引擎；生效后 Settings WLAN 页显示 "Blocked by your IT admin"） | Set AP Config Lockdown / Set AP Config Allowed |
| `IsApConfigLockdown` | 无 | 查询 AP 配置锁定状态（restriction=no_config_wifi） | Query AP Config Lockdown State |
| `SetWifiSsidWhitelist` | `enabled`（Boolean，必填）、`ssids`（String 数组，enabled=true 时必填，UTF-8 ≤32 字节且无控制字符） | 安装/清除企业 WiFi SSID 白名单（device owner `setWifiSsidPolicy` ALLOWLIST；生效时断开不符合条件的网络，disconnectApplied 上报） | Set SSID Whitelist (sample) / Clear SSID Whitelist |
| `GetWifiSsidWhitelist` | 无 | 查询当前 SSID 策略（enabled/policyType/ssids/connectedSsid） | Query SSID Whitelist |
| `SetManualAddWifiDisabled` | `disabled`（必填） | 禁止/允许手动添加网络（`DISALLOW_ADD_WIFI_CONFIG`；生效后 Settings WLAN 页非当前网络行 "Not allowed by your organization" 且不可点击） | Set Manual Add Wifi Disabled / Enabled |
| `IsManualAddWifiDisabled` | 无 | 查询手动添加网络限制状态 | Query Manual Add State |
| `SetUserConfigWifiDisabled` | `disabled`（必填） | 禁止/允许编辑 WLAN 设置（`DISALLOW_CONFIG_WIFI`，ASR-0141 同引擎，ASR-0155 复用） | Set Edit WLAN Disabled / Enabled |
| `IsUserConfigWifiDisabled` | 无 | 查询编辑 WLAN 限制状态 | Query Edit WLAN State |
| `SetMinimumWifiSecurityLevel` | `level`（int，必填，0=OPEN 1=PERSONAL 2=ENTERPRISE_EAP 3=ENTERPRISE_192） | 设置 WLAN 最低安全级别（device owner `setMinimumRequiredWifiSecurityLevel`；生效时框架断开低于级别的已连接网络并拦截连接） | Set Security Level OPEN/PERSONAL/ENTERPRISE_EAP/ENTERPRISE_192 |
| `GetMinimumWifiSecurityLevel` | 无 | 查询最低安全级别（level + levelName） | Query Security Level |
| `SetWifiDirectDisabled` | `disabled`（必填） | 禁止/允许 WLAN 直连（`DISALLOW_WIFI_DIRECT`） | Set WLAN Direct Disabled / Enabled |
| `IsWifiDirectDisabled` | 无 | 查询 WLAN 直连限制状态 | Query WLAN Direct State |
| `SetUserConfigTetheringDisabled` | `disabled`（必填） | 禁止/允许修改热点配置（`DISALLOW_CONFIG_TETHERING`） | Set Hotspot Config Disabled / Enabled |
| `IsUserConfigTetheringDisabled` | 无 | 查询热点配置限制状态 | Query Hotspot Config State |
| `TryAddWifiNetwork` | 无 | 本应用（平台签名、非 device owner，与 Settings 权限级相同）调用 `WifiManager.addNetwork` 添加临时网络并立即删除——用于验证 AP 配置锁定类机制的真实执行 | Try Add Wifi Network |
| `TryConnectOpenWifi` | `ssid`（必填，已保存开放网络名）、`networkId`（可选，跳过已保存网络查找） | 对已保存开放网络执行 `enableNetwork` 并观察是否真实连接（8 秒窗口），验证 SSID 白名单/最低安全级别拦截；连接成功后立即断开，不清除已保存网络 | Try Connect Open Wifi (Syrius_Guest) |

说明：WLAN 强管控由 Launcher 侧实现（见 `documents/technical-documentation/WlanControl-ASR-0150-0152-0153-0155-0158-0160-0168-TechnicalDesign.md`）。**本 ROM SDK 为 fork 版**：`WifiSsidPolicy` 用 `WifiSsid` 对象（`WIFI_SSID_POLICY_TYPE_ALLOWLIST=0`）、DPM 的 set/get 方法无 ComponentName 参数、安全级别常量 0~3（OPEN/PERSONAL/ENTERPRISE_EAP/ENTERPRISE_192，开发者预览命名），命令返回的 policyType=0 即白名单、levelName=enterprise_eap/enterprise_192 均为本 ROM 正常值。**本 ROM 框架执行链**：SSID 白名单与最低安全级别在策略变更时断开不合格网络（logcat `WifiService: disconnect admin restricted network`），并经 `isAdminRestrictedNetwork` 拦截 enableNetwork/connect/自动加入（logcat `enableNetwork not allowed for admin restricted network Id=%`）——TryConnectOpenWifi 在策略生效时返回 `enableNetworkResult:false`。**ASR-0150 决策**：标准 AOSP 的 `wifi_device_owner_configs_lockdown` 键在本 ROM 仅保护 DO 创建的配置（反编译 + addNetwork 实测），经确认改用 `DISALLOW_CONFIG_WIFI`（同 ASR-0141/0155 引擎）。TryAddWifiNetwork 的 `addNetworkSucceeded:true` 即证明该键不拦截（供排查对照）。

**WLAN 配置/接入管控（ASR-0144/0145/0147/0148/0151/0161）**

| 事件 | 参数 | 说明 | 对应 UI |
|---|---|---|---|
| `ConfigureWifi` | `ssid`（必填）、`securityType`（int，必填 0=open 1=wpa2 2=wep）、`password`（wpa2/wep 必填）、`connect`（可选） | 配置 WLAN 网络（`WifiManager.addNetwork`；同 SSID 同安全类型已存在则先替换，replacedNetId 上报；读回核对；ASR-0144） | Configure WPA2 sample / Configure Open sample |
| `ConfigureEnterpriseWifi` | `ssid`（必填）、`eapMethod`（int，必填 0=PEAP 1=TLS 2=TTLS 3=PWD 4=SIM 5=AKA 6=AKA_PRIME）、`phase2`（可选默认 0：0=none 1=PAP 2=MSCHAP 3=MSCHAPv2 4=GTC）、`identity`、`anonymousIdentity`、`password`（SIM/AKA/AKA_PRIME 免）、`connect`（可选） | 配置企业 WiFi（`WifiManager.addNetwork` + `WifiEnterpriseConfig`；读回含 eapMethod/phase2/identity/passwordMasked；ASR-0151） | Configure Enterprise PEAP sample |
| `RemoveWifiNetwork` | `networkId`（可选，优先）或 `ssid`（可选） | 删除已保存热点（`removeNetwork`；按 SSID 删除该 SSID 全部条目——本 ROM 单 SSID 双条目；读回核对；ASR-0145） | Remove sample / (IPC) |
| `GetSavedWifiNetworks` | 无 | 已保存网络列表（networkId/ssid/securityType/securityTypeName/enabled；本 ROM 每个新网络含伴生 owe 条目属预期） | List Saved Networks |
| `SetSsidAccessPolicy` | `mode`（int，必填 0=off 1=whitelist 2=blacklist） | 设置 SSID 黑白名单模式并立即评估（违规即断开，enforcement 上报；ASR-0147） | SSID Policy Off/Whitelist/Blacklist |
| `GetSsidAccessPolicy` | 无 | 查询 SSID 接入策略（mode/whitelist/blacklist/当前连接） | Query SSID Access Policy |
| `SetSsidAccessWhitelist` | `ssids`（String 数组，整体替换） | 替换 SSID 白名单（立即生效；ASR-0147） | SSID Whitelist sample |
| `SetSsidAccessBlacklist` | `ssids`（String 数组，整体替换） | 替换 SSID 黑名单（立即生效；ASR-0147） | SSID Blacklist sample |
| `SetMacAccessPolicy` | `mode`（int，必填 0/1/2） | 设置 AP MAC（BSSID）黑白名单模式并立即评估（ASR-0148） | MAC Policy Off/Whitelist/Blacklist |
| `GetMacAccessPolicy` | 无 | 查询 MAC 接入策略（mode/whitelist/blacklist/currentBssid） | Query MAC Access Policy |
| `SetMacAccessWhitelist` | `macs`（String 数组 `AA:BB:CC:DD:EE:FF`，整体替换） | 替换 MAC 白名单（立即生效；ASR-0148） | MAC Whitelist = current BSSID |
| `SetMacAccessBlacklist` | `macs`（String 数组，整体替换） | 替换 MAC 黑名单（立即生效；ASR-0148） | MAC Blacklist = current BSSID |
| `SetWifiAutoConnectPolicy` | `enabled`（Boolean，必填 true=禁止自动连接） | 禁止/允许自动连接：禁止=全量 `disableNetwork` 已保存网络 + 断开当前连接（新保存网络由回调自动禁用），允许=全量 `enableNetwork(netId,false)`（ASR-0161） | Forbid/Allow Auto-Connect |
| `GetWifiAutoConnectPolicy` | 无 | 查询自动连接策略（autoConnectForbidden + 各网络 enabled 状态） | Query Auto-Connect Policy |
| `ApplyWifiAccessPolicy` | 无 | 立即对当前连接执行一次策略评估（violation/disconnected 上报） | Apply Access Policy Now |

说明：WLAN 配置/接入管控由 Launcher 侧实现（见 `documents/technical-documentation/WlanConfigAccessControl-ASR-0144-0145-0147-0148-0151-0161-TechnicalDesign.md`）。**权限通道**：Launcher 平台签名（uid=1000）+ manifest 既有 `NETWORK_SETTINGS`（signature|privileged）特权，`addNetwork/removeNetwork/enableNetwork/disableNetwork/getConfiguredNetworks` 全部可用，无需定位/NEARBY_WIFI_DEVICES。**本 ROM 特性**：① 单 SSID 双条目——每个新网络自动生成伴生 OWE 条目（同一 netId 出现两次，`GetSavedWifiNetworks` 如实列出；`RemoveWifiNetwork` 按 SSID 一次删净；`SetWifiAutoConnectPolicy` 的 netId 列表已去重）；② SSID/MAC 黑白名单为"网络回调 + 断连策略"——`NETWORK_STATE_CHANGED` 回调检测违规连接并断开，框架自动重连被反复断开（logcat `WifiAccessPolicyManager enforce(NETWORK_STATE_CHANGED): violation=ssid/bssid not in whitelist|in blacklist ... disconnected=true`），策略持久化 SharedPreferences `wifi_access_policy`，进程重启/开机经 syncPolicy 重新武装；③ 企业网络读回密码恒为掩码（passwordMasked=masked，预期）；④ TryConnectOpenWifi 在本机连接建立需 8~25 秒，8 秒窗口内 connected=false 时以 `dumpsys wifi` 为准。**与 ASR-0152 的关系**：DPM SSID 白名单（框架拦截）与 Launcher 侧名单策略（断连纠正）相互独立、可叠加。

**权限/AppOps（ASR-0043/0044/0045/0046/0149）**

| 事件 | 参数 | 说明 | 对应 UI |
|---|---|---|---|
| `SetPictureInPictureDisabled` | `packageName`（必填）、`disabled`（Boolean，必填） | 禁用/启用指定应用画中画（`OP_PICTURE_IN_PICTURE` AppOps，写后读回核对；ASR-0043） | Disable/Enable Own Picture-in-Picture |
| `IsPictureInPictureDisabled` | `packageName`（必填） | 查询指定应用画中画禁用状态（ASR-0043） | Query Own Picture-in-Picture |
| `SetWriteSettingsDisabled` | `packageName`（必填）、`disabled`（必填） | 禁用/启用指定应用写设置（`OP_WRITE_SETTINGS` AppOps；ASR-0044） | Disable/Enable Own Write Settings |
| `IsWriteSettingsDisabled` | `packageName`（必填） | 查询指定应用写设置禁用状态（ASR-0044） | Query Own Write Settings |
| `SetNotificationListenerAccessGranted` | `component`（必填，pkg/class）、`granted`（Boolean，必填） | 授予/取消绑定通知监听服务的权限（反射 `NotificationManager.setNotificationListenerAccessGranted`，回退直写 `enabled_notification_listeners`；ASR-0045） | Grant/Revoke Own Notification Listener |
| `IsNotificationListenerAccessGranted` | `component`（必填） | 查询通知监听服务授权状态（ASR-0045） | Query Own Notification Listener |
| `SetWifiPermissionBlacklist` | `packageNames`（String 数组，整体替换） | 设置 WLAN 权限黑名单（`OP_CHANGE_WIFI_STATE`；黑名单模式激活时立即整体执行；ASR-0149） | Set/Clear Wifi Permission Blacklist |
| `GetWifiPermissionBlacklist` | 无 | 获取 WLAN 权限黑名单（ASR-0149） | Query Wifi Permission Blacklist |
| `SetWifiPermissionPolicyMode` | `mode`（必填，0=off，2=blacklist） | 设置 WLAN 权限策略模式并立即应用（ASR-0149） | Set Wifi Permission Policy Off/Blacklist |
| `GetWifiPermissionPolicyMode` | 无 | 获取 WLAN 权限策略模式（ASR-0149） | Query Wifi Permission Policy Mode |
| `ApplyWifiPermissionPolicy` | 无 | 立即将当前策略整体套用到全部已安装应用，返回实际变更包列表（ASR-0149） | Apply Wifi Permission Policy Now |
| `GrantUsbPermission` | `packageName`（必填）、`deviceName`（可选：设备名 / `vid:pid` / `first`） | 授予指定包已连接 USB host 设备的权限（反射 `UsbManager.grantPermission`；ASR-0046） | Grant USB Permission to Own App (first device) |
| `GetUsbDeviceList` | 无 | 枚举当前已连接 USB host 设备（名称/vid/pid；ASR-0046 验证辅助） | List Attached USB Devices |
| `TryWriteSettings` | 无 | 本应用真实写 `Settings.System.screen_brightness`（当前值原值回写，无状态变更）：op 被忽略时 SecurityException、op 允许时成功（ASR-0044 效果探测） | Probe: Try Writing a Settings.System Value |
| `TryChangeWifiState` | 无 | 本应用真实调用 `WifiManager.setWifiEnabled(当前状态)`（无操作调用）：op 被忽略时返回 false/异常、允许时返回 true（ASR-0149 效果探测） | Probe: Try Change Wifi State (No-op) |
| `TryEnterPip` | 无 | 尝试进入画中画（需 Activity 上下文：IPC 返回 needsUi 提示，请从测试页按钮点按；ASR-0043 效果探测） | Probe: Try Enter Picture-in-Picture |
| `CheckUsbPermission` | `deviceName`（可选） | 本应用检查已连接设备上的 USB 权限（`UsbManager.hasPermission`；ASR-0046 端到端验证） | Check Own USB Permission |

**设备管理（ASR-0080/0081/0083/0085）**

| 事件 | 参数 | 说明 | 对应 UI |
|---|---|---|---|
| `SetDeviceAdminActive` | `component`（必填，pkg/class）、`active`（Boolean，必填） | 免交互激活/注销指定设备管理器组件（反射 `setActiveAdmin` / `removeActiveAdmin`；注销 DO/PO 的 admin 被引擎预检拒绝；ASR-0080） | Activate/Deactivate Own Admin (non-interactive) |
| `IsDeviceAdminActive` | `component`（必填） | 查询指定组件是否活动 admin，附报其是否为当前 DO/PO admin（ASR-0080） | Query Own Admin (Launcher API) |
| `ForceSetDeviceAdminActive` | `component`（必填）、`active`（必填） | 强制激活/注销（`refreshing=true` 覆盖刷新，已激活时重复激活不报错；ASR-0081） | Force Activate Own Admin |
| `SetDeviceOwner` | `packageName`（必填）、`component`（可选，缺省 `pkg/.AdminReceiver`）、`ownerName`（可选） | 设置 DeviceOwner：binder 通道（setup 前）失败后回退写 `/data/system/device_owner_2.xml`（返回 restartRequired=true，需 `adb shell stop/start` 生效；ASR-0083） | Set Device Owner (com.hmdm.launcher) |
| `DeleteDeviceOwner` | `packageName`（可选，缺省当前 DO） | 删除 DeviceOwner：`clearDeviceOwnerApp` 清内存 + 直写 `device_owner_2.xml` 移除 owner 元素（重启不复活；ASR-0083） | Delete Device Owner |
| `IsDeviceOwner` | `packageName`（可选） | 查询 DeviceOwner 状态（内存态 + 磁盘 `fileHasOwner`，含 DO 组件/用户；ASR-0083） | Query Device Owner |
| `SetProfileOwner` | `component`（必填）、`ownerName`（可选）、`userId`（可选，缺省 0） | 设置 ProfileOwner（setActiveProfileOwner 语义：先激活 admin 再设 PO）：binder 通道（未 setup 用户）失败后回退写 `/data/system/users/<id>/profile_owner.xml`（restartRequired=true；ASR-0085） | Set Profile Owner (user 0, post-setup demo) |
| `DeleteProfileOwner` | `component`（可选，缺省当前 PO）、`userId`（可选，缺省 0） | 删除 ProfileOwner：`clearProfileOwner`（调用方为 PO 时）回退删除 profile_owner.xml 文件（ASR-0085） | Delete Profile Owner |
| `IsProfileOwner` | `packageName`（可选）、`userId`（可选，缺省 0） | 查询指定用户 ProfileOwner 状态（含 PO 组件与磁盘 fileHasOwner；ASR-0085） | Query Profile Owner |
| `QueryOwnAdminLocal` | 无 | 本应用直接经公开 `dpm.isAdminActive(TestAdminReceiver)` 端到端探测自身 admin 激活态（ASR-0080 验证辅助） | Query Own Admin (local dpm.isAdminActive) |

说明：设备管理由 Launcher 侧实现（见 `documents/technical-documentation/DeviceManagement-ASR-0080-0081-0083-0085-TechnicalDesign.md`）。**AOSP 13 框架限制（源码核验）**：① `setDeviceOwner`/`setProfileOwner` 的 binder 通道在**用户 setup 完成后对非 adb 调用方拒绝**（`STATUS_USER_SETUP_COMPLETED` / "Unable to set non-default profile owner post-setup"），命令自动回退文件通道并返回 `restartRequired=true`（需 `adb root && adb shell stop && adb shell start` 生效，约 30~60s）；② `removeActiveAdmin` 框架拒绝移除当前 DO/PO 的 admin，引擎预检返回 `owner admin cannot be removed`；③ `clearDeviceOwnerApp` 只清内存不落盘，引擎必须直写 `device_owner_2.xml` 否则重启后 DO 复活；④ 删除 DO 后 Launcher 失去 DPM 特权（隐藏/挂起等接口不可用），重设 DO 并重启后恢复——测试闭环须完整执行；⑤ 本机测试通道为 `com.hmdm.testapp/com.hmdm.testapp.TestAdminReceiver`（testapp 自带 DeviceAdminReceiver）。

说明：权限/AppOps 管控由 Launcher 侧实现（见 `documents/technical-documentation/AppOpsPermissionControl-ASR-0043-0044-0045-0046-0149-TechnicalDesign.md`）。**本 ROM 特性**：① **AppOps op 数值码被厂商重排**（`OP_WRITE_SETTINGS=23`，AOSP 13 为 22；framework.jar 常量表 dex 反编译核验），引擎采用字符串 op 变体 `setMode(String, ...)` 优先（code 由 ROM 自身表解析），三项 op（PIP/WRITE_SETTINGS/CHANGE_WIFI_STATE）实测均正常落地；② 本 ROM WifiService 对 `OP_CHANGE_WIFI_STATE` 拒绝**不抛异常**（`setWifiEnabled` 返回 false、`addNetwork` 返回 -1，`dumpsys appops` 记录 rejectTime），TryChangeWifiState 同时上报布尔结果与异常供对照；③ MtkSettingsProvider 对 targetSdk>22 非特权应用写**自定义** System 键抛与 WRITE_SETTINGS 无关的键校验异常，TryWriteSettings 采用公开键 `screen_brightness` 原值回写规避；④ 通知监听授权本 ROM 走 @hide 通道（channel=nm，logcat `Allowing notification listener ... (userSet: true)` 可对照），NMS 将授权异步持久化到 `enabled_notification_listeners`（约 1~3 秒，全格式组件名）；⑤ **USB 权限需已连接 USB host 设备**：本机无设备时 `GrantUsbPermission` 返回 `{success:false, error:"no matching USB device attached", devices:[]}`（预期行为），真实授权与 `CheckUsbPermission` 的 hasPermission=true 需 USB 真机验收；⑥ 强制类/纠正器与本批无关（AppOps 模式与授权均由框架持久化，无需重启武装）。

**定位/导航栏/飞行模式/NFC（ASR-0310/0311/0315/0316/0317/0319/0320/0321/0349）**

| 事件 | 参数 | 说明 | 对应 UI |
|---|---|---|---|
| `SetLocationEnabled` | `enabled`（Boolean，必填） | 禁用/启用（关闭/打开）定位服务：禁用=保存当前模式并写 `location_mode=0`，启用=恢复保存的模式（无记录时默认 3=高精度；ASR-0310/0311 共用） | Disable/Enable Location |
| `IsLocationEnabled` | 无 | 查询定位状态（location_mode/savedMode/forceOpen，enabled=模式非 0） | Query Location State |
| `ForceOpenLocation` | `forceOpen`（Boolean，必填） | 强制打开定位：开启后每 10 秒纠正一次（用户关闭被重新打开），标志持久化、进程重启/开机自动重新武装；false 停止 | Force Open / Stop Location Force-Open |
| `SetPassiveLocationAllowed` | `allowed`（Boolean，必填） | 允许/禁止被动定位（ASR-0313）：禁止=保存当前模式并写 `location_mode=0`，允许=恢复保存的模式（与 ASR-0310/0311 共用引擎；本 ROM 被动 provider 随非 0 模式启用，见说明 ⑧） | Forbid/Allow Passive Location |
| `IsPassiveLocationAllowed` | 无 | 查询被动定位状态（passiveAllowed=location_mode 非 0，附报 savedMode） | Query Passive Location State |
| `SetAirplaneMode` | `enabled`（必填） | 打开/关闭飞行模式（写 `airplane_mode_on` + 发 `ACTION_AIRPLANE_MODE_CHANGED` 平台广播；ASR-0319/0320 共用） | Turn Airplane Mode On/Off |
| `IsAirplaneMode` | 无 | 查询飞行模式状态（airplane_mode_on/forceOpen） | Query Airplane Mode State |
| `ForceOpenAirplaneMode` | `forceOpen`（必填） | 强制打开飞行模式：开启后每 10 秒纠正一次（用户关闭被重新应用），标志持久化、进程重启/开机自动重新武装；false 停止 | Force Open / Stop Airplane Force-Open |
| `SetNavigationBarEnabled` | `enabled`（必填） | 显示/隐藏导航栏（写 MTK 键 `Settings.System.navigation_visible`，附报 navigation_mode 对照值） | Disable/Enable Navigation Bar |
| `IsNavigationBarEnabled` | 无 | 查询导航栏状态（navigation_visible==1 即启用） | Query Navigation Bar State |
| `SetNfcEnabled` | `enabled`（Boolean，必填） | 打开/关闭 NFC（反射 `NfcAdapter.enable/disable`，WRITE_NFC_SETTINGS 签名权限；ASR-0315/0316 共用，写后轮询状态机核对） | Turn NFC On/Off |
| `IsNfcEnabled` | 无 | 查询 NFC 状态（state/stateName/nfc_on/forceOpen/supported） | Query NFC State |
| `ForceOpenNfc` | `forceOpen`（Boolean，必填） | 强制打开 NFC：开启后每 10 秒纠正一次（用户关闭被重新打开），标志持久化、进程重启/开机自动重新武装；false 停止 | Force Open / Stop NFC Force-Open |
| `SetMobileDataEnabled` | `enabled`（Boolean，必填） | 打开/关闭（禁用/启用）移动数据（反射 `TelephonyManager.setDataEnabled`，MODIFY_PHONE_STATE 签名权限；ASR-0275/0276 共用，写后读回核对） | Turn Mobile Data On/Off |
| `IsMobileDataEnabled` | 无 | 查询移动数据状态（dataEnabled/tmDataEnabled/mobile_data/forceClose/forceOpen/locked） | Query Mobile Data State |
| `ForceCloseMobileData` | `forceClose`（Boolean，必填） | 强制关闭移动数据：关闭后每 10 秒纠正一次（用户打开被重新关闭），标志持久化、进程重启/开机自动重新武装；false 停止 | Force Close / Stop Mobile Data Force-Close |
| `ForceOpenMobileData` | `forceOpen`（Boolean，必填） | 强制开启移动数据：开启后每 10 秒纠正一次（用户关闭被重新打开），标志持久化、进程重启/开机自动重新武装；false 停止 | Force Open / Stop Mobile Data Force-Open |
| `SetMobileDataStateLocked` | `locked`（Boolean，必填） | 锁定移动数据状态：锁定时快照当前状态为基线，任何状态变更每 10 秒被回滚到基线（ASR-0279）；false 解锁 | Lock / Unlock Mobile Data State |
| `IsMobileDataStateLocked` | 无 | 查询移动数据状态是否锁定（locked/baseline） | Query Mobile Data Lock |

说明：定位/导航栏/飞行模式/NFC/移动数据由 Launcher 侧实现（见 `documents/technical-documentation/DeviceStateControl-ASR-0310-0311-0319-0320-0321-0349-TechnicalDesign.md`、`documents/technical-documentation/PassiveLocationControl-ASR-0313-TechnicalDesign.md`、`documents/technical-documentation/NfcControl-ASR-0315-0316-0317-TechnicalDesign.md` 与 `documents/technical-documentation/MobileDataControl-ASR-0275-0276-0277-0279-TechnicalDesign.md`）。**本 ROM 特性**：① 飞行模式"写键 + 广播"两步缺一不可（单写或单广播均不生效）；本 ROM 飞行模式下 Wi-Fi 保持启用（MTK 行为），生效判定以 `dumpsys wifi` 的 `AirplaneModeOn` 与 `dumpsys telephony.registry` 射频电源为准；② ASR-0349 计划目标 `navigation_mode` 是导航栏 overlay 镜像值（ASR-0345 已核验直写无效），本 ROM 真实控制键为 `Settings.System.navigation_visible`（MTK 定制），命令读写该键；③ 强制打开只纠正状态、不拦截设置页入口（ASR-0322 不在本批范围）；④ 强制纠正器 tick 间隔 10 秒，验证等待 12~13 秒；测试结束后必须 `forceOpen=false` 清标志；⑤ **NFC 无硬件设备（无 android.hardware.nfc 特性、NfcService 未启动）**：三个 NFC 命令返回 `{supported:false, success:false, error:"NFC not supported on this device"}`（预期行为，非缺陷），不持久化强制标志、不启动纠正器；`NfcAdapter.enable/disable` 的真实开关行为须在具备 NFC 硬件的设备上验收；⑥ **移动数据主开关本 ROM 无 `ConnectivityManager.setMobileDataEnabled`（Sheet1 原规划路径，dex 反编译核验不存在）**，落地为反射 `TelephonyManager.setDataEnabled`（MODIFY_PHONE_STATE，平台签名自动授予）；`ConnectivityManager.getMobileDataEnabled()` 为本 ROM 客户端壳（读 per-sub 状态），`Settings.Global.mobile_data` 为遗留镜像键（实测不随框架状态变化，仅附报对照）；**无 SIM 卡设备（gsm.sim.state=ABSENT）**：Set 命令 callResult=true（API/权限核验通过）但 per-sub 状态无可翻转目标，如实返回 `success:false` + error（预期行为，非缺陷），强制/锁定命令的纠正器机制与持久化正常；状态真实翻转须在插 SIM 设备上验收；⑦ 移动数据多标志优先级：强制关闭 > 强制开启 > 状态锁定（如强制开启中再锁定，纠正器仍按强制开启执行，停止后回落锁定基线）；⑧ **被动定位（ASR-0313）**：Android 13 无独立"被动定位"开关，`Settings.Secure.location_providers_allowed` 不被消费（本 ROM 为 null）；被动 provider 的启用完全随 `location_mode`——模式 1/2/3 均 enabled、模式 0 时 disabled（2026-08-11 真机核验），故"允许被动定位"= 定位开启（LOCATION_MODE 组合），命令与 ASR-0310/0311 共用 `location_policy` savedMode 账本；与 ASR-0311 强制打开并存时强制纠正器优先级更高（禁止被动定位会被 10 秒纠正器重新打开）。

**组件/默认应用（ASR-0019/0087/0089/0094）**

| 事件 | 参数 | 说明 | 对应 UI |
|---|---|---|---|
| `SetComponentEnabled` | `packageName`（必填）、`component`（可选 `pkg/class`，缺省=整应用）、`enabled`（Boolean，必填） | 禁用/启用应用组件（`setComponentEnabledSetting`）或整应用（`setApplicationEnabledSetting`，CHANGE_COMPONENT_ENABLED_STATE 签名权限），写后读回核对；Launcher 自身拒绝（self-protection）；DONT_KILL_APP 不杀运行中进程；状态 PMS 持久化重启保持（ASR-0019） | Disable/Enable Own Activity Component / Disable/Enable Whole App |
| `IsComponentEnabled` | `packageName`（必填）、`component`（可选，缺省=整应用） | 查询组件/整应用启用状态（state/stateName/enabled；DEFAULT 态附报 manifestDefault/resolvedEnabled）（ASR-0019） | Query Own Activity Component State / Query Whole App State |
| `SetDefaultSmsApp` | `packageName`（必填） | 设置默认短信应用（`RoleManager.addRoleHolderAsUser` @SystemApi 反射，`android.app.role.SMS`；框架资格校验目标包，非合格包被拒 success=false；ASR-0087） | Set Default SMS App (com.android.mms / own app, framework rejects) |
| `GetDefaultSmsApp` | 无 | 查询默认短信应用角色持有者（holder/holders/count）（ASR-0087） | Query Default SMS App |
| `SetDefaultDialerApp` | `packageName`（必填） | 设置默认拨号应用（同机制，`android.app.role.DIALER`；ASR-0089） | Set Default Dialer App (com.android.dialer) |
| `GetDefaultDialerApp` | 无 | 查询默认拨号应用角色持有者（ASR-0089） | Query Default Dialer App |
| `SetDefaultAssistant` | `packageName`（必填） | 设置默认 Assistant（同机制，`android.app.role.ASSISTANT`；本机无合格助理应用，任何包均被框架拒绝；ASR-0094） | Set Default Assistant (com.mediatek.voicecommand, framework rejects) |
| `GetDefaultAssistant` | 无 | 查询默认 Assistant 角色持有者（ASR-0094） | Query Default Assistant |
| `TryStartComponent` | `component`（必填，pkg/class） | 本应用显式启动指定 Activity 组件：禁用时 ActivityNotFoundException（started=false + error），启用时 started=true（ASR-0019 端到端探测） | Probe: Try Start StatsQueryTestActivity |
| `ResolveComponent` | `component`（必填） | 本应用 `resolveActivity` 解析指定组件：禁用时 resolved=false（ASR-0019 交叉核对） | - |
| `GetAssistantSetting` | 无 | 读取 `Settings.Secure.assistant` 镜像键（角色持有者为空时键值为空；ASR-0094 交叉核对） | Probe: Read Settings.Secure assistant Key |
| `AddVpnProfile` | `name`（必填）、`type`（必填：`pptp`/`l2tp_ipsec_psk`/`l2tp_ipsec_rsa`/`ipsec_xauth_psk`/`ipsec_xauth_rsa`/`ipsec_hybrid_rsa`/`ikev2_ipsec_user_pass`/`ikev2_ipsec_psk`/`ikev2_ipsec_rsa` 或 0~8）、`server`（必填）、`username`、`password`、`dnsServers`、`searchDomains`、`routes`、`mppe`、`l2tpSecret`、`ipsecIdentifier`、`ipsecSecret`、`ipsecUserCert`、`ipsecCaCert`、`ipsecServerCert`、`saveLogin`、`maxMtu`、`excludeLocalRoutes` | 新增/覆盖 VPN profile（`LegacyVpnProfileStore` 存储 `VPN_<name>`，写后读回解码核对 readBackOk；提供 username/password 时默认 saveLogin=true 以便序列化保存；IKEv2 类型自动注入本 ROM 兼容算法集、自动 base64 化原始 ipsecSecret、身份回填 ipsecIdentifier；ASR-0215） | Add PPTP Profile / Add L2TP/IPSec PSK Profile / Add IPSec XAUTH PSK Profile |
| `StartVpnProfile` | `name`（必填）、`platform`（可选，缺省 false） | 连接存储的 profile（验证辅助）：默认通道 `VpnManager.startLegacyVpn`（**本 ROM legacy 类型被框架拒绝** "Legacy VPN is deprecated"，IKEv2 缺参/算法不满足时如实报错）；`platform=true` 走 `IVpnManager.startVpnProfile`（profile 存 `PLATFORM_VPN_<user>_com.hmdm.launcher`，IKEv2 会话进入 CONNECTING 并返回 sessionKey；ASR-0218 断开验证辅助） | Start Profile (test_vpn, verification aid) |
| `DeleteVpnProfile` | `name`（必填） | 删除 VPN profile（remove + 读回核对 verified；连接中删除附报 wasConnected；ASR-0216） | Delete Profile (test_vpn) |
| `GetVpnProfileList` | 无 | 列出 VPN profile（name/key/type/typeName/server/username/dnsServers/searchDomains/routes/saveLogin/connected；ASR-0217） | List VPN Profiles |
| `DisconnectVpn` | 无 | 断开 profile 型 VPN（legacy 经 prepareVpn、平台键连接经 stopVpnProfile、provisioned 经 stopProvisionedVpnProfile；App 型 VpnService 含 Launcher 防火墙不触碰；ASR-0218） | Disconnect VPN |
| `SetVpnDisabled` | `disabled`（Boolean，必填） | 禁用/启用 VPN：隐藏 VPN 设置入口（`Settings$VpnSettingsActivity` 组件）+ 已配置 always-on VPN 的 lockdown（`dumpsys device_policy` mAlwaysOnVpnLockdown 读回），启用恢复原值；禁用期间配置/列表功能不受影响（ASR-0214） | Set VPN Disabled / Set VPN Enabled |
| `IsVpnDisabled` | 无 | 查询 VPN 禁用状态 + 连接状态（settingsEntryHidden/alwaysOnVpnPackage/appLockdown/legacyLockdownName/legacyState/provisionedState/provisionedSessionName/appVpnPackages；ASR-0214） | Query VPN Disabled State |
| `TryOpenVpnSettings` | 无 | 本应用真实启动 `com.android.settings/.Settings$VpnSettingsActivity`：禁用时 ActivityNotFoundException（visible=false），启用时 visible=true（ASR-0214 端到端探测） | Probe: Open VPN Settings Activity |
| `CheckVpnNetworks` | 无 | 枚举 TRANSPORT_VPN 网络（network/state/interface/packages；本 ROM getUids/getOwnerUid 不可用，packages 常为空，以 interface+防火墙状态交叉确认；ASR-0218 交叉核对） | Probe: List TRANSPORT_VPN Networks |

说明：VPN 管控由 Launcher 侧实现（见 `documents/technical-documentation/VpnProfileControl-ASR-0214-0215-0216-0217-0218-TechnicalDesign.md`）。**本 ROM 特性**：① profile 存储为 keystore2 legacy keystore SQLite（`/data/misc/keystore/vpnprofilestore.sqlite`，`profiles(owner, alias, profile)`），**owner 按调用方 uid 隔离**——Launcher（uid=1000）与 system_server 同空间（profile 系统可见、可连接），Settings 创建的 profile 在 Settings uid 空间（互不可见）；② **legacy 类型（PPTP/L2TP/IPSec）连接被本 ROM 框架禁用**（`DEVICE_INITIAL_SDK_INT=33` 下 `startLegacyVpn` 抛 "Legacy VPN is deprecated"），IKEv2 连接需真实 IKEv2 服务端（硬件受限项）；③ 断开测试闭环经平台通道（`platform=true`）；④ 测试期间重启 testapp 进程请用 root `kill <pid>`（force-stop 触发 DuraSpeed suppress list 导致 IPC 失效，重启设备恢复）。

说明：组件/默认应用由 Launcher 侧实现（见 `documents/technical-documentation/DefaultAppComponentControl-ASR-0019-0087-0089-0094-TechnicalDesign.md`）。**本 ROM 特性**：① **RoleManager 无同步 `setRoleHolder`**（Sheet1 规划路径，运行时方法集 dump 核验），引擎反射 R 风格异步 `addRoleHolderAsUser(String, String, int, UserHandle, Executor, Consumer<Boolean>)` + 回调等待（channel=addRoleHolderAsUser）；② 该 API 的 UserHandle 参数本 ROM 要求非空（传 null 抛 `NullPointerException: user cannot be null`），引擎传当前用户句柄；③ **框架角色资格校验**：目标包不合格时回调 false（无异常），命令如实返回 success=false（logcat `Role: <pkg> not qualified for android.app.role.<ROLE> due to missing ...` 可对照）；④ 本机仅 `com.android.mms`（SMS）/`com.android.dialer`（DIALER）为合格包（重设当前持有者幂等成功闭环），**ASSISTANT 无任何合格应用**——设置到合格 Assistant/切换多候选应用的路径需具备相应应用的设备验收（见需求文档"硬件受限测试说明"）；⑤ **整应用禁用会关闭 testapp 自身 IPC 通道**（广播接收器随禁用失效），恢复须走 Launcher 广播通道 `./send_test_broadcast.sh SetComponentEnabled packageName=com.hmdm.testapp enabled=true`（或 `adb shell pm enable com.hmdm.testapp`）；⑥ `send_test_broadcast.sh` 全值字符串化，`enabled` 布尔经引擎 `paramBoolean` 兼容解析，广播与 AIDL 两通道行为一致；⑦ 测试期间重启 testapp 进程请用 root `kill <pid>`（force-stop 触发 DuraSpeed suppress list 导致 IPC 失效，重启设备恢复）。

**数据/存储/截屏/用户（ASR-0125/0127/0187/0197/0326/0385/0386）**

| 事件 | 参数 | 说明 | 对应 UI |
|---|---|---|---|
| `BackupAppData` | `packageName`（必填）、`file`（可选公开路径，缺省仅存 Launcher 数据目录） | 备份指定应用数据为 .ab 文件（`IBackupManager.adbBackup` 反射，adb backup 同通道；**设备端弹出备份确认界面，需点按 BACK UP MY DATA**；Binder 阻塞至确认/60s 超时；SELinux 限制下文件先落 Launcher 数据目录再复制公开副本 publicFile/publicCopy；"ANDROID BACKUP" 魔数头 + 大小验证；ASR-0125） | Backup App Data (.ab) |
| `RestoreAppData` | `file`（必填 .ab 路径） | 从 .ab 文件恢复应用数据（`IBackupManager.adbRestore` 反射；**点按 RESTORE MY DATA 确认**；/sdcard 文件先暂存 stagedFile 再执行；恢复期间目标应用进程被框架强杀为预期，结果以 Launcher 侧日志为准；ASR-0125） | Restore App Data (.ab) |
| `ClearAppCache` | `packageName`（必填） | 清除指定应用缓存（`PackageManager.deleteApplicationCacheFiles` 反射；**本 ROM 回调不触发**，引擎以缓存目录读回核对 verified 兜底；包不存在返回 package not installed；ASR-0127） | Clear App Cache |
| `TakeScreenshot` | `file`（可选，缺省 /sdcard/Pictures/MDM/screenshot_\<ts\>.png）、`width`/`height`（可选 int） | 捕获当前屏幕为 PNG（MTK 通道 `SurfaceControl.captureDisplay` 反射，AOSP 通道回退；CAPTURE_VIDEO_OUTPUT；FLAG_SECURE 窗口为黑块；ASR-0187） | Take Screenshot (PNG) |
| `GetStorageVolumes` | 无 | 枚举存储卷（id/type/state/stateLabel/removable/description；本 ROM emulated type=2；ASR-0197/0326 辅助） | List Storage Volumes |
| `UnmountUsbStorage` | `volumeId`（可选，缺省第一个可移除公共卷） | 卸载 USB 存储卷（`StorageManager.unmount` 反射；仅可移除公共卷可选中，emulated/private 拒绝；无卷如实返回 no removable USB volume + 卷清单；ASR-0197） | Unmount USB Storage |
| `FormatExternalSd` | `volumeId`（可选，缺省第一个可移除公共卷） | 格式化外部 SD 卡（`StorageManager.format` 反射；异步执行，返回 before/after 状态；无卷如实返回 no removable SD volume；ASR-0326） | Format External SD |
| `CreateUser` | `name`（必填）、`flags`（可选 int，缺省 0） | 创建用户（`UserManager.createUser` 反射，MANAGE_USERS；本 ROM 返回 UserInfo 兼容处理；返回 userId + 用户清单；ASR-0385） | Create User (mdm_test_user) |
| `DeleteUser` | `userId`（必填 int > 0） | 删除用户（`UserManager.removeUser` 反射；拒绝主用户/当前用户；异步删除轮询确认；ASR-0386） | Delete User (userId=0, expect refuse) |
| `GetUserList` | 无 | 用户清单（id/name/flags/admin/guest/primary；本 ROM UserInfo 读公共字段；ASR-0385/0386 辅助） | List Users (Launcher API) |
| `WriteDataProbe` | `value`（必填） | 本应用 files 目录写入 probe.txt（ASR-0125 备份/恢复端到端探针） | Write data probe |
| `ReadDataProbe` | 无 | 读取本应用 probe.txt 内容（ASR-0125 恢复验证） | Read data probe |
| `WriteCacheProbe` | `value`（必填） | 本应用 cache 目录写入 cache_probe.txt（ASR-0127 探针） | Write cache probe |
| `CheckCacheProbe` | 无 | 检查 cache_probe.txt 是否存在（ASR-0127 清除验证） | Check cache probe |
| `GetUserListLocal` | 无 | 本应用自身 `UserManager.getUsers()` 探针（无 MANAGE_USERS 时如实报 SecurityException；ASR-0385/0386 交叉核对） | List Users (local probe) |

**电源/Doze/语音助手/有线网卡（ASR-0415/0416/0373/0374/0420/0424）**

| 事件 | 参数 | 说明 | 对应 UI |
|---|---|---|---|
| `WakeUp` | 无 | 唤醒设备（反射 `PowerManager.wakeUp`，DEVICE_POWER；轮询 isInteractive 核对，附 interactiveBefore/After/wakefulness/channel；ASR-0415） | Wake device up |
| `GoToSleep` | 无 | 休眠设备（反射 `PowerManager.goToSleep`，GO_TO_SLEEP_REASON_DEVICE_ADMIN；轮询核对；ASR-0416；**前置 `svc power stayon false`**） | Put device to sleep |
| `GetPowerState` | 无 | 电源状态查询：isInteractive + `dumpsys power` mWakefulness（ASR-0415/0416 核对） | Query power state |
| `SetDozeDisabled` | `disabled`（Boolean，必填） | 禁止/恢复 Doze（设备空闲省电）：**本 ROM 经 `cmd device_config put device_idle` 覆盖五个过渡超时标志为 7 天（AOSP 标准键 device_idle_constants 本 ROM 不消费）**，恢复=置 null 回 ROM 默认；附 dumpsys deviceidle 生效常量；ASR-0373 | Forbid Doze / Allow Doze |
| `IsDozeDisabled` | 无 | 查询 Doze 禁止态（五标志读回 + 生效常量；ASR-0373） | Query Doze state |
| `SetDozeWhitelist` | `packageNames`（String 数组，必填，整体替换） | Doze 白名单全量替换（`PowerWhitelistManager.addToWhitelist/removeFromWhitelist` 反射，回退 `cmd deviceidle whitelist`；与实时白名单求差 + 逐包读回核验 added/removed/failed；**仍被 ASR-0016/0030 耗电白名单持久化持有的包不移除（kept 如实返回）**；名单持久化重启重新下发；ASR-0374） | Set Doze Whitelist / Clear Doze Whitelist |
| `GetDozeWhitelist` | 无 | 白名单查询：持久化名单 + 实时 whitelisted 状态 + 系统清单（ASR-0374） | Get Doze Whitelist |
| `SetVoiceAssistantDisabled` | `disabled`（Boolean，必填） | 禁用/恢复语音助手：置空 `Settings.Secure.voice_interaction_service`（原值备份、启用恢复/删键；ASR-0420） | Disable voice assistant / Enable voice assistant |
| `IsVoiceAssistantDisabled` | 无 | 查询语音助手禁用态（键值 + backedUp；ASR-0420） | Query voice assistant |
| `SetEthernetConfig` | `mode`（dhcp/static，必填）、`iface`（可选，缺省首选接口/eth0）、static 必填 `ipAddress`/`prefixLength`(0-32)/`gateway`/`dns1`，可选 `dns2`/`domains`，可选 `proxyHost`/`proxyPort`/`proxyExclusionList`（数组） | 配置有线网卡（`EthernetManager.setConfiguration` 反射，写后 getConfiguration 读回核对；镜像持久化重启重新下发；附 available/interfaces/enabled；ASR-0424） | Set DHCP config / Set STATIC config |
| `GetEthernetConfig` | `iface`（可选） | 查询有线网卡配置与环境（config/persisted/available/interfaces/enabled；ASR-0424） | Get Ethernet config |
| `SetEthernetEnabled` | `enabled`（Boolean，必填） | 启停以太网栈（`EthernetManager.setEthernetEnabled` 反射；`dumpsys ethernet` 状态轮询核对；ASR-0424 配套） | Enable Ethernet / Disable Ethernet |
| `IsEthernetEnabled` | 无 | 查询以太网栈启停态（dumpsys + persisted；ASR-0424 配套） | Is Ethernet enabled |
| `SetBlueOpen` | `open`（Boolean，必填） | 开启/关闭蓝牙（Launcher 既有 `BluetoothAdapter.enable/disable`；ASR-0171/0172 测试辅助） | Enable Bluetooth / Disable Bluetooth |
| `IsBlueOpen` | 无 | 查询蓝牙开关态（ASR-0171/0172 测试辅助） | Query Bluetooth on/off |
| `SetDiscoverableForbidden` | `disabled`（Boolean，必填） | 禁止/恢复可被发现模式：扫描模式强制拉回 CONNECTABLE + `cancelDiscovery`，`ACTION_SCAN_MODE_CHANGED` 回调持续纠正（约 1 秒内回滚设置页可见性开关）；附 forbidden/scanMode/scanModeCorrected；ASR-0180 | Forbid discoverable / Allow discoverable |
| `IsDiscoverableForbidden` | 无 | 查询可被发现禁止态（标志 + 适配器/扫描模式状态；ASR-0180） | Query discoverable forbid |
| `SetLimitedDiscoverableForbidden` | `disabled`（Boolean，必填） | 禁止/恢复有限可被发现模式（Android 一般与有限可发现共用同一扫描模式常量，与 ASR-0180 共用强制执行，标志独立持久化；ASR-0181） | Forbid limited discoverable / Allow |
| `IsLimitedDiscoverableForbidden` | 无 | 查询有限可发现禁止态（ASR-0181） | Query limited forbid |
| `SetBluetoothPageDisabled` | `disabled`（Boolean，必填） | 禁用/恢复蓝牙设置页（`Settings$BluetoothSettingsActivity` 组件禁用/恢复 DEFAULT，写后读回核对；原禁用态还原；ASR-0174） | Disable BT page / Enable BT page |
| `IsBluetoothPageDisabled` | 无 | 查询蓝牙设置页禁用态（标志 + 组件读回；ASR-0174） | Query BT page state |
| `SetBluetoothFileTransferDisabled` | `disabled`（Boolean，必填） | 禁止/恢复蓝牙文件传输：禁用/恢复 com.android.bluetooth 全部 9 个 BluetoothOpp 组件（7 activity+2 receiver，含 TransferHistory；**本 ROM 无 BluetoothOppService 且 6/9 个 manifest 出厂禁用**，APK manifest aapt2 核验；枚举带 MATCH_DISABLED_COMPONENTS；原禁用态组件保持；附 components 状态列表；ASR-0176） | Forbid BT file transfer / Allow |
| `IsBluetoothFileTransferDisabled` | 无 | 查询文件传输禁止态（标志 + 各 OPP 组件状态；ASR-0176） | Query BT file transfer |
| `SetScoCallDisabled` | `disabled`（Boolean，必填） | 禁止/恢复蓝牙外设通话：通话中经 `AudioManager.setCommunicationDevice` 强制路由听筒/扬声器 + 停止/禁止 SCO；`SCO_AUDIO_STATE_CHANGED`/`MODE_CHANGED`/`PHONE_STATE_CHANGED` 回调持续执行；解除时 `clearCommunicationDevice`；附 audioMode/callActive/routedTo/communicationDeviceAfter；ASR-0178 | Forbid BT calls / Allow |
| `IsScoCallDisabled` | 无 | 查询 SCO 通话禁止态（标志 + 音频模式/SCO/通信设备状态；ASR-0178） | Query SCO policy |
| `SetBluetoothAccessPolicy` | `mode`（int，必填 0=off 1=whitelist 2=blacklist） | 设置蓝牙连接策略模式并立即评估（地址/名称任一维度违规即触发；ASR-0175） | Set Access Mode Off/Whitelist/Blacklist |
| `GetBluetoothAccessPolicy` | 无 | 查询连接策略（mode + 地址/名称四份名单 + 当前已连接/已配对设备；ASR-0175） | Get Access Policy |
| `SetBluetoothAddressWhitelist` | `addresses`（String 数组 `AA:BB:CC:DD:EE:FF`，必填，整体替换） | 替换设备地址白名单（格式校验、大写归一化；ASR-0175） | Set Address Whitelist |
| `SetBluetoothAddressBlacklist` | `addresses`（String 数组，必填，整体替换） | 替换设备地址黑名单（ASR-0175） | Set Address Blacklist |
| `SetBluetoothNameWhitelist` | `names`（String 数组，必填，整体替换） | 替换设备名称白名单（别名优先 getName/getAlias；ASR-0175） | Set Name Whitelist |
| `SetBluetoothNameBlacklist` | `names`（String 数组，必填，整体替换） | 替换设备名称黑名单（ASR-0175） | Set Name Blacklist |
| `ApplyBluetoothAccessPolicy` | 无 | 立即评估一轮：遍历已连接/已配对设备，违规断开（反射 `BluetoothDevice.disconnect`）/解除配对（反射 `removeBond`）；附 disconnected/unbonded 列表；ASR-0175 | Apply Access Policy |
| `GetBluetoothStatus` | 无 | 整体蓝牙状态：五项策略标志、适配器状态（supported/enabled/scanMode/discovering/bondedDevices）、音频状态（audioMode/scoActive/communicationDevice）、设置页组件状态、OPP 组件状态（辅助） | Get Bluetooth Status |
| `TrySetDiscoverable` | 无 | 框架探测：本 app 调用 `setScanMode(DISCOVERABLE)` 模拟设置页可见性开关（**本 ROM 该调用需 BLUETOOTH_PRIVILEGED，普通应用被 SecurityException 拒绝、如实上报**），回滚验证以真实路径 REQUEST_DISCOVERABLE 对话框为准；附 setDiscoverableSucceeded/scanModeAfterSet/scanModeSettled/revertedByPolicy；ASR-0180/0181 验证 | Probe: set discoverable & observe revert |
| `TryStartDiscovery` | 无 | 框架探测：启动发现（有限可发现态）并观察策略回滚（**本 ROM 普通应用 startDiscovery 被框架拒绝 discoveryStarted=false**，如实上报；ASR-0180/0181 验证） | Probe: start discovery & observe revert |
| `TryOpenBluetoothSettings` | 无 | 框架探测：启动 `android.settings.BLUETOOTH_SETTINGS`（禁用时 ActivityNotFoundException → blocked=true；ASR-0174 验证） | Probe: open BT settings |
| `ResolveBluetoothShare` | 无 | 框架探测：解析 `ACTION_SEND` + package=com.android.bluetooth 分享目标（OPP 组件禁用后 resolved=false；ASR-0176 验证） | Probe: resolve BT share |
| `GetBluetoothStateLocal` | 无 | 本地探测：本 app 直读适配器状态（scanMode/bondedDevices/discovering；对照用） | Probe: local BT state |

说明：蓝牙管控由 Launcher 侧实现（见 `documents/technical-documentation/BluetoothControl-ASR-0174-0175-0176-0178-0180-0181-TechnicalDesign.md`）。**权限通道**：Launcher 平台签名（uid=1000），`BLUETOOTH_CONNECT`（signature\|appop\|privileged，manifest 既有声明）自动授予；蓝牙客户端类位于本 ROM `/apex/com.android.btservices/javalib/framework-bluetooth.jar`（boot classpath）。**本 ROM 特性**：① 一般可发现与有限可发现共用扫描模式常量 `SCAN_MODE_CONNECTABLE_DISCOVERABLE`（ASR-0180/0181 同一强制引擎、标志独立）；② **无 `BluetoothOppService`**，文件传输禁止=禁用全部 9 个 `.opp.*` 组件（其中 6 个 manifest 出厂禁用，枚举带 MATCH_DISABLED_COMPONENTS；`SetBluetoothFileTransferDisabled` 返回 components 列表）；③ 项目 compileSdk 33 的 android.jar 为裁剪版（`setScanMode`/`getConnectedDevices` 等缺失），引擎全部反射调用（ROM 端 dex 核验存在）；④ 连接/配对策略为"蓝牙回调 + 断连/解配"纠正语义（`ACTION_ACL_CONNECTED` 断开违规连接、`ACTION_BOND_STATE_CHANGED` 解除违规配对，框架重连被反复纠正；配对策略为配对完成即解除，无阻止配对发生的标准接口）；⑤ 本机无 SIM/无蓝牙外设，SCO 通话路由与名单断连/解配的真实效果为受限验证（见需求文档"硬件受限测试说明"）；⑥ **IPC 门禁**：Launcher ApiBinder 仅放行系统 uid/shell/平台签名包（testapp 同平台签名放行），testapp TestCommandReceiver 已加 `android.permission.DUMP` 权限（adb shell 通道不受影响）；⑦ 白名单模式 + 名单全空时配对解绑挂起（仅断连不解配，防误清空配对）；⑧ SCO 禁止仅在引擎实际强制过路由后清除（`scoRouteForced`）。

**壁纸管控（ASR-0183/0184）**

| 事件 | 参数 | 说明 | 对应 UI |
|---|---|---|---|
| `SetWallpaper` | `target`（home/lock/both，必填）、`imageBase64`（可选；缺省时按 color/width/height 生成纯色 PNG）、`color`（可选：red/green/blue/purple/cyan/yellow/magenta/gray/grey/white/black 或 `0xAARRGGBB`/`#RRGGBB`/十进制——无 alpha 形式按不透明处理；未知回退红；默认红）、`width`（默认 800，1~4096）、`height`（默认 480，1~4096） | 设置桌面（ASR-0183）/锁屏（ASR-0184）壁纸：`WallpaperManager.setBitmap`（FLAG_SYSTEM/FLAG_LOCK/组合），base64 解码 + 三重写后核对（id 变化、框架 colors 主色匹配、实际内容主色读回）；**尺寸防护**：任一维超 8192/总像素超 40MP 拒绝，合法大图 inSampleSize 采样解码（返回 decodedWidth/decodedHeight/sampleSize）；`imageBase64` 在 ApiBinder 与 TestBroadcast 日志中均脱敏（`***`） | Set HOME/LOCK/BOTH wallpaper（6 按钮） |
| `GetWallpaper` | 无 | 查询壁纸状态：桌面/锁屏 {wallpaperId, colors（primary/secondary/tertiary）, drawableDominantColor}（锁屏读回恒 null——本 ROM 无 `getLockWallpaperBitmap`） | Query wallpaper state |
| `GetWallpaperStateLocal` | 无 | 本地探测：本 app 自身 WallpaperManager 独立读回（homeId/lockId/colors/lockSet；getDrawable 权限门禁字段如实上报 null） | Local probe: WallpaperManager cross-check |

说明：壁纸管控由 Launcher 侧实现（见 `documents/technical-documentation/WallpaperControl-ASR-0183-0184-TechnicalDesign.md`）。**权限**：manifest 新增 `SET_WALLPAPER`（normal 级，安装即授予）；公开 API 实现，无 @SystemApi/ROM 依赖。**本 ROM 特性**：① `getLockWallpaperBitmap()` 不存在（framework.jar 运行期 NoSuchMethodException + 裁剪 SDK javap 双核验）——锁屏位图读回不可用，锁屏核验以 id+colors+文件（`/data/system/users/0/wallpaper_lock`）+ `dumpsys wallpaper` 为准；② `getWallpaperId(FLAG_LOCK)` 未设置时为 -1（非 AOSP 的 0），首次任意 setBitmap 后锁屏数据对象初始化（id 与当时桌面 id 同值）；③ 壁纸内容由 WallpaperManagerService 持久化（`/data/system/users/0/wallpaper`/`wallpaper_lock`），无需 Launcher 持久化/重新下发；④ 锁屏记录元数据（id+颜色缓存）持久化于 `wallpaper_info.xml`（ABX），删除 wallpaper_lock 文件重启 framework 后锁屏渲染回退默认灰底但元数据残留；⑤ HMDM Launcher 主页自绘背景覆盖桌面壁纸，桌面可视化以文件级核验为准；⑥ `imageBase64` 在 ApiBinder 与 TestBroadcast 日志中均脱敏（`***`）；⑦ TestBroadcast 通道命令在工作线程 + goAsync 派发，壁纸页按钮在工作线程执行（大图命令秒级耗时不影响 UI）。

**设备信息查询（ASR-0108/0110/0219/0262/0265/0290/0387/0442）**

| 事件 | 参数 | 说明 | 对应 UI |
|---|---|---|---|
| `GetFileAttribute` | `path`（必填）、`list`（可选布尔，目录列表，前 200 项） | 获取文件/目录属性（存在性、类型、大小、时间戳、权限位、symlink、canonical；ASR-0108） | Get File Attribute / + list |
| `CheckRootStatus` | 无 | 检查设备 root 状态（su 二进制/Magisk/Superuser APK/su 守护/开发构建标志；shell 探针原始证据 suLsProbe/suExecProbe 返回；ASR-0110） | Check Root Status (Launcher API) |
| `CheckRootStatusLocal` | 无 | 本地探针：本 app 进程内同口径 root 检查（交叉核对；ASR-0110） | Check Root Status (local probe) |
| `GetVpnStatus` | 无 | 查询 VPN 服务状态：活动 TRANSPORT_VPN 网络（state/interface/owner packages）+ device owner always-on 包（ASR-0219） | Get VPN Status |
| `QueryNumberAttribution` | `number`（必填） | 离线号码归属地查询：最长前缀匹配（7→3 位），返回 province/city/operator（ASR-0262） | Query Number Attribution |
| `GetCellInfo` | 无 | 获取 Cell ID/小区信息：getAllCellInfo（按类型）+ getCellLocation + 订阅列表（ASR-0265） | Get Cell Info (Launcher API) |
| `GetCellInfoLocal` | 无 | 本地探针：本 app 直调 getAllCellInfo（无权限时 SecurityException 为权限门证据；ASR-0265） | Get Cell Info (local probe) |
| `GetSimContacts` | 无 | 获取 SIM 联系人：IccProvider `content://icc/adn`（name/number/emails/anr/efid/index；ASR-0290） | Get SIM Contacts (Launcher API) |
| `GetSimContactsLocal` | 无 | 本地探针：本 app 直查 icc/adn（无 READ_CONTACTS 时 SecurityException 为权限门证据；ASR-0290） | Get SIM Contacts (local probe) |
| `GetUserList` | 无 | 用户列表：`UserManager.getUsers`（id/name/flags/admin/guest/primary；ASR-0387；亦为 0385/0386 读回源） | List Users (Launcher API) |
| `GetUserListLocal` | 无 | 本地探针：本 app 直调 getUsers（API 33 非特权应用 SecurityException 为权限门证据） | List Users (local probe) |
| `GetWebViewInfo` | 无 | WebView Provider 信息上报：webview_provider_default 设置键 + 当前 Provider（WebViewUpdateService 反射 / dumpsys 回填）+ 候选包 versionName/versionCode/enabled + dumpsysCurrent 交叉核对（ASR-0442） | Get WebView Info (Launcher API) |
| `GetWebViewInfoLocal` | 无 | 本地探针：设置键 + 本 app 可见的 webview 包（包可见性限制 note 说明；ASR-0442） | Get WebView Info (local probe) |

说明：设备信息查询由 Launcher 侧实现（见 `documents/technical-documentation/InfoQueryControl-ASR-0108-0110-0219-0262-0265-0290-0387-0442-TechnicalDesign.md`）。全部为**只读查询**，无系统配置写入。**权限**：manifest 新增 `READ_CONTACTS`（SIM 联系人）；复用 READ_PHONE_STATE/READ_PRIVILEGED_PHONE_STATE/ACCESS_FINE_LOCATION（Cell ID）/ACCESS_NETWORK_STATE（VPN）/DUMP（dumpsys webviewupdate）/MANAGE_USERS（用户列表）。**本 ROM 特性**：① SELinux 将 su 标记 `su_exec` 并拒绝 app 域 stat（root 检查直接 File.exists() 假阴性，shell `ls` 探针 "Permission denied" 行 + ro.debuggable=1 双重证据判定；本机 /system/xbin/su 实测可获 root）；② `dpm.setAlwaysOnVpnPackage` 抛 UnsupportedOperationException（alwaysOnVpnPackage 如实 null）；③ `WebViewUpdateService.getCurrentWebViewPackage` 不存在（NoSuchMethodException），当前 Provider 经 `dumpsys webviewupdate` 解析回填（source=dumpsys）；④ 本机无 SIM——Cell ID/SIM 联系人如实返回空结构（真实数据需 SIM 真机）；⑤ WebView 包对非系统应用不可见（本地探针 providers 为空为预期）；⑥ 号码归属地为内置样例离线库（`assets/phone_attribution.csv`，可整库替换）。

说明：数据/存储/截屏/用户由 Launcher 侧实现（见 `documents/technical-documentation/DataStorageScreenshotUserControl-ASR-0125-0127-0187-0197-0326-0385-0386-TechnicalDesign.md`）。**本 ROM 特性**：① 备份接口为 AOSP 12 风格 `adbBackup/adbRestore`（AOSP 13 的 `fullBackup` 不存在），**备份/恢复需设备端 backupconfirm 确认界面点按**（60s 不点按即取消；界面无法在锁屏之上显示）；② **SELinux 拒绝 system_server 读写 /sdcard fuse 对象**——备份文件先落 Launcher 数据目录（system_app_data_file）再由 Launcher 复制公开副本；③ 清除缓存回调不触发（引擎 verified 读回兜底）；④ 截屏为本 ROM MTK 通道（`getInternalDisplayToken + captureDisplay + asBitmap`）；⑤ 用户管理 `createUser` 返回 UserInfo、UserInfo 读公共字段；⑥ 本机无外置 SD/USB OTG 卷，卸载/格式化命令如实返回 no removable volume（真实行为需可移除存储真机）；⑦ 备份/恢复用例中恢复会强杀 testapp 进程（其 IPC 结果日志缺失为预期，以 Launcher 侧日志为准）；⑧ 测试期间重启 testapp 进程请用 root `kill <pid>`（force-stop 触发 DuraSpeed suppress list 导致 IPC 失效，重启设备恢复）。

**应用安装/卸载策略（ASR-0006/0007/0010/0015/0029/0040/0072/0073）**

| 事件 | 参数 | 说明 | 对应 UI |
|---|---|---|---|
| `SetUninstallWhitelist` | `packageNames`（String 数组，整体替换） | 卸载白名单：名单内包不可卸载、其余非系统包解除锁定 | Add/Remove to uninstall whitelist |
| `GetUninstallWhitelist` | 无 | 卸载白名单查询（含逐包 blocked/installed 实时态） | Get uninstall whitelist |
| `SetUninstallBlacklist` | `packageNames`（String 数组，整体替换） | 卸载黑名单：仅名单内包锁定卸载 | Add/Remove to uninstall blacklist |
| `GetUninstallBlacklist` | 无 | 卸载黑名单查询 | Get uninstall blacklist |
| `SetInstallPolicyMode` | `mode`（0=off/1=whitelist/2=blacklist） | 安装策略模式（黑名单=反向模式，命中正则新装包自动静默卸载） | Mode 0/1/2 buttons |
| `GetInstallPolicyMode` | 无 | 安装策略模式 + 黑名单正则查询 | Get install policy mode |
| `SetInstallBlacklist` | `patterns`（正则数组，整体替换） | 安装黑名单正则列表替换 | Add/Remove regex to install blacklist |
| `GetInstallBlacklist` | 无 | 安装黑名单正则查询 | Get install blacklist regexes |
| `SetKeepAliveEnabled` | `packageName`、`enabled` | 保活开关（Doze/App Standby 白名单近似） | Enable/Disable keep-alive |
| `IsKeepAliveEnabled` | `packageName` | 保活标志 + 实时 ignoring 状态查询 | Query keep-alive |
| `GetKeepAliveList` | 无 | 保活名单全量查询 | Get keep-alive list |
| `IsAppAlive` | `packageName` | 检测应用是否存活（运行进程匹配） | Is app alive |
| `SetManageExternalStorageGranted` | `packageName`、`granted` | MANAGE_EXTERNAL_STORAGE 授予/取消（AppOps） | Grant/Revoke MANAGE_EXTERNAL_STORAGE |
| `IsManageExternalStorageGranted` | `packageName` | MANAGE_EXTERNAL_STORAGE 状态查询 | Query storage permission |
| `SetDesktopIconHidden` | `packageName`、`hidden` | 隐藏/显示桌面图标（setApplicationHidden） | Hide/Show desktop icon |
| `IsDesktopIconHidden` | `packageName` | 图标隐藏状态查询 | Query icon hidden state |
| `GetPackageInfo` | `packageName` | 指定包 PackageInfo 独立查询 | Get PackageInfo |

**默认意图（ASR-0091/0095/0102）**

| 事件 | 参数 | 说明 | 对应 UI |
|---|---|---|---|
| `SetDefaultLauncherSettingLocked` | `locked` | 管控用户修改默认桌面（no_config_home_app） | Lock/Unlock launcher change |
| `IsDefaultLauncherSettingLocked` | 无 | 默认桌面修改锁查询 | Query launcher lock |
| `SetDefaultVideoPlayer` | `packageName`（必填）、`activityName`（可选，自动解析） | 设置默认视频播放器（video/* persistent preferred） | Set default video player |
| `GetDefaultVideoPlayer` | 无 | 默认视频播放器查询 | Get default video player |
| `ClearDefaultVideoPlayer` | `packageName` | 清除视频播放器绑定 | Clear video player binding |
| `SetDefaultAppForFileType` | `mimeType`、`packageName`、`activityName`（可选） | 设置指定文件类型默认应用 | Set default app for MIME type |
| `GetDefaultAppForFileType` | `mimeType` | 文件类型默认应用查询 | Get default app for MIME type |
| `ClearDefaultAppForFileType` | `mimeType`、`packageName` | 清除文件类型绑定 | Clear file-type binding |

**USB/存储/SIM（ASR-0191/0196/0273/0325）**

| 事件 | 参数 | 说明 | 对应 UI |
|---|---|---|---|
| `SetUsbDataTransferDisabled` | `disabled` | USB 数据传输锁（no_usb_file_transfer + 函数 charging/恢复） | Disable/Enable USB data transfer |
| `IsUsbDataTransferDisabled` | 无 | USB 数据传输锁 + 当前 USB 函数查询 | Query USB data lock |
| `SetUsbExternalStorageDisabled` | `disabled` | USB 外接存储锁（no_physical_media） | Disable/Enable USB ext storage |
| `IsUsbExternalStorageDisabled` | 无 | USB 外接存储锁查询 | Query USB ext storage lock |
| `SetDataRoamingDisabled` | `disabled` | 数据漫游禁用/启用（DISALLOW_DATA_ROAMING） | Disable/Enable roaming |
| `IsDataRoamingDisabled` | 无 | 数据漫游状态查询 | Query roaming |
| `SetSdCardMountDisabled` | `disabled` | SD 卡挂载锁（no_physical_media 共用 + 卸载可移除卷） | Disable/Enable SD mount |
| `IsSdCardMountDisabled` | 无 | SD 卡挂载锁查询 | Query SD mount lock |

**音量/显示锁（ASR-0390/0391/0393/0395/0397/0412/0431）**

| 事件 | 参数 | 说明 | 对应 UI |
|---|---|---|---|
| `SetUserVolumeSettingDisabled` | `disabled` | 用户设置音量锁（no_adjust_volume 共用） | Disable/Enable user volume setting |
| `IsUserVolumeSettingDisabled` | 无 | 用户音量设置锁查询 | Query user volume lock |
| `SetVolumeKeyDisabled` | `disabled` | 音量物理键锁（同上共用限制） | Disable/Enable volume keys |
| `IsVolumeKeyDisabled` | 无 | 音量键锁查询 | Query volume key lock |
| `SetMediaVolumeModificationLocked` | `locked` | 媒体音量修改锁（捕获+10s 校正器） | Lock/Unlock media volume |
| `IsMediaVolumeModificationLocked` | 无 | 媒体音量锁查询 | Query media volume lock |
| `SetNotificationVolumeModificationLocked` | `locked` | 通知音量修改锁 | Lock/Unlock notification volume |
| `IsNotificationVolumeModificationLocked` | 无 | 通知音量锁查询 | Query notification volume lock |
| `SetAlarmVolumeModificationLocked` | `locked` | 闹钟音量修改锁 | Lock/Unlock alarm volume |
| `IsAlarmVolumeModificationLocked` | 无 | 闹钟音量锁查询 | Query alarm volume lock |
| `SetAutoSleepDisabled` | `disabled` | 自动休眠开关（screen_off_timeout=MAX/恢复） | Disable/Enable auto sleep |
| `IsAutoSleepDisabled` | 无 | 自动休眠状态查询 | Query auto sleep |
| `SetAlwaysFullscreen` | `fullscreen` | 一直全屏（状态栏+导航栏隐藏/恢复） | Enable/Disable fullscreen |
| `IsAlwaysFullscreen` | 无 | 全屏状态查询 | Query fullscreen |

### 4.4 运行时权限请求

权限请求依赖 Activity（IPC 广播无法弹权限框），使用独立 Activity：

```bash
adb shell am start -n com.hmdm.testapp/.PermissionActivity \
  --es permissions POST_NOTIFICATIONS,CAMERA,RECORD_AUDIO
```

结果写入 logcat（tag `HYX-TESTAPP-CMD`）。也可用 `adb shell pm grant com.hmdm.testapp <permission>` 直接授权。

### 4.5 返回结果示例

```bash
$ ./send_test_command.sh GetConnectionStatus
==> event=GetConnectionStatus param={}
Broadcast completed: result=0, data="{"RESULT":{"bound":true,"zenMode":0}}"
==> last HYX-TESTAPP-CMD log line:
08-04 10:00:00.000 I/HYX-TESTAPP-CMD: GetConnectionStatus => {"RESULT":{"bound":true,"zenMode":0}}
```

## 5. 典型测试流程示例（IPC）

### 5.1 通知管控（ASR-0053/0054/0055/0057/0059）

```bash
# 1. 探测连接
./send_test_command.sh GetConnectionStatus

# 2. 发送测试通知并确认可见
./send_test_command.sh SendTestNotification
adb shell dumpsys notification --noredact | grep 'pkg=com.hmdm.testapp'

# 3. 按包禁用本包通知，重发确认不再出现
./send_test_command.sh SetNotificationsEnabledForPackage packageName=com.hmdm.testapp enabled=false
./send_test_command.sh IsNotificationsEnabledForPackage packageName=com.hmdm.testapp
./send_test_command.sh SendTestNotification

# 4. 白名单/黑名单模式
./send_test_command.sh SetNotificationsWhitelist 'packageNames=["com.android.settings"]'
./send_test_command.sh GetNotificationsWhitelist
./send_test_command.sh SetNotificationsPolicyMode mode=1
./send_test_command.sh GetNotificationsPolicyMode
./send_test_command.sh SetNotificationsPolicyMode mode=0
./send_test_command.sh SetNotificationsWhitelist 'packageNames=[]'

# 5. 锁屏通知
./send_test_command.sh SetLockscreenNotificationsDisabled disabled=true
./send_test_command.sh IsLockscreenNotificationsDisabled
adb shell settings get secure lock_screen_show_notifications
./send_test_command.sh SetLockscreenNotificationsDisabled disabled=false

# 6. 状态栏通知（ASR-0059）：禁用后状态栏隐藏通知图标（通知仍可发送、面板仍展示）
./send_test_command.sh SetStatusBarNotificationsDisabled disabled=true
./send_test_command.sh IsStatusBarNotificationsDisabled
adb shell dumpsys statusbar | grep -E 'mDisabled1'   # 出现 0x20000 位（本机基线 0x1000000）
./send_test_command.sh SendTestNotification          # 通知记录仍在（dumpsys notification）
adb shell dumpsys notification --noredact | grep 'pkg=com.hmdm.testapp'
./send_test_command.sh SetStatusBarNotificationsDisabled disabled=false   # 恢复（必须）

# 7. BACK 键（ASR-0346）：三键导航的 BACK 按键隐藏禁用/恢复显示；
#    全面屏手势模式的返回手势不受控（本 ROM 限制），命令附 navigation_mode 供判断
./send_test_command.sh IsBackKeyDisabled             # 基线：disabled=false
./send_test_command.sh SetBackKeyDisabled disabled=true
adb shell dumpsys statusbar | grep mDisabled1        # 出现 0x400000 位
./send_test_command.sh IsBackKeyDisabled             # disabled=true, liveDisabled=true
./send_test_command.sh SetBackKeyDisabled disabled=false   # 恢复（必须）
```

### 5.2 摄像头/麦克风（ASR-0207/0369）

```bash
# 授权（首次需真机确认权限框，或 pm grant）
adb shell am start -n com.hmdm.testapp/.PermissionActivity --es permissions CAMERA,RECORD_AUDIO

# 基线：硬件可用
./send_test_command.sh TryOpenCamera
./send_test_command.sh TryRecordAudio

# 禁用后：策略查询 + 硬件验证
./send_test_command.sh SetCameraDisabled disabled=true
./send_test_command.sh IsCameraDisabled
./send_test_command.sh TryOpenCamera        # 期望 security/access 异常或失败
./send_test_command.sh SetMicrophoneDisabled disabled=true
./send_test_command.sh IsMicrophoneDisabled
./send_test_command.sh TryRecordAudio        # 期望 start 失败

# 恢复
./send_test_command.sh SetCameraDisabled disabled=false
./send_test_command.sh SetMicrophoneDisabled disabled=false
./send_test_command.sh TryOpenCamera
./send_test_command.sh TryRecordAudio
```

### 5.3 日志管控（ASR-0189/0190）

```bash
# 查询当前缓冲区
./send_test_command.sh GetLogBufferSize

# 设置缓冲区（运行时生效；本 ROM logd 不消费 persist.logd.size，重启回落默认 256KB）
./send_test_command.sh SetLogBufferSize size=1M buffer=all
./send_test_command.sh GetLogBufferSize

# 日志级别
./send_test_command.sh SetLogLevel tag=HYX_MDM_TEST level=V
./send_test_command.sh GetLogLevel tag=HYX_MDM_TEST
./send_test_command.sh EmitTestLogs tag=HYX_MDM_TEST
adb logcat -d -s HYX_MDM_TEST:V        # level=V 时 V/D 日志可见
./send_test_command.sh SetLogLevel tag=HYX_MDM_TEST level=D
./send_test_command.sh EmitTestLogs tag=HYX_MDM_TEST
adb logcat -d -s HYX_MDM_TEST:V        # V 日志被过滤
./send_test_command.sh SetLogLevel tag=HYX_MDM_TEST   # 恢复默认
```

### 5.4 网络黑白名单（ASR-0135/0136）

```bash
# 0. 基线：网络连通 + 防火墙未运行
./send_test_command.sh TestHttpGet url=http://example.com/
./send_test_command.sh GetNetworkFirewallStatus     # vpnRunning=false, vpnStatus=OFF

# 1. 域名白名单模式
./send_test_command.sh SetDomainWhitelist 'domains=["example.com"]'
./send_test_command.sh GetDomainWhitelist
./send_test_command.sh SetDomainPolicyMode mode=1
./send_test_command.sh GetNetworkFirewallStatus     # vpnRunning=true
./send_test_command.sh TestDnsLookup host=example.com        # resolved=true
./send_test_command.sh TestHttpGet url=http://example.com/   # 可达
./send_test_command.sh TestDnsLookup host=baidu.com          # REFUSED 秒失败
./send_test_command.sh GetNetworkFirewallStatus     # dnsBlocked 增加

# 2. 域名黑名单模式
./send_test_command.sh SetDomainPolicyMode mode=2
./send_test_command.sh SetDomainBlacklist 'domains=["baidu.com"]'
./send_test_command.sh TestDnsLookup host=baidu.com          # 拦截
./send_test_command.sh TestDnsLookup host=example.com        # 放行

# 3. IP 白名单模式（示例目标 1.1.1.1:80）
./send_test_command.sh SetDomainPolicyMode mode=0
./send_test_command.sh SetIpWhitelist 'ips=["1.1.1.1"]'
./send_test_command.sh SetIpPolicyMode mode=1
./send_test_command.sh TestTcpConnect host=1.1.1.1 port=80   # 可达
./send_test_command.sh TestTcpConnect host=223.5.5.5 port=80 # 超时（SYN 丢弃）

# 4. IP 黑名单模式
./send_test_command.sh SetIpPolicyMode mode=2
./send_test_command.sh SetIpBlacklist 'ips=["1.1.1.1"]'
./send_test_command.sh TestTcpConnect host=1.1.1.1 port=80   # 超时

# 5. 还原（模式全关 + 名单清空，VPN 停止）
./send_test_command.sh SetDomainPolicyMode mode=0
./send_test_command.sh SetIpPolicyMode mode=0
./send_test_command.sh SetDomainWhitelist 'domains=[]'
./send_test_command.sh SetDomainBlacklist 'domains=[]'
./send_test_command.sh SetIpWhitelist 'ips=[]'
./send_test_command.sh SetIpBlacklist 'ips=[]'
./send_test_command.sh GetNetworkFirewallStatus     # vpnRunning=false
```

### 5.5 统计查询（ASR-0139/0372/0021-0023）

```bash
# 1. 流量（近 1 天，全部网络；可按单应用查询）
./send_test_command.sh QueryAppTraffic
./send_test_command.sh QueryAppTraffic days=7 network=wifi
./send_test_command.sh QueryAppTraffic packageName=com.hmdm.launcher

# 2. 耗电（与设置页一致的系统估计值）
./send_test_command.sh QueryAppBattery
./send_test_command.sh QueryAppBattery packageName=com.hmdm.launcher
./send_test_command.sh QueryAppBattery limit=5

# 3. 运行时长（近 7 天前台时长）
./send_test_command.sh QueryAppRuntime
./send_test_command.sh QueryAppRuntime packageName=com.hmdm.launcher days=1

# 4. 正在运行进程
./send_test_command.sh QueryRunningApps

# 5. 运行异常（crash/ANR 历史）
./send_test_command.sh QueryAppCrashInfo
./send_test_command.sh QueryAppCrashInfo packageName=com.hmdm.testapp
```

### 5.6 进程管控（ASR-0027/0028）

```bash
# 1. 基线：启动一个目标应用并使其进入后台
adb shell am start -n com.android.settings/.Settings
adb shell input keyevent 3
adb shell ps -A | grep android.settings        # 确认存在

# 2. 结束指定应用进程（前台/后台均可）
./send_test_command.sh KillAppProcess packageName=com.android.settings
adb shell ps -A | grep android.settings        # 无输出 = 已结束

# 3. 清理后台进程（可携带 except 保留列表）
./send_test_command.sh KillBackgroundProcesses
./send_test_command.sh KillBackgroundProcesses 'except=["com.android.gallery3d"]'

# 4. 结束 testapp 自身（结果 data 可能丢失，以 ps 验证）
./send_test_command.sh KillAppProcess packageName=com.hmdm.testapp
adb shell ps -A | grep hmdm.testapp            # 无输出 = 已结束
```

### 5.7 应用运行/隐藏管控（ASR-0016/0017/0030/0099/0104/0131）

```bash
# 1. 禁止运行白名单（ASR-0017）：添加 → 验证挂起+隐藏 → 启动被阻止 → 移除恢复
./send_test_command.sh GetBlockedRunningWhitelist
./send_test_command.sh SetBlockedRunningWhitelist 'packageNames=["com.android.gallery3d"]'
adb shell dumpsys package com.android.gallery3d | grep -E 'hidden=|suspended='   # 均 true
adb shell am start -n com.android.gallery3d/.app.GalleryActivity                  # 启动失败
./send_test_command.sh GetBlockedRunningWhitelist
./send_test_command.sh SetBlockedRunningWhitelist 'packageNames=[]'               # 清空恢复
./send_test_command.sh SetBlockedRunningWhitelist 'packageNames=["com.hmdm.launcher"]'  # skipped（保护名单）

# 2. 忽略耗电优化白名单（ASR-0016/0030）：添加（免交互）→ 系统对照 → 移除
./send_test_command.sh GetIgnoreBatteryOptimizationWhitelist
./send_test_command.sh SetIgnoreBatteryOptimizationWhitelist 'packageNames=["com.hmdm.testapp"]'
./send_test_command.sh IsIgnoringBatteryOptimization packageName=com.hmdm.testapp   # true
adb shell dumpsys deviceidle whitelist | grep '^user'                                # user,com.hmdm.testapp
./send_test_command.sh SetIgnoreBatteryOptimizationWhitelist 'packageNames=[]'       # 清空恢复

# 3. 禁用/启用指定应用（ASR-0099/0104/0131）
./send_test_command.sh SetApplicationHidden packageName=org.chromium.webview_shell hidden=true   # 禁用系统浏览器
./send_test_command.sh IsApplicationHidden packageName=org.chromium.webview_shell                # true
adb shell am start -n org.chromium.webview_shell/.WebViewBrowserActivity                          # 启动失败
./send_test_command.sh SetApplicationHidden packageName=org.chromium.webview_shell hidden=false  # 恢复
./send_test_command.sh SetApplicationHidden packageName=com.android.settings hidden=true         # 隐藏 Settings
./send_test_command.sh SetApplicationHidden packageName=com.android.settings hidden=false        # 启用 Settings（ASR-0104）
./send_test_command.sh SetApplicationHidden packageName=com.android.vending hidden=true          # 本 ROM 无 Play Store → false
```

### 5.8 全局/安全设置（ASR-0166/0204/0205/0314/0345/0426）

```bash
# 1. Captive portal 弹窗（ASR-0166）：禁止 → 查询 → 恢复
./send_test_command.sh IsCaptivePortalDisabled
./send_test_command.sh SetCaptivePortalDisabled disabled=true
adb shell settings get global captive_portal_mode                     # 0
./send_test_command.sh SetCaptivePortalDisabled disabled=false

# 2. 不保留活动（ASR-0204）：禁止 → 系统对照 → 恢复
./send_test_command.sh SetAlwaysFinishActivitiesDisabled disabled=true
adb shell settings get global always_finish_activities                # 0（禁止）
./send_test_command.sh SetAlwaysFinishActivitiesDisabled disabled=false

# 3. 模拟定位（ASR-0205）：禁止 → 系统对照 → 恢复
./send_test_command.sh SetMockLocationDisabled disabled=true
adb shell settings get secure mock_location                           # 0（禁止）
./send_test_command.sh SetMockLocationDisabled disabled=false

# 4. 定位模式（ASR-0314）：省电 → 高精度恢复；越界值拒绝
./send_test_command.sh GetLocationMode
./send_test_command.sh SetLocationMode mode=2
adb shell settings get secure location_mode                            # 2
./send_test_command.sh SetLocationMode mode=3
./send_test_command.sh SetLocationMode mode=4                          # invalid mode（不写入）

# 5. 全面屏手势导航（ASR-0345）：禁用（三键）→ overlay/设置双对照 → 恢复手势
./send_test_command.sh IsGestureNavigationDisabled                     # navigation_mode=2, gesturalOverlay=true
./send_test_command.sh SetGestureNavigationDisabled disabled=true
adb shell settings get secure navigation_mode                          # 0（三键）
adb shell cmd overlay list | grep navbar                               # [x] com.android.internal.systemui.navbar.threebutton
./send_test_command.sh SetGestureNavigationDisabled disabled=false     # navigation_mode=2 恢复

# 6. 安卓小动画（ASR-0426）：禁用 → 系统对照 → 恢复
./send_test_command.sh SetAnimationsDisabled disabled=true
adb shell settings get global window_animation_scale                   # 0
./send_test_command.sh IsAnimationsDisabled                            # disabled=true
./send_test_command.sh SetAnimationsDisabled disabled=false            # 三键恢复 1.0

# 7. 字体大小（ASR-0407）：特大档 → 存储/框架配置双对照 → 恢复默认；越界/非数值拒绝
./send_test_command.sh GetFontScale                                    # scale=1.0, presets=[0.85,1,1.15,1.3]
./send_test_command.sh SetFontScale scale=1.3
adb shell settings get system font_scale                               # 1.3
adb shell dumpsys activity | grep mGlobalConfiguration                 # {1.3 ...（框架全局配置跟随）
./send_test_command.sh SetFontScale scale=0.4                          # invalid scale（不写入）
./send_test_command.sh SetFontScale scale=abc                          # invalid scale（不写入）
./send_test_command.sh SetFontScale scale=1.0                          # 恢复默认档
```

### 5.9 锁屏策略（ASR-0359/0363/0365）

```bash
# 1. 强认证超时（ASR-0359）：设置 2 小时 → 系统对照 → 恢复 0（立即强认证）
./send_test_command.sh GetStrongAuthTimeout                      # 基线 timeoutMs=0
./send_test_command.sh SetStrongAuthTimeout timeoutMs=7200000
adb shell dumpsys device_policy | grep strongAuthUnlockTimeout   # 7200000
./send_test_command.sh SetStrongAuthTimeout timeoutMs=0          # 恢复
# 注意：本 ROM 将非 0 且 <3600000ms 的值提升为 1 小时（如 300000 → 3600000，命令如实报 success=false）

# 2. 密码更改宽限期（ASR-0365）：设置 1 天 → 系统对照 → 恢复 0（永不过期）
./send_test_command.sh GetPasswordExpirationTimeout              # timeoutMs=0, expiration=0
./send_test_command.sh SetPasswordExpirationTimeout timeoutMs=86400000
adb shell dumpsys device_policy | grep passwordExpiration        # timeout=86400000
./send_test_command.sh SetPasswordExpirationTimeout timeoutMs=0  # 恢复

# 3. 连续数字序列上限（ASR-0363）：limit=2 → 策略映射 → 锁屏真实校验 → 恢复 0 → 清密码
./send_test_command.sh GetConsecutiveDigitsLimit                 # limit=0, quality=unspecified
./send_test_command.sh SetConsecutiveDigitsLimit limit=2         # quality=alphanumeric, minLength=6
adb shell dumpsys device_policy | grep -E "passwordQuality|minimumPasswordLength"
adb shell locksettings set-password 1234                          # 被拒（不满足策略）
adb shell locksettings set-password abc123                        # 成功（合规密码）
./send_test_command.sh SetConsecutiveDigitsLimit limit=0          # 先恢复策略
adb shell locksettings clear --old abc123                         # 再清除密码（策略激活期间会被拒）
```

注意：第 3 步会真实设置/清除设备锁屏密码，恢复顺序须为先恢复策略（limit=0）再清密码，避免设备遗留密码。

### 5.10 账户/备份（ASR-0124/0129/0132）

```bash
# 1. 系统备份（ASR-0124）：禁用 → 读回核对 → 恢复
./send_test_command.sh IsBackupDisabled                          # 查询当前备份服务状态
./send_test_command.sh SetBackupDisabled disabled=true           # backupServiceEnabled=false
./send_test_command.sh IsBackupDisabled                           # disabled=true
./send_test_command.sh SetBackupDisabled disabled=false           # 恢复启用（backupServiceEnabled=true）

# 2. 谷歌账户（ASR-0129）：禁用 → 系统对照 → 恢复
./send_test_command.sh IsGoogleAccountsDisabled                   # accounts=[]（本 ROM 无 GMS）
./send_test_command.sh SetGoogleAccountsDisabled disabled=true
adb shell dumpsys user | grep -A5 -i restriction                  # 含 no_modify_accounts
adb shell dumpsys account                                          # 无账户
./send_test_command.sh SetGoogleAccountsDisabled disabled=false   # 恢复

# 3. Google 备份和恢复（ASR-0132）：禁用 → 系统对照 → 恢复
./send_test_command.sh SetBackupRestoreDisabled disabled=true     # channel=settings（AOSP 白名单外回退直写）
adb shell settings get secure backup_enabled                      # 0
adb shell settings get secure backup_auto_restore                 # 0
./send_test_command.sh SetBackupRestoreDisabled disabled=false    # 恢复 1/1

# 4. 自动同步（ASR-0128）：禁用 → 系统对照 → 恢复
./send_test_command.sh IsAutoSync                                 # 基线 masterSyncAutomatically=true
./send_test_command.sh SetAutoSync enabled=false                  # masterSyncAutomatically=false
adb shell dumpsys content | grep "Auto sync"                      # Auto sync: u0=false
./send_test_command.sh SetAutoSync enabled=true                   # 恢复 true

# 5. 谷歌账户自动同步（ASR-0130）：禁用 → 查询 → 启用（本 ROM 无 GMS，updatedPairs=0 + note 为正常预期）
./send_test_command.sh IsGoogleAccountAutoSync                    # enabled=false + note（无账户）
./send_test_command.sh SetGoogleAccountAutoSync enabled=false
./send_test_command.sh SetGoogleAccountAutoSync enabled=true
```

### 5.11 无障碍快捷方式/截屏/系统升级策略（ASR-0078/0185/0186/0444）

```bash
# 1. 无障碍快捷方式（ASR-0078）：基线 → 禁用 → 系统对照 → 恢复
./send_test_command.sh IsAccessibilityShortcutDisabled           # 基线（三键空 → disabled=true）
adb shell settings put secure accessibility_shortcut "com.hmdm.testapp/com.hmdm.testapp.FakeAccessibilityService"  # 模拟用户配置目标
./send_test_command.sh IsAccessibilityShortcutDisabled           # disabled=false
./send_test_command.sh SetAccessibilityShortcutDisabled disabled=true    # channel=settings（DPM 白名单外回退直写）
adb shell settings get secure accessibility_shortcut             # 空
./send_test_command.sh SetAccessibilityShortcutDisabled disabled=false   # 恢复预置目标
adb shell settings delete secure accessibility_shortcut          # 清理模拟值，恢复基线

# 2. 截屏（ASR-0185/0186）：禁用 → dumpsys/screencap 双对照 → 恢复
./send_test_command.sh IsScreenshotsDisabled                     # false（基线）
./send_test_command.sh SetScreenshotsDisabled disabled=true      # true
adb shell dumpsys device_policy | grep -i capture                # disableScreenCapture=true, disallowed users: [-1]
adb shell screencap -p /sdcard/kilo_disabled.png && ls -la /sdcard/kilo_disabled.png   # 0 字节（FLAG_SECURE 阻断）
./send_test_command.sh SetScreenshotsDisabled disabled=false     # true
./send_test_command.sh IsScreenshotsDisabled                     # false

# 3. 在线 FOTA（ASR-0444）：禁用 → 系统对照 → 幂等 → 恢复
./send_test_command.sh IsOnlineFotaDisabled                      # disabled=false, policySet=false
./send_test_command.sh SetOnlineFotaDisabled disabled=true       # success=true, policyType=3, name=postpone（本 ROM fork 常量）
adb shell dumpsys device_policy | grep -i -A3 "System Update Policy"   # type: 3, windowStart: 0, windowEnd: 0, freezes: []
./send_test_command.sh SetOnlineFotaDisabled disabled=true       # 幂等成功
./send_test_command.sh SetOnlineFotaDisabled disabled=false      # success=true, policySet=false（策略清除）
./send_test_command.sh IsOnlineFotaDisabled                      # disabled=false
```

### 5.12 无障碍服务控制（ASR-0074/0075）

```bash
# 测试目标服务（本机）：
#   自有：com.hmdm.testapp/com.hmdm.testapp.TestAccessibilityService
#   第三方：com.wandoujia.phoenix2/com.pp.assistant.accessibility.AccessibilityService
# 系统对照三件套：settings get secure enabled_accessibility_services / accessibility_enabled / dumpsys accessibility

# 0. 基线
./send_test_command.sh GetAccessibilityServiceState              # mode=0, enabledServices=[], accessibilityEnabled=false

# 1. 免交互激活/注销（ASR-0074）：激活 → 系统三处对照 → 注销
./send_test_command.sh SetAccessibilityServiceEnabled component=com.hmdm.testapp/com.hmdm.testapp.TestAccessibilityService enabled=true
adb shell settings get secure enabled_accessibility_services     # 含自有服务
adb shell settings get secure accessibility_enabled              # 1
adb shell dumpsys accessibility | grep -A2 "Bound services"      # 含 TestAccessibilityService（免交互真实绑定）
./send_test_command.sh IsAccessibilityServiceEnabled component=com.hmdm.testapp/com.hmdm.testapp.TestAccessibilityService   # enabled=true
./send_test_command.sh SetAccessibilityServiceEnabled component=com.wandoujia.phoenix2/com.pp.assistant.accessibility.AccessibilityService enabled=true   # 第二个服务
./send_test_command.sh SetAccessibilityServiceEnabled component=com.hmdm.testapp/com.hmdm.testapp.TestAccessibilityService enabled=false  # 注销其一：总开关保持 1
./send_test_command.sh SetAccessibilityServiceEnabled component=com.wandoujia.phoenix2/com.pp.assistant.accessibility.AccessibilityService enabled=false  # 注销最后一个：总开关 0

# 2. 白名单（ASR-0075）：两服务启用 → 白名单=自有 → 模式=1 → 豌豆荚被立即移除
./send_test_command.sh SetAccessibilityServiceEnabled component=com.hmdm.testapp/com.hmdm.testapp.TestAccessibilityService enabled=true
./send_test_command.sh SetAccessibilityServiceEnabled component=com.wandoujia.phoenix2/com.pp.assistant.accessibility.AccessibilityService enabled=true
./send_test_command.sh SetAccessibilityServiceWhitelist 'components=["com.hmdm.testapp/com.hmdm.testapp.TestAccessibilityService"]'
./send_test_command.sh SetAccessibilityServicePolicyMode mode=1   # removed=[豌豆荚], applied=true
./send_test_command.sh GetAccessibilityServiceState               # enabledServices 只剩自有服务
./send_test_command.sh SetAccessibilityServiceEnabled component=com.wandoujia.phoenix2/com.pp.assistant.accessibility.AccessibilityService enabled=true  # 被策略拒绝

# 3. 黑名单 + 观察者持续执行：黑名单=自有 → 模式=2 → 模拟设置页开关被回滚
./send_test_command.sh SetAccessibilityServicePolicyMode mode=0
./send_test_command.sh SetAccessibilityServiceBlacklist 'components=["com.hmdm.testapp/com.hmdm.testapp.TestAccessibilityService"]'
./send_test_command.sh SetAccessibilityServicePolicyMode mode=2   # 自有服务被立即移除
adb shell settings put secure enabled_accessibility_services "com.hmdm.testapp/com.hmdm.testapp.TestAccessibilityService:com.wandoujia.phoenix2/com.pp.assistant.accessibility.AccessibilityService"  # 模拟用户在设置页开启（全格式）
sleep 2 && adb shell settings get secure enabled_accessibility_services   # 约 1 秒内被回滚，只剩豌豆荚
adb shell settings put secure enabled_accessibility_services "com.hmdm.testapp/.TestAccessibilityService"  # 短格式注入同样被回滚
sleep 2 && adb shell settings get secure enabled_accessibility_services   # 空（全被回滚）

# 4. 还原（必须）：模式全关 + 名单清空
./send_test_command.sh SetAccessibilityServicePolicyMode mode=0
./send_test_command.sh SetAccessibilityServiceBlacklist 'components=[]'
./send_test_command.sh SetAccessibilityServiceWhitelist 'components=[]'
./send_test_command.sh GetAccessibilityServiceState              # 基线恢复
```

注意：① 本 ROM 无 `AccessibilityManager.setEnabledAccessibilityServiceList`（Sheet1 P1 规划路径），命令恒走平台签名直写 Settings.Secure（channel=settings 为预期值）；② 组件名格式：设置页写全格式（`pkg/pkg.Class`）、命令写短格式（`pkg/.Class`），引擎统一规范化，两种格式注入均可命中；③ **MTK DuraSpeed**：对 testapp 执行 `am force-stop` 会触发其加入 DuraSpeed suppress list（`dumpsys duraspeed suppress_list`），此后 testapp 全部 manifest receiver 广播被 AMS 丢弃（IPC 失效），重启设备恢复；测试期间重启 testapp 进程请用 root `kill <pid>`（adb root 后执行）而非 force-stop；④ 策略为持久化状态（进程重启/开机经 syncPolicy 重新武装并立即执行），测试结束务必按第 4 步还原，避免遗留策略。

### 5.13 WLAN 强管控（ASR-0150/0152/0153/0155/0158/0160/0168）

```bash
# 1. AP 配置锁定（ASR-0150，DISALLOW_CONFIG_WIFI）：禁用 → 设置页封锁验证 → 恢复
./send_test_command.sh IsApConfigLockdown                       # disabled=false
./send_test_command.sh SetApConfigLockdown disabled=true
adb shell dumpsys user | grep -A5 -i restriction                 # Effective restrictions 含 no_config_wifi
adb shell am start -a android.settings.WIFI_SETTINGS             # 页面显示 "Blocked by your IT admin"
adb shell am force-stop com.android.settings                    # 关闭验证页
./send_test_command.sh SetApConfigLockdown disabled=false        # 恢复

# 2. 企业 WiFi 白名单（ASR-0152）：含当前网络 → 切换名单触发断开+拦截 → 清除恢复
./send_test_command.sh GetWifiSsidWhitelist                      # 基线 policyType=-1
./send_test_command.sh SetWifiSsidWhitelist enabled=true 'ssids=["Syrius_Guest"]'
adb shell cmd wifi status                                        # 仍连接（名单含当前网络）
./send_test_command.sh SetWifiSsidWhitelist enabled=true 'ssids=["HYX-MDM-WIFI"]'
adb shell logcat -d -s WifiService:V | grep restricted           # disconnect admin restricted network
./send_test_command.sh TryConnectOpenWifi ssid=Syrius_Guest networkId=0   # enableNetworkResult=false（拦截）
./send_test_command.sh SetWifiSsidWhitelist enabled=false        # 清除
./send_test_command.sh TryConnectOpenWifi ssid=Syrius_Guest networkId=0   # enableNetworkResult=true，恢复连接

# 3. 禁止手动添加网络（ASR-0153）：限制 → 设置页行禁用验证 → 恢复
./send_test_command.sh SetManualAddWifiDisabled disabled=true
adb shell dumpsys user | grep -A5 -i restriction                 # 含 no_add_wifi_config
adb shell am start -a android.settings.WIFI_SETTINGS             # 非当前网络行 "Not allowed by your organization"（enabled=false）
adb shell am force-stop com.android.settings
./send_test_command.sh SetManualAddWifiDisabled disabled=false   # 恢复

# 4. 禁用编辑 WLAN（ASR-0155，复用 ASR-0141 命令）：禁用 → 设置页封锁 → 恢复
./send_test_command.sh SetUserConfigWifiDisabled disabled=true
adb shell am start -a android.settings.WIFI_SETTINGS             # "Blocked by your IT admin"
adb shell am force-stop com.android.settings
./send_test_command.sh SetUserConfigWifiDisabled disabled=false

# 5. 最低安全级别（ASR-0158）：PERSONAL → 断开开放网 + 拦截 → 恢复 OPEN
./send_test_command.sh GetMinimumWifiSecurityLevel                # level=0
./send_test_command.sh SetMinimumWifiSecurityLevel level=1
adb shell dumpsys device_policy | grep -i mWifiMinimumSecurityLevel  # =1
adb shell cmd wifi status                                        # 开放网已被框架断开
./send_test_command.sh TryConnectOpenWifi ssid=Syrius_Guest networkId=0   # enableNetworkResult=false
./send_test_command.sh SetMinimumWifiSecurityLevel level=0       # 恢复
./send_test_command.sh TryConnectOpenWifi ssid=Syrius_Guest networkId=0   # 恢复连接

# 6. WLAN 直连（ASR-0160）/ 热点配置（ASR-0168）：限制 → dumpsys 对照 → 恢复
./send_test_command.sh SetWifiDirectDisabled disabled=true
adb shell dumpsys user | grep -A5 -i restriction                 # 含 no_wifi_direct
./send_test_command.sh SetWifiDirectDisabled disabled=false
./send_test_command.sh SetUserConfigTetheringDisabled disabled=true
adb shell dumpsys user | grep -A5 -i restriction                 # 含 no_config_tethering
./send_test_command.sh SetUserConfigTetheringDisabled disabled=false
```

注意：第 2/5 步会真实断开设备 WiFi 并验证拦截行为，恢复步骤会自动重新连接 `Syrius_Guest`（已保存网络）；TryConnectOpenWifi 的 `networkId` 可用 `adb shell cmd wifi list-networks` 查询（Syrius_Guest=0）。

### 5.13b WLAN 配置/接入管控（ASR-0144/0145/0147/0148/0151/0161）

```bash
# 1. 配置网络（ASR-0144/0151）：开放 / WPA2 / 企业 PEAP → 列表核对 → 删除（ASR-0145）
./send_test_command.sh ConfigureWifi ssid=HYX-MDM-OPEN securityType=0
./send_test_command.sh ConfigureWifi ssid=HYX-MDM-WIFI securityType=1 'password=HYX@12345'
./send_test_command.sh ConfigureEnterpriseWifi ssid=HYX-MDM-EAP eapMethod=0 phase2=3 identity=testuser 'anonymousIdentity=anon@example.com' 'password=testpass123'
./send_test_command.sh GetSavedWifiNetworks                       # 新网络含伴生 owe 条目（本 ROM 特性，预期）
./send_test_command.sh RemoveWifiNetwork ssid=HYX-MDM-OPEN        # 按 SSID 一次删净全部条目
./send_test_command.sh RemoveWifiNetwork ssid=HYX-MDM-EAP
./send_test_command.sh RemoveWifiNetwork ssid=HYX-MDM-WIFI

# 2. SSID 黑白名单（ASR-0147）：白名单含当前网络 → 切换名单断开 → 黑名单断开 → 恢复
./send_test_command.sh SetSsidAccessWhitelist 'ssids=["Syrius_Guest"]'
./send_test_command.sh SetSsidAccessPolicy mode=1                 # violation=none，连接保持
./send_test_command.sh SetSsidAccessWhitelist 'ssids=["HYX-MDM-WIFI"]'   # 立即断开
adb shell logcat -d | grep WifiAccessPolicyManager                # enforce(NETWORK_STATE_CHANGED): violation=ssid not in whitelist ... disconnected=true
./send_test_command.sh SetSsidAccessWhitelist 'ssids=[]'
./send_test_command.sh SetSsidAccessBlacklist 'ssids=["Syrius_Guest"]'
./send_test_command.sh SetSsidAccessPolicy mode=2                 # 断开（黑名单命中）
./send_test_command.sh SetSsidAccessBlacklist 'ssids=[]'
./send_test_command.sh SetSsidAccessPolicy mode=0                 # 恢复；设备自动重连 Syrius_Guest

# 3. MAC 黑白名单（ASR-0148）：currentBssid 核对 → 白名单保留 → 换名单断开 → 恢复
./send_test_command.sh GetMacAccessPolicy                          # currentBssid=B4:89:01:F1:C0:67（以实际为准）
./send_test_command.sh SetMacAccessWhitelist 'macs=["B4:89:01:F1:C0:67"]'
./send_test_command.sh SetMacAccessPolicy mode=1                   # violation=none，连接保持
./send_test_command.sh SetMacAccessWhitelist 'macs=["AA:BB:CC:DD:EE:FF"]'   # violation=bssid not in whitelist，断开
./send_test_command.sh SetMacAccessWhitelist 'macs=[]'
./send_test_command.sh SetMacAccessPolicy mode=0                   # 恢复

# 4. 自动连接策略（ASR-0161）：禁止 → 全量禁用+断开+无重连 → 新网络自动禁用 → 允许恢复
./send_test_command.sh GetWifiAutoConnectPolicy                    # autoConnectForbidden=false，网络全 enabled
./send_test_command.sh SetWifiAutoConnectPolicy enabled=true       # disabledNetworkIds 全部 + disconnectApplied=true
sleep 15 && adb shell dumpsys wifi | grep mWifiInfo                # <unknown ssid>（无自动重连）
./send_test_command.sh ConfigureWifi ssid=HYX-MDM-AC securityType=0
./send_test_command.sh GetSavedWifiNetworks                        # HYX-MDM-AC enabled=false（回调自动禁用）
./send_test_command.sh RemoveWifiNetwork ssid=HYX-MDM-AC
./send_test_command.sh SetWifiAutoConnectPolicy enabled=false      # 全部恢复 enabled；设备重连 Syrius_Guest
```

注意：第 2/3/4 步会真实断开设备 WiFi 并验证断连策略（框架自动重连会被回调反复断开，logcat `WifiAccessPolicyManager enforce(NETWORK_STATE_CHANGED)` 为执行证据）；每次用例结束务必恢复 mode=0 + 清空名单 + autoConnectForbidden=false，并等待设备重连 `Syrius_Guest`；本机连接建立较慢（8~25 秒），`TryConnectOpenWifi` 8 秒窗口内 connected=false 时以 `dumpsys wifi` 为准。

### 5.14 定位/被动定位/导航栏/飞行模式（ASR-0310/0311/0313/0319/0320/0321/0349）

```bash
# 1. 定位（ASR-0310/0311）：基线 → 禁用 → 系统对照 → 启用恢复 → 强制打开 → 纠正验证 → 停止
./send_test_command.sh IsLocationEnabled                      # location_mode=3, enabled=true
./send_test_command.sh SetLocationEnabled enabled=false       # location_mode=0, savedMode=3
adb shell settings get secure location_mode                    # 0
./send_test_command.sh SetLocationEnabled enabled=true         # 恢复 3
./send_test_command.sh ForceOpenLocation forceOpen=true        # forceOpen=true, location_mode=3
adb shell settings put secure location_mode 0                  # 模拟用户关闭
sleep 12 && adb shell settings get secure location_mode        # 纠正器恢复 3
./send_test_command.sh ForceOpenLocation forceOpen=false       # 停止纠正（必须）

# 2. 飞行模式（ASR-0319/0320/0321）：打开 → 系统对照 → 关闭 → 强制打开 → 纠正验证 → 停止
./send_test_command.sh IsAirplaneMode                          # airplane_mode_on=0
./send_test_command.sh SetAirplaneMode enabled=true            # airplane_mode_on=1
adb shell dumpsys wifi | grep AirplaneModeOn                   # AirplaneModeOn true（生效判定）
adb shell dumpsys telephony.registry | grep mRadioPowerState   # 射频电源按 SIM 关闭
./send_test_command.sh SetAirplaneMode enabled=false           # 恢复
./send_test_command.sh ForceOpenAirplaneMode forceOpen=true
adb shell settings put global airplane_mode_on 0
adb shell am broadcast -a android.intent.action.AIRPLANE_MODE_CHANGED --ez state false   # 模拟用户关闭
sleep 12 && adb shell settings get global airplane_mode_on     # 纠正器重开为 1
./send_test_command.sh ForceOpenAirplaneMode forceOpen=false   # 停止纠正（必须）

# 3. 被动定位（ASR-0313）：基线 → 禁止 → provider 对照 → 允许恢复 → 模式保存/恢复验证
./send_test_command.sh IsPassiveLocationAllowed              # passiveAllowed=true, location_mode=3
./send_test_command.sh SetPassiveLocationAllowed allowed=false   # location_mode=0, savedMode=3
adb shell settings get secure location_mode                  # 0
adb shell dumpsys location | grep -A1 "passive provider"     # enabled=false（被动 provider 随定位关闭）
./send_test_command.sh SetPassiveLocationAllowed allowed=true    # 恢复 savedMode=3
adb shell dumpsys location | grep -A1 "passive provider"     # enabled=true
./send_test_command.sh SetLocationMode mode=2                # 省电模式下禁止 → savedMode=2 → 允许恢复 2
./send_test_command.sh SetPassiveLocationAllowed allowed=false
./send_test_command.sh SetPassiveLocationAllowed allowed=true
adb shell settings get secure location_mode                  # 2（按模式保存/恢复）
./send_test_command.sh SetLocationMode mode=3                # 恢复高精度基线

# 4. 导航栏（ASR-0349）：禁用 → 窗口对照 → 启用恢复
./send_test_command.sh IsNavigationBarEnabled                   # navigation_visible=1, enabled=true
./send_test_command.sh SetNavigationBarEnabled enabled=false    # navigation_visible=0
adb shell dumpsys window windows | grep -A8 NavigationBar0 | grep isVisible   # false（栏隐藏）
./send_test_command.sh SetNavigationBarEnabled enabled=true     # 恢复显示（isVisible=true）
```

注意：被动定位（ASR-0313）与定位开关（ASR-0310/0311）共用 `location_policy` savedMode 账本——禁止=保存当前模式并写 0、允许=恢复保存模式（默认 3）；Android 13 无独立被动定位开关，被动 provider 随非 0 模式启用（`dumpsys location` 对照）；飞行模式开关会真实关闭射频/蓝牙（等待 3~5 秒异步生效）；本 ROM 飞行模式下 Wi-Fi 保持启用（MTK 行为），勿以 Wi-Fi 状态判定；强制打开结束后务必 `forceOpen=false` 清标志，否则纠正器持续生效。

### 5.15 NFC（ASR-0315/0316/0317）

```bash
# 0. 硬件判定：无 android.hardware.nfc 特性 / dumpsys nfc 报 Can't find service 即为无 NFC 设备，
#    以下所有命令预期返回 supported=false（非缺陷）；本流程在有 NFC 硬件的设备上执行
adb shell pm list features | grep nfc

# 1. NFC 开关（ASR-0315/0316）：基线 → 关闭 → 系统对照 → 打开
./send_test_command.sh IsNfcEnabled                        # state=ON, enabled=true
./send_test_command.sh SetNfcEnabled enabled=false         # state=OFF, nfc_on=0
adb shell settings get global nfc_on                        # 0
./send_test_command.sh SetNfcEnabled enabled=true           # state=ON, nfc_on=1
adb shell settings get global nfc_on                        # 1

# 2. 强制打开（ASR-0317）：开启 → 用户关闭后被纠正 → 停止
./send_test_command.sh ForceOpenNfc forceOpen=true          # forceOpen=true, state=ON
./send_test_command.sh SetNfcEnabled enabled=false          # 模拟用户关闭
sleep 12 && ./send_test_command.sh IsNfcEnabled             # 纠正器重新打开：state=ON
./send_test_command.sh ForceOpenNfc forceOpen=false         # 停止纠正（必须）
```

注意：NFC 开关经平台签名 Launcher 反射 `NfcAdapter.enable/disable`（`WRITE_NFC_SETTINGS` 签名权限）；强制打开结束后务必 `forceOpen=false` 清标志；无 NFC 硬件设备上强制打开命令不持久化标志、不启动纠正器。

### 5.16 移动数据（ASR-0275/0276/0277/0279）

```bash
# 0. SIM 判定：无 SIM（gsm.sim.state=ABSENT）时 Set 命令 callResult=true 但状态不翻转、
#    success=false（受限验证边界，非缺陷）；以下流程在插 SIM 设备上执行
adb shell getprop gsm.sim.state

# 1. 移动数据开关（ASR-0275/0276）：基线 → 关闭 → 开启
./send_test_command.sh IsMobileDataEnabled                # enabled/dataEnabled, mobile_data 镜像对照
./send_test_command.sh SetMobileDataEnabled enabled=false # 关闭：dataEnabled=false
./send_test_command.sh SetMobileDataEnabled enabled=true  # 开启：dataEnabled=true

# 2. 强制开启（ASR-0277）：开启 → 用户关闭后被纠正 → 停止
./send_test_command.sh ForceOpenMobileData forceOpen=true  # forceOpen=true, dataEnabled=true
./send_test_command.sh SetMobileDataEnabled enabled=false  # 模拟用户关闭
sleep 12 && ./send_test_command.sh IsMobileDataEnabled     # 纠正器重新打开：dataEnabled=true
./send_test_command.sh ForceOpenMobileData forceOpen=false  # 停止纠正（必须）

# 3. 强制关闭（ASR-0276）：关闭 → 用户打开后被纠正 → 停止
./send_test_command.sh ForceCloseMobileData forceClose=true  # forceClose=true, dataEnabled=false
./send_test_command.sh SetMobileDataEnabled enabled=true     # 模拟用户打开
sleep 12 && ./send_test_command.sh IsMobileDataEnabled       # 纠正器重新关闭：dataEnabled=false
./send_test_command.sh ForceCloseMobileData forceClose=false # 停止纠正（必须）

# 4. 状态不允许变更（ASR-0279）：锁定当前开启态 → 用户关闭被回滚 → 解锁
./send_test_command.sh SetMobileDataStateLocked locked=true  # locked=true, baseline=true（快照）
./send_test_command.sh SetMobileDataEnabled enabled=false    # 模拟用户关闭
sleep 12 && ./send_test_command.sh IsMobileDataStateLocked   # 回滚：dataEnabled=true（=baseline）
./send_test_command.sh SetMobileDataStateLocked locked=false # 解锁（必须）
```

注意：移动数据主开关经平台签名 Launcher 反射 `TelephonyManager.setDataEnabled`（`MODIFY_PHONE_STATE` 签名权限；本 ROM 无 `ConnectivityManager.setMobileDataEnabled`，原规划路径经 dex 反编译核验不存在）；多标志优先级：强制关闭 > 强制开启 > 状态锁定；强制/锁定用例结束后务必依次清空三个标志，否则纠正器持续生效；无 SIM 设备上机制核验看 logcat `MobileDataPolicyManager corrector: ...` 与 `/data/data/com.hmdm.launcher/shared_prefs/mobile_data_policy.xml`。

### 5.17 热点/网络共享（ASR-0167/0169/0398-0405）

```bash
# 0. 硬件/前置判定：WLAN 硬件（热点）、USB 连接（USB 共享）、蓝牙开启
#    （adb shell cmd bluetooth_manager enable）为对应用例前置；不具备时命令如实
#    上报 success=false/supported=false，不 crash
adb shell dumpsys connectivity tethering   # tethering 状态对照
adb shell cmd bluetooth_manager enable     # 蓝牙共享用例前置

# 1. WiFi 个人热点开关（ASR-0167/0169）：打开 → 查询 → 关闭
./send_test_command.sh IsHotspotEnabled                 # 基线：wifiTethering=false
./send_test_command.sh SetHotspotEnabled enabled=true   # 打开：hotspotActive=true
./send_test_command.sh IsHotspotEnabled                 # wifiTethering=true
./send_test_command.sh SetHotspotEnabled enabled=false  # 关闭：wifiTethering=false

# 2. USB 共享静默开关（ASR-0401）：打开（无弹窗）→ 查询 → 关闭
./send_test_command.sh SetUsbTetheringEnabled enabled=true   # usbTethering=true（静默）
./send_test_command.sh IsUsbTetheringEnabled                 # 查询
./send_test_command.sh SetUsbTetheringEnabled enabled=false  # usbTethering=false

# 3. 蓝牙共享静默开关（ASR-0404）：打开（无弹窗）→ 查询 → 关闭
./send_test_command.sh SetBluetoothTetheringEnabled enabled=true   # bluetoothTethering=true
./send_test_command.sh IsBluetoothTetheringEnabled                 # 查询
./send_test_command.sh SetBluetoothTetheringEnabled enabled=false  # 关闭

# 4. 网络共享总开关（ASR-0398）：禁用（停止全部共享）→ 用户打开被纠正 → 启用
./send_test_command.sh SetTetheringDisabled disabled=true   # 停止全部活动共享
./send_test_command.sh SetHotspotEnabled enabled=true       # 模拟用户打开热点
sleep 12 && ./send_test_command.sh IsTetheringDisabled      # 纠正器重新关闭：wifiTethering=false
./send_test_command.sh SetTetheringDisabled disabled=false  # 启用（必须）

# 5. 禁止 USB 共享（ASR-0399）：禁止 → 用户打开被纠正 → 允许
./send_test_command.sh SetUsbTetheringForbidden forbidden=true   # 停止 USB 共享
./send_test_command.sh SetUsbTetheringEnabled enabled=true       # 模拟用户打开
sleep 12 && ./send_test_command.sh IsUsbTetheringForbidden       # 纠正器重新关闭：usbTethering=false
./send_test_command.sh SetUsbTetheringForbidden forbidden=false  # 允许（必须）

# 6. 禁止 WLAN 共享（ASR-0402）：禁止 → 用户打开被纠正 → 允许
./send_test_command.sh SetWifiTetheringForbidden forbidden=true   # 停止热点
./send_test_command.sh SetHotspotEnabled enabled=true             # 模拟用户打开
sleep 12 && ./send_test_command.sh IsWifiTetheringForbidden       # 纠正器重新关闭
./send_test_command.sh SetWifiTetheringForbidden forbidden=false  # 允许（必须）

# 7. 禁止蓝牙共享（ASR-0403）：禁止 → 用户打开被纠正 → 允许
./send_test_command.sh SetBluetoothTetheringForbidden forbidden=true   # 停止蓝牙共享
./send_test_command.sh SetBluetoothTetheringEnabled enabled=true       # 模拟用户打开
sleep 12 && ./send_test_command.sh IsBluetoothTetheringForbidden       # 纠正器重新关闭
./send_test_command.sh SetBluetoothTetheringForbidden forbidden=false  # 允许（必须）

# 8. 用户修改共享 Settings 限制（ASR-0400/0405，复用 ASR-0168 引擎）：禁止 → 查询 → 允许
./send_test_command.sh SetUserConfigTetheringDisabled disabled=true   # no_config_tethering 生效
./send_test_command.sh IsUserConfigTetheringDisabled                 # disabled=true
./send_test_command.sh SetUserConfigTetheringDisabled disabled=false  # 允许（必须）
```

注意：热点/共享开关经平台签名 Launcher 反射 `TetheringManager`（@SystemApi，不在公开 SDK；`NETWORK_SETTINGS`/`TETHER_PRIVILEGED` 签名权限 manifest 新增声明自动授予）；打开为静默（showProvisioningUi=false，无弹窗）；ASR-0398 总开关隐含全部三种共享禁止，`disabled=false` 后各类型自身禁止标志仍生效；禁止类用例结束后务必清空对应标志与总开关（否则纠正器持续生效）；机制核验看 logcat `TetheringPolicyManager corrector: ...` 与 `/data/data/com.hmdm.launcher/shared_prefs/tethering_policy.xml`；UI 按钮在 testapp 主页 "Hotspot &amp; sharing" 页面。**本机（MT6771 银星 ROM）限制**：蓝牙共享打开在本 ROM 框架级失败（networkstack `getProfileProxy(PAN)` 卡住、无回调），蓝牙共享用例（打开/关闭/禁止纠正）在本机仅能验证机制路径（命令如实上报 success=false），真实行为需具备 BT 共享能力的设备；本机 Settings 无"热点和网络共享"入口，系统状态对照以 `ip addr show ap0/rndis0` 与 `dumpsys tethering` 为准。

### 5.18 权限/AppOps（ASR-0043/0044/0045/0046/0149）

```bash
# 1. 画中画（ASR-0043）：禁用 → 查询 → 启用（AppOps 读回核对，dumpsys appops 对照）
./send_test_command.sh SetPictureInPictureDisabled packageName=com.hmdm.testapp disabled=true
./send_test_command.sh IsPictureInPictureDisabled packageName=com.hmdm.testapp
./send_test_command.sh SetPictureInPictureDisabled packageName=com.hmdm.testapp disabled=false  # 恢复（必须）

# 2. 写设置（ASR-0044）：禁用 → 真实写入被拒 → 启用 → 真实写入成功
./send_test_command.sh SetWriteSettingsDisabled packageName=com.hmdm.testapp disabled=true
./send_test_command.sh TryWriteSettings          # SecurityException（wrote=false）
./send_test_command.sh SetWriteSettingsDisabled packageName=com.hmdm.testapp disabled=false  # 恢复（必须）
./send_test_command.sh TryWriteSettings          # wrote=true（screen_brightness 原值回写）

# 3. 通知监听服务（ASR-0045）：授予 → 查询 → 撤销
./send_test_command.sh SetNotificationListenerAccessGranted component=com.hmdm.testapp/com.hmdm.testapp.TestNotificationListenerService granted=true
./send_test_command.sh IsNotificationListenerAccessGranted component=com.hmdm.testapp/com.hmdm.testapp.TestNotificationListenerService
./send_test_command.sh SetNotificationListenerAccessGranted component=com.hmdm.testapp/com.hmdm.testapp.TestNotificationListenerService granted=false  # 恢复（必须）

# 4. WLAN 权限黑名单（ASR-0149）：模式 → 名单 → 效果 → 恢复
./send_test_command.sh SetWifiPermissionPolicyMode mode=2
./send_test_command.sh SetWifiPermissionBlacklist 'packageNames=["com.hmdm.testapp"]'
./send_test_command.sh TryChangeWifiState        # setWifiEnabled=false（被拒）
./send_test_command.sh TryAddWifiNetwork         # addNetworkSucceeded=false
./send_test_command.sh SetWifiPermissionBlacklist 'packageNames=[]'   # 恢复（必须）
./send_test_command.sh SetWifiPermissionPolicyMode mode=0             # 恢复（必须）

# 5. USB 权限（ASR-0046，需已连接 USB host 设备；无设备时命令如实报错）
./send_test_command.sh GetUsbDeviceList
./send_test_command.sh GrantUsbPermission packageName=com.hmdm.testapp deviceName=first
./send_test_command.sh CheckUsbPermission
```

注意：本批命令均为免交互开关（AppOps 模式与监听授权由框架持久化，无需进程重启/开机重新武装）；AppOps 效果核验以 `adb shell dumpsys appops`（op 模式 + Access/Reject 历史）与 `adb shell cmd appops get com.hmdm.testapp <OP>` 为准；**本 ROM op 数值码被厂商重排**（OP_WRITE_SETTINGS=23≠AOSP 22），引擎字符串变体优先已适配（详见技术设计文档 2.1）；`TryEnterPip` 需在测试页点按按钮执行（IPC 返回 needsUi 提示）；WLAN 黑名单/监听授权用例结束后务必恢复（黑名单清空 + mode=0、granted=false），否则策略持续生效。

### 5.19 设备管理（ASR-0080/0081/0083/0085）

```bash
# 0. 基线
./send_test_command.sh IsDeviceOwner                 # isDeviceOwner=true（Launcher 为 DO）
./send_test_command.sh IsProfileOwner                # isProfileOwner=false

# 1. 免交互激活/注销（ASR-0080）：激活 → 查询 → 端到端本地探测 → 注销 → 查询
./send_test_command.sh SetDeviceAdminActive component=com.hmdm.testapp/com.hmdm.testapp.TestAdminReceiver active=true
./send_test_command.sh IsDeviceAdminActive component=com.hmdm.testapp/com.hmdm.testapp.TestAdminReceiver
./send_test_command.sh QueryOwnAdminLocal            # 本应用 dpm.isAdminActive=true
./send_test_command.sh SetDeviceAdminActive component=com.hmdm.testapp/com.hmdm.testapp.TestAdminReceiver active=false
./send_test_command.sh IsDeviceAdminActive component=com.hmdm.testapp/com.hmdm.testapp.TestAdminReceiver
./send_test_command.sh QueryOwnAdminLocal            # false

# 2. 强制激活（ASR-0081）：未激活态激活 → 已激活态重复激活（幂等）
./send_test_command.sh ForceSetDeviceAdminActive component=com.hmdm.testapp/com.hmdm.testapp.TestAdminReceiver active=true
./send_test_command.sh ForceSetDeviceAdminActive component=com.hmdm.testapp/com.hmdm.testapp.TestAdminReceiver active=true
./send_test_command.sh ForceSetDeviceAdminActive component=com.hmdm.testapp/com.hmdm.testapp.TestAdminReceiver active=false

# 3. 注销 DO admin 保护：返回 owner admin cannot be removed，DO 不变
./send_test_command.sh SetDeviceAdminActive component=com.hmdm.launcher/com.hmdm.launcher.AdminReceiver active=false

# 4. 已设 DO 再设置：返回 already set（不落文件通道）
./send_test_command.sh SetDeviceOwner packageName=com.hmdm.launcher

# 5. 删除 DO（破坏性，需确认）：binder 清内存 + 写 device_owner_2.xml → 查询 → dpm list-owners 空
./send_test_command.sh DeleteDeviceOwner
./send_test_command.sh IsDeviceOwner                 # isDeviceOwner=false, fileHasOwner=false
adb shell dpm list-owners                            # 空
adb shell cat /data/system/device_owner_2.xml        # 无 device-owner 元素

# 6. 重设 DO（需确认）：引擎双通道如实拒绝（binder 已 set-up + 本 ROM SELinux 拒绝文件写入）→ adb root 写文件 → stop/start 生效
./send_test_command.sh SetDeviceOwner packageName=com.hmdm.launcher component=com.hmdm.launcher/com.hmdm.launcher.AdminReceiver
#   → success=false，error 附 root 提示（binder 通道：device already set-up；文件通道：SELinux 拒绝）
adb root
adb shell "cat > /data/system/device_owner_2.xml << 'EOF'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<root>
<device-owner package=\"com.hmdm.launcher\" name=\"\" component=\"com.hmdm.launcher/com.hmdm.launcher.AdminReceiver\" />
<device-owner-context userId=\"0\" />
</root>
EOF"
adb shell stop && adb shell start                       # 等待恢复（约 30~60s）
adb shell dpm list-owners                                # 恢复 DeviceOwner,Affiliated
./send_test_command.sh IsDeviceOwner                     # isDeviceOwner=true
# 备选：设备无账户时也可用 adb shell dpm set-device-owner com.hmdm.launcher/.AdminReceiver 直接重设（实测可用）

# 7. ProfileOwner（ASR-0085）：user 0 演示 post-setup 限制 → 新用户闭环
./send_test_command.sh SetProfileOwner component=com.hmdm.launcher/com.hmdm.launcher.AdminReceiver
#    → success=false（用户 0 已有 DO：前置互斥检查；binder 通道对非 supervision 组件同样拒绝）
adb shell pm create-user testpo                      # 记下 userId=N
adb shell pm install-existing --user N com.hmdm.launcher
./send_test_command.sh SetProfileOwner component=com.hmdm.launcher/com.hmdm.launcher.AdminReceiver userId=N
./send_test_command.sh IsProfileOwner userId=N       # isProfileOwner=true
adb shell dumpsys device_policy | grep -i "profile owner"
adb shell am start-user N                            # 用户 N 运行解锁
./send_test_command.sh DeleteProfileOwner component=com.hmdm.launcher/com.hmdm.launcher.AdminReceiver userId=N
#    → 跨用户删除：引擎走文件通道（binderError 如实），本 ROM 文件通道被 SELinux 拒绝（fileError）
adb root && adb shell rm /data/system/users/N/profile_owner.xml
adb shell stop && adb shell start                    # 等待恢复（约 30~60s）
./send_test_command.sh IsProfileOwner userId=N       # isProfileOwner=false
adb shell pm remove-user N                           # 清理测试用户
```

注意：本批命令直接操纵系统 owner 状态（DPMS 内存态 + `/data/system/device_owner_2.xml` / `/data/system/users/<id>/profile_owner.xml` 磁盘态）；删除/重设 DO 与文件通道用例执行前须确认，重设失败可用 `adb root` 手工恢复（删除 `device_owner_2.xml` 后 `stop`/`start`，或设备无账户时 `adb shell dpm set-device-owner` 直接重设——实测可用）；删除 DO 后 Launcher 的 DPM 特权接口不可用，闭环内须完成重设与重启恢复；**本 ROM SELinux 拒绝 system_app 访问 /data/system**（dmesg `avc: denied ... system_data_file`）：引擎的 `device_owner_2.xml`/`profile_owner.xml` 文件通道在本 ROM 被拒（命令如实返回 fileError/fileWritten=false，不伪装成功），文件级操作需 `adb root`（`/system/xbin/su` 仅 root/shell 组可执行）；`clearProfileOwner` 仅作用于调用方所在用户（跨用户删除 PO 引擎自动走文件通道并防护调用方用户 DO admin）；`DeleteDeviceOwner`/`DeleteProfileOwner` 执行前自动备份目标文件为 `.bak`（ABX 原始字节）；机制核验看 logcat `DeviceAdminPolicyManager` 与命令返回的 channel/restartRequired/fileError 字段。

### 5.20 组件/默认应用（ASR-0019/0087/0089/0094）

```bash
# 1. 组件禁用/启用（ASR-0019）：基线 → 禁用 → 真实启动被拒 → 恢复
./send_test_command.sh IsComponentEnabled packageName=com.hmdm.testapp component=com.hmdm.testapp/com.hmdm.testapp.StatsQueryTestActivity
./send_test_command.sh SetComponentEnabled packageName=com.hmdm.testapp component=com.hmdm.testapp/com.hmdm.testapp.StatsQueryTestActivity enabled=false
adb shell am start -n com.hmdm.testapp/.StatsQueryTestActivity    # Error type 3（ActivityNotFoundException）
./send_test_command.sh ResolveComponent component=com.hmdm.testapp/com.hmdm.testapp.StatsQueryTestActivity   # resolved=false
./send_test_command.sh SetComponentEnabled packageName=com.hmdm.testapp component=com.hmdm.testapp/com.hmdm.testapp.StatsQueryTestActivity enabled=true   # 恢复（必须）
adb shell am start -n com.hmdm.testapp/.StatsQueryTestActivity    # 正常启动

# 2. 整应用禁用/恢复（ASR-0019；禁用后 testapp IPC 失效，恢复走 Launcher 广播通道）
./send_test_command.sh SetComponentEnabled packageName=com.hmdm.testapp enabled=false
adb shell am start -n com.hmdm.testapp/.MainActivity             # Error type 3
./send_test_broadcast.sh SetComponentEnabled packageName=com.hmdm.testapp enabled=true    # 恢复（必须，Launcher 通道）
adb shell am start -n com.hmdm.testapp/.MainActivity             # 正常启动

# 3. 默认短信/拨号（ASR-0087/0089）：查询 → 设置合格包 → 非合格包被框架拒绝
./send_test_command.sh GetDefaultSmsApp                          # holder=com.android.mms
./send_test_command.sh SetDefaultSmsApp packageName=com.android.mms      # success=true（幂等重设）
./send_test_command.sh SetDefaultSmsApp packageName=com.hmdm.testapp     # success=false（框架资格校验拒绝）
./send_test_command.sh GetDefaultDialerApp                        # holder=com.android.dialer
./send_test_command.sh SetDefaultDialerApp packageName=com.android.dialer

# 4. 默认 Assistant（ASR-0094）：查询（本机无合格助理应用，持有者为空）
./send_test_command.sh GetDefaultAssistant                        # holders=[]
./send_test_command.sh SetDefaultAssistant packageName=com.mediatek.voicecommand   # success=false（框架拒绝）
./send_test_command.sh GetAssistantSetting                        # assistant=""
```

注意：本批命令由 Launcher 侧实现（见 `documents/technical-documentation/DefaultAppComponentControl-ASR-0019-0087-0089-0094-TechnicalDesign.md`）；组件/整应用禁用状态由 PMS 持久化（重启保持），用例结束必须恢复启用；**整应用禁用后 testapp 广播接收器失效，恢复只能走 Launcher 广播通道**（`./send_test_broadcast.sh`）或 `adb shell pm enable com.hmdm.testapp`；**本 ROM RoleManager 无同步 setRoleHolder**（Sheet1 规划路径，运行时方法集 dump 核验），引擎走异步 `addRoleHolderAsUser` 反射通道（channel=addRoleHolderAsUser），目标包经框架资格校验（非合格包 success=false、logcat "not qualified ... due to missing ..." 可对照）；ASSISTANT 角色本机无合格应用（查询恒空、设置恒被拒为预期），设置到合格助理应用需具备相应应用的设备；测试期间重启 testapp 进程请用 root `kill <pid>`（force-stop 触发 DuraSpeed suppress list 导致 IPC 失效，重启设备恢复）。

### 5.21 数据/存储/截屏/用户（ASR-0125/0127/0187/0197/0326/0385/0386）

```bash
# 1. 应用数据备份/恢复（ASR-0125）：探针写入 → 备份 → 改值 → 恢复 → 探针复核
./send_test_command.sh WriteDataProbe value=mdm-data-probe-v1
./send_test_command.sh ReadDataProbe                                  # value=mdm-data-probe-v1
./send_test_command.sh BackupAppData packageName=com.hmdm.testapp file=/sdcard/MDM/backup/testapp_v1.ab
#   ↑ 设备端弹出备份确认界面后点按 BACK UP MY DATA：
#     adb shell uiautomator dump /sdcard/ui.xml   （查找 text="BACK UP MY DATA" 的 bounds）
#     adb shell input tap <cx> <cy>               （本机为 540 1498）
./send_test_command.sh WriteDataProbe value=mdm-data-probe-v2
./send_test_command.sh ReadDataProbe                                  # value=mdm-data-probe-v2
./send_test_command.sh RestoreAppData file=/sdcard/MDM/backup/testapp_v1.ab
#   ↑ 点按 RESTORE MY DATA（同上）；恢复期间 testapp 进程被框架强杀（预期），
#     结果以 Launcher 侧日志为准：adb logcat -s HYX-MDM-APP:I | grep restoreAppData
./send_test_command.sh ReadDataProbe                                  # value=mdm-data-probe-v1（恢复成功）

# 2. 清除应用缓存（ASR-0127）：探针写入 → 清除 → 探针消失
./send_test_command.sh WriteCacheProbe value=mdm-cache-probe-v1
./send_test_command.sh CheckCacheProbe                                # exists=true
./send_test_command.sh ClearAppCache packageName=com.hmdm.testapp     # success=true（verified=true；本 ROM 回调不触发）
./send_test_command.sh CheckCacheProbe                                # exists=false

# 3. 截屏（ASR-0187）
./send_test_command.sh TakeScreenshot                                 # 默认尺寸 PNG（/sdcard/Pictures/MDM/）
./send_test_command.sh TakeScreenshot file=/sdcard/Pictures/MDM/test_720x1280.png width=720 height=1280

# 4. 存储卷（ASR-0197/0326；本机无外置卷，命令如实返回 no removable volume）
./send_test_command.sh GetStorageVolumes
./send_test_command.sh UnmountUsbStorage
./send_test_command.sh FormatExternalSd

# 5. 用户创建/删除（ASR-0385/0386）
./send_test_command.sh GetUserList                                    # 仅 Owner
./send_test_command.sh CreateUser name=mdm_test_user                  # userId=10（示例）
./send_test_command.sh GetUserList                                    # 含 mdm_test_user
./send_test_command.sh DeleteUser userId=10                           # 删除并轮询确认
./send_test_command.sh DeleteUser userId=0                            # 拒绝（主用户）
```

注意：本批命令由 Launcher 侧实现（见 `documents/technical-documentation/DataStorageScreenshotUserControl-ASR-0125-0127-0187-0197-0326-0385-0386-TechnicalDesign.md`）；**备份/恢复需屏幕点亮且无锁屏**（backupconfirm 确认界面无法在锁屏之上显示），确认界面 60s 不点按即取消；**本 ROM SELinux 拒绝 system_server 读写 /sdcard**，备份文件先落 Launcher 数据目录再复制公开副本（命令返回 file 与 publicFile/publicCopy 两个路径）；恢复用例中 testapp 进程被备份框架强杀为预期行为；**勿对 testapp 使用 `am force-stop`**（触发 MTK DuraSpeed suppress list 使 IPC 广播失效，已抑制需重启设备清空）；用户测试结束后必须删除创建的测试用户（`DeleteUser`）。

### 5.22 电源/Doze/语音助手/有线网卡（ASR-0415/0416/0373/0374/0420/0424）

```bash
# 0. 前置：唤醒/休眠用例需先关闭充电保持亮屏（本机默认 stayon=7 会抑制休眠），结束后恢复
adb shell svc power stayon false
adb shell dumpsys power | grep mWakefulness      # 唤醒态对照

# 1. 唤醒/休眠（ASR-0415/0416）：休眠 → 查询 → 唤醒 → 查询（闭环）
./send_test_command.sh GetPowerState             # interactive=true, wakefulness=Awake
./send_test_command.sh GoToSleep                 # success=true, interactiveAfter=false, Asleep
./send_test_command.sh GetPowerState             # interactive=false, wakefulness=Asleep
./send_test_command.sh WakeUp                    # success=true, interactiveAfter=true, Awake
./send_test_command.sh GetPowerState             # 恢复 Awake（必须，保持设备亮屏）
adb shell svc power stayon true                  # 恢复前置

# 2. Doze 禁止/恢复（ASR-0373）：禁止 → 系统对照 → 恢复
./send_test_command.sh IsDozeDisabled            # 基线 disabled=false, inactive_to=+30m（ROM 默认）
./send_test_command.sh SetDozeDisabled disabled=true
adb shell cmd device_config get device_idle inactive_to    # 604800000（标志读回）
adb shell dumpsys deviceidle | grep inactive_to            # +7d0h0m0s0ms（已消费）
./send_test_command.sh SetDozeDisabled disabled=false       # 恢复（必须）：常量回 ROM 默认

# 3. Doze 白名单（ASR-0374）：添加 → 系统对照 → 清空
./send_test_command.sh SetDozeWhitelist 'packageNames=["com.hmdm.testapp","com.android.settings"]'
adb shell cmd deviceidle whitelist                      # 系统清单含两包（对照）
./send_test_command.sh GetDozeWhitelist                 # requested 两包 whitelisted=true
./send_test_command.sh SetDozeWhitelist 'packageNames=[]'   # 清空（必须）

# 4. 语音助手（ASR-0420）：禁用 → 系统对照 → 恢复
./send_test_command.sh IsVoiceAssistantDisabled         # 基线 disabled=false
./send_test_command.sh SetVoiceAssistantDisabled disabled=true
adb shell settings get secure voice_interaction_service   # 空（禁用）
./send_test_command.sh SetVoiceAssistantDisabled disabled=false   # 恢复（必须）

# 5. 有线网卡（ASR-0424）：DHCP → 静态 → 查询 → 启停 → 恢复（无网线时 available=false 为如实上报）
./send_test_command.sh GetEthernetConfig                # 基线 config.mode=dhcp, available=false
./send_test_command.sh SetEthernetConfig mode=dhcp      # success=true, applied.mode=dhcp
./send_test_command.sh SetEthernetConfig mode=static ipAddress=192.168.10.100 prefixLength=24 gateway=192.168.10.1 dns1=8.8.8.8
./send_test_command.sh GetEthernetConfig                # applied 含静态 IP/前缀/网关/DNS（读回一致）
./send_test_command.sh SetEthernetEnabled enabled=false # dumpsys ethernet Ethernet State=disabled
./send_test_command.sh IsEthernetEnabled                # enabled=false
./send_test_command.sh SetEthernetEnabled enabled=true  # 恢复 enabled=true（必须）
./send_test_command.sh SetEthernetConfig mode=dhcp      # 恢复 DHCP（必须）
```

注意：本批命令由 Launcher 侧实现（见 `documents/technical-documentation/PowerDozeControl-ASR-0415-0416-0373-0374-0420-0424-TechnicalDesign.md`）；唤醒/休眠经 @hide `PowerManager.wakeUp/goToSleep`（DEVICE_POWER 签名权限，manifest 既有声明；本 ROM 熄屏后 wakefulness 为 Dozing，以 interactive 为主判据；stayon=true 不抑制显式休眠）；**Doze 开关经 `cmd device_config put device_idle` 覆盖五个过渡超时标志（本 ROM DeviceIdleController 从 DeviceConfig 读取，AOSP 标准键 `device_idle_constants` 不被消费）**；**本 ROM PowerWhitelistManager 为 fork 版方法集**（`addToWhitelist(String)`/`removeFromWhitelist(String)`，AOSP 式 `setAppIdWhitelist(int,boolean)` 不存在，引擎运行时方法发现三级回退，channel 字段附报）；Doze 白名单与 ASR-0016/0030 耗电白名单**共用系统同一清单**（doze 替换时对耗电策略仍持久化持有的包不移除、kept 字段如实返回）；**Launcher 侧 TestBroadcast 已加 DUMP 权限门禁**（第三方应用无法驱动测试广播，adb shell 通道不受影响）；以太网经 `EthernetManager`（@SystemApi，本 ROM 位于 APEX framework-connectivity-t.jar，manifest 新增 `MANAGE_ETHERNET_NETWORKS` 声明自动授予；静态配置全量读回核对、代理排除列表 StringSet 持久化重启往返）；**本机当前无网线**（ETH 接口未出现），配置存储/读回/启停/持久化可全量验证，真实 DHCP/静态网络建立需插网线；语音助手禁用=置空 `voice_interaction_service`（本机出厂即为空串且无合格 Assistant 角色持有者，实测完全禁用）；用例结束后按第 5 步恢复 DHCP/启用与第 2~4 步恢复策略，避免残留。

## 6. 注意事项

- **等效性保证**：UI 按钮与 IPC 事件共用 `TestActions.execute()` 同一入口，新增测试功能时只改 `TestActions`，UI 与 IPC 自动同步；
- **无效输入**：缺少必填参数时返回 `"missing parameter: <key>"`；未知事件返回 `"unknown event: ..."`；`ListEvents` 可随时查询目录；
- **执行时长**：`am broadcast` 等待结果约 10 秒。`TryOpenCamera` 最长 5 秒、`TryRecordAudio` 约 1 秒，均在窗口内；`TryConnectOpenWifi` 内部轮询最长 8 秒（连接建立可能慢于此窗口，以随后 `cmd wifi status` 为准）；`TestCommandReceiver` 为单线程执行，并发命令排队；
- **权限类事件**（`RequestPostNotificationsPermission`、`RequestSensorPermissions`）为 UI 专属，IPC 调用返回引导信息，等价操作为 4.4 节命令；
- **平台签名部署（2026-08-04 起）**：testapp 现与 Launcher 同平台密钥签名（普通应用拿不到本 ROM 的 `CHANGE_WIFI_STATE` 签名权限，WLAN 框架执行验证需要）；换签名重装前须先解除安装白名单卸载锁（`./send_test_broadcast.sh SetUninstallBlocked packageName=com.hmdm.testapp canUninstall=true`），否则 `adb uninstall` 报 `DELETE_FAILED_OWNER_BLOCKED`；Launcher 白名单会在重装后自动重新加锁；
- **进程绑定**：ApiService 连接在进程内常驻（`ApiHolder` 单例），测试过程中请勿强杀 testapp 进程（如需重连，进程重启后自动重新绑定）；重装 Launcher（`adb install -r`）会结束其进程且不自动重启，需重新拉起 HOME（`am start -a android.intent.action.MAIN -c android.intent.category.HOME`）并 `am force-stop com.hmdm.testapp` 重建绑定，否则返回 `RESULT:null`；
- **负数字符串化**：`send_test_command.sh` 仅对纯数字透传，负数（如 `mode=-1`）会作为字符串发送，命令侧已兼容数字字符串解析；
- 需求文档与对照表（`安卓系统软件需求.md`、`Sheet1需求完成情况对照.md`）不随本指南修改。

### 5.23 蓝牙管控（ASR-0174/0175/0176/0178/0180/0181）

```bash
# 0. 前置：开启蓝牙（出厂默认关闭）
./send_test_command.sh SetBlueOpen open=true             # 蓝牙开启
./send_test_command.sh GetBluetoothStatus                # 基线：五项标志 false、scanMode=connectable

# 1. 可发现/有限可发现禁止（ASR-0180/0181）：禁止 → 探测回滚 → 恢复
./send_test_command.sh SetDiscoverableForbidden disabled=true       # success=true
./send_test_command.sh TrySetDiscoverable                 # 本 ROM 需 BLUETOOTH_PRIVILEGED：SecurityException 如实上报
./send_test_command.sh TryStartDiscovery                  # 本 ROM 普通应用 discovery 被拒：discoveryStarted=false
# 回滚验证（真实用户路径）：确认 REQUEST_DISCOVERABLE 对话框（uiautomator dump 定位 ALLOW 后 input tap）
adb shell am start -a android.bluetooth.adapter.action.REQUEST_DISCOVERABLE --ei android.bluetooth.adapter.extra.DISCOVERABLE_DURATION 120
# → 扫描模式短暂 discoverable 后被拉回 connectable（logcat enforceDiscoverable(SCAN_MODE_CHANGED) ok=true）
./send_test_command.sh IsDiscoverableForbidden            # discoverableForbidden=true
./send_test_command.sh SetLimitedDiscoverableForbidden disabled=true   # 独立标志（ASR-0181）
./send_test_command.sh SetDiscoverableForbidden disabled=false        # 恢复（必须）
./send_test_command.sh SetLimitedDiscoverableForbidden disabled=false # 恢复（必须）

# 2. 蓝牙页面可用（ASR-0174）：禁用 → 入口探测 → 恢复
./send_test_command.sh SetBluetoothPageDisabled disabled=true    # readBack=disabled
./send_test_command.sh TryOpenBluetoothSettings          # started=false, blocked=true（ActivityNotFoundException）
./send_test_command.sh SetBluetoothPageDisabled disabled=false   # readBack=default（恢复，必须）

# 3. 文件传输禁止（ASR-0176）：禁止 → 组件核对 → 分享解析 → 恢复
./send_test_command.sh ResolveBluetoothShare             # 基线 resolved=true（BluetoothOppLauncherActivity）
./send_test_command.sh SetBluetoothFileTransferDisabled disabled=true   # components 全部 disabled
./send_test_command.sh ResolveBluetoothShare             # resolved=false（OPP 组件禁用生效）
adb shell cmd package dump com.android.bluetooth | grep -i opp        # enabled=2（DISABLED）对照
./send_test_command.sh SetBluetoothFileTransferDisabled disabled=false  # 恢复（必须）

# 4. SCO 通话禁止（ASR-0178）：禁止 → 查询 → 恢复（本机无 SIM/耳机，applied=false 为如实上报）
./send_test_command.sh SetScoCallDisabled disabled=true   # scoCallForbidden=true, callActive=false
./send_test_command.sh IsScoCallDisabled                  # audioMode/scoActive/communicationDevice 状态
./send_test_command.sh SetScoCallDisabled disabled=false  # 恢复（必须）

# 5. 连接黑白名单（ASR-0175）：模式/名单 → 查询 → 立即评估 → 恢复
./send_test_command.sh SetBluetoothAddressWhitelist 'addresses=["AA:BB:CC:DD:EE:FF"]'
./send_test_command.sh SetBluetoothAccessPolicy mode=1    # modeName=whitelist, enforcement 上报
./send_test_command.sh GetBluetoothAccessPolicy           # 四份名单 + 当前设备
./send_test_command.sh ApplyBluetoothAccessPolicy         # 立即评估（本机无设备，disconnected/unbonded 空）
./send_test_command.sh SetBluetoothAccessPolicy mode=0    # 恢复（必须）
./send_test_command.sh SetBluetoothAddressWhitelist 'addresses=[]'    # 清空（必须）

# 6. 收尾：恢复出厂基线（蓝牙出厂关闭态）
./send_test_command.sh SetBlueOpen open=false             # 蓝牙关闭
```

### 5.24 壁纸管控（ASR-0183/0184）

```bash
# 1. 桌面壁纸（ASR-0183）：基线 → 设置（生成纯色图）→ 核对 → 换色
./send_test_command.sh GetWallpaper                        # 基线：home.id=当前值、lock.id=-1（未设置）
./send_test_command.sh SetWallpaper target=home color=red  # expectedDominant=-65536（红），idChanged/colorsMatched/drawableDominantMatch=true
adb shell md5sum /data/system/users/0/wallpaper            # 文件级核对（root）：与 imageBytes 一致
./send_test_command.sh SetWallpaper target=home color=green # 覆盖：id 再递增、primary=-16711936（绿）
./send_test_command.sh GetWallpaperStateLocal              # testapp 本地交叉核对（homeId/colors 一致）

# 2. 自定义图像（显式 base64）与尺寸/颜色参数
# 白色 400×200 PNG 的 base64 传入（本机构造）；或 color=yellow width=320 height=200、color=0xFF800080（hex）
./send_test_command.sh SetWallpaper target=home imageBase64=<b64>   # width=400/height=200/imageBytes 核对

# 3. 锁屏壁纸（ASR-0184）：设置 → 文件/服务核对 → 桌面不受影响
./send_test_command.sh SetWallpaper target=lock color=blue # lock id 由 -1 变为正数、primary=-16776961（蓝）
adb shell dumpsys wallpaper                               # Lock wallpaper state: User 0: id=<afterId> 对照
adb shell md5sum /data/system/users/0/wallpaper_lock      # 文件级核对（root）
./send_test_command.sh GetWallpaper                        # home 不受影响（id/colors 不变）

# 4. 双目标与错误路径
./send_test_command.sh SetWallpaper target=both color=purple  # home+lock 两 flag 同时设置（primary=-65281 紫）
./send_test_command.sh SetWallpaper                         # missing parameter: target
./send_test_command.sh SetWallpaper target=desktop color=red # invalid target
./send_test_command.sh SetWallpaper target=home imageBase64=not-base64!!  # invalid base64 image data

# 5. 收尾：恢复基线（桌面白色；锁屏文件删除 + framework 软重启回退默认）
./send_test_command.sh SetWallpaper target=home color=white
adb root && adb shell rm -f /data/system/users/0/wallpaper_lock /data/system/users/0/wallpaper_lock_orig
adb shell stop && adb shell start                        # 锁屏渲染回退默认灰底（wallpaper_info.xml 元数据残留为 ROM 副作用）
```

### 5.25 设备信息查询（ASR-0108/0110/0219/0262/0265/0290/0387/0442）

```bash
# 1. 文件属性（ASR-0108）：目录/文件/列表/系统路径/不存在
adb shell mkdir -p /sdcard/MDM && dd if=/dev/zero of=/sdcard/MDM/f.bin bs=1 count=1234
./send_test_command.sh GetFileAttribute path=/sdcard/MDM            # isDirectory=true、canonicalPath=/storage/emulated/0/MDM
./send_test_command.sh GetFileAttribute path=/sdcard/MDM/f.bin       # isFile=true、size=1234
./send_test_command.sh GetFileAttribute path=/sdcard/MDM list=true   # listCount/entries（含子目录标记）
./send_test_command.sh GetFileAttribute path=/system/build.prop      # 系统路径属性（size=8592）
./send_test_command.sh GetFileAttribute path=/sdcard/not_exist       # success=false + file not found
./send_test_command.sh GetFileAttribute                              # missing parameter: path

# 2. root 状态（ASR-0110）：Launcher API + 本地探针交叉核对
./send_test_command.sh CheckRootStatus          # rooted/suBinaries/suLsProbe/suExecProbe/debuggable 全量
./send_test_command.sh CheckRootStatusLocal     # 本地探针同口径
adb shell ls /system/xbin/su && adb shell su     # 系统对照（su 存在且可获 root）

# 3. VPN 服务状态（ASR-0219）：无 VPN → 启用防火墙策略 → 恢复
./send_test_command.sh GetVpnStatus              # vpnActive=false、networks=[]
./send_test_command.sh SetDomainPolicyMode mode=1 # 启动 Launcher 防火墙 VPN
./send_test_command.sh GetVpnStatus              # vpnActive=true、tun0/CONNECTED、packages 含 com.hmdm.launcher
./send_test_command.sh CheckVpnNetworks          # 交叉核对：1 个 TRANSPORT_VPN 网络
./send_test_command.sh SetDomainPolicyMode mode=0 # 恢复（vpnActive=false）

# 4. 号码归属地（ASR-0262）：城市级/4 位前缀/格式归一化/电信与虚拟号/异常
./send_test_command.sh QueryNumberAttribution number=13910001234   # 北京/北京/中国移动（7 位段）
./send_test_command.sh QueryNumberAttribution number=13112345678   # 北京/中国联通（4 位地域惯例）
./send_test_command.sh QueryNumberAttribution number=+8613910001234 # 归一化后结果一致
./send_test_command.sh QueryNumberAttribution number=13312345678   # 中国电信
./send_test_command.sh QueryNumberAttribution number=17012345678   # 虚拟运营商
./send_test_command.sh QueryNumberAttribution number=138           # 运营商级命中 + note
./send_test_command.sh QueryNumberAttribution number=12345         # matched=false + error

# 5. Cell ID（ASR-0265）与 SIM 联系人（ASR-0290）：本机无 SIM 如实空结构；权限门证据
./send_test_command.sh GetCellInfo            # simPresent=false、cellInfo 空/桩条目、cellLocation 空
./send_test_command.sh GetCellInfoLocal       # SecurityException（ACCESS_FINE_LOCATION 权限门）
./send_test_command.sh GetSimContacts         # contactsCount=0 + note
./send_test_command.sh GetSimContactsLocal    # SecurityException（READ_CONTACTS 权限门）
adb shell dumpsys telephony.registry | grep -iE "cell|sim"   # 系统对照

# 6. 用户列表（ASR-0387）：查询 + 创建/删除闭环
./send_test_command.sh GetUserList            # users=[{id=0, name=Owner, ...}]
./send_test_command.sh CreateUser name=mdm_test_user   # userId=10 入列
./send_test_command.sh DeleteUser userId=10            # removed=true、列表恢复
adb shell dumpsys user                        # 系统对照

# 7. WebView Provider 上报（ASR-0442）
./send_test_command.sh GetWebViewInfo         # currentProvider=com.android.webview 101.0.4951.61/495156103（dumpsys 回填）、enabled=true
./send_test_command.sh GetWebViewInfoLocal    # 设置键一致；providers 空为包可见性限制（note）
adb shell dumpsys webviewupdate               # 系统对照（Current WebView package 行）
```

### 5.26 入口/设置锁定（ASR-0048/0049/0058/0062-0068/0076/0077/0086/0088/0093/0100/0199/0203/0308/0322/0334/0335/0370）

```bash
# 0. 本地探测基线（resolve 目标页面 + 组件状态 + 原始值 + 音频）
./send_test_command.sh GetEntriesLocal        # appPermissionPage/allAppsPage/roleRequest 等解析 + componentStates
./send_test_command.sh GetValuesLocal         # adb_enabled / airplane_mode_on / 锁屏通知 / 无障碍原始值
./send_test_command.sh GetAudioStateLocal     # 6 流音量 + speakerphoneOn

# 1. 应用权限页入口（ASR-0048/0049）：禁用 → 解析失效 → 恢复
./send_test_command.sh SetAppPermissionPageDisabled disabled=true
./send_test_command.sh IsAppPermissionPageDisabled
./send_test_command.sh GetEntriesLocal        # appPermissionPage="unresolved"
./send_test_command.sh SetAppPermissionPageDisabled disabled=false
./send_test_command.sh SetSpecifiedAppPermissionPageDisabled packageName=com.android.settings disabled=true
./send_test_command.sh SetSpecifiedAppPermissionPageDisabled packageName=com.nonexistent.pkg disabled=true   # package not installed
./send_test_command.sh SetSpecifiedAppPermissionPageDisabled packageName=com.android.settings disabled=false

# 2. 通知管理界面（ASR-0058）与用户修改锁（ASR-0063/0064/0065）+ 白名单防关闭（ASR-0062）
./send_test_command.sh SetAppNotificationUiDisabled disabled=true     # ASR-0058，All apps 通知页/单应用通知页禁用
./send_test_command.sh SetAppNotificationUiDisabled disabled=false
./send_test_command.sh SetNotificationsWhitelist 'packageNames=["com.hmdm.testapp"]'
./send_test_command.sh SetNotificationWhitelistLocked locked=true     # ASR-0062
./send_test_command.sh SetNotificationsEnabledForPackage packageName=com.hmdm.testapp enabled=false  # 模拟用户关闭
./send_test_command.sh IsNotificationsEnabledForPackage packageName=com.hmdm.testapp                  # 秒级恢复 true
./send_test_command.sh SetNotificationWhitelistLocked locked=false
./send_test_command.sh SetNotificationsWhitelist 'packageNames=[]'
./send_test_command.sh SetUserNotificationSettingsDisabled disabled=true   # ASR-0063（通知设置页禁用 + 回滚）
./send_test_command.sh SetUserNotificationSettingsDisabled disabled=false
./send_test_command.sh SetStatusBarNotificationsDisabled disabled=true    # ASR-0059 状态
./send_test_command.sh SetStatusBarNotificationSettingLocked locked=true   # ASR-0064
adb shell cmd statusbar send-disable-flag none                             # 模拟用户清除
adb shell dumpsys statusbar | grep -oE 'mDisabled1=0x[0-9a-fA-F]+'         # 10s 内回 0x20000
./send_test_command.sh SetStatusBarNotificationSettingLocked locked=false
./send_test_command.sh SetStatusBarNotificationsDisabled disabled=false
./send_test_command.sh SetLockscreenNotificationsDisabled disabled=true   # ASR-0057 状态
./send_test_command.sh SetLockscreenNotificationSettingLocked locked=true  # ASR-0065
adb shell settings put secure lock_screen_show_notifications 1             # 模拟用户修改
adb shell settings get secure lock_screen_show_notifications               # 2s 内回滚为 0
./send_test_command.sh SetLockscreenNotificationSettingLocked locked=false
./send_test_command.sh SetLockscreenNotificationsDisabled disabled=false

# 3. 应用管理页面（ASR-0066）与白/黑名单（ASR-0067/0068）
./send_test_command.sh SetAppManagementPageDisabled disabled=true     # 应用管理页禁用 → GetEntriesLocal allAppsPage unresolved
./send_test_command.sh SetAppManagementPageDisabled disabled=false
./send_test_command.sh SetAppManagementWhitelist 'packageNames=["com.hmdm.testapp"]'
./send_test_command.sh SetAppManagementPolicyMode mode=1              # 白名单：非白名单第三方应用隐藏（系统应用/保护名单 skipped）
./send_test_command.sh SetAppManagementPolicyMode mode=0              # 恢复全部
./send_test_command.sh SetAppManagementBlacklist 'packageNames=["com.android.documentsui"]'
./send_test_command.sh SetAppManagementPolicyMode mode=2              # 黑名单：documentsui 隐藏（dumpsys hidden=true、All apps 列表消失）
./send_test_command.sh SetAppManagementPolicyMode mode=0              # restored 恢复
./send_test_command.sh SetAppManagementBlacklist 'packageNames=[]'

# 4. 已开启辅助功能列表（ASR-0076）与无障碍 UI 入口（ASR-0077）
./send_test_command.sh GetEnabledAccessibilityServices                 # enabledServices/enabledCount/installedServices
./send_test_command.sh SetAccessibilityServiceEnabled component=com.hmdm.testapp/.TestAccessibilityService enabled=true
./send_test_command.sh GetEnabledAccessibilityServices                 # enabledCount=1、含 testapp 服务
./send_test_command.sh SetAccessibilityServiceEnabled component=com.hmdm.testapp/.TestAccessibilityService enabled=false
./send_test_command.sh SetAccessibilityUiEntryDisabled disabled=true   # 无障碍设置页禁用 → unresolved
./send_test_command.sh SetAccessibilityUiEntryDisabled disabled=false

# 5. 默认短信/拨号/Assistant/浏览器设置锁（ASR-0086/0088/0093/0100，共享角色选择器）
./send_test_command.sh SetSmsAppSettingLocked locked=true              # rolePickerEnabled=false、RequestRoleActivity 禁用
./send_test_command.sh SetDialerAppSettingLocked locked=true           # 叠加
./send_test_command.sh SetSmsAppSettingLocked locked=false             # 仅解 SMS：选择器仍禁用（dialer 锁在）
./send_test_command.sh SetAssistantModificationLocked locked=true      # 另禁 ManageAssistActivity
./send_test_command.sh SetDefaultBrowserModificationLocked locked=true
./send_test_command.sh SetDialerAppSettingLocked locked=false
./send_test_command.sh SetAssistantModificationLocked locked=false
./send_test_command.sh SetDefaultBrowserModificationLocked locked=false  # 全关后 rolePickerEnabled=true、解析恢复

# 6. USB 设置（ASR-0199）/ USB 调试设置（ASR-0203）/ APN 设置项（ASR-0308）
./send_test_command.sh SetUsbSettingsLocked locked=true && ./send_test_command.sh SetUsbSettingsLocked locked=false
./send_test_command.sh SetUsbDebuggingSettingLocked locked=true        # 锁定 adb_enabled=1
adb shell settings put global adb_enabled 0                             # 模拟用户修改
adb shell settings get global adb_enabled                               # 3s 内回滚为 1
./send_test_command.sh SetUsbDebuggingSettingLocked locked=false
./send_test_command.sh SetApnSettingsEnabled enabled=false             # APN 设置页禁用 → unresolved
./send_test_command.sh SetApnSettingsEnabled enabled=true

# 7. 飞行模式修改锁（ASR-0322）/ 语言切换（ASR-0334/0335）/ 禁用扬声器（ASR-0370）
./send_test_command.sh SetUserAirplaneModeChangeLocked locked=true     # 锁定 airplane_mode_on=0
adb shell settings put global airplane_mode_on 1                        # 模拟用户修改
adb shell settings get global airplane_mode_on                          # 3s 内回滚为 0
./send_test_command.sh SetUserAirplaneModeChangeLocked locked=false
./send_test_command.sh SetLanguageSwitchingDisabled disabled=true      # 语言选择页禁用 + SetLanguage 门禁
./send_test_broadcast.sh SetLanguage language=en country=US            # success=false（ASR-0334 拒绝）
./send_test_command.sh SetLanguageSwitchingDisabled disabled=false
./send_test_command.sh SetUserLanguageModificationDisabled disabled=true && ./send_test_command.sh SetUserLanguageModificationDisabled disabled=false
./send_test_command.sh SetSpeakerDisabled disabled=true                # 6 流音量清零 + speakerphoneOn=false
./send_test_command.sh GetAudioStateLocal                               # 本地交叉核对全 0
./send_test_command.sh SetSpeakerDisabled disabled=false               # 音量恢复锁定前值
```

### 5.27 邮件管控（ASR-0423，应用黑名单）

```bash
# 0. 基线
./send_test_command.sh GetEmailPolicyMode        # mode=0、blacklist=[]、controlled=[]
./send_test_command.sh IsEmailControlled packageName=com.android.music   # controlled=false、suspended=false、hidden=false

# 1. 开启黑名单模式（mode=2）并设置黑名单 → 挂起+隐藏立即生效
./send_test_command.sh SetEmailPolicyMode mode=2                        # 空名单对账：blocked=[]（skipped 含 Launcher/testapp 与系统关键前缀）
./send_test_command.sh SetEmailBlacklist 'packageNames=["com.android.music","com.android.documentsui"]'
#   blocked 两包均 {suspended:true, hidden:true}（写后读回核对）
adb shell dumpsys package com.android.music | grep "User 0:"            # hidden=true suspended=true（系统对照）
adb shell am start -n com.android.music/.MusicBrowserActivity           # Error type 3（挂起无法启动）

# 2. 查询与幂等对账
./send_test_command.sh GetEmailPolicyMode        # mode=2、blacklist/controlled 均含两包
./send_test_command.sh IsEmailControlled packageName=com.android.music   # controlled=true、suspended=true、hidden=true
./send_test_command.sh ApplyEmailPolicy          # 已管控态幂等：blocked=[] restored=[]

# 3. 名单缩减/模式关闭 → 恢复
./send_test_command.sh SetEmailBlacklist 'packageNames=["com.android.documentsui"]'   # restored 含 music
./send_test_command.sh SetEmailPolicyMode mode=0                        # restored 含 documentsui
adb shell monkey -p com.android.documentsui -c android.intent.category.LAUNCHER 1   # Events injected: 1（恢复可用）

# 4. 错误路径与保护
./send_test_command.sh SetEmailPolicyMode mode=1                        # invalid mode: 1 (0=off, 2=blacklist)
./send_test_command.sh SetEmailBlacklist                                # missing parameter: packageNames (array)
./send_test_command.sh IsEmailControlled                                # missing parameter: packageName
./send_test_command.sh SetEmailPolicyMode mode=2
./send_test_command.sh SetEmailBlacklist 'packageNames=["com.hmdm.testapp"]'  # testapp 在 skipped（保护名单）
./send_test_command.sh IsEmailControlled packageName=com.hmdm.testapp   # listed=true 但 suspended=false hidden=false（永不挂起/隐藏）

# 5. 持久化与重新武装（保持 mode=2 + 名单非空）
adb root && adb shell "kill -9 $(adb shell pidof com.hmdm.launcher)" && adb unroot   # 进程重启
./send_test_command.sh GetEmailPolicyMode        # 状态保持；logcat EmailControlPolicyManager syncPolicy: email control policy restored
adb reboot                                       # 整机重启（DPM 持久化 + BootCompletedReceiver 重新武装）
adb shell dumpsys package com.android.documentsui | grep "User 0:"      # 重启后仍 hidden=true suspended=true

# 6. 恢复基线
./send_test_command.sh SetEmailPolicyMode mode=0
./send_test_command.sh SetEmailBlacklist 'packageNames=[]'
```

> 说明：邮件管控由 Launcher 侧实现（见 `documents/technical-documentation/EmailControl-ASR-0423-TechnicalDesign.md`）。**机制**：device owner 公开接口 `setPackagesSuspended`（挂起，无法运行）+ `setApplicationHidden`（隐藏，桌面/应用管理入口消失）双通道；黑名单模式 0=关闭/2=黑名单（同 ASR-0149 形制）、名单整体替换、写后读回核对（`dumpsys package` 的 `User 0:` 行 hidden/suspended 位为系统对照）；**保护名单**（com.hmdm.launcher/com.hmdm.testapp/com.android.settings/com.android.permissioncontroller/com.android.systemui/com.android.shell/com.android.providers.settings）与**系统关键前缀**（`com.android.providers.`/`com.android.phone`/`com.android.bluetooth`/`com.android.nfc`/`com.android.cellbroadcast`/`com.mediatek.`/`com.android.inputmethod.`）永远跳过（共享常量类 `PolicyConstants`，与 ASR-0067/0068 引擎同源；防隐藏运行中系统 provider 触发 VcnManagementService 崩溃）；**管控账本**（controlled）保证恢复只解除本引擎管控过的包，且**解除前查兄弟策略名单**（ASR-0017/0067/0068 仍管控的包保持管控、账本保留待兄弟释放后恢复），与 ASR-0017/0067/0068 共用系统状态互不干扰；策略持久化 SharedPreferences `email_policy`，进程重启/开机经 syncPolicy 幂等对账、PackageChangedReceiver 新装应用自动套用。**本 ROM 无邮件应用**（无 GMS/系统邮件客户端），黑名单以普通应用（com.android.music/com.android.documentsui）占位验证——机制与应用类型无关，Gmail/Outlook/Exchange 等真实邮件客户端入名单行为一致（见需求文档"硬件受限测试说明"）。

> 注意：测试期间**不要对 testapp 执行 `am force-stop`**——本 ROM MTK DuraSpeed 会把 force-stop 的应用加入静态 receiver 抑制名单（广播被 AMS 丢弃，logcat `DuraSpeed: ... isSkip=true`，重启设备清空）；进程重启用 `adb root` + `kill <pid>` + `adb unroot`。`send_test_command.sh` 已带 `--include-stopped-packages`。
```

### 5.28 应用安装/卸载策略（ASR-0006/0007/0010/0015/0029/0040/0072/0073）

```bash
# 0. 基线
./send_test_command.sh GetUninstallWhitelist        # mode=whitelist、list=[]
./send_test_command.sh GetUninstallBlacklist        # mode=blacklist、list=[]
./send_test_command.sh GetInstallPolicyMode         # mode=1（whitelist 默认）、blacklist=[]

# 1. 卸载白名单（ASR-0006）：名单内不可卸载、其余非系统包解除锁定
./send_test_command.sh SetUninstallWhitelist 'packageNames=["com.hmdm.testapp"]'
#   blocked 含 com.hmdm.testapp；其余非系统包 unblocked（Launcher 自身 skipped）
adb shell dumpsys package com.hmdm.testapp | grep -i "uninstall"     # blocked=true（系统对照）
./send_test_command.sh GetUninstallWhitelist        # list 含 {packageName, blocked:true, installed:true}

# 2. 卸载黑名单（ASR-0007）：仅名单内包锁定
./send_test_command.sh SetUninstallBlacklist 'packageNames=["com.android.music"]'
adb shell dumpsys package com.android.music | grep -i "uninstall"    # blocked=true

# 3. 安装黑名单（ASR-0010）：模式 2 命中正则的新装包自动静默卸载
./send_test_command.sh SetInstallBlacklist 'patterns=["com\\.example\\..*"]'
./send_test_command.sh SetInstallPolicyMode mode=2                   # 切换黑名单模式
./send_test_command.sh GetInstallPolicyMode         # mode=2、modeName=blacklist
# 安装包名 com.example.stub1 的测试 APK → 安装完成瞬间被自动卸载（pm list packages 无残留）
./send_test_command.sh SetInstallPolicyMode mode=1                   # 恢复白名单模式

# 4. 保活开关（ASR-0015）
./send_test_command.sh SetKeepAliveEnabled packageName=com.hmdm.testapp enabled=true
#   success=true、liveIgnoring=true；adb shell cmd deviceidle whitelist 含 com.hmdm.testapp
./send_test_command.sh IsKeepAliveEnabled packageName=com.hmdm.testapp
./send_test_command.sh GetKeepAliveList
./send_test_command.sh SetKeepAliveEnabled packageName=com.hmdm.testapp enabled=false

# 5. 应用存活检测（ASR-0029）
./send_test_command.sh IsAppAlive packageName=com.hmdm.testapp       # alive=true、pid>0
./send_test_command.sh IsAppAlive packageName=com.not.installed      # installed=false、alive=false

# 6. MANAGE_EXTERNAL_STORAGE（ASR-0040；本 ROM 受限：AppOpsService 忽略该 op 写入）
./send_test_command.sh SetManageExternalStorageGranted packageName=com.hmdm.testapp granted=true
#   本 ROM：success=false（mode 恒 default，写入被框架忽略；仅 Settings 用户确认界面可授予）
adb shell cmd appops get com.hmdm.testapp MANAGE_EXTERNAL_STORAGE   # No operations（系统对照）
./send_test_command.sh IsManageExternalStorageGranted packageName=com.hmdm.testapp
./send_test_command.sh SetManageExternalStorageGranted packageName=com.hmdm.testapp granted=false

# 7. 桌面图标（ASR-0072）
./send_test_command.sh SetDesktopIconHidden packageName=com.android.music hidden=true
adb shell dumpsys package com.android.music | grep "User 0:"         # hidden=true（DPM 对照）
./send_test_command.sh SetDesktopIconHidden packageName=com.android.music hidden=false

# 8. PackageInfo（ASR-0073）
./send_test_command.sh GetPackageInfo packageName=com.hmdm.testapp  # versionName/versionCode/uid/flags/签名 SHA-1 等

# 9. 恢复基线
./send_test_command.sh SetUninstallWhitelist 'packageNames=[]'
./send_test_command.sh SetUninstallBlacklist 'packageNames=[]'
```

> 说明：见 `documents/technical-documentation/AppInstallUninstallPolicyControl-ASR-0006-0007-0010-0015-0029-0040-0072-0073-TechnicalDesign.md`。**机制**：卸载白/黑名单为 device owner 公开 `dpm.setUninstallBlocked`（白名单=名单内锁定+名单外非系统包显式解除；黑名单=仅名单内锁定），名单持久化 SharedPreferences `uninstall_policy`，PackageChangedReceiver 新装应用自动套用；安装黑名单为 `InstallWhitelistManager` 反向模式（模式 0/1/2 + 正则持久化 `install_policy`，进程重启/开机 syncPolicy 重新注册接收器）；保活为 Doze/App Standby 白名单近似（`cmd deviceidle whitelist`，彻底防杀需 ROM 级机制）；MANAGE_EXTERNAL_STORAGE 经平台签名反射 AppOps `android:manage_external_storage`（字符串 op 变体）——**本 ROM AppOpsService 忽略该 op 的 setMode 写入（shell/root/平台应用三通道核验），仅 Settings 用户确认界面可授予，命令如实 success=false，ASR-0040 维持部分完成待 ROM 适配**；桌面图标经 `dpm.setApplicationHidden`（**受第三方桌面渲染限制**，验收以 DPM 状态为准）。

### 5.29 默认意图（ASR-0091/0095/0102）

```bash
# 1. 默认桌面修改锁（ASR-0091）
./send_test_command.sh IsDefaultLauncherSettingLocked      # locked=false
./send_test_command.sh SetDefaultLauncherSettingLocked locked=true
#   success=true、readBack=true；adb shell dumpsys device_policy 限制含 no_config_home_app
./send_test_command.sh SetDefaultLauncherSettingLocked locked=false

# 2. 默认视频播放器（ASR-0095；本 ROM 受限：DPMS 静默丢弃 PPTA 写入）
./send_test_command.sh GetDefaultVideoPlayer               # 基线 component=null
./send_test_command.sh SetDefaultVideoPlayer packageName=com.android.gallery3d activityName=com.android.gallery3d.app.MovieActivity
#   本 ROM：success=false、readBack=null（写入被 fork DPMS 静默丢弃，无 preferred_activities.xml、解析不跟随）
./send_test_command.sh GetDefaultVideoPlayer               # 仍为 null
./send_test_command.sh ClearDefaultVideoPlayer packageName=com.android.gallery3d   # 接口正常调用
#   ASR-0095 维持部分完成待 ROM 适配（无替代 API：无 MIME 角色、无 shell 命令）

# 3. 指定文件类型默认应用（ASR-0102；同受 PPTA 静默丢弃限制）
./send_test_command.sh SetDefaultAppForFileType mimeType=text/html packageName=com.android.htmlviewer   # success=false 如实
./send_test_command.sh GetDefaultAppForFileType mimeType=text/html
./send_test_command.sh ClearDefaultAppForFileType mimeType=text/html packageName=com.android.htmlviewer
./send_test_command.sh SetDefaultAppForFileType mimeType='bad/mime/type' packageName=com.android.htmlviewer
#   error "malformed MIME type"（非法 MIME 拒绝路径通过）
```

> 说明：见 `documents/technical-documentation/DefaultAppIntentControl-ASR-0091-0095-0102-TechnicalDesign.md`。**机制**：默认桌面修改锁为 device owner 用户限制 `no_config_home_app`（RoleManagerService 拒绝 HOME 角色变更）；默认视频/文件类型为 `dpm.addPersistentPreferredActivity`（ACTION_VIEW+CATEGORY_DEFAULT+MIME，框架持久化 preferred_activities.xml 重启保持），目标 Activity 可显式指定或包内自动解析；占位应用按本机已装应用选择（gallery3d/htmlviewer 不可用时报错路径即为机制验证）。**本 ROM 受限核验（2026-08-11）**：`no_config_home_app` 限制键在本 ROM framework 中不存在（dex 扫描），ASR-0091 落地为组件锁（禁用 HomeSettingsActivity + RequestRoleActivity + DefaultAppActivity，HOME_SETTINGS 解析失效即验证）；`addPersistentPreferredActivity` 被 fork DPMS 静默丢弃（受控实验：非视频包绑定解析不跟随），ASR-0095/0102 命令如实上报、维持部分完成待 ROM 适配。

### 5.30 USB/存储/SIM（ASR-0191/0196/0273/0325）

```bash
# 1. USB 数据传输锁（ASR-0191；限制通道为主机制）
./send_test_command.sh IsUsbDataTransferDisabled          # disabled=false、restriction=false
./send_test_command.sh SetUsbDataTransferDisabled disabled=true
#   success=true、readBackRestriction=true；函数通道本 ROM 无 charging 位（settable 表核验）如实 functionOk=false
adb shell dumpsys user | grep -A4 "Device policy global restrictions"   # no_usb_file_transfer（框架强制：UsbDeviceManager.isUsbTransferAllowed 消费）
./send_test_command.sh SetUsbDataTransferDisabled disabled=false   # 限制清除

# 2. USB 外接存储锁（ASR-0196）
./send_test_command.sh SetUsbExternalStorageDisabled disabled=true
adb shell dumpsys device_policy | grep -i physical        # no_physical_media 限制
./send_test_command.sh IsUsbExternalStorageDisabled
./send_test_command.sh SetUsbExternalStorageDisabled disabled=false

# 3. 数据漫游（ASR-0273）
./send_test_command.sh SetDataRoamingDisabled disabled=true
adb shell dumpsys device_policy | grep -i roaming         # no_data_roaming 限制
./send_test_command.sh IsDataRoamingDisabled              # disabled=true、roamingAllowed=false
./send_test_command.sh SetDataRoamingDisabled disabled=false

# 4. SD 卡挂载锁（ASR-0325，含与 0196 双标志调和）
./send_test_command.sh SetSdCardMountDisabled disabled=true
#   success=true、unmountedVolumes=[]（本机无外置卷）+ note
./send_test_command.sh SetUsbExternalStorageDisabled disabled=true
./send_test_command.sh SetSdCardMountDisabled disabled=false     # 0196 标志仍开 → restriction 保持 true
./send_test_command.sh SetUsbExternalStorageDisabled disabled=false  # 两标志全关 → restriction=false
```

> 说明：见 `documents/technical-documentation/UsbStorageSimPolicyControl-ASR-0191-0196-0273-0325-TechnicalDesign.md`。**机制**：USB 数据传输锁=用户限制 `no_usb_file_transfer` + 反射 `UsbManager.setCurrentFunction("charging")`（MANAGE_USB 签名权限）；USB 外接存储/SD 挂载共用用户限制 `no_physical_media`（双标志独立，任一开限制在、全关才清；0325 禁用时额外卸载已挂载可移除卷）；数据漫游为 `no_data_roaming`（MdmUtils 既有引擎）。**本机无外置 SD/USB 卷与 SIM**：限制设置/查询/双标志调和全量可测；真实卷挂载行为与漫游数据业务需对应真机（见需求文档"硬件受限测试说明"）。

### 5.31 音量/显示锁（ASR-0390/0391/0393/0395/0397/0412/0431）

```bash
# 1. 用户音量设置锁 / 音量物理键锁（ASR-0390/0391，共用限制）
./send_test_command.sh SetUserVolumeSettingDisabled disabled=true
adb shell dumpsys device_policy | grep -i volume         # no_adjust_volume 限制
adb shell media volume --stream 3 --set 5                # 调整被拒（音量不变）
./send_test_command.sh SetVolumeKeyDisabled disabled=true
./send_test_command.sh SetVolumeKeyDisabled disabled=false      # 0390 标志仍开 → restriction 保持 true
./send_test_command.sh SetUserVolumeSettingDisabled disabled=false  # 全关 → restriction=false

# 2. 媒体/通知/闹钟音量修改锁（ASR-0393/0395/0397，10s 校正器回滚）
./send_test_command.sh SetMediaVolumeModificationLocked locked=true
#   locked=true、lockedVolume=当前媒体音量
adb shell media volume --stream 3 --set 5                # 音量先短暂变化
sleep 11 && adb shell media volume --stream 3 --get      # 10s 内被校正器回滚至锁定值
./send_test_command.sh SetMediaVolumeModificationLocked locked=false
./send_test_command.sh SetNotificationVolumeModificationLocked locked=true && sleep 11 && ./send_test_command.sh SetNotificationVolumeModificationLocked locked=false
./send_test_command.sh SetAlarmVolumeModificationLocked locked=true && sleep 11 && ./send_test_command.sh SetAlarmVolumeModificationLocked locked=false

# 3. 自动休眠开关（ASR-0412）
./send_test_command.sh SetAutoSleepDisabled disabled=true
adb shell settings get system screen_off_timeout         # 2147483647
./send_test_command.sh SetAutoSleepDisabled disabled=false   # 恢复捕获值

# 4. 一直全屏（ASR-0431；本 ROM 仅导航栏侧生效）
./send_test_command.sh SetAlwaysFullscreen fullscreen=true
#   success=true、navigationVisible=0（导航栏消失）；statusBarDisabled=false + note
#   （本 ROM：dpm.setStatusBarDisabled 被 fork DPMS 静默丢弃、policy_control immersive 不被 SystemUI 消费——状态栏侧无机制）
./send_test_command.sh SetAlwaysFullscreen fullscreen=false    # 恢复（navigationVisible=1）
```

> 说明：见 `documents/technical-documentation/VolumeLockPolicyControl-ASR-0390-0391-0393-0395-0397-TechnicalDesign.md` 与 `DisplayModeControl-ASR-0412-0431-TechnicalDesign.md`。**机制**：0390/0391 共用 `no_adjust_volume` 限制（AudioService 强制，Android 无"仅禁 UI/仅禁按键"粒度）；0393/0395/0397 为单流捕获+10s 校正器回滚（音量/静音态偏离即纠正，与 ASR-0370 同形制）；自动休眠为 `screen_off_timeout=Integer.MAX_VALUE`/恢复捕获值；全屏为 `dpm.setStatusBarDisabled` + `Settings.System.navigation_visible=0`——**本 ROM 状态栏通道无机制（DPMS 丢弃/SystemUI 不消费，真机核验），仅导航栏侧生效，命令如实附注 note，ASR-0431 维持部分完成（导航栏侧）**。**注意**：音量键模拟用 `input keyevent 24/25`（与物理键同路径）；测试结束务必恢复各流音量与全屏/休眠基线。

# 13. OTA 接口组（ASR-0446/0448/0449/0450/0451/0452）：检查/下载 → 本地策略 → 任务控制 → 回调 → 槽位
# 前置：宿主机提供假 OTA 包（zip 含 payload_metadata.bin 伪内容 + payload_properties.txt + compatibility.zip）：
#   adb reverse tcp:8080 tcp:8080 && (cd <ota包目录> && python3 -m http.server 8080)
# config 模板（offset/size 按实际包内条目填写）：
#   {"name":"TEST-OTA-BUILD-1","url":"http://127.0.0.1:8080/fake_ota.zip","ab_install_type":"FILE_PROVIDER",
#    "ab_config":{"force_switch_slot":false,"verify_payload_metadata":false,
#    "property_files":[{"filename":"payload_metadata.bin","offset":<m>,"size":<s>},...]}}

# 1. 检查版本/下载 FOTA（ASR-0446）
./send_test_command.sh FotaCheckUpdate config='{"name":"TEST-OTA-BUILD-1","url":"http://127.0.0.1:8080/fake_ota.zip","ab_install_type":"FILE_PROVIDER","ab_config":{"force_switch_slot":false,"verify_payload_metadata":false,"property_files":[...]}}'
#   success=true、versionName=TEST-OTA-BUILD-1、downloadedFiles 字节数一致、verifyPayloadMetadata=false（假包如实）
./send_test_command.sh FotaDownloadUpdate config='<同上>'
#   success=true、filePath=…/files/ota/fota_package.zip、fileSize 一致
adb root && ls -la /data/user/0/com.hmdm.launcher/files/ota/fota_package.zip

# 2. 本地 OTA 策略开关（ASR-0448）：禁用 → 门禁 → 恢复
./send_test_command.sh SetLocalOtaEnabled enabled=false
./send_test_command.sh IsLocalOtaEnabled                       # enabled=false
adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast -a ON_SQA_INTERFACE_TEST \
  --es event FotaStart --es param '{"actionId":"t1","otaFileUri":"content://com.hmdm.testapp/x/update.zip"}'
#   logcat FotaStart：-104 local ota disabled by policy
./send_test_command.sh FotaApply config='{"name":"x","url":"file:///data/local/tmp/u.zip","ab_install_type":"NON_STREAMING","ab_config":{"force_switch_slot":false,"verify_payload_metadata":false}}'
#   success=false、error=local ota disabled by policy
./send_test_command.sh SetLocalOtaEnabled enabled=true         # 恢复

# 3. 任务控制（ASR-0449/0450）：IDLE 下取消/暂停/恢复 + 状态读回
./send_test_command.sh FotaCancel          # success=true、state=0/IDLE
./send_test_command.sh FotaSuspend         # dispatch 成功、state 如实
./send_test_command.sh FotaResume          # dispatch 成功、state 如实

# 4. 回调消费（ASR-0451）：应用无效包 → 引擎回调经广播到达 testapp
./send_test_command.sh ClearOtaCallbackLog
./send_test_command.sh FotaApply config='{"name":"x","url":"http://127.0.0.1:9999/none.zip","ab_install_type":"FILE_PROVIDER","ab_config":{"force_switch_slot":false,"verify_payload_metadata":false}}'
sleep 8 && ./send_test_command.sh GetOtaCallbackLog
#   count>0、events 含 updater_state（RUNNING/ERROR，valueText 可读）与/或 engine_complete（失败错误码）

# 5. A/B 槽位（ASR-0452）：查询 + 切换 dispatch
./send_test_command.sh GetSlotInfo
#   supported=true、currentSlotSuffix=_a（getprop ro.boot.slot_suffix 对照）、slots 2 项
./send_test_command.sh SetSwitchSlotOnReboot config='<示例 config>'   # success=true、dispatched=true
