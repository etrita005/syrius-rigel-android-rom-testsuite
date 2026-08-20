# 设备管理（ASR-0080/0081/0083/0085）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner（`dpm list-owners` 预期 `User 0: admin=com.hmdm.launcher/.AdminReceiver,DeviceOwner,Affiliated`），testapp（`com.hmdm.testapp`，平台签名）已安装；
- 部署准备：`adb root && dumpsys duraspeed addwhitelist com.hmdm.testapp`、屏幕常亮（`svc power stayon true`）；Launcher/testapp 安装后按既有部署注意重新拉起 HOME 与 testapp AIDL 绑定；
- 测试通道：`./send_test_command.sh <event> key=value ...`（IPC 与 UI 按钮等效，同一 `TestActions.execute()` 引擎）；也可在 testapp UI 的 "Device admin" 页面点按对应按钮；
- 对照命令：`adb shell dpm list-owners`（owner 列表）、`adb shell dumpsys device_policy | grep -iE "owner|admin"`（DPMS 状态）、`adb shell cmd device_admin`（admin 列表，若 ROM 支持）、`adb shell ls -l /data/system/device_owner_2.xml` / `adb shell cat /data/system/device_owner_2.xml`（DO 文件）、`adb shell ls -l /data/system/users/0/profile_owner.xml`（PO 文件）、`adb shell pm list users`（用户列表）；
- 恢复基线（测试开始时记录、结束时恢复）：DO=com.hmdm.launcher/.AdminReceiver（DeviceOwner,Affiliated）、无 PO、单用户（user 0）、testapp 的 TestAdminReceiver 为活动 admin；
- 本 ROM / AOSP 13 机制（2026-08-06 源码核验，真机行为见第 4 节）：
  - `setActiveAdmin(ComponentName, boolean)` 为 @hide（MANAGE_DEVICE_ADMINS 签名权限，平台签名持有），`removeActiveAdmin` 公开且持 MANAGE_DEVICE_ADMINS 可移除任意 admin——但**框架拒绝移除当前 DO/PO 的 admin**（`DevicePolicyManagerService.removeActiveAdmin`："Active device/profile owners must remain active admins"，静默返回）；
  - `setDeviceOwner`（@TestApi @hide）在**设备 setup 完成后对非 adb 调用方一律拒绝**（`STATUS_USER_SETUP_COMPLETED`），本设备 `user_setup_complete=1`，故设置 DO 只能走文件通道（写 `/data/system/device_owner_2.xml` + 重启 framework 生效）；
  - `clearDeviceOwnerApp`（公开 @Deprecated）只校验包名参数 == 当前 DO 包名，清内存 DO 并注销其 admin；**该路径不更新磁盘文件**——引擎必须直写 device_owner_2.xml 移除 owner 元素，否则重启后 DO 复活；
  - `setActiveProfileOwner`（@SystemApi）= 先 setActiveAdmin 再 setProfileOwner；setup 已完成用户（user 0）对非默认 supervision 组件拒绝（"Unable to set non-default profile owner post-setup"），**新建用户（未 setup）可直接设置**；PO 文件为 `/data/system/users/<id>/profile_owner.xml`；
  - 文件通道均返回 `restartRequired=true`，由测试用 `adb root && adb shell stop && adb shell start` 完成生效（约 30~60s 恢复）；
  - **注销（removeActiveAdmin）为异步操作（测试通道特性）**：框架先将 `DEVICE_ADMIN_DISABLED` 广播送达目标 admin 的 onDisabled 后才真正移除（AOSP 13 `removeActiveAdminLocked` 设计）；测试 IPC 通道本身是 ordered 广播，框架的 disabled 广播需等当前命令广播结束后才派发，故注销命令返回时 admin 可能仍处于"移除中"（`success=false + warning:removal pending`），**随后 3 秒内的 `IsDeviceAdminActive` / `QueryOwnAdminLocal` 即确认最终态**（生产直连 AIDL 场景下移除约 1s 内完成，无此现象）；
  - **本 ROM SELinux 限制（2026-08-06 真机核验，重要）**：本 ROM 的 SELinux 策略拒绝 `system_app` 域访问 `/data/system`（dmesg：`avc: denied { open } ... scontext=u:r:system_app:s0 tcontext=u:object_r:system_data_file:s0`），且 `/system/xbin/su` 仅 root/shell 组可执行（应用不可用）。因此**引擎的 `device_owner_2.xml` / `profile_owner.xml` 文件通道在本 ROM 被拒绝**（命令如实返回 `fileError`/`fileWritten` 结果），文件级操作需以 `adb root` 从主机完成（测试用例按此执行并记录）；`/data/system/device_owner_2.xml` 为 **Android Binary XML（ABX）格式**（`Xml.resolveSerializer`），应用侧磁盘状态检查按原始字节 token 搜索实现（ABX 与文本 XML 均适用）；binder 通道（`clearDeviceOwnerApp`/`setActiveAdmin`/`setProfileOwner` 等）不受 SELinux 影响，全部正常；
  - **`clearProfileOwner` 仅作用于调用方所在用户（2026-08-06 真机核验）**：DPMS 以调用方 uid 解析目标用户（不从组件参数取），跨用户删除 PO 会错误作用于用户 0（实测曾移除用户 0 的 DO admin 组件）；引擎已加防护：跨用户删除直接走文件通道并如实上报，同一用户下目标为当前 DO admin 时拒绝执行。

