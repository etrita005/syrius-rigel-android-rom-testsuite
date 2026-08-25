# 屏幕/休眠/亮度管控（ASR-0408/0409/0410/0411/0413/0414）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 底层机制 |
|---|---|---|---|
| ASR-0408 | 屏幕常亮 | 充电时屏幕保持常亮 | `Settings.Global STAY_ON_WHILE_PLUGGED_IN`（SetStayAwakeWhileCharging）+ setScreenNeverSleep 引擎 |
| ASR-0409 | 自动熄屏时长 | 设置/查询熄屏超时 | `Settings.System SCREEN_OFF_TIMEOUT`（SetSleepTimeOut/GetSleepTimeout） |
| ASR-0413 | 自动休眠时长 | 同 ASR-0409（共用命令；SetAutoSleepDisabled 为 VolumeDisplay 批次"永不休眠"变体） | 同上 |
| ASR-0410 | 设置屏幕亮度 | 设置/查询屏幕亮度 | `Settings.System SCREEN_BRIGHTNESS`（SetBrightness/GetBrightness，WRITE_SETTINGS） |
| ASR-0411 | 是否允许用户修改屏幕亮度 | 禁止/允许用户调节亮度 | device owner `DISALLOW_CONFIG_BRIGHTNESS`（P+） |
| ASR-0414 | 是否禁止用户设置休眠时长 | 禁止/允许用户设置休眠时长 | device owner `DISALLOW_CONFIG_SCREEN_TIMEOUT`（P+） |

**归属**：「Launcher（MDM）」。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署；本机基线：stayOn=0、timeout=60000、brightness=87、mode=0、限制均 false。

## 2. 技术选型与可行性核验

| 方案 | 说明 | 结论 |
|---|---|---|
| Settings 直写（0408/0409/0410） | 平台签名 WRITE_SETTINGS/WRITE_SECURE_SETTINGS 既有声明，SettingsProvider 持久化 | **采用** |
| DISALLOW_CONFIG_BRIGHTNESS / DISALLOW_CONFIG_SCREEN_TIMEOUT（0411/0414） | device owner 用户限制（P+） | **采用** |

真机核验（2026-08-13）：全部命令往返一致（写后读回 + Settings 直读三方对照）；设置均持久化（测试后恢复基线）。

## 3. 命令接口定义

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetStayAwakeWhileCharging` | stayAwakeWhileCharging（Boolean，必填） | Boolean | ASR-0408 |
| `IsStayAwakeWhileCharging` | 无 | Boolean | ASR-0408 |
| `SetSleepTimeOut` | timeout（int ms，必填） | Boolean | ASR-0409/0413 |
| `GetSleepTimeout` | 无 | int（ms） | ASR-0409/0413 |
| `SetBrightness` | brightness（int 0-255，必填） | Boolean | ASR-0410 |
| `GetBrightness` | 无 | int（0-255） | ASR-0410 |
| `SetDisallowUserConfigBrightness` | disallow（Boolean，必填） | Boolean | ASR-0411 |
| `IsDisallowUserConfigBrightness` | 无 | Boolean | ASR-0411 |
| `SetDisallowUserConfigSleepTimeout` | disallow（Boolean，必填） | Boolean | ASR-0414 |
| `IsDisallowUserConfigSleepTimeout` | 无 | Boolean | ASR-0414 |

**调用示例**：

```java
Map<String, Object> p = new HashMap<>();
p.put("brightness", 150);
Map result = api.onEvent("SetBrightness", p);
// {"RESULT":true}；GetBrightness → 150（Settings 直读一致）
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/
├── syrius/service/command/screen/                  # SetStayAwakeWhileCharging/IsStayAwakeWhileCharging/
│                                                 # SetSleepTimeOut/GetSleepTimeout/SetBrightness/GetBrightness/
│                                                 # Set|IsDisallowUserConfigBrightness/
│                                                 # Set|IsDisallowUserConfigSleepTimeout（全部既有）
└── syrius/service/ApiBinder.java                  # 既有注册

testapp/
└── src/main/java/com/hmdm/testapp/
    ├── TestActions.java                           # 11 个事件（参数名：stayAwakeWhileCharging/timeout/
    │                                             #   brightness/disallow）
    ├── ScreenStateVerifier.java                   # 新增：本地 Settings 探针（stay-on/timeout/brightness/mode）
    ├── ScreenSleepBrightnessControlTestActivity.java  # 新增测试页
    └── src/main/res/layout/activity_screen_sleep_brightness_test.xml
```

## 5. 执行逻辑

```
SetStayAwakeWhileCharging(stayAwakeWhileCharging): STAY_ON_WHILE_PLUGGED_IN 写 3/0（USB+AC）
SetSleepTimeOut(timeout): SCREEN_OFF_TIMEOUT 写 ms
SetBrightness(brightness): SCREEN_BRIGHTNESS 写 0-255（同时关自动亮度 mode=0，亮度即时生效）
SetDisallowUserConfigBrightness/SleepTimeout(disallow): DISALLOW_CONFIG_BRIGHTNESS /
  DISALLOW_CONFIG_SCREEN_TIMEOUT 加/除（P+）
```

**安全设计**：WRITE_SETTINGS 平台签名；调用方经 ApiBinder 门禁；设置持久化——测试后恢复基线。

## 6. 权限与归属

- Settings 直写（WRITE_SETTINGS/WRITE_SECURE_SETTINGS 既有声明）；用户限制为 device owner 公开接口（P+）；
- testapp：无新增权限；无 ROM 侧代码改动，无 root 依赖。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺参 | testapp 侧返回 missing parameter，不 crash |
| brightness 越界 | 命令按传入值写入（引擎内处理）；文档建议 0-255 |
| 持久性 | 各设置持久化——测试后恢复基线（本机 stayOn=0/timeout=60000/brightness=87） |
| 参数名 | stayAwakeWhileCharging/timeout/brightness/disallow 为实际参数名（核对记录） |

## 8. 真机验证记录（2026-08-13，Android 13 / API 33 userdebug，平台签名 + device owner）

| 核验项 | 结果 |
|---|---|
| ASR-0408 | 基线 Is=false、stayOn=0；SetStayAwakeWhileCharging(true) → Is=true、探针 stayOnWhilePluggedIn=3；false → 恢复 0 |
| ASR-0409/0413 | SetSleepTimeOut(120000) → Get=120000、探针一致；恢复 60000 |
| ASR-0410 | SetBrightness(150) → Get=150、探针一致；恢复 87 |
| ASR-0411 | SetDisallowUserConfigBrightness(disallow=true) → Is=true；false → false |
| ASR-0414 | SetDisallowUserConfigSleepTimeout(disallow=true) → Is=true；false → false |
| 测试后状态 | 全部恢复基线（stayOn=0/timeout=60000/brightness=87/限制 false）；DO 在位 |
