# 权限授予管控（ASR-0037/0042）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner，testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root`、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`；
- 测试目标包：testapp 自身（com.hmdm.testapp，声明了 CAMERA/RECORD_AUDIO/POST_NOTIFICATIONS 等运行时权限）；
- 对照命令：`adb shell cmd appops get com.hmdm.testapp SYSTEM_ALERT_WINDOW`、`adb shell dumpsys package com.hmdm.testapp | grep -A2 SYSTEM_ALERT_WINDOW`、`adb shell am start -a android.settings.action.MANAGE_OVERLAY_PERMISSION`（Settings 悬浮窗界面，uiautomator 核对开关）；
- 恢复基线：记录测试前 testapp 权限状态并在文档标注（本机基线：CAMERA/RECORD_AUDIO 已授予、POST_NOTIFICATIONS 未授予）；授予后权限为 policy-fixed（`pm revoke` 被拒），恢复需 DO 重置或卸载重装——如实记录；
- 本 ROM 特性（2026-08-13 核验）：**AppOpsService 忽略 SYSTEM_ALERT_WINDOW 的 setMode 写入（应用反射/shell/Settings 界面三通道均无效，强制执行状态保持 default，真实悬浮窗被 BadTokenException 拒绝）**——ASR-0042 维持部分完成（命令面完成、如实读回，ROM 适配 op 写入后验证），与 ASR-0040 同类限制。

## 2. 测试用例表

### 2.1 ASR-0037 授予应用运行时权限（GrantAllRuntimePermission）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0037-01 基线 | `./send_test_command.sh CheckPermissionsLocal` | 记录 CAMERA/RECORD_AUDIO/POST_NOTIFICATIONS 当前状态（本机：true/true/false） |
| TC-0037-02 批量授予 | `./send_test_command.sh GrantAllRuntimePermission packageName=com.hmdm.testapp`；等待 2s；`CheckPermissionsLocal` | RESULT=true；POST_NOTIFICATIONS 变 true（CAMERA/RECORD_AUDIO 保持 true）——免交互授予生效 |
| TC-0037-03 授予持久性（policy-fixed） | `adb shell pm revoke com.hmdm.testapp android.permission.POST_NOTIFICATIONS` | 报 `SecurityException: Cannot revoke policy fixed permission`——授予为 DPM policy-fixed（预期；恢复需 DO setPermissionGrantState(DEFAULT) 或卸载重装，如实记录） |
| TC-0037-04 缺参 | `./send_test_command.sh GrantAllRuntimePermission` | 返回 `missing parameter: packageName`，不 crash |
| TC-0037-05 包不存在 | `GrantAllRuntimePermission packageName=com.nonexistent.xyz` | 返回 false（引擎异常捕获），不 crash |

### 2.2 ASR-0042 悬浮窗权限（GrantOverlay / CheckGrantOverlay）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0042-01 基线 | `./send_test_command.sh CheckGrantOverlay packageName=com.hmdm.testapp`；`adb shell cmd appops get com.hmdm.testapp SYSTEM_ALERT_WINDOW` | false；状态 default |
| TC-0042-02 命令写后读回（本 ROM 限制） | `./send_test_command.sh GrantOverlay packageName=com.hmdm.testapp`；`CheckGrantOverlay`；`cmd appops get` | GrantOverlay 返回 {success=false, granted=false, note=本 ROM 写入未生效说明}（写后读回如实上报）；CheckGrantOverlay=false；appops 仍 default 且 rejectTime 更新——**免交互授予在本 ROM 无法实现（AppOps 写忽略）** |
| TC-0042-03 shell 通道对照 | `adb shell cmd appops set com.hmdm.testapp SYSTEM_ALERT_WINDOW allow`；`cmd appops get` | 状态保持 default（shell 通道同样被忽略） |
| TC-0042-04 Settings 用户界面对照 | `adb shell am start -a android.settings.action.MANAGE_OVERLAY_PERMISSION -d "package:com.hmdm.testapp"`；uiautomator dump 核对开关；`TryShowOverlay` 真实悬浮窗 | Settings 开关显示开启（checked=true）但强制执行状态仍 default（界面与执行脱钩，2026-08-13 实测）；`TryShowOverlay` → `BadTokenException: permission denied for window type 2038`（真实悬浮窗被框架按 AppOps 拒绝） |
| TC-0042-05 缺参 | `./send_test_command.sh GrantOverlay` | 返回 `missing parameter: packageName`，不 crash |

### 2.3 恢复基线（测试结束）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-B3-01 权限状态记录 | `./send_test_command.sh CheckPermissionsLocal`；`CheckGrantOverlay` | 如实记录：POST_NOTIFICATIONS 保持 granted（policy-fixed，无害）；悬浮窗 default |
| TC-B3-02 部署校验 | `adb shell dpm list-owners`；`./send_test_command.sh GetConnectionStatus`；屏幕状态（本机出现通知栏卡住现象时经电源键+唤醒恢复） | DO 在位；bound=true；屏幕正常 |

## 3. 硬件受限测试说明

- ASR-0037/0042 无硬件依赖；上述用例全部在本机（Android 13 / API 33 userdebug，平台签名 + device owner）执行（2026-08-13）。
- **ASR-0042 受限说明**：本 ROM AppOpsService 忽略 SYSTEM_ALERT_WINDOW 的 setMode 写入（应用反射/shell/Settings 界面三通道均无效，真实悬浮窗被拒），与 ASR-0040（MANAGE_EXTERNAL_STORAGE）同类 ROM 限制——免交互授予需 ROM 适配 op 写入能力后验证；命令与引擎已实现并如实读回上报（GrantOverlay success=false + note），需求维持部分完成。Settings 界面开关显示"开启"但强制执行脱钩的现象已记录，验收以真实悬浮窗（TryShowOverlay）与 AppOps 读回为准。