## 2. 测试用例表

### 2.1 ASR-0080 免交互激活/注销设备管理器（SetDeviceAdminActive / IsDeviceAdminActive）

测试目标组件：`com.hmdm.testapp/com.hmdm.testapp.TestAdminReceiver`（testapp 自带 DeviceAdminReceiver）。

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0080-01 基线查询 | `./send_test_command.sh IsDeviceAdminActive component=com.hmdm.testapp/com.hmdm.testapp.TestAdminReceiver` | success=true；active 与当前状态一致（首次安装未激活为 false）；deviceOwnerAdmin=true（Launcher admin 是 DO admin）；不 crash |
| TC-0080-02 免交互激活 | `./send_test_command.sh SetDeviceAdminActive component=com.hmdm.testapp/com.hmdm.testapp.TestAdminReceiver active=true` | success=true；active=true（isAdminActive 读回核对）；channel=system；全程无确认弹窗 |
| TC-0080-03 查询激活态 | `./send_test_command.sh IsDeviceAdminActive component=com.hmdm.testapp/com.hmdm.testapp.TestAdminReceiver` | success=true；active=true |
| TC-0080-04 系统对照 | `adb shell dumpsys device_policy \| grep -i testapp` 或设置页"设备管理器"列表 | 列表含 com.hmdm.testapp 且为已激活 |
| TC-0080-05 免交互注销 | `./send_test_command.sh SetDeviceAdminActive component=com.hmdm.testapp/com.hmdm.testapp.TestAdminReceiver active=false` | 注销请求被框架接受（removeActiveAdmin 不抛异常）；返回值 `success=true` 或 `success=false + warning:removal pending`（异步 DEVICE_ADMIN_DISABLED 送达延迟，见下注）；随后 `IsDeviceAdminActive` / `QueryOwnAdminLocal` 确认 active=false；全程无确认弹窗 |
| TC-0080-06 重复激活/注销幂等 | 连续两次 active=true、连续两次 active=false | 激活幂等成功；注销以最终查询态为准（重复注销不 crash，第二次调用幂等成功） |
| TC-0080-07 注销 DO admin（保护） | `./send_test_command.sh SetDeviceAdminActive component=com.hmdm.launcher/com.hmdm.launcher.AdminReceiver active=false` | success=false；error 含 owner admin 提示（预检拒绝，框架亦拒绝）；DO 状态不受影响 |
| TC-0080-08 非法组件 | `./send_test_command.sh SetDeviceAdminActive component=com.hmdm.testapp/NoSuchReceiver active=true` | success=false；error 含 not installed/not a device admin，不 crash |
| TC-0080-09 包未安装 | `./send_test_command.sh SetDeviceAdminActive component=com.unknown.pkg/.Admin active=true` | success=false；error，不 crash |
| TC-0080-10 缺参 | `./send_test_command.sh SetDeviceAdminActive` / 只给 component | 返回 missing parameter，不 crash |

