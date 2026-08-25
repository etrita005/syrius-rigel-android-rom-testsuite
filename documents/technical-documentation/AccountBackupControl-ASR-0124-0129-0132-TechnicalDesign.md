# 账户/备份管控（ASR-0124/0129/0132）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 实现机制 |
|---|---|---|---|
| ASR-0124 | 应用数据 | 获取/设置是否允许系统备份功能 | device owner `dpm.setBackupServiceEnabled` / `isBackupServiceEnabled`（API 31+ 公开接口）+ Settings.Secure backup_enabled 附报 |
| ASR-0129 | Google Play | 获取/设置 是否禁用谷歌账户 | device owner `DISALLOW_MODIFY_ACCOUNTS` 限制 + `AccountManager.removeAccountExplicitly` 清除存量账户 |
| ASR-0132 | Google Play | 禁用/启用Google备份和恢复 | `Settings.Secure.backup_enabled` + `backup_auto_restore` 键写入（`dpm.setSecureSetting` 优先，白名单外回退平台签名直写） |

**归属**：按需求文档归属列，ASR-0124 为「Launcher（MDM）」（公开 DPM 接口 + device owner 即可）；ASR-0129 为「Launcher（MDM）」（公开 DPM 接口 + 公开 AccountManager 接口）；ASR-0132 为「Launcher（MDM）」（`dpm.setSecureSetting` 为公开 SDK 接口，写保护设置键的直写通道依赖平台签名 uid=1000 的 WRITE_SECURE_SETTINGS，与已实现 SystemSettingsControl 批次同一模式）。三项均**无需 ROM 改动、无隐藏 API**。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署；bootloader 锁定，不可改动 system 分区。测试前设备基线（2026-08-04 实测）：`backup_enabled=0`、`backup_auto_restore` 未设置（null，缺省视为 1）、DPM 备份服务已关闭（前序配置状态）、无 `no_modify_accounts` 限制、`dumpsys account` 无账户。

## 2. 技术选型与可行性核验

### 2.1 ASR-0124 系统备份（setBackupServiceEnabled）

**语义**：禁用后系统备份服务整体停用——BackupManagerService 不执行备份/恢复（含 adb backup 与传输）。

**实现修正（2026-08-04 真机核验，重点）**：Sheet1 P0 规划为 `DISALLOW_BACKUP` 用户限制，但真机实测 `dpm.addUserRestriction(admin, "no_backup")` **静默丢弃**：AOSP 13 的 `UserRestrictionsUtils.USER_RESTRICTIONS`（有效限制集合）已**不再包含** `DISALLOW_BACKUP`（no_backup），UserManagerService 对未知限制键直接忽略并记日志（logcat 实测：`Unknown restriction queried by uid 1000 (com.android.deskclock et al): no_backup`；`dumpsys user` 无变化；读回 hasUserRestriction=false）。经确认改用 device owner 公开接口：

| 接口 | API 级别 | 调用方要求 | 本部署 |
|---|---|---|---|
| `dpm.setBackupServiceEnabled(ComponentName, boolean)` | API 31（S） | device owner | 满足（API 33） |
| `dpm.isBackupServiceEnabled(ComponentName)` | API 31 | 任意 | 满足 |

该接口为 Android 12+ 设备所有者禁用备份的标准通道（`MdmUtils.setBackupServiceEnabled` 既有同款调用），禁用即 `setBackupServiceEnabled(admin, false)`，查询经 `isBackupServiceEnabled` 读回核对，并附报 `Settings.Secure.backup_enabled` 设置键当前值（设置页开关的另一独立通道，如实反映）。

**测试基线补充**：本测试设备基线（前序配置状态）即 `backupServiceEnabled=false`、`backup_enabled=0`，命令双向切换（false↔true）均读回核对通过。

### 2.2 ASR-0129 谷歌账户（DISALLOW_MODIFY_ACCOUNTS + removeAccount）

**语义**：禁用后（disabled=true）：
1. 加 `DISALLOW_MODIFY_ACCOUNTS` 限制——用户/应用**无法再添加、删除账户**（AccountManager.addAccount/removeAccount 对受限用户被服务端拒绝，Settings 账户页编辑入口被禁用）；
2. 立即调用 `AccountManager.removeAccountExplicitly` **清除设备上全部存量账户**，达成"设备上无谷歌账户"的最终状态。

启用（disabled=false）时仅清除限制，恢复账户添加/删除能力（已删账户不找回）。

