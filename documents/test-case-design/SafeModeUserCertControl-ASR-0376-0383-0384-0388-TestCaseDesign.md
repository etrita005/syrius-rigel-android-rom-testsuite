# 安全模式/多用户/证书管控（ASR-0376/0383/0384/0388）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner，testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root`、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`；
- 测试素材：`/sdcard/MDM/isrg_root_x1.pem`（Launcher assets 预置的真实根证书）；
- 对照命令：`adb shell dumpsys device_policy`（用户限制）、`adb shell ls /data/misc/apexdata/com.android.conscrypt/cacerts-added`、`adb shell ls /data/misc/user/0/cacerts-added`（root，用户 CA 存储）、`adb shell dumpsys keystore`；
- 恢复基线：限制全部复位（IsSafeModeDisabled=false、IsDisallowMulUser=false）、DO 在位；
- 本 ROM 特性（2026-08-13 核验）：**dpm.installCaCert 接受调用但不落盘**（用户 CA 存储无新文件、keystore 无写入）——命令写后读回如实上报，ASR-0388 维持部分完成（ROM 适配后验证）。

## 2. 测试用例表

### 2.1 ASR-0376 禁用安全模式（Set/IsSafeModeDisabled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0376-01 基线 | `./send_test_command.sh IsSafeModeDisabled` | false |
| TC-0376-02 禁止 | `./send_test_command.sh SetSafeModeDisabled disabled=true`；`IsSafeModeDisabled` | RESULT=true；Is=true（DISALLOW_SAFE_BOOT） |
| TC-0376-03 恢复 | `SetSafeModeDisabled disabled=false`；`IsSafeModeDisabled` | true；Is=false（往返一致，无残留） |
| TC-0376-04 缺参 | `./send_test_command.sh SetSafeModeDisabled` | 返回 `missing parameter: disabled`，不 crash |

### 2.2 ASR-0383/0384 禁止添加多用户/入口显示（DisallowMulUser / IsDisallowMulUser）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0383-01 基线 | `./send_test_command.sh IsDisallowMulUser` | false |
| TC-0383-02 禁止 | `./send_test_command.sh DisallowMulUser disallow=true`；`IsDisallowMulUser` | RESULT=true；Is=true（DISALLOW_ADD_USER + DISALLOW_USER_SWITCH——添加入口与切换入口均受限） |
| TC-0383-03 恢复 | `DisallowMulUser disallow=false`；`IsDisallowMulUser` | true；Is=false |
| TC-0384-01 入口联动 | 同 TC-0383-02（DISALLOW_USER_SWITCH 隐藏切换入口） | 限制生效（入口隐藏由系统消费） |
| TC-0383-04 缺参 | `./send_test_command.sh DisallowMulUser` | 返回 `missing parameter: disallow`，不 crash |

### 2.3 ASR-0388 安装用户证书（InstallCaCert）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0388-01 准备 | `adb shell mkdir -p /sdcard/MDM`；`adb push <assets>/isrg_root_x1.pem /sdcard/MDM/` | 文件就绪 |
| TC-0388-02 安装（本 ROM 限制） | `./send_test_command.sh InstallCaCert certPath=/sdcard/MDM/isrg_root_x1.pem`；root 检查 `ls /data/misc/apexdata/com.android.conscrypt/cacerts-added`、`ls /data/misc/user/0/cacerts-added`、`dumpsys keystore` | 命令返回 {success:false, verified:false, note=本 ROM 不落盘}（dpm.installCaCert 接受但用户 CA 存储无新文件、keystore 无写入）——**维持部分完成（ROM 适配后验证）** |
| TC-0388-03 文件不存在 | `InstallCaCert certPath=/sdcard/MDM/nonexistent.pem` | {success:false, error:cert file not found}，不 crash |
| TC-0388-04 缺参 | `./send_test_command.sh InstallCaCert` | 返回 `missing parameter: certPath`，不 crash |

### 2.4 恢复基线（测试结束）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-B17-01 恢复 | `SetSafeModeDisabled disabled=false`；`DisallowMulUser disallow=false`；`adb shell dpm list-owners`；`GetConnectionStatus` | 限制复位；DO 在位；bound=true |

## 3. 硬件受限测试说明

- ASR-0376/0383/0384 无硬件依赖，上述用例全部在本机执行通过（2026-08-13）。
- **ASR-0388 受限说明**：本 ROM DPMS 接受 dpm.installCaCert 调用但不持久化任何证书文件（用户 CA 存储/keystore 均无写入，root 检查证实）——与 setStatusBarDisabled 同类 MTK fork 行为；命令已写后读回如实上报（success=false + note），需 ROM 适配后验证；真实证书信任生效验证（TLS 握手）亦依赖该存储落盘。
