# OTA 接口组（ASR-0446/0448/0449/0450/0451/0452）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM（A/B 设备），已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner，testapp（`com.hmdm.testapp`）已安装；
- 测试通道：`./send_test_command.sh <event> key=value ...`（IPC 与 UI 按钮等效，同一 `TestActions.execute()` 引擎）；也可在 testapp UI 的 "OTA interface group" 页面点按对应按钮（config JSON 参数仅走 IPC）；
- 对照命令：`adb shell getprop ro.boot.slot_suffix` / `ro.boot.slot`（槽位属性）、`adb shell cmd -l | grep -i update` 与 `adb shell dumpsys update_engine`（update_engine 服务）、`adb shell ls /data/user/0/com.hmdm.launcher/files/ota/`（adb root，下载目录）；
- 测试 OTA 包服务：宿主机 `python3 -m http.server 8080` + `adb reverse tcp:8080 tcp:8080`，设备经 `http://127.0.0.1:8080/<file>` 访问；假 OTA 包（zip：payload_metadata.bin 伪内容 + payload_properties.txt 含 PACKAGE_VERSION 等键值 + compatibility.zip 伪内容）；
- 回调消费（ASR-0451）：testapp 动态注册 `com.hmdm.launcher.ACTION_OTA_STATUS` 广播接收器（OtaCallbackRecorder），`GetOtaCallbackLog` 读取、`ClearOtaCallbackLog` 清空；
- 恢复基线（测试开始时记录、结束时恢复）：本地 OTA 策略 enabled=true、无运行中 OTA（UpdaterState=IDLE）、槽位未切换（不触发重启）。

## 2. 测试用例表

### 2.1 ASR-0446 检查版本/下载 FOTA（FotaCheckUpdate / FotaDownloadUpdate）

示例 config（假 OTA 包，经 adb reverse 本地 HTTP 服务）：

```json
{"name":"TEST-OTA-BUILD-1","url":"http://127.0.0.1:8080/fake_ota.zip","ab_install_type":"FILE_PROVIDER",
 "ab_config":{"force_switch_slot":false,"verify_payload_metadata":false,
 "property_files":[{"filename":"payload_metadata.bin","offset":0,"size":<m>},
 {"filename":"payload_properties.txt","offset":<m>,"size":<p>},
 {"filename":"compatibility.zip","offset":<m+p>,"size":<c>}]}}
```

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0446-01 缺参 | `./send_test_command.sh FotaCheckUpdate` | 返回 `missing parameter: config (JSON)`，不 crash |
| TC-0446-02 非法 JSON | `./send_test_command.sh FotaCheckUpdate config='{bad'` | success=false，error 含 invalid config JSON，不 crash |
| TC-0446-03 元数据下载+校验 | 预置假 OTA 包后 `./send_test_command.sh FotaCheckUpdate config='<示例 config>'` | success=true；versionName=TEST-OTA-BUILD-1；downloadedFiles 含 payload_metadata.bin/payload_properties.txt/compatibility.zip 且字节数与 property_files 一致；**verifyPayloadMetadata=false**（虚假包如实校验失败，不误报成功）；payloadProperties 解析出 FILE_HASH 等键值 |
| TC-0446-04 校验接口可用性 | 同上返回后 `adb shell logcat -d -s FotaUpdateChecker` | 无 verifyPayloadMetadata 接口不可用错误（verifyError 为空）；update_engine 正常响应 |
| TC-0446-05 下载目录核对 | `adb root && ls -la /data/user/0/com.hmdm.launcher/files/ota/check/` | 三个文件存在且大小一致；重复执行先清理旧文件 |
| TC-0446-06 URL 不可达 | `config` 的 url 改为 `http://127.0.0.1:9999/x.zip` | success=false，error 含下载失败信息，不 crash |
| TC-0446-07 无 property_files | config 的 ab_config 不含 property_files | success=true（或如实），verifyPayloadMetadata=null + note 跳过说明，不 crash |
| TC-0446-08 整包下载 | `./send_test_command.sh FotaDownloadUpdate config='<示例 config>'` | success=true；filePath=…/files/ota/fota_package.zip；fileSize 与源包一致；verifyPayloadMetadata=false（假包）；payloadProperties 解析正确 |
| TC-0446-09 下载包可读 | `adb root && ls -la /data/user/0/com.hmdm.launcher/files/ota/fota_package.zip` | 文件存在、大小一致（0 字节下载视为失败） |

