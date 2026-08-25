# 无障碍服务控制（ASR-0074/0075）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 实现机制 |
|---|---|---|---|
| ASR-0074 | 无障碍服务 | 免交互激活与注销辅助服务功能（无障碍服务）：不经过用户交互界面，直接激活/注销指定的无障碍服务 | 平台签名 uid=1000 读写 `Settings.Secure.enabled_accessibility_services`（已启用服务列表，冒号分隔的组件名）与 `accessibility_enabled`（总开关）；计划路径反射 `AccessibilityManager.setEnabledAccessibilityServiceList`（@hide），**本 ROM 不存在该方法（dex 反编译核验），落地为直写通道**，写后读回核对 |
| ASR-0075 | 无障碍服务 | 可使用无障碍功能黑白名单：白名单模式仅名单内服务可被启用，黑名单模式名单内服务不可被启用 | SharedPreferences 持久化策略（模式 + 名单），设置/修改时立即纠正已启用列表；Settings.Secure 变更 ContentObserver 持续重新执行策略（用户或应用在设置页切换服务被回滚） |

**归属**：按需求文档归属列，两项均为「Launcher（MDM）+ 系统 API」（依赖平台签名 uid=1000 的 WRITE_SECURE_SETTINGS 与 INTERACT_ACROSS_USERS_FULL，与 SystemSettingsControl/AccountBackupControl 批次同模式）。**无需 ROM 改动**。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署；bootloader 锁定，不可改动 system 分区。测试前设备基线（2026-08-05 实测）：`enabled_accessibility_services` 为空、`accessibility_enabled=0`；已安装无障碍服务 2 个（testapp 自带 `com.hmdm.testapp/.TestAccessibilityService` 与本 ROM 预装豌豆荚 `com.wandoujia.phoenix2/com.pp.assistant.accessibility.AccessibilityService`）。

## 2. 技术选型与可行性核验

### 2.1 系统状态模型

AOSP 13 中，当前用户已启用的无障碍服务持久化于两个 Settings.Secure 键：

| 键（Settings.Secure） | 类型 | 用途 |
|---|---|---|
| `enabled_accessibility_services` | 字符串 | 已启用服务列表，冒号分隔的扁平组件名（如 `pkg/class` 或 `pkg/.ShortClass`） |
| `accessibility_enabled` | 0/1 | 无障碍总开关（master switch） |

AccessibilityManagerService 注册了这两个键的 ContentObserver：**任何写入都会触发框架重新解析并绑定/解绑对应服务**，无需用户交互。因此「免交互激活/注销」的可行通道有两条：

1. **反射调用 `AccessibilityManager.setEnabledAccessibilityServiceList(List, boolean)`**（@hide，AOSP 13 存在；AMS 侧要求调用方持有 INTERACT_ACROSS_USERS_FULL 签名权限，平台签名 uid=1000 已授予）；
2. **平台签名直写两个 Settings.Secure 键**（WRITE_SECURE_SETTINGS），由框架的 settings observer 完成服务绑定。

### 2.2 本 ROM 核验结论（2026-08-05，重点）

经 framework dex 反编译核验（`dexdump` services.jar/framework.jar + 设备运行期反射实测）：

- **本 ROM 的 `AccessibilityManager` 不存在 `setEnabledAccessibilityServiceList` 方法**——2 参数 `(List, boolean)` 与 3 参数 `(List, boolean, int)` 变体均抛 `NoSuchMethodException`（MTK fork 移除/更名）。Sheet1 P1 规划路径在 stock AOSP 可用，本 ROM 不可用；
- 引擎实现为**双通道**：先尝试反射调用（stock ROM 上走 `channel=ams`），失败后**回退平台签名直写 Settings.Secure**（`channel=settings`）。本 ROM 实测恒走 `channel=settings`，两条通道最终都落在同一个框架状态机（accessibility_enabled + enabled_accessibility_services），写后读回核对一致即为成功；
- **组件名格式差异（关键）**：Settings 应用写入的 enabled_accessibility_services 为**全格式**（`pkg/pkg.Class`），而 `ComponentName.flattenToShortString()` 为**短格式**（`pkg/.ShortClass`）。引擎在解析设置键时对每个组件统一规范化（`ComponentName.unflattenFromString` → `flattenToShortString`），保证黑白名单匹配不受写入方格式影响（真机实测：未规范化前，设置页写入的全格式条目无法被黑名单命中，规范化后立即生效）。

### 2.3 ASR-0074 免交互激活/注销

