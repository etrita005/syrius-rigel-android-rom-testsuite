# 电源/Doze/杂项管控（ASR-0415/0416/0373/0374/0420/0424）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 实现机制 |
|---|---|---|---|
| ASR-0415 | 休眠/唤醒 | 唤醒设备 | `WakeUp`/`GetPowerState`：反射 `PowerManager.wakeUp`（@hide，DEVICE_POWER 签名权限），轮询 `isInteractive()` 写后读回核对 |
| ASR-0416 | 休眠/唤醒 | 休眠设备 | `GoToSleep`/`GetPowerState`：反射 `PowerManager.goToSleep`（GO_TO_SLEEP_REASON_DEVICE_ADMIN），轮询 `isInteractive()` 写后读回核对 |
| ASR-0373 | 电量 | 禁止/运行 Doze Settings | `SetDozeDisabled`/`IsDozeDisabled`：**语义=禁止/恢复 Doze（设备空闲省电模式）**——禁止时经 `cmd device_config put device_idle` 将五个过渡超时标志（inactive_to/light_after_inactive_to/idle_after_inactive_to/sensing_to/locating_to）覆盖为 7 天（Doze 实际不再触发），恢复时置 null 回 ROM 默认；**本 ROM 的 DeviceIdleController.Constants 从 DeviceConfig 读取标志（onPropertiesChanged 监听），AOSP 标准的 Settings.Global device_idle_constants 键不被消费（真机核验）** |
| ASR-0374 | 电量 | Doze Settings 白名单 | `SetDozeWhitelist`/`GetDozeWhitelist`：平台 `PowerWhitelistManager`（@SystemApi 不在公开 SDK）全量替换 Doze 白名单（免电池优化白名单，`isIgnoringBatteryOptimizations` 同一清单），名单持久化 + 重启/开机重新下发 |
| ASR-0420 | 语音助手 | 查询/设置 是否禁用语音助手 | `SetVoiceAssistantDisabled`/`IsVoiceAssistantDisabled`：`Settings.Secure.voice_interaction_service` 置空（备份原值，启用时恢复/删除键） |
| ASR-0424 | 配置有线网卡 | 配置有线网卡（DHCP/静态 IP/DNS/代理/网卡选择/异常返回） | `SetEthernetConfig`/`GetEthernetConfig`/`SetEthernetEnabled`/`IsEthernetEnabled`：反射 `EthernetManager`（@SystemApi 不在公开 SDK，本 ROM 位于 `/apex/com.android.tethering/javalib/framework-connectivity-t.jar`）按接口下发 `IpConfiguration`（公开 SDK 类：DHCP / 静态 IP+前缀+网关+DNS / 代理），写后读回核对 |

**归属**：6 项全部落地为**平台签名应用调用 @SystemApi / @hide / 受保护设置**（uid=1000 现有部署即可调用），无 ROM 侧代码改动。ASR-0415/0416 经 @hide `PowerManager.wakeUp/goToSleep` + `DEVICE_POWER`（manifest 既有声明，已授予）；ASR-0373/0420 经 `WRITE_SECURE_SETTINGS`（既有）直写 Settings；ASR-0374 经 `PowerWhitelistManager`（@SystemApi，反射，无新增权限）；ASR-0424 经 `EthernetManager` + manifest 新增声明 `MANAGE_ETHERNET_NETWORKS` 签名权限。

## 2. 技术选型与可行性核验（2026-08-06，framework.jar / services.jar / APEX jar 反编译核验）

### 2.1 PowerManager（ASR-0415/0416）

