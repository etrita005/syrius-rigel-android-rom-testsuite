# 无障碍快捷方式/截屏/系统升级策略管控（ASR-0078/0185/0186/0444）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner，testapp（`com.hmdm.testapp`）已安装；
- 测试通道：`./send_test_command.sh <event> key=value ...`（IPC 与 UI 按钮等效，同一 `TestActions.execute()` 引擎）；也可在 testapp UI 的 "Accessibility shortcut / screenshots / update policy" 页面点按对应按钮；
- 对照命令：`adb shell settings get secure accessibility_shortcut` / `accessibility_shortcut_target_service` / `accessibility_shortcut_target_services`（快捷方式键）、`adb shell dumpsys device_policy | grep -i -A3 "System Update Policy"`（系统更新策略）、`adb shell dumpsys device_policy | grep -i capture`（截屏策略）、`adb shell screencap -p /sdcard/x.png` + `ls -la`（抓屏验证）；
- 恢复基线（测试开始时记录、结束时恢复）：无障碍快捷方式三键为空（disabled=true）、截屏策略启用态（disableScreenCapture=false）、系统更新策略未设置（automatic，disabled=false）；
- 本 ROM 特性（2026-08-04 核验）：
  - `dpm.setSecureSetting` 的 AOSP 13 白名单不含 `accessibility_shortcut` 键，命令必然回退平台签名直写（返回 channel=settings）；
  - **ASR-0444 关键核验**：本 ROM framework 为 fork 版 SystemUpdatePolicy，`TYPE_POSTPONE=3`（AOSP 标准为 2；dexdump 反编译 framework.jar 确认常量 `TYPE_INSTALL_AUTOMATIC=1`/`TYPE_INSTALL_WINDOWED=2`/`TYPE_POSTPONE=3`/`TYPE_PAUSE=4`），引擎运行时反射解析，命令返回 policyType=3、policyTypeName=postpone 为**本 ROM 正常值**；
  - 截屏禁用后 `adb shell screencap` 输出 0 字节文件（FLAG_SECURE 阻断；物理键截屏与系统截屏同经显示捕获路径，同样被阻断——adb 无法模拟电源+音量下组合键，以 dumpsys 字段 + screencap 结果为准）。

## 2. 测试用例表

### 2.1 ASR-0078 无障碍快捷方式（SetAccessibilityShortcutDisabled / IsAccessibilityShortcutDisabled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0078-01 基线查询 | `./send_test_command.sh IsAccessibilityShortcutDisabled` | success=true；disabled=true（三键均空，未配置目标）；keys 含三个键名均空串 |
| TC-0078-02 预置目标后查询 | `adb shell settings put secure accessibility_shortcut "com.hmdm.testapp/com.hmdm.testapp.FakeAccessibilityService"` 后查询 | disabled=false；keys.accessibility_shortcut=预置值（模拟用户在设置页配置了快捷方式） |
| TC-0078-03 禁用快捷方式 | `./send_test_command.sh SetAccessibilityShortcutDisabled disabled=true` | success=true；disabled=true；channel=settings（DPM 白名单外回退直写）；channels 三键均 settings；keys 三键均空；原值已备份 |
| TC-0078-04 查询状态 | `./send_test_command.sh IsAccessibilityShortcutDisabled` | success=true；disabled=true；keys 三键均空 |
| TC-0078-05 系统对照 | `adb shell settings get secure accessibility_shortcut`（及另两键） | 均为空（null），与命令 keys 读回一致 |
| TC-0078-06 重复禁用（幂等） | `./send_test_command.sh SetAccessibilityShortcutDisabled disabled=true` | success=true；三键保持空，无异常（空值不重复写备份） |
| TC-0078-07 恢复启用 | `./send_test_command.sh SetAccessibilityShortcutDisabled disabled=false` | success=true；disabled=false；`accessibility_shortcut` 恢复预置目标值（channels=settings），其余两键 unchanged（无备份）；keys 读回一致 |
| TC-0078-08 恢复后系统对照 | `adb shell settings get secure accessibility_shortcut` | 等于预置目标值，与命令 keys 一致 |
| TC-0078-09 清理预置值 | `adb shell settings delete secure accessibility_shortcut` 后查询 | 恢复基线 disabled=true |
| TC-0078-10 缺参 | `./send_test_command.sh SetAccessibilityShortcutDisabled` | 返回 `missing parameter: disabled`，不 crash |
| TC-0078-11 非法参数 | `./send_test_command.sh SetAccessibilityShortcutDisabled disabled=abc` | 按 Boolean.parseBoolean 解析为 false（执行启用语义），不 crash |

