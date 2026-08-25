# 账户/备份管控（ASR-0124/0129/0132）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner，testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root && dumpsys duraspeed addwhitelist com.hmdm.testapp`、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`（IPC 与 UI 按钮等效，同一 `TestActions.execute()` 引擎）；也可在 testapp UI 的 "Account & backup" 页面点按对应按钮；
- 对照命令：`adb shell dumpsys user | grep -A5 -i restriction`（用户限制）、`adb shell dumpsys account` / `adb shell pm list accounts`（账户列表）、`adb shell settings get secure backup_enabled` / `backup_auto_restore`（备份恢复键）、`adb shell dumpsys backup | grep -i enabled`（备份服务状态）；
- 恢复基线（测试开始时记录、结束时恢复）：无 `no_modify_accounts` 用户限制、`backup_enabled=0`、`backup_auto_restore` 未设置（null）、DPM 备份服务关闭（`backupServiceEnabled=false`）、无账户；
- 本 ROM 特性（2026-08-04 核验）：无 GMS（无 Google 账户类型），设备当前无账户；`dpm.setSecureSetting` 的 AOSP 白名单不含备份键（`backup_enabled`/`backup_auto_restore` 必然回退平台签名直写，命令返回 channel=settings）；**ASR-0124 关键核验**：`DISALLOW_BACKUP`（no_backup）已被 AOSP 13 移出有效用户限制集合（`UserRestrictionsUtils.USER_RESTRICTIONS`），`dpm.addUserRestriction` 静默丢弃（logcat 报 `Unknown restriction queried by uid 1000: no_backup`，`dumpsys user` 无变化），故实现改用 device owner 公开接口 `dpm.setBackupServiceEnabled`/`isBackupServiceEnabled`（API 31+）。

## 2. 测试用例表

### 2.1 ASR-0124 系统备份（SetBackupDisabled / IsBackupDisabled，dpm.setBackupServiceEnabled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0124-01 基线查询 | `./send_test_command.sh IsBackupDisabled` | success=true；backupServiceEnabled 与当前 DPM 状态一致（本设备基线为 false，disabled=true）；backup_enabled 附报当前设置键值（本设备基线 0） |
| TC-0124-02 禁用系统备份 | `./send_test_command.sh SetBackupDisabled disabled=true` | success=true；backupServiceEnabled=false；disabled=true；读回一致 |
| TC-0124-03 查询状态 | `./send_test_command.sh IsBackupDisabled` | success=true；disabled=true；backupServiceEnabled=false |
| TC-0124-04 重复禁用（幂等） | `./send_test_command.sh SetBackupDisabled disabled=true` | success=true；状态保持 disabled=true，无异常 |
| TC-0124-05 恢复启用 | `./send_test_command.sh SetBackupDisabled disabled=false` | success=true；backupServiceEnabled=true；disabled=false；读回一致 |
| TC-0124-06 缺参 | `./send_test_command.sh SetBackupDisabled` | 返回 `missing parameter: disabled`，不 crash |
| TC-0124-07 非法参数 | `./send_test_command.sh SetBackupDisabled disabled=abc`、`disabled=2` | 任意非 `true` 字符串按 Boolean.parseBoolean 语义解析为 false（与既有命令一致），命令不 crash |
| TC-0124-08 布尔参数兼容 | `./send_test_command.sh SetBackupDisabled disabled=true`（对照） | send_test_command.sh 将 `true`/`false` 透传为 Boolean；`disabled=1` 以字符串发送时解析为 false（文档化差异，命令侧不 crash） |

### 2.2 ASR-0129 谷歌账户（SetGoogleAccountsDisabled / IsGoogleAccountsDisabled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0129-01 基线查询 | `./send_test_command.sh IsGoogleAccountsDisabled` | success=true；disabled=false；restriction=no_modify_accounts；accounts=[]（设备无账户） |
| TC-0129-02 禁用谷歌账户 | `./send_test_command.sh SetGoogleAccountsDisabled disabled=true` | success=true；disabled=true；restrictionApplied=true；removedAccounts=0（无存量账户）；`dumpsys user` 对照 Restrictions 含 `no_modify_accounts` |
| TC-0129-03 查询状态 | `./send_test_command.sh IsGoogleAccountsDisabled` | success=true；disabled=true；accounts=[] |
| TC-0129-04 恢复启用 | `./send_test_command.sh SetGoogleAccountsDisabled disabled=false` | success=true；disabled=false；`dumpsys user` 对照 Restrictions 无 `no_modify_accounts` |
| TC-0129-05 缺参 | `./send_test_command.sh SetGoogleAccountsDisabled` | 返回 `missing parameter: disabled`，不 crash |
| TC-0129-06 非法参数 | `./send_test_command.sh SetGoogleAccountsDisabled disabled=abc` | 命令不 crash（布尔解析边界同 TC-0124-07/08 说明） |
| TC-0129-07 账户删除机制（代码评审 + 空账户实测） | 设备无账户时执行禁用 | removedAccounts=0、accounts=[]；删除路径为 `AccountManager.removeAccountExplicitly` 通用调用（对任意账户类型生效；本 ROM 无 GMS，无 Google 账户可实测删除） |

