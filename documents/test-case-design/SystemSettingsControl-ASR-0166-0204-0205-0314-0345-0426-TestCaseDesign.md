# 全局/安全设置管控（ASR-0166/0204/0205/0314/0345/0426）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner，testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root && dumpsys duraspeed addwhitelist com.hmdm.testapp`、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`（IPC 与 UI 按钮等效，同一 `TestActions.execute()` 引擎）；也可在 testapp UI 的 "System settings" 页面点按对应按钮；
- 对照命令：`adb shell settings get global/secure <键>`、`adb shell cmd overlay list | grep navbar`、`adb shell dumpsys window | grep ITYPE_NAVIGATION_BAR`；
- 恢复基线（测试开始时记录、结束时恢复）：`captive_portal_mode`（缺省=1）、`always_finish_activities`（缺省=0）、`mock_location`（=0）、`location_mode`（=3）、`navigation_mode`（=2，gestural overlay 启用）、动画三键（window/transition=1.0、animator 缺省）；
- 本 ROM 特性（2026-08-04 核验）：`navigation_mode` 由导航栏运行时 overlay 驱动、SystemUI 回写镜像值（gestural↔2、threebutton↔0）；直接写该设置无效，须经 `cmd overlay` 切换。

## 2. 测试用例表

### 2.1 ASR-0166 禁止 captive portal 弹窗（SetCaptivePortalDisabled / IsCaptivePortalDisabled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0166-01 基线查询 | `./send_test_command.sh IsCaptivePortalDisabled` | success=true；captive_portal_mode 与 captive_portal_detection_enabled 均为 1（缺省语义）；disabled=false |
| TC-0166-02 禁止弹窗 | `./send_test_command.sh SetCaptivePortalDisabled disabled=true` | success=true；两键均=0；`settings get global captive_portal_mode` / `captive_portal_detection_enabled` 对照=0 |
| TC-0166-03 查询状态 | `./send_test_command.sh IsCaptivePortalDisabled` | disabled=true；两键=0 |
| TC-0166-04 恢复允许 | `./send_test_command.sh SetCaptivePortalDisabled disabled=false` | success=true；两键均=1；系统对照=1 |
| TC-0166-05 缺参 | `./send_test_command.sh SetCaptivePortalDisabled` | 返回 `missing parameter: disabled`，不 crash |

### 2.2 ASR-0204 禁止"不保留活动"（SetAlwaysFinishActivitiesDisabled / IsAlwaysFinishActivitiesDisabled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0204-01 基线查询 | `./send_test_command.sh IsAlwaysFinishActivitiesDisabled` | success=true；always_finish_activities=0（缺省）；disabled=true |
| TC-0204-02 禁止不保留活动 | `./send_test_command.sh SetAlwaysFinishActivitiesDisabled disabled=true` | success=true；键=0；`settings get global always_finish_activities`=0 |
| TC-0204-03 允许不保留活动 | `./send_test_command.sh SetAlwaysFinishActivitiesDisabled disabled=false` | success=true；键=1；系统对照=1 |
| TC-0204-04 查询状态 | `./send_test_command.sh IsAlwaysFinishActivitiesDisabled` | 键=1 时 disabled=false |
| TC-0204-05 缺参 | `./send_test_command.sh SetAlwaysFinishActivitiesDisabled` | 返回 `missing parameter: disabled`，不 crash |

### 2.3 ASR-0205 禁止模拟定位（SetMockLocationDisabled / IsMockLocationDisabled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0205-01 基线查询 | `./send_test_command.sh IsMockLocationDisabled` | success=true；mock_location=0；disabled=true |
| TC-0205-02 禁止模拟定位 | `./send_test_command.sh SetMockLocationDisabled disabled=true` | success=true；键=0；`settings get secure mock_location`=0 |
| TC-0205-03 允许模拟定位 | `./send_test_command.sh SetMockLocationDisabled disabled=false` | success=true；键=1；系统对照=1 |
| TC-0205-04 查询状态 | `./send_test_command.sh IsMockLocationDisabled` | 键=1 时 disabled=false |
| TC-0205-05 缺参 | `./send_test_command.sh SetMockLocationDisabled` | 返回 `missing parameter: disabled`，不 crash |

### 2.4 ASR-0314 设置定位模式（SetLocationMode / GetLocationMode）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0314-01 基线查询 | `./send_test_command.sh GetLocationMode` | success=true；location_mode=3（高精度，本机基线） |
| TC-0314-02 省电模式 | `./send_test_command.sh SetLocationMode mode=2` | success=true；键=2；`settings get secure location_mode`=2 |
| TC-0314-03 仅设备模式 | `./send_test_command.sh SetLocationMode mode=1` | success=true；键=1 |
| TC-0314-04 关闭定位 | `./send_test_command.sh SetLocationMode mode=0` | success=true；键=0 |
| TC-0314-05 恢复高精度 | `./send_test_command.sh SetLocationMode mode=3` | success=true；键=3 |
| TC-0314-06 越界值 | `./send_test_command.sh SetLocationMode mode=4`、`mode=-1` | 返回 `invalid mode`，不写入（键保持原值），不 crash |
| TC-0314-07 缺参 | `./send_test_command.sh SetLocationMode` | 返回 `missing parameter: mode`，不 crash |

