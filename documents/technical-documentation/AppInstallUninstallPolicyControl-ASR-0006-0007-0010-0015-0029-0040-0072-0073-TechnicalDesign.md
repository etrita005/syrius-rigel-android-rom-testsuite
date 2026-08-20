# 应用安装/卸载策略与运行查询管控（ASR-0006/0007/0010/0015/0029/0040/0072/0073）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 实现机制 |
|---|---|---|---|
| ASR-0006 | 静默卸载 | 应用可卸载白名单 | `dpm.setUninstallBlocked` 白名单模式：白名单包不可卸载，其余非系统包显式解除卸载锁定 |
| ASR-0007 | 静默卸载 | 应用可卸载黑名单 | 同上黑名单模式：黑名单包不可卸载，其余包不动 |
| ASR-0010 | 静默卸载 | 应用可安装黑名单 | `InstallWhitelistManager` 反向模式：安装命中黑名单正则的包立即自动卸载 |
| ASR-0015 | 应用运行 | 获取/设置 是否保活 | Doze/App Standby 白名单开关（`cmd deviceidle whitelist +/-<pkg>`）+ 标志持久化 |
| ASR-0029 | 关闭进程和应用 | 检测应用是否存活 | `ActivityManager.getRunningAppProcesses` 进程 pkgList 匹配（QueryRunningApps 引擎） |
| ASR-0040 | 权限 | 免交互授予/取消指定应用 MANAGE_EXTERNAL_STORAGE | 平台签名反射 `AppOpsManager.setMode` 置 OP `android:manage_external_storage` ALLOWED/IGNORED |
| ASR-0072 | 桌面图标 | 隐藏指定应用的桌面图标 | device owner `dpm.setApplicationHidden`（受第三方桌面渲染限制，如实文档化） |
| ASR-0073 | 获取指定应用包名的 PackageInfo | 获取指定应用包名的 PackageInfo | 公开 `PackageManager.getPackageInfo` 独立命令 |

**归属**：ASR-0006/0007/0010/0015/0029/0073 为 Launcher（MDM）；ASR-0040/0072 为 Launcher（MDM）+ 系统 API（平台签名/device owner 能力）。无 ROM 侧代码改动。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署。

## 2. 技术选型与可行性核验

### 2.1 ASR-0006/0007 卸载白/黑名单

- 引擎 `UninstallPolicyManager`：两份名单（whitelist/blacklist）独立持久化于 Launcher SharedPreferences `uninstall_policy`（StringSet），设置命令为**整体替换语义**（与通知/蓝牙/WLAN 名单形制一致），应用差异后逐包 `isUninstallBlocked` 读回核对；
- 全量套用仅遍历**非系统包**（FLAG_SYSTEM 跳过——系统包本就不可卸载，解除锁定无意义），Launcher 自身永远锁定（跳过不解除，保护 device owner）；
- 白名单模式下名单外非系统包**显式解除锁定**（防止历史策略残留）；黑名单模式只触碰名单内包，名单外保持现状；
- 卸载锁定状态由框架持久化（device_policies.xml），重启保持；名单持久化于 Launcher，`PackageChangedReceiver` 对新装/替换应用自动套用（防"先装后控"空窗）；
- 依赖：`dpm.setUninstallBlocked`/`isUninstallBlocked` 为 device owner 公开接口，无需 uses-policy 声明、无需重启 framework。

### 2.2 ASR-0010 安装黑名单（InstallWhitelistManager 反向模式）

- 既有 `InstallWhitelistManager` 为 ASR-0009 白名单语义（命中→锁定卸载+锁任务；未命中→自动卸载）；本批次扩展为**模式化**：`install_policy`（SharedPreferences）持久化 mode（0=off / 1=whitelist / 2=blacklist）与 blacklist 正则集合；
- 黑名单模式（反向）：新装包命中黑名单正则 → `setUninstallBlocked(false)` + 移除锁任务 + 静默卸载；未命中 → 显式解除卸载锁定并移除锁任务（清掉白名单残留保护）；
- `App.onCreate` 启动注册改走 `syncPolicy()`（按持久化模式选择注册内容，白名单默认正则 `DEFAULT_WHITELIST_REGEX` 与历史一致）；`ApiService.onCreate`/`BootCompletedReceiver` 同步重新武装；
- 卸载通道复用 `ApplicationHelper.silentUninstallApplication`（PackageInstaller.uninstall，与 ASR-0003 同引擎）。

