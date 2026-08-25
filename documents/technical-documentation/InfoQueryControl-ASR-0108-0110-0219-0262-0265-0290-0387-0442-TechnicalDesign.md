# 设备信息查询类（ASR-0108/0110/0219/0262/0265/0290/0387/0442）技术设计文档

## 1. 需求概述

| 需求 ID | 子功能项 | 需求语义 |
|---|---|---|
| ASR-0108 | 获取文件属性 | 获取指定路径文件/目录的属性（存在性、类型、大小、时间戳、权限位等） |
| ASR-0110 | 检查 root 状态 | 检查设备是否已 root（su 二进制、Magisk、Superuser 应用等静态指标） |
| ASR-0219 | 查询 VPN 服务状态 | 查询当前 VPN 服务状态（ConnectivityManager：活动 VPN 网络、状态、所属应用） |
| ASR-0262 | 查询号码归属地 | 离线查询手机号码的归属地（省份/城市/运营商） |
| ASR-0265 | 获取 Cell ID | 获取当前小区信息（TelephonyManager：CellInfo/CellLocation 的 CID/LAC/TAC 等） |
| ASR-0290 | 获取 SIM 联系人 | 获取 SIM 卡上的联系人（IccProvider `content://icc/adn`） |
| ASR-0387 | 获取用户列表 | 获取系统多用户列表（`UserManager.getUsers`） |
| ASR-0442 | WebView Provider 信息上报 | 查询并上报当前 WebView Provider 的包名、versionName、versionCode 和启用状态 |

**归属**：全部 8 项均为「Launcher（MDM）」——公开 Android SDK（File / TelephonyManager / ConnectivityManager / ContentResolver / UserManager / PackageManager / Settings）即可完成，无 ROM 侧改动、无隐藏 API 依赖（ASR-0265/0290 因编译 SDK 裁剪与框架差异使用反射读取，均为运行期可用性兜底而非权限依赖）。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名（`sharedUserId=android.uid.system`，uid=1000）+ device owner 部署；bootloader 锁定，不可改动 system 分区。

## 2. 技术选型与可行性核验

### 2.1 各子功能数据源选型

| 子功能 | 方案 | 依赖/权限 | 结论 |
|---|---|---|---|
| ASR-0108 文件属性 | **java.io.File API**（exists/isFile/isDirectory/length/lastModified/canRead/canWrite/canExecute/isHidden + canonical/symlink 判定），可选目录列表（上限 200 项） | 无额外权限（uid=1000 可读任意路径） | 采用 |
| ASR-0110 root 状态 | **静态指标组合**：su 二进制路径枚举（9 处）、Magisk（`/data/adb/magisk`）、Superuser APK、SuperSU init 守护脚本、`Build.TAGS`（test-keys）、`/proc/mounts` 中 /system 是否 rw | 无额外权限 | 采用 |
| ASR-0219 VPN 状态 | **ConnectivityManager.getAllNetworks + NetworkCapabilities.hasTransport(TRANSPORT_VPN)**（状态/接口/所属应用 uid→包名，uid 列表经反射读取）+ device owner 的 `dpm.getAlwaysOnVpnPackage` | ACCESS_NETWORK_STATE（已声明）；getUids 为 @SystemApi 反射兜底 | 采用 |
| ASR-0262 号码归属地 | **内置离线数据库** `assets/phone_attribution.csv`（647 行：3 位前缀运营商行 + 4 位前缀地域惯例行 + 常用 7 位段覆盖），号码归一化后按最长前缀（7→3 位）匹配 | 无网络、无权限 | 采用 |
| ASR-0265 Cell ID | **TelephonyManager.getAllCellInfo()**（按类型提取 GSM/LTE/WCDMA/TDSCDMA/NR/CDMA 的 identity 字段）+ **getCellLocation()**（GSM cid/lac/psc，CDMA bsId/nId/sId）+ 活动订阅与网络运营商信息 | READ_PHONE_STATE / READ_PRIVILEGED_PHONE_STATE / ACCESS_FINE_LOCATION（manifest 既有声明，uid=1000 自动授予） | 采用 |
| ASR-0290 SIM 联系人 | **IccProvider `content://icc/adn`**（ContentResolver.query，读 name/number/emails/anr/efid/index） | READ_CONTACTS（本批次 manifest 新增声明；uid=1000 系统权限持有） | 采用 |
| ASR-0387 用户列表 | **`UserManager.getUsers`**（反射读取，本 ROM UserInfo 为公共字段 id/name/flags + isAdmin/isGuest/isPrimary 方法） | MANAGE_USERS（既有声明） | 采用（GetUserList 命令 2026-08-06 批次已实现，本批次正式纳入需求验收） |
| ASR-0442 WebView 上报 | **Settings.Global `webview_provider_default` + `WebViewUpdateService.getCurrentWebViewPackage`（反射，@SystemApi）+ PackageManager**（候选包 versionName/versionCode/enabled）+ `dumpsys webviewupdate` 交叉核对（DUMP 权限持有） | DUMP（既有声明） | 采用 |

