# 应用可见性与锁任务管控（ASR-0018/0024/0025/0347/0348）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner（`dpm list-owners` 预期 `DeviceOwner,Affiliated`），testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root`、屏幕常亮（`svc power stayon true`）；测试前确认默认桌面为 GGR（`cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME`，若出现 ResolverActivity 先 `cmd package set-home-activity com.syriusrobotics.platform.launcher/.MainActivity`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`（IPC 与 UI 按钮等效）；
- 测试素材：锁任务探针 APK `com.hmdm.lockprobe`（Activity onCreate 调 `startLockTask()`，工具脚本构建，仅测试期安装）；
- 对照命令：`adb shell dumpsys activity activities | grep mLockTaskModeState`（LOCKED=锁任务激活）、`dumpsys window | grep mCurrentFocus`、`dumpsys package <pkg> | grep "User 0:"`（hidden 字段）、`adb shell strings /data/system/device_policies.xml | grep lock-task-component`、`screencap`（截图字节数作为画面内容代理，本机 1600×720 纯色/同内容 ≈ 6KB、有内容 ≈ 21KB）；
- 恢复基线：隐藏状态全部复位（hidden=false）、锁任务名单恢复 `["com.hmdm.testapp"]`、探针卸载、GGR 默认桌面在位；
- 本 ROM 特性（2026-08-13 核验）：① 锁任务激活（mLockTaskModeState=LOCKED）期间 HOME/RECENT 键被系统吞掉、画面不变；② 锁任务期间通知栏若展开则不可收起（features NONE 语义），退出后恢复；③ 锁任务退出=将包移出名单；④ 多 HOME 候选，锁任务测试后可能弹 HOME 选择器，用 `cmd package set-home-activity` 恢复。

## 2. 测试用例表

### 2.1 ASR-0018/0024 隐藏应用（SetApplicationHidden / IsApplicationHidden）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0018-01 基线 | 安装探针 `adb install -r /tmp/kilo/lockprobe.apk`；`./send_test_command.sh IsApplicationHidden packageName=com.hmdm.lockprobe` | false（未隐藏） |
| TC-0018-02 隐藏 | `./send_test_command.sh SetApplicationHidden packageName=com.hmdm.lockprobe hidden=true`；`IsApplicationHidden`；`adb shell dumpsys package com.hmdm.lockprobe \| grep "User 0:"` | Set RESULT=true；Is=true；dumpsys `hidden=true`（框架状态三方一致）；桌面/应用列表不再出现该应用（隐藏语义，外部桌面遵从时图标消失） |
| TC-0018-03 恢复 | `SetApplicationHidden packageName=com.hmdm.lockprobe hidden=false`；`IsApplicationHidden`；dumpsys | Is=false；dumpsys `hidden=false` |
| TC-0024-01 白名单语义（多包） | 对白名单内多个包逐一执行隐藏/恢复（示例：探针 + 任一用户包） | 各包独立生效、互不影响；IsApplicationHidden 分别核对 |
| TC-0018-04 缺参 | `SetApplicationHidden hidden=true` | 返回 `missing parameter: packageName`，不 crash |
| TC-0018-05 隐藏不存在的包 | `SetApplicationHidden packageName=com.nonexistent.xyz hidden=true` | RESULT=false（真实 DPM 结果），不 crash |

### 2.2 ASR-0025 固定屏幕显示应用（锁任务名单）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0025-01 基线名单 | `./send_test_command.sh GetLockTaskPackages` | 返回数组（记录基线，如 [com.hmdm.testapp]） |
| TC-0025-02 添加 | `./send_test_command.sh SetLockTaskPackages 'packageNames=["com.hmdm.lockprobe"]'`；`GetLockTaskPackages`；`adb shell strings /data/system/device_policies.xml \| grep -a lock-task-component` | Set RESULT=true；Get 返回 [com.hmdm.lockprobe]；device_policies.xml lock-task-component 含该包 |
| TC-0025-03 固定屏幕（进入锁任务） | `adb shell am start -n com.hmdm.lockprobe/.LockProbeActivity`；等待 3s；`dumpsys activity activities \| grep mLockTaskModeState`；`dumpsys window \| grep mCurrentFocus`；`screencap` | mLockTaskModeState=LOCKED；mCurrentFocus=lockprobe 活动；截图有内容（≈21KB）——应用被固定显示 |
| TC-0025-04 查询 | `./send_test_command.sh GetLockTaskPackages` | 仍为 [com.hmdm.lockprobe] |
| TC-0025-05 退出固定 | `./send_test_command.sh SetLockTaskPackages 'packageNames=[]'`；等待 3s；`dumpsys activity activities \| grep mLockTaskModeState` | RESULT=true；mLockTaskModeState=NONE（名单移除即退出锁任务，系统机制） |
| TC-0025-06 恢复名单 | `SetLockTaskPackages 'packageNames=["com.hmdm.testapp"]'`；`GetLockTaskPackages` | 与测试前基线一致 |

