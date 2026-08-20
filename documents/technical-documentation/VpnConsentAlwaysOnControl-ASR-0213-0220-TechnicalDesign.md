# VPN 免交互授权与 Always-on 管控（ASR-0213/0220）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 实现机制 |
|---|---|---|---|
| ASR-0213 | VPN | 授权指定应用可免交互连接 VPN 白名单 | 双通道：`Settings.Secure.vpn_prepared` 键（框架切换 VPN 的免确认短路）+ `android:activate_vpn` AppOps 置 ALLOWED（本 ROM 首次授权的判定门）；清除时复位 AppOps 并删键 |
| ASR-0220 | VPN | 保持/取消/查询指定 VPN 连接始终运行 | `dpm.setAlwaysOnVpnPackage(admin, pkg, lockdown, allowlist)`（device owner 通道），查询经 `dpm.getAlwaysOnVpnPackage`，取消经 `setAlwaysOnVpnPackage(admin, null, false)` |

**归属**：ASR-0213 为「Launcher（MDM）+ 系统 API」——本 ROM（Android 13，MTK fork）的 DevicePolicyManager 无完整能力，落地为平台签名 uid=1000 直写受保护设置键 + 反射 `AppOpsManager.setMode`（字符串 op 变体）；ASR-0220 为「Launcher（MDM）」——device owner 公开 DPM 接口。**无需 ROM 改动**。

**基线**（2026-08-13 实测）：`vpn_prepared`=null、`dumpsys device_policy` 的 `mAlwaysOnVpnPackage`=null / `mAlwaysOnVpnLockdown`=false、`com.syriusrobotics.platform.launcher`（GGR，`MyVpnService` 为 VpnService）的 `ACTIVATE_VPN` AppOps=default、无 TRANSPORT_VPN 网络。

## 2. 技术选型与可行性核验（本 ROM 逆向/真机核验，重点）

### 2.1 首次授权判定门：ACTIVATE_VPN AppOps，而非 vpn_prepared

真机实验闭环（2026-08-13，GGR 为被测包）：

1. **`vpn_prepared` 单独写入无效**：写 `Settings.Secure vpn_prepared=com.syriusrobotics.platform.launcher` 后全新启动 GGR，`VpnService.prepare()` 仍返回授权 Intent，`com.android.vpndialogs/.ConfirmDialog`（"Connection request" 弹窗）照常弹出——本 ROM 框架仅当 `prepare(oldPackage, newPackage)` 的 **oldPackage** 命中该键时才免确认（已授权应用的 profile 切换场景），`VpnService.prepare(Context)` 的首次授权路径不消费该键；
2. **`ACTIVATE_VPN` AppOps 是首次授权的判定门**：`cmd appops set com.syriusrobotics.platform.launcher android:activate_vpn allow` 后 `prepare()` 返回 null，GGR 直接建立 tun0（`dumpsys connectivity` 网络 OwnerUid=10123），无弹窗；复位 default 后弹窗复现（负向闭环）；
3. **本 ROM 的 VPN 授权不豁免 device owner**（与 NetworkAccessControl 批次结论一致）：DO 自身调用 `prepare()` 同样受 AppOps 门控。

**结论**：ASR-0213 的免交互白名单以 AppOps 授权为主通道、`vpn_prepared` 为框架语义上的辅助键（覆盖已授权应用的切换场景），两者同时写入。

### 2.2 AppOps 写入通道（复用 ASR-0043/0044/0149 批次已验证通道）

- 经反射 `AppOpsManager.setMode(String op, int uid, String pkg, int mode)`（`AppOpsPolicyManager.setAppOpMode`，字符串 op 变体——op 数值码由 ROM 自身表解析，免疫厂商重排；本 ROM 已核验 op 码与 AOSP 有差异）；
- `uid` 经 `PackageManager.getApplicationInfo(pkg)` 取用；包未安装时如实返回 `appOpsGranted=false`、`appOpsReadBack=-1` 并**回滚** `vpn_prepared` 写入（不留半成品状态）；
- 读回核对经 `unsafeCheckOpNoThrow`（Q+，hiddenapi 对平台签名 uid=1000 豁免）：授予路径写 MODE_ALLOWED（0）、清除路径写 MODE_DEFAULT（本 ROM 执行点按原始模式判断，DEFAULT 视为拒绝）；
- **一次性授权语义（本 ROM 框架行为，如实记录）**：框架在 VPN 拆除时将 `ACTIVATE_VPN` 复位 default（本批次与 VPN/防火墙批次多次实测一致），故 `SetVpnPreparedFor` 的免弹窗授权在目标包 VPN 断开后失效，需再次下发；**持续免交互由 ASR-0220 的 always-on 机制承担**（见 2.3）。