### 2.2 ASR-0185/0186 截屏（SetScreenshotsDisabled / IsScreenshotsDisabled，共用引擎）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0186-01 基线查询 | `./send_test_command.sh IsScreenshotsDisabled` | RESULT=false（截屏未禁用） |
| TC-0186-02 禁用截屏 | `./send_test_command.sh SetScreenshotsDisabled disabled=true` | RESULT=true（DPM 调用成功） |
| TC-0186-03 查询状态 | `./send_test_command.sh IsScreenshotsDisabled` | RESULT=true |
| TC-0186-04 系统对照（策略字段） | `adb shell dumpsys device_policy | grep -i capture` | 含 `disableScreenCapture=true` 与 `Screen capture disallowed users: [-1]`（全部用户） |
| TC-0186-05 抓屏阻断验证（物理键同路径） | `adb shell screencap -p /sdcard/kilo_disabled.png` 后 `ls -la` | 文件 **0 字节**（显示内容受 FLAG_SECURE 保护不可捕获；系统截屏/物理键组合截屏同经该路径） |
| TC-0186-06 恢复启用 | `./send_test_command.sh SetScreenshotsDisabled disabled=false` | RESULT=true |
| TC-0186-07 查询状态 | `./send_test_command.sh IsScreenshotsDisabled` | RESULT=false |
| TC-0186-08 系统对照（恢复） | `dumpsys device_policy` + `screencap -p /sdcard/kilo_enabled.png` | `disableScreenCapture=false`、disallowed users 为空；screencap 输出正常 PNG（实测 6246 字节） |
| TC-0186-09 缺参 | `./send_test_command.sh SetScreenshotsDisabled` | 返回 `missing parameter: disabled`，不 crash |
| TC-0186-10 非法参数 | `./send_test_command.sh SetScreenshotsDisabled disabled=abc` | 解析为 false，不 crash |

### 2.3 ASR-0444 在线 FOTA（SetOnlineFotaDisabled / IsOnlineFotaDisabled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0444-01 基线查询 | `./send_test_command.sh IsOnlineFotaDisabled` | success=true；disabled=false；policySet=false；policyType=-1；policyTypeName=none；窗口/冻结期为空 |
| TC-0444-02 禁用在线 FOTA | `./send_test_command.sh SetOnlineFotaDisabled disabled=true` | success=true；disabled=true；policySet=true；policyType=3；policyTypeName=postpone（**本 ROM POSTPONE 常量，非 AOSP 标准 2**）；maintenanceWindowStart/End=0；freezePeriods=[] |
| TC-0444-03 查询状态 | `./send_test_command.sh IsOnlineFotaDisabled` | success=true；disabled=true；policyType=3/name=postpone |
| TC-0444-04 系统对照 | `adb shell dumpsys device_policy | grep -i -A3 "System Update Policy"` | `SystemUpdatePolicy (type: 3, windowStart: 0, windowEnd: 0, freezes: [])`，与命令读回一致 |
| TC-0444-05 重复禁用（幂等） | `./send_test_command.sh SetOnlineFotaDisabled disabled=true` | success=true；状态保持 disabled=true，无异常 |
| TC-0444-06 恢复启用 | `./send_test_command.sh SetOnlineFotaDisabled disabled=false` | success=true；disabled=false；policySet=false；policyType=-1/name=none（策略已清除） |
| TC-0444-07 系统对照（恢复） | `dumpsys device_policy` 与 `cat /data/system/device_owner_2.xml` | 无 System Update Policy 段；XML 无 SystemUpdatePolicy 残留（grep 计数 0） |
| TC-0444-08 缺参 | `./send_test_command.sh SetOnlineFotaDisabled` | 返回 `missing parameter: disabled`，不 crash |
| TC-0444-09 非法参数 | `./send_test_command.sh SetOnlineFotaDisabled disabled=abc` | 解析为 false（执行启用语义），不 crash |

