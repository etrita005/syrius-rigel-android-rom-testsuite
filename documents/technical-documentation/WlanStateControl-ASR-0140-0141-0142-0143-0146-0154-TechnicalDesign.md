# WLAN 状态管控（ASR-0140/0141/0142/0143/0146/0154）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 底层机制 |
|---|---|---|---|
| ASR-0140 | 禁用/启用 WLAN | 设置/查询 WLAN 总开关状态 | `WifiManager.setWifiEnabled`（平台签名特权调用） |
| ASR-0142 | 打开/关闭 WLAN | 同 ASR-0140（共用 Set/IsWifiOpen） | 同上 |
| ASR-0143 | 强制打开 WLAN | WLAN 被关闭后周期强制重新打开 | **服务器 config 驱动**（`StatusControlService` 按 `config.getWifi()` 周期纠正，无 ApiBinder 命令） |
| ASR-0146 | 查询 WLAN MAC | 获取设备 WLAN MAC 地址 | device owner `dpm.getWifiMacAddress` |
| ASR-0141 | 禁止/允许用户改变 WLAN 设置 | 用户无法进入/修改 WLAN 设置 | device owner `DISALLOW_CONFIG_WIFI` 用户限制 |
| ASR-0154 | 禁止配置 WLAN | 同 ASR-0141（共用 Set/IsUserConfigWifiDisabled，WlanControl 文档交叉引用） | 同上 |

**归属**：「Launcher（MDM）」/「Launcher（MDM）+ 系统 API」（setWifiEnabled 需 NETWORK_SETTINGS 签名权限）。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署。

## 2. 技术选型与可行性核验

| 方案 | 说明 | 结论 |
|---|---|---|
| `WifiManager.setWifiEnabled` | 平台签名特权调用（NETWORK_SETTINGS 签名权限 manifest 既有声明），WifiService 记录 `setWifiEnabled package=com.hmdm.launcher uid=1000 isPrivileged=true`（真机日志） | **采用** |
| `dpm.getWifiMacAddress` | device owner 公开接口，返回真实 MAC（不受 WLAN 关闭影响） | **采用**（0146） |
| `DISALLOW_CONFIG_WIFI` | device owner 用户限制，设置页显示 "Blocked by your IT admin"（WlanControl 批次真机核验） | **采用**（0141/0154，与 ASR-0155 同引擎） |
| config 周期纠正（0143） | `StatusControlService` 轮询 `config.getWifi()`：true 且当前关闭 → setWifiEnabled(true)；false 且开启 → 关闭 | **采用**（配置驱动，无独立命令） |

**真机特性（2026-08-13 核验）**：
- `setWifiEnabled` 为**异步生效**：命令返回 true 后 WLAN 状态约 10~20s 才完全就绪（期间 `isWifiEnabled` 仍 false）——测试用例需等待后核对（WifiService 日志 `setWifiEnabled ... enable=true` 与最终 IsWifiOpen=true 对照）；
- `GetWifiMac`（dpm.getWifiMacAddress）**在 WLAN 开启时返回真实 MAC `00:08:22:a0:8b:03`，关闭时返回 null**（2026-08-14 复核修正：23:57 首次核验时 WLAN 处于开启过程中故返回 MAC）；`WifiManager.getConnectionInfo().getMacAddress()` 在关闭时为匿名 `02:00:00:00:00:00`——文档区分两通道；
- **ASR-0143 强制打开为 config 驱动**：本机无服务器 config（wifi 字段）时纠正循环不激活；IPC 可验证的是其底层执行通道（SetWifiOpen），强制语义（周期纠正）依赖服务器下发 config.wifi=true——文档如实说明（与 ASR-0311/0321 等 ForceOpen 命令式实现不同，WLAN 未实现独立 ForceOpen 命令/标志）。

## 3. 命令接口定义

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetWifiOpen` | open（Boolean，必填） | Boolean（setWifiEnabled 调用结果；生效异步约 10~20s） | ASR-0140/0142 |
| `IsWifiOpen` | 无 | Boolean（isWifiEnabled） | ASR-0140/0142 |
| `GetWifiMac` | 无 | String（dpm.getWifiMacAddress 真实 MAC） | ASR-0146 |
| `SetUserConfigWifiDisabled` | disabled（Boolean，必填） | Boolean | ASR-0141/0154 |
| `IsUserConfigWifiDisabled` | 无 | Boolean | ASR-0141/0154 |

**调用示例**：

```java
Map<String, Object> p = new HashMap<>();
p.put("open", true);
Map result = api.onEvent("SetWifiOpen", p);
// {"RESULT":true}（异步生效，等待后 IsWifiOpen=true）

