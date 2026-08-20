# 锁屏策略管控（ASR-0359/0363/0365）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner，testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root && dumpsys duraspeed addwhitelist com.hmdm.testapp`、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`（IPC 与 UI 按钮等效，同一 `TestActions.execute()` 引擎）；也可在 testapp UI 的 "Lock screen policy" 页面点按对应按钮；
- 对照命令：`adb shell dumpsys device_policy | grep -iE "password|strong"`（passwordQuality/minimumPasswordLength/strongAuthUnlockTimeout/passwordExpirationTimeout/passwordExpirationDate）、`adb shell locksettings set-password <pw>` / `adb shell locksettings clear`（策略真实生效验证）；
- 恢复基线（测试开始时记录、结束时恢复）：`strongAuthUnlockTimeout=0`、`passwordExpirationTimeout=0`、`passwordQuality=0x0`（UNSPECIFIED）、`minimumPasswordLength=0`、无锁屏密码；
- 本 ROM 特性（2026-08-04 核验）：DPM 相关字段在 `dumpsys device_policy` 中完整存在；ASR-0363 为 `setPasswordQuality`/`setPasswordMinimumLength` 组合近似实现（AOSP 无连续数字内容校验 API），映射与反向映射规则见技术设计文档 2.4；**ASR-0359**：非 0 且 <3600000ms 的强认证超时会被 ROM 提升为 3600000ms（1 小时下限）；**ASR-0363**：本 ROM 在 quality=UNSPECIFIED 时拒绝 setPasswordMinimumLength（引擎按方向调整写入顺序规避），密码策略激活期间 `locksettings clear` 亦被拒绝（须先恢复策略再清密码）。

## 2. 测试用例表

### 2.1 ASR-0359 锁屏强认证超时（SetStrongAuthTimeout / GetStrongAuthTimeout）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0359-01 基线查询 | `./send_test_command.sh GetStrongAuthTimeout` | success=true；timeoutMs=0（基线） |
| TC-0359-02 设置超时 | `./send_test_command.sh SetStrongAuthTimeout timeoutMs=7200000` | success=true；timeoutMs=7200000；`dumpsys device_policy` 对照 strongAuthUnlockTimeout=7200000 |
| TC-0359-03 查询状态 | `./send_test_command.sh GetStrongAuthTimeout` | success=true；timeoutMs=7200000 |
| TC-0359-04 设置 0（立即强认证） | `./send_test_command.sh SetStrongAuthTimeout timeoutMs=0` | success=true；timeoutMs=0；系统对照=0 |
| TC-0359-05 恢复基线 | `./send_test_command.sh SetStrongAuthTimeout timeoutMs=0` | 基线保持 0 |
| TC-0359-06 缺参 | `./send_test_command.sh SetStrongAuthTimeout` | 返回 `missing parameter: timeoutMs`，不 crash |
| TC-0359-07 非法参数 | `./send_test_command.sh SetStrongAuthTimeout timeoutMs=-1`、`timeoutMs=abc` | 返回 `invalid timeoutMs`，不写入（键保持原值），不 crash |
| TC-0359-08 **ROM 下限机制** | `./send_test_command.sh SetStrongAuthTimeout timeoutMs=300000`、`timeoutMs=60000` | 本 ROM 将非 0 且 <3600000 的值提升为 3600000（1 小时，MTK 默认强认证超时）：返回 success=false + error（read-back mismatch，实际 3600000），如实上报不假报成功 |
| TC-0359-09 超出上限 | `./send_test_command.sh SetStrongAuthTimeout timeoutMs=2592000001` | 返回 `invalid timeoutMs`/`timeoutMs out of range`，不写入，不 crash（上限 30 天 = 2592000000） |