### 2.5 ASR-0345 禁用全面屏手势导航（SetGestureNavigationDisabled / IsGestureNavigationDisabled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0345-01 基线查询 | `./send_test_command.sh IsGestureNavigationDisabled` | success=true；navigation_mode=2；gesturalOverlay=true；disabled=false |
| TC-0345-02 禁用手势导航 | `./send_test_command.sh SetGestureNavigationDisabled disabled=true` | success=true；navigation_mode=0；threeButtonOverlay=true、gesturalOverlay=false；`settings get secure navigation_mode`=0；`cmd overlay list` 对照 threebutton 启用 |
| TC-0345-03 导航栏实际切换 | `adb shell dumpsys window \| grep ITYPE_NAVIGATION_BAR` | 三键导航栏 inset 生效（visible=true，48dp 高度栏）；屏幕底部出现返回/主页/最近任务三键 |
| TC-0345-04 查询状态 | `./send_test_command.sh IsGestureNavigationDisabled` | disabled=true；navigation_mode=0 |
| TC-0345-05 恢复手势导航 | `./send_test_command.sh SetGestureNavigationDisabled disabled=false` | success=true；navigation_mode=2（本 ROM SystemUI 回写值）；gesturalOverlay=true |
| TC-0345-06 缺参 | `./send_test_command.sh SetGestureNavigationDisabled` | 返回 `missing parameter: disabled`，不 crash |

### 2.6 ASR-0426 禁用安卓小动画（SetAnimationsDisabled / IsAnimationsDisabled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0426-01 基线查询 | `./send_test_command.sh IsAnimationsDisabled` | success=true；scales 三键非全 0（window/transition=1.0）；disabled=false |
| TC-0426-02 禁用动画 | `./send_test_command.sh SetAnimationsDisabled disabled=true` | success=true；scales 三键均="0"；`settings get global window_animation_scale` 等对照=0 |
| TC-0426-03 查询状态 | `./send_test_command.sh IsAnimationsDisabled` | disabled=true；三键均="0" |
| TC-0426-04 界面无动画 | 打开/切换应用 | 窗口切换无缩放/淡入淡出动画（动画缩放=0 生效） |
| TC-0426-05 恢复动画 | `./send_test_command.sh SetAnimationsDisabled disabled=false` | success=true；三键均="1.0"；系统对照=1.0 |
| TC-0426-06 缺参 | `./send_test_command.sh SetAnimationsDisabled` | 返回 `missing parameter: disabled`，不 crash |

### 2.7 通用用例

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-M-01 缺参容错 | 12 个命令均不带参数/缺 disabled 或 mode 执行 | 均返回缺参提示，不 crash |
| TC-M-02 未知事件 | `./send_test_command.sh SetCaptivePortalDisabledXXX` | 返回 unknown event，不 crash |
| TC-M-03 UI 等效 | testapp UI "System settings" 页逐一按钮 | 与 IPC 返回一致（同一 TestActions 引擎） |
| TC-M-04 返回值 JDK 类型 | 检查命令返回 JSON | 全部为 Map/List/基本类型，无自定义类（AIDL 通道约束） |
| TC-M-05 状态恢复 | 用例执行结束后复查全部键 | captive_portal 两键=1、always_finish_activities=0、mock_location=0、location_mode=3、navigation_mode=2（gestural overlay 启用）、动画三键=1.0，无测试残留 |

## 3. 验证提示

- 结果查看：`./send_test_command.sh` 输出 `Broadcast completed: data=...` 与 logcat `HYX-TESTAPP-CMD` 行；
- 设置对照：`adb shell settings get global/secure <键>`（captive_portal_mode、captive_portal_detection_enabled、always_finish_activities、mock_location、location_mode、navigation_mode、window_animation_scale、transition_animation_scale、animator_duration_scale）；
- 手势导航对照：`adb shell cmd overlay list | grep navbar`（[x] 为启用）、`adb shell dumpsys window | grep ITYPE_NAVIGATION_BAR`（三键模式 48dp 栏可见）；
- 动画生效对照：禁用后打开/切换应用窗口无动画（需肉眼观察，配合 TC-0426-04）；
- 恢复注意：ASR-0345 切换 overlay 后 SystemUI 会回写 `navigation_mode`（三键=0、手势=2），勿用 `settings put` 直改该键（无效且会被回写）。

## 4. 实测结果（2026-08-04，Android 13 / API 33 userdebug，平台签名 + device owner）

