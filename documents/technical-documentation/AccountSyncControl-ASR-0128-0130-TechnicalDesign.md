# 账户同步管控（ASR-0128/0130）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 实现机制 |
|---|---|---|---|
| ASR-0128 | 应用数据 | 获取/设置自动同步 | 公开 `ContentResolver.setMasterSyncAutomatically(boolean)` / `getMasterSyncAutomatically()`——全局"自动同步数据"主开关 |
| ASR-0130 | Google Play | 获取/设置是否启用谷歌账户自动同步 | 公开 `ContentResolver.setSyncAutomatically(Account, String, boolean)` / `getSyncAutomatically(Account, String)`——对全部 Google 账户（类型 `com.google`）的（账户, authority）对逐项设置/查询 |

**归属**：按需求文档归属列，两项均为「Launcher（MDM）」——`ContentResolver` 同步系列为**公开 SDK** 接口（API 1 起），无需 device owner、无需平台签名特权、无需 shell、无隐藏 API、无 ROM 改动。与 Sheet1 第四节 P2 规划一致（`ContentResolver.setMasterSyncAutomatically / setSyncAutomatically`）。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署。测试前设备基线（2026-08-07 实测）：全局自动同步开（`dumpsys content` 显示 `Auto sync: u0=true`，SyncManager 出厂默认）、无任何账户（`dumpsys account`：`Accounts: 0`）、无同步适配器（`dumpsys content` Sync adapters 段为空——本 ROM 无 GMS）。

## 2. 技术选型与可行性核验

### 2.1 ASR-0128 自动同步（setMasterSyncAutomatically）

**语义**：全局"自动同步数据"主开关（Settings > 账户 > 自动同步数据）。关闭后 SyncManager 不再调度任何账户同步；各账户/条目的单独开关状态保留，主开关重新打开后按各自开关恢复调度。

**通道核验（2026-08-07 真机）**：

| 接口 | API 级别 | 调用方要求 | 本部署 |
|---|---|---|---|
| `ContentResolver.setMasterSyncAutomatically(boolean)` | API 1 | 无 | 满足（公开 SDK） |
| `ContentResolver.getMasterSyncAutomatically()` | API 1 | 无 | 满足 |

**状态载体**：主开关**不是 Settings 键**（真机核验：`settings get global master_sync_automatically` 与 secure 变体均返回 null），由 SyncManager（ContentService）按用户维护并持久化于其自身状态存储（框架管理，整机重启保持）。系统侧对照通道：`dumpsys content` 的 `Auto sync: u0=true/false` 行（按用户逐行）。命令读回经 `getMasterSyncAutomatically` 与系统 dump 双重核对（真机闭环：置 false 后两处均为 false，置 true 后两处均为 true）。

### 2.2 ASR-0130 谷歌账户自动同步（setSyncAutomatically）

**语义**：Google 账户（Settings > 账户 > [Google 账户] > 账户同步页）的"自动同步"逐项开关。禁用=全部 Google 账户的全部（账户, authority）对 `syncAutomatically=false`；启用=全部 `true`。设置后 SyncManager 按各开关调度同步。

**通道核验**：

| 接口 | API 级别 | 调用方要求 | 本部署 |
|---|---|---|---|
| `ContentResolver.setSyncAutomatically(Account, String, boolean)` | API 1 | `WRITE_SYNC_SETTINGS`（normal，安装即授予） | 满足（本批次 manifest 新增声明，真机 granted=true 核验） |
| `ContentResolver.getSyncAutomatically(Account, String)` | API 1 | 无 | 满足 |
| `ContentResolver.getSyncAdapterTypes()` | API 1 | 无 | 满足（候选 authority 集合） |
| `AccountManager.getAccountsByType("com.google")` | API 1 | GET_ACCOUNTS（normal） | 满足（manifest 既有声明/平台签名授予） |

**权限修正（2026-08-07）**：`setSyncAutomatically` 服务端强制 `WRITE_SYNC_SETTINGS`（normal 权限需在 manifest 声明才会在安装时授予）。初版引擎未声明该权限——本机无账户（`setSyncAutomatically` 未被实际调用）测试不暴露，但 GMS 设备上会抛 SecurityException。代码审查后于 `app/src/main/AndroidManifest.xml` 新增 `android.permission.WRITE_SYNC_SETTINGS`，真机 `dumpsys package com.hmdm.launcher` granted=true 核验。