Map result2 = api.onEvent("GetWifiMac", null);
// {"RESULT":"00:08:22:a0:8b:03"}
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/
├── syrius/utils/WifiPolicyManager.java            # setWifiEnabled / isWifiEnabled / getWifiMacAddress
├── service/StatusControlService.java              # 0143 强制打开：config.getWifi() 周期纠正（配置驱动）
├── syrius/service/command/wifi/                   # SetWifiOpen / IsWifiOpen / GetWifiMac（既有）
└── syrius/service/ApiBinder.java                  # 既有注册（SetUserConfigWifiDisabled 属 WlanControl 批次）

testapp/
└── src/main/java/com/hmdm/testapp/
    ├── TestActions.java                           # SetWifiOpen/IsWifiOpen/GetWifiMac/WifiStateLocal 事件
    ├── WifiVerifier.java                          # 新增 getStateLocal（WifiManager enabled+MAC 本地探针）
    └── WifiControlTestActivity.java               # 扩展：WLAN 开关/MAC 按钮（0141/0154 按钮为既有）
```

## 5. 执行逻辑

```
SetWifiOpen(open): WifiManager.setWifiEnabled(open) → 返回调用结果（异步生效）
IsWifiOpen(): WifiManager.isWifiEnabled()
GetWifiMac(): dpm.getWifiMacAddress(admin)
SetUserConfigWifiDisabled(disabled):
  disabled=true → addUserRestriction(DISALLOW_CONFIG_WIFI)
  disabled=false → clearUserRestriction(DISALLOW_CONFIG_WIFI)
StatusControlService（0143）: 周期检查 config.getWifi() ≠ 实际状态 → setWifiEnabled 纠正（配置驱动）
```

**安全设计**：setWifiEnabled 需平台签名特权（NETWORK_SETTINGS，manifest 既有）；ApiBinder 调用方门禁；无注入面。

## 6. 权限与归属

- ASR-0140/0142/0146：平台签名特权调用（NETWORK_SETTINGS 既有声明）/ device owner 公开接口；
- ASR-0141/0154：device owner 用户限制；
- ASR-0143：Launcher 进程内服务（config 驱动）；
- testapp：新增本地探针用公开 WifiManager（无新增权限）；
- 无 ROM 侧代码改动，无 root 依赖。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺参（open/disabled） | testapp 侧返回 missing parameter，不 crash |
| setWifiEnabled 异步 | 命令返回调用结果；测试以等待（约 20s）+ IsWifiOpen/本地探针核对（真机实测异步约 10~20s） |
| WLAN 关闭时查 MAC | GetWifiMac（dpm 通道）仍返回真实 MAC；WifiManager 通道为匿名 02:00:00:00:00:00——文档区分 |
| 强制打开（0143）无命令 | config 驱动（服务器 config.wifi）；无 config 时纠正循环不激活，文档说明；底层通道（SetWifiOpen）可独立验证 |
| 0141/0154 与 0155 交互 | 共用 DISALLOW_CONFIG_WIFI 限制、标志独立持久化（WlanControl 批次设计） |
| 测试后状态 | WLAN 恢复会话基线（本机关闭）、用户配置限制复位（disabled=false） |

## 8. 真机验证记录（2026-08-13，Android 13 / API 33 userdebug，平台签名 + device owner）

| 核验项 | 结果 |
|---|---|
| 基线 | WifiStateLocal enabled=false（本机 WLAN 关闭）；GetWifiMac → 00:08:22:a0:8b:03（dpm 通道，关闭时仍返回真实 MAC） |
| ASR-0140/0142 打开 | SetWifiOpen(true) → true；WifiService 日志 `setWifiEnabled package=com.hmdm.launcher uid=1000 enable=true isPrivileged=true`；**约 15s 后** IsWifiOpen=true、WifiStateLocal enabled=true（异步生效记录） |
| ASR-0140/0142 关闭 | SetWifiOpen(false) → true；IsWifiOpen=false（WifiService enable=false 日志） |
| ASR-0146 | GetWifiMac → 00:08:22:a0:8b:03（真实 MAC，WLAN 开启时）；WLAN 关闭时 dpm 通道返回 null、WifiManager 通道返回匿名 02:00:00:00:00:00（2026-08-14 复核修正：dpm 通道关闭时不可用） |
| ASR-0141/0154 | SetUserConfigWifiDisabled(true) → Is=true；false → Is=false（往返一致） |
| ASR-0143 | 文档说明 config 驱动（无服务器 config 时纠正循环不激活）；底层 SetWifiOpen 通道已验证 |
| 测试后状态 | WLAN 恢复关闭（会话基线）、用户配置限制复位；DO 在位 |
