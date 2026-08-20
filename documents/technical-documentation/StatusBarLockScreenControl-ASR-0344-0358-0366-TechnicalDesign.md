# 状态栏/锁屏/锁屏密码管控（ASR-0344/0358/0366）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 底层机制 |
|---|---|---|---|
| ASR-0344 | 禁用系统状态栏 | 禁用/恢复系统状态栏显示 | `dpm.setStatusBarDisabled`——**本 ROM 限制（MTK fork DPMS 无该方法/静默丢弃）** |
| ASR-0358 | 打开/关闭/强制关闭锁屏 | 禁用/恢复锁屏（keyguard） | device owner `dpm.setKeyguardDisabled` |
| ASR-0366 | 设置锁屏密码 | 设置/清除锁屏密码 | `resetPasswordWithToken`（token 机制）+ **`dpm.resetPassword` 回退**（2026-08-13 补命令注册与回退修复） |

**归属**：「Launcher（MDM）」。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署；本机基线：无锁屏密码、状态栏正常、keyguard 正常。

## 2. 技术选型与可行性核验

### 2.1 ASR-0344 状态栏禁用（本 ROM 限制）

| 方案 | 说明 | 结论 |
|---|---|---|
| `dpm.setStatusBarDisabled` | device owner 公开接口——**本 ROM 真机核验（2026-08-14）：写入后 `dumpsys statusbar` 无变化、状态栏保持显示；DPM 读方法 `isStatusBarDisabled` 不存在（NoSuchMethodException，MTK fork 移除）**——与 B19 批次"状态栏通道无机制"结论一致（当时记录为"DPMS 静默丢弃"，本次确认为方法缺失/无效果） | **受限**（ROM 适配后验证） |

命令已改为**写后读回核对、如实上报**（success=false + note，遵循 ASR-0040/0042 先例）；需求维持部分完成。

### 2.2 ASR-0358 锁屏开关（真机可用）

- `dpm.setKeyguardDisabled(admin, disabled)`：device owner 公开接口（真机可用）；**行为验证**：禁用后电源键息屏/亮屏直接回到前台应用（无 keyguard，mDreamingLockscreen=false、焦点=应用），恢复后重新出现 keyguard——2026-08-14 真机闭环；
- **读方法 `dpm.isKeyguardDisabled` 本 ROM 不存在**（NoSuchMethodException）——Is 命令如实返回 note；状态验证以行为为准（息屏亮屏是否出现 keyguard）。

### 2.3 ASR-0366 锁屏密码（2026-08-13 补命令 + 修复）

- **补命令** `SetLockScreenPassword`（password 参数，空串=清除）与 `IsLockScreenPasswordSet`（查询）；
- **token 机制**：`Utils.initPasswordReset`（Launcher 启动时 setResetPasswordToken + 持久化 prefs token）→ `resetPasswordWithToken`；
- **2026-08-14 真机核验与修复**：本机从未设置过密码 → **reset token 从未激活**（AOSP 语义：用户输入当前密码后才激活；无密码时永不激活）→ 原实现 `resetPasswordWithToken` 直接返回 false——**修复：token 未激活时回退 `dpm.resetPassword(password, 0)`**（API 26+ 已废弃但 DO 可用，真机验证设置成功）；
- **清除路径**：token 激活后 `resetPasswordWithToken("")` 可清除；**token 未激活时空密码清除被拒（false）**——实测恢复经 shell `locksettings clear --old <password>`（已知密码）或 UI 输入；文档记录两路径；
- **查询**：`dpm.getActivePasswordQuality` 本 ROM 不存在 → 改读 `Settings.Secure lockscreen.password_type`——**真机核验该镜像在设置密码后仍为 0（本 ROM 不写该键）**，查询命令如实返回但参考价值有限；可靠状态指示：`locksettings get-disabled`（有密码时要求 --old 凭据，无密码时直接返回）、`dumpsys lock_settings` 的 CredentialType（Password/None）；
- **本 ROM keyguard 渲染怪癖**：设置密码后 keyguard 界面仅显示时钟（uiautomator 无密码输入框）——UI 解锁无法在测试环境完成，经 shell locksettings 清除（如实记录）。

## 3. 命令接口定义

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetStatusBarDisabled` | disabled（Boolean，必填） | **Map**{success, disabled, note?}——写后读回核对；本 ROM 无效时 success=false+note（2026-08-14 改造） | ASR-0344 |
| `IsStatusBarDisabled` | 无 | Map{disabled, note?}——本 ROM 读方法缺失时 note（2026-08-14 改造） | ASR-0344 |
| `SetKeyguardDisabled` | disabled（Boolean，必填） | Boolean（dpm.setKeyguardDisabled 结果） | ASR-0358 |
| `IsKeyguardDisabled` | 无 | Map{disabled, note?}——本 ROM 读方法缺失时 note | ASR-0358 |
| `SetLockScreenPassword` | password（String，必填；空=清除） | Boolean（token 激活→WithToken；未激活→resetPassword 回退，2026-08-14 修复） | ASR-0366 |
| `IsLockScreenPasswordSet` | 无 | Map{passwordSet, quality, note?}——lockscreen.password_type 镜像（本 ROM 设置后仍 0，参考有限） | ASR-0366 |

**调用示例**：

```java
Map<String, Object> p = new HashMap<>();
p.put("password", "Test1234");
Map result = api.onEvent("SetLockScreenPassword", p);
// {"RESULT":true}（密码已设；清除需 token 激活或 shell locksettings clear --old）

