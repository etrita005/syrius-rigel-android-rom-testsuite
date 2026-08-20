# 音量锁策略管控（ASR-0390/0391/0393/0395/0397）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 实现机制 |
|---|---|---|---|
| ASR-0390 | 音量 | 禁用/启用用户设置音量 | `no_adjust_volume`（DISALLOW_ADJUST_VOLUME）用户限制（与 ASR-0391 共用，双标志独立） |
| ASR-0391 | 音量 | 查询/设置是否禁用音量（上下按键）物理按键 | 同上限制（Android 单一限制同时覆盖按键与 UI 调整，如实文档化） |
| ASR-0393 | 音量 | 是否允许用户修改 Media Volume | 单流捕获+10s 校正器回滚（STREAM_MUSIC） |
| ASR-0395 | 音量 | 是否允许用户修改通知音量 | 同上（STREAM_NOTIFICATION） |
| ASR-0397 | 音量 | 是否允许用户修改 Alarm 音量 | 同上（STREAM_ALARM） |

**归属**：均为 Launcher（MDM）（device owner 用户限制 + AudioManager 公开 API）。无 ROM 侧代码改动。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署。

## 2. 技术选型与可行性核验

### 2.1 ASR-0390/0391 共享 DISALLOW_ADJUST_VOLUME 限制

- Android 在 `AudioService.adjustStreamVolume` 强制检查 `no_adjust_volume`（DISALLOW_ADJUST_VOLUME）用户限制——设置后**设置界面音量滑杆与物理音量键均被拒绝**（音量调整入口统一收敛到 AudioService）；
- 平台仅提供这一条限制同时覆盖两类入口，因此两需求实现为**两个独立标志共用一个限制**（同蓝牙可发现模式/物理媒体引擎形制）：`userVolumeLocked`（ASR-0390）与 `volumeKeyLocked`（ASR-0391）独立持久化于 SharedPreferences `volume_lock_policy`，任一标志开启即置限制，两标志全关才清限制；
- 查询分别返回各自标志 + 实时限制状态；
- 局限如实文档化：无法做到"禁按键不禁 UI"或反之（平台粒度）。

### 2.2 ASR-0393/0395/0397 单流音量修改锁

- Android 无按流（media/notification/alarm）的音量修改限制；采用**捕获+回滚校正器**方案（与 ASR-0370 扬声器引擎同形制）：
  1. 锁定时捕获目标流当前音量与静音态，持久化于 `volume_lock_policy`（`stream_locked_<stream>`/`stream_volume_<stream>`/`stream_muted_<stream>`）；
  2. 10 秒周期校正器（`ScheduledThreadPoolExecutor`，三流共用单执行器）：检测到音量/静音态偏离锁定值即 `setStreamVolume`（FLAG_REMOVE_SOUND_AND_VIBRATE）或 ADJUST_MUTE/UNMUTE 回滚；
  3. 解锁时恢复捕获值并清除持久化键；无任何流锁时执行器自动停止；
- 流常量：STREAM_MUSIC（ASR-0393）/STREAM_NOTIFICATION（ASR-0395）/STREAM_ALARM（ASR-0397）；
- 进程重启/开机 `syncPolicy`：重放持久化锁（立即应用锁定值）+ 重启执行器 + 调和 0390/0391 限制。

## 3. 命令接口定义（SystemApiInterface.onEvent）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetUserVolumeSettingDisabled` | disabled | Map：{success, disabled, readBackRestriction, restriction} 或 "missing parameter" | ASR-0390 |
| `IsUserVolumeSettingDisabled` | 无 | Map：{disabled, restriction} | ASR-0390 |
| `SetVolumeKeyDisabled` | disabled | Map：{success, disabled, readBackRestriction, restriction} 或 "missing parameter" | ASR-0391 |
| `IsVolumeKeyDisabled` | 无 | Map：{disabled, restriction} | ASR-0391 |
| `SetMediaVolumeModificationLocked` | locked | Map：{success, locked, stream=media, volume, muted, lockedVolume} | ASR-0393 |
| `IsMediaVolumeModificationLocked` | 无 | Map：{success, locked, stream, volume, muted, lockedVolume} | ASR-0393 |
| `SetNotificationVolumeModificationLocked` | locked | Map：{success, locked, stream=notification, volume, muted, lockedVolume} | ASR-0395 |
| `IsNotificationVolumeModificationLocked` | 无 | Map：{success, locked, stream, volume, muted, lockedVolume} | ASR-0395 |
| `SetAlarmVolumeModificationLocked` | locked | Map：{success, locked, stream=alarm, volume, muted, lockedVolume} | ASR-0397 |
| `IsAlarmVolumeModificationLocked` | 无 | Map：{success, locked, stream, volume, muted, lockedVolume} | ASR-0397 |

**调用示例**：

```java
Map<String, Object> p = new HashMap<>();
p.put("locked", true);
Map result = api.onEvent("SetMediaVolumeModificationLocked", p);
// {"RESULT":{"success":true,"locked":true,"stream":"media","volume":10,"muted":false,"lockedVolume":10}}

Map result2 = api.onEvent("IsUserVolumeSettingDisabled", new HashMap<>());
// {"RESULT":{"disabled":true,"restriction":true}}
```

**广播通道**：