- 激活（enabled=true）：当前已启用列表 + 目标组件 → 写入列表，`accessibility_enabled=1`（激活首个服务必须打开总开关）；
- 注销（enabled=false）：当前列表移除目标组件 → 写入；**注销最后一个服务时总开关置 0**（完整「注销」语义，与需求表述一致）；
- 写后读回核对：列表集合相等且总开关符合期望才报 success=true；
- 幂等：重复激活/注销同一组件不改变结果，success=true；
- **策略联动**：ASR-0075 策略生效时，激活被策略禁止的服务被**确定性拒绝**（success=false + error），而非激活后被纠正器回滚（避免竞态与误解）；
- 非法输入：组件非 `pkg/class` 格式 → error；缺参数 → missing parameter。

### 2.4 ASR-0075 可使用无障碍功能黑白名单

策略模型（SharedPreferences `accessibility_service_policy`）：

| 字段 | 键 | 说明 |
|---|---|---|
| 模式 | `mode` | 0=关闭，1=白名单，2=黑名单 |
| 白名单 | `whitelist`（StringSet） | 白名单模式下唯一可启用的服务集合 |
| 黑名单 | `blacklist`（StringSet） | 黑名单模式下禁止启用的服务集合 |

执行机制：

1. **立即纠正**：设置模式/名单后，立即计算「应禁用的已启用服务」（白名单模式=不在名单内的已启用服务；黑名单模式=在名单内的已启用服务），从 enabled_accessibility_services 中移除并写回（读回核对）；
2. **持续执行（ContentObserver）**：Launcher 在 ApiService.onCreate 注册 `Settings.Secure.CONTENT_URI` 观察者（300ms 防抖），`enabled_accessibility_services`/`accessibility_enabled` 任何外部变更（用户在设置页切换服务、其他应用写入）都会触发策略重算并回滚违规项——与既有批次的「10 秒纠正器」语义一致但更即时；
3. **重启武装**：策略持久化于 SharedPreferences，进程重启（ApiService.onCreate）/开机（BootCompletedReceiver）经 `syncPolicy` 重新注册观察者并立即执行一次策略；
4. **总开关不干预**：策略只裁剪服务列表，不修改 `accessibility_enabled`（总开关语义归 ASR-0074 与用户）；
5. 名单为整体替换语义，与通知黑白名单（ASR-0054/0055）一致。

### 2.5 系统配置声明

无 `device_admin.xml` 变更、无新增 manifest 权限（WRITE_SECURE_SETTINGS / INTERACT_ACROSS_USERS_FULL 均为既有声明，平台签名自动授予），无需重启 framework。

## 3. 命令接口定义（SystemApiInterface.onEvent）

### 3.1 新增命令（本批次，10 条）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetAccessibilityServiceEnabled` | component（String，`pkg/class`，必填）、enabled（boolean，必填） | Map：{success, enabled, component, channel, accessibilityEnabled, enabledServices} 或 {error} | ASR-0074 |
| `IsAccessibilityServiceEnabled` | component（必填） | Map：{success, enabled, component, accessibilityEnabled, enabledServices} | ASR-0074 |
| `GetAccessibilityServiceState` | 无 | Map：{success, accessibilityEnabled, enabledServices, installedServices, mode, whitelist, blacklist} | ASR-0074/0075（验证辅助） |
| `SetAccessibilityServicePolicyMode` | mode（int，必填，0/1/2） | Map：{success, mode, applied, removed, channel, enabledServices} 或 {error} | ASR-0075 |
| `GetAccessibilityServicePolicyMode` | 无 | Map：{success, mode, whitelist, blacklist} | ASR-0075 |
| `SetAccessibilityServiceWhitelist` | components（String 数组，整体替换） | Map：{success, whitelist[, applied, removed, channel, enabledServices]} | ASR-0075 |
| `GetAccessibilityServiceWhitelist` | 无 | Map：{success, whitelist} | ASR-0075 |
| `SetAccessibilityServiceBlacklist` | components（数组，整体替换） | Map：{success, blacklist[, applied, removed, channel, enabledServices]} | ASR-0075 |
| `GetAccessibilityServiceBlacklist` | 无 | Map：{success, blacklist} | ASR-0075 |
| `ApplyAccessibilityServicePolicy` | 无 | Map：{success, applied, removed, mode, channel, enabledServices} | ASR-0075 |

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> p = new HashMap<>();
p.put("component", "com.hmdm.testapp/com.hmdm.testapp.TestAccessibilityService");
p.put("enabled", true);
Map result = api.onEvent("SetAccessibilityServiceEnabled", p);
// {"RESULT":{"channel":"settings","component":"com.hmdm.testapp/.TestAccessibilityService",
//   "enabledServices":["com.hmdm.testapp/.TestAccessibilityService"],
//   "success":true,"enabled":true,"accessibilityEnabled":true}}

