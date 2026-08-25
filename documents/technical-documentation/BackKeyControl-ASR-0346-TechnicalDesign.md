# BACK 键管控（ASR-0346）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 底层机制 |
|---|---|---|---|
| ASR-0346 | BACK 键 | 获取/设置 是否禁用 BACK 键 | `StatusBarManager.DISABLE_BACK`（0x400000）禁用标志（system_server 持有）+ SharedPreferences 策略持久化 |

**归属**：「Launcher（MDM）」（2026-08-03 复核维持：公开 SDK 可见 `StatusBarManager` 类，隐藏 `disable(int)` 方法经平台签名 uid=1000 反射调用，无 ROM 改动）。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署；bootloader 锁定，不可改动 system 分区。

**达成度说明（2026-08-12 真机核验）**：三键导航模式的 BACK 按键可完全禁用（按键隐藏、不可点击）；全面屏手势模式的边缘返回手势**不受本机制控制**（本 ROM SystemUI 的 SysUiState back-disabled 标志不来源于 DISABLE_BACK 位，framework 亦无 `setSystemUiState` 通道，详见 2.4），需求维持**部分完成**，待 ROM 适配后手势侧可补全。

## 2. 技术选型与可行性核验

### 2.1 候选方案对比

| 方案 | 说明 | 结论 |
|---|---|---|
| `StatusBarManager.DISABLE_BACK` 禁用标志 | AOSP 标准"禁用 BACK 键"通道：标志位由 system_server 的 StatusBarManagerService 持有，经 CommandQueue 下发 SystemUI；MtkSystemUI `NavigationBarView.updateNavButtonIcons` 消费该位（dex 核验见 2.3） | **采用** |
| shell `cmd statusbar send-disable-flag back` | ASR-0059 同通道；**本 ROM StatusBarShellCommand 的合法标志列表不含 "back"**（`cmd statusbar help` 实测仅有 none/search/home/recents/notification-*/system-icons/clock/statusbar-expansion/notification-peek，2026-08-12） | 放弃 |
| 锁任务（lock task） | Sheet1 前期核验：锁任务禁 HOME/最近任务，**BACK 键未管控**（锁任务内仍可逐页返回） | 放弃（BACK 管控主通道） |
| 全局按键拦截（InputManager / accessibility） | 无系统级公开 API；无障碍服务仅能拦截自身窗口事件 | 放弃 |

### 2.2 设置通道：反射 `StatusBarManager.disable(int)`

`StatusBarManager.disable(int what)`（@hide，hiddenapi=UNSUPPORTED）经 `IStatusBarService.disableForUser(what, token, pkg, userId)` 写入调用方自身的 DisableRecord（按 userId+token 独立记录，**整值替换本记录、不影响其他记录的位**——shell 命令与 lock task 等记录的标志互不干扰），最终聚合进 system_server 的 `mDisabled1` 并下发 SystemUI。平台签名 uid=1000 持有 `STATUS_BAR` 签名权限（自动授予，无需 manifest 新增声明），且平台签名应用豁免 hidden API 限制，反射调用可行（与既有 AppOps/PowerManager 反射引擎同模式）。

- `disabled=true` → `disable(0x400000)`（DISABLE_BACK）
- `disabled=false` → `disable(0)`（清空 Launcher 自身记录）

### 2.3 SystemUI 消费侧核验（MtkSystemUI dex 反编译，2026-08-12）

- `NavigationBarView.updateNavButtonIcons()`：`mDisabledFlags & 0x400000 != 0` 时对 back 按钮执行 `setVisibility(INVISIBLE)`（INVISIBLE 而非 GONE，按钮不可见且不再接收点击），IMAX 渲染导航按钮/屏幕固定（screen pinning）场景例外放行；
- `NavigationBarView.updateDisabledSystemUiStateFlags(SysUiState)`：mDisabledFlags → SysUiState 仅映射 屏幕固定(0x1)/最近任务(0x80)/HOME(0x100)/搜索(0x400)，**无 BACK 位映射**；
- `QuickStepContract.isBackGestureDisabled(SysUiState)` 检查的 back-disabled 位（0x8）由 `OverviewProxyService.onStatusBarStateChanged` 从窗口回调第三参数写入，该参数在本 ROM `NotificationShadeWindowControllerImpl.notifyStateChangedCallbacks` 中为 `mBouncerShowing`（AOSP 原生为 mBackGestureDisabled）——**本 ROM 边缘返回手势与 DISABLE_BACK 完全脱钩**；
- framework.jar（API 33 fork）中 `StatusBarManager` 无 `setSystemUiState` 方法（strings 核验），系统侧亦无外部通道可置位该标志。