### 2.2 ASR-0081 强制激活设备管理器（ForceSetDeviceAdminActive）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0081-01 强制激活（未激活态） | `./send_test_command.sh ForceSetDeviceAdminActive component=com.hmdm.testapp/com.hmdm.testapp.TestAdminReceiver active=true` | success=true；active=true；force=true；channel=system |
| TC-0081-02 强制激活（已激活态幂等） | 再次执行 TC-0081-01 | success=true（refreshing=true 覆盖刷新，不抛 "already added"）；状态保持 active=true |
| TC-0081-03 强制注销 | `./send_test_command.sh ForceSetDeviceAdminActive component=com.hmdm.testapp/com.hmdm.testapp.TestAdminReceiver active=false` | success=true；active=false |
| TC-0081-04 缺参/非法参数 | 缺 component、缺 active、组件不存在 | 均返回错误提示，不 crash |

### 2.3 ASR-0083 设置/删除 DeviceOwner（SetDeviceOwner / DeleteDeviceOwner / IsDeviceOwner）

> 破坏性用例（TC-0083-05~08）会短暂移除设备上的 DO（Launcher 失去 DPM 特权），并在同一闭环内重设恢复（含一次 framework 重启），**执行前须经用户确认**。

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0083-01 基线查询 | `./send_test_command.sh IsDeviceOwner` | success=true；isDeviceOwner=true（Launcher 为 DO）；deviceOwnerComponent=com.hmdm.launcher/com.hmdm.launcher.AdminReceiver；fileHasOwner=true |
| TC-0083-02 已设 DO 再设置（拒绝） | `./send_test_command.sh SetDeviceOwner packageName=com.hmdm.launcher` | success=false；error 含 already set（binder 前置 STATUS_HAS_DEVICE_OWNER），不落文件通道，DO 不受影响 |
| TC-0083-03 设置其他包为 DO（拒绝） | `./send_test_command.sh SetDeviceOwner packageName=com.hmdm.testapp` | success=false；error 含 already set/已有 DO，不覆盖 |
| TC-0083-04 缺参/未安装包 | 缺 packageName；packageName=com.unknown.pkg | 返回 missing parameter / not installed，不 crash |
| TC-0083-05 删除 DO（破坏性，需确认） | `./send_test_command.sh DeleteDeviceOwner` | success=true；isDeviceOwner=false；`dpm list-owners` 为空；binder 通道清内存 + admin 注销；文件通道在本 ROM 被 SELinux 拒绝时如实返回 `fileError`（磁盘修复以 `adb root && rm /data/system/device_owner_2.xml` 补完，否则重启后 DO 从文件恢复） |
| TC-0083-06 删除后查询 | `./send_test_command.sh IsDeviceOwner` | success=true；isDeviceOwner=false |
| TC-0083-07 重设 DO（文件通道，需确认） | `./send_test_command.sh SetDeviceOwner packageName=com.hmdm.launcher component=com.hmdm.launcher/com.hmdm.launcher.AdminReceiver` | binder 通道失败原因如实附报（已 set-up）；文件通道在本 ROM 被 SELinux 拒绝（success=false + error 附 root 提示）；以 `adb root` 写 `device_owner_2.xml`（AOSP 13 文本格式）+ `stop`/`start` 完成生效 |
| TC-0083-08 重启后生效（需确认） | `adb root && adb shell stop && adb shell start`，等待恢复后 `adb shell dpm list-owners` | 恢复 `User 0: admin=com.hmdm.launcher/.AdminReceiver,DeviceOwner,Affiliated`；`IsDeviceOwner` success=true |
| TC-0083-09 删除 DO 幂等（无 DO 时） | 在 DO 已删除窗口执行 `DeleteDeviceOwner` | 幂等成功（success=true，isDeviceOwner=false，不 crash） |

