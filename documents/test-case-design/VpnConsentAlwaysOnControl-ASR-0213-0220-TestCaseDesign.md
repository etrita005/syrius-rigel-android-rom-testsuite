# VPN 免交互授权与 Always-on 管控（ASR-0213/0220）测试用例设计文档

## 1. 前置条件

1. Launcher（`com.hmdm.launcher`）平台签名安装、已设为 device owner（`dpm list-owners` 显示 `DeviceOwner,Affiliated`）、API 服务在线（logcat tag `HYX-MDM-APP`）；
2. testapp（`com.hmdm.testapp`）已安装（IPC 方式测试无需 UI，`send_test_command.sh <event> key=value ...`；命令结果以 logcat tag `HYX-TESTAPP-CMD` 或 am broadcast data 为准）；
3. GGR（`com.syriusrobotics.platform.launcher`，其 `MyVpnService` 为 VpnService，主界面启动时调用 `VpnService.prepare()`）已安装，作为被测免交互包；
4. 基线清理：`settings get secure vpn_prepared`=null、`dumpsys device_policy` 的 `mAlwaysOnVpnPackage`=null / `mAlwaysOnVpnLockdown`=false、`cmd appops get com.syriusrobotics.platform.launcher ACTIVATE_VPN`=default、无 TRANSPORT_VPN 网络；
5. 测试期间保持 Launcher 网络防火墙策略关闭（`GetNetworkFirewallStatus` 的 mode=0），避免防火墙 VPN 干扰判定。

## 2. 测试环境

- 目标平台：Android 13（API 33）userdebug，MTK fork（MT6771），平台签名 + device owner；
- 关键系统通道（真机核验，见技术设计文档）：首次授权判定门为 `android:activate_vpn` AppOps（`vpn_prepared` 键仅覆盖已授权应用的 profile 切换场景）；always-on 经 `dpm.setAlwaysOnVpnPackage`（本 ROM 可用）；
- 对照手段：`settings get secure vpn_prepared`、`cmd appops get <pkg> ACTIVATE_VPN`、`dumpsys device_policy | grep -E "mAlwaysOn"`、`dumpsys activity activities | grep ResumedActivity`（确认无 `com.android.vpndialogs/.ConfirmDialog`）、`dumpsys connectivity | grep -c "OwnerUid: 10123"`（GGR VPN 网络，10123 为 GGR uid）。

## 3. 测试用例表

### 3.1 ASR-0213 免交互授权白名单（SetVpnPreparedFor / ClearVpnPreparedFor / GetVpnPreparedForPackageName）

| 用例编号 | 用例名称 | 操作步骤 | 预期结果 |
|---|---|---|---|
| TC-VC-01 | 授权写入成功 | `SetVpnPreparedFor packageName=com.syriusrobotics.platform.launcher` | `{success:true, vpnPreparedSet:true, appOpsGranted:true, appOpsReadBack:0}`；`settings get secure vpn_prepared`=GGR；`cmd appops get ... ACTIVATE_VPN`=allow |
| TC-VC-02 | 端到端免弹窗 | TC-VC-01 后 force-stop GGR 再 `am start -n com.syriusrobotics.platform.launcher/.MainActivity` | ResumedActivity=GGR MainActivity（无 ConfirmDialog）；`dumpsys connectivity` 出现 OwnerUid=10123 的 VPN 网络（tun0）；ACTIVATE_VPN 刷新为 allow |
| TC-VC-03 | 授权查询与跨进程核对 | TC-VC-01 后 `GetVpnPreparedForPackageName`、testapp `GetVpnPreparedLocal` | 两者均返回 `com.syriusrobotics.platform.launcher`（Launcher 进程与 testapp 进程读同一系统键一致） |
| TC-VC-04 | 缺少 packageName | `SetVpnPreparedFor`（无参数） | `missing parameter: packageName` |
| TC-VC-05 | 目标包未安装 | `SetVpnPreparedFor packageName=com.no.such.pkg` | `{success:false, appOpsGranted:false, appOpsReadBack:-1, vpnPreparedRolledBack:true}`；`vpn_prepared` 保持 null（回滚） |
| TC-VC-06 | 清除授权 | 授权后 `ClearVpnPreparedFor` | `{success:true, clearedPackage:com.syriusrobotics.platform.launcher, vpnPreparedCleared:true}`；`vpn_prepared`=null；ACTIVATE_VPN 复位 default（已建立的 VPN 连接不被拆除） |
| TC-VC-07 | 清除后弹窗复现（负向基线） | TC-VC-06 后 force-stop GGR 再启动 | ResumedActivity=`com.android.vpndialogs/.ConfirmDialog`（弹窗恢复，证明授权确实被撤销） |
| TC-VC-08 | 清除幂等 | 无授权时 `ClearVpnPreparedFor` | `{success:true, clearedPackage:null, vpnPreparedCleared:true}` |

