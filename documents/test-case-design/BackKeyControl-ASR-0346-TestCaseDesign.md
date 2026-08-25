# BACK 键管控（ASR-0346）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner，testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root`、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`（IPC 与 UI 按钮等效，同一 `TestActions.execute()` 引擎）；
- 对照命令：`adb shell dumpsys statusbar | grep mDisabled1`（0x400000 位 = BACK 禁用）、`adb shell settings get secure navigation_mode`、`adb shell cmd overlay list | grep navbar`、`adb shell screencap -p /sdcard/nav.png`（导航栏按键可见性截图核对）；
- 恢复基线（测试开始时记录、结束时恢复）：BACK 键启用（disabled=false）、导航模式 gestural（navigation_mode=2，gestural overlay 启用）；
- 本 ROM 特性（2026-08-12 核验）：`StatusBarManager.DISABLE_BACK`（0x400000）仅对三键导航的 BACK 按键生效（按键隐藏、不可点击，MtkSystemUI NavigationBarView dex 核验 + 截图验证）；全面屏手势模式的边缘返回手势不受该标志控制（本 ROM SysUiState back-disabled 位不来源于 DISABLE_BACK），属 ROM 限制，需求维持部分完成。
- 查询路径说明：`IsBackKeyDisabled` 仅读 SharedPreferences（意图 + 最近写路径核验的 liveDisabled 缓存），不执行 dumpsys（与 ASR-0059 查询先例一致）；`dumpsys statusbar` 对照仅在写路径核验。

## 2. 测试用例表

### 2.1 ASR-0346 禁用/启用 BACK 键（SetBackKeyDisabled / IsBackKeyDisabled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0346-01 基线查询 | `./send_test_command.sh IsBackKeyDisabled` | success=true；disabled=false、liveDisabled=false；navigation_mode=2（gestural）；`dumpsys statusbar` mDisabled1 无 0x400000 位 |
| TC-0346-02 禁用 BACK 键 | `./send_test_command.sh SetBackKeyDisabled disabled=true` | success=true；disabled=true、liveDisabled=true；`dumpsys statusbar` mDisabled1 含 0x400000 位（如 0x1400000）；Launcher 日志 `SystemSettingsManager setBackKeyDisabled true -> {success=true,...}` |
| TC-0346-03 查询状态 | `./send_test_command.sh IsBackKeyDisabled` | success=true；disabled=true、liveDisabled=true（liveDisabled 为写路径核验缓存，不执行 dumpsys） |
| TC-0346-04 三键模式按键隐藏（机制核验） | ① `SetGestureNavigationDisabled disabled=true`（切三键，navigation_mode=0）② `screencap` 保存 nav_back_disabled.png ③ `SetBackKeyDisabled disabled=false` ④ 再次 `screencap` 保存 nav_back_enabled.png | 截图对比：启用时导航栏含 back/home/recents 三个图标，禁用后 back 图标消失、home/recents 不变（像素级：back 图标列 x≈180-191 消失） |
| TC-0346-05 恢复启用 | `./send_test_command.sh SetBackKeyDisabled disabled=false` | success=true；liveDisabled=false；`dumpsys statusbar` mDisabled1 无 0x400000 位；三键模式 back 图标恢复 |
| TC-0346-06 进程重启重新武装 | ① `SetBackKeyDisabled disabled=true` ② `adb shell am force-stop com.hmdm.launcher` ③ `adb shell am startservice -n com.hmdm.launcher/.syrius.service.ApiService` ④ `IsBackKeyDisabled` ⑤ `dumpsys statusbar` | ④ success=true；disabled=true、liveDisabled=true；⑤ mDisabled1 含 0x400000 位（syncBackKeyPolicy 重放成功） |
| TC-0346-07 重复设置同值（幂等） | `SetBackKeyDisabled disabled=true` 连续两次 | 两次均 success=true；无异常 |
| TC-0346-08 缺参 | `./send_test_command.sh SetBackKeyDisabled` | 返回 `missing parameter: disabled`，不 crash，系统状态不变 |
| TC-0346-09 手势模式限制（文档化） | ① 恢复 gestural（`SetGestureNavigationDisabled disabled=false`，navigation_mode=2）② `SetBackKeyDisabled disabled=true` ③ 打开设置页（`am start -a android.settings.SETTINGS`）④ 从屏幕左边缘执行返回滑动（`input swipe 3 1000 600 1000 250`） | ③ 设置页打开；④ 设置页可返回（退出到桌面）——返回手势不受 DISABLE_BACK 控制，属本 ROM 限制（liveDisabled=true 但手势仍可用），如实记录 |

### 2.2 恢复基线（测试结束）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0346-10 恢复基线 | `SetBackKeyDisabled disabled=false`；`SetGestureNavigationDisabled disabled=false` | disabled=false、liveDisabled=false；`dumpsys statusbar` mDisabled1=0x0；navigation_mode=2（gestural overlay 启用），与测试前一致 |

## 3. 硬件受限测试说明

- ASR-0346 无硬件依赖；上述用例均在本机（Android 13 / API 33 userdebug，平台签名 + device owner）执行通过。
- 全面屏手势模式的返回手势禁用依赖 ROM 适配（`updateDisabledSystemUiStateFlags` 增加 BACK→SysUiState 映射或恢复 framework `setSystemUiState` 通道），ROM 适配前维持部分完成。