**接口核验**：

| 接口 | API 级别 | 调用方要求 | 本部署 |
|---|---|---|---|
| `dpm.addUserRestriction`（`no_modify_accounts`） | API 21 | device owner | 满足 |
| `AccountManager.getAccounts()` | API 1 | GET_ACCOUNTS | uid=1000 平台签名自动授予 |
| `AccountManager.removeAccountExplicitly(Account)` | API 22 | MANAGE_ACCOUNTS（签名/特权）或账户属主 | 满足（平台签名） |

**本 ROM 现状**：无 GMS（无 Google 账户类型），测试设备当前无任何账户。验证路径：`dumpsys account`（或 `pm list accounts`）对照账户列表；限制状态经 `dumpsys user` 对照。真实账户删除行为以模拟账户为限（无 Google Authenticator 场景），删除逻辑为通用 AccountManager 调用，对任意账户类型生效（文档化局限）。

### 2.3 ASR-0132 Google 备份和恢复（backup_enabled / backup_auto_restore）

**语义**：`Settings.Secure.backup_enabled`（总开关）与 `backup_auto_restore`（重装应用时自动恢复备份数据）双双写 0 即禁用；写 1 恢复。BackupManagerService 直接消费这两个键决定备份/恢复行为。

**通道设计（重要）**：按 Sheet1 规划首选 `dpm.setSecureSetting(ComponentName, String, String)`（公开 SDK，device owner 通道）。**但 AOSP 13 的 DPM 仅白名单少数键**（`default_input_method`、`skip_first_use_hints`、`touch_exploration_enabled`、`bluetooth_on`、`immersive_mode_confirmations`、`allowed_headless_system_apps`），`backup_enabled`/`backup_auto_restore` **不在白名单**——DPM 抛 `SecurityException("... is not a secure setting allowed to set")`。因此引擎实现为：

1. 逐键先尝试 `dpm.setSecureSetting`（若厂商 ROM 放宽白名单则直接生效，结果如实上报 `channel=dpm`）；
2. 抛异常时**回退平台签名直写** `Settings.Secure.putString`（uid=1000 持有 WRITE_SECURE_SETTINGS，与 SystemSettingsControl 批次同模式），结果上报 `channel=settings`；
3. 两键均写后读回核对，全部一致才报 success=true。

**查询**：读两键当前值（缺省视为 1）；`disabled = (backup_enabled==0 && backup_auto_restore==0)`。

### 2.4 接口冲突与既有逻辑

- ASR-0124 与 ASR-0132 语义重叠但机制独立：`setBackupServiceEnabled` 从 DPM 层控制备份服务；`backup_enabled=0` 从设置层停用。二者可同时下发（互相增强），本批次命令各自独立写读，不做联动。
- 系统备份页（BackupSettings）写这两个键的行为与命令共享同一存储：用户若在设置页手动改回，查询如实反映（写后核对只保证命令调用时刻生效）。
- `MdmUtils.setBackupServiceEnabled`（既有，配置驱动 `dpm.setBackupServiceEnabled`）与本批次 ASR-0124 使用**同一 DPM 通道**：命令为显式覆盖语义（调用即写入并读回核对），如后续服务器配置再次下发会覆盖（Launcher 配置驱动行为的既有设计，文档化）。

### 2.5 系统配置声明

- ASR-0124 的 `setBackupServiceEnabled` 与 ASR-0129 的 addUserRestriction **均无需** `device_admin.xml` uses-policy 声明；
- ASR-0132 直写 Settings.Secure **无需** uses-policy 声明（DPM 白名单通道本就不可达，回退直写）；
- 本批次**不修改** `device_admin.xml`，无需重启 framework。

## 3. 命令接口定义（SystemApiInterface.onEvent）

### 3.1 新增命令（本批次，6 条）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetBackupDisabled` | disabled（boolean，必填） | Map：{success, disabled, backupServiceEnabled, backup_enabled} 或 {error} | ASR-0124 |
| `IsBackupDisabled` | 无 | Map：{success, disabled, backupServiceEnabled, backup_enabled} | ASR-0124 |
| `SetGoogleAccountsDisabled` | disabled（boolean，必填） | Map：{success, disabled, restriction, restrictionApplied, removedAccounts, accounts} 或 {error} | ASR-0129 |
| `IsGoogleAccountsDisabled` | 无 | Map：{success, disabled, restriction, accounts} | ASR-0129 |
| `SetBackupRestoreDisabled` | disabled（boolean，必填） | Map：{success, disabled, backup_enabled, backup_auto_restore, channel} 或 {error} | ASR-0132 |
| `IsBackupRestoreDisabled` | 无 | Map：{success, disabled, backup_enabled, backup_auto_restore} | ASR-0132 |

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> p = new HashMap<>();
p.put("disabled", true);
Map result = api.onEvent("SetBackupDisabled", p);
// {"RESULT":{"success":true,"disabled":true,"backupServiceEnabled":false,"backup_enabled":0}}

