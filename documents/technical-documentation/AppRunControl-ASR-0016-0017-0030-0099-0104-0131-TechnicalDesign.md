# 应用运行/隐藏管控（ASR-0016/0017/0030/0099/0104/0131）技术设计文档

## 1. 需求概述

| 需求 ID | 子功能项 | 需求语义 |
|---|---|---|
| ASR-0017 | 禁止运行白名单 | 获取/设置禁止运行白名单：名单内应用不允许运行（挂起 + 隐藏） |
| ASR-0016 | 耗电应用免清理 | 获取/设置耗电应用免清理名单（应用不被省电清理/耗电优化策略回收） |
| ASR-0030 | 忽略耗电优化应用白名单 | 获取/设置忽略耗电优化（Doze/App Standby 电源白名单）应用名单 |
| ASR-0099 | 禁用系统预装浏览器 | 获取/设置是否禁用系统预装浏览器 |
| ASR-0104 | 启用 Settings 应用 | 获取/设置是否启用 Settings 应用（解除隐藏） |
| ASR-0131 | 禁用 Google Play Store | 获取/设置是否禁用 Google Play Store 应用 |

**归属**：ASR-0017/0099/0104/0131 为「Launcher（MDM）」——公开 DevicePolicyManager（device owner）即可实现；ASR-0016/0030 为「Launcher（MDM）+ 系统 API」——查询用公开 `PowerManager.isIgnoringBatteryOptimizations`，设置因本 ROM 缺少 `PowerManager.requestIgnoreBatteryOptimizations`（MTK framework 未包含该 @SystemApi 方法）且隐藏接口 `IPowerManager` binder 被 hidden API 限制拦截（Launcher 为 /data 安装、非预装豁免），最终经 `cmd deviceidle whitelist +/-<pkg>` shell 命令落地（uid=1000 为系统 uid，允许执行 shell 命令；device owner 授予免交互）。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署；bootloader 锁定，不可改动 system 分区。

## 2. 技术选型与可行性核验

### 2.1 ASR-0017 禁止运行白名单

| 方案 | 说明 | 结论 |
|---|---|---|
| `DevicePolicyManager.setPackagesSuspended(admin, [pkg], suspended)`（API 24+） | 挂起应用：不可启动、进程被强制结束、通知被隐藏；返回 `String[]`（失败包列表）。DO 可对系统应用执行（真机核验 settings/gallery3d 均可挂起） | 采用（主路径） |
| `DevicePolicyManager.setApplicationHidden(admin, pkg, hidden)`（API 21+） | 隐藏应用：从启动器与解析表中移除（隐藏后 `am start` 报 "Activity class does not exist"）；对系统应用同样有效（真机核验 settings） | 采用（叠加路径） |

**名单语义**：白名单整体替换（与通知/网络黑白名单一致），命令内部对新增包执行 挂起+隐藏，对移除包执行 解除挂起+解除隐藏；Launcher 与 testapp 为保护名单（不可被加入）。

**行为细节**（真机核验）：挂起会立即强制结束目标进程；隐藏后应用的 Activity 无法解析（等价"禁止运行"）。系统应用（settings/gallery3d）可被挂起与隐藏；不存在的包返回失败条目（DPM 拒绝）。

### 2.2 ASR-0016/0030 耗电/电池优化白名单

Android 13 上"耗电应用免清理"与"忽略耗电优化"均对应同一底层机制：**Doze/App Standby 电源省电白名单**（`dumpsys deviceidle whitelist` 的 user 条目），二者共用一套引擎与命令。

| 方案 | 说明 | 结论 |
|---|---|---|
| `PowerManager.isIgnoringBatteryOptimizations(pkg)`（公开 API 23+） | 查询单包是否在电源白名单 | 采用（查询路径） |
| `PowerManager.requestIgnoreBatteryOptimizations(pkg)`（@SystemApi 23+） | DO 授予免交互。**本 MTK ROM framework.jar 中不存在该方法**（dexdump 核验），无法反射调用 | 放弃 |
| `IPowerManager.addPowerSaveWhitelistApp / removePowerSaveWhitelistApp`（binder，@hide） | 底层接口存在（dexdump 核验 9/17 处）；但 `android.os.IPowerManager` 属 blacklist 隐藏 API，Launcher 为 /data 安装（非预装、无平台域豁免），运行时被 hidden API 限制拦截（实测 `asInterface` 抛 NoSuchMethodException） | 放弃 |
| `cmd deviceidle whitelist +<pkg> / -<pkg>`（DeviceIdleController shell 命令） | **采用**。真机核验：`su 1000 sh -c 'cmd deviceidle whitelist +com.hmdm.testapp'` → "Added: ..."；移除 → "Removed: ..."；`dumpsys deviceidle whitelist` 出现/消失 user 条目；`isIgnoringBatteryOptimizations` 同步翻转。原理：ShellCommand 允许 {shell(2000), root(0), system(1000)} 三类 uid 执行；Launcher 恰为 uid=1000；命令内部经 DeviceIdleController → PowerManagerService.addPowerSaveWhitelistApp（内部路径），device owner 校验通过，全程免交互 | 采用（设置路径） |

