# 被动定位管控（ASR-0313）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner（`dpm list-owners` = DeviceOwner,Affiliated），testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root && dumpsys duraspeed addwhitelist com.hmdm.testapp`（MTK DuraSpeed 白名单，防 manifest receiver 广播被丢弃；**注意：`am force-stop com.hmdm.testapp` 会使 testapp 进入 DuraSpeed suppress list、广播被静默丢弃（Broadcast result=0）——整机重启清空恢复**，测试期间避免 force-stop 或测试前检查 `dumpsys duraspeed suppress_list`）、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`（IPC 与 UI 按钮等效，同一 `TestActions.execute()` 引擎）；也可在 testapp UI 的 "Device state" 页 "ASR-0313 Passive location" 分区点按对应按钮；
- 对照命令：`adb shell settings get secure location_mode`、`adb shell dumpsys location`（`Location Providers:` 区 passive provider 的 `enabled=` 字段与历史日志 `passive provider [u0] enabled/disabled`）；
- 恢复基线（测试开始时记录、结束时恢复）：`location_mode=3`（高精度）、passive provider enabled=true。

## 2. 测试用例表

### 2.1 ASR-0313 被动定位（SetPassiveLocationAllowed / IsPassiveLocationAllowed）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0313-01 基线查询 | `./send_test_command.sh IsPassiveLocationAllowed` | success=true；passiveAllowed=true；location_mode=3；savedMode=3 |
| TC-0313-02 禁止被动定位 | `./send_test_command.sh SetPassiveLocationAllowed allowed=false` | success=true；passiveAllowed=false；location_mode=0；savedMode=3（当前模式被保存） |
| TC-0313-03 系统状态对照 | `adb shell settings get secure location_mode` + `adb shell dumpsys location` | location_mode=0；passive provider `enabled=false`（历史日志 `passive provider [u0] disabled` + `ProviderRequest[OFF]`） |
| TC-0313-04 禁止后查询 | `./send_test_command.sh IsPassiveLocationAllowed` | success=true；passiveAllowed=false；location_mode=0 |
| TC-0313-05 允许被动定位 | `./send_test_command.sh SetPassiveLocationAllowed allowed=true` | success=true；passiveAllowed=true；location_mode=3（恢复 savedMode）；`dumpsys location` passive provider enabled=true |
| TC-0313-06 省电模式往返 | ① `SetLocationMode mode=2`；② `SetPassiveLocationAllowed allowed=false`；③ `allowed=true` | ② savedMode=2、location_mode=0；③ location_mode=2（按模式保存/恢复，非固定 3）；IsPassiveLocationAllowed → passiveAllowed=true |
| TC-0313-07 传感器模式查询 | `SetLocationMode mode=1` 后 `IsPassiveLocationAllowed` | success=true；passiveAllowed=true（非 0 模式被动 provider 均启用）；`dumpsys location` passive enabled=true |
| TC-0313-08 幂等禁止 | 连续两次 `SetPassiveLocationAllowed allowed=false` | 两次均 location_mode=0；savedMode 不被重复覆盖（保持首次保存值） |
| TC-0313-09 缺参 | `./send_test_command.sh SetPassiveLocationAllowed`（不带 allowed） | 返回 `missing parameter: allowed`，不 crash、不改状态 |
| TC-0313-10 整机重启保持 | 恢复 mode=3 后 `adb reboot`，开机后查询 | `settings get secure location_mode`=3；IsPassiveLocationAllowed → passiveAllowed=true（Settings.Secure 持久化，无需重新武装） |
| TC-0313-11 恢复无残留 | 测试结束 `SetPassiveLocationAllowed allowed=true` + `SetLocationMode mode=3` | location_mode=3、passiveAllowed=true、passive provider enabled=true |

### 2.2 通用用例

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-M-01 未知事件 | `./send_test_command.sh SetPassiveLocationAllowedXXX` | 返回 unknown event，不 crash |
| TC-M-02 UI 等效 | testapp UI "Device state" 页 "ASR-0313 Passive location" 分区按钮（Forbid/Allow/Query） | 页面为 resumed activity（mFocusedApp=DeviceStateTestActivity）；按钮与 IPC 共用 `TestActions.execute()` 引擎；点 Forbid → location_mode=0、点 Allow → 恢复 3（真机闭环） |
| TC-M-03 返回值 JDK 类型 | 检查命令返回 JSON | 全部为 Map/List/基本类型，无自定义类（AIDL 通道约束） |
| TC-M-04 事件目录 | `./send_test_command.sh ListEvents` | 目录含 2 个新事件（SetPassiveLocationAllowed/IsPassiveLocationAllowed）及其参数说明 |

## 3. 验证提示

