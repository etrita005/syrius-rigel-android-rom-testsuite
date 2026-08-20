# NFC 管控（ASR-0315/0316/0317）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 实现机制 |
|---|---|---|---|
| ASR-0315 | NFC | 禁用/启用 NFC | 反射 `NfcAdapter.enable()/disable()`（@hide，经平台签名 + `WRITE_NFC_SETTINGS` 签名权限调用） |
| ASR-0316 | NFC | 关闭/打开 NFC | 同 ASR-0315 引擎（ASR-0315/0316 共用 Set/Is 命令，与 WLAN ASR-0140/0142、飞行模式 ASR-0319/0320 同模式） |
| ASR-0317 | NFC | 强制打开 NFC | 打开 + 10 秒周期纠正器（用户/应用关闭后被重新打开） |

**归属**：三项均为「Launcher（MDM）+ 系统 API」。Android 13（API 33）下 DevicePolicyManager 无 NFC 开关能力，落地为**平台签名应用调用 @hide 系统接口**（反射 `NfcAdapter.enable/disable`，`WRITE_NFC_SETTINGS` 为 signature|privileged|development 级权限，平台签名 Launcher uid=1000 自动授予，manifest 已声明）。无 ROM 侧代码改动。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署；bootloader 锁定。**本机硬件核验（2026-08-05）：当前测试机（MT8788）无 NFC 硬件/框架支持**——`pm list features` 无 `android.hardware.nfc` 特性、`/vendor/lib/hw` 无 NFC HAL、NfcService 未启动（`dumpsys nfc` → Can't find service: nfc，无 nfc 进程）、`Settings.Global.nfc_on` 为 null；`com.android.nfc` 包虽预装但不生效。**本批次在本机执行受限验证（无硬件路径），开关行为验收需 NFC 真机**（见第 8 节）。

## 2. 技术选型与可行性核验

### 2.1 机制：NfcAdapter.enable/disable（@hide，反射调用）

AOSP 13 中 `NfcAdapter.enable()`/`disable()` 为 @hide 方法，经 `INfcAdapter` Binder 调 NfcService 状态机（OFF → TURNING_ON → ON / ON → TURNING_OFF → OFF），调用侧需持有 `WRITE_NFC_SETTINGS`（signature|privileged|development）权限：

| 方案 | 说明 | 结论 |
|---|---|---|
| ROM 广播 / 厂商定制接口 | 依赖定制 ROM（Sheet1 原缺口无既有实现） | 放弃 |
| 反射 `NfcAdapter.enable/disable`（平台签名 uid=1000 + WRITE_NFC_SETTINGS） | Sheet1 P1 规划路径；Settings 应用的 NFC 开关同机制（NfcPreferenceController 调 NfcAdapter.enable/disable） | 采用 |

**编译与运行**：本 ROM fork SDK（android-33）中 `enable()/disable()` 与 `getState()` 均为隐藏（编译期不可见），全部经运行时反射调用（`NfcAdapter.class.getMethod(...)`），返回结果如实上报；反射失败返回 `callResult=false` 并如实报告，不 crash。

**状态机**：`getState()` 返回 1=OFF、2=TURNING_ON、3=ON、4=TURNING_OFF。设置命令调用后轮询等待稳定态（ON/OFF，最长 5 秒，200ms 间隔），以稳定态核对写入是否生效；`Settings.Global.nfc_on`（NfcService 随状态机写回镜像键）作为二次对照值附报。

### 2.2 语义设计（与"禁用/启用"+"关闭/打开"双需求共用引擎的模式一致）

- ASR-0315 禁用/启用 与 ASR-0316 关闭/打开 共用命令 `SetNfcEnabled`/`IsNfcEnabled`：`enabled=false` → `NfcAdapter.disable()`；`enabled=true` → `NfcAdapter.enable()`；写后状态核对一致才 `success=true`；
- **强制打开（ASR-0317）**：`ForceOpenNfc forceOpen=true` 时持久化标志（SharedPreferences `nfc_policy` 的 `forceOpen`）并启动 10 秒周期纠正器（静态 ScheduledThreadPoolExecutor，与 AirplaneModePolicyManager 同模式）：每次 tick 若 `getState()!=ON` 则重新 `enable()`。`forceOpen=false` 停止纠正器并清标志。**持久化**：标志存于 SharedPreferences，进程重启（`ApiService.onCreate`）与开机（`BootCompletedReceiver`）时经 `syncForceOpen` 重新武装并立即纠正一次。

### 2.3 无 NFC 硬件设备的行为（本机已核验路径）

