# WLAN 强管控（ASR-0150/0152/0153/0155/0158/0160/0168）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 实现机制 |
|---|---|---|---|
| ASR-0150 | WLAN | 禁止/允许 用户修改AP 配置 | device owner `DISALLOW_CONFIG_WIFI` 用户限制（与 ASR-0141/0155 同引擎） |
| ASR-0152 | WLAN强管控 | 配置企业Wifi 白名单 | device owner `dpm.setWifiSsidPolicy`（ALLOWLIST，API 33） |
| ASR-0153 | WLAN强管控 | 查询/设置是否 禁止手动添加网络 | device owner `DISALLOW_ADD_WIFI_CONFIG` 用户限制 |
| ASR-0155 | WLAN强管控 | 查询/设置是否 禁用编辑WLAN设置项 | device owner `DISALLOW_CONFIG_WIFI`（复用既有命令 `SetUserConfigWifiDisabled`/`IsUserConfigWifiDisabled`，ASR-0141 同引擎） |
| ASR-0158 | WLAN强管控 | 设置WLAN连接的最低安全级别 | device owner `dpm.setMinimumRequiredWifiSecurityLevel`（API 33） |
| ASR-0160 | WLAN强管控 | 禁止/允许WLAN直连 | device owner `DISALLOW_WIFI_DIRECT` 用户限制 |
| ASR-0168 | WiFi个人热点 | 禁止/允许 用户修改WIFI个人热点配置 | device owner `DISALLOW_CONFIG_TETHERING` 用户限制 |

**归属**：按需求文档归属列，七项均为「Launcher（MDM）」（公开 DPM 接口 + device owner 即可）。本批次**无新增 manifest 权限、无 shell、无 ROM 改动、不修改 `device_admin.xml`**（用户限制与 WiFi 策略接口均无需 uses-policy 声明，无需重启 framework）。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署；bootloader 锁定。测试设备基线（2026-08-04 实测）：WiFi 已连接 `Syrius_Guest`（开放网络，netId=0，另有已保存 `syrius-4px` wpa2）；无任何 DPM 用户限制（`dumpsys user` "Device policy local restrictions: none"）；`dumpsys device_policy` 的 `mWifiMinimumSecurityLevel=0`；SSID 策略为空（`getWifiSsidPolicy` 返回 null）。

## 2. 技术选型与可行性核验

### 2.1 本 ROM SDK 为 fork 版（重要，2026-08-04 反编译核验）

本 ROM 的 compileSdk android.jar（`/home/alex/Android/Sdk/platforms/android-33/`）为厂商定制版，与本批次相关的 DPM API 与 AOSP 标准 API 33 **不一致**：

| API | 本 ROM fork | 标准 AOSP 13 |
|---|---|---|
| `WifiSsidPolicy` 构造 | `WifiSsidPolicy(int, Set<WifiSsid>)`，SSID 为 `android.net.wifi.WifiSsid` 对象（经 `WifiSsid.fromBytes(byte[])` 构造） | `WifiSsidPolicy(int, Set<String>)` |
| `WifiSsidPolicy.WIFI_SSID_POLICY_TYPE_ALLOWLIST` | **0**（DENYLIST=1） | 1（DENYLIST=2） |
| `dpm.setWifiSsidPolicy` / `getWifiSsidPolicy` | **无 ComponentName 参数**（device owner 由调用方推导） | set 带 ComponentName；get 无参 |
| 安全级别常量 | `WIFI_SECURITY_OPEN=0`、`WIFI_SECURITY_PERSONAL=1`、`WIFI_SECURITY_ENTERPRISE_EAP=2`、`WIFI_SECURITY_ENTERPRISE_192=3`（开发者预览命名，企业级拆两档） | `WIFI_SECURITY_LEVEL_OPEN/PERSONAL/ENTERPRISE`（0/1/2） |
| `dpm.setMinimumRequiredWifiSecurityLevel` / `get...` | **无 ComponentName 参数** | set 带 ComponentName |

