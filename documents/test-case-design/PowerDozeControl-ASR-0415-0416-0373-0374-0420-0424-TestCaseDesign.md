# 电源/Doze/杂项管控（ASR-0415/0416/0373/0374/0420/0424）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000，`MANAGE_ETHERNET_NETWORKS`/`DEVICE_POWER` granted）并设为 device owner，testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root && dumpsys duraspeed addwhitelist com.hmdm.testapp`；
- **唤醒/休眠用例前置**：`svc power stayon false`（本机充电中默认 stay_on_while_plugged_in=7 会抑制休眠），用例结束后恢复 `svc power stayon true`；
- 测试通道：`./send_test_command.sh <event> key=value ...`（IPC 与 UI 按钮等效，同一 `TestActions.execute()` 引擎）；也可在 testapp UI 的 "Power / Doze / voice assistant" 页（PowerDozeTestActivity）与 "Wired NIC" 页（EthernetTestActivity）按对应按钮；
- 对照命令：`adb shell dumpsys power | grep mWakefulness`（唤醒态）、`adb shell cmd device_config get device_idle <key>` / `adb shell dumpsys deviceidle | grep -E "inactive_to|light_after"`（Doze 标志与生效常量）、`adb shell cmd deviceidle whitelist`（白名单对照，含 list 输出）、`adb shell settings get secure voice_interaction_service`（语音助手键）、`adb shell dumpsys ethernet`（以太网状态/配置）、`adb shell cat /data/data/com.hmdm.launcher/shared_prefs/doze_policy.xml` / `ethernet_policy.xml` / `voice_assistant_policy.xml`（持久化文件）；
- **硬件前提**：以太网用例的"真实网络建立"部分需插入网线（ETH 接口出现）；无网线时配置存储/查询/启停仍可验证（命令如实上报 available=false/interfaces=[]，不 crash）；唤醒/休眠需屏幕可控（无强约束锁屏）；
- 恢复基线：测试结束后——设备恢复亮屏、Doze 恢复运行（`SetDozeDisabled disabled=false`）、Doze 白名单清空（`SetDozeWhitelist packageNames=[]`）、语音助手恢复（`SetVoiceAssistantDisabled disabled=false`）、以太网配置恢复 DHCP、以太网恢复启用（`SetEthernetEnabled enabled=true`），三个 SharedPreferences 无残留策略。

## 2. 测试用例表

### 2.1 ASR-0415 唤醒设备（WakeUp / GetPowerState）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-PD-01 基线查询 | `./send_test_command.sh GetPowerState` | success=true；interactive=true（或如实返回当前态）；wakefulness=Awake；不 crash |
| TC-PD-02 休眠后唤醒 | 先 `GoToSleep`（见 2.2）使设备休眠，再 `./send_test_command.sh WakeUp` | success=true；interactiveBefore=false、interactiveAfter=true；wakefulness=Awake；屏幕点亮；`dumpsys power` mWakefulness=Awake |
| TC-PD-03 已唤醒时唤醒（幂等） | 设备亮屏时 `./send_test_command.sh WakeUp` | success=true（目标态已达成）；interactiveAfter=true |
| TC-PD-04 唤醒后查询 | `./send_test_command.sh GetPowerState` | success=true；interactive=true；wakefulness=Awake |

### 2.2 ASR-0416 休眠设备（GoToSleep / GetPowerState）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-PD-05 休眠设备 | 亮屏时 `./send_test_command.sh GoToSleep` | success=true；interactiveBefore=true、interactiveAfter=false；wakefulness=Asleep；屏幕熄灭；`dumpsys power` mWakefulness=Asleep |
| TC-PD-06 休眠后查询 | `./send_test_command.sh GetPowerState` | success=true；interactive=false；wakefulness=Asleep |
| TC-PD-07 已休眠时休眠（幂等） | 设备休眠时 `./send_test_command.sh GoToSleep` | success=true；interactiveAfter=false |
| TC-PD-08 休眠→唤醒闭环 | 依次 `GoToSleep` → `WakeUp` → `GetPowerState` | 三命令均 success=true；最终 interactive=true、wakefulness=Awake（设备恢复亮屏） |
| TC-PD-09 休眠被充电抑制场景 | `svc power stayon true` 下执行 `GoToSleep` | 可能 success=false + error（stayOn 抑制）——如实上报不误报；此为环境行为，测试以 TC-PD-05（stayon false 下）为准 |

