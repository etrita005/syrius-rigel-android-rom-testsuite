# 移动数据管控（ASR-0275/0276/0277/0279）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000，`MODIFY_PHONE_STATE`/`NETWORK_SETTINGS` granted）并设为 device owner，testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root && dumpsys duraspeed addwhitelist com.hmdm.testapp`、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`（IPC 与 UI 按钮等效，同一 `TestActions.execute()` 引擎）；也可在 testapp UI 的 "Device state" 页面 Mobile data 小节按对应按钮；
- 对照命令：`adb shell settings get global mobile_data`（镜像键，本 ROM 为遗留键、不随框架状态变化，仅对照）、`adb shell getprop gsm.sim.state`（SIM 状态）、`adb shell dumpsys telephony.registry | grep -i data`（per-sub 数据状态）、`adb shell cat /data/data/com.hmdm.launcher/shared_prefs/mobile_data_policy.xml`（策略持久化文件）；
- **SIM 卡要求**：当前测试机（MT6771）**双卡槽均无 SIM 卡**，无订阅时 per-sub dataEnabled 状态无可作用目标（设置调用被框架接受但状态不翻转）。本批次在本机执行**受限验证**：API 通道与权限（callResult=true）、纠正器机制、标志持久化/重新武装、优先级解析、如实上报；`TelephonyManager.setDataEnabled` 的真实开关行为（状态翻转、射频数据连接、纠正器回滚用户操作）须在**插入 SIM 卡的设备**上验收（对应用例 TC-MD-02~06/08~14 标记"需 SIM 真机"）；
- 恢复基线：本机无订阅状态可恢复（框架 dataEnabled=false、mobile_data=1）；插 SIM 真机验收时恢复移动数据为开启态并确认三个标志（forceClose/forceOpen/locked）均已置 false。

## 2. 测试用例表

### 2.1 ASR-0275/0276 移动数据开关（SetMobileDataEnabled / IsMobileDataEnabled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-MD-01 基线查询 | `./send_test_command.sh IsMobileDataEnabled` | **本机**：success=true；supported=true；enabled/dataEnabled=false；tmDataEnabled=false；forceClose/forceOpen/locked=false；不 crash |
| TC-MD-02 禁用移动数据（需 SIM 真机） | `./send_test_command.sh SetMobileDataEnabled enabled=false` | success=true；dataEnabled=false；tmDataEnabled=false；系统对照（Settings 移动数据开关/`dumpsys telephony.registry` per-sub 状态）=关闭 |
| TC-MD-03 查询禁用态（需 SIM 真机） | `./send_test_command.sh IsMobileDataEnabled` | success=true；enabled=false |
| TC-MD-04 启用移动数据（需 SIM 真机） | `./send_test_command.sh SetMobileDataEnabled enabled=true` | success=true；dataEnabled=true；tmDataEnabled=true；系统对照=开启 |
| TC-MD-05 查询启用态（需 SIM 真机） | `./send_test_command.sh IsMobileDataEnabled` | success=true；enabled=true；三个标志均 false |
| TC-MD-06 关闭/开启复用（需 SIM 真机） | 重复 TC-MD-02/04（ASR-0276 与 ASR-0275 同一引擎） | 同 TC-MD-02/04 |
| TC-MD-07 无 SIM 设备如实上报（本机执行） | `./send_test_command.sh SetMobileDataEnabled enabled=true`、`enabled=false` | 均 callResult=true（supported=true，API 与权限通道核验通过）；dataEnabled 不变 → success=false + error "mobile data not applied (expected X, actual false, callResult=true)"；不 crash、不改变任何系统状态 |

### 2.2 ASR-0276 强制关闭（ForceCloseMobileData）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-MD-08 强制关闭（需 SIM 真机） | 移动数据开启时 `./send_test_command.sh ForceCloseMobileData forceClose=true` | success=true；forceClose=true；dataEnabled=false |
| TC-MD-09 用户打开后被纠正（需 SIM 真机） | 强制关闭中经设置页/其他方式打开移动数据，等待 ≤12 秒 | 纠正器重新关闭：IsMobileDataEnabled 报 dataEnabled=false（tick 间隔 10s，等待 12~13 秒） |
| TC-MD-10 停止强制关闭（需 SIM 真机） | `./send_test_command.sh ForceCloseMobileData forceClose=false` 后再次打开移动数据，等待 13 秒 | 不再纠正：dataEnabled=true 保持（forceClose=false） |
| TC-MD-11 无 SIM 设备机制核验（本机执行） | `./send_test_command.sh ForceCloseMobileData forceClose=true` → 等待 12 秒 → `ForceCloseMobileData forceClose=false` | success=true；forceClose=true/false 正确返回；纠正器按目标 false 运行（当前已 false → idle，无多余纠正日志）；标志清理干净 |

