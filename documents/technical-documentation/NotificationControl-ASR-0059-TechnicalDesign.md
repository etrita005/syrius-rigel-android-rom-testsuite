# 状态栏通知管控（ASR-0059）技术设计文档

## 1. 需求概述

| 需求 ID | 子功能项 | 需求语义 |
|---|---|---|
| ASR-0059 | 设置是否启用状态栏通知 | 全局禁用/启用状态栏通知：禁用后状态栏不再显示任何通知图标，启用后恢复显示 |

**归属**：Launcher（MDM）+ 系统 API。实现全部位于 Launcher 侧（`mdm_launcher/hmdm-android`），依赖平台签名（`sharedUserId="android.uid.system"`，uid=1000）执行系统命令。

**语义界定**：与 ASR-0052（免打扰/静音）、ASR-0053/0054/0055（按包通知开关与黑白名单）、ASR-0057（锁屏通知）正交——本需求只管控**状态栏通知图标的显示**，不改变通知的发送、通知面板（下拉通知栏）展示、声音/振动提醒等行为；禁用后应用仍可正常发送通知，通知仍存在于通知面板，仅状态栏图标被隐藏。

## 2. 技术选型

### 2.1 接口机制：StatusBarManager 禁用标志

- **机制**：`StatusBarManagerService.disableForUser` 的 `DISABLE_NOTIFICATION_ICONS`（`0x00020000`）标志——SystemUI 的状态栏图标区（NotificationIconAreaController）在标志置位时整体隐藏通知图标，这是 AOSP 标准的状态栏通知图标显示开关，也是各厂商"状态栏通知"设置的系统级实现。
- **本 ROM 暴露方式**：本 ROM（MTK fork AOSP 13）的 `cmd statusbar send-disable-flag` shell 命令提供该接口的逐标志控制（`adb shell cmd statusbar help` 实测可用）：`notification-icons` 置位、`none` 复位。该命令在 system_server 内执行，禁用记录（DisableRecord）由 system_server 持有，**进程/整机重启后清零**。
- **实现路径**：Launcher 为平台签名应用（uid=1000），`Runtime.exec("cmd statusbar send-disable-flag <flag>")` 的子进程以 uid=1000 发起 Binder 调用，`runSendDisableFlag` 的 `STATUS_BAR` 签名权限检查对 uid=1000 通过（真机 `su 1000 cmd statusbar send-disable-flag notification-icons` 实测成功，见 2.2）。

### 2.2 权限与调用链真机核验（2026-08-05）

| 核验项 | 结果 |
|---|---|
| `cmd statusbar send-disable-flag notification-icons`（shell） | mDisabled1 由 0x1000000 → 0x1020000，标志持久（5 秒后仍置位） |
| `cmd statusbar send-disable-flag none` | mDisabled1 恢复 0x1000000（循环多次均正常，DisableRecord 按 token 复用更新，无累计残留） |
| uid=1000 调用（`adb root` 后 `su 1000 cmd statusbar send-disable-flag ...`） | 成功置位/复位（STATUS_BAR 签名权限对平台签名系统 uid 自动授予，**无需在 manifest 新增声明**） |
| `dumpsys statusbar` 读回 | `mDisabled1=0x1020000` 与 `mDisableRecords` 中 `StatusBarShellCommandToken` 记录（what1=0x00020000）对照一致 |
| `dumpsys window` 状态栏窗口 | StatusBar 窗口存在且可见（frame=[0,0][720,56]），图标渲染路径消费该标志 |

### 2.3 写后读回核对

`setStatusBarNotificationsDisabled` 在执行命令后，解析 `dumpsys statusbar` 的 `mDisabled1` 十六进制值核对 `0x20000` 位是否处于目标状态：匹配才返回 `true` 并持久化；不匹配返回 `false`（如实上报）。`dumpsys` 解析不可用时（读回通道异常）回退为仅以命令退出码（exit=0）判定，保证命令可用性。

### 2.4 持久化与重新武装

- **存储**：Launcher SharedPreferences 独立文件 `status_bar_notifications_policy`（键 `disabled`，布尔），仅在设置成功（读回核对通过）时写入。
- **生命周期**：禁用标志由 system_server 持有，Launcher 进程被杀不影响标志生效；**整机重启后 system_server 重启、DisableRecord 清零**，须重新武装：
  - `ApiService.onCreate` → `NotificationPolicyManager.syncStatusBarPolicy()`（进程重启路径）；
  - `BootCompletedReceiver`（`ACTION_BOOT_COMPLETED`）→ 同一方法（开机路径）。
  - 重新武装为幂等操作：持久化标志为 false 时为空操作，不产生系统写入。
