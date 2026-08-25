# 设备信息查询（ASR-0210/0211/0212/0258/0260/0261/0263/0264）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 底层机制 |
|---|---|---|---|
| ASR-0210 | 查询 SN | 设备序列号 | `Build.getSerial()` 反射（READ_PHONE_STATE 平台签名） |
| ASR-0211 | 查询 MAC | 主 WLAN MAC 地址 | wlan0 sysfs 读取 → `dpm.getWifiMacAddress` 回退（2026-08-13 补命令注册） |
| ASR-0212 | 查询 Model | 设备型号 | `Build.MODEL` |
| ASR-0258 | 查询本机 SIM 卡电话号码 | SIM 线路 1 号码 | `TelephonyManager.getLine1Number()`（2026-08-13 补命令注册） |
| ASR-0260 | 查询 IMEI | 卡槽 0/1 IMEI | `TelephonyManager.getImei(0/1)` |
| ASR-0261 | 查询 MEID | MEID | `TelephonyManager.getMeid()` |
| ASR-0263 | 查询 MCC | 移动国家码 | `TelephonyManager.getSimOperator()/getNetworkOperator()` 前 3 位（2026-08-13 补命令注册） |
| ASR-0264 | 查询 MNC | 移动网络码 | 同上第 4-5 位（2026-08-13 补命令注册） |

**归属**：「Launcher（MDM）」/「Launcher（MDM）+ 系统 API」（Build.getSerial 反射 / TelephonyManager 需要 READ_PHONE_STATE）。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署。

## 2. 技术选型与可行性核验

| 方案 | 说明 | 结论 |
|---|---|---|
| `GetDeviceInfo`（0210/0212/0260/0261） | 既有命令，一次返回 {imei, imei2, meid, sn, model}（DeviceInfoHelper） | **采用** |
| `GetMacAddress`（0211） | **2026-08-13 补命令**：wlan0 sysfs（`/sys/class/net/wlan0/address`，WiFi 关闭亦有效）→ 本 ROM **无 wlan0 接口**（MTK 网卡仅 ccmni*/dummy0 等，实测）→ 回退 `dpm.getWifiMacAddress`（**WLAN 开启时返回真实 MAC；关闭时返回 null——2026-08-14 复核修正**） | **采用**（回退链） |
| `GetMcc`/`GetMnc`（0263/0264） | **2026-08-13 补命令**：公开 TelephonyManager API（无反射）；无 SIM 时返回空串 | **采用** |
| `GetSimTelephoneNumber`（0258） | **2026-08-13 补命令**：公开 `getLine1Number()`；无 SIM/未写入号码时返回空串 | **采用** |

**真机核验（2026-08-13/08-14）**：
- `GetDeviceInfo` → imei=imei2=354707100352113、meid=null（本机无 CDMA，如实）、sn=P65246404D60624SC02（与 `adb devices` 序列号一致）、model="Android"（本 ROM Build.MODEL 值）；
- **本机无 SIM 卡**：GetMcc/GetMnc/GetSimTelephoneNumber 均返回空串（如实）；含 SIM 的验证需插卡设备（硬件受限说明）；
- MAC：本 ROM 无 wlan0 sysfs 接口；WLAN 开启时 dpm 通道返回 00:08:22:a0:8b:03；关闭时返回 null（修正记录见 WlanStateControl 文档）。

## 3. 命令接口定义

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `GetDeviceInfo` | 无 | Map{imei, imei2, meid, sn, model} | ASR-0210/0212/0260/0261 |
| `GetMacAddress` | 无 | String（wlan0 sysfs → dpm 回退；unavailable/null 如实） | ASR-0211 |
| `GetMcc` | 无 | String（MCC，无 SIM 为空） | ASR-0263 |
| `GetMnc` | 无 | String（MNC，无 SIM 为空） | ASR-0264 |
| `GetSimTelephoneNumber` | 无 | String（线路 1 号码，无 SIM 为空） | ASR-0258 |

**调用示例**：

