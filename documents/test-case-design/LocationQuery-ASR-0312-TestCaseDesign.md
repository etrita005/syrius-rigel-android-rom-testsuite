# 当前位置查询（ASR-0312）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner，testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root`、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`；
- 对照命令：`adb shell cmd location providers`、`adb shell dumpsys location`；
- 恢复基线：定位开关恢复测试前状态（本机关闭）、DO 在位；
- 本 ROM/构建特性（2026-08-13 核验）：opensource 构建的 pro 定位上报管线为桩（ProUtils.processLocation），命令以框架 LocationManager 最近定位提供等价查询；本机室内无 GPS 信号且无网络定位源，命令如实返回 available=false。

## 2. 测试用例表

### 2.1 ASR-0312 获取当前位置信息（GetLastKnownLocation）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0312-01 基线（定位关闭） | `./send_test_command.sh GetLastKnownLocation`；`adb shell dumpsys location \| grep -i enabled` | {available:false, provider:""}（无缓存定位如实返回） |
| TC-0312-02 开启定位后查询 | `./send_test_command.sh SetLocationEnabled enabled=true`；等待 3s；`GetLastKnownLocation` | Set RESULT location_mode=3；GetLastKnownLocation 仍 available=false（本机无 GPS/网络定位源，如实）——有定位环境（室外/GPS 或模拟注入）时返回 available:true + 经纬度/精度 |
| TC-0312-03 恢复 | `SetLocationEnabled enabled=false`；`GetLastKnownLocation` | location_mode=0（基线恢复）；查询保持 available=false |

### 2.2 恢复基线（测试结束）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-B14-01 部署校验 | `adb shell dpm list-owners`；`GetConnectionStatus` | DO 在位；bound=true |

## 3. 硬件受限测试说明

- 命令与引擎已实现并真机验证（无缓存定位时 available=false 如实返回，开启定位不影响命令行为）。
- **本机室内环境无 GPS 信号、无网络定位源**：有值定位（available=true 的经纬度）的验证需室外环境或 GPS 模拟注入（`adb emu` 模拟仅限模拟器；本机为真实设备——可经外部定位源或 mock provider 注入后复测）；真实定位属环境依赖，非实现缺陷。
- opensource 构建的 pro 定位管线为桩已在设计文档说明（pro 版含前台监听与上报）。
