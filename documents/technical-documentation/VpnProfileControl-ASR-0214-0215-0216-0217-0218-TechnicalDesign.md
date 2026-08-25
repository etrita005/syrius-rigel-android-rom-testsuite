# VPN 管控（ASR-0214/0215/0216/0217/0218）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 实现机制 |
|---|---|---|---|
| ASR-0214 | VPN | 禁用/启用 VPN | 隐藏 VPN 设置入口（禁用 `com.android.settings/.Settings$VpnSettingsActivity` 组件）+ 已配置 VPN 的 always-on lockdown（`dpm.setAlwaysOnVpnPackage` lockdown 标志），启用时恢复（符合 Sheet1 建议优先级规划"always-on lockdown + 隐藏设置"） |
| ASR-0215 | VPN | 配置手机中的 VPN（新增/修改配置） | `VpnProfile` 反射构建 + `encode()` 序列化 + `android.security.LegacyVpnProfileStore.put("VPN_<name>", bytes)` 存储，写后读回解码核对 |
| ASR-0216 | VPN | 删除手机中的 VPN | `LegacyVpnProfileStore.remove("VPN_<name>")` + 读回核对；若删除的是当前连接中的 legacy VPN 附报 `wasConnected` |
| ASR-0217 | VPN | 查询手机中的 VPN 列表 | `LegacyVpnProfileStore.list("VPN_")` + 逐项 `get` + `VpnProfile.decode` 解析详情（名称/类型/服务器/账号/DNS/路由等）+ 连接状态 |
| ASR-0218 | VPN | 断开 VPN | legacy VPN 经 `VpnManager.prepareVpn(connected, "[Legacy VPN]", userId)` 断开（Settings 同路径）；provisioned 平台 profile 经 `VpnManager.stopProvisionedVpnProfile()` 停止；App 型 VpnService 连接（含 Launcher 自身防火墙）仅上报不触碰 |

**归属**：按需求文档归属列，ASR-0214 为「Launcher（MDM）」（DPM + 组件禁用），ASR-0215/0216/0217/0218 为「Launcher（MDM）+ 系统 API」（依赖平台签名 uid=1000 反射调用 @hide/@SystemApi：`LegacyVpnProfileStore`（hiddenapi BLOCKED）、`VpnManager`（注册系统服务 vpn_management）、`VpnProfile`（com.android.internal.net，@hide））。manifest 新增声明签名权限 `CONTROL_VPN`（`VpnManager.prepareVpn` 等调用所需，平台签名自动授予）。**无需 ROM 改动**。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署；bootloader 锁定，不可改动 system 分区。测试前设备基线（2026-08-06 实测）：`/data/misc/keystore/vpnprofilestore.sqlite` 的 `profiles` 表为空（无任何 VPN profile）、`dumpsys device_policy` 的 `mAlwaysOnVpnPackage=null`、`mAlwaysOnVpnLockdown=false`、VPN 设置入口 `Settings$VpnSettingsActivity` 处于默认（启用）状态、无 TRANSPORT_VPN 网络。

## 2. 技术选型与可行性核验（本 ROM 逆向核验，重点）

本 ROM 为 MTK fork 的 Android 13（T 基准 + U 风格 VPN 栈），**与 stock AOSP 13 存在多处 VPN 差异**，全部经 framework/services jar dex 反编译 + 真机反射探针（root/run-as uid 10128 双通道）核验：

### 2.1 Profile 存储通道：legacy keystore（SQLite），非 VpnProfileStore

- **stock AOSP 13 的 `android.net.VpnProfileStore`（@SystemApi，S+ 文件存储）在本 ROM 不存在**（framework.jar 四个 dex 全量检索无 `Landroid/net/VpnProfileStore;`）；
- 本 ROM 的 VPN profile 存储为 **keystore2 的 legacy keystore 服务**（`android.security.legacykeystore`，`service list` 确认存在），数据落在 **`/data/misc/keystore/vpnprofilestore.sqlite`**，表结构 `profiles(owner INTEGER, alias BLOB, profile BLOB, UNIQUE(owner, alias))`；
- 客户端封装为 **`android.security.LegacyVpnProfileStore`**（framework classes3.dex，hiddenapi BLOCKED，静态方法 `put(String key, byte[])` / `get(String key)` / `remove(String key)` / `list(String prefix)`，经 `ILegacyKeystore` binder 调用，uid 参数 -1=调用方）；Settings 应用（MtkSettings dex 核验 `loadVpnProfiles`/`save`/删除逻辑）走同一通道；
- **owner 按调用方 uid 隔离（真机核验）**：root（uid 0）写入 → 行 owner=0；testapp（uid 10128）写入 → 行 owner=10128；`list` 仅返回本 uid 行。**Launcher（uid=1000）与 system_server（uid=1000）同 uid，共享同一 profile 空间**——Launcher 写入的 profile 系统可见、可连接；Settings 应用（uid≈10047）创建的 profile 在 Settings uid 空间，本引擎不可见（反向亦然）。该隔离为本 ROM 存储设计，记录于文档供对照；
- profile key 格式：**`"VPN_" + 名称`**（Settings `loadVpnProfiles` 用 `list("VPN_")` + 逐项 `get("VPN_" + name)` 核验）；lockdown profile 名称以**明文 UTF-8 字节**存于键 **`"LOCKDOWN_VPN"`**（Settings `getLockdownVpn` = `new String(LegacyVpnProfileStore.get("LOCKDOWN_VPN"))` 核验）；
- 反射探针（app_process + 反射，2026-08-06）：`put/get/list/remove` 全通道真机通过（`list` 返回去前缀别名），且 **Settings 同通道同机制，Launcher 写入对 Settings UI 不可见属预期**（uid 隔离）。

