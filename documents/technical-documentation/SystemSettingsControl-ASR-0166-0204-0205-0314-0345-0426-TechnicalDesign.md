# 全局/安全设置管控（ASR-0166/0204/0205/0314/0345/0426）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 底层 Settings 键 |
|---|---|---|---|
| ASR-0166 | WLAN强管控 | 禁止/允许弹出 WIFI captive portal 弹窗 | `Settings.Global.captive_portal_mode` + `captive_portal_detection_enabled` |
| ASR-0204 | 开发者选项 | 查询/设置 是否禁止"不保留活动"设置状态 | `Settings.Global.always_finish_activities` |
| ASR-0205 | 开发者选项 | 查询/设置 是否禁止模拟定位模式设置 | `Settings.Secure.mock_location` |
| ASR-0314 | 定位服务 | 设置定位模式 | `Settings.Secure.location_mode`（0/1/2/3） |
| ASR-0345 | 全面屏手势导航 | 获取/设置 是否禁用全面屏手势导航 | `Settings.Secure.navigation_mode`（由 navbar 运行时 overlay 驱动，见 2.5） |
| ASR-0426 | 安卓小动画 | 获取/设置是否禁用安卓小动画 | `Settings.Global.window_animation_scale` / `transition_animation_scale` / `animator_duration_scale` |

**归属**：「Launcher（MDM）+ 系统 API」。六项均对应**受保护系统设置**（`WRITE_SECURE_SETTINGS` 签名权限保护的 Settings.Global / Settings.Secure 键），DevicePolicyManager 的 `setGlobalSetting` / `setSecureSetting` 仅允许白名单键（不含上述任一键），故由平台签名 Launcher（uid=1000，`sharedUserId=android.uid.system`）经 SettingsProvider 直写；ASR-0345 因本 ROM 机制特殊，经 `cmd overlay` shell 命令切换导航栏运行时 overlay（详见 2.5）。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署；bootloader 锁定，不可改动 system 分区。

## 2. 技术选型与可行性核验

### 2.1 共同前提：Settings 直写权限

Launcher manifest 已声明 `android.permission.WRITE_SECURE_SETTINGS`（signature|privileged，平台签名 uid=1000 自动授予）。写入 `Settings.Global` / `Settings.Secure` 的受保护键均需该权限；既有的 `DndUtils`（zen_mode）、`NotificationPolicyManager`（lock_screen_show_notifications）、`DisplayPolicyManager`（stay_on_while_plugged_in）已采用同一路径。真机核验：`settings put global/secure` 各键读写正常（2026-08-04 实测）。

### 2.2 ASR-0166 禁止 captive portal 弹窗

| 方案 | 说明 | 结论 |
|---|---|---|
| `Settings.Global.captive_portal_mode`（0/1） | 0 = 从不检测/弹出 captive portal 对话框；1 = 检测并提示 | 采用（主键） |
| `Settings.Global.captive_portal_detection_enabled`（0/1，API 29+） | 0 = 完全关闭 captive portal 检测（含"网络可能不可用"通知）；1 = 启用检测 | 采用（伴随键，与主键同值写入，保证"禁止弹窗"语义完整） |

**语义**：`disabled=true` → 两键均写 0（禁止弹窗）；`disabled=false` → 两键均写 1（恢复允许，1 为系统默认值；键原本缺失时写入显式 1，等同默认，无害）。真机核验两键可写可读（`settings get global` 返回 0/0、1/1）。

### 2.3 ASR-0204 禁止"不保留活动" / ASR-0205 禁止模拟定位

| 需求 | 键 | 语义映射 |
|---|---|---|
| ASR-0204 | `Settings.Global.always_finish_activities` | 开发者选项"不保留活动"：1 = 开启（退出即销毁 Activity），0 = 关闭。**禁止"不保留活动" = 该选项必须关闭 = 写 0** |
| ASR-0205 | `Settings.Secure.mock_location`（ALLOW_MOCK_LOCATION） | 1 = 允许模拟定位，0 = 禁止。**禁止模拟定位 = 写 0** |

**语义**：`disabled=true` → 键写 0（禁止）；`disabled=false` → 键写 1（恢复允许开发者选项生效）。查询返回键的实际值 + 派生 `disabled`（值==0）。真机核验：两键 `settings put/get` 正常（mock_location 本 ROM 位于 secure 表，初值 0）。

### 2.4 ASR-0314 设置定位模式