Map result2 = api.onEvent("SetAccessibilityServicePolicyMode", p2); // p2={"mode":2}
// {"RESULT":{"mode":2,"channel":"settings","enabledServices":[...],
//   "removed":[...],"applied":true,"success":true}}
```

**广播通道**（TestBroadcast，与现有命令一致）：

```bash
adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast \
  -a ON_SQA_INTERFACE_TEST --es event SetAccessibilityServiceEnabled \
  --es param '{"component":"com.hmdm.testapp/com.hmdm.testapp.TestAccessibilityService","enabled":true}'
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/
│   └── AccessibilityServicePolicyManager.java  # 新增：无障碍服务控制引擎
│       （双通道写 enabled_accessibility_services/accessibility_enabled + 读回核对；
│        组件名规范化；策略持久化 + Settings.Secure ContentObserver 300ms 防抖执行；
│        syncPolicy 进程重启/开机武装）
├── service/command/accessibility/
│   ├── SetAccessibilityServiceEnabled.java      # 新增：ASR-0074 设置
│   ├── IsAccessibilityServiceEnabled.java       # 新增：ASR-0074 查询
│   ├── GetAccessibilityServiceState.java        # 新增：状态查询（验证辅助）
│   ├── SetAccessibilityServicePolicyMode.java   # 新增：ASR-0075 模式
│   ├── GetAccessibilityServicePolicyMode.java   # 新增：ASR-0075 模式查询
│   ├── SetAccessibilityServiceWhitelist.java    # 新增：ASR-0075 白名单
│   ├── GetAccessibilityServiceWhitelist.java    # 新增：ASR-0075 白名单查询
│   ├── SetAccessibilityServiceBlacklist.java    # 新增：ASR-0075 黑名单
│   ├── GetAccessibilityServiceBlacklist.java    # 新增：ASR-0075 黑名单查询
│   └── ApplyAccessibilityServicePolicy.java     # 新增：ASR-0075 立即执行
└── service/ApiBinder.java           # 注册 10 个新命令
    service/ApiService.java          # onCreate 调 syncPolicy（重启武装）
    broadcast/BootCompletedReceiver.java  # 开机调 syncPolicy

testapp/
├── src/main/java/com/hmdm/testapp/
│   ├── TestAccessibilityService.java            # 新增：测试用无障碍服务（无操作，仅可绑定）
│   ├── AccessibilityTestActivity.java           # 新增：测试页（激活/注销/状态/策略/名单按钮）
│   ├── TestActions.java                         # 新增 10 个事件（含参数校验）与事件目录
│   └── MainActivity.java                        # 增加 "Accessibility service control" 入口
├── src/main/res/layout/activity_accessibility_test.xml  # 新增测试页布局
├── src/main/res/xml/accessibility_service_config.xml    # 新增服务配置
└── src/main/AndroidManifest.xml  # 新增 TestAccessibilityService 声明（BIND_ACCESSIBILITY_SERVICE）
```

## 5. 执行逻辑

```
SetAccessibilityServiceEnabled(component, enabled):
  1. 参数校验（缺 component/enabled → {error}）；组件解析失败 → {error}
  2. enabled=true 且策略禁止该组件（白名单模式不在名单 / 黑名单模式在名单）
     → success=false + error "activation blocked by accessibility service policy (mode=N)"
  3. wanted = 当前列表 ± component；master = enabled ? true : !wanted.isEmpty()
  4. applyServices(wanted, master)：先反射 setEnabledAccessibilityServiceList（本 ROM 无该方法，必然失败）
     → 回退 Settings.Secure.putString(enabled_accessibility_services) + putInt(accessibility_enabled)
  5. 读回核对：列表集合相等 + master 符合 → success=true（channel 如实上报 ams/settings）

IsAccessibilityServiceEnabled(component):
  1. 解析设置键当前列表（组件名规范化）
  2. 返回 {success, enabled=列表含目标, accessibilityEnabled, enabledServices}

SetAccessibilityServicePolicyMode(mode):
  1. mode 越界（非 0/1/2）→ {error}
  2. 持久化 mode；applyPolicy()：计算应禁用的已启用服务并写回（读回核对）
  3. 返回 {success, mode, applied, removed, channel, enabledServices}

SetAccessibilityServiceWhitelist/Blacklist(components):
  1. 组件规范化（非法项剔除、去重）；整体替换持久化
  2. 对应模式激活时立即 applyPolicy()

applyPolicy():
  mode=0 → 不动作
  mode=1：forbidden = 已启用 - 白名单
  mode=2：forbidden = 已启用 ∩ 黑名单
  forbidden 为空 → 不动作；否则移除并写回，读回核对（逐项检查违规服务均已消失）

syncPolicy(ctx)（ApiService.onCreate / BootCompletedReceiver）：
  1. 注册 Settings.Secure.CONTENT_URI ContentObserver（静态单次，300ms 防抖 → applyPolicy）
  2. mode != 0 时立即 applyPolicy()（重启后恢复策略状态）
