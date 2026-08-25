# 字体大小管控（ASR-0407）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 实现机制 |
|---|---|---|---|
| ASR-0407 | 字体大小 | 设置 字体大小 | 直写 `Settings.System.font_scale`（float，0.5~2.0；Settings 应用预置档 0.85/1.0/1.15/1.30） |

**归属**：Launcher（MDM）。落地为受保护系统设置直写（平台签名 Launcher uid=1000，system uid 自动持有 `WRITE_SETTINGS`），框架即时消费生效，无 ROM 侧代码改动。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署。测试设备基线（2026-08-11 实测）：`font_scale` 未设置（缺省 1.0）、`dumpsys activity` mGlobalConfiguration `{1.0 ...}`。

## 2. 技术选型与可行性核验

### 2.1 机制核验（本 ROM 与 Settings 应用同键同路径）

Sheet1 P2 规划目标为 `Settings.System FONT_SCALE`。2026-08-11 经 **MtkSettings APK 反编译** 与 **services.jar dex 核验**确认该路径为本 ROM 字体大小设置的唯一机制：

| 环节 | 核验结果 |
|---|---|
| Settings 应用写入 | `FontSizeData.commit`：`Settings$System.putFloat(resolver, "font_scale", value)`（MtkSettings dex 反编译核验）；预置档 `R.array.entryvalues_font_size = ["0.85","1.0","1.15","1.30"]`（aapt2 资源核验，4 档：小/默认/大/特大） |
| 框架消费 | `ActivityTaskManagerService$SettingObserver`（system_server）对 `font_scale` 注册 ContentObserver（`Settings$System.getUriFor("font_scale")`）；`updateFontScaleIfNeeded(userId)` 读 `Settings$System.getFloatForUser("font_scale", 1.0f)`，与 `getGlobalConfiguration().fontScale` 比对，不等则 `computeNewConfiguration` + `iput Configuration.fontScale` + `updatePersistentConfiguration` 全系统下发（services.jar dex 反编译核验） |
| 权限 | `WRITE_SETTINGS`（signature）由平台签名 uid=1000（sharedUserId=android.uid.system）自动持有；同属 `Settings.System` 直写的 navigation_visible（ASR-0349）已在本项目既有路径核验 |

**真机生效闭环（2026-08-11）**：`SetFontScale scale=1.3` 后 `settings get system font_scale`=1.3，`dumpsys activity` 的 mGlobalConfiguration 变为 `{1.3 ...}`（ATMS 与 WMS 双份一致）——框架全链路即时生效，与 Settings 应用手动调整字体大小效果完全一致。

### 2.2 取值设计

- 接受任意 float（0.5~2.0 闭区间），越界拒绝（`error`，不写库）；
- 返回附报本 ROM 预置档 `presets=[0.85, 1.0, 1.15, 1.30]` 供上层换算档位；
- 写后读回核对（`Math.abs(actual - scale) < 0.0001f`），一致才 `success=true`；
- 查询返回当前值与预置档。

## 3. 命令接口定义（SystemApiInterface.onEvent）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetFontScale` | scale（float/数字字符串，必填，0.5~2.0） | Map：{success, scale, presets} 或 {error} | ASR-0407 |
| `GetFontScale` | 无 | Map：{success, scale, presets} | ASR-0407 |

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> p = new HashMap<>();
p.put("scale", 1.3);
Map result = api.onEvent("SetFontScale", p);
// {"RESULT":{"scale":1.3,"success":true,"presets":[0.85,1,1.15,1.3]}}

Map result2 = api.onEvent("GetFontScale", new HashMap<>());
// {"RESULT":{"scale":1.0,"success":true,"presets":[0.85,1,1.15,1.3]}}
```

**广播通道**（TestBroadcast / testapp IPC，与现有命令一致）：

```bash
adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast \
  -a ON_SQA_INTERFACE_TEST --es event SetFontScale \
  --es param '{"scale":1.3}'