`Settings.Secure.location_mode`：0=关闭，1=仅设备（GPS/传感器），2=省电（仅网络），3=高精度（GPS+网络）。LocationManagerService 通过 SettingsObserver 监听该键，直写即时生效（真机核验 3→2→3 往返正常，`settings get secure location_mode` 同步）。`mode` 仅接受 0~3，越界返回 `{error}`。

### 2.5 ASR-0345 禁用全面屏手势导航（本 ROM 机制核验，重要）

**本 ROM（MTK Android 13，MtkSystemUI）的反编译核验**（2026-08-04）：

- `SystemUI.NavigationModeController.getCurrentInteractionMode()` 读取的是 **framework 资源 `config_navBarInteractionMode`**（`resources.getInteger(...)`），并非直接读设置；
- `updateCurrentInteractionMode()` 依据当前生效的 navbar overlay（`com.android.internal.systemui.navbar.gestural` / `threebutton` 等运行时 overlay 包，覆盖该资源）确定模式，并**将结果回写到 `Settings.Secure.navigation_mode`（字符串）**；
- Settings 应用（MtkSettings，`OneHandedSettingsUtils.setNavigationBarMode`）切换手势开关时同样写 `navigation_mode`，且 SystemUI 侧 overlay 切换后导航栏即时生效（无需重启 SystemUI）。

**实测结论**：`navigation_mode` 是 overlay 状态的**镜像，不是真源**。直接 `settings put secure navigation_mode 0` 后：导航栏无变化；重启 SystemUI 后 SystemUI 将值**回写为 2**（gestural overlay 持续生效）。改用 overlay 切换后：`cmd overlay enable navbar.threebutton` + `disable navbar.gestural` → 导航栏切为三键（nav bar inset 变高）、`navigation_mode` 被 SystemUI 回写为 0；反向操作恢复 2。

| 方案 | 说明 | 结论 |
|---|---|---|
| 直写 `Settings.Secure.navigation_mode` | 仅镜像值，SystemUI 不消费（实测无效） | 放弃 |
| `cmd overlay enable/disable com.android.internal.systemui.navbar.{threebutton,gestural}` | **采用**。uid=1000 系统 uid 可执行 shell 命令（与 ASR-0016/0030 的 `cmd deviceidle` 同一模式）；OverlayManagerService 校验 CHANGE_OVERLAY_PACKAGES（signature，uid=1000 持有）；真机核验 overlay 切换后导航栏即时变化、`navigation_mode` 同步回写 | 采用（设置路径） |
| `Settings.Secure.navigation_mode` 读取 | 查询路径：`0` = 三键（手势已禁用）；非 0 = 手势导航开启（本 ROM gestural ↔ 2，AOSP 原生 ↔ 1） | 采用（查询路径） |

**语义**：`disabled=true` → 启用 threebutton overlay + 禁用 gestural overlay（全面屏手势不可用）；`disabled=false` → 反向恢复。命令内轮询 `navigation_mode`（最长 5s）等待 SystemUI 异步回写完成后再判定 success，并附两个 overlay 的实时启用状态。注：本机制为 AOSP 标准导航栏 overlay 机制，移植到原生 ROM 亦适用。

### 2.6 ASR-0426 禁用安卓小动画

| 键 | 含义 |
|---|---|
| `Settings.Global.window_animation_scale` | 窗口动画缩放 |
| `Settings.Global.transition_animation_scale` | 转场动画缩放 |
| `Settings.Global.animator_duration_scale` | 动画时长缩放 |

三键均为字符串型（如 "1.0"）。`disabled=true` → 三键均写 "0"（禁用全部系统动画）；`disabled=false` → 三键均写 "1.0"（恢复默认缩放，标准 MDM 语义，覆盖用户自定义缩放值，文档化）。查询：三键逐一读取，`disabled = 三键均为 "0"`，并原样返回各键值。

## 3. 命令接口定义（SystemApiInterface.onEvent）