Map result2 = api.onEvent("SetStatusBarDisabled", param);
// {"RESULT":{"success":false,"disabled":false,"note":"this ROM's DPMS drops setStatusBarDisabled ..."}}
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/
├── util/Utils.java                                # initPasswordReset / passwordReset（2026-08-14 补 resetPassword 回退）
├── syrius/service/command/statusbar/              # SetStatusBarDisabled（2026-08-14 读回核对化）
│                                                 # IsStatusBarDisabled（2026-08-14 新增）
├── syrius/service/command/keyguard/               # SetKeyguardDisabled（既有）
│                                                 # IsKeyguardDisabled（2026-08-14 新增）
├── syrius/service/command/lock_screen/            # SetLockScreenPassword（2026-08-14 新增）
│                                                 # IsLockScreenPasswordSet（2026-08-14 新增）
└── syrius/service/ApiBinder.java                  # 注册 4 条新命令

testapp/
└── src/main/java/com/hmdm/testapp/
    ├── TestActions.java                           # 6 个事件
    ├── StatusBarLockScreenControlTestActivity.java # 新增测试页
    └── src/main/res/layout/activity_status_bar_lock_screen_test.xml
```

## 5. 执行逻辑

```
SetStatusBarDisabled(disabled): dpm.setStatusBarDisabled → 读回核对 → {success=读回一致, disabled, note}
SetKeyguardDisabled(disabled): dpm.setKeyguardDisabled → true（行为验证：息屏亮屏是否出现 keyguard）
SetLockScreenPassword(password):
  1. token 激活 → resetPasswordWithToken(password, token)
  2. token 未激活 → dpm.resetPassword(password, 0)（回退，2026-08-14 修复）
  3. 空密码清除：需 token 激活（未激活被拒——恢复经 shell locksettings clear --old）
IsLockScreenPasswordSet(): Settings.Secure lockscreen.password_type（本 ROM 镜像不更新，参考有限）
```

**安全设计**：密码设置为高影响操作——测试流程"设置→验证→立即清除"；清除失败时已知密码可经 shell locksettings 恢复（文档记录恢复路径）；调用方经 ApiBinder 门禁。

## 6. 权限与归属

- dpm.setKeyguardDisabled/setStatusBarDisabled/resetPassword 系列为 device owner 公开接口（无需权限声明）；
- 无 ROM 侧代码改动，无 root 依赖；testapp 无新增权限。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 0344 本 ROM 无效 | 命令写后读回核对、success=false+note（维持部分完成，ROM 适配后验证） |
| 0358 读方法缺失 | Is 命令返回 note；状态验证以行为为准（息屏亮屏） |
| token 未激活 | SetLockScreenPassword 回退 resetPassword（设置成功）；空密码清除被拒——恢复路径：shell `locksettings clear --old <password>` 或 UI 解锁后 token 激活再清除 |
| 密码已设后查询 | lockscreen.password_type 镜像本 ROM 不更新（quality 0）——可靠指示：locksettings get-disabled/dumpsys lock_settings CredentialType |
| keyguard 渲染怪癖 | 本 ROM 密码键盘 UI 在 uiautomator 不可见（仅时钟）——UI 解锁在测试环境不可行，经 shell 清除（如实记录） |
| 测试后状态 | 密码清除（CredentialType=None）、keyguard/状态栏恢复、DO 在位 |

## 8. 真机验证记录（2026-08-14，Android 13 / API 33 userdebug，平台签名 + device owner）

| 核验项 | 结果 |
|---|---|
| ASR-0344 | SetStatusBarDisabled(true) → 命令返回 true 但 dumpsys statusbar 无变化、状态栏保持显示；读方法 isStatusBarDisabled 不存在（NoSuchMethodException）——改造后命令如实 {success:false, note}；**本 ROM 状态栏禁用无机制（与 B19 批次结论一致）** |
| ASR-0358 禁用 | SetKeyguardDisabled(true) → true；电源键息屏/亮屏 → **直接回到前台应用（无 keyguard 出现）**——禁用生效（行为验证） |
| ASR-0358 恢复 | SetKeyguardDisabled(false) → keyguard 恢复（息屏亮屏出现 keyguard） |
| ASR-0366 设置 | SetLockScreenPassword(Test1234) → true（token 未激活时 resetPassword 回退生效）；dumpsys lock_settings CredentialType=Password、locksettings get-disabled 要求 --old 凭据——密码生效 |
| ASR-0366 清除 | 空密码清除被拒（token 未激活，false）→ **经 `locksettings clear --old Test1234` 清除**（CredentialType=None、get-disabled 恢复正常）——清除路径记录 |
| ASR-0366 查询 | IsLockScreenPasswordSet：password_type 镜像在密码已设时仍为 0（本 ROM 不写镜像键）——如实返回，参考有限 |
| 测试后状态 | 密码清除、keyguard/状态栏基线、屏幕恢复前台应用、DO 在位、bound=true |