### 2.3 ASR-0373 禁止/运行 Doze（SetDozeDisabled / IsDozeDisabled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-PD-10 基线查询 | `./send_test_command.sh IsDozeDisabled` | success=true；disabled=false；五标志均 null；系统对照 `dumpsys deviceidle` inactive_to=+30m0s0ms（ROM 默认） |
| TC-PD-11 禁止 Doze | `./send_test_command.sh SetDozeDisabled disabled=true` | success=true；disabled=true；五标志=604800000；系统对照 `cmd device_config get device_idle inactive_to`=604800000、`dumpsys deviceidle` inactive_to=+7d0h0m0s0ms、light_after_inactive_to=+7d0h0m0s0ms（DeviceIdleController onPropertiesChanged 已消费） |
| TC-PD-12 禁止态查询 | `./send_test_command.sh IsDozeDisabled` | success=true；disabled=true；inactiveTo=+7d0h0m0s0ms 如实返回 |
| TC-PD-13 恢复运行 Doze | `./send_test_command.sh SetDozeDisabled disabled=false` | success=true；disabled=false；五标志 null；系统对照常量回 ROM 默认（inactive_to=+30m0s0ms） |
| TC-PD-14 幂等 | 重复 `SetDozeDisabled disabled=true` / `disabled=false` 各一次 | 均 success=true；状态与系统对照一致 |
| TC-PD-15 缺参数 | `./send_test_command.sh SetDozeDisabled` | RESULT="missing parameter: disabled"（不 crash） |
| TC-PD-16 持久化（DeviceConfig 存储） | 禁止 Doze 后 `adb shell cmd device_config get device_idle inactive_to` | 标志 604800000 保持（DeviceConfig 持久）；`am force-stop com.hmdm.launcher` 后查询仍 disabled=true |

### 2.4 ASR-0374 Doze 白名单（SetDozeWhitelist / GetDozeWhitelist）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-PD-17 基线查询 | `./send_test_command.sh GetDozeWhitelist` | success=true；requested=[]（无持久化名单）；whitelistedPackages 为系统清单（含系统应用） |
| TC-PD-18 添加白名单 | `./send_test_command.sh SetDozeWhitelist 'packageNames=["com.hmdm.testapp","com.android.settings"]'` | success=true；added 含两包；kept=[]；channel=PowerWhitelistManager；系统对照 `cmd deviceidle whitelist` 清单包含两包（isIgnoringBatteryOptimizations=true） |
| TC-PD-19 白名单查询 | `./send_test_command.sh GetDozeWhitelist` | success=true；requested 两包均 whitelisted=true；whitelistedPackages 含两包 |
| TC-PD-20 增量更新 | `SetDozeWhitelist packageNames=["com.android.settings"]`（移除 testapp） | success=true；removed 含 com.hmdm.testapp；系统对照清单不再含该包 |
| TC-PD-21 清空白名单 | `./send_test_command.sh SetDozeWhitelist 'packageNames=[]'` | success=true；removed 含全部原名单包；系统对照清单不再含测试加入的包 |
| TC-PD-22 非法包名 | `./send_test_command.sh SetDozeWhitelist 'packageNames=["bad name!"]'` | failed 含 "invalid package name"；success=false 但 added/removed 如实、不 crash |
| TC-PD-23 未安装包 | `./send_test_command.sh SetDozeWhitelist 'packageNames=["com.example.not.installed"]'` | failed 含 "package not installed"；系统白名单不变 |
| TC-PD-24 缺参数 | `./send_test_command.sh SetDozeWhitelist` | RESULT="missing parameter: packageNames (array)" |
| TC-PD-25 持久化与重新下发 | 添加名单 → `am force-stop com.hmdm.launcher` → 重新拉起 → `GetDozeWhitelist` + `cmd deviceidle whitelist` 对照 | requested 保留（doze_policy.xml）；系统清单仍含名单包（ApiService.onCreate syncPolicy 重新下发）；清空后 force-stop 再查 requested=[] |
| TC-PD-26 与耗电白名单共存说明 | 先经 ASR-0016/0030 `SetIgnoreBatteryOptimizationWhitelist` 添加包，再 `SetDozeWhitelist` 替换 | 两通道共用系统同一清单；**doze 替换时对仍被耗电白名单策略持久化持有的包不移除（kept 字段如实返回），两策略不漂移**；名单外实时清单以最后一次实际下发为准 |

