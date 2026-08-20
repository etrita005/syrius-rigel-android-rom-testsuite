# 定位/导航栏/飞行模式管控（ASR-0310/0311/0319/0320/0321/0349）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 实现机制 |
|---|---|---|---|
| ASR-0310 | 定位服务 | 禁用/启用定位服务 | 直写 `Settings.Secure.location_mode`（禁用=保存当前模式并写 0，启用=恢复保存的模式） |
| ASR-0311 | 定位服务 | 关闭/打开/强制打开定位服务 | 同 ASR-0310 引擎（关闭/打开）；强制打开=打开 + 10 秒周期纠正器（用户关闭后被重新打开） |
| ASR-0319 | 飞行模式 | 禁用/启用 飞行模式 | 直写 `Settings.Global.airplane_mode_on` + 平台广播 `ACTION_AIRPLANE_MODE_CHANGED` |
| ASR-0320 | 飞行模式 | 关闭/打开 飞行模式 | 同 ASR-0319 引擎（ASR-0319/0320 共用 Set/Is 命令，与 WLAN ASR-0140/0142 同模式） |
| ASR-0321 | 飞行模式 | 强制打开 飞行模式 | 打开 + 10 秒周期纠正器（用户关闭后被重新应用） |
| ASR-0349 | 导航栏 | 查询/是否 启用导航栏 | 直写 `Settings.System.navigation_visible`（MTK 键，1=显示、0=隐藏；计划目标的 `navigation_mode` 经核验为本 ROM 镜像值，见 2.3） |

**归属**：ASR-0310/0311/0319 为「Launcher（MDM）」，ASR-0320/0321/0349 为「Launcher（MDM）+ 系统 API」。六项均落地为**受保护系统设置直写**（`WRITE_SECURE_SETTINGS` 签名权限，平台签名 Launcher uid=1000 自动授予）+ **平台广播**（飞行模式），替换既有依赖定制 ROM 响应的广播方案（`custom.intent.action.OPEN_GPS/CLOSE_GPS`、`ENABLE_NAV_BAR/DISABLE_NAV_BAR`）。无 ROM 侧代码改动。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署；bootloader 锁定。测试设备基线（2026-08-05 实测）：`location_mode=3`（高精度）、`navigation_mode=2`（gestural overlay 生效中）、`navigation_visible=1`（导航栏显示）、`airplane_mode_on=0`。

## 2. 技术选型与可行性核验

### 2.1 ASR-0310/0311 定位服务（直写 location_mode）

`Settings.Secure.location_mode`：0=关闭、1=仅设备（传感器）、2=省电（仅网络）、3=高精度（GPS+网络）。LocationManagerService 经 SettingsObserver 监听该键即时生效（ASR-0314 已于 2026-08-04 真机核验 3→2→3 往返正常）。本批次替换 ROM 广播 `custom.intent.action.OPEN_GPS/CLOSE_GPS`：

| 方案 | 说明 | 结论 |
|---|---|---|
| ROM 广播 OPEN_GPS/CLOSE_GPS | 依赖定制 ROM 响应（Sheet1 原"部分完成"缺口） | 放弃 |
| 直写 `location_mode`（平台签名 uid=1000，WRITE_SECURE_SETTINGS） | 既有 ASR-0314 同键同权限路径，已核验即时生效 | 采用（设置路径） |

**语义设计**（与 WLAN/蓝牙"禁用/启用"+"关闭/打开"双需求共用同一引擎的模式一致）：
- 禁用/关闭（`enabled=false`）：当前模式非 0 时保存到 Launcher SharedPreferences（`location_policy` 的 `savedMode`），写 0；已是 0 则不写；
- 启用/打开（`enabled=true`）：当前为 0 时恢复 `savedMode`（无保存记录时默认 3=高精度）；已开启则保持当前模式不动（不覆盖用户在当前开启期间调整的模式）；
- 写后读回核对，一致才 `success=true`。