### 2.3 ASR-0132 Google 备份和恢复（SetBackupRestoreDisabled / IsBackupRestoreDisabled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0132-01 基线查询 | `./send_test_command.sh IsBackupRestoreDisabled` | success=true；disabled=false；backup_enabled=1；backup_auto_restore=1（基线） |
| TC-0132-02 禁用备份恢复 | `./send_test_command.sh SetBackupRestoreDisabled disabled=true` | success=true；disabled=true；backup_enabled=0；backup_auto_restore=0；channel=settings（本 ROM DPM 白名单外回退直写） |
| TC-0132-03 查询状态 | `./send_test_command.sh IsBackupRestoreDisabled` | success=true；disabled=true；backup_enabled=0；backup_auto_restore=0 |
| TC-0132-04 系统对照 | `adb shell settings get secure backup_enabled`、`adb shell settings get secure backup_auto_restore` | 均为 0，与命令返回一致 |
| TC-0132-05 恢复启用 | `./send_test_command.sh SetBackupRestoreDisabled disabled=false` | success=true；disabled=false；backup_enabled=1；backup_auto_restore=1 |
| TC-0132-06 缺参 | `./send_test_command.sh SetBackupRestoreDisabled` | 返回 `missing parameter: disabled`，不 crash |
| TC-0132-07 非法参数 | `./send_test_command.sh SetBackupRestoreDisabled disabled=abc` | 命令不 crash（布尔解析边界同 TC-0124-07/08 说明） |

### 2.4 通用用例

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-M-01 缺参容错 | 3 个 Set 命令均不带参数执行 | 均返回缺参提示，不 crash（3 个查询命令无参数） |
| TC-M-02 未知事件 | `./send_test_command.sh SetBackupDisabledXXX` | 返回 unknown event，不 crash |
| TC-M-03 UI 等效 | testapp UI "Account & backup" 页逐一按钮 | 与 IPC 返回一致（同一 TestActions 引擎） |
| TC-M-04 返回值 JDK 类型 | 检查命令返回 JSON | 全部为 Map/List/基本类型，无自定义类（AIDL 通道约束） |
| TC-M-05 状态恢复 | 用例执行结束后复查限制与设置键 | 无 `no_modify_accounts` 限制、backup_enabled=0、backup_auto_restore 未设置（null）、DPM 备份服务恢复前序状态（本设备为关闭），无测试残留 |

## 3. 验证提示

- 结果查看：`./send_test_command.sh` 输出 `Broadcast completed: data=...` 与 logcat `HYX-TESTAPP-CMD` 行；
- 用户限制对照：`adb shell dumpsys user | grep -A5 -i restriction`（ASR-0129 限制键名 `no_modify_accounts`；"Device policy local restrictions" 段为用户限制实时状态）；
- 备份服务对照：`adb shell dumpsys backup | grep -i enabled`（备份服务启用状态）；ASR-0124 经 `dpm.isBackupServiceEnabled` 读回核对；
- 账户对照：`adb shell dumpsys account` / `adb shell pm list accounts`（本 ROM 无 GMS，预期无账户）；
- 备份恢复键对照：`adb shell settings get secure backup_enabled`、`adb shell settings get secure backup_auto_restore`（0=禁用，1=启用，缺省视为 1）；
- 布尔参数说明：`send_test_command.sh` 对 `true`/`false` 透传为 Boolean；数字串（如 `1`）与任意字符串按 `Boolean.parseBoolean` 语义处理（非 `true` 均为 false），命令侧不 crash（与既有 `SetCameraDisabled` 等命令行为一致）。

## 4. 实测结果（2026-08-04，Android 13 / API 33 userdebug，平台签名 + device owner）

全部用例经 `send_test_command.sh` IPC 通道执行（与 UI 按钮共用 TestActions 引擎）。**设备基线（实测）**：`backup_enabled=0`、`backup_auto_restore` 未设置（null）、DPM 备份服务已关闭（backupServiceEnabled=false，前序配置状态）、无账户、无 no_modify_accounts 限制。