### 2.2 VpnProfile 序列化：U 风格 int type，反射 encode/decode 保字节兼容

- `com.android.internal.net.VpnProfile` 存在于 framework（classes2/classes4.dex），**type 为 int**（AOSP S/T 为 String），常量核验：`TYPE_PPTP=0`、`TYPE_L2TP_IPSEC_PSK=1`、`TYPE_L2TP_IPSEC_RSA=2`、`TYPE_IPSEC_XAUTH_PSK=3`、`TYPE_IPSEC_XAUTH_RSA=4`、`TYPE_IPSEC_HYBRID_RSA=5`、`TYPE_IKEV2_IPSEC_USER_PASS=6`、`TYPE_IKEV2_IPSEC_PSK=7`、`TYPE_IKEV2_IPSEC_RSA=8`；
- 字段核验：`name/server/username/password/dnsServers/searchDomains/routes`（String）、`mppe/saveLogin/excludeLocalRoutes`（boolean）、`l2tpSecret/ipsecIdentifier/ipsecSecret/ipsecUserCert/ipsecCaCert/ipsecServerCert`（String，非 S 的布尔+String 双字段）、`maxMtu`（int）、`key`（String）；无 marketApp/remoteBypass 字段；
- 引擎**只用 ROM 自身 `encode()`/`decode(String, byte[])` 反射序列化**（字段名/顺序差异全部由 ROM 编解码器消化），字节兼容性有保证；探针实测 encode/decode 往返一致。

### 2.3 连接/断开通道：VpnManager（vpn_management 注册服务）

- `android.net.VpnManager` 为本 ROM 注册系统服务（`SystemServiceRegistry$17` 创建 `new VpnManager(context, IVpnManager)`，`context.getSystemService("vpn_management")` 可取），方法集核验（@SystemApi hiddenapi BLOCKED，平台签名 uid=1000 豁免执行）：`startLegacyVpn(VpnProfile)`、`prepareVpn(String, String, int)`、`stopVpnProfile(String)`、`stopProvisionedVpnProfile()`、`getLegacyVpnInfo(int)`、`getProvisionedVpnProfileState()`、`getVpnConfig(int)`、`updateLockdownVpn()` 等（`isVpnLockdownEnabled()` 存在但**不反映 DPM always-on lockdown 标志**，真机核验恒 false，禁用/启用引擎的 lockdown 读回改用 `dumpsys device_policy` 的 `mAlwaysOnVpnLockdown` 解析）；
- **连接（验证辅助命令 StartVpnProfile）两通道（真机核验）**：
  1. **默认通道（Settings 语义）**：`VpnManager.startLegacyVpn(VpnProfile)`——VpnManagerService 反编译核验：`DEVICE_INITIAL_SDK_INT >= 31` 时 **legacy 类型（0~5）直接被拒**（`UnsupportedOperationException("Legacy VPN is deprecated")`，本设备 `ro.product.first_api_level=33` 恒命中），并要求存在活跃网络（`IllegalStateException("Missing active network connection")`）+ `throwIfLockdownEnabled`；IKEv2 类型（6~8）可通过该闸门（本 ROM Settings 无 IKEv2 连接路径，仅 IVpnManager 键路径可用）；
  2. **平台通道（`StartVpnProfile platform=true`，IKEv2 可用）**：`IVpnManager.startVpnProfile(profileKey)`（ServiceManager 取 `vpn_management` + `IVpnManager$Stub.asInterface` 反射）——框架 `verifyCallingUidAndPackage(profileKey, uid)` 要求 `getAppUid(profileKey)==调用方 uid`，故 profileKey 取 **Launcher 自身包名**、profile 存于 **`PLATFORM_VPN_<userId>_<launcherPackage>`** 键（`Vpn.getProfileNameForPackage` 反编译核验键格式）；成功返回 IKE 会话 UUID、`getProvisionedVpnProfileState` 进入 CONNECTING（1）；IKEv2 参数适配（真机逐层核验）：`ipsecSecret` 须为 **base64**（VpnIkev2Utils base64 解码，非 base64 原文自动编码）、用户身份取自 **`ipsecIdentifier`**（VpnIkev2Utils 映射 mUserIdentity，缺失时引擎用 username 回填）、`mAllowedAlgorithms` 为**私有字段**须 getDeclaredField 反射写入，且本 ROM 校验集**拒绝 `hmac(md5)`/`hmac(sha1)`**、要求 `cbc(aes)`+`hmac(sha256/384/512)` 或 `rfc4106(gcm(aes))`（Ikev2VpnProfile dex 核验，引擎注入 `["cbc(aes)","hmac(sha256)","rfc4106(gcm(aes))"]`）；
