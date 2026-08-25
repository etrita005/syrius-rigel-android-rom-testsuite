# 无障碍快捷方式/截屏/系统升级策略管控（ASR-0078/0185/0186/0444）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 实现机制 |
|---|---|---|---|
| ASR-0078 | 无障碍服务 | 查询/设置是否禁用无障碍快捷方式 | `dpm.setSecureSetting` 优先写 `Settings.Secure.accessibility_shortcut`（AOSP 13 白名单不含该键，回退平台签名 uid=1000 直写），同时清空/恢复 `accessibility_shortcut_target_service(s)` 两个新键 |
| ASR-0185 | 截屏 | 查询/设置是否禁用系统截屏功能 | device owner `dpm.setScreenCaptureDisabled`（既有引擎，MdmUtils.disableScreenshots/isScreenshotsDisable） |
| ASR-0186 | 截屏 | 查询/设置是否禁用手动截屏功能 | **与 ASR-0185 同一引擎**：`setScreenCaptureDisabled` 对全部窗口施加 FLAG_SECURE，物理键（电源+音量下）与系统截屏入口的抓屏均被阻断 |
| ASR-0444 | 系统升级策略 | Launcher（MDM）对业务应用提供获取/设置"是否禁用在线 FOTA"的接口，并查询当前策略状态 | device owner `dpm.setSystemUpdatePolicy`：禁用=`createPostponeInstallPolicy()`（POSTPONE 延期安装），启用=清除策略回自动；写后读回核对 |

**归属**：按需求文档归属列——ASR-0078 为「Launcher（MDM）+ 系统 API」（`dpm.setSecureSetting` 公开 SDK；回退通道依赖平台签名 uid=1000 的 WRITE_SECURE_SETTINGS，与 SystemSettingsControl/AccountBackupControl 批次同模式）；ASR-0185/0186 为「Launcher（MDM）」（公开 DPM 接口 + device owner）；ASR-0444 为「Launcher（MDM）」（公开 DPM 接口 + device owner）。四项均**无需 ROM 改动**。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署；bootloader 锁定，不可改动 system 分区。测试前设备基线（2026-08-04 实测）：`accessibility_shortcut`/`accessibility_shortcut_target_service`/`accessibility_shortcut_target_services` 三键均空（无障碍快捷方式未配置，disabled=true）；DPM 截屏策略未启用（disableScreenCapture=false）；系统更新策略未设置（automatic，disabled=false）。

## 2. 技术选型与可行性核验

### 2.1 ASR-0078 无障碍快捷方式（accessibility_shortcut 设置键）

**语义**：无障碍快捷方式（音量键组合/长按等触发的辅助功能入口）的目标服务保存在 Settings.Secure 键中，值为空即无目标，快捷方式无法触发任何无障碍服务。禁用=清空目标键；启用=恢复禁用前保存的目标（若禁用前未配置目标则保持为空）。

**键位设计**：AOSP 13 的 Settings UI / AccessibilityManagerService 可能消费三个键，全部纳入管控并如实上报：

| 键（Settings.Secure） | 来源 | 用途 |
|---|---|---|
| `accessibility_shortcut`（`Settings.Secure.ACCESSIBILITY_SHORTCUT`） | 经典键 | 快捷方式目标服务（component 字符串） |
| `accessibility_shortcut_target_service` | Android 12+ | 单目标快捷方式 |
| `accessibility_shortcut_target_services` | Android 13 | 多目标快捷方式（冒号分隔列表） |

**通道设计（与 ASR-0132 同模式）**：按 Sheet1 P0 规划首选 `dpm.setSecureSetting(ComponentName, String, String)`（公开 SDK，device owner 通道）。但 AOSP 13 的 DPM 仅白名单少数键（`default_input_method`、`skip_first_use_hints`、`touch_exploration_enabled`、`bluetooth_on`、`immersive_mode_confirmations`、`allowed_headless_system_apps` 等），`accessibility_shortcut` **不在白名单**——DPM 抛 `SecurityException`。引擎实现为：

1. 逐键先尝试 `dpm.setSecureSetting`（若厂商 ROM 放宽白名单则直接生效，结果如实上报 `channel=dpm`）；
2. 抛异常时**回退平台签名直写** `Settings.Secure.putString`（uid=1000 持有 WRITE_SECURE_SETTINGS），结果上报 `channel=settings`；
3. 禁用时先把非空键值备份到 Launcher SharedPreferences（`accessibility_shortcut_policy`），再写空串；启用时按备份逐键恢复（无备份的键保持不动，不主动创建）；
4. 全部键写后读回核对，一致才报 success=true。