因此引擎直接按 fork 版 API 编写（`WifiSsid` 对象集合、无参调用），并在文档中记录差异。`WifiSsid.toString()` 返回带引号形式，引擎输出时统一去除首尾引号。

### 2.2 ASR-0150 AP 配置锁定（DISALLOW_CONFIG_WIFI，用户确认方案）

**语义**：禁止后用户无法添加/修改/删除任何 AP（Wi-Fi 网络）配置。

**方案决策（2026-08-04 真机核验 + 用户确认）**：Sheet1 P0 原规划未指定接口，标准 AOSP 机制为 `Settings.Global.wifi_device_owner_configs_lockdown`（`WIFI_DEVICE_OWNER_CONFIGS_LOCKDOWN`）。但本 ROM 实测该键**不生效于用户创建的配置**：

- 反编译 wifi APEX（`/apex/com.android.wifi/javalib/service-wifi.jar`，WifiConfigManager.canModifyNetwork）确认：该键仅在 `canModifyNetwork` 中消费，且仅对「creator 为组织托管 device admin 的配置」生效（lockdown=1 时任何人不可改、=0 时需 NETWORK_SETTINGS/NETWORK_SETUP_WIZARD 权限）；**用户（Settings）自己创建的配置因其 creatorUid == 调用方 uid 直接放行**，与 lockdown 取值无关；
- 真机验证：lockdown=1 时，平台签名（同 Settings 权限级）但非 device owner 的 testapp 调用 `WifiManager.addNetwork` 仍成功（netId 正常返回）。

而 `DISALLOW_CONFIG_WIFI`（ASR-0141/0155 同引擎）实测**完全封锁用户入口**：限制生效后 Settings 的 WLAN 页直接显示 "Blocked by your IT admin" 对话框，用户无法进入 WLAN 设置，自然无法修改任何 AP 配置。经与用户确认（2026-08-04），ASR-0150 采用该限制实现，命令 `SetApConfigLockdown`/`IsApConfigLockdown` 读写 `DISALLOW_CONFIG_WIFI` 状态。**一致性处理**：既有 `WifiPolicyManager.setWifiDeviceOwnerConfigsLockdown`/`isWifiDeviceOwnerConfigsLockdownEnabled`（WifiMgActivity"WiFi配置锁定"按钮原直写该失效键）改为委托本引擎（setApConfigLockdown/isApConfigLockdown），两条路径状态一致，避免界面误报锁定。

### 2.3 ASR-0152 企业 WiFi 白名单（setWifiSsidPolicy）

**语义**：配置 SSID 允许列表；仅列表内 SSID 的网络可被连接；策略生效时断开不符合条件的网络（需求文档备注明确要求）。

**接口核验**：

| 接口 | API 级别 | 调用方要求 | 本部署 |
|---|---|---|---|
| `dpm.setWifiSsidPolicy(WifiSsidPolicy)`（fork 无参版） | API 33 | device owner | 满足 |
| `dpm.getWifiSsidPolicy()` | API 33 | 任意 | 满足 |

**引擎行为**（enabled=true 时）：
1. 校验并归一化 SSID 列表（去引号、非空、UTF-8 字节长度 ≤32（802.11 上限）、无控制字符，非法项整单拒绝）；
2. `dpm.setWifiSsidPolicy(new WifiSsidPolicy(ALLOWLIST, WifiSsid 集合))`；
3. 读回 `getWifiSsidPolicy()` 核对（policyType + SSID 集合一致才报 success）；
4. 若当前连接网络的 SSID 不在名单内，调用 `WifiManager.disconnect()` 立即断开（对应需求备注"策略生效时断开不符合条件的网络"；`disconnectApplied` 如实上报）。

enabled=false 时 `setWifiSsidPolicy(null)` 清除策略（读回为 null 即成功）。