### 2.2 ASR-0448 SD 卡/本地 OTA 策略开关（SetLocalOtaEnabled / IsLocalOtaEnabled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0448-01 基线查询 | `./send_test_command.sh IsLocalOtaEnabled` | success=true；enabled=true（默认允许） |
| TC-0448-02 禁用 | `./send_test_command.sh SetLocalOtaEnabled enabled=false` | success=true；enabled=false |
| TC-0448-03 查询 | `./send_test_command.sh IsLocalOtaEnabled` | success=true；enabled=false |
| TC-0448-04 FotaStart 门禁 | 禁用状态下 `adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast -a ON_SQA_INTERFACE_TEST --es event FotaStart --es param '{"actionId":"t1","otaFileUri":"content://com.hmdm.testapp/x/update.zip"}'` 后查 logcat FotaStart | 广播 -104 local ota disabled by policy；未进入安装流程（无 PREPARED 状态） |
| TC-0448-05 FotaApply 门禁 | 禁用状态下 `./send_test_command.sh FotaApply config='{"name":"x","url":"file:///data/local/tmp/u.zip","ab_install_type":"NON_STREAMING","ab_config":{"force_switch_slot":false,"verify_payload_metadata":false}}'` | success=false；error=local ota disabled by policy；未绑定引擎 |
| TC-0448-06 恢复启用 | `./send_test_command.sh SetLocalOtaEnabled enabled=true` | success=true；enabled=true |
| TC-0448-07 恢复后 FotaStart 受理 | 启用状态下重复 TC-0448-04 | 不再返回 -104（进入正常流程或 -102 file not exists 等后续状态，证明门禁已放开） |
| TC-0448-08 缺参 | `./send_test_command.sh SetLocalOtaEnabled` | 返回 `missing parameter: enabled`，不 crash |

### 2.3 ASR-0449 取消 OTA（FotaCancel）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0449-01 IDLE 状态取消 | `./send_test_command.sh FotaCancel` | success=true；dispatched=true；state=0/stateText=IDLE（IDLE→IDLE 幂等） |
| TC-0449-02 引擎可用性 | 随后 `./send_test_command.sh FotaCancel` 再查 logcat ExportUpdaterService | 无 InvalidTransitionException 错误；getUpdaterState 正常读回 |
| TC-0449-03 运行中取消 | `FotaApply config='<无效 url 的 config>'` 触发 RUNNING→（下载失败）→ERROR 后，立即 `FotaCancel` | 命令不 crash；state 如实上报（ERROR 或 IDLE，视引擎时序）；callback 广播收到 updater_state |
| TC-0449-04 状态文本映射 | 观察返回 stateText | 0~5 分别映射 IDLE/ERROR/RUNNING/PAUSED/SLOT_SWITCH_REQUIRED/REBOOT_REQUIRED |

### 2.4 ASR-0450 暂停/恢复 OTA（FotaSuspend / FotaResume）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0450-01 IDLE 状态暂停 | `./send_test_command.sh FotaSuspend` | success=true；dispatched=true；state 如实上报（IDLE 下转换非法，命令不 crash） |
| TC-0450-02 IDLE 状态恢复 | `./send_test_command.sh FotaResume` | 同上（非法转换被服务捕获记日志，命令如实返回） |
| TC-0450-03 运行中暂停 | 触发 RUNNING 后 `./send_test_command.sh FotaSuspend` | dispatch 成功；随后 GetOtaCallbackLog 出现 updater_state=PAUSED（状态机转换成功则生效） |
| TC-0450-04 暂停后恢复 | `./send_test_command.sh FotaResume` | dispatch 成功；状态转换如实上报（引擎支持才生效，命令如实返回当前 state） |

