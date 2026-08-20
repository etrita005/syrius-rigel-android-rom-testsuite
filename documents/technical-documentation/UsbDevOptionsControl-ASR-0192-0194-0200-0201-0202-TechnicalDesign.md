# USB 模式/adb/开发者选项管控（ASR-0192/0194/0200/0201/0202）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 底层机制 |
|---|---|---|---|
| ASR-0192 | 设置 USB 默认模式 | 设置/查询 USB 默认模式（充电/MTP/PTP 等） | `Settings.Global default_usb_configuration`（MTK 键，平台签名直写） |
| ASR-0194 | 禁用/启用 adb 端口 | 设置/查询 USB 调试开关 | device owner `dpm.setGlobalSetting(Settings.Global.ADB_ENABLED)` |
| ASR-0200 | 管控开发者选项入口 | 隐藏/显示设置中"开发者选项"入口 | `DISALLOW_DEBUGGING_FEATURES` 用户限制 + `DevelopmentSettings` 组件禁用 |
| ASR-0201 | 获取/设置是否允许 USBDEBUG | 同 ASR-0194（共用 Set/IsAdbEnabled） | 同上 |
| ASR-0202 | 查询/设置是否开启 USBDEBUG 模式 | 同 ASR-0194/0201 | 同上 |

**归属**：「Launcher（MDM）」（dpm.setGlobalSetting / UserManager 限制 / 组件状态均为 device owner/平台签名能力）。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署。

## 2. 技术选型与可行性核验

| 方案 | 说明 | 结论 |
|---|---|---|
| `dpm.setGlobalSetting(ADB_ENABLED)`（0194/0201/0202） | device owner 公开接口写 Settings.Global.adb_enabled；查询直读 | **采用** |
| `Settings.Global default_usb_configuration`（0192） | MTK 默认 USB 配置键，平台签名 WRITE_SECURE_SETTINGS 直写 | **采用** |
| `DISALLOW_DEBUGGING_FEATURES` + 组件禁用（0200） | UserManager 限制（禁止调试功能，设置页开发者选项不可用）+ `setComponentEnabledSetting(DevelopmentSettings, DISABLED)`（入口隐藏） | **采用** |

**真机核验记录（2026-08-13/08-14，userdebug）——重要行为发现**：

1. **`SetAdbEnabled(false)` 会真实切断 adb 连接**：adb_enabled=0 → SystemServer AdbSettingsObserver → USB 复合功能移除 adb（设备接口仅剩 PTP class 06）→ adb 断连（`adb devices` 清空）。**该行为是 ASR-0194"禁用 adb 端口"的预期语义**；且 adb_enabled 持久化，重启后开机强制执行（无 adb 功能）——**恢复需设备侧动作**：testapp UI 按钮（"Enable USB Debugging (adb)"，与 IPC 同引擎）或 DO 后续命令（需要其他通道）。
2. **`SetDevOptSettingEnterHidden(hidden=true)` 除隐藏入口外还设置 `DISALLOW_DEBUGGING_FEATURES`**，同样触发 adb_enabled=0 → **adb 断连（副作用）**；限制持久化，重启后重新生效。真机完整闭环：隐藏 → adb 断连（PTP-only 验证）→ 用户重启设备 adb 仍不可达（限制开机重新生效）→ **经 testapp UI 按钮恢复**（"Show Developer Options Entry" + "Enable USB Debugging (adb)"，UI 与 IPC 同引擎）→ adb 恢复、限制清除、DO 保持。
3. 恢复路径记录：**IPC 依赖 adb，adb 被禁用后唯一的免物理操作恢复通道是设备本地 UI（testapp 按钮）或 GGR 系统侧能力**；测试用例设计必须包含恢复步骤（本批次经用户 UI 操作恢复两次，全程设备无 wipe）。

## 3. 命令接口定义

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetDefaultUsbMode` | usbMode（int，必填，≥0） | Boolean（设置写入结果） | ASR-0192 |
| `GetDefaultUsbMode` | 无 | int（-1=未设置/获取失败） | ASR-0192 |
| `SetAdbEnabled` | enabled（Boolean，必填） | Boolean（setGlobalSetting 结果；**false 会切断 adb 连接**） | ASR-0194/0201/0202 |
| `IsAdbEnabled` | 无 | Boolean（adb_enabled 读回） | ASR-0194/0201/0202 |
| `SetDevOptSettingEnterHidden` | hidden（Boolean，必填） | Boolean（**hidden=true 设置 DISALLOW_DEBUGGING_FEATURES，会切断 adb**） | ASR-0200 |
| `IsDevOptSettingEnterHidden` | 无 | Boolean（限制或组件禁用任一成立） | ASR-0200 |

**调用示例**：

```java
Map<String, Object> p = new HashMap<>();
p.put("enabled", false);
Map result = api.onEvent("SetAdbEnabled", p);
// {"RESULT":true}（随后 adb 断连——预期语义；恢复需设备侧 UI 或后续通道）
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/
├── syrius/utils/UsbUtils.java                     # setDefaultUsbMode/getDefaultUsbMode（MTK 键）
│                                                 # setAdbEnabled/isAdbEnabled（dpm.setGlobalSetting）
│                                                 # setDeveloperOptionsHidden/isDeveloperOptionsHidden
│                                                 #   （DISALLOW_DEBUGGING_FEATURES + DevelopmentSettings 组件）
├── syrius/service/command/usb/                   # Set/GetDefaultUsbMode、Set/IsAdbEnabled、
│                                                 # Set/IsDevOptSettingEnterHidden（既有）
└── syrius/service/ApiBinder.java                  # 既有注册

