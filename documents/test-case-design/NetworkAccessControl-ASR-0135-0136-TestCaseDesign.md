# 网络黑白名单（ASR-0135/0136）测试用例设计文档

## 1. 前置条件

| 项目 | 说明 |
|---|---|
| 设备 | Android 13（API 33）userdebug；平台签名 + device owner 部署；WiFi 已联网（测试目标为公网域名/IP） |
| Launcher | 平台签名（`sharedUserId="android.uid.system"`，uid=1000）安装 `mdm-launcher-*.apk`；`dpm list-owners` 输出含 `DeviceOwner` |
| 测试 APP | 安装 `testapp-debug.apk`（`com.hmdm.testapp`），自动 bind Launcher `ApiService`，日志显示"connected"；清单已含 `INTERNET` |
| 触发方式 | `./send_test_command.sh <事件> key=value`（推荐，IPC 与 UI 按钮等效）；或 testapp `NetworkTestActivity` 按钮 |
| 状态验证 | `./send_test_command.sh GetNetworkFirewallStatus`（策略/`vpnRunning`/`dnsBlocked` 等计数）；`adb shell dumpsys connectivity`（VPN CONNECTED）；logcat `-s NetworkPolicyVpnService`（拦截日志） |
| 已知限制 | ① 域名白名单模式仅拦截经 DNS 解析的访问，硬编码 IP 直连由 IP 策略管控；② 与真实企业 VPN 互斥（Android 单 VPN）；③ DoT 由策略开启时自动关闭 Private DNS 消除，应用内自实现 DoH 仍可绕过；④ 本 ROM 需 root 放开低端口绑定（见下） |
| 本 ROM 部署附加步骤 | ① `adb root && sysctl -w net.ipv4.ip_unprivileged_port_start=0`（每次重启后重放；DNS 服务器 30s 绑定重试自动恢复）；② `dumpsys duraspeed addwhitelist com.hmdm.testapp`（防后台广播抑制）+ `svc power stayon true` / `settings put global stay_on_while_plugged_in 3`；③ 重启后如 WiFi 未自动重连：`cmd wifi connect-network Syrius_Guest open` |

## 2. 用例表

### 2.1 前置与基线

| 编号 | 用例名称 | 前置/步骤 | 预期结果 | 实测 |
|---|---|---|---|---|
| TC-P-01 | 基线网络连通 | `send_test_command.sh TestHttpGet url=http://example.com/`；`TestDnsLookup host=example.com` | HTTP status=200（或 3xx/4xx 均视为连通），DNS resolved=true；此时策略全关 | 通过（status=200，resolved=true） |
| TC-P-02 | 策略全关状态 | `GetNetworkFirewallStatus` | domainMode=0、ipMode=0、vpnRunning=false、vpnStatus=OFF | 通过 |
| TC-P-03 | 启用后 VPN 建立 | `SetDomainPolicyMode mode=1`；随后 `GetNetworkFirewallStatus`（轮询） | RESULT=true；vpnRunning=true、vpnStatus=RUNNING；`dumpsys connectivity` 显示 VPN CONNECTED | 通过 |

### 2.2 ASR-0135 域名黑白名单

