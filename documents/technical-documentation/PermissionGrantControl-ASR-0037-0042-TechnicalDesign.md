# 权限授予管控（ASR-0037/0042）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 底层机制 |
|---|---|---|---|
| ASR-0037 | 授予应用运行时权限 | 免交互批量授予指定应用的运行时权限 | device owner `dpm.setPermissionGrantState` 按 requestedPermissions 批量 GRANTED |
| ASR-0042 | 设置禁用"允许显示在其他应用上层"权限开关 | 免交互授予/查询"显示在其他应用上层"（SYSTEM_ALERT_WINDOW）权限 | AppOps `setMode(OPSTR_SYSTEM_ALERT_WINDOW, ALLOWED)` 反射 + `unsafeCheckOpNoThrow` 读回 |

**归属**：「Launcher（MDM）」（device owner 公开接口 / 平台签名 AppOps）。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署。

## 2. 技术选型与可行性核验

### 2.1 运行时权限批量授予（ASR-0037）

| 方案 | 说明 | 结论 |
|---|---|---|
| device owner `dpm.setPermissionGrantState(admin, pkg, perm, PERMISSION_GRANT_STATE_GRANTED)` | 公开接口，逐权限免交互授予（含 POST_NOTIFICATIONS 等普通 grant 通道受限的权限）；配合 `dpm.setPermissionPolicy(PERMISSION_POLICY_AUTO_GRANT)` 安装后自动授权 | **采用** |
| `pm grant` shell 通道 | 仅对已请求且非 policy-fixed 的权限有效；本 ROM 实测对未请求权限抛 SecurityException | 辅助对照 |

- 命令 `GrantAllRuntimePermission`：遍历目标包 `requestedPermissions` 逐个 `setPermissionGrantState(GRANTED)`；
- 写后核对：testapp 侧本地探针 `CheckPermissionsLocal`（checkSelfPermission 读自身 CAMERA/RECORD_AUDIO/POST_NOTIFICATIONS 状态）交叉验证；
- **恢复语义**：授予后权限为 policy-fixed（`pm revoke` 报 "Cannot revoke policy fixed permission"，2026-08-13 实测）；恢复需 DO 侧 `setPermissionGrantState(DEFAULT)` 或卸载重装——文档记录。

### 2.2 悬浮窗权限（ASR-0042）

**机制**：平台签名应用反射 `AppOpsManager.setMode(String op, int uid, String pkg, int mode)` 置 `OPSTR_SYSTEM_ALERT_WINDOW`=ALLOWED（免交互、无弹窗），查询 `unsafeCheckOpNoThrow` 读回。

**2026-08-13 真机核验结论——本 ROM 无法免交互授予 SYSTEM_ALERT_WINDOW**：

| 通道 | 结果 |
|---|---|
| Launcher 反射 setMode（字符串 op 变体） | 调用无异常，`cmd appops get` 状态保持 default，rejectTime 记录拒绝 |
| shell `cmd appops set com.hmdm.testapp SYSTEM_ALERT_WINDOW allow` | 同样静默忽略，状态保持 default |
| Settings 用户确认界面（ACTION_MANAGE_OVERLAY_PERMISSION + uiautomator 点击开关） | 开关显示为已开启（列表副标题 "Allowed"），但**强制执行状态（AppOps）仍为 default**——Settings 界面状态与强制执行脱钩 |
| 真实悬浮窗验证（TYPE_APPLICATION_OVERLAY addView） | `BadTokenException: permission denied for window type 2038`——框架按 AppOps 拒绝 |

与 ASR-0040（MANAGE_EXTERNAL_STORAGE）同类：**本 ROM AppOpsService 忽略 SYSTEM_ALERT_WINDOW 的 setMode 写入（各通道均无效），需 ROM 侧适配 op 写入能力后验证**。据此按 ASR-0040 先例处理：命令保留（GrantOverlay 本批次改为**写后读回核对、如实返回**——原实现仅返回写入调用结果 true，掩盖了写入被忽略的事实），需求维持**部分完成**（命令面完成、ROM 适配后可验证）。

## 3. 命令接口定义

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `GrantAllRuntimePermission` | packageName（String，必填） | Boolean（批量授予调用完成） | ASR-0037 |
| `GrantOverlay` | packageName（String，必填） | **Map**：{success, granted, note?}——2026-08-13 改为写后读回核对（success=granted=AppOps 实际状态；本 ROM 写入被忽略时 success=false + note 说明） | ASR-0042 |
| `CheckGrantOverlay` | packageName（String，必填） | Boolean（AppOps 读回） | ASR-0042 |

**调用示例**：

```java
Map<String, Object> p = new HashMap<>();
p.put("packageName", "com.hmdm.testapp");
Map result = api.onEvent("GrantAllRuntimePermission", p);
// {"RESULT":true}（POST_NOTIFICATIONS 等由探针核对 granted）

Map result2 = api.onEvent("GrantOverlay", p);
// {"RESULT":{"success":false,"granted":false,
//   "note":"AppOps SYSTEM_ALERT_WINDOW write did not take effect on this ROM ..."}}
// （本 ROM 限制，如实上报）
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/
├── syrius/utils/ApplicationHelper.java           # autoGrantAllRuntimePermissions（批量 setPermissionGrantState）
├── util/OverlayPermissionHelper.java             # grantOverlayPermission（AppOps setMode 反射）
│                                                 # checkOverlayPermission（unsafeCheckOpNoThrow 读回）
├── syrius/service/command/permissiond/
│   ├── GrantAllRuntimePermission.java            # 既有
│   ├── GrantOverlay.java                         # 2026-08-13 改为写后读回核对、如实上报
│   └── CheckGrantOverlay.java                    # 既有
└── syrius/service/ApiBinder.java                 # 既有注册

testapp/
└── src/main/java/com/hmdm/testapp/
    ├── TestActions.java                          # GrantAllRuntimePermission/GrantOverlay/CheckGrantOverlay/
    │                                             # CheckPermissionsLocal/CanDrawOverlaysLocal/TryShowOverlay 事件
    ├── PermissionGrantVerifier.java              # 新增：本地权限探针（checkSelfPermission/canDrawOverlays/
    │                                             #   真实 TYPE_APPLICATION_OVERLAY 窗口验证）
    ├── PermissionGrantControlTestActivity.java   # 新增测试页
    └── src/main/res/layout/activity_permission_grant_test.xml
```