### 2.2 编译 SDK 裁剪与反射策略

本项目 compileSdk 33 的 `android.jar` 为裁剪版（此前蓝牙批次已核验），本批次实测缺失符号：

- `android.telephony.GsmCellLocation` / `CdmaCellLocation` 不在公开 SDK（`CellLocation` 为公开抽象类）——`getCellLocation()` 返回对象按类名 `GsmCellLocation`/`CdmaCellLocation` 判定，getter 全部反射调用（`intMethod` 兜底）；
- `CellInfoNr`/`CellInfoTdscdma` 的 identity getter（getMcc/getMnc 等）在裁剪 SDK 缺失——cell info 提取统一走反射（`cellInfoEntry`：按 CellInfo 子类名分型，`getCellIdentity()` 反射读取后按字段名逐一反射取值）。

反射仅用于读取，任何缺失均如实上报不 crash（框架侧方法集差异由运行期方法发现兜底，与本仓库既有批次一致）。

### 2.3 AIDL 通道约束

命令经 `ApiBinder.method2Commands` 注册（不修改 AIDL/lib 模块），返回 `Map{"RESULT": ...}`；返回结构全部使用 JDK 类型（Map/List/String/Long/Integer/Boolean），避免自定义 Bean 跨进程反序列化失败（与既有批次一致）。

## 3. 命令接口定义（SystemApiInterface.onEvent）

| 命令名 | 参数（Map） | 返回 RESULT | 说明 |
|---|---|---|---|
| `GetFileAttribute` | path（必填）、list（可选布尔，目录列表） | Map（见下） | ASR-0108 |
| `CheckRootStatus` | - | Map（见下） | ASR-0110 |
| `GetVpnStatus` | - | Map（见下） | ASR-0219 |
| `QueryNumberAttribution` | number（必填） | Map（见下） | ASR-0262 |
| `GetCellInfo` | - | Map（见下） | ASR-0265 |
| `GetSimContacts` | - | Map（见下） | ASR-0290 |
| `GetUserList` | - | Map（见下） | ASR-0387（2026-08-06 批次已注册，本批次纳入） |
| `GetWebViewInfo` | - | Map（见下） | ASR-0442 |

**GetFileAttribute 返回结构**：

```
{success:boolean, path:String, absolutePath:String, canonicalPath:String,
 name:String, parent:String, isFile:boolean, isDirectory:boolean,
 isHidden:boolean, isSymbolicLink:boolean, size:long,
 lastModified:long, lastModifiedText:String, readable:boolean,
 writable:boolean, executable:boolean,
 [list=true 且为目录]: listCount:int, listTruncated:boolean,
   entries:[{name:String, isFile:boolean, isDirectory:boolean, size:long}, ...]}
```

