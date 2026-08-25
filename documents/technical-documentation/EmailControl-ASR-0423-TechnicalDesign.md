# 邮件管控（ASR-0423）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 实现机制 |
|---|---|---|---|
| ASR-0423 | 邮件管控 | 需求原文仅有"邮件管控"，无具体行为描述（requirements-rationality-analysis 3.2 曾建议补充被管控的邮件应用、账户类型、收发策略和验收条件，否则删除）；按 Sheet1 第四节建议优先级 P2 规划的**"邮件管控（应用黑名单）"**语义实现：被管控的邮件应用（黑名单）对用户不可用 | device owner `DevicePolicyManager.setPackagesSuspended`（挂起，应用无法运行）+ `setApplicationHidden`（隐藏，桌面/应用管理入口消失）双通道黑名单引擎 |

**归属**：需求文档归属列为「Launcher（MDM）」——`setPackagesSuspended`/`setApplicationHidden` 均为 **device owner 公开 DPM 接口**（API 24 / 21 起），无需平台签名特权、无需隐藏 API、无需 shell、无 ROM 改动。与 Sheet1 第四节 P2 规划一致，属于"入口/设置锁定（隐藏组件或锁定设置页）"批次主题：黑名单应用从入口层面（Launcher 图标、Settings 应用列表、显式启动）整体不可达。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署。测试前设备基线（2026-08-07 实测）：策略 off（mode=0）、黑名单空、controlled 集合空、无任何应用处于 suspended/hidden 状态（`dumpsys package <pkg>` 的 `User 0:` 行 `hidden=false suspended=false`）。

## 2. 技术选型与可行性核验

### 2.1 通道核验（2026-08-07 真机）

| 接口 | API 级别 | 调用方要求 | 本部署 |
|---|---|---|---|
| `DevicePolicyManager.setPackagesSuspended(admin, String[], boolean)` | 24 | device owner（或 profile owner） | 满足（`dpm list-owners` = DeviceOwner,Affiliated） |
| `DevicePolicyManager.isPackageSuspended(admin, String)` | 24 | 同上 | 满足 |
| `DevicePolicyManager.setApplicationHidden(admin, String, boolean)` | 21 | device owner | 满足 |
| `ApplicationHelper.isApplicationHidden`（dpm.isApplicationHidden） | 21 | 同上 | 满足 |

**状态载体**：挂起/隐藏状态由 DevicePolicyManagerService 持久化（`/data/system/device_policies.xml`，框架 ABX 序列化；本 ROM 上 `dumpsys package <pkg>` 的 `User 0:` 行 `hidden=true suspended=true` 为系统侧对照通道），整机重启保持（真机核验：重启后仍为 hidden=true suspended=true，且 BootCompletedReceiver 经 syncPolicy 重新武装）。**不需要 Launcher 侧 SharedPreferences 保存系统状态本身**——引擎仅持久化策略配置（模式 + 黑名单 + 自身管控账本），系统状态由 DPM 管理。

### 2.2 效果语义（真机核验）

- **挂起**：黑名单应用无法启动——`am start` 返回 `Error type 3`（activity 不存在语义，挂起应用对用户不可见），进程若在运行会在短时间内被杀；挂起应用收到 `ACTION_PACKAGES_SUSPENDED` 广播并停止交互。
- **隐藏**：应用从 Launcher 图标与 Settings "All apps" 列表消失（与 ASR-0067/0068 应用管理可见性策略同一 `setApplicationHidden` 机制，该批次已 uiautomator 核验列表消失；本批次以 `dumpsys package` hidden 标志 + 启动被拒为 DPM 层证据）。
- **恢复**：解除挂起 + 解除隐藏后应用可正常使用（真机核验：monkey 启动成功、`hidden=false suspended=false`）。

### 2.3 策略模型

与 ASR-0149 WLAN 权限黑名单同形：**模式 mode：0=关闭（恢复），2=黑名单**（无需白名单——"邮件管控"语义是禁止被管控的邮件应用，白名单模式无对应业务含义，Sheet1 规划亦为"应用黑名单"）。