- **查询**：`IsStatusBarNotificationsDisabled` 返回持久化标志（最后一次成功应用的状态），与仓库内其他标志类策略（forceOpen、tethering 禁止标志等）一致。

### 2.5 与相邻需求的关系

| 需求 | 关系 |
|---|---|
| ASR-0052 免打扰（zen_mode） | 独立：免打扰影响提醒与静音，本需求只影响状态栏图标 |
| ASR-0053/0054/0055 按包通知开关/黑白名单 | 独立：按包管控改变通知发送本身，本需求为全局显示层；二者可共存 |
| ASR-0057 锁屏通知（lock_screen_show_notifications） | 独立：锁屏显示 vs 状态栏显示 |
| ASR-0064 状态栏通知设置（用户修改管控） | 本需求为"设置状态栏通知是否启用"的执行体；ASR-0064 为入口管控，不在本批次范围 |
| ASR-0462 默认禁用所有应用通知（ROM） | 本 ROM 出厂默认在应用级禁用几乎全部应用的通知（`dumpsys notification` AppSettings 全量 importance=NONE/fixedImportance），导致状态栏本就不渲染应用通知图标——本需求提供的是独立于应用级开关的状态栏图标显示开关，二者机制不同、可独立生效 |

## 3. 命令接口定义（SystemApiInterface.onEvent）

命令通过 `ApiBinder.method2Commands` 注册，`CallWithCommand` 包装调用，返回 `Map{"RESULT": 返回值}`。`disabled` 参数兼容 Boolean / String / Number 三种取值（与 `SetLockscreenNotificationsDisabled` 等既有命令一致）。

| 命令名 | 参数（Map） | 返回 RESULT | 说明 |
|---|---|---|---|
| `SetStatusBarNotificationsDisabled` | disabled: Boolean | Boolean | ASR-0059 禁用/启用状态栏通知（执行 `cmd statusbar send-disable-flag notification-icons/none` + 读回核对；成功才持久化） |
| `IsStatusBarNotificationsDisabled` | - | Boolean | ASR-0059 查询（返回持久化标志） |

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> param = new HashMap<>();
param.put("disabled", true);
Map result = api.onEvent("SetStatusBarNotificationsDisabled", param);
boolean ok = (Boolean) result.get("RESULT");
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/NotificationPolicyManager.java            # 新增状态栏通知方法（set/query/sync/apply+读回核对）
└── service/command/notification/
    ├── SetStatusBarNotificationsDisabled.java      # 新增命令
    └── IsStatusBarNotificationsDisabled.java       # 新增命令

app/src/main/java/com/hmdm/launcher/syrius/service/ApiBinder.java   # 注册 2 条命令
app/src/main/java/com/hmdm/launcher/syrius/service/ApiService.java  # onCreate 重新武装
app/src/main/java/com/hmdm/launcher/syrius/broadcast/BootCompletedReceiver.java  # 开机重新武装

testapp/                                            # 测试 APP 模块
├── src/main/java/com/hmdm/testapp/TestActions.java          # IPC 事件路由 + 事件目录
├── src/main/java/com/hmdm/testapp/NotificationTestActivity.java  # 新增 3 个按钮
└── src/main/res/layout/activity_notification_test.xml       # ASR-0059 分区
```

不修改 `AndroidManifest.xml`（STATUS_BAR 签名权限对平台签名 uid 自动授予，无需声明）、不修改 `device_admin.xml`（无 DPM uses-policy）、不新增 ROM 侧代码、无需重启 framework。

## 5. 策略逻辑

```
setStatusBarNotificationsDisabled(disabled):
  执行 cmd statusbar send-disable-flag (notification-icons | none)
  exit != 0 → 返回 false（如实上报）
  读回 dumpsys statusbar mDisabled1，核对 0x20000 位 == 目标状态
    （读回不可用时信任 exit=0）
  不匹配 → 返回 false；匹配 → 持久化 disabled 到 status_bar_notifications_policy → true

isStatusBarNotificationsDisabled():
  返回 status_bar_notifications_policy.disabled（默认 false）

syncStatusBarPolicy():          # ApiService.onCreate / BootCompletedReceiver 调用
  持久化 disabled == true 时重新执行 setStatusBarNotificationsDisabled(true)（幂等）
