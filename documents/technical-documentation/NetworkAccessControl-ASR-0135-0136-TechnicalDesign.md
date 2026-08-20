# 网络黑白名单（ASR-0135/0136）技术设计文档

## 1. 需求概述

| 需求 ID | 子功能项 | 需求语义 |
|---|---|---|
| ASR-0135 | 域名黑白名单 | 设置域名黑名单/白名单，限制设备应用可访问的域名（设备级） |
| ASR-0136 | IP 地址黑白名单 | 设置 IP 地址黑名单/白名单，限制设备应用可访问的目标 IP（IPv4/IPv6） |

**归属**：Launcher（MDM）。需求文档备注：ASR-0135/0136 归属「Launcher（MDM）」；对照表建议优先级第 1 项即「网络黑白名单（ASR-0135/0136）：可通过 DPM 全局代理/防火墙或配合 ROM 实现」。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名（`sharedUserId=android.uid.system`，uid=1000）+ device owner 部署；bootloader 锁定，不可改动 system 分区（无 ROM 侧定制能力）。

## 2. 技术选型与可行性核验

### 2.1 可选方案对比

| 方案 | 域名级过滤 | IP 级过滤 | 设备级（全应用） | 依赖 | 结论 |
|---|---|---|---|---|---|
| DPM 全局代理（`setGlobalProxy`） | 仅 HTTP 代理可达域名 | 否 | 仅走代理的应用 | device owner | 不满足 IP 级，语义不符 |
| ROM 定制 Netd/iptables 防火墙 | ✔（需 ROM 支持） | ✔ | ✔ | 改 system 分区（本设备 bootloader 锁定，不可行） | 排除 |
| root + iptables（adb root 重放） | ✔（需 dnsmasq/xt_DNS 类模块配合） | ✔ | ✔ | 依赖 adb root（生产不可用）、重启重放 | 排除 |
| **VpnService 本地防火墙** | ✔（DNS 查询拦截） | ✔（逐包匹配） | ✔（除 Launcher 自身） | 标准 API 21+；device owner 免授权（见 2.2） | **采用** |

选型结论：**VpnService 本地防火墙**，纯 Launcher 侧实现，无 ROM 定制、无 root 依赖，且 Launcher 为 device owner + 平台签名，VPN 建立无需用户授权弹窗（见 2.2）。对照表建议「DPM 全局代理/防火墙」路径经核验不满足 IP 级过滤，未采用。

### 2.2 VPN 授权机制（device owner 免弹窗）

- 标准路径 `VpnService.prepare()` 返回 null 表示已授权，可直接 `Builder.establish()`；
- 若返回授权 Intent（个别 ROM 未对 device owner 豁免），降级路径：`DevicePolicyManager.setAlwaysOnVpnPackage(admin, 本包, false)`（device owner 配置 always-on VPN 无需用户同意，系统授权并自动拉起本服务）；
- 降级成功后在 `onStartCommand` 重试 `prepare()` → 建立；策略全部关闭时清除 always-on 配置（`setAlwaysOnVpnPackage(admin, null, false)`）并停止服务；
- 若两条路径均失败（非 device owner 部署等），`GetNetworkFirewallStatus` 返回 `vpnStatus=CONSENT_REQUIRED`，命令不 crash，可查日志定位。

### 2.3 包过滤引擎（socket 中继模型）

- 建立 VPN：`addAddress(10.11.12.1/24)` + `addRoute(0.0.0.0/0)`（IPv6 仅当物理网络存在 IPv6 默认路由时加 `fd00:1:2:3::1/64` + `::/0`，避免 AAAA 首选导致 IPv4 回退失效），**Launcher 自身经 `addDisallowedApplication` 排除**；
- **数据面采用 socket 中继（NetGuard 模型）而非 tun 写回**：读 tun → 解析 IP 头 → 决策 → 放行包经 protected 真实 socket 转发：
  - UDP：按 4 元组维护 DatagramSocket（连接真实目的），响应按客户端报文模板合成 IP/UDP 头（交换地址端口、重算校验和）写回 tun；
  - TCP：按 4 元组维护 Socket；SYN 建流（异步 connect，成功回 SYN-ACK 通告自选 seq + MSS），客户端数据经 socketOut 直写，服务端数据按 `serverSeqBase+1+delivered` seq、`clientSeqBase+received` ack 合成回写（SYN-ACK 占一个序号，初版漏此偏移导致客户端丢弃全部数据包，实测修复）；跟踪客户端 ACK 做窗口流控；
  - 本 ROM 路由规则（`iif tun0 lookup <空表>`）会丢弃 tun 写回包、且源地址 10.11.12.1 在局域网不可路由，写回透传方案不可行（实测验证）；
