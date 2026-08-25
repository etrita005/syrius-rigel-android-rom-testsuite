# 进程管控（ASR-0027/0028）技术设计文档

## 1. 需求概述

| 需求 ID | 子功能项 | 需求语义 |
|---|---|---|
| ASR-0027 | 结束进程 | 结束指定应用（包名）的进程，使其立即停止运行（含前台应用） |
| ASR-0028 | 清理后台进程 | 批量结束当前处于后台状态的进程，释放内存 |

**归属**：「Launcher（MDM）+ 系统 API」——DevicePolicyManager 无对应完整能力（无"结束指定应用进程"接口），需平台签名/特权应用调用系统服务。本实现由平台签名 Launcher（`sharedUserId=android.uid.system`，uid=1000）经隐藏接口 `ActivityManager.forceStopPackage`（结束进程）与公开接口 `ActivityManager.killBackgroundProcesses`（清理后台）完成，SELinux 与权限检查均已真机核验。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署；bootloader 锁定，不可改动 system 分区（无 ROM 侧定制能力）。

## 2. 技术选型与可行性核验

### 2.1 ASR-0027 结束进程

| 方案 | 说明 | 结论 |
|---|---|---|
| `ActivityManager.forceStopPackage(String)`（隐藏 API，反射调用） | 强制结束指定包的全部进程与组件（含前台），ActivityManagerService 权限检查为 `FORCE_STOP_PACKAGES`（signature\|privileged）；uid=1000 为系统 uid，持有该权限（真机 `dumpsys package` 核验 granted=true） | 采用（主路径） |
| `ActivityManager.forceStopPackage(String, int)`（隐藏 API，反射） | 带 userId 变体，用于旧版本主路径反射失败的兜底 | 采用（备选） |
| `ActivityManager.killBackgroundProcesses(String)`（公开 API） | 仅结束后台（cached）进程，无法结束前台应用；权限 `KILL_BACKGROUND_PROCESSES`（normal 级，uid=1000 自动授予） | 采用（最终回退） |

**SELinux 核验**：system_app 域调用 ActivityManagerService 的 forceStop/killBackground 属常规 Binder 调用，无额外文件/属性访问；真机验证 forceStopPackage 成功返回且目标进程被终止。

**调用链保护（关键设计）**：testapp 经 AIDL 同步调用本命令。若立即结束进程，调用方（testapp）会在收到响应前被杀死，`am broadcast` 无法拿到结果数据。因此命令在**执行结束前延时 500ms**，让 AIDL 响应先回传调用方、广播结果先发送，再真正结束进程（自结束场景实测：广播完成但 data 可能丢失，见第 7 节）。

### 2.2 ASR-0028 清理后台进程

| 方案 | 说明 | 结论 |
|---|---|---|
| `ActivityManager.getRunningAppProcesses()` 枚举进程 + 按包聚合最小 importance + `killBackgroundProcesses(package)` 逐包结束 | 公开 API 组合；uid=1000 可获取全量进程列表（与 ASR-0022 同源） | 采用 |

**后台判定**：进程 importance ≥ `IMPORTANCE_BACKGROUND`（400，即 BACKGROUND/CACHED/EMPTY）。前台（100）、前台服务（125）、可见（200）、可感知（230）、服务（300）进程不清理。若某包同时存在前台与后台进程（多进程应用），按"包内最小 importance"判定——仍有前台进程的包不清理。

**保护名单**（始终不清理）：Launcher 自身包名（`com.hmdm.launcher`）、`com.android.systemui`、`com.android.settings`、`com.android.phone`、`com.android.inputmethod.latin`、`android`；调用方可经 `except` 参数追加需保留的包（如 MDM 客户端自身）。

### 2.3 AIDL 通道约束

命令经 `ApiBinder.method2Commands` 注册（不修改 AIDL/lib 模块），返回 `Map{"RESULT": ...}`；返回结构全部使用 JDK 类型（Map/List/String/Boolean），`except` 数组经 Bundle `putSerializable`（ArrayList 为 Serializable）传递，避免自定义 Bean 跨进程反序列化失败（与 GetLogBufferSize 踩坑一致）。

## 3. 命令接口定义（SystemApiInterface.onEvent）