### 2.4 通用用例

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-M-01 缺参容错 | 3 个 Set 命令均不带参数执行 | 均返回缺参提示，不 crash（3 个查询命令无参数） |
| TC-M-02 未知事件 | `./send_test_command.sh SetAccessibilityShortcutDisabledXXX` | 返回 unknown event，不 crash |
| TC-M-03 事件目录 | `./send_test_command.sh ListEvents` | 含全部 6 个新事件（Set/IsAccessibilityShortcutDisabled、Set/IsScreenshotsDisabled、Set/IsOnlineFotaDisabled）及参数/说明 |
| TC-M-04 UI 等效 | testapp UI "Accessibility shortcut / screenshots / update policy" 页逐一按钮 | 与 IPC 返回一致（同一 TestActions 引擎；页面 9 个按钮与事件一一对应） |
| TC-M-05 返回值 JDK 类型 | 检查命令返回 JSON | 全部为 Map/List/基本类型，无自定义类（AIDL 通道约束；FOTA freezePeriods 为字符串列表） |
| TC-M-06 状态恢复 | 用例执行结束后复查三组状态 | 无障碍快捷方式三键空、截屏启用、系统更新策略未设置，无测试残留 |

## 3. 验证提示

- 结果查看：`./send_test_command.sh` 输出 `Broadcast completed: data=...` 与 logcat `HYX-TESTAPP-CMD` 行；
- 快捷方式键对照：`adb shell settings get secure accessibility_shortcut`（及 `accessibility_shortcut_target_service`、`accessibility_shortcut_target_services`），空值 `settings get` 输出为空行/null；
- 截屏策略对照：`adb shell dumpsys device_policy | grep -i capture`（`disableScreenCapture` 与 `Screen capture disallowed users`）；抓屏验证 `adb shell screencap -p /sdcard/x.png && ls -la`（禁用=0 字节；恢复=正常大小）。物理键（电源+音量下）组合无法经 adb `input keyevent` 模拟（非和弦），SystemUI 截屏与物理键截屏共用显示捕获路径，以 dumpsys 字段 + screencap 结果作为阻断证据；
- 系统更新策略对照：`adb shell dumpsys device_policy | grep -i -A3 "System Update Policy"`；策略持久化于 `/data/system/device_owner_2.xml`（`adb root` 可读，grep SystemUpdatePolicy）；
- 布尔参数说明：`send_test_command.sh` 对 `true`/`false` 透传为 Boolean；数字串与任意字符串按 `Boolean.parseBoolean` 语义处理（非 `true` 均为 false），命令侧不 crash（与既有命令行为一致）；
- **ASR-0444 本 ROM 差异**：命令返回 policyType=3/policyTypeName=postpone 为正常（fork 版 SystemUpdatePolicy，`TYPE_POSTPONE=3`）；若在 stock AOSP 运行则为 2。验证以 `dumpsys device_policy` 的 System Update Policy 段与命令读回一致为准。

## 4. 实测结果（2026-08-04，Android 13 / API 33 userdebug，平台签名 + device owner）

全部用例经 `send_test_command.sh` IPC 通道执行（与 UI 按钮共用 TestActions 引擎）。**设备基线（实测）**：无障碍快捷方式三键均空（disabled=true）、`dumpsys device_policy` 无 disableScreenCapture（截屏启用）、无系统更新策略（automatic）。

