# 摄像头/麦克风禁用（ASR-0207/0369）技术设计文档

## 1. 需求概述

| 需求 ID | 子功能项 | 需求语义 |
|---|---|---|
| ASR-0207 | 禁用/启用摄像头 | 全局禁用/启用摄像头（所有应用无法使用摄像头），并支持查询当前禁用状态 |
| ASR-0369 | 查询/设置是否禁用麦克风 | 全局禁用/启用麦克风（所有应用无法录音/采集音频），并支持查询当前禁用状态 |

**归属**：Launcher（MDM）。摄像头接口为公开 API（`DevicePolicyManager.setCameraDisabled` / `getCameraDisabled`，device owner）；麦克风接口本 ROM 无 DPM 能力，采用公开 API `AudioManager.setMicrophoneMute` / `isMicrophoneMute`（系统 uid 全局生效）。两条需求均无 ROM 侧代码改动，全部位于 Launcher 侧。

**基线**：目标平台 Android 13（API 33）。摄像头接口要求 API 21+（LOLLIPOP），麦克风接口要求 API 23+（M）。Launcher 的 minSdk 为 16，命令层对低版本返回 `false` 而不抛异常。

## 2. 技术选型

### 2.1 摄像头禁用（ASR-0207）

- **接口**：`DevicePolicyManager.setCameraDisabled(ComponentName admin, boolean disabled)`（API 21+，公开 API）。
- **查询**：`DevicePolicyManager.getCameraDisabled(ComponentName admin)`（API 21+，公开 API）。
- **权限要求**：调用方须为 device owner 或 profile owner（系统实现按 user 维度在 `CameraService` 打开摄像头时检查 `getCameraDisabled`）；且 `device_admin.xml` 的 `<uses-policies>` 必须声明 `<disable-camera/>`，否则 DPM 抛 `SecurityException: Admin did not specify uses-policy for: disable-camera`（实测踩坑，详见第 8 节部署注意）。
- **行为**：禁用后，任何应用（含系统相机应用）打开摄像头均失败——相机应用无法预览/拍照，`CameraManager.openCamera` 抛 `CameraAccessException(CAMERA_DISABLED)`，应用层表现为相机不可用。
- **现有先例**：同文件 `MdmUtils.disableScreenshots()` / `isScreenshotsDisable()` 已使用完全相同的 `DevicePolicyManager + getAdminComponentName` 模式（ASR-0185），实现与其对齐。

### 2.2 麦克风禁用（ASR-0369）

- **ROM 现状（真机核验，Android 13 / API 33）**：拉取 `/system/framework/framework.jar` 反查 dex 字符串，本 ROM **不存在** `DevicePolicyManager.setMicrophoneDisabled` / `getMicrophoneDisabled`（DPM 无麦克风策略），亦无 `IAudioService.setMicrophoneMuteFromSystem`；`<disable-microphone/>` uses-policy 标签被系统忽略（`dumpsys device_policy` 仅识别 wipe-data/limit-password/disable-camera）。
- **选型**：改用公开 API `AudioManager.setMicrophoneMute(boolean)`（API 19+，公开 SDK 自带）实现全局麦克风禁用，`AudioManager.isMicrophoneMute()` 实现查询。Launcher 为平台签名系统应用（uid=1000），调用经 AudioService 校验（系统 uid 或 `MODIFY_AUDIO_SETTINGS`）通过，静音状态在音频服务层对所有应用全局生效。
- **语义差异（文档记录）**：DPM 策略语义为"录音失败"；AudioManager 静音语义为"录音成功但采集到静音数据"。两者对 MDM"禁用麦克风"的验收效果一致：应用无法采集到有效音频。
- **持久性**：摄像头策略由 DPM 持久化、重启保持；麦克风静音状态为 AudioService 内存态，重启后需重新下发（策略层可后续在 `BootCompletedReceiver` 恢复，本次记录为已知限制）。

### 2.3 参数兼容

沿用通知管控命令的参数兼容约定：`disabled` 参数兼容 Boolean / String / Number 三种取值（testapp binder 直传 Boolean、TestBroadcast 经 Gson 解析、`send_test_broadcast.sh` 传字符串），统一通过 `Bundle.getSerializable("disabled")` 解析，解析失败回退 `getBoolean` 默认值。