| 命令名 | 参数（Map） | 返回 RESULT | 说明 |
|---|---|---|---|
| `KillAppProcess` | packageName（String，必填） | Map（见下） | ASR-0027 结束指定应用进程 |
| `KillBackgroundProcesses` | except（可选，String 数组，需保留的包名） | Map（见下） | ASR-0028 清理后台进程 |

**KillAppProcess 返回结构**：

```
{success:boolean, packageName:String, method:String(forceStopPackage|killBackgroundProcesses)}
或 {error:String}   # 缺参 / 目标是 Launcher 自身 / 全部结束方式失败
```

**KillBackgroundProcesses 返回结构**：

```
{count:int, killed:[String,...], skipped:[String,...]}
# count 与 killed 长度一致；skipped 为保护名单/except/结束失败未清理的包
```

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> param = new HashMap<>();
param.put("packageName", "com.android.settings");
Map result = api.onEvent("KillAppProcess", param);   // {"RESULT":{"success":true,...}}

Map<String, Object> p2 = new HashMap<>();
p2.put("except", Arrays.asList("com.hmdm.testapp"));
Map result2 = api.onEvent("KillBackgroundProcesses", p2); // {"RESULT":{"count":N,"killed":[...],"skipped":[...]}}
```

**广播通道**（TestBroadcast，与现有命令一致）：

```bash
adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast \
  -a ON_SQA_INTERFACE_TEST --es event KillAppProcess --es param '{"packageName":"com.android.settings"}'
# 或 ./send_test_broadcast.sh KillAppProcess packageName=com.android.settings
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── service/command/process/
│   ├── KillAppProcess.java          # 新增：ASR-0027，forceStopPackage 反射链 + killBackgroundProcesses 回退 + 500ms 前置延时
│   └── KillBackgroundProcesses.java # 新增：ASR-0028，importance≥400 进程枚举 + 逐包 killBackgroundProcesses + 保护名单
├── service/ApiBinder.java           # 注册 KillAppProcess / KillBackgroundProcesses
└── AndroidManifest.xml              # 新增 FORCE_STOP_PACKAGES / KILL_BACKGROUND_PROCESSES 声明

testapp/
├── src/main/java/com/hmdm/testapp/
│   ├── ProcessControlTestActivity.java # 新增：进程管控测试页（目标包输入 + 结束进程按钮 + except 输入 + 清理按钮）
│   ├── TestActions.java                # 新增 KillAppProcess / KillBackgroundProcesses 事件（含参数校验）
│   └── MainActivity.java               # 增加 "Process control" 入口
├── src/main/res/layout/activity_process_control_test.xml # 新增测试页布局
└── src/main/AndroidManifest.xml        # 注册 ProcessControlTestActivity
```

## 5. 执行逻辑

```
KillAppProcess(packageName):
  1. 参数校验：缺 packageName → {error:"missing parameter: packageName"}
  2. 自保护：packageName == com.hmdm.launcher → {error:"cannot kill the MDM Launcher itself"}
  3. 延时 500ms（保证命令结果先回传调用方，自结束场景不丢链）
  4. 反射 forceStopPackage(String) → 失败反射 forceStopPackage(String, int=0)
     → 失败 killBackgroundProcesses(packageName) → 再失败 {error}
  5. 返回 {success:true, packageName, method:实际使用的接口}

KillBackgroundProcesses(except?):
  1. getRunningAppProcesses() 全量进程，按包聚合包内最小 importance
  2. 过滤 importance ≥ 400（BACKGROUND/CACHED/EMPTY）的包
  3. 排除保护名单（Launcher 自身/systemui/settings/phone/输入法/android）与 except 参数
  4. 逐包 killBackgroundProcesses(pkg)，失败计入 skipped
  5. 返回 {count, killed[], skipped[]}