`NfcAdapter.getDefaultAdapter()` 在 NfcService 未运行时返回 null。所有命令对此**如实上报而非误报成功**：

```
{supported:false, success:false, error:"NFC not supported on this device", ...}
```

- 不 crash、不启动纠正器、不持久化 forceOpen 标志（本机核验：`nfc_policy.xml` 不存在）；
- 已发布产品在无 NFC 机型上可通过 `supported=false` 识别并跳过下发。

## 3. 命令接口定义（SystemApiInterface.onEvent）

### 3.1 新增命令（本批次，3 条）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetNfcEnabled` | enabled（Boolean，必填） | Map：{success, supported, enabled, state, stateName, nfc_on} 或 {error} | ASR-0315/0316 |
| `IsNfcEnabled` | 无 | Map：{success, supported, enabled, state, stateName, nfc_on, forceOpen} | ASR-0315/0316 |
| `ForceOpenNfc` | forceOpen（Boolean，必填） | Map：{success, supported, forceOpen, state, stateName, nfc_on} 或 {error} | ASR-0317 |

**Set 类命令返回结构**（以 SetNfcEnabled enabled=false 为例，NFC 真机）：

```
{success:boolean, supported:true, enabled:false, state:1, stateName:"OFF", nfc_on:0}
# 调用 NfcAdapter.disable() 后轮询至稳定态（≤5s），state==OFF 才 success=true；不一致 → success=false + error（含期望态/实际态/callResult）
```

**无 NFC 硬件设备**（本机实测路径）：

```
{success:false, supported:false, enabled:false, error:"NFC not supported on this device"}
```

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> p = new HashMap<>();
p.put("enabled", false);
Map result = api.onEvent("SetNfcEnabled", p);

Map<String, Object> f = new HashMap<>();
f.put("forceOpen", true);
Map result2 = api.onEvent("ForceOpenNfc", f);
```

**广播通道**（TestBroadcast，与现有命令一致）：

```bash
adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast \
  -a ON_SQA_INTERFACE_TEST --es event SetNfcEnabled \
  --es param '{"enabled":false}'
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/
│   └── NfcPolicyManager.java                # 新增：NFC 开关/查询/强制打开引擎（反射 enable/disable/getState + 10s 纠正器 + SharedPreferences 持久化）
├── service/command/nfc/
│   ├── SetNfcEnabled.java                   # 新增：ASR-0315/0316 设置
│   ├── IsNfcEnabled.java                    # 新增：ASR-0315/0316 查询
│   └── ForceOpenNfc.java                    # 新增：ASR-0317 强制打开
├── service/ApiBinder.java                   # 注册 3 个新命令
├── service/ApiService.java                  # onCreate 重新武装 NFC 强制纠正器（进程重启）
└── broadcast/BootCompletedReceiver.java     # 开机重新武装 NFC 强制纠正器

app/src/main/AndroidManifest.xml             # 新增声明 WRITE_NFC_SETTINGS（签名权限，平台签名自动授予）

testapp/
├── src/main/java/com/hmdm/testapp/
│   ├── DeviceStateTestActivity.java         # 扩展：新增 NFC 5 个按钮（关闭/打开/查询/强制开/停强制）
│   ├── TestActions.java                     # 新增 3 个事件（含参数校验）与事件目录
│   └── MainActivity.java                    # 主页按钮文案更新（含 NFC）
└── src/main/res/layout/activity_device_state_test.xml # 新增 NFC 小节
```

## 5. 执行逻辑

```
SetNfcEnabled(enabled):
  1. 参数校验（缺 enabled → {error}）
  2. getDefaultAdapter()==null → {success:false, supported:false, error:"NFC not supported on this device"}
  3. 反射调用 enable()/disable()（callResult 记录）
  4. 轮询 getState() 至稳定态（≤5s）：期望 enabled?ON:OFF
  5. 附报 nfc_on 镜像键；稳定态==期望 → success=true，否则 success=false + error
  6. 返回 {success, supported, enabled, state, stateName, nfc_on}

ForceOpenNfc(forceOpen):
  1. 参数校验（缺 forceOpen → {error}）
  2. getDefaultAdapter()==null → {success:false, supported:false, error:...}（不持久化标志）
  3. true：持久化标志；当前非 ON 则立即 enable()；启动 10s 纠正器
  4. false：清标志；停止纠正器
  5. 返回 {success, supported, forceOpen, state, stateName, nfc_on}