### 2.5 ASR-0420 禁用语音助手（SetVoiceAssistantDisabled / IsVoiceAssistantDisabled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-PD-27 基线查询 | `./send_test_command.sh IsVoiceAssistantDisabled` | success=true；disabled=false（本机 voice_interaction_service=null）；backedUp=false |
| TC-PD-28 禁用语音助手 | `./send_test_command.sh SetVoiceAssistantDisabled disabled=true` | success=true；disabled=true；voice_interaction_service=""；backedUp=true（原值已备份，若原值非空）；系统对照 `settings get secure voice_interaction_service` 为空 |
| TC-PD-29 禁用态查询 | `./send_test_command.sh IsVoiceAssistantDisabled` | success=true；disabled=true |
| TC-PD-30 恢复语音助手 | `./send_test_command.sh SetVoiceAssistantDisabled disabled=false` | success=true；disabled=false；voice_interaction_service 恢复原值（有备份）或删除键（无备份）；backedUp=false（备份已清除）；系统对照一致 |
| TC-PD-31 幂等 | 连续两次 `SetVoiceAssistantDisabled disabled=true` → 两次 `disabled=false` | 均 success=true；最终状态与基线一致 |
| TC-PD-32 缺参数 | `./send_test_command.sh SetVoiceAssistantDisabled` | RESULT="missing parameter: disabled" |

