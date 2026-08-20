# NFC 管控（ASR-0315/0316/0317）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner，testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root && dumpsys duraspeed addwhitelist com.hmdm.testapp`、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`（IPC 与 UI 按钮等效，同一 `TestActions.execute()` 引擎）；也可在 testapp UI 的 "Device state" 页面 NFC 小节点按对应按钮；
- 对照命令：`adb shell dumpsys nfc`（NfcService 状态，本机 Can't find service）、`adb shell settings get global nfc_on`（NfcService 镜像键，1=开/0=关，本机 null）、`adb shell ps -A | grep nfc`（nfc 进程）、`adb shell pm list features | grep nfc`（硬件特性）；
- **NFC 硬件要求**：当前测试机（MT8788）**无 NFC 硬件/框架支持**（无 android.hardware.nfc 特性、无 NFC HAL、NfcService 未启动），本批次在本机执行**受限验证**：验证命令正确性与容错（supported=false 如实上报、不 crash、不误持久化）；`NfcAdapter.enable/disable` 的真实开关行为（状态机 ON/OFF 切换、nfc_on 镜像、10 秒纠正器重开）须在**具备 NFC 硬件的设备**上验收（对应用例 TC-NFC-02~06/08~10 标记"需 NFC 真机"）；
- 恢复基线：本机无 nfc 状态可恢复（nfc_on=null）；NFC 真机验收时恢复 NFC 为开启态并确认 `ForceOpenNfc forceOpen=false` 已清标志。

## 2. 测试用例表

### 2.1 ASR-0315/0316 NFC 开关（SetNfcEnabled / IsNfcEnabled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-NFC-01 基线查询 | `./send_test_command.sh IsNfcEnabled` | **本机**：success=false；supported=false；error="NFC not supported on this device"；不 crash |
| TC-NFC-02 禁用 NFC（需 NFC 真机） | `./send_test_command.sh SetNfcEnabled enabled=false` | success=true；state=1/OFF；nfc_on=0；`settings get global nfc_on`=0 |
| TC-NFC-03 查询禁用态（需 NFC 真机） | `./send_test_command.sh IsNfcEnabled` | success=true；enabled=false；state=OFF |
| TC-NFC-04 启用 NFC（需 NFC 真机） | `./send_test_command.sh SetNfcEnabled enabled=true` | success=true；state=3/ON；nfc_on=1；系统对照=1 |
| TC-NFC-05 查询启用态（需 NFC 真机） | `./send_test_command.sh IsNfcEnabled` | success=true；enabled=true；state=ON；forceOpen=false |
| TC-NFC-06 关闭/打开复用（需 NFC 真机） | 重复 TC-NFC-02/04（ASR-0316 与 ASR-0315 同一引擎） | 同 TC-NFC-02/04 |
| TC-NFC-07 无硬件设备容错（本机执行） | `./send_test_command.sh SetNfcEnabled enabled=false`、`enabled=true` | 均 success=false；supported=false；error 如实上报；不 crash、不改变任何系统状态 |

### 2.2 ASR-0317 强制打开 NFC（ForceOpenNfc）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-NFC-08 强制打开（需 NFC 真机） | `./send_test_command.sh ForceOpenNfc forceOpen=true` | success=true；forceOpen=true；state=ON |
| TC-NFC-09 用户关闭后被纠正（需 NFC 真机） | 强制打开中经设置页/其他方式关闭 NFC，等待 ≤12 秒 | 纠正器重新打开：IsNfcEnabled 报 state=ON（tick 间隔 10s，等待 12~13 秒） |
| TC-NFC-10 停止强制（需 NFC 真机） | `./send_test_command.sh ForceOpenNfc forceOpen=false` 后再次关闭 NFC，等待 13 秒 | 不再纠正：state=OFF 保持（IsNfcEnabled 报 forceOpen=false） |
| TC-NFC-11 强制持久化（需 NFC 真机） | `ForceOpenNfc forceOpen=true` → 关闭 NFC → `am force-stop com.hmdm.launcher` → 重新拉起 HOME 与 testapp → `IsNfcEnabled`，等待 ≤12 秒 | forceOpen=true 保留；state 恢复 ON（ApiService.onCreate 重新武装） |
| TC-NFC-12 无硬件设备容错（本机执行） | `./send_test_command.sh ForceOpenNfc forceOpen=true`、`forceOpen=false` | 均 success=false；supported=false；error 如实上报；**不持久化标志**（`/data/data/com.hmdm.launcher/shared_prefs/nfc_policy.xml` 不存在）、纠正器未启动 |
| TC-NFC-13 缺参 | `./send_test_command.sh SetNfcEnabled`、`ForceOpenNfc` | 返回 `missing parameter: enabled` / `missing parameter: forceOpen`，不 crash |