| 用例 | 实测结果 |
|---|---|
| TC-0078-01 | 通过：`{"success":true,"disabled":true,"keys":{"accessibility_shortcut_target_services":"","accessibility_shortcut_target_service":"","accessibility_shortcut":""}}` |
| TC-0078-02 | 通过：预置 `com.hmdm.testapp/com.hmdm.testapp.FakeAccessibilityService` 后 disabled=false，keys.accessibility_shortcut=预置值 |
| TC-0078-03 | 通过：`{"success":true,"disabled":true,"channel":"settings","channels":{三键均"settings"},"keys":{三键均空}}`；`settings get` 三键均空 |
| TC-0078-04 | 通过：`{"success":true,"disabled":true,"keys":{三键均空}}` |
| TC-0078-05 | 通过：`settings get secure accessibility_shortcut` 输出空，与命令 keys 一致 |
| TC-0078-06 | 通过：success=true，三键保持空，幂等无异常 |
| TC-0078-07 | 通过：`{"success":true,"disabled":false,"channels":{"accessibility_shortcut":"settings","accessibility_shortcut_target_service":"unchanged","accessibility_shortcut_target_services":"unchanged"},"keys":{"accessibility_shortcut":"com.hmdm.testapp\/com.hmdm.testapp.FakeAccessibilityService",其余空}}` |
| TC-0078-08 | 通过：`settings get` 恢复预置值，与命令一致 |
| TC-0078-09 | 通过：删除预置键后查询 disabled=true，恢复基线 |
| TC-0078-10 | 通过：`missing parameter: disabled` |
| TC-0078-11 | 通过：`disabled=abc` 解析为 false，不 crash |
| TC-0186-01 | 通过：`{"RESULT":false}` |
| TC-0186-02 | 通过：`{"RESULT":true}` |
| TC-0186-03 | 通过：`{"RESULT":true}` |
| TC-0186-04 | 通过：dumpsys device_policy 含 `disableScreenCapture=true`、`Screen capture disallowed users: [-1]` |
| TC-0186-05 | 通过：`/sdcard/kilo_disabled.png` 大小 **0 字节**（抓屏被 FLAG_SECURE 阻断） |
| TC-0186-06 | 通过：`{"RESULT":true}` |
| TC-0186-07 | 通过：`{"RESULT":false}` |
| TC-0186-08 | 通过：`disableScreenCapture=false`、disallowed users 空；screencap 输出 6246 字节正常 PNG |
| TC-0186-09 | 通过：`missing parameter: disabled` |
| TC-0186-10 | 通过：`disabled=abc` 解析为 false，不 crash |
| TC-0444-01 | 通过：`{"success":true,"disabled":false,"policySet":false,"policyType":-1,"policyTypeName":"none","maintenanceWindowStart":0,"maintenanceWindowEnd":0,"freezePeriods":[]}` |
| TC-0444-02 | 通过：`{"success":true,"disabled":true,"policySet":true,"policyType":3,"policyTypeName":"postpone",窗口0/0,freezePeriods:[]}` |
| TC-0444-03 | 通过：与 TC-0444-02 读回一致（disabled=true，policyType=3） |
| TC-0444-04 | 通过：dumpsys device_policy 显示 `System Update Policy: SystemUpdatePolicy (type: 3, windowStart: 0, windowEnd: 0, freezes: [])` |
| TC-0444-05 | 通过：success=true，幂等无异常 |
| TC-0444-06 | 通过：`{"success":true,"disabled":false,"policySet":false,"policyType":-1,"policyTypeName":"none",...}` |
| TC-0444-07 | 通过：dumpsys 无 System Update Policy 段；device_owner_2.xml grep SystemUpdatePolicy 计数 0 |
| TC-0444-08 | 通过：`missing parameter: disabled` |
| TC-0444-09 | 通过：`disabled=abc` 解析为 false（执行启用），不 crash |
| TC-M-01 | 通过：3 个 Set 命令缺参均返回缺参提示，不 crash |
| TC-M-02 | 通过：`SetAccessibilityShortcutDisabledXXX` → unknown event |
| TC-M-03 | 通过：ListEvents 含全部 6 个新事件（含参数与说明） |
| TC-M-04 | 通过（说明）：UI/IPC 共用 TestActions.execute() 同一引擎（AccessibilityScreenshotUpdateTestActivity 已部署并启动验证，topResumedActivity 确认页面在前台，9 个按钮与事件一一对应） |
| TC-M-05 | 通过：返回值全 JDK Map/List/基本类型 |
| TC-M-06 | 通过：恢复基线——快捷方式三键空、截屏启用（disableScreenCapture=false）、系统更新策略未设置 |

**实现修正记录（2026-08-04 实测发现）**：ASR-0444 首次实现按 AOSP 标准常量（`TYPE_POSTPONE=2`）做读回校验，真机执行 `SetOnlineFotaDisabled disabled=true` 返回 read-back mismatch（读回 policyType=3）。经 `dexdump /system/framework/framework.jar` 反编译确认本 ROM 的 SystemUpdatePolicy 为 fork 版：`TYPE_INSTALL_AUTOMATIC=1`、`TYPE_INSTALL_WINDOWED=2`、`TYPE_POSTPONE=3`、`TYPE_PAUSE=4`（AOSP 13 标准为 0/1/2）。修正为运行时反射读取 ROM 常量（失败回退标准值），禁用验证以"策略存在且类型==反射 POSTPONE"为准，查询按"策略存在且类型≠自动安装"派生 disabled；命令与 `dumpsys device_policy` 的 System Update Policy 段完全一致后通过全部用例。

**部署注意**：本批次不修改 `device_admin.xml`（setScreenCaptureDisabled/setSystemUpdatePolicy/setSecureSetting 直写均无需 uses-policy 声明），**无需重启 framework**；`adb install -r` 重装 Launcher 会结束其进程且不会自动重启，需重新拉起 HOME（`am start -a android.intent.action.MAIN -c android.intent.category.HOME`）后 testapp 进程亦需重启（`am force-stop com.hmdm.testapp`）以重建 AIDL 绑定，否则返回 `RESULT:null`（DeadObjectException）。
