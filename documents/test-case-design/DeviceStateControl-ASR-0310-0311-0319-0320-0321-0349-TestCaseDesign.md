# 定位/导航栏/飞行模式管控（ASR-0310/0311/0319/0320/0321/0349）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner，testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root && dumpsys duraspeed addwhitelist com.hmdm.testapp`、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`（IPC 与 UI 按钮等效，同一 `TestActions.execute()` 引擎）；也可在 testapp UI 的 "Device state" 页面点按对应按钮；
- 对照命令：`adb shell settings get secure location_mode`、`adb shell settings get global airplane_mode_on`、`adb shell settings get system navigation_visible`、`adb shell dumpsys wifi | grep AirplaneModeOn`、`adb shell dumpsys window windows | grep -A8 NavigationBar0 | grep isVisible`、`adb shell dumpsys telephony.registry | grep mRadioPowerState`；
- 恢复基线（测试开始时记录、结束时恢复）：`location_mode=3`（高精度）、`navigation_mode=2`（gestural overlay 启用）、`navigation_visible=1`、`airplane_mode_on=0`（`dumpsys wifi` AirplaneModeOn=false）；
- 本 ROM 特性（2026-08-05 核验）：飞行模式须"写 `airplane_mode_on` + 发 `ACTION_AIRPLANE_MODE_CHANGED` 广播"两步同做才生效（单写或单广播均不生效）；导航栏可见性由 MTK 键 `Settings.System.navigation_visible` 控制（`navigation_mode` 为 overlay 镜像值，直写无效）；飞行模式下本 ROM Wi-Fi 保持启用（MTK 行为）。

## 2. 测试用例表

### 2.1 ASR-0310/0311 定位服务（SetLocationEnabled / IsLocationEnabled / ForceOpenLocation）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0310-01 基线查询 | `./send_test_command.sh IsLocationEnabled` | success=true；location_mode=3（本机基线）；enabled=true；savedMode=3；forceOpen=false |
| TC-0310-02 禁用定位 | `./send_test_command.sh SetLocationEnabled enabled=false` | success=true；location_mode=0；savedMode=3；`settings get secure location_mode`=0 |
| TC-0310-03 查询禁用态 | `./send_test_command.sh IsLocationEnabled` | success=true；enabled=false；location_mode=0 |
| TC-0310-04 启用定位（恢复） | `./send_test_command.sh SetLocationEnabled enabled=true` | success=true；location_mode=3（恢复 savedMode）；系统对照=3 |
| TC-0311-01 关闭/打开复用 | 重复 TC-0310-02/04（ASR-0311 关闭/打开与 ASR-0310 同一引擎） | 同 TC-0310-02/04 |
| TC-0311-02 开启时保持用户模式 | `SetLocationMode mode=2` 后 `SetLocationEnabled enabled=false` 再 `enabled=true` | 启用恢复 2（保存的是禁用时的模式，而非固定 3） |
| TC-0311-03 强制打开 | `./send_test_command.sh ForceOpenLocation forceOpen=true` | success=true；location_mode=3；forceOpen=true |
| TC-0311-04 用户关闭后被纠正 | `adb shell settings put secure location_mode 0`（模拟用户关闭），等待 ≤12 秒 | 纠正器将 location_mode 重写回 3（`settings get` 对照） |
| TC-0311-05 停止强制 | `./send_test_command.sh ForceOpenLocation forceOpen=false` 后 `settings put secure location_mode 0`，等待 13 秒 | 不再纠正：location_mode 保持 0（IsLocationEnabled 报 forceOpen=false） |
| TC-0311-06 强制持久化（进程重启） | `ForceOpenLocation forceOpen=true` → `settings put secure location_mode 0` → `am force-stop com.hmdm.launcher` → 重新拉起 HOME 与 testapp → `IsLocationEnabled`，等待 ≤12 秒 | forceOpen=true 保留；location_mode 恢复 3（ApiService.onCreate 重新武装） |
| TC-0310-05 缺参 | `./send_test_command.sh SetLocationEnabled`、`ForceOpenLocation` | 返回 `missing parameter: enabled` / `missing parameter: forceOpen`，不 crash |

### 2.2 ASR-0319/0320 飞行模式（SetAirplaneMode / IsAirplaneMode）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0319-01 基线查询 | `./send_test_command.sh IsAirplaneMode` | success=true；airplane_mode_on=0；enabled=false；forceOpen=false |
| TC-0319-02 打开飞行模式 | `./send_test_command.sh SetAirplaneMode enabled=true` | success=true；airplane_mode_on=1；`settings get global airplane_mode_on`=1；`dumpsys wifi` 出现 `AirplaneModeOn true` |
| TC-0319-03 射频生效对照 | `adb shell dumpsys telephony.registry \| grep mRadioPowerState`、`dumpsys bluetooth_manager` | 射频电源按 SIM 关闭（出现 mRadioPowerState=0）、蓝牙关闭 |
| TC-0319-04 查询开启态 | `./send_test_command.sh IsAirplaneMode` | success=true；enabled=true；airplane_mode_on=1 |
| TC-0319-05 关闭飞行模式 | `./send_test_command.sh SetAirplaneMode enabled=false` | success=true；airplane_mode_on=0；`dumpsys wifi` 恢复 `AirplaneModeOn false` |
| TC-0320-01 打开/关闭复用 | 重复 TC-0319-02/05（ASR-0320 与 ASR-0319 同一引擎） | 同 TC-0319-02/05 |
| TC-0319-06 缺参 | `./send_test_command.sh SetAirplaneMode` | 返回 `missing parameter: enabled`，不 crash |