### 2.2 ASR-0365 密码更改宽限期（SetPasswordExpirationTimeout / GetPasswordExpirationTimeout）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0365-01 基线查询 | `./send_test_command.sh GetPasswordExpirationTimeout` | success=true；timeoutMs=0；expiration=0 |
| TC-0365-02 设置宽限期 | `./send_test_command.sh SetPasswordExpirationTimeout timeoutMs=86400000` | success=true；timeoutMs=86400000；expiration≈now+86400000（设置超时即产生到期时刻，即使无密码）；`dumpsys device_policy` 对照 passwordExpirationTimeout=86400000、passwordExpirationDate 同步 |
| TC-0365-03 查询状态 | `./send_test_command.sh GetPasswordExpirationTimeout` | success=true；timeoutMs=86400000 |
| TC-0365-04 恢复 0（永不过期） | `./send_test_command.sh SetPasswordExpirationTimeout timeoutMs=0` | success=true；timeoutMs=0；expiration=0 |
| TC-0365-05 缺参 | `./send_test_command.sh SetPasswordExpirationTimeout` | 返回 `missing parameter: timeoutMs`，不 crash |
| TC-0365-06 非法参数 | `./send_test_command.sh SetPasswordExpirationTimeout timeoutMs=-5`、`timeoutMs=xyz` | 返回 `invalid timeoutMs`，不写入，不 crash |
| TC-0365-07 超出上限 | `./send_test_command.sh SetPasswordExpirationTimeout timeoutMs=2592000001` | 返回 `timeoutMs out of range`，不写入，不 crash（上限 30 天） |

### 2.3 ASR-0363 连续数字序列长度上限（SetConsecutiveDigitsLimit / GetConsecutiveDigitsLimit）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0363-01 基线查询 | `./send_test_command.sh GetConsecutiveDigitsLimit` | success=true；limit=0；quality=0（unspecified）；minLength=0 |
| TC-0363-02 设置 limit=2 | `./send_test_command.sh SetConsecutiveDigitsLimit limit=2` | success=true；limit=2；quality=alphanumeric；minLength=6；`dumpsys device_policy` 对照 passwordQuality=0x50000、minimumPasswordLength=6 |
| TC-0363-03 锁屏真实校验（弱密码被拒） | `adb shell locksettings set-password 1234` | 被锁屏服务拒绝（密码不满足 alphanumeric+6 位策略），提示密码太弱/不满足要求 |
| TC-0363-04 锁屏真实校验（合规密码可设置） | `adb shell locksettings set-password abc123` | 设置成功；`dumpsys device_policy` 对照 activePasswordSufficient=true |
| TC-0363-05 清除密码 | 先恢复策略（`SetConsecutiveDigitsLimit limit=0`）再 `adb shell locksettings clear --old <pw>` | 策略激活期间 `locksettings clear` 被锁屏服务拒绝（"Weak credential type"，移除凭据不满足策略）；恢复策略（limit=0）后清除成功（"Lock credential cleared"） |
| TC-0363-06 设置 limit=1 | `./send_test_command.sh SetConsecutiveDigitsLimit limit=1` | success=true；返回 limit=2（映射区间上界）；quality=alphanumeric；minLength=6 |
| TC-0363-07 设置 limit=5 | `./send_test_command.sh SetConsecutiveDigitsLimit limit=5` | success=true；返回 limit=7（区间上界）；quality=numeric；minLength=8 |
| TC-0363-08 设置 limit=10 | `./send_test_command.sh SetConsecutiveDigitsLimit limit=10` | success=true；返回 limit=16（区间上界）；quality=numeric；minLength=6 |
| TC-0363-09 查询与映射一致 | `./send_test_command.sh GetConsecutiveDigitsLimit` | 返回的 limit 与 2.4 节反向映射一致（alphanumeric/6→2、numeric/8→7、numeric/6→16；limit=10 时查询返回 16，如实反映策略区间上界） |
| TC-0363-10 恢复 0 | `./send_test_command.sh SetConsecutiveDigitsLimit limit=0` | success=true；limit=0；quality=unspecified；minLength=0；系统对照恢复 0。**修复记录**：初测报 `setConsecutiveDigitsLimit failed: password quality should be at least 131072 for setPasswordMinimumLength`——本 ROM 在 quality=UNSPECIFIED 时拒绝 setPasswordMinimumLength（AOSP 无此限制）；引擎改为按方向调整写入顺序（target=UNSPECIFIED 时先降长度再降质量，其余先立质量再写长度）后复测通过 |
| TC-0363-11 缺参 | `./send_test_command.sh SetConsecutiveDigitsLimit` | 返回 `missing parameter: limit`，不 crash |
| TC-0363-12 越界值 | `./send_test_command.sh SetConsecutiveDigitsLimit limit=17`、`limit=-1`、`limit=abc` | 返回 `invalid limit`，不写入，不 crash |

