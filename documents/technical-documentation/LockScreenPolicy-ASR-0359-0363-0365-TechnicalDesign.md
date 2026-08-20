# 锁屏策略管控（ASR-0359/0363/0365）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 底层 DPM 接口 |
|---|---|---|---|
| ASR-0359 | 锁屏 | 查询/设置 锁屏强认证超时时间 | `DevicePolicyManager.setRequiredStrongAuthTimeout` / `getRequiredStrongAuthTimeout` |
| ASR-0363 | 锁屏 | 查询/设置 锁屏密码连续数字序列长度上限参数 | `setPasswordQuality` + `setPasswordMinimumLength` 组合（近似实现，见 2.4） |
| ASR-0365 | 锁屏 | 查询/设置 密码更改宽限期 | `DevicePolicyManager.setPasswordExpirationTimeout` / `getPasswordExpirationTimeout` / `getPasswordExpiration` |

**归属**：三项均经 **DevicePolicyManager 公开 SDK 接口**落地（需求文档归属列：ASR-0359/0365 为「Launcher（MDM）」，ASR-0363 为「Launcher（MDM）+ 系统 API」，实现上无需隐藏 API / 平台签名权限，仅依赖 device owner 身份——Launcher 已是 device owner，DPM 接口即可达全部语义）。ASR-0363 因 AOSP 无"连续数字序列"内容级校验 API，按 Sheet1 建议优先级 P0 方案采用 `setPasswordQuality`/`setPasswordMinimumLength` 组合**近似实现**（映射规则见 2.4）。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署；bootloader 锁定，不可改动 system 分区。测试前设备基线（2026-08-04 实测）：`passwordQuality=0x0`（UNSPECIFIED）、`minimumPasswordLength=0`、`strongAuthUnlockTimeout=0`、`passwordExpirationTimeout=0`、`passwordExpirationDate=0`。

## 2. 技术选型与可行性核验

### 2.1 共同前提：DPM 公开接口 + device owner

Launcher（`com.hmdm.launcher`）已设为 device owner（`dpm list-owners`：`admin=com.hmdm.launcher/.AdminReceiver,DeviceOwner,Affiliated`），满足三项接口的全部权限要求：

| 接口 | API 级别 | 调用方要求 | 本部署 |
|---|---|---|---|
| `setRequiredStrongAuthTimeout(ComponentName, long)` | API 26（O） | device owner / profile owner | 满足（API 33） |
| `getRequiredStrongAuthTimeout(ComponentName)` | API 26 | 任意（返回聚合值） | 满足 |
| `setPasswordExpirationTimeout(ComponentName, long)` | API 8 | 激活的 admin | 满足（DO 即激活 admin） |
| `getPasswordExpirationTimeout` / `getPasswordExpiration` | API 8 | 任意 | 满足 |
| `setPasswordQuality` / `setPasswordMinimumLength` 及对应 getter | API 8 | 激活的 admin | 满足 |
| `isActivePasswordSufficient()` | API 8 | 任意 | 满足 |

全部为公开 SDK 接口，**无需隐藏 API、无需新增权限、无需 shell 命令**（与已实现批次中依赖 uid=1000 直写设置的实现不同，本批次为纯 DPM 策略）。

### 2.2 ASR-0359 锁屏强认证超时

**语义**：设置屏幕熄灭/锁定后、再次解锁时必须进行**强认证**（PIN/密码，而非指纹/面部等弱认证）的等待时间上限。`timeoutMs=0` 表示屏幕熄灭后立即要求强认证（每次解锁都要输密码）；`timeoutMs>0` 表示在该时长内弱认证可解锁，超过后必须强认证。DPM 存值后经 Keyguard/LockPatternUtils 消费，**对下次屏幕熄灭生效**。

**查询**：`getRequiredStrongAuthTimeout(admin)` 返回存储值；系统对照 `dumpsys device_policy` 输出中的 `strongAuthUnlockTimeout=` 字段（真机实测字段存在，初始 0）。

**本 ROM 限制（2026-08-04 实测）**：`setRequiredStrongAuthTimeout` 对**非 0 且 <3600000ms** 的请求值强制提升为 3600000ms（1 小时，MTK 默认强认证超时）；`0` 原样保留。请求 300000/60000 均被提升，命令读回核对后如实返回 success=false + error（不假报成功）；请求 ≥3600000 的值原样生效。业务层如设置 5 分钟超时，实际生效为 1 小时（文档化限制，AOSP 语义为"上限"约束，本 ROM 表现为非 0 下限）。