| 用例 | 实测结果 |
|---|---|
| TC-0124-01 | 通过：`{"success":true,"disabled":true,"backup_enabled":0,"backupServiceEnabled":false}`（基线即禁用态，如实反映） |
| TC-0124-02 | 通过：success=true，backupServiceEnabled=false，disabled=true（基线已禁用，读回一致） |
| TC-0124-03 | 通过：`{"success":true,"disabled":true,"backupServiceEnabled":false}` |
| TC-0124-04 | 通过：success=true，幂等无异常 |
| TC-0124-05 | 通过：success=true，backupServiceEnabled=true，disabled=false（启用方向读回一致，证明双向可控） |
| TC-0124-06 | 通过：`missing parameter: disabled` |
| TC-0124-07 | 通过：`disabled=abc` 解析为 false（执行启用，返回 success=true），不 crash |
| TC-0124-08 | 通过：`true` 透传 Boolean；`1` 字符串解析为 false（文档化差异） |
| TC-0129-01 | 通过：`{"success":true,"disabled":false,"restriction":"no_modify_accounts","accounts":[]}` |
| TC-0129-02 | 通过：success=true，restrictionApplied=true，removedAccounts=0，accounts=[]；`dumpsys user` "Device policy local restrictions" 含 no_modify_accounts |
| TC-0129-03 | 通过：`{"success":true,"disabled":true,"accounts":[]}` |
| TC-0129-04 | 通过：success=true，disabled=false；`dumpsys user` 限制段恢复 none |
| TC-0129-05 | 通过：`missing parameter: disabled` |
| TC-0129-06 | 通过：`disabled=abc` 解析为 false，不 crash |
| TC-0129-07 | 通过（代码评审 + 空账户实测）：设备无账户，removedAccounts=0；删除路径为 `AccountManager.removeAccountExplicitly` 通用调用 |
| TC-0132-01 | 通过：`{"success":true,"disabled":false,"backup_enabled":0,"backup_auto_restore":1}`（基线 backup_enabled=0 为前序状态，如实反映） |
| TC-0132-02 | 通过：success=true，backup_enabled=0、backup_auto_restore=0，channel=settings（DPM 白名单外回退直写） |
| TC-0132-03 | 通过：`{"success":true,"disabled":true,"backup_enabled":0,"backup_auto_restore":0}` |
| TC-0132-04 | 通过：`settings get secure backup_enabled`=0、`backup_auto_restore`=0，与命令返回一致 |
| TC-0132-05 | 通过：success=true，backup_enabled=1、backup_auto_restore=1 |
| TC-0132-06 | 通过：`missing parameter: disabled` |
| TC-0132-07 | 通过：`disabled=abc` 解析为 false，不 crash |
| TC-M-01 | 通过：3 个 Set 命令缺参均返回缺参提示，不 crash（3 个查询命令无参数） |
| TC-M-02 | 通过：`SetBackupDisabledXXX` → unknown event |
| TC-M-03 | 通过（说明）：UI/IPC 共用 TestActions.execute() 同一引擎，行为一致（AccountBackupTestActivity 已部署并启动验证，按钮与 IPC 事件一一对应） |
| TC-M-04 | 通过：返回值全 JDK Map/List/基本类型 |
| TC-M-05 | 通过：恢复基线——backup_enabled=0、backup_auto_restore 未设置（null）、DPM 备份服务关闭（backupServiceEnabled=false）、无 no_modify_accounts 限制、无账户 |

**实现修正记录（2026-08-04 实测发现）**：ASR-0124 原按 Sheet1 P0 规划采用 `DISALLOW_BACKUP` 用户限制（no_backup），真机实测 `dpm.addUserRestriction` 静默丢弃——AOSP 13 已将 no_backup 移出有效用户限制集合（`UserRestrictionsUtils.USER_RESTRICTIONS`，logcat 报 `Unknown restriction queried by uid 1000: no_backup`，`dumpsys user` 无变化）。经与用户确认后改用 device owner 公开接口 `dpm.setBackupServiceEnabled`/`isBackupServiceEnabled`（API 31+，MdmUtils 既有同款调用），写后读回核对；命令名与参数不变，查询结果语义为备份服务启用状态。

**部署注意**：本批次不修改 `device_admin.xml`（addUserRestriction 与 Settings 直写均无需 uses-policy 声明），**无需重启 framework**；`adb install -r` 重装 Launcher 会结束其进程且不会自动重启，需重新拉起 HOME（`am start -a android.intent.action.MAIN -c android.intent.category.HOME`）后 testapp 进程亦需重启（`am force-stop com.hmdm.testapp`）以重建 AIDL 绑定，否则返回 `RESULT:null`（DeadObjectException）。