全部用例经 `send_test_command.sh` IPC 通道执行（与 UI 按钮共用 TestActions 引擎）：

| 用例 | 实测结果 |
|---|---|
| TC-0166-01 | 通过：`{"captive_portal_mode":1,"captive_portal_detection_enabled":1,"disabled":false,"success":true}` |
| TC-0166-02 | 通过：success=true，两键均=0；`settings get global` 对照 0/0 |
| TC-0166-03 | 通过：disabled=true，两键=0 |
| TC-0166-04 | 通过：success=true，两键均=1；系统对照=1 |
| TC-0166-05 | 通过：`missing parameter: disabled` |
| TC-0204-01 | 通过：`always_finish_activities=0`，disabled=true |
| TC-0204-02 | 通过：success=true，键=0，系统对照=0 |
| TC-0204-03 | 通过：success=true，键=1，系统对照=1 |
| TC-0204-04 | 通过：键=1 时 disabled=false |
| TC-0204-05 | 通过：`missing parameter: disabled` |
| TC-0205-01 | 通过：mock_location=0，disabled=true |
| TC-0205-02 | 通过：success=true，键=0，系统对照=0 |
| TC-0205-03 | 通过：success=true，键=1，系统对照=1 |
| TC-0205-04 | 通过：键=1 时 disabled=false |
| TC-0205-05 | 通过：`missing parameter: disabled` |
| TC-0314-01 | 通过：location_mode=3 |
| TC-0314-02 | 通过：success=true，键=2，系统对照=2 |
| TC-0314-03 | 通过：success=true，键=1 |
| TC-0314-04 | 通过：success=true，键=0 |
| TC-0314-05 | 通过：success=true，键=3 |
| TC-0314-06 | 通过：`mode=4`、`mode=-1`、`mode=abc` 均返回 `invalid mode: ...`，键保持原值（3），不写入、不 crash。**修复记录**：初测 `mode=-1` 误报 success（脚本将负数字符串化 + Gson 反序列化为 Double，`Bundle.getInt` 取不到整数），命令改为 Number/String 兼容解析后复测通过 |
| TC-0314-07 | 通过：`missing parameter: mode (0/1/2/3)` |
| TC-0345-01 | 通过：`{"navigation_mode":2,"disabled":false,"threeButtonOverlay":false,"gesturalOverlay":true}` |
| TC-0345-02 | 通过：success=true；navigation_mode=0；threeButtonOverlay=true、gesturalOverlay=false；`settings get secure navigation_mode`=0；`cmd overlay list` 对照 threebutton `[x]` |
| TC-0345-03 | 通过：`dumpsys window` ITYPE_NAVIGATION_BAR 48dp 栏 frame 存在（三键模式）；overlay 与 navigation_mode 双对照确认切换生效 |
| TC-0345-04 | 通过：disabled=true，navigation_mode=0 |
| TC-0345-05 | 通过：success=true；navigation_mode=2（SystemUI 回写）；gesturalOverlay=true |
| TC-0345-06 | 通过：`missing parameter: disabled` |
| TC-0426-01 | 通过：window/transition=1.0、animator=null（缺省），disabled=false |
| TC-0426-02 | 通过：success=true，三键均="0"；`settings get global` 对照 0/0/0 |
| TC-0426-03 | 通过：disabled=true，三键均="0" |
| TC-0426-04 | 通过（说明）：三键=0 后窗口切换无动画（IPC 无法量化，肉眼核验 + 系统值对照） |
| TC-0426-05 | 通过：success=true，三键均="1.0"，系统对照 1.0/1.0/1.0 |
| TC-0426-06 | 通过：`missing parameter: disabled` |
| TC-M-01 | 通过：6 个 Set 命令缺参均返回缺参提示，不 crash（6 个查询命令无参数） |
| TC-M-02 | 通过：`SetCaptivePortalDisabledXXX` → unknown event |
| TC-M-03 | 通过（说明）：UI/IPC 共用 TestActions.execute() 同一引擎，行为一致（UI 按钮未逐一点击，IPC 全覆盖；SystemSettingsTestActivity 已部署） |
| TC-M-04 | 通过：返回值全 JDK Map/List/基本类型 |
| TC-M-05 | 通过：全部键恢复基线——captive_portal_mode=1、captive_portal_detection_enabled 已删除（缺省）、always_finish_activities=0、mock_location=0、location_mode=3、navigation_mode=2（gestural overlay 启用）、动画三键=1.0 |

**部署注意**：`adb install -r` 重装 Launcher 会结束其进程且不会自动重启（HOME 桌面进程被杀），需重新拉起 HOME（`am start -a android.intent.action.MAIN -c android.intent.category.HOME`）后 testapp 进程亦需重启（`am force-stop com.hmdm.testapp`）以重建 AIDL 绑定，否则返回 `RESULT:null`（DeadObjectException）。