### 2.3 ASR-0015 保活开关

- Android 无"进程永不杀"公开 API；本 ROM 可达的最接近机制是 **Doze/App Standby 电源省电白名单**（白名单包不受空闲 Doze 限制与 App Standby 后台节流）——与 ASR-0016/0030 共用同一系统清单与写入通道（`cmd deviceidle whitelist +/-<pkg>`，平台签名 uid=1000 允许执行 shell 命令、device owner 授予免交互；查询用公开 `isIgnoringBatteryOptimizations`）；
- 引擎 `KeepAlivePolicyManager`：标志集持久化于 `keep_alive_policy`（StringSet），`SetKeepAliveEnabled` 写标志并增/删白名单，写后以实时 `isIgnoringBatteryOptimizations` 读回核对；`IsKeepAliveEnabled` 返回标志 + 实时状态；`GetKeepAliveList` 全量；
- 进程重启/开机 `syncPolicy` 对全部标志包幂等重新下发；
- **如实文档化的局限**：防用户"最近任务"划杀与厂商 RAM 清理器需 ROM 级自启/防杀机制，本开关覆盖框架省电杀路径。

### 2.4 ASR-0029 检测应用是否存活

- 命令 `IsAppAlive`：`ActivityManager.getRunningAppProcesses()` 全量进程列表按 `pkgList` 匹配（平台签名 uid=1000 返回全部进程，与 ASR-0022 QueryRunningApps 同数据源——同一引擎）；
- 附报：`getPackageImportance`（API 29+，反射调用，本 ROM 存在即附报）、包 installed/enabled 状态（区分"未运行"与"未安装"）、命中进程 pid/processName/importance。

### 2.5 ASR-0040 MANAGE_EXTERNAL_STORAGE 独立授予/取消

- "所有文件访问"由 AppOps `OP_MANAGE_EXTERNAL_STORAGE`（`android:manage_external_storage`）管治；平台签名 uid=1000 经反射 `AppOpsManager.setMode(String, int, String, int)` 按包置 `MODE_ALLOWED`（授予）/`MODE_IGNORED`（取消）——与 ASR-0043/0044 同一引擎（**仅用字符串 op 变体，代码随 ROM 自身 op 表解析，适配本 ROM 重排数值码**）；
- 写后 `unsafeCheckOpNoThrow` 读回核对，另附报目标包 `checkPermission(MANAGE_EXTERNAL_STORAGE)` 状态（权限授予随 op 模式联动）；
- **2026-08-11 真机核验：本 ROM 的 AppOpsService 完全忽略该 op 的 setMode 写入**——三条通道逐一验证：① 平台应用反射 setMode（MANAGE_APP_OPS_MODES）：写入后读回短暂 MODE_ALLOWED，随后框架以 "MANAGE_EXTERNAL_STORAGE changed" 杀死目标进程并把模式复位为默认；② shell `cmd appops set <pkg> MANAGE_EXTERNAL_STORAGE allow`：静默忽略（`cmd appops get` 恒 "No operations. Default mode: default"）；③ root 同 shell 通道：同样忽略；对照 op（POST_NOTIFICATION）同通道写入持久生效，排除通道本身问题；本 ROM 该 op 的授予仅 Settings 用户确认界面（ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION）可行——免交互授予无法实现；
- **按用户决策**：命令保留、写入失败如实返回 success=false + 原因（mode 恒 default），ASR-0040 维持部分完成，待 ROM 适配后验证；引擎逻辑对适配后的 ROM 无需改动；
- AppOps 状态由框架持久化（appops.xml），重启保持，无需重新武装；依赖 MANAGE_APP_OPS_MODES 签名权限（manifest 既有声明）。

### 2.6 ASR-0072 隐藏桌面图标

