# 音量设置与静音管控（ASR-0389/0392/0394/0396）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner，testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root`、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`；
- 对照命令：`adb shell dumpsys audio | grep -iE "mute|volume"`（AudioService 状态）；
- 恢复基线：音量 15/15/15（本机）、静音全部解除、DO 在位；
- 流索引：STREAM_MUSIC=3、STREAM_ALARM=4、STREAM_NOTIFICATION=5（AudioManager 常量）；
- 本 ROM 特性（2026-08-13 核验）：MuteAll 实测静音 5/6 流（闹钟流 4 未静音——VolumeManager 实现细节）；音量持久化（测试后恢复）。

## 2. 测试用例表

### 2.1 ASR-0389 手机是否静音（Mute / UnMute / MuteAll / UnMuteAll）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0389-01 基线 | `./send_test_command.sh MuteStateLocal` | 六流均 false |
| TC-0389-02 单流静音 | `./send_test_command.sh Mute streamType=3`；`MuteStateLocal` | RESULT=true；stream_3=true（探针读回一致） |
| TC-0389-03 单流解除 | `./send_test_command.sh UnMute streamType=3`；`MuteStateLocal` | true；stream_3=false |
| TC-0389-04 全部静音 | `./send_test_command.sh MuteAll`；`MuteStateLocal` | RESULT=true；stream_3/5=true、**stream_4（闹钟）=false**（2026-08-13 实测，实现细节） |
| TC-0389-05 全部解除 | `./send_test_command.sh UnMuteAll`；`MuteStateLocal` | true；全部 false（恢复基线） |
| TC-0389-06 缺参 | `./send_test_command.sh Mute` | 返回 `missing parameter: streamType`，不 crash |

### 2.2 ASR-0392/0394/0396 音量设置（SetVolume / GetVolume）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0392-01 基线 | `./send_test_command.sh GetVolume streamType=3` | {current:15, max:15} |
| TC-0392-02 媒体音量 | `./send_test_command.sh SetVolume streamType=3 volume=5`；`GetVolume streamType=3` | RESULT=true；current=5（写后读回一致） |
| TC-0394-01 通知音量 | `SetVolume streamType=5 volume=3`；`GetVolume streamType=5` | true；current=3 |
| TC-0396-01 闹钟音量 | `SetVolume streamType=4 volume=4`；`GetVolume streamType=4` | true；current=4 |
| TC-039X-02 恢复 | `SetVolume streamType=3/5/4 volume=15`；`GetVolume` 逐一核对 | 15/15/15（基线恢复，音量持久化） |
| TC-039X-03 缺参 | `./send_test_command.sh SetVolume streamType=3` | 返回 `missing parameter: streamType/volume`，不 crash |

### 2.3 恢复基线（测试结束）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-B18-01 恢复 | `UnMuteAll`；`SetVolume 3/5/4=15`；`adb shell dpm list-owners` | 静音解除、音量 15/15/15；DO 在位 |

## 3. 硬件受限测试说明

- ASR-0389/0392/0394/0396 无硬件依赖（本机音频子系统可用）；上述用例全部在本机执行通过（2026-08-13）。
- MuteAll 对闹钟流（stream_4）未生效为本实现细节（VolumeManager.muteAll 覆盖范围），已如实记录；单流 Mute(4) 可独立静音闹钟。
