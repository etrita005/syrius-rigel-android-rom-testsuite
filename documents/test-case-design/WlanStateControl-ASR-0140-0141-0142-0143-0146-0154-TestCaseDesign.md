# WLAN 状态管控（ASR-0140/0141/0142/0143/0146/0154）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner，testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root`、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`；
- 对照命令：`adb logcat -d | grep setWifiEnabled`（WifiService 日志：`setWifiEnabled package=com.hmdm.launcher uid=1000 enable=true isPrivileged=true`）、`adb shell dumpsys device_policy`；
- 恢复基线：WLAN 状态恢复测试前（本机关闭）、`SetUserConfigWifiDisabled disabled=false`；
- 本 ROM 特性（2026-08-13 核验）：① `setWifiEnabled` 异步生效（约 10~20s 才就绪，命令返回 true 后需等待再核对）；② `GetWifiMac`（dpm 通道）在 WLAN 关闭时仍返回真实 MAC，`WifiManager.getConnectionInfo().getMacAddress()` 关闭时为匿名 02:00:00:00:00:00；③ ASR-0143 强制打开为服务器 config 驱动（`StatusControlService` 周期纠正），无独立命令——本机无 config 时纠正循环不激活，测试验证底层 SetWifiOpen 通道并如实记录。

## 2. 测试用例表

### 2.1 ASR-0140/0142 禁用/启用、打开/关闭 WLAN（SetWifiOpen / IsWifiOpen）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0140-01 基线 | `./send_test_command.sh WifiStateLocal`；`IsWifiOpen` | 记录当前状态（本机 enabled=false） |
| TC-0140-02 打开 | `./send_test_command.sh SetWifiOpen open=true`；等待 20s；`IsWifiOpen`；`WifiStateLocal`；`adb logcat -d \| grep setWifiEnabled` | RESULT=true；20s 后 IsWifiOpen=true、本地探针 enabled=true（**异步生效**）；WifiService 日志 enable=true isPrivileged=true |
| TC-0140-03 关闭 | `SetWifiOpen open=false`；等待 10s；`IsWifiOpen`；`WifiStateLocal` | RESULT=true；IsWifiOpen=false（WifiService enable=false 日志） |
| TC-0140-04 反复开关（幂等） | `SetWifiOpen open=true`、等待、`IsWifiOpen`、再 `open=false` | 各次调用正常、状态跟随 |
| TC-0140-05 缺参 | `./send_test_command.sh SetWifiOpen` | 返回 `missing parameter: open`，不 crash |

### 2.2 ASR-0146 查询 WLAN MAC（GetWifiMac）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0146-01 查询（WLAN 关闭时） | `./send_test_command.sh GetWifiMac`；`./send_test_command.sh WifiStateLocal` | **dpm 通道返回 null**（2026-08-14 复核：dpm.getWifiMacAddress 在 WLAN 关闭时不可用）、本地探针 mac 为 02:00:00:00:00:00（匿名）——如实记录 |
| TC-0146-02 查询（WLAN 开启时） | `SetWifiOpen open=true`、等待就绪；`GetWifiMac` | 返回真实 MAC（本机 00:08:22:a0:8b:03） |

### 2.3 ASR-0141/0154 禁止/允许用户配置 WLAN（Set/IsUserConfigWifiDisabled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0141-01 基线 | `./send_test_command.sh IsUserConfigWifiDisabled` | false |
| TC-0141-02 禁止 | `./send_test_command.sh SetUserConfigWifiDisabled disabled=true`；`IsUserConfigWifiDisabled` | RESULT=true；Is=true（DISALLOW_CONFIG_WIFI；设置页 "Blocked by your IT admin" 为 WlanControl 批次已核验效果） |
| TC-0141-03 恢复 | `SetUserConfigWifiDisabled disabled=false`；`IsUserConfigWifiDisabled` | true；Is=false |
| TC-0141-04 缺参 | `./send_test_command.sh SetUserConfigWifiDisabled` | 返回 `missing parameter: disabled`，不 crash |

### 2.4 ASR-0143 强制打开 WLAN（config 驱动，文档化）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0143-01 机制说明 | 查看 `service/StatusControlService.java`（config.getWifi() 周期纠正）与设计文档第 2/7 节 | 强制打开为服务器 config 驱动：config.wifi=true 时周期纠正 WLAN 开启；本机无服务器 config，纠正循环不激活——如实记录 |
| TC-0143-02 底层通道 | `SetWifiOpen open=true` + 等待核对（同 TC-0140-02） | 强制打开所依赖的执行通道（setWifiEnabled）可用（已验证） |

### 2.5 恢复基线（测试结束）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-B7-01 恢复 | `SetWifiOpen open=false`（如基线为关）；`SetUserConfigWifiDisabled disabled=false`；`adb shell dpm list-owners` | WLAN 状态与限制复位；DO 在位 |

## 3. 硬件受限测试说明

- ASR-0140/0141/0142/0143/0146/0154 无硬件依赖（WLAN 芯片本机可用）；上述用例全部在本机执行（2026-08-13）。
- ASR-0143 强制打开的周期纠正依赖服务器 config（wifi 字段）下发，本测试环境无服务器 config，仅验证底层执行通道并文档化机制；真实强制场景需服务器配置环境或按 ForceOpen 命令式实现（其他功能批次先例）补齐独立命令后验证。
- `GetWifiMac` 与本地 WifiManager 探针的 MAC 通道差异（dpm 真实 MAC vs 匿名 MAC）已如实记录。
