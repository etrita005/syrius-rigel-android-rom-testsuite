# 静默安装/卸载管控（ASR-0001/0002/0004/0005/0008/0009/0011）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 底层机制 |
|---|---|---|---|
| ASR-0001 | 静默安装 | 免交互静默安装 APK（无安装界面、无确认弹窗） | `PackageInstaller` 会话安装（`InstallUtils.silentInstallApplication`） |
| ASR-0002 | 静默降级安装 | 支持比已装版本更低的 APK 静默安装 | `SessionParams.setRequestDowngrade(true)` 反射 |
| ASR-0004 | 保留数据卸载 | 卸载应用但保留其数据（/data/data/<pkg>） | 反射 `PackageManager.deletePackage(pkg, observer, DELETE_KEEP_DATA)`（2026-08-13 本批次补实现） |
| ASR-0005 | 设置是否禁止卸载 | 设置/查询单个应用是否禁止卸载 | device owner `dpm.setUninstallBlocked` |
| ASR-0008 | 设置是否禁止安装 | 禁止/允许本设备安装任何应用（对所有用户） | device owner `DISALLOW_INSTALL_APPS` 用户限制（2026-08-13 本批次补命令注册） |
| ASR-0009 | 应用可安装白名单 | 白名单内应用安装后受保护（不可卸载+锁任务）；非白名单新装应用自动静默卸载 | `InstallWhitelistManager` 正则白名单接收器 |
| ASR-0011 | 禁止未知来源应用安装 | 获取/设置是否禁止未知来源应用安装 | device owner `DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY` 用户限制 |

**归属**：「Launcher（MDM）」/「Launcher（MDM）+ 系统 API」（ASR-0002/0004 依赖平台签名能力的 PackageInstaller/隐藏 API）。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署；bootloader 锁定。

## 2. 技术选型与可行性核验

### 2.1 静默安装（ASR-0001/0002）

| 方案 | 说明 | 结论 |
|---|---|---|
| `PackageInstaller` 会话安装 | 平台签名应用（uid=1000）创建安装会话、写入 APK 流、`commit` 免交互静默安装；XAPK 走 `XapkUtils` 解包安装 | **采用** |
| `pm install` exec | shell 命令通道；依赖 exec 环境且本 ROM 应用域 exec 行为不可靠（本批次 ASR-0004 实测 `pm uninstall -k` 从应用域执行经常不生效） | 放弃（安装路径） |

- 降级支持：反射 `SessionParams.setRequestDowngrade(true)`（ASR-0002），会话参数在提交前设置，PMS 放行低于已装 versionCode 的安装；业务层高版本须先 Remove（既有语义）。
- 写后核验：安装为异步会话，`SessionCallback.onFinished(success)` 回调 + 提交后轮询 `PackageManager.getPackageInfo` 包存在与版本号核对（本批次 testapp 侧 `IsPackageInstalledLocal` 探针补充独立对照；**探针依赖 `QUERY_ALL_PACKAGES`——Android 11+ 包可见性过滤下无该权限的普通应用对用户安装包 getPackageInfo 抛 NameNotFoundException，2026-08-13 真机核验并已在 testapp manifest 补充声明**）。

### 2.2 静默卸载（ASR-0004 与默认卸载）