**候选 authority 集合**：`ContentResolver.getSyncAdapterTypes()`（当前用户全部已注册同步适配器）。对每个 Google 账户 × 每个 authority 调用 set/getSyncAutomatically。本 ROM 无 GMS、无同步适配器（真机核验 Sync adapters 段为空），集合恒空。

**查询语义（重要）**：`enabled=true` 仅当存在至少一个（账户, authority）对且全部 `syncAutomatically=true`；无 Google 账户时 `enabled=false` 并附 `note`（"no google accounts on device (no GMS)"）如实上报。禁用方向（全 false）与启用方向（全 true）与查询定义自洽。

### 2.3 接口冲突与既有逻辑

- ASR-0128 主开关与 ASR-0130 逐账户开关**互相独立**：主开关关闭后 SyncManager 整体停摆（逐项开关状态保留）；逐项关闭只影响该账户条目。两命令各自写读，不做联动（业务可组合下发：主开关关闭时逐项开关无需逐个设置，主开关重新打开后保持各自状态）。
- 与 ASR-0129（DISALLOW_MODIFY_ACCOUNTS）独立：限制只禁止账户增删，不影响同步调度。
- 用户若在系统设置页手动翻转自动同步开关，查询如实反映（命令写后核对只保证调用时刻生效）。

### 2.4 系统配置声明

- ASR-0128：主开关状态由 SyncManager（ContentService）持久化（框架管理），**非** `device_admin.xml` uses-policy、**非** Settings 键、Launcher 不直写任何系统文件；
- ASR-0130：逐项 syncAutomatically 状态由 SyncManager 持久化于其状态存储（框架管理），Launcher 不直写；
- 本批次**不修改** `device_admin.xml`，无需重启 framework；manifest 新增 `WRITE_SYNC_SETTINGS`（normal）一项。

## 3. 命令接口定义（SystemApiInterface.onEvent）

### 3.1 新增命令（本批次，4 条）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetAutoSync` | enabled（boolean，必填） | Map：{success, enabled, masterSyncAutomatically} 或 {error} | ASR-0128 |
| `IsAutoSync` | 无 | Map：{success, enabled, masterSyncAutomatically} | ASR-0128 |
| `SetGoogleAccountAutoSync` | enabled（boolean，必填） | Map：{success, enabled, updatedPairs, accounts, note?} 或 {error} | ASR-0130 |
| `IsGoogleAccountAutoSync` | 无 | Map：{success, enabled, accounts, note?} | ASR-0130 |

accounts 结构：`[{type, name, authorities: [{authority, syncAutomatically, syncable}]}]`（本 ROM 恒为 `[]`）。

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> p = new HashMap<>();
p.put("enabled", false);
Map result = api.onEvent("SetAutoSync", p);
// {"RESULT":{"masterSyncAutomatically":false,"success":true,"enabled":false}}

Map result2 = api.onEvent("IsGoogleAccountAutoSync", new HashMap<>());
// {"RESULT":{"note":"no google accounts on device (no GMS)","accounts":[],"success":true,"enabled":false}}
```

**广播通道**（TestBroadcast，与现有命令一致）：

```bash
adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast \
  -a ON_SQA_INTERFACE_TEST --es event SetAutoSync \
  --es param '{"enabled":false}'
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/
│   └── AccountSyncPolicyManager.java     # 新增：账户同步策略引擎（主开关 + 逐账户 syncAutomatically，写后读回核对）
├── service/command/account/
│   ├── SetAutoSync.java                  # 新增：ASR-0128 设置
│   ├── IsAutoSync.java                   # 新增：ASR-0128 查询
│   ├── SetGoogleAccountAutoSync.java     # 新增：ASR-0130 设置
│   └── IsGoogleAccountAutoSync.java      # 新增：ASR-0130 查询
└── service/ApiBinder.java                # 注册 4 个新命令

app/src/main/AndroidManifest.xml          # 新增 android.permission.WRITE_SYNC_SETTINGS（normal）

testapp/
├── src/main/java/com/hmdm/testapp/
│   ├── TestActions.java                  # 新增 4 个事件（含参数校验）与事件目录
│   └── AccountBackupTestActivity.java    # 新增 2 组同步测试按钮（复用既有"账户/备份"测试页）
└── src/main/res/layout/activity_account_backup_test.xml  # 新增同步按钮与分区标题
```

## 5. 执行逻辑

```
SetAutoSync:
  1. 参数校验（缺 enabled → {error}）
  2. ContentResolver.setMasterSyncAutomatically(enabled)
  3. getMasterSyncAutomatically 读回核对，一致 → success=true；不一致 → success=false + error（含期望值/实际值）
  4. 返回 {success, enabled, masterSyncAutomatically}

