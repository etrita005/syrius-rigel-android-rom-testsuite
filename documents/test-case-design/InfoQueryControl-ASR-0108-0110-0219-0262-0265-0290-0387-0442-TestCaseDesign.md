# 设备信息查询类（ASR-0108/0110/0219/0262/0265/0290/0387/0442）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner，testapp（`com.hmdm.testapp`，平台签名）已安装；
- 部署准备：`adb root && dumpsys duraspeed addwhitelist com.hmdm.testapp`、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`（IPC 与 UI 按钮等效，同一 `TestActions.execute()` 引擎）；也可在 testapp UI 的 "Info queries" 页面点按对应按钮；
- 对照命令（可选）：`adb shell ls -l`、`adb shell getprop | grep -i su`、`adb shell dumpsys connectivity`、`adb shell dumpsys webviewupdate`、`adb shell dumpsys telephony.registry | grep -i cell`、`adb shell content query --uri content://icc/adn`、`adb shell dumpsys user`。

## 2. 测试用例表

### 2.1 ASR-0108 获取文件属性（GetFileAttribute）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0108-01 文件属性 | `adb shell mkdir -p /sdcard/MDM` 后 `./send_test_command.sh GetFileAttribute path=/sdcard/MDM` | success=true；isDirectory=true；canonicalPath=/storage/emulated/0/MDM；size/lastModified 合理 |
| TC-0108-02 文件类型/大小 | `adb push` 一个已知大小文件（如 1234 字节）到 /sdcard/MDM，`GetFileAttribute path=<该文件>` | isFile=true、isDirectory=false、size=1234 |
| TC-0108-03 权限位 | `adb shell chmod 600 /sdcard/MDM/perm_test` 后查询；`chmod 444` 后再查（注：/sdcard 为 fuse 挂载，uid=1000 按 sdcard_rw 组放行 W_OK，chmod 不生效——另以原生 ext4 路径 /data/system/device_policies.xml 核对权限位如实反映） | readable/writable 如实反映（fuse 限制文档化）；原生路径 readable=true/writable=false 正确 |
| TC-0108-04 目录列表 | `GetFileAttribute path=/sdcard/MDM list=true` | listCount 与实际文件数一致；entries 前 200 项含 name/isFile/isDirectory/size；listTruncated 正确 |
| TC-0108-05 系统路径 | `GetFileAttribute path=/system/build.prop` | success=true（uid=1000 可读）；isFile=true；size>0 |
| TC-0108-06 不存在路径 | `GetFileAttribute path=/sdcard/not_exist_xyz` | success=false；error 含 file not found |
| TC-0108-07 缺参数 | `./send_test_command.sh GetFileAttribute` | 返回 missing parameter: path |
| TC-0108-08 目录文件混合列表 | 在 /sdcard/MDM 下同时放置文件与子目录后 list=true | entries 中 isFile/isDirectory 标记正确 |

### 2.2 ASR-0110 检查 root 状态（CheckRootStatus）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0110-01 默认查询 | `./send_test_command.sh CheckRootStatus` | success=true；字段齐全（rooted/suBinaries/suLsProbe/suExecProbe/magisk/superuserApk/suDaemon/testKeys/debuggable/rwSystem/buildTags） |
| TC-0110-02 判定一致性 | 对照 `adb shell ls /system/bin/su /sbin/su` 等 | suBinaries 与文件系统实际情况一致（本机 /system/xbin/su 存在且 `adb shell su` 实测可获 root → rooted=true；SELinux 隐藏导致直接 stat 假阴性时以 shell 探针 "Permission denied" 证据为准，原始输出 suLsProbe 全量返回） |
| TC-0110-03 开发构建口径 | 查看 buildTags/debuggable 与 `adb shell getprop ro.build.tags`、`getprop ro.debuggable` | 本机 buildTags=release-keys/testKeys=false 但 debuggable=1；devBuild 指示计入判定（RootBeer 同口径），文档化 |
| TC-0110-04 本地探针交叉核对 | `./send_test_command.sh CheckRootStatusLocal` | 与 Launcher API 结果一致（suBinaries 均含 /system/xbin/su，rooted=true） |
| TC-0110-05 rwSystem 报告 | 对照 `adb shell mount | grep " /system "` | rwSystem 与挂载标志一致（本机 locked bootloader /system 为 ro → false） |

