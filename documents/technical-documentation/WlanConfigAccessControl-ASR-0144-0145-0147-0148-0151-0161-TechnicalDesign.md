# WLAN 配置/接入管控（ASR-0144/0145/0147/0148/0151/0161）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 实现机制 |
|---|---|---|---|
| ASR-0144 | WLAN | 配置 WLAN 参数（添加/配置个人热点：开放/WPA2/WEP） | `WifiManager.addNetwork`（平台签名特权通道） |
| ASR-0151 | WLAN强管控 | 配置企业 Wifi（EAP 企业网络） | `WifiManager.addNetwork` + `WifiEnterpriseConfig`（PEAP/TLS/TTLS/PWD/SIM/AKA/AKA_PRIME） |
| ASR-0145 | WLAN | 删除已保存的指定 WIFI 热点 | `WifiManager.removeNetwork` |
| ASR-0147 | WLAN | SSID 黑白名单（仅名单内/剔除名单内 SSID 可连接） | Launcher 侧策略引擎 + Wi-Fi 网络回调（NETWORK_STATE_CHANGED）断连策略 |
| ASR-0148 | WLAN | MAC 黑白名单（按 AP BSSID 管控） | 同上（按 BSSID 匹配） |
| ASR-0161 | WLAN强管控 | 禁止/允许自动连接 WiFi 热点策略 | `WifiManager.disableNetwork/enableNetwork` 全量禁/启已保存网络 + 回调持续纠正 |

**归属**：按需求文档归属列，六项均为「Launcher（MDM）」（ASR-0161 需求文档标注「Launcher（MDM）+ 系统 API」，落地为平台签名特权调用 `WifiManager` 系列接口）。本批次**无新增 manifest 权限、无 shell、无 ROM 改动、不修改 `device_admin.xml`**：Launcher manifest 既有 `NETWORK_SETTINGS`（signature|privileged，热点批次新增）与 `ACCESS_WIFI_STATE`/`CHANGE_WIFI_STATE`（既有）声明，平台签名 uid=1000 自动授予，Wi-Fi 服务将其视为特权调用方，`getConfiguredNetworks`/`addNetwork`/`removeNetwork`/`enableNetwork`/`disableNetwork` 均无需定位/NEARBY_WIFI_DEVICES 即可调用。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署；bootloader 锁定。测试设备基线（2026-08-06 实测）：WiFi 连接 `Syrius_Guest`（开放网，netId=0，BSSID `B4:89:01:F1:C0:67`，另有 OWE 伴生条目）；已保存网络：`Syrius_Guest`（open+owe 双条目）、`syrius-4px`（wpa2-psk+wpa3-sae 双条目）；无 SSID/MAC/自动连接策略。

## 2. 技术选型与可行性核验

### 2.1 本 ROM 网络配置存储特性（2026-08-06 真机核验）

- **addNetwork 返回有效 netId**：平台签名非特权应用（testapp）与平台签名特权应用（Launcher，NETWORK_SETTINGS）均可调用 `WifiManager.addNetwork` 成功（本批次真机：netId 2~10 连续可用）；ASR-0149 WLAN 权限黑名单生效时 addNetwork 返回 -1（既有记录，命令如实上报）；
- **单 SSID 双条目**：本 ROM 的 WifiConfigManager 为每个新网络自动生成**伴生 OWE 条目**（开放网：open+owe；企业网：EAP+owe；WPA2 网：wpa2-psk 无伴生，但既有 `syrius-4px` 为 wpa2-psk+wpa3-sae 双条目）——同一 netId 在 `getConfiguredNetworks` 中出现两次，`removeNetwork(netId)` 一次调用即删除全部伴生条目，再次对同一 netId 调用返回 false（引擎按 netId 去重 + 读回核对判定，见 5.2）；
- **网络列表持久化**：Wi-Fi 服务自行将网络列表写入 `WifiConfigStore.xml`，addNetwork/removeNetwork 后重启保持；Launcher 不直写该文件；
- **getConfiguredNetworks 返回**：对特权调用方完整返回（含 networkId/SSID/安全参数/enterpriseConfig）；`cmd wifi list-networks` 可对照（本机 Syrius_Guest=0、syrius-4px=1，与命令返回一致）。