**结论**：DISABLE_BACK 位仅对三键导航的 BACK 按键生效；全面屏手势模式不受控，属 ROM 限制（需 ROM 在 `updateDisabledSystemUiStateFlags` 增加 BACK→SysUiState 映射或恢复 `setSystemUiState` 通道）。

### 2.4 读回核对

`dumpsys statusbar` 解析 `mDisabled1=0x...` 的 0x400000 位（与 ASR-0059 `statusBarIconsDisabled` 同解析模式）。写后必须位值匹配才判定 success=true；dump 不可用时仅信任调用成功（`liveDisabled` 返回 null 附注）。

### 2.5 持久化与重新武装

策略标志持久化 Launcher SharedPreferences `back_key_policy`（键 `back_key_disabled`）。进程重启（ApiService.onCreate）与开机（BootCompletedReceiver）经 `SystemSettingsManager.syncBackKeyPolicy` 重新下发（幂等，仅在持久化标志为 true 时重放）。系统侧禁用标志由 system_server 持有，整机重启清零，依赖重新武装恢复。

## 3. 命令接口定义

### 3.1 新增命令（2 条）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetBackKeyDisabled` | disabled（Boolean，必填） | Map：{success, disabled, liveDisabled, navigation_mode} 或 {error} | ASR-0346 |
| `IsBackKeyDisabled` | 无 | Map：{success, disabled, liveDisabled, navigation_mode} | ASR-0346 |

**返回字段说明**：

- `disabled`：策略意图（持久化标志；Set 命令为本次请求值，Is 命令为上次成功应用值）；
- `liveDisabled`：最近一次写路径核验的实时标志（SharedPreferences `back_key_live_disabled` 缓存，写路径/重新武装时刷新）；**查询路径不执行 dumpsys**（与 ASR-0059 查询先例一致），非"查询瞬间"的实时值；
- `navigation_mode`：当前导航模式（0=三键，2=本 ROM gestural），供调用方判断 BACK 键的生效形态（按键 vs 手势）。

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> p = new HashMap<>();
p.put("disabled", true);
Map result = api.onEvent("SetBackKeyDisabled", p);
// {"RESULT":{"success":true,"disabled":true,"liveDisabled":true,"navigation_mode":2}}

Map result2 = api.onEvent("IsBackKeyDisabled", null);
// {"RESULT":{"success":true,"disabled":true,"liveDisabled":true,"navigation_mode":2}}
```

**广播通道**（TestBroadcast，与现有命令一致）：

```bash
adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast \
  -a ON_SQA_INTERFACE_TEST --es event SetBackKeyDisabled \
  --es param '{"disabled":true}'
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/
│   └── SystemSettingsManager.java          # 新增：setBackKeyDisabled / isBackKeyDisabled /
│                                           #       syncBackKeyPolicy / applyBackKeyFlag /
│                                           #       statusBarBackDisabled（反射 disable + dumpsys 读回）
├── service/command/settings/
│   ├── SetBackKeyDisabled.java             # 新增：ASR-0346 设置
│   └── IsBackKeyDisabled.java              # 新增：ASR-0346 查询
└── service/ApiBinder.java                  # 注册 2 条命令
    service/ApiService.java                 # onCreate 增 syncBackKeyPolicy
broadcast/BootCompletedReceiver.java        # 开机增 syncBackKeyPolicy

testapp/
└── src/main/java/com/hmdm/testapp/TestActions.java  # 新增 Set/IsBackKeyDisabled 事件（含缺参校验）与事件目录
```

## 5. 执行逻辑

```
SetBackKeyDisabled(disabled):
  1. 参数校验（缺参 → {error:"missing parameter: disabled"}）
  2. 反射 StatusBarManager.disable(disabled ? 0x400000 : 0)（替换 Launcher 自身 DisableRecord）
  3. dumpsys statusbar 解析 mDisabled1 位（0x400000）读回核对——短重试（5×200ms，
     吸收 dump 锁竞争/一次性失败）：
     位值 == disabled → success=true，持久化 back_key_disabled（意图）+
       back_key_live_disabled（核验位）
     位值 != disabled 或重试后仍不可读 → success=false + error（含期望/实际），
       不持久化（保留原策略，fail-safe：未证实启用/禁用时维持原状）
  4. 附 navigation_mode
  5. 返回 {success, disabled, liveDisabled, navigation_mode} 或 {error}

IsBackKeyDisabled():
  1. 读 SharedPreferences：disabled（意图）+ liveDisabled（最近核验位，默认随意图）
  2. 附 navigation_mode，返回 {success, disabled, liveDisabled, navigation_mode}
     （查询路径不执行 dumpsys，与 ASR-0059 查询先例一致）