### 2.6 ASR-0424 配置有线网卡（SetEthernetConfig / GetEthernetConfig / SetEthernetEnabled / IsEthernetEnabled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-PD-33 基线查询 | `./send_test_command.sh GetEthernetConfig` | success=true；available=false（无网线）；interfaces=[]；enabled=true；config.mode=dhcp（或如实）；不 crash |
| TC-PD-34 配置 DHCP | `./send_test_command.sh SetEthernetConfig mode=dhcp` | success=true；mode=dhcp；applied.mode=dhcp（getConfiguration 读回核对）；persisted=true |
| TC-PD-35 配置静态 IP | `./send_test_command.sh SetEthernetConfig mode=static ipAddress=192.168.10.100 prefixLength=24 gateway=192.168.10.1 dns1=8.8.8.8` | success=true；applied 含 ipAddress/prefixLength=24/gateway/dnsServers=[8.8.8.8]；persisted=true；`ethernet_policy.xml` 镜像一致 |
| TC-PD-36 静态+双 DNS | 同 TC-PD-35 且 dns2=8.8.4.4 | success=true；dnsServers=[8.8.8.8, 8.8.4.4] |
| TC-PD-37 静态+代理 | `mode=static ... proxyHost=10.0.0.1 proxyPort=8080 'proxyExclusionList=["10.0.0.1","10.0.0.2"]'` | success=true；applied 含 proxyHost/proxyPort/proxyExclusionList |
| TC-PD-38 查询当前配置 | `./send_test_command.sh GetEthernetConfig` | success=true；config 与上次下发一致；persisted 镜像一致 |
| TC-PD-39 静态缺参 | `./send_test_command.sh SetEthernetConfig mode=static ipAddress=192.168.10.100` | error 含 "static mode requires ipAddress, prefixLength (0-32), gateway, dns1"；不落盘 |
| TC-PD-40 非法参数 | `mode=static prefixLength=33 ...` / 非法 IP | error（invalid static config / 校验失败）；不落盘 |
| TC-PD-41 非法 mode | `./send_test_command.sh SetEthernetConfig mode=foo` | error："invalid mode: foo (dhcp/static)" |
| TC-PD-42 缺 mode | `./send_test_command.sh SetEthernetConfig` | RESULT="missing parameter: mode (dhcp/static)" |
| TC-PD-43 指定接口 | `./send_test_command.sh SetEthernetConfig mode=dhcp iface=eth0` | success=true；iface=eth0（接口名过滤 eth\d 的默认/首选接口）；读回一致 |
| TC-PD-44 禁用以太网 | `./send_test_command.sh SetEthernetEnabled enabled=false` | success=true；enabled=false；系统对照 `dumpsys ethernet` Ethernet State=disabled；persisted=true |
| TC-PD-45 查询禁用态 | `./send_test_command.sh IsEthernetEnabled` | success=true；enabled=false；persisted=false 标志保留 |
| TC-PD-46 启用以太网 | `./send_test_command.sh SetEthernetEnabled enabled=true` | success=true；enabled=true；`dumpsys ethernet` Ethernet State=enabled |
| TC-PD-47 缺 enabled 参数 | `./send_test_command.sh SetEthernetEnabled` | RESULT="missing parameter: enabled" |
| TC-PD-48 持久化与重新下发 | 静态配置 + 禁用状态 → `am force-stop com.hmdm.launcher` → 重新拉起 → `GetEthernetConfig` + `IsEthernetEnabled` | persisted 镜像保留（ethernet_policy.xml）；syncPolicy 重新下发（getConfiguration 读回一致、Ethernet State=disabled） |
| TC-PD-49 接网线真实网络（需网线） | 插入网线后配置静态 IP（同网段）→ `ip addr show eth0` / `dumpsys ethernet` | eth0 接口出现（Tracking interfaces）；静态 IP 生效于接口；DHCP 模式下自动获取地址；真实网络建立（见硬件受限测试说明） |
| TC-PD-50 无残留 | 依次 `SetEthernetConfig mode=dhcp` → `SetEthernetEnabled enabled=true` → 查询 | success=true；`ethernet_policy.xml` 镜像为 dhcp/enabled=true（无残留策略）；状态与基线一致 |

## 3. 真机验收记录（2026-08-06，Android 13 / API 33 userdebug，平台签名 + device owner）

设备：MT6771（银星定制 ROM）；`MANAGE_ETHERNET_NETWORKS`/`DEVICE_POWER` granted=true（`dumpsys package` 核对）；以太网服务在线（`dumpsys ethernet` Ethernet State: enabled）但当前无网线。