```

## 6. 权限与归属

- 归属：Launcher（MDM）+ 系统 API。无 ROM 侧代码改动，无 root 依赖。
- 新增权限声明（平台签名自动授予，真机核验 granted=true）：
  - `android.permission.FORCE_STOP_PACKAGES`（signature|privileged）：forceStopPackage 权限检查；
  - `android.permission.KILL_BACKGROUND_PROCESSES`（normal）：killBackgroundProcesses 权限检查。
- forceStopPackage 不在公开 SDK，经反射调用；`killBackgroundProcesses` 为公开 API（API 33 起标 deprecated，仅提示、行为不变）。
- 不修改 AIDL / lib 模块；testapp 无需新增权限。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺 packageName / 非法 except（非数组） | 返回 `{"error":"missing parameter: packageName"}` / `"invalid parameter: except (array)"`，不 crash |
| 目标是 Launcher 自身 | 拒绝并返回 error（防止自毁管控链） |
| 包不存在或未运行 | forceStopPackage 为空操作，返回 success=true（接口语义即"确保已结束"） |
| 调用方结束自己（testapp 结束 testapp） | 500ms 延时保证 AIDL 响应先回传；进程随后终止，`am broadcast` 完成但 result data 可能丢失（实测 result=0），需以 `ps -A` 验证进程已死（文档化限制） |
| 前台应用结束 | forceStopPackage 可结束前台应用（区别于 killBackgroundProcesses） |
| 多进程应用前后台混合 | 按包内最小 importance 判定，仍有前台进程的包不清理 |
| 权限被拒（非平台签名部署） | forceStop 反射失败 → 回退 killBackgroundProcesses（normal 权限仍可用）；两者均失败返回 error |
| 系统关键包 | 保护名单 + except 双重排除，永不清理 |
| getRunningAppProcesses 返回空 | 返回 count=0，不 crash |

## 8. 真机验证记录（2026-08-04，Android 13 / API 33 userdebug，平台签名 + device owner）

| 用例 | 结果 |
|---|---|
| TC-0027-01 结束后台应用 | `KillAppProcess packageName=com.android.settings` → `{"method":"forceStopPackage","success":true}`；`ps -A` 确认进程已终止 |
| TC-0027-02 前台应用可结束 | settings 重新启动后再次结束 → success，进程消失（forceStopPackage 不受前后台限制） |
| TC-0027-03 自结束（调用方=testapp） | 返回 result=0（进程在响应返回瞬间被终止，data 未及回传），`ps -A` 确认 testapp 已死；符合设计限制 |
| TC-0027-04 缺参 | 返回 `missing parameter: packageName` |
| TC-0027-05 自保护 | `packageName=com.hmdm.launcher` → `cannot kill the MDM Launcher itself`；Launcher 进程存活 |
| TC-0027-06 不存在包 | success=true（空操作），不 crash |
| TC-0028-01 批量清理 | `KillBackgroundProcesses` → count=11，killed 含 printspooler/cellbroadcast/providers.calendar/downloads/simprocessor/keychain/providers.contacts/blockednumber/providers.media/mtp/providers.userdictionary 等后台包；Launcher 与 testapp 进程存活 |
| TC-0028-02 前台保护 | 清理后 com.hmdm.testapp（调用进程）、com.hmdm.launcher 均存活；settings 因前台/保护名单未受影响 |
| TC-0028-03 except 参数 | 前台启动 gallery3d 后按 HOME 使其后台 → `except=["com.android.gallery3d"]` 清理 → gallery3d 在 skipped，其余后台包（permissioncontroller）被清理 |
| TC-0028-04 无 except | 再次清理 → `killed=["com.android.gallery3d"]`，`ps -A` 确认进程已死 |
| TC-0028-05 非法 except | `except=notarray` → `invalid parameter: except (array)` |
| TC-M-01/02 | 未知事件返回 unknown event；UI/IPC 共用 TestActions 引擎 |

**部署与实现注意**：
1. forceStopPackage 权限检查以调用者 uid 判定，必须平台签名部署（uid=1000）；非平台签名场景自动回退 killBackgroundProcesses，只能清理后台进程。
2. 自结束场景（结束 testapp 自身）依赖 500ms 前置延时回传结果；若远端经 `send_test_broadcast.sh` 直连 Launcher 调用，无此问题（调用方非被结束对象）。
3. 本 ROM 无计算器应用（`com.android.calculator2` 不存在），测试目标选用 `com.android.gallery3d`。
4. testapp 广播调用需保持 `dumpsys duraspeed addwhitelist com.hmdm.testapp`（MTK DurASpeed 抑制后台广播）；屏幕常亮 `svc power stayon true`。