| 核验项 | 结果 |
|---|---|
| `PowerManager.wakeUp` | @hide（公开 SDK 仅 `isInteractive`）；本 ROM framework.jar dex 核验存在三变体：`wakeUp(long)`、`wakeUp(long, String)`、`wakeUp(long, int, String)`（MTK 扩展）；引擎优先 `(long, String)`，回退 `(long)` |
| `PowerManager.goToSleep` | 本 ROM 核验存在 `goToSleep(long)` 与 `goToSleep(long, int, int)`（无 AOSP 常见的 2 参变体）；引擎优先 `(long, int, int)`，`GO_TO_SLEEP_REASON_DEVICE_ADMIN` 常量反射读取（回退 1） |
| 权限 | `DEVICE_POWER`（signature|privileged）manifest 既有声明，本机 `dumpsys package` 核对 granted=true；平台签名 uid=1000 且豁免 hidden API 限制 |
| 核对通道 | `PowerManager.isInteractive()`（公开）+ `dumpsys power` 的 `mWakefulness=` 行解析；命令轮询最多 5s（200ms 间隔）确认状态翻转，未翻转如实 success=false |
| 交互限制 | 设备充电中且 `stay_on_while_plugged_in` 置位（本机 mStayOnWhilePluggedInSetting=7）；**本 ROM 实测显式 goToSleep 不被 stayon 抑制**（wakefulness 正常转 Dozing），测试前置 `svc power stayon false` 仅为与其他批次保持一致（见测试用例文档前置条件） |

### 2.2 Doze（ASR-0373/0374）

| 核验项 | 结果 |
|---|---|
| DeviceConfig 标志（`device_idle` 命名空间） | **本 ROM 的 DeviceIdleController.Constants 从 DeviceConfig 读取过渡常量（onPropertiesChanged 监听，services.jar dex 核验：Constants 类仅 <init>/dump/onPropertiesChanged 三个方法，无 Settings 观察者）**；Sheet1 规划路径 `Settings.Global.device_idle_constants` 真机核验**不被消费**（写键后 `dumpsys deviceidle` 常量不变）；引擎经 `cmd device_config put device_idle <key> <ms>`（uid=1000 可执行，DeviceConfigManagerService 对 WRITE_DEVICE_CONFIG 的签名校验对平台签名放行；与 cmd overlay/cmd statusbar 通道同模式）覆盖五个过渡超时，恢复置 `null`；标志持久于 DeviceConfig 存储（重启保持）；`dumpsys deviceidle` 生效常量（inactive_to=+30m 等默认值随覆盖变化）为系统对照 |
| 禁止值设计 | 五个过渡超时键（inactive_to/light_after_inactive_to/idle_after_inactive_to/sensing_to/locating_to）均覆盖为 604800000ms（7 天）——设备在实用时间尺度内不再进入浅/深度 Doze；恢复=置 null 回 ROM 默认 |
| `PowerWhitelistManager` | @SystemApi 类，**公开 SDK 无此类**；本 ROM framework.jar classes2.dex 核验存在，构造器 `(Context)` 公开，方法集为**本 ROM fork 版**：`addToWhitelist(String)`、`addToWhitelist(List)`、`removeFromWhitelist(String)`、`isWhitelisted(String, boolean)`、`getWhitelistedAppIds(boolean)`——**计划中的 AOSP 式 `setAppIdWhitelist(int, boolean)` 在本 ROM 不存在**（NoSuchMethodException 实测路径），引擎运行时方法发现：addToWhitelist/removeFromWhitelist → setAppIdWhitelist → `cmd deviceidle whitelist +/-pkg`（AppRunPolicyManager 既有通道）三级回退 |
| 白名单归属 | Doze 白名单与 ASR-0016/0030"忽略耗电优化白名单"（AppRunPolicyManager，cmd deviceidle 通道）**共用 DeviceIdleController 同一用户清单**；本批次经 PowerWhitelistManager 通道管理，两批次 Set 以最后一次下发为准，查询均显示系统实时清单（设计文档明示，避免误判冲突） |
| 持久化 | 系统白名单为本 ROM 运行时态；本批次将请求名单持久化于 SharedPreferences `doze_policy`，进程重启（ApiService.onCreate）/开机（BootCompletedReceiver）经 `syncPolicy` 重新下发（幂等），查询附实时 isWhitelisted 状态 |

### 2.3 语音助手（ASR-0420）