### 2.2 ASR-0144/0151 配置通道（WifiManager.addNetwork）

**语义**：ASR-0144 配置 WLAN 参数——添加开放/WPA2/WEP 网络；ASR-0151 配置企业 WiFi——添加 EAP 网络（需求文档明确 `WifiManager.addNetwork`）。

**安全类型构建**（经典字段法，规避本 ROM fork SDK 可能改动的 `setSecurityParams`）：

| 类型 | WifiConfiguration 字段 |
|---|---|
| open | `allowedKeyManagement: NONE` |
| wpa2 | `allowedKeyManagement: WPA_PSK+SAE`；`allowedProtocols: WPA+RSN`；`allowedPairwiseCiphers: CCMP`；`allowedGroupCiphers: CCMP+TKIP`；`preSharedKey="<密码>"` |
| wep | `allowedKeyManagement: NONE`；`allowedAuthAlgorithms: OPEN+SHARED`；`allowedGroupCiphers: WEP40+WEP104`；`wepKeys[0]`（5/13 字符 ASCII 加引号，否则按十六进制）；`wepTxKeyIndex=0` |
| eap | `allowedKeyManagement: NONE` + `WifiEnterpriseConfig`（见下） |

**企业配置（ASR-0151）**：`WifiEnterpriseConfig.setEapMethod`（0=PEAP 1=TLS 2=TTLS 3=PWD 4=SIM 5=AKA 6=AKA_PRIME）、`setPhase2Method`（0=none 1=PAP 2=MSCHAP 3=MSCHAPv2 4=GTC，PEAP/TTLS 使用）、`setIdentity`、`setAnonymousIdentity`、`setPassword`；SIM/AKA/AKA_PRIME 无需密码。CA 证书参数（TLS 常用）本批次未开放（需证书导入通道，见边界 7.8）。

**写后读回核对**：addNetwork 成功后经 `getConfiguredNetworks` 按 netId 读回（SSID/eapMethod/phase2/identity/anonymousIdentity 逐项核对；enterprise 密码读回为 `*` 掩码时如实上报 `passwordMasked:masked`——本 ROM 对特权调用方仍掩码，属预期）。

**同 SSID+同安全类型替换**：本 ROM 允许同 SSID 不同安全类型共存，引擎在 addNetwork 前查找同 SSID 且同安全类型的已保存网络并先删除（返回 `replacedNetId`），保证读回确定性、避免测试期重复堆积。

**连接选项**：`connect=true` 时 `enableNetwork(netId, true)` 触发连接（与 WifiVerifier 真机验证同路径，本 ROM 有效）。

### 2.3 ASR-0145 删除通道（WifiManager.removeNetwork）

按 `networkId`（优先）或按 SSID 精确匹配定位；**SSID 删除删除该 SSID 的全部条目**（本 ROM 单 SSID 双条目，语义为"删除该热点"）。定位失败如实返回 `saved network not found`；删除后读回核对（`getConfiguredNetworks` 不再含该 SSID/netId）。

### 2.4 ASR-0147/0148 黑白名单（网络回调 + 断连策略）

**方案决策**：DPM 侧仅提供 SSID 白名单（ASR-0152 `setWifiSsidPolicy` ALLOWLIST，本 ROM fork 亦无黑名单消费方——`isAdminRestrictedNetwork` 只处理 ALLOWLIST），**无法覆盖 SSID 黑名单与 MAC（BSSID）名单**；ASR-0147 的"黑白名单"与 ASR-0148 的"MAC 黑白名单"落地为 **Launcher 侧策略引擎 + Wi-Fi 网络回调断连策略**（Sheet1 P2 规划路径）：