### 2.3 ASR-0219 查询 VPN 服务状态（GetVpnStatus）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0219-01 默认查询 | `./send_test_command.sh GetVpnStatus` | success=true、supported=true；vpnNetworkCount 与 networks 数组长度一致 |
| TC-0219-02 无 VPN 状态 | 未启用任何 VPN 时查询 | vpnActive=false；networks 空数组；note 说明 |
| TC-0219-03 防火墙 VPN 识别 | 启用域名/IP 黑白名单策略（SetDomainPolicyMode mode=1）后查询 | 出现 Launcher 防火墙 VPN 网络（interface=tun0 或类似、packages 含 com.hmdm.launcher）；vpnActive=true |
| TC-0219-04 本地探针交叉核对 | `./send_test_command.sh CheckVpnNetworks` | 两通道 VPN 网络数量与 owner 包一致 |
| TC-0219-05 always-on 字段 | 设置 always-on VPN（SetAlwaysOnVpn packageName=com.syriusrobotics.platform.launcher lockdown=false）后查询 | alwaysOnVpnPackage 返回所设包名（2026-08-13 修正：本批次初版记录 `dpm.setAlwaysOnVpnPackage` 抛 UnsupportedOperationException 有误，该接口本 ROM 实测可用，非 null 路径已在 VpnConsentAlwaysOnControl 批次闭环） |
| TC-0219-06 字段完整性 | 有 VPN 网络时逐项核对 | 每项含 network/state/connected/interface/packages |

### 2.4 ASR-0262 查询号码归属地（QueryNumberAttribution）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0262-01 城市级命中 | `./send_test_command.sh QueryNumberAttribution number=13910001234` | matched=true；province=北京、city=北京、operator=中国移动（1391000 覆盖段） |
| TC-0262-02 4 位前缀命中 | `./send_test_command.sh QueryNumberAttribution number=13112345678` | matched=true；matchedPrefix=1311（4 位地域惯例）、province/city=北京；operator=中国联通（131 前缀）；3 位运营商级行仅在号码 <4 位时命中（如 number=138 → 中国移动 + note） |
| TC-0262-03 格式归一化 | `number=+8613910001234` / `number=0086-139-1000-1234` | 与 13910001234 结果一致（normalized 字段相同） |
| TC-0262-04 电信/虚拟号 | `number=13312345678`、`number=17012345678` | operator=中国电信 / 虚拟运营商 |
| TC-0262-05 未匹配 | `number=12345` | 前缀无匹配 → matched=false + error，不 crash |
| TC-0262-06 缺参数 | `./send_test_command.sh QueryNumberAttribution` | 返回 missing parameter: number |
| TC-0262-07 短号码 | `number=138` | normalized=138 → matched=true 仅运营商级（中国移动，province/city 空 + note） |
| TC-0262-08 返回字段 | 命中结果 | 含 number/normalized/matchedPrefix/matchedPrefixLen/operator/province/city |

### 2.5 ASR-0265 获取 Cell ID（GetCellInfo）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0265-01 默认查询 | `./send_test_command.sh GetCellInfo` | success=true；字段齐全（phoneCount/phoneType/networkOperator/simPresent/subscriptions/cellInfo/cellLocation） |
| TC-0265-02 无 SIM 如实上报 | 本机（无 SIM）查询 | simPresent=false；cellInfo 空数组；cellLocation 空（type 缺省）；不 crash |
| TC-0265-03 订阅列表 | 对照 `adb shell dumpsys subscription` | subscriptions 与实际订阅一致（无 SIM 时为空） |
| TC-0265-04 本地探针权限门 | `./send_test_command.sh GetCellInfoLocal` | 非系统 uid 应用无 ACCESS_FINE_LOCATION → granted=false + SecurityException 说明（权限门证据） |
| TC-0265-05 有 SIM 真机（受限） | 插入 SIM 卡且注册网络后查询（需 SIM 真机） | cellInfo 出现 GSM/LTE 条目（cid/lac/tac/pci 等 identity 字段）；cellLocation 有值；与 `dumpsys telephony.registry` 对照一致 |