**本 ROM 框架层执行链（反编译 + 真机双重核验）**：
- `WifiServiceImpl.notifyWifiSsidPolicyChanged`（DPM 调用回调）：对每个 client 模式检查当前连接网络 SSID，不在名单内 → `ClientMode.disconnect()` + 日志 `disconnect admin restricted network`（真机 logcat 实测出现）；
- `WifiPermissionsUtil.isAdminRestrictedNetwork(config)`：读取 DPM 的 SSID 策略，allowlist 下 SSID 不在名单 → restricted；该判定被 `WifiServiceImpl.enableNetwork`（日志 `enableNetwork not allowed for admin restricted network Id=%`）、`WifiServiceImpl.connect` 及连接完成路径（ClientModeImpl）消费，即**手动连接与自动加入均被拦截**；
- 真机验证：allowlist=[HYX-MDM-WIFI] 时对已保存开放网 Syrius_Guest 执行 enableNetwork → 返回 false + logcat 拒绝日志；清除策略后同一调用 → 成功连接。自动加入在策略生效期间亦不重连（37 秒观察窗口）。

**结论**：本 ROM 对 SSID 白名单的执行是完整的（断开 + 连接/自动加入拦截），引擎侧额外 disconnect 为冗余安全网（幂等无害）。

### 2.4 ASR-0153 禁止手动添加网络（DISALLOW_ADD_WIFI_CONFIG）

**语义**：禁止后用户无法手动添加 Wi-Fi 网络（设置页"添加网络"入口及网络连接被禁）。

**接口核验**：device owner `addUserRestriction/clearUserRestriction` + `hasUserRestriction` 读回核对。`no_add_wifi_config` 在 AOSP 13 `UserRestrictionsUtils.USER_RESTRICTIONS` 有效集合内（与 DISALLOW_BACKUP 不同，**不会被静默丢弃**，实测生效）。

**本 ROM 设置页表现（真机 uiautomator 实测）**：限制生效后 Settings WLAN 页中**非当前连接**的已保存网络行显示 "Not allowed by your organization" 且 `enabled="false"`（不可点击，无法连接）；已连接网络保持连接。清除限制后恢复可点击与正常文案。

### 2.5 ASR-0155 禁用编辑 WLAN（DISALLOW_CONFIG_WIFI，复用既有命令）

与 ASR-0141 同引擎（`no_config_wifi`），复用既有命令 `SetUserConfigWifiDisabled`/`IsUserConfigWifiDisabled`（`WifiPolicyManager`，L+）。本批次**不新增命令**，仅补充 testapp 事件与文档。实测（uiautomator）：限制生效后 Settings WLAN 页显示 "Blocked by your IT admin"，用户无法进入编辑。

### 2.6 ASR-0158 最低安全级别（setMinimumRequiredWifiSecurityLevel）

**语义**：设置 WLAN 连接的最低安全级别（低于该级别的网络不可连接）。本 ROM fork 提供 4 档：0=OPEN（无要求）、1=PERSONAL、2=ENTERPRISE_EAP、3=ENTERPRISE_192。命令参数 level 取 0~3，越界（含非数字）拒绝。

**接口核验**：`dpm.setMinimumRequiredWifiSecurityLevel(int)`（fork 无参版，API 33，device owner）+ `getMinimumRequiredWifiSecurityLevel()` 读回核对；系统侧 `dumpsys device_policy` 的 `mWifiMinimumSecurityLevel` 与命令一致。

**本 ROM 框架层执行链（反编译 + 真机双重核验）**：
- `WifiServiceImpl.notifyMinimumRequiredWifiSecurityLevelChanged`：对每个 client 模式检查当前连接网络的 DPM 安全级别（`getCurrentSecurityType` → `convertSecurityTypeToDpmWifiSecurity`），低于要求 → `ClientMode.disconnect()` + 日志 `disconnect admin restricted network`；
- `isAdminRestrictedNetwork`：遍历配置的安全参数列表，若**全部**低于要求级别 → restricted；被 enableNetwork/connect/自动加入路径消费；
- 真机验证：level=1 时已连接的开放网 Syrius_Guest 被框架立即断开（logcat 实测），对该网 enableNetwork 返回 false；恢复 level=0 后可正常连接。