### 2.3 ASR-0347 禁用 HOME 键（锁任务内验证）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0347-01 进入锁任务 | 同 TC-0025-03（名单 [com.hmdm.lockprobe] + am start 探针） | mLockTaskModeState=LOCKED、焦点=lockprobe |
| TC-0347-02 HOME 键无效 | 记录当前截图 A（screencap → lt_home_before.png）；`adb shell input keyevent 3`（HOME）；等待 2s；再次截图 B；`dumpsys activity activities \| grep mLockTaskModeState`；`dumpsys window \| grep mCurrentFocus` | mLockTaskModeState 仍 LOCKED；焦点仍在 lockprobe；截图 A/B 字节一致（画面未离开锁任务应用）——HOME 键被禁用（2026-08-13 实测：21809 字节不变） |
| TC-0347-03 退出后 HOME 恢复 | `SetLockTaskPackages 'packageNames=[]'`；等待 3s；`adb shell input keyevent 3`；等待 3s；`dumpsys window \| grep mCurrentFocus` | mLockTaskModeState=NONE；HOME 键恢复（GGR 桌面前置）；若出现 ResolverActivity（多 HOME 候选）→ `cmd package set-home-activity com.syriusrobotics.platform.launcher/.MainActivity` 恢复并记录 |

### 2.4 ASR-0348 禁用 MENU(RECENT/TASK) 键（锁任务内验证）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0348-01 进入锁任务 | 同 TC-0025-03 | LOCKED、焦点=lockprobe |
| TC-0348-02 RECENT 键无效 | 截图 A；`adb shell input keyevent 187`（APP_SWITCH/RECENT）；等待 2s；截图 B；`dumpsys activity activities \| grep mLockTaskModeState`；`dumpsys window windows \| grep -iE 'RecentsView\|Overview'` | mLockTaskModeState 仍 LOCKED；焦点仍在 lockprobe；截图 A/B 一致；无 RecentsView/Overview 窗口出现——RECENT/MENU 键被禁用（2026-08-13 实测） |
| TC-0348-03 退出与恢复 | 同 TC-0347-03 | 退出锁任务、HOME/最近任务键恢复、桌面基线恢复 |

### 2.5 恢复基线（测试结束）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-B2-01 清理探针 | `./send_test_command.sh SetUninstallBlocked packageName=com.hmdm.lockprobe canUninstall=true`（解除白名单锁定）；`adb uninstall com.hmdm.lockprobe`；`pm list packages \| grep lockprobe` | 卸载成功、无残留 |
| TC-B2-02 策略复位 | `SetLockTaskPackages 'packageNames=["com.hmdm.testapp"]'`；`SetApplicationHidden packageName=com.hmdm.lockprobe hidden=false`（如未恢复）；`cmd package set-home-activity com.syriusrobotics.platform.launcher/.MainActivity` | 名单/隐藏/默认桌面与基线一致 |
| TC-B2-03 部署校验 | `adb shell dpm list-owners`；`./send_test_command.sh GetConnectionStatus` | DeviceOwner,Affiliated 在位；bound=true |

## 3. 硬件受限测试说明

- ASR-0018/0024/0025/0347/0348 均无硬件依赖；上述用例全部在本机（Android 13 / API 33 userdebug，平台签名 + device owner）执行通过（2026-08-13）。
- 锁任务探针 `com.hmdm.lockprobe` 为测试素材（工具脚本构建，Activity onCreate 调 `startLockTask()`），非交付物；锁任务验证需真实锁任务模式（mLockTaskModeState=LOCKED），无该机制的 ROM 无法验证 HOME/RECENT 键禁用效果。
- 测试中记录的 ROM 特性：锁任务期间通知栏不可收起、退出后 HOME 可能弹选择器（多 HOME 候选）、`cmd package set-home-activity` 恢复默认桌面——已如实写入设计文档第 2/7/8 节。
