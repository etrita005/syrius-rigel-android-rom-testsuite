# USB 模式/adb/开发者选项管控（ASR-0192/0194/0200/0201/0202）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner，testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root`、屏幕常亮（`svc power stayon true`）；**本批次测试可能切断 adb——需要设备可触屏操作 testapp UI（恢复通道）**；
- 测试通道：`./send_test_command.sh <event> key=value ...`；恢复通道：testapp UI 按钮（与 IPC 同引擎）——"Show Developer Options Entry"、"Enable USB Debugging (adb)"；
- 对照命令：`adb shell settings get global adb_enabled`、`adb shell settings get global default_usb_configuration`、`adb shell dumpsys package com.android.settings | grep DevelopmentSettings`、宿主机 `lsusb`/`/sys/bus/usb/devices/1-13/1-13:1.*/bInterfaceClass`（USB 接口核对）；
- 恢复基线：adb_enabled=1、default_usb_configuration 未设置（-1）、开发者选项入口可见、DO 在位；
- 本 ROM 特性（2026-08-13/08-14 核验）：① `SetAdbEnabled(false)` 与 `SetDevOptSettingEnterHidden(true)`（DISALLOW_DEBUGGING_FEATURES）都会真实切断 adb（USB 复合功能移除、接口仅剩 PTP），且持久化、重启后重新生效；② 恢复必须经设备侧动作（testapp UI 按钮），IPC 通道自身不可用；③ 用户重启设备无法自行恢复 adb（限制开机重新生效）——必须以 UI 恢复。

## 2. 测试用例表

### 2.1 ASR-0192 设置 USB 默认模式（Set/GetDefaultUsbMode）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0192-01 基线 | `./send_test_command.sh GetDefaultUsbMode`；`adb shell settings get global default_usb_configuration` | -1（键未设置） |
| TC-0192-02 设置 | `./send_test_command.sh SetDefaultUsbMode usbMode=1`；`GetDefaultUsbMode`；`settings get global default_usb_configuration` | RESULT=true；Get=1；settings get=1（三方一致） |
| TC-0192-03 改值 | `SetDefaultUsbMode usbMode=2`；`GetDefaultUsbMode` | Get=2（覆盖写入） |
| TC-0192-04 恢复 | `adb shell settings delete global default_usb_configuration`；`GetDefaultUsbMode` | -1（恢复基线） |
| TC-0192-05 非法值 | `SetDefaultUsbMode usbMode=-1` | RESULT=false，不 crash |
| TC-0192-06 缺参 | `./send_test_command.sh SetDefaultUsbMode` | 返回 `missing parameter: usbMode`，不 crash |

### 2.2 ASR-0200 管控开发者选项入口（Set/IsDevOptSettingEnterHidden）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0200-01 基线 | `./send_test_command.sh IsDevOptSettingEnterHidden`；`UsbDevOptionsStateLocal` | false；组件 default(0) |
| TC-0200-02 隐藏（含 adb 副作用） | `./send_test_command.sh SetDevOptSettingEnterHidden hidden=true` | **adb 断连**（DISALLOW_DEBUGGING_FEATURES 生效：USB 接口仅剩 PTP、`adb devices` 清空）——隐藏效果与副作用一并确认；命令 RESULT 可能因连接中断无法返回（如实记录） |
| TC-0200-03 恢复（设备侧） | 用户重启设备 → adb 仍不可达（限制开机重新生效）→ **testapp UI："Show Developer Options Entry"** | adb 恢复；`IsDevOptSettingEnterHidden`=false；组件 default(0)；限制清除 |
| TC-0200-04 缺参 | （恢复后）`./send_test_command.sh SetDevOptSettingEnterHidden` | 返回 `missing parameter: hidden`，不 crash |

### 2.3 ASR-0194/0201/0202 禁用/启用 USB 调试（Set/IsAdbEnabled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0194-01 基线 | `./send_test_command.sh IsAdbEnabled`；`adb shell settings get global adb_enabled` | true；1 |
| TC-0194-02 禁用（adb 断连） | `./send_test_command.sh SetAdbEnabled enabled=false` | adb_enabled=0 写入；**adb 断连**（USB 功能移除、接口仅剩 PTP）——"禁用 adb 端口"预期语义（2026-08-13 真机确认） |
| TC-0194-03 恢复（设备侧） | **testapp UI："Enable USB Debugging (adb)"** | adb 恢复；`IsAdbEnabled`=true；`settings get global adb_enabled`=1 |
| TC-0194-04 查询 | `./send_test_command.sh IsAdbEnabled`；`UsbDevOptionsStateLocal` | true；adbEnabled=true（探针一致） |
| TC-0194-05 缺参 | `./send_test_command.sh SetAdbEnabled` | 返回 `missing parameter: enabled`，不 crash |

### 2.4 恢复基线（测试结束）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-B9-01 恢复 | `SetAdbEnabled enabled=true`（如非 1）；`adb shell settings delete global default_usb_configuration`；`SetDevOptSettingEnterHidden hidden=false`（如隐藏）；`adb shell dpm list-owners`；`GetConnectionStatus` | adb_enabled=1、usb 模式未设置、开发者选项可见；DO 在位；bound=true |

## 3. 硬件受限测试说明

- ASR-0192/0194/0200/0201/0202 无硬件依赖（USB 与触摸屏本机可用）；上述用例全部在本机执行（2026-08-13/08-14）。
- **本批次为破坏性连接类测试**：禁用 adb（0194）与隐藏开发者选项（0200，DISALLOW_DEBUGGING_FEATURES 副作用）都会切断 adb 且持久化（重启后重新生效）——测试必须配合设备侧 UI 恢复通道（testapp 按钮，与 IPC 同引擎）；本批次实际经用户 UI 操作恢复两次，设备全程无 wipe。
- 测试中发现的实现行为（adb 副作用、重启持久化、UI 恢复路径）已如实写入设计文档第 2/7/8 节与需求文档备注。
