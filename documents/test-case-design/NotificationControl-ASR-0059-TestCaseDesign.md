# 状态栏通知管控（ASR-0059）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner，testapp（`com.hmdm.testapp`）已安装；
- 测试通道：`./send_test_command.sh <event> key=value ...`（IPC 与 UI 按钮等效，同一 `TestActions.execute()` 引擎）；也可在 testapp UI 的 "Notification control" 页面（NotificationTestActivity）按 "Disable/Enable/Query status bar notification icons" 按钮；
- 对照命令：
  - `adb shell dumpsys statusbar | grep -E "mDisabled1|mDisabled2"`（禁用标志位，`0x00020000`=DISABLE_NOTIFICATION_ICONS，本机基线 mDisabled1=0x1000000 为锁任务 RECENT 标志，勿与 0x20000 混淆）；
  - `adb shell dumpsys statusbar | grep -A3 mDisableRecords`（DisableRecord 对照：`pkg=android` 的 `StatusBarShellCommandToken` 记录 what1）；
  - `adb shell dumpsys notification --noredact`（通知记录：验证禁用状态栏通知不影响通知发送与面板展示）；
  - `adb shell cat /data/data/com.hmdm.launcher/shared_prefs/status_bar_notifications_policy.xml`（策略持久化文件，需 `adb root`）；
  - 状态栏条带截屏比对（`screencap` + 顶部 56px 条带像素 diff，本机因 ROM 定制受限，见 TC-SB-05）。
- 测试前置：确保 testapp 通知已启用（`./send_test_command.sh SetNotificationsEnabledForPackage packageName=com.hmdm.testapp enabled=true`，本机出厂默认禁用所有应用通知，须先启用才能观察到通知记录）；
- 恢复基线：测试结束后 `SetStatusBarNotificationsDisabled disabled=false`，`mDisabled1` 恢复基线（无 0x20000 位）、策略文件 disabled=false、testapp 通知保持启用。

## 2. 测试用例表

### 2.1 基线 / 禁用 / 启用 / 查询

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-SB-01 基线查询 | `./send_test_command.sh IsStatusBarNotificationsDisabled` | RESULT=false；`dumpsys statusbar` mDisabled1 无 0x20000 位（本机基线 0x1000000）；testapp 发测试通知后 `dumpsys notification` 有 NotificationRecord |
| TC-SB-02 禁用状态栏通知 | `./send_test_command.sh SetStatusBarNotificationsDisabled disabled=true` | RESULT=true；`dumpsys statusbar` mDisabled1 出现 0x20000 位（0x1000000 → 0x1020000）；`mDisableRecords` 中 `StatusBarShellCommandToken` 记录 what1=0x00020000；logcat `NotificationPolicyManager applyStatusBarFlag flag:notification-icons exit:0` |
| TC-SB-03 查询禁用态 | `./send_test_command.sh IsStatusBarNotificationsDisabled` | RESULT=true |
| TC-SB-04 禁用不影响通知发送/面板 | 禁用状态下 `./send_test_command.sh SendTestNotification` | `dumpsys notification` 仍有 testapp NotificationRecord（importance=3）——仅状态栏图标被隐藏，通知本身与面板展示不受影响 |
| TC-SB-05 状态栏图标显示/隐藏 | 对比禁用前/后状态栏条带截屏；或在通知功能正常的设备上目视 | **机制级**：mDisabled1 0x20000 位置位/清除（SystemUI 渲染通知图标的唯一开关）；**本机显示定制限制**：出厂默认应用级禁用几乎所有应用通知（`dumpsys notification` AppSettings 全量 importance=NONE/fixedImportance，与 ASR-0462 一致），testapp 通知图标在本机状态栏本就不渲染（启用/禁用两态条带截屏像素级一致），图标级可视化验收需通知功能正常的标准 Android 13 设备补充执行（见需求文档"硬件受限测试说明"） |
| TC-SB-06 启用恢复 | `./send_test_command.sh SetStatusBarNotificationsDisabled disabled=false` | RESULT=true；`dumpsys statusbar` mDisabled1 0x20000 位清除（0x1020000 → 0x1000000）；logcat `applyStatusBarFlag flag:none exit:0` |
| TC-SB-07 查询启用态 | `./send_test_command.sh IsStatusBarNotificationsDisabled` | RESULT=false |

### 2.2 参数校验 / 幂等

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-SB-08 缺参数 | `./send_test_command.sh SetStatusBarNotificationsDisabled` | RESULT="missing parameter: disabled"（不 crash） |
| TC-SB-09 幂等重复禁用 | 连续两次 `SetStatusBarNotificationsDisabled disabled=true` | 均 RESULT=true；mDisabled1 0x20000 位稳定置位 |
| TC-SB-10 非法参数 | `./send_test_command.sh SetStatusBarNotificationsDisabled disabled=abc` | 不 crash；disabled 按 Boolean.parseBoolean 解析为 false（等价启用路径），RESULT 如实返回 |