- **网络回调**：动态注册 `BroadcastReceiver` 监听 `WifiManager.NETWORK_STATE_CHANGED_ACTION`（框架的 Wi-Fi 网络状态回调广播，携带已连接 WifiInfo）+ `WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION` + `WIFI_STATE_CHANGED_ACTION`；进程重启（ApiService.onCreate）/开机（BootCompletedReceiver）经 `syncPolicy` 重新注册（与既有策略引擎同模式）；
- **断连策略**：回调触发时读取当前连接（`getConnectionInfo()`：SSID + BSSID，supplicant COMPLETED 且非占位值 `02:00:00:00:00:00`/`<unknown ssid>` 才判定已连接），按策略判定违规即 `WifiManager.disconnect()`；框架自动重连会再次触发回调再次断连（2026-08-06 实测 logcat 连续 5+ 次 `enforce(NETWORK_STATE_CHANGED): violation=... disconnected=true`），构成持续执行闭环；
- **策略模型**（与 ASR-0075/0149 同风格）：模式 0=关闭 / 1=白名单（仅名单内可连接）/ 2=黑名单（名单内断开），名单整体替换；持久化 SharedPreferences `wifi_access_policy`（ssidMode/ssidWhitelist/ssidBlacklist/macMode/macWhitelist/macBlacklist/autoConnectForbid）；
- **设置即生效**：任何模式/名单变更立即对当前连接执行一次评估（`enforce`），命令返回值含 `enforcement`（violation/disconnected）与当前连接上下文（currentSsid/currentBssid/connected）；
- **无连接时拦截**：引擎无法拦截其他调用方的 addNetwork/enableNetwork（非 DPM 策略无 connect-time 消费方），违规连接在建立后被回调断开（"断连策略"语义，与需求描述一致）；
- **MAC 归一化**：BSSID 统一大写 `AA:BB:CC:DD:EE:FF` 格式校验与比对；`02:00:00:00:00:00` 为未连接占位值，不参与判定。

### 2.5 ASR-0161 自动连接策略（enableNetwork/disableNetwork）

**语义**：禁止后设备不得自动连接任何已保存热点（用户手动连接亦被断连策略兜底；允许后恢复自动连接）。

**落地**：
- 禁止（`enabled=true`）：遍历已保存网络逐个 `WifiManager.disableNetwork(netId)`（去重后逐 netId 执行，本 ROM 伴生条目共享 netId）+ 断开当前连接（`disconnectApplied` 上报）；此后框架不会自动加入任何已保存网络（实测 15 秒观察窗口无重连）；
- 新保存网络：`CONFIGURED_NETWORKS_CHANGED` 回调对禁止期间新添加的网络立即执行 disableNetwork（实测 ConfigureWifi 后新条目 `enabled:false`）；
- 允许（`enabled=false`）：遍历已保存网络逐个 `enableNetwork(netId, false)`（仅启用不主动连接），设备按框架自动加入策略恢复连接；
- 持久化 `autoConnectForbid` 标志，进程重启/开机经 `syncPolicy` 重新武装（重新执行全量禁用 + 评估）。

### 2.6 系统配置声明

- 本批次写入的系统状态：**Wi-Fi 网络配置**（`/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml`，框架持久化）——addNetwork/removeNetwork 增删条目、enableNetwork/disableNetwork 置位网络启用状态，均由 Wi-Fi 服务落盘，Launcher 不直写文件；
- 策略状态（SSID/MAC 模式与名单、autoConnectForbid）持久化于 **Launcher 私有 SharedPreferences** `wifi_access_policy`（非系统配置）；
- **不修改** `device_admin.xml`、不写 Settings 键、**无需重启 framework**。

## 3. 命令接口定义（SystemApiInterface.onEvent）