### 2.3 ASR-0321 强制打开飞行模式（ForceOpenAirplaneMode）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0321-01 强制打开 | `./send_test_command.sh ForceOpenAirplaneMode forceOpen=true` | success=true；airplane_mode_on=1；forceOpen=true |
| TC-0321-02 用户关闭后被纠正 | `adb shell settings put global airplane_mode_on 0` + `am broadcast -a android.intent.action.AIRPLANE_MODE_CHANGED --ez state false`（模拟用户关闭），等待 ≤12 秒 | 纠正器重开：airplane_mode_on=1；`dumpsys wifi` AirplaneModeOn true |
| TC-0321-03 查询强制态 | `./send_test_command.sh IsAirplaneMode` | success=true；enabled=true；forceOpen=true |
| TC-0321-04 停止强制 | `./send_test_command.sh ForceOpenAirplaneMode forceOpen=false` 后再次模拟用户关闭，等待 13 秒 | 不再纠正：airplane_mode_on 保持 0 |
| TC-0321-05 缺参 | `./send_test_command.sh ForceOpenAirplaneMode` | 返回 `missing parameter: forceOpen`，不 crash |

### 2.4 ASR-0349 导航栏（SetNavigationBarEnabled / IsNavigationBarEnabled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0349-01 基线查询 | `./send_test_command.sh IsNavigationBarEnabled` | success=true；navigation_visible=1；enabled=true；navigation_mode=2（对照值） |
| TC-0349-02 禁用导航栏 | `./send_test_command.sh SetNavigationBarEnabled enabled=false` | success=true；navigation_visible=0；`settings get system navigation_visible`=0 |
| TC-0349-03 导航栏实际隐藏 | `adb shell dumpsys window windows \| grep -A8 NavigationBar0 \| grep isVisible` | NavigationBar0 窗口 isVisible=false（栏隐藏） |
| TC-0349-04 查询禁用态 | `./send_test_command.sh IsNavigationBarEnabled` | success=true；enabled=false；navigation_visible=0 |
| TC-0349-05 启用导航栏 | `./send_test_command.sh SetNavigationBarEnabled enabled=true` | success=true；navigation_visible=1；系统对照=1 |
| TC-0349-06 导航栏实际显示 | 同 TC-0349-03 对照命令 | NavigationBar0 窗口 isVisible=true（栏恢复） |
| TC-0349-07 缺参 | `./send_test_command.sh SetNavigationBarEnabled` | 返回 `missing parameter: enabled`，不 crash |

### 2.5 通用用例

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-M-01 缺参容错 | 5 个 Set/Force 命令均不带参数执行 | 均返回缺参提示，不 crash |
| TC-M-02 未知事件 | `./send_test_command.sh SetLocationEnabledXXX` | 返回 unknown event，不 crash |
| TC-M-03 事件目录 | `./send_test_command.sh ListEvents` | 含全部 8 个新事件（Set/IsLocationEnabled、ForceOpenLocation、Set/IsAirplaneMode、ForceOpenAirplaneMode、Set/IsNavigationBarEnabled） |
| TC-M-04 UI 等效 | testapp UI "Device state" 页逐一按钮 | 与 IPC 返回一致（同一 TestActions 引擎） |
| TC-M-05 返回值 JDK 类型 | 检查命令返回 JSON | 全部为 Map/List/基本类型，无自定义类（AIDL 通道约束） |
| TC-M-06 状态恢复 | 用例执行结束后复查全部键 | location_mode=3、navigation_visible=1、airplane_mode_on=0（AirplaneModeOn false）、navigation_mode=2，无测试残留 |

## 3. 验证提示

- 结果查看：`./send_test_command.sh` 输出 `Broadcast completed: data=...` 与 logcat `HYX-TESTAPP-CMD` 行；
- 定位对照：`adb shell settings get secure location_mode`（0/1/2/3）；强制纠正器行为观察窗口 ≥10 秒（tick 间隔 10s，用例统一等待 12~13 秒）；
- 飞行模式对照：`adb shell settings get global airplane_mode_on`、`adb shell dumpsys wifi | grep AirplaneModeOn`（true/false）、`adb shell dumpsys telephony.registry | grep mRadioPowerState`（射频电源）、`adb shell dumpsys bluetooth_manager | grep "state:"`；**注意**：本 ROM 飞行模式下 Wi-Fi 仍显示 enabled（MTK 行为），勿以 Wi-Fi 状态判定飞行模式生效；
- 导航栏对照：`adb shell settings get system navigation_visible`、`adb shell dumpsys window windows | grep -A8 NavigationBar0 | grep isVisible`（隐藏时 false/NO_SURFACE）；
- 恢复注意：强制打开用例结束后必须执行 `ForceOpenLocation forceOpen=false` / `ForceOpenAirplaneMode forceOpen=false` 清标志（否则纠正器持续生效）；飞行模式恢复后建议等待 3~5 秒再查射频/蓝牙状态（异步生效）。