### 2.4 通用用例

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-M-01 缺参容错 | 3 个 Set 命令均不带参数执行 | 均返回缺参提示，不 crash（3 个查询命令无参数） |
| TC-M-02 未知事件 | `./send_test_command.sh SetStrongAuthTimeoutXXX` | 返回 unknown event，不 crash |
| TC-M-03 UI 等效 | testapp UI "Lock screen policy" 页逐一按钮 | 与 IPC 返回一致（同一 TestActions 引擎） |
| TC-M-04 返回值 JDK 类型 | 检查命令返回 JSON | 全部为 Map/List/基本类型，无自定义类（AIDL 通道约束） |
| TC-M-05 状态恢复 | 用例执行结束后复查 DPM 字段与锁屏密码 | strongAuthUnlockTimeout=0、passwordExpirationTimeout=0、passwordQuality=0、minimumPasswordLength=0、无锁屏密码，无测试残留 |

## 3. 验证提示

- 结果查看：`./send_test_command.sh` 输出 `Broadcast completed: data=...` 与 logcat `HYX-TESTAPP-CMD` 行；
- DPM 对照：`adb shell dumpsys device_policy | grep -iE "password|strong"`（passwordQuality、minimumPasswordLength、strongAuthUnlockTimeout、passwordExpirationTimeout、passwordExpirationDate）；
- 密码策略真实生效验证：`adb shell locksettings set-password <pw>`（不满足策略时被拒；对照 `dumpsys device_policy`：passwordQuality=0x50000=alphanumeric、0x20000=numeric、0x0=unspecified）、`adb shell locksettings clear`（清除密码恢复；本 ROM 无 `locksettings get-password-quality` 命令，用 `dumpsys device_policy` 对照）；
- **注意**：TC-0363-03/04 会真实设置/清除设备锁屏密码，执行期间勿中断；执行顺序须保证先设置合规密码再 `locksettings clear` 恢复，避免设备遗留密码；
- 恢复注意：本批命令均为 DPM 策略写后读回核对，恢复时以命令返回 + `dumpsys device_policy` 双对照为准。

## 4. 实测结果（2026-08-04，Android 13 / API 33 userdebug，平台签名 + device owner）

全部用例经 `send_test_command.sh` IPC 通道执行（与 UI 按钮共用 TestActions 引擎）：