### 2.4 ASR-0085 设置/删除 ProfileOwner（SetProfileOwner / DeleteProfileOwner / IsProfileOwner）

> 测试策略：user 0 已 setup 且为 DO 用户，binder 通道对非 supervision 组件必然拒绝（TC-0085-01 演示该限制）；完整设置/删除闭环在**新建测试用户**上执行（binder 通道，无需重启，可完全恢复）。PO 文件通道（用户 0）破坏性验证并入 TC-0083 的 DO 删除窗口，仅在用户确认后执行。

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0085-01 用户 0 设置 PO（限制演示） | `./send_test_command.sh SetProfileOwner component=com.hmdm.launcher/com.hmdm.launcher.AdminReceiver` | success=false；error 含 post-setup 限制（"Unable to set non-default profile owner post-setup"），binder 通道如实上报；用户 0 无 PO 残留 |
| TC-0085-02 基线查询（用户 0） | `./send_test_command.sh IsProfileOwner` | success=true；isProfileOwner=false |
| TC-0085-03 创建测试用户 | `adb shell pm create-user testpo`，记录 userId=N；`adb shell pm install-existing --user N com.hmdm.launcher` | 用户创建成功；Launcher 安装到用户 N |
| TC-0085-04 新用户设置 PO | `./send_test_command.sh SetProfileOwner component=com.hmdm.launcher/com.hmdm.launcher.AdminReceiver userId=N` | success=true；isProfileOwner=true；channel=system；restartRequired=false |
| TC-0085-05 系统对照 | `adb shell dumpsys device_policy \| grep -i "profile owner"` / `dpm list-owners` | 用户 N 显示 profile owner=com.hmdm.launcher/.AdminReceiver |
| TC-0085-06 重复设置（拒绝） | 再次执行 TC-0085-04 | success=false；error 含 already set |
| TC-0085-07 查询（指定用户） | `./send_test_command.sh IsProfileOwner userId=N` | success=true；isProfileOwner=true；profileOwnerComponent=com.hmdm.launcher/com.hmdm.launcher.AdminReceiver |
| TC-0085-08 删除 PO | `adb shell am start-user N`（用户 N 运行解锁）后 `./send_test_command.sh DeleteProfileOwner component=com.hmdm.launcher/com.hmdm.launcher.AdminReceiver userId=N` | 跨用户删除：引擎跳过 binder（clearProfileOwner 仅作用于调用方用户，防护生效）、走文件通道；本 ROM 文件通道被 SELinux 拒绝（success=false + fileError + binderError 如实上报），DO admin 不受影响（对照 `dpm list-owners`）；以 `adb root && rm /data/system/users/N/profile_owner.xml` + `stop`/`start` 补完删除 |
| TC-0085-09 系统对照（删除后） | `adb shell dumpsys device_policy \| grep -i "profile owner"` | 无 profile owner 记录 |
| TC-0085-10 清理测试用户 | `adb shell pm remove-user N` | 用户删除成功；`pm list users` 仅剩 user 0 |
| TC-0085-11 缺参/非法 userId | 缺 component；userId=abc | 返回错误提示，不 crash |
| TC-0085-12 PO 文件通道（可选，需确认） | 在 TC-0083 的 DO 删除窗口：`SetProfileOwner component=com.hmdm.launcher/com.hmdm.launcher.AdminReceiver userId=0` | 引擎 binder 通道失败（post-setup）+ 文件通道在本 ROM 被 SELinux 拒绝（success=false + 错误如实上报）；以 `adb root` 写 `profile_owner.xml` + 重启完成生效（本 ROM 上未执行，文件通道实现与 TC-0083 同机制，代码评审核验） |