| 核验项 | 结果 |
|---|---|
| `Settings.Secure.voice_interaction_service` | 本机当前为 null（无任何语音助手/角色持有者）；置空（""）后 VoiceInteractionManagerService 无可用服务组件 → 语音助手不可用 |
| 权限 | `WRITE_SECURE_SETTINGS`（signature）manifest 既有声明，平台签名直写（与 SystemSettingsControl 批次同模式） |
| 还原设计 | 禁用前将原值备份至 SharedPreferences `voice_assistant_policy.backupValue`；启用时恢复备份（写后读回核对），无备份则删除键 |
| 边界说明 | 若设备存在合格 Assistant 角色持有者（本 ROM 无），角色通道仍可能解析助手（属 ASR-0094 角色引擎范围，非本需求通道）；本机实测效果=完全禁用 |

### 2.4 有线网卡（ASR-0424）

| 核验项 | 结果 |
|---|---|
| 客户端类位置 | **`android.net.EthernetManager` 不在 framework.jar**——本 ROM 位于 `/apex/com.android.tethering/javalib/framework-connectivity-t.jar`（boot classpath 成员，`Class.forName` 与 `Context.getSystemService("ethernet")` 均可用）；服务端 `android.net.IEthernetManager`（service list 核对）在 `/apex/com.android.tethering/javalib/service-connectivity.jar`（EthernetServiceImpl/EthernetTracker，AOSP 13 版） |
| API 面（APEX jar dex 核验） | `setConfiguration(String, IpConfiguration)`、`getConfiguration(String) → IpConfiguration`、`setEthernetEnabled(boolean)`、`getAvailableInterfaces() → String[]`、`isAvailable()`——AOSP 13 按接口配置模型；**无 isEnabled() getter**，启停状态经 `dumpsys ethernet` 的 `Ethernet State:` 行读取 |
| 配置对象 | `IpConfiguration`/`StaticIpConfiguration`/`LinkAddress`/`ProxyInfo` 均为公开 SDK 类（API 29+ Builder 链）；`LinkAddress(InetAddress, int)` 构造器为 @hide（公开 SDK 仅无参），经反射构造（平台签名豁免 hidden API） |
| 权限 | `MANAGE_ETHERNET_NETWORKS`（signature|privileged）——本批次 manifest 新增声明，平台签名 uid=1000 自动授予（EthernetServiceImpl.enforceAccessPermission 核对） |
| 持久化 | AOSP 13 的 per-interface IpConfiguration 与启停状态均为**内存态**（框架不落盘）；本批次镜像持久化于 SharedPreferences `ethernet_policy`，`syncPolicy` 在进程重启/开机重新下发（eth 接口出现时 tracker 自动采用已存配置） |
| 本机硬件 | `dumpsys ethernet` 正常（Ethernet State: enabled、接口名过滤 eth\d）；当前无 eth 接口在线（无网线）——配置存储/查询/启停可完整验证，真实 DHCP/静态网络建立需接网线（硬件受限测试说明见需求文档与测试用例文档） |

## 3. 命令接口定义（SystemApiInterface.onEvent）

### 3.1 新增命令（本批次 13 条）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `WakeUp` | 无 | Map：{success, called, channel, interactiveBefore, interactiveAfter, wakefulness} 或 {error} | ASR-0415 |
| `GoToSleep` | 无 | 同上（+ reason） | ASR-0416 |
| `GetPowerState` | 无 | Map：{success, interactive, wakefulness} | ASR-0415/0416 核对 |
| `SetDozeDisabled` | disabled（Boolean，必填） | Map：{success, disabled, channel, flags{5 键}, inactiveTo, lightAfterInactiveTo} 或 {error} | ASR-0373 |
| `IsDozeDisabled` | 无 | 同上（含 disabled） | ASR-0373 |
| `SetDozeWhitelist` | packageNames（List\<String\>，必填，全量替换） | Map：{success, channel, added[], removed[], kept[], failed[]} | ASR-0374 |
| `GetDozeWhitelist` | 无 | Map：{success, channel, requested[{packageName, whitelisted}], whitelistedPackages[]} | ASR-0374 |
| `SetVoiceAssistantDisabled` | disabled（Boolean，必填） | Map：{success, disabled, voice_interaction_service, backedUp} 或 {error} | ASR-0420 |
| `IsVoiceAssistantDisabled` | 无 | 同上（含 disabled） | ASR-0420 |
| `SetEthernetConfig` | mode（dhcp/static，必填）；iface（可选，默认首选接口/eth0）；static 必填 ipAddress/prefixLength(0-32)/gateway/dns1，可选 dns2/domains；可选 proxyHost/proxyPort/proxyExclusionList | Map：{success, mode, iface, applied{mode, ipAddress, prefixLength, gateway, dnsServers[], domains, proxyHost, proxyPort, proxyExclusionList}, persisted, available, interfaces[], enabled} 或 {error} | ASR-0424 |
| `GetEthernetConfig` | iface（可选） | Map：{success, iface, config{...}, persisted{...}, available, interfaces[], enabled} | ASR-0424 |
| `SetEthernetEnabled` | enabled（Boolean，必填） | Map：{success, enabled, persisted, available, interfaces[]} 或 {error} | ASR-0424 配套 |
| `IsEthernetEnabled` | 无 | Map：{success, enabled, persisted, available, interfaces[]} | ASR-0424 配套 |