### 2.3 与其他通知策略共存

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-SB-11 与按包管控共存 | 状态栏禁用中执行 `SetNotificationsEnabledForPackage packageName=com.hmdm.testapp enabled=false` | 通知记录消失（按包管控生效：通知不再发送/展示），mDisabled1 0x20000 位不受影响（两机制独立）；再 `enabled=true` 恢复 |
| TC-SB-12 与锁屏通知共存 | 状态栏禁用中执行 `SetLockscreenNotificationsDisabled disabled=true` | RESULT=true；`lock_screen_show_notifications=0`；mDisabled1 0x20000 位不受影响；恢复 disabled=false |

### 2.4 持久化 / 重新武装 / 无残留

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-SB-13 策略持久化 | `SetStatusBarNotificationsDisabled disabled=true` 后 `adb root` 查看 `status_bar_notifications_policy.xml` | 文件存在且 `<boolean name="disabled" value="true" />` |
| TC-SB-14 整机重启重新武装 | `disabled=true` → `adb reboot` → 开机后查询 | `dumpsys statusbar` mDisabled1=0x1020000（BootCompletedReceiver → `syncStatusBarPolicy` 重新应用，logcat `syncStatusBarPolicy: status bar notifications disabled re-applied:true`）；`IsStatusBarNotificationsDisabled` → true |
| TC-SB-15 无残留 | 全部用例结束后 `SetStatusBarNotificationsDisabled disabled=false` | mDisabled1 恢复基线（无 0x20000 位）；`status_bar_notifications_policy.xml` disabled=false；logcat 无新 `send-disable-flag` 调用；与测试前状态一致 |

## 3. 真机验收记录（2026-08-05，Android 13 / API 33 userdebug，平台签名 + device owner）

设备：MT6771（银星定制 ROM）；`STATUS_BAR` 权限对平台签名 uid=1000 自动授予（`su 1000 cmd statusbar` 实测，无需 manifest 声明）。

| 用例 | 结果 | 备注 |
|---|---|---|
| TC-SB-01 | 通过 | RESULT=false；mDisabled1=0x1000000（无 0x20000 位）；testapp 通知启用后 `dumpsys notification` 有记录（本机出厂默认禁用所有应用通知，须先 `SetNotificationsEnabledForPackage ... enabled=true`） |
| TC-SB-02 | 通过 | RESULT=true；mDisabled1=0x1020000；mDisableRecords 出现 `StatusBarShellCommandToken` what1=0x00020000；引擎日志 exit:0 |
| TC-SB-03 | 通过 | RESULT=true |
| TC-SB-04 | 通过 | 禁用中 testapp 通知正常发送：NotificationRecord importance=3 仍在（仅隐藏状态栏图标） |
| TC-SB-05 | 部分受限（ROM 显示定制） | 机制级通过：0x20000 位置位/清除、读回核对、DisableRecord 对照一致；本机状态栏不渲染应用通知图标（出厂默认应用级禁用通知），启用/禁用两态条带截屏像素级一致，图标级可视化验收需标准设备补充（详见需求文档"硬件受限测试说明"） |
| TC-SB-06 | 通过 | RESULT=true；mDisabled1 恢复 0x1000000；日志 flag:none exit:0 |
| TC-SB-07 | 通过 | RESULT=false |
| TC-SB-08 | 通过 | RESULT="missing parameter: disabled" |
| TC-SB-09 | 通过 | 两次均 true；0x20000 位稳定 |
| TC-SB-10 | 通过 | disabled=abc 解析为 false（启用路径），RESULT=true，不 crash |
| TC-SB-11 | 通过 | 按包禁用后通知记录消失（grep 计数 0）；mDisabled1 0x20000 位不受影响；恢复后通知可再发 |
| TC-SB-12 | 通过 | 锁屏通知开关独立生效（lock_screen_show_notifications 0↔1），状态栏标志不受影响 |
| TC-SB-13 | 通过 | `status_bar_notifications_policy.xml` 含 disabled=true（adb root 读取） |
| TC-SB-14 | 通过 | 重启后 mDisabled1=0x1020000（`syncStatusBarPolicy: status bar notifications disabled re-applied:true`，BootCompletedReceiver 路径）；查询 true |
| TC-SB-15 | 通过 | 恢复后 mDisabled1=0x1000000、策略文件 disabled=false、查询 false |

**备注**：`am force-stop com.hmdm.launcher` 在本机未观察到进程终止（pid 不变，疑与平台签名/系统 uid 进程保护相关），进程级重新武装路径经整机重启用例（TC-SB-14）覆盖——重启同时验证了 system_server 标志清零后的开机重新武装，比进程重启更严格。

## 4. 硬件受限测试说明

（如本机硬件/环境受限，受影响用例记录于此，引用本文档用例编号）

- **TC-SB-05（图标级可视化部分）**：本机为银星定制 ROM，出厂默认在应用级禁用几乎所有应用的通知（`dumpsys notification` AppSettings 全量 `importance=NONE/fixedImportance`，与需求 ASR-0462"默认禁用所有应用通知（ROM）"一致；`cmd notification post` 的 shell 通知同样被 fixedImportance 阻断）——testapp 通知启用后其状态栏图标在本机状态栏本就不渲染（启用/禁用两态状态栏条带截屏像素级比对完全一致）。图标"出现/消失"的可视化验收需在**通知功能正常的标准 Android 13 设备**上补充执行（机制级证据——0x20000 标志置位/清除 + DisableRecord 对照——已在本机全部通过，见技术设计文档 §8）。