- **域名过滤经本地 DNS 服务器实现**：绑定 **tun 地址 10.11.12.1:53**（内核 local 表交付，与发送方路由上下文无关）+ `addDnsServer("10.11.12.1")`：
  - 本 ROM netd 解析器 socket 绑定物理网络，DNS 查询不进入 tun（tun 内拦截永远看不到）；环回地址 127.0.0.1 作为 VPN DNS 被框架拒绝；
  - 收到查询 → 解析域名 → 域名策略：拦截 → 合成 DNS REFUSED（QR+RCODE=5）应答；放行 → 经 protected socket 转发到真实 DNS（**取非 VPN 网络的第一个 IPv4 DNS**，须跳过 VPN 自身网络，否则自循环）→ 原样回传应答；
  - tun 内保留 DNS 载荷拦截作为兜底（硬编码 DNS 的应用）；
  - 端口 53 绑定受 `ip_unprivileged_port_start` 限制的 ROM（本 ROM=1024）需部署时以 root 放开（见第 8 节第 5 条），DNS 服务器带 30s 绑定重试自动恢复；
- 被拦截的 UDP 53 查询合成 DNS REFUSED 应答（复制查询报文、置 QR+RCODE=5、重算 IP/UDP 校验和），应用秒级失败；逐包读取 `NetworkPolicyManager.getPolicy()` 不可变快照（volatile 引用切换），策略变更即时生效，无需重启 VPN。

### 2.4 策略语义

- 域名与 IP 各自独立模式：**0=关闭、1=白名单、2=黑名单**（可组合，如域名白名单 + IP 黑名单）；
- 域名匹配：条目小写化、去首尾点；命中规则 = 完全相等 OR 主机名以 `.条目` 结尾（子域名）OR 条目为 `*`（全部）；
- IP 匹配：IPv4/IPv6 精确地址、IPv4/IPv6 CIDR（如 `192.168.1.0/24`）、`*`（全部）；
- 域名策略开启时自动关闭 Private DNS（DoT 走 netd 物理网络连接，绕过过滤），关闭策略时恢复原值；
- 域名白名单模式的边界（文档化限制）：仅能拦截经 DNS 解析的访问；应用硬编码 IP 直连绕过域名过滤（由 IP 策略管控）；DoH/DoT 在 Private DNS 被关闭后不再存在，但应用内自实现 DoH 仍可绕过；
- 无法解析的包（分片后续片、未知版本等）放行，避免破坏连通性；ICMP 等非 TCP/UDP 协议不中继（丢弃）。

## 3. 命令接口定义（SystemApiInterface.onEvent）

命令经 `ApiBinder.method2Commands` 注册，`CallWithCommand` 包装调用，返回 `Map{"RESULT": 返回值}`。不修改 `SystemApiInterface.aidl` / `lib` 模块，客户端无需升级协议。

