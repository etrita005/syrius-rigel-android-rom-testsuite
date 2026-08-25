# 电源与恢复出厂管控（ASR-0112/0113/0121/0122/0123）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 底层机制 |
|---|---|---|---|
| ASR-0112 | 强制关机 | 免交互强制关机 | 反射 `PowerManager.shutdown(false, reason, false)`（DEVICE_POWER 签名权限） |
| ASR-0113 | 强制重启 | 免交互强制重启 | device owner `dpm.reboot`（公开接口） |
| ASR-0121 | 禁用/启用恢复出厂设置 | 禁止/允许用户在设置中执行恢复出厂 | device owner `DISALLOW_FACTORY_RESET` + `DISALLOW_NETWORK_RESET` + `DISALLOW_APPS_CONTROL` 用户限制 |
| ASR-0122 | 禁止用户执行恢复出厂设置 | 查询/设置"禁止用户执行恢复出厂"状态 | 同上（共用命令 Set/IsUserPerformFactoryResetDisabled） |
| ASR-0123 | 执行恢复出厂设置 | 免交互执行恢复出厂（wipe） | device owner `dpm.wipeData(WIPE_EXTERNAL_STORAGE \| WIPE_RESET_PROTECTION_DATA)` |

**归属**：「Launcher（MDM）」/「Launcher（MDM）+ 系统 API」（ASR-0112 反射隐藏 API）。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署；bootloader 锁定。

**批次内顺序**（2026-08-13 按计划执行）：先 0121/0122（限制类，安全）→ 0113 强制重启（本批最后）→ 0112 强制关机（0113 之后单独安排）；**0123 恢复出厂为全部 19 批的全局最后一步**（执行 wipe 后按 AGENTS.md 重新部署 DO + testapp），不在本批执行。

## 2. 技术选型与可行性核验

| 方案 | 说明 | 结论 |
|---|---|---|
| `dpm.reboot`（0113） | device owner 公开接口（API 24+），静默重启 | **采用** |
| `PowerManager.shutdown` 反射（0112） | 平台签名应用反射 @hide `shutdown(boolean confirm, String reason, boolean wait)`，confirm=false 免确认关机 | **采用**（AOSP 隐藏 API，平台签名豁免 hidden API 限制） |
| `dpm.wipeData`（0123） | device owner 公开接口，flags 含外部存储与 Reset Protection 数据 | **采用** |
| DISALLOW_FACTORY_RESET 组合（0121/0122） | device owner 用户限制，禁止设置中恢复出厂；附 DISALLOW_NETWORK_RESET（网络重置）与 DISALLOW_APPS_CONTROL（应用管控入口）同链路管控 | **采用** |

**真机核验记录（2026-08-13）**：
- 0113 强制重启：命令触发后设备立即重启（adb "Broken pipe" 为预期），重启后 boot 完成、**DO 保持**（`dpm list-owners` = DeviceOwner,Affiliated）、IPC 恢复；
- 0112 强制关机：命令返回 true，设备断电（adb 列表清空）；**本 ROM 支持接电自动开机**（ASR-0439 定制特性，真机复现：USB 保持连接约 1 分钟内自动上电开机），开机后 DO 保持、IPC 恢复；
- 0121/0122：限制写入/读回往返一致（IsUserPerformFactoryResetDisabled true/false），恢复无残留。

## 3. 命令接口定义

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `PowerOff` | 无 | Boolean（shutdown 反射调用结果；设备随后断电） | ASR-0112 |
| `Reboot` | 无 | Boolean（dpm.reboot 调用结果；设备随后重启） | ASR-0113 |
| `SetUserPerformFactoryResetDisabled` | disabled（Boolean，必填） | Boolean | ASR-0121/0122 |
| `IsUserPerformFactoryResetDisabled` | 无 | Boolean（DISALLOW_FACTORY_RESET 当前状态） | ASR-0121/0122 |
| `PerformFactoryReset` | 无 | Boolean（wipeData 已发起；**破坏性，全局最后执行**） | ASR-0123 |

**调用示例**：