**名单语义**：整体替换；新增包执行 `+pkg`，移除包执行 `-pkg`（幂等：包已不在白名单时跳过移除）。不存在包的 `+pkg` 被系统静默忽略（实测 added 条目出现但 ignoring=false），文档化限制。设备重启后系统持久化 user 白名单（`com.android.phone` 预置条目即用户授予），Launcher 侧另有 SharedPreferences 持久化管理名单。

### 2.3 ASR-0099/0104/0131 禁用/启用指定应用

均复用既有 `SetApplicationHidden` / `IsApplicationHidden` 命令（`setApplicationHidden` 公开 DPM 接口，API 21+）：

- ASR-0099 禁用系统预装浏览器：`SetApplicationHidden(com.ume.browser, true)`（本 ROM 预装浏览器实测为 `com.ume.browser`，/data 安装；系统内另有浏览器能力应用 `org.chromium.webview_shell`，来自 /product/app/Browser2）；
- ASR-0104 启用 Settings 应用：`SetApplicationHidden(com.android.settings, false)`（真机核验系统应用可隐藏/解除隐藏）；
- ASR-0131 禁用 Google Play Store：`SetApplicationHidden(com.android.vending, true)`（本 ROM 无 GMS，包不存在时 DPM 返回 false）。

本次改进：`SetApplicationHidden` 命令原实现恒返回 `true`（忽略 DPM 返回值），改为返回真实 DPM 结果（包不存在/隐藏失败时返回 false），缺参返回 `{error}`；`ApplicationHelper.setApplicationHidden` 相应返回 boolean（既有 UI 调用方忽略返回值，无影响）。

### 2.4 与安装白名单策略的交互（重要边界）

Launcher 既有 `InstallWhitelistManager`（App.java 注册，监听 `PACKAGE_ADDED`）对不匹配正则（`com\.syriusrobotics\..*`、`com\.hmdm\..*` 等）的**非系统应用执行静默卸载**。隐藏操作会触发 `PACKAGE_REMOVED`、**解除隐藏会触发 `PACKAGE_ADDED`**（隐藏包视为未安装）。因此：

- 解除隐藏一个**非白名单、非系统**的应用会触发既有策略的静默卸载（测试期实测：com.ume.browser 在解除隐藏后 15:54:07 被该策略卸载，数据丢失）；
- 系统应用（settings/gallery3d/webview_shell）卸载失败、无害（实测卸载尝试后应用完好）；
- testapp（`com\.hmdm\..*`）在白名单内，不受影响。

结论：ASR-0017 移除名单、ASR-0099/0104/0131 解除隐藏的路径与安装白名单策略存在耦合，属既有产品行为（安装白名单语义即"只允许白名单应用存在"），本功能不修改该策略，文档化此限制；对系统应用场景（预装浏览器/系统应用）无实际影响。

## 3. 命令接口定义（SystemApiInterface.onEvent）

### 3.1 新增命令（本批次）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetBlockedRunningWhitelist` | packageNames（String 数组，必填，整体替换） | Map（见下） | ASR-0017 |
| `GetBlockedRunningWhitelist` | 无 | Map：{success, list:[{packageName, suspended, hidden}]} | ASR-0017 |
| `IsPackageSuspended` | packageName（必填） | Map：{packageName, suspended, hidden} 或 {error} | ASR-0017 |
| `SetIgnoreBatteryOptimizationWhitelist` | packageNames（String 数组，必填，整体替换） | Map（见下） | ASR-0016/0030 |
| `GetIgnoreBatteryOptimizationWhitelist` | 无 | Map：{success, list:[{packageName, ignoring}]} | ASR-0016/0030 |
| `IsIgnoringBatteryOptimization` | packageName（必填） | Map：{packageName, ignoring} 或 {error} | ASR-0016/0030 |