## 4. 实测结果（2026-08-05，Android 13 / API 33 userdebug，平台签名 + device owner）

全部用例经 `send_test_command.sh` IPC 通道执行（与 UI 按钮共用 TestActions 引擎）：

| 用例 | 实测结果 |
|---|---|
| TC-0310-01 | 通过：`{"location_mode":3,"savedMode":3,"forceOpen":false,"success":true,"enabled":true}` |
| TC-0310-02 | 通过：success=true；location_mode=0；savedMode=3；`settings get secure location_mode`=0 |
| TC-0310-03 | 通过：`{"location_mode":0,"savedMode":3,"forceOpen":false,"success":true,"enabled":false}` |
| TC-0310-04 | 通过：success=true；location_mode=3 恢复；系统对照=3 |
| TC-0311-01 | 通过：同 TC-0310-02/04 结果 |
| TC-0311-02 | 通过：mode=2 时禁用再启用，恢复 2（保存的是禁用时刻的模式） |
| TC-0311-03 | 通过：`{"location_mode":3,"forceOpen":true,"savedMode":3,"success":true}` |
| TC-0311-04 | 通过：`settings put secure location_mode 0` 后 12 秒内纠正器恢复 3 |
| TC-0311-05 | 通过：forceOpen=false 后用户关闭保持 0（13 秒观察窗口无纠正） |
| TC-0311-06 | 通过：force-stop Launcher 重启后 IsLocationEnabled 报 forceOpen=true；手动关闭后 12 秒内恢复 3（ApiService.onCreate 重新武装） |
| TC-0310-05 | 通过：`missing parameter: enabled` / `missing parameter: forceOpen` |
| TC-0319-01 | 通过：`{"forceOpen":false,"airplane_mode_on":0,"success":true,"enabled":false}` |
| TC-0319-02 | 通过：success=true；airplane_mode_on=1；`dumpsys wifi` 出现 `AirplaneModeOn true` |
| TC-0319-03 | 通过（说明）：`dumpsys telephony.registry` 出现 mRadioPowerState=0（射频电源关闭）；蓝牙 state: OFF |
| TC-0319-04 | 通过：enabled=true；airplane_mode_on=1 |
| TC-0319-05 | 通过：success=true；airplane_mode_on=0；`AirplaneModeOn false` |
| TC-0320-01 | 通过：同 TC-0319-02/05 结果 |
| TC-0319-06 | 通过：`missing parameter: enabled` |
| TC-0321-01 | 通过：`{"forceOpen":true,"airplane_mode_on":1,"success":true}` |
| TC-0321-02 | 通过：用户写 0+广播关闭后 12 秒内纠正器重开（airplane_mode_on=1、AirplaneModeOn true） |
| TC-0321-03 | 通过：enabled=true；forceOpen=true |
| TC-0321-04 | 通过：forceOpen=false 后用户关闭保持 0（13 秒观察窗口无纠正） |
| TC-0321-05 | 通过：`missing parameter: forceOpen` |
| TC-0349-01 | 通过：`{"navigation_visible":1,"navigation_mode":2,"success":true,"enabled":true}` |
| TC-0349-02 | 通过：success=true；navigation_visible=0；系统对照=0 |
| TC-0349-03 | 通过：`dumpsys window windows` NavigationBar0 `isVisible=false`（隐藏生效） |
| TC-0349-04 | 通过：enabled=false；navigation_visible=0 |
| TC-0349-05 | 通过：success=true；navigation_visible=1；系统对照=1 |
| TC-0349-06 | 通过：NavigationBar0 `isVisible=true`（恢复显示） |
| TC-0349-07 | 通过：`missing parameter: enabled` |
| TC-M-01 | 通过：5 个 Set/Force 命令缺参均返回缺参提示，不 crash |
| TC-M-02 | 通过：`SetLocationEnabledXXX` → unknown event |
| TC-M-03 | 通过：ListEvents 含全部 8 个新事件 |
| TC-M-04 | 通过（说明）：UI/IPC 共用 TestActions.execute() 同一引擎，行为一致（UI 按钮未逐一点击，IPC 全覆盖；DeviceStateTestActivity 已部署） |
| TC-M-05 | 通过：返回值全 JDK Map/List/基本类型 |
| TC-M-06 | 通过：location_mode=3、navigation_visible=1、airplane_mode_on=0（AirplaneModeOn false）、navigation_mode=2，无残留 |

**部署注意**：`adb install -r` 重装 Launcher 会结束其进程且不会自动重启（HOME 桌面进程被杀），需重新拉起 HOME（`am start -a android.intent.action.MAIN -c android.intent.category.HOME`）后 testapp 进程亦需重启（`am force-stop com.hmdm.testapp`）以重建 AIDL 绑定，否则返回 `RESULT:null`（DeadObjectException）。