- 机制为 device owner `dpm.setApplicationHidden`（隐藏应用从桌面/应用管理列表消失），写后 `isApplicationHidden` 读回核对；
- **受第三方桌面渲染限制（需求原文）**：本设备桌面由外部 Launcher（com.syriusrobotics.platform.launcher）渲染，遵从隐藏标志的桌面不绘制图标；不遵从的桌面仍可能显示——命令如实附注 `note`，验收口径以 DPM 状态为准；
- 与既有 `SetApplicationHidden`（ASR-0099/0104/0131）共用 `ApplicationHelper`，独立命令命名（Set/IsDesktopIconHidden）对齐需求语义。

### 2.7 ASR-0073 PackageInfo 独立命令

- 公开 `PackageManager.getPackageInfo(packageName, GET_PERMISSIONS|GET_SIGNATURES|GET_ACTIVITIES|GET_RECEIVERS|GET_SERVICES|GET_PROVIDERS)`，返回：包名/versionName/versionCode/首次安装与更新时间/uid/flags/targetSdk/enabled/系统包标志/label/requestedPermissions/签名 SHA-1 摘要/activity 清单/launchActivity；
- 包不存在返回 `package not installed`；平台签名 uid=1000 可见全部已装包。

## 3. 命令接口定义（SystemApiInterface.onEvent）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetUninstallWhitelist` | packageNames（String 数组，整体替换） | Map：{success, mode=whitelist, blocked[], unblocked[], failed[], skipped[]} | ASR-0006 |
| `GetUninstallWhitelist` | 无 | Map：{success, mode, list[{packageName, blocked, installed}]} | ASR-0006 |
| `SetUninstallBlacklist` | packageNames（String 数组，整体替换） | Map：{success, mode=blacklist, blocked[], unblocked[], failed[], skipped[]} | ASR-0007 |
| `GetUninstallBlacklist` | 无 | Map：{success, mode, list[{packageName, blocked, installed}]} | ASR-0007 |
| `SetInstallPolicyMode` | mode（0=off/1=whitelist/2=blacklist） | Boolean 或 "missing parameter" | ASR-0010 |
| `GetInstallPolicyMode` | 无 | Map：{mode, modeName, blacklist[]} | ASR-0010 |
| `SetInstallBlacklist` | patterns（正则数组，整体替换） | Boolean | ASR-0010 |
| `GetInstallBlacklist` | 无 | List<String>（正则列表） | ASR-0010 |
| `SetKeepAliveEnabled` | packageName, enabled | Map：{success, packageName, enabled, changed, liveIgnoring, note?} | ASR-0015 |
| `IsKeepAliveEnabled` | packageName | Map：{success, packageName, enabled, liveIgnoring} | ASR-0015 |
| `GetKeepAliveList` | 无 | Map：{success, list[{packageName, enabled, liveIgnoring}]} | ASR-0015 |
| `IsAppAlive` | packageName | Map：{packageName, installed, enabled, alive, importance, importanceText, pid, processName, packageImportance} 或 {error} | ASR-0029 |
| `SetManageExternalStorageGranted` | packageName, granted | Map：{success, packageName, granted, mode, permissionGranted} 或 "missing parameter" | ASR-0040 |
| `IsManageExternalStorageGranted` | packageName | Map：{success, packageName, granted, mode, permissionGranted} | ASR-0040 |
| `SetDesktopIconHidden` | packageName, hidden | Map：{success, packageName, hidden, readBack, note} | ASR-0072 |
| `IsDesktopIconHidden` | packageName | Map：{success, packageName, hidden} | ASR-0072 |
| `GetPackageInfo` | packageName | Map：{success, packageName, versionName, versionCode, firstInstallTime, lastUpdateTime, uid, flags, targetSdkVersion, enabled, system, label, requestedPermissions[], signatureSha1[], activities[], launchActivity} 或 {error} | ASR-0073 |

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> p = new HashMap<>();
p.put("packageNames", Arrays.asList("com.example.app", "com.hmdm.testapp"));
Map result = api.onEvent("SetUninstallWhitelist", p);
// {"RESULT":{"blocked":[...],"success":true,"mode":"whitelist",...}}