**SetBlockedRunningWhitelist 返回结构**：

```
{success:boolean, added:[{packageName, suspended, hidden}, ...],
 removed:[{packageName, suspended, hidden}, ...],
 failed:[{packageName, error}, ...], skipped:[String,...]}
# skipped 为保护名单（com.hmdm.launcher / com.hmdm.testapp）中请求的包
# success=false 当存在 failed 条目或持久化失败；success=true 仅当全部请求生效且已持久化
```

**SetIgnoreBatteryOptimizationWhitelist 返回结构**：

```
{success:boolean, added:[{packageName, ignoring}, ...],
 removed:[{packageName, ignoring}, ...],
 failed:[{packageName, error}, ...]}
# 差异整体以单次 shell 调用应用（单进程启动），随后逐包用
# isIgnoringBatteryOptimizations 复核真实状态，added/removed 条目与实际系统状态一致；
# 不存在的包/非法包名在应用前即拒绝（failed）；success=false 当存在 failed 条目或持久化失败
```

### 3.2 复用命令（ASR-0099/0104/0131）

| 命令名 | 参数 | 返回 RESULT | 说明 |
|---|---|---|---|
| `SetApplicationHidden` | packageName（必填）、hidden（必填） | boolean（真实 DPM 结果）或 {error} | 本次改进返回值 |
| `IsApplicationHidden` | packageName（必填） | boolean | 隐藏状态查询 |

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> param = new HashMap<>();
param.put("packageNames", Arrays.asList("com.android.gallery3d"));
Map result = api.onEvent("SetBlockedRunningWhitelist", param);
// {"RESULT":{"success":true,"added":[{"packageName":"com.android.gallery3d","suspended":true,"hidden":true}],"removed":[],"failed":[],"skipped":[]}}

Map<String, Object> p2 = new HashMap<>();
p2.put("packageNames", Arrays.asList("com.hmdm.testapp"));
Map result2 = api.onEvent("SetIgnoreBatteryOptimizationWhitelist", p2);
// {"RESULT":{"success":true,"added":[{"packageName":"com.hmdm.testapp","ignoring":true}],...}}

api.onEvent("SetApplicationHidden", {"packageName":"com.ume.browser","hidden":true}); // RESULT: true
```

**广播通道**（TestBroadcast，与现有命令一致）：

```bash
adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast \
  -a ON_SQA_INTERFACE_TEST --es event SetBlockedRunningWhitelist \
  --es param '{"packageNames":["com.android.gallery3d"]}'
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/
│   ├── AppRunPolicyManager.java        # 新增：双白名单引擎（持久化 + 差异应用 + 保护名单 + deviceidle 设置）
│   ├── ApplicationHelper.java          # 修改：setApplicationHidden 返回真实 DPM 结果
│   └── ShellUtils.java                 # 既有：shell 命令执行（cmd deviceidle 落地）
├── service/command/run_control/
│   ├── SetBlockedRunningWhitelist.java          # 新增：ASR-0017 名单替换
│   ├── GetBlockedRunningWhitelist.java          # 新增：ASR-0017 名单查询（含实时状态）
│   ├── IsPackageSuspended.java                  # 新增：ASR-0017 单包状态查询
│   ├── SetIgnoreBatteryOptimizationWhitelist.java # 新增：ASR-0016/0030 名单替换
│   ├── GetIgnoreBatteryOptimizationWhitelist.java # 新增：ASR-0016/0030 名单查询
│   └── IsIgnoringBatteryOptimization.java       # 新增：ASR-0016/0030 单包状态查询
├── service/command/application_hidden/SetApplicationHidden.java # 修改：返回真实结果
└── service/ApiBinder.java             # 注册 6 个新命令
    AndroidManifest.xml                # 新增 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS / DEVICE_POWER 声明