SetGoogleAccountAutoSync:
  1. 参数校验（缺 enabled → {error}）
  2. 枚举 getAccountsByType("com.google") × getSyncAdapterTypes() 全部（账户, authority）对
  3. 逐对 setSyncAutomatically(account, authority, enabled)，单对失败记日志、verified=false 并如实上报 error
  4. 全部读回核对（getSyncAutomatically 逐对）一致 → success=true；否则 success=false + error
  5. 返回 {success, enabled, updatedPairs, accounts, note（无账户时）}

Is* 查询命令：直接读主开关 / 逐对 syncAutomatically 返回；异常 → success=false + error
```

**安全设计**：本批次命令参数为布尔值，**无字符串进入 shell / 系统命令**，无命令注入面；仅读写同步调度状态，不触碰账户凭据与账户数据；`setSyncAutomatically` 单对 try/catch，单对失败不影响其余对处理（结果如实上报）。

## 6. 权限与归属

- ASR-0128：公开 `ContentResolver` 主开关接口，**无新增权限、无 device owner 要求**；
- ASR-0130：公开 `ContentResolver` + `AccountManager` 接口，manifest **新增** `WRITE_SYNC_SETTINGS`（normal，安装即授予；真机 granted=true 核验）；
- 不修改 AIDL / lib 模块；testapp 无需新增权限。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺 enabled 参数 | 命令返回 {error："missing parameter: enabled"}，不 crash；testapp 侧同样拦截 |
| 无 Google 账户（本 ROM 无 GMS） | 设置命令无可操作对象：success=true + updatedPairs=0 + note（"no google accounts on device (no GMS), nothing to toggle"），如实上报；查询 enabled=false + note（空集合下"未启用"语义自洽） |
| 个别（账户, authority）对 set 失败 | 该对跳过并记日志，继续处理其余对；读回不一致时 success=false + error（如实上报） |
| 主开关写后读回不一致 | success=false + error（含期望值/实际值），可重试 |
| 用户在设置页手动翻转自动同步 | 查询如实反映当前系统状态（命令只保证调用时刻生效） |
| `enabled=abc` 等非法字符串 | 命令侧 `Bundle.getBoolean` 语义解析为 false（与既有命令一致），不 crash |
| 主开关与逐项开关同时管理 | 独立写读不联动；主开关关闭期间逐项开关状态保留，重开后按各自状态恢复调度 |

## 8. 真机验证记录（2026-08-07，Android 13 / API 33 userdebug，平台签名 + device owner）

（命令级用例结果见测试用例设计文档，此处记录机制核验关键结论）

| 核验项 | 结果 |
|---|---|
| 设备基线 | `dumpsys content` 显示 `Auto sync: u0=true`（出厂默认开）；`dumpsys account` `Accounts: 0`；Sync adapters 段为空（无 GMS） |
| 主开关非 Settings 键 | `settings get global master_sync_automatically` / secure 变体均返回 null——状态由 SyncManager 维护，系统对照经 `dumpsys content` "Auto sync" 行 |
| ASR-0128 写入与读回 | `SetAutoSync enabled=false` → getMasterSyncAutomatically=false（success=true），`dumpsys content` 同步显示 `Auto sync: u0=false`；`enabled=true` 恢复，`u0=true`；双向切换读回核对通过 |
| ASR-0128 通道 | 公开 ContentResolver 接口，无需权限/无需 device owner/无需重启 framework |
| ASR-0130 写入与读回 | `SetGoogleAccountAutoSync enabled=true/false` → success=true、updatedPairs=0（无账户无可操作对象，如实上报 note），查询 enabled=false + accounts=[] |
| WRITE_SYNC_SETTINGS 授权 | manifest 新增声明后 `dumpsys package com.hmdm.launcher` 显示 `android.permission.WRITE_SYNC_SETTINGS: granted=true` |
| 整机重启保持 | 主开关状态由 SyncManager 持久化，重启后查询与系统 dump 一致（本批次测试前后均保持基线 true） |
| 测试后设备恢复 | 主开关恢复基线 true；无账户、无同步适配器（未触碰）；测试前后 `dumpsys content` Auto sync 行一致 |
