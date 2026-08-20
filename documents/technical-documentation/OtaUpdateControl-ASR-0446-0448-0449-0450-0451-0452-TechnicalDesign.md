# OTA 接口组（ASR-0446/0448/0449/0450/0451/0452）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 实现机制 |
|---|---|---|---|
| ASR-0446 | 在线系统升级（FOTA） | Launcher（MDM）对业务应用提供检查可用系统版本、获取版本信息以及下载经过签名校验的 FOTA 升级包的接口 | `FotaCheckUpdate`/`FotaDownloadUpdate`（FotaUpdateChecker 引擎：FileDownloader 下载元数据/整包 + 反射 `UpdateEngine.verifyPayloadMetadata` 签名校验 + `RecoverySystem.verifyPackageCompatibility` 兼容性校验 + payload_properties.txt 版本信息解析） |
| ASR-0448 | 本地系统升级（OTA） | SD 卡/本地文件 OTA 策略开关（ROM 提供安装能力，Launcher 提供受管开关） | `SetLocalOtaEnabled`/`IsLocalOtaEnabled`（LocalOtaPolicyManager，SharedPreferences `local_ota_policy` 持久化；关闭时 FotaStart/FotaApply 拒绝本地 OTA） |
| ASR-0449 | OTA 任务控制 | 取消正在应用的 OTA Payload，返回操作结果和当前升级状态 | `FotaCancel`（UpdaterServiceClient → ExportUpdaterService `cancelUpdate`（UpdateEngine.cancel）+ `getUpdaterState` 读回） |
| ASR-0450 | OTA 任务控制 | 暂停和恢复 OTA Payload 应用过程；仅底层引擎支持的转换开放 | `FotaSuspend`/`FotaResume`（同上 AIDL `suspendUpdate`/`resumeUpdate`，UpdateManager 状态机 RUNNING↔PAUSED） |
| ASR-0451 | OTA 任务控制 | 升级回调：持续上报 OTA 状态、进度、完成结果、错误码以及等待重启状态 | ExportUpdaterService 四个引擎回调（UpdaterState/EngineStatus/EngineComplete/Progress）经广播 `com.hmdm.launcher.ACTION_OTA_STATUS`（extras `callbackType`+`value`）持续上报；业务应用注册 BroadcastReceiver 消费；取消/暂停/恢复等操作亦绑定回调以驱动状态广播；testapp `OtaCallbackRecorder` 作为消费验证客户端 |
| ASR-0452 | A/B 槽位管理 | 查询当前运行槽位、下次启动槽位、各槽位可启动状态和启动成功状态（切换经受控接口） | `GetSlotInfo`（当前运行槽位经 bootloader 属性 ro.boot.slot/slot_suffix 读取 + 反射 @SystemApi `android.os.BootControl` 尝试——**本 ROM 未接入 bootcontrol HAL，可启动/启动成功标志与下次启动槽位不可查询，如实上报，按用户决策维持部分完成**）+ `SetSwitchSlotOnReboot`（ExportUpdaterService `setSwitchSlotOnReboot`，SWITCH_SLOT_ON_REBOOT 属性重放 payload） |

**归属**：ASR-0446 为「Launcher（MDM）」（下载与校验均为 Launcher 侧既有 A/B 工具链）；ASR-0448 需求本体为「ROM」（安装能力），本批次实现其 Launcher 侧策略开关（受管接口）；ASR-0449/0450/0451 为「Launcher（MDM）+ 系统 API」（UpdateEngine 为公开 API 33 类，Binder 绑定 Launcher 自有服务，平台签名）；ASR-0452 为「ROM + Launcher（MDM）」（bootcontrol HAL 为 ROM 能力，Launcher 封装查询/切换接口）。均**无需 ROM 改动**。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署；bootloader 锁定。测试前设备基线（2026-08-11 实测）：`ro.boot.slot_suffix=_a`（A/B 设备）；本地 OTA 策略默认 enabled；无运行中 OTA（UpdaterState=IDLE）；`update_engine` 服务可用。

## 2. 技术选型与可行性核验

### 2.1 既有 OTA 底座（ExportUpdaterService / UpdateManager / FotaStart）

本批次之前 Launcher 已有完整的 A/B OTA 底座（源自 AOSP SystemUpdaterSample + 参考实现 hxy_demo）：

