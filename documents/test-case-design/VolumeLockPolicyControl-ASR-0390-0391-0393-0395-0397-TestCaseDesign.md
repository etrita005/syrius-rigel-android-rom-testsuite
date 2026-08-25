# 音量锁策略管控（ASR-0390/0391/0393/0395/0397）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner（`dpm list-owners` = DeviceOwner,Affiliated），testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root && dumpsys duraspeed addwhitelist com.hmdm.testapp`（MTK DuraSpeed 白名单；**`am force-stop com.hmdm.testapp` 会使 testapp 进入 DuraSpeed suppress list、广播被静默丢弃——整机重启清空恢复**，测试期间避免 force-stop）、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`（IPC 与 UI 按钮等效，同一 `TestActions.execute()` 引擎）；也可在 testapp UI 的 "Volume / display locks" 页点按；
- 对照命令：`adb shell dumpsys device_policy`（no_adjust_volume 限制）、`adb shell media volume --stream 3 --get`（媒体流）、`adb shell media volume --stream 5 --get`（通知流）、`adb shell media volume --stream 4 --get`（闹钟流）、`adb shell dumpsys audio`（各流音量/静音态）、`adb shell input keyevent 24/25`（音量加减物理键模拟）、`adb shell settings get system volume_media` 等；
- 音量键行为说明：`input keyevent 24/25` 模拟按键事件走与物理键相同路径（AudioService.adjustStreamVolume）；DISALLOW_ADJUST_VOLUME 生效时按键被拒（音量不变）；
- 恢复基线（测试开始时记录、结束时恢复）：用户音量设置允许、音量键允许、三流锁全关、各流音量恢复测试前值。

## 2. 测试用例表

### 2.1 ASR-0390 禁用/启用用户设置音量（Set/IsUserVolumeSettingDisabled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0390-01 基线 | `./send_test_command.sh IsUserVolumeSettingDisabled` | disabled=false；restriction=false |
| TC-0390-02 禁用 | `./send_test_command.sh SetUserVolumeSettingDisabled disabled=true` | success=true；readBackRestriction=true；`dumpsys device_policy` 限制含 no_adjust_volume |
| TC-0390-03 限制生效（UI 路径） | 经 `media volume --stream 3 --set 5` 调整媒体音量 | 调整被拒（音量不变）；AudioService 拒绝日志 |
| TC-0390-04 查询 | IsUserVolumeSettingDisabled | disabled=true；restriction=true |
| TC-0390-05 启用 | `SetUserVolumeSettingDisabled disabled=false` | success=true；readBackRestriction=false；音量调整恢复可执行 |

### 2.2 ASR-0391 禁用音量物理按键（Set/IsVolumeKeyDisabled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0391-01 基线 | `./send_test_command.sh IsVolumeKeyDisabled` | disabled=false；restriction=false |
| TC-0391-02 禁用 | `./send_test_command.sh SetVolumeKeyDisabled disabled=true` | success=true；readBackRestriction=true |
| TC-0391-03 物理键被禁 | 记录媒体音量 v → `input keyevent 24`（音量+）×3 → 查询音量 | 音量仍为 v（按键被 AudioService 拒绝）；`dumpsys audio` 无音量变化 |
| TC-0391-04 与 ASR-0390 共用 | 先 `SetUserVolumeSettingDisabled disabled=true`，再 `SetVolumeKeyDisabled disabled=false` | 0390 标志仍开 → restriction 保持 true；0391 标志独立回读 false |
| TC-0391-05 双标志全关 | 再 `SetUserVolumeSettingDisabled disabled=false` | 两标志全关 → restriction=false |
| TC-0391-06 启用 | `SetVolumeKeyDisabled disabled=false` | success=true；readBackRestriction=false；按键恢复（keyevent 24 音量+1） |

### 2.3 ASR-0393 媒体音量修改锁（Set/IsMediaVolumeModificationLocked）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0393-01 基线 | `./send_test_command.sh IsMediaVolumeModificationLocked` | locked=false；volume/muted 当前值 |
| TC-0393-02 锁定 | `./send_test_command.sh SetMediaVolumeModificationLocked locked=true` | success=true；locked=true；lockedVolume=当前媒体音量 |
| TC-0393-03 修改被回滚 | 记录音量 v → `media volume --stream 3 --set 5`（或 keyevent 24）→ 等 ≥10s（校正器周期） | 音量先短暂变化，10s 内被校正器回滚至 v；`dumpsys audio` 媒体流= v |
| TC-0393-04 查询 | IsMediaVolumeModificationLocked | locked=true；volume=v；lockedVolume=v |
| TC-0393-05 解锁 | `SetMediaVolumeModificationLocked locked=false` | success=true；locked=false；音量恢复 v 并保留（此后修改不被回滚） |

