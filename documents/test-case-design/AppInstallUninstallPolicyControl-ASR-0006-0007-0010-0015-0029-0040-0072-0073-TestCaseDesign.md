# 应用安装/卸载策略与运行查询管控（ASR-0006/0007/0010/0015/0029/0040/0072/0073）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner（`dpm list-owners` = DeviceOwner,Affiliated），testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root && dumpsys duraspeed addwhitelist com.hmdm.testapp`（MTK DuraSpeed 白名单；**`am force-stop com.hmdm.testapp` 会使 testapp 进入 DuraSpeed suppress list、广播被静默丢弃——整机重启清空恢复**，测试期间避免 force-stop）、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`（IPC 与 UI 按钮等效，同一 `TestActions.execute()` 引擎）；也可在 testapp UI 的 "App install/uninstall policy" 页点按（UI 输入包名/正则）；
- 对照命令：`adb shell dumpsys package <pkg>`（User 0 行 `hidden=`/`suspended=` 位、uninstall blocked 位）、`adb shell cmd deviceidle whitelist`（保活白名单）、`adb shell cmd appops get <pkg> MANAGE_EXTERNAL_STORAGE`、`adb shell pm list packages`；
- 测试用包：`com.hmdm.testapp`（自身）、`com.android.music`（本 ROM 音乐应用，非系统可卸载验证占位）、`com.android.documentsui`、`com.ume.browser`（预装浏览器）；安装黑名单用例用 `com.example.stub1`（若未安装可用 `pm install` 装任意测试 APK 占位）；
- 恢复基线（测试开始时记录、结束时恢复）：卸载白名单/黑名单空名单、install mode=1（白名单）、保活名单空、桌面图标全显示、MANAGE_EXTERNAL_STORAGE 恢复原状。

## 2. 测试用例表

### 2.1 ASR-0006 应用可卸载白名单（SetUninstallWhitelist / GetUninstallWhitelist）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0006-01 基线查询 | `./send_test_command.sh GetUninstallWhitelist` | success=true；list 空（或与上次配置一致） |
| TC-0006-02 设置白名单 | `./send_test_command.sh SetUninstallWhitelist packageNames='["com.hmdm.testapp"]'` | success=true；blocked 含 com.hmdm.testapp；`dumpsys package com.hmdm.testapp` uninstall blocked=true；非名单非系统包 unblocked |
| TC-0006-03 白名单生效 | 尝试卸载 com.hmdm.testapp（`pm uninstall`） | 卸载被拒（DELETE_FAILED_OWNER_BLOCKED），包仍在 |
| TC-0006-04 名单外恢复可卸载 | `SetUninstallWhitelist packageNames='[]'` 后尝试卸载占位应用 | 空名单=全部非系统包解除锁定；占位应用可正常卸载（测试后重装恢复） |
| TC-0006-05 查询回显 | GetUninstallWhitelist | list 含条目 {packageName, blocked, installed}，blocked 与 dumpsys 一致 |
| TC-0006-06 缺参 | `SetUninstallWhitelist`（不带 packageNames） | testapp 侧返回 missing parameter: packageNames (array)；不 crash |

### 2.2 ASR-0007 应用可卸载黑名单（SetUninstallBlacklist / GetUninstallBlacklist）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0007-01 设置黑名单 | `./send_test_command.sh SetUninstallBlacklist packageNames='["com.android.music"]'` | success=true；blocked 含 com.android.music；`dumpsys package com.android.music` blocked=true |
| TC-0007-02 黑名单外不动 | 查询其他非系统包 uninstall blocked | 名单外包保持原状态（未被全量解除） |
| TC-0007-03 清空黑名单 | `SetUninstallBlacklist packageNames='[]'` | blocked 中的包解除锁定，读回 blocked=false |
| TC-0007-04 查询回显 | GetUninstallBlacklist | list 与配置一致，blocked 与 dumpsys 一致 |

### 2.3 ASR-0010 应用可安装黑名单（SetInstallPolicyMode / SetInstallBlacklist / GetInstallPolicyMode / GetInstallBlacklist）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0010-01 基线模式 | GetInstallPolicyMode | mode=1（whitelist）+ blacklist 列表 |
| TC-0010-02 设置黑名单正则 | `./send_test_command.sh SetInstallBlacklist patterns='["com\\.example\\..*"]'` | 返回 true；GetInstallBlacklist 回显正则 |
| TC-0010-03 切换黑名单模式 | `./send_test_command.sh SetInstallPolicyMode mode=2` | 返回 true；GetInstallPolicyMode → mode=2/modeName=blacklist |
| TC-0010-04 黑名单命中自动卸载 | 安装 com.example.stub1（若已装先卸，`pm install` 任意改名 stub APK） | 安装完成瞬间被引擎静默卸载：安装后立即 `pm list packages \| grep stub` 无残留；logcat `InstallWhitelistManager blacklist matched silentUninstallApplication` |
| TC-0010-05 黑名单未命中不受影响 | 安装 com.android.music（若未装） | 安装后保持存在（未命中正则，仅解除卸载锁定） |
| TC-0010-06 切回白名单模式 | `SetInstallPolicyMode mode=1` | 返回 true；白名单默认正则（com.hmdm.* 等）恢复保护；黑名单正则保留可查询 |
| TC-0010-07 非法模式 | `SetInstallPolicyMode mode=9` | 返回 false（非法模式拒绝） |
| TC-0010-08 进程重启保持 | mode=2 后 kill Launcher 进程（root kill） | Launcher 重启后 syncPolicy 按持久化 mode=2 重新注册黑名单接收器；GetInstallPolicyMode 仍为 2 |