```bash
./send_test_command.sh SetUserVolumeSettingDisabled disabled=true
./send_test_command.sh SetVolumeKeyDisabled disabled=true
./send_test_command.sh SetMediaVolumeModificationLocked locked=true
./send_test_command.sh SetAlarmVolumeModificationLocked locked=true
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/VolumeLockPolicyManager.java      # 新增：0390/0391 共用限制引擎 + 0393/0395/0397 单流捕获回滚校正器 + syncPolicy
├── service/command/volume_lock/
│   ├── SetUserVolumeSettingDisabled.java / IsUserVolumeSettingDisabled.java         # 新增：ASR-0390
│   ├── SetVolumeKeyDisabled.java / IsVolumeKeyDisabled.java                         # 新增：ASR-0391
│   ├── SetMediaVolumeModificationLocked.java / IsMediaVolumeModificationLocked.java # 新增：ASR-0393
│   ├── SetNotificationVolumeModificationLocked.java / IsNotificationVolumeModificationLocked.java  # 新增：ASR-0395
│   └── SetAlarmVolumeModificationLocked.java / IsAlarmVolumeModificationLocked.java # 新增：ASR-0397
├── service/ApiBinder.java                  # 注册 10 个新命令
├── service/ApiService.java                 # onCreate：VolumeLockPolicyManager.syncPolicy
└── broadcast/BootCompletedReceiver.java    # 开机同步同上

testapp/
├── src/main/java/com/hmdm/testapp/
│   ├── VolumeDisplayTestActivity.java      # 新增测试页（含音量锁分区）
│   ├── TestActions.java                    # 新增 10 个事件与事件目录
│   ├── MainActivity.java                   # 新增入口按钮
│   └── res/layout/activity_volume_display_test.xml  # 新增布局
```

## 5. 执行逻辑

```
SetMediaVolumeModificationLocked(locked=true):
  1. 捕获 STREAM_MUSIC 当前 volume + isStreamMute，持久化（stream_locked/volume/muted）
  2. applyStreamLockedValue（当前值已一致则跳过写入）
  3. 启动/复用 10s 校正器（三流共用）
  4. 返回 {locked, stream, volume, muted, lockedVolume}

校正器 tick（每 10s）:
  对每个 stream_locked_<s>==true 的流：
    volume != 锁定值 → setStreamVolume(锁定值)
    muted != 锁定值 → ADJUST_MUTE/ADJUST_UNMUTE

SetUserVolumeSettingDisabled(disabled=true):
  1. 持久化 userVolumeLocked
  2. reconcile：任一标志（0390/0391）开 → addUserRestriction(no_adjust_volume)，全关 → clear
  3. getUserRestrictions 读回核对
```

**安全设计**：无 shell 命令；参数仅布尔；音频调整走公开 AudioManager API。

## 6. 权限与归属

- `no_adjust_volume` 用户限制：device owner 公开接口，无需 uses-policy 声明；
- AudioManager 读写：公开 API（MODIFY_AUDIO_SETTINGS manifest 既有声明）；
- 无新 manifest 权限、无 `device_admin.xml` 变更、无 AIDL/lib 模块变更、无需重启 framework。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 0390 与 0391 一开一关 | 共用限制保持开启；各自标志独立回读；全关才清除 |
| 锁定流时音量=0/静音 | 捕获 0 与 muted=true，校正器维持该状态（含用户 ADJUST_UNMUTE 被回滚） |
| 用户/应用修改被回滚窗口 | 10s 周期内短暂可见，下一 tick 回滚（文档化近似语义） |
| 解锁恢复 | 恢复捕获音量与静音态；清除持久化键；无流锁时停止执行器 |
| 进程重启/开机 | syncPolicy 重放：锁定值立即应用 + 校正器重启 + 限制调和 |
| 非 device owner | 限制通道静默失败（读回不符），命令 success=false 如实上报；流锁通道不依赖 DO |

## 8. 真机验证记录

（2026-08-11 批次；命令级用例结果见测试用例设计文档，此处记录机制核验关键结论）

| 核验项 | 结果 |
|---|---|
| no_adjust_volume 限制读写与双标志共用 | 通过：限制设置/读回/清除闭环；双标志一开一关限制保持、全关清除；限制键在本 ROM UserRestrictionsUtils GLOBAL_RESTRICTIONS 表内（services.jar dex 核验，框架强制） |
| 音量调整被禁（行为侧） | 受限核验：本 ROM 无 media/cmd audio 音量命令、`input keyevent 24/25` 注入不生效（未锁定态同样不生效——注入通道失效），行为侧以框架强制（AudioService.adjustStreamVolume 检查 + 限制表核验）为准；`settings put system volume_music*` 写入不改变实际音量（MTK 每设备音量键 AudioService 不消费）——测试环境限制如实记录，限制状态侧全量通过 |
| media/notification/alarm 流锁回滚 | 通过：三流闭环（锁定 14/15/15 → SetVolume 改 3/2/1 → 10s 内校正器回滚至锁定值，GetVolume 前后对照） |
| 解锁恢复 | 通过：解锁恢复锁定值且不再回滚；无流锁时校正器停止（日志 anyStreamLocked:false） |
| 测试后设备恢复 | 通过（限制清除、三流锁关闭、音量恢复基线） |
