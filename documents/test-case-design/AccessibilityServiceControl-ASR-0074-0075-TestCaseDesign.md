# 无障碍服务控制（ASR-0074/0075）测试用例设计文档

## 1. 测试范围与前置条件

**范围**：ASR-0074 免交互激活/注销辅助服务、ASR-0075 可使用无障碍功能黑白名单，覆盖命令设置/查询、策略立即执行、设置变更观察者执行、进程重启武装、非法输入与恢复用例。

**前置条件**：

| 项 | 要求 |
|---|---|
| 设备 | Android 13（API 33）userdebug，平台签名 Launcher（device owner，uid=1000）已部署 |
| testapp | 已安装（平台签名），已声明测试用无障碍服务 `com.hmdm.testapp/.TestAccessibilityService` |
| 依赖服务 | 设备已安装至少 1 个第三方无障碍服务（本机实测为豌豆荚 `com.wandoujia.phoenix2/com.pp.assistant.accessibility.AccessibilityService`，命令支持任意已安装服务） |
| 基线 | `settings get secure enabled_accessibility_services` 为空、`accessibility_enabled=0`；命令 GetAccessibilityServiceState 返回 mode=0、名单为空 |
| 入口 | UI：testapp 主页 `Accessibility service control (ASR-0074/0075)` → `AccessibilityTestActivity`；IPC：`./send_test_command.sh <event> key=value ...`（与 UI 按钮完全等效） |

**测试目标服务组件**（本机）：

- 自有：`com.hmdm.testapp/com.hmdm.testapp.TestAccessibilityService`
- 第三方：`com.wandoujia.phoenix2/com.pp.assistant.accessibility.AccessibilityService`

## 2. 测试用例表

### 2.1 ASR-0074 免交互激活/注销辅助服务

| 编号 | 用例 | 操作（IPC） | 预期结果 | 实测结果（2026-08-05） |
|---|---|---|---|---|
| 0074-01 | 免交互激活自有服务 | `SetAccessibilityServiceEnabled component=com.hmdm.testapp/com.hmdm.testapp.TestAccessibilityService enabled=true` | success=true、channel=settings、accessibilityEnabled=true、enabledServices 含目标；`settings get secure enabled_accessibility_services` 含目标、`accessibility_enabled=1`；`dumpsys accessibility` Bound services 含 TestAccessibilityService（无任何用户交互弹窗） | ✅ success=true、channel=settings；三处系统对照一致，服务真实绑定 |
| 0074-02 | 查询服务启用状态 | `IsAccessibilityServiceEnabled component=<自有服务>` | success=true、enabled=true、accessibilityEnabled=true | ✅ enabled=true |
| 0074-03 | 免交互激活第二个服务 | `SetAccessibilityServiceEnabled component=<豌豆荚服务> enabled=true` | success=true；enabledServices 含两个服务，总开关保持 1 | ✅ 两服务共存，accessibilityEnabled=true |
| 0074-04 | 注销其一（多服务共存） | `SetAccessibilityServiceEnabled component=<自有服务> enabled=false` | success=true；enabledServices 仅剩豌豆荚，总开关仍为 1（注销单个不影响总开关） | ✅ enabledServices=[豌豆荚]、accessibilityEnabled=true |
| 0074-05 | 注销最后一个服务 | `SetAccessibilityServiceEnabled component=<豌豆荚服务> enabled=false` | success=true；enabledServices 空、accessibility_enabled=0（完整注销语义） | ✅ 列表空 + 总开关 0 |
| 0074-06 | 幂等激活 | 连续两次 `enabled=true` | 两次均 success=true，列表不变 | ✅ |
| 0074-07 | 幂等注销 | 连续两次 `enabled=false`（已注销状态） | 两次均 success=true，列表不变 | ✅ |
| 0074-08 | 非法组件格式 | `SetAccessibilityServiceEnabled component=not-a-component enabled=true` | success=false + error "invalid component: not-a-component"，不写设置 | ✅ |
| 0074-09 | 缺 component 参数 | `SetAccessibilityServiceEnabled enabled=true` | error "missing parameter: component" | ✅ |
| 0074-10 | 缺 enabled 参数 | `SetAccessibilityServiceEnabled component=<自有服务>` | error "missing parameter: enabled" | ✅ |
| 0074-11 | 策略禁止时激活被拒 | 黑名单模式含自有服务时执行 0074-01 | success=false + error "activation blocked by accessibility service policy (mode=2)"，服务不启用（确定性拒绝，不依赖回滚） | ✅ |
| 0074-12 | 白名单外激活被拒 | 白名单模式（名单=自有服务）时激活豌豆荚 | success=false + error "activation blocked by accessibility service policy (mode=1)" | ✅ |
| 0074-13 | 真实绑定核验 | 激活后 `dumpsys accessibility` | Enabled services 含目标组件，无 Crashed services | ✅ 见 0074-01 |