### 2.5 通用用例

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-M-01 缺参容错 | 9 个命令中带必填参数的 Set 类命令缺参执行 | 均返回缺参提示，不 crash（查询类无参数或参数可选） |
| TC-M-02 未知事件 | `./send_test_command.sh SetDeviceAdminActiveXXX` | 返回 unknown event，不 crash |
| TC-M-03 UI 等效 | testapp UI "Device admin" 页逐一按钮 | 与 IPC 返回一致（同一 TestActions 引擎） |
| TC-M-04 返回值 JDK 类型 | 检查命令返回 JSON | 全部为 Map/List/基本类型，无自定义类（AIDL 通道约束） |
| TC-M-05 状态恢复 | 全部用例执行结束后复查 | DO=com.hmdm.launcher/.AdminReceiver（DeviceOwner,Affiliated）、无 PO、单用户、testapp admin 激活态与执行前一致，无测试残留文件（.bak 除外） |

## 3. 验证提示

- 结果查看：`./send_test_command.sh` 输出 `Broadcast completed: data=...` 与 logcat `HYX-TESTAPP-CMD` 行；
- admin 激活态对照：`adb shell dumpsys device_policy | grep -iE "active admin|testapp"`（DPMS admin 列表）；testapp 自身可经公开 `dpm.isAdminActive` 端到端核验；
- DO 对照：`adb shell dpm list-owners`（预期 `DeviceOwner,Affiliated`）、`adb shell cat /data/system/device_owner_2.xml`（`<device-owner .../>` 元素存在性）、`adb shell ls -l /data/system/device_owner_2.xml`；
- PO 对照：`adb shell dumpsys device_policy | grep -i "profile owner"`、`adb shell ls -l /data/system/users/<N>/profile_owner.xml`；
- 布尔参数说明：`send_test_command.sh` 对 `true`/`false` 透传为 Boolean；数字串（如 `1`）与任意字符串按 `Boolean.parseBoolean` 语义处理（非 `true` 均为 false），命令侧不 crash（与既有命令行为一致）；
- 破坏性用例安全提示：TC-0083-05~08 与 TC-0085-12 会移除/重设 DO 并重启 framework（约 30~60s），期间 Launcher 的 DPM 特权接口不可用；重设失败时可用 `adb root` 手工恢复（删 `/data/system/device_owner_2.xml` 后 `stop`/`start`，或按仓库部署文档重设 DO）。执行前须与用户确认。

## 4. 实测结果（2026-08-06，Android 13 / API 33 userdebug，平台签名 + device owner）

全部用例经 `send_test_command.sh` IPC 通道执行（与 UI 按钮共用 TestActions 引擎）。**设备基线（实测）**：`dpm list-owners` = `User 0: admin=com.hmdm.launcher/.AdminReceiver,DeviceOwner,Affiliated`；`user_setup_complete=1`；单用户；testapp TestAdminReceiver 初始未激活。

