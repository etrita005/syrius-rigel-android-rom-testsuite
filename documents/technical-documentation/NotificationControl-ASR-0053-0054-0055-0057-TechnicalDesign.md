# 通知管控（ASR-0053/0054/0055/0057）技术设计文档

## 1. 需求概述

| 需求 ID | 子功能项 | 需求语义 |
|---|---|---|
| ASR-0053 | 是否允许指定应用发送通知 | 按包启用/禁用通知，可查询指定包当前通知开关状态 |
| ASR-0054 | 发送通知的应用白名单 | 白名单模式：仅名单内应用可发通知，其余全部禁用 |
| ASR-0055 | 发送通知的应用黑名单 | 黑名单模式：名单内应用禁用，其余全部允许 |
| ASR-0057 | 是否禁用锁屏时通知 | 全局禁用/启用锁屏界面显示通知 |

**归属**：Launcher（MDM）+ 系统 API。实现全部位于 Launcher 侧（`mdm_launcher/hmdm-android`），依赖平台签名（`sharedUserId="android.uid.system"`）与隐藏系统接口。

**全量管控决策**：黑白名单采用全量管控模式（mode=0 关闭 / mode=1 白名单 / mode=2 黑名单），新安装应用通过 Package 广播自动套用当前策略。

## 2. 技术选型

### 2.1 按包启用/禁用通知（ASR-0053）

- **接口**：`NotificationManager.setNotificationsEnabledForPackage(String, boolean)`，@SystemApi（API 28+），SDK 33 标准 jar 中不可见，**必须反射调用**。
- **实现**：`Class.getDeclaredMethod("setNotificationsEnabledForPackage", String.class, boolean.class)` + `setAccessible(true)` + `invoke`。该 App 为平台签名（`android.uid.system`），持有 `WRITE_SECURE_SETTINGS`、`MANAGE_APP_OPS_MODES` 等权限，调用无权限障碍。
- **查询**：优先反射 `areNotificationsEnabledForPackage(String)`（@hide）；失败回退 `AppOpsManager.checkOpNoThrow("android:post_notification", uid, pkg)`（public API 23+，int 常量版 `OP_POST_NOTIFICATION` 在 SDK 33 中为 hide），结果 `== MODE_ALLOWED` 视为启用，为近似值（用户手动改通知开关也会反映在此）。
- **厂商 ROM 适配（真机实测，MTK Android 13）**：
  - 该 ROM 的 `NotificationManager` **不包含** `setNotificationsEnabledForPackage`（任何变体）与 `areNotificationsEnabledForPackage(String)`，反射必然失败；
  - 该 ROM 的 `AppOpsManager.setMode`（int/String 两种变体）**静默无效**（不抛异常也不改状态）；
  - 回退链最终落到 `PackageManager.grantRuntimePermission/revokeRuntimePermission`（@hide 反射，系统 uid 或 device owner 可用），实测有效；执行前先校验目标包是否声明 `POST_NOTIFICATIONS`，未声明则跳过；
  - 设置前先读回当前 AppOps 模式，已处于目标状态则幂等直接返回（加速全量套用）。
- **API < 28**：命令返回 `false`，不抛异常。
- **参考**：现有 `MdmUtils.isMobileDataEnabled()` 的反射模式。

### 2.2 黑白名单（ASR-0054/0055）

- **存储**：`SharedPreferences(Const.PREFERENCES)`（与 `MdmUtils` 同文件），键 `ntf_policy_mode`（int）、`ntf_whitelist`（StringSet）、`ntf_blacklist`（StringSet），全量替换语义。
- **策略模式**：0=关闭（全部允许，策略不生效）；1=白名单（仅名单内包允许通知，其余全部禁用，含系统应用）；2=黑名单（名单内包禁用，其余允许）。
- **套用方式**：枚举 `PackageManager.getInstalledApplications(0)`，对每个包调用按包启用/禁用接口；**跳过 Launcher 自身包名**（`com.hmdm.launcher`），避免前台服务通知被禁导致服务保活异常。
- **新装应用自动套用**：`PackageChangedReceiver` 监听 `ACTION_PACKAGE_ADDED` / `ACTION_PACKAGE_REPLACED`（`<data android:scheme="package"/>`，exported=true，不指定包过滤），`intent.getData().getSchemeSpecificPart()` 取包名，调用 `NotificationPolicyManager.applyToPackage()`。策略模式为 0 时该调用为空操作。
- **注意**：黑名单模式同样作用于系统应用（系统应用也可能发通知），属预期全量管控行为；黑名单无法管控 Launcher 自身通知（被排除），可接受——MDM 自身通常需要发前台服务通知。

### 2.3 锁屏通知（ASR-0057）

