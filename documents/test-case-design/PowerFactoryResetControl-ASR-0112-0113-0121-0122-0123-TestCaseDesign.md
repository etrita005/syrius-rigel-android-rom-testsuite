# 电源与恢复出厂管控（ASR-0112/0113/0121/0122/0123）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner，testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root`、屏幕常亮（`svc power stayon true`）；测试前确认 USB 连接稳定（关机后依赖接电自动开机）；
- 测试通道：`./send_test_command.sh <event> key=value ...`；
- 对照命令：`adb shell dpm list-owners`、`adb shell getprop sys.boot_completed`、`adb devices`；
- **批次内顺序**（2026-08-13 计划）：0121/0122（安全）→ 0113 强制重启（本批最后）→ 0112 强制关机（0113 之后单独安排）；**0123 恢复出厂不属本批**——为全部 19 批全局最后一步；
- 恢复基线：用户恢复出厂限制复位（disabled=false）、DO 在位、testapp 在线；
- 本 ROM 特性（2026-08-13 核验）：强制关机后保持 USB 连接约 1 分钟内自动上电开机（ASR-0439 定制特性）；重启/关机不丢失 DO。

## 2. 测试用例表

### 2.1 ASR-0121/0122 用户恢复出厂限制（Set/IsUserPerformFactoryResetDisabled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0121-01 基线 | `./send_test_command.sh IsUserPerformFactoryResetDisabled` | false |
| TC-0121-02 禁止 | `./send_test_command.sh SetUserPerformFactoryResetDisabled disabled=true`；`IsUserPerformFactoryResetDisabled` | RESULT=true；Is=true（DISALLOW_FACTORY_RESET + NETWORK_RESET + APPS_CONTROL 写入） |
| TC-0121-03 恢复 | `SetUserPerformFactoryResetDisabled disabled=false`；`IsUserPerformFactoryResetDisabled` | true；Is=false（往返一致，无残留） |
| TC-0121-04 缺参 | `./send_test_command.sh SetUserPerformFactoryResetDisabled` | 返回 `missing parameter: disabled`，不 crash |

### 2.2 ASR-0113 强制重启（本批最后）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0113-01 重启 | `./send_test_command.sh Reboot` | RESULT=true（或 adb Broken pipe——设备重启为预期）；设备立即重启 |
| TC-0113-02 重启后验证 | `adb wait-for-device`；轮询 `getprop sys.boot_completed`=1；`adb shell dpm list-owners`；`./send_test_command.sh GetConnectionStatus` | boot 完成；`DeviceOwner,Affiliated` 保持（device owner 重启不丢失）；bound=true、IPC 恢复 |

### 2.3 ASR-0112 强制关机（0113 之后单独安排）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0112-01 关机 | `./send_test_command.sh PowerOff` | RESULT=true；设备断电（`adb devices` 列表清空） |
| TC-0112-02 自动开机与恢复 | 保持 USB 连接等待自动上电（本 ROM 接电自动开机，ASR-0439 特性；约 1 分钟内）；`adb wait-for-device`；`getprop sys.boot_completed`；`dpm list-owners`；`GetConnectionStatus` | 设备自动开机、boot 完成；DO 保持；bound=true（2026-08-13 实测通过） |

### 2.4 ASR-0123 执行恢复出厂（全局最后，不在本批）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0123-01 门禁 | `./send_test_command.sh PerformFactoryReset` | 返回门禁提示（destructive action: ASR-0123 runs as the global last step...）——不通过 IPC 随意触发 |
| TC-0123-02 正式执行（全部批次完成后） | 按全局收尾计划执行 `PerformFactoryReset`（wipe） | 设备恢复出厂；随后按 AGENTS.md 重新部署：装 Launcher → `dpm set-device-owner com.hmdm.launcher/.AdminReceiver` → 验证 `dpm list-owners` → 装 testapp → 复验关键基线（如 ASR-0186 截屏禁用等 1~2 项） |

### 2.5 恢复基线（本批结束）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-B5-01 部署校验 | `adb shell dpm list-owners`；`GetConnectionStatus`；`IsUserPerformFactoryResetDisabled` | DO 在位；bound=true；限制复位 false |

## 3. 硬件受限测试说明

- ASR-0112/0113/0121/0122 无硬件依赖，上述用例全部在本机执行通过（2026-08-13，强制关机后依赖本 ROM 接电自动开机特性恢复，已实测）。
- ASR-0123 为破坏性测试，安排在全部 19 批的最后（全局最后一步），执行 wipe 后按 AGENTS.md 重新部署设备基线。