- `ExportUpdaterService`（`syrius/fota/services/`）：绑定式服务，`IUpdaterService` AIDL（applyUpdate/cancelUpdate/suspendUpdate/resumeUpdate/resetUpdate/setSwitchSlotOnReboot），内部持 `UpdateManager`；
- `UpdateManager`（`syrius/fota/UpdateManager.java:475-551`）：A/B UpdateEngine 封装——applyPayload、槽位切换（`setSwitchSlotOnReboot`/`setSwitchSlotOnRebootForce`，SWITCH_SLOT_ON_REBOOT 属性重放）、状态回调（onStatusUpdate/onPayloadApplicationComplete/onProgressUpdate）与 UpdaterState 状态机（0=IDLE 1=ERROR 2=RUNNING 3=PAUSED 4=SLOT_SWITCH_REQUIRED 5=REBOOT_REQUIRED）；
- `PrepareUpdateService`：IntentService，下载/校验/构造 PayloadSpec（verifyPayloadMetadata 反射通道）；
- `FotaStart` 命令：`dpm.installSystemUpdate`（Q+）非 A/B 安装路径 + 状态广播 `com.hmdm.launcher.ACTION_OTA_STATUS`（code/msg/actionId）。

**本批次缺口**（与 Sheet1 部分完成表一致）：服务已实现但 **ApiBinder 无命令、客户端未接入**（0449/0450/0451）；无"检查可用版本"接口（0446）；SD 卡升级策略开关无（0448）；无槽位状态查询接口（0452）。

### 2.2 ASR-0446 检查版本/下载 FOTA（FotaUpdateChecker）

**语义拆解**：检查可用系统版本 = 下载 OTA 包元数据（payload_metadata.bin + payload_properties.txt + compatibility.zip）并校验（包签名/完整性 + 版本兼容性）；版本信息 = 配置 `name` 字段 + payload_properties.txt 键值对（FILE_HASH/FILE_SIZE/PACKAGE_VERSION 等，payload_properties.txt 为 A/B 包标准属性文件）；下载 = 整包拉取到 Launcher 数据目录供后续 FotaStart 本地安装。

**校验接口**：

| 接口 | 通道 | 说明 |
|---|---|---|
| `UpdateEngine.verifyPayloadMetadata(String path)` | @hide，反射 | 由 update_engine 校验 payload 元数据签名/完整性；存在即返回 true/false，异常如实上报 verifyError |
| `RecoverySystem.verifyPackageCompatibility(File)` | @hide，反射 | 校验 compatibility.zip 与设备版本兼容性（compatibility matrix） |

两个接口均与 `PrepareUpdateService` 既有通道一致（反射调用，平台签名豁免 hidden API 限制）；虚假/伪造包校验结果为 **false**，命令如实返回（不误报成功）——这是"下载经过签名校验的 FOTA 升级包"的验收口径：**未通过校验的包不会被标记为可用**。

**下载实现**：
- `checkUpdate`：仅下载 property_files 中列出的 payload_metadata.bin / payload_properties.txt / compatibility.zip（FileDownloader 按 offset/size 拉取，与 PrepareUpdateService 同机制），目录 `filesDir/ota/check/`，每次先清理；
- `downloadUpdate`：整包下载（URL 流式，http/https/file 均可）至 `filesDir/ota/fota_package.zip`，再从 zip 内提取三个校验文件（java.util.zip.ZipFile）执行同一校验；文件大小校验（0 字节下载视为失败）。

**版本信息**：`versionName`=config.name；`payloadProperties`=payload_properties.txt 全量键值；`PACKAGE_VERSION` 存在时另附 `packageVersion`。

### 2.3 ASR-0448 SD 卡/本地 OTA 策略开关（LocalOtaPolicyManager）

**语义**：受管策略开关，控制"是否允许本地（SD 卡/本地文件）OTA"。策略持久化 SharedPreferences `local_ota_policy`（键 `localOtaEnabled`，默认 true=允许），写后读回核对。**强制点**：
- `FotaStart`（本地安装主入口）：URI scheme 为 content:// 或 file:// 且策略关闭 → 拒绝（状态码 **-104 local ota disabled by policy** 广播）；
- `FotaApply`（A/B 引擎路径）：config.url 为 file:// 且策略关闭 → 拒绝（success=false + error，不绑定引擎）；
- 在线（http/https）FOTA 不受影响（ASR-0444 的 Set/IsOnlineFotaDisabled 管在线策略）。