| 用例 | 结果 | 备注 |
|---|---|---|
| TC-PD-01 | 通过 | 实测基线为休眠态（interactive=false、wakefulness=Dozing——测试前置时设备已熄屏进入 Dozing，如实返回） |
| TC-PD-02 | 通过 | 从 Dozing 态 WakeUp success=true（channel=wakeUp(long,String)）；interactiveBefore=false→After=true；mWakefulness=Dozing→Awake；屏幕点亮 |
| TC-PD-03 | 通过 | 亮屏时 WakeUp 幂等 success=true（interactiveBefore=true） |
| TC-PD-04 | 通过 | interactive=true、wakefulness=Awake |
| TC-PD-05 | 通过 | success=true（channel=goToSleep(long,int,int)、reason=1=DEVICE_ADMIN）；interactiveBefore=true→After=false；**本 ROM 熄屏后 wakefulness 为 Dozing（MTK 行为，非 AOSP Asleep）**、屏幕熄灭 |
| TC-PD-06 | 通过 | interactive=false、wakefulness=Dozing（如实返回本 ROM 熄屏态） |
| TC-PD-07 | 通过 | 已休眠再休眠幂等 success=true |
| TC-PD-08 | 通过 | 休眠→唤醒闭环，最终 interactive=true、Awake |
| TC-PD-09 | 通过（本 ROM 无抑制） | **本 ROM 实测 stayon=true 不抑制 goToSleep**（success=true、mWakefulness=Dozing，与设计文档假设不同——本 ROM 显式 goToSleep 直接生效，无环境抑制路径）；正式用例仍以 stayon=false 执行保持一致 |
| TC-PD-10 | 通过 | disabled=false、五标志 null、inactive_to=+30m（ROM 默认） |
| TC-PD-11 | 通过 | disabled=true；五标志=604800000（读回核对）；`dumpsys deviceidle` inactive_to/light_after_inactive_to=+7d0h0m0s0ms（DeviceIdleController onPropertiesChanged 即时消费）；**AOSP 标准键 device_idle_constants 写后无效（本 ROM 不消费）——引擎采用 DeviceConfig 通道** |
| TC-PD-12 | 通过 | disabled=true、inactiveTo=+7d0h0m0s0ms |
| TC-PD-13 | 通过 | 五标志 null、常量回 ROM 默认 |
| TC-PD-14 | 通过 | 幂等往返均 success=true |
| TC-PD-15 | 通过 | RESULT="missing parameter: disabled" |
| TC-PD-16 | 通过 | DeviceConfig 标志持久（`cmd device_config get device_idle inactive_to`=604800000）；force-stop 后仍 disabled=true |
| TC-PD-17 | 通过 | requested=[]；whitelistedPackages 为系统清单 |
| TC-PD-18 | 通过 | added 两包、kept=[]；`cmd deviceidle whitelist` 对照一致；isIgnoringBatteryOptimizations=true |
| TC-PD-19 | 通过 | 两包 whitelisted=true |
| TC-PD-20 | 通过 | removed 含 com.hmdm.testapp、系统清单同步移除 |
| TC-PD-21 | 通过 | 清空后系统清单无测试包 |
| TC-PD-22 | 通过 | failed 含 invalid package name |
| TC-PD-23 | 通过 | failed 含 package not installed |
| TC-PD-24 | 通过 | RESULT="missing parameter: packageNames (array)" |
| TC-PD-25 | 通过 | doze_policy.xml 持久；force-stop 后 ApiService.onCreate 重新下发（logcat syncPolicy: doze whitelist re-applied） |
| TC-PD-26 | 通过（机制） | 两通道共用清单；**kept 调和实测**：testapp 被耗电白名单持久化持有 → doze 替换不含 testapp 时 kept=["com.hmdm.testapp"]、系统白名单保留 testapp（`cmd deviceidle whitelist` 对照） |
| TC-PD-27 | 通过 | **实测基线即 disabled=true（本 ROM 出厂 voice_interaction_service=""——语音助手出厂默认禁用），如实返回**；backedUp=false |
| TC-PD-28 | 通过 | voice_interaction_service=""（原值本为空串，无可备份内容，backedUp=false 如实）；settings get secure 对照为空 |
| TC-PD-29 | 通过 | disabled=true |
| TC-PD-30 | 通过 | 无备份场景启用=删除键（voice_interaction_service=null、disabled=false）、backedUp=false |
| TC-PD-31 | 通过 | 幂等往返 success=true（禁用置空、启用删键） |
| TC-PD-32 | 通过 | RESULT="missing parameter: disabled" |
| TC-PD-33 | 通过 | available=false、interfaces=[]、enabled=true（无网线如实上报） |
| TC-PD-34 | 通过 | mode=dhcp；读回一致；persisted=true |
| TC-PD-35 | 通过 | 静态 IP 读回一致（192.168.10.100/24、gateway、dns） |
| TC-PD-36 | 通过 | dnsServers=[8.8.8.8, 8.8.4.4] |
| TC-PD-37 | 通过 | proxy 读回一致；**代码审查修复复核：代理排除列表经 force-stop + syncPolicy 重新下发后仍完整**（StringSet 往返 + 旧 String 格式兼容）；静态配置全量核对（IP/前缀/网关/双 DNS/代理逐项） |
| TC-PD-38 | 通过 | config/persisted 与下发一致 |
| TC-PD-39 | 通过 | error 提示缺 gateway/dns1；未落盘 |
| TC-PD-40 | 通过 | prefix=33 与非法 IP 均被拒（error），未落盘 |
| TC-PD-41 | 通过 | error invalid mode |
| TC-PD-42 | 通过 | RESULT="missing parameter: mode (dhcp/static)" |
| TC-PD-43 | 通过 | iface=eth0 指定生效 |
| TC-PD-44 | 通过 | `dumpsys ethernet` Ethernet State=disabled（轮询核对一致） |
| TC-PD-45 | 通过 | enabled=false、persisted 标志保留 |
| TC-PD-46 | 通过 | Ethernet State=enabled 恢复 |
| TC-PD-47 | 通过 | RESULT="missing parameter: enabled" |
| TC-PD-48 | 通过 | force-stop 后镜像保留、syncPolicy 重新下发（读回一致、State=disabled） |
| TC-PD-49 | 受限（硬件） | 本机无网线（eth 接口未出现），真实 DHCP/静态网络建立需插网线（见硬件受限测试说明） |
| TC-PD-50 | 通过 | 恢复 DHCP + enabled=true，ethernet_policy.xml 无残留策略 |

