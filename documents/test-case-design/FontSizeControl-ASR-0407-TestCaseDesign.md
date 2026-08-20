# 字体大小管控（ASR-0407）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner（`dpm list-owners` = DeviceOwner,Affiliated），testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root && dumpsys duraspeed addwhitelist com.hmdm.testapp`（MTK DuraSpeed 白名单，防 manifest receiver 广播被丢弃；**注意：`am force-stop com.hmdm.testapp` 会使 testapp 进入 DuraSpeed suppress list、广播被静默丢弃（Broadcast result=0）——整机重启清空恢复**，测试期间避免 force-stop 或测试前检查 `dumpsys duraspeed suppress_list`）、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`（IPC 与 UI 按钮等效，同一 `TestActions.execute()` 引擎）；也可在 testapp UI 的 "System settings" 页 "ASR-0407 Font size" 分区输入 scale 后点按 "Set Font Scale"/"Get Font Scale"；
- 对照命令：`adb shell settings get system font_scale`、`adb shell dumpsys activity`（`mGlobalConfiguration` 首字段 fontScale，ATMS 全局配置，框架层事实）、`adb shell dumpsys window`（WMS mGlobalConfiguration 同字段交叉对照）；
- 预置档：本 ROM Settings 应用字体大小档位 `entryvalues_font_size = [0.85, 1.0, 1.15, 1.30]`（aapt2 资源核验），命令附报 presets；
- 恢复基线（测试开始时记录、结束时恢复）：`font_scale=1.0`（缺省），mGlobalConfiguration `{1.0 ...}`。

## 2. 测试用例表

### 2.1 ASR-0407 字体大小（SetFontScale / GetFontScale）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0407-01 基线查询 | `./send_test_command.sh GetFontScale` | success=true；scale=1.0；presets=[0.85, 1, 1.15, 1.3] |
| TC-0407-02 设置小号档 | `./send_test_command.sh SetFontScale scale=0.85` | success=true；scale=0.85（写后读回核对）；`settings get system font_scale`=0.85；mGlobalConfiguration `{0.85 ...}` |
| TC-0407-03 设置大号档 | `./send_test_command.sh SetFontScale scale=1.15` | success=true；scale=1.15；mGlobalConfiguration `{1.15 ...}` |
| TC-0407-04 设置特大档 | `./send_test_command.sh SetFontScale scale=1.3` | success=true；scale=1.3；mGlobalConfiguration `{1.3 ...}`（ATMS/WMS 双份一致） |
| TC-0407-05 查询回读 | `./send_test_command.sh GetFontScale` | success=true；scale 与最近一次设置一致 |
| TC-0407-06 越界拒绝（下限） | `./send_test_command.sh SetFontScale scale=0.4` | error "invalid scale: 0.4 (0.5..2.0)"；键值保持原样（不写库） |
| TC-0407-07 越界拒绝（上限） | `./send_test_command.sh SetFontScale scale=2.5` | error "invalid scale: 2.5 (0.5..2.0)"；键值保持原样 |
| TC-0407-08 非数值拒绝 | `./send_test_command.sh SetFontScale scale=abc` | error "invalid scale: abc (0.5..2.0, ...)"；不 crash、不写库 |
| TC-0407-09 缺参 | `./send_test_command.sh SetFontScale`（不带 scale） | 返回 `missing parameter: scale (0.5..2.0)`，不 crash、不改状态 |
| TC-0407-10 恢复默认档 | `./send_test_command.sh SetFontScale scale=1.0` | success=true；scale=1.0；mGlobalConfiguration `{1.0 ...}` |
| TC-0407-11 整机重启保持 | 设置 1.3 后 `adb reboot`，开机后查询 | `settings get system font_scale`=1.3；GetFontScale → scale=1.3（SettingsProvider 持久化，无需重新武装） |

### 2.2 通用用例

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-M-01 未知事件 | `./send_test_command.sh SetFontScaleXXX` | 返回 unknown event，不 crash |
| TC-M-02 UI 等效 | testapp UI "System settings" 页 "ASR-0407 Font size" 分区：输入 1.3 → "Set Font Scale" | 页面为 resumed activity（mFocusedApp=SystemSettingsTestActivity）；输入框/按钮与 IPC 共用 `TestActions.execute()` 引擎；设置后 font_scale=1.3 + mGlobalConfiguration `{1.3 ...}`（真机闭环） |
| TC-M-03 返回值 JDK 类型 | 检查命令返回 JSON | 全部为 Map/List/基本类型，无自定义类（AIDL 通道约束） |
| TC-M-04 事件目录 | `./send_test_command.sh ListEvents` | 目录含 2 个新事件（SetFontScale/GetFontScale）及其参数说明 |

## 3. 验证提示

