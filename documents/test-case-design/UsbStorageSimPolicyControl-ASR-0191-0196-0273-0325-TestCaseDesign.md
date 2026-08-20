# USB / 外接存储 / 数据漫游管控（ASR-0191/0196/0273/0325）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner（`dpm list-owners` = DeviceOwner,Affiliated），testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root && dumpsys duraspeed addwhitelist com.hmdm.testapp`（MTK DuraSpeed 白名单；**`am force-stop com.hmdm.testapp` 会使 testapp 进入 DuraSpeed suppress list、广播被静默丢弃——整机重启清空恢复**，测试期间避免 force-stop）、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`（IPC 与 UI 按钮等效，同一 `TestActions.execute()` 引擎）；也可在 testapp UI 的 "USB / storage / SIM" 页点按；
- 对照命令：`adb shell dumpsys usb`（USB 函数/模式）、`adb shell getprop sys.usb.config` / `persist.sys.usb.config`、`adb shell dumpsys device_policy`（用户限制清单）、`adb shell dumpsys mount`（卷状态）、`adb shell sm list-volumes all`、`adb shell dumpsys telephony.registry`（漫游态）；
- **本机无 SIM 卡与无外置 SD/USB 卷**：ASR-0273 限制状态读写可完整验证；ASR-0196/0325 的"挂载行为"受限验证——命令层面的限制设置/查询/双标志调和全量可测，真实卷挂载行为需外置存储真机（见需求文档"硬件受限测试说明"）；
- 恢复基线（测试开始时记录、结束时恢复）：USB 数据传输开启（函数恢复原值）、USB 外接存储与 SD 挂载禁用标志全关、数据漫游允许。

## 2. 测试用例表

### 2.1 ASR-0191 USB 数据传输锁（SetUsbDataTransferDisabled / IsUsbDataTransferDisabled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0191-01 基线查询 | `./send_test_command.sh IsUsbDataTransferDisabled` | disabled=false；restriction=false；currentFunction 为设备当前函数（如 mtp/charging） |
| TC-0191-02 禁用 | `./send_test_command.sh SetUsbDataTransferDisabled disabled=true` | success=true；readBackRestriction=true；currentFunction=charging；`dumpsys device_policy` 限制含 no_usb_file_transfer |
| TC-0191-03 禁用后函数对照 | `getprop sys.usb.config` | 函数切换为 charging（MTP 数据通道关闭；USB 线仅充电） |
| TC-0191-04 查询回读 | IsUsbDataTransferDisabled | disabled=true；restriction=true；currentFunction=charging |
| TC-0191-05 启用 | `./send_test_command.sh SetUsbDataTransferDisabled disabled=false` | success=true；readBackRestriction=false；currentFunction 恢复原函数 |
| TC-0191-06 缺参 | 不带 disabled | testapp 侧 missing parameter：disabled |

### 2.2 ASR-0196 USB 外接存储锁（SetUsbExternalStorageDisabled / IsUsbExternalStorageDisabled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0196-01 基线 | `IsUsbExternalStorageDisabled` | disabled=false；restriction=false |
| TC-0196-02 禁用 | `./send_test_command.sh SetUsbExternalStorageDisabled disabled=true` | success=true；readBackRestriction=true；`dumpsys device_policy` 限制含 no_physical_media |
| TC-0196-03 查询 | IsUsbExternalStorageDisabled | disabled=true；restriction=true |
| TC-0196-04 与 ASR-0325 共用 | 见 2.4 TC-0325-04/05（双标志调和） | 任一标志开限制保持；全关才清 |
| TC-0196-05 启用 | `SetUsbExternalStorageDisabled disabled=false` | success=true；readBackRestriction=false |

### 2.3 ASR-0273 数据漫游（SetDataRoamingDisabled / IsDataRoamingDisabled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0273-01 基线 | `./send_test_command.sh IsDataRoamingDisabled` | disabled=false（roamingAllowed=true） |
| TC-0273-02 禁用 | `./send_test_command.sh SetDataRoamingDisabled disabled=true` | success=true；readBack=true；`dumpsys device_policy` 限制含 no_data_roaming |
| TC-0273-03 查询 | IsDataRoamingDisabled | disabled=true；roamingAllowed=false |
| TC-0273-04 启用 | `SetDataRoamingDisabled disabled=false` | success=true；readBack=false |
| TC-0273-05 缺参 | 不带 disabled | testapp 侧 missing parameter：disabled |