### 2.7 ASR-0160 WLAN 直连（DISALLOW_WIFI_DIRECT）/ ASR-0168 热点配置（DISALLOW_CONFIG_TETHERING）

均为 device owner 用户限制，`no_wifi_direct`（API 21+）与 `no_config_tethering`（API 21+）均在 AOSP 13 有效限制集合内（实测 `dumpsys user` "Effective restrictions" 出现对应键、hasUserRestriction 读回一致）。需求备注提及的 NEARBY_WIFI_DEVICES 权限仅在使用 Wi-Fi 扫描类 API 时需要，本引擎仅操作用户限制，**无需该权限**。

### 2.8 系统配置声明

- 四项用户限制（no_config_wifi / no_add_wifi_config / no_wifi_direct / no_config_tethering）经 `dpm.addUserRestriction`/`clearUserRestriction` 写入，由 DPM 持久化到 `device_policies.xml`（"Device policy local restrictions" 段），**无需** uses-policy 声明；
- SSID 策略与安全级别经 DPM 公开接口写入（`dumpsys device_policy` 可对照 `mWifiMinimumSecurityLevel`；SSID 策略经 `getWifiSsidPolicy` 读回），**无需** uses-policy 声明；
- 本批次**不修改** `device_admin.xml`，**无需重启 framework**。

## 3. 命令接口定义（SystemApiInterface.onEvent）

### 3.1 新增命令（本批次，12 条）+ 复用命令（2 条）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetApConfigLockdown` | disabled（boolean，必填） | Map：{success, disabled, restriction=no_config_wifi} 或 {error} | ASR-0150 |
| `IsApConfigLockdown` | 无 | Map：{success, disabled, restriction} | ASR-0150 |
| `SetWifiSsidWhitelist` | enabled（boolean，必填）、ssids（String 数组，enabled=true 时必填） | Map：{success, enabled, policyType, ssids, connectedSsid, disconnectApplied} 或 {error} | ASR-0152 |
| `GetWifiSsidWhitelist` | 无 | Map：{success, enabled, policyType, ssids, connectedSsid} | ASR-0152 |
| `SetManualAddWifiDisabled` | disabled（boolean，必填） | Map：{success, disabled, restriction=no_add_wifi_config} 或 {error} | ASR-0153 |
| `IsManualAddWifiDisabled` | 无 | Map：{success, disabled, restriction} | ASR-0153 |
| `SetUserConfigWifiDisabled`（复用既有，ASR-0141） | disabled（boolean，必填） | Boolean（既有行为） | ASR-0155 |
| `IsUserConfigWifiDisabled`（复用既有，ASR-0141） | 无 | Boolean（既有行为） | ASR-0155 |
| `SetMinimumWifiSecurityLevel` | level（int，必填，0=OPEN 1=PERSONAL 2=ENTERPRISE_EAP 3=ENTERPRISE_192） | Map：{success, level, levelName} 或 {error} | ASR-0158 |
| `GetMinimumWifiSecurityLevel` | 无 | Map：{success, level, levelName} | ASR-0158 |
| `SetWifiDirectDisabled` | disabled（boolean，必填） | Map：{success, disabled, restriction=no_wifi_direct} 或 {error} | ASR-0160 |
| `IsWifiDirectDisabled` | 无 | Map：{success, disabled, restriction} | ASR-0160 |
| `SetUserConfigTetheringDisabled` | disabled（boolean，必填） | Map：{success, disabled, restriction=no_config_tethering} 或 {error} | ASR-0168 |
| `IsUserConfigTetheringDisabled` | 无 | Map：{success, disabled, restriction} | ASR-0168 |

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> p = new HashMap<>();
p.put("disabled", true);
Map result = api.onEvent("SetApConfigLockdown", p);
// {"RESULT":{"restriction":"no_config_wifi","success":true,"disabled":true}}