**强制打开（ASR-0311）**：`forceOpen=true` 时持久化标志（`location_policy` 的 `forceOpen`）并启动 10 秒周期纠正器（静态 ScheduledThreadPoolExecutor，与 StatusControlService 的 10 秒轮询同模式）：每次 tick 若 `location_mode==0` 则重写为 `savedMode`（或 3）。用户/其他应用关闭定位后被重新打开。`forceOpen=false` 停止纠正器并清标志。**持久化**：标志存于 SharedPreferences，进程重启（`ApiService.onCreate`）与开机（`BootCompletedReceiver`）时经 `syncForceOpen` 重新武装并立即纠正一次——真机核验：强制开启中杀掉 Launcher 进程后重新拉起，`forceOpen=true` 保留、定位被手动关闭后 10 秒内恢复。

### 2.2 ASR-0319/0320/0321 飞行模式（airplane_mode_on + 平台广播）

与 Settings 应用（SettingsLib）完全相同的两段式机制：

1. `Settings.Global.airplane_mode_on` 直写 1/0（WRITE_SECURE_SETTINGS，uid=1000）；
2. 发送平台广播 `Intent.ACTION_AIRPLANE_MODE_CHANGED`（extra `state`）到当前用户（`Process.myUserHandle()`；本 ROM fork SDK 的 `UserHandle` stub 被裁剪、无 `ALL` 常量，单用户设备下 `myUserHandle()` 与 Settings 的 `UserHandle.ALL` 到达相同系统接收者——phone 进程的 AirplaneModeChangeReceiver 驱动射频开关、ConnectivityService 处理网络栈）。

**真机核验（2026-08-05）**：
- 仅直写 `airplane_mode_on`（不发广播）→ `dumpsys wifi` 无 `AirplaneModeOn` 行、Wi-Fi 状态不变：**不生效**；
- 仅发广播（不写设置）→ 设置值不变化、无生效迹象：**不生效**；
- 直写 + 广播（本实现路径）→ `dumpsys wifi` 出现 `AirplaneModeOn true`、`dumpsys telephony.registry` 射频电源按 SIM 卡关闭（mRadioPowerState=0）、蓝牙关闭；关闭方向恢复 `AirplaneModeOn false`。**两步缺一不可，本实现两步都做**。

**语义**：ASR-0319 禁用/启用与 ASR-0320 关闭/打开共用命令 `SetAirplaneMode`/`IsAirplaneMode`（与 WLAN ASR-0140/0142、蓝牙 ASR-0171/0172 同模式）；ASR-0321 强制打开 = `ForceOpenAirplaneMode forceOpen=true`：打开 + 持久化标志（`airplane_policy`）+ 10 秒纠正器（每次 tick 若 `airplane_mode_on!=1` 则重写 1 并重发广播），进程重启/开机重新武装。

**边界说明**：强制打开只保证"飞行模式保持开启"，不拦截设置页入口（入口封锁属 ASR-0322，不在本批次范围）；本 ROM 在飞行模式下 Wi-Fi 保持启用（MTK 行为，`dumpsys wifi` 显示 enabled），射频与蓝牙被关闭，为 ROM 自身策略。

### 2.3 ASR-0349 导航栏（navigation_visible，本 ROM 机制核验，重要）

Sheet1 P1 规划目标为 `Settings.Secure.navigation_mode`。但 ASR-0345（2026-08-04）已核验本 ROM 的 `navigation_mode` **是导航栏运行时 overlay 状态的镜像值**（SystemUI 从 overlay 资源推导后回写），直写无效。2026-08-05 经设备检查发现本 ROM 存在独立的 MTK 键 `Settings.System.navigation_visible`（缺省 1），实测其**真实控制导航栏窗口可见性**：