**真机核验（2026-08-04）**：`dpm.setSecureSetting(accessibility_shortcut)` 在设备上抛 SecurityException（白名单外），命令回退直写，`channel=settings`；`settings get secure accessibility_shortcut` 与命令读回一致。

### 2.2 ASR-0185/0186 截屏（setScreenCaptureDisabled 同引擎）

**语义**：`dpm.setScreenCaptureDisabled(admin, true)`（L+，device owner）对设备全部窗口施加 FLAG_SECURE，**所有**抓屏路径被阻断：

- 系统截屏（ASR-0185）：SystemUI 截屏服务（快捷设置磁贴、导航栏截图按钮）；
- 手动截屏（ASR-0186）：物理键组合（电源+音量下）触发 SystemUI 截屏，同样经显示捕获路径，被 FLAG_SECURE 阻断；
- `adb shell screencap`（真机实测：禁用时输出 **0 字节**文件，启用时输出正常 PNG）。

**实现**：复用既有引擎 `MdmUtils.disableScreenshots`/`isScreenshotsDisable`（`syrius/utils/MdmUtils.java:976-999`，配置驱动 `Utils.disableScreenshots` 与既有命令 `Set/IsScreenshotsDisabled` 同源）。本批次**不新增命令**：ASR-0185 的命令 `SetScreenshotsDisabled`/`IsScreenshotsDisabled`（返回 Boolean，兼容既有调用方）即 ASR-0186 的接口，testapp 补齐事件与真机用例验证两条需求语义。

**真机核验（2026-08-04）**：禁用后 `dumpsys device_policy` 显示 `disableScreenCapture=true` 与 `Screen capture disallowed users: [-1]`（全部用户）；`adb shell screencap -p` 输出 0 字节（内容不可捕获）。恢复后 `disableScreenCapture=false`、screencap 输出 6246 字节正常图像。

**系统配置声明**：`setScreenCaptureDisabled` 在 AOSP 13 无 uses-policy 要求（ActiveAdmin.screenCaptureDisabled 字段持久化于 device_policies.xml，经 WindowManager 对显示施加安全标记），**不修改** `device_admin.xml`，无需重启 framework。

### 2.3 ASR-0444 在线 FOTA（setSystemUpdatePolicy POSTPONE）

**语义**：禁用在线 FOTA=设置系统更新策略为 POSTPONE（已下载更新最多延期 30 天安装），设备不会在非维护窗口自动安装；启用=清除策略恢复自动安装行为。查询返回当前策略类型与参数。

**接口核验**：

| 接口 | API 级别 | 调用方要求 | 本部署 |
|---|---|---|---|
| `dpm.setSystemUpdatePolicy(ComponentName, SystemUpdatePolicy)` | API 21 | 非 null 策略仅 device owner | 满足（device owner） |
| `dpm.getSystemUpdatePolicy()` | API 21 | 任意 | 满足 |
| `SystemUpdatePolicy.createPostponeInstallPolicy()` | API 26 | - | 满足（API 33） |

`setSystemUpdatePolicy(admin, null)` 清除策略（null 不受 device owner 限制）。**无需** uses-policy 声明，无需重启 framework。

**本 ROM 关键差异（2026-08-04 真机 + framework 反编译核验，重点）**：本 ROM 的 framework 携带**fork 版 SystemUpdatePolicy**，类型常量与 AOSP 13 不同（经 `dexdump /system/framework/framework.jar` 核对）：

| 类型 | 本 ROM fork 常量值 | AOSP 13 标准值 |
|---|---|---|
| 自动安装 | `TYPE_INSTALL_AUTOMATIC = 1` | `TYPE_AUTOMATIC = 0` |
| 维护窗口 | `TYPE_INSTALL_WINDOWED = 2` | `TYPE_WINDOWED = 1` |
| 延期安装 | `TYPE_POSTPONE = 3` | `TYPE_POSTPONE = 2` |
| 暂停安装 | `TYPE_PAUSE = 4`（本 ROM 独有） | 无 |

且 `TYPE_*` 常量、`getMaintenanceWindowStart/End`、`FreezePeriod` 类均为 @hide（SDK android.jar 不可见）。引擎处理：

