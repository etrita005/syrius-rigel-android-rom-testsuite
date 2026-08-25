# 蓝牙状态管控（ASR-0171/0172/0173）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 底层机制 |
|---|---|---|---|
| ASR-0171 | 禁用/启用蓝牙 | 设置/查询蓝牙总开关状态 | `BluetoothAdapter.enable()/disable()`（BLUETOOTH_CONNECT 等权限，平台签名） |
| ASR-0172 | 打开/关闭蓝牙 | 同 ASR-0171（共用 Set/IsBlueOpen） | 同上 |
| ASR-0173 | 强制打开蓝牙 | 蓝牙被关闭后周期强制重新打开 | **服务器 config 驱动**（`Initializer.applyEarlyNonInteractivePolicies` 注册期一次性设置 + `StatusControlService` 周期纠正，config.getBluetooth()） |

**归属**：「Launcher（MDM）」。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署。

## 2. 技术选型与可行性核验

| 方案 | 说明 | 结论 |
|---|---|---|
| `BluetoothAdapter.enable()/disable()` | 公开 API（BLUETOOTH_CONNECT 运行时权限，平台签名授予），免交互切换；`isEnabled()` 查询 | **采用** |
| config 周期纠正（0173） | `Initializer.applyEarlyNonInteractivePolicies`（config.getBluetooth() 注册期应用）+ `StatusControlService` 周期轮询纠正 | **采用**（配置驱动，无独立命令） |

**真机特性（2026-08-13 核验）**：
- `SetBlueOpen` 开关生效（蓝牙约 3~5s 内完成状态切换）；本地探针 `GetBluetoothStateLocal`（BluetoothVerifier：supported/enabled/scanMode/bondedDevices）与 IsBlueOpen 双通道核对一致；
- **ASR-0173 强制打开为 config 驱动**（与 ASR-0143 WLAN 同模式）：无独立命令/标志；本机无服务器 config（bluetooth 字段）时纠正循环不激活——IPC 可验证的是其底层执行通道（SetBlueOpen），文档如实说明。

## 3. 命令接口定义

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetBlueOpen` | open（Boolean，必填） | Boolean（enable/disable 调用完成） | ASR-0171/0172 |
| `IsBlueOpen` | 无 | Boolean（BluetoothAdapter.isEnabled） | ASR-0171/0172 |

**调用示例**：

```java
Map<String, Object> p = new HashMap<>();
p.put("open", true);
Map result = api.onEvent("SetBlueOpen", p);
// {"RESULT":true}；IsBlueOpen → true
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/
├── syrius/utils/BluetoothHelper.java              # enableBluetooth/disableBluetooth/isBluetoothEnabled
├── helper/Initializer.java                        # 0173：applyEarlyNonInteractivePolicies（config.getBluetooth() 注册期设置）
├── service/StatusControlService.java              # 0173：config.getBluetooth() 周期纠正
├── syrius/service/command/blue/                   # SetBlueOpen / IsBlueOpen（既有）
└── syrius/service/ApiBinder.java                  # 既有注册

testapp/
└── src/main/java/com/hmdm/testapp/
    ├── TestActions.java                           # SetBlueOpen/IsBlueOpen/GetBluetoothStateLocal（既有）
    ├── BluetoothVerifier.java                     # getBluetoothState 本地探针（既有）
    └── BluetoothTestActivity.java                 # btn_bt_on/off/query（既有）
```

## 5. 执行逻辑

```
SetBlueOpen(open): open → BluetoothAdapter.enable()；否则 disable() → 返回调用完成
IsBlueOpen(): BluetoothAdapter.isEnabled()
Initializer/StatusControlService（0173）: config.getBluetooth() 与实际状态不一致 → enable/disable 纠正（配置驱动）
```

**安全设计**：调用方经 ApiBinder 门禁；蓝牙开关为公开 API（无注入面）。

## 6. 权限与归属

- ASR-0171/0172：公开 BluetoothAdapter API（BLUETOOTH_CONNECT/BLUETOOTH 权限 manifest 既有声明，平台签名授予）；
- ASR-0173：Launcher 进程内 config 驱动（无独立命令）；
- testapp：本地探针用公开 BluetoothAdapter（既有权限）；
- 无 ROM 侧代码改动，无 root 依赖。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺参（open） | testapp 侧返回 missing parameter，不 crash |
| 无蓝牙硬件 | 本地探针 supported=false 如实上报；enable 无操作 |
| 强制打开（0173）无命令 | config 驱动（服务器 config.bluetooth）；无 config 时纠正循环不激活，文档说明；底层通道（SetBlueOpen）独立验证 |
| 开关切换异步 | 蓝牙状态切换约 3~5s，测试等待后核对（真机实测） |
| 测试后状态 | 蓝牙恢复会话基线（本机关闭） |

## 8. 真机验证记录（2026-08-13，Android 13 / API 33 userdebug，平台签名 + device owner）

| 核验项 | 结果 |
|---|---|
| 基线 | IsBlueOpen=false；GetBluetoothStateLocal：bluetoothSupported=true、bluetoothEnabled=false（本机蓝牙关闭） |
| ASR-0171/0172 打开 | SetBlueOpen(true) → true；约 5s 后 IsBlueOpen=true、本地探针 bluetoothEnabled=true、scanMode=connectable、bondedDevices=[]（双通道一致） |
| ASR-0171/0172 关闭 | SetBlueOpen(false) → true；IsBlueOpen=false、探针 bluetoothEnabled=false（恢复会话基线） |
| ASR-0173 | 文档说明 config 驱动（Initializer 注册期 + StatusControlService 周期纠正，无独立命令）；底层 SetBlueOpen 通道已验证 |
| 测试后状态 | 蓝牙关闭（会话基线）；DO 在位 |