Map result2 = api.onEvent("SetBackupRestoreDisabled", p);
// {"RESULT":{"success":true,"disabled":true,"backup_enabled":0,"backup_auto_restore":0,"channel":"settings"}}
```

**广播通道**（TestBroadcast，与现有命令一致）：

```bash
adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast \
  -a ON_SQA_INTERFACE_TEST --es event SetBackupDisabled \
  --es param '{"disabled":true}'
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/
│   └── AccountBackupPolicyManager.java     # 新增：账户/备份策略引擎（备份服务开关 + 用户限制 + 账户删除 + 设置键直写，写后读回核对）
├── service/command/backup/
│   ├── SetBackupDisabled.java               # 新增：ASR-0124 设置
│   ├── IsBackupDisabled.java                # 新增：ASR-0124 查询
│   ├── SetGoogleAccountsDisabled.java       # 新增：ASR-0129 设置
│   ├── IsGoogleAccountsDisabled.java        # 新增：ASR-0129 查询
│   ├── SetBackupRestoreDisabled.java        # 新增：ASR-0132 设置
│   └── IsBackupRestoreDisabled.java         # 新增：ASR-0132 查询
└── service/ApiBinder.java         # 注册 6 个新命令

testapp/
├── src/main/java/com/hmdm/testapp/
│   ├── AccountBackupTestActivity.java       # 新增：测试页（三项功能禁用/启用/查询按钮）
│   ├── TestActions.java                     # 新增 6 个事件（含参数校验）与事件目录
│   └── MainActivity.java                    # 增加 "Account & backup" 入口
└── src/main/res/layout/activity_account_backup_test.xml # 新增测试页布局
```

## 5. 执行逻辑

```
SetBackupDisabled:
  1. 参数校验（缺 disabled → {error}）
  2. 校验 device owner（非 DO → success=false + error）；API < 31 → success=false + error
  3. dpm.setBackupServiceEnabled(admin, !disabled)
  4. dpm.isBackupServiceEnabled 读回核对，一致 → success=true；附报 Settings.Secure backup_enabled
  5. 读回不一致 → success=false + error（含期望值/实际值）

SetGoogleAccountsDisabled:
  1. 参数校验（缺 disabled → {error}）
  2. 校验 device owner（非 DO → success=false + error）
  3. addUserRestriction（disabled=true）或 clearUserRestriction（disabled=false）
  4. hasUserRestriction 读回核对限制状态
  5. disabled=true 时枚举并 removeAccountExplicitly 全部账户，
     返回 removedAccounts 与剩余账户列表；剩余非空 → success=false + accountError
  6. 读回不一致 → success=false + error（含期望值/实际值）

SetBackupRestoreDisabled:
  1. 参数校验（缺 disabled → {error}）
  2. 逐键（backup_enabled / backup_auto_restore）：
     a. 尝试 dpm.setSecureSetting（白名单内则 channel=dpm）
     b. 异常回退 Settings.Secure.putString 直写（channel=settings）
  3. 两键读回核对全部一致 → success=true；否则 success=false + error
  4. 返回 {success, disabled, backup_enabled, backup_auto_restore, channel}