1. 类型常量经**运行时反射**读取 `SystemUpdatePolicy` 静态字段（`TYPE_INSTALL_AUTOMATIC`/`TYPE_AUTOMATIC`/`TYPE_POSTPONE`/`TYPE_INSTALL_WINDOWED`/`TYPE_WINDOWED`/`TYPE_PAUSE`），失败回退标准值——同一代码在 stock AOSP 与本 ROM 均正确；
2. 禁用验证：读回策略非空且 `getPolicyType() == TYPE_POSTPONE`（本 ROM=3）；启用验证：读回策略为 null；
3. 查询：`policySet`（策略是否存在）、`policyType`/`policyTypeName`（raw 值 + 名称映射，本 ROM 实测 `postpone`↔3）、`maintenanceWindowStart/End`（反射读，0=不适用）、`freezePeriods`（反射读 FreezePeriod.getStart/getEnd，格式 MM-dd~MM-dd）；
4. `disabled` 派生口径：策略存在且类型 ≠ 自动安装 → true（POSTPONE 与 WINDOWED/PAUSE 均视为"禁用在线 FOTA"——都阻止无约束的在线安装）；无策略（null）→ false。

**真机核验（2026-08-04）**：`SetOnlineFotaDisabled disabled=true` → `dumpsys device_policy` 显示 `System Update Policy: SystemUpdatePolicy (type: 3, windowStart: 0, windowEnd: 0, freezes: [])`，命令读回 policyType=3/name=postpone、success=true；`disabled=false` → 策略清除（`device_owner_2.xml` 无 SystemUpdatePolicy 段、dumpsys 无该段），读回 policySet=false、success=true。首次实现按 AOSP 常量（postpone=2）校验曾报 read-back mismatch（读回 3），已按本 ROM 常量修正并如实记录。

## 3. 命令接口定义（SystemApiInterface.onEvent）

### 3.1 新增命令（本批次，4 条）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetAccessibilityShortcutDisabled` | disabled（boolean，必填） | Map：{success, disabled, channel, channels, keys} 或 {error} | ASR-0078 |
| `IsAccessibilityShortcutDisabled` | 无 | Map：{success, disabled, keys} | ASR-0078 |
| `SetOnlineFotaDisabled` | disabled（boolean，必填） | Map：{success, disabled, policySet, policyType, policyTypeName, maintenanceWindowStart, maintenanceWindowEnd, freezePeriods} 或 {error} | ASR-0444 |
| `IsOnlineFotaDisabled` | 无 | 同上（disabled 为派生口径） | ASR-0444 |

**复用命令（ASR-0185/0186 共用，既有接口保持不变）**：

| 命令名 | 参数 | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetScreenshotsDisabled` | disabled（boolean，必填） | Boolean（既有契约，MdmUtils.disableScreenshots） | ASR-0185/0186 |
| `IsScreenshotsDisabled` | 无 | Boolean（dpm.getScreenCaptureDisabled） | ASR-0185/0186 |

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> p = new HashMap<>();
p.put("disabled", true);
Map result = api.onEvent("SetAccessibilityShortcutDisabled", p);
// {"RESULT":{"success":true,"disabled":true,"channel":"settings",
//   "channels":{...三个键均"settings"...},"keys":{...三键均空...}}}

Map result2 = api.onEvent("SetOnlineFotaDisabled", p);
// {"RESULT":{"success":true,"disabled":true,"policySet":true,"policyType":3,
//   "policyTypeName":"postpone","maintenanceWindowStart":0,"maintenanceWindowEnd":0,"freezePeriods":[]}}
```

**广播通道**（TestBroadcast，与现有命令一致）：

```bash
adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast \
  -a ON_SQA_INTERFACE_TEST --es event SetOnlineFotaDisabled \
  --es param '{"disabled":true}'
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/
│   ├── AccessibilityShortcutPolicyManager.java  # 新增：无障碍快捷方式引擎（三键清空/备份/恢复，dpm 优先 + 直写回退，写后读回核对）
│   └── SystemUpdatePolicyManager.java           # 新增：系统更新策略引擎（POSTPONE/清除，类型常量运行时反射，写后读回核对）
├── service/command/accessibility/
│   ├── SetAccessibilityShortcutDisabled.java    # 新增：ASR-0078 设置
│   └── IsAccessibilityShortcutDisabled.java     # 新增：ASR-0078 查询
├── service/command/fota/
│   ├── SetOnlineFotaDisabled.java               # 新增：ASR-0444 设置
│   └── IsOnlineFotaDisabled.java                # 新增：ASR-0444 查询
└── service/ApiBinder.java           # 注册 4 个新命令

testapp/
├── src/main/java/com/hmdm/testapp/
│   ├── AccessibilityScreenshotUpdateTestActivity.java  # 新增：测试页（三项功能禁用/启用/查询按钮，9 个）
│   ├── TestActions.java                     # 新增 6 个事件（含参数校验）与事件目录
│   └── MainActivity.java                    # 增加 "Accessibility shortcut / screenshots / update policy" 入口
└── src/main/res/layout/activity_accessibility_screenshot_update_test.xml  # 新增测试页布局
```