- **黑名单**：整体替换（StringSet 持久化），任意已安装应用可入名单（典型目标 Gmail/Outlook/Exchange/系统邮件客户端等邮件应用；本 ROM 无邮件应用，真机验收以普通应用占位——机制与应用类型无关）。
- **立即生效**：设置模式/名单后立即对全部已安装应用执行；名单变更走"差量"路径（新增→管控、移除→恢复），`ApplyEmailPolicy` 提供全量对账。
- **保护名单**：`com.hmdm.launcher`（Launcher 自身，管控会断管理链路）、`com.hmdm.testapp`（测试调用方）及系统框架包 `com.android.settings`/`com.android.permissioncontroller`/`com.android.systemui`/`com.android.shell`/`com.android.providers.settings` 永不被挂起/隐藏，即使被列入黑名单（真机核验：testapp 列入名单后 `suspended=false hidden=false`，返回 skipped）；名单经共享常量类 `PolicyConstants` 提供，与 ASR-0067/0068 应用管理引擎同一来源（防漂移，代码审查后统一）。
- **关键前缀保护**：`com.android.providers.`/`com.android.phone`/`com.android.bluetooth`/`com.android.nfc`/`com.android.cellbroadcast`/`com.mediatek.`/`com.android.inputmethod.` 前缀系统包跳过——隐藏运行中的系统服务承载者曾触发本 ROM VcnManagementService 崩溃循环（2026-08-07 入口/设置锁定批次事故记录），复用同保护列表；**其余系统应用（如 com.android.music 音乐应用）允许入名单**（邮件应用本身在部分 ROM 上就是系统应用，如 com.android.email）。
- **管控账本（controlled）**：引擎只恢复**自己成功挂起+隐藏过的包**——黑名单移除或模式关闭时仅对 controlled 集合中的包解除管控，**解除前另查兄弟策略持久化名单**（ASR-0017 禁止运行白名单 `run_block_whitelist`、ASR-0067/0068 应用管理可见性 `settings_entry_policy` 模式+名单：黑名单模式命中、白名单模式隐藏非白名单第三方应用均视为仍被兄弟策略管控），仍被兄弟策略管控的包保持管控、账本保留（兄弟策略释放后下次对账再恢复），绝不触碰其他策略控制的包，避免跨策略干扰；账本随全量对账剪枝（已卸载包移除）。
- **幂等自愈**：全量对账（ApplyEmailPolicy/syncPolicy）对已处于目标状态的包跳过写入；若用户绕过策略手动解除（settings 页"卸载/停用"路径外无正常通道，此处指异常态），再次对账即恢复管控。

### 2.4 接口冲突与既有逻辑

- 与 ASR-0017 禁止运行白名单（suspend+hide 白名单模式）共用系统挂起/隐藏状态：**账本机制保证恢复方向互不干扰**（各自只恢复自己管控的包）；管控方向（列表交集）双方都执行挂起+隐藏，结果一致无冲突。
- 与 ASR-0067/0068 应用管理可见性（仅 setApplicationHidden）可叠加：同包被两策略隐藏时，一方恢复不会导致另一方失效（DPM 状态位为布尔，恢复后另一策略的对账会重新置位）。
- 与 ASR-0099/0104/0131 单包隐藏命令（SetApplicationHidden）独立：引擎只对账自己账本内的包。

### 2.5 系统配置声明

- 挂起/隐藏状态经 DPM 公开接口写入，由框架持久化于 `device_policies.xml`（**非** `device_admin.xml` uses-policy——setPackagesSuspended/setApplicationHidden 无需 uses-policy 声明）；
- 策略配置（mode/blacklist/controlled）持久化于 Launcher SharedPreferences `email_policy`；
- 本批次**不修改** `device_admin.xml`、不写 Settings 键、manifest 无新增权限，无需重启 framework。

## 3. 命令接口定义（SystemApiInterface.onEvent）

### 3.1 新增命令（本批次，6 条）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetEmailPolicyMode` | mode（int，必填：0=off，2=blacklist） | Map：{success, mode, blocked, restored, skipped} 或 {error}（缺省/非整数 mode 解析为 -1 → "invalid mode" 错误，绝不静默回落到 0） | ASR-0423 |
| `GetEmailPolicyMode` | 无 | Map：{success, mode, blacklist, controlled} | ASR-0423 |
| `SetEmailBlacklist` | packageNames（List<String>，整体替换） | Map：{success, blacklist, blocked?, restored?, skipped?}（黑名单模式下立即对账） | ASR-0423 |
| `GetEmailBlacklist` | 无 | Map：{success, blacklist} | ASR-0423 |
| `ApplyEmailPolicy` | 无 | Map：{success, mode, blocked, restored, skipped} | ASR-0423 |
| `IsEmailControlled` | packageName（String，必填） | Map：{success, packageName, controlled, listed, mode, suspended, hidden} 或 {error} | ASR-0423 |