```java
Map result = api.onEvent("GetDeviceInfo", null);
// {"RESULT":{"imei":"354707100352113","imei2":"354707100352113","meid":null,
//            "sn":"P65246404D60624SC02","model":"Android"}}
Map result2 = api.onEvent("GetMacAddress", null);
// {"RESULT":"00:08:22:a0:8b:03"}（WLAN 开启时）
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/
├── syrius/utils/DeviceInfoHelper.java             # getModel/getImei/getImei2/getMeid/getSerialNumber/
│                                                 # getPrimaryMacAddress（wlan0 sysfs）
├── syrius/service/command/imei_meid/
│   ├── GetDeviceInfo.java                         # 既有（imei/imei2/meid/sn/model）
│   ├── GetMacAddress.java                         # 新增（wlan0 → dpm 回退，ASR-0211）
│   ├── GetMcc.java / GetMnc.java                  # 新增（TelephonyManager，ASR-0263/0264）
│   └── GetSimTelephoneNumber.java                 # 新增（getLine1Number，ASR-0258）
└── syrius/service/ApiBinder.java                  # 注册 4 条新命令

testapp/
└── src/main/java/com/hmdm/testapp/
    ├── TestActions.java                           # 5 个事件（GetDeviceInfo/GetMacAddress/GetMcc/GetMnc/
    │                                             #   GetSimTelephoneNumber）
    ├── InfoQueryTestActivity.java                 # 扩展：设备信息按钮组
    └── src/main/res/layout/activity_info_query_test.xml
```

## 5. 执行逻辑

```
GetDeviceInfo(): DeviceInfoHelper → {imei(getImei(0)), imei2(getImei(1)), meid(getMeid()),
                                     sn(getSerialNumber), model(Build.MODEL)}
GetMacAddress(): 读 /sys/class/net/wlan0/address（本 ROM 不存在 → unavailable）
                 → 回退 dpm.getWifiMacAddress（WLAN 开启有效；关闭返回 null——2026-08-14 复核）
GetMcc()/GetMnc(): TelephonyManager.getSimOperator()（不足 5 位时 getNetworkOperator()）截取
GetSimTelephoneNumber(): TelephonyManager.getLine1Number()（null → ""）
```

**安全设计**：全部只读查询；TelephonyManager 公共 API 需 READ_PHONE_STATE（manifest 既有声明，平台签名授予）；无注入面。

## 6. 权限与归属

- ASR-0210：平台签名反射 Build.getSerial（READ_PHONE_STATE 既有声明）；
- ASR-0211：平台签名 sysfs 读 + device owner dpm 接口；
- ASR-0258/0260/0261/0263/0264：公开 TelephonyManager API（READ_PHONE_STATE 既有声明）；
- ASR-0212：公开 Build.MODEL；
- testapp：无新增权限；无 ROM 侧代码改动，无 root 依赖。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 无 SIM 卡 | MCC/MNC/电话号码返回空串（如实；硬件受限说明：含 SIM 验证需插卡设备） |
| 无 CDMA 能力 | meid=null（如实） |
| 本 ROM 无 wlan0 接口 | GetMacAddress 回退 dpm 通道（WLAN 开启时有效）；关闭时 null——文档记录双通道限制 |
| TelephonyManager 异常 | 捕获返回空串（引擎内 catch） |
| SN 反射失败 | getSerialNumber 内部处理（反射捕获，返回 null 如实） |

## 8. 真机验证记录（2026-08-13/08-14，Android 13 / API 33 userdebug，平台签名 + device owner）

| 核验项 | 结果 |
|---|---|
| ASR-0210 SN | GetDeviceInfo.sn=P65246404D60624SC02（与 `adb devices` 序列号一致） |
| ASR-0212 Model | model="Android"（本 ROM Build.MODEL） |
| ASR-0260 IMEI | imei=imei2=354707100352113（双卡槽同号） |
| ASR-0261 MEID | meid=null（本机无 CDMA，如实） |
| ASR-0211 MAC | WLAN 开启：GetMacAddress=00:08:22:a0:8b:03（dpm 回退通道）；关闭：null（2026-08-14 复核）；wlan0 sysfs 本 ROM 不存在 |
| ASR-0258/0263/0264 | 无 SIM：GetSimTelephoneNumber/GetMcc/GetMnc 均返回空串（如实；命令注册完成，含 SIM 验证待插卡设备） |
| 测试后状态 | WLAN 恢复关闭（会话基线）；DO 在位 |
