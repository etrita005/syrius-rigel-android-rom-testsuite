# 应用可见性与锁任务管控（ASR-0018/0024/0025/0347/0348）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 底层机制 |
|---|---|---|---|
| ASR-0018 | 隐藏并禁止应用 | 隐藏指定应用（桌面/应用列表不可见）并禁止其运行 | device owner `dpm.setApplicationHidden`（隐藏）+ `dpm.setPackagesSuspended`（挂起禁止运行，同引擎见 AppRunControl 文档） |
| ASR-0024 | 隐藏应用白名单 | 按白名单批量隐藏应用 | 同 setApplicationHidden 引擎，按包逐一隐藏（白名单语义） |
| ASR-0025 | 固定屏幕显示应用 | 查询/添加可固定屏幕显示（锁任务）的应用 | device owner `setLockTaskPackages` + `setLockTaskFeatures(NONE)`（2026-08-13 本批次补命令注册） |
| ASR-0347 | 禁用 HOME 键 | 锁任务激活期间 HOME 键失效 | 锁任务机制（系统层，LOCK_TASK_FEATURE_NONE） |
| ASR-0348 | 禁用 MENU(RECENT/TASK) 键 | 锁任务激活期间最近任务键失效 | 锁任务机制（系统层，LOCK_TASK_FEATURE_NONE） |

**归属**：「Launcher（MDM）」/「Launcher（MDM）+ 系统 API」（锁任务为 DPM 公开能力，ASR-0347/0348 效果由系统锁任务模式提供）。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署；bootloader 锁定。

## 2. 技术选型与可行性核验

### 2.1 隐藏应用（ASR-0018/0024）

| 方案 | 说明 | 结论 |
|---|---|---|
| device owner `dpm.setApplicationHidden` | 公开接口，按包隐藏/恢复；隐藏后应用从桌面与应用列表消失、`isApplicationHidden` 读回核对；框架持久化（PMS package-restrictions.xml，重启保持） | **采用** |
| 禁用组件（setComponentEnabledSetting） | 整应用禁用仅对组件有效且影响面不同；隐藏语义（从列表消失）由 setApplicationHidden 提供 | 补充（不替代） |

- ASR-0018「并禁止」：挂起通道——`dpm.setPackagesSuspended`（挂起后应用无法启动、显示"已暂停"提示），与 ASR-0017 禁止运行白名单共用引擎（AppRunPolicyManager，详见 AppRunControl 设计文档）；本批次文档交叉引用，命令面以 `SetApplicationHidden`/`IsApplicationHidden` 为主（挂起为白名单引擎职责）。
- 写后核对：命令返回真实 DPM 结果 + `IsApplicationHidden` 读回 + `dumpsys package <pkg>` 的 `hidden=` 字段三方对照（真机核验一致）。

### 2.2 锁任务（ASR-0025/0347/0348）

**机制**：device owner `dpm.setLockTaskPackages(admin, packages)` 声明可进入锁任务模式的应用白名单 + `dpm.setLockTaskFeatures(admin, LOCK_TASK_FEATURE_NONE)` 关闭锁任务内全部系统特性；名单内应用调用 `startLockTask()` 后设备进入锁任务模式（Kiosk）——**HOME 键、最近任务（RECENT/MENU）键、全局操作、通知栏在该模式下均失效**（系统层强制，ASR-0347/0348 的实现机制）。

- **候选方案对比**：
  - 锁任务（Kiosk）模式：系统级，HOME/RECENT 一并禁用，AOSP 标准机制；**采用**；
  - StatusBarManager 禁用标志（ASR-0346 引擎）：仅 BACK 键通道，无 HOME/OVERVIEW 标志（本 ROM shell 通道亦无）；
  - 按键拦截（InputManager/无障碍）：无系统级公开 API，放弃。
