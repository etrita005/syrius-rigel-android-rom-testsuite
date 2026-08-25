# USB / 外接存储 / 数据漫游管控（ASR-0191/0196/0273/0325）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 实现机制 |
|---|---|---|---|
| ASR-0191 | USB 功能 | 禁用/启用 USB 数据传输（USB 线是否能使用） | `no_usb_file_transfer` 用户限制 + 反射 `UsbManager.setCurrentFunction("charging")`（禁用）/恢复原函数（启用） |
| ASR-0196 | USB 功能 | 获取/设置是否禁用 USB 外接存储设备 | `no_physical_media`（DISALLOW_MOUNT_PHYSICAL_MEDIA）用户限制（与 ASR-0325 共用，双标志独立） |
| ASR-0273 | SIM卡/卡槽 | 禁用数据漫游数据业务 | `no_data_roaming`（DISALLOW_DATA_ROAMING）用户限制（MdmUtils 既有引擎，补齐命令注册） |
| ASR-0325 | SD卡 | 禁用/启用 SD卡挂载 | `no_physical_media` 用户限制（与 ASR-0196 共用）+ 禁用时卸载已挂载可移除卷（尽力） |

**归属**：ASR-0191/0196/0325 为 Launcher（MDM）+ 系统 API（device owner 用户限制 + @hide UsbManager）；ASR-0273 为 Launcher（MDM）+ 系统 API（device owner 用户限制）。无 ROM 侧代码改动。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署。

## 2. 技术选型与可行性核验

### 2.1 ASR-0191 USB 数据传输锁

- 双通道落地（禁用时同时执行，查询分别回显）：
  1. **用户限制通道**：`dpm.addUserRestriction(admin, "no_usb_file_transfer")`（UserManager.DISALLOW_USB_FILE_TRANSFER）——历史 `lockUsbStorage` 既有机制，框架/供应商消费该限制拦截 USB 文件传输；
  2. **USB 函数通道**：反射 @hide `UsbManager.setCurrentFunction(String[, boolean])`（MANAGE_USB 签名权限，ASR-0046 批次已声明，平台签名自动授予）置 `"charging"`——USB 只充电、MTP/PTP/RNDIS 等数据函数立即关闭（与系统"仅充电"模式同路径）；
- 禁用前捕获当前函数（`getCurrentFunctions()` 位掩码）持久化于 SharedPreferences `usb_storage_policy.previousUsbFunctionMask`；启用时经 `setCurrentFunctions(long)` 恢复；
- **2026-08-11 真机核验（函数通道 ROM 差异）**：本 ROM 的 UsbManager 为 MTK fork 方法集——`getCurrentFunction()` 不存在（NoSuchMethodException，运行时方法集 dump 核验：仅有 `getCurrentFunctions()/setCurrentFunctions(long)/setCurrentFunction(String,boolean)` 等）；`setCurrentFunction("charging", true)` 抛 IllegalArgumentException（UsbService.setCurrentFunction 内部字符串表无 "charging"）；`setCurrentFunctions(FUNC_CHARGING=1)` 同样被拒（UsbService.setCurrentFunctions → `areSettableFunctions` 校验失败，dex 反编译核验本 ROM settable 表仅含位 {4,8,16,32,1024,32768} 与 1056 组合，无 charging 位）；`getCurrentFunctions()` 不反映实际函数态（恒 0，sys.usb.config=adb 对照）；**本 ROM 无"仅充电"函数可设**——函数通道如实上报 functionOk=false；
- **主通道强制核验（通过）**：`no_usb_file_transfer` 限制被本 ROM UsbDeviceManager 强制消费——services.jar dex 反编译：`UsbHandler.isUsbTransferAllowed()` 直接 `UserManager.hasUserRestriction("no_usb_file_transfer")`（USB 数据传输挂载/使能路径拦截），限制设置/读回/清除闭环通过；按用户决策以限制通道为主机制，ASR-0191 计为已完成（函数通道对 ROM 适配后同样可用，引擎无需改动）；
- 查询：`IsUsbDataTransferDisabled` 返回标志 + 限制状态 + 当前 USB 函数（`getCurrentFunctions` 位解码：charging/mtp/ptp/rndis/midi，本 ROM 恒 0 如实上报）；
- 标志持久化 `usb_storage_policy.dataTransferDisabled`，进程重启/开机 `syncPolicy` 重新武装。

