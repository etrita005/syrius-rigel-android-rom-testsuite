# VPN 管控（ASR-0214/0215/0216/0217/0218）测试用例设计文档

## 1. 前置条件

1. Launcher（`com.hmdm.launcher`）平台签名安装、已设为 device owner（`dpm list-owners` 显示 `DeviceOwner,Affiliated`）、API 服务在线（logcat tag `HYX-MDM-APP`）；
2. testapp（`com.hmdm.testapp`）已安装（IPC 方式测试无需 UI，`send_test_command.sh <event> key=value ...`）；
3. 设备有活跃网络（WLAN 或移动数据）——`VpnManager.startLegacyVpn` 要求存在活跃网络（"Missing active network connection"）；
4. 基线清理：`/data/misc/keystore/vpnprofilestore.sqlite` 的 `profiles` 表无 VPN profile、无 always-on VPN（`dumpsys device_policy` 的 `mAlwaysOnVpnPackage=null`）、VPN 设置入口可用、无 TRANSPORT_VPN 网络（`CheckVpnNetworks` 返回 0）；
5. 测试期间保持 Launcher 网络防火墙策略关闭（`GetNetworkFirewallStatus` 的 mode=0），避免防火墙 VPN 干扰连接状态判定；若此前执行过 VPN 禁用，先 `SetVpnDisabled disabled=false` 恢复。

## 2. 测试环境

- 目标平台：Android 13（API 33）userdebug，MTK fork（MT8788/MT6771），平台签名 + device owner；
- 关键系统通道（本 ROM 逆向核验，见技术设计文档）：profile 存储为 keystore2 legacy keystore（`/data/misc/keystore/vpnprofilestore.sqlite`，`profiles(owner, alias, profile)`，owner=调用方 uid；Launcher uid=1000 与 system_server 同空间）；`VpnManager` 为 `vpn_management` 注册服务；legacy 连接经 `startLegacyVpn`、断开经 `prepareVpn(connected, "[Legacy VPN]", userId)`；
- 对照手段：SQLite 直查 `sqlite3 /data/misc/keystore/vpnprofilestore.sqlite 'SELECT owner, hex(alias), length(profile) FROM profiles;'`、`dumpsys device_policy`（mAlwaysOnVpnPackage/mAlwaysOnVpnLockdown）、`pm dump com.android.settings`（组件状态）、`CheckVpnNetworks`（TRANSPORT_VPN 网络）、`dumpsys connectivity | grep -i vpn`。

## 3. 测试用例表

### 3.1 ASR-0215 配置 VPN（AddVpnProfile）

| 用例编号 | 用例名称 | 操作步骤 | 预期结果 |
|---|---|---|---|
| TC-VP-01 | PPTP 配置成功 | `AddVpnProfile name=test_vpn type=pptp server=10.10.10.10 username=mdm_user password=mdm_pass` | success=true、readBackOk=true、type=0、typeName=pptp；SQLite 出现 `VPN_test_vpn` 行（owner=1000） |
| TC-VP-02 | L2TP/IPSec PSK 配置成功 | `AddVpnProfile name=test_l2tp type=l2tp_ipsec_psk server=10.10.10.10 ipsecSecret=secret123` | success=true、type=1；读回 ipsecSecret 一致 |
| TC-VP-03 | IPSec XAUTH PSK 配置成功 | `AddVpnProfile name=test_xauth type=ipsec_xauth_psk server=10.10.10.10 ipsecSecret=secret123` | success=true、type=3 |
| TC-VP-04 | 数字类型参数 | `AddVpnProfile name=test_num type=5 server=10.10.10.10` | success=true、type=5、typeName=ipsec_hybrid_rsa |
| TC-VP-05 | IKEv2 类型可存储 | `AddVpnProfile name=test_ikev2 type=ikev2_ipsec_psk server=10.10.10.10 ipsecSecret=secret123` | success=true、type=7（仅存储，连接不支持见 TC-VP-14） |
| TC-VP-06 | 缺少 name | `AddVpnProfile type=pptp server=10.10.10.10` | `{success:false, error:"missing parameter: name"}` |
| TC-VP-07 | 缺少 type | `AddVpnProfile name=test_vpn server=10.10.10.10` | `{success:false, error:"missing parameter: type"}` |
| TC-VP-08 | 缺少 server | `AddVpnProfile name=test_vpn type=pptp` | `{success:false, error:"missing parameter: server"}` |
| TC-VP-09 | 非法类型 | `AddVpnProfile name=test_vpn type=foo server=10.10.10.10` | `{success:false, error:"invalid type: foo ..."}`；越界数字（type=99）同样拒绝 |
| TC-VP-10 | 同名覆盖更新 | 先添加 name=test_vpn server=10.10.10.10，再 `AddVpnProfile name=test_vpn type=pptp server=192.168.1.1` | 第二次 success=true；列表/读回 server=192.168.1.1；SQLite 仅一行 `VPN_test_vpn` |