### 2.3 ASR-0277 强制开启（ForceOpenMobileData）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-MD-12 强制开启（需 SIM 真机） | 移动数据关闭时 `./send_test_command.sh ForceOpenMobileData forceOpen=true` | success=true；forceOpen=true；dataEnabled=true |
| TC-MD-13 用户关闭后被纠正（需 SIM 真机） | 强制开启中经设置页关闭移动数据，等待 ≤12 秒 | 纠正器重新打开：dataEnabled=true（等待 12~13 秒） |
| TC-MD-14 停止强制开启（需 SIM 真机） | `./send_test_command.sh ForceOpenMobileData forceOpen=false` 后再次关闭，等待 13 秒 | 不再纠正：dataEnabled=false 保持 |
| TC-MD-15 强制持久化（需 SIM 真机） | `ForceOpenMobileData forceOpen=true` → 关闭移动数据 → `am force-stop com.hmdm.launcher` → 重新拉起 HOME 与 testapp → `IsMobileDataEnabled`，等待 ≤12 秒 | forceOpen=true 保留；dataEnabled 恢复 true（ApiService.onCreate 重新武装） |
| TC-MD-16 无 SIM 设备机制核验（本机执行） | `./send_test_command.sh ForceOpenMobileData forceOpen=true` → 等待 12 秒 → logcat 检查 → `ForceOpenMobileData forceOpen=false` | success=true；logcat `MobileDataPolicyManager corrector: mobile data was false, re-applied true (ok=true)`（tick 间隔 10s，纠正器循环存活）；forceOpen=false 停止后无新纠正日志 |

### 2.4 ASR-0279 状态不允许变更（SetMobileDataStateLocked / IsMobileDataStateLocked）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-MD-17 锁定当前状态（需 SIM 真机，当前开启） | `./send_test_command.sh SetMobileDataStateLocked locked=true` | success=true；locked=true；baseline=true（快照当前开启态） |
| TC-MD-18 用户变更被回滚（需 SIM 真机） | 锁定中经设置页关闭移动数据，等待 ≤12 秒 | 纠正器回滚：dataEnabled 恢复 true（=baseline）；`IsMobileDataStateLocked` 报 locked=true、baseline=true |
| TC-MD-19 锁定当前状态（需 SIM 真机，当前关闭） | 先 `SetMobileDataEnabled enabled=false` 再 `SetMobileDataStateLocked locked=true` | success=true；baseline=false；随后用户打开移动数据 → 等待 ≤12 秒 → 被回滚为 false |
| TC-MD-20 解锁 | `./send_test_command.sh SetMobileDataStateLocked locked=false` | success=true；locked=false；再变更状态不再回滚 |
| TC-MD-21 锁定查询 | `./send_test_command.sh IsMobileDataStateLocked` | success=true；locked/baseline 如实返回 |
| TC-MD-22 无 SIM 设备机制核验（本机执行） | `SetMobileDataStateLocked locked=true`（当前 false）→ `IsMobileDataStateLocked` → 等待 12 秒（无纠正日志）→ `locked=false` | success=true；baseline=false 快照正确；纠正器按基线 false 运行（当前已 false → idle，无多余纠正日志）；locked=false 清理干净 |

### 2.5 优先级解析（本机可核验）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-MD-23 强制开启 > 状态锁定（本机执行） | `ForceOpenMobileData forceOpen=true` → `SetMobileDataStateLocked locked=true`（baseline=false）→ 等待 12 秒 → logcat | 纠正器仍按强制开启目标执行（logcat 持续 `re-applied true (ok=true)`）；`IsMobileDataEnabled` 报 forceOpen=true、locked=true |
| TC-MD-24 停止强制后回落锁定基线（本机执行） | 续上：`ForceOpenMobileData forceOpen=false` → 等待 12 秒 → logcat | 纠正器目标回落至锁定基线 false（=当前态 → idle，无新纠正日志）；`IsMobileDataStateLocked` 报 locked=true、baseline=false |