**Set 类命令返回结构**（以 SetEthernetConfig 为例）：

```
{success:true, mode:"static", iface:"eth0",
 applied:{mode:"static", ipAddress:"192.168.10.100", prefixLength:24,
          gateway:"192.168.10.1", dnsServers:["8.8.8.8","8.8.4.4"], domains:null},
 persisted:true, available:false, interfaces:[], enabled:true}
# setConfiguration 后 getConfiguration 读回核对（mode/ip/prefix 一致）才 success=true
```

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> p = new HashMap<>();
p.put("mode", "static");
p.put("iface", "eth0");
p.put("ipAddress", "192.168.10.100");
p.put("prefixLength", 24);
p.put("gateway", "192.168.10.1");
p.put("dns1", "8.8.8.8");
Map result = api.onEvent("SetEthernetConfig", p);

Map<String, Object> w = new HashMap<>();
w.put("packageNames", Arrays.asList("com.hmdm.testapp", "com.android.settings"));
Map result2 = api.onEvent("SetDozeWhitelist", w);

Map<String, Object> d = new HashMap<>();
d.put("disabled", true);
Map result3 = api.onEvent("SetDozeDisabled", d);
```

**广播通道**（TestBroadcast，与现有命令一致）：

```bash
adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast \
  -a ON_SQA_INTERFACE_TEST --es event SetDozeDisabled \
  --es param '{"disabled":true}'
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/
│   ├── PowerDozePolicyManager.java            # 新增：唤醒/休眠/Doze 开关/Doze 白名单/语音助手引擎
│   └── EthernetPolicyManager.java             # 新增：有线网卡配置引擎（IpConfiguration 构造 + 反射调用 + 镜像持久化）
├── service/command/powerdoze/
│   ├── WakeUp.java                            # 新增：ASR-0415 唤醒设备
│   ├── GoToSleep.java                         # 新增：ASR-0416 休眠设备
│   ├── GetPowerState.java                     # 新增：ASR-0415/0416 状态查询
│   ├── SetDozeDisabled.java                   # 新增：ASR-0373 禁止/运行 Doze
│   ├── IsDozeDisabled.java                    # 新增：ASR-0373 查询
│   ├── SetDozeWhitelist.java                  # 新增：ASR-0374 白名单全量替换
│   ├── GetDozeWhitelist.java                  # 新增：ASR-0374 查询
│   ├── SetVoiceAssistantDisabled.java         # 新增：ASR-0420 禁用语音助手
│   └── IsVoiceAssistantDisabled.java          # 新增：ASR-0420 查询
├── service/command/ethernet/
│   ├── SetEthernetConfig.java                 # 新增：ASR-0424 配置有线网卡
│   ├── GetEthernetConfig.java                 # 新增：ASR-0424 查询
│   ├── SetEthernetEnabled.java                # 新增：ASR-0424 启停以太网栈
│   └── IsEthernetEnabled.java                 # 新增：ASR-0424 查询
├── service/ApiBinder.java                     # 注册 13 个新命令
├── service/ApiService.java                    # onCreate 重新下发 Doze 白名单/以太网配置（进程重启）
└── broadcast/BootCompletedReceiver.java       # 开机重新下发 Doze 白名单/以太网配置