Map<String, Object> w = new HashMap<>();
w.put("enabled", true);
w.put("ssids", Arrays.asList("HYX-MDM-WIFI"));
Map result2 = api.onEvent("SetWifiSsidWhitelist", w);
// {"RESULT":{"ssids":["HYX-MDM-WIFI"],"connectedSsid":"Syrius_Guest","success":true,"enabled":true,"disconnectApplied":true,"policyType":0}}
```

**广播通道**（TestBroadcast，与现有命令一致）：

```bash
adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast \
  -a ON_SQA_INTERFACE_TEST --es event SetWifiSsidWhitelist \
  --es param '{"enabled":true,"ssids":["HYX-MDM-WIFI"]}'
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/
│   └── WifiControlPolicyManager.java        # 新增：WLAN 强管控引擎（用户限制 + SSID 策略 + 安全级别 + 断开逻辑，写后读回核对）
├── service/command/wifi/
│   ├── SetApConfigLockdown.java             # 新增：ASR-0150 设置
│   ├── IsApConfigLockdown.java              # 新增：ASR-0150 查询
│   ├── SetWifiSsidWhitelist.java            # 新增：ASR-0152 设置（enabled + ssids 数组）
│   ├── GetWifiSsidWhitelist.java            # 新增：ASR-0152 查询
│   ├── SetManualAddWifiDisabled.java        # 新增：ASR-0153 设置
│   ├── IsManualAddWifiDisabled.java         # 新增：ASR-0153 查询
│   ├── SetMinimumWifiSecurityLevel.java     # 新增：ASR-0158 设置（level 0~3 校验）
│   ├── GetMinimumWifiSecurityLevel.java     # 新增：ASR-0158 查询
│   ├── SetWifiDirectDisabled.java           # 新增：ASR-0160 设置
│   ├── IsWifiDirectDisabled.java            # 新增：ASR-0160 查询
│   ├── SetUserConfigTetheringDisabled.java  # 新增：ASR-0168 设置
│   └── IsUserConfigTetheringDisabled.java   # 新增：ASR-0168 查询
└── service/ApiBinder.java         # 注册 12 个新命令（wifi 包已通配导入）

testapp/
├── src/main/java/com/hmdm/testapp/
│   ├── WifiControlTestActivity.java         # 新增：测试页（7 项功能禁用/启用/查询按钮 + 2 个框架执行验证按钮）
│   ├── WifiVerifier.java                    # 新增：真实框架执行验证（addNetwork / enableNetwork 连接探测）
│   ├── TestActions.java                     # 新增 16 个事件（12 新 + 2 复用 + 2 验证）与事件目录
│   ├── MainActivity.java                    # 增加 "WLAN control" 入口
│   └── AndroidManifest.xml                  # 新增 WifiControlTestActivity；新增 ACCESS_WIFI_STATE/CHANGE_WIFI_STATE 权限声明
└── src/main/res/layout/activity_wifi_control_test.xml # 新增测试页布局
```

## 5. 执行逻辑

```
SetApConfigLockdown / SetManualAddWifiDisabled / SetWifiDirectDisabled / SetUserConfigTetheringDisabled：
  1. 参数校验（缺 disabled → {error}）
  2. 校验 device owner（非 DO → success=false + error）
  3. dpm.addUserRestriction（disabled=true）或 clearUserRestriction（disabled=false）
  4. UserManager.hasUserRestriction 读回核对，一致 → success=true；不一致 → success=false + error