**CheckRootStatus 返回结构**：

```
{success:boolean, rooted:boolean, buildTags:String, testKeys:boolean,
 debuggable:String, rootAccessProp:String,
 suBinaries:[String,...], suLsProbe:String, suExecProbe:String,
 magisk:[String,...], superuserApk:[String,...], suDaemon:boolean,
 rwSystem:boolean, note:String}
```

**GetVpnStatus 返回结构**：

```
{success:boolean, supported:boolean, vpnActive:boolean, vpnNetworkCount:int,
 alwaysOnVpnPackage:String|null,
 networks:[{network:String, state:String, connected:boolean,
   interface:String, packages:[String,...]}, ...]}
```

**QueryNumberAttribution 返回结构**：

```
{success:boolean, matched:boolean, number:String, normalized:String,
 matchedPrefix:String, matchedPrefixLen:int, operator:String,
 province:String, city:String}
（仅命中 3 位前缀时 province/city 为空并附 note；未命中 matched=false + error）
```

**GetCellInfo 返回结构**：

```
{success:boolean, phoneCount:int, phoneType:String, networkOperator:String,
 networkOperatorName:String, networkType:String, simPresent:boolean,
 subscriptions:[{subscriptionId:int, simSlotIndex:int, mcc:int, mnc:int,
   displayName:String}, ...],
 cellInfo:[{registered:boolean, timeStamp:long, type:String, <identity 字段>}, ...],
 cellLocation:{type:String, cid:int/lac:int/psc:int 或 baseStationId:int/...}}
```

**GetSimContacts 返回结构**：

```
{success:boolean, simPresent:boolean, contactsCount:int,
 contacts:[{name:String, number:String, emails:String, anr:String,
   efid:String, index:String}, ...]}
```

**GetUserList 返回结构**（既有，2026-08-06 批次）：

```
{success:boolean, users:[{id:int, name:String, flags:int, admin:boolean,
 guest:boolean, primary:boolean}, ...]}
```

**GetWebViewInfo 返回结构**：

```
{success:boolean, webviewProviderDefault:String,
 currentProvider:{packageName:String, versionName:String, versionCode:long,
   isSystem:boolean, firstInstallTime:long, lastUpdateTime:long,
   enabled:boolean, installed:boolean}|null,
 currentProviderSource:String,   // WebViewUpdateService | dumpsys | none
 currentProviderError:String,    // 反射失败原因（无该方法时）
 providersCount:int,
 providers:[{...同上}, ...], dumpsysCurrent:String|null}
```

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> param = new HashMap<>();
param.put("number", "13910001234");
Map result = api.onEvent("QueryNumberAttribution", param);
Map res = (Map) result.get("RESULT");
String city = (String) res.get("city");
```

**广播通道**（TestBroadcast，与现有命令一致）：

```bash
adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast \
  -a ON_SQA_INTERFACE_TEST --es event QueryNumberAttribution --es param '{"number":"13910001234"}'
# 或 ./send_test_broadcast.sh QueryNumberAttribution number=13910001234
```

## 4. 实现结构

```
app/src/main/
├── assets/phone_attribution.csv                # 新增：号码归属地离线库（647 行，UTF-8 CSV）
└── java/com/hmdm/launcher/syrius/
    ├── utils/InfoQueryPolicyManager.java       # 新增：8 项查询引擎（File/root/VPN/归属地/CellInfo/SIM 联系人/WebView）
    ├── service/command/infoquery/
    │   ├── GetFileAttribute.java               # 新增：ASR-0108
    │   ├── CheckRootStatus.java                # 新增：ASR-0110
    │   ├── GetVpnStatus.java                   # 新增：ASR-0219
    │   ├── QueryNumberAttribution.java         # 新增：ASR-0262
    │   ├── GetCellInfo.java                    # 新增：ASR-0265
    │   ├── GetSimContacts.java                 # 新增：ASR-0290
    │   └── GetWebViewInfo.java                 # 新增：ASR-0442
    ├── service/ApiBinder.java                  # 注册 7 条新命令（GetUserList 已注册）
    └── AndroidManifest.xml                     # 新增 READ_CONTACTS 声明