blocked/restored 条目结构：`{packageName, suspended, hidden}`（挂起+隐藏或解除后的系统实际状态）；skipped：保护名单与关键前缀包列表。

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> p = new HashMap<>();
p.put("mode", 2);
Map result = api.onEvent("SetEmailPolicyMode", p);
// {"RESULT":{"mode":2,"blocked":[],"restored":[],"skipped":[...],"success":true}}

Map<String, Object> p2 = new HashMap<>();
p2.put("packageNames", Arrays.asList("com.android.music"));
Map result2 = api.onEvent("SetEmailBlacklist", p2);
// {"RESULT":{"blocked":[{"packageName":"com.android.music","suspended":true,"hidden":true}],...}}
```

**广播通道**（TestBroadcast，与现有命令一致）：

```bash
adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast \
  -a ON_SQA_INTERFACE_TEST --es event SetEmailPolicyMode \
  --es param '{"mode":2}'
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/
│   └── EmailControlPolicyManager.java     # 新增：邮件应用黑名单策略引擎（suspend+hide 双通道，读回核对，账本，syncPolicy）
├── service/command/email/
│   ├── SetEmailPolicyMode.java            # 新增：模式设置（0/2，写后立即对账）
│   ├── GetEmailPolicyMode.java            # 新增：模式/名单/账本查询
│   ├── SetEmailBlacklist.java             # 新增：黑名单整体替换（黑名单模式立即对账）
│   ├── GetEmailBlacklist.java             # 新增：黑名单查询
│   ├── ApplyEmailPolicy.java              # 新增：全量对账（幂等）
│   └── IsEmailControlled.java             # 新增：单包管控状态查询
├── service/ApiBinder.java                 # 注册 6 个新命令
├── service/ApiService.java                # onCreate 增加 EmailControlPolicyManager.syncPolicy（进程重启重新武装）
└── broadcast/
    ├── BootCompletedReceiver.java         # 开机增加 syncPolicy（重新武装 + 全量对账）
    └── PackageChangedReceiver.java        # 新装/替换应用增加 applyEmailPolicyToPackage（黑名单自动套用）

testapp/
├── src/main/java/com/hmdm/testapp/
│   ├── TestActions.java                   # 新增 6 个事件（含参数校验）与事件目录
│   ├── EmailControlTestActivity.java      # 新增：邮件管控测试页（ASR-0423）
│   └── MainActivity.java                  # 主页新增入口按钮
├── src/main/res/layout/
│   ├── activity_email_control_test.xml    # 新增：测试页布局
│   └── activity_main.xml                  # 主页新增按钮
└── src/main/AndroidManifest.xml           # 注册 EmailControlTestActivity
```

## 5. 执行逻辑

```
SetEmailPolicyMode:
  1. 参数解析（缺省/非整数 mode → -1；-1 非 0/2 → {error: "invalid mode: -1 (0=off, 2=blacklist)"}，绝不静默执行 mode=0）
  2. 持久化 mode（SharedPreferences email_policy.email_mode）
  3. applyEmailPolicy 全量对账，返回 blocked/restored/skipped

SetEmailBlacklist:
  1. 去空/去重后整体替换持久化（email_policy.email_blacklist）
  2. 若当前 mode=2 → applyEmailPolicy 立即对账；否则 applied=false 仅存名单

ApplyEmailPolicy（全量对账）:
  1. mode/名单/账本读取；非 device owner → {error}
  2. getInstalledApplications(MATCH_UNINSTALLED_PACKAGES) 枚举（本 ROM 隐藏应用从用户视图消失，恢复路径必须显式枚举）
  3. 逐包：
     a. 保护名单/关键前缀（PolicyConstants 共享常量）→ skipped
     b. mode=2 且入黑名单：**按需探测**当前挂起/隐藏状态（仅此分支产生 DPM 状态读取，mode=0 时零探测），已挂起且已隐藏 → 跳过（幂等，不记账）；否则 blockPackage（先 setPackagesSuspended(true) 后 setApplicationHidden(true)，任一失败该包记 failed 不继续；成功后**读回 isPackageSuspended/isApplicationHidden 核对**，不一致记 failed）
     c. 非黑名单但在账本 controlled 中：**先查兄弟策略（ASR-0017 run_block_whitelist、ASR-0067/0068 settings_entry_policy 模式+名单）是否仍要管控该包**——仍管控则保持管控、账本保留（后续对账再评估），否则 unblockPackage（先 suspend(false) 后 hidden(false)，读回核对）
     d. 记账：block 成功 → controlled.add；unblock 成功 → controlled.remove
  4. 账本剪枝（不在已安装集合的条目移除）后持久化
  5. 返回 {success, mode, blocked[], restored[], skipped[]}（blocked/restored 条目含 success + 读回 suspended/hidden）