### 2.2 ASR-0075 可使用无障碍功能黑白名单

| 编号 | 用例 | 操作（IPC） | 预期结果 | 实测结果（2026-08-05） |
|---|---|---|---|---|
| 0075-01 | 策略模式设置/查询 | `SetAccessibilityServicePolicyMode mode=1` → `GetAccessibilityServicePolicyMode` | mode=1、whitelist/blacklist 如实上报 | ✅ |
| 0075-02 | 非法模式 | `SetAccessibilityServicePolicyMode mode=3`（缺参 `SetAccessibilityServicePolicyMode`） | success=false + error "invalid mode: 3 (0/1/2)"（缺参 error "missing parameter: mode"），模式不变 | ✅ |
| 0075-03 | 白名单立即执行 | 两服务均启用 → `SetAccessibilityServiceWhitelist components=[自有服务]` → `SetAccessibilityServicePolicyMode mode=1` | 白名单外服务立即被移除（removed 上报豌豆荚）、自有服务保留、applied=true | ✅ removed=[豌豆荚]，enabledServices=[自有服务] |
| 0075-04 | 空白名单禁用全部 | 模式=1 后 `SetAccessibilityServiceWhitelist components=[]` | 全部已启用服务被移除，enabledServices 空 | ✅ |
| 0075-05 | 黑名单立即执行 | mode=0、两服务启用 → `SetAccessibilityServiceBlacklist components=[自有服务]` → `SetAccessibilityServicePolicyMode mode=2` | 黑名单服务立即被移除（removed 上报自有服务）、豌豆荚保留 | ✅ |
| 0075-06 | 黑名单观察者执行（模拟设置页开关） | 模式=2 黑名单=[自有服务] 时：`settings put secure enabled_accessibility_services "com.hmdm.testapp/com.hmdm.testapp.TestAccessibilityService:com.wandoujia...AccessibilityService"`（全格式，等同设置页写入）→ 等待约 1~2 秒 | 自有服务被观察者回滚（enabled_accessibility_services 只剩豌豆荚），合规项保留；launcher 日志 `settings change observed` + applyPolicy removed 核对 | ✅ 约 1 秒内回滚，只保留豌豆荚 |
| 0075-07 | 短格式注入同样被回滚 | 同上，注入短格式 `com.hmdm.testapp/.TestAccessibilityService` | 同样被回滚（组件名规范化，格式无关） | ✅ |
| 0075-08 | 名单查询 | `GetAccessibilityServiceWhitelist` / `GetAccessibilityServiceBlacklist` | success=true + 名单如实上报 | ✅ |
| 0075-09 | 名单整体替换/清空 | `SetAccessibilityServiceBlacklist components=[]`、`SetAccessibilityServiceWhitelist components=[]` | success=true、名单清空 | ✅ |
| 0075-10 | 立即执行命令 | 注入违规服务后 `ApplyAccessibilityServicePolicy` | 违规服务被移除（applied=true、removed 上报） | ✅ |
| 0075-11 | 进程重启武装 | 模式=1 白名单=[自有服务] 生效 → 杀掉 Launcher 进程 → 注入豌豆荚（launcher 停机窗口）→ 重启完成 | ApiService.onCreate 的 syncPolicy 重新执行策略，注入的豌豆荚被回滚（launcher 日志 applyPolicy removed 核对） | ✅ 重启后 23:21:53 日志 removed=[豌豆荚] |
| 0075-12 | 观察者空转（值未变） | 重复写入与当前相同的 enabled_accessibility_services | 状态本就合规，无副作用（SettingsProvider 不通知未变值，观察者不触发） | ✅ |
| 0075-13 | 恢复/清理 | mode=0、黑白名单清空 | GetAccessibilityServiceState：mode=0、名单空、enabledServices 空、accessibilityEnabled=false；`settings get` 双键核对 | ✅ 设备恢复基线 |