| 方案 | 说明 | 结论 |
|---|---|---|
| 直写 `Settings.Secure.navigation_mode` | 仅镜像值，SystemUI 不消费（ASR-0345 已核验） | 放弃 |
| 直写 `Settings.System.navigation_visible`（1=显示、0=隐藏） | 采用。真机核验：写 0 后 `dumpsys window windows` 的 NavigationBar0 窗口 `isVisible=false`、`mDrawState=NO_SURFACE`（栏被隐藏）；写 1 恢复 `isVisible=true`。MTK 定制键，本 ROM 专有（AOSP 无此键，移植他 ROM 需评估替代：`Settings.Global.policy_control` immersive 或厂商键） | 采用（设置路径） |
| 查询路径 | `navigation_visible==1` 即导航栏启用；顺带返回 `navigation_mode` 供对照 | 采用（查询路径） |

**语义**：`enabled=true` → `navigation_visible=1`（显示导航栏）；`enabled=false` → `navigation_visible=0`（隐藏导航栏）。与 ASR-0345（手势导航三键/手势切换）互不干扰：本命令只控制栏的显示/隐藏，不改变三键/手势模式（测试中 `navigation_mode` 保持 2=gestural）。

## 3. 命令接口定义（SystemApiInterface.onEvent）

### 3.1 新增命令（本批次，8 条）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetLocationEnabled` | enabled（Boolean，必填） | Map：{success, enabled, location_mode, savedMode} 或 {error} | ASR-0310/0311 |
| `IsLocationEnabled` | 无 | Map：{success, enabled, location_mode, savedMode, forceOpen} | ASR-0310/0311 |
| `ForceOpenLocation` | forceOpen（Boolean，必填） | Map：{success, forceOpen, location_mode, savedMode} 或 {error} | ASR-0311 |
| `SetAirplaneMode` | enabled（Boolean，必填） | Map：{success, enabled, airplane_mode_on} 或 {error} | ASR-0319/0320 |
| `IsAirplaneMode` | 无 | Map：{success, enabled, airplane_mode_on, forceOpen} | ASR-0319/0320 |
| `ForceOpenAirplaneMode` | forceOpen（Boolean，必填） | Map：{success, forceOpen, airplane_mode_on} 或 {error} | ASR-0321 |
| `SetNavigationBarEnabled` | enabled（Boolean，必填） | Map：{success, enabled, navigation_visible, navigation_mode} 或 {error} | ASR-0349 |
| `IsNavigationBarEnabled` | 无 | Map：{success, enabled, navigation_visible, navigation_mode} | ASR-0349 |

**Set 类命令返回结构**（以 SetLocationEnabled 为例）：

```
{success:boolean, enabled:boolean, location_mode:int, savedMode:int}
# 写入后读回核对，一致才 success=true；不一致 → success=false + error（含期望值/实际值）
```

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> p = new HashMap<>();
p.put("enabled", false);
Map result = api.onEvent("SetLocationEnabled", p);
// {"RESULT":{"location_mode":0,"savedMode":3,"success":true,"enabled":false}}

Map<String, Object> f = new HashMap<>();
f.put("forceOpen", true);
Map result2 = api.onEvent("ForceOpenAirplaneMode", f);
// {"RESULT":{"airplane_mode_on":1,"forceOpen":true,"success":true}}
```

**广播通道**（TestBroadcast，与现有命令一致）：

```bash
adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast \
  -a ON_SQA_INTERFACE_TEST --es event SetNavigationBarEnabled \
  --es param '{"enabled":false}'
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/
│   ├── LocationPolicyManager.java         # 新增：定位开关/保存恢复/强制打开引擎（location_mode 直写 + 10s 纠正器 + SharedPreferences 持久化）
│   ├── AirplaneModePolicyManager.java     # 新增：飞行模式引擎（airplane_mode_on 直写 + ACTION_AIRPLANE_MODE_CHANGED 平台广播 + 10s 纠正器）
│   └── SystemSettingsManager.java         # 扩展：导航栏（navigation_visible 直写 + navigation_mode 对照）
├── service/command/location/
│   ├── SetLocationEnabled.java            # 新增：ASR-0310/0311 设置
│   ├── IsLocationEnabled.java             # 新增：ASR-0310/0311 查询
│   └── ForceOpenLocation.java             # 新增：ASR-0311 强制打开
├── service/command/airplane/
│   ├── SetAirplaneMode.java               # 新增：ASR-0319/0320 设置
│   ├── IsAirplaneMode.java                # 新增：ASR-0319/0320 查询
│   └── ForceOpenAirplaneMode.java         # 新增：ASR-0321 强制打开
├── service/command/settings/
│   ├── SetNavigationBarEnabled.java       # 新增：ASR-0349 设置
│   └── IsNavigationBarEnabled.java        # 新增：ASR-0349 查询
├── service/ApiBinder.java                 # 注册 8 个新命令
├── service/ApiService.java                # onCreate 重新武装两个强制纠正器（进程重启）
└── broadcast/BootCompletedReceiver.java   # 开机重新武装两个强制纠正器