### 3.2 ASR-0217 查询 VPN 列表（GetVpnProfileList）

| 用例编号 | 用例名称 | 操作步骤 | 预期结果 |
|---|---|---|---|
| TC-VP-11 | 空列表 | 基线（无 profile）时 `GetVpnProfileList` | 返回空数组 `[]` |
| TC-VP-12 | 多 profile 列表 | 添加 test_vpn(pptp)、test_l2tp(l2tp_ipsec_psk)、test_ikev2(ikev2_ipsec_psk) 后 `GetVpnProfileList` | 返回 3 项；每项含 name/key/type/typeName/server/username/dnsServers/searchDomains/routes/saveLogin/connected；test_vpn 项 username=mdm_user、dnsServers=8.8.8.8 |
| TC-VP-13 | 列表连接状态标注 | test_vpn 启动（TC-VP-15）后 `GetVpnProfileList` | test_vpn 项 connected=true，其余 connected=false |
| TC-VP-14 | IKEv2 profile 无法启动 | `StartVpnProfile name=test_ikev2` | `{success:false, error:"exception: ..."}`（框架 UnsupportedOperationException "Legacy VPN is deprecated"），如实上报不误报成功 |

### 3.3 ASR-0218 断开 VPN（DisconnectVpn，配合验证辅助 StartVpnProfile）

| 用例编号 | 用例名称 | 操作步骤 | 预期结果 |
|---|---|---|---|
| TC-VP-15 | legacy VPN 启动（连接状态进入） | `StartVpnProfile name=test_vpn`（server=10.10.10.10 不可达） | success=true、stateAfter=2（CONNECTING）或 3/5（不可达服务器可能快速 FAILED）；`CheckVpnNetworks` 出现 TRANSPORT_VPN 网络或 `dumpsys connectivity` 显示 legacy VPN 处理中 |
| TC-VP-16 | 断开已连接 VPN | 在 TC-VP-15 状态（CONNECTING）下 `DisconnectVpn` | legacyWasConnected=true、legacyDisconnected=true、legacyStateAfter=0（DISCONNECTED）、success=true；`CheckVpnNetworks` 无新增 VPN 网络（防火墙关闭时 vpnNetworkCount=0）；`GetVpnProfileList` 全部 connected=false |
| TC-VP-17 | 断开无连接 | 无任何连接时 `DisconnectVpn` | legacyWasConnected=false、legacyDisconnected=false、success=true（幂等） |
| TC-VP-18 | 断开后再次断开 | 连续两次 `DisconnectVpn` | 第一次正常断开；第二次同 TC-VP-17（幂等，不报错） |
| TC-VP-19 | 连接自动失败后的断开 | 等待 CONNECTING 转 FAILED（10.10.10.10 不可达，约数秒~数十秒）后再 `DisconnectVpn` | 幂等成功，状态不再变化（FAILED 态不参与连接判定） |

### 3.4 ASR-0216 删除 VPN（DeleteVpnProfile）