### 2.3 通用用例

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-M-01 未知事件 | `./send_test_command.sh SetNfcEnabledXXX` | 返回 unknown event，不 crash |
| TC-M-02 事件目录 | `./send_test_command.sh ListEvents` | 含全部 3 个新事件（SetNfcEnabled/IsNfcEnabled/ForceOpenNfc） |
| TC-M-03 UI 等效 | testapp UI "Device state" 页面 NFC 小节按钮 | 与 IPC 返回一致（同一 TestActions 引擎） |
| TC-M-04 返回值 JDK 类型 | 检查命令返回 JSON | 全部为 Map/List/基本类型，无自定义类（AIDL 通道约束） |
| TC-M-05 状态恢复 | 用例执行结束后复查 | 本机：nfc_on 保持 null、无 nfc 进程/服务产生、`nfc_policy.xml` 不存在；NFC 真机：NFC 恢复开启、forceOpen=false、无残留 |

## 3. 验证提示

- 结果查看：`./send_test_command.sh` 输出 `Broadcast completed: data=...` 与 logcat `HYX-TESTAPP-CMD` 行；Launcher 侧执行日志见 logcat tag `HYX-MDM-APP`（NfcPolicyManager 每步上报）；
- NFC 真机对照：`adb shell settings get global nfc_on`（1/0）、`adb shell dumpsys nfc | grep -iE "mState|state"`（NfcService 状态机）、`adb shell dumpsys nfcservice`（部分 ROM 有）；强制纠正器行为观察窗口 ≥10 秒（tick 间隔 10s，用例统一等待 12~13 秒）；
- 无硬件设备判定：`adb shell pm list features | grep nfc` 无输出 + `adb shell dumpsys nfc` 报 Can't find service 即无 NFC 框架支持，此时所有命令应返回 supported=false（预期行为，非缺陷）；
- 恢复注意：NFC 真机上强制打开用例结束后必须执行 `ForceOpenNfc forceOpen=false` 清标志（否则纠正器持续生效）。

## 4. 实测结果（2026-08-05，Android 13 / API 33 userdebug，平台签名 + device owner）

**本机无 NFC 硬件**，全部用例经 `send_test_command.sh` IPC 通道执行（与 UI 按钮共用 TestActions 引擎），**受限验证**：开关/纠正器的功能路径用例（TC-NFC-02~06/08~11）待 NFC 真机验收，其余在本机实测：

| 用例 | 实测结果 |
|---|---|
| TC-NFC-01 | 通过：`{"error":"NFC not supported on this device","success":false,"enabled":false,"supported":false}` |
| TC-NFC-02 | 待 NFC 真机（本机不适用） |
| TC-NFC-03 | 待 NFC 真机（本机不适用） |
| TC-NFC-04 | 待 NFC 真机（本机不适用） |
| TC-NFC-05 | 待 NFC 真机（本机不适用） |
| TC-NFC-06 | 待 NFC 真机（本机不适用） |
| TC-NFC-07 | 通过：`SetNfcEnabled enabled=false/true` 均返回 `{success:false, supported:false, error:"NFC not supported on this device"}`；不 crash；nfc_on 保持 null |
| TC-NFC-08 | 待 NFC 真机（本机不适用） |
| TC-NFC-09 | 待 NFC 真机（本机不适用） |
| TC-NFC-10 | 待 NFC 真机（本机不适用） |
| TC-NFC-11 | 待 NFC 真机（本机不适用） |
| TC-NFC-12 | 通过：`ForceOpenNfc forceOpen=true/false` 均返回 `{success:false, supported:false, error:"NFC not supported on this device"}`；`/data/data/com.hmdm.launcher/shared_prefs/nfc_policy.xml` 不存在（标志未持久化、纠正器未启动） |
| TC-NFC-13 | 通过：`missing parameter: enabled` / `missing parameter: forceOpen` |
| TC-M-01 | 通过：`SetNfcEnabledXXX` → unknown event |
| TC-M-02 | 通过：ListEvents 含 SetNfcEnabled/IsNfcEnabled/ForceOpenNfc 三个事件 |
| TC-M-03 | 通过（说明）：UI/IPC 共用 TestActions.execute() 同一引擎，行为一致（NFC 按钮已随 DeviceStateTestActivity 部署，未逐一点击，IPC 全覆盖） |
| TC-M-04 | 通过：返回值全 JDK Map/List/基本类型 |
| TC-M-05 | 通过：nfc_on 保持 null、无 nfc 进程/服务产生、无 `nfc_policy.xml`、系统状态与测试前一致 |

**部署注意**：`adb install -r` 重装 Launcher 会结束其进程且不会自动重启（HOME 桌面进程被杀），需重新拉起 HOME（`am start -a android.intent.action.MAIN -c android.intent.category.HOME`）后 testapp 进程亦需重启（`am force-stop com.hmdm.testapp`）以重建 AIDL 绑定，否则返回 `RESULT:null`（DeadObjectException）。