### 2.4 ASR-0449/0450 取消/暂停/恢复（UpdaterServiceClient）

`UpdaterServiceClient`（单例）：进程内绑定 ExportUpdaterService（显式组件 + `com.syrius.android.fota.ACTION_BIND_UPDATER` 动作，BIND_AUTO_CREATE），首次绑定等待至多 10s，连接后各操作即发即弃（AIDL 异步）。操作后经 `getUpdaterState()`（本批次新增 AIDL 方法，返回 UpdateManager 当前状态）读回当前升级状态随操作结果返回——满足"返回操作结果和当前升级状态"。

**状态转换合法性**（UpdateManager 状态机，`UpdaterState.TRANSITIONS`）：IDLE→RUNNING（apply）、RUNNING→PAUSED（suspend，底层 cancel 语义）、PAUSED→RUNNING（resume，重放 payload）、RUNNING→IDLE（cancel）；非法转换抛 `InvalidTransitionException` 由服务捕获记日志，命令侧仍读回真实状态如实上报。

**ExportUpdaterService 本批次改动**：cancelUpdate/suspendUpdate/resumeUpdate/resetUpdate 原来忽略回调参数——改为存储回调对象，使状态变更经 AIDL 回调与广播双通道送达客户端；新增 `getUpdaterState()` 实现；UpdateEngine bind 包 try/catch（引擎不可用时服务不崩溃）。

### 2.5 ASR-0451 OTA 回调消费通道（广播中继）

**设计**：ExportUpdaterService 四个回调方法（onUpdaterStateChange/onEngineStatusUpdate/onEnginePayloadApplicationComplete/onProgressUpdate）在原有 AIDL 回调之外，**统一转发为广播**：

| callbackType | 触发点 | value 语义 |
|---|---|---|
| `updater_state` | UpdaterState 状态变化 | 0~5（IDLE/ERROR/RUNNING/PAUSED/SLOT_SWITCH_REQUIRED/REBOOT_REQUIRED） |
| `engine_status` | UpdateEngine onStatusUpdate 状态变化 | update_engine 状态码（UpdateEngineStatuses） |
| `engine_complete` | onPayloadApplicationComplete | 错误码（UpdateEngineErrorCodes，0=成功/UPDATED_BUT_NOT_ACTIVE=成功但未激活） |
| `progress` | 进度更新 | 0.0~1.0 字符串 |

广播 action 与 FotaStart 状态广播共用 `com.hmdm.launcher.ACTION_OTA_STATUS`（extras：`callbackType`+`value`；FotaStart 事件为 `code`+`msg`+`actionId` 格式，消费方可区分）。业务应用（含 testapp）注册 BroadcastReceiver 即完成客户端消费，无需持有 AIDL 连接。

### 2.6 ASR-0452 A/B 槽位查询/切换（FotaSlotHelper + SetSwitchSlotOnReboot）

**查询（GetSlotInfo）**：优先反射 `android.os.BootControl`（@SystemApi，框架 `IBootControl` bootcontrol HAL）：

| 方法 | 返回 |
|---|---|
| `getInstance()` | BootControl 实例（无 "bootcontrol" binder 服务时 null） |
| `getCurrentSlot()` | 当前运行槽位索引（0/1） |
| `getSuffix(int)` | 槽位后缀（_a/_b） |
| `isSlotBootable(int)` | 槽位可启动性 |
| `isSlotMarkedSuccessful(int)` | 槽位启动成功标志 |

- **本 ROM（MTK）实测：bootcontrol HAL 未接入框架**——`BootControl.getInstance()` 返回 null（ServiceManager 无 "bootcontrol" 服务；HIDL `android.hardware.boot@1.x::IBootControl/default` 进程在跑、lshal 可见注册，但无框架 Java 桥接，且 SELinux 拒 shell `find` hal_bootctl_hwservice）；HIDL Java 桩反射回退（android.hardware.boot.V1_0~1_2.IBootControl.getService）在本 ROM framework 亦不可用；
- **降级路径**：`supported=true`（ro.build.ab_update=true）+ `halAvailable=false`；`currentSlot`/`currentSlotSuffix` 经 bootloader 属性（ro.boot.slot / ro.boot.slot_suffix，隐藏 `SystemProperties.get` 反射）读取；`bootable`/`markedSuccessful` 以字符串 `unknown` 上报、`nextBootSlot=-1`（AIDL 通道不可携带 null）+ note 说明"需 ROM 接入 bootcontrol 服务后提供"；
- 无 A/B（无 ab_update 且无 HAL）→ `supported=false` + note，不 crash。