- 结果查看：`./send_test_command.sh` 输出 `Broadcast completed: data=...` 与 logcat `HYX-TESTAPP-CMD` 行（本 ROM goAsync 时序下 result 码恒为 -1，以 data 内容为准；**若 result=0 且无日志，检查 DuraSpeed suppress_list**）；
- 系统状态对照：`dumpsys location` 的 `Location Providers:` 区——passive provider 的 `enabled=true/false` 字段（框架层事实，与命令返回的 passiveAllowed 一致），以及历史区 `passive provider [u0] enabled/disabled` 事件；
- 语义说明：Android 13 无独立"被动定位"开关，被动 provider 随 `location_mode`（LOCATION_MODE 组合）启用——禁止=写 0（保存原模式）、允许=恢复保存模式（默认 3），与 ASR-0310/0311 共用引擎与 savedMode 账本。

## 4. 实测结果（2026-08-11，Android 13 / API 33 userdebug，平台签名 + device owner）

全部用例经 `send_test_command.sh` IPC 通道执行（与 UI 按钮共用 TestActions 引擎）。**设备基线（实测）**：location_mode=3、savedMode=3、passive provider enabled=true。

| 用例 | 实测结果 |
|---|---|
| TC-0313-01 | 通过：`{"location_mode":3,"savedMode":3,"success":true,"passiveAllowed":true}` |
| TC-0313-02 | 通过：`{"location_mode":0,"savedMode":3,"success":true,"enabled":false,"passiveAllowed":false}` |
| TC-0313-03 | 通过：`settings get secure location_mode`=0；`dumpsys location` 历史日志 `08-11 22:18:55.688: passive provider [u0] disabled` + `passive provider request = ProviderRequest[OFF]` |
| TC-0313-04 | 通过：`{"location_mode":0,"savedMode":3,"success":true,"passiveAllowed":false}` |
| TC-0313-05 | 通过：`{"location_mode":3,"savedMode":3,"success":true,"enabled":true,"passiveAllowed":true}`；`dumpsys location` passive/fused/gps 均 `enabled=true` |
| TC-0313-06 | 通过：② `{"location_mode":0,"savedMode":2,...}`；③ `{"location_mode":2,...}`；`settings get secure location_mode`=2（按模式保存/恢复） |
| TC-0313-07 | 通过：mode=1 下 `{"location_mode":1,"savedMode":2,"success":true,"passiveAllowed":true}` |
| TC-0313-08 | 通过：两次禁止均 location_mode=0，savedMode 保持首次保存值（1，未被第二次覆盖） |
| TC-0313-09 | 通过：`missing parameter: allowed` |
| TC-0313-10 | 通过：整机重启后 `settings get secure location_mode`=3、IsPassiveLocationAllowed → passiveAllowed=true（SettingsProvider 持久化） |
| TC-0313-11 | 通过：恢复 mode=3，passive/fused/gps provider 均 enabled=true，无残留 |
| TC-M-01 | 通过：`unknown event: SetPassiveLocationAllowedXXX (try ListEvents)` |
| TC-M-02 | 通过：页面 resumed（mFocusedApp=com.hmdm.testapp/.DeviceStateTestActivity）；uiautomator dump 见 "ASR-0313 Passive location" 分区与 FORBID/ALLOW/QUERY 三按钮（bounds 实测）；点按 Forbid → location_mode=0、点按 Allow → 恢复 3（与 IPC 结果一致，共用 TestActions 引擎） |
| TC-M-03 | 通过：返回值全 JDK Map/List/基本类型 |
| TC-M-04 | 通过：ListEvents 含 SetPassiveLocationAllowed/IsPassiveLocationAllowed 及参数说明 |

**机制核验补充（2026-08-11）**：① 本 ROM 被动 provider 的启用状态完全由 `location_mode` 决定——mode 1/2/3 均 enabled=true、mode 0 时 disabled（`dumpsys location` 事件日志实测）；② 与 ASR-0310/0311 共用 `location_policy` savedMode 账本，三需求互不干扰；③ 与 ASR-0311 强制打开并存时强制纠正器优先级更高（禁止被动定位会被 10 秒纠正器重新打开），为预期语义；④ Settings.Secure 存储持久化，整机重启无需重新武装。

**测试环境记录**：`am force-stop com.hmdm.testapp`（UI 测试需要）触发 MTK DuraSpeed 将 testapp 加入 suppress list（`dumpsys duraspeed suppress_list`），manifest receiver 广播被 AMS 丢弃（Broadcast result=0、无 HYX-TESTAPP-CMD 日志）；`adb root` 下 `dumpsys duraspeed addwhitelist com.hmdm.testapp` 仅解除自启限制，**suppress list 需整机重启清空**（与历史批次一致）；重启后全部用例复测通过。