### 3.1 新增命令（本批次，12 条）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetCaptivePortalDisabled` | disabled（Boolean，必填） | Map（见下） | ASR-0166 |
| `IsCaptivePortalDisabled` | 无 | Map：{success, disabled, captive_portal_mode, captive_portal_detection_enabled} | ASR-0166 |
| `SetAlwaysFinishActivitiesDisabled` | disabled（必填） | Map：{success, disabled, always_finish_activities} 或 {error} | ASR-0204 |
| `IsAlwaysFinishActivitiesDisabled` | 无 | Map：{success, disabled, always_finish_activities} | ASR-0204 |
| `SetMockLocationDisabled` | disabled（必填） | Map：{success, disabled, mock_location} 或 {error} | ASR-0205 |
| `IsMockLocationDisabled` | 无 | Map：{success, disabled, mock_location} | ASR-0205 |
| `SetLocationMode` | mode（int，必填，0~3） | Map：{success, location_mode} 或 {error} | ASR-0314 |
| `GetLocationMode` | 无 | Map：{success, location_mode} | ASR-0314 |
| `SetGestureNavigationDisabled` | disabled（必填） | Map：{success, disabled, navigation_mode, threeButtonOverlay, gesturalOverlay} 或 {error} | ASR-0345 |
| `IsGestureNavigationDisabled` | 无 | Map：{success, disabled, navigation_mode, threeButtonOverlay, gesturalOverlay} | ASR-0345 |
| `SetAnimationsDisabled` | disabled（必填） | Map：{success, disabled, scales:{三键:值}} 或 {error} | ASR-0426 |
| `IsAnimationsDisabled` | 无 | Map：{success, disabled, scales:{三键:值}} | ASR-0426 |

**Set 类命令返回结构**（以 SetCaptivePortalDisabled 为例）：

```
{success:boolean, disabled:boolean, captive_portal_mode:int,
 captive_portal_detection_enabled:int}
# 写入后逐键读回核对，全部一致才 success=true；
# 任一键未生效 → success=false 并附 error（含期望值）
```

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> p = new HashMap<>();
p.put("disabled", true);
Map result = api.onEvent("SetCaptivePortalDisabled", p);
// {"RESULT":{"success":true,"disabled":true,"captive_portal_mode":0,"captive_portal_detection_enabled":0}}

Map<String, Object> p2 = new HashMap<>();
p2.put("mode", 2);
Map result2 = api.onEvent("SetLocationMode", p2);
// {"RESULT":{"success":true,"location_mode":2}}
```

**广播通道**（TestBroadcast，与现有命令一致）：

```bash
adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast \
  -a ON_SQA_INTERFACE_TEST --es event SetGestureNavigationDisabled \
  --es param '{"disabled":true}'
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/
│   ├── SystemSettingsManager.java          # 新增：六功能引擎（Settings 直写 + cmd overlay，写后读回核对）
│   └── ShellUtils.java                     # 既有：shell 命令执行（cmd overlay 落地）
├── service/command/settings/
│   ├── SetCaptivePortalDisabled.java               # 新增：ASR-0166 设置
│   ├── IsCaptivePortalDisabled.java                # 新增：ASR-0166 查询
│   ├── SetAlwaysFinishActivitiesDisabled.java      # 新增：ASR-0204 设置
│   ├── IsAlwaysFinishActivitiesDisabled.java       # 新增：ASR-0204 查询
│   ├── SetMockLocationDisabled.java                # 新增：ASR-0205 设置
│   ├── IsMockLocationDisabled.java                 # 新增：ASR-0205 查询
│   ├── SetLocationMode.java                        # 新增：ASR-0314 设置
│   ├── GetLocationMode.java                        # 新增：ASR-0314 查询
│   ├── SetGestureNavigationDisabled.java           # 新增：ASR-0345 设置
│   ├── IsGestureNavigationDisabled.java            # 新增：ASR-0345 查询
│   ├── SetAnimationsDisabled.java                  # 新增：ASR-0426 设置
│   └── IsAnimationsDisabled.java                   # 新增：ASR-0426 查询
└── service/ApiBinder.java             # 注册 12 个新命令

testapp/
├── src/main/java/com/hmdm/testapp/
│   ├── SystemSettingsTestActivity.java  # 新增：测试页（六功能设置/查询按钮）
│   ├── TestActions.java                 # 新增 12 个事件（含参数校验）与事件目录
│   └── MainActivity.java                # 增加 "System settings" 入口
├── src/main/res/layout/activity_system_settings_test.xml # 新增测试页布局
└── src/main/AndroidManifest.xml         # 注册 SystemSettingsTestActivity
```

## 5. 执行逻辑

```
SetCaptivePortalDisabled / SetAlwaysFinishActivitiesDisabled / SetMockLocationDisabled
  / SetAnimationsDisabled / SetLocationMode(disabled/mode):
  1. 参数校验（缺参 → {error}，mode 越界 0~3 → {error}）
  2. Settings.Global/Secure 直写目标键（动画为三键，captive portal 为两键）
  3. 逐键读回核对：与期望值一致 → success=true；任一不一致 → success=false + error
  4. 返回 {success, 各键实际值, ...}