**切换（SetSwitchSlotOnReboot）**：复用既有 UpdateManager 机制——`setSwitchSlotOnReboot(config)` 以 SWITCH_SLOT_ON_REBOOT 属性重放 payload（含 SKIP_POST_INSTALL），使设备下次重启进入另一槽位（真机切换会触发重启，本批次仅验证命令契约与 dispatch；槽位受控切换权限见 ASR-0453/0454 ROM 侧）。命令返回 {success, dispatched}。

## 3. 命令接口定义（SystemApiInterface.onEvent）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `FotaCheckUpdate` | config（JSON 字符串，必填；格式同 FotaStart：name/url/ab_install_type/ab_config.property_files） | Map：{success, versionName, downloadUrl, installType, downloadedFiles, verifyPayloadMetadata, verifyError?, packageCompatible, compatNote?, payloadProperties, packageVersion?, note?} 或 {error} | ASR-0446 |
| `FotaDownloadUpdate` | config（JSON 字符串，必填） | Map：{success, filePath, fileSize, versionName, downloadUrl, verifyPayloadMetadata, verifyError?, packageCompatible, payloadProperties} 或 {error} | ASR-0446 |
| `SetLocalOtaEnabled` | enabled（boolean，必填） | Map：{success, enabled} 或 {error} | ASR-0448 |
| `IsLocalOtaEnabled` | 无 | Map：{success, enabled} | ASR-0448 |
| `FotaApply` | config（JSON 字符串，必填） | Map：{success, dispatched, state, stateText} 或 {error} | ASR-0447 引擎路径 / ASR-0451 回调驱动 |
| `FotaCancel` | 无 | Map：{success, dispatched, state, stateText} | ASR-0449 |
| `FotaSuspend` | 无 | Map：{success, dispatched, state, stateText} | ASR-0450 |
| `FotaResume` | 无 | Map：{success, dispatched, state, stateText} | ASR-0450 |
| `GetSlotInfo` | 无 | Map：{success, supported, currentSlot, currentSlotSuffix, nextBootSlot, bootloaderSlots, slots:[{slot, suffix, bootable, markedSuccessful}], note?} | ASR-0452 |
| `SetSwitchSlotOnReboot` | config（JSON 字符串，必填） | Map：{success, dispatched} 或 {error} | ASR-0452 |

**回调广播接口**（ASR-0451，非 onEvent 命令）：action `com.hmdm.launcher.ACTION_OTA_STATUS`，extras `callbackType`（updater_state/engine_status/engine_complete/progress）+ `value`（字符串）；FotaStart 事件兼容（code/msg/actionId）。

**调用示例**：

```java
Map<String, Object> p = new HashMap<>();
p.put("config", "{\"name\":\"TEST-OTA-1\",\"url\":\"http://127.0.0.1:8080/ota.zip\","
        + "\"ab_install_type\":\"FILE_PROVIDER\","
        + "\"ab_config\":{\"force_switch_slot\":false,\"verify_payload_metadata\":false,"
        + "\"property_files\":[{\"filename\":\"payload_metadata.bin\",\"offset\":0,\"size\":1000},"
        + "{\"filename\":\"payload_properties.txt\",\"offset\":1000,\"size\":200}]}}");
Map result = api.onEvent("FotaCheckUpdate", p);
// {"RESULT":{"success":true,"versionName":"TEST-OTA-1",...,"verifyPayloadMetadata":false,...}}
```

**广播通道**（TestBroadcast，与现有命令一致）：

```bash
adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast \
  -a ON_SQA_INTERFACE_TEST --es event FotaCancel
```

## 4. 实现结构