SetWifiSsidWhitelist：
  1. 参数校验（缺 enabled → {error}；enabled=true 且缺 ssids 数组 → {error}）
  2. 校验 device owner（非 DO → error）；API < 33 → error
  3. 归一化 SSID（去引号）；enabled=true 且列表为空或含非法项（空串/超 32 字符）→ {error}
  4. setWifiSsidPolicy(ALLOWLIST, WifiSsid 集合) 或 setWifiSsidPolicy(null)（enabled=false）
  5. getWifiSsidPolicy 读回核对（类型 + 集合一致 / 清除后为 null）→ success
  6. enabled=true 且当前连接 SSID 不在名单 → WifiManager.disconnect()，disconnectApplied=true 上报
  7. 返回 {success, enabled, policyType, ssids, connectedSsid, disconnectApplied}

SetMinimumWifiSecurityLevel：
  1. 参数校验（缺 level → {error}）；level 非 0~3 整数（数值/数字字符串均可，NaN/∞/小数拒绝）→ {error}
  2. 校验 device owner（非 DO → error）；API < 33 → error
  3. setMinimumRequiredWifiSecurityLevel(level)，getMinimumRequiredWifiSecurityLevel 读回核对 → success
  4. 返回 {success, level, levelName}

Is* 查询命令：读限制/策略/级别返回；异常 → success=false + error
```

**安全设计**：本批次命令参数为布尔值、整数与 SSID 字符串数组。SSID 仅进入 DPM 策略接口（Binder 内），**无字符串进入 shell / 系统命令**，无命令注入面；SSID 长度/字符集校验在 Launcher 侧完成；`WifiSsid` 构造经 `fromBytes`（UTF-8），非 ASCII SSID 经框架转义处理（文档化局限，测试用 ASCII SSID）。

## 6. 权限与归属

- 七项均为公开 DPM 接口 + device owner 身份，**无新增 manifest 权限**（Launcher 侧）；`addUserRestriction` 系列与 WiFi 策略接口均**无需** `device_admin.xml` uses-policy 声明，**无需重启 framework**；
- 不修改 AIDL / lib 模块；
- **testapp 部署变更（本批次）**：testapp 从普通应用改为**平台签名部署**（与 Launcher 同平台密钥），原因：本 ROM 的 `CHANGE_WIFI_STATE` 为纯签名权限（非 appop），普通应用无法获得，而 WifiVerifier 的 `addNetwork`/`enableNetwork` 框架执行验证需要该权限（平台签名后签名权限自动授予）。平台签名 testapp 与 Settings 应用权限级相同（签名权限 + 非 device owner），其执行结果可代表 Settings 的行为。重装流程：先经 `SetUninstallBlocked`（canUninstall=true）解除安装白名单卸载锁（否则 `DELETE_FAILED_OWNER_BLOCKED`），`adb uninstall` 后安装平台签名 APK；Launcher 白名单会再次自动加锁（恢复基线行为）。测试结束后可随时换回普通签名（同流程）。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺 disabled/level/enabled/ssids 参数 | 命令返回 {error："missing parameter: ..."}，不 crash；testapp 侧同样拦截 |
| 非 device owner | success=false + error "not a device owner"，不 crash |
| API < 33 调用 SSID 策略/安全级别 | success=false + error "requires API 33+ (...)" |
| enabled=true 且 ssids 为空 | {error："ssids must not be empty when enabled"}，不写入 |
| SSID 非法（空串、含控制字符、UTF-8 超 32 字节） | {error："invalid ssids (empty, control characters or longer than 32 bytes): (...)"}，整单拒绝，不写入 |
| level 越界（如 9）或非数字（abc） | {error："invalid level: ... (0=OPEN, 1=PERSONAL, 2=ENTERPRISE_EAP, 3=ENTERPRISE_192)"} |
| 写后读回不一致（限制/策略/级别） | success=false + error（含期望值/实际值），可重试 |
| 当前连接网络不在白名单 | 引擎主动 disconnect + 上报 disconnectApplied；本 ROM 框架同步断开（"disconnect admin restricted network"）并拦截后续连接/自动加入 |
| 非 ASCII SSID | 经 WifiSsid.fromBytes UTF-8 转义处理，读回字符串可能与输入不同（测试与文档以 ASCII 为准） |
| 三个用户限制同时下发 | 独立键互不影响，命令各自写读 |
| 用户在设置页手动恢复 | 查询如实反映当前系统状态（命令只保证调用时刻生效；设置页在限制生效时被封锁或网络被禁用，用户难以手动恢复） |
| ASR-0150 与 ASR-0155/0141 同键 | 三者共享 no_config_wifi 限制，命令语义等价（文档化；ASR-0150 为需求追溯独立命令名） |
| WIFI_DEVICE_OWNER_CONFIGS_LOCKDOWN（备选方案） | 本 ROM 仅保护 DO 创建的配置、不保护用户配置（见 2.2 核验），经用户确认不采用；命令中无残留 |

## 8. 真机验证记录（2026-08-04，Android 13 / API 33 userdebug，平台签名 + device owner）

（命令级用例结果见测试用例设计文档，此处记录机制核验关键结论）

| 核验项 | 结果 |
|---|---|
| 设备基线 | WiFi 连接 Syrius_Guest（开放网，netId=0）；无用户限制；mWifiMinimumSecurityLevel=0；SSID 策略空 |
| **SDK fork 核验** | android.jar 中 `WifiSsidPolicy(int, Set<WifiSsid>)`、`ALLOWLIST=0`、set/get 无 ComponentName、`WIFI_SECURITY_*` 常量 0~3（javap 确认） |
| **ASR-0150 方案核验** | lockdown=1 时平台签名非 DO 应用 addNetwork 成功（netId=2）；反编译 canModifyNetwork 确认该键仅保护 DO 创建配置；`DISALLOW_CONFIG_WIFI` 生效后 Settings WLAN 页显示 "Blocked by your IT admin"（uiautomator 确认）——经用户确认采用后者 |
| ASR-0150 写入与读回 | SetApConfigLockdown disabled=true → dumpsys user "Effective restrictions" 含 no_config_wifi；读回一致；双向切换通过 |
| ASR-0152 写入与读回 | 设 allowlist=[Syrius_Guest]（含当前网络）→ success，disconnectApplied=false；改为 [HYX-MDM-WIFI] → disconnectApplied=true + 框架 logcat "disconnect admin restricted network"；getWifiSsidPolicy 读回 policyType=0(ALLOWLIST)+ssids 一致；清除 → policyType=-1 |
| ASR-0152 连接拦截 | allowlist=[HYX-MDM-WIFI] 时 enableNetwork(Syrius_Guest, netId=0) → false + logcat "enableNetwork not allowed for admin restricted network Id=0"；37 秒内自动加入未重连；清除后同调用成功连接 |
| ASR-0153 写入与 UI | 限制生效 → dumpsys user 含 no_add_wifi_config；Settings WLAN 页非当前网络行 "Not allowed by your organization" 且 enabled=false（uiautomator）；清除恢复 |
| ASR-0155 | SetUserConfigWifiDisabled disabled=true → Settings WLAN 页 "Blocked by your IT admin"；IsUserConfigWifiDisabled 读回一致 |
| ASR-0158 写入与断开 | level=1 时已连接开放网被框架断开（logcat "disconnect admin restricted network"）+ mWifiMinimumSecurityLevel=1；enableNetwork(开放网) → false；level 0/1/2/3 双向读回通过；level=9/abc 拒绝 |
| ASR-0160/0168 | no_wifi_direct / no_config_tethering 限制写入、dumpsys user 对照、读回、清除均通过 |
| 测试后设备恢复 | 全部恢复基线：无用户限制、SSID 策略空、级别 0、WiFi 重新连接 Syrius_Guest、wifi_device_owner_configs_lockdown 已删除（null） |
