# 默认应用与意图管控（ASR-0091/0095/0102）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 实现机制 |
|---|---|---|---|
| ASR-0091 | 默认应用 | 管控用户修改默认桌面 | 组件锁（本 ROM 无 `no_config_home_app` 限制键）：禁用 PermissionController HomeSettingsActivity（HOME_SETTINGS 入口）+ RequestRoleActivity/DefaultAppActivity（角色确认） |
| ASR-0095 | 默认应用 | 设置 Video player 默认应用 | `dpm.addPersistentPreferredActivity`（ACTION_VIEW + CATEGORY_DEFAULT + `video/*`）绑定目标 Activity，无选择器直达 |
| ASR-0102 | 默认应用 | 设置打开指定文件类型的默认应用 | 同上机制，MIME 类型由调用方指定（如 `application/pdf`） |

**归属**：ASR-0091/0095/0102 均为 Launcher（MDM）（device owner 公开 DPM 接口）。无 ROM 侧代码改动。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署。

## 2. 技术选型与可行性核验

### 2.1 ASR-0091 管控修改默认桌面

- **首选机制核验失败**：`dpm.addUserRestriction(admin, "no_config_home_app")`（DISALLOW_CONFIG_HOME_APP，AOSP 13 标准"禁改默认桌面"限制）在本 ROM **静默不生效**——2026-08-11 真机核验：写入无异常、`getUserRestrictions` 读回恒 false、`dumpsys user` 限制表无条目；root 通道 `framework.jar` dex 字符串扫描确认**本 ROM 的 framework 根本没有 `no_config_home_app` 键**（同批核验 no_physical_media/no_adjust_volume/no_data_roaming 均存在），ROM 级移除该限制；
- **落地机制（组件锁）**：锁定=禁用 PermissionController 的 **HomeSettingsActivity**（`android.settings.HOME_SETTINGS` 解析目标，即 Settings"默认应用→主屏幕应用"选择器入口）+ **RequestRoleActivity/DefaultAppActivity**（HOME 角色确认流转，与 ASR-0086/0088/0093/0100 共用同一角色确认组件集）；解锁=全部恢复 DEFAULT；逐组件 `getComponentEnabledSetting` 读回核对；真机闭环：锁定后 `resolve-activity -a android.settings.HOME_SETTINGS` = No activity found（选择器入口失效），解锁后恢复；
- 标志持久化 SharedPreferences `default_launcher_lock`，查询返回标志 + 三组件实时状态；
- 与 ASR-0090（设置默认桌面，`addPersistentPreferredActivity` HOME 绑定）正交：0090 决定谁是默认桌面，0091 决定用户能否改（本 ROM 0090 的 PPTA 通道同样受限，见 2.2 节说明）。

### 2.2 ASR-0095/0102 默认应用意图绑定