- **断开（ASR-0218）三通道（真机闭环）**：① legacy 连接（owner=`"[Legacy VPN]"`）经 `VpnManager.prepareVpn(connected, "[Legacy VPN]", userId)`（Settings `disconnectLegacyVpn` 同路径；`prepare` 反编译核验 CONTROL_VPN 持有者免旧包校验、`"[Legacy VPN]"` 标记触发 legacy 拆除）；② **平台键连接（owner=profileKey）**经 `VpnManager.stopVpnProfile(connected)`（`isCurrentIkev2VpnLocked` 命中 → `prepareInternal("[Legacy VPN]")` 拆除）——真机实测 CONNECTING 的 IKEv2 会话经该通道回 0；③ provisioned profile 经 `stopProvisionedVpnProfile()`；**App 型 VpnService（含 Launcher 自身防火墙 NetworkPolicyVpnService）不触碰**，App VPN 枚举用 `getVpnConfig(userId).user`（owner 包名；TRANSPORT_VPN 网络级 getUids/getOwnerUid 本 ROM 均不可用，核验为空）；
- 连接状态读取：`getLegacyVpnInfo(userId)` → `{key(=mConfig.user)，state(0=DISCONNECTED/2=CONNECTING/3=CONNECTED/5=FAILED)}`；`getVpnConfig(userId)` → `session`=profile 名、`user`=owner；`getProvisionedVpnProfileState()` → `mState`（0/1=CONNECTING/2=CONNECTED/3=FAILED）——平台键连接的 profile 名经 `PLATFORM_VPN_` 键解码映射（`provisionedSessionName`），供列表 connected 标注；**本 ROM 连接失败后可残留 VpnConfig（session 仍在而 state=0）**，列表 connected 判定叠加 state 活跃校验，避免误标。

### 2.4 ASR-0214 禁用/启用（always-on lockdown + 隐藏设置）

- **隐藏设置**：`PackageManager.setComponentEnabledSetting` 禁用 `com.android.settings/.Settings$VpnSettingsActivity`（本 ROM pm dump 确认存在该组件，承载 `android.settings.VPN_SETTINGS` 意图；与 ASR-0019 组件管控同机制，CHANGE_COMPONENT_ENABLED_STATE 签名权限平台签名自动授予），读回 `getComponentEnabledSetting` 核对；启用恢复 **DEFAULT 态**（原值备份于 SharedPreferences `vpn_policy`）；
- **always-on lockdown**：若已配置 App 型 always-on VPN（`dpm.getAlwaysOnVpnPackage` 非空）且当前 lockdown 关闭（`VpnManager.isVpnLockdownEnabled` 读回），禁用时经 `dpm.setAlwaysOnVpnPackage(admin, pkg, true, null)` 置 lockdown（原值备份），启用时恢复 lockdown=false；已存在的 legacy lockdown profile（`LOCKDOWN_VPN` 键）保持不动；无 always-on 配置时如实上报 `lockdownSkippedReason`；
- 语义说明（记录于文档供对照）：按 Sheet1 规划，**"禁用 VPN" = 锁定 VPN 使用状态（用户不可变更，always-on lockdown）+ 隐藏 VPN 管理入口**，而非关闭 VPN 连接本身；"启用" = 恢复入口与 lockdown 原值；
- **持久化**：SharedPreferences `vpn_policy`（disabled 标志 + 备份字段），进程重启/开机无需重新武装（组件状态由 PMS 持久化于 package-restrictions.xml；lockdown 由 DPM 持久化；恢复动作由用户显式"启用"触发）。