### 2.3 ASR-0365 密码更改宽限期

**语义**：设置密码必须被更换的间隔（宽限期）。`timeoutMs=0` 表示密码永不过期；`timeoutMs>0` 表示从密码（重新）设置时刻起算，超过宽限期后系统提示/要求更换密码。`getPasswordExpiration(admin)` 返回具体到期时刻（epoch ms，0 = 无到期）。**DPM 语义：宽限期自最近一次设置密码时刻起算**（密码从未设置或刚清除时无到期日）。

**系统对照**：`dumpsys device_policy` 输出 `passwordExpirationTimeout=` 与 `passwordExpirationDate=` 字段（真机实测字段存在，初始 0/0）。**实测补充**：设置 timeoutMs 后立即产生到期时刻（expiration = now + timeout，即使当前无锁屏密码）；恢复 0 后 expiration 归 0。

### 2.4 ASR-0363 连续数字序列长度上限（近似实现，重点）

**需求语义**：锁屏密码中"连续数字序列"（如 `1234`、`56789`）的长度不得超过上限 N。**AOSP Android 13 的 DPM / LockSettingsService 无任何按密码内容（连续序列、重复字符等）校验的 API**（内容级校验仅存在于厂商 ROM 自研密码检查器），故按 Sheet1 规划采用 `setPasswordQuality` + `setPasswordMinimumLength` 组合近似：

| limit（请求值） | 映射策略（quality / minLength） | 近似语义 |
|---|---|---|
| 0 | `PASSWORD_QUALITY_UNSPECIFIED` / 0 | 不限制（恢复默认，与设备基线一致） |
| 1 ~ 2 | `PASSWORD_QUALITY_ALPHANUMERIC` / 6 | 连续数字最多 1~2 位：密码必须混合字母且至少 6 位——纯数字弱密码（`12345`、`111111` 等）不满足策略，被锁屏服务拒绝 |
| 3 ~ 4 | `PASSWORD_QUALITY_ALPHANUMERIC` / 4 | 必须混合字母且至少 4 位 |
| 5 ~ 7 | `PASSWORD_QUALITY_NUMERIC` / 8 | 允许纯数字但至少 8 位（短序列 `1234` 等不满足） |
| 8 ~ 16 | `PASSWORD_QUALITY_NUMERIC` / 6 | 允许纯数字至少 6 位（最宽松档） |

设计依据：上限越小（越严格）→ 强制密码必须包含非数字字符（ALPHANUMERIC）并提高最短长度，从机制上杜绝短连续数字序列作为密码；上限越大逐步放宽。**局限（文档化）**：混合密码内部的长数字串（如 `abc1234567`）AOSP 无法内容校验，属近似实现的固有边界。

**查询（反向映射）**：命令不持久化请求值，查询为当前 DPM 策略（quality+minLength）的**纯函数**：`UNSPECIFIED → 0`；`ALPHANUMERIC + minLength≥6 → 2`，`ALPHANUMERIC + minLength 4~5 → 4`；`NUMERIC + minLength≥8 → 7`，`NUMERIC + minLength 6~7 → 16`。同时原样返回 `quality` / `qualityName` / `minLength` 与 `managedByPasswordMode`（quality 非本引擎映射集合——如 COMPLEX/ALPHABETIC，即由配置驱动 passwordMode 特性写入的状态），保证查询结果如实反映系统实际策略（含被其他通道修改的情形）。

**策略生效验证路径**：策略修改后 `isActivePasswordSufficient()` 反映当前密码是否满足新策略；锁屏服务在用户下次设置密码时按 DPM 约束校验（真机验证：`locksettings set-password 1234` 被拒 "Password too short; required: 6"，`locksettings set-password abc123` 成功）。

**本 ROM 限制（2026-08-04 实测）**：
1. `setPasswordMinimumLength` 在 **quality=UNSPECIFIED 时被 ROM 拒绝**（抛 "password quality should be at least 131072 for setPasswordMinimumLength"，AOSP 无此限制，且本 ROM 先写入后校验——异常返回时状态可能已部分变更）。引擎按方向调整写入顺序规避：目标 quality=UNSPECIFIED（limit=0 恢复）时**先降长度再降质量**；其余方向**先立质量再写长度**；若当前策略已等于目标则跳过写入。
2. 密码策略激活期间 `locksettings clear` 被锁屏服务拒绝（"Weak credential type"，移除凭据不满足策略）——DPM 语义的正常表现；测试恢复顺序须为**先恢复策略（limit=0）再清除密码**。

