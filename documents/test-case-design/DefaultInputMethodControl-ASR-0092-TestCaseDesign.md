# 默认输入法管控（ASR-0092）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner，testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root`、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`；
- 本机输入法：com.android.inputmethod.latin/.LatinIME（系统默认）、org.fcitx.fcitx5.android/.input.FcitxInputMethodService（已装）；
- 对照命令：`adb shell settings get secure default_input_method`、`adb shell dumpsys input_method | grep mCurMethodId`、`adb shell ime list -a -s`；
- 恢复基线：测试结束默认输入法必须恢复 latin（避免影响后续批次文本输入）。

## 2. 测试用例表

### 2.1 ASR-0092 设置默认输入法（SetDefaultInputMethod / GetDefaultInputMethod / GetAvailableInputMethods）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0092-01 基线 | `./send_test_command.sh GetAvailableInputMethods`；`GetDefaultInputMethod`；`adb shell settings get secure default_input_method` | 列表含 latin + fcitx；默认=latin（三方一致） |
| TC-0092-02 切换默认输入法 | `./send_test_command.sh SetDefaultInputMethod imeId=org.fcitx.fcitx5.android/.input.FcitxInputMethodService`；`GetDefaultInputMethod`；`settings get secure default_input_method`；`./send_test_command.sh InputMethodsLocal` | RESULT=true；GetDefault 与 settings get 均返回 fcitx ID（写后核对一致） |
| TC-0092-03 切回恢复 | `SetDefaultInputMethod imeId=com.android.inputmethod.latin/.LatinIME`；`GetDefaultInputMethod` | true；默认恢复 latin（基线） |
| TC-0092-04 无效 imeId（边界） | `SetDefaultInputMethod imeId=com.nonexistent.ime/.FakeIme`；`GetDefaultInputMethod`；`settings get secure default_input_method`；`dumpsys input_method \| grep mCurMethodId`；随后 `SetDefaultInputMethod imeId=com.android.inputmethod.latin/.LatinIME` 恢复 | RESULT=true（setSecureSetting 不校验）；GetDefault 如实返回无效值；secure 键持有该值；mCurMethodId=null（框架运行态回落系统默认，2026-08-13 实测）；恢复后 latin 复位 |
| TC-0092-05 缺参 | `./send_test_command.sh SetDefaultInputMethod` | 返回 `missing parameter: imeId`，不 crash |
| TC-0092-06 恢复基线复核 | `GetDefaultInputMethod`；`adb shell dpm list-owners` | 默认=latin；DO 在位 |

## 3. 硬件受限测试说明

- ASR-0092 无硬件依赖；上述用例全部在本机（Android 13 / API 33 userdebug，平台签名 + device owner）执行通过（2026-08-13）。
- 切换输入法对 testapp IPC/UI 无影响（IPC 引擎与输入法无关），但测试结束必须恢复原默认输入法；无效 imeId 的框架回落行为（mCurMethodId=null）已如实记录。