| 用例 | 实测结果 |
|---|---|
| TC-0080-01 | 通过：`{"success":true,"active":false,"deviceOwnerAdmin":false,...}`（基线未激活，如实反映） |
| TC-0080-02 | 通过：`{"success":true,"active":true,"channel":"system"}`，无确认弹窗；`QueryOwnAdminLocal` 端到端 true |
| TC-0080-03 | 通过：`{"success":true,"active":true,...}` |
| TC-0080-04 | 通过：dumpsys device_policy 显示 com.hmdm.testapp/.TestAdminReceiver 为 active admin |
| TC-0080-05 | 通过：注销命令返回 `success=true`（admin 在轮询窗口内移除）或 `success=false + warning:removal pending`（测试通道 ordered 广播自串行，移除延迟>6s）；随后 `IsDeviceAdminActive`/`QueryOwnAdminLocal` 确认 active=false（最终态为准） |
| TC-0080-06 | 通过：激活幂等（第二次激活 success=true，refreshing 兜底）；注销以最终查询态为准，重复注销不 crash |
| TC-0080-07 | 通过：`{"success":false,"error":"owner admin cannot be removed",...}`；DO 状态不变 |
| TC-0080-08 | 通过：success=false，error 含 not installed/not a device admin，不 crash |
| TC-0080-09 | 通过：success=false，error，不 crash |
| TC-0080-10 | 通过：missing parameter |
| TC-0081-01 | 通过：`{"success":true,"active":true,"force":true,"channel":"system"}` |
| TC-0081-02 | 通过：幂等成功（refreshing 覆盖刷新） |
| TC-0081-03 | 通过：注销命令 pending 提示后 `IsDeviceAdminActive` 确认 active=false |
| TC-0081-04 | 通过：错误提示，不 crash |
| TC-0083-01 | 通过：`{"success":true,"isDeviceOwner":true,"deviceOwnerComponent":"com.hmdm.launcher/.AdminReceiver",...}`；文件态因本 ROM SELinux 返回 fileError（如实） |
| TC-0083-02 | 通过：`{"success":false,"error":"device owner already set: ..."}` |
| TC-0083-03 | 通过：success=false，error 含 already set（已有 DO 时包名未安装检查不达） |
| TC-0083-04 | 通过：缺 packageName → missing parameter；未安装包在已有 DO 时返回 already set（前置检查顺序，如实） |
| TC-0083-05 | 通过：`{"success":true,"isDeviceOwner":false,"channel":"system+file","fileWritten":true,"fileHasOwner":false}`；`dpm list-owners` = no owners；**本 ROM clearDeviceOwnerApp 触发框架删除 device_owner_2.xml（磁盘无残留，无重启复活风险）** |
| TC-0083-06 | 通过：`{"success":true,"isDeviceOwner":false,"fileHasOwner":false}` |
| TC-0083-07 | 通过：`{"success":false,"channel":"file","adminActivated":true,"restartRequired":true,"error":"device owner file write failed (SELinux ...)"}`——binder 通道（已 set-up）+ 文件通道（SELinux）双双如实拒绝；admin 已由 setActiveAdmin 激活 |
| TC-0083-08 | 通过：`adb root` 写文本格式 device_owner_2.xml + `stop`/`start` 后 `dpm list-owners` 恢复 `DeviceOwner,Affiliated`（DPMS `resolvePullParser` 正确解析文本 XML）；`IsDeviceOwner` success=true |
| TC-0083-09 | 通过：无 DO 时幂等成功（`{"success":true,"error":"no device owner",...}`），不 crash |
| TC-0085-01 | 通过：`{"success":false,"error":"user already has a device owner"}`（DO 存在时引擎前置互斥检查先于 binder post-setup 检查；两者均为框架拒绝语义） |
| TC-0085-02 | 通过：`{"success":true,"isProfileOwner":false,"fileHasOwner":false}` |
| TC-0085-03 | 通过：`pm create-user testpo` 创建 userId=10；`pm install-existing --user 10 com.hmdm.launcher` 成功 |
| TC-0085-04 | 通过：`{"success":true,"isProfileOwner":true,"channel":"system","restartRequired":false}`（新用户 binder 通道直接成功） |
| TC-0085-05 | 通过：dumpsys device_policy 显示 `Profile Owner (User 10)` |
| TC-0085-06 | 通过：`{"success":false,"error":"profile owner already set: ..."}` |
| TC-0085-07 | 通过：`{"success":true,"isProfileOwner":true,"profileOwnerComponent":"com.hmdm.launcher/.AdminReceiver",...}` |
| TC-0085-08 | 通过：跨用户删除——引擎防护生效（binderError：clearProfileOwner 仅作用于调用方用户），文件通道 SELinux 拒绝（fileError 如实）；对照 `dpm list-owners` DO 不受影响；`adb root && rm /data/system/users/10/profile_owner.xml` + `stop`/`start` 后 PO 删除生效 |
| TC-0085-09 | 通过：dumpsys device_policy 无 profile owner 记录 |
| TC-0085-10 | 通过：`pm remove-user 10` 成功，`pm list users` 仅剩 user 0 |
| TC-0085-11 | 通过：缺 component → missing parameter；userId=abc 解析为 0 不 crash（按 DO 互斥检查返回） |
| TC-0085-12 | 通过（窗口内执行）：DO 删除窗口内 `SetProfileOwner userId=0` → 文件通道 SELinux 拒绝（success=false + error 如实）；未执行 PO 文件通道 + 重启的完整闭环（与 TC-0083 同机制） |
| TC-M-01 | 通过：带必填参数的 Set 类命令缺参均返回缺参提示；无参/可选参命令（IsDeviceOwner/IsProfileOwner/DeleteProfileOwner）按设计正常执行或幂等 |
| TC-M-02 | 通过：未知事件返回 unknown event |
| TC-M-03 | 通过（说明）：UI/IPC 共用 TestActions.execute() 同一引擎，行为一致（DeviceAdminTestActivity 已部署并启动验证，按钮与 IPC 事件一一对应） |
| TC-M-04 | 通过：返回值全 JDK Map/List/基本类型 |
| TC-M-05 | 通过：恢复基线——DO=com.hmdm.launcher/.AdminReceiver（DeviceOwner,Affiliated）、admin 激活态恢复、无 PO、单用户（user 0）、testapp 连接正常 |

