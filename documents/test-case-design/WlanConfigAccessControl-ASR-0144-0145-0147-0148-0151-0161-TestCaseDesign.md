# WLAN 配置/接入管控（ASR-0144/0145/0147/0148/0151/0161）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner；testapp（`com.hmdm.testapp`，平台签名部署）已安装；
- 部署准备：`adb root`、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`（IPC 与 UI 按钮等效，同一 `TestActions.execute()` 引擎）；也可在 testapp UI 的 "WLAN config & access" 页面点按对应按钮；
- 对照命令：`adb shell cmd wifi list-networks`（已保存网络）、`adb shell dumpsys wifi | grep mWifiInfo`（连接状态/SSID/BSSID）、`adb shell logcat -d | grep WifiAccessPolicyManager`（引擎执行日志：`enforce(NETWORK_STATE_CHANGED): violation=... disconnected=true`）；
- 已保存网络基线：`Syrius_Guest`（开放网，netId=0，BSSID `B4:89:01:F1:C0:67`，open+owe 双条目）、`syrius-4px`（wpa2，netId=1）；
- 恢复基线（测试开始时记录、结束时恢复）：无测试网络残留（仅 Syrius_Guest/syrius-4px）、SSID/MAC 模式 0 且名单空、自动连接允许、设备连接 Syrius_Guest；
- 本 ROM 特性（2026-08-06 核验）：① 特权通道：Launcher（NETWORK_SETTINGS 签名权限，manifest 既有声明）addNetwork/removeNetwork/enableNetwork/disableNetwork 全部可用（无需定位/NEARBY_WIFI_DEVICES）；② **单 SSID 双条目**：每个新网络自动生成伴生 OWE 条目（同一 netId 在 getConfiguredNetworks 出现两次），removeNetwork(netId) 一次删除全部、对已删 netId 再调用返回 false（引擎去重 + 读回核对）；③ 黑白名单为"网络回调 + 断连策略"：连接建立后被回调断开，框架自动重连被反复断开（logcat 连续 enforce 日志）；④ 本机连接建立较慢（enableNetwork 后约 8~25 秒），`TryConnectOpenWifi` 8 秒窗口内可能显示 connected=false，以随后 `dumpsys wifi` 为准。

## 2. 测试用例表

### 2.1 ASR-0144 配置 WLAN 参数（ConfigureWifi）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0144-01 配置开放网络 | `./send_test_command.sh ConfigureWifi ssid=HYX-MDM-OPEN securityType=0` | success=true；netId 返回有效值；readBack=verified；`cmd wifi list-networks` 出现该网络 |
| TC-0144-02 配置 WPA2 网络 | `./send_test_command.sh ConfigureWifi ssid=HYX-MDM-WIFI securityType=1 'password=HYX@12345'` | success=true；readBack=verified；list-networks 显示 wpa2 |
| TC-0144-03 列表核对 | `./send_test_command.sh GetSavedWifiNetworks` | count 含新配置网络；securityTypeName=open/wpa2 正确；新增开放网为 open+owe 双条目（预期 ROM 行为） |
| TC-0144-04 同 SSID 重配替换 | 再次 `ConfigureWifi ssid=HYX-MDM-WIFI securityType=1 'password=HYX@12345'` | success=true；本 ROM addNetwork 对相同配置去重返回既有 netId（不触发替换，无 replacedNetId）；列表无重复堆积 |
| TC-0144-05 缺参 | `./send_test_command.sh ConfigureWifi` / 缺 securityType | 返回 `missing parameter: ssid` / `missing parameter: securityType (0=open, 1=wpa2, 2=wep)`，不 crash |
| TC-0144-06 非法类型 | `./send_test_command.sh ConfigureWifi ssid=HYX-MDM-X securityType=9` | error `invalid securityType: 9 (0=open, 1=wpa2, 2=wep)`，不写入 |
| TC-0144-07 非法 SSID | 超 32 字节 SSID | error `invalid ssid (empty, control characters or longer than 32 bytes): ...`，不写入 |
| TC-0144-08 缺密码 | `ConfigureWifi ssid=HYX-MDM-X securityType=1` | error `missing parameter: password (required for securityType 1)`，不写入 |
| TC-0144-09 非法密码 | `ConfigureWifi ssid=HYX-MDM-WIFI securityType=1 'password=abcd'`（<8 字符，且该 SSID 已有保存配置） | error `invalid wpa2 password: 8-63 ASCII characters or 64 hex digits required`；**既有同 SSID 配置保留**（先加后删，addNetwork 失败不触碰旧配置） |

### 2.2 ASR-0151 配置企业 WiFi（ConfigureEnterpriseWifi）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0151-01 PEAP 配置 | `./send_test_command.sh ConfigureEnterpriseWifi ssid=HYX-MDM-EAP eapMethod=0 phase2=3 identity=testuser 'anonymousIdentity=anon@example.com' 'password=testpass123'` | success=true；netId 有效；readBack 中 eapMethod=0、phase2=3、identity=testuser、anonymousIdentity 一致；passwordMasked=masked（特权调用方读回仍掩码，预期） |
| TC-0151-02 TLS 配置 | `ConfigureEnterpriseWifi ssid=HYX-MDM-TLS eapMethod=1 phase2=0 identity=testuser 'password=tls-pass'` | success=true；readBack eapMethod=1、phase2=0 |
| TC-0151-03 SIM 配置（免密码） | `ConfigureEnterpriseWifi ssid=HYX-MDM-SIM eapMethod=4` | success=true；readBack eapMethod=4；passwordMasked=plain（无密码，预期） |
| TC-0151-04 非法 eapMethod/phase2 | eapMethod=9 / phase2=7 | error `invalid eapMethod: 9 (0=PEAP ...)` / `invalid phase2: 7 (0=none ...)`，不写入 |
| TC-0151-05 缺密码 | `ConfigureEnterpriseWifi ssid=HYX-MDM-NOPASS eapMethod=0` | error `missing parameter: password (required for eapMethod 0)`，不写入 |
| TC-0151-06 列表核对 | `GetSavedWifiNetworks` | 企业网络 securityTypeName=eap（含伴生 owe 条目，预期） |

### 2.3 ASR-0145 删除已保存热点（RemoveWifiNetwork）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0145-01 按 SSID 删除 | `./send_test_command.sh RemoveWifiNetwork ssid=HYX-MDM-OPEN` | success=true；removed=true；removedNetworkIds 含全部条目 netId；`GetSavedWifiNetworks` 不再含该 SSID |
| TC-0145-02 删除不存在网络 | `./send_test_command.sh RemoveWifiNetwork ssid=DOES-NOT-EXIST` | success=false + error `saved network not found: DOES-NOT-EXIST`，不 crash |
| TC-0145-03 按 networkId 删除 | `RemoveWifiNetwork networkId=<目标>` | success=true；该 netId 从列表消失 |
| TC-0145-04 缺参 | `./send_test_command.sh RemoveWifiNetwork` | error `missing parameter: networkId or ssid`，不 crash |
| TC-0145-05 删除不存在 networkId | `./send_test_command.sh RemoveWifiNetwork networkId=999` | success=false + error `saved network not found: networkId 999`（与 SSID 路径一致，不误报成功） |

### 2.4 ASR-0147 SSID 黑白名单（Set/GetSsidAccessPolicy、SetSsidAccessWhitelist/Blacklist）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0147-01 基线查询 | `./send_test_command.sh GetSsidAccessPolicy` | success=true；mode=0；modeName=off；whitelist/blacklist=[]；connected=true；currentSsid=Syrius_Guest |
| TC-0147-02 白名单含当前网络 | `SetSsidAccessWhitelist 'ssids=["Syrius_Guest"]'` 后 `SetSsidAccessPolicy mode=1` | success=true；enforcement violation=none、disconnected=false；连接保持 |
| TC-0147-03 白名单切换触发断开 | `SetSsidAccessWhitelist 'ssids=["HYX-MDM-WIFI"]'` | 当前连接被断开（`dumpsys wifi` 显示未连接）；logcat 出现 `enforce(NETWORK_STATE_CHANGED): violation=ssid not in whitelist ... disconnected=true` |
| TC-0147-04 连接建立后拦截 | 白名单不含 Syrius_Guest 时 `./send_test_command.sh TryConnectOpenWifi ssid=Syrius_Guest networkId=0` | enableNetworkResult=true（框架放行）但最终 connected=false；`cmd wifi status` 未连接；logcat 连续断开日志 |
| TC-0147-05 黑名单触发断开 | `SetSsidAccessBlacklist 'ssids=["Syrius_Guest"]'` 后 `SetSsidAccessPolicy mode=2` | enforcement violation=ssid in blacklist、disconnected=true；连接断开 |
| TC-0147-06 非法 mode | `./send_test_command.sh SetSsidAccessPolicy mode=5` | error `invalid mode: 5 (0=off, 1=whitelist, 2=blacklist)`，不写入 |
| TC-0147-07 缺名单参数 | `./send_test_command.sh SetSsidAccessWhitelist` | 返回 `missing parameter: ssids (array)`，不 crash |
| TC-0147-08 非法名单条目 | 名单含超 32 字节 SSID | error `invalid ssid entries (empty, control characters or longer than 32 bytes): [...]`，整单拒绝、原名单不变 |
| TC-0147-09 恢复 | 名单清空 + mode=0 | success=true；设备可重新连接（自动重连或 TryConnectOpenWifi 后 `dumpsys wifi` 显示 Syrius_Guest） |

### 2.5 ASR-0148 MAC 黑白名单（Set/GetMacAccessPolicy、SetMacAccessWhitelist/Blacklist）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0148-01 基线查询 | `./send_test_command.sh GetMacAccessPolicy` | success=true；mode=0；currentBssid=B4:89:01:F1:C0:67（与 `dumpsys wifi` 一致） |
| TC-0148-02 白名单含当前 BSSID | `SetMacAccessWhitelist 'macs=["B4:89:01:F1:C0:67"]'` 后 `SetMacAccessPolicy mode=1` | enforcement violation=none；连接保持；结果 whitelist 字段正确（**校验点：Set 命令返回的名单键名必须为 whitelist 而非 blacklist**，2026-08-06 曾发现键名错位已修复） |
| TC-0148-03 白名单不含当前 BSSID | `SetMacAccessWhitelist 'macs=["AA:BB:CC:DD:EE:FF"]'` | logcat `enforce(...): violation=bssid not in whitelist ... disconnected=true`；连接断开 |
| TC-0148-04 黑名单命中 | 连接恢复后 `SetMacAccessBlacklist 'macs=["B4:89:01:F1:C0:67"]'` 后 `SetMacAccessPolicy mode=2` | 连接建立后被断开（logcat `violation=bssid in blacklist`）；TryConnectOpenWifi 探测 connected=false |
| TC-0148-05 非法 MAC 格式 | `./send_test_command.sh SetMacAccessBlacklist 'macs=["not-a-mac"]'` | error `invalid mac entries (expected AA:BB:CC:DD:EE:FF): [not-a-mac]`，整单拒绝 |
| TC-0148-06 恢复 | 名单清空 + mode=0 | success=true；设备重新连接 |

### 2.6 ASR-0161 自动连接策略（Set/GetWifiAutoConnectPolicy）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0161-01 基线查询 | `./send_test_command.sh GetWifiAutoConnectPolicy` | success=true；autoConnectForbidden=false；已保存网络全部 enabled=true |
| TC-0161-02 禁止自动连接 | `./send_test_command.sh SetWifiAutoConnectPolicy enabled=true` | success=true；autoConnectForbidden=true；disabledNetworkIds 覆盖全部已保存网络 netId（去重）；disconnectApplied=true；`GetWifiAutoConnectPolicy` 复查全部 enabled=false |
| TC-0161-03 无自动重连 | 禁止状态下等待 ≥15 秒 | `dumpsys wifi` 保持未连接（自动加入被抑制） |
| TC-0161-04 禁止期间新网络 | 禁止状态下 `ConfigureWifi ssid=HYX-MDM-AUTOCONNECT securityType=0` 后 `GetSavedWifiNetworks` | 新条目 enabled=false（CONFIGURED_NETWORKS_CHANGED 回调自动禁用） |
| TC-0161-05 缺参 | `./send_test_command.sh SetWifiAutoConnectPolicy` | 返回 `missing parameter: enabled`，不 crash |
| TC-0161-06 允许自动连接 | `./send_test_command.sh SetWifiAutoConnectPolicy enabled=false` | success=true；autoConnectForbidden=false；enabledNetworkIds 覆盖全部网络；复查全部 enabled=true；设备随后自动重连 Syrius_Guest（或经 TryConnectOpenWifi） |

### 2.7 通用用例

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-M-01 缺参容错 | 各 Set 命令缺参执行（ConfigureWifi/ConfigureEnterpriseWifi/SetSsidAccessPolicy/SetMacAccessPolicy/SetWifiAutoConnectPolicy/名单命令） | 均返回缺参提示，不 crash |
| TC-M-02 未知事件 | `./send_test_command.sh ConfigureWifiXXX` | 返回 unknown event，不 crash |
| TC-M-03 事件目录 | `./send_test_command.sh ListEvents` | 目录含 15 个新事件（logcat 行超长被截断属 logcat 限制，以单事件可调用为准） |
| TC-M-04 UI 等效 | testapp UI "WLAN config & access" 页逐一按钮（`dumpsys activity top` 核对 21 个按钮 + 分组标题渲染） | 按钮与 IPC 事件一一对应（同一 TestActions 引擎）；页面正常渲染 |
| TC-M-05 返回值 JDK 类型 | 检查命令返回 JSON | 全部为 Map/List/基本类型，无自定义类（AIDL 通道约束；WifiConfiguration 对象仅存在于 Launcher 进程内，输出为字符串/整数列表） |
| TC-M-06 持久化与重新武装 | 设置 MAC 白名单 mode=1 后重装 Launcher（进程重启） | 策略保持（SharedPreferences）；接收器经 syncPolicy 重新注册；白名单含当前 BSSID 时连接不被误断 |
| TC-M-07 状态恢复 | 用例执行结束后复查 | 无测试网络残留、SSID/MAC 模式 0 名单空、autoConnectForbidden=false、设备连接 Syrius_Guest，无测试残留 |

## 3. 验证提示

- 结果查看：`./send_test_command.sh` 输出 `Broadcast completed: data=...` 与 logcat `HYX-TESTAPP-CMD` 行；
- 已保存网络对照：`adb shell cmd wifi list-networks`（Syrius_Guest=0、syrius-4px=1；新网络 netId 递增）；
- 连接状态对照：`adb shell dumpsys wifi | grep mWifiInfo`（`SSID: "Syrius_Guest"` 已连接 / `<unknown ssid>` 未连接；BSSID 大小写与本机一致）；
- 引擎执行日志：`adb shell logcat -d | grep WifiAccessPolicyManager`（模式/名单变更即时评估 `enforce(replaceList .../setSsidPolicyMode ...)`、网络回调持续执行 `enforce(NETWORK_STATE_CHANGED): violation=ssid/bssid not in whitelist|in blacklist ... disconnected=true`、`syncPolicy` 重新武装日志）；
- 名单持久化文件（排查用）：`/data/data/com.hmdm.launcher/shared_prefs/wifi_access_policy.xml`；
- 网络配置持久化文件（排查用，root）：`/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml`；
- 数值参数说明：mode/securityType/eapMethod/phase2/networkId 支持数值与数字字符串；布尔参数 `true`/`false` 透传为 Boolean；
- UI 注意：本机（银星 ROM）通知栏偶发遮挡（`mCurrentFocus=NotificationShade`），UI 渲染核对用 `dumpsys activity top` 的 ViewHierarchy（按钮 id/文本齐全即通过），uiautomator dump 被 SystemUI 窗口覆盖时不代表应用异常。

## 4. 实测结果（2026-08-06，Android 13 / API 33 userdebug，平台签名 + device owner）

全部用例经 `send_test_command.sh` IPC 通道执行（与 UI 按钮共用 TestActions 引擎），关键系统状态经 adb 对照。**设备基线（实测）**：WiFi 连接 Syrius_Guest（开放网，netId=0，BSSID `B4:89:01:F1:C0:67`）；已保存网络 4 条目（Syrius_Guest open+owe、syrius-4px wpa2-psk+wpa3-sae）；无任何接入策略。

| 用例 | 实测结果 |
|---|---|
| TC-0144-01 | 通过：`{"securityTypeName":"open","netId":2,"ssid":"HYX-MDM-OPEN","success":true,"securityType":0,"readBack":"verified"}` |
| TC-0144-02 | 通过：`{"securityTypeName":"wpa2","netId":3,...,"readBack":"verified"}` |
| TC-0144-03 | 通过：count=7（新增 HYX-MDM-OPEN open+owe 双条目、HYX-MDM-WIFI wpa2） |
| TC-0144-04 | 通过：重配返回既有 netId（addNetwork 去重，`{"netId":11,...,"readBack":"verified"}` 无 replacedNetId）；列表无重复 |
| TC-0144-05 | 通过：`missing parameter: ssid`；缺 securityType → `missing parameter: securityType (0=open, 1=wpa2, 2=wep)` |
| TC-0144-06 | 通过：`{"error":"invalid securityType: 9 (0=open, 1=wpa2, 2=wep)","success":false}` |
| TC-0144-07 | 通过：`invalid ssid (empty, control characters or longer than 32 bytes): THIS-SSID-...` |
| TC-0144-08 | 通过：`{"error":"missing parameter: password (required for securityType 1)","success":false}` |
| TC-0144-09 | 通过（2026-08-06 审查修复后回归）：`{"error":"invalid wpa2 password: 8-63 ASCII characters or 64 hex digits required","success":false}`；`GetSavedWifiNetworks` 复核既有 HYX-MDM-WIFI 配置保留 |
| TC-0151-01 | 通过：netId=5；readBack `{"phase2":3,"eapMethod":0,"identity":"testuser","passwordMasked":"masked","networkId":5,"ssid":"HYX-MDM-EAP","anonymousIdentity":"anon@example.com"}` |
| TC-0151-02 | 通过：netId=6；readBack eapMethod=1、phase2=0、identity=testuser |
| TC-0151-03 | 通过：netId=7；readBack eapMethod=4；`passwordMasked":"plain"` |
| TC-0151-04 | 通过：`invalid eapMethod: 9 (0=PEAP 1=TLS 2=TTLS 3=PWD 4=SIM 5=AKA 6=AKA_PRIME)`；`invalid phase2: 7 (0=none 1=PAP 2=MSCHAP 3=MSCHAPv2 4=GTC)` |
| TC-0151-05 | 通过：`{"error":"missing parameter: password (required for eapMethod 0)","success":false}` |
| TC-0151-06 | 通过：企业网络 securityTypeName=eap（伴生 owe 条目同为预期 ROM 行为） |
| TC-0145-01 | 通过：`{"removedNetworkIds":[2],"removed":true,"netId":2,"ssid":"HYX-MDM-OPEN","success":true}`（open+owe 双条目一次删净）；`GetSavedWifiNetworks` 复核无残留 |
| TC-0145-02 | 通过：`{"error":"saved network not found: DOES-NOT-EXIST","netId":-1,"success":false}` |
| TC-0145-03 | 通过：按 networkId 删除 success=true（HYX-MDM-SIM netId=7） |
| TC-0145-04 | 通过：`{"error":"missing parameter: networkId or ssid","success":false}` |
| TC-0145-05 | 通过（2026-08-06 审查修复后回归）：`{"RESULT":{"removed":false,"error":"saved network not found: networkId 999","netId":999,"ssid":"","success":false}}` |
| TC-0147-01 | 通过：`{"mode":0,"connected":true,"currentSsid":"Syrius_Guest","currentBssid":"B4:89:01:F1:C0:67",...,"whitelist":[],"blacklist":[]}` |
| TC-0147-02 | 通过：SetSsidAccessPolicy mode=1 → enforcement `{"violation":"none","disconnected":false}`，连接保持 |
| TC-0147-03 | 通过：名单切换后 logcat `WifiAccessPolicyManager enforce(NETWORK_STATE_CHANGED): violation=ssid not in whitelist ssid=Syrius_Guest ... disconnected=true` 连续 5+ 次；`dumpsys wifi` 未连接 |
| TC-0147-04 | 通过：`{"savedNetId":0,"enableNetworkResult":true,"connected":false,"connectedSsid":"<unknown ssid>"}`——enableNetwork 被框架放行但连接被回调断开 |
| TC-0147-05 | 通过：`SetSsidAccessPolicy mode=2` → enforcement `{"violation":"ssid in blacklist","disconnected":true}` |
| TC-0147-06 | 通过：`{"error":"invalid mode: 5 (0=off, 1=whitelist, 2=blacklist)","success":false}` |
| TC-0147-07 | 通过：`missing parameter: ssids (array)` |
| TC-0147-08 | 通过：`{"error":"invalid ssid entries (empty, control characters or longer than 32 bytes): [...]","success":false}` |
| TC-0147-09 | 通过：mode=0 + 名单清空后设备自动重连 Syrius_Guest（`dumpsys wifi` SSID 确认） |
| TC-0148-01 | 通过：`{"mode":0,"currentBssid":"B4:89:01:F1:C0:67",...,"whitelist":[],"blacklist":[]}` |
| TC-0148-02 | 通过：mode=1 → enforcement violation=none；连接保持；SetMacAccessWhitelist 返回键名 whitelist 正确（键名错位缺陷已修复后复测） |
| TC-0148-03 | 通过：logcat `enforce(replaceList macWhitelist): violation=bssid not in whitelist ... disconnected=true` |
| TC-0148-04 | 通过：连接建立后 logcat `enforce(NETWORK_STATE_CHANGED): violation=bssid in blacklist ... disconnected=true`；TryConnectOpenWifi connected=false |
| TC-0148-05 | 通过：`{"error":"invalid mac entries (expected AA:BB:CC:DD:EE:FF): [not-a-mac]","success":false}` |
| TC-0148-06 | 通过：mode=0 + 名单清空，设备重连 Syrius_Guest |
| TC-0161-01 | 通过：`autoConnectForbidden=false`；全部网络 enabled=true |
| TC-0161-02 | 通过：`disabledNetworkIds":[0,1,4,5,6,8]`（去重）、`disconnectApplied:true`；复查全部 enabled=false |
| TC-0161-03 | 通过：15 秒观察窗口 `dumpsys wifi` 保持未连接 |
| TC-0161-04 | 通过：新配置 HYX-MDM-AUTOCONNECT 立即 `enabled:false`（回调自动禁用） |
| TC-0161-05 | 通过：`missing parameter: enabled` |
| TC-0161-06 | 通过：`enabledNetworkIds":[0,1]`（去重）；复查全部 enabled=true；设备随后重连 Syrius_Guest |
| TC-M-01 | 通过：8 个 Set 命令缺参均返回缺参提示，不 crash |
| TC-M-02 | 通过：`ConfigureWifiXXX` → unknown event |
| TC-M-03 | 通过：ListEvents 含 15 个新事件（logcat 行 4KB 截断为 logcat 限制，单事件可调用验证全过） |
| TC-M-04 | 通过（dumpsys activity top）：WifiAccessTestActivity 渲染 21 个按钮 + 6 个分组标题，按钮 id 与布局一致（本机通知栏偶发遮挡为环境现象） |
| TC-M-05 | 通过：返回值全 JDK Map/List/基本类型 |
| TC-M-06 | 通过：MAC 白名单 mode=1 状态在 Launcher 重装（进程重启）后保持（`wifi_access_policy.xml`），白名单含当前 BSSID 不断连 |
| TC-M-07 | 通过：测试网络全部删除（恢复 4 条目基线）、模式 0 名单空、autoConnectForbidden=false、设备连接 Syrius_Guest |