| 命令名 | 参数（Map） | 返回 RESULT | 说明 |
|---|---|---|---|
| `SetDomainWhitelist` | domains: String 数组（必填，整体替换） | Boolean | ASR-0135 设置域名白名单 |
| `GetDomainWhitelist` | - | List\<String\> | ASR-0135 获取域名白名单 |
| `SetDomainBlacklist` | domains: String 数组（必填，整体替换） | Boolean | ASR-0135 设置域名黑名单 |
| `GetDomainBlacklist` | - | List\<String\> | ASR-0135 获取域名黑名单 |
| `SetDomainPolicyMode` | mode: int（0/1/2） | Boolean | ASR-0135 设置域名策略模式并即时生效 |
| `GetDomainPolicyMode` | - | int | ASR-0135 获取域名策略模式 |
| `SetIpWhitelist` | ips: String 数组（必填，整体替换；支持精确地址/CIDR/*） | Boolean | ASR-0136 设置 IP 白名单 |
| `GetIpWhitelist` | - | List\<String\> | ASR-0136 获取 IP 白名单 |
| `SetIpBlacklist` | ips: String 数组（必填，整体替换） | Boolean | ASR-0136 设置 IP 黑名单 |
| `GetIpBlacklist` | - | List\<String\> | ASR-0136 获取 IP 黑名单 |
| `SetIpPolicyMode` | mode: int（0/1/2） | Boolean | ASR-0136 设置 IP 策略模式并即时生效 |
| `GetIpPolicyMode` | - | int | ASR-0136 获取 IP 策略模式 |
| `GetNetworkFirewallStatus` | - | Map（见下） | ASR-0135/0136 策略+VPN 状态+拦截计数 |

**GetNetworkFirewallStatus 返回结构**（全部 JDK 类型，符合 AIDL Map 通道约束，避免自定义 Bean 跨进程反序列化失败）：

```
{domainMode:int, domainWhitelist:[], domainBlacklist:[],
 ipMode:int, ipWhitelist:[], ipBlacklist:[],
 vpnRunning:boolean, vpnStatus:String("OFF/STARTING/RUNNING/CONSENT_REQUIRED/AUTHORIZING_ALWAYS_ON/ESTABLISH_FAILED/STOPPED"),
 dnsBlocked:long, packetsBlocked:long, packetsForwarded:long}
```

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> param = new HashMap<>();
param.put("domains", Arrays.asList("example.com"));
Map result = api.onEvent("SetDomainWhitelist", param);
boolean ok = (Boolean) result.get("RESULT");
```

```java
Map result = api.onEvent("GetNetworkFirewallStatus", new HashMap<String, Object>());
Map<String, Object> status = (Map<String, Object>) result.get("RESULT");
boolean running = (Boolean) status.get("vpnRunning");
```

**广播通道**（TestBroadcast，与现有命令一致）：

```bash
adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast \
  -a ON_SQA_INTERFACE_TEST --es event SetDomainPolicyMode --es param '{"mode":1}'
# 或 ./send_test_broadcast.sh SetDomainPolicyMode mode=1
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/NetworkPolicyManager.java            # 新增：策略存储/匹配/缓存快照 + VPN 启停 + Private DNS 管理
├── service/NetworkPolicyVpnService.java       # 新增：VpnService 数据面（UDP/TCP 中继 + 本地 DNS 服务器 + REFUSED 合成）
├── service/command/network/
│   ├── SetDomainWhitelist.java / GetDomainWhitelist.java
│   ├── SetDomainBlacklist.java / GetDomainBlacklist.java
│   ├── SetDomainPolicyMode.java / GetDomainPolicyMode.java
│   ├── SetIpWhitelist.java / GetIpWhitelist.java
│   ├── SetIpBlacklist.java / GetIpBlacklist.java
│   ├── SetIpPolicyMode.java / GetIpPolicyMode.java
│   ├── GetNetworkFirewallStatus.java
│   └── NetworkCommandUtils.java               # 参数解析（list/mode）
├── service/ApiBinder.java                     # 注册 13 条命令
└── broadcast/BootCompletedReceiver.java       # 开机恢复 VPN（策略持久化于 SharedPreferences）

app/src/main/AndroidManifest.xml               # 注册 NetworkPolicyVpnService（BIND_VPN_SERVICE + dataSync 前台类型）

testapp/
├── src/main/java/com/hmdm/testapp/
│   ├── NetworkTestActivity.java               # 新增：域名/IP 黑白名单测试页
│   ├── NetworkVerifier.java                   # 新增：DNS/TCP/HTTP 真机验证（流量经防火墙 VPN）
│   ├── TestActions.java                       # 13 条策略事件 + 3 条验证事件
│   └── MainActivity.java                      # 增加"Network access control"入口
└── src/main/res/layout/activity_network_test.xml  # 新增测试页布局
```

## 5. 策略逻辑

```
SetDomainWhitelist(domains):               # 同构：SetDomainBlacklist / SetIpWhitelist / SetIpBlacklist
  条目规范化（域名小写去点校验字符 / IP 按 parseIp 校验）→ 无效条目跳过
  整体替换 SharedPreferences(StringSet) → commit
  refreshPolicy() 刷新缓存快照 → syncVpn()（任一模式开启则启动 VPN，全部关闭则停止）

SetDomainPolicyMode(mode=0/1/2):          # 同构：SetIpPolicyMode
  mode 非法（<0 或 >2）→ false
  写 KEY_DOMAIN_MODE → refreshPolicy() → syncVpn()（开启时同时关闭 Private DNS，关闭时恢复）

本地 DNS 服务器（10.11.12.1:53，tun 地址）：
  收到查询 → parseDnsQueryHost 提取域名
  域名策略（模式 1：不在白名单 → REFUSED；模式 2：在黑名单 → REFUSED）→ 计数 + 日志
  放行 → protect socket 转发至真实 DNS（非 VPN 网络第一个 IPv4 DNS，3s 超时）→ 原样回传应答

VPN 数据面（tun 读循环，逐包）：
  UDP 53：域名策略拦截 → REFUSED 合成回写；放行 → UDP 中继（4 元组 DatagramSocket）
  TCP：域名策略拦截 → 丢弃；放行 → TCP 中继（SYN 建流异步 connect + seq/ack 重写 + 窗口流控）
  其余包按目的 IP 匹配 IP 策略（白名单外/黑名单内 → 丢弃）
  非 TCP/UDP（ICMP 等）不中继；分片后续片放行

VPN 生命周期：
  任一模式开启 → ensureVpnRunning()（prepare 免授权 or vpn_prepared 授权 → establish → 前台通知 + 循环线程）
  全部模式关闭 → teardownAndStop()（停 DNS 服务器 → 关 tun fd → stopSelf，系统解绑并移除接口）
  开机 → BootCompletedReceiver → syncVpn() 恢复（策略持久化于 SharedPreferences）
```

## 6. 权限与归属

- 归属：Launcher（MDM）。无 ROM 侧代码改动，无 root 依赖。
- 新增服务声明：`NetworkPolicyVpnService`（`android.permission.BIND_VPN_SERVICE` 权限保护，`exported=false`，前台服务类型 dataSync）。
- 依赖 platform 签名 + device owner 部署：
  - VpnService 建立授权：device owner 免弹窗（AOSP 对 DO/PO 豁免 VPN consent）；个别 ROM 不支持时走 `dpm.setAlwaysOnVpnPackage` 降级（DO 配置 always-on VPN 无需用户同意）；
  - `addDisallowedApplication` 排除 Launcher 自身，其后台通信不受管控。
- 不修改 AIDL / lib 模块；testapp 新增 `INTERNET` 权限 + `usesCleartextTraffic`（HTTP 直连验证用）。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| mode 非法（-1/3 等） | 返回 false，Logger 记录，不 crash |
| 域名条目非法（空/含非法字符） | 该条目跳过（其余正常保存），Logger 记录 |
| IP 条目非法（非地址格式/坏 CIDR） | 该条目跳过，Logger 记录 |
| 空名单 + 白名单模式 | 全部域名/IP 被拦截（严格白名单语义） |
| 黑名单条目 `*` | 全部命中（等价全断网），文档提示慎用 |
| 策略变更后 VPN 未运行 | Set 命令返回 true（持久化成功），VPN 异步拉起；`GetNetworkFirewallStatus` 可见 vpnStatus |
| VPN 授权失败（非 DO 部署） | vpnStatus=CONSENT_REQUIRED，命令不 crash；日志 `establishVpn: consent required` |
| 与真实企业 VPN 冲突 | Android 单 VPN 限制：企业 always-on VPN 存在时本防火墙无法同时建立（文档化限制）；防火墙先建立时企业 VPN 需先断开 |
| 域名白名单 + 应用硬编码 IP 直连 | 域名策略仅拦截 DNS 解析，直连 IP 由 IP 策略管控（文档化限制，见 2.4） |
| 分片/非 IP/无法解析包 | 放行，防断网 |
| DNS over TCP 拦截 | 直接丢弃（不合成应答），应用走 TCP 查询时会超时失败 |
| 进程被杀/系统重启 | START_STICKY + BootCompletedReceiver 重拉；策略在 SharedPreferences 持久化 |
| AIDL 通道返回自定义类 | 统一 JDK Map/List/包装类型（GetNetworkFirewallStatus 全 JDK 结构） |

## 8. 真机验证记录（2026-08-04，Android 13 / API 33 userdebug，平台签名 + device owner）

| 用例 | 结果 |
|---|---|
| 基线网络连通（TC-P-01） | HTTP GET example.com status=200、DNS 可解析；防火墙未运行时 vpnStatus=OFF |
| 启用策略触发 VPN（TC-P-03） | SetDomainPolicyMode → vpnRunning=true、vpnStatus=RUNNING；`dumpsys connectivity` 显示 VPN CONNECTED（tun0） |
| 域名白名单放行（TC-0135-02/04） | 白名单 `example.com` 下 example.com/www.example.com 均解析成功（子域名匹配）；HTTP 200 |
| 域名白名单拦截（TC-0135-03） | baidu.com 解析失败（REFUSED 秒失败）；dnsBlocked 计数增长；logcat `blocked DNS baidu.com` |
| 域名黑名单（TC-0135-05） | 黑名单 `baidu.com` 拦截、example.com 放行；模式即时切换（TC-M-03）无需重启 VPN |
| 黑名单通配 `*`（TC-0135-06） | 全部域名拦截（github.com/example.com 均失败），还原后恢复 |
| 模式关闭（TC-0135-07） | 恢复解析；VPN 随策略关闭自动停止（teardownAndStop → onDestroy → tun0 消失） |
| 非法入参（TC-0135-08/09、TC-0136-06） | 非法域名/IP 条目保存时跳过（Get 回读仅合法条目）；mode=9 返回 false 状态不变 |
| IP 白名单（TC-0136-02/03） | 白名单 `1.1.1.1` 下 TCP 1.1.1.1:80 连通；223.5.5.5:80 超时（SYN 丢弃），packetsBlocked 增长 |
| IP 黑名单 + CIDR（TC-0136-04/05） | 黑名单 `1.1.1.1`、`223.5.5.0/24` 均按预期拦截；非名单 IP 连通 |
| 组合策略（TC-0136-08） | 域名白名单 example.com + IP 黑名单 1.1.1.1 同时生效，互不影响 |
| 空白名单全拦（TC-M-01） | 严格白名单语义（空名单=全部拦截） |
| 缺参/未知事件（TC-M-07） | 返回 `missing parameter: <key>` / `unknown event`，不 crash |
| 重启保持（TC-M-04） | 黑名单模式重启后：BootCompletedReceiver 自动恢复 VPN（vpnRunning=true）、策略回读一致、过滤继续生效；device owner 保持 |
| 环境还原（TC-M-06） | 模式全关 + 名单清空 → vpnRunning=false、tun0 移除；private_dns 保持 off（本设备原为 unset/opportunistic，测试前已手动关闭） |

**部署与实现注意（本次实测踩坑，均为本 ROM 特性）**：
1. **MTK DurASpeed 会抑制 testapp 的后台广播**：屏幕关闭清理后 testapp 进入 suppress list，`am broadcast` 到 TestCommandReceiver 完全不投递（BroadcastQueue 记录 1ms finish、无日志）。部署/测试前需 `dumpsys duraspeed addwhitelist com.hmdm.testapp` 并保持屏幕常亮（`svc power stayon true` + `settings put global stay_on_while_plugged_in 3`）。
2. **本 ROM 的 VPN 授权不豁免 device owner**：`VpnService.prepare()` 返回授权 Intent；`vpn_prepared` secure 设置路径（VpnAlwaysOnManager 同款机制）授权后 `prepare()` 返回 null，无需弹窗。`dpm.setAlwaysOnVpnPackage` 降级路径在本 ROM 抛异常（已记录，未采用）。
3. **tun 直写回灌（passthrough）在本 ROM 不可行**：路由规则 `iif tun0 lookup <空表>` 丢弃回写包，且源地址 10.11.12.1 在局域网侧不可路由。必须采用 socket 中继模型（UDP datagram relay + TCP stream relay 带 seq/ack 重写），实测 TCP/HTTP 正常。
4. **本 ROM netd 解析器 socket 绑定物理网络**：应用 DNS 查询（经 netd）不进入 tun，tun 内拦截永远看不到；且 `addDnsServer(127.0.0.1)` 环回地址被框架拒绝（establish 抛 "Bad address"）。解决方案：本地 DNS 服务器绑定 **tun 地址 10.11.12.1:53**（内核 local 表交付，任何网络上下文可达）+ `addDnsServer("10.11.12.1")`；放行查询转发到真实 DNS（取非 VPN 网络的第一个 IPv4 DNS，须跳过 VPN 自身网络，否则自循环）。
5. **本 ROM 限制非特权低端口绑定**（`net.ipv4.ip_unprivileged_port_start=1024`）：端口 53 绑定需 root。部署时 `adb root && sysctl -w net.ipv4.ip_unprivileged_port_start=0`（每次重启后需重放；DNS 服务器带 30s 绑定重试，sysctl 生效后自动恢复）。标准 Android（该 sysctl 为 0）无需此步骤。
6. **Private DNS（DoT）会绕过域名过滤**（netd 的 DoT 连接走物理网络）：策略开启时 Launcher 自动 `settings put global private_dns_mode off` 并在策略关闭时恢复原值。
7. **netd 解析器缓存**：域名过滤测试需使用未缓存主机名或等待缓存过期，否则命中缓存直接成功/失败（与防火墙无关）。
8. **VPN 停止**：服务被框架以 `android.net.VpnService` 绑定（BIND_AUTO_CREATE|FGS），仅 `stopService`/`stopSelf` 无法销毁；须先关闭 tun fd + DNS 服务器（触发接口移除、框架解绑），再 `stopSelf`（teardownAndStop）。
9. **开机 WiFi 未自动重连**（本设备环境问题）：重启后 WiFi 断开，需 `cmd wifi connect-network Syrius_Guest open` 手动重连（非防火墙问题）。
10. 重启后 `ip_unprivileged_port_start` 回落 1024（内核默认），需按第 5 条重放；其余（策略、VPN 恢复、过滤）均正常。