IsEmailControlled:
  1. packageName 校验
  2. 返回 {controlled: mode==2 && listed, listed, mode, suspended, hidden}（suspended/hidden 为系统实时状态；保护名单包可能 listed=true 但 suspended=false hidden=false——引擎跳过执行，如实上报）

applyEmailPolicyToPackage（PackageChangedReceiver）:
  1. 新装/替换包若 mode=2 且入黑名单 → blockPackage + 记账（新装邮件应用立即被管控）
  2. 否则若在账本中且兄弟策略不再管控 → unblockPackage + 销账

syncPolicy（ApiService.onCreate / BootCompletedReceiver）:
  mode != 0 → applyEmailPolicy 全量对账（系统挂起/隐藏状态由 DPM 持久化，重启后对账幂等）
```

**安全设计**：本批次参数仅包名数组与整数模式；包名不进入 shell/系统命令（DPM 接口按包名直接调用），无命令注入面；包名仅做去空白/去重规范化，未安装包允许入名单（安装后经 PackageChangedReceiver 自动管控——故意为之，防"先装后控"空窗）；不触碰邮件数据/账户/凭据。

## 6. 权限与归属

- 全部命令为 device owner 公开 DPM 接口（setPackagesSuspended/setApplicationHidden/isPackageSuspended/isApplicationHidden），**无新增权限、无 manifest 变更、无需 device owner 之外的任何身份**；
- 不修改 AIDL / lib 模块；testapp 无需新增权限；
- 保护名单与关键前缀经共享常量类 `PolicyConstants` 提供，与入口/设置锁定批次（ASR-0067/0068）应用管理引擎同一来源（防漂移）。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| mode 非法（非 0/2）或**缺省/非整数** | 命令返回 {success:false, error:"invalid mode: x (0=off, 2=blacklist)"}（缺省/非整数解析为 -1 哨兵——绝不静默回落 0 造成误关管控），不持久化、不对账 |
| 缺 packageName / packageNames | 命令返回缺参提示（IsEmailControlled："missing parameter: packageName"；testapp 侧 SetEmailBlacklist："missing parameter: packageNames (array)"），不 crash |
| 保护名单包（Launcher/testapp/settings/permissioncontroller/systemui/shell/providers.settings）被列入黑名单 | 引擎跳过执行（skipped），**永不挂起/隐藏**（真机核验：testapp 列入名单后 suspended=false hidden=false）；IsEmailControlled 如实返回 listed=true + controlled=true + suspended=false/hidden=false（语义：被策略选中但被保护，未执行） |
| 关键前缀系统包（含输入法 com.android.inputmethod.）被列入黑名单 | 同上 skipped（防 VcnManagementService 式框架崩溃与输入法失效，2026-08-07 事故修复模式） |
| 未安装包被列入黑名单 | 允许入名单；对账时不在已安装集合 → 不执行；**新装该包时 PackageChangedReceiver 自动管控**（防"先装后控"空窗） |
| blockPackage 挂起失败/写后读回不一致 | 该包记 failed（不继续隐藏，避免"隐藏但可运行"半态；读回不一致同样记 failed 并附实际 suspended/hidden 值），其余包继续；success=false |
| 用户/其他策略已挂起同包（跨策略交集） | 管控方向再置位幂等（不记账——防止恢复时误解除他策略状态）；恢复方向仅触碰本引擎账本内包 |
| 兄弟策略仍管控账本内包（ASR-0017/0067/0068 名单命中） | 解除前查兄弟策略持久化名单：仍管控 → 保持管控、账本保留（兄弟释放后下次对账再恢复），**绝不解除他策略状态** |
| 黑名单移除/模式关闭恢复 | 仅对账本内包解除（unblock 读回核对）；兄弟策略管控的包不受影响 |
| 应用被卸载 | 账本条目在下次对账时剪枝；DPM 隐藏/挂起状态随包移除自动清除 |
| 整机重启 | DPM 持久化保持 hidden/suspended（真机核验），BootCompletedReceiver syncPolicy 幂等对账（logcat "email control policy restored"） |
| 进程被杀/重启 | ApiService.onCreate syncPolicy 重新武装（真机核验 force-stop 后恢复） |
| **Launcher 数据被清除（pm clear）时黑名单在生效** | DPM 隐藏/挂起状态持久于 device_policies.xml 不受影响，但 Launcher SharedPreferences（模式/黑名单/账本）随数据清除丢失——mode 回落 0 且账本为空，API 无法再恢复已管控包；**恢复需 adb root 手工编辑 /data/system/device_policies.xml 删除对应包条目的 hidden/suspended 记录并 `stop && start` 重启 framework**（见 system_configurations.md 第 11 节） |
| 非 device owner 环境 | 对账返回 {success:false, error:"device owner required for setPackagesSuspended/setApplicationHidden"}，查询命令仍可用 |

## 8. 真机验证记录（2026-08-07，Android 13 / API 33 userdebug，平台签名 + device owner）

（命令级用例结果见测试用例设计文档，此处记录机制核验关键结论）

| 核验项 | 结果 |
|---|---|
| 设备基线 | 策略 off、黑名单空、无任何包 hidden/suspended（`dumpsys package` `User 0:` 行核对） |
| 管控落地 | `SetEmailPolicyMode mode=2` + `SetEmailBlacklist [com.android.music, com.android.documentsui]` → 两包均 suspended=true hidden=true（写后读回核对）；`dumpsys package` 系统侧对照一致 |
| 启动被拒 | 挂起后 `am start` 目标 activity → `Error type 3`（挂起语义，activity 解析失效） |
| 恢复闭环 | 名单移除/模式关闭 → 读回 hidden=false suspended=false；monkey 启动成功（com.android.documentsui/.LauncherActivity 为真实入口，.DocumentsActivity 为本 ROM 不存在类——错误路径核验时以 resolve-activity 对照） |
| 幂等对账 | ApplyEmailPolicy 在已管控态 → blocked=[] restored=[]（无重复写入） |
| 保护名单 | com.hmdm.testapp 列入黑名单 → skipped，suspended=false hidden=false（引擎跳过）；代码审查后补全系统框架包（settings/permissioncontroller/systemui/shell/providers.settings）与输入法前缀（com.android.inputmethod.）经共享 `PolicyConstants` 生效 |
| 关键前缀 | 全量对账 skipped 列表含 com.android.providers.* / com.android.phone / com.android.bluetooth / com.android.nfc / com.android.cellbroadcast / com.mediatek.* 全部系统服务承载包 |
| 进程重启 | root kill Launcher 进程 → ApiService.onCreate `syncPolicy: email control policy restored` + `applyEmailPolicy mode:2 blocked:0 restored:0`（幂等） |
| 整机重启 | 重启后 `dumpsys package` 仍 hidden=true suspended=true（device_policies.xml 持久化）；BootCompletedReceiver `syncPolicy: email control policy restored` |
| 测试后恢复 | 模式恢复 0、黑名单清空，重启后基线核对 mode=0/黑名单空/两包 hidden=false suspended=false，无残留 |
| 代码审查修复复核 | ① 保护名单经 `PolicyConstants` 与 ASR-0067/0068 引擎共享（settings/systemui/permissioncontroller/shell/inputmethod 前缀补全，黑名单 com.android.systemui → skipped）；② `SetEmailPolicyMode` 缺省/非整数 mode → "invalid mode: -1" 错误（不再静默回落 0）；③ blocked/restored 条目改为写后读回核对（含 success 字段）；④ 恢复前查兄弟策略名单（ASR-0017/0067/0068），仍管控的包保持管控；⑤ 全量对账状态探测改为按需（仅黑名单命中分支），mode=0 零 DPM 状态读取；以上均经真机复核（见测试用例设计文档"代码审查后修正"节） |
| 环境记录 | 本 ROM 无邮件应用（无 GMS、无系统邮件客户端），真机验收以 com.android.music / com.android.documentsui 占位（机制与应用类型无关，任意已安装应用可入名单）；reboot 后本 ROM 通知栏（NotificationShade）保持展开聚焦（mCurrentFocus 恒为 NotificationShade），uiautomator 无法再截取应用窗口——UI 按钮级验证以"测试页为 resumed activity（mFocusedApp）+ 与 IPC 共用 TestActions.execute() 引擎"为等价证据（与历史批次同架构） |