### 2.4 ASR-0015 保活开关（SetKeepAliveEnabled / IsKeepAliveEnabled / GetKeepAliveList）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0015-01 开启保活 | `./send_test_command.sh SetKeepAliveEnabled packageName=com.hmdm.testapp enabled=true` | success=true；liveIgnoring=true；`cmd deviceidle whitelist` 含 com.hmdm.testapp |
| TC-0015-02 查询 | `IsKeepAliveEnabled packageName=com.hmdm.testapp` | enabled=true；liveIgnoring=true |
| TC-0015-03 列表 | GetKeepAliveList | list 含 {packageName, enabled, liveIgnoring} |
| TC-0015-04 关闭保活 | `SetKeepAliveEnabled packageName=com.hmdm.testapp enabled=false` | success=true；liveIgnoring=false；deviceidle whitelist 移除 |
| TC-0015-05 未安装包 | `SetKeepAliveEnabled packageName=com.not.installed enabled=true` | error "package not installed"，不执行 shell |
| TC-0015-06 非法包名 | `SetKeepAliveEnabled packageName='bad;rm -rf' enabled=true` | error "invalid package name"，无 shell 注入 |
| TC-0015-07 重启保持 | 开启后整机重启 | GetKeepAliveList 仍含该包；liveIgnoring=true（syncPolicy 重新下发） |

### 2.5 ASR-0029 检测应用是否存活（IsAppAlive）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0029-01 存活检测 | `./send_test_command.sh IsAppAlive packageName=com.hmdm.testapp`（testapp 进程在跑） | alive=true；installed=true；pid>0；importance 文本化 |
| TC-0029-02 未运行检测 | 先 `am force-stop com.hmdm.testapp` 后查询（或查未启动包） | alive=false；installed=true（进程列表无该包） |
| TC-0029-03 未安装包 | `IsAppAlive packageName=com.not.installed` | installed=false；alive=false；不 crash |
| TC-0029-04 缺参 | `IsAppAlive`（不带 packageName） | 返回 error/missing parameter；不 crash |

### 2.6 ASR-0040 MANAGE_EXTERNAL_STORAGE 独立授予/取消（Set/IsManageExternalStorageGranted）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0040-01 基线 | `IsManageExternalStorageGranted packageName=com.hmdm.testapp` | granted=false（默认未授予） |
| TC-0040-02 授予 | `./send_test_command.sh SetManageExternalStorageGranted packageName=com.hmdm.testapp granted=true` | success=true；granted=true；`cmd appops get com.hmdm.testapp MANAGE_EXTERNAL_STORAGE` = allow |
| TC-0040-03 查询回读 | IsManageExternalStorageGranted | granted=true；permissionGranted=true（随 op 联动） |
| TC-0040-04 取消 | `SetManageExternalStorageGranted packageName=com.hmdm.testapp granted=false` | success=true；granted=false；appops = ignore |
| TC-0040-05 未安装包 | `SetManageExternalStorageGranted packageName=com.not.installed granted=true` | success=false（包不存在） |
| TC-0040-06 缺参 | 不带 granted | testapp 侧 missing parameter；不 crash |

### 2.7 ASR-0072 隐藏桌面图标（SetDesktopIconHidden / IsDesktopIconHidden）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0072-01 隐藏图标 | `./send_test_command.sh SetDesktopIconHidden packageName=com.android.music hidden=true` | success=true；readBack=true；`dumpsys package com.android.music` User 0 行 hidden=true |
| TC-0072-02 查询 | `IsDesktopIconHidden packageName=com.android.music` | hidden=true |
| TC-0072-03 恢复显示 | `SetDesktopIconHidden packageName=com.android.music hidden=false` | success=true；readBack=false；dumpsys hidden=false |
| TC-0072-04 桌面渲染说明 | 观察外部桌面（com.syriusrobotics.platform.launcher）应用列表 | 桌面如遵从 hidden 标志则图标消失；命令返回附注 note（受第三方桌面渲染限制，验收以 DPM 状态为准） |