- **2026-08-13 本批次补命令注册**：锁任务此前仅经 InstallWhitelistManager 自动添加（白名单命中包 addLockTaskPackage）与调试 UI 触发（ApplicationHelper.setLockTask），无 ApiBinder 命令——按 ASR-0273 补命令先例注册 `Set/GetLockTaskPackages`（Set 复用 ApplicationHelper.setLockTask：setLockTaskPackages 整体替换 + setLockTaskFeatures(NONE)）。
- **退出路径**：将应用从锁任务名单移除（`setLockTaskPackages` 不含当前锁定包）→ 系统自动退出锁任务模式（真机核验：清空名单后 `mLockTaskModeState` 由 LOCKED 回 NONE）。
- **测试探针**：纯桩应用 `com.hmdm.lockprobe`（Activity onCreate 调 `startLockTask()`）作为锁任务触发载体；须先加入锁任务名单，否则 startLockTask 抛 SecurityException。
- **真机特性记录**：锁任务激活期间若通知栏处于展开状态则无法收起（features NONE 语义下系统窗口交互受限），退出锁任务后恢复；锁任务期间按 HOME 产生的默认桌面意图在退出后可能触发 HOME 选择器（本机多个 HOME 候选），测试后经 `cmd package set-home-activity com.syriusrobotics.platform.launcher/.MainActivity` 恢复（2026-08-13 实测）。

## 3. 命令接口定义

### 3.1 既有命令（本批次纳入需求验收）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetApplicationHidden` | packageName（String，必填）、hidden（Boolean，必填） | Boolean（真实 DPM 结果） | ASR-0018/0024 |
| `IsApplicationHidden` | packageName（String，必填） | Boolean | ASR-0018/0024 |

### 3.2 本批次新增命令（2 条）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetLockTaskPackages` | packageNames（String 数组，整体替换） | Boolean（setLockTaskPackages+setLockTaskFeatures(NONE) 调用结果） | ASR-0025/0347/0348 |
| `GetLockTaskPackages` | 无 | List\<String\>（当前锁任务名单） | ASR-0025 |

**调用示例**：

```java
Map<String, Object> p = new HashMap<>();
p.put("packageNames", new ArrayList<>(Arrays.asList("com.hmdm.lockprobe")));
Map result = api.onEvent("SetLockTaskPackages", p);
// {"RESULT":true}

Map result2 = api.onEvent("GetLockTaskPackages", null);
// {"RESULT":["com.hmdm.lockprobe"]}
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/
├── syrius/utils/ApplicationHelper.java           # setApplicationHidden/isApplicationHidden
│                                                 # setLockTask（setLockTaskPackages+features NONE）
├── syrius/utils/InstallWhitelistManager.java     # 白名单命中包自动 addLockTaskPackage（既有）
├── syrius/service/command/application_hidden/
│   ├── SetApplicationHidden.java / IsApplicationHidden.java   # 既有
│   ├── SetLockTaskPackages.java                  # 新增（ASR-0025/0347/0348）
│   └── GetLockTaskPackages.java                  # 新增（ASR-0025）
└── syrius/service/ApiBinder.java                 # 注册 2 条新命令

testapp/
└── src/main/java/com/hmdm/testapp/
    ├── TestActions.java                          # Set/GetLockTaskPackages、LaunchLockProbe 事件
    ├── LockTaskProbeLauncher.java                # 新增：锁任务探针启动器
    ├── AppVisibilityTaskControlTestActivity.java # 新增测试页
    └── src/main/res/layout/activity_app_visibility_task_test.xml

探针 APK（测试素材，非交付物）：
/tmp/kilo/lockprobe.apk（com.hmdm.lockprobe，Activity onCreate 调 startLockTask）
```

## 5. 执行逻辑

```
SetApplicationHidden(packageName, hidden):
  1. 参数校验（缺 packageName → {error}，testapp 侧拦截）
  2. dpm.setApplicationHidden(admin, pkg, hidden)（设备未设 DO 或异常 → false）
  3. 返回真实 DPM 结果；IsApplicationHidden 读回核对

SetLockTaskPackages(packageNames):
  1. 参数校验（packageNames 数组，整体替换）
  2. ApplicationHelper.setLockTask：dpm.setLockTaskPackages(admin, array)
     + dpm.setLockTaskFeatures(admin, LOCK_TASK_FEATURE_NONE)
  3. 返回调用结果；GetLockTaskPackages 读回核对

GetLockTaskPackages():
  读 dpm.getLockTaskPackages(admin) 返回数组

锁任务激活（探针侧）:
  com.hmdm.lockprobe Activity.onCreate → startLockTask()
  → 系统进入锁任务模式（mLockTaskModeState=LOCKED）：
    HOME/RECENT/全局操作/通知栏均失效（LOCK_TASK_FEATURE_NONE）
退出：SetLockTaskPackages 名单移除当前包 → 系统自动退出锁任务
```