### 3.2 ASR-0220 保持/取消/查询 always-on（SetAlwaysOnVpn / GetAlwaysOnVpn / ClearAlwaysOnVpn）

| 用例编号 | 用例名称 | 操作步骤 | 预期结果 |
|---|---|---|---|
| TC-VC-09 | 基线查询 | 基线（无 always-on）`GetAlwaysOnVpn` | `{"RESULT":null}` |
| TC-VC-10 | 设置 always-on（lockdown=false）并自动拉起 | force-stop GGR 后 `SetAlwaysOnVpn packageName=com.syriusrobotics.platform.launcher lockdown=false` | RESULT=true；`dumpsys device_policy` `mAlwaysOnVpnPackage`=GGR、`mAlwaysOnVpnLockdown`=false；6 秒内框架自动拉起 GGR VpnService（tun0、OwnerUid=10123，**无需启动 GGR 界面**）；ACTIVATE_VPN 自动 allow |
| TC-VC-11 | 查询 always-on | TC-VC-10 后 `GetAlwaysOnVpn` | `{"RESULT":"com.syriusrobotics.platform.launcher"}` |
| TC-VC-12 | lockdown 置位 | `SetAlwaysOnVpn packageName=com.syriusrobotics.platform.launcher lockdown=true` | RESULT=true；`mAlwaysOnVpnLockdown`=true；`settings get secure always_on_vpn_lockdown`=1 |
| TC-VC-13 | 取消 always-on | `ClearAlwaysOnVpn` | RESULT=true；`mAlwaysOnVpnPackage`=null、lockdown=false；框架拆除 VPN（OwnerUid 网络消失）；ACTIVATE_VPN 复位 default |
| TC-VC-14 | 缺少 packageName | `SetAlwaysOnVpn`（无参数） | `missing parameter: packageName`（testapp 侧校验） |

## 4. 恢复与清理

测试结束后：

1. `ClearVpnPreparedFor`（清除免交互授权与 AppOps 复位）；
2. `ClearAlwaysOnVpn`（清除 always-on 状态）；
3. 确认 `settings get secure vpn_prepared`=null、`dumpsys device_policy` 无 always-on 条目、`cmd appops get com.syriusrobotics.platform.launcher ACTIVATE_VPN`=default、无残留 tun0；
4. GGR 界面如残留 ConfirmDialog，`adb shell input keyevent KEYCODE_BACK` 关闭。

## 5. 验证提示

- 命令结果以 `send_test_command.sh` 的 logcat 行（tag `HYX-TESTAPP-CMD`）或 am broadcast data 为准；
- Launcher 侧执行日志：`adb logcat -s HYX-MDM-APP`（引擎 tag `VpnAlwaysOnManager`/`AppOpsPolicyManager`）；
- 弹窗判定：`adb shell dumpsys activity activities | grep ResumedActivity`——`com.android.vpndialogs/.ConfirmDialog` 在前台即弹窗出现；
- GGR VPN 网络判定：`adb shell dumpsys connectivity | grep "OwnerUid: 10123"`（10123 为 GGR uid，可用 `pm list packages -U` 对照）；
- DPM 对照：`adb shell dumpsys device_policy | grep -E "mAlwaysOn"`、`settings get secure always_on_vpn_app/always_on_vpn_lockdown`；
- 注意：`send_test_command.sh` 的 lockdown 参数按脚本 value 类型化规则传布尔（`lockdown=false`）；直接 adb 手写 JSON 时注意远端 shell 会剥离双引号导致 JSON 解析失败，一律使用脚本。

