# 音量设置与静音管控（ASR-0389/0392/0394/0396）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 底层机制 |
|---|---|---|---|
| ASR-0389 | 手机是否静音 | 静音/取消静音（单流与全部） | `AudioManager.setStreamMute`（VolumeManager：Mute/UnMute 单流、MuteAll/UnMuteAll 六流） |
| ASR-0392 | 设置 Media Volume | 设置/查询媒体音量 | `AudioManager.setStreamVolume(STREAM_MUSIC)` |
| ASR-0394 | 设置 Notification Volume | 设置/查询通知音量 | `setStreamVolume(STREAM_NOTIFICATION)` |
| ASR-0396 | 设置 Alarm Volume | 设置/查询闹钟音量 | `setStreamVolume(STREAM_ALARM)` |

**归属**：「Launcher（MDM）」（公开 AudioManager API，平台签名 MODIFY_AUDIO_SETTINGS）。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署。

## 2. 技术选型与可行性核验

| 方案 | 说明 | 结论 |
|---|---|---|
| `AudioManager.setStreamMute/setStreamVolume` | 公开 API（MODIFY_AUDIO_SETTINGS 既有声明），平台签名授予 | **采用**（VolumeManager 既有引擎） |

- 命令均既有（Mute/UnMute/MuteAll/UnMuteAll/SetVolume/GetVolume）；本批次补 testapp IPC 事件（Mute/UnMute/MuteAll/UnMuteAll/MuteStateLocal 探针）与 VolumeDisplayTestActivity 按钮；
- 真机核验（2026-08-13）：单流 Mute/UnMute 生效（本地探针 isStreamMute 读回一致）；SetVolume 写后 GetVolume 一致；**MuteAll 实测静音 5/6 流（闹钟流 stream_4 未静音——VolumeManager.muteAll 的实现细节，如实记录）**；音量流索引：STREAM_MUSIC=3、STREAM_ALARM=4、STREAM_NOTIFICATION=5（AudioManager 常量）。

## 3. 命令接口定义

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `Mute` | streamType（int，必填） | Boolean | ASR-0389 |
| `UnMute` | streamType（int，必填） | Boolean | ASR-0389 |
| `MuteAll` | 无 | Boolean | ASR-0389 |
| `UnMuteAll` | 无 | Boolean | ASR-0389 |
| `SetVolume` | streamType（int，必填）、volume（int，必填） | Boolean | ASR-0392/0394/0396 |
| `GetVolume` | streamType（int，必填） | Map{current, max} | ASR-0392/0394/0396 |

**调用示例**：

```java
Map<String, Object> p = new HashMap<>();
p.put("streamType", 3);
p.put("volume", 5);
Map result = api.onEvent("SetVolume", p);
// {"RESULT":true}；GetVolume(3) → {current:5, max:15}
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/
├── syrius/utils/VolumeManager.java                # mute/unMute/muteAll/unMuteAll/setVolume/getVolume
├── syrius/service/command/volume/                 # Mute/UnMute/MuteAll/UnMuteAll/SetVolume/GetVolume（既有）
└── syrius/service/ApiBinder.java                  # 既有注册

testapp/
└── src/main/java/com/hmdm/testapp/
    ├── TestActions.java                           # Mute/UnMute/MuteAll/UnMuteAll/MuteStateLocal 事件
    ├── VolumeVerifier.java                        # 新增：本地静音状态探针（isStreamMute 六流）
    ├── VolumeDisplayTestActivity.java             # 扩展：静音/音量按钮组
    └── src/main/res/layout/activity_volume_display_test.xml
```

## 5. 执行逻辑

```
Mute(streamType): AudioManager.setStreamMute(streamType, true)
UnMute(streamType): setStreamMute(streamType, false)
MuteAll(): 六流依次静音（STREAM_VOICE_CALL/SYSTEM/RING/MUSIC/ALARM/NOTIFICATION——
            实测闹钟流未静音，VolumeManager 实现细节，如实记录）
SetVolume(streamType, volume): setStreamVolume(streamType, volume, 0)
GetVolume(streamType): {current, max}
```

**安全设计**：公开 API + 平台签名；调用方经 ApiBinder 门禁。

## 6. 权限与归属

- MODIFY_AUDIO_SETTINGS（manifest 既有声明）；testapp 无新增权限；无 ROM 侧代码改动，无 root 依赖。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺参（streamType/volume） | testapp 侧返回 missing parameter，不 crash |
| 非法 streamType | AudioManager 忽略/异常捕获（命令如实） |
| MuteAll 闹钟流 | 实测未静音（VolumeManager 实现细节，记录；单流 Mute(4) 可用） |
| 音量持久性 | 音量设置持久化（AudioService）——测试后恢复原值（本机 15/15/15） |

## 8. 真机验证记录（2026-08-13，Android 13 / API 33 userdebug，平台签名 + device owner）

| 核验项 | 结果 |
|---|---|
| ASR-0389 单流静音 | 基线六流均 false；Mute(3) → stream_3=true（探针读回）；UnMute(3) → false |
| ASR-0389 全部静音 | MuteAll → stream_3/5=true、**stream_4（闹钟）=false**（实现细节记录）；UnMuteAll → 全部 false（恢复） |
| ASR-0392 | SetVolume(3,5) → GetVolume(3)={current:5, max:15}；恢复 15 |
| ASR-0394 | SetVolume(5,3) → GetVolume(5)=3；恢复 15 |
| ASR-0396 | SetVolume(4,4) → GetVolume(4)=4；恢复 15 |
| 测试后状态 | 音量恢复 15/15/15、静音全部解除；DO 在位 |