```java
Map result = api.onEvent("Reboot", null);
// {"RESULT":true}（设备重启）

Map<String, Object> p = new HashMap<>();
p.put("disabled", true);
Map result2 = api.onEvent("SetUserPerformFactoryResetDisabled", p);
// {"RESULT":true}；IsUserPerformFactoryResetDisabled → true
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/
├── syrius/utils/PowerHelper.java                 # reboot（dpm.reboot）/ powerOff（PowerManager.shutdown 反射）
├── syrius/utils/FactoryResetController.java      # performFactoryReset（dpm.wipeData）
│                                                 # disable/enable/isUserFactoryResetDisabled（用户限制）
├── syrius/service/command/power/                 # PowerOff.java / Reboot.java
├── syrius/service/command/factory_reset/         # PerformFactoryReset / Set|IsUserPerformFactoryResetDisabled
└── syrius/service/ApiBinder.java                 # 既有注册

testapp/
└── src/main/java/com/hmdm/testapp/
    ├── TestActions.java                          # PowerOff/Reboot/Set|IsUserPerformFactoryResetDisabled/
    │                                             # PerformFactoryReset（IPC 门禁提示）事件
    ├── PowerFactoryResetControlTestActivity.java # 新增测试页
    └── src/main/res/layout/activity_power_factory_reset_test.xml
```

## 5. 执行逻辑

```
Reboot(): dpm.reboot(admin) → true（设备重启；DO 与持久化策略重启后自动保持）
PowerOff(): 反射 PowerManager.shutdown(false, "userrequested", false) → true（设备断电；
            本 ROM 接电自动开机，开机后策略保持）
SetUserPerformFactoryResetDisabled(disabled):
  disabled=true → addUserRestriction(DISALLOW_FACTORY_RESET + DISALLOW_NETWORK_RESET + DISALLOW_APPS_CONTROL)
  disabled=false → clearUserRestriction(三者)
IsUserPerformFactoryResetDisabled(): getUserRestrictions 读 DISALLOW_FACTORY_RESET 位
PerformFactoryReset(): dpm.wipeData(WIPE_EXTERNAL_STORAGE | WIPE_RESET_PROTECTION_DATA)（全局最后）
```

**安全设计**：破坏性命令（Reboot/PowerOff/PerformFactoryReset）经 ApiBinder 调用方门禁；testapp IPC 对 PerformFactoryReset 返回门禁提示（作为全局最后一步，不通过 IPC 随意触发）；批次内顺序受测试流程约束（0121/0122 → 0113 → 0112 → 0123）。

## 6. 权限与归属

- ASR-0113/0121/0122/0123：device owner 公开接口（dpm.reboot / 用户限制 / dpm.wipeData，wipe-data uses-policy 既有声明）；
- ASR-0112：平台签名 uid=1000 反射 PowerManager.shutdown（DEVICE_POWER 签名权限，manifest 既有声明）；
- testapp：无新增权限；
- 无 ROM 侧代码改动，无 root 依赖。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 非 DO 环境 | dpm.reboot/wipeData 抛异常 → 捕获返回 false |
| shutdown 反射失败 | powerOff 返回 false（引擎内 catch），如实上报 |
| 重启/关机后连接 | adb wait-for-device + boot 轮询（getprop sys.boot_completed）；DO 自动保持（真机验证） |
| 关机后无法上电 | 本 ROM 接电自动开机（ASR-0439 定制特性，真机复现）；异常时需人工按电源键 |
| 0123 执行后 | 按 AGENTS.md 重新部署：装 Launcher → dpm set-device-owner → 验证 dpm list-owners → 装 testapp → 复验关键基线 |
| 缺参（disabled） | testapp 侧返回 missing parameter，不 crash |

## 8. 真机验证记录（2026-08-13，Android 13 / API 33 userdebug，平台签名 + device owner）

| 核验项 | 结果 |
|---|---|
| ASR-0121/0122 基线 | IsUserPerformFactoryResetDisabled=false |
| ASR-0121/0122 禁止/恢复 | SetUserPerformFactoryResetDisabled(disabled=true) → true、Is=true；disabled=false → Is=false（往返一致，无残留） |
| ASR-0113 强制重启 | Reboot → true；adb Broken pipe（设备重启预期）；wait-for-device + boot 完成后 `dpm list-owners` = DeviceOwner,Affiliated（DO 保持）；GetConnectionStatus bound=true |
| ASR-0112 强制关机 | PowerOff → true；adb 设备列表清空（断电）；**接电自动开机**（约 1 分钟内自动上电，ASR-0439 特性复现）；开机后 DO 保持、IPC 恢复 |
| ASR-0123 | 未执行（按计划为全部批次全局最后一步，届时执行 wipe 并按 AGENTS.md 重新部署） |
| 测试后状态 | 用户恢复出厂限制复位（disabled=false）；DO 在位；testapp 在线 |
