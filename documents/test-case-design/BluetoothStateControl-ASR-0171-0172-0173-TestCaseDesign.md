# 蓝牙状态管控（ASR-0171/0172/0173）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner，testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root`、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`；
- 对照命令：`adb shell settings get global bluetooth_on`、`adb shell dumpsys bluetooth_manager | grep -E "state|mEnable"`；
- 恢复基线：蓝牙状态恢复测试前（本机关闭）；
- 本 ROM 特性（2026-08-13 核验）：① 蓝牙开关切换约 3~5s 生效，测试需等待后核对；② ASR-0173 强制打开为服务器 config 驱动（Initializer 注册期 + StatusControlService 周期纠正），无独立命令——本机无 config 时纠正循环不激活，测试验证底层 SetBlueOpen 通道并如实记录。

## 2. 测试用例表

### 2.1 ASR-0171/0172 禁用/启用、打开/关闭蓝牙（SetBlueOpen / IsBlueOpen）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0171-01 基线 | `./send_test_command.sh IsBlueOpen`；`GetBluetoothStateLocal`；`adb shell settings get global bluetooth_on` | 记录当前状态（本机 false/off） |
| TC-0171-02 打开 | `./send_test_command.sh SetBlueOpen open=true`；等待 5s；`IsBlueOpen`；`GetBluetoothStateLocal` | RESULT=true；IsBlueOpen=true；探针 bluetoothEnabled=true、scanMode=connectable、bondedDevices=[]（双通道一致） |
| TC-0171-03 关闭 | `SetBlueOpen open=false`；等待 5s；`IsBlueOpen`；`GetBluetoothStateLocal` | RESULT=true；IsBlueOpen=false；探针 bluetoothEnabled=false |
| TC-0171-04 反复开关（幂等） | `SetBlueOpen open=true` → 等待核对 → `open=false` → 等待核对 | 各次调用正常、状态跟随 |
| TC-0171-05 缺参 | `./send_test_command.sh SetBlueOpen` | 返回 `missing parameter: open`，不 crash |

### 2.2 ASR-0173 强制打开蓝牙（config 驱动，文档化）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0173-01 机制说明 | 查看 `helper/Initializer.java:237-251`（applyEarlyNonInteractivePolicies：config.getBluetooth() 注册期一次性设置）与 `StatusControlService` 周期纠正；对照设计文档第 2/7 节 | 强制打开为服务器 config 驱动，无独立命令；本机无服务器 config，纠正循环不激活——如实记录 |
| TC-0173-02 底层通道 | `SetBlueOpen open=true` + 等待核对（同 TC-0171-02） | 强制打开所依赖的执行通道（BluetoothAdapter.enable）可用（已验证） |

### 2.3 恢复基线（测试结束）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-B8-01 恢复 | `SetBlueOpen open=false`（如基线为关）；`adb shell dpm list-owners` | 蓝牙恢复关闭；DO 在位 |

## 3. 硬件受限测试说明

- ASR-0171/0172/0173 无硬件依赖（本机蓝牙芯片可用，supported=true）；上述用例全部在本机执行（2026-08-13）。
- ASR-0173 强制打开的周期纠正依赖服务器 config（bluetooth 字段）下发，本测试环境无服务器 config，仅验证底层执行通道并文档化机制。