SetGestureNavigationDisabled(disabled):
  1. 参数校验（缺参 → {error}）
  2. disabled=true：cmd overlay enable navbar.threebutton + disable navbar.gestural
     disabled=false：cmd overlay enable navbar.gestural + disable navbar.threebutton
  3. 轮询 Settings.Secure.navigation_mode（最长 5s，SystemUI 异步回写）：
     disabled → 期望 0；允许 → 期望非 0（本 ROM 回写 2，AOSP 回写 1）
  4. 附带查询两个 navbar overlay 的启用状态（cmd overlay list 解析 [x] 行）
  5. 返回 {success, disabled, navigation_mode, threeButtonOverlay, gesturalOverlay} 或 {error}

Is* 查询命令：读对应键（gesture 附 overlay 状态），返回实际值与派生状态
```

**安全设计**：`ApiService` 为 exported 且无权限保护（既有设计），但本批全部命令参数为布尔/整数枚举，**无字符串进入 shell**（`cmd overlay` 的包名均为代码内固定常量），无命令注入面；设置键名亦为代码内常量。`cmd overlay` 执行面仅此一处且参数不可由调用方控制。

## 6. 权限与归属

- 六项均为「Launcher（MDM）+ 系统 API」：受保护系统设置（`WRITE_SECURE_SETTINGS`，manifest 既有声明，平台签名 uid=1000 自动授予，真机核验 granted）；
- ASR-0345 额外依赖 shell 命令执行权（uid=1000 系统 uid）与 OverlayManagerService 的 `CHANGE_OVERLAY_PACKAGES`（signature，uid=1000 自动授予）；
- 无 ROM 侧代码改动，无 root 依赖；不修改 AIDL / lib 模块；testapp 无需新增权限。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺 disabled / mode 参数 | 命令返回 {error："missing parameter: ..."}，不 crash；testapp 侧同样拦截 |
| mode 越界（≠0~3） | SetLocationMode 返回 {error："invalid mode: ..."}，不写入 |
| **mode 数值类型差异** | 不同调用通道的数值类型不同：AIDL 直达为 Integer、send_test_command.sh 对负数/非纯数字按字符串发送、TestBroadcast 经 Gson 解析为 Double。命令按 Number/String 兼容解析（`Number.intValue()` / `Integer.parseInt`），负数与非法字符串均被拒绝，正数各通道行为一致（初测 mode=-1 误报 success 的根因即类型差异，修复后复测通过） |
| Settings 写失败或读回不一致 | success=false + error（含期望值/实际值），如实上报，调用方可重试 |
| 键原本缺失（null） | 读取时按语义默认值处理（captive portal 默认 1、always_finish_activities 默认 0、mock_location 默认 0）；恢复路径写入显式默认值，无害 |
| ASR-0426 用户自定义缩放值（如 0.5） | disabled=false 统一写 1.0（标准 MDM 恢复语义，文档化） |
| overlay 切换后 SystemUI 未及时回写 navigation_mode | 轮询最长 5s；超时 success=false + error（附实际值），不报假成功 |
| 目标 ROM 无 navbar overlay（非 AOSP 结构） | threeButtonOverlay/gesturalOverlay 均为 false，navigation_mode 不变 → success=false，文档化限制 |
| `cmd overlay` 执行失败（shell 不可用） | ShellUtils.exec 返回 null → 轮询超时 → success=false |

## 8. 真机验证记录（2026-08-04，Android 13 / API 33 userdebug，平台签名 + device owner）

（命令级用例结果见测试用例设计文档，此处记录机制核验关键结论）

| 核验项 | 结果 |
|---|---|
| Settings 直写权限 | 平台签名 uid=1000 持有 WRITE_SECURE_SETTINGS；六类键 `settings put/get` 均正常 |
| ASR-0345 机制反编译核验 | MtkSystemUI `NavigationModeController.getCurrentInteractionMode` 读框架资源（overlay 决定），`navigation_mode` 为 SystemUI 回写的镜像值 |
| ASR-0345 直写设置无效 | `settings put secure navigation_mode 0` → 导航栏无变化；重启 SystemUI 后值被回写为 2（gestural overlay 生效中） |
| ASR-0345 overlay 切换有效 | `cmd overlay enable navbar.threebutton` + `disable navbar.gestural` → 导航栏切三键、`navigation_mode` 回写 0；反向操作恢复 2（gestural） |
| 导航模式值语义 | 本 ROM：0=三键（手势禁用），2=gestural（全面屏手势）；查询以 `navigation_mode==0` 判定手势已禁用 |
| 测试后设备恢复 | 全部键恢复原值：location_mode=3、mock_location=0、captive_portal_mode=1、navigation_mode=2（gestural overlay 启用） |