- 结果查看：`./send_test_command.sh` 输出 `Broadcast completed: data=...` 与 logcat `HYX-TESTAPP-CMD` 行（本 ROM goAsync 时序下 result 码恒为 -1，以 data 内容为准；**若 result=0 且无日志，检查 DuraSpeed suppress_list**）；
- 系统状态对照：`adb shell settings get system font_scale`（存储层）+ `dumpsys activity` 的 `mGlobalConfiguration` 首字段（框架全局配置，权威生效证据；`dumpsys window` 的 mGlobalConfiguration 交叉对照）；
- 档位换算：命令接受任意 0.5~2.0 浮点；预置档 0.85/1.0/1.15/1.30 与 Settings 应用滑块档位对应（非预置值框架同样生效，滑块按最接近档位显示）；
- UI 输入：EditText 用 `input text` 输入浮点（键盘弹出会改变布局，先 `input keyevent 4` 收起键盘再点按 Set 按钮，按钮 bounds 以收起后 uiautomator dump 为准）。

## 4. 实测结果（2026-08-11，Android 13 / API 33 userdebug，平台签名 + device owner）

全部用例经 `send_test_command.sh` IPC 通道执行（与 UI 按钮共用 TestActions 引擎）。**设备基线（实测）**：font_scale 未设置（缺省 1.0）、mGlobalConfiguration `{1.0 ...}`。

| 用例 | 实测结果 |
|---|---|
| TC-0407-01 | 通过：`{"scale":1,"success":true,"presets":[0.85,1,1.15,1.3]}` |
| TC-0407-02 | 通过：`{"scale":0.85,"success":true,...}`；`settings get system font_scale`=0.85 |
| TC-0407-03 | 通过：`{"scale":1.15,"success":true,...}`；mGlobalConfiguration `{1.15 ...}` |
| TC-0407-04 | 通过：`{"scale":1.3,"success":true,...}`；mGlobalConfiguration `{1.3 ...}`（dumpsys activity 与 dumpsys window 双份一致） |
| TC-0407-05 | 通过：GetFontScale 返回 scale 与最近设置一致 |
| TC-0407-06 | 通过：`{"error":"invalid scale: 0.4 (0.5..2.0)"}`；键值保持原样 |
| TC-0407-07 | 通过：`{"error":"invalid scale: 2.5 (0.5..2.0)"}`；键值保持原样 |
| TC-0407-08 | 通过：`{"error":"invalid scale: abc (0.5..2.0, Settings app presets 0.85\/1.0\/1.15\/1.30)"}`，不 crash |
| TC-0407-09 | 通过：`missing parameter: scale (0.5..2.0)` |
| TC-0407-10 | 通过：`{"scale":1,"success":true,...}`；mGlobalConfiguration `{1.0 ...}` |
| TC-0407-11 | 通过：整机重启后 `settings get system font_scale`=1.3（重启前设置值保持）、GetFontScale 返回 1.3（SettingsProvider 持久化）；测试后恢复 1.0 |
| TC-M-01 | 通过：`unknown event: SetFontScaleXXX (try ListEvents)` |
| TC-M-02 | 通过：页面 resumed（mFocusedApp=com.hmdm.testapp/.SystemSettingsTestActivity）；uiautomator dump 见 "ASR-0407 Font size" 分区与 et_font_scale/btn_font_scale_set/btn_font_scale_get（bounds 实测）；UI 输入 1.3 点 "Set Font Scale" → font_scale=1.3 + mGlobalConfiguration `{1.3 ...}`；再经 UI 恢复 1.0 → `{1.0 ...}`（与 IPC 结果一致，共用 TestActions 引擎） |
| TC-M-03 | 通过：返回值全 JDK Map/List/基本类型 |
| TC-M-04 | 通过：ListEvents 含 SetFontScale/GetFontScale 及参数说明 |

**机制核验补充（2026-08-11）**：① 本 ROM 字体大小机制与 Settings 应用完全同键同路径——MtkSettings `FontSizeData.commit` 写 `Settings$System.putFloat("font_scale")`（dex 反编译核验），system_server `ActivityTaskManagerService$SettingObserver` 监听该键并经 `updateFontScaleIfNeeded` + `updatePersistentConfiguration` 全系统生效（services.jar dex 核验），命令返回与 mGlobalConfiguration 逐档一致；② 预置档 `entryvalues_font_size=["0.85","1.0","1.15","1.30"]` 为 4 档（小/默认/大/特大），命令附报 presets 供档位换算；③ Settings.System 存储持久化，整机重启无需重新武装。

**测试环境记录**：UI 测试需 `am force-stop com.hmdm.testapp`，触发 MTK DuraSpeed 将 testapp 加入 suppress list（广播被丢弃，Broadcast result=0）；`dumpsys duraspeed addwhitelist` 仅解除自启限制，**suppress list 需整机重启清空**（与历史批次一致）；重启后用例复测通过。