- 默认卸载（数据删除）：`PackageInstaller.uninstall(pkg, sender)` 异步卸载，**实测删除 /data/data/<pkg> 数据目录**（2026-08-13 探针验证：数据文件与目录一并消失）——即"不保留数据"语义（ASR-0003）。
- **ASR-0004 保留数据卸载**：`PackageInstaller.uninstall` 无 keep-data 标志（卸载即删数据，与 Sheet1 早期说明"不传 keepData 标志即保留数据"**不符**，2026-08-13 真机核验后按用户确认补实现）：
  - 候选 A：`pm uninstall -k` exec——本 ROM 实测从应用域（system_app SELinux 域）spawn 的 pm shell 脚本卸载**时灵时不灵**（多数情况 15~40 秒内包仍存在、输出为空、exit=255），不可靠；
  - 候选 B：反射隐藏 `PackageManager.deletePackage(String, IPackageDeleteObserver, int)` 带 `DELETE_KEEP_DATA=0x1`——直接 binder 调用 PMS，**采用**；
  - **本 ROM 特殊性**：PMS 不回调 `IPackageDeleteObserver.packageDeleted`（30 秒实测无回调，与既有 `deleteApplicationCacheFiles` 同款 ROM 缺陷，见 ASR-0127 文档）——引擎以读回兜底：回调等待 30s 后轮询 `getPackageInfo` 直至包消失（最长 30s，实测该 ROM 从应用域发起的删除约 30s 完成）。
  - 卸载前预清理：解 `setUninstallBlocked` + 移除锁任务成员（两者都会导致卸载被拒，本 ROM 实测 lock task 成员直接导致 `pm uninstall` 返回 DELETE_FAILED_OWNER_BLOCKED）。

### 2.3 禁止卸载（ASR-0005）

device owner 公开接口 `dpm.setUninstallBlocked(admin, pkg, blocked)`，框架持久化于 device_policies.xml；查询 `dpm.isUninstallBlocked`。

- **2026-08-13 本批次修正**：`SetUninstallBlocked` 命令原实现把参数 `canUninstall` 直接透传为 `uninstallBlocked`（语义反转——canUninstall=true 反而禁止卸载，真机实测 `pm uninstall` 被拒），已改为 `!canUninstall` 后传入，并修正命令日志占位符 bug（原打印字面量 "packageName"）。
- 交互说明：白名单模式下受保护包同时处于 uninstall-blocked + lock task 状态，`Uninstall` 命令（含 keepData 路径）卸载前自动解两者（`ApplicationHelper.silentUninstallApplication` 既有行为）。

### 2.4 禁止安装（ASR-0008）

device owner `DISALLOW_INSTALL_APPS` 用户限制（`MdmUtils.lockUserRestrictions` 引擎，与服务器 config restrictions 下发通道同源）。**2026-08-13 本批次补命令注册**（`Set/IsDisallowInstallApps`，遵循 ASR-0273"补齐命令注册"先例）：限制生效时 PMS 拒绝创建安装会话（`SecurityException: User restriction prevents installing`，含 adb shell 安装，真机实测）。

### 2.5 安装白名单（ASR-0009）

`InstallWhitelistManager`（正则列表 + `ACTION_PACKAGE_ADDED` 接收器）：
- 模式 1（白名单，出厂默认）：命中正则的新装包 → `setUninstallBlocked(true)` + 加入锁任务（受保护）；未命中 → 解除卸载锁定 + 锁任务移除 + 立即静默卸载；
- 默认白名单正则：`com\.syriusrobotics\..*`、`org\.fcitx\..*`、`com\.cookiegames\..*`、`com\.example\.stub1~5`、`com\.hmdm\..*`；
- 模式与正则持久化 Launcher SharedPreferences `install_policy`（mode/blacklist），`syncPolicy` 进程重启/开机重新注册接收器。

**2026-08-13 本批次修正**：`syncPolicy` 原实现把模式 0（off）落入白名单分支（mode!=2 即注册白名单接收器，"off"名不副实，真机实测模式 0 下非白名单包仍被自动卸载）——已修正为模式 0 注销接收器、不执行任何管控。

### 2.6 禁止未知来源安装（ASR-0011）

device owner `DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY` 用户限制（`ApplicationHelper.setDisallowInstallUnknownSources`）；查询 `dpm.getUserRestrictions` 读回。真机核验：该限制拦截的是普通应用/设置界面通道的未知来源安装流程，**adb shell 安装不受限**（shell 通道豁免，与 AOSP 语义一致），测试用例按此口径设计。

## 3. 命令接口定义