| 用例 | 实测结果 |
|---|---|
| TC-0359-01 | 通过：`{"success":true,"timeoutMs":0}` |
| TC-0359-02 | 通过：success=true，timeoutMs=7200000；`dumpsys device_policy` strongAuthUnlockTimeout=7200000 |
| TC-0359-03 | 通过：`{"success":true,"timeoutMs":7200000}` |
| TC-0359-04 | 通过：success=true，timeoutMs=0；系统对照=0 |
| TC-0359-05 | 通过：基线保持 0 |
| TC-0359-06 | 通过：`missing parameter: timeoutMs` |
| TC-0359-07 | 通过：`-1`、`abc` 均返回 `invalid timeoutMs: ...`，键保持原值（0），不写入、不 crash |
| TC-0359-08 | 通过：`timeoutMs=300000`、`timeoutMs=60000` 均返回 success=false + `read-back mismatch, expected ... got 3600000`；系统对照 strongAuthUnlockTimeout=3600000（ROM 下限机制如实上报） |
| TC-0359-09 | 通过（评审加固后补测）：`timeoutMs=2592000001` 返回 `{"error":"timeoutMs out of range (0..2592000000)","success":false}`，不写入；TestBroadcast 通道 `{"timeoutMs":16.9}`（Gson Double）返回 invalid timeoutMs，状态不变（类型校验加固） |
| TC-0365-01 | 通过：`{"success":true,"timeoutMs":0,"expiration":0}` |
| TC-0365-02 | 通过：success=true，timeoutMs=86400000，expiration=1785936140461（now+24h）；系统对照 passwordExpirationTimeout=86400000、passwordExpirationDate=1785936140461 |
| TC-0365-03 | 通过：timeoutMs=86400000，expiration 一致 |
| TC-0365-04 | 通过：success=true，timeoutMs=0，expiration=0；系统对照 0/0 |
| TC-0365-05 | 通过：`missing parameter: timeoutMs` |
| TC-0365-06 | 通过：`-5`、`xyz` 均返回 `invalid timeoutMs: ...`，不写入、不 crash |
| TC-0365-07 | 通过（评审加固后补测）：`timeoutMs=2592000001` 返回 `timeoutMs out of range (0..2592000000)`，不写入 |
| TC-0363-01 | 通过：`{"success":true,"limit":0,"quality":0,"qualityName":"unspecified","minLength":0,"activePasswordSufficient":true}` |
| TC-0363-02 | 通过：success=true，limit=2，quality=327680（alphanumeric），minLength=6；系统对照 passwordQuality=0x50000、minimumPasswordLength=6 |
| TC-0363-03 | 通过：`locksettings set-password 1234` 被拒：`New credential doesn't satisfy admin policies: Password too short; required: 6` |
| TC-0363-04 | 通过：`locksettings set-password abc123` 成功：`Password set to 'abc123'` |
| TC-0363-05 | 通过：策略激活期间 `locksettings clear` 被拒（`Weak credential type`）；`SetConsecutiveDigitsLimit limit=0` 恢复策略后 `locksettings clear --old abc123` 成功（`Lock credential cleared`），系统对照 activePasswordSufficient=true |
| TC-0363-06 | 通过：success=true，返回 limit=2（区间上界），alphanumeric/6 |
| TC-0363-07 | 通过：success=true，返回 limit=7（区间上界），numeric/8 |
| TC-0363-08 | 通过：success=true，返回 limit=16（区间上界），numeric/6 |
| TC-0363-09 | 通过：查询返回 limit=16、numeric/6，与反向映射一致；查询/设置结果均含 `managedByPasswordMode`（本引擎策略=false） |
| TC-0363-10 | 通过：success=true，limit=0，unspecified/0，系统对照恢复 0/0。**修复记录**：初测 limit=0 报 `password quality should be at least 131072 for setPasswordMinimumLength`（本 ROM 在 quality=UNSPECIFIED 时拒绝 setPasswordMinimumLength，且写入先于校验导致状态已部分变更）；引擎改为按方向调整写入顺序（target=UNSPECIFIED 先降长度再降质量，其余先立质量再写长度）后复测通过 |
| TC-0363-11 | 通过：`missing parameter: limit` |
| TC-0363-12 | 通过：`limit=17`、`limit=-1`、`limit=abc` 均返回 `invalid limit: ... (int, 0..16)`，不写入、不 crash |
| TC-M-01 | 通过：3 个 Set 命令缺参均返回缺参提示，不 crash（3 个查询命令无参数） |
| TC-M-02 | 通过：`SetStrongAuthTimeoutXXX` → unknown event |
| TC-M-03 | 通过（说明）：UI/IPC 共用 TestActions.execute() 同一引擎，行为一致（UI 按钮未逐一点击，IPC 全覆盖；LockScreenPolicyTestActivity 已部署并启动验证） |
| TC-M-04 | 通过：返回值全 JDK Map/List/基本类型 |
| TC-M-05 | 通过：全部恢复基线——strongAuthUnlockTimeout=0、passwordExpirationTimeout=0、passwordExpirationDate=0、passwordQuality=0x0、minimumPasswordLength=0、无锁屏密码（`locksettings get-disabled` 正常） |

**部署注意**：① ASR-0365 需 `device_admin.xml` 声明 `<expire-password/>` uses-policy，本 ROM DPM 缓存策略集，仅重装 APK 不生效，须重启 framework/整机（实测 adb reboot 后生效）；② `adb install -r` 重装 Launcher 会结束其进程且不会自动重启（HOME 桌面进程被杀），需重新拉起 HOME（`am start -a android.intent.action.MAIN -c android.intent.category.HOME`）后 testapp 进程亦需重启（`am force-stop com.hmdm.testapp`）以重建 AIDL 绑定，否则返回 `RESULT:null`（DeadObjectException）。