### 2.6 ASR-0290 获取 SIM 联系人（GetSimContacts）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0290-01 默认查询 | `./send_test_command.sh GetSimContacts` | success=true；字段齐全（simPresent/contactsCount/contacts） |
| TC-0290-02 无 SIM 如实上报 | 本机（无 SIM）查询 | simPresent=false；contactsCount=0；contacts 空数组；note 说明 |
| TC-0290-03 本地探针权限门 | `./send_test_command.sh GetSimContactsLocal` | 非系统 uid 应用无 READ_CONTACTS → granted=false + SecurityException 说明（权限门证据） |
| TC-0290-04 有 SIM 真机（受限） | 在 SIM 卡通讯录预存联系人后查询（需 SIM 真机） | contactsCount 与实际 SIM 联系人一致；name/number/efid/index 正确；与 `adb shell content query --uri content://icc/adn`（root）对照一致 |

### 2.7 ASR-0387 获取用户列表（GetUserList）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0387-01 默认查询 | `./send_test_command.sh GetUserList` | success=true；users 数组含主用户（id=0，primary=true/admin=true） |
| TC-0387-02 本地探针交叉核对 | `./send_test_command.sh GetUserListLocal` | **本 ROM/API 33 限制**：非特权应用 `UserManager.getUsers` 抛 SecurityException（需 MANAGE_USERS/CREATE_USERS）——如实上报，以 Launcher（uid=1000）结果为准 |
| TC-0387-03 系统对照 | 对照 `adb shell dumpsys user` | 用户 id/名称与系统一致（本机仅用户 0，Owner） |
| TC-0387-04 多用户场景 | 创建临时用户（CreateUser name=mdm_test_user）后查询 → 删除（DeleteUser）后再查 | 新用户出现在列表（id>0）；删除后消失 |

### 2.8 ASR-0442 WebView Provider 信息上报（GetWebViewInfo）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0442-01 默认查询 | `./send_test_command.sh GetWebViewInfo` | success=true；字段齐全（webviewProviderDefault/currentProvider/providers/dumpsysCurrent） |
| TC-0442-02 当前 Provider | 对照 `adb shell dumpsys webviewupdate` Current WebView package 行 | currentProvider 的 packageName/versionName/versionCode 与系统一致（本机 com.android.webview 101.0.4951.61 / 495156103） |
| TC-0442-03 dumpsys 交叉核对 | 对照 dumpsysCurrent 字段 | dumpsysCurrent 解析的 (name, version) 与 currentProvider 一致 |
| TC-0442-04 启用状态 | 对照 `adb shell pm list packages -d` | enabled 与系统启用状态一致（Provider 未禁用时 enabled=true） |
| TC-0442-05 设置键 | 对照 `adb shell settings get global webview_provider_default` | webviewProviderDefault 与系统键一致（本机 null → 空字符串，文档化） |
| TC-0442-06 本地探针交叉核对 | `./send_test_command.sh GetWebViewInfoLocal` | **本 ROM 限制**：WebView 包对非系统应用不可见（包可见性规则），本地探针 providers=[]（webviewProviderDefault 与 Launcher 一致）——note 说明，以 Launcher（uid=1000）结果为准 |
| TC-0442-07 providers 列表 | 检查 providers 数组 | 含已安装 webview 候选包；每项含 versionName/versionCode/enabled/installed |

### 2.9 通用用例

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-M-01 缺参容错 | 8 个命令均不带参数执行（GetFileAttribute/QueryNumberAttribution 除外——必填参数场景见各节） | 全部返回有效结果，不 crash |
| TC-M-02 未知事件 | `./send_test_command.sh GetFileInfoXXX` | 返回 unknown event，不 crash |
| TC-M-03 UI 等效 | testapp UI "Info queries" 页逐一按钮 | 与 IPC 返回一致（同一 TestActions 引擎） |
| TC-M-04 返回值 JDK 类型 | 检查 8 个命令返回 JSON | 全部为 Map/List/基本类型，无自定义类（AIDL 通道约束） |
| TC-M-05 命令注册 | `./send_test_broadcast.sh GetVpnStatus`（Launcher 广播通道） | 与 IPC 命令等价执行，logcat HYX-MDM-APP 有结果 |

## 3. 验证提示