IsNfcEnabled():
  1. getDefaultAdapter()==null → {success:false, supported:false, error:...}
  2. 读取 getState()（enabled = state==ON）、nfc_on、forceOpen 标志
  3. 返回 {success, supported, enabled, state, stateName, nfc_on, forceOpen}
```

**安全设计**：本批命令参数全部为布尔值，无字符串进入 shell / 系统命令，无命令注入面；反射方法名均为代码内常量；仅调用系统既有 @hide 接口，无权限面扩大、无 ROM 改动。

## 6. 权限与归属

- `WRITE_NFC_SETTINGS`（signature|privileged|development）：manifest 新增声明，平台签名（平台密钥）Launcher 安装时自动授予——与既有 `MANAGE_USERS`/`WRITE_SECURE_SETTINGS` 等签名权限同模式（`dumpsys package com.hmdm.launcher` 可见该权限条目）；
- NfcService 侧权限校验 `checkCallingOrSelfPermission(WRITE_NFC_SETTINGS)` 对 uid=1000 平台签名应用放行（与 Settings 应用 NFC 开关同权限路径）；
- 无 ROM 侧代码改动，无 root 依赖；不修改 AIDL / lib 模块；testapp 无需新增权限（命令在 Launcher 进程执行）；不修改 `device_admin.xml`，无需重启 framework。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺 enabled / forceOpen 参数 | 命令返回 {error："missing parameter: ..."}，不 crash；testapp 侧同样拦截 |
| 无 NFC 硬件（getDefaultAdapter()==null） | 所有命令返回 {supported:false, success:false, error:"NFC not supported on this device"}，不 crash、不启动纠正器、不持久化标志（真机核验通过） |
| 反射 enable/disable 失败（如权限缺失、ROM 裁剪） | callResult=false，轮询稳定态后如实报 success=false + error（含期望态/实际态/callResult） |
| 状态机转换中（TURNING_ON/OFF） | 设置命令轮询等待稳定态（≤5 秒），以最终稳定态核对 |
| 设置后状态未达期望 | success=false + error（含期望态/实际态），如实上报，可重试 |
| 强制纠正器运行时用户/应用再次关闭 | 10 秒内被重新打开；`forceOpen=false` 停止纠正并保持用户状态 |
| 进程被杀 / 重启 / 开机 | 标志持久化于 SharedPreferences `nfc_policy`；ApiService.onCreate 与 BootCompletedReceiver 重新武装并立即纠正一次 |
| 无 NFC 机型上业务下发强制打开 | 命令返回 supported=false 不持久化，业务侧可据 supported 跳过 |
| 本机无硬件时的验收边界 | 开关/强制打开的真实行为（射频级状态切换、NfcService 写 nfc_on）需 NFC 真机验收，本批次记录为受限验证 |

## 8. 真机验证记录（2026-08-05，Android 13 / API 33 userdebug，平台签名 + device owner）

（命令级用例结果见测试用例设计文档，此处记录机制核验关键结论）

| 核验项 | 结果 |
|---|---|
| 设备基线 | **本机无 NFC 硬件**：`pm list features` 无 android.hardware.nfc、/vendor/lib/hw 无 NFC HAL、`dumpsys nfc` → Can't find service、无 nfc 进程、`settings get global nfc_on` → null、`ro.factory.nfc=true` 仅为工厂属性 |
| 部署 | Launcher（平台签名，uid=1000，device owner 保持）、testapp 安装成功；manifest 新增 `WRITE_NFC_SETTINGS` 后 `dumpsys package com.hmdm.launcher` 可见该权限（签名权限，平台签名自动授予） |
| 无硬件路径（本机实测） | `IsNfcEnabled`/`SetNfcEnabled enabled=false/true`/`ForceOpenNfc forceOpen=true/false` 均返回 `{supported:false, success:false, error:"NFC not supported on this device"}`，不 crash；logcat HYX-MDM-APP 显示命令经 NfcPolicyManager 正常执行 |
| forceOpen 不误持久化 | 强制打开命令返回 supported=false 后，`/data/data/com.hmdm.launcher/shared_prefs/nfc_policy.xml` 不存在（标志未写入、纠正器未启动） |
| 状态无残留 | nfc_on 保持 null、无 nfc 服务/进程产生，系统状态与测试前一致 |
| 功能路径（NFC 真机待验） | `NfcAdapter.enable/disable` 反射调用、稳定态核对、10 秒纠正器、进程重启/开机重新武装——代码路径与 AirplaneModePolicyManager（已真机验收）同构，待 NFC 机型上完成开关行为验收 |
| 测试后设备恢复 | 无任何设置写入残留，nfc 相关状态与测试前一致 |