```

## 6. 权限与归属

- Launcher（MDM）+ 系统 API：平台签名应用（uid=1000）执行系统命令控制 @SystemApi 级状态栏禁用标志。
- 权限：`STATUS_BAR`（签名级，平台签名 uid=1000 自动授予，`su 1000` 实测通过，无需 manifest 声明）；`dumpsys` 读回依赖 uid=1000 的 DUMP 权限（平台签名自动授予，与 ASR-0372 batterystats 读回同路径）。
- 不修改 `IMdmApi.aidl` / `lib` 模块，对外命令通道仍为 `SystemApiInterface.onEvent`。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| `cmd statusbar` 不存在/被移除（其他 ROM） | 命令执行异常 → catch 返回 false，日志记录，不 crash |
| 命令执行成功但退出码非 0 | 返回 false（如 STATUS_BAR 权限不足时 SecurityException 表现为非 0 退出码） |
| 命令/读回进程挂起 | 10 秒超时 + `process.destroy()` 兜底（`cmd statusbar` 路径为带超时的 execWait，`dumpsys statusbar` 读回复用 ShellUtils.exec 的超时保护），不阻塞调用线程 |
| 读回核对不匹配 | 返回 false 并记录 mismatch（标志可能被其他来源修改） |
| `dumpsys statusbar` 不可解析 | 回退仅以 exit=0 判定（读回为 best-effort） |
| 重复设置同一状态 | 幂等：重新执行 + 核对 + 写同一值，返回 true |
| 整机重启 | 标志清零；BootCompletedReceiver 经 syncStatusBarPolicy 重新武装（真机验证） |
| 进程被杀 | 标志由 system_server 持有不受影响；ApiService.onCreate 幂等重新武装 |
| 禁用期间应用发送通知 | 通知正常发送、存在于通知面板，仅状态栏图标隐藏（语义界定，见 §1） |
| 与其他通知策略共存 | 按包管控/免打扰/锁屏通知独立生效，互不影响（真机验证） |

## 8. 真机验证记录（2026-08-05，MTK Android 13 userdebug，平台签名 + device owner）

| 用例 | 结果 |
|---|---|
| 基线查询 | `IsStatusBarNotificationsDisabled` → false；`dumpsys statusbar` mDisabled1=0x1000000（无 0x20000 位） |
| 禁用 | `SetStatusBarNotificationsDisabled disabled=true` → true；mDisabled1=0x1020000；引擎日志 `applyStatusBarFlag flag:notification-icons exit:0` + `setStatusBarNotificationsDisabled disabled:true applied:true persisted:true` |
| 查询禁用态 | → true |
| 通知不受影响 | 禁用后 testapp 再发通知：`dumpsys notification` NotificationRecord 仍在（importance=3，通知面板正常展示） |
| 启用恢复 | `disabled=false` → true；mDisabled1=0x1000000（0x20000 位清除） |
| 查询启用态 | → false |
| 缺参数 | RESULT="missing parameter: disabled" |
| 幂等 | 连续两次 disabled=true 均 true，标志稳定 |
| 与其他通知策略共存 | 状态栏禁用期间 `SetNotificationsEnabledForPackage testapp=false`：通知记录消失（按包管控生效），mDisabled1 0x20000 位不受影响；恢复 testapp 通知 |
| 整机重启重新武装 | `disabled=true` → `adb reboot` → 开机后 mDisabled1=0x1020000，引擎日志 `syncStatusBarPolicy: status bar notifications disabled re-applied:true`（BootCompletedReceiver 路径），查询 → true |
| 无残留 | 恢复 disabled=false 后 mDisabled1=0x1000000，`status_bar_notifications_policy.xml` 中 disabled=false |

**本机显示定制限制（如实记录）**：本机为银星定制 ROM，出厂默认在应用级禁用几乎所有应用的通知（`dumpsys notification` AppSettings 显示系统应用全量 `importance=NONE/fixedImportance`，与 ASR-0462"默认禁用所有应用通知（ROM）"一致；`cmd notification post` 的 shell 通知同样被 `fixedImportance` 阻断）——testapp 通知启用后其**状态栏图标在本机状态栏本就不渲染**（启用/禁用两种状态下状态栏条带截图像素级比对完全一致，仅时钟/系统图标可见）。因此"状态栏图标出现/消失"的可视化验收在本机不可观测；本批次以**系统标志机制级证据**验收：`cmd statusbar send-disable-flag`（本 ROM 为状态栏通知图标显示提供的标准接口）置位/复位 `DISABLE_NOTIFICATION_ICONS` 标志 + `mDisabled1` 位读回核对 + DisableRecord 对照 + SystemUI 状态栏窗口消费路径，标志即 SystemUI 渲染通知图标的唯一开关。图标级可视化确认需在通知功能正常的标准 Android 13 设备上补充执行（见测试用例文档 TC-SB-05 与需求文档"硬件受限测试说明"）。
