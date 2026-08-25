# 移动数据管控（ASR-0275/0276/0277/0279）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 实现机制 |
|---|---|---|---|
| ASR-0275 | 移动数据连接 | 获取/设置是否禁用移动数据连接 | 反射 `TelephonyManager.setDataEnabled(boolean)`（@hide/SDK,TEST-API，MODIFY_PHONE_STATE 签名权限），与 ASR-0276 关闭/开启共用 Set/Is 命令 |
| ASR-0276 | 移动数据连接 | 关闭/开启/强制关闭 移动数据连接 | 关闭/开启同 ASR-0275 引擎；强制关闭 `ForceCloseMobileData` = 关闭 + 10 秒周期纠正器（用户/应用打开后被重新关闭） |
| ASR-0277 | 移动数据连接 | 强制开启移动数据连接 | `ForceOpenMobileData` = 开启 + 10 秒周期纠正器（用户/应用关闭后被重新打开） |
| ASR-0279 | 移动数据连接 | 获取/设置是否移动数据连接状态不允许变更 | `SetMobileDataStateLocked`/`IsMobileDataStateLocked` = 锁定时快照当前状态为基线，10 秒纠正器将任何状态变更回滚到基线 |

**归属**：四项均为「Launcher（MDM）+ 系统 API」。Android 13（API 33）下 DevicePolicyManager 无移动数据开关能力，落地为**平台签名应用调用 @hide 系统接口**（反射 `TelephonyManager.setDataEnabled`，`MODIFY_PHONE_STATE` 为 signature|privileged 级权限，平台签名 Launcher uid=1000 自动授予（`dumpsys` 实测 granted=true））。无 ROM 侧代码改动。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署；bootloader 锁定。**本机硬件核验（2026-08-05）：当前测试机（MT6771）双卡槽均无 SIM 卡**（`gsm.sim.state`=ABSENT,ABSENT），射频 POWER_OFF、无订阅；移动数据设置（per-sub dataEnabled）在无订阅时**没有可作用的订阅对象**，设置调用被框架接受但状态无可翻转目标（详见第 8 节）。**本批次在本机执行受限验证（无 SIM 路径：API 通道、纠正器机制、持久化、优先级、如实上报），开关真实行为验收需插 SIM 真机**。

## 2. 技术选型与可行性核验

### 2.1 本 ROM 机制核验（2026-08-05，framework/服务 dex 反编译 + 真机实测）

Sheet1 P1 规划路径为「反射 `ConnectivityManager.setMobileDataEnabled`，替换定制 ROM 广播」。经本 ROM 逐层核验，该路径需要适配：

| 核验项 | 结果 |
|---|---|
| `ConnectivityManager.setMobileDataEnabled(boolean)` | **本 ROM 不存在**（反射 NoSuchMethodException；`ConnectivityManager` 位于 `/apex/com.android.tethering/javalib/framework-connectivity.jar`，其中无该方法）——放弃原规划签名 |
| `ConnectivityManager.getMobileDataEnabled()` | 存在（PUBLIC，hiddenapi=UNSUPPORTED，仅客户端壳）：内部经 `TelephonyManager` 读取默认数据订阅的 per-sub 状态，无 ConnectivityService 服务端方法 |
| `ConnectivityService`（service-connectivity.jar） | 无 setMobileDataEnabled/getMobileDataEnabled 方法（仅 mDefaultMobileDataRequest 等网络请求逻辑） |
| 主开关真实通道 | `TelephonyManager.setDataEnabled(boolean)` 与 `setDataEnabled(int subId, boolean)`（均为 PUBLIC SDK,TEST-API @SystemApi 级，MODIFY_PHONE_STATE）——本 ROM 唯一的主开关系统通道 |
| 查询通道 | `TelephonyManager.isDataEnabled()`（SDK,TEST-API）与上述 `ConnectivityManager.getMobileDataEnabled()` 壳（二者同源，均读 per-sub dataEnabled；命令附报两者交叉核对） |
| `Settings.Global.mobile_data` 镜像键 | 键存在且=1，但本 ROM framework 各 dex 中**无任何消费/写入该键的代码**（仅 Settings.Global 常量定义；AOSP 13 写该键的 ConnectivityService.setMobileDataEnabled 在本 ROM 不存在）——实测：框架状态与镜像键不一致（state=false 而镜像=1），命令仅附报对照，不作为判定依据 |
| `svc data enable/disable` | 为 shell 脚本转发 `cmd phone data enable/disable`——MTK Data Test Mode（DTM），作用于射频测试态，**不写 per-sub dataEnabled 设置**（实测无任何状态变化）——非主开关通道 |
| `TelephonyManager.setMobileDataPolicyEnabled(int policy, boolean)` | 为 fork 命名版 AOSP `setMobileDataPolicy`，policy 仅 1=通话时非默认卡数据 / 2=MMS 常允许（MOBILE_DATA_POLICY_* 常量实测值 1/2），**非主开关**——未采用 |