```
app/src/main/
├── aidl/syrius/mdm/mobile_operator/fota/
│   └── IUpdaterService.aidl                        # 新增 int getUpdaterState()
├── java/com/hmdm/launcher/syrius/fota/
│   ├── LocalOtaPolicyManager.java                  # 新增：ASR-0448 本地 OTA 策略（SharedPreferences 持久化 + 读回核对）
│   ├── FotaUpdateChecker.java                      # 新增：ASR-0446 元数据下载/整包下载 + verifyPayloadMetadata/verifyPackageCompatibility + 版本解析
│   ├── FotaSlotHelper.java                         # 新增：ASR-0452 BootControl 反射槽位查询 + 属性回退
│   └── services/
│       ├── ExportUpdaterService.java               # 修改：四回调广播中继（ACTION_OTA_STATUS）、cancel/suspend/resume/reset 存储回调、getUpdaterState、bind 容错
│       └── UpdaterServiceClient.java               # 新增：ExportUpdaterService 绑定客户端（apply/cancel/suspend/resume/reset/setSwitchSlotOnReboot/getUpdaterState）
├── java/com/hmdm/launcher/syrius/service/
│   ├── ApiBinder.java                              # 注册 10 个新命令
│   └── command/fota/
│       ├── FotaCheckUpdate.java / FotaDownloadUpdate.java   # 新增：ASR-0446
│       ├── SetLocalOtaEnabled.java / IsLocalOtaEnabled.java # 新增：ASR-0448
│       ├── FotaApply.java                                    # 新增：ASR-0447 引擎路径（file:// 受 0448 策略门禁）
│       ├── FotaCancel.java                                   # 新增：ASR-0449
│       ├── FotaSuspend.java / FotaResume.java                # 新增：ASR-0450
│       ├── GetSlotInfo.java / SetSwitchSlotOnReboot.java     # 新增：ASR-0452
│       └── FotaStart.java                           # 修改：本地 URI 受 ASR-0448 策略门禁（-104）

testapp/
├── src/main/java/com/hmdm/testapp/
│   ├── OtaCallbackRecorder.java                     # 新增：ACTION_OTA_STATUS 广播消费（ASR-0451 客户端）
│   ├── OtaTestActivity.java                         # 新增：OTA 测试页（13 按钮）
│   ├── TestActions.java                             # 新增 13 个事件与目录
│   └── MainActivity.java                            # 新增入口
├── src/main/res/layout/activity_ota_test.xml        # 新增测试页布局
└── src/main/AndroidManifest.xml                     # 注册 OtaTestActivity
```

## 5. 执行逻辑

```
FotaCheckUpdate:
  1. 参数校验（缺 config → {error}）；UpdateConfig.fromJson 解析（失败 → {success:false, error})
  2. 定位 property_files 中 payload_metadata.bin / payload_properties.txt / compatibility.zip
  3. FileDownloader 按 offset/size 下载到 filesDir/ota/check/（每次先清理）；缺文件如实记下载结果
  4. 有 metadata → 反射 UpdateEngine.verifyPayloadMetadata → verifyPayloadMetadata/verifyError
  5. 有 compatibility.zip → 反射 RecoverySystem.verifyPackageCompatibility → packageCompatible
  6. 解析 payload_properties.txt → payloadProperties/packageVersion
  7. 返回 {success:true, versionName, downloadedFiles, ...}

FotaDownloadUpdate:
  1. 同 1（参数校验/解析）
  2. URL 流式整包下载到 filesDir/ota/fota_package.zip（0 字节 → 失败）
  3. ZipFile 提取 metadata/properties/compatibility → 同 4/5/6 校验
  4. 返回 {success, filePath, fileSize, versionName, ...}

SetLocalOtaEnabled:
  1. 参数校验（缺 enabled → {error}）
  2. SharedPreferences local_ota_policy 写 enabled → 读回核对 → {success, enabled}
  3. 强制点：FotaStart（content://|file:// 且禁用 → -104 广播拒绝）、FotaApply（file:// 且禁用 → success=false）

FotaCancel / FotaSuspend / FotaResume:
  1. UpdaterServiceClient 绑定 ExportUpdaterService（首次绑定 ≤10s）
  2. AIDL cancelUpdate/suspendUpdate/resumeUpdate（回调参数已存储 → 状态变更广播）
  3. getUpdaterState 读回 → {success, dispatched, state, stateText}

FotaApply / SetSwitchSlotOnReboot:
  1. 参数校验 + JSON 解析；FotaApply 校验 file:// 策略门禁
  2. AIDL applyUpdate / setSwitchSlotOnReboot（回调已注册 → 引擎回调经广播持续上报）
  3. 返回 {success, dispatched, state?, stateText?}

GetSlotInfo:
  1. 反射 BootControl.getInstance()；null → {success:true, supported:false, note}
  2. getCurrentSlot/getSuffix/isSlotBootable/isSlotMarkedSuccessful 遍历 0/1
  3. nextBootSlot = 非当前且 bootable 的槽位；bootloaderSlots = ro.boot.slot/slot_suffix（隐藏属性反射）
  4. 异常 → {success:false, error}，不 crash
```