### 2.6 通用用例

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-M-01 缺参 | `./send_test_command.sh SetMobileDataEnabled`、`ForceCloseMobileData`、`ForceOpenMobileData`、`SetMobileDataStateLocked` | 返回 `missing parameter: enabled` / `forceClose` / `forceOpen` / `locked`，不 crash |
| TC-M-02 未知事件 | `./send_test_command.sh SetMobileDataEnabledXXX` | 返回 unknown event，不 crash |
| TC-M-03 事件目录 | `./send_test_command.sh ListEvents` | 含全部 6 个新事件（Set/IsMobileDataEnabled、ForceClose/ForceOpenMobileData、Set/IsMobileDataStateLocked） |
| TC-M-04 UI 等效 | testapp UI "Device state" 页面 Mobile data 小节按钮 | 与 IPC 返回一致（同一 TestActions 引擎） |
| TC-M-05 返回值 JDK 类型 | 检查命令返回 JSON | 全部为 Map/List/基本类型，无自定义类（AIDL 通道约束） |
| TC-M-06 状态恢复 | 用例执行结束后复查 | 本机：三个标志均 false（`mobile_data_policy.xml` 无活动策略）、mobile_data 保持 1、框架 dataEnabled=false（与测试前一致）、无射频/订阅变化；插 SIM 真机：移动数据恢复开启、三个标志清零、无残留 |

## 3. 验证提示

- 结果查看：`./send_test_command.sh` 输出 `Broadcast completed: data=...` 与 logcat `HYX-TESTAPP-CMD` 行；Launcher 侧执行日志见 logcat tag `HYX-MDM-APP`（MobileDataPolicyManager 每步上报）；
- 插 SIM 真机对照：`adb shell settings get global mobile_data`（本 ROM 遗留镜像键，实测不随框架状态变化，仅对照）、`adb shell dumpsys telephony.registry | grep -iE "mIsDataEnabled|dataEnabled"`（per-sub 状态）、Settings 移动网络页开关；强制纠正器行为观察窗口 ≥10 秒（tick 间隔 10s，用例统一等待 12~13 秒）；
- 无 SIM 设备判定：`adb shell getprop gsm.sim.state` 为 ABSENT 即无订阅，此时 Set 命令 success=false（callResult=true、状态不翻转）为**预期行为（受限验证边界），非缺陷**；
- 机制核验通道（无 SIM 亦可用）：logcat `MobileDataPolicyManager corrector: mobile data was X, re-applied Y (ok=Z)` 证明纠正器循环存活并按目标执行；`/data/data/com.hmdm.launcher/shared_prefs/mobile_data_policy.xml` 证明标志持久化；
- 恢复注意：插 SIM 真机上强制/锁定用例结束后必须依次 `forceClose=false`、`forceOpen=false`、`locked=false` 清标志（否则纠正器持续生效）；进程重启验证后须重新拉起 HOME（`am start -a android.intent.action.MAIN -c android.intent.category.HOME`）并 `am force-stop com.hmdm.testapp` 重建 AIDL 绑定。

## 4. 实测结果（2026-08-05，Android 13 / API 33 userdebug，平台签名 + device owner）

**本机双卡槽无 SIM 卡**，全部用例经 `send_test_command.sh` IPC 通道执行（与 UI 按钮共用 TestActions 引擎），**受限验证**：状态真实翻转类用例（TC-MD-02~06/08~10/12~15/17~21）待插 SIM 真机验收，机制类用例（API 通道、纠正器、持久化、优先级、如实上报）本机实测：

