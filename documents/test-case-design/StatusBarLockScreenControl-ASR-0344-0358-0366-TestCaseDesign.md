# 状态栏/锁屏/锁屏密码管控（ASR-0344/0358/0366）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner，testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root`、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`；
- 对照命令：`adb shell dumpsys statusbar`、`adb shell dumpsys window | grep mCurrentFocus`、`adb shell dumpsys lock_settings | grep -E "CredentialType|Quality:"`、`adb shell locksettings get-disabled [--old <pw>]`、`adb shell input keyevent 26`（电源键）；
- 恢复基线：无锁屏密码（CredentialType=None）、keyguard 正常、状态栏正常、DO 在位；
- 本 ROM 特性（2026-08-14 核验）：① 0344 状态栏禁用无机制（DPM 方法缺失/静默丢弃，命令如实 success=false）；② 0358 读方法缺失（以息屏亮屏行为验证）；③ 0366 设置密码经 resetPassword 回退成功（token 未激活）；空密码清除需 token 激活（未激活被拒，恢复经 `locksettings clear --old <pw>`）；④ keyguard 密码键盘 UI 在 uiautomator 不可见（仅时钟）——UI 解锁在测试环境不可行。

## 2. 测试用例表

### 2.1 ASR-0344 禁用系统状态栏（Set/IsStatusBarDisabled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0344-01 基线 | `./send_test_command.sh IsStatusBarDisabled` | {disabled:false}（或 note=读方法缺失） |
| TC-0344-02 禁用（本 ROM 限制） | `./send_test_command.sh SetStatusBarDisabled disabled=true`；`IsStatusBarDisabled`；`adb shell dumpsys statusbar \| grep mDisabled1` | **本 ROM**：命令如实 {success:false, disabled:false, note=DPMS drops/方法缺失}；dumpsys 无变化、状态栏保持显示——**维持部分完成（ROM 适配后验证）** |
| TC-0344-03 恢复 | `SetStatusBarDisabled disabled=false` | 如实返回（基线） |

### 2.2 ASR-0358 打开/关闭/强制关闭锁屏（Set/IsKeyguardDisabled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0358-01 基线 | `./send_test_command.sh IsKeyguardDisabled` | {disabled:false}（或 note） |
| TC-0358-02 关闭锁屏（行为验证） | `./send_test_command.sh SetKeyguardDisabled disabled=true`；`adb shell input keyevent 26`（息屏）→ 26（亮屏）；`dumpsys window \| grep mCurrentFocus`；`dumpsys window \| grep mDreamingLockscreen` | RESULT=true；亮屏**直接回到前台应用**（无 keyguard，mDreamingLockscreen=false）——禁用生效（2026-08-14 实测：焦点=testapp MainActivity） |
| TC-0358-03 恢复 | `SetKeyguardDisabled disabled=false`；息屏/亮屏 | keyguard 恢复出现（行为验证） |
| TC-0358-04 缺参 | `./send_test_command.sh SetKeyguardDisabled` | 返回 `missing parameter: disabled`，不 crash |

### 2.3 ASR-0366 设置锁屏密码（SetLockScreenPassword / IsLockScreenPasswordSet）——破坏性，设置后立即清除

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0366-01 基线 | `./send_test_command.sh IsLockScreenPasswordSet`；`adb shell locksettings get-disabled` | passwordSet=false；get-disabled 正常返回（无凭据要求） |
| TC-0366-02 设置 | `./send_test_command.sh SetLockScreenPassword password=Test1234`；`adb shell dumpsys lock_settings \| grep CredentialType`；`adb shell locksettings get-disabled` | RESULT=true（resetPassword 回退，token 未激活）；CredentialType=Password；get-disabled 要求 --old 凭据（"Credential can't be null or empty"）——密码生效 |
| TC-0366-03 查询（本 ROM 镜像限制） | `./send_test_command.sh IsLockScreenPasswordSet` | passwordSet=false、quality=0（lockscreen.password_type 镜像本 ROM 不更新——如实返回，参考有限；可靠指示为 dumpsys/locksettings） |
| TC-0366-04 清除 | `./send_test_command.sh SetLockScreenPassword password=` | 空密码清除被拒（token 未激活，false）——**恢复路径**：`adb shell locksettings clear --old Test1234` → "Lock credential cleared"；`dumpsys lock_settings` CredentialType=None、get-disabled 恢复正常（2026-08-14 实测） |
| TC-0366-05 缺参 | `./send_test_command.sh SetLockScreenPassword` | 返回 `missing parameter: password`，不 crash |
| TC-0366-06 keyguard 渲染怪癖 | （可选）设置密码后息屏亮屏 + uiautomator | keyguard 仅显示时钟、无密码输入框（本 ROM 渲染怪癖，UI 解锁不可行——如实记录） |

### 2.4 恢复基线（测试结束）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-B16-01 恢复 | `locksettings clear --old Test1234`（如已设）；`SetKeyguardDisabled disabled=false`；`SetStatusBarDisabled disabled=false`；`adb shell dpm list-owners`；`GetConnectionStatus` | 密码清除、keyguard/状态栏基线；DO 在位；bound=true |

## 3. 硬件受限测试说明

- ASR-0344/0358/0366 无硬件依赖；上述用例全部在本机（Android 13 / API 33 userdebug，平台签名 + device owner）执行（2026-08-14）。
- **ASR-0344 受限**：本 ROM 状态栏禁用无机制（DPM setStatusBarDisabled 无效果/读方法缺失）——命令如实上报、维持部分完成，ROM 适配后验证。
- **ASR-0366 密码清除路径**：token 未激活时命令空密码清除被拒——恢复经 shell `locksettings clear --old <已知密码>`（本批次实测）；token 激活（用户 UI 解锁一次）后命令清除路径可用；keyguard 密码键盘 UI 本 ROM 在 uiautomator 不可见（渲染怪癖，记录）。