**安全设计**：config 参数为 JSON 字符串，仅经 UpdateConfig.fromJson/JSONObject 解析出字段，无字符串进入 shell；下载目标固定于 Launcher 私有数据目录（filesDir/ota），不做路径拼接；verifyPayloadMetadata/verifyPackageCompatibility 仅以文件路径调用系统服务；本地策略门禁在命令入口强制执行，防止策略关闭后经 FotaStart/FotaApply 绕过；UpdaterServiceClient 仅绑定 Launcher 自有组件（显式包名+类名）。

## 6. 权限与归属

- ASR-0446：无新增权限——下载为普通网络/文件 IO（INTERNET 既有），校验经平台签名豁免的 @hide 反射（UpdateEngine/RecoverySystem 公开类）；
- ASR-0448：无新增权限——SharedPreferences 应用内数据 + FotaStart/FotaApply 命令内门禁；
- ASR-0449/0450/0451：无新增权限——绑定 Launcher 自有服务（exported 既有声明），UpdateEngine 为公开 API（API 25+）由 ExportUpdaterService 使用；广播为普通动态接收（testapp 在 API 33 用 RECEIVER_EXPORTED）；
- ASR-0452：无新增权限——BootControl 为 @SystemApi 反射调用（平台签名豁免 hidden API 限制），SystemProperties 隐藏类反射；
- 不修改 lib 模块；AIDL 仅 Launcher 内部使用（IUpdaterService 新增 getUpdaterState，兼容既有方法）。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺 config 参数 | 命令返回 {error:"missing parameter: config (JSON)"}，不 crash |
| config JSON 非法 | 返回 {success:false, error:"invalid config JSON: ..."}，不 crash |
| property_files 无 payload_metadata.bin | verifyPayloadMetadata=null + note（跳过校验说明），其余字段正常返回 |
| property_files 无 compatibility.zip | packageCompatible=null + compatNote |
| 下载失败（URL 不可达/offset 错/0 字节） | 命令返回 {success:false, error}（FotaUpdateChecker 捕获）；下载过程异常不外泄堆栈 |
| verifyPayloadMetadata 接口不可用（无 update_engine） | verifyPayloadMetadata=null + verifyError，不 crash（虚假包校验 false 为预期正常值） |
| 虚假/伪造 OTA 包 | 校验如实返回 false（验收口径：未通过校验的包不可用） |
| ExportUpdaterService 未绑定/绑定失败 | FotaCancel 等返回 {success:false, dispatched:false, error}，state=-1/stateText=unavailable |
| 非法状态转换（如 IDLE 时 suspend） | UpdateManager 抛 InvalidTransitionException，服务捕获记日志；命令读回真实 state 如实上报 |
| UpdateEngine 不可用（bind 异常） | ExportUpdaterService 容错继续运行（getUpdaterState 仍可用） |
| 本地 OTA 策略关闭时 FotaStart 本地 URI | 广播 -104 local ota disabled by policy，不执行安装 |
| 本地 OTA 策略关闭时 FotaApply file:// 配置 | 返回 {success:false, dispatched:false, error:"local ota disabled by policy"} |
| 无 bootcontrol HAL（非 A/B） | GetSlotInfo 返回 {success:true, supported:false, note} |
| BootControl 方法缺失（ROM 差异） | 捕获异常返回 {success:false, supported:false, error}，不 crash |
| setSwitchSlotOnReboot 无已应用 payload | UpdateManager 无 mLastUpdateData 时静默（AIDL 已 dispatch），命令如实 success=true/dispatched=true（真实槽位切换需先应用 OTA） |
| 回调广播无消费方 | 正常丢弃（普通广播），不影响引擎 |

## 8. 真机验证记录（2026-08-11，Android 13 / API 33 userdebug，平台签名 + device owner）

（命令级用例结果见测试用例设计文档，此处记录机制核验关键结论）