### 2.3 Always-on 通道：dpm.setAlwaysOnVpnPackage 在本 ROM 可用

- `dpm.setAlwaysOnVpnPackage(admin, pkg, lockdown, allowlist)` 真机闭环通过（2026-08-13）：设置后 `dumpsys device_policy` 出现 `mAlwaysOnVpnPackage=<pkg>`，framework 同步镜像 `Settings.Secure.always_on_vpn_app`/`always_on_vpn_lockdown`；
- **副作用即收益**：DO 设置 always-on 时框架自动授予目标包 `ACTIVATE_VPN=ALLOWED` 并**自动拉起其 VpnService**（GGR 被 force-stop、无任何界面启动的情况下 6 秒内 tun0 建立，OwnerUid=10123 核验）——"免交互 + 始终运行"一步到位；
- **lockdown 置位/清除**：`lockdown=true` → `mAlwaysOnVpnLockdown=true`、`always_on_vpn_lockdown=1`；清除 always-on 时框架拆除 VPN 并将 `ACTIVATE_VPN` 复位 default；
- `getAlwaysOnVpnPackage` 查询、`setAlwaysOnVpnPackage(admin, null, false)` 取消均闭环通过；
- **边界语义（AOSP 行为，如实记录）**：目标包被 `force-stop` 后其 VpnService 处于 stopped 状态，框架不会在其解除 stopped 前重新拉起（VPN 网络随进程终止消失）；always-on 的保持语义作用于 VPN 断连重连，不覆盖 force-stop；
- 与既有文档差异修正：信息查询批次（2026-08-07）记录的"本 ROM `setAlwaysOnVpnPackage` 抛 UnsupportedOperationException"与本批次三次实测矛盾（三次设置/查询/清除全部成功），以本批次实测为准，原记录在 InfoQueryControl 文档中保留备查。

### 2.4 权限与声明

- `AppOpsManager.setMode` 反射通道：MODIFY_APP_OPS_STATE 等价特权（平台签名 uid=1000）——manifest 无需新增声明（与 ASR-0043/0044/0149 批次同通道）；
- `dpm.setAlwaysOnVpnPackage`：device owner 公开接口，**无需** uses-policy 声明（本 ROM 实测）；
- `Settings.Secure vpn_prepared` 直写：WRITE_SECURE_SETTINGS（平台签名 uid=1000 隐式持有）；
- 无 `device_admin.xml` 变更，无需重启 framework。

## 3. 命令接口定义（SystemApiInterface.onEvent）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetVpnPreparedFor` | packageName（必填） | Map：{success, packageName, vpnPreparedSet, appOpsGranted, appOpsReadBack（0=ALLOWED/-1=包不存在）, vpnPreparedRolledBack（失败回滚附报）} 或 {success:false, error:"missing parameter: packageName"} | ASR-0213 |
| `ClearVpnPreparedFor` | 无 | Map：{success, clearedPackage（清除前的包，可为 null）, vpnPreparedCleared} | ASR-0213 |
| `GetVpnPreparedForPackageName` | 无 | String：当前免交互授权包名，null 表示无 | ASR-0213 |
| `SetAlwaysOnVpn` | packageName（必填）、lockdown（可选 boolean，缺省 true）、lockdownAllowlist（可选 String 集合，缺省 null） | boolean：设置是否成功（本 ROM `dpm.setAlwaysOnVpnPackage` 无异常路径） | ASR-0220 |
| `GetAlwaysOnVpn` | 无 | String：当前 always-on 包名，null 表示无 | ASR-0220 |
| `ClearAlwaysOnVpn` | 无 | 无返回值（清除动作本身） | ASR-0220 |

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> p = new HashMap<>();
p.put("packageName", "com.syriusrobotics.platform.launcher");
Map result = api.onEvent("SetVpnPreparedFor", p);
// {"RESULT":{"appOpsGranted":true,"appOpsReadBack":0,"packageName":"com.syriusrobotics.platform.launcher",
//   "success":true,"vpnPreparedSet":true}}
```

**广播通道**（TestBroadcast，与现有命令一致）：

```bash
adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast \
  -a ON_SQA_INTERFACE_TEST --es event SetVpnPreparedFor \
  --es param '{"packageName":"com.syriusrobotics.platform.launcher"}'
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/
│   ├── VpnAlwaysOnManager.java           # 修改：applyVpnPreparedFor/clearVpnPreparedFor
│   │       （vpn_prepared 键写入/清除 + AppOpsPolicyManager 通道授予/复位
│   │        ACTIVATE_VPN；失败回滚；grantVpnPreparedFor/clearVpnPrepared
│   │        布尔包装保留（InitialSetupActivity 兼容））
│   └── AppOpsPolicyManager.java          # 复用：setAppOpMode/getAppOpMode（反射字符串 op）
└── service/command/vpn/
    ├── SetVpnPreparedFor.java            # 修改：参数校验 + 返回详细结果 Map
    ├── ClearVpnPreparedFor.java          # 修改：返回详细结果 Map
    └── GetVpnPreparedForPackageName.java # 既有：查询
    # SetAlwaysOnVpn/GetAlwaysOnVpn/ClearAlwaysOnVpn 既有（ASR-0220 复用）