## 5. 执行逻辑

```
SetAccessibilityShortcutDisabled:
  1. 参数校验（缺 disabled → {error}）
  2. 遍历三个键（accessibility_shortcut / accessibility_shortcut_target_service / accessibility_shortcut_target_services）：
     a. disabled=true：非空值先备份到 SharedPreferences；目标=空串
     b. disabled=false：从备份取原值；无备份 → 该键标记 unchanged 跳过
  3. 逐键尝试 dpm.setSecureSetting（成功 → channel=dpm），SecurityException → 平台签名直写（channel=settings）
  4. 三键读回核对全部等于目标 → success=true；否则 success=false + error
  5. 返回 {success, disabled, channel, channels, keys}

IsAccessibilityShortcutDisabled:
  1. 读三键当前值（null 视为空串）
  2. disabled = 三键全空（快捷方式无目标，不可触发）
  3. 返回 {success, disabled, keys}

SetOnlineFotaDisabled:
  1. 参数校验（缺 disabled → {error}）；校验 device owner（非 DO → success=false + error）
  2. disabled=true → dpm.setSystemUpdatePolicy(admin, SystemUpdatePolicy.createPostponeInstallPolicy())
     disabled=false → dpm.setSystemUpdatePolicy(admin, null)
  3. 读回：disabled 期望 policySet=true 且 policyType==反射解析的 TYPE_POSTPONE（本 ROM=3）；启用期望 policy==null
  4. 一致 → success=true；否则 success=false + error（含期望/实际类型）
  5. 返回 {success, disabled, policySet, policyType, policyTypeName, maintenanceWindowStart, maintenanceWindowEnd, freezePeriods}

IsOnlineFotaDisabled:
  1. dpm.getSystemUpdatePolicy()；null → policyType=-1（none）
  2. disabled = policySet && policyType != 自动安装类型（POSTPONE/WINDOWED/PAUSE 均视为禁用）
  3. maintenanceWindowStart/End 与 freezePeriods 经反射读取（@hide）
  4. 返回 {success, disabled, policySet, policyType, policyTypeName, maintenanceWindowStart, maintenanceWindowEnd, freezePeriods}

Set/IsScreenshotsDisabled（既有命令，ASR-0185/0186 共用）：
  MdmUtils.disableScreenshots(ctx, disabled) / isScreenshotsDisable(ctx)
  （device owner setScreenCaptureDisabled/getScreenCaptureDisabled，L+；Boolean 契约不变）
```

**安全设计**：本批次命令参数为布尔值，**无字符串进入 shell / 系统命令**，无命令注入面；SharedPreferences 备份仅存快捷方式目标组件串（由系统设置页写入的既有数据），启用时原样写回，不解析、不执行；反射仅读取 framework 常量与策略 getter，不触碰受保护字段。

## 6. 权限与归属