## 6. 真机执行记录（2026-08-13）

> 执行方式：testapp IPC（`send_test_command.sh`），全部用例在最终构建版本（含 AppOps 授权与回滚修正）复跑确认；Launcher 重装后 testapp 持有旧 binder，先 root `kill` testapp 进程再执行。

| 用例编号 | 结果 | 实际观测 |
|---|---|---|
| TC-VC-01 | ✅ | `{success:true, vpnPreparedSet:true, appOpsGranted:true, appOpsReadBack:0, packageName:com.syriusrobotics.platform.launcher}`；`vpn_prepared`=GGR；`ACTIVATE_VPN: allow` |
| TC-VC-02 | ✅ | force-stop 后启动 GGR → ResumedActivity=`com.syriusrobotics.platform.launcher/.MainActivity`（无 ConfirmDialog）、`OwnerUid: 10123` 计数=1（VPN CONNECTED）、ACTIVATE_VPN=allow（time 刷新于连接时刻） |
| TC-VC-03 | ✅ | `GetVpnPreparedForPackageName`=`com.syriusrobotics.platform.launcher`；`GetVpnPreparedLocal`（testapp 进程直读）同值 |
| TC-VC-04 | ✅ | `{"RESULT":"missing parameter: packageName"}` |
| TC-VC-05 | ✅ | `{success:false, appOpsGranted:false, appOpsReadBack:-1, vpnPreparedSet:true, vpnPreparedRolledBack:true}`；`vpn_prepared` 保持 null |
| TC-VC-06 | ✅ | `{success:true, clearedPackage:com.syriusrobotics.platform.launcher, vpnPreparedCleared:true}`；键=null；ACTIVATE_VPN 复位 `default`（VPN 连接保持，未被拆除） |
| TC-VC-07 | ✅ | 清除后 force-stop GGR 再启动 → ResumedActivity=`com.android.vpndialogs/.ConfirmDialog`（弹窗复现） |
| TC-VC-08 | ✅ | 无授权时清除 → `{success:true, clearedPackage:null, vpnPreparedCleared:true}`（幂等） |
| TC-VC-09 | ✅ | `GetAlwaysOnVpn` → `{"RESULT":null}` |
| TC-VC-10 | ✅ | GGR force-stop（tun0 计数=0）后 `SetAlwaysOnVpn lockdown=false` → RESULT=true；`mAlwaysOnVpnPackage`=GGR；6 秒内 `OwnerUid: 10123` 计数=1（框架自动拉起，ResumedActivity 仍非 GGR）；ACTIVATE_VPN 自动 allow |
| TC-VC-11 | ✅ | `GetAlwaysOnVpn` → `com.syriusrobotics.platform.launcher` |
| TC-VC-12 | ✅ | lockdown=true → `mAlwaysOnVpnLockdown`=true、`always_on_vpn_lockdown`=1 |
| TC-VC-13 | ✅ | `ClearAlwaysOnVpn` → mAlwaysOn 双字段回 null/false、`OwnerUid: 10123` 计数=0（框架拆除）、ACTIVATE_VPN 复位 default |
| TC-VC-14 | ✅ | `{"RESULT":"missing parameter: packageName"}` |

**附加记录**：① 弹窗基线（负向对照）在未授权状态下实测 `com.android.vpndialogs/.ConfirmDialog` 前台可见（用户报告的症状复现）；② 开发期修复：初版 `SetVpnPreparedFor` 未安装包时只失败不回滚，终版回滚 `vpn_prepared` 写入（TC-VC-05 核验）；③ Launcher 重装后 testapp AIDL 旧 binder 失效（RESULT:null、Launcher 无日志），root kill testapp 进程后恢复——重装 Launcher 后的标准操作；④ `send_test_command.sh` 路径正常，直接 adb 手写含双引号 JSON 会被远端 shell 剥离引号导致 `Value packageName of type java.lang.String cannot be converted to JSONObject`——一律用脚本传参。