### 2.5 接口冲突与既有逻辑

Launcher 既有 `MdmUtils.setPasswordMode`（`Const.PASSWORD_QUALITY_*` 配置驱动，作用于同一组 quality/minLength 键）与本批次命令共享同一策略存储。本批次命令为**显式覆盖**语义：命令调用即写入并读回核对；如后续服务器配置再次下发 passwordMode，会覆盖本策略（Launcher 配置驱动行为的既有设计，文档化）。设备当前无配置下发（基线 quality=0），无实际冲突。

### 2.6 系统配置声明：device_admin.xml 的 `<expire-password/>`

ASR-0365 的 `setPasswordExpirationTimeout` 要求 admin 声明 `<expire-password/>` uses-policy（DPM 校验，缺失时报 "did not specify uses-policy for: expire-password"）。本批次在 `app/src/main/res/xml/device_admin.xml` 新增该声明（写入内容见 `documents/system_configurations.md`）。**本 ROM 的 DPM 服务在框架启动时缓存 admin 策略集，仅重装 APK 不生效，须重启 framework 或整机**（实测：重装后仍报缺策略；adb reboot 后生效；ASR-0207 摄像头批次同流程）。

## 3. 命令接口定义（SystemApiInterface.onEvent）

### 3.1 新增命令（本批次，6 条）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetStrongAuthTimeout` | timeoutMs（long，必填，0 ~ 2592000000=30 天；0=立即要求强认证） | Map：{success, timeoutMs} 或 {error} | ASR-0359 |
| `GetStrongAuthTimeout` | 无 | Map：{success, timeoutMs} | ASR-0359 |
| `SetPasswordExpirationTimeout` | timeoutMs（long，必填，0 ~ 2592000000=30 天；0=永不过期） | Map：{success, timeoutMs, expiration} 或 {error} | ASR-0365 |
| `GetPasswordExpirationTimeout` | 无 | Map：{success, timeoutMs, expiration} | ASR-0365 |
| `SetConsecutiveDigitsLimit` | limit（int，必填，0~16；0=不限制） | Map：{success, limit, quality, qualityName, minLength, managedByPasswordMode, activePasswordSufficient} 或 {error} | ASR-0363 |
| `GetConsecutiveDigitsLimit` | 无 | Map：{success, limit, quality, qualityName, minLength, managedByPasswordMode, activePasswordSufficient} | ASR-0363 |

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> p = new HashMap<>();
p.put("timeoutMs", 300000L);
Map result = api.onEvent("SetStrongAuthTimeout", p);
// {"RESULT":{"success":true,"timeoutMs":300000}}

Map<String, Object> p2 = new HashMap<>();
p2.put("limit", 2);
Map result2 = api.onEvent("SetConsecutiveDigitsLimit", p2);
// {"RESULT":{"success":true,"limit":2,"quality":262144,"qualityName":"alphanumeric",
//   "minLength":6,"activePasswordSufficient":true}}
```

**广播通道**（TestBroadcast，与现有命令一致）：

```bash
adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast \
  -a ON_SQA_INTERFACE_TEST --es event SetStrongAuthTimeout \
  --es param '{"timeoutMs":300000}'
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/
│   └── LockScreenPolicyManager.java        # 新增：三项策略引擎（DPM 调用 + 写后读回核对 + limit 映射）
├── service/command/lock_screen/
│   ├── SetStrongAuthTimeout.java           # 新增：ASR-0359 设置
│   ├── GetStrongAuthTimeout.java           # 新增：ASR-0359 查询
│   ├── SetPasswordExpirationTimeout.java   # 新增：ASR-0365 设置
│   ├── GetPasswordExpirationTimeout.java   # 新增：ASR-0365 查询
│   ├── SetConsecutiveDigitsLimit.java      # 新增：ASR-0363 设置
│   └── GetConsecutiveDigitsLimit.java      # 新增：ASR-0363 查询
└── service/ApiBinder.java         # 注册 6 个新命令