### 3.1 既有命令（本批次纳入需求验收）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `Install` | apkFilePath（String，必填）、packageName（String，必填） | Boolean（提交会话成功与否） | ASR-0001/0002 |
| `Uninstall` | packageName（String，必填）、keepData（Boolean，可选，默认 false） | Boolean | ASR-0004 |
| `SetUninstallBlocked` | packageName（String，必填）、canUninstall（Boolean，必填） | Boolean（`!canUninstall` 语义，2026-08-13 修正） | ASR-0005 |
| `IsUninstallBlocked` | packageName（String，必填） | Boolean | ASR-0005 |
| `SetDisallowInstallUnknownSource` | disallow（Boolean，必填） | Boolean | ASR-0011 |
| `IsDisallowInstallUnknownSource` | 无 | Boolean | ASR-0011 |
| `SetInstallPolicyMode` | mode（int，0=off/1=白名单/2=黑名单） | Boolean（持久化+重注册结果） | ASR-0009 |
| `GetInstallPolicyMode` | 无 | {mode, modeName, blacklist} | ASR-0009 |

### 3.2 本批次新增命令（2 条）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetDisallowInstallApps` | disallow（Boolean，必填） | Boolean（限制写入结果） | ASR-0008 |
| `IsDisallowInstallApps` | 无 | Boolean（DISALLOW_INSTALL_APPS 当前状态） | ASR-0008 |

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> p = new HashMap<>();
p.put("apkFilePath", "/sdcard/MDM/testapp.apk");
p.put("packageName", "com.hmdm.testapp");
Map result = api.onEvent("Install", p);
// {"RESULT":true}

Map<String, Object> p2 = new HashMap<>();
p2.put("packageName", "com.mdmprobe.test");
p2.put("keepData", true);
Map result2 = api.onEvent("Uninstall", p2);
// {"RESULT":true}（包消失且 /data/data 保留）

Map<String, Object> p3 = new HashMap<>();
p3.put("disallow", true);
Map result3 = api.onEvent("SetDisallowInstallApps", p3);
// {"RESULT":true}
```

**广播通道**（TestBroadcast，与现有命令一致）：`adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast -a ON_SQA_INTERFACE_TEST --es event <命令> --es param '<json>'`。

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/
├── util/InstallUtils.java                        # silentInstallApplication（PackageInstaller+降级）
│                                                 # silentUninstallApplication（PackageInstaller.uninstall）
│                                                 # silentUninstallApplicationKeepData（新增：隐藏
│                                                 #   deletePackage+DELETE_KEEP_DATA，读回兜底）
├── syrius/utils/ApplicationHelper.java           # setUninstallBlocked/isUninstallBlocked/
│                                                 # setDisallowInstallUnknownSources/
│                                                 # silentUninstallApplication（解 block+锁任务）
│                                                 # silentUninstallApplicationKeepData（新增）
├── syrius/utils/InstallWhitelistManager.java     # ASR-0009 白名单/黑名单接收器（本批次修正模式 0=off）
├── syrius/utils/MdmUtils.java                    # lockUserRestrictions/releaseUserRestrictions（0008 引擎）
├── syrius/service/command/install/
│   ├── Install.java / Uninstall.java             # Uninstall 本批次增加 keepData 参数
│   ├── SetUninstallBlocked.java                  # 本批次修正 canUninstall 语义反转
│   ├── IsUninstallBlocked.java
│   ├── SetDisallowInstallApps.java               # 新增（ASR-0008）
│   └── IsDisallowInstallApps.java                # 新增（ASR-0008）
├── syrius/service/command/disallow_install_unknown_source/
│   └── Set/IsDisallowInstallUnknownSource.java
├── syrius/service/command/install_policy/        # Set/GetInstallPolicyMode、Set/GetInstallBlacklist
├── syrius/service/ApiBinder.java                 # 注册 2 条新命令
└── src/main/java/android/content/pm/IPackageDeleteObserver.java  # 新增：隐藏接口本地桩（沿用
                                                                #   IPackageDataObserver 先例）

testapp/
└── src/main/java/com/hmdm/testapp/
    ├── TestActions.java                          # Install/Uninstall/Set|IsUninstallBlocked/
    │                                             # Set|IsDisallowInstallUnknownSource/
    │                                             # Set|IsDisallowInstallApps/IsPackageInstalledLocal 事件
    ├── InstallProbeVerifier.java                 # 新增：本地包存在性探针（QUERY_ALL_PACKAGES）
    ├── InstallUninstallTestActivity.java         # 新增测试页
    └── src/main/res/layout/activity_install_uninstall_test.xml
    AndroidManifest.xml                           # 新增 QUERY_ALL_PACKAGES + 新 Activity
```