testapp/
├── src/main/java/com/hmdm/testapp/
│   ├── PowerDozeTestActivity.java             # 新增：电源/Doze/语音助手测试页（12 个按钮 + 名单输入）
│   ├── EthernetTestActivity.java              # 新增：有线网卡测试页（7 个按钮 + 静态参数输入）
│   ├── TestActions.java                       # 新增 13 个事件（含参数校验）与事件目录
│   └── MainActivity.java                      # 主页新增 2 个入口按钮
└── src/main/res/layout/
    ├── activity_power_doze_test.xml           # 新增
    ├── activity_ethernet_test.xml             # 新增
    └── activity_main.xml                      # 主页新增 2 个入口按钮
```

## 5. 执行逻辑

```
WakeUp:
  1. 反射 PowerManager.wakeUp(long, String)（"com.hmdm.launcher:mdm-wakeup"）→ 回退 wakeUp(long)
  2. 轮询 isInteractive()==true（≤5s，200ms 间隔）
  3. 返回 {success:called && interactive, channel, before/after, wakefulness}

GoToSleep:
  1. 反射读 GO_TO_SLEEP_REASON_DEVICE_ADMIN（回退 1）
  2. 反射 PowerManager.goToSleep(long, reason, 0) → 回退 goToSleep(long)
  3. 轮询 isInteractive()==false（≤5s）
  4. 返回 {success:called && !interactive, channel, reason, before/after, wakefulness}

SetDozeDisabled(disabled):
  1. 参数校验（缺 disabled → {error}）
  2. true：对五个超时键依次 cmd device_config put device_idle <key> 604800000；false：置 null
  3. 逐键读回核对（cmd device_config get）+ dumpsys deviceidle 生效值（inactive_to/light_after_inactive_to）附报

SetDozeWhitelist(packageNames):
  1. 参数校验（缺/非数组 → {error}）
  2. 名单清洗（去重/trim/丢弃空串）；逐包校验（包名正则 + 已安装，防 shell 注入）
  3. **与实时白名单状态求差**（非仅持久化名单）：请求包已在实时白名单则跳过；持久化名单中不再请求的包——若仍被 ASR-0016/0030 耗电白名单策略持久化持有则**保留不移除并计入 kept**（防两策略漂移），否则仅当实时处于白名单才执行移除
  4. 新增包 → addToWhitelist(pkg)；移除包 → removeFromWhitelist(pkg)
     （PowerWhitelistManager → 回退 setAppIdWhitelist → 回退 cmd deviceidle whitelist +/-pkg）
  5. 逐包读回核验 isWhitelisted(pkg, true) → added/removed/failed 如实分列
  6. 持久化最终名单（doze_policy.xml，kept 包保留）；全部成功才 success=true

SetVoiceAssistantDisabled(disabled):
  1. 参数校验
  2. true：非空原值先备份（voice_assistant_policy.backupValue）→ putString(voice_interaction_service, "")
  3. false：有备份 → 恢复备份并清除备份；无备份 → 删除键
  4. 读回核对（禁用=空串；启用=备份值/空）

SetEthernetConfig:
  1. 参数校验（mode 必填且 dhcp/static；static 必填 ipAddress/prefixLength(0-32)/gateway/dns1）
  2. 获取 EthernetManager（getSystemService("ethernet") → 回退反射构造）；不可用 → {success:false, supported:false}
  3. 解析接口（参数 iface → getAvailableInterfaces()[0] → "eth0"）
  4. 构造 IpConfiguration（DHCP=Builder().build()；STATIC=StaticIpConfiguration.Builder(LinkAddress(反射构造)/gateway/dns) + setStaticIpConfiguration；代理=setHttpProxy(ProxyInfo.buildDirectProxy)）
  5. 反射 setConfiguration(iface, config)；getConfiguration(iface) 读回**全量核对**（mode/ip/prefix/gateway/dns 列表/domains/代理 host+port+排除列表逐项比对）
  6. 成功 → 单次原子镜像持久化（ethernet_policy.xml，排除列表存 StringSet）；附报 available/interfaces/enabled