testapp/
├── src/main/java/com/hmdm/testapp/
│   ├── LockScreenPolicyTestActivity.java   # 新增：测试页（三项功能设置/查询按钮 + 数值输入）
│   ├── TestActions.java                    # 新增 6 个事件（含参数校验）与事件目录
│   └── MainActivity.java                   # 增加 "Lock screen policy" 入口
├── src/main/res/layout/activity_lock_screen_policy_test.xml # 新增测试页布局
└── src/main/AndroidManifest.xml            # 注册 LockScreenPolicyTestActivity
```

## 5. 执行逻辑

```
SetStrongAuthTimeout / SetPasswordExpirationTimeout:
  1. 参数校验（缺 timeoutMs → {error}；非数字/负数 → {error}）
  2. 校验 device owner（非 DO → success=false + error）
  3. ASR-0359 额外校验 API >= 26（setRequiredStrongAuthTimeout 为 O 起接口）
  4. DPM 写入 setRequiredStrongAuthTimeout / setPasswordExpirationTimeout
  5. 读回核对：getRequiredStrongAuthTimeout / getPasswordExpirationTimeout 与请求一致 → success=true；
     ASR-0365 附加 getPasswordExpiration 到期时刻
  6. 读回不一致 → success=false + error（含期望值/实际值）

SetConsecutiveDigitsLimit:
  1. 参数校验（缺 limit → {error}；limit 越界 0~16 → {error}）
  2. 校验 device owner
  3. limit → mapLimit 映射表得 {quality, minLength}
  4. DPM 写入 setPasswordQuality + setPasswordMinimumLength
  5. 读回核对 getPasswordQuality/getPasswordMinimumLength，全部一致 → success=true
  6. 返回 {success, limit(反向映射有效上限), quality, qualityName, minLength, activePasswordSufficient}