- 机制：device owner `dpm.addPersistentPreferredActivity(admin, IntentFilter, ComponentName)`——绑定对 ACTION_VIEW + 指定 MIME 类型 + CATEGORY_DEFAULT 的意图解析，用户/应用发起该类打开不再弹选择器（与 ASR-0098 浏览器批次 `BrowserPolicyManager` 同通道同形制）；
- 目标 Activity 解析：调用方显式给 `packageName + activityName`（短格式兼容）；仅给 packageName 时引擎自动解析该包第一个接受该 MIME 类型的可用 ACTION_VIEW activity（`queryIntentActivities` + setPackage）；
- 查询：`PackageManager.resolveActivity(ACTION_VIEW + type + CATEGORY_DEFAULT, MATCH_DEFAULT_ONLY)` 读回核对（返回当前解析的组件）；
- 清除：`dpm.clearPackagePersistentPreferredActivities(admin, packageName)` 按包清除（清除指定包的 persistent preferred 绑定），随后读回核对；
- **2026-08-11 真机核验：本 ROM 的 DPMS 静默丢弃 `addPersistentPreferredActivity` 写入**——写入无异常，但 `/data/system/users/0/preferred_activities.xml` 从未生成、`dumpsys package preferred-activities` 无新增条目、受控实验（video/mp4 绑定到非视频包 com.android.music）解析不跟随（仍为 manifest 解析结果）；浏览器批次（ASR-0098）同路径在本 ROM 同样不生效（其"生效"表象为 webview_shell 本就是 ROM 预置浏览器）。**本 ROM 无任何替代 API**（无 MIME 类型角色、无 set-preferred-app shell 命令、cmd package 仅 set-home-activity）。按用户决策：命令保留、如实上报（success=false + note），ASR-0095/0102 维持部分完成待 ROM 适配 preferred 机制；
- 本机另核验：gallery3d MovieActivity 的 video/* manifest 过滤带 scheme（http/https/content/file），type-only 意图不匹配、带 file:// data 才匹配——与 PPTA 无关的 manifest 行为，命令查询口径（resolveActivity type-only）如实反映。

## 3. 命令接口定义（SystemApiInterface.onEvent）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetDefaultLauncherSettingLocked` | locked | Map：{success, locked, components[]}（组件锁：HomeSettingsActivity/RequestRoleActivity/DefaultAppActivity） | ASR-0091 |
| `IsDefaultLauncherSettingLocked` | 无 | Map：{locked, 三组件实时状态（DEFAULT/DISABLED）} | ASR-0091 |
| `SetDefaultVideoPlayer` | packageName（必填）, activityName（可选，缺省自动解析） | Map：{success, mimeType, packageName, component, readBack, note?} 或 {error} | ASR-0095 |
| `GetDefaultVideoPlayer` | 无 | Map：{success, mimeType=video/*, packageName, component} | ASR-0095 |
| `ClearDefaultVideoPlayer` | packageName | Map：{success, mimeType, packageName, readBack} | ASR-0095 |
| `SetDefaultAppForFileType` | mimeType（必填）, packageName（必填）, activityName（可选） | Map：{success, mimeType, packageName, component, readBack, note?} 或 {error} | ASR-0102 |
| `GetDefaultAppForFileType` | mimeType | Map：{success, mimeType, packageName, component} | ASR-0102 |
| `ClearDefaultAppForFileType` | mimeType, packageName | Map：{success, mimeType, packageName, readBack} | ASR-0102 |

**调用示例**：

```java
Map<String, Object> p = new HashMap<>();
p.put("packageName", "com.android.gallery3d");
Map result = api.onEvent("SetDefaultVideoPlayer", p);
// {"RESULT":{"component":"com.android.gallery3d/.app.MovieActivity","success":true,"mimeType":"video/*",...}}

Map result2 = api.onEvent("SetDefaultAppForFileType", 
        mapOf("mimeType", "application/pdf", "packageName", "com.android.documentui"));
```

**广播通道**：

```bash
./send_test_command.sh SetDefaultLauncherSettingLocked locked=true
./send_test_command.sh SetDefaultVideoPlayer packageName=com.android.gallery3d
./send_test_command.sh SetDefaultAppForFileType mimeType=application/pdf packageName=com.android.documentui
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/DefaultAppIntentPolicyManager.java   # 新增：限制引擎 + persistent preferred activity 引擎（视频/文件类型通用）
├── service/command/default_app/
│   ├── SetDefaultLauncherSettingLocked.java / IsDefaultLauncherSettingLocked.java   # 新增：ASR-0091
│   ├── SetDefaultVideoPlayer.java / GetDefaultVideoPlayer.java / ClearDefaultVideoPlayer.java   # 新增：ASR-0095
│   └── SetDefaultAppForFileType.java / GetDefaultAppForFileType.java / ClearDefaultAppForFileType.java  # 新增：ASR-0102
└── service/ApiBinder.java                      # 注册 8 个新命令

testapp/
├── src/main/java/com/hmdm/testapp/
│   ├── DefaultIntentTestActivity.java          # 新增测试页（包名/Activity/MIME 输入 + 按钮）
│   ├── TestActions.java                        # 新增 8 个事件与事件目录
│   ├── MainActivity.java                       # 新增入口按钮
│   └── res/layout/activity_default_intent_test.xml  # 新增布局
```

## 5. 执行逻辑

```
SetDefaultLauncherSettingLocked(locked):
  1. 持久化标志 default_launcher_lock
  2. 锁定：逐组件 setComponentEnabledSetting(DISABLED)；解锁：恢复 DEFAULT（三组件：HomeSettingsActivity/RequestRoleActivity/DefaultAppActivity）
  3. 逐组件 getComponentEnabledSetting 读回核对 → success

SetDefaultVideoPlayer(packageName[, activityName]):
  1. 校验 device owner 身份（非 DO 返回 error）
  2. 解析目标 ComponentName（显式 activityName 或包内自动解析第一个 video/* 可处理 activity）
  3. IntentFilter(ACTION_VIEW) + CATEGORY_DEFAULT + addDataType("video/*")
  4. dpm.addPersistentPreferredActivity → resolveActivity(MATCH_DEFAULT_ONLY) 读回核对
  5. 目标与读回一致才 success=true

SetDefaultAppForFileType(mimeType, packageName[, activityName]):
  同 SetDefaultVideoPlayer，仅 addDataType(mimeType) 为调用方指定
```

**安全设计**：无 shell 命令；MIME 类型经 `IntentFilter.MalformedMimeTypeException` 校验；Activity 短格式经 `ComponentName.unflattenFromString` 校验。

## 6. 权限与归属

- `addPersistentPreferredActivity`/`clearPackagePersistentPreferredActivities`/用户限制：device owner 公开接口（SET_PREFERRED_APPLICATIONS 签名权限 manifest 既有声明），无需 uses-policy 声明；
- 无需新 manifest 权限、无 `device_admin.xml` 变更、无 AIDL/lib 模块变更、无需重启 framework。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 非 device owner 调用 | 返回 error "not device owner"，不执行 |
| 目标包无匹配 MIME 的 ACTION_VIEW activity | error "no ACTION_VIEW activity accepting <mime> found for <pkg>"，不写绑定 |
| MIME 格式非法 | error "malformed MIME type"，不写绑定 |
| 显式 activityName 非法/与包不匹配 | ComponentName 解析失败 → error |
| 清除指定包绑定 | clearPackagePersistentPreferredActivities(包名) 后读回；读回仍为该包时 success=false + note |
| 绑定后用户经系统 UI 改回 | persistent preferred 为框架级绑定，普通用户无入口改写（同浏览器批次行为） |
| 整机重启 | preferred_activities.xml 持久化，重启保持，无需重新武装 |

## 8. 真机验证记录

（2026-08-11 批次；命令级用例结果见测试用例设计文档，此处记录机制核验关键结论）

| 核验项 | 结果 |
|---|---|
| no_config_home_app 限制读写 | **本 ROM framework 无该限制键**（dex 扫描），addUserRestriction 静默丢弃（读回恒 false）→ 弃用，改组件锁 |
| 组件锁（ASR-0091） | 通过：锁定后三组件 DISABLED + HOME_SETTINGS 解析 No activity found；解锁恢复 DEFAULT + 解析恢复；标志持久化重启保持 |
| 默认视频播放器绑定（ASR-0095） | **本 ROM DPMS 静默丢弃 PPTA 写入**（无文件/无条目/解析不跟随，受控实验），命令如实 success=false；解析路径核验：gallery3d MovieActivity 需带 file:// data 才匹配（manifest scheme 约束） |
| 文件类型默认应用（ASR-0102） | 同 ASR-0095（PPTA 静默丢弃）；非法 MIME 拒绝路径通过（malformed MIME type） |
| 清除绑定 | 接口正常调用（本 ROM 无绑定可清），读回如实 |
| 测试后设备恢复 | 通过（组件恢复 DEFAULT、无残留绑定） |