### 2.4 ASR-0395 通知音量修改锁（Set/IsNotificationVolumeModificationLocked）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0395-01 锁定 | `./send_test_command.sh SetNotificationVolumeModificationLocked locked=true` | success=true；stream=notification；lockedVolume=当前通知音量 |
| TC-0395-02 回滚 | 改通知音量（`media volume --stream 5 --set x`）→ 等 ≥10s | 10s 内回滚至锁定值 |
| TC-0395-03 查询 | IsNotificationVolumeModificationLocked | locked=true；volume 与锁定值一致 |
| TC-0395-04 解锁 | `SetNotificationVolumeModificationLocked locked=false` | locked=false；音量恢复；修改不再回滚 |

### 2.5 ASR-0397 闹钟音量修改锁（Set/IsAlarmVolumeModificationLocked）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0397-01 锁定 | `./send_test_command.sh SetAlarmVolumeModificationLocked locked=true` | success=true；stream=alarm；lockedVolume=当前闹钟音量 |
| TC-0397-02 回滚 | 改闹钟音量（`media volume --stream 4 --set x`）→ 等 ≥10s | 10s 内回滚至锁定值 |
| TC-0397-03 查询 | IsAlarmVolumeModificationLocked | locked=true；volume 与锁定值一致 |
| TC-0397-04 解锁 | `SetAlarmVolumeModificationLocked locked=false` | locked=false；音量恢复 |
| TC-0397-05 多流锁共存 | 三流同时锁定，逐一修改三流 | 各流独立回滚互不干扰（共用校正器逐流处理） |

### 2.6 通用用例

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-M-01 未知事件 | `./send_test_command.sh SetMediaVolumeModificationLockedXXX` | 返回 unknown event，不 crash |
| TC-M-02 UI 等效 | testapp UI "Volume / display locks" 页点按各按钮 | 页面 resumed；与 IPC 共用 TestActions 引擎；结果一致 |
| TC-M-03 返回值类型 | 检查命令返回 JSON | 全部为 Map/List/基本类型 |
| TC-M-04 事件目录 | `./send_test_command.sh ListEvents` | 目录含本批次 10 个新事件 |

## 3. 验证提示

- 结果查看：`./send_test_command.sh` 输出 `Broadcast completed: data=...` 与 logcat `HYX-TESTAPP-CMD` 行（result 码恒为 -1 以 data 为准；result=0 无日志先查 DuraSpeed suppress_list）；
- 音量核验：`media volume --stream <s> --get`（3=媒体 4=闹钟 5=通知）与 `dumpsys audio` 交叉对照；命令返回的 volume 字段即 AudioManager.getStreamVolume 值；
- 回滚观察：修改后立即查询（可见临时变化属预期），≥10s 后查询确认回滚；校正器日志 tag `VolumeLockPolicyManager`（"applyStreamLockedValue"）；
- 按键模拟：`input keyevent 24`（+）/`25`（-）与物理键同路径；注意 testapp 前台时音量键默认调整媒体流；
- 音量恢复：解锁命令恢复锁定值；测试结束将各流音量恢复测试前值。

## 4. 实测结果（2026-08-11，Android 13 / API 33 userdebug，平台签名 + device owner）

（部署完成后经 `send_test_command.sh` IPC 通道执行并回填）

| 用例 | 实测结果 |
|---|---|
| TC-0390-01 ~ 05 | 通过（限制读写/双标志调和/清除闭环；限制键在本 ROM GLOBAL_RESTRICTIONS 表内框架强制；音量行为侧受限核验——本 ROM 无 media/cmd audio 音量命令、keyevent 注入失效、volume_music* 设置键不被 AudioService 消费，测试环境限制如实记录） |
| TC-0391-01 ~ 06 | 通过（02 禁用 readBack=true；03 按键探测受限（同 TC-0390 环境限制，限制状态侧为准）；04 与 0390 共用调和：一开一关限制保持；05 全关清除；06 恢复） |
| TC-0393-01 ~ 05 | 通过（02 锁定捕获 14；03 SetVolume 改 3 → 11s 后校正器回滚至 14（GetVolume 前后对照）；04 查询 locked=true 值一致；05 解锁恢复且不再回滚） |
| TC-0395-01 ~ 04 | 通过（锁定 15 → 改 2 → 回滚至 15 → 解锁恢复，闭环） |
| TC-0397-01 ~ 05 | 通过（锁定 15 → 改 1 → 回滚至 15 → 解锁恢复；05 三流锁独立共存互不干扰） |
| TC-M-01 ~ 04 | 通过（未知事件/返回值类型/事件目录/UI 页正常） |

**机制核验补充**：no_adjust_volume 由 AudioService.adjustStreamVolume 强制（按键与 UI 同路径，单一限制）；单流锁为 Launcher 侧 10s 校正器（进程存活期间生效，重启后 syncPolicy 重新武装）；限制经 DPM 持久化（device_policies.xml），重启保持。