### 2.2 ASR-0196 / ASR-0325 物理媒体限制（共用引擎）

- Android 对"物理媒体挂载"只有一条标准用户限制 `no_physical_media`（DISALLOW_MOUNT_PHYSICAL_MEDIA）：设置后 StorageManager 拒绝物理卷（USB 外接存储与 SD 卡）挂载；
- 两需求实现为**两个独立标志共用一个限制**（同蓝牙可发现模式引擎形制）：`externalStorageDisabled`（ASR-0196）与 `sdMountDisabled`（ASR-0325）独立持久化，任一标志开启即置限制，两标志全关才清限制（`reconcilePhysicalMediaRestriction`）；
- ASR-0325 禁用时额外**尽力卸载已挂载的可移除公共卷**（反射 `StorageManager.getVolumes` + `unmount`，**双重校验：state==2 mounted 且 `getDisk().isRemovable()` 才卸载**——2026-08-11 真机修正：首版仅按 mounted 判断误触 internal/private 卷（unmount("private") 引发 17s binder 超时与 emulated 卷卸载，设备重启恢复），修复后选择器与 ASR-0197 批次同口径，本机无外置卷如实返回 unmountedVolumes=[] + note）；
- 本机无外置 SD/USB 卷：命令如实返回限制状态 + `unmountedVolumes` 空 + note（真实挂载/卸载行为需可移除存储真机，见需求文档"硬件受限测试说明"）。

### 2.3 ASR-0273 数据漫游

- `MdmUtils.setDataRoamingAllowed/isDataRoamingDisallowed` 既有实现（DISALLOW_DATA_ROAMING 用户限制），Sheet1 部分完成缺口为"未注册 AIDL 命令"——本批次补 `SetDataRoamingDisabled`/`IsDataRoamingDisabled` 命令，写后 `getUserRestrictions` 读回核对。

## 3. 命令接口定义（SystemApiInterface.onEvent）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetUsbDataTransferDisabled` | disabled | Map：{success, disabled, restrictionOk, functionOk, readBackRestriction, currentFunction} 或 "missing parameter" | ASR-0191 |
| `IsUsbDataTransferDisabled` | 无 | Map：{success, disabled, restriction, currentFunction, usbFunctions} | ASR-0191 |
| `SetUsbExternalStorageDisabled` | disabled | Map：{success, disabled, readBackRestriction, restriction} 或 "missing parameter" | ASR-0196 |
| `IsUsbExternalStorageDisabled` | 无 | Map：{success, disabled, restriction} | ASR-0196 |
| `SetSdCardMountDisabled` | disabled | Map：{success, disabled, readBackRestriction, restriction, unmountedVolumes[], note?} 或 "missing parameter" | ASR-0325 |
| `IsSdCardMountDisabled` | 无 | Map：{success, disabled, restriction} | ASR-0325 |
| `SetDataRoamingDisabled` | disabled | Map：{success, disabled, readBack} 或 "missing parameter" | ASR-0273 |
| `IsDataRoamingDisabled` | 无 | Map：{disabled, roamingAllowed} | ASR-0273 |

**调用示例**：

```java
Map<String, Object> p = new HashMap<>();
p.put("disabled", true);
Map result = api.onEvent("SetUsbDataTransferDisabled", p);
// {"RESULT":{"currentFunction":"charging","success":true,"disabled":true,"readBackRestriction":true,...}}

Map result2 = api.onEvent("IsSdCardMountDisabled", new HashMap<>());
// {"RESULT":{"success":true,"disabled":true,"restriction":true}}
```

**广播通道**：