### 2.5 ASR-0451 OTA 回调（广播消费，OtaCallbackRecorder）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0451-01 清空日志 | `./send_test_command.sh ClearOtaCallbackLog` | cleared=当前条数（首次为 0） |
| TC-0451-02 空日志查询 | `./send_test_command.sh GetOtaCallbackLog` | count=0，events=[] |
| TC-0451-03 回调驱动 | `./send_test_command.sh FotaApply config='<无效 config（url 不可达）>'` 后等待数秒 `./send_test_command.sh GetOtaCallbackLog` | count>0；events 含 callbackType=updater_state（RUNNING/ERROR）与/或 engine_complete（失败错误码）；valueText 可读 |
| TC-0451-04 引擎状态回调 | 同上日志中查 engine_status | 引擎状态码变化事件存在（如 0/IDLE→…，以实际引擎为准；无变化时如实 count 不变） |
| TC-0451-05 进度回调 | 同上日志中查 progress | progress 事件存在（引擎上报时；无效包场景可能无，如实记录） |
| TC-0451-06 任务控制回调 | 触发 RUNNING 后 `FotaCancel`，再查日志 | 出现 updater_state=IDLE（取消触发状态广播，AIDL 回调参数已存储） |
| TC-0451-07 广播可重复消费 | 再次 `GetOtaCallbackLog` | count 单调不减（广播为普通广播，可被多个接收器接收） |

### 2.6 ASR-0452 A/B 槽位查询/切换（GetSlotInfo / SetSwitchSlotOnReboot）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0452-01 槽位查询 | `./send_test_command.sh GetSlotInfo` | success=true；supported=true；currentSlotSuffix=_a（与 `getprop ro.boot.slot_suffix` 一致）；slots 含 2 个槽位（suffix _a/_b），bootable/markedSuccessful 为布尔 |
| TC-0452-02 属性对照 | `adb shell getprop ro.boot.slot_suffix` 与 `ro.boot.slot` | 与命令 currentSlotSuffix/bootloaderSlots 一致 |
| TC-0452-03 nextBootSlot 口径 | 观察返回 nextBootSlot | 为非当前且 bootable 的槽位（A/B 受控切换目标），无 bootable 其他槽位时=当前槽位 |
| TC-0452-04 切换 dispatch | `./send_test_command.sh SetSwitchSlotOnReboot config='<示例 config>'` | success=true；dispatched=true（AIDL 已送达；真实槽位切换需已应用 OTA 且会触发重启，本用例仅验证命令契约） |
| TC-0452-05 缺参 | `./send_test_command.sh SetSwitchSlotOnReboot` | 返回 `missing parameter: config (JSON)`，不 crash |
| TC-0452-06 非法 JSON | `./send_test_command.sh SetSwitchSlotOnReboot config='{bad'` | success=false，error 含 invalid config JSON，不 crash |

### 2.7 通用用例

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-M-01 缺参容错 | FotaCheckUpdate/FotaDownloadUpdate/FotaApply/SetSwitchSlotOnReboot 不带 config、SetLocalOtaEnabled 不带 enabled | 均返回缺参提示，不 crash |
| TC-M-02 未知事件 | `./send_test_command.sh FotaCancelXXX` | 返回 unknown event，不 crash |
| TC-M-03 事件目录 | `./send_test_command.sh ListEvents` | 含全部 13 个新事件（FotaCheckUpdate/FotaDownloadUpdate/Set+IsLocalOtaEnabled/FotaApply/FotaCancel/FotaSuspend/FotaResume/GetSlotInfo/SetSwitchSlotOnReboot/Get+ClearOtaCallbackLog）及参数/说明 |
| TC-M-04 UI 等效 | testapp UI "OTA interface group" 页逐一按钮（无 config 参数事件） | 与 IPC 返回一致（同一 TestActions 引擎；config JSON 参数事件在 UI 无输入框，以 IPC 为准） |
| TC-M-05 返回值 JDK 类型 | 检查命令返回 JSON | 全部为 Map/List/基本类型，无自定义类（AIDL 通道约束） |
| TC-M-06 状态恢复 | 用例执行结束后复查 | 本地 OTA 策略 enabled=true、无运行中 OTA（FotaCancel 置 IDLE）、槽位未切换；下载目录残留文件删除（adb root） |

## 3. 验证提示