testapp/
└── src/main/
    ├── java/com/hmdm/testapp/
    │   ├── InfoQueryVerifier.java              # 新增：本地探针（root/cellInfo/SIM 联系人/WebView/用户列表交叉核对）
    │   ├── InfoQueryTestActivity.java          # 新增：查询测试页（14 个按钮 + 路径/号码输入框）
    │   ├── TestActions.java                    # 新增 11 个事件（7 个 API 事件 + 4 个本地探针事件）
    │   └── MainActivity.java                   # 增加 "Info queries" 入口
    ├── res/layout/activity_info_query_test.xml # 新增测试页布局
    └── AndroidManifest.xml                     # 新增 InfoQueryTestActivity 与探针权限声明
```

## 5. 查询逻辑

```
GetFileAttribute(path, list=false):
  路径必填校验 → File(path)
  不存在 → {success:false, error:"file not found: <path>"}
  存在 → 属性采集（canonical/symlink 判定、size、lastModified+文本、权限位）
  目录且 list=true → listFiles()（拒绝时如实上报 listError），前 200 项 {name,isFile,isDirectory,size}

CheckRootStatus():
  su 路径枚举（9 处，含 .su 隐藏名与 su.orig）→ suBinaries（直接 stat）
  shell `ls -ld` 探针：对存在路径输出 "Permission denied" 行 → 计入 suBinaries
    （仅规范名路径；父目录 search 被拒时点号变体可能误报，原始输出 suLsProbe 返回）
  su exec 探针 `su 0 id`（3s 超时）→ suExecProbe（uid=0/root 输出计 rootCapability）
  Magisk 路径（/data/adb/magisk[/magisk]、/sbin/.magisk）→ magisk
  Superuser APK 枚举（5 处）→ superuserApk
  /system/etc/init.d/99SuperSUDaemon → suDaemon
  Build.TAGS / SystemProperties ro.debuggable / persist.sys.root_access → devBuild 指示
  /proc/mounts 解析 /system 挂载 rw → rwSystem
  rooted = suBinaries ∨ magisk ∨ superuserApk ∨ suDaemon ∨ rootCapability ∨ devBuild
  （SELinux 隐藏 su 的 ROM 上，直接 stat 假阴性由 shell 探针 + devBuild 双重证据兜底）

GetVpnStatus():
  getAllNetworks → 过滤 hasTransport(TRANSPORT_VPN)
  每网络：NetworkInfo.state/connected、LinkProperties.interface、
    caps.getUids() 反射（回退 getOwnerUid）→ uid → 包名列表
  任一网络 connectedOrConnecting → vpnActive=true
  dpm.isDeviceOwnerApp → dpm.getAlwaysOnVpnPackage(AdminReceiver) → alwaysOnVpnPackage

QueryNumberAttribution(number):
  数字归一化（去非数字、去 86/0086 前缀、超 11 位截尾）
  <7 位 → matched=false（too short）
  assets/phone_attribution.csv 首次加载缓存（进程级）
  按 7→3 位最长前缀匹配；3 位命中仅返回运营商，province/city 为空
  全未命中 → matched=false + error

GetCellInfo():
  phoneCount/phoneType/networkOperator(+Name)/networkType
  SubscriptionManager 活动订阅列表 → subscriptions + simPresent
  getAllCellInfo() → 每项 cellInfoEntry()（反射分型提取 identity 字段）
  getCellLocation() → GsmCellLocation{type,cid,lac,psc} /
    CdmaCellLocation{type,baseStationId,networkId,systemId}（反射）
  SecurityException → 如实上报 cellInfoError/cellLocation.error