**安全设计**：锁任务名单整体替换（不会残留误锁）；测试用探针包仅在本批次测试时安装，测试结束即卸载；锁任务激活期间测试流程固定为"验证→立即清名单退出"。

## 6. 权限与归属

- ASR-0018/0024：device owner 公开 `setApplicationHidden`；挂起通道 `setPackagesSuspended` 同为 device owner 公开接口；
- ASR-0025/0347/0348：device owner 公开 `setLockTaskPackages`/`setLockTaskFeatures`；锁任务模式效果由系统提供（无需权限声明）；
- testapp：无新增权限；探针包为普通应用（debug 签名，仅本批次测试载体）；
- 无 ROM 侧代码改动，无 root 依赖。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺参（packageName/hidden/packageNames） | testapp 侧返回 missing parameter 提示，不 crash |
| 隐藏不存在的包 | dpm.setApplicationHidden 返回 false，如实上报 |
| 隐藏系统关键应用 | 由 DPM 判定（部分系统应用可隐藏，真机核验 settings 可隐藏）；保护名单由各策略引擎控制（见 AppRunControl/AppManagement 文档） |
| 探针未安装时启动 | LaunchLockProbe 返回 error: lock task probe not installed |
| startLockTask 时包不在名单 | SecurityException（探针 onCreate 捕获则无法进入锁任务）——测试前必须 SetLockTaskPackages 先加入 |
| 锁任务内通知栏展开 | features NONE 下系统窗口交互受限、无法收起（真机实测）；退出锁任务后恢复——文档记录 |
| 锁任务退出 | 清空/移除当前包的名单 → 系统自动退出（真机实测 LOCKED→NONE）；HOME 默认桌面若丢失（多 HOME 候选）用 `cmd package set-home-activity` 恢复 |
| 进程重启/开机 | 锁任务名单与隐藏状态由框架持久化（device_policies.xml / package-restrictions.xml）自动保持；锁任务模式本身随进程/开机重置为未激活（需应用重新 startLockTask） |

## 8. 真机验证记录（2026-08-13，Android 13 / API 33 userdebug，平台签名 + device owner）

| 核验项 | 结果 |
|---|---|
| ASR-0018/0024 隐藏应用 | `SetApplicationHidden packageName=com.hmdm.lockprobe hidden=true` → true；`IsApplicationHidden` → true；`dumpsys package` hidden=true 三方一致；恢复 hidden=false 后 hidden=false |
| ASR-0025 锁任务名单 | `SetLockTaskPackages packageNames=["com.hmdm.lockprobe"]` → true；`GetLockTaskPackages` → [com.hmdm.lockprobe]；device_policies.xml lock-task-component 含该包 |
| ASR-0025 固定屏幕（进入锁任务） | `adb shell am start -n com.hmdm.lockprobe/.LockProbeActivity`（探针 onCreate 调 startLockTask）→ `dumpsys activity` mLockTaskModeState=LOCKED、mCurrentFocus=lockprobe、截图内容存在 |
| ASR-0347 HOME 键禁用 | 锁任务内 `input keyevent 3`（HOME）→ mLockTaskModeState 仍 LOCKED、焦点仍在 lockprobe、截图逐字节相同（21809 字节不变）——HOME 键无效 |
| ASR-0348 MENU/RECENT 键禁用 | 锁任务内 `input keyevent 187`（APP_SWITCH）→ 同上全部不变、无 RecentsView/Overview 窗口——RECENT 键无效 |
| 锁任务退出 | `SetLockTaskPackages packageNames=[]` → mLockTaskModeState=NONE；HOME 键恢复（GGR 桌面前置；本机多 HOME 候选，测试后 `cmd package set-home-activity com.syriusrobotics.platform.launcher/.MainActivity` 恢复默认） |
| 特性记录 | 锁任务期间通知栏若已展开则不可收起（features NONE 语义），退出后恢复；锁任务期间 HOME 意图被吞、退出后可能触发 HOME 选择器——均已记录并恢复 |
| 测试后恢复 | lockprobe 探针卸载（先 SetUninstallBlocked canUninstall=true 解除白名单锁定）、锁任务名单恢复 [com.hmdm.testapp]、隐藏状态复位、GGR 默认桌面恢复、DO 在位 |