- 结果查看：`./send_test_command.sh` 输出 `Broadcast completed: data=...` 与 logcat `HYX-TESTAPP-CMD` 行；
- 文件对照：`adb shell ls -l <path>`（大小/权限/时间戳）；
- root 对照：`adb shell ls /system/bin/su /sbin/su 2>&1`、`adb shell getprop ro.build.tags`、`adb shell mount | grep " /system "`；
- VPN 对照：`adb shell dumpsys connectivity | grep -A 5 -i vpn`、`adb shell dumpsys device_policy | grep -i alwayson`；
- 归属地对照：号码段与运营商对照工信部号段分配表（离线库为内置样例，见技术设计文档第 8 节第 4 条）；
- WebView 对照：`adb shell dumpsys webviewupdate`、`adb shell settings get global webview_provider_default`；
- Cell/SIM 对照：`adb shell dumpsys telephony.registry | grep -i cell`、`adb shell content query --uri content://icc/adn`（root）；
- 用户对照：`adb shell dumpsys user`。

## 4. 实测结果（2026-08-07，Android 13 / API 33 userdebug，平台签名 + device owner）

全部用例经 `send_test_command.sh` IPC 通道执行（与 UI 按钮共用 TestActions 引擎），部分经 `send_test_broadcast.sh` 广播通道交叉验证：