# testapp 侧（与 UI 按钮等效）：./send_test_command.sh SetFontScale scale=1.3
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/SystemSettingsManager.java        # 扩展：setFontScale/getFontScale（Settings.System.font_scale 直写 + 写后读回核对）
├── service/command/settings/
│   ├── SetFontScale.java                   # 新增：ASR-0407 设置命令（scale 参数数值/数字字符串兼容）
│   └── GetFontScale.java                   # 新增：ASR-0407 查询命令
└── service/ApiBinder.java                  # 注册 2 个新命令

testapp/
├── src/main/java/com/hmdm/testapp/
│   ├── SystemSettingsTestActivity.java     # 扩展：字体大小 EditText + Set/Get 按钮（ASR-0407 分区）
│   ├── TestActions.java                    # 新增 2 个事件（含参数校验）与事件目录
│   └── res/layout/activity_system_settings_test.xml  # 新增 ASR-0407 分区
```

## 5. 执行逻辑

```
SetFontScale(scale):
  1. 参数校验（缺 scale → {error}；非数值 → {error}）
  2. 范围校验：0.5 <= scale <= 2.0 且有限值，否则 {error："invalid scale: x (0.5..2.0)"}
  3. Settings.System 直写 font_scale（putFloat），读回核对（±0.0001）→ success
  4. 返回 {success, scale, presets}

GetFontScale:
  1. 读 font_scale（缺省 1.0）
  2. 返回 {success, scale, presets}
```

**安全设计**：参数为单浮点数，无字符串进入 shell / 系统命令，无命令注入面；设置键名为代码内常量。

## 6. 权限与归属

- `WRITE_SETTINGS`（signature 级，平台签名 uid=1000 自动授予，真机核验生效）；`Settings.System.font_scale` 为 Settings 应用同键；
- 无 ROM 侧代码改动，无 root 依赖；不修改 AIDL / lib 模块；testapp 无需新增权限；不修改 `device_admin.xml`，无需重启 framework。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺 scale 参数 | 命令返回 {error："missing parameter: scale (0.5..2.0, ...)"}，不 crash；testapp 侧同样拦截 |
| 非数值 scale（如 abc） | {error："invalid scale: abc (0.5..2.0, ...)"}，不 crash、不写库 |
| 越界 scale（<0.5 或 >2.0） | {error："invalid scale: x (0.5..2.0)"}，不写库 |
| 写失败或读回不一致 | success=false + error（含期望值/实际值），如实上报，可重试 |
| 任意非预置档值 | 直接生效（框架接受任意 float 档位，Settings 应用滑块位置按最接近预置档显示） |
| 整机重启 | `font_scale` 由 SettingsProvider 持久化（Settings.System 存储），重启保持，无需重新武装 |

## 8. 真机验证记录（2026-08-11，Android 13 / API 33 userdebug，平台签名 + device owner）

（命令级用例结果见测试用例设计文档，此处记录机制核验关键结论）

| 核验项 | 结果 |
|---|---|
| 设备基线 | font_scale 未设置（缺省 1.0），mGlobalConfiguration `{1.0 ...}` |
| 写入生效 | SetFontScale 1.3 → `settings get system font_scale`=1.3；mGlobalConfiguration `{1.3 ...}`（ATMS/WMS 双份一致） |
| 各档往返 | 0.85 → 1.0 → 1.15 → 1.3 → 1.0 全部写后读回 success=true，框架配置逐次跟随（1.15、1.0 均实测） |
| 越界拒绝 | 0.4 / 2.5 → error，键值保持原样 |
| 非数值拒绝 | abc → error，不 crash |
| 缺参 | scale 缺失 → missing parameter，不 crash |
| UI 按钮闭环 | testapp "System settings" 页输入 1.3 点 "Set Font Scale" → font_scale=1.3 + 框架配置 1.3（UI 与 IPC 共用 TestActions 引擎） |
| 重启持久化 | font_scale 为 Settings.System 存储，整机重启保持（SettingsProvider 持久化，无 Launcher 重新武装需求） |
| 测试后设备恢复 | font_scale=1.0（mGlobalConfiguration `{1.0 ...}`），无残留 |