## 3. 验证提示

- 系统状态对照三件套：`adb shell settings get secure enabled_accessibility_services`、`settings get secure accessibility_enabled`、`adb shell dumpsys accessibility`（Bound services / Enabled services 段）；
- 观察者执行可在 launcher 日志核验：`/sdcard/Android/data/com.hmdm.launcher/files/logger/<date>/` 下 `settings change observed` 与 `applyPolicy ... removed=[...]` 条目；
- UI 按钮文本（AccessibilityTestActivity）：`Enable/Disable Own Accessibility Service`、`Query Own Accessibility Service`、`Query Full Accessibility State`、`Set Policy Mode Off/Whitelist/Blacklist`、`Query Policy Mode`、`Apply Policy Now`、`Set/Clear/Query Whitelist`、`Set/Clear/Query Blacklist`——与上表 IPC 事件一一对应；
- 激活/注销为**免交互**验证：全程不得出现设置页弹窗/确认框；`dumpsys accessibility` 服务绑定即证明框架已消费设置（无用户操作）。

## 4. 环境与 ROM 差异记录（2026-08-05）

1. **MTK DuraSpeed 抑制 manifest receiver（部署注意）**：对 testapp 执行 `am force-stop` 后，MTK DuraSpeed（`persist.vendor.duraspeed.*`）会把该包加入内存 suppress list（`dumpsys duraspeed suppress_list`），此后 AMS 丢弃该包的全部 manifest receiver 广播（logcat BroadcastQueue "suppress to start process of staticReceiver for package:..."，其逻辑位于 services.jar `AmsExtImpl.onBeforeStartProcessForStaticReceiver` → duraspeed.jar `SuppressAction`，策略位 0x800 + 名单命中）。**处理**：重启设备即清空该内存名单（开机后 `suppress list: []`）；测试期间避免 `am force-stop com.hmdm.testapp`，需要重启 testapp 进程时用 root `kill <pid>`（本机已验证不触发名单）。本批次全部用例在该环境下正常执行。
2. **本 ROM 无 `AccessibilityManager.setEnabledAccessibilityServiceList`**（Sheet1 P1 规划路径，2 参与 3 参均经 dex 反编译 + 运行期反射核验为 NoSuchMethodException）：引擎回退平台签名直写 Settings.Secure，channel=settings 为预期值（非缺陷）；stock AOSP 设备将走反射通道（channel=ams），接口契约不变。
3. **组件名格式**：设置页写入全格式（`pkg/pkg.Class`）、引擎写短格式（`pkg/.Class`）；引擎统一规范化，用例 0075-06/07 覆盖两种格式注入。
4. **无 TalkBack 等系统预置无障碍服务**：测试目标使用 testapp 自带服务 + 本机豌豆荚服务，均为真实可绑定服务；`dumpsys accessibility` 的绑定状态与设置键三处对照一致即为有效证据。

## 5. 用例执行结果汇总

- ASR-0074：13/13 通过（含 2 条策略联动用例，在 ASR-0075 策略生效状态下执行）；
- ASR-0075：13/13 通过；
- 全部用例执行后设备已恢复基线（策略关闭、名单清空、无障碍全部注销、总开关 0），无残留状态。