SetEthernetEnabled(enabled):
  1. 参数校验
  2. 反射 setEthernetEnabled(boolean)；轮询 dumpsys ethernet "Ethernet State:"（≤3s）核对
  3. 成功 → 持久化 enabled 标志（false 时重启重新下发）

Is* 查询: 如实读取（settings/白名单/系统状态）并附报环境信息

syncPolicy（进程重启/开机）:
  - PowerDozePolicyManager：持久化 Doze 白名单非空 → 全量重新 addToWhitelist（幂等）
  - EthernetPolicyManager：持久化配置存在 → setConfiguration 重新下发；enabled=false 持久化 → setEthernetEnabled(false)
  （Doze 标志持久于 DeviceConfig 存储、voice_interaction_service 键持久于设置库，均无需重发）
```

**安全设计**：白名单与以太网接口名均经正则/枚举校验后进入反射或 shell（包名 `[a-zA-Z0-9_.]+` + 已安装校验，与 AppRunPolicyManager 同款注入闸门）；反射方法名均为代码内常量；仅调用系统既有 @SystemApi/@hide 接口（DEVICE_POWER / WRITE_SECURE_SETTINGS / MANAGE_ETHERNET_NETWORKS 签名权限），无权限面扩大、无 ROM 改动；轮询均带超时不悬死。**测试广播通道加固（代码审查结论落地）**：`TestBroadcast`（manifest exported）原无发送方校验，任意第三方应用可驱动平台签名进程执行本批特权命令——本批次为该接收器增加 `android:permission="android.permission.DUMP"` 框架级发送方门禁（shell/root/system 持有 DUMP，第三方应用无；adb shell am broadcast 通道实测放行，第三方应用被 AMS 拒绝）；本 ROM BroadcastReceiver 无 `getSendingUid()`（MTK fork 裁剪，framework dex 核验仅 getSendingUser/getSendingUserId），故以 manifest 权限门禁替代代码内 uid 校验。

## 6. 权限与归属

- `DEVICE_POWER`（signature|privileged）：manifest 既有声明，`dumpsys package com.hmdm.launcher` 核对 granted=true（本机核验）；PowerManager.wakeUp/goToSleep 均要求该权限（PowerManagerService 侧 enforce），平台签名 uid=1000 放行；
- `WRITE_SECURE_SETTINGS`（signature）：manifest 既有声明（SystemSettingsControl 批次），ASR-0420 直写 Settings.Secure 受保护键（ASR-0373 经 cmd device_config 通道，见第 2.2 节）；
- `PowerWhitelistManager`：@SystemApi 类，平台签名豁免 hidden API 限制（公共 SDK 无此类，全反射）；无新增权限；
- `MANAGE_ETHERNET_NETWORKS`（signature|privileged）：**本批次 manifest 新增声明**，平台签名 uid=1000 自动授予（安装后 `dumpsys package` 核对 granted=true）；EthernetServiceImpl 的 enforceAccessPermission 校验该权限；
- 无 ROM 侧代码改动，无 root 依赖；不修改 AIDL / lib 模块；testapp 无需新增权限（命令在 Launcher 进程执行）；不修改 `device_admin.xml`，无需重启 framework（APK 安装后 ApiService 自动重启加载新命令）。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺 enabled/disabled/mode/packageNames 参数 | 命令返回 {error："missing parameter: ..."}，不 crash；testapp 侧同样拦截 |
| 设备已处于目标状态（已唤醒再唤醒/已休眠再休眠） | wakeUp/goToSleep 为幂等 no-op，轮询命中目标态即 success=true |
| wakeUp/goToSleep 反射失败（方法集差异/SecurityException） | 如实 success=false + error（含异常信息与已尝试 channel），不 crash |
| 充电中 stay_on_while_plugged_in 抑制休眠（部分 ROM） | 若轮询超时（本 ROM 实测不抑制），如实 success=false + error（interactiveAfter 附报实际态）；测试前置 `svc power stayon false` |
| `PowerWhitelistManager` 方法集差异（本 ROM 为 addToWhitelist/removeFromWhitelist 版） | 运行时方法发现三级回退（addToWhitelist → setAppIdWhitelist → cmd deviceidle whitelist），channel 字段附报实际通道 |
| 白名单含未安装/非法包名 | 逐包 failed 分列（"package not installed"/"invalid package name"），其余包正常处理；success=false 但 added/removed 如实 |
| 与 ASR-0016/0030 耗电白名单并存 | 共用同一系统清单，后下发者覆盖（设计明示）；本批次查询显示系统实时清单 |
| 语音助手原值为空/无备份启用 | 启用=删除键，读回 null/空均视为已启用，如实附报 |
| 以太网服务不可用（无 EthernetService） | {success:false, supported:false, error:"ethernet service unavailable"}，不 crash |
| 静态配置参数非法（IP/前缀/网关/DNS 格式错） | {error："invalid static config: ..."}，不落盘 |
| 无接口且未指定 iface | 默认回落 "eth0" 存储配置（接口出现时生效）；`GetEthernetConfig` 报 no interface + 环境信息 |
| setConfiguration 读回不一致 | success=false + error（read-back mismatch），不持久化镜像 |
| 进程被杀 / 重启 / 开机 | Doze 白名单与以太网配置/禁用标志经 syncPolicy 重新下发（doze_policy.xml / ethernet_policy.xml）；Doze 开关标志持久于 DeviceConfig 存储、语音助手键持久于设置库无需重发 |
| Launcher 卸载/停用（设备退役） | ① Doze 开关标志（`device_idle` 五键 7 天覆盖）**独立于应用持久于 DeviceConfig，卸载/数据清除后仍保留且无自恢复路径**——退役须手动恢复（adb root）：`cmd device_config put device_idle inactive_to null`（五个键依次）或重装后管理端重新下发 `SetDozeDisabled disabled=false`；② 以太网镜像随卸载清除、框架配置/启停为内存态（重启回 enabled+DHCP），无残留；③ 语音助手键由设置库保留（本机出厂即空串），退役如需恢复原值以系统侧处置 |
| 测试结束状态恢复 | 唤醒/休眠状态恢复亮屏（WakeUp）；Doze 恢复（SetDozeDisabled disabled=false）；白名单清空（SetDozeWhitelist packageNames=[]）；语音助手恢复（SetVoiceAssistantDisabled disabled=false）；以太网配置恢复 DHCP、启停恢复 enabled=true（ethernet_policy.xml 镜像与设置库无残留策略） |

## 8. 真机验证记录（2026-08-06，Android 13 / API 33 userdebug，平台签名 + device owner）

（命令级用例结果见测试用例设计文档，此处记录机制核验关键结论）

| 核验项 | 结果 |
|---|---|
| 部署 | Launcher（平台签名，uid=1000，device owner 保持）与 testapp 重装成功；`MANAGE_ETHERNET_NETWORKS` 签名权限 granted=true（manifest 新增声明，`dumpsys package` 核对）；DEVICE_POWER 既有 granted=true |
| 唤醒/休眠通道 | `PowerManager.wakeUp(long,String)` / `goToSleep(long,int,int)` 反射成功（channel 附报）；`dumpsys power` mWakefulness Awake↔Dozing 随动（本 ROM 熄屏态为 Dozing 而非 AOSP Asleep）；isInteractive 轮询核对一致；**stayon=true 不抑制显式 goToSleep（本 ROM 实测）** |
| Doze 开关 | `SetDozeDisabled disabled=true` → 五个 `device_idle` DeviceConfig 标志=604800000（`cmd device_config get` 读回核对）、`dumpsys deviceidle` Settings 段 inactive_to/light_after_inactive_to=+7d0h0m0s0ms 生效（onPropertiesChanged 即时消费核验）；`disabled=false` → 标志 null、常量回 ROM 默认（+30m/+4m）；**AOSP 标准键 Settings.Global device_idle_constants 写后 `dumpsys deviceidle` 不变化（本 ROM 不消费，经核验未采用）** |
| Doze 白名单 | `PowerWhitelistManager(Context)` 反射构造成功；addToWhitelist/removeFromWhitelist 反射成功（channel=PowerWhitelistManager）；`isWhitelisted(pkg,true)` 读回核对一致；`cmd deviceidle whitelist` 对照清单一致；持久化 doze_policy.xml；force-stop 后 syncPolicy 重新下发 |
| 语音助手 | `SetVoiceAssistantDisabled disabled=true` → voice_interaction_service=""（原值备份）；disabled=false → 恢复原值/删除键；读回核对一致；本机无合格 Assistant 角色持有者，禁用后无任何可解析的语音助手服务 |
| 以太网 | `getSystemService("ethernet")` 返回 EthernetManager；`setConfiguration(eth0, IpConfiguration)` 成功且 `getConfiguration(eth0)` 读回一致（DHCP↔静态 IP 闭环）；`setEthernetEnabled false/true` → `dumpsys ethernet` Ethernet State 随动（轮询核对）；`getAvailableInterfaces` 当前为空数组（无网线）如实上报；镜像持久化 ethernet_policy.xml 与 syncPolicy 重新下发（force-stop 核验） |
| 无残留 | 全部策略复位后 settings/预置文件无残留（device_idle 五标志 null、voice_interaction_service 恢复、doze_policy/ethernet_policy 空标志、以太网 enabled=true） |
| 本机 ROM 环境备注 | ① 本 ROM PowerWhitelistManager 为 fork 版方法集（无 setAppIdWhitelist，addToWhitelist(String) 版）；② EthernetManager 类在 APEX framework-connectivity-t.jar（非 framework.jar），boot classpath 可加载；③ 以太网接口当前无网线（ETH 接口未出现），真实 DHCP/静态网络建立需接网线（见需求文档"硬件受限测试说明"）；④ 本 ROM BroadcastReceiver 无 getSendingUid()（仅 getSendingUser/getSendingUserId） |
| 代码审查修复复核（2026-08-06） | ① **代理排除列表往返**：静态+代理（proxyHost/proxyPort/proxyExclusionList）下发后 force-stop Launcher → syncPolicy 重新下发 → `getConfiguration` 读回与镜像均含排除列表（StringSet 持久化 + 旧 String 格式兼容读取）；② **静态配置全量核对**：IP/前缀/网关/双 DNS/代理逐项读回比对（成功路径与缺参/非法路径）；③ **白名单 kept 调和**：testapp 同时被 ASR-0016/0030 耗电白名单持久化持有 → `SetDozeWhitelist`（不含 testapp）返回 kept:["com.hmdm.testapp"] 且系统白名单保留 testapp（`cmd deviceidle whitelist` 对照），两策略不再漂移；④ **TestBroadcast DUMP 门禁**：adb shell（uid 2000）广播放行（receive command 执行），manifest `android:permission="android.permission.DUMP"` 生效（第三方应用无 DUMP 被 AMS 拒绝） |

## 9. 与既有批次的关系

- 与 SystemSettingsControl（ASR-0166/0204/0205/0314/0345/0426）共用「平台签名 uid=1000 直写 Settings + 写后读回核对」模式（ASR-0373/0420 为同模式设置键管控）；
- ASR-0374 与 AppRunControl 批次 ASR-0016/0030（忽略耗电优化白名单）**共用 DeviceIdleController 同一用户白名单**——本批次换用 PowerWhitelistManager @SystemApi 通道（计划指定），注入闸门与逐包读回核验沿用 AppRunPolicyManager 模式；
- ASR-0415/0416 与既有 PowerOff/Reboot（`power/` 命令包）同属电源域，但为 @hide PowerManager 反射通道（PowerHelper.powerOff 同通道先例）；
- ASR-0424 为新增设备域（Ethernet），沿用「Set/Is 共用引擎 + 读回核对 + SharedPreferences 镜像 + ApiService/BootCompletedReceiver 重新下发」批次模式；
- 命令注册、testapp 事件目录、文档体系与既有批次一致；无 AIDL/lib 模块改动。
