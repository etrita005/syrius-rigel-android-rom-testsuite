# 显示模式管控：自动休眠开关与一直全屏（ASR-0412/0431）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 实现机制 |
|---|---|---|---|
| ASR-0412 | 休眠/唤醒 | 禁用/启用自动休眠 | `Settings.System.screen_off_timeout` 置 `Integer.MAX_VALUE`（永不休眠）/恢复捕获值 |
| ASR-0431 | 设置 是否一直全屏 | 设置 是否一直全屏 | 隐藏状态栏（`dpm.setStatusBarDisabled`）+ 隐藏导航栏（`Settings.System.navigation_visible=0`），启用恢复 |

**归属**：ASR-0412 为 Launcher（MDM）（受保护设置直写，平台签名 uid=1000）；ASR-0431 为 Launcher（MDM）+ 系统 API（device owner DPM 策略 + 受保护设置）。无 ROM 侧代码改动。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署。

## 2. 技术选型与可行性核验

### 2.1 ASR-0412 自动休眠开关

- 本 ROM 既有 `DisplayPolicyManager.setScreenNeverSleep`（`Settings.System.SCREEN_OFF_TIMEOUT = Integer.MAX_VALUE`）为"屏幕常亮"机制（`isScreenAlwaysOn` 同口径判断），ASR-0412 在此基础上补齐**开关语义**：
  1. 禁用自动休眠：捕获当前 `screen_off_timeout` 持久化于 `auto_sleep_policy`（`previousTimeoutMs`），写入 `Integer.MAX_VALUE`（约 24.8 天，Settings UI 上限 30 分钟但键本身接受全 int 范围，PowerManagerService 按值生效）；
  2. 启用自动休眠：恢复捕获值（无捕获值回退 30s 默认）；
  3. 标志 `disabled` 持久化，进程重启/开机 `syncPolicy` 重新置位；
- 与 ASR-0413（设置自动休眠时长，`SetSleepTimeOut` 既有命令）正交：0413 设具体时长，0412 是"禁止自动休眠"总开关。

### 2.2 ASR-0431 一直全屏

- 全屏=隐藏系统两条 chrome：状态栏 + 导航栏：
  1. 导航栏：`Settings.System.navigation_visible = 0`（MTK 键，ASR-0349 批次真机核验过的导航栏可见性控制键），启用时捕获原值持久化（`fullscreen_policy.previousNavigationVisible`）、恢复时写回——**本 ROM 生效侧（真机核验：写 0 后导航栏隐藏、恢复 1 显示，重启保持）**；
  2. 状态栏：device owner `dpm.setStatusBarDisabled(admin, true)`（查询 `getStatusBarDisabled` 本 ROM 客户端无此方法——NoSuchMethodException，反射读回恒 false）；
- **2026-08-11 真机核验（状态栏侧 ROM 差异）**：本 ROM 状态栏隐藏无可用机制——① `dpm.setStatusBarDisabled` 写入无异常但被 fork DPMS 静默丢弃（`dumpsys device_policy` 无 status bar 标志、`dumpsys window` StatusBar 窗口 isVisible 恒 true）；② 替代通道 `Settings.Global policy_control = "immersive.full=*"`（AOSP 沉浸策略）同样不被 fork SystemUI 消费（mViewVisibility=0x0 恒 VISIBLE）；③ 设置库无 MTK 状态栏可见性键（仅 status_bar_show_battery_percent）。**按用户决策**：接受部分实现——导航栏侧生效，状态栏通道如实上报 note，ASR-0431 维持部分完成（导航栏侧）待 ROM 适配；
- 标志 `fullscreen` 持久化于 `fullscreen_policy`，进程重启/开机 `syncPolicy` 重新武装（幂等）；命令返回 statusBarDisabled/statusBarChannel/navigationVisible 全量状态。

## 3. 命令接口定义（SystemApiInterface.onEvent）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetAutoSleepDisabled` | disabled | Map：{success, disabled, screenOffTimeoutMs, neverSleepEffective} 或 "missing parameter" | ASR-0412 |
| `IsAutoSleepDisabled` | 无 | Map：{disabled, screenOffTimeoutMs, neverSleepEffective} | ASR-0412 |
| `SetAlwaysFullscreen` | fullscreen | Map：{success, fullscreen, statusBarDisabled, statusBarChannel, navigationVisible, note?} 或 "missing parameter" | ASR-0431 |
| `IsAlwaysFullscreen` | 无 | Map：{fullscreen, statusBarDisabled, navigationVisible} | ASR-0431 |

**调用示例**：

```java
Map<String, Object> p = new HashMap<>();
p.put("disabled", true);
Map result = api.onEvent("SetAutoSleepDisabled", p);
// {"RESULT":{"success":true,"disabled":true,"screenOffTimeoutMs":2147483647,"neverSleepEffective":true}}

Map result2 = api.onEvent("SetAlwaysFullscreen", singletonMap("fullscreen", true));
// {"RESULT":{"success":true,"fullscreen":true,"statusBarDisabled":true,"navigationVisible":0}}
```