**实现决策记录（2026-08-06 真机核验）**：① 黑白名单（ASR-0147/0148）不采用 DPM `setWifiSsidPolicy`（该接口仅 ALLOWLIST 且本 ROM fork 的 `isAdminRestrictedNetwork` 无黑名单消费方），按 Sheet1 P2 规划落地为 Launcher 侧"网络回调 + 断连策略"引擎（动态广播接收器 + SharedPreferences 持久化 + 进程重启/开机重新武装），黑名单/白名单/自动连接策略共用同一回调通道；② ASR-0161 采用 disableNetwork/enableNetwork 全量禁用/启用已保存网络 + CONFIGURED_NETWORKS_CHANGED 回调对新网络自动禁用；③ 本 ROM 单 SSID 双条目（伴生 OWE）使 removeNetwork 与名单禁用出现同 netId 重复调用，引擎以 netId 去重 + 读回核对判定；④ 开发期发现并修复四个缺陷：`SetMacAccessWhitelist` 返回键名误置 blacklist（结果标签错位，持久化本身正确）、SSID 删除对双条目误报 removed=false（重复 removeNetwork 返回 false 干扰判定，改去重 + 读回核对）、删除不存在 networkId 误报 success=true（改显式 not found，TC-0145-05）、同 SSID 替换先删后加可能丢失既有配置（改先加后删 + WPA2/WEP 密码格式预校验，TC-0144-09）；另对 ConfigureWifi/ConfigureEnterpriseWifi 的 password/identity/anonymousIdentity 参数做日志脱敏（ApiBinder 与 TestBroadcast 通道均不落明文，logcat `param:{password=***,...}` 可核验）。

**部署注意**：本批次不修改 `device_admin.xml`、无新增 manifest 权限、**无需重启 framework**；`adb install -r` 重装 Launcher 会结束其进程且不会自动重启，需重新拉起 HOME（`am start -a android.intent.action.MAIN -c android.intent.category.HOME`）后 testapp 进程亦需重启（root `kill <pid>` 后 `am start`，勿用 force-stop——MTK DuraSpeed 会抑制其 manifest receiver）以重建 AIDL 绑定。