testapp/
├── src/main/java/com/hmdm/testapp/
│   ├── DeviceStateTestActivity.java       # 新增：测试页（定位/飞行模式/导航栏 13 个按钮）
│   ├── TestActions.java                   # 新增 8 个事件（含参数校验）与事件目录
│   ├── MainActivity.java                  # 增加 "Device state" 入口
│   └── AndroidManifest.xml                # 注册 DeviceStateTestActivity
└── src/main/res/layout/activity_device_state_test.xml # 新增测试页布局
```

## 5. 执行逻辑

```
SetLocationEnabled(enabled):
  1. 参数校验（缺 enabled → {error}）
  2. enabled=false 且当前模式非 0 → 保存 savedMode
  3. 计算目标值：enabled=true → 当前非 0 则保持，为 0 则 savedMode（无记录=3）；enabled=false → 0
  4. Settings.Secure 直写 location_mode，读回核对 → success
  5. 返回 {success, enabled, location_mode, savedMode}

ForceOpenLocation(forceOpen):
  1. 参数校验（缺 forceOpen → {error}）
  2. true：持久化标志；当前为 0 则立即恢复 savedMode（或 3）；启动 10s 纠正器
  3. false：清标志；停止纠正器
  4. 返回 {success, forceOpen, location_mode, savedMode}

SetAirplaneMode(enabled):
  1. 参数校验（缺 enabled → {error}）
  2. Settings.Global 直写 airplane_mode_on（1/0）
  3. 发送 ACTION_AIRPLANE_MODE_CHANGED 广播（extra state=enabled，Process.myUserHandle()）
  4. 读回 airplane_mode_on 核对 → success
  5. 返回 {success, enabled, airplane_mode_on}

ForceOpenAirplaneMode(forceOpen):
  1. 参数校验（缺 forceOpen → {error}）
  2. true：持久化标志；立即打开（写 + 广播）；启动 10s 纠正器
  3. false：清标志；停止纠正器
  4. 返回 {success, forceOpen, airplane_mode_on}

SetNavigationBarEnabled(enabled):
  1. 参数校验（缺 enabled → {error}）
  2. Settings.System 直写 navigation_visible（1/0），读回核对 → success
  3. 附报 navigation_mode（对照值）
  4. 返回 {success, enabled, navigation_visible, navigation_mode}