**结论**：主开关落地为反射 `TelephonyManager.setDataEnabled(boolean)`（首选）→ `setDataEnabled(int, boolean)` + `SubscriptionManager.getDefaultDataSubscriptionId()`（回退），与 ROM 自己的查询壳 `ConnectivityManager.getMobileDataEnabled()` 组成读写通道。无 ROM 侧改动。

### 2.2 语义设计（与"禁用/启用"+"关闭/打开"双需求共用引擎的模式一致）

- ASR-0275 禁用/启用 与 ASR-0276 关闭/开启 共用命令 `SetMobileDataEnabled`/`IsMobileDataEnabled`：`enabled=false` → `setDataEnabled(false)`；`enabled=true` → `setDataEnabled(true)`；写后读回核对一致才 `success=true`（与 NFC ASR-0315/0316、飞行模式 ASR-0319/0320 同模式）；
- **强制关闭（ASR-0276）**：`ForceCloseMobileData forceClose=true` 持久化标志并启动 10 秒周期纠正器，tick 时若状态非 OFF 则重新 `setDataEnabled(false)`；`false` 停止纠正并清标志；
- **强制开启（ASR-0277）**：`ForceOpenMobileData forceOpen=true` 同上模式，目标为 ON；
- **状态不允许变更（ASR-0279）**：`SetMobileDataStateLocked locked=true` 时**快照当前状态为基线**（如当前 OFF，锁定后用户/应用开启会被回滚为 OFF；当前 ON 则回滚为 ON），10 秒纠正器按基线回滚；`locked=false` 停止；
- **优先级**：多个标志同时生效时纠正器按固定优先级解析唯一目标：**强制关闭 > 强制开启 > 状态锁定**（如强制开启中再锁定，纠正器仍按强制开启目标执行；停止强制后自动回落到锁定的基线目标——实测核验）；
- **持久化**：三个标志 + 锁定基线存于 SharedPreferences `mobile_data_policy`，进程重启（`ApiService.onCreate`）与开机（`BootCompletedReceiver`）经 `syncPolicy` 重新武装并立即纠正一次。

## 3. 命令接口定义（SystemApiInterface.onEvent）

### 3.1 新增命令（本批次，6 条）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetMobileDataEnabled` | enabled（Boolean，必填） | Map：{success, supported, enabled, dataEnabled, tmDataEnabled, mobile_data} 或 {error} | ASR-0275/0276 |
| `IsMobileDataEnabled` | 无 | Map：{success, supported, enabled, dataEnabled, tmDataEnabled, mobile_data, forceClose, forceOpen, locked} | ASR-0275/0276 |
| `ForceCloseMobileData` | forceClose（Boolean，必填） | Map：{success, supported, forceClose, dataEnabled, tmDataEnabled, mobile_data} 或 {error} | ASR-0276 |
| `ForceOpenMobileData` | forceOpen（Boolean，必填） | Map：{success, supported, forceOpen, dataEnabled, tmDataEnabled, mobile_data} 或 {error} | ASR-0277 |
| `SetMobileDataStateLocked` | locked（Boolean，必填） | Map：{success, supported, locked, baseline, dataEnabled, tmDataEnabled, mobile_data} 或 {error} | ASR-0279 |
| `IsMobileDataStateLocked` | 无 | Map：{success, supported, locked, baseline, dataEnabled, tmDataEnabled, mobile_data} 或 {error} | ASR-0279 |