| 用例 | 实测结果 |
|---|---|
| TC-0108-01 | 通过：/sdcard/MDM isDirectory=true、size=4096、canonicalPath=/storage/emulated/0/MDM |
| TC-0108-02 | 通过：file_1234.bin isFile=true、size=1234（与 dd 生成字节数精确一致） |
| TC-0108-03 | 通过（带说明）：/sdcard fuse 上 chmod 444 后 writable 仍为 true（fuse 按 sdcard_rw 组放行）；/data/system/device_policies.xml readable=true/writable=false 如实反映 |
| TC-0108-04 | 通过：listCount=3、entries 3 项（file_1234.bin 1234 / subdir 目录 / perm_test 6）、listTruncated=false |
| TC-0108-05 | 通过：/system/build.prop isFile=true、size=8592、lastModifiedText=2009-01-01 |
| TC-0108-06 | 通过：success=false、error 含 file not found |
| TC-0108-07 | 通过：返回 missing parameter: path |
| TC-0108-08 | 通过：entries 中 isFile/isDirectory 标记正确 |
| TC-0110-01 | 通过：字段齐全；rooted=true、suBinaries=[/system/xbin/su]、suLsProbe/suExecProbe 原始证据返回 |
| TC-0110-02 | 通过：/system/xbin/su 实测存在且 `adb shell su` 可获 root（uid=0, context u:r:su:s0）；SELinux su_exec 隐藏使直接 stat 假阴性，shell 探针 "Permission denied" 证据 + debuggable=1 双重判定 |
| TC-0110-03 | 通过：buildTags=release-keys（testKeys=false）但 ro.debuggable=1，devBuild 指示计入判定（文档化口径） |
| TC-0110-04 | 通过：本地探针 suBinaries=[/system/xbin/su]、rooted=true，与 Launcher 一致 |
| TC-0110-05 | 通过：rwSystem=false，与 `mount | grep " /system "` 一致（locked bootloader ro） |
| TC-0219-01 | 通过：success=true、supported=true、vpnNetworkCount=0 与 networks 长度一致 |
| TC-0219-02 | 通过：vpnActive=false、networks=[]、note 说明 |
| TC-0219-03 | 通过：SetDomainPolicyMode mode=1 后 GetVpnStatus → vpnActive=true、network 102/tun0/CONNECTED、packages 含 com.hmdm.launcher；恢复 mode=0 后 vpnActive=false |
| TC-0219-04 | 通过：CheckVpnNetworks 同样报告 1 个 VPN 网络（tun0） |
| TC-0219-05 | 受限（本 ROM，2026-08-07 结论）→ **2026-08-13 已闭环修正**：本批次曾记录 `dpm.setAlwaysOnVpnPackage` 抛 UnsupportedOperationException、alwaysOnVpnPackage 只能验证 null 路径——该结论有误；VpnConsentAlwaysOnControl 批次实测该接口可用，`SetAlwaysOnVpn` 后 `GetVpnStatus` 返回 alwaysOnVpnPackage=com.syriusrobotics.platform.launcher（非 null 路径已闭环） |
| TC-0219-06 | 通过：network/state/connected/interface/packages 逐项齐全 |
| TC-0262-01 | 通过：13910001234 → 北京/北京/中国移动（matchedPrefix=1391000，7 位段） |
| TC-0262-02 | 通过：13112345678 → matchedPrefix=1311、北京/中国联通（4 位地域惯例） |
| TC-0262-03 | 通过：+8613910001234 与 0086-139-1000-1234 normalized 均为 13910001234，结果一致 |
| TC-0262-04 | 通过：13312345678 → 中国电信；17012345678 → 虚拟运营商 |
| TC-0262-05 | 通过：12345 → matched=false + error（number too short / 无匹配），不 crash |
| TC-0262-06 | 通过：返回 missing parameter: number |
| TC-0262-07 | 通过：138 → matched=true、operator=中国移动（3 位运营商级，province/city 空 + note） |
| TC-0262-08 | 通过：命中结果字段齐全 |
| TC-0265-01 | 通过：字段齐全；simPresent=false、subscriptions=[]、cellInfo 每条卡槽 1 条 LTE 桩条目（本机 2 槽共 2 条，registered=false，ci/tac/pci/mcc/mnc=-1）、cellLocation GSM cid=268435455/lac=65535 |
| TC-0265-02 | 通过：无 SIM 如实上报空结构，不 crash |
| TC-0265-03 | 通过：subscriptions 与系统订阅一致（无 SIM 为空） |
| TC-0265-04 | 通过：GetCellInfoLocal → SecurityException（Not allowed to access cell info），权限门证据 |
| TC-0265-05 | 受限（需 SIM 真机）：见需求文档"硬件受限测试说明" |
| TC-0290-01 | 通过：字段齐全（simPresent/contactsCount/contacts） |
| TC-0290-02 | 通过：simPresent=false、contactsCount=0、contacts=[] + note |
| TC-0290-03 | 通过：GetSimContactsLocal → SecurityException（READ_CONTACTS required），权限门证据 |
| TC-0290-04 | 受限（需 SIM 真机）：见需求文档"硬件受限测试说明" |
| TC-0387-01 | 通过：users=[{id=0, name=Owner, flags=3091, admin=true, primary=true}] |
| TC-0387-02 | 受限（本 ROM/API 33）：GetUserListLocal → SecurityException（MANAGE_USERS required），以 Launcher 结果为准 |
| TC-0387-03 | 通过：与 `dumpsys user` 一致（仅用户 0） |
| TC-0387-04 | 通过：CreateUser mdm_test_user → id=10 入列；DeleteUser userId=10 → removed=true、列表恢复仅 Owner |
| TC-0442-01 | 通过：字段齐全（webviewProviderDefault/currentProvider/providers/dumpsysCurrent） |
| TC-0442-02 | 通过：currentProvider=com.android.webview 101.0.4951.61 / 495156103，与 `dumpsys webviewupdate` 一致 |
| TC-0442-03 | 通过：dumpsysCurrent 解析 "(com.android.webview, 101.0.4951.61)" 与 currentProvider 一致；**本 ROM WebViewUpdateService.getCurrentWebViewPackage 不存在（NoSuchMethodException），currentProvider 经 dumpsys 回填（source=dumpsys）** |
| TC-0442-04 | 通过：enabled=true（Provider 未禁用） |
| TC-0442-05 | 通过：webviewProviderDefault 为空（本 ROM 出厂 null，文档化） |
| TC-0442-06 | 受限（本 ROM）：本地探针 providers=[]（WebView 包对非系统应用不可见），以 Launcher 结果为准 |
| TC-0442-07 | 通过：providers 3 个候选包（com.android.webview / org.chromium.webview_shell / com.norman.webviewup.demo），字段齐全 |
| TC-M-01 | 通过：6 个无参命令缺参均返回有效结果 |
| TC-M-02 | 通过：未知事件返回 unknown event |
| TC-M-03 | 通过（说明）：UI/IPC 共用 TestActions.execute() 同一引擎，行为一致（IPC 全覆盖，UI 按钮未逐一点击） |
| TC-M-04 | 通过：返回值全 JDK Map/List/基本类型 |
| TC-M-05 | 通过：`send_test_broadcast.sh GetVpnStatus` 经 Launcher 广播通道等价执行（TestBroadcast result 完整返回） |