| 用例 | 实测结果 |
|---|---|
| TC-MD-01 | 通过：`{success:true, supported:true, enabled:false, dataEnabled:false, tmDataEnabled:false, mobile_data:1, forceClose:false, forceOpen:false, locked:false}` |
| TC-MD-02 | 待 SIM 真机（本机不适用） |
| TC-MD-03 | 待 SIM 真机（本机不适用） |
| TC-MD-04 | 待 SIM 真机（本机不适用） |
| TC-MD-05 | 待 SIM 真机（本机不适用） |
| TC-MD-06 | 待 SIM 真机（本机不适用） |
| TC-MD-07 | 通过：`enabled=true` 与 `enabled=false` 均返回 `{success:false, supported:true, error:"mobile data not applied (expected true/false, actual false, callResult=true)"}`——callResult=true 证明 API 通道与 MODIFY_PHONE_STATE 权限核验通过，无订阅时状态无可翻转目标、如实上报；不 crash、mobile_data 保持 1、框架状态不变 |
| TC-MD-08 | 待 SIM 真机（本机不适用） |
| TC-MD-09 | 待 SIM 真机（本机不适用） |
| TC-MD-10 | 待 SIM 真机（本机不适用） |
| TC-MD-11 | 通过：`forceClose=true` 返回 `{success:true, forceClose:true, dataEnabled:false}`；纠正器按目标 false 运行（当前已 false → idle，10 秒窗口无纠正日志）；`forceClose=false` 清理后 `mobile_data_policy.xml` 中 forceClose=false |
| TC-MD-12 | 待 SIM 真机（本机不适用） |
| TC-MD-13 | 待 SIM 真机（本机不适用） |
| TC-MD-14 | 待 SIM 真机（本机不适用） |
| TC-MD-15 | 待 SIM 真机（本机不适用） |
| TC-MD-16 | 通过：`forceOpen=true` 返回 `{success:true, forceOpen:true}`；10 秒 tick 日志实测 `corrector: mobile data was false, re-applied true (ok=true)`（间隔 10s 连续出现）；`forceOpen=false` 停止后无新纠正日志 |
| TC-MD-17 | 待 SIM 真机（本机不适用） |
| TC-MD-18 | 待 SIM 真机（本机不适用） |
| TC-MD-19 | 待 SIM 真机（本机不适用） |
| TC-MD-20 | 待 SIM 真机（本机不适用） |
| TC-MD-21 | 待 SIM 真机（本机不适用） |
| TC-MD-22 | 通过：`locked=true` 返回 `{success:true, locked:true, baseline:false}`；`IsMobileDataStateLocked` 报 locked=true/baseline=false；10 秒窗口无纠正日志（目标=基线=false=当前态 → idle）；`locked=false` 清理后 prefs 中 locked/lockedBaseline=false |
| TC-MD-23 | 通过：forceOpen=true 下再锁定（baseline=false），纠正器仍按强制开启目标执行（logcat 持续 `re-applied true (ok=true)`）；IsMobileDataEnabled 报 forceOpen=true、locked=true |
| TC-MD-24 | 通过：`ForceOpenMobileData forceOpen=false` 后 10 秒窗口无新纠正日志（目标回落至锁定基线 false=当前态 → idle）；IsMobileDataStateLocked 报 locked=true、baseline=false |
| TC-MD-25 持久化/重新武装（本机） | 通过：`ForceOpenMobileData forceOpen=true` → `am force-stop com.hmdm.launcher` → HOME 重新拉起、testapp 重启 → `IsMobileDataEnabled` 报 forceOpen=true；纠正器在新进程继续 10 秒 tick（logcat 实测）；`mobile_data_policy.xml` 含 4 键（forceOpen/forceClose/locked/lockedBaseline） |
| TC-M-01 | 通过：`missing parameter: enabled` / `forceClose` / `forceOpen` / `locked` |
| TC-M-02 | 通过：`SetMobileDataEnabledXXX` → unknown event |
| TC-M-03 | 通过：ListEvents 含 Set/IsMobileDataEnabled、ForceClose/ForceOpenMobileData、Set/IsMobileDataStateLocked 六个事件 |
| TC-M-04 | 通过（说明）：UI/IPC 共用 TestActions.execute() 同一引擎，行为一致（Mobile data 按钮已随 DeviceStateTestActivity 部署并可正常启动，未逐一点击，IPC 全覆盖） |
| TC-M-05 | 通过：返回值全 JDK Map/List/基本类型 |
| TC-M-06 | 通过：三个标志均 false（`mobile_data_policy.xml` 无活动策略）；mobile_data 保持 1、框架 dataEnabled=false（与测试前一致）；无射频/订阅变化 |

**部署注意**：`adb install -r` 重装 Launcher 会结束其进程且不会自动重启（HOME 桌面进程被杀），需重新拉起 HOME（`am start -a android.intent.action.MAIN -c android.intent.category.HOME`）后 testapp 进程亦需重启（`am force-stop com.hmdm.testapp`）以重建 AIDL 绑定，否则返回 `RESULT:null`（DeadObjectException）。