## 5. 执行逻辑

```
GrantAllRuntimePermission(packageName):
  1. 参数校验（缺 packageName → testapp 侧拦截）
  2. getPackageInfo(GET_PERMISSIONS) 取 requestedPermissions（无声明则直接返回）
  3. 逐权限 dpm.setPermissionGrantState(GRANTED)
  4. 返回调用完成；testapp 探针 checkSelfPermission 交叉核对（授予后 policy-fixed，恢复需 DO 重置/重装）

GrantOverlay(packageName):
  1. 参数校验（缺 packageName → testapp 侧拦截）
  2. getApplicationInfo 取 uid → 反射 AppOpsManager.setMode(OPSTR_SYSTEM_ALERT_WINDOW, uid, pkg, ALLOWED)
  3. unsafeCheckOpNoThrow 读回核对：granted=ALLOWED？
  4. 返回 {success=写入且读回一致, granted=读回状态, note=未生效原因}
     （本 ROM 各通道写入被忽略 → success=false + note，维持部分完成）

CheckGrantOverlay(packageName): unsafeCheckOpNoThrow 读回 ALLOWED？
```

**安全设计**：命令仅对平台签名/系统/shell 调用方开放（ApiBinder 既有门禁）；OverlayPermissionHelper 反射仅固定方法/常量；testapp 悬浮窗探针即时添加+移除，无残留窗口。

## 6. 权限与归属

- ASR-0037：device owner 公开 `setPermissionGrantState`（无需权限声明）；
- ASR-0042：平台签名 uid=1000 反射 AppOpsManager.setMode（AppOps 写通道，无需 manifest 权限声明）；
- testapp：本地探针仅用自身权限查询与窗口验证（无新增权限）；TryShowOverlay 使用 TYPE_APPLICATION_OVERLAY（需悬浮窗权限，未授予时如实报 BadTokenException——探针本身即验证手段）；
- 无 ROM 侧代码改动，无 root 依赖。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺参 | testapp 侧返回 missing parameter，不 crash |
| 包不存在 | GrantAllRuntimePermission 异常捕获返回（引擎内 catch）；OverlayPermissionHelper 返回 false |
| 目标包未声明权限 | requestedPermissions 为空则直接返回（无权限可授） |
| 本 ROM AppOps 写忽略 | GrantOverlay 读回不一致 → success=false + note 如实上报（ASR-0040 先例），不伪造成功 |
| 探针线程 | TryShowOverlay 切主线程 Looper 执行（WindowManager 要求），latch 等待，超时返回 |
| 授予后恢复 | 授予为 policy-fixed，`pm revoke` 被拒（SecurityException，真机实测）；恢复经 DO setPermissionGrantState(DEFAULT) 或卸载重装——文档记录 |
| 测试后状态 | POST_NOTIFICATIONS 保持已授予（无害，后续批次可继续使用）；悬浮窗始终 default（未生效） |

## 8. 真机验证记录（2026-08-13，Android 13 / API 33 userdebug，平台签名 + device owner）

| 核验项 | 结果 |
|---|---|
| ASR-0037 基线 | CheckPermissionsLocal：CAMERA=true、RECORD_AUDIO=true（前期批次已授）、POST_NOTIFICATIONS=false |
| ASR-0037 批量授予 | `GrantAllRuntimePermission packageName=com.hmdm.testapp` → true；探针 POST_NOTIFICATIONS=true（授予生效）；`pm revoke` 报 "Cannot revoke policy fixed permission"（policy-fixed 语义验证） |
| ASR-0042 AppOps 写入 | Launcher 反射 setMode 无异常但 `cmd appops get` 保持 default（rejectTime 记录）；shell `cmd appops set ... allow` 同样忽略——**本 ROM AppOpsService 忽略 SYSTEM_ALERT_WINDOW 写入** |
| ASR-0042 Settings 通道 | ACTION_MANAGE_OVERLAY_PERMISSION 界面开关显示开启（uiautomator 确认 Switch checked=true、"Allowed" 副标题），但强制执行状态仍 default——界面与执行脱钩 |
| ASR-0042 真实悬浮窗 | testapp TryShowOverlay（TYPE_APPLICATION_OVERLAY addView）→ `BadTokenException: permission denied for window type 2038`——按 AppOps 拒绝 |
| GrantOverlay 诚实化 | 改造后返回 {success=false, granted=false, note=ROM 限制说明}（原实现掩盖写入失败），CheckGrantOverlay=false |
| 测试后恢复 | POST_NOTIFICATIONS 保持 granted（policy-fixed，无害）；悬浮窗状态 default；屏幕/桌面基线恢复（本机测试中出现状态栏通知栏卡住现象，经电源键+唤醒恢复，已记录） |