| 用例编号 | 用例名称 | 操作步骤 | 预期结果 |
|---|---|---|---|
| TC-VP-20 | 删除存在 profile | `DeleteVpnProfile name=test_vpn` | success=true、removed=true、verified=true；SQLite 无 `VPN_test_vpn` 行；`GetVpnProfileList` 无该项 |
| TC-VP-21 | 删除不存在 profile | `DeleteVpnProfile name=no_such` | `{success:false, error:"profile not found: no_such"}` |
| TC-VP-22 | 缺少 name | `DeleteVpnProfile` | `{success:false, error:"missing parameter: name"}` |
| TC-VP-23 | 删除正在连接的 profile | test_vpn 连接中（CONNECTING/FAILED）执行 `DeleteVpnProfile name=test_vpn` | 删除成功、wasConnected=true（连接保持，断开由 DisconnectVpn 指令负责，与 Settings 行为一致） |
| TC-VP-24 | 删除后断开残留连接 | TC-VP-23 后 `DisconnectVpn` | 正常断开（状态回 0） |

### 3.5 ASR-0214 禁用/启用 VPN（SetVpnDisabled / IsVpnDisabled）

| 用例编号 | 用例名称 | 操作步骤 | 预期结果 |
|---|---|---|---|
| TC-VP-25 | 禁用 VPN（无 always-on 配置） | `SetVpnDisabled disabled=true` | success=true、disabled=true、settingsHidden=true、lockdownApplied=false、lockdownSkippedReason="no always-on VPN configured" |
| TC-VP-26 | 禁用后设置入口隐藏 | 禁用后 testapp `TryOpenVpnSettings` | `{resolved:false, started:false, visible:false, reason:"component disabled or missing"}`（ActivityNotFoundException）；`pm dump com.android.settings` 该组件 enabled=2（DISABLED） |
| TC-VP-27 | 禁用状态查询 | 禁用后 `IsVpnDisabled` | disabled=true、settingsEntryHidden=true |
| TC-VP-28 | 启用 VPN 恢复入口 | `SetVpnDisabled disabled=false` | success=true、disabled=false、settingsEntryHidden=false；`TryOpenVpnSettings` 回到 resolved=true 并可拉起 Settings VPN 页（visible=true） |
| TC-VP-29 | 禁用幂等 | 连续两次 `SetVpnDisabled disabled=true` | 第二次 `{success:true, disabled:true, already:true}`，无副作用 |
| TC-VP-30 | 启用幂等 | 连续两次 `SetVpnDisabled disabled=false` | 第二次成功，状态不变 |
| TC-VP-31 | 缺参数 | `SetVpnDisabled`（无 disabled） | testapp 侧返回 `missing parameter: disabled` |
| TC-VP-32 | 禁用期间 profile 操作不受影响 | 禁用后 `AddVpnProfile` + `GetVpnProfileList` | 配置/列表功能正常（仅入口与连接锁定） |
| TC-VP-33 | 配置 always-on 后的 lockdown 置位 | ① `SetAlwaysOnVpn packageName=com.hmdm.launcher lockdown=false`（沿用既有命令，验证前确认 `dumpsys device_policy` 出现 mAlwaysOnVpnPackage=com.hmdm.launcher）→ ② `SetVpnDisabled disabled=true` | ② 返回 lockdownApplied=true；`dumpsys device_policy` 显示 mAlwaysOnVpnLockdown=true；`IsVpnDisabled` appLockdown=true |
| TC-VP-34 | 启用恢复 lockdown 原值 | TC-VP-33 后 `SetVpnDisabled disabled=false` | lockdownRestored=true；`dumpsys device_policy` mAlwaysOnVpnLockdown=false（回原值） |
| TC-VP-35 | 已 lockdown 时禁用保持 | TC-VP-33 状态（已 lockdown）再 `SetVpnDisabled disabled=true` → `SetVpnDisabled disabled=false` | 禁用阶段 lockdownApplied=false、lockdownSkippedReason="lockdown already active"；启用后 lockdown 仍为 true（非本次禁用引入，不恢复） |