**字段说明**：`dataEnabled` 为主查询通道（`ConnectivityManager.getMobileDataEnabled()`，ROM 客户端壳）；`tmDataEnabled` 为交叉核对值（`TelephonyManager.isDataEnabled()`）；`mobile_data` 为 `Settings.Global.mobile_data` 镜像键（本 ROM 实测为遗留键、不随框架状态变化，仅附报对照）。

**Set 类命令返回结构**（以 SetMobileDataEnabled enabled=false 为例，插 SIM 真机预期）：

```
{success:boolean, supported:true, enabled:false, dataEnabled:false, tmDataEnabled:false, mobile_data:1}
# 调用 TelephonyManager.setDataEnabled(false) 后轮询读回（≤5s），dataEnabled==false 才 success=true；不一致 → success=false + error（含期望态/实际态/callResult）
```

**无 SIM 设备**（本机实测路径，插卡前）：

```
{success:false, supported:true, enabled:false, dataEnabled:false, tmDataEnabled:false, mobile_data:1,
 error:"mobile data not applied (expected false, actual false, callResult=true)"}
# callResult=true 表示 API 通道与权限核验通过；无订阅时 per-sub 状态无可翻转目标，如实上报而非误报成功
```

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> p = new HashMap<>();
p.put("enabled", false);
Map result = api.onEvent("SetMobileDataEnabled", p);

Map<String, Object> f = new HashMap<>();
f.put("forceOpen", true);
Map result2 = api.onEvent("ForceOpenMobileData", f);

Map<String, Object> l = new HashMap<>();
l.put("locked", true);
Map result3 = api.onEvent("SetMobileDataStateLocked", l);
```

**广播通道**（TestBroadcast，与现有命令一致）：

```bash
adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast \
  -a ON_SQA_INTERFACE_TEST --es event SetMobileDataEnabled \
  --es param '{"enabled":false}'
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/
│   └── MobileDataPolicyManager.java          # 新增：移动数据开关/查询/强制关闭/强制开启/状态锁定引擎（反射 setDataEnabled + 10s 纠正器 + 优先级解析 + SharedPreferences 持久化）
├── service/command/mobiledata/
│   ├── SetMobileDataEnabled.java             # 新增：ASR-0275/0276 设置
│   ├── IsMobileDataEnabled.java              # 新增：ASR-0275/0276 查询
│   ├── ForceCloseMobileData.java             # 新增：ASR-0276 强制关闭
│   ├── ForceOpenMobileData.java              # 新增：ASR-0277 强制开启
│   ├── SetMobileDataStateLocked.java         # 新增：ASR-0279 状态锁定
│   └── IsMobileDataStateLocked.java          # 新增：ASR-0279 锁定查询
├── service/ApiBinder.java                    # 注册 6 个新命令
├── service/ApiService.java                   # onCreate 重新武装移动数据纠正器（进程重启）
└── broadcast/BootCompletedReceiver.java      # 开机重新武装移动数据纠正器

testapp/
├── src/main/java/com/hmdm/testapp/
│   ├── DeviceStateTestActivity.java          # 扩展：新增移动数据 10 个按钮（关/开/查询/强制关开/强制开开/锁开锁关/锁查询）
│   ├── TestActions.java                      # 新增 6 个事件（含参数校验）与事件目录
│   └── MainActivity.java                     # 主页按钮文案更新（含移动数据）
└── src/main/res/layout/
    ├── activity_device_state_test.xml        # 新增移动数据小节
    └── activity_main.xml                     # 主页按钮文案更新