## 5. 执行逻辑

```
Install(apkFilePath, packageName):
  1. 参数校验（缺 apkFilePath/packageName → {error}，testapp 侧拦截）
  2. FileInputStream 读取 APK，PackageInstaller.createSession(MODE_FULL_INSTALL)
  3. 反射 setRequestDowngrade(true)（ASR-0002）
  4. 流式写入 session → fsync → commit(IntentSender)
  5. SessionCallback.onFinished 记录结果；命令返回提交是否成功
  6. testapp 侧 IsPackageInstalledLocal 探针核对包已安装/版本

Uninstall(packageName, keepData=false):
  1. 解 setUninstallBlocked + 移除锁任务（防 OWNER_BLOCKED）
  2. keepData=false → PackageInstaller.uninstall（数据删除）
     keepData=true → 反射 deletePackage(pkg, observer, DELETE_KEEP_DATA)：
       a. 回调等待 30s（本 ROM 不回调，实测特性）
       b. 读回兜底：轮询 getPackageInfo 最长 30s，包消失 → success=true
       c. 期间包仍可见 → success=false + 日志
  3. 返回 Boolean

SetUninstallBlocked(packageName, canUninstall):
  1. 参数校验（缺参 → testapp 侧拦截）
  2. dpm.setUninstallBlocked(admin, pkg, !canUninstall)（本批次修正反转）
  3. 返回调用结果；IsUninstallBlocked 读回核对

SetDisallowInstallApps(disallow):
  1. disallow=true → MdmUtils.lockUserRestrictions(DISALLOW_INSTALL_APPS)
     disallow=false → releaseUserRestrictions(DISALLOW_INSTALL_APPS)
  2. 返回限制写入结果；IsDisallowInstallApps 读回核对

IsDisallowInstallApps():
  读 dpm.getUserRestrictions(admin) 的 DISALLOW_INSTALL_APPS 位

InstallWhitelistManager（模式 1）:
  ACTION_PACKAGE_ADDED → 正则匹配：
    命中 → setUninstallBlocked(true) + addLockTaskPackage（受保护）
    未命中 → setUninstallBlocked(false) + removeLockTaskPackage + silentUninstallApplication
  （本批次修正：模式 0 不注册接收器；模式 2 为黑名单反向逻辑，详见
   AppInstallUninstallPolicyControl 文档）
```

**安全设计**：命令参数经 ApiBinder 调用方白名单（平台签名/系统/shell）校验；keepData 路径仅反射固定签名方法与常量，无外部字符串注入面。

## 6. 权限与归属