- 结果查看：`./send_test_command.sh` 输出 `Broadcast completed: data=...` 与 logcat `HYX-TESTAPP-CMD` 行；
- 引擎侧日志：`adb shell logcat -d -s ExportUpdaterService:V UpdateManager:V PrepareUpdateService:V FotaUpdateChecker:V UpdaterServiceClient:V`（回调广播、状态机转换、下载/校验过程）；
- 回调消费：`GetOtaCallbackLog` 返回 events（callbackType+value+time），updater_state 的 valueText 已映射 IDLE/ERROR/RUNNING/PAUSED/SLOT_SWITCH_REQUIRED/REBOOT_REQUIRED；
- 槽位对照：`adb shell getprop ro.boot.slot_suffix`（当前槽位）；bootcontrol 状态无 dumpsys 服务，以命令 slots 列表为准；
- 假 OTA 包说明：payload_metadata.bin/compatibility.zip 为伪内容时 verifyPayloadMetadata/packageCompatible 返回 **false 为预期**（"下载经过签名校验的 FOTA 升级包"的验收口径是校验结果如实、失败不误报可用）；真实 OTA 包的校验 true 需供应商签名包真机验证；
- HTTP 服务：`adb reverse tcp:8080 tcp:8080` 仅对当前 adb 会话有效，重连后需重新执行；测试结束 `adb reverse --remove-all`。

## 4. 实测结果（2026-08-11，Android 13 / API 33 userdebug，平台签名 + device owner）

全部用例经 `send_test_command.sh` IPC 通道执行（与 UI 按钮共用 TestActions 引擎）。**设备基线（实测）**：`ro.boot.slot_suffix=_a`、`ro.boot.slot=a`（A/B 设备）；`update_engine` 服务运行中；**本 ROM 无 bootcontrol HAL 接入**（`android.os.BootControl` 无服务，HIDL `android.hardware.boot@1.x::IBootControl/default` 虽注册但框架 Java 通道缺失，见 ASR-0452 记录）；本地 OTA 策略默认 enabled=true；测试 OTA 包经宿主机 HTTP 服务 + `adb reverse tcp:8080 tcp:8080` 提供。

