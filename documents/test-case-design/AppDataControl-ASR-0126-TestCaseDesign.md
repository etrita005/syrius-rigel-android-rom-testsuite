# 清除应用数据（ASR-0126）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner，testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root`、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`；
- 测试目标：testapp 自身（数据探针方案）与探针包 `com.hmdm.probe`（跨包方案）；**禁止对 Launcher 执行**；
- 对照命令：`adb shell ls /data/user/0/<pkg>/`（root）、`adb shell pm list packages`；
- 恢复基线：探针包卸载、testapp 数据清除后状态无害、DO 在位；
- 本 ROM 特性（2026-08-13 核验）：clearApplicationUserData 完成回调不触发（与 deleteApplicationCacheFiles/deletePackage 同款缺陷）——命令以读回核验兜底（success=true 依据 verified）；清除自身（testapp）时目标进程被杀、IPC 无返回值属预期。

## 2. 测试用例表

### 2.1 ASR-0126 清除应用数据（ClearAppData）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0126-01 写数据探针 | `./send_test_command.sh WriteDataProbe value=clear-me`；`ReadDataProbe` | probe.txt 写入 /data/user/0/com.hmdm.testapp/files/，读回 exists=true、value=clear-me |
| TC-0126-02 清除自身数据 | `./send_test_command.sh ClearAppData packageName=com.hmdm.testapp` | 目标进程被杀（IPC 可能无返回值，预期）；随后 `./send_test_command.sh ReadDataProbe` → exists=false（数据已清除）；`adb root` 后 `ls /data/user/0/com.hmdm.testapp/files/` 不存在 |
| TC-0126-03 跨包清除（探针） | 安装探针 `adb install -r /tmp/kilo/probe-hmdm.apk`、`am start -n com.hmdm.probe/.Stub`（建立数据目录）、root 写入 probe.txt；`./send_test_command.sh ClearAppData packageName=com.hmdm.probe`；`adb shell ls /data/data/com.hmdm.probe/` | RESULT={success:true, verified:true, cleared:false}（本 ROM 不回调、读回确认）；数据目录子目录（files/databases/shared_prefs/cache/code_cache）全部消失、目录为空 |
| TC-0126-04 缺参 | `./send_test_command.sh ClearAppData` | 返回 `missing parameter: packageName`，不 crash |
| TC-0126-05 包不存在 | `ClearAppData packageName=com.nonexistent.xyz` | success=false + error（启动失败/无读回），不 crash |

### 2.2 恢复基线（测试结束）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-B6-01 清理探针 | `./send_test_command.sh SetUninstallBlocked packageName=com.hmdm.probe canUninstall=true`；`adb uninstall com.hmdm.probe` | 探针卸载 |
| TC-B6-02 部署校验 | `adb shell dpm list-owners`；`./send_test_command.sh GetConnectionStatus`；`ReadDataProbe` | DO 在位；bound=true；探针文件不存在（清除后状态） |

## 3. 硬件受限测试说明

- ASR-0126 无硬件依赖；上述用例全部在本机（Android 13 / API 33 userdebug，平台签名 + device owner）执行通过（2026-08-13）。
- 本 ROM 清除回调不触发属框架缺陷（与 ASR-0127/0004 同款），命令以读回核验兜底并如实上报（cleared/verified 双字段）；对 Launcher 的清除被明确禁止（DO 应用）。