## 3. 命令接口定义（SystemApiInterface.onEvent）

命令通过 `ApiBinder.method2Commands` 注册，`CallWithCommand` 包装调用，返回 `Map{"RESULT": 返回值}`。不修改 `SystemApiInterface.aidl` / `lib` 模块，客户端无需升级协议。

| 命令名 | 参数（Map） | 返回 RESULT | 说明 |
|---|---|---|---|
| `SetCameraDisabled` | disabled: Boolean | Boolean | ASR-0207 禁用/启用摄像头（API 21+，DPM） |
| `IsCameraDisabled` | - | Boolean | ASR-0207 查询摄像头禁用状态（API 21+） |
| `SetMicrophoneDisabled` | disabled: Boolean | Boolean | ASR-0369 禁用/启用麦克风（API 19+，AudioManager 静音） |
| `IsMicrophoneDisabled` | - | Boolean | ASR-0369 查询麦克风禁用状态（API 19+） |

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> param = new HashMap<>();
param.put("disabled", true);
Map result = api.onEvent("SetCameraDisabled", param);
boolean ok = (Boolean) result.get("RESULT");
```

```java
Map result = api.onEvent("IsCameraDisabled", new HashMap<String, Object>());
boolean disabled = (Boolean) result.get("RESULT");
```

**广播通道**（TestBroadcast，与现有命令一致）：

```bash
adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast \
  -a ON_SQA_INTERFACE_TEST --es event SetCameraDisabled --es param '{"disabled":"true"}'
# 或 ./send_test_broadcast.sh SetCameraDisabled disabled=true
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/MdmUtils.java                             # 新增 4 个方法（摄像头与 disableScreenshots 同 DPM 模式；麦克风走 AudioManager）
│   ├── setCameraDisabled(Context, boolean)
│   ├── isCameraDisabled(Context)
│   ├── setMicrophoneDisabled(Context, boolean)
│   └── isMicrophoneDisabled(Context)
├── service/command/camera/                         # 新增 4 个命令类
│   ├── SetCameraDisabled.java
│   ├── IsCameraDisabled.java
│   ├── SetMicrophoneDisabled.java
│   └── IsMicrophoneDisabled.java
├── service/ApiBinder.java                          # 注册 4 条命令
└── activity/CameraMicMgActivity.java               # 手动验证管理页

app/src/main/AndroidManifest.xml                    # 注册 CameraMicMgActivity（exported=false）
app/src/main/res/layout/activity_camera_mic_mg.xml  # 管理页布局
app/src/main/res/layout/activity_test.xml           # TestMainActivity 增加入口按钮
app/src/main/java/com/hmdm/launcher/syrius/activity/TestMainActivity.java

testapp/                                            # 测试 APP 增加摄像头/麦克风测试分区
└── src/main/
    ├── java/com/hmdm/testapp/MainActivity.java     # 设置/查询命令 + 真机开相机/录音验证按钮
    ├── res/layout/activity_main.xml
    └── AndroidManifest.xml                         # 增加 CAMERA / RECORD_AUDIO 权限
```

## 5. 策略逻辑

```
SetCameraDisabled(disabled):
  非 device owner → false（不调用 DPM）
  dpm.setCameraDisabled(getAdminComponentName(), disabled)   # 公开 API
  成功 → true；异常 → false

IsCameraDisabled():
  dpm.getCameraDisabled(getAdminComponentName())   # 异常 → false

SetMicrophoneDisabled(disabled):
  audioManager.setMicrophoneMute(disabled)   # 公开 API，系统 uid 全局生效
  成功 → true；异常 → false

IsMicrophoneDisabled():
  audioManager.isMicrophoneMute()   # 异常 → false