```

**安全设计**：组件名经 `ComponentName.unflattenFromString` 解析后仅用于框架设置键读写与集合比较，不进入 shell/系统命令，无注入面；名单与策略仅存 SharedPreferences（Launcher 私有目录）；观察者回调只读设置键与私有策略，无外部数据落地。

## 6. 权限与归属

- 反射 `AccessibilityManager.setEnabledAccessibilityServiceList`（@hide）：依赖 INTERACT_ACROSS_USERS_FULL 签名权限（平台签名 uid=1000 已授予，`dumpsys package` granted=true 核验）；
- 直写通道：WRITE_SECURE_SETTINGS 签名权限（manifest 既有声明，平台签名自动授予）；
- 无需 device owner 身份、无新增 manifest 权限、无 shell、无 ROM 改动；
- 不修改 AIDL / lib 模块；testapp 新增无障碍服务声明（BIND_ACCESSIBILITY_SERVICE，系统保护权限，由框架授予）。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺 component/enabled 参数 | 命令返回 {error："missing parameter: ..."}，不 crash；testapp 侧同样拦截 |
| 组件格式非法（非 pkg/class） | {success:false, error:"invalid component: ..."}，不写设置 |
| 激活被策略禁止的组件 | 确定性拒绝 {success:false, error:"activation blocked by accessibility service policy (mode=N)"}（不依赖纠正器回滚） |
| 本 ROM 无 setEnabledAccessibilityServiceList | 反射抛 NoSuchMethodException（2 参与 3 参均核验），静默回退直写通道，channel=settings 如实上报 |
| 设置键中组件为全格式（设置页写入） | 解析时统一 `unflattenFromString → flattenToShortString` 规范化，策略匹配不受格式影响（真机实测修正项） |
| 注销最后一个已启用服务 | 列表置空且 accessibility_enabled=0（完整注销语义） |
| 重复激活/注销（幂等） | 列表集合不变，读回核对一致，success=true |
| 白名单模式下名单为空 | 全部已启用服务被禁用（可理解为本模式禁止使用任何无障碍服务） |
| 用户在设置页切换服务 | ContentObserver（300ms 防抖）触发策略重算，违规服务被回滚（真机实测：注入全格式列表后约 1 秒内恢复） |
| 设置键值写入与本次相同 | SettingsProvider 不通知（实测），观察者不触发——状态本就合规，无影响 |
| 策略持久化丢失（Launcher 数据清空） | mode 回 0，观察者空转不执行；名单为空 |
| 进程重启 / 开机 | syncPolicy 重新注册观察者并立即执行一次策略（真机实测：重启后注入违规服务被回滚） |
| 反射/直写均失败（极端情况） | success=false + error，读回核对如实上报 |

## 8. 真机验证记录（2026-08-05，Android 13 / API 33 userdebug，平台签名 + device owner）

（命令级用例结果见测试用例设计文档，此处记录机制核验关键结论）

| 核验项 | 结果 |
|---|---|
| 设备基线 | enabled_accessibility_services 空、accessibility_enabled=0；已安装服务 2 个（testapp 自带 + 豌豆荚） |
| 通道核验 | 反射 2 参/3 参均抛 NoSuchMethodException（本 ROM AccessibilityManager 无此方法），引擎回退直写，channel=settings |
| ASR-0074 激活 | SetAccessibilityServiceEnabled(true) → success=true、accessibilityEnabled=true；`settings get` 与 `dumpsys accessibility`（Bound services/Enabled services）三处一致，服务真实绑定 |
| ASR-0074 注销 | 注销其一（多服务共存时总开关保持 1）；注销最后一个 → 列表空 + accessibility_enabled=0 |
| 组件名规范化 | 设置页全格式条目（`pkg/pkg.Class`）注入后黑名单命中并回滚（修正前不命中，为本次实现关键修正） |
| ASR-0075 白名单 | 白名单=[testapp] + mode=1 → 已启用的豌豆荚服务立即被移除（removed 如实上报） |
| ASR-0075 黑名单 | 黑名单=[testapp] + mode=2 → testapp 服务立即被移除 |
| ASR-0075 观察者 | 模拟设置页开关（settings put 全格式列表）→ 约 1 秒内违规项被回滚，合规项保留 |
| 重启武装 | 策略 mode=1 白名单=[testapp] 生效时杀掉 Launcher 进程 → 重启后注入违规服务被 syncPolicy 回滚（logcat applyPolicy removed 核对） |
| 策略联动 | 黑名单模式激活 testapp 服务 → 确定性拒绝（success=false + mode=2 上报） |
| 异常路径 | mode=3 越界、非法组件、缺参均返回对应 error，不 crash |
| 测试后设备恢复 | 策略 mode=0、名单清空、enabled_accessibility_services 空、accessibility_enabled=0 |