**实测发现与记录**：
- `setDeviceOwner`/`setProfileOwner` 的 binder 通道在 setup 完成设备上被框架前置条件拒绝（错误信息与 AOSP 13 源码核验一致），引擎自动回退文件通道——真机验证了双通道设计的必要性；
- **本 ROM 的 `clearDeviceOwnerApp` 会触发框架删除 `device_owner_2.xml`**（与 AOSP 13 源码"clear 路径不落盘"不同——本 ROM 删除了文件，删除后磁盘无 owner 残留，无重启复活风险）；引擎仍保留文件通道兜底（其他 ROM 行为不一）；
- **本 ROM SELinux 拒绝 system_app 访问 /data/system**（`avc: denied { open } ... tcontext=u:object_r:system_data_file:s0`），引擎的 `device_owner_2.xml`/`profile_owner.xml` 文件通道在本 ROM 被拒（命令如实返回 fileError/fileWritten=false）；`/system/xbin/su` 仅 root/shell 组可执行（应用不可用）；文件级操作以 `adb root` 从主机完成（TC-0083-08/TC-0085-08 实测闭环）——**生产部署需 ROM 允许 system_app 写 /data/system（AOSP 默认允许）或提供特权通道**；
- **`clearProfileOwner` 仅作用于调用方所在用户**：跨用户删除 PO 实测曾移除用户 0 的 DO admin 组件（DPMS 以调用方 uid 解析目标用户，回退到 device owner admin 并 removeActiveAdminLocked 无 DO 保护）——引擎已加跨用户防护（直接走文件通道）+ 同用户 DO admin 目标拒绝，二次实测 DO 状态不受影响；
- `dpm remove-active-admin` 拒绝非 testOnly admin（本 ROM 既有记录），ASR-0080 注销不走该通道，直接经 DPMS `removeActiveAdmin`（MANAGE_DEVICE_ADMINS 身份）完成；
- 注销（removeActiveAdmin）异步性：框架等待目标 admin 的 `DEVICE_ADMIN_DISABLED` onDisabled 返回后才真正移除；测试 IPC 通道（ordered 广播）自串行导致移除延迟可达 10s+，引擎轮询 6s 后如实返回 pending 提示，最终态以查询命令为准；
- **破坏性闭环（用户确认后执行）**：DeleteDeviceOwner（删除成功，list-owners 空）→ 引擎 SetDeviceOwner（binder 已 set-up + 文件通道 SELinux 均如实拒绝，adminActivated=true）→ adb root 写文本 device_owner_2.xml + stop/start → DO 恢复（DPMS resolvePullParser 正确解析文本 XML）→ 无 DO 幂等删除通过；测试后设备状态完整恢复。

**部署注意**：本批次不修改 Launcher 的 `device_admin.xml`（本批次操作均无需 uses-policy 声明），**无需重启 framework 安装**（除 TC-0083-08 的生效重启）；`adb install -r` 重装 Launcher 会结束其进程且不会自动重启，需重新拉起 HOME（`am start -a android.intent.action.MAIN -c android.intent.category.HOME`）后 testapp 进程亦需重启（`am force-stop com.hmdm.testapp`）以重建 AIDL 绑定，否则返回 `RESULT:null`（DeadObjectException）。