### 2.5 权限与声明

manifest 新增 `<uses-permission android:name="android.permission.CONTROL_VPN"/>`（签名权限，平台签名自动授予）；无 `device_admin.xml` 变更；无需重启 framework。

## 3. 命令接口定义（SystemApiInterface.onEvent）

### 3.1 新增命令（本批次，7 条）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `AddVpnProfile` | name（必填）、type（必填，字符串名或 0~8）、server（必填）、username、password、dnsServers、searchDomains、routes、mppe、l2tpSecret、ipsecIdentifier、ipsecSecret、ipsecUserCert、ipsecCaCert、ipsecServerCert、saveLogin、maxMtu、excludeLocalRoutes | Map：{success, name, key, type, typeName, server, readBackOk} 或 {success:false, error} | ASR-0215 |
| `DeleteVpnProfile` | name（必填） | Map：{success, name, key, removed, verified, wasConnected} 或 {success:false, error:"profile not found: <name>"} | ASR-0216 |
| `GetVpnProfileList` | 无 | List<Map>：{name, key, type, typeName, server, username, dnsServers, searchDomains, routes, saveLogin, connected} | ASR-0217 |
| `DisconnectVpn` | 无 | Map：{success, legacyWasConnected, legacyDisconnected, provisionedWasConnected, provisionedStopped, legacyStateAfter, provisionedStateAfter, appVpnPackages} | ASR-0218 |
| `StartVpnProfile` | name（必填）、platform（可选 boolean，默认 false） | Map：{success, channel(legacy/platform), type, typeName, stateAfter, sessionAfter, sessionKey/startError} 或 {success:false, error} | ASR-0218 验证辅助 |
| `SetVpnDisabled` | disabled（必填，boolean） | Map：{success, disabled, lockdownApplied, lockdownSkippedReason, settingsHidden, ...状态附报} | ASR-0214 |
| `IsVpnDisabled` | 无 | Map：{disabled, settingsEntryHidden, alwaysOnVpnPackage, appLockdown, legacyLockdownName, legacyKey, legacySession, legacyState, provisionedState, provisionedSessionName, appVpnPackages} | ASR-0214 |

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> p = new HashMap<>();
p.put("name", "test_vpn");
p.put("type", "pptp");
p.put("server", "10.10.10.10");
p.put("username", "mdm_user");
p.put("password", "mdm_pass");
Map result = api.onEvent("AddVpnProfile", p);
// {"RESULT":{"key":"VPN_test_vpn","name":"test_vpn","readBackOk":true,
//   "server":"10.10.10.10","success":true,"type":0,"typeName":"pptp"}}

