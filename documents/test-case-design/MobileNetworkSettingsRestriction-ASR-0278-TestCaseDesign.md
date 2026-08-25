# 移动网络设置限制（ASR-0278）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner，testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root`、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`（**注意：本组命令参数名为 `disallow`**）；
- 对照命令：`adb shell dumpsys device_policy`（DISALLOW_CONFIG_MOBILE_NETWORKS）；
- 恢复基线：限制复位（Is=false）、DO 在位。

## 2. 测试用例表

### 2.1 ASR-0278 禁止/允许用户改变移动网络设置（DisallowConfigMobileNetworks / IsDisallowConfigMobileNetworks）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0278-01 基线 | `./send_test_command.sh IsDisallowConfigMobileNetworks` | false |
| TC-0278-02 禁止 | `./send_test_command.sh DisallowConfigMobileNetworks disallow=true`；`IsDisallowConfigMobileNetworks` | RESULT=true；Is=true（DISALLOW_CONFIG_MOBILE_NETWORKS；设置页移动网络受限） |
| TC-0278-03 恢复 | `DisallowConfigMobileNetworks disallow=false`；`IsDisallowConfigMobileNetworks` | true；Is=false（往返一致，无残留） |
| TC-0278-04 缺参 | `./send_test_command.sh DisallowConfigMobileNetworks` | 返回 `missing parameter: disallow`，不 crash |
| TC-B12-01 部署校验 | `adb shell dpm list-owners`；`GetConnectionStatus` | DO 在位；bound=true |

## 3. 硬件受限测试说明

- ASR-0278 无硬件依赖（限制类，与 SIM/网络硬件无关）；上述用例全部在本机执行通过（2026-08-13）。
- 参数名注意：命令参数为 `disallow`（testapp 事件首版误用 disabled 导致限制未生效，已修正并记录——见设计文档第 2 节）。