### 3.1 新增命令（本批次，15 条）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `ConfigureWifi` | ssid（String，必填）、securityType（int，必填 0=open 1=wpa2 2=wep）、password（String，wpa2/wep 必填）、connect（boolean，可选） | Map：{success, netId, ssid, securityType, securityTypeName, readBack, replacedNetId?, connectResult?} 或 {error} | ASR-0144 |
| `ConfigureEnterpriseWifi` | ssid（String，必填）、eapMethod（int，必填 0~6）、phase2（int，可选默认 0）、identity、anonymousIdentity、password（除 SIM/AKA/AKA_PRIME 外必填）、connect（可选） | Map：{success, netId, ssid, eapMethod, eapMethodName, phase2, phase2Name, identity, anonymousIdentity, readBack, replacedNetId?, connectResult?} 或 {error} | ASR-0151 |
| `RemoveWifiNetwork` | networkId（int，可选，优先）或 ssid（String，可选） | Map：{success, removed, netId, removedNetworkIds, ssid} 或 {error} | ASR-0145 |
| `GetSavedWifiNetworks` | 无 | Map：{success, count, networks:[{networkId, ssid, securityType, securityTypeName, enabled}]} | 辅助（ASR-0144/0145/0161 定位与核对） |
| `SetSsidAccessPolicy` | mode（int，必填 0/1/2） | Map：{success, mode, modeName, whitelist, blacklist, 当前连接, enforcement} 或 {error} | ASR-0147 |
| `GetSsidAccessPolicy` | 无 | Map：{success, mode, modeName, whitelist, blacklist, 当前连接} | ASR-0147 |
| `SetSsidAccessWhitelist` | ssids（String 数组，必填，整体替换） | Map：{success, whitelist, 当前连接} 或 {error} | ASR-0147 |
| `SetSsidAccessBlacklist` | ssids（String 数组，必填，整体替换） | Map：{success, blacklist, 当前连接} 或 {error} | ASR-0147 |
| `SetMacAccessPolicy` | mode（int，必填 0/1/2） | Map：{success, mode, modeName, whitelist, blacklist, 当前连接, enforcement} 或 {error} | ASR-0148 |
| `GetMacAccessPolicy` | 无 | Map：{success, mode, modeName, whitelist, blacklist, 当前连接} | ASR-0148 |
| `SetMacAccessWhitelist` | macs（String 数组 `AA:BB:CC:DD:EE:FF`，必填，整体替换） | Map：{success, whitelist, 当前连接} 或 {error} | ASR-0148 |
| `SetMacAccessBlacklist` | macs（String 数组，必填，整体替换） | Map：{success, blacklist, 当前连接} 或 {error} | ASR-0148 |
| `SetWifiAutoConnectPolicy` | enabled（boolean，必填 true=禁止自动连接） | Map：{success, autoConnectForbidden, disabledNetworkIds/enabledNetworkIds, failed, disconnectApplied, 当前连接} 或 {error} | ASR-0161 |
| `GetWifiAutoConnectPolicy` | 无 | Map：{success, autoConnectForbidden, networks:[{networkId, ssid, enabled}], 当前连接} | ASR-0161 |
| `ApplyWifiAccessPolicy` | 无 | Map：{success 语义为评估结果, wifiEnabled, connected, ssid, bssid, violation, disconnected} | ASR-0147/0148/0161（立即执行评估） |

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> p = new HashMap<>();
p.put("ssid", "HYX-MDM-WIFI");
p.put("securityType", 1);
p.put("password", "HYX@12345");
Map result = api.onEvent("ConfigureWifi", p);
// {"RESULT":{"securityTypeName":"wpa2","netId":3,"ssid":"HYX-MDM-WIFI","success":true,"securityType":1,"readBack":"verified"}}