- **接口**：`Settings.Secure.putInt(contentResolver, "lock_screen_show_notifications", 0|1)`。常量在 API 33 标记 deprecated 但仍由系统 SettingsProvider 响应，写 0 全局立即生效。
- **增强**：disabled=true 时同时写 `lock_screen_allow_private_notifications=0`，隐藏锁屏通知详情。
- **查询**：读 `lock_screen_show_notifications`，文件不存在默认 1（未禁用）。
- **权限**：`WRITE_SECURE_SETTINGS` + 平台签名（manifest 已声明）。

### 2.4 测试 APP（testapp 模块）

- 新 Gradle 模块 `testapp/`（`include ':app', ':lib', ':testapp'`），`com.android.application`，compileSdk 33 / minSdk 26 / targetSdk 33，普通应用（不声明 sharedUserId）。
- 复制 `SystemApiInterface.aidl`（`package syrius.mdm.mobile_operator;` 必须与 Launcher 一致），bindService 连接 `ApiService`（action `syrius.mdm.api_service`，component `com.hmdm.launcher/com.hmdm.launcher.syrius.service.ApiService`），事件队列 + 3s 重试，模式对齐 `mdm_sample/GoGoReady3/hxy_demo` 的 `MdmOfHXY`。
- 自身可发通知（POST_NOTIFICATIONS 运行时权限），用于验证管控效果。

## 3. 命令接口定义（SystemApiInterface.onEvent）

命令通过 `ApiBinder.method2Commands` 注册，`CallWithCommand` 包装调用，返回 `Map{"RESULT": 返回值}`。List 参数在 `ApiBinder.toBundle` 中按 Serializable 存入 Bundle，取回使用 `param.getSerializable("packageNames")` 强转 `List<String>`。

**参数兼容**：TestBroadcast 用 Gson 解析 JSON（数字解析为 Double）、`send_test_broadcast.sh` 传全字符串参数，因此 `SetNotificationsPolicyMode` 的 `mode`、`SetNotificationsEnabledForPackage` 的 `enabled`、`SetLockscreenNotificationsDisabled` 的 `disabled` 均兼容 Number / String / Boolean 三种取值（testapp binder 直传 Integer/Boolean 亦兼容）。

| 命令名 | 参数（Map） | 返回 RESULT | 说明 |
|---|---|---|---|
| `SetNotificationsEnabledForPackage` | packageName: String, enabled: Boolean | Boolean | ASR-0053 按包启用/禁用 |
| `IsNotificationsEnabledForPackage` | packageName: String | Boolean | ASR-0053 查询 |
| `SetNotificationsWhitelist` | packageNames: ArrayList\<String\> | Boolean | ASR-0054 全量替换白名单 |
| `GetNotificationsWhitelist` | - | List\<String\> | ASR-0054 读取 |
| `SetNotificationsBlacklist` | packageNames: ArrayList\<String\> | Boolean | ASR-0055 全量替换黑名单 |
| `GetNotificationsBlacklist` | - | List\<String\> | ASR-0055 读取 |
| `SetNotificationsPolicyMode` | mode: int（0/1/2） | Boolean | 设置模式并立即套用（mode≠0） |
| `GetNotificationsPolicyMode` | - | Integer | 读取模式 |
| `ApplyNotificationsPolicy` | - | Integer | 全量套用，返回受影响包数 |
| `SetLockscreenNotificationsDisabled` | disabled: Boolean | Boolean | ASR-0057 禁用/启用锁屏通知 |
| `IsLockscreenNotificationsDisabled` | - | Boolean | ASR-0057 查询 |

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> param = new HashMap<>();
param.put("packageName", "com.hmdm.testapp");
param.put("enabled", false);
Map result = api.onEvent("SetNotificationsEnabledForPackage", param);
boolean ok = (Boolean) result.get("RESULT");
```

```java
Map<String, Object> param = new HashMap<>();
param.put("packageNames", new ArrayList<>(Arrays.asList("com.hmdm.testapp", "com.android.settings")));
api.onEvent("SetNotificationsWhitelist", param);
```

```java
Map<String, Object> param = new HashMap<>();
param.put("mode", 1); // 0 关闭 / 1 白名单 / 2 黑名单
api.onEvent("SetNotificationsPolicyMode", param);
```

```java
Map<String, Object> param = new HashMap<>();
param.put("disabled", true);
api.onEvent("SetLockscreenNotificationsDisabled", param);
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/NotificationPolicyManager.java            # 核心工具类（策略存储/套用/锁屏/反射）
├── service/command/notification/                   # 11 个命令类
│   ├── SetNotificationsEnabledForPackage.java
│   ├── IsNotificationsEnabledForPackage.java
│   ├── SetNotificationsWhitelist.java
│   ├── GetNotificationsWhitelist.java
│   ├── SetNotificationsBlacklist.java
│   ├── GetNotificationsBlacklist.java
│   ├── SetNotificationsPolicyMode.java
│   ├── GetNotificationsPolicyMode.java
│   ├── ApplyNotificationsPolicy.java
│   ├── SetLockscreenNotificationsDisabled.java
│   └── IsLockscreenNotificationsDisabled.java
├── broadcast/PackageChangedReceiver.java           # 新装/替换应用自动套用策略
└── activity/NotificationMgActivity.java            # 手动验证管理页