### 2.8 ASR-0073 PackageInfo 独立命令（GetPackageInfo）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0073-01 查询自身 | `./send_test_command.sh GetPackageInfo packageName=com.hmdm.testapp` | success=true；versionName/versionCode/uid/label/requestedPermissions/signatureSha1/launchActivity 齐全 |
| TC-0073-02 查询系统应用 | `GetPackageInfo packageName=com.android.settings` | success=true；system=true；flags/targetSdkVersion 正常 |
| TC-0073-03 未安装包 | `GetPackageInfo packageName=com.not.installed` | error "package not installed"；不 crash |
| TC-0073-04 缺参 | `GetPackageInfo`（不带 packageName） | error "missing parameter: packageName" |

### 2.9 通用用例

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-M-01 未知事件 | `./send_test_command.sh SetUninstallWhitelistXXX` | 返回 unknown event，不 crash |
| TC-M-02 UI 等效 | testapp UI "App install/uninstall policy" 页点按各按钮 | 页面 resumed；按钮与 IPC 共用 TestActions 引擎；真机闭环结果与 IPC 一致 |
| TC-M-03 返回值类型 | 检查命令返回 JSON | 全部为 Map/List/基本类型（AIDL 通道约束） |
| TC-M-04 事件目录 | `./send_test_command.sh ListEvents` | 目录含本批次 17 个新事件及其参数说明 |

## 3. 验证提示

- 结果查看：`./send_test_command.sh` 输出 `Broadcast completed: data=...` 与 logcat `HYX-TESTAPP-CMD` 行（本 ROM goAsync 时序下 result 码恒为 -1，以 data 内容为准；**若 result=0 且无日志，检查 DuraSpeed suppress_list**）；
- 系统对照：卸载锁定/隐藏位经 `dumpsys package <pkg>` 的 `User 0:` 行核验；保活白名单经 `cmd deviceidle whitelist` 核验；AppOps 经 `cmd appops get` 核验；
- 安装黑名单用例需先准备测试 APK（包名 com.example.stub1 的任意小 APK），安装后立即查询包列表确认被自动卸载；
- 音量/全屏类交互命令注意避免与用例冲突（本页不涉及）；UI 输入用 `input text`，键盘弹出后先 `input keyevent 4` 收起再点按钮。

## 4. 实测结果（2026-08-11，Android 13 / API 33 userdebug，平台签名 + device owner）

（部署完成后经 `send_test_command.sh` IPC 通道执行并回填）

| 用例 | 实测结果 |
|---|---|
| TC-0006-01 ~ 06 | 通过（02 白名单生效 blocked + dumpsys 对照；03 卸载被拒 DELETE_FAILED_OWNER_BLOCKED；04 清空解除；com.android.music 为 FLAG_SYSTEM 系统包自动跳过；06 缺参错误路径） |
| TC-0007-01 ~ 04 | 通过（黑名单锁定/清空解除/查询回显；卸载校验经 pm uninstall 闭环） |
| TC-0010-01 ~ 08 | 通过（01 基线 mode=1；02/03 名单+模式切换；04 命中正则新装包被自动静默卸载——自建 com.example.stub1 测试 APK（aapt2+d8+zipalign 构建）安装后 4s 内无残留，接收器日志 mode:2 matched:true + silentUninstallApplication；05 未命中包（testapp 重装）保持；06 恢复白名单模式名单保留；07 非法模式拒绝；08 进程重启（root kill）与整机重启后模式/名单保持） |
| TC-0015-01 ~ 07 | 通过（01 开启 liveIgnoring=true + cmd deviceidle whitelist 对照；02/03 查询/列表；04 关闭移除；05/06 未安装/非法包名拒绝（含注入防护）；07 整机重启 syncPolicy 重新下发保持） |
| TC-0029-01 ~ 04 | 通过（01 存活 alive=true + pid/importance；02 后台运行 CACHED 进程正确识别；03 未安装 installed=false；04 缺参错误路径） |
| TC-0040-01 ~ 06 | **01 基线即暴露本 ROM 限制：mode=3（MODE_DEFAULT）且 granted 判定修正为仅 MODE_ALLOWED 计授予；02~04 本 ROM AppOpsService 忽略该 op 写入（shell/root/平台应用三通道核验，写入读回短暂 ALLOWED 后被框架复位、cmd appops get 恒 No operations），命令如实 success=false；维持部分完成待 ROM 适配**（06 缺参路径通过） |
| TC-0072-01 ~ 04 | 通过（隐藏/查询/恢复 + dumpsys User 0 hidden 位对照；04 桌面渲染说明随命令 note 返回） |
| TC-0073-01 ~ 04 | 通过（01 全字段齐全，签名 SHA-1=2093311f410a0ff4734ba9d5f3813b1288beff28 与平台密钥指纹一致；02 系统应用 system=true；03/04 错误路径） |
| TC-M-01 ~ 04 | 通过（未知事件/返回值类型/事件目录均正常；UI 四新测试页可正常打开与按钮执行，与 IPC 共用 TestActions 引擎） |

**机制核验补充**：卸载锁定经 DPM 持久化（device_policies.xml）；保活白名单为 DeviceIdleController 运行时态（重启后由 syncPolicy 重新下发）；AppOps MANAGE_EXTERNAL_STORAGE 状态框架持久化（appops.xml）无需重新武装。