Map result2 = api.onEvent("DisconnectVpn", new HashMap<>());
// {"RESULT":{"appVpnPackages":[],"legacyDisconnected":true,"legacyStateAfter":0,
//   "legacyWasConnected":true,"provisionedStateAfter":0,"provisionedStopped":false,
//   "provisionedWasConnected":false,"success":true}}
```

**广播通道**（TestBroadcast，与现有命令一致）：

```bash
adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast \
  -a ON_SQA_INTERFACE_TEST --es event AddVpnProfile \
  --es param '{"name":"test_vpn","type":"pptp","server":"10.10.10.10"}'
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/
│   └── VpnProfilePolicyManager.java      # 新增：VPN profile/连接/禁用策略引擎
│       （LegacyVpnProfileStore 反射通道 put/get/remove/list（"VPN_" 前缀）；
│        VpnProfile 反射构建/encode/decode；VpnManager（vpn_management 服务）
│        反射 startLegacyVpn/prepareVpn/stopProvisionedVpnProfile/getLegacyVpnInfo/
│        getProvisionedVpnProfileState/getVpnConfig/isVpnLockdownEnabled；
│        ASR-0214 组件禁用 + DPM lockdown + SharedPreferences vpn_policy 持久化；
│        TRANSPORT_VPN 网络枚举（getUids 反射）附报 App VPN）
├── service/command/vpn/
│   ├── AddVpnProfile.java               # 新增：ASR-0215 配置
│   ├── DeleteVpnProfile.java            # 新增：ASR-0216 删除
│   ├── GetVpnProfileList.java           # 新增：ASR-0217 列表
│   ├── DisconnectVpn.java               # 新增：ASR-0218 断开
│   ├── StartVpnProfile.java             # 新增：ASR-0218 验证辅助（连接，支持 platform 通道）
│   ├── SetVpnDisabled.java              # 新增：ASR-0214 设置
│   ├── IsVpnDisabled.java               # 新增：ASR-0214 查询
│   └── SetAlwaysOnVpn.java              # 修改：新增 lockdown 参数（缺省 true 保持原行为，ASR-0214 测试基线用）
└── service/ApiBinder.java               # 注册 7 条命令（vpn 包通配 import 已有）
```

testapp（`testapp/src/main/java/com/hmdm/testapp/`）：

```
├── VpnTestActivity.java                 # 新增：VPN 管控测试页（ASR-0214~0218）
├── VpnVerifier.java                     # 新增：本地探测（TryOpenVpnSettings/
│                                        #       CheckVpnNetworks）
└── TestActions.java                     # 新增 9 个事件（7 命令 + 2 探测）
```

## 5. 边界情况与异常处理

| 场景 | 处理 |
|---|---|
| 重复添加同名 profile | 覆盖更新（INSERT OR REPLACE 语义），返回当前内容 |
| name/server/type 缺失 | 返回 `{success:false, error:"missing parameter: ..."}` |
| type 非法（非 0~8/非已知名） | 返回 `{success:false, error:"invalid type: ..."}`（兼容 AOSP 历史拼写 `l2tp_ipsce_psk`→1） |
| 删除不存在的 profile | 返回 `{success:false, error:"profile not found: <name>"}` |
| 删除正在连接的 profile | 删除成功并附报 `wasConnected:true`（连接保持至断开指令，与 Settings 行为一致） |
| 断开时无连接 | `success:true`，各 wasConnected=false，如实上报 |
| IKEv2 profile 启动 | 框架拒绝（"Legacy VPN is deprecated"），如实上报异常 |
| 无活跃网络时启动 | 框架抛 "Missing active network connection"，如实上报 |
| 引擎反射类缺失（非本 ROM） | 返回 `{success:false, error:"...not available on this ROM"}`，不 crash |
| 禁用时无 always-on VPN | lockdownSkippedReason="no always-on VPN configured"，其余机制正常执行 |
| 禁用时已 lockdown | lockdownApplied=false + lockdownSkippedReason="lockdown already active"，保持原值 |
| 启用恢复 | 组件回 DEFAULT 态（原值）、lockdown 回原值（prevAppLockdown=false 才恢复）；幂等 |
| 与 Launcher 防火墙 VpnService 共存 | 断开/列表仅识别 profile 型 VPN；防火墙网络在 appVpnPackages 附报中被排除（自身包跳过） |

## 6. 真机验收记录

（2026-08-06 部署后按测试用例设计文档执行，全部用例通过/受限项如实标注；逐条结果见 `documents/test-case-design/VpnProfileControl-ASR-0214-0215-0216-0217-0218-TestCaseDesign.md` 第 6 节执行记录）

**真机关键闭环（2026-08-06）**：

1. **配置/列表/删除**：PPTP/L2TP/IPSec XAUTH/IPSec Hybrid（数字与名称类型均可）/IKEv2 五种类型 profile 全量 add→readBack→list→delete 闭环；SQLite `vpnprofilestore.sqlite` owner=1000 落行与删除核对一致；同名覆盖、缺参/非法类型拒绝路径全部通过；
2. **连接/断开（平台通道）**：IKEv2 profile（自动 base64 PSK + 身份回填 + 算法集注入）经 `StartVpnProfile platform=true` 进入 CONNECTING（sessionKey=UUID），列表 connected 标注正确，`DisconnectVpn` 三通道中 `stopVpnProfile(connected)` 通道拆除成功（provisionedStateAfter=0），断开后无残留、幂等；legacy 类型连接被本 ROM 框架禁用（"Legacy VPN is deprecated"，DEVICE_INITIAL_SDK_INT=33）如实上报——真实隧道建立需可用 VPN 服务端（见需求文档"硬件受限测试说明"）；
3. **禁用/启用**：设置入口隐藏（`TryOpenVpnSettings` ActivityNotFoundException）与恢复（Settings VPN 页真实拉起）闭环；always-on lockdown 置位（`dumpsys device_policy` mAlwaysOnVpnLockdown=true）与恢复原值闭环；已 lockdown 时禁用保持原值；幂等与禁用期间配置功能不受影响；
4. **共存**：防火墙（Launcher 自身 VpnService）运行中 `DisconnectVpn` 不触碰（vpnRunning 保持 true，appVpnPackages 排除自身）；本会话记录跨批次交互：框架在 VPN 断开时将 VpnService 授权 AppOps（ACTIVATE_VPN）复位 default，防火墙 consent 循环需 `cmd appops set com.hmdm.launcher android:activate_vpn allow` 恢复。