```bash
./send_test_command.sh SetUsbDataTransferDisabled disabled=true
./send_test_command.sh IsUsbDataTransferDisabled
./send_test_command.sh SetSdCardMountDisabled disabled=true
./send_test_command.sh SetDataRoamingDisabled disabled=true
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/UsbStoragePolicyManager.java      # 新增：0191/0196/0325 引擎（限制共用 + USB 函数通道 + 卷卸载 + syncPolicy）
├── service/command/usb/
│   ├── SetUsbDataTransferDisabled.java / IsUsbDataTransferDisabled.java       # 新增：ASR-0191
│   ├── SetUsbExternalStorageDisabled.java / IsUsbExternalStorageDisabled.java # 新增：ASR-0196
│   └── SetSdCardMountDisabled.java / IsSdCardMountDisabled.java               # 新增：ASR-0325
├── service/command/roaming/
│   ├── SetDataRoamingDisabled.java / IsDataRoamingDisabled.java               # 新增：ASR-0273（MdmUtils 既有引擎）
├── service/ApiBinder.java                  # 注册 8 个新命令
├── service/ApiService.java                 # onCreate：UsbStoragePolicyManager.syncPolicy
└── broadcast/BootCompletedReceiver.java    # 开机同步同上

testapp/
├── src/main/java/com/hmdm/testapp/
│   ├── UsbStorageTestActivity.java         # 新增测试页
│   ├── TestActions.java                    # 新增 8 个事件与事件目录
│   ├── MainActivity.java                   # 新增入口按钮
│   └── res/layout/activity_usb_storage_test.xml  # 新增布局
```

## 5. 执行逻辑

```
SetUsbDataTransferDisabled(disabled):
  1. 禁用：applyRestriction(no_usb_file_transfer, true)；
          捕获当前 USB 函数（首次）→ setCurrentFunction("charging") → 持久化标志
  2. 启用：clearRestriction(no_usb_file_transfer)；恢复 previousUsbFunction（或跳过恢复）→ 清标志
  3. getUserRestrictions 读回核对 + getCurrentFunction 附报

SetSdCardMountDisabled(disabled):
  1. 持久化 sdMountDisabled 标志
  2. 禁用时：枚举 StorageManager.getVolumes，state==2（mounted）的可移除卷逐个 unmount（尽力，失败不阻断）
  3. reconcilePhysicalMediaRestriction()（任一标志开即置限制，全关才清）
  4. 读回限制状态，返回 unmountedVolumes 明细
```

**安全设计**：无 shell 命令注入面（限制键为常量）；USB 函数参数为引擎内常量（"charging"）或此前捕获值；卷 ID 仅作 unmount 参数（StorageManager 侧有权限校验）。

## 6. 权限与归属

- 用户限制：device owner 公开接口，无需 uses-policy 声明；
- `UsbManager.setCurrentFunction`/`getCurrentFunction`：MANAGE_USB（signature\|privileged，manifest 既有声明，平台签名自动授予）；
- `StorageManager.unmount`：MOUNT_UNMOUNT_FILESYSTEMS（manifest 既有声明）；
- 无 `device_admin.xml` 变更、无 AIDL/lib 模块变更、无需重启 framework。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 非 device owner | 限制通道静默失败（readBackRestriction 不随动），函数通道独立执行；命令如实返回各通道结果 |
| `setCurrentFunction` 本 ROM 无该方法 | 捕获异常返回 functionOk=false，限制通道继续生效（双通道解耦） |
| 启用时 previousUsbFunction 为空 | 不恢复函数（保持当前值），清备份键 |
| 无外置卷时禁用 SD 挂载 | 限制生效 + unmountedVolumes=[] + note "no mounted removable volume"（真实卸载行为需 SD 真机） |
| 0196 与 0325 标志冲突 | 共用限制引擎：一开一关时限制保持开启；两关才清除（读回如实） |
| 进程重启/开机 | syncPolicy 重放持久化标志（限制与函数通道幂等重应用） |

## 8. 真机验证记录

（2026-08-11 批次；命令级用例结果见测试用例设计文档，此处记录机制核验关键结论）

| 核验项 | 结果 |
|---|---|
| no_usb_file_transfer 限制读写 | 通过：设置/读回/清除闭环 + `dumpsys user` 对照；框架强制经 UsbDeviceManager.isUsbTransferAllowed 消费（dex 核验） |
| USB 函数切 charging/恢复 | **本 ROM 无 charging 函数位**（settable 表核验 + 字符串表核验），函数通道 functionOk=false 如实上报；限制通道为主机制 |
| no_physical_media 双标志共用 | 通过：任一标志开限制在、全关才清（0196/0325 双向调和闭环） |
| SD 挂载禁用（无卷场景） | 通过：限制生效 + unmountedVolumes=[]（修复后的可移除卷选择器，未误触 private 卷） |
| 数据漫游限制读写 | 通过：设置/读回/清除闭环 + `dumpsys user` 对照 |
| 测试后设备恢复 | 通过（全标志关闭、限制清空；期间误卸载 internal 卷经重启恢复，emulated 正常挂载） |