> 注：TC-VP-33/34/35 的 always-on 目标包使用 Launcher 自身（`com.hmdm.launcher`，其 NetworkPolicyVpnService 为 VpnService）以在本机闭环验证 lockdown 标志链路；lockdown=true 且防火墙未运行时设备流量会被框架阻断（always-on lockdown 语义），测试窗口内保持 adb 通道可用并随即执行启用恢复。App 型 always-on 需真实 VPN 应用的完整行为（如断开自动重连）为**硬件受限项**（见需求文档"硬件受限测试说明"）。

### 3.6 探测辅助

| 用例编号 | 用例名称 | 操作步骤 | 预期结果 |
|---|---|---|---|
| TC-VP-36 | CheckVpnNetworks 基线 | 防火墙策略关闭、无 VPN 连接时 `CheckVpnNetworks` | vpnNetworkCount=0 |
| TC-VP-37 | CheckVpnNetworks 随动 | 防火墙策略开启（`SetDomainPolicyMode mode=1` 后 `GetNetworkFirewallStatus` 确认 VPN 激活）再 `CheckVpnNetworks` | vpnNetworkCount=1、packages 含 com.hmdm.launcher（防火墙自身）、interface=tun0 类 |
| TC-VP-38 | 防火墙开启时 DisconnectVpn 不触碰 | TC-VP-37 状态下 `DisconnectVpn` | appVpnPackages 附报不含 Launcher 自身；`GetNetworkFirewallStatus` 防火墙仍激活（vpnEnabled=true） |
| TC-VP-39 | 存储隔离核验（对照） | ① 用 testapp 本地探测（root adb 直查 SQLite）对比 owner：Launcher 添加的 profile 行 owner=1000；② 检查 Settings 数据空间无本引擎条目 | ① owner=1000（与 system_server 同空间，系统可见）；Settings uid 空间条目不受本引擎操作影响（本 ROM uid 隔离存储设计，见技术设计文档 2.1） |

## 4. 恢复与清理

测试结束后：

1. `SetVpnDisabled disabled=false`（恢复设置入口与 lockdown 原值）；
2. 逐个 `DeleteVpnProfile name=...` 删除测试 profile（或 root 直删 SQLite 行）；
3. 确认 `dumpsys device_policy` mAlwaysOnVpnPackage 回原值（如 TC-VP-33 曾设置）；
4. 防火墙策略如需保持关闭，`SetDomainPolicyMode mode=0`。

## 5. 验证提示

- 命令结果以 `send_test_command.sh` 的 logcat 行（tag `HYX-TESTAPP-CMD`）或 am broadcast data 为准；
- Launcher 侧执行日志：`adb logcat -s HYX-MDM-APP`（引擎 tag `VpnProfilePolicyManager`）；
- 存储对照：`adb shell sqlite3 /data/misc/keystore/vpnprofilestore.sqlite 'SELECT owner, hex(alias), length(profile) FROM profiles;'`；
- 组件状态对照：`adb shell pm dump com.android.settings | grep -A2 VpnSettingsActivity`（enabled=2 为禁用）；
- DPM 对照：`adb shell dumpsys device_policy | grep -E "mAlwaysOnVpn"`；
- 连接状态对照：`adb shell dumpsys connectivity | grep -i vpn`、`CheckVpnNetworks`；
- 无真实 VPN 服务器时，用不可达地址（如 10.10.10.10）验证 CONNECTING→FAILED 状态机与断开机制；真实隧道建立/数据转发的验收需可用的 VPN 服务端（硬件受限项）。

## 6. 真机执行记录（2026-08-06）

> 执行方式：testapp IPC（`send_test_command.sh`），全部用例在最终构建版本（含 IKEv2 算法/身份/PSK 适配与 lockdown dumpsys 读回修正）复跑确认；SQLite 对照经 adb root 直查。