```

## 5. 执行逻辑

```
SetMobileDataEnabled(enabled):
  1. 参数校验（缺 enabled → {error}）
  2. 反射调用 TelephonyManager.setDataEnabled(enabled)（失败回退 setDataEnabled(getDefaultDataSubscriptionId(), enabled)；callResult 记录）
  3. 轮询读回 dataEnabled（≤5s）：期望==enabled
  4. 附报 tmDataEnabled / mobile_data 镜像键；读回==期望 → success=true，否则 success=false + error
  5. 返回 {success, supported, enabled, dataEnabled, tmDataEnabled, mobile_data}

ForceCloseMobileData(forceClose):
  1. 参数校验（缺 forceClose → {error}）
  2. true：持久化 forceClose 标志；立即 setDataEnabled(false)；启动 10s 纠正器
  3. false：清标志；无其他活动标志则停止纠正器
  4. 返回 {success, supported, forceClose, dataEnabled, tmDataEnabled, mobile_data}

ForceOpenMobileData(forceOpen):  同上模式，目标为 true（ASR-0277）

SetMobileDataStateLocked(locked):
  1. 参数校验（缺 locked → {error}）
  2. 读取当前 dataEnabled；查询不可用 → {success:false, supported:false, error}
  3. true：持久化 locked + lockedBaseline=当前状态；启动 10s 纠正器
  4. false：清标志；无其他活动标志则停止纠正器
  5. 返回 {success, supported, locked, baseline, dataEnabled, tmDataEnabled, mobile_data}

纠正器 tick（10s，单线程池）:
  1. 无任何活动标志 → 停止纠正器
  2. 解析目标：forceClose→false；否则 forceOpen→true；否则 locked→lockedBaseline
  3. 当前 dataEnabled != 目标 → 重新 setDataEnabled(目标)（日志记录 was/ok）