```

- 设置与查询均为**立即生效**的全局策略，无持久化存储需求（DPM 自行持久化，重启后保持）。
- 不提供按应用白名单（ASR-0206 摄像头白名单归属 ROM + Launcher，不在本次范围）。

## 6. 权限与归属

- 归属：Launcher（MDM）——摄像头为公开 SDK 接口（DPM，device owner）；麦克风为公开 SDK 接口（AudioManager，系统 uid 全局生效），均为 Launcher 侧实现，无 ROM 改动。
- 摄像头依赖 device owner 身份（`<disable-camera/>` uses-policy 已声明）；麦克风依赖平台签名（uid=1000）通过 AudioService 校验，不依赖 device owner。
- 依赖权限：无新增 manifest 权限（`MODIFY_AUDIO_SETTINGS` 由平台签名隐含授权，且系统 uid 无需声明即通过校验）。
- 不修改 `IMdmApi.aidl` / `lib` 模块；不新增 ROM 侧代码。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| API < 21（摄像头）/ API < 19（麦克风） | 命令返回 false，不 crash |
| 摄像头非 device owner | 返回 false |
| DPM/AudioManager 调用抛异常（如 Binder 异常、SecurityException） | catch 后 Logger 记录，返回 false（查询返回 false） |
| disabled 参数类型不确定（String/Number/Boolean） | 统一 getSerializable 解析，失败回退默认 false |
| 空参数 / 缺参数 | 按 disabled=false（启用）处理，不 crash |
| 设置后立即查询 | 读回状态应一致（DPM 同步生效；AudioManager 静音同步生效） |
| 摄像头禁用期间系统相机崩溃/报错 | 属预期行为（全局管控），验收时以相机应用无法预览为通过标准 |
| 麦克风禁用期间录音应用 | 录音不报错但采集到静音（AudioManager 静音语义，非 DPM 拒绝语义），验收以无有效音频为通过标准 |
| 麦克风禁用状态重启 | 摄像头策略保持；麦克风静音为内存态、重启后复位（已知限制，后续可在 BootCompletedReceiver 恢复） |

## 8. 真机验证记录（2026-08-03，Android 13 / API 33 userdebug，平台签名 + device owner）

| 用例 | 结果 |
|---|---|
| ASR-0207 禁用摄像头 | `SetCameraDisabled disabled=true` → RESULT=true；`IsCameraDisabled` → true |
| ASR-0207 真机效果（禁用） | testapp `CameraManager.openCamera` → `CAMERA_DISABLED (1): connectDevice:1748: Camera disabled by device policy`；系统相机 `com.mediatek.camera` 启动即崩溃（launchCamera→setCameraMain3Value 异常） |
| ASR-0207 启用摄像头 | `SetCameraDisabled disabled=false` → RESULT=true；`IsCameraDisabled` → false；`openCamera` → onOpened camera=0；系统相机恢复正常预览 |
| ASR-0369 禁用麦克风 | `SetMicrophoneDisabled disabled=true` → RESULT=true；`IsMicrophoneDisabled` → true；`dumpsys audio`：`mic mute FromSwitch=false FromRestrictions=false FromApi=true from system=true`；AudioRecord 采集 37888 样本、**energy=0（纯静音）** |
| ASR-0369 启用麦克风 | `SetMicrophoneDisabled disabled=false` → RESULT=true；`IsMicrophoneDisabled` → false；AudioRecord 采集 37888 样本、**energy=2809910（avg amp=74，正常音频）** |
| ASR-0207/0369 重启保持 | 摄像头策略重启后仍为 true（DPM 持久化）；麦克风静音重启后复位为 false（AudioManager 内存态，已知限制） |
| 异常入参 | `disabled="abc"` / 缺参 → 按 disabled=false 处理，返回 true 不 crash |
| 摄像头/麦克风互不干扰 | 状态独立，切换互不影响 |
| 广播通道 | `am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast ...` 全流程可驱动（logcat TAG `HYX-MDM-APP`） |

**部署注意（本次实测踩坑）**：
1. `device_admin.xml` 必须声明 `<disable-camera/>` uses-policy，否则 `setCameraDisabled` 抛 `SecurityException: Admin did not specify uses-policy for: disable-camera`；
2. 安装含新 uses-policies 的 APK 后，`DevicePolicyManagerService` 内存中的 admin 策略缓存不会立即刷新，需 `adb shell stop && start`（软重启 framework）或重启设备后新策略才生效；
3. 麦克风禁用依赖平台签名（uid=1000）调用 `AudioManager.setMicrophoneMute`，与 device owner 无关；若未来 ROM 提供 `DevicePolicyManager.setMicrophoneDisabled`，可无缝替换为 DPM 方案（命令接口不变）。