| 核验项 | 结果 |
|---|---|
| 设备基线 | `ro.boot.slot_suffix=_a`、`ro.boot.slot=a`（A/B，`ro.build.ab_update=true`）；`update_engine` 服务运行中（android.os.UpdateEngineService）；**本 ROM 未接入 bootcontrol HAL**——`android.os.BootControl`（ServiceManager "bootcontrol" 服务缺失返回 null）、HIDL `android.hardware.boot@1.x::IBootControl/default` 进程在跑但无框架 Java 桥接（SELinux 亦拒 shell `find`，system_app 通道无服务可连）；UpdaterState=IDLE；本地 OTA 策略默认 enabled |
| ASR-0446 下载路径 | 元数据下载到 `/data/ota_package/`（引擎可读；**Launcher 私有目录被 update_engine SELinux 拒绝**——avc denied { search } system_data_file，初版实测修正）；文件 0644（**update_engine 无 CAP_DAC_OVERRIDE，SELinux 拒 dac_override，0600 读不了，实测修正**）；字节数与 property_files 一致 |
| ASR-0446 校验如实性 | update_engine 日志：`Received a request of verifying payload metadata in /data/ota_package/payload_metadata.bin` + `Bad payload format -- invalid delta magic: 46414b45 Expected: 43724155`——虚假包被引擎如实拒绝，命令返回 verifyPayloadMetadata=false（未误报可用）；校验拒绝（引擎已处理）与 API 不可用（null+verifyError）区分上报 |
| ASR-0446 整包下载 | FotaDownloadUpdate 整包落盘 `filesDir/ota/fota_package.zip`（2795 字节与源一致），zip 内提取三个校验文件闭环校验 |
| ASR-0448 策略 | Set/IsLocalOtaEnabled 写后读回一致；关闭后 FotaStart（content:// 真实 Uri，testapp FotaStartLocal 事件）广播 -104 local ota disabled by policy 拒绝、FotaApply（file:// 配置）返回 success=false + error 拒绝；恢复 enabled 后 FotaStart 重新受理（accepted） |
| ASR-0449/0450 任务控制 | FotaCancel/FotaSuspend/FotaResume dispatch 成功，getUpdaterState 读回 IDLE/RUNNING/ERROR 随操作变化、stateText 正确；**本 ROM update_engine 无任务时 cancel 抛"Failed to open…No ongoing update to cancel"**（引擎行为，容错后服务与命令正常）；IDLE→PAUSED 非法转换被服务捕获如实上报；**真实 RUNNING→PAUSED→RUNNING 转换需有效签名 OTA payload 驱动**（本机无签名 OTA 包，虚假包在引擎属性校验处快速失败——受限验证，见硬件受限测试说明） |
| ASR-0451 回调消费 | 无效 URL 应用驱动 4 条回调广播：updater_state=IDLE（apply 前复位）→ RUNNING → progress=0.0 → ERROR（PrepareUpdateService 失败）；FotaCancel 触发 updater_state=IDLE 广播；testapp OtaCallbackRecorder 完整消费（GetOtaCallbackLog count/events/callbackTypeName/valueText 解析正确）；FotaStart 的 code/msg 事件同样被消费（-104 事件） |
| ASR-0452 槽位 | GetSlotInfo：supported=true、halAvailable=false（**本 ROM 无 bootcontrol HAL 接入**）、currentSlot=0/currentSlotSuffix=_a 与 `getprop ro.boot.slot`/`ro.boot.slot_suffix` 一致、bootloaderSlots=a/_a；slots 两槽位 bootable/markedSuccessful=unknown（不可查询）+ note 说明；nextBootSlot=-1；SetSwitchSlotOnReboot dispatch 成功（AIDL 送达）——**可启动/启动成功标志与下次启动槽位查询需 ROM 接入 bootcontrol 服务后提供**；按用户决策（2026-08-11）ASR-0452 维持"部分完成（受限于 ROM）"（见需求文档备注与 Sheet1 第二节/第五节） |
| 异常路径 | 全部命令缺参/非法 JSON/URL 不可达均如实报错不 crash；UpdateEngine fork 方法集（bind(UpdateEngineCallback) 缺失致服务崩溃）经运行时方法发现 + Throwable 容错修复 |
| 测试后设备恢复 | 本地 OTA 策略恢复 enabled=true；UpdaterState=IDLE；/data/ota_package 与 filesDir/ota 测试文件已清理；槽位未切换（未触发重启）；adb reverse 已移除 |