syncBackKeyPolicy(): 意图为 true 时重放 applyBackKeyFlag(ctx, true)，
  并读回核验成功后刷新 liveDisabled 缓存
```

**安全设计**：`ApiService` exported 且无权限保护（既有设计），但本命令参数仅布尔，无字符串进入反射/shell，无注入面；反射目标类/方法名、标志位均为代码内常量。

## 6. 权限与归属

- 「Launcher（MDM）」：`STATUS_BAR` 签名权限（平台签名 uid=1000 自动授予，无需 manifest 新增声明）满足 StatusBarManagerService 的调用校验；
- 无 ROM 侧代码改动，无 root 依赖；不修改 AIDL / lib 模块；testapp 无需新增权限。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺 disabled 参数 | 命令返回 {error:"missing parameter: disabled"}，不 crash；testapp 侧同样拦截 |
| 反射失败（类/方法缺失、SecurityException） | applyBackKeyFlag 返回 false → success=false + error，如实上报 |
| dumpsys statusbar 不可用 | 写路径短重试（5×200ms）后仍不可读 → success=false + error，**不持久化**（保留原策略，fail-safe）；查询路径不执行 dumpsys，liveDisabled 为最近核验缓存 |
| 查询路径性能 | Is* 查询仅读 SharedPreferences，不触发系统 dump（与 ASR-0059 查询先例一致） |
| 其他组件持有同 token 的 DisableRecord | Launcher 进程内唯一 StatusBarManager 实例，disable() 整值替换自身记录，不与 shell/其他应用记录冲突（真机核验：shell record 与 Launcher record 独立聚合） |
| 全面屏手势模式（navigation_mode=2） | 命令正常置位（liveDisabled=true），但边缘返回手势仍可用——ROM 限制，命令如实返回 navigation_mode 供调用方判断，文档化 |
| 进程被杀 / 整机重启 | 系统标志随 system_server 记录清空；进程重启/开机 syncBackKeyPolicy 按持久化标志重新下发（真机核验通过） |
| 反复设置同值 | disable() 幂等；位核对一致即 success=true |

## 8. 真机验证记录（2026-08-12，Android 13 / API 33 userdebug，平台签名 + device owner）

（命令级用例结果见测试用例设计文档，此处记录机制核验关键结论）

| 核验项 | 结果 |
|---|---|
| 本 ROM shell 通道无 "back" 标志 | `cmd statusbar help` 合法标志列表仅 none/search/home/recents/notification-*/system-icons/clock/statusbar-expansion/notification-peek；`send-disable-flag 4194304`（数值）无效果、mDisabled1 不变 |
| SystemUI 消费 DISABLE_BACK | MtkSystemUI `NavigationBarView.updateNavButtonIcons`：`mDisabledFlags & 0x400000` → back 按钮 setVisibility(INVISIBLE)（dex 反编译核验） |
| 手势与标志脱钩 | `updateDisabledSystemUiStateFlags` 无 BACK→SysUiState 映射；`NotificationShadeWindowControllerImpl.notifyStateChangedCallbacks` 第三参数为 mBouncerShowing（AOSP 原生为 mBackGestureDisabled）；framework.jar 无 setSystemUiState |
| 反射 disable 置位 | `SetBackKeyDisabled disabled=true` → success=true、liveDisabled=true、`dumpsys statusbar` mDisabled1=0x1400000（含 0x400000 位） |
| 三键模式按键隐藏 | 3-button overlay 下截图对比：启用时导航栏 3 个图标（back x≈180-191 / home x≈351-368 / recents x≈525-554），禁用后 back 图标消失、home/recents 不变（像素级核验） |
| 手势模式限制 | 置位后（gestural）从 Settings 页执行边缘滑动返回，页面仍可返回（打开状态退出到桌面）——返回手势不受控 |
| 进程重启重新武装 | 置位后 force-stop Launcher 并重启 ApiService → mDisabled1=0x400000（syncBackKeyPolicy 重放成功）、IsBackKeyDisabled=true |
| 代码审查后复测（查询路径无 dumpsys） | 重构后复测全链路：置位（读回重试核验通过）→ 查询（缓存 liveDisabled=true）→ 重启重新武装 → 复位（mDisabled1=0x0）→ 查询（liveDisabled=false），全部通过 |
| 测试后设备恢复 | SetBackKeyDisabled disabled=false（mDisabled1=0x0）、SetGestureNavigationDisabled disabled=false（navigation_mode=2 gestural overlay 启用），基线恢复 |