Is* 查询命令：读对应键 + 持久化标志，返回实际值与派生状态
```

**安全设计**：本批命令参数全部为布尔值，无字符串进入 shell / 系统命令，无命令注入面；设置键名均为代码内常量；飞行模式广播为系统标准广播（Settings 应用同路径），无权限面扩大。

## 6. 权限与归属

- 六项均为受保护系统设置直写：`WRITE_SECURE_SETTINGS`（manifest 既有声明，平台签名 uid=1000 自动授予，真机核验 granted）；`Settings.System.navigation_visible` 与 `Settings.Global.airplane_mode_on`、`Settings.Secure.location_mode` 同权限域；
- 飞行模式额外依赖平台广播发送权（uid=1000 系统 uid，`sendBroadcastAsUser` 到当前用户）与系统接收者（PhoneInterfaceManager / ConnectivityService / WifiService，framework 侧既有）；
- 无 ROM 侧代码改动，无 root 依赖；不修改 AIDL / lib 模块；testapp 无需新增权限；不修改 `device_admin.xml`，无需重启 framework。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺 enabled / forceOpen 参数 | 命令返回 {error："missing parameter: ..."}，不 crash；testapp 侧同样拦截 |
| 定位禁用时当前模式已是 0 | 不重复保存，写 0 幂等；启用时恢复上次保存值 |
| 定位启用但无 savedMode 记录 | 默认 3=高精度（文档化） |
| 启用时用户已自行调整过模式（非 0） | 保持用户模式不动，仅确保非 0（语义=开启） |
| Settings 写失败或读回不一致 | success=false + error（含期望值/实际值），如实上报，可重试 |
| 飞行模式广播发出但系统未完全应用（如射频异步） | 命令只核对 airplane_mode_on 键；射频/网络栈生效以 `dumpsys wifi` 的 AirplaneModeOn / `dumpsys telephony.registry` 为准（验证时对照） |
| 强制纠正器运行时用户/应用再次关闭 | 10 秒内被重新打开；`forceOpen=false` 停止纠正并如实保持用户状态 |
| 进程被杀 / 重启 / 开机 | 标志持久化于 SharedPreferences；ApiService.onCreate 与 BootCompletedReceiver 重新武装并立即纠正一次（真机核验通过） |
| 本 ROM 飞行模式下 Wi-Fi 保持启用 | ROM 自身行为（MTK），非本实现缺陷，文档化 |
| 无 `navigation_visible` 键的 ROM | MTK 定制键，AOSP 无此键；移植需评估替代机制（policy_control immersive 等），文档化 |
| 强制打开与设置页入口 | 本批仅保证状态被纠正，不拦截设置页入口（ASR-0322 用户修改飞行模式不在本批范围） |

## 8. 真机验证记录（2026-08-05，Android 13 / API 33 userdebug，平台签名 + device owner）

（命令级用例结果见测试用例设计文档，此处记录机制核验关键结论）

| 核验项 | 结果 |
|---|---|
| 设备基线 | location_mode=3、navigation_mode=2（gestural）、navigation_visible=1、airplane_mode_on=0 |
| ASR-0310/0311 定位开关 | 禁用 → location_mode=0（savedMode=3）；`settings get secure location_mode`=0；启用 → 恢复 3，系统对照一致；写后读回核对均 success=true |
| ASR-0311 定位强制 | forceOpen=true → 用户直写 0 后 12 秒内纠正器恢复 3；forceOpen=false 后不再纠正（13 秒观察窗口保持 0） |
| ASR-0311 强制持久化 | 强制开启中 `am force-stop com.hmdm.launcher` 重启进程 → IsLocationEnabled 报 forceOpen=true，手动关闭后 12 秒内恢复 3（ApiService.onCreate 重新武装） |
| ASR-0319/0320 飞行开关 | 打开 → airplane_mode_on=1 + `dumpsys wifi` "AirplaneModeOn true"；关闭 → 0 + "AirplaneModeOn false" |
| 飞行模式机制分段核验 | 仅写键不生效、仅广播不生效、写键+广播生效（`dumpsys wifi` AirplaneModeOn、telephony.registry 射频电源、蓝牙状态）——两步缺一不可 |
| ASR-0321 飞行强制 | forceOpen=true → 用户写 0+广播关闭后 12 秒内纠正器重开（AirplaneModeOn true）；forceOpen=false 后不再纠正 |
| ASR-0349 导航栏 | 禁用 → navigation_visible=0 + `dumpsys window windows` NavigationBar0 `isVisible=false`/NO_SURFACE；启用 → 1 + `isVisible=true`；navigation_mode 全程保持 2（不影响三键/手势模式） |
| ASR-0349 方案核验 | `navigation_mode` 为本 ROM overlay 镜像值（ASR-0345 已有结论），直写无效；`Settings.System.navigation_visible` 为本 ROM 真实控制键（MTK 定制），写 0 隐藏 / 写 1 显示实测生效 |
| 测试后设备恢复 | location_mode=3、navigation_mode=2、navigation_visible=1、airplane_mode_on=0、AirplaneModeOn false，无残留 |