app/src/main/AndroidManifest.xml                    # receiver + activity 注册（权限已齐）
app/src/main/res/layout/activity_notification_mg.xml

testapp/                                            # 测试 APP 模块
├── build.gradle / proguard-rules.pro
└── src/main/
    ├── aidl/syrius/mdm/mobile_operator/SystemApiInterface.aidl   # 复制自 Launcher
    ├── java/com/hmdm/testapp/MdmApiClient.java    # bindService + 事件队列 + 重试
    ├── java/com/hmdm/testapp/NotificationSender.java # 测试通知发送
    ├── java/com/hmdm/testapp/MainActivity.java    # 分区测试 UI
    └── res/...                                     # 布局/字符串/图标
```

## 5. 策略逻辑

```
setPolicyMode(mode):
  校验 mode ∈ {0,1,2}，写 SharedPreferences
  mode != 0 时立即 applyPolicy()

applyPolicy():
  mode == 0 → 直接返回 0（不生效）
  遍历 getInstalledApplications(0)，跳过自身包名
  对每个包按 mode 决定 enabled 并调用 setNotificationsEnabledForPackage
  返回成功设置包数

applyToPackage(pkg):   # 新装/替换时调用
  mode == 0 → false（空操作）
  白名单: enabled = whitelist.contains(pkg)
  黑名单: enabled = !blacklist.contains(pkg)
  返回 setNotificationsEnabledForPackage 结果
```

## 6. 权限与归属

- Launcher（MDM）+ 系统 API：平台签名应用调用 @SystemApi/@hide 隐藏接口与受保护系统设置。
- 依赖权限：`WRITE_SECURE_SETTINGS`（锁屏设置）、`MANAGE_APP_OPS_MODES`/系统 uid（AppOps 回退查询）、`POST_NOTIFICATIONS`（自身通知），manifest 均已声明。
- 不修改 `IMdmApi.aidl` / `lib` 模块，对外命令通道仍为 `SystemApiInterface.onEvent`，客户端无需升级协议。
- 不新增 ROM 侧代码。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| API < 28 调用按包控制 | 返回 false，无 crash |
| 包名空/未安装 | 空包名直接 false；未安装包反射调用返回 false，查询返回 false |
| 反射失败（厂商 ROM 差异） | catch 后 Logger 记录，返回 false；查询回退 AppOps；设置回退 AppOps.setMode（带读回验证）→ 权限 grant/revoke |
| 目标包未声明 POST_NOTIFICATIONS | 跳过不处理（返回 false），避免 grant/revoke 抛 IllegalArgumentException |
| 空名单 / 非法 mode | 名单允许空（清空）；mode 非法返回 false |
| 黑名单含系统应用 | 正常生效（预期全量管控），文档注明 |
| Launcher 自身 | applyPolicy 显式跳过，策略无法管控自身（预期） |
| 锁屏常量不响应（厂商 ROM） | 命令返回 putInt 结果；真机验收点见测试用例文档 |
| 厂商 ROM 差异（系统应用可能无权限限制） | 文档注明，以真机验收为准 |

## 8. 真机验证记录（2026-08-03，MTK Android 13 userdebug，平台签名 + device owner）

| 用例 | 结果 |
|---|---|
| ASR-0053 禁用 testapp → 发通知 | `POST_NOTIFICATION: ignore`，通知不出现（0 条记录） |
| ASR-0053 启用 testapp → 发通知 | 通知正常展示（1 条记录） |
| ASR-0054 白名单不含 testapp + apply | 通知被抑制；白名单加入后 apply（affected:83）→ 正常展示 |
| ASR-0055 黑名单含 testapp + apply | 通知被抑制；清空后 apply（affected:159）→ 正常展示 |
| ASR-0057 锁屏禁用/启用 | `lock_screen_show_notifications` 0↔1，查询 true/false 正确 |
| 查询命令 | Get/Is 全部返回预期值（模式、白/黑名单、通知状态） |
| 广播触发 | `am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast ...` 全流程可驱动 |

**真机环境注意**：launcher 需平台签名安装（ROM 平台密钥签名，`sharedUserId=android.uid.system`，uid=1000）；Launcher 的安装白名单（`App.java` 中 `InstallWhitelistManager` 正则）需包含 `com.hmdm.*`，否则 testapp 安装即被自动卸载；testapp 支持 `am start -n com.hmdm.testapp/.MainActivity --ez send_test_notification true` 免 UI 触发测试通知。