Map result2 = api.onEvent("IsAppAlive", singletonMap("packageName", "com.hmdm.testapp"));
// {"RESULT":{"alive":true,"installed":true,"pid":1234,"importance":100,...}}
```

**广播通道**（testapp IPC，与 UI 按钮等效）：

```bash
./send_test_command.sh SetUninstallWhitelist packageNames='["com.example.app","com.hmdm.testapp"]'
./send_test_command.sh IsAppAlive packageName=com.hmdm.testapp
./send_test_command.sh SetKeepAliveEnabled packageName=com.hmdm.testapp enabled=true
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/
│   ├── UninstallPolicyManager.java        # 新增：ASR-0006/0007 卸载白/黑名单引擎（持久化+全量套用+读回核对）
│   ├── KeepAlivePolicyManager.java        # 新增：ASR-0015 保活开关引擎（deviceidle 白名单 + syncPolicy）
│   ├── InstallWhitelistManager.java       # 扩展：模式化（0/1/2）+ 黑名单反向模式 + syncPolicy
│   ├── AppOpsPolicyManager.java           # 扩展：ASR-0040 setManageExternalStorageGranted/isManageExternalStorageGranted
│   └── ApplicationHelper.java             # 既有：setUninstallBlocked/setApplicationHidden/silentUninstallApplication 复用
├── service/command/
│   ├── uninstall_policy/  SetUninstallWhitelist/GetUninstallWhitelist/SetUninstallBlacklist/GetUninstallBlacklist.java   # 新增
│   ├── install_policy/    SetInstallPolicyMode/GetInstallPolicyMode/SetInstallBlacklist/GetInstallBlacklist.java        # 新增
│   ├── keep_alive/        SetKeepAliveEnabled/IsKeepAliveEnabled/GetKeepAliveList.java                                  # 新增
│   ├── query/IsAppAlive.java             # 新增：ASR-0029
│   ├── appops/SetManageExternalStorageGranted/IsManageExternalStorageGranted.java    # 新增：ASR-0040
│   ├── desktop/SetDesktopIconHidden/IsDesktopIconHidden.java                         # 新增：ASR-0072
│   └── infoquery/GetPackageInfo.java     # 新增：ASR-0073
├── service/ApiBinder.java                # 注册 17 个新命令
├── service/ApiService.java               # onCreate：InstallWhitelistManager/KeepAlivePolicyManager syncPolicy
├── broadcast/BootCompletedReceiver.java  # 开机同步同上
└── broadcast/PackageChangedReceiver.java # 新增 UninstallPolicyManager.applyToPackage

app/src/main/java/com/hmdm/launcher/App.java   # 启动注册改走 InstallWhitelistManager.syncPolicy()

testapp/
├── src/main/java/com/hmdm/testapp/
│   ├── AppPolicyTestActivity.java        # 新增测试页（含包名/正则输入与全部按钮）
│   ├── TestActions.java                  # 新增 17 个事件（含参数校验）与事件目录
│   ├── MainActivity.java                 # 新增入口按钮
│   └── res/layout/activity_app_policy_test.xml  # 新增布局
```

## 5. 执行逻辑

```
SetUninstallWhitelist(packageNames):
  1. 参数清洗（去空/去重），持久化 whitelist
  2. 枚举已装应用（跳过 FLAG_SYSTEM 系统包与 Launcher 自身）
  3. 逐包：目标=名单包含（或 Launcher 自身）→ setUninstallBlocked(true)，否则 setUninstallBlocked(false)；跳过状态已一致的包
  4. 返回 blocked/unblocked/failed/skipped 明细

SetInstallPolicyMode(mode):
  1. 校验 mode ∈ {0,1,2}，持久化
  2. syncPolicy() 按新 mode 重新注册安装广播接收器（黑名单模式用 blacklist 正则，白名单模式用默认正则）

SetKeepAliveEnabled(packageName, enabled):
  1. 校验包名合法且已安装
  2. 更新持久化标志集
  3. cmd deviceidle whitelist +/-<pkg>（包名经 [a-zA-Z0-9_.]+ 白名单校验，防 shell 注入）
  4. isIgnoringBatteryOptimizations 读回核对 → success

IsAppAlive(packageName):
  1. 枚举 getRunningAppProcesses()，pkgList 命中 → alive=true + pid/processName/importance
  2. 反射 getPackageImportance 附报；包 installed/enabled 附报

SetManageExternalStorageGranted(packageName, granted):
  1. 反射 AppOpsManager.setMode("android:manage_external_storage", uid, pkg, ALLOWED/IGNORED)
  2. unsafeCheckOpNoThrow 读回核对 + checkPermission 附报
