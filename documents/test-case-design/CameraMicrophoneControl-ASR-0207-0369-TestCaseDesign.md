# 摄像头/麦克风禁用（ASR-0207/0369）测试用例设计文档

## 1. 前置条件

| 项目 | 说明 |
|---|---|
| 设备 | Android 5.0+（API 21+）验证摄像头；麦克风禁用基于 `AudioManager.setMicrophoneMute`（API 19+），本机 Android 13（API 33） |
| Launcher | 平台签名（`sharedUserId="android.uid.system"`，uid=1000）安装 `mdm-launcher-*.apk`；摄像头需 **device owner**（麦克风为系统 uid 全局静音，不依赖 DO，但本机已具备） |
| 测试 APP | 安装 `testapp-debug.apk`（`com.hmdm.testapp`），首次进入点击"Request CAMERA/RECORD_AUDIO permissions"并允许 |
| 连接 | testapp 自动 bind Launcher `ApiService`（action `syrius.mdm.api_service`），日志显示"connected"；未连接时命令入队，连接成功后自动重放 |
| 备选触发方式 | `adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast -a ON_SQA_INTERFACE_TEST --es event <命令名> --es param '<json>'`，或用 `./send_test_broadcast.sh <命令名> disabled=true` |
| 测试目标 | 系统相机应用（验证摄像头真实效果）；testapp 内置 `CameraManager.openCamera` / `AudioRecord` 自证（验证麦克风真实效果） |
| 已知差异 | 本 ROM 无 DPM 麦克风策略，麦克风禁用实现为 AudioManager 全局静音：录音应用不报错但采集到静音数据（验收以"无有效音频"为准）；静音为内存态，重启后复位 |

## 2. 用例表

### 2.1 ASR-0207 禁用/启用摄像头

| 编号 | 用例名称 | 前置/步骤 | 预期结果 |
|---|---|---|---|
| TC-0207-01 | 禁用摄像头 | testapp 点击"Set Camera Disabled"；再点击"Query Camera Status" | RESULT=true；查询返回 true |
| TC-0207-02 | 摄像头真实禁用效果 | 接 TC-0207-01，点击"Try Open Camera"；打开系统相机应用 | openCamera 回调 onError（日志输出错误码），相机应用无法预览/拍照 |
| TC-0207-03 | 启用摄像头 | testapp 点击"Set Camera Enabled"；再点击"Query Camera Status" | RESULT=true；查询返回 false |
| TC-0207-04 | 摄像头真实恢复效果 | 接 TC-0207-03，点击"Try Open Camera"；打开系统相机应用 | openCamera 成功（日志输出 onOpened），相机应用正常预览 |
| TC-0207-05 | 广播通道触发 | `send_test_broadcast.sh SetCameraDisabled disabled=true` / `IsCameraDisabled` | logcat（TAG `HYX-MDM-APP`）输出命令与 RESULT=true |
| TC-0207-06 | 异常入参：非布尔值 | 广播 `--es param '{"disabled":"abc"}'` | 按 false（启用）处理，返回 true 且查询为 false，不 crash |
| TC-0207-07 | 还原 | 用例结束后点击"Set Camera Enabled" | 摄像头恢复可用，不影响后续用例 |

### 2.2 ASR-0369 查询/设置是否禁用麦克风

| 编号 | 用例名称 | 前置/步骤 | 预期结果 |
|---|---|---|---|
| TC-0369-01 | 禁用麦克风 | testapp 点击"Set Microphone Disabled"；再点击"Query Microphone Status" | RESULT=true；查询返回 true |
| TC-0369-02 | 麦克风真实禁用效果 | 接 TC-0369-01，点击"Try Record Audio"；或使用录音类应用 | AudioRecord 可启动但读到静音（总样本数为 0 或音量极低）；录音应用生成的音频无有效声音 |
| TC-0369-03 | 启用麦克风 | testapp 点击"Set Microphone Enabled"；再点击"Query Microphone Status" | RESULT=true；查询返回 false |
| TC-0369-04 | 麦克风真实恢复效果 | 接 TC-0369-03，点击"Try Record Audio" | AudioRecord 正常读取到音频数据（read 返回 >0，样本非全零） |
| TC-0369-05 | 广播通道触发 | `send_test_broadcast.sh SetMicrophoneDisabled disabled=true` / `IsMicrophoneDisabled` | logcat 输出命令与 RESULT=true |
| TC-0369-06 | 异常入参：缺参 | 广播不带 param 或 param 为空 | 按 disabled=false 处理，不 crash |
| TC-0369-07 | 还原 | 用例结束后点击"Set Microphone Enabled" | 麦克风恢复可用，不影响后续用例 |

### 2.3 通用与边界

| 编号 | 用例名称 | 前置/步骤 | 预期结果 |
|---|---|---|---|
| TC-M-01 | 状态重启保持 | 禁用摄像头/麦克风后重启设备，打开 testapp 查询 | 摄像头查询仍为 true（DPM 持久化）；麦克风查询为 false（AudioManager 静音为内存态，重启复位，属已知限制） |
| TC-M-02 | 非 device owner 场景（静态） | 代码审查 + 在非 DO 设备调用摄像头命令 | 摄像头命令返回 false 不 crash（`isDeviceOwner` 守卫）；麦克风命令不依赖 DO |
| TC-M-03 | 断开 Launcher 场景 | 未连接时点击任意命令 | 命令入队不 crash；连接后自动重放并输出 RESULT |
| TC-M-04 | 摄像头/麦克风互不干扰 | 仅禁用摄像头后查询麦克风 | 麦克风状态不受影响（false），反之亦然 |

## 3. 验证要点提示

- testapp 日志区（底部 TextView）展示每次命令的 RESULT 与真实硬件验证结果；优先以真机行为（相机无法预览 / 录音为静音）复核实际效果。
- "Try Open Camera" 使用 `CameraManager.openCamera`（异步回调 + 超时），关闭摄像头策略生效时回调 onError；"Try Record Audio" 使用 `AudioRecord` 采集 1 秒数据并报告读取字节数与有效样本，禁用时读数为 0 或全零样本。
- 摄像头策略依赖 device owner，验证前确认 `adb shell dpm list-owners` 输出含 `DeviceOwner`；麦克风静音为系统 uid 能力，不依赖 DO。
- 麦克风"禁用"的验收口径为**静音**：录音可启动但内容无有效音频（本 ROM 无 DPM 麦克风策略，AudioManager 静音为可用替代，语义差异见设计文档）。
- 系统相机应用在策略禁用时可能弹错误提示或黑屏，属预期全局管控行为。
- 摄像头禁用对前后摄像头均生效；无需区分应用，属全设备策略。
- 麦克风静音重启后复位（内存态），如需重启保持需在 Launcher 启动流程补发策略（本次记录为已知限制）。