| 编号 | 用例名称 | 前置/步骤 | 预期结果 | 实测 |
|---|---|---|---|---|
| TC-0135-01 | 设置/获取域名白名单 | `SetDomainWhitelist 'domains=["example.com"]'`；`GetDomainWhitelist` | RESULT=true；回读 `[example.com]` | 通过 |
| TC-0135-02 | 域名白名单模式放行 | 白名单 `example.com` + `SetDomainPolicyMode mode=1`；`TestDnsLookup host=example.com`；`TestHttpGet url=http://example.com/` | resolved=true；HTTP 可达（status 2xx/3xx/4xx） | 通过（resolved=true；HTTP status=200） |
| TC-0135-03 | 域名白名单拦截非名单域 | 接 TC-0135-02；`TestDnsLookup host=baidu.com`；`TestHttpGet url=http://baidu.com/` | resolved=false（UnknownHostException，REFUSED 秒失败）；HTTP 失败；`GetNetworkFirewallStatus` 的 dnsBlocked 增加 | 通过（秒失败；dnsBlocked=2；logcat `blocked DNS baidu.com`） |
| TC-0135-04 | 子域名匹配 | 白名单 `example.com`，`TestDnsLookup host=www.example.com` | resolved=true（子域名放行） | 通过（含 HTTP 200） |
| TC-0135-05 | 域名黑名单模式 | `SetDomainPolicyMode mode=2` + `SetDomainBlacklist 'domains=["baidu.com"]'`；`TestDnsLookup host=baidu.com`；`TestDnsLookup host=example.com` | baidu.com 拦截（resolved=false）；example.com 放行 | 通过 |
| TC-0135-06 | 域名黑名单通配 | `SetDomainBlacklist 'domains=["*"]'`；`TestDnsLookup host=example.com` | resolved=false（全部拦截）；完成后立即清空黑名单并关模式 | 通过（github.com/example.com 均拦截；注意用未缓存主机名验证） |
| TC-0135-07 | 域名模式关闭 | `SetDomainPolicyMode mode=0`；`TestDnsLookup host=baidu.com` | resolved=true（恢复）；vpnRunning=false（若 IP 模式也关闭） | 通过 |
| TC-0135-08 | 非法域名条目 | `SetDomainWhitelist 'domains=["bad domain","ok.com"]'` | RESULT=true；`GetDomainWhitelist` 仅 `[ok.com]`（非法条目跳过） | 通过 |
| TC-0135-09 | 非法模式 | `SetDomainPolicyMode mode=9` | RESULT=false，模式不变 | 通过 |

### 2.3 ASR-0136 IP 黑白名单

| 编号 | 用例名称 | 前置/步骤 | 预期结果 | 实测 |
|---|---|---|---|---|
| TC-0136-01 | 设置/获取 IP 白名单 | `SetIpWhitelist 'ips=["1.1.1.1","8.8.8.8/32"]'`；`GetIpWhitelist` | RESULT=true；回读两条 | 通过 |
| TC-0136-02 | IP 白名单放行 | 白名单含 `1.1.1.1` + `SetIpPolicyMode mode=1`；`TestTcpConnect host=1.1.1.1 port=80` | connected=true（或非超时错误，如 Connection refused 视为网络可达） | 通过（connected=true） |
| TC-0136-03 | IP 白名单拦截非名单 IP | 接 TC-0136-02；`TestTcpConnect host=223.5.5.5 port=80` | connected=false 且 error 为 SocketTimeoutException（SYN 被丢弃）；packetsBlocked 增加 | 通过（超时；packetsBlocked=8） |
| TC-0136-04 | IP 黑名单拦截 | `SetIpPolicyMode mode=2` + `SetIpBlacklist 'ips=["1.1.1.1"]'`；`TestTcpConnect host=1.1.1.1 port=80` | connected=false（超时）；`TestTcpConnect host=223.5.5.5 port=80` 可达 | 通过 |
| TC-0136-05 | IP 黑名单 CIDR | `SetIpBlacklist 'ips=["223.5.5.0/24"]'`；`TestTcpConnect host=223.5.5.5 port=80` | connected=false（CIDR 命中） | 通过 |
| TC-0136-06 | 非法 IP 条目 | `SetIpWhitelist 'ips=["999.1.1.1","1.1.1.1"]'` | RESULT=true；回读仅 `[1.1.1.1]`（非法条目跳过） | 通过 |
| TC-0136-07 | IP 模式关闭 | `SetIpPolicyMode mode=0`；`TestTcpConnect host=1.1.1.1 port=80` | 恢复可达；vpnRunning=false（若域名模式也关闭） | 通过 |
| TC-0136-08 | 组合策略 | 域名白名单 `example.com`（mode=1）+ IP 黑名单 `1.1.1.1`（mode=2）同时生效；`TestTcpConnect host=1.1.1.1 port=80`、`TestHttpGet url=http://example.com/` | IP 拦截生效（超时）；域名放行（HTTP 可达）；两模式互不影响 | 通过 |