IsMobileDataEnabled() / IsMobileDataStateLocked():  读取状态与各标志如实返回
```

**安全设计**：本批命令参数全部为布尔值，无字符串进入 shell / 系统命令，无命令注入面；反射方法名均为代码内常量；仅调用系统既有 @hide 接口（MODIFY_PHONE_STATE 签名权限），无权限面扩大、无 ROM 改动。

## 6. 权限与归属

- `MODIFY_PHONE_STATE`（signature|privileged）：平台签名 Launcher 安装时自动授予（`dumpsys package com.hmdm.launcher` 实测 `MODIFY_PHONE_STATE: granted=true`）——与既有 `NETWORK_SETTINGS`/`WRITE_SECURE_SETTINGS` 等签名权限同模式；
- PhoneInterfaceManager.setDataEnabled 对 uid=1000 平台签名应用放行（Settings 应用同权限路径）；
- 无 ROM 侧代码改动，无 root 依赖；不修改 AIDL / lib 模块；testapp 无需新增权限（命令在 Launcher 进程执行）；不修改 `device_admin.xml`，无需重启 framework。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺 enabled / forceClose / forceOpen / locked 参数 | 命令返回 {error："missing parameter: ..."}，不 crash；testapp 侧同样拦截 |
| 无 SIM / 无订阅（本机实测路径） | 反射调用被框架接受（callResult=true，API 与权限核验通过），但 per-sub 状态无可作用订阅、读回不变 → success=false + error 如实上报（含期望态/实际态/callResult），不误报成功 |
| 反射 setDataEnabled 失败（方法缺失、权限拒绝） | callResult=false，如实报 success=false + error |
| 查询通道不可用（getMobileDataEnabled/isDataEnabled 均失败） | 查询返回 {supported:false, success:false, error}；锁定命令在快照前即拒绝（不持久化标志） |
| 设置后状态未达期望 | success=false + error（含期望态/实际态），如实上报，可重试 |
| 强制纠正器运行时用户/应用改变状态 | 10 秒内被回滚（强制关闭→OFF、强制开启→ON、锁定时→锁定基线）；`false` 停止纠正并保持用户状态 |
| 多标志同时活动 | 固定优先级：强制关闭 > 强制开启 > 状态锁定；停止高优先级标志后自动回落至次优先级目标（实测核验） |
| 进程被杀 / 重启 / 开机 | 标志与锁定基线持久化于 SharedPreferences `mobile_data_policy`；ApiService.onCreate 与 BootCompletedReceiver 经 syncPolicy 重新武装并立即纠正一次（实测核验） |
| 本机无 SIM 时的验收边界 | 开关真实翻转、射频级数据连接建立、镜像键随动均需插 SIM 真机验收；本批次记录为受限验证 |
| 测试结束状态恢复 | 三个标志均须置 false 清空；`mobile_data_policy.xml` 留空标志（无残留策略），系统状态与测试前一致（实测核验） |

## 8. 真机验证记录（2026-08-05，Android 13 / API 33 userdebug，平台签名 + device owner）

（命令级用例结果见测试用例设计文档，此处记录机制核验关键结论）

| 核验项 | 结果 |
|---|---|
| 设备基线 | **本机双卡槽均无 SIM**（gsm.sim.state=ABSENT,ABSENT）：射频 POWER_OFF、无订阅（subId 无效）；`settings get global mobile_data`=1；框架状态 dataEnabled=false |
| 部署 | Launcher（平台签名，uid=1000，device owner 保持）与 testapp 重装成功；`MODIFY_PHONE_STATE: granted=true`（签名权限平台签名自动授予） |
| 无 SIM 路径（本机实测） | ① `SetMobileDataEnabled enabled=true/false`：callResult=true（API 通道与权限核验通过）、读回不变 → `{success:false, supported:true, error:"mobile data not applied (expected X, actual false, callResult=true)"}`，不 crash、不误报成功；② 强制打开：`forceOpen=true` 后 10 秒 tick 日志 `corrector: mobile data was false, re-applied true (ok=true)`（纠正器循环存活）；③ 强制关闭/锁定：标志与基线正确持久化（`mobile_data_policy.xml` 实测 4 键） |
| 持久化/重新武装 | `ForceOpenMobileData forceOpen=true` → `am force-stop com.hmdm.launcher` → HOME 重新拉起 → `IsMobileDataEnabled` 报 forceOpen=true，纠正器在新进程继续 10 秒 tick（实测） |
| 优先级 | 强制开启中再锁定（baseline=false）：纠正器仍按强制开启目标执行（tick 持续 re-applied true）；停止强制开启后无新纠正日志（目标回落至锁定基线=false=当前态，idle）（实测） |
| 无残留 | 三个标志置 false 后 `mobile_data_policy.xml` 无活动策略；mobile_data 镜像保持 1、框架状态 false（与测试前一致）、无射频/订阅变化 |
| 功能路径（插 SIM 真机待验） | per-sub dataEnabled 真实翻转（Settings 页移动数据开关状态随动）、射频数据连接建立/断开、强制纠正器回滚用户操作——代码路径与本机已验证机制同构，待插 SIM 机型完成开关行为验收 |

## 9. 与既有批次的关系

- 与 NFC（ASR-0315/0316/0317）、飞行模式（ASR-0319/0320/0321）共用「Set/Is 共用引擎 + 强制纠正器 + SharedPreferences 持久化 + ApiService/BootCompletedReceiver 重新武装」模式；本批次新增多标志**优先级解析**（强制关闭 > 强制开启 > 状态锁定）与**锁定基线快照**，为 ASR-0279 特有语义；
- 命令注册、testapp 事件目录、文档体系与既有批次一致；无 AIDL/lib 模块改动。