GetSimContacts():
  SubscriptionManager → simPresent
  ContentResolver.query(content://icc/adn, null,...) → 逐行 {name,number,emails,anr,efid,index}
  无 SIM → 空列表 + note；SecurityException → 如实上报（READ_CONTACTS 要求）

GetWebViewInfo():
  Settings.Global webview_provider_default → webviewProviderDefault
  反射 WebViewUpdateService.getCurrentWebViewPackage → currentProvider + 来源标记
  候选包枚举：com.android.webview/com.google.android.webview 等已知包 +
    已安装包名含 "webview" 的应用（含禁用/卸载保留状态）
  每候选包 → providerEntry{versionName, versionCode(getLongVersionCode),
    isSystem, firstInstallTime, lastUpdateTime, enabled(
    getApplicationEnabledSetting + applicationInfo.enabled)}
  exec dumpsys webviewupdate（16KB 截断）→ 解析 "Current WebView package (name, version): (...)"
    → dumpsysCurrent 交叉核对
```

## 6. 权限与归属

- 归属：全部 Launcher（MDM）。无 ROM 侧代码改动，无 root 依赖。
- 新增权限声明：
  - `android.permission.READ_CONTACTS`：IccProvider（`content://icc/adn`）读取 SIM 联系人（dangerous 级；uid=1000 与系统共享权限集，实际授权无需运行时弹窗，声明用于清单完整性）。
- 既有声明复用：READ_PHONE_STATE、READ_PRIVILEGED_PHONE_STATE、ACCESS_FINE_LOCATION（TelephonyManager cell info）、ACCESS_NETWORK_STATE（ConnectivityManager）、DUMP（dumpsys webviewupdate）、MANAGE_USERS（UserManager.getUsers）。
- testapp 新增声明：READ_PHONE_STATE / ACCESS_FINE_LOCATION / READ_CONTACTS（本地探针用；非系统 uid 未授予时探针如实上报 SecurityException，作为权限门证据）。
- 不修改 AIDL / lib 模块。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| GetFileAttribute 缺 path | 返回 `{"success":false,"error":"missing parameter: path"}` |
| 路径不存在/无权限 | `file not found` / 属性读取异常如实上报，不 crash |
| 目录列表权限拒绝 | listError 字段如实上报（listFiles 返回 null） |
| CheckRootStatus | 纯静态指标，无抛异常路径；userdebug test-keys 不计入 rooted（文档化口径） |
| GetVpnStatus 无 VPN | networks 空数组 + note（无活动 TRANSPORT_VPN 网络），vpnActive=false |
| 非 device owner 时 always-on 查询 | 跳过（isDeviceOwnerApp 前置），alwaysOnVpnPackage=null |
| 归属地号码归一化 | 去 86/0086/非数字，超长截尾；<7 位或未命中返回 matched=false + error |
| 离线库加载失败 | 空库 + error（matched=false），不 crash；库文件缺失时下次访问重载 |
| 无 SIM / 无服务 | GetCellInfo：simPresent=false、cellInfo 空、cellLocation 空（如实上报）；GetSimContacts：contactsCount=0 + note |
| getAllCellInfo/getCellLocation 权限异常 | SecurityException → cellInfoError/cellLocation.error 如实上报 |
| 裁剪 SDK / 框架方法差异 | 全部反射读取，缺失字段记 -1/缺字段，不 crash（2.2 节） |
| WebViewUpdateService 无该方法 | currentProviderSource="none" + dumpsysCurrent 交叉核对兜底 |
| dumpsys webviewupdate 执行失败 | dumpsysCurrent=null，其余字段不受影响 |
| webview_provider_default 未设置 | 返回空字符串（本 ROM 出厂为 null，实际 Provider 以 WebViewUpdateService 为准，文档化） |
| AIDL 通道返回自定义类 | 全部 JDK Map/List 结构（见 2.3 节） |

## 8. 真机验证记录（2026-08-07，Android 13 / API 33 userdebug，平台签名 + device owner）

全部用例经 testapp IPC（`send_test_command.sh`，与 UI 按钮共用 `TestActions.execute()` 引擎）执行，部分经 Launcher 广播通道（`send_test_broadcast.sh`）交叉验证，对应测试用例设计文档 TC- 编号：

| 用例 | 结果 |
|---|---|
| TC-0108-01/02/04/05/06/07/08 | 通过：/sdcard/MDM 目录属性、file_1234.bin size=1234 精确、目录列表 3 项（含子目录）、/system/build.prop（size=8592）、不存在路径 error、缺参提示、混合列表标记 |
| TC-0108-03 权限位 | 通过（带说明）：/data/system/device_policies.xml readable=true/writable=false 如实反映；**/sdcard（fuse）上 chmod 444 对 uid=1000 仍报 writable=true（fuse 挂载按 sdcard_rw 组放行 W_OK，不反映 chmod）**，文档化 |
| TC-0110-01/02/03/04/05 | 通过：rooted=true、suBinaries=[/system/xbin/su]（shell 探针 "Permission denied" 证据）、debuggable=1、testKeys=false（release-keys）、rwSystem=false；本地探针一致；**本 ROM 出厂自带 /system/xbin/su（userdebug su 工具，setuid root shell，`adb shell su` 实测可获 root）**，且 SELinux 将该文件标记 su_exec 并拒绝 system_app/appdomain stat——直接 File.exists() 假阴性，经 shell `ls` 探针（"Permission denied" 行）与 ro.debuggable=1 双重证据判定 rooted=true |
| TC-0219-01/02/03/04/06 | 通过：无 VPN 时 vpnActive=false、networks=[]；启用域名策略后防火墙 VPN 出现（network 102、tun0、CONNECTED、packages 含 com.hmdm.launcher 及被路由应用 uid）；CheckVpnNetworks 交叉核对 1 个 VPN 网络一致；恢复 mode=0 后回到空 |
| TC-0219-05 always-on | 受限（本 ROM，2026-08-07 结论）→ **2026-08-13 已闭环修正**：本批次曾记录 `dpm.setAlwaysOnVpnPackage` 抛 UnsupportedOperationException、alwaysOnVpnPackage 只能验证 null 路径——该结论有误；VpnConsentAlwaysOnControl 批次（ASR-0213/0220）三次实测该接口可用，`SetAlwaysOnVpn packageName=com.syriusrobotics.platform.launcher lockdown=false` 后 `GetVpnStatus` 返回 alwaysOnVpnPackage=com.syriusrobotics.platform.launcher（非 null 路径闭环，见 VpnConsentAlwaysOnControl 测试用例文档） |
| TC-0262-01/02/03/04/05/06/07/08 | 通过：13910001234→北京/北京/中国移动（7 位段）；13112345678→北京/中国联通（4 位地域惯例）；+86/0086 格式归一化一致；133→中国电信、170→虚拟运营商；12345 无匹配 error；138→matched=true 仅运营商级（note 说明）；缺参提示 |
| TC-0265-01/02/03/04 | 通过（受限部分见需求文档"硬件受限测试说明"）：simPresent=false、subscriptions=[]、cellInfo 每条卡槽 1 条 LTE 桩条目（本机 2 槽共 2 条，registered=false，ci/tac/pci/mcc/mnc=-1，与 telephony.registry mCellInfo=null 口径一致）、cellLocation GSM cid=268435455（unavailable）/lac=65535；本地探针 SecurityException（权限门证据） |
| TC-0290-01/02/03 | 通过：simPresent=false、contactsCount=0、contacts=[]（IccProvider 无行）+ note；本地探针 SecurityException（READ_CONTACTS 权限门证据） |
| TC-0387-01/03/04 | 通过：默认 1 用户（Owner id=0 primary/admin）；CreateUser mdm_test_user→id=10 出现于列表、DeleteUser→消失，`dumpsys user` 无残留 |
| TC-0387-02 本地探针 | 受限（本 ROM/API 33）：非特权应用 `UserManager.getUsers` 抛 SecurityException（需 MANAGE_USERS/CREATE_USERS）——如实上报，Launcher（uid=1000）结果为准 |
| TC-0442-01/02/03/04/05/07 | 通过：webviewProviderDefault 空（本 ROM 出厂 null）；currentProvider=com.android.webview 101.0.4951.61 / versionCode 495156103 / enabled=true（**本 ROM WebViewUpdateService 无 getCurrentWebViewPackage 方法**——运行期 NoSuchMethodException 核验，currentProvider 经 `dumpsys webviewupdate` "Current WebView package" 行解析回填，source=dumpsys）；dumpsysCurrent 解析正确；providers 3 个候选包（含 org.chromium.webview_shell 与 com.norman.webviewup.demo） |
| TC-0442-06 本地探针 | 受限（本 ROM）：WebView 包对非系统应用不可见（包可见性规则），本地探针 providers=[]，note 说明，Launcher（uid=1000）结果为准 |
| TC-M-01/02/04/05 | 通过：8 命令缺参容错、未知事件 unknown event、返回值全 JDK 类型、Launcher 广播通道等价执行 |

**实现与部署注意（本批次实测踩坑）**：
1. 编译期：裁剪版 android.jar 缺 `GsmCellLocation`/`CdmaCellLocation`/`CellInfoNr`/`CellInfoTdscdma` 部分符号（见 2.2 节），cell location / cell info 提取统一反射。
2. 本机（MT8788 平板）**无 SIM 卡**（`gsm.sim.state=ABSENT,ABSENT`）、telephony registry 显示 OUT_OF_SERVICE/POWER_OFF、`mCellIdentity=null`、`mCellInfo=null`——ASR-0265/0290 在本机验证「如实上报空结果」路径，真实小区信息/SIM 联系人验证需插 SIM 的真机（见需求文档"硬件受限测试说明"）。
3. 本 ROM 出厂 `webview_provider_default` 为 null，实际 Provider 由 WebViewUpdateService 解析（com.android.webview 101.0.4951.61 / versionCode 495156103），命令同时上报设置键与解析结果，两者均如实呈现；`WebViewUpdateService.getCurrentWebViewPackage` 在本 ROM 不存在（NoSuchMethodException），当前 Provider 由 `dumpsys webviewupdate` 解析回填（source=dumpsys）。
4. 号码归属地离线库为内置样例库（按 4 位前缀地域编码惯例 + 常用 7 位段覆盖），3 位前缀全量覆盖运营商；完整商用库可由用户直接替换 `assets/phone_attribution.csv`（UTF-8，`prefix,province,city,operator`），无需改代码。
5. **ASR-0110 判定口径（本 ROM 实测）**：SELinux 将 su 标记 `su_exec` 并拒绝 system_app/appdomain 对它的 stat（含父目录 /system/xbin 的 search），直接 `File.exists()` 全假阴性；shell `ls` 探针对存在路径输出 "Permission denied"（与父目录 search 拒绝无法区分，故点号隐藏变体路径不纳入证据，原始输出 suLsProbe 全量返回）；`ro.debuggable=1` 与 test-keys 作为开发构建指示计入判定（RootBeer 等主流 root 检测同口径）；本机 verdict：suBinaries=[/system/xbin/su] + debuggable=1 → rooted=true（与 `adb shell su` 实测可获 root 一致）。
6. 部署流程提醒：`adb install -r` 重装后 Launcher/testapp 进程可能残留旧代码（system uid 进程不受普通 force-stop 影响），须 `adb shell kill $(pidof ...)` 后重启 testapp 再验证；force-stop testapp 会触发 MTK DuraSpeed suppress 列表导致 manifest receiver 广播被丢弃（需重启设备清空，与既有批次一致）。