testapp/
├── src/main/java/com/hmdm/testapp/
│   ├── AppRunPolicyTestActivity.java  # 新增：测试页（名单增删/查询/状态查询/指定应用隐藏）
│   ├── TestActions.java               # 新增 8 个事件（含参数校验）与事件目录
│   ├── BaseTestActivity.java          # 新增 apiCall 帮助方法
│   └── MainActivity.java              # 增加 "App run policy" 入口
├── src/main/res/layout/activity_app_run_policy_test.xml # 新增测试页布局
└── src/main/AndroidManifest.xml       # 注册 AppRunPolicyTestActivity
```

## 5. 执行逻辑

```
SetBlockedRunningWhitelist(packageNames):
  1. 参数清洗（去空/去重）；加载持久化名单做差集
  2. 新增包：保护名单(com.hmdm.launcher/com.hmdm.testapp) → skipped；
     否则 setPackagesSuspended([pkg], true)——失败立即中止该包（跳过隐藏，避免
     "已隐藏但未禁止运行"的孤儿状态），成功后才 setApplicationHidden(pkg, true)；
     全部成功 → added 并持久化，任一失败 → failed 且不持久化
  3. 移除包：setPackagesSuspended([pkg], false)——失败立即中止（跳过解除隐藏）；
     成功后才 setApplicationHidden(pkg, false)；成功 → removed 并移除持久化
  4. 返回 {success(全成功且持久化成功), added, removed, failed, skipped}

SetIgnoreBatteryOptimizationWhitelist(packageNames):
  1. 参数清洗；加载持久化名单做差集
  2. 校验：非法包名（非 [a-zA-Z0-9_.]+）或未安装的包 → failed，不进入应用
  3. 差集整体拼接为单次 shell 脚本 "cmd deviceidle whitelist +p1; ... -p2; ..."，
     单次 ShellUtils.exec 执行（一次进程启动，避免逐包 10s 超时放大）
  4. 逐包用 isIgnoringBatteryOptimizations 复核：状态翻转 → added/removed 并持久化；
     未翻转 → failed（如实反映系统状态，杜绝"报成功但未生效"）
  5. 返回 {success(全成功且持久化成功), added, removed, failed}

GetBlockedRunningWhitelist / GetIgnoreBatteryOptimizationWhitelist:
  持久化名单逐项附加实时系统状态（suspended+hidden / ignoring）

SetApplicationHidden(packageName, hidden):
  缺参 → {error}；dpm.setApplicationHidden 真实结果返回
```

**安全设计**：`ApiService` 为 exported 且无权限保护（既有设计），所有命令参数来自任意调用方；本批新增的 `cmd deviceidle` shell 执行面是唯一的 shell 拼接点，包名在进入 shell 前必须通过 `PACKAGE_NAME_PATTERN`（`[a-zA-Z0-9_.]+`，Android 包名字符集）校验并确认已安装，杜绝命令注入（真机以 `a; id`、`$(...)` 载荷验证被拒）。

## 6. 权限与归属

- ASR-0017/0099/0104/0131：Launcher（MDM），device owner 即可，无需新增权限（setPackagesSuspended/setApplicationHidden 为 DPM 管理接口）。
- ASR-0016/0030：Launcher（MDM）+ 系统 API。查询 `isIgnoringBatteryOptimizations` 为公开 API；设置经 shell 命令 `cmd deviceidle`（uid=1000 系统 uid 允许执行 shell 命令），manifest 新增声明（平台签名/系统 uid 自动授予，核验 granted=true）：
  - `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（normal，deviceidle 命令内部权限校验）；
  - `android.permission.DEVICE_POWER`（signature，DEVICE_POWER 预留，本实现未直接使用）。