Map<String, Object> w = new HashMap<>();
w.put("mode", 2);
w.put("ssids", Arrays.asList("Syrius_Guest"));   // 先 SetSsidAccessBlacklist 再 SetSsidAccessPolicy
Map result2 = api.onEvent("SetSsidAccessPolicy", w);
// {"RESULT":{"mode":2,...,"enforcement":{"violation":"ssid in blacklist","disconnected":true,...}}}
```

**广播通道**（TestBroadcast，与现有命令一致）：

```bash
adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast \
  -a ON_SQA_INTERFACE_TEST --es event ConfigureEnterpriseWifi \
  --es param '{"ssid":"HYX-MDM-EAP","eapMethod":0,"phase2":3,"identity":"testuser","password":"testpass123"}'
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/
│   ├── WifiConfigPolicyManager.java        # 新增：WLAN 配置/删除引擎（addNetwork/removeNetwork/读回核对/安全类型分类，ASR-0144/0145/0151）
│   └── WifiAccessPolicyManager.java        # 新增：SSID/MAC 黑白名单 + 自动连接策略引擎（SharedPreferences 持久化 + 动态广播回调断连 + syncPolicy，ASR-0147/0148/0161）
├── service/command/wifi/
│   ├── ConfigureWifi.java                  # 新增：ASR-0144
│   ├── ConfigureEnterpriseWifi.java        # 新增：ASR-0151
│   ├── RemoveWifiNetwork.java              # 新增：ASR-0145
│   ├── GetSavedWifiNetworks.java           # 新增：辅助列表
│   ├── SetSsidAccessPolicy.java / GetSsidAccessPolicy.java          # 新增：ASR-0147
│   ├── SetSsidAccessWhitelist.java / SetSsidAccessBlacklist.java    # 新增：ASR-0147
│   ├── SetMacAccessPolicy.java / GetMacAccessPolicy.java            # 新增：ASR-0148
│   ├── SetMacAccessWhitelist.java / SetMacAccessBlacklist.java      # 新增：ASR-0148
│   ├── SetWifiAutoConnectPolicy.java / GetWifiAutoConnectPolicy.java # 新增：ASR-0161
│   └── ApplyWifiAccessPolicy.java          # 新增：立即执行评估
└── service/ApiBinder.java         # 注册 15 个新命令（wifi 包已通配导入）
    service/ApiService.java        # onCreate 增加 WifiAccessPolicyManager.syncPolicy
broadcast/BootCompletedReceiver.java  # BOOT_COMPLETED 增加 WifiAccessPolicyManager.syncPolicy

testapp/
├── src/main/java/com/hmdm/testapp/
│   ├── WifiAccessTestActivity.java         # 新增：测试页（配置/删除/列表 + SSID/MAC 名单 + 自动连接 + 框架探测）
│   ├── TestActions.java                    # 新增 15 个事件与事件目录
│   ├── MainActivity.java                   # 增加 "WLAN config & access" 入口
│   └── AndroidManifest.xml                 # 注册 WifiAccessTestActivity
└── src/main/res/layout/activity_wifi_access_test.xml  # 新增测试页布局
```

## 5. 执行逻辑

### 5.1 ConfigureWifi / ConfigureEnterpriseWifi（ASR-0144/0151）

```
1. 参数校验（缺 ssid/securityType → {error}；securityType 非 0~2 → {error}；SSID 归一化去引号 + 32 字节/控制字符校验 → {error}）
2. open 类型忽略密码；wpa2/wep 缺密码 → {error}；密码格式校验：WPA2 须 8~63 ASCII 字符或 64 位 hex（64 位 hex 按原始 hex PSK 写入不加引号）、WEP 须 5/13 ASCII 或 10/26 hex，非法 → {error}（2026-08-06 代码审查修复）
3. 企业网：eapMethod 非 0~6 → {error}；phase2 非 0~4 → {error}；PEAP/TLS/TTLS/PWD 缺密码 → {error}（SIM/AKA/AKA_PRIME 免密码）
4. WiFi 未启用 → {error:"wifi is disabled"}
5. 构建 WifiConfiguration（见 2.2）；addNetwork 成功后若存在同 SSID 同安全类型旧配置才删除（replacedNetId/replacedRemoved 上报）——先加后删，addNetwork 失败不触碰旧配置（2026-08-06 代码审查修复；本 ROM addNetwork 对相同配置去重返回既有 netId，此时不触发替换）
6. addNetwork → netId；-1 → {error}（可能被 WLAN 权限黑名单拦截，如实上报）
7. getConfiguredNetworks 读回核对（存在且 SSID/安全类型一致 → readBack:"verified"；企业网附 readBack Map：eapMethod/phase2/identity/anonymousIdentity/passwordMasked）
8. connect=true → enableNetwork(netId, true)（connectResult 上报）
```

### 5.2 RemoveWifiNetwork（ASR-0145）

```
1. networkId >=0 → 先经 getConfiguredNetworks 校验该 netId 存在，不存在 → {success:false, error:"saved network not found: networkId <id>"}（2026-08-06 代码审查修复，与 SSID 路径一致）；否则按 SSID 归一化精确匹配收集全部条目 netId 列表（allNetIdsBySsid）
2. 无目标 → {success:false, error:"saved network not found: <ssid>"}
3. 目标 netId 去重后逐个 removeNetwork（本 ROM 伴生条目共享 netId，一次调用删除全部；重复调用返回 false 属预期，以读回为准）
4. getConfiguredNetworks 读回核对：不再含该 SSID/netId → success=true；仍存在 → {error:"read-back: network still present after removeNetwork"}
```

### 5.3 SSID/MAC 黑白名单（ASR-0147/0148）

```
SetSsidAccessPolicy / SetMacAccessPolicy：
  1. mode 非 0~2 → {error}
  2. SharedPreferences 持久化 mode
  3. ensureReceiver（进程内注册一次动态广播接收器）
  4. enforce() 立即评估：已连接且违规 → disconnect；返回 enforcement（violation/disconnected）
  5. 返回 {success, mode, modeName, 名单, currentSsid/currentBssid/connected, enforcement}