Get* 查询命令：直接读 DPM 当前值返回（含 activePasswordSufficient）；异常 → success=false + error
```

**安全设计**：本批次命令参数为数值（long/int），**无字符串进入 shell / 系统命令**；DPM 接口为公开 SDK 调用，无命令注入面；与既有 ApiService 无权限保护设计一致（调用方仅能修改锁屏密码策略）。**加固**（2026-08-04 评审）：Number 参数仅接受有限整数值（NaN/Infinity/小数拒绝），timeoutMs 上限 30 天。遗留考虑（非本批次）：导出无权限 AIDL 服务上的密码策略变更命令未做调用方包名校验（与既有 ~100 条命令一致，属项目既有架构；如后续收紧，可对 Set 类命令校验 `Binder.getCallingUid()` 归属包名，复用 shell 类命令的包名校验模式）。

## 6. 权限与归属

- 三项均为「Launcher（MDM）」/「Launcher（MDM）+ 系统 API」归属下的 **DPM 公开接口实现**：仅需 device owner 身份（Launcher 已具备），**无新增权限、无隐藏 API、无 shell、无 ROM 改动**；
- ASR-0359 要求 API 26+（目标平台 API 33 满足）；ASR-0363/0365 为 API 8 起接口；
- ASR-0365 额外依赖 `device_admin.xml` 声明 `<expire-password/>` uses-policy（2026-08-04 新增，见 `documents/system_configurations.md`；修改后须重启 framework/整机生效）；
- 不修改 AIDL / lib 模块；testapp 无需新增权限。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺 timeoutMs / limit 参数 | 命令返回 {error："missing parameter: ..."}，不 crash；testapp 侧同样拦截 |
| 参数非法（非数字、负数、limit 越界 0~16、timeoutMs > 30 天） | 返回 {error："invalid ..."} / {success=false, error:"timeoutMs out of range"}，不写入 |
| **数值类型差异** | 各调用通道数值类型不同（AIDL 直达 Integer/Long、send_test_command.sh 纯数字透传、org.json 按范围转 Integer/Long、TestBroadcast 经 Gson 为 Double）。引擎按 Number/String 兼容解析：**Number 仅接受有限且为整数值**（NaN/±Infinity/小数/越界均拒绝），正数各通道行为一致 |
| 非 device owner | success=false + error "not a device owner"，不 crash |
| API < 26 调用 ASR-0359 | success=false + error "requires API 26+" |
| **ASR-0359 ROM 下限**：请求非 0 且 <3600000ms | 本 ROM 提升为 3600000ms，读回核对不一致 → success=false + error（附实际值），如实上报（TC-0359-08 实测） |
| **timeoutMs 无上限风险** | 命令/引擎双重校验上限 30 天（2592000000ms）：超出拒绝，防止 Long.MAX_VALUE 使 DPM 到期计算溢出为"已过期"（TC-0359-09/0365-07 实测） |
| **ASR-0363 旧凭据不满足新策略** | 严格 limit 升级 quality/minLength 后，既有弱凭据不满足策略 → isActivePasswordSufficient=false、锁屏强制交互改密且 `locksettings clear` 被拒；恢复须远程下发 limit=0 或本机交互改密（文档化于 system_configurations.md §2） |
| **ASR-0365 旧凭据立即到期** | expiration 以策略设置时刻起算：设备上已存在设置时间早于 timeout 的凭据时，下次解锁即强制改密；业务部署建议先设密码再设宽限期，恢复命令 SetPasswordExpirationTimeout timeoutMs=0 |
| **ASR-0363 ROM 校验**：quality=UNSPECIFIED 时调 setPasswordMinimumLength | 本 ROM 拒绝（AOSP 无此限制）；引擎按方向调整写入顺序规避（目标 UNSPECIFIED 先降长度再降质量），已达目标状态时跳过写入（TC-0363-10 修复后实测通过） |
| **ASR-0363 清除密码**：策略激活期间 locksettings clear | 锁屏服务拒绝（"Weak credential type"）；属 DPM 语义正常表现，文档化测试恢复顺序（先恢复策略再清密码） |
| 策略被其他通道覆盖（如配置 passwordMode 下发） | 查询返回系统实际策略（纯函数反向映射），如实反映；文档化冲突语义 |
| ASR-0365 设置超时但无密码 | expiration = now + timeout（实测），恢复 0 后归 0 |
| ASR-0365 缺 `<expire-password/>` 声明 | 命令返回 success=false + error "did not specify uses-policy for: expire-password"；已随本批次声明并重启生效 |
| DPM 写失败或读回不一致 | success=false + error（含期望值/实际值），如实上报，可重试 |
| 当前密码不满足新策略 | activePasswordSufficient=false（如实返回，不视为失败）；锁屏服务将在下次设置密码时执行约束 |
| ASR-0363 混合密码内长数字串无法校验 | AOSP 无内容校验 API，属近似实现固有边界（文档化于 2.4） |

## 8. 真机验证记录（2026-08-04，Android 13 / API 33 userdebug，平台签名 + device owner）

（命令级用例结果见测试用例设计文档，此处记录机制核验关键结论）

| 核验项 | 结果 |
|---|---|
| DPM 基线字段 | `dumpsys device_policy`：passwordQuality=0x0、minimumPasswordLength=0、strongAuthUnlockTimeout=0、passwordExpirationTimeout=0、passwordExpirationDate=0 |
| ASR-0359 写入与读回 | set 7200000 → get/dumpsys 均 7200000（success=true）；set 0 → 0；**非 0 且 <3600000 被 ROM 提升为 3600000**（set 300000/60000 → 读回 3600000，命令如实返回 success=false + error） |
| ASR-0359 机制补充 | `getRequiredStrongAuthTimeout` 返回值与 `dumpsys device_policy` strongAuthUnlockTimeout 字段一致，作为系统对照 |
| ASR-0365 写入与读回 | set 86400000 → get/dumpsys 均 86400000，expiration=now+86400000（设置超时即产生到期时刻，即使无密码）；set 0 → 0/0 恢复 |
| ASR-0365 前置条件 | 需 device_admin.xml `<expire-password/>` uses-policy（本批次新增）；本 ROM 缓存策略集，重装不生效，**adb reboot 后生效** |
| ASR-0363 映射与锁屏校验 | limit=2 → alphanumeric(0x50000)/6；limit=5 → numeric(0x20000)/8；limit=10 → numeric/6；`locksettings set-password 1234` 被拒（"Password too short; required: 6"）、`abc123` 成功；策略激活期间 `locksettings clear` 被拒，恢复策略后清除成功 |
| ASR-0363 ROM 校验 | quality=UNSPECIFIED 时 setPasswordMinimumLength 被拒（"password quality should be at least 131072"）；引擎按方向调整写入顺序修复，0↔2↔5↔10↔0 全循环 success=true |
| 测试后设备恢复 | 全部恢复基线：strongAuthUnlockTimeout=0、passwordExpirationTimeout=0、passwordExpirationDate=0、passwordQuality=0x0、minimumPasswordLength=0、无锁屏密码 |
| 评审加固复核（2026-08-04 评审后补测） | parseLong/parseInt 拒绝非有限/非整数 Number（TestBroadcast 通道 `{"timeoutMs":16.9}`、`{"limit":2.5}` 均拒绝且状态不变）；timeoutMs 超上限（2592000001）返回 `timeoutOut of range`；查询/设置结果含 `managedByPasswordMode`；加固后正常流程（7200000↔0、limit 2↔0）全部通过 |