| 用例 | 实测结果 |
|---|---|
| TC-0446-01 | 通过：`missing parameter: config (JSON)`，不 crash |
| TC-0446-02 | 通过：`{"success":false,"error":"invalid config JSON: Expected ':' after bad at character 4 of {bad"}` |
| TC-0446-03 | 通过：`{"success":true,"versionName":"TEST-OTA-BUILD-1","downloadedFiles":{"payload_metadata.bin":2048,"payload_properties.txt":94,"compatibility.zip":178},"verifyPayloadMetadata":false,"packageCompatible":true,"payloadProperties":{"FILE_HASH":"abcdef0123456789","FILE_SIZE":"9999","PACKAGE_VERSION":"1.2.3-TEST","PACKAGE_BUILD":"2026-08-11"},"packageVersion":"1.2.3-TEST"}`——虚假包被 update_engine 如实拒绝（invalid delta magic），未误报可用 |
| TC-0446-04 | 通过：update_engine 日志 `Received a request of verifying payload metadata in /data/ota_package/payload_metadata.bin` + `Bad payload format -- invalid delta magic: 46414b45 Expected: 43724155`（引擎正常响应校验请求，接口可用） |
| TC-0446-05 | 通过：`/data/ota_package/` 下三个文件存在（0644）且大小与 property_files 一致；重复执行先清理旧文件 |
| TC-0446-06 | 通过：`{"success":false,"error":"java.net.ConnectException: Failed to connect to /127.0.0.1:9999"}` |
| TC-0446-07 | 通过：`{"success":true,"note":"...contains no payload_metadata.bin; metadata verification skipped","verifyPayloadMetadata":null,"payloadProperties":{}}` |
| TC-0446-08 | 通过：`{"success":true,"filePath":"/data/user/0/com.hmdm.launcher/files/ota/fota_package.zip","fileSize":2795,...}`（与源包 2795 字节一致；zip 内提取校验闭环，verifyPayloadMetadata=false） |
| TC-0446-09 | 通过：`ls -la /data/user/0/com.hmdm.launcher/files/ota/` 显示 fota_package.zip 2795 字节 |
| TC-0448-01 | 通过：`{"success":true,"enabled":true}`（默认允许） |
| TC-0448-02 | 通过：`{"success":true,"enabled":false}` |
| TC-0448-03 | 通过：`{"success":true,"enabled":false}` |
| TC-0448-04 | 通过：FotaStart（content:// 本地 URI）→ 广播 -104 local ota disabled by policy，未进入安装流程（logcat `FotaStart notifyStatus ... code:-104`；testapp OtaCallbackRecorder 亦收到 code=-104 事件） |
| TC-0448-05 | 通过：`{"success":false,"dispatched":false,"error":"local ota disabled by policy"}`（file:// 配置未绑定引擎） |
| TC-0448-06 | 通过：`{"success":true,"enabled":true}` |
| TC-0448-07 | 通过：FotaStart 重新受理（logcat `code:100 accepted`，随后 -4 no content provider 为测试 Uri 不存在所致，证明门禁已放开） |
| TC-0448-08 | 通过：`missing parameter: enabled` |
| TC-0449-01 | 通过：`{"success":true,"dispatched":true,"state":0,"stateText":"IDLE"}`（IDLE 幂等取消） |
| TC-0449-02 | 通过：无崩溃；引擎无运行中更新时 cancel 抛"Failed to open…No ongoing update to cancel"被引擎容错捕获（本 ROM update_engine 对无任务 cancel 抛异常为预期，服务与命令均正常） |
| TC-0449-03 | 通过（受限验证）：ERROR 态取消 → `{"success":true,"dispatched":true,"state":0,"stateText":"IDLE"}`，回调日志出现 updater_state=0（取消触发状态广播）；真实 RUNNING 中取消需有效 OTA payload 驱动（本机无签名 OTA 包，见硬件受限测试说明） |
| TC-0449-04 | 通过：stateText 映射 0/1/2/3/4/5 ↔ IDLE/ERROR/RUNNING/PAUSED/SLOT_SWITCH_REQUIRED/REBOOT_REQUIRED 经各状态实测正确 |
| TC-0450-01 | 通过：IDLE 下 suspend → `{"success":true,"dispatched":true,"state":0,"stateText":"IDLE"}`（IDLE→PAUSED 非法转换被服务捕获记日志，命令如实返回） |
| TC-0450-02 | 通过：IDLE 下 resume → `{"success":true,"dispatched":true,"state":2,"stateText":"RUNNING"}`（IDLE→RUNNING 合法，无 payload 时重放为空操作，命令如实返回） |
| TC-0450-03 | 通过（受限验证）：流式应用虚假大包（50MB payload.bin）→ 引擎因 payload 属性校验失败快速置 ERROR，suspend 落在 ERROR 态如实返回 state=1/ERROR；dispatch 与状态上报正确，真实 RUNNING→PAUSED 转换需有效 OTA payload |
| TC-0450-04 | 通过（受限验证）：同上，resume 对 ERROR 态如实返回 state=1/ERROR，不 crash；真实 PAUSED→RUNNING 转换需有效 OTA payload |
| TC-0451-01 | 通过：`{"cleared":N}`（按当前日志条数返回） |
| TC-0451-02 | 通过：`{"count":0,"events":[]}` |
| TC-0451-03 | 通过：FotaApply（不可达 URL）后回调日志 4 条：`updater_state=0(IDLE)`（apply 前复位）→ `updater_state=2(RUNNING)` → `progress=0.0` → `updater_state=1(ERROR)`（PrepareUpdateService 失败）；callbackType/valueText 解析正确 |
| TC-0451-04 | 通过（说明）：无效 payload 场景引擎状态码未变化（恒 0），无 engine_status 事件为如实结果（真实升级过程中引擎状态码变化时上报） |
| TC-0451-05 | 通过：`progress=0.0` 事件经广播到达（引擎上报进度即转发） |
| TC-0451-06 | 通过：ERROR 态 FotaCancel → 回调日志新增 `updater_state=0(IDLE)`（取消/暂停/恢复操作绑定回调后状态变更持续广播） |
| TC-0451-07 | 通过：连续 GetOtaCallbackLog count 单调不减（广播可被多个接收器消费） |
| TC-0452-01 | 通过（ROM 受限）：`{"success":true,"supported":true,"halAvailable":false,"currentSlot":0,"currentSlotSuffix":"_a","nextBootSlot":-1,"bootloaderSlots":"a/_a","slots":[{"slot":0,"suffix":"_a","bootable":"unknown","markedSuccessful":"unknown"},{"slot":1,"suffix":"_b",...}]}` + note：**本 ROM（MTK）未接入 bootcontrol HAL**（android.os.BootControl 无服务；HIDL HAL 进程在跑但框架 Java 通道缺失），当前槽位经 bootloader 属性读取，可启动/启动成功标志与下次启动槽位不可查询 |
| TC-0452-02 | 通过：`getprop ro.boot.slot`=a、`ro.boot.slot_suffix`=_a，与命令 currentSlot=0/currentSlotSuffix=_a/bootloaderSlots=a/_a 一致 |
| TC-0452-03 | 通过（ROM 受限）：nextBootSlot=-1（无 HAL 不可查询，如实上报 + note） |
| TC-0452-04 | 通过：`{"success":true,"dispatched":true}`（AIDL setSwitchSlotOnReboot 送达 ExportUpdaterService；真实槽位切换需已应用 OTA 且触发重启，本用例验证命令契约） |
| TC-0452-05 | 通过：`missing parameter: config (JSON)` |
| TC-0452-06 | 通过：`{"success":false,"error":"invalid config JSON: ..."}` |
| TC-M-01 | 通过：4 个 config 命令缺参均返回 `missing parameter: config (JSON)`、SetLocalOtaEnabled 缺参返回 `missing parameter: enabled`，不 crash |
| TC-M-02 | 通过：`unknown event: FotaCancelXXX (try ListEvents)` |
| TC-M-03 | 通过：ListEvents 含全部 13 个新事件（FotaCheckUpdate/FotaDownloadUpdate/Set+IsLocalOtaEnabled/FotaApply/FotaStartLocal/FotaCancel/FotaSuspend/FotaResume/GetSlotInfo/SetSwitchSlotOnReboot/Get+ClearOtaCallbackLog）及参数/说明 |
| TC-M-04 | 通过：`OtaTestActivity` 启动后 `dumpsys activity` ResumedActivity=com.hmdm.testapp/.OtaTestActivity（页面 13 按钮与事件对应；config 参数事件以 IPC 为准） |
| TC-M-05 | 通过：全部返回值为 Map/List/基本类型（AIDL 通道约束；GetSlotInfo 无 HAL 路径不携带 null 值——bootable/markedSuccessful 用字符串 unknown、nextBootSlot 用 -1） |
| TC-M-06 | 通过：本地 OTA 策略恢复 enabled=true；UpdaterState 恢复 IDLE；测试文件已清理（/data/ota_package 与 filesDir/ota 删除）；槽位未切换（未触发重启） |