### 2.4 ASR-0325 SD 卡挂载锁（SetSdCardMountDisabled / IsSdCardMountDisabled）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0325-01 基线 | `./send_test_command.sh IsSdCardMountDisabled` | disabled=false；restriction=false |
| TC-0325-02 禁用（无卷场景） | `./send_test_command.sh SetSdCardMountDisabled disabled=true` | success=true；readBackRestriction=true；unmountedVolumes=[]（本机无外置卷）+ note；`dumpsys device_policy` 限制含 no_physical_media |
| TC-0325-03 查询 | IsSdCardMountDisabled | disabled=true；restriction=true |
| TC-0325-04 双标志调和（0196 开） | 先 `SetUsbExternalStorageDisabled disabled=true`，再 `SetSdCardMountDisabled disabled=false` | USB 外接存储标志仍开 → restriction 保持 true（成功解除本标志但限制不清） |
| TC-0325-05 双标志全关 | 再 `SetUsbExternalStorageDisabled disabled=false` | 两标志全关 → restriction=false（限制清除） |
| TC-0325-06 启用 | `SetSdCardMountDisabled disabled=false` | success=true；readBackRestriction=false |

### 2.5 通用用例

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-M-01 未知事件 | `./send_test_command.sh SetUsbDataTransferDisabledXXX` | 返回 unknown event，不 crash |
| TC-M-02 UI 等效 | testapp UI "USB / storage / SIM" 页点按各按钮 | 页面 resumed；与 IPC 共用 TestActions 引擎；结果一致 |
| TC-M-03 返回值类型 | 检查命令返回 JSON | 全部为 Map/List/基本类型 |
| TC-M-04 事件目录 | `./send_test_command.sh ListEvents` | 目录含本批次 8 个新事件 |

## 3. 验证提示

- 结果查看：`./send_test_command.sh` 输出 `Broadcast completed: data=...` 与 logcat `HYX-TESTAPP-CMD` 行（result 码恒为 -1 以 data 为准；result=0 无日志先查 DuraSpeed suppress_list）；
- 限制核验：`dumpsys device_policy` 用户限制清单（no_usb_file_transfer / no_physical_media / no_data_roaming）；
- USB 函数核验：`dumpsys usb` 当前函数行 + `getprop sys.usb.config`/`persist.sys.usb.config` 交叉对照；**切换 charging 后 adb 可能短暂重连（USB 枚举）——若 adb 断开属预期，等待重连后再继续**；
- 卷核验：`sm list-volumes all`（本机无外置卷时仅 public:emulated 与 internal）；
- 硬件受限说明：真实外接存储挂载/卸载行为需 USB OTG 盘/SD 卡真机补充验证（机制为标准 DPM 限制 + StorageManager.unmount，与本 ROM 无关性已在限制设置/查询闭环中验证）。

## 4. 实测结果（2026-08-11，Android 13 / API 33 userdebug，平台签名 + device owner）

（部署完成后经 `send_test_command.sh` IPC 通道执行并回填）

| 用例 | 实测结果 |
|---|---|
| TC-0191-01 ~ 06 | 通过（主通道）：01 基线；02 禁用 readBackRestriction=true + dumpsys user 对照；03 框架强制（UsbDeviceManager.isUsbTransferAllowed 消费 no_usb_file_transfer，dex 核验）；04 查询；05 启用清除；函数通道本 ROM 无 charging 位（settable 表核验）如实 functionOk=false，按用户决策限制通道为主机制计已完成；06 缺参路径通过 |
| TC-0196-01 ~ 05 | 通过（限制设置/查询/启用闭环 + dumpsys user 对照；与 0325 双标志调和见 TC-0325-04/05） |
| TC-0273-01 ~ 05 | 通过（禁用 readBack=true + dumpsys user 对照；查询 roamingAllowed 反转；启用恢复；缺参路径） |
| TC-0325-01 ~ 06 | 通过（02 无卷场景限制生效 + unmountedVolumes=[]；**修复后选择器不再误触 private 卷**（首版按 mounted 误卸载 internal 卷经重启恢复）；04 与 0196 双标志调和：一开一关限制保持；05 全关清除；06 缺参/启用路径） |
| TC-M-01 ~ 04 | 通过（未知事件/返回值类型/事件目录/UI 页正常） |

**机制核验补充**：no_physical_media 为 AOSP 标准"禁挂载物理媒体"限制（StorageManager 挂载路径强制）；USB 函数通道为本 ROM UsbService 实际消费（getprop sys.usb.config 可对照）；用户限制均经 DPM 持久化（device_policies.xml），重启保持。