- ASR-0001/0002/0004：平台签名 uid=1000 使用 `PackageInstaller`/隐藏 `PackageManager.deletePackage`（DELETE_PACKAGES 签名权限自动授予）；
- ASR-0005/0008/0011：device owner 公开 DPM 接口/用户限制；
- ASR-0009：Launcher 进程内接收器 + dpm 接口；
- testapp：新增 `QUERY_ALL_PACKAGES`（normal 权限，包可见性探针需要；真机核验无该权限时对用户安装包 getPackageInfo 抛 NameNotFoundException）。
- 无 ROM 侧代码改动，无 root 依赖。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺参（apkFilePath/packageName/canUninstall/disallow） | testapp 侧返回 missing parameter 提示，不 crash |
| APK 文件不存在/损坏 | PackageInstaller 会话失败，onInstallError 回调，命令如实返回 false |
| 目标包不存在（Install/Uninstall） | install 提交失败回调 / uninstall 异常捕获返回 false（PMS 语义） |
| keepData 卸载回调不触发 | 本 ROM PMS 不回调 deletePackage 观察者（与 deleteApplicationCacheFiles 同缺陷）——读回轮询兜底判定，文档记录 |
| 卸载被锁任务/卸载锁定拒绝 | Uninstall 路径先解 setUninstallBlocked + 移除锁任务再卸载；直接 pm uninstall 仍会 OWNER_BLOCKED（预期，属 0005/0009 管控效果） |
| 白名单接收器与卸载竞态 | 接收器处理为异步广播，重装 Launcher 后延迟补投递；文档建议测试间隔 3s+ 再核对（真机实测） |
| 模式 0 语义 | 本批次修正：0=off 不注册接收器；此前版本 mode!=2 均按白名单执行（真机实测并修正） |
| 包可见性 | testapp 探针需 QUERY_ALL_PACKAGES（Android 11+ 过滤）；未声明时对用户安装包误报未安装（真机核验） |
| adb shell 安装 | 不受 DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY 限制（shell 豁免，AOSP 语义）；受 DISALLOW_INSTALL_APPS 限制（SecurityException，真机实测） |
| 进程重启/开机 | InstallWhitelistManager.syncPolicy 按持久化模式重新注册；DPM 限制/卸载锁定/锁任务由框架持久化（device_policies.xml）自动保持 |

## 8. 真机验证记录（2026-08-13，Android 13 / API 33 userdebug，平台签名 + device owner）

| 核验项 | 结果 |
|---|---|
| ASR-0001 静默安装 | `Install apkFilePath=/sdcard/MDM/testapp.apk packageName=com.hmdm.testapp` → RESULT true，探针确认包存在（testapp 自安装升级）；Launcher 日志 `install success: true` |
| ASR-0002 静默降级安装 | testapp 构建 versionCode=2 安装后，Install 命令装回 versionCode=1 的 APK → 成功，探针 versionCode=1（setRequestDowngrade 生效） |
| ASR-0004 保留数据卸载 | 探针包写入 /data/data/com.hmdm.probe/probe.txt 后 `Uninstall keepData=true` → RESULT true（最终版），包消失、数据目录与文件保留；**早期版本（PackageInstaller.uninstall）实测删除数据目录**，与 Sheet1 旧说明不符，按用户确认补实现 |
| ASR-0005 禁止卸载 | `SetUninstallBlocked canUninstall=false` → IsUninstallBlocked=true、`pm uninstall` 返回 DELETE_FAILED_OWNER_BLOCKED；`canUninstall=true` → IsUninstallBlocked=false、pm uninstall 成功；**修正前命令语义反转（canUninstall=true 反而锁定）已修复** |
| ASR-0008 禁止安装 | `SetDisallowInstallApps disallow=true` → Is=true，adb install 报 `SecurityException: User restriction prevents installing`；disallow=false → 安装恢复（本批次补命令注册） |
| ASR-0009 安装白名单 | 模式 1：白名单包 com.hmdm.probe 安装后保留且 IsUninstallBlocked=true（device_policies.xml lock-task-component 含该包）；非白名单 com.mdmprobe.test 安装后数秒内被自动静默卸载（Launcher 日志 `not matched silentUninstallApplication`，探针确认消失） |
| ASR-0011 未知来源 | set/查询往返通过；adb shell 安装不受限（shell 豁免，如实记录） |
| 模式 0 修正 | 修正前模式 0 下非白名单包仍被自动卸载（syncPolicy 落入白名单分支）；修正后模式 0 无管控（探针包保留），模式 1/2 行为不变 |
| testapp 基建 | MdmApiClient 增加断线自动重绑（Launcher 重装后原实现永久断开、命令排队丢失返回值，真机复现）；QUERY_ALL_PACKAGES 声明修复包可见性探针 |
| 测试后恢复 | testapp 恢复 versionCode=1；安装策略恢复模式 1（白名单，会话基线）；探针包全部卸载；DO 在位（`dpm list-owners` = DeviceOwner,Affiliated） |