Is* 查询命令：直接读限制/键值返回（含账户列表 / backupServiceEnabled）；异常 → success=false + error
```

**安全设计**：本批次命令参数为布尔值，**无字符串进入 shell / 系统命令**，无命令注入面；账户删除仅作用于当前用户账户（MANAGE_ACCOUNTS 权限由平台签名授予），不触碰凭据文件；`removeAccountExplicitly` 逐个 try/catch，单账户失败不影响其余账户删除与限制生效（结果如实上报）。

## 6. 权限与归属

- ASR-0124：公开 DPM 接口（`setBackupServiceEnabled`/`isBackupServiceEnabled`，API 31+），仅需 device owner 身份，**无新增 manifest 声明、无 shell、无 ROM 改动**；
- ASR-0129：公开 DPM 接口 + 公开 AccountManager 接口，仅需 device owner 身份与平台签名（MANAGE_ACCOUNTS/GET_ACCOUNTS 签名权限自动授予），**无新增 manifest 声明**；
- ASR-0132：`dpm.setSecureSetting`（公开 SDK）+ SettingsProvider 直写（依赖平台签名 uid=1000 的 WRITE_SECURE_SETTINGS，manifest 既有声明），**无新增权限**；
- 不修改 AIDL / lib 模块；testapp 无需新增权限。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺 disabled 参数 | 命令返回 {error："missing parameter: disabled"}，不 crash；testapp 侧同样拦截 |
| 非 device owner | success=false + error "not a device owner"，不 crash |
| API < 31 调用 ASR-0124 | success=false + error "requires API 31+ (setBackupServiceEnabled)" |
| **DISALLOW_BACKUP 失效（AOSP 13）** | no_backup 不在 UserRestrictionsUtils 有效限制集合，addUserRestriction 静默丢弃（logcat 报 Unknown restriction）；实现已改用 setBackupServiceEnabled（见 2.1），命令结果以 DPM 备份服务状态为准 |
| 账户删除失败（单个） | 该账户跳过并记日志，继续删除其余账户；返回 removedAccounts 与剩余 accounts 列表，剩余非空时 success=false + accountError（如实上报） |
| `dpm.setSecureSetting` 白名单外（AOSP 13 必然） | 静默回退 Settings.Secure 直写，返回 channel=settings；厂商 ROM 放宽白名单时 channel=dpm 如实上报 |
| Settings 写失败或读回不一致 | success=false + error（含期望值/实际值），可重试 |
| 本 ROM 无 Google 账户类型（无 GMS） | 账户列表为空为常态；删除逻辑对任意账户类型通用（文档化局限） |
| ASR-0124 与 ASR-0132 同时下发 | 独立机制互相增强，命令各自写读不联动 |
| 用户在设置页手动改回 backup 键 | 查询如实反映当前系统状态（命令只保证调用时刻生效） |
| 清除限制后已删账户 | 不自动找回（恢复能力 ≠ 恢复数据） |

## 8. 真机验证记录（2026-08-04，Android 13 / API 33 userdebug，平台签名 + device owner）

（命令级用例结果见测试用例设计文档，此处记录机制核验关键结论）

| 核验项 | 结果 |
|---|---|
| 设备基线 | `settings get secure backup_enabled` = 0、`backup_auto_restore` 未设置（null）；DPM 备份服务已关闭（前序配置状态）；无 no_modify_accounts 限制；`dumpsys account` 无账户 |
| **DISALLOW_BACKUP 失效核验** | `dpm.addUserRestriction(admin, "no_backup")` 不抛异常但静默丢弃：logcat 报 `Unknown restriction queried by uid 1000 ... no_backup`，`dumpsys user` Restrictions 段无变化，hasUserRestriction 读回 false——AOSP 13 UserRestrictionsUtils.USER_RESTRICTIONS 已不含该键（源码比对确认） |
| ASR-0124 写入与读回 | `SetBackupDisabled disabled=true` → isBackupServiceEnabled=false（success=true）；`disabled=false` → true（success=true）；双向切换读回核对通过，附报 backup_enabled 键 |
| ASR-0124 通道 | 公开 DPM 接口（API 31+），无需 uses-policy 声明、无需重启 framework |
| ASR-0129 写入与读回 | `SetGoogleAccountsDisabled disabled=true` → `dumpsys user` "Device policy local restrictions" 含 no_modify_accounts（读回 true）；`disabled=false` → 限制段恢复 none；账户列表恒空（无 GMS），removedAccounts=0 |
| ASR-0132 通道与读回 | `SetBackupRestoreDisabled disabled=true` → `dpm.setSecureSetting` 抛 SecurityException（白名单外）→ 回退直写，channel=settings，两键读回 0/0，success=true；恢复 → 1/1 |
| ASR-0132 系统对照 | `settings get secure backup_enabled` / `backup_auto_restore` 与命令返回一致 |
| 测试后设备恢复 | 全部恢复基线：backup_enabled=0、backup_auto_restore 未设置（null）、DPM 备份服务关闭（backupServiceEnabled=false）、无 no_modify_accounts 限制、无账户 |