SetSsidAccessWhitelist/Blacklist / SetMacAccessWhitelist/Blacklist：
  1. 缺数组 → {error}；条目逐个校验（SSID：去引号/非空/≤32 字节/无控制字符；MAC：AA:BB:CC:DD:EE:FF 格式），任一非法 → 整单拒绝 {error}
  2. 整体替换持久化 → ensureReceiver → enforce()
  3. 返回 {success, whitelist|blacklist, 当前连接}

enforce()（网络回调与命令共用）：
  1. WiFi 未启用 → {wifiEnabled:false}
  2. getConnectionInfo：SSID/BSSID 提取（unquote/大写归一化）；未连接判定（SSID 空或 <unknown ssid>、BSSID 占位 02:00:00:00:00:00、非 COMPLETED）→ {connected:false}
  3. violationReason：白名单模式名单不含当前 SSID/BSSID → "ssid/bssid not in whitelist"；黑名单模式命中 → "ssid/bssid in blacklist"；autoConnectForbid → "autoConnectForbidden"
  4. 违规 → disconnect() + 日志 enforce(<reason>): violation=... disconnected=...
```

### 5.4 SetWifiAutoConnectPolicy（ASR-0161）

```
1. 缺 enabled → {error}
2. 持久化 autoConnectForbid；ensureReceiver
3. applyAutoConnect：getConfiguredNetworks 逐个 disableNetwork（禁止）/ enableNetwork(netId,false)（允许），netId 去重，返回 disabledNetworkIds/enabledNetworkIds/failed
4. 禁止时附 disconnectApplied（断开当前连接）
5. 网络回调：CONFIGURED_NETWORKS_CHANGED / WIFI_STATE_CHANGED(ENABLED) 且禁止标志生效 → 重新全量禁用 + 评估
```

**安全设计**：本批次命令参数为字符串/整数/布尔/数组；SSID/MAC 仅进入 Wi-Fi 服务 Binder 接口与 SharedPreferences，**无字符串进入 shell / 系统命令**，无命令注入面；SSID 长度/字符集与 MAC 格式校验在 Launcher 侧完成；名单持久化于应用私有目录。**凭据日志脱敏（2026-08-06 代码审查修复）**：ConfigureWifi/ConfigureEnterpriseWifi 的 `password`/`identity`/`anonymousIdentity` 参数在 `ApiBinder.onEventReceived` 与 `TestBroadcast.receive` 的日志输出前统一替换为 `***`（凭据不落 logcat）；configure 命令内部日志亦不含密码明文。

## 6. 权限与归属

- 六项均为公开 `WifiManager` 接口 + 平台签名特权调用（uid=1000）：`NETWORK_SETTINGS`（signature|privileged，热点批次既有声明）使 Wi-Fi 服务按特权调用方放行配置类接口；`ACCESS_WIFI_STATE`/`CHANGE_WIFI_STATE`（既有声明）覆盖查询/开关路径；**无新增 manifest 权限、无 uses-policy 声明、无需重启 framework**；
- 不修改 AIDL / lib 模块；
- 策略引擎为 Launcher 常驻行为（动态广播接收器），与既有策略引擎（无障碍/AppOps/热点等）同生命周期模式：ApiService.onCreate + BootCompletedReceiver 重新武装。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺 ssid/securityType/eapMethod/enabled/mode/名单数组参数 | 命令返回 {error：...}，不 crash；testapp 侧同样拦截 |
| securityType/eapMethod/phase2 越界或非数字 | {error："invalid securityType/eapMethod/phase2: ..."}，不写入 |
| SSID 非法（空、控制字符、UTF-8 超 32 字节）；MAC 非 AA:BB:CC:DD:EE:FF | {error}，整单拒绝，不写入 |
| open 网络带密码 | 密码忽略（按 open 构建） |
| wpa2/wep 缺密码；企业网（除 SIM/AKA/AKA_PRIME）缺密码 | {error："missing parameter: password ..."} |
| WPA2 密码非 8~63 ASCII 或 64 位 hex；WEP 密钥非 5/13 ASCII 或 10/26 hex | {error："invalid wpa2 password: 8-63 ASCII characters or 64 hex digits required" / "invalid wep key: 5/13 ASCII characters or 10/26 hex digits required"}，不写入、不触碰既有网络 |
| 同 SSID 同安全类型替换 | **先 addNetwork 后删旧配置**（replacedNetId/replacedRemoved 上报）；addNetwork 失败时旧配置保留；本 ROM 对相同配置去重返回既有 netId 时自然不触发替换 |
| 删除不存在的 networkId | {success:false, error:"saved network not found: networkId <id>"}，与 SSID 路径一致 |
| 删除目标不存在（SSID） | {success:false, error:"saved network not found: <ssid>"} |
| WiFi 未启用 | {error："wifi is disabled"}，不写入 |
| addNetwork 返回 -1 | {error："addNetwork returned -1 ..."}（可能为 ASR-0149 WLAN 权限黑名单拦截，如实上报） |
| 删除目标不存在 | {success:false, error:"saved network not found: <ssid>"} |
| 删除后读回仍存在 | {success:false, error:"read-back: network still present after removeNetwork"} |
| 名单校验失败（任一非法条目） | 整单拒绝，原名单不变 |
| 白名单模式 + 空名单 | 语义=全部网络禁止连接（合法配置，文档化）；恢复需换名单或 mode=0 |
| 黑名单模式命中当前网络 | 立即断开；框架自动重连被回调反复断开（持续执行闭环，logcat 可核验） |
| 用户在设置页手动连接违规网络 | 连接建立后由网络回调断开（断连策略语义；无 connect-time 拦截通道，文档化） |
| ASR-0147 与 ASR-0152 白名单并存 | 两策略独立执行（DPM 框架拦截 + Launcher 断连），须同时通过；文档化 |
| 进程重启/开机 | SharedPreferences 持久化 + ApiService.onCreate/BootCompletedReceiver 经 syncPolicy 重新注册接收器并立即评估/重新禁用（真机验证：Launcher 重装重启后策略与名单保持、白名单当前网络不断连） |
| 企业网真实连接 | 本机无企业 AP，addNetwork/读回全量通过；真实 EAP 协商需企业 AP 环境（见需求文档"硬件受限测试说明"） |
| 本 ROM 单 SSID 双条目（伴生 OWE） | 列表如实展示双条目（securityType 区分 open/owe/eap/wpa2）；SSID 删除一次删除全部；netId 去重防重复调用误报 |

## 8. 真机验证记录（2026-08-06，Android 13 / API 33 userdebug，平台签名 + device owner）

（命令级用例结果见测试用例设计文档，此处记录机制核验关键结论）

| 核验项 | 结果 |
|---|---|
| 设备基线 | WiFi 连接 Syrius_Guest（开放网，netId=0，BSSID B4:89:01:F1:C0:67）；已保存网络 4 条目（Syrius_Guest open+owe、syrius-4px wpa2-psk+wpa3-sae）；无任何接入策略 |
| **特权通道核验** | Launcher（NETWORK_SETTINGS 特权）addNetwork 连续成功（netId 2~10）、removeNetwork/enableNetwork/disableNetwork 全部有效；testapp（平台签名非特权）enableNetwork 探测可用 |
| **单 SSID 双条目核验** | open 网 addNetwork 生成 open+owe 双条目（同一 netId）；removeNetwork(netId) 一次删除全部；对已删 netId 再次 removeNetwork 返回 false（引擎 netId 去重 + 读回核对，命令 success 判定以读回为准） |
| ASR-0144 配置 | open/wpa2 配置成功、读回 verified；同 SSID 同类型重配替换（replacedNetId=3→4）；缺参/非法类型/超长 SSID/缺密码全部正确拒绝 |
| ASR-0151 企业配置 | PEAP（phase2=mschapv2）/TLS/SIM 三类配置成功，读回 eapMethod/phase2/identity/anonymousIdentity 逐项一致，密码掩码如实上报（masked/plain）；非法 eapMethod/phase2、缺密码正确拒绝 |
| ASR-0145 删除 | 按 SSID 删除（open 双条目一次删净）、按不存在 SSID 报 saved network not found、缺参报错，读回核对无残留 |
| **ASR-0147 白名单执行链** | mode=1 + 名单含当前网络 → 保持连接；名单切换为不含当前网络 → 立即断开（logcat `enforce(NETWORK_STATE_CHANGED): violation=ssid not in whitelist disconnected=true` 连续 5+ 次，框架自动重连被反复断开）；enableNetwork 探测连接建立后同样被断开（connected=false） |
| **ASR-0147 黑名单执行链** | mode=2 + 黑名单含当前网络 → `violation=ssid in blacklist disconnected=true` 立即断开 |
| **ASR-0148 执行链** | 白名单含当前 BSSID → 保持；名单不含当前 BSSID → `violation=bssid not in whitelist` 断开；黑名单命中 → `violation=bssid in blacklist` 断开；非法 MAC 格式整单拒绝 |
| **ASR-0161 执行链** | 禁止 → 全部已保存网络 disabledNetworkIds=[0,1,4,5,6,8] 去重、当前连接断开、15 秒观察窗口无自动重连；禁止期间新增网络被 CONFIGURED_NETWORKS_CHANGED 回调立即禁用（enabled:false）；允许 → 全部网络恢复 enabled、设备自动重连 Syrius_Guest |
| **持久化与重新武装** | 策略/名单/标志持久化 SharedPreferences：Launcher 重装（进程重启）后策略保持、接收器经 syncPolicy 重新注册、白名单含当前 BSSID 时不断连 |
| **代码审查修复回归（2026-08-06 晚）** | ① 删除不存在 networkId=999 → `{success:false, error:"saved network not found: networkId 999"}`；② WPA2 短密码（abcd）→ `invalid wpa2 password: 8-63 ASCII characters or 64 hex digits required` 且既有 HYX-MDM-WIFI 配置保留（先加后删）；合法重配 success=true（本 ROM addNetwork 去重返回既有 netId，不触发替换）；③ 日志脱敏：ApiBinder 通道 `param:{password=***, ...}`、TestBroadcast 通道 `param:{"ssid":"...","password":"***"}`，密码不落 logcat |
| 测试后设备恢复 | 全部测试网络删除、SSID/MAC 模式 0 名单清空、自动连接允许、设备重连 Syrius_Guest（基线 4 条目无残留） |