### 2.4 通用与边界

| 编号 | 用例名称 | 前置/步骤 | 预期结果 | 实测 |
|---|---|---|---|---|
| TC-M-01 | 空白名单全拦 | 域名白名单清空（`domains=[]`）+ mode=1；`TestDnsLookup host=example.com` | resolved=false（严格白名单） | 通过 |
| TC-M-02 | 拦截计数与日志 | 黑名单命中后 `GetNetworkFirewallStatus` | dnsBlocked/packetsBlocked 增长；logcat `NetworkPolicyVpnService` 有 `blocked DNS <host>` | 通过 |
| TC-M-03 | 策略即时生效 | 运行中模式 1→2 直接切换；立即 `TestDnsLookup` | 无需重启 VPN，新策略立即生效 | 通过 |
| TC-M-04 | 开机恢复 | 设置域名黑名单模式 2 后重启设备；`GetNetworkFirewallStatus` + `TestDnsLookup host=baidu.com` | 重启后 vpnRunning=true（BootCompletedReceiver 恢复）；黑名单仍拦截 | 通过（重启后自动恢复；baidu.com 仍拦截、example.com 放行） |
| TC-M-05 | Launcher 自身豁免 | 策略开启期间 Launcher 后台通信（如与服务器交互日志） | 不因防火墙中断（`addDisallowedApplication` 排除） | 通过（设计保证：Launcher 排除于 VPN UID 范围之外） |
| TC-M-06 | 清理还原 | 全部模式置 0、名单清空；`GetNetworkFirewallStatus` | domainMode=0、ipMode=0、vpnRunning=false、vpnStatus=OFF | 通过（tun0 移除） |
| TC-M-07 | 缺参/类型错误 | `SetDomainWhitelist`（无 domains 参数）；`SetDomainPolicyMode`（无 mode 参数）；`TestDnsLookup`（无 host） | 返回 `"missing parameter: <key>"`，不 crash | 通过（含未知事件提示） |
| TC-M-08 | 未连接 Launcher | 断开后执行任意策略命令 | 命令入队不 crash；连接后自动重放并输出 RESULT | 设计保证（MdmApiClient 排队重放） |

## 3. 验证要点提示

- 优先以 `GetNetworkFirewallStatus` 的 vpnRunning/vpnStatus 与 dnsBlocked/packetsBlocked 计数复核实际拦截行为；testapp 日志区与 logcat `-s HYX-TESTAPP-CMD` 展示每条命令 RESULT。
- 域名拦截的秒级失败（REFUSED 应答）与 IP 拦截的超时失败（SYN 丢弃）是区分两类策略生效的直接证据；`SocketTimeoutException` = 包被丢弃，`ConnectionException/refused` = 包已送达但服务端拒绝。
- 真机网络环境需公网可达（TC-P-01 基线先行）；若当前网络无公网出口，IP 用例可改用局域网内已知可达 IP 验证相对行为。
- 黑名单 `*` 会全断网：执行 TC-0135-06 后必须立即清空并还原，避免影响后续用例与设备使用。
- **DNS 缓存注意**：netd 解析器会缓存解析结果（正/负），快速连续测试同一域名可能命中缓存（与防火墙无关）；验证过滤请使用未缓存主机名或更换测试域名。
- 清理残留：还原策略（mode=0 + 清空名单）后确认 vpnRunning=false；本 ROM 部署附加步骤（sysctl/duraspeed）见前置条件表；`adb unroot` 还原 adbd 非 root。