- 无 ROM 侧代码改动，无 root 依赖；不修改 AIDL / lib 模块；testapp 无需新增权限。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺 packageNames / packageName | testapp 侧返回 `missing parameter: packageNames (array)` / `missing parameter: packageName`；命令侧 SetApplicationHidden 缺参返回 {error}，不 crash |
| 保护名单（Launcher/testapp）加入禁止运行名单 | 跳过并在 skipped 中返回（防止破坏管控链与测试通道） |
| **非法包名 / 命令注入载荷**（如 `a; id`、`$(...)`） | 进入电池名单前被 `[a-zA-Z0-9_.]+` 校验拒绝 → failed："invalid package name"，任何字符不进入 shell（真机验证载荷被拒、无副作用） |
| 目标为不存在的包 | 禁止运行名单：setPackagesSuspended 失败 → failed（不持久化）；电池名单：应用前校验拒绝 → failed："package not installed"（不再出现"报 added 但 ignoring=false"的假象） |
| **挂起失败但隐藏本可成功** | 立即中止该包（跳过隐藏），不产生"已隐藏而未禁止运行"的孤儿状态（与设计口径一致） |
| 解除挂起失败但解除隐藏本可成功 | 立即中止（跳过解除隐藏），不产生"可见但仍挂起"的孤儿状态 |
| 移除名单触发解除隐藏 → PACKAGE_ADDED → 安装白名单策略 | 系统应用：卸载失败无害；非白名单非系统应用：会被既有策略静默卸载（既有产品行为，文档化；与 ASR-0099 的 ume.browser 实测一致） |
| 系统应用挂起/隐藏 | 均可用（真机核验 settings/gallery3d）；解除隐藏同样安全 |
| setPackagesSuspended 返回 String[] 失败列表 | 取首个失败包名构造错误信息并中止该包 |
| 电池名单应用后系统状态未翻转（cmd 缺失/被拒/静默忽略） | 逐包 isIgnoringBatteryOptimizations 复核 → failed："still not ignoring battery optimizations after add/remove"，不持久化，不报假成功 |
| 全部失败或 SharedPreferences 提交失败 | success=false（added/removed 如实为空），调用方可据此重试 |
| 本 ROM 无 Google Play Store | SetApplicationHidden(com.android.vending) 返回 false（DPM 对不存在包拒绝），机制本身由 ASR-0099/0104 覆盖验证 |
| 持久化写失败 | success=false，系统状态可能已应用（下次 Set 以实际系统状态为准对账） |

## 8. 真机验证记录（2026-08-04，Android 13 / API 33 userdebug，平台签名 + device owner）

| 用例 | 结果 |
|---|---|
| TC-0017-01 基线查询 | `GetBlockedRunningWhitelist` → `{"list":[],"success":true}` |
| TC-0017-02 添加 gallery3d | `added:[{suspended:true,hidden:true,...}]`；dumpsys 对照 hidden=true suspended=true |
| TC-0017-04 启动被阻止 | `am start` → "Activity class does not exist"（隐藏包不可解析） |
| TC-0017-06 批量添加（settings+gallery3d） | 均 added；settings 系统应用挂起成功 |
| TC-0017-08 保护名单 | `skipped:["com.hmdm.launcher","com.hmdm.testapp"]` |
| TC-0017-09 不存在包 | `failed:[{error:"setPackagesSuspended failed for: com.not.exist"}]` |
| TC-0017-10/11 移除与清空 | removed 条目 + 系统状态恢复（hidden=false suspended=false）；gallery3d 可重新启动 |
| TC-0030-02 添加 testapp | `cmd deviceidle whitelist +com.hmdm.testapp` → "Added"；isIgnoring=true；deviceidle whitelist 出现 user 条目；免交互 |
| TC-0030-05 移除 testapp | "Removed"；isIgnoring=false；user 条目消失；gallery3d 保持 true |
| TC-0030-08 不存在包 | added 但 ignoring=false（系统静默忽略） |
| TC-0099-02/03 隐藏/恢复系统浏览器 | webview_shell 隐藏 → 启动失败 → 解除隐藏 → 可启动（真实返回 true/false） |
| TC-0104-01 Settings 启用 | 隐藏/解除隐藏均成功（系统应用） |
| TC-0131-01 不存在的 Play Store | `SetApplicationHidden(com.android.vending)` → false（真实 DPM 结果） |

**部署与实现注意**：
1. ASR-0016/0030 的设置在 MTK ROM 上依赖 `cmd deviceidle whitelist` shell 命令与 uid=1000 执行权限；若移植到缺少该命令的 ROM，需改回 binder 路径（预装部署时 `IPowerManager` 不受 hidden API 限制）。
2. 本 ROM 预装浏览器 `com.ume.browser` 为 /data 安装；测试期间因解除隐藏触发既有安装白名单策略被静默卸载（非本功能缺陷）。系统预装浏览器能力应用为 `org.chromium.webview_shell`（/product/app/Browser2），ASR-0099 实测以此为目标。
3. 隐藏系统 Settings 测试期间注意及时解除（本批测试均即时恢复，最终设备状态：settings/webview_shell 均 hidden=false，白名单无残留 user 条目）。
4. testapp 广播调用需保持 `dumpsys duraspeed addwhitelist com.hmdm.testapp`；屏幕常亮 `svc power stayon true`。