**广播通道**：

```bash
./send_test_command.sh SetAutoSleepDisabled disabled=true
./send_test_command.sh IsAutoSleepDisabled
./send_test_command.sh SetAlwaysFullscreen fullscreen=true
./send_test_command.sh IsAlwaysFullscreen
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/AutoSleepPolicyManager.java       # 新增：ASR-0412（screen_off_timeout 置 max/恢复 + syncPolicy）
├── utils/FullscreenPolicyManager.java      # 新增：ASR-0431（setStatusBarDisabled + navigation_visible + syncPolicy）
├── service/command/display/
│   ├── SetAutoSleepDisabled.java / IsAutoSleepDisabled.java   # 新增：ASR-0412
│   └── SetAlwaysFullscreen.java / IsAlwaysFullscreen.java     # 新增：ASR-0431
├── service/ApiBinder.java                  # 注册 4 个新命令
├── service/ApiService.java                 # onCreate：AutoSleepPolicyManager/FullscreenPolicyManager syncPolicy
└── broadcast/BootCompletedReceiver.java    # 开机同步同上

testapp/
├── src/main/java/com/hmdm/testapp/
│   ├── VolumeDisplayTestActivity.java      # 新增测试页（自动休眠/全屏分区）
│   ├── TestActions.java                    # 新增 4 个事件与事件目录
│   ├── MainActivity.java                   # 新增入口按钮
│   └── res/layout/activity_volume_display_test.xml  # 新增布局
```

## 5. 执行逻辑

```
SetAutoSleepDisabled(disabled=true):
  1. 捕获 screen_off_timeout → 持久化 previousTimeoutMs
  2. Settings.System.putInt(SCREEN_OFF_TIMEOUT, Integer.MAX_VALUE)
  3. 读回核对 → neverSleepEffective = (timeout == MAX_VALUE)

SetAutoSleepDisabled(disabled=false):
  1. 恢复 previousTimeoutMs（无备份回退 30000）
  2. 清标志与备份键

SetAlwaysFullscreen(fullscreen=true):
  1. 捕获 navigation_visible（首次）→ 持久化
  2. dpm.setStatusBarDisabled(true) + Settings.System.putInt(navigation_visible, 0)
  3. 读回（反射 getStatusBarDisabled + navigation_visible）→ success

syncPolicy（进程重启/开机）:
  autoSleep: disabled 标志 → 重新写 MAX_VALUE
  fullscreen: fullscreen 标志 → 重新 setStatusBarDisabled(true) + navigation_visible=0
```

**安全设计**：无 shell 命令；参数仅布尔；写键名均为代码常量。

## 6. 权限与归属

- `screen_off_timeout`/`navigation_visible`：Settings.System 受保护键（WRITE_SETTINGS 签名权限，平台签名 uid=1000 自动持有；同 ASR-0349 批次 navigation_visible 真机核验路径）；
- `dpm.setStatusBarDisabled`：device owner 公开接口（STATUS_BAR 权限由平台签名隐式持有），无需 uses-policy 声明；
- 无新 manifest 权限、无 `device_admin.xml` 变更、无 AIDL/lib 模块变更、无需重启 framework。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 启用自动休眠时无备份值 | 回退 30000ms（30 秒默认） |
| screen_off_timeout 读取失败 | 捕获 -1，写后读回如实上报 |
| 全屏时 previousNavigationVisible 已存在（重复开启） | 不覆盖备份（contains 判断），幂等 |
| 全屏恢复时备份为 0 | 写回 1（导航栏显示），防"恢复后仍隐藏" |
| 状态栏 DPM 策略与 ASR-0344 交互 | 0344 为既有 `SetStatusBarDisabled` 命令（MdmUtils），本引擎独立标志，互不覆盖（读回如实） |
| 整机重启 | DPM 状态栏策略框架持久化；navigation_visible 设置库持久化；syncPolicy 幂等重放 |
| 设备亮度/触摸无操作导致休眠 | screen_off_timeout 为全局键，MAX_VALUE 下无超时休眠（Doze 屏灭等系统行为不受影响，如实文档化） |

## 8. 真机验证记录

（2026-08-11 批次；命令级用例结果见测试用例设计文档，此处记录机制核验关键结论）

| 核验项 | 结果 |
|---|---|
| 自动休眠禁用（timeout=MAX_VALUE） | 通过：screen_off_timeout=2147483647 写后读回 + settings 键对照；整机重启保持 |
| 恢复自动休眠 | 通过：恢复捕获值 1800000（30 分钟） |
| 全屏=导航栏隐藏 | 通过：navigation_visible 1→0→1 闭环 + 重启保持；状态栏侧本 ROM 无机制（见 2.2 节），如实上报 note |
| 全屏恢复 | 通过：navigationVisible 恢复 1、标志清除 |
| 测试后设备恢复 | 通过（全屏关闭、timeout 恢复、重启后基线完好） |