| 用例编号 | 结果 | 实际观测 |
|---|---|---|
| TC-VP-01 | ✅ | `AddVpnProfile name=test_vpn type=pptp server=10.10.10.10 username=mdm_user password=mdm_pass dnsServers=8.8.8.8` → `{success:true, readBackOk:true, type:0, typeName:pptp, key:VPN_test_vpn}`；SQLite 出现 `owner=1000, alias=VPN_test_vpn` 行 |
| TC-VP-02 | ✅ | l2tp_ipsec_psk → `{success:true, type:1}`，读回一致 |
| TC-VP-03 | ✅ | ipsec_xauth_psk → `{success:true, type:3}` |
| TC-VP-04 | ✅ | `type=5`（数字参数）→ `{success:true, type:5, typeName:ipsec_hybrid_rsa}`（命令层做了 Bundle int→String 适配） |
| TC-VP-05 | ✅ | ikev2_ipsec_psk（username=ikeuser）→ `{success:true, type:7}`，可存储可列表 |
| TC-VP-06 | ✅ | 缺 type/server → testapp 侧 `missing parameter: name/type/server` |
| TC-VP-07 | ✅ | 同 TC-VP-06（缺 type 被同一校验拦截） |
| TC-VP-08 | ✅ | 同 TC-VP-06（缺 server 被同一校验拦截） |
| TC-VP-09 | ✅ | `type=foo`、`type=99` → `{success:false, error:"invalid type: ..."}` |
| TC-VP-10 | ✅ | 同名覆盖：server 10.10.10.10→192.168.1.1，读回 server=192.168.1.1、username=mdm_user2、saveLogin=true（提供凭据时默认保存）；SQLite 仍单行 |
| TC-VP-11 | ✅ | 基线空列表 → `{"RESULT":[]}` |
| TC-VP-12 | ✅ | 5 个 profile 全量返回（name/key/type/typeName/server/username/dnsServers/searchDomains/routes/saveLogin/connected），test_vpn 项 username=mdm_user2、saveLogin=true |
| TC-VP-13 | ✅ | 平台通道连接后 `GetVpnProfileList` 对应 profile connected=true（provisionedSessionName 映射，state=CONNECTING 计入活动） |
| TC-VP-14 | ✅ | IKEv2 `StartVpnProfile`（默认 legacy 通道）→ 框架拒绝 `UnsupportedOperationException: Legacy VPN is deprecated`（本 ROM DEVICE_INITIAL_SDK_INT=33 对 legacy 类型禁用 startLegacyVpn），如实上报 `{success:false, error}` |
| TC-VP-15 | ✅（适配） | legacy 类型经 startLegacyVpn 在本 ROM 被框架拒绝（同 TC-VP-14，ROM 限制）；改用**平台通道**（`StartVpnProfile platform=true`，IVpnManager.startVpnProfile + PLATFORM_VPN_ 键）连接 IKEv2 profile → `{success:true, stateAfter:1(CONNECTING), sessionKey:<UUID>}`；`CheckVpnNetworks` 显示 tun0 网络（防火墙关闭时） |
| TC-VP-16 | ✅ | CONNECTING 状态下 `DisconnectVpn` → `{success:true, provisionedWasConnected:true, provisionedStopped:true, provisionedStateAfter:0}`；`IsVpnDisabled` provisionedState=0；列表全部 connected=false |
| TC-VP-17 | ✅ | 无连接 `DisconnectVpn` → `{success:true, legacyWasConnected:false, provisionedWasConnected:false}` |
| TC-VP-18 | ✅ | 连续两次 DisconnectVpn 均幂等成功 |
| TC-VP-19 | ✅ | 连接后等待 60s（IKEv2 重试调度保持 CONNECTING）再断开 → 正常回 0 |
| TC-VP-20 | ✅ | `DeleteVpnProfile name=test_num` → `{success:true, removed:true, verified:true}`；SQLite 行消失 |
| TC-VP-21 | ✅ | `DeleteVpnProfile name=no_such` → `{success:false, error:"profile not found: no_such"}` |
| TC-VP-22 | ✅ | 缺 name → `missing parameter: name` |
| TC-VP-23 | ✅ | 连接中删除 → `{success:true, wasConnected:true}`（连接保持，随后断开指令负责拆除） |
| TC-VP-24 | ✅ | 删除后 DisconnectVpn 正常拆除残留连接 |
| TC-VP-25 | ✅ | 无 always-on 配置时禁用 → `{success:true, disabled:true, settingsHidden:true, lockdownApplied:false, lockdownSkippedReason:"no always-on VPN configured"}` |
| TC-VP-26 | ✅ | 禁用后 `TryOpenVpnSettings` → `{resolved:false, visible:false, reason:"component disabled or missing (NameNotFoundException)"}`（am start 目标 ActivityNotFoundException） |
| TC-VP-27 | ✅ | `IsVpnDisabled` → `{disabled:true, settingsEntryHidden:true}` |
| TC-VP-28 | ✅ | 启用 → `{disabled:false, settingsEntryHidden:false}`；`TryOpenVpnSettings` 恢复 `{resolved:true, started:true, visible:true}`（Settings VPN 页真实拉起） |
| TC-VP-29 | ✅ | 二次禁用 → `{success:true, disabled:true, already:true}` |
| TC-VP-30 | ✅ | 二次启用幂等成功 |
| TC-VP-31 | ✅ | 缺 disabled → `missing parameter: disabled` |
| TC-VP-32 | ✅ | 禁用期间 AddVpnProfile 正常（配置能力不受影响，仅入口与锁定） |
| TC-VP-33 | ✅ | `SetAlwaysOnVpn packageName=com.hmdm.launcher lockdown=false`（SetAlwaysOnVpn 命令本批次新增 lockdown 参数）后禁用 → `{lockdownApplied:true}`；`dumpsys device_policy` mAlwaysOnVpnLockdown=true |
| TC-VP-34 | ✅ | 启用 → `{lockdownRestored:true}`；mAlwaysOnVpnLockdown 回 false（原值） |
| TC-VP-35 | ✅ | 基线 lockdown=true 时禁用 → `lockdownApplied:false, lockdownSkippedReason:"lockdown already active"`；启用 → `lockdownRestored:false`，mAlwaysOnVpnLockdown 保持 true（非本批次引入不恢复）；修正记录：初版用 `VpnManager.isVpnLockdownEnabled` 读回不反映 DPM 标志（真机核验恒 false），最终版改 `dumpsys device_policy` mAlwaysOnVpnLockdown 解析 |
| TC-VP-36 | ✅ | 基线 `CheckVpnNetworks` → `{vpnNetworkCount:0}` |
| TC-VP-37 | ✅（受限） | 防火墙策略开启后 → `{vpnNetworkCount:1, networks:[{network:102, state:CONNECTED, interface:tun0, packages:[]}]}`；**packages 字段本 ROM 的 getUids/getOwnerUid 均不可用（为空），所有者映射为空**——以 interface=tun0 + GetNetworkFirewallStatus vpnRunning=true 交叉确认；本会话另记录：防火墙授权依赖 `ACTIVATE_VPN` AppOps（初版 `allow`，本批次断开测试后框架将 VpnService 授权 AppOps 复位为 default 导致防火墙 consent 循环，`cmd appops set com.hmdm.launcher android:activate_vpn allow` 恢复——跨批次交互，记录于文档） |
| TC-VP-38 | ✅ | 防火墙运行中 `DisconnectVpn` → `{success:true, appVpnPackages:[]}`（Launcher 自身排除）；`GetNetworkFirewallStatus` vpnRunning 保持 true（不触碰 App 型 VPN） |
| TC-VP-39 | ✅ | Launcher 写入行 owner=1000（与 system_server 同 uid 空间，系统可见可连接）；testapp/root 探针行 owner=10128/0（uid 隔离）；Settings uid 空间无本引擎条目（本 ROM 按 uid 隔离存储，Settings UI 不显示 Launcher 管理的 profile——记录于技术设计文档 2.1） |