**备注**：唤醒/休眠用例在 `svc power stayon false` 下执行（本 ROM 实测 stayon=true 亦不抑制显式 goToSleep，前置仅为保持一致）；本 ROM 熄屏后 wakefulness 为 Dozing（MTK 行为，interactive=false 为主判据）；测试期间以 `dumpsys power`/`dumpsys deviceidle`/`dumpsys ethernet`/`cmd deviceidle whitelist` 系统接口级对照代替 UI 对照（本 ROM 无 Doze/以太网设置页入口）。

## 4. 硬件受限测试说明

- **无网线（本机）**：ETH 接口未出现（`getAvailableInterfaces`=[]、`/sys/class/net` 无 eth*），TC-PD-49（插网线后真实 DHCP/静态网络建立、接口地址核对）无法在本机执行；配置存储/读回核对/启停/持久化/重新下发（TC-PD-33~48、50）已全部通过机制级验收，真实网络建立需接入网线（或带 ETH 接口的扩展坞）的机器补充执行；
- **语音助手**：本机无任何合格 Assistant 应用/角色持有者，且**本 ROM 出厂 voice_interaction_service 即为空串（语音助手出厂默认禁用）**——TC-PD-28 的"禁用后语音助手不可用"以系统键置空 + 无角色持有者（dumpsys voiceinteraction 无活动服务）为验证口径；若设备存在合格 Assistant 角色持有者，角色通道可能仍解析助手（属 ASR-0094 角色引擎范围，见技术设计文档第 2.3 节）；
- **Doze 通道差异（本 ROM 关键发现）**：AOSP 标准的 `Settings.Global.device_idle_constants` 键在本 ROM **不被 DeviceIdleController 消费**（写后 `dumpsys deviceidle` 常量不变）——本 ROM Constants 类从 DeviceConfig（`device_idle` 命名空间）读取（services.jar dex 核验），引擎改用 `cmd device_config put device_idle <key>` 通道并逐键读回核对。