testapp/
└── src/main/java/com/hmdm/testapp/
    ├── TestActions.java                           # 6 个事件（Set/IsAdbEnabled、Set/GetDefaultUsbMode、
    │                                             #   Set/IsDevOptSettingEnterHidden、UsbDevOptionsStateLocal）
    ├── UsbDevOptionsVerifier.java                 # 新增：adb_enabled/default_usb_configuration/
    │                                             #   DevelopmentSettings 组件状态本地探针
    ├── UsbDevOptionsControlTestActivity.java      # 新增测试页（含恢复按钮——UI 与 IPC 同引擎，
    │                                             #   adb 断连后的本地恢复通道）
    └── src/main/res/layout/activity_usb_dev_options_test.xml
```

## 5. 执行逻辑

```
SetAdbEnabled(enabled): dpm.setGlobalSetting(admin, ADB_ENABLED, enabled?"1":"0") → true
  （enabled=false 时 SystemServer 观察器移除 USB adb 功能 → adb 断连，持久化，重启仍生效；
    恢复：SetAdbEnabled(true) 需设备侧通道，或 testapp UI 按钮）
IsAdbEnabled(): Settings.Global.adb_enabled == 1
SetDefaultUsbMode(usbMode): Settings.Global.putInt("default_usb_configuration", usbMode)
GetDefaultUsbMode(): Settings.Global.getInt(...)（-1 未设置）
SetDevOptSettingEnterHidden(hidden):
  hidden=true → userManager.setUserRestriction(DISALLOW_DEBUGGING_FEATURES, true)
              + pm.setComponentEnabledSetting(DevelopmentSettings, DISABLED, DONT_KILL_APP)
  hidden=false → 限制清除 + 组件 ENABLED
  （限制设置会触发 adb_enabled=0 → adb 断连，同 SetAdbEnabled(false) 链路）
IsDevOptSettingEnterHidden(): 限制存在 || 组件禁用
```

**安全设计**：adb 禁用为持久化系统设置（重启仍生效）——文档明确恢复路径；调用方经 ApiBinder 门禁。

## 6. 权限与归属

- ASR-0192：平台签名直写 Settings.Global（WRITE_SECURE_SETTINGS 既有声明）；
- ASR-0194/0201/0202：device owner 公开 setGlobalSetting；
- ASR-0200：UserManager 限制（DO）+ setComponentEnabledSetting（CHANGE_COMPONENT_ENABLED_STATE 签名权限既有声明）；
- testapp：本地探针仅读公开 Settings（无新增权限）；
- 无 ROM 侧代码改动，无 root 依赖。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺参（enabled/usbMode/hidden） | testapp 侧返回 missing parameter，不 crash |
| usbMode 非法（<0） | 命令返回 false |
| 禁用 adb 后连接丢失 | 预期语义（0194）；持久化+重启仍生效；恢复=设备侧 UI 按钮或 DO 后续命令（文档记录，测试用例含恢复步骤） |
| 隐藏开发者选项的 adb 副作用 | DISALLOW_DEBUGGING_FEATURES 同时禁止调试功能（adb 断连）——需求语义外的联动，如实记录；恢复同 adb 通道 |
| 重启后 adb 不可达 | 限制/设置持久化、开机强制执行（真机验证）；须经 UI 恢复 |
| 默认 USB 模式未设置 | GetDefaultUsbMode=-1（键不存在），如实返回 |
| 测试后状态 | adb_enabled=1、default_usb_configuration 恢复（本机基线未设置）、开发者选项恢复显示、DO 在位 |

## 8. 真机验证记录（2026-08-13/08-14，Android 13 / API 33 userdebug，平台签名 + device owner）

| 核验项 | 结果 |
|---|---|
| ASR-0192 | 基线 GetDefaultUsbMode=-1（键未设置）；SetDefaultUsbMode(1)→Get=1、`settings get global default_usb_configuration`=1；Set(2)→Get=2；`settings delete` 恢复-1 |
| ASR-0200 隐藏 | SetDevOptSettingEnterHidden(true) → **adb 断连**（DISALLOW_DEBUGGING_FEATURES 生效，接口仅剩 PTP class 06）——隐藏效果与副作用同时确认 |
| ASR-0200 恢复 | 用户重启设备后 adb 仍不可达（限制开机重新生效）→ **testapp UI 按钮恢复**（Show Dev Options + Enable USB Debugging）→ IsDevOptSettingEnterHidden=false、组件 default |
| ASR-0194/0201/0202 禁用 | SetAdbEnabled(false) → adb_enabled=0、USB 功能移除（PTP-only）、adb 断连（预期语义） |
| ASR-0194/0201/0202 恢复与查询 | testapp UI 按钮 Enable USB Debugging → adb 恢复、IsAdbEnabled=true、adb_enabled=1；查询往返一致 |
| 测试后状态 | 全部复位（adb_enabled=1、usb 模式未设置、开发者选项可见）；DO 在位；IPC bound=true |
| 恢复通道 | 本批次经用户 UI 操作恢复两次（无 wipe）——testapp UI 与 IPC 同引擎，是 adb 断连后的本地恢复通道（文档记录） |