- ASR-0078：`dpm.setSecureSetting`（公开 SDK，device owner）+ SettingsProvider 直写（平台签名 uid=1000 的 WRITE_SECURE_SETTINGS，manifest 既有声明），**无新增权限、无隐藏 API**；
- ASR-0185/0186：公开 DPM 接口（`setScreenCaptureDisabled`/`getScreenCaptureDisabled`，L+），仅需 device owner 身份，**无新增 manifest 声明、无 shell、无 ROM 改动**；
- ASR-0444：公开 DPM 接口（`setSystemUpdatePolicy`/`getSystemUpdatePolicy`），仅需 device owner 身份；类型常量与窗口/冻结期 getter 经运行时反射读取（平台签名豁免 hidden API 限制），**无新增 manifest 声明**；
- 不修改 AIDL / lib 模块；testapp 无需新增权限。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺 disabled 参数 | 命令返回 {error："missing parameter: disabled"}，不 crash；testapp 侧同样拦截 |
| 非 device owner（ASR-0444） | success=false + error "not a device owner"，不 crash |
| `dpm.setSecureSetting` 白名单外（AOSP 13 必然） | 静默回退 Settings.Secure 直写，返回 channel=settings（逐键 channels 如实上报） |
| 快捷方式禁用前无目标（键本就为空） | 不写备份；启用时该键 unchanged 不创建键，读回核对跳过（disabled 仍为 true，语义正确） |
| 快捷方式备份被清除（如 Launcher 数据清空）后启用 | 备份缺失的键不恢复（保持空），其余键正常恢复；读回按实际目标核对 |
| 本 ROM fork 的 SystemUpdatePolicy 类型（POSTPONE=3） | 常量运行时反射解析，验证/命名自适应；首次按 AOSP 常量校验时如实报 mismatch（已修正） |
| SystemUpdatePolicy 为 null（未设置） | 查询报 policyType=-1/name=none/policySet=false/disabled=false（自动安装行为） |
| 反射读取失败（极端 ROM 差异） | 回退标准 AOSP 常量（0/1/2），窗口/冻结期返回空值并记日志 |
| 冻结期解析失败（单个） | 跳过该期并记日志，其余正常返回 |
| 用户在设置页手动配置快捷方式目标 | 查询如实反映（命令只保证调用时刻生效）；再次禁用会覆盖备份为新值 |
| WINDOWED/PAUSE 类型策略已存在时查询 | 如实返回类型与参数，disabled=true（阻止无约束在线安装） |
| `SetScreenshotsDisabled` 返回 false（如非 DO） | Boolean 契约不变，调用方按 false 处理；dumpsys device_policy 可对照 disableScreenCapture 字段 |

## 8. 真机验证记录（2026-08-04，Android 13 / API 33 userdebug，平台签名 + device owner）

（命令级用例结果见测试用例设计文档，此处记录机制核验关键结论）

| 核验项 | 结果 |
|---|---|
| 设备基线 | 无障碍快捷方式三键均空（disabled=true）；`dumpsys device_policy` 无 disableScreenCapture（false）；无系统更新策略（automatic） |
| ASR-0078 通道 | `dpm.setSecureSetting(accessibility_shortcut)` 抛 SecurityException（AOSP 13 白名单外），命令回退平台签名直写，channel=settings（三键均 settings） |
| ASR-0078 禁用/恢复 | 预置目标值后禁用 → 三键清空、备份保存、success=true；启用 → `accessibility_shortcut` 恢复原目标值、其余键 unchanged；`settings get secure` 与命令 keys 读回一致 |
| ASR-0078 读回核对 | 禁用后查询 disabled=true、三键全空；恢复后查询 disabled=false、键值还原 |
| ASR-0185/0186 禁用 | `SetScreenshotsDisabled disabled=true` → true；`dumpsys device_policy` 显示 `disableScreenCapture=true`、`Screen capture disallowed users: [-1]`；`adb shell screencap -p` 输出 **0 字节**（FLAG_SECURE 阻断，物理键/系统截屏同路径） |
| ASR-0185/0186 恢复 | `disabled=false` → true；`disableScreenCapture=false`、disallowed users 空；screencap 输出 6246 字节正常 PNG |
| **ASR-0444 类型差异核验** | `createPostponeInstallPolicy()` 读回 policyType=**3**（AOSP 标准为 2）；dexdump 反编译 framework.jar 确认本 ROM fork 常量：`TYPE_INSTALL_AUTOMATIC=1`、`TYPE_INSTALL_WINDOWED=2`、`TYPE_POSTPONE=3`、`TYPE_PAUSE=4`；引擎改为运行时反射解析常量 |
| ASR-0444 禁用与读回 | `SetOnlineFotaDisabled disabled=true` → success=true、policySet=true、policyType=3/name=postpone；`dumpsys device_policy` 显示 `System Update Policy: SystemUpdatePolicy (type: 3, windowStart: 0, windowEnd: 0, freezes: [])`；重复禁用幂等成功 |
| ASR-0444 恢复 | `disabled=false` → success=true、policySet=false、policyType=-1/name=none；dumpsys 无 System Update Policy 段、`device_owner_2.xml` 无残留（grep 计数 0） |
| 异常路径 | 三个 Set 命令缺参均返回 `missing parameter: disabled`；`disabled=abc` 解析为 false 不 crash；未知事件返回 unknown event |
| 测试后设备恢复 | 无障碍快捷方式三键空（基线）、截屏启用、系统更新策略未设置（automatic） |