```

**安全设计**：包名/正则参数经字符集白名单或仅作为 PackageManager/Preference 输入；`deviceidle whitelist` shell 命令仅拼接白名单校验后的包名（同 ASR-0030 引擎模式），无注入面；无明文凭据参数。

## 6. 权限与归属

- 卸载锁定/隐藏/安装白名单：device owner 公开 DPM 接口（`setUninstallBlocked`/`setApplicationHidden`），无需 uses-policy 声明；
- ASR-0040：`MANAGE_APP_OPS_MODES`（signature，manifest 既有声明，平台签名自动授予）；
- ASR-0015：shell 命令通道（uid=1000 允许执行）+ 公开 PowerManager 查询，无需新权限；
- ASR-0073：公开 PackageManager API，无需新权限；
- 无 `device_admin.xml` 变更、无 AIDL/lib 模块变更、无需重启 framework。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 卸载名单含未安装包 | 正常持久化；全量套用阶段未安装包自然跳过；查询回显 installed=false |
| 名单含 Launcher/testapp 等保护包 | Launcher 自身永不解除锁定（skipped）；testapp 可正常入名单（正/反向均按名单执行） |
| 卸载名单整体替换空名单 | 白名单模式空名单=全部非系统包解除锁定（恢复默认）；黑名单模式空名单=不动任何包 |
| 安装黑名单模式切换回白名单 | 重新注册白名单接收器（默认正则），黑名单正则保留可查询 |
| 保活目标包未安装/非法包名 | error 如实返回，不执行 shell 命令 |
| MANAGE_EXTERNAL_STORAGE 目标包不存在 | setAppOpMode 返回 false（uid 解析失败），命令 success=false + 附报 |
| IsAppAlive 包未安装 | installed=false、alive=false（不 crash） |
| 隐藏桌面图标后 Launcher 自身 | 引擎不拦截（SetDesktopIconHidden 对任意包有效，但隐藏 Launcher 自身会中断管控——文档提示谨慎使用；DPM 层允许） |
| 进程重启/开机 | 名单/标志持久化经 ApiService.onCreate / BootCompletedReceiver 重新武装（install policy、keep-alive、卸载名单由 PackageChangedReceiver 对新装包套用） |

## 8. 真机验证记录

（2026-08-11 批次，Android 13 / API 33 userdebug，平台签名 + device owner；命令级用例结果见测试用例设计文档，此处记录机制核验关键结论）

| 核验项 | 结果 |
|---|---|
| 卸载白名单生效 | 通过：名单内包卸载被拒（DELETE_FAILED_OWNER_BLOCKED），清空名单恢复；系统包（FLAG_SYSTEM）自动跳过；PackageChangedReceiver 新装套用 |
| 卸载黑名单生效 | 通过：仅名单内包锁定、清空恢复；读回与 dumpsys 一致 |
| 安装黑名单自动卸载 | 通过：模式 2 + 正则 `com\\.example\\..*`，安装 stub1（aapt2/d8 自建测试 APK）后接收器命中（mode:2 matched:true）并静默卸载（pm list 无残留）；未命中包（testapp）保持；模式/名单进程重启与整机重启后保持 |
| 保活白名单读写 | 通过：开启后 liveIgnoring=true + `cmd deviceidle whitelist` 对照；关闭移除；非法包名/未安装包拒绝；整机重启后 syncPolicy 重新下发保持 |
| 应用存活检测 | 通过：运行中包 alive=true + pid/importance；未安装 installed=false；缺参错误路径 |
| MANAGE_EXTERNAL_STORAGE AppOps | **本 ROM 忽略该 op 写入**（三通道核验，见 2.5 节），命令如实 success=false；维持部分完成 |
| 桌面图标隐藏 | 通过：隐藏/恢复 + dumpsys hidden 位对照 + 读回核对（第三方桌面渲染说明随命令附注） |
| PackageInfo 命令 | 通过：完整字段（版本/uid/flags/权限/签名 SHA-1 与平台密钥指纹一致/launchActivity）；未安装包错误路径 |
| 测试后设备恢复 | 通过（名单清空、模式恢复 1、保活关闭、图标恢复、设备重启后基线完好） |