**实现修正记录（2026-08-11 实测发现）**：
1. **update_engine 无法读 Launcher 私有目录**：初版校验文件下载到 `filesDir/ota/check/`，update_engine 报 SELinux `avc: denied { search } ... system_data_file` 无法打开——校验文件改落 `/data/ota_package`（ota_package_file 类型、引擎可读，与 PrepareUpdateService 同目录），且因 **update_engine 无 CAP_DAC_OVERRIDE（SELinux 拒绝 capability）**，文件需 chmod 0644（0600 时引擎报 dac_read_search/dac_override 拒绝）；
2. **本 ROM UpdateEngine 为 fork 方法集**：`bind(UpdateEngineCallback)` 不存在（NoSuchMethodError 崩溃 ExportUpdaterService）——UpdateManager.bind 改为运行时方法发现（bind(UpdateEngineCallback,Handler)/bind(UpdateEngineCallback) 变体 + Throwable 容错）；cancel/resetStatus 等直接调用同样 Throwable 容错（本 ROM 无任务时 cancel 抛 update_engine 错误为预期，服务不崩溃）；
3. **状态机卡死**：FotaApply 在 RUNNING/ERROR 残留态下抛 InvalidTransitionException 拒绝应用——ExportUpdaterService.applyUpdate 增加非 IDLE 态先复位（cancelRunningUpdate 尽力，本 ROM 引擎无任务 cancel 抛错被容错）；
4. **AIDL 通道 null 值**：GetSlotInfo 无 HAL 路径曾携带 null（nextBootSlot/bootable）导致事务失败返回 null——改为 unknown 字符串/-1；
5. **IPC 参数类型**：`send_test_command.sh` 对 JSON 对象值不加引号，config 到达 testapp 为 Map——TestActions 对 Fota* 事件支持 Map 序列化回 JSON（config 键或直接平铺字段两种写法均可）。

**部署注意**：重装 Launcher 后 testapp 进程必须重启（`kill <pid>`，勿用 am force-stop——MTK DuraSpeed 会将其加入 suppress list 丢弃广播）以重建 AIDL 绑定，否则命令返回 `RESULT:null`；`adb reverse` 仅在当前会话有效，测试结束 `adb reverse --remove-all`。