```

testapp（`testapp/src/main/java/com/hmdm/testapp/`）：

```
├── VpnConsentTestActivity.java           # 新增：ASR-0213/0220 测试页（7 按钮）
├── VpnVerifier.java                      # 修改：新增 getVpnPreparedLocal 本地探针
└── TestActions.java                      # 新增 8 个事件（6 命令 + 2 探针：
│                                         #       GetVpnPreparedLocal、GetAlwaysOnVpn 等）
```

## 5. 边界情况与异常处理

| 场景 | 处理 |
|---|---|
| packageName 缺失 | 返回 `{success:false, error:"missing parameter: packageName"}` |
| 目标包未安装 | `appOpsGranted=false`、`appOpsReadBack=-1`，`vpn_prepared` 写入回滚（`vpnPreparedRolledBack=true`），`success=false` |
| 清除时无授权包 | `clearedPackage=null`、`vpnPreparedCleared=true`、`success=true`（幂等） |
| 授权后目标包 VPN 断开 | 框架复位 `ACTIVATE_VPN` 为 default——授权一次性失效，需再次下发 `SetVpnPreparedFor`（或经 `SetAlwaysOnVpn` 持续授权） |
| 授权期间目标包 VPN 已连接 | 复位 AppOps 不拆除已建立的 VPN（连接保持至框架断开），仅后续 `prepare()` 需重新授权 |
| 清除 always-on | 框架拆除目标包 VPN 并复位其 `ACTIVATE_VPN` 为 default |
| always-on 目标包被 force-stop | VPN 网络随进程终止消失，框架待 stopped 状态解除后恢复保持（AOSP 语义，非缺陷） |
| 非本 ROM（vpn_prepared 消费方不同） | AppOps 通道为 AOSP 通用判定门，语义保持一致；引擎无 ROM 专属硬编码 |

## 6. 真机验收记录

（2026-08-13 部署后按测试用例设计文档执行，全部用例通过；逐条结果见 `documents/test-case-design/VpnConsentAlwaysOnControl-ASR-0213-0220-TestCaseDesign.md` 第 6 节执行记录）

**真机关键闭环（2026-08-13）**：

1. **免交互授权**：`SetVpnPreparedFor`（vpn_prepared + ACTIVATE_VPN=ALLOWED）→ 全新启动 GGR 无 "Connection request" 弹窗（ResumedActivity=GGR MainActivity、无 ConfirmDialog）、tun0 建立（OwnerUid=10123）；清除后弹窗复现（负向闭环）；
2. **Always-on**：`SetAlwaysOnVpn`（lockdown=false）→ `mAlwaysOnVpnPackage` 置位、框架自动授予 ACTIVATE_VPN=ALLOWED 并自动拉起 GGR VpnService（GGR 处于 force-stop、无界面启动，6 秒内 tun0 建立）；lockdown=true → `mAlwaysOnVpnLockdown`/`always_on_vpn_lockdown=1` 置位；`ClearAlwaysOnVpn` → 状态回 null/false、框架拆除 VPN、AppOps 复位 default；
3. **查询与探针**：`GetVpnPreparedForPackageName`/`GetAlwaysOnVpn` 与系统键/`dumpsys device_policy` 全程一致；testapp `GetVpnPreparedLocal` 第二进程读回一致（跨进程交叉核对）；
4. **异常路径**：缺参拒绝、未安装包拒绝 + 回滚、清除幂等。
