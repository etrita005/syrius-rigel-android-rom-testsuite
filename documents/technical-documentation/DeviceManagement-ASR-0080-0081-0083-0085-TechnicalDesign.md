# 设备管理（ASR-0080/0081/0083/0085）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 子功能项 | 需求语义 | 实现机制 |
|---|---|---|---|---|
| ASR-0080 | 设备管理 | 免交互激活/注销设备管理器组件 | 对任意已安装应用的 DeviceAdminReceiver 组件免交互激活（成为活动设备管理器）与注销，全程无用户确认界面 | 平台签名 uid=1000 反射 @hide `DevicePolicyManager.setActiveAdmin(ComponentName, boolean)` 激活（MANAGE_DEVICE_ADMINS 签名权限），注销经公开 `removeActiveAdmin(ComponentName)`（调用方持 MANAGE_DEVICE_ADMINS 时 DPMS 以调用方身份执行，可移除任意 admin） |
| ASR-0081 | 设备管理 | 强制激活设备管理器 | 无条件强制激活指定设备管理器组件（含已激活时重复激活的强制刷新语义） | 与 ASR-0080 共用引擎，`refreshing=true` 强制模式（已激活的 admin 重复激活不再抛 IllegalArgumentException）+ 写后读回核对与一次重试 |
| ASR-0083 | 设备管理 | 设置/删除 DeviceOwner | 设置指定应用为设备所有者；删除设备所有者（按需求要求写 `/data/system/device_owner_2.xml`） | 设置：双通道——先反射 @hide `setDeviceOwner`（仅 setup 完成前可用），失败回退直写 `device_owner_2.xml`（框架重启后生效）；删除：反射 @Deprecated 公开 `clearDeviceOwnerApp` 清内存 + 直写 `device_owner_2.xml` 移除 owner 元素保证重启不复现 |
| ASR-0085 | 设备管理 | 设置/删除 ProfileOwner | 设置/删除指定用户的 ProfileOwner（`setActiveProfileOwner` 语义 = 先激活 admin 再设 PO） | 双通道——先 `setActiveAdmin(component,false,userId)` 再反射 @hide `setProfileOwner(component,ownerName,userId)`（用户 setup 未完成或新用户直接成功）；setup 已完成的用户回退直写 `/data/system/users/<id>/profile_owner.xml`（框架重启后生效）；删除经公开 `clearProfileOwner`（调用方包名 == PO 包名时可用），回退文件删除 |

**归属**：四项均为「Launcher（MDM）+ 系统 API」——公开/隐藏 DPM 接口 + 平台签名 uid=1000（sharedUserId=android.uid.system）持有签名权限（MANAGE_DEVICE_ADMINS、MANAGE_PROFILE_AND_DEVICE_OWNERS、INTERACT_ACROSS_USERS_FULL）与 /data/system 写权限；**无需 ROM 改动**。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署（当前 DO=com.hmdm.launcher/.AdminReceiver，`dpm list-owners` 核验 `DeviceOwner,Affiliated`），`user_setup_complete=1`（设备已 setup 完成——这决定 setDeviceOwner/setProfileOwner 的 binder 通道在用户 0 必然被框架拒绝，详见第 2 节），bootloader 锁定不可改 system 分区，单用户（user 0）。

## 2. 技术选型与可行性核验

> 本批次全部接口的权限/前置条件均以 AOSP 13（android-13.0.0_r1）`DevicePolicyManager.java` / `DevicePolicyManagerService.java` / `OwnersData.java` 源码为准核验（与真机运行时行为在测试用例文档中记录）。

### 2.1 ASR-0080 激活/注销（setActiveAdmin / removeActiveAdmin）

**激活**：DPM 客户端 `setActiveAdmin(ComponentName, boolean)` 为 @hide（2 参 = 当前用户），3 参版（带 userId）为 @TestApi；服务端 `DPMS.setActiveAdmin` 校验：

```java
Preconditions.checkCallAuthorization(hasCallingOrSelfPermission(permission.MANAGE_DEVICE_ADMINS));
Preconditions.checkCallAuthorization(hasFullCrossUsersPermission(caller, userHandle));
```

- `MANAGE_DEVICE_ADMINS` 为 signature|privileged 权限，平台签名 uid=1000 声明后自动持有（本批次新增声明）；
- 同用户操作时 `hasFullCrossUsersPermission` 恒真（跨用户需 `INTERACT_ACROSS_USERS_FULL`，一并声明）；
- 目标组件必须是已安装应用声明的合法 DeviceAdminReceiver（带 `BIND_DEVICE_ADMIN` 保护 + `android.app.device_admin` meta-data），否则 `findAdmin` 抛异常——引擎返回明确错误；
- `refreshing=false` 时对已激活 admin 重复激活抛 `IllegalArgumentException("Admin is already added")`；`refreshing=true` 时覆盖刷新（ASR-0081 强制激活语义）。

**注销**：公开 `removeActiveAdmin(ComponentName)` 调用 `DPMS.removeActiveAdmin`：

```java
final CallerIdentity caller = hasCallingOrSelfPermission(permission.MANAGE_DEVICE_ADMINS)
        ? getCallerIdentity() : getCallerIdentity(adminReceiver);
```

调用方持 MANAGE_DEVICE_ADMINS 时以调用方身份执行，可移除**任意** admin（否则只能移除自己包的 admin）。**框架硬限制（重要）**：

```java
// Active device/profile owners must remain active admins.
if (isDeviceOwner(adminReceiver, userHandle) || isProfileOwner(adminReceiver, userHandle)) {
    Slogf.e(LOG_TAG, "Device/profile owner cannot be removed: component=" + adminReceiver);
    return;   // 静默拒绝
}
```

即当前 DO/PO 的 admin 组件**不可被注销**（框架直接拒绝，防破坏设计）。引擎在调用前先预检并如实上报 `ownerAdminCannotBeRemoved`，不依赖框架静默行为。

### 2.2 ASR-0083 设置 DeviceOwner

**binder 通道**：DPM 客户端 `setDeviceOwner(ComponentName, String, int)` 为 @TestApi @hide，要求 `MANAGE_PROFILE_AND_DEVICE_OWNERS`；服务端 `enforceCanSetDeviceOwnerLocked` → `checkDeviceOwnerProvisioningPreConditionLocked`：

```java
} else {
    // DO has to be user 0
    if (deviceOwnerUserId != UserHandle.USER_SYSTEM) { return STATUS_NOT_SYSTEM_USER; }
    // Only provision DO before setup wizard completes
    if (hasUserSetupCompleted(UserHandle.USER_SYSTEM)) { return STATUS_USER_SETUP_COMPLETED; }
    return STATUS_OK;
}
```

**非 adb 调用方（含系统 uid）在设备 setup 完成后一律拒绝**（`IllegalStateException: Cannot set the device owner if the device is already set-up`）；`isAdb`（shell uid 2000）通道在无账户时可设置。Launcher 的 `Runtime.exec` 子进程继承 uid 1000，不是 shell，故 `dpm set-device-owner` 亦不可用。**结论：本设备（setup 完成）设置 DO 只能走文件通道**（与需求原文"写 device_owner_2.xml"一致）。

**文件通道**（`/data/system/device_owner_2.xml`，AOSP 13 `OwnersData` 格式）：

```xml
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<root>
<device-owner package="com.hmdm.launcher" name="" component="com.hmdm.launcher/com.hmdm.launcher.AdminReceiver" />
<device-owner-context userId="0" />
</root>
```

- 文件属主 system（uid 1000），平台签名 Launcher 可直接读写（仓库既有 `SystemUtils.becomeDeviceOwnerByXmlFile` 同款通道）；**本 ROM SELinux 限制**：本 ROM 的 SELinux 策略拒绝 system_app 域访问 /data/system（`avc: denied { open } ... tcontext=u:object_r:system_data_file:s0`，dmesg 核验），且 `/system/xbin/su` 仅 root/shell 组可执行（应用无法提权）——**引擎的文件通道在本 ROM 被 SELinux 拒绝**（命令如实返回 fileError/fileWritten=false），文件级操作需以 `adb root` 从主机完成；AOSP 默认策略允许 system_app 读 /data/system 属主文件，生产部署在其他 ROM 上可正常走文件通道；
- 文件为 **Android Binary XML（ABX）格式**（本 ROM `Xml.resolveSerializer` 输出）：DPMS 启动装载用 `Xml.resolvePullParser`（自动识别文本/二进制），因此引擎写**文本 XML**可被正确解析（真机实测：root 写文本 device_owner_2.xml + 重启后 DO 生效，框架随后重序列化为 ABX）；应用侧磁盘态检查按原始字节 token 搜索（ABX 与文本均含 UTF-8 标签名）；
- DPMS 仅在启动时读取该文件（`OwnersData.load`），故文件写入后需**重启 framework**（`adb root; adb shell stop; adb shell start`）才生效；
- 关键配套：DPMS 要求 DO admin 必须是活动 admin（`setDeviceOwner` 有 `checkArgument(activeAdmin != null, "Not active admin")`），文件通道**先**经 `setActiveAdmin`（binder，可用）激活目标 admin 并持久化到 `/data/system/device_policies.xml`，再写 owner 文件，重启后 DPMS 装载即一致；
- 引擎返回 `restartRequired=true` 交由运维/测试执行重启（Launcher 不自动重启 framework——命令进程会在重启中消亡，结果无法回传，且属高影响操作，明确文档化）。

### 2.3 ASR-0083 删除 DeviceOwner（写 device_owner_2.xml）

**binder 通道**：公开 `clearDeviceOwnerApp(String)`（@Deprecated，测试用途）→ `DPMS.clearDeviceOwner(packageName)`：

```java
if (!mOwners.hasDeviceOwner() || !deviceOwnerComponent.getPackageName().equals(packageName)
        || (deviceOwnerUserId != caller.getUserId())) {
    throw new SecurityException("clearDeviceOwner can only be called by the device owner");
}
```

校验只比对**包名参数与当前 DO 包名**（uid/userId 取自 binder），因此平台签名 Launcher 传入当前 DO 包名即可通过（不需要调用方真是 DO）。成功后：

1. `clearDeviceOwnerLocked` 清内存 DO、停 owner 服务、`saveSettingsLocked`（device_policies.xml）；
2. `removeActiveAdminLocked(deviceOwnerComponent, userId)` 同步注销 DO 的 admin；
3. 广播 `ACTION_DEVICE_OWNER_CHANGED`。

**文件通道（必须，需求原文）**：AOSP 13 的 `DPMS.clearDeviceOwner` 路径**不调用** `mOwners.writeDeviceOwner()`——`/data/system/device_owner_2.xml` 磁盘内容不会立即更新；若仅靠 binder 清内存，**重启后 DPMS 从文件重新装载，DO 复活**。因此引擎在 binder 清除后**处理 `device_owner_2.xml`**：按原始字节检查 owner 元素，无其他内容时删除文件（与 `OwnersData.shouldWrite()` 行为一致）；若同文件持久化了系统更新策略则一并删除并告警（内存策略存活至重启，重设 DO 后可重新下发）。**本 ROM 实测**：`clearDeviceOwnerApp` 触发框架自身删除该文件（磁盘无残留，删除后无重启复活风险）——引擎的文件处理保留为其他 ROM 的兜底。binder 通道失败时回退纯文件通道并返回 `restartRequired=true`。

**SELinux 限制（本 ROM）**：引擎直写/直删 `/data/system` 文件被 SELinux 拒绝（system_app 域），命令如实返回 `fileWritten=false`/`fileError` 与 root 操作提示；测试中以 `adb root` 从主机完成文件级操作（实测闭环通过）。

### 2.4 ASR-0085 设置/删除 ProfileOwner

**binder 通道**：`setActiveProfileOwner(ComponentName, String)`（@SystemApi @Deprecated，DPM 客户端实现即"先 `setActiveAdmin(admin,false,myUserId)` 再 `setProfileOwner(admin,ownerName,myUserId)`"），或直接分两步调用 3 参版本（支持指定 userId）：

`DPMS.setProfileOwner` 前置 `enforceCanSetProfileOwnerLocked`：

```java
if (isAdb(caller)) { ...accounts 校验... return; }
Preconditions.checkCallAuthorization(hasCallingOrSelfPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS));
if ((mIsWatch || hasUserSetupCompleted(userHandle))) {
    Preconditions.checkState(isSystemUid(caller), "Cannot set the profile owner on a user which is already set-up");
    if (!mIsWatch) {
        if (!isSupervisionComponentLocked(owner)) {
            throw new IllegalStateException("Unable to set non-default profile owner post-setup " + owner);
        }
    }
}
```

- **setup 未完成的用户（新建用户、新管理配置文件）**：无后置检查，`MANAGE_PROFILE_AND_DEVICE_OWNERS` 持有者直接成功；
- **setup 已完成且用户已 setup**（本设备 user 0）：即使系统 uid 也要求目标组件是默认 supervision 组件（`config_defaultSupervisionProfileOwnerComponent`），自定义应用一律 `IllegalStateException`；shell（adb）通道无账户时可用，但 Launcher 子进程非 shell——**用户 0 只能走文件通道**；
- 前置还要求 `admin != null`（先激活 admin，即 setActiveProfileOwner 第一步）且包已安装到目标用户。

**文件通道**（`/data/system/users/<userId>/profile_owner.xml`，AOSP 13 起 PO 独立成文件，不再混入 device_policies.xml）：

```xml
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<root>
<profile-owner package="com.hmdm.launcher" component="com.hmdm.launcher/com.hmdm.launcher.AdminReceiver" />
</root>
```

设置：先 `setActiveAdmin(component,false,userId)`（binder 可用）→ 写 profile_owner.xml → `restartRequired=true`；删除：binder `clearProfileOwner(ComponentName)` **仅作用于调用方所在用户**（DPMS 以调用方 uid 解析目标用户，不从组件取用户；`getCallerIdentity(who)` 校验 admin 属于调用方 uid，`getProfileOwnerOrDeviceOwnerLocked` 在目标用户无 PO 时**回退到 device owner admin 并执行 removeActiveAdminLocked——无 DO 保护**，真机实测跨用户删除曾移除用户 0 的 DO admin 组件），引擎因此加防护：**跨用户删除直接走文件通道**（如实上报原因），同一用户下目标为当前 DO admin 时拒绝执行；同用户 PO 删除经 binder（调用方包名 == PO 包名且用户运行解锁）失败时回退：删除 profile_owner.xml 文件 → `restartRequired=true`。文件通道同样受本 ROM SELinux 限制（如实上报，adb root 补完）。

**DO 与 PO 互斥**：`enforceCanSetProfileOwnerLocked` 拒绝在已有 DO 的同用户上设 PO（`IllegalStateException`）；`OwnersData.load` 对同用户 DO+PO 并存只告警（"User has both DO and PO, which is not supported"）。文件通道实现上同样禁止在 DO 用户上写 PO 文件、在已有 PO 用户上覆盖写（返回错误而非破坏）。

### 2.5 系统配置声明

- 本批次**不修改** Launcher 的 `device_admin.xml` uses-policy（setActiveAdmin/removeActiveAdmin/clearDeviceOwnerApp/clearProfileOwner 均为系统级操作，无需 uses-policy 声明）；
- 新增 manifest 权限（签名权限，平台签名自动授予）：`MANAGE_DEVICE_ADMINS`（signature|privileged）、`MANAGE_PROFILE_AND_DEVICE_OWNERS`（signature|privileged）、`INTERACT_ACROSS_USERS_FULL`（signature）；
- 直写文件：`/data/system/device_owner_2.xml`、`/data/system/users/<id>/profile_owner.xml`（写入内容见 `documents/system_configurations.md`）。

## 3. 命令接口定义（SystemApiInterface.onEvent）

### 3.1 新增命令（本批次，9 条）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetDeviceAdminActive` | component（pkg/.Cls，必填）、active（boolean，必填） | Map：{success, active, component, channel, error?} | ASR-0080 |
| `IsDeviceAdminActive` | component（必填） | Map：{success, active, component, deviceOwnerAdmin, profileOwnerAdmin, error?} | ASR-0080 |
| `ForceSetDeviceAdminActive` | component（必填）、active（必填） | Map：{success, active, component, channel, force, error?} | ASR-0081 |
| `SetDeviceOwner` | packageName（必填）、component（可选，默认 `<packageName>/.AdminReceiver`）、ownerName（可选） | Map：{success, isDeviceOwner, channel（dpm/file）, restartRequired, error?} | ASR-0083 |
| `DeleteDeviceOwner` | packageName（可选，默认当前 DO 包名） | Map：{success, isDeviceOwner, channel, fileWritten, restartRequired, error?} | ASR-0083 |
| `IsDeviceOwner` | packageName（可选，默认当前 DO 包名） | Map：{success, isDeviceOwner, deviceOwnerPackage, deviceOwnerComponent, deviceOwnerUserId, fileHasOwner, error?} | ASR-0083 |
| `SetProfileOwner` | component（必填）、ownerName（可选）、userId（可选，默认 0） | Map：{success, isProfileOwner, userId, channel（system/file）, restartRequired, error?} | ASR-0085 |
| `DeleteProfileOwner` | component（可选，默认当前 PO 组件）、userId（可选，默认 0） | Map：{success, isProfileOwner, userId, channel, fileWritten, restartRequired, error?} | ASR-0085 |
| `IsProfileOwner` | packageName（可选，默认当前 PO 包名）、userId（可选，默认 0） | Map：{success, isProfileOwner, profileOwnerPackage, profileOwnerComponent, userId, error?} | ASR-0085 |

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> p = new HashMap<>();
p.put("component", "com.hmdm.testapp/com.hmdm.testapp.TestAdminReceiver");
p.put("active", true);
Map result = api.onEvent("SetDeviceAdminActive", p);
// {"RESULT":{"success":true,"active":true,"component":"com.hmdm.testapp/com.hmdm.testapp.TestAdminReceiver","channel":"system"}}
```

**广播通道**（TestBroadcast，与现有命令一致）：

```bash
adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast \
  -a ON_SQA_INTERFACE_TEST --es event SetDeviceAdminActive \
  --es param '{"component":"com.hmdm.testapp/com.hmdm.testapp.TestAdminReceiver","active":true}'
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/
│   └── DeviceAdminPolicyManager.java      # 新增：设备管理策略引擎（激活/注销 admin、DO/PO 设置删除，双通道 + 文件直写 + 写后读回核对）
├── service/command/device_admin/
│   ├── SetDeviceAdminActive.java          # 新增：ASR-0080 激活
│   ├── IsDeviceAdminActive.java           # 新增：ASR-0080 查询
│   ├── ForceSetDeviceAdminActive.java     # 新增：ASR-0081 强制激活
│   ├── SetDeviceOwner.java                # 新增：ASR-0083 设置 DO
│   ├── DeleteDeviceOwner.java             # 新增：ASR-0083 删除 DO（写 device_owner_2.xml）
│   ├── IsDeviceOwner.java                 # 新增：ASR-0083 查询
│   ├── SetProfileOwner.java               # 新增：ASR-0085 设置 PO
│   ├── DeleteProfileOwner.java            # 新增：ASR-0085 删除 PO
│   └── IsProfileOwner.java                # 新增：ASR-0085 查询
├── service/ApiBinder.java         # 注册 9 个新命令
└── AndroidManifest.xml            # 新增 3 个签名权限声明

testapp/
├── src/main/java/com/hmdm/testapp/
│   ├── TestAdminReceiver.java             # 新增：测试用 DeviceAdminReceiver（激活/注销测试目标）
│   ├── DeviceAdminTestActivity.java       # 新增：测试页（激活/注销/DO/PO 按钮组）
│   ├── TestActions.java                   # 新增 9 个事件（含参数校验）与事件目录
│   └── MainActivity.java                  # 增加 "Device admin" 入口
├── src/main/res/xml/device_admin.xml      # 新增：测试 admin 的 uses-policy 声明
└── src/main/res/layout/activity_device_admin_test.xml  # 新增测试页布局
```

## 5. 执行逻辑

```
SetDeviceAdminActive (ASR-0080) / ForceSetDeviceAdminActive (ASR-0081):
  1. 参数校验（缺 component/active → {error}）
  2. 解析 component；package 未安装或非 DeviceAdminReceiver → {success:false, error}
  3. active=true：反射 dpm.setActiveAdmin(component, refreshing)（ASR-0081 强制：refreshing=true，失败重试一次）
  4. active=false：预检该组件是当前 DO/PO admin → {success:false, error:ownerAdminCannotBeRemoved}；
     否则 dpm.removeActiveAdmin(component)
  5. dpm.isAdminActive 读回核对，一致 → success=true；不一致 → success=false + error（含期望/实际）
  6. 返回 {success, active(读回), component, channel:"system", force?}

SetDeviceOwner (ASR-0083):
  1. 参数校验（缺 packageName → {error}；component 缺省 packageName/.AdminReceiver）
  2. 包未安装 → {success:false, error}
  3. 已有 DO 且不是目标包 → {success:false, error:"device owner already set"}
  4. 通道一（binder）：反射 setDeviceOwner(component, ownerName, 0)（MANAGE_PROFILE_AND_DEVICE_OWNERS）
     - 成功 → isDeviceOwnerApp 读回核对 → {channel:"dpm", restartRequired:false}
     - 抛 IllegalStateException（setup 已完成等）→ 继续通道二，error 原因记录
  5. 通道二（文件）：setActiveAdmin(component, false, 0) 激活并持久化 admin →
     写 /data/system/device_owner_2.xml（AOSP 13 格式，先备份）→ 读回核对文件内容
     → {channel:"file", restartRequired:true, error:通道一失败原因}
  6. 校验 dpm.isDeviceOwnerApp 反映当前实际（文件通道未重启前仍为 false，如实上报）

DeleteDeviceOwner (ASR-0083):
  1. packageName 缺省取当前 DO 包名；无 DO → {success:true, isDeviceOwner:false, error:"no device owner"}（幂等）
  2. packageName 与当前 DO 包名不符 → {success:false, error}
  3. 备份 device_owner_2.xml 到 .bak
  4. 通道一（binder）：dpm.clearDeviceOwnerApp(packageName)（清内存 + 注销 admin + 广播）
  5. 通道二（文件，必须）：解析 device_owner_2.xml，移除 device-owner/device-owner-context 元素；
     剩余仅 <root/> 则删除文件（与框架行为一致）；写后读回核对 fileHasOwner=false
  6. isDeviceOwnerApp 读回核对 → success；返回 {success, isDeviceOwner, channel, fileWritten, restartRequired:false}

SetProfileOwner (ASR-0085):
  1. 参数校验（缺 component → {error}；userId 缺省 0；包未安装到目标用户 → {error}）
  2. 目标用户已有 PO → {success:false, error:"profile owner already set"}
  3. 目标用户已有 DO → {success:false, error:"user already has a device owner"}（DO/PO 互斥）
  4. 通道一（binder）：反射 setActiveAdmin(component, false, userId) →
     反射 setProfileOwner(component, ownerName, userId)
     - 成功 → isProfileOwnerApp(pkg, userId) 读回核对 → {channel:"system", restartRequired:false}
     - 抛 IllegalStateException（post-setup 非 supervision 组件等）→ 继续通道二
  5. 通道二（文件）：确保 admin 已激活（binder）→ 写 /data/system/users/<userId>/profile_owner.xml
     （AOSP 13 格式，先备份）→ 读回核对 → {channel:"file", restartRequired:true}

DeleteProfileOwner (ASR-0085):
  1. component 缺省取当前 PO 组件；无 PO → 幂等成功
  2. 通道一（binder）：dpm.clearProfileOwner(component)（要求调用方包名 == PO 包名）
     - 成功 → isProfileOwnerApp 读回核对 → {channel:"system", restartRequired:false}
     - SecurityException（非本包 PO）→ 继续通道二
  3. 通道二（文件）：删除 /data/system/users/<userId>/profile_owner.xml（先备份）→
     {channel:"file", fileWritten, restartRequired:true}

Is* 查询命令：dpm.isAdminActive / isDeviceOwnerApp / isProfileOwnerApp(+userId 反射) 读当前状态，
附报 owner 组件/用户与文件内容（fileHasOwner），异常 → success=false + error
```

**安全设计**：
- 注销前预检 DO/PO admin，防止破坏设备所有者的 admin 状态（框架亦会拒绝，双保险）；
- 文件直写前先备份（`.bak`），写入内容与 AOSP 13 `OwnersData` 序列化格式一致，读回核对；
- 参数均为组件名/包名/布尔，无 shell 拼接，无命令注入面；
- 引擎不自动重启 framework（重启会杀死命令进程导致结果无法回传，且属高影响操作）：文件通道返回 `restartRequired=true`，由运维/测试用 `adb root; adb shell stop; adb shell start` 完成生效并核验；
- 覆盖写保护：已有 DO/PO 时拒绝覆盖（binder 与文件通道一致）。

## 6. 权限与归属

| 接口 | 权限/身份 | 备注 |
|---|---|---|
| `setActiveAdmin(ComponentName, boolean[, int])` | @hide；MANAGE_DEVICE_ADMINS（签名）+ INTERACT_ACROSS_USERS_FULL（跨用户时） | 反射调用 |
| `removeActiveAdmin(ComponentName)` | 公开；调用方持 MANAGE_DEVICE_ADMINS 可移除任意 admin（DO/PO admin 除外） | 直接调用 |
| `setDeviceOwner(ComponentName, String, int)` | @TestApi @hide；MANAGE_PROFILE_AND_DEVICE_OWNERS；setup 完成后非 adb 拒绝 | 反射调用 |
| `clearDeviceOwnerApp(String)` | 公开（@Deprecated）；包名参数与当前 DO 包名一致即可 | 直接调用 |
| `setProfileOwner(ComponentName, String, int)` | @hide；MANAGE_PROFILE_AND_DEVICE_OWNERS；setup 未完成用户可用 | 反射调用 |
| `clearProfileOwner(ComponentName)` | 公开（@Deprecated）；调用方包名 == PO 包名 | 直接调用 |
| `isDeviceOwnerApp` / `isProfileOwnerApp` / `isAdminActive` | 公开 | 直接调用（跨用户查询反射 @hide 2 参版） |
| 直写 `/data/system/device_owner_2.xml`、`/data/system/users/<id>/profile_owner.xml` | uid=1000（system）文件属主 | 平台签名部署 |

- 新增 manifest 权限声明 3 项（见 2.5），不修改 AIDL / lib 模块；testapp 无需新增权限（TestAdminReceiver 仅需 BIND_DEVICE_ADMIN 保护的 receiver 声明）。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺 component / active / packageName 参数 | 命令返回 {error:"missing parameter: ..."}，不 crash；testapp 侧同样拦截 |
| component 格式非法 | {success:false, error:"invalid component: ..."} |
| 包未安装 / 不是 DeviceAdminReceiver | {success:false, error:"package not installed or not a device admin"} |
| 注销当前 DO/PO 的 admin（ASR-0080） | 预检拒绝：{success:false, error:"owner admin cannot be removed"}（框架同拒，双保险） |
| 重复激活（ASR-0080 refreshing=false） | 框架抛 IllegalArgumentException → {success:false, error}；ASR-0081 用 refreshing=true 覆盖刷新 |
| 已设 DO 再设置 DO | binder 抛 STATUS_HAS_DEVICE_OWNER → {success:false, error:"device owner already set"}，不落文件通道 |
| setup 已完成设备设置 DO/PO（用户 0） | binder 通道抛 IllegalStateException（"already set-up"/"non-default profile owner post-setup"）→ 自动回退文件通道，restartRequired=true，原因如实上报 |
| 目标用户已有 PO | {success:false, error:"profile owner already set"}（binder 与文件通道均不覆盖） |
| DO 用户上设 PO / PO 用户上设 DO | {success:false, error}（DO/PO 互斥，框架同拒） |
| clearDeviceOwnerApp 包名不符 | {success:false, error:"packageName does not match the current device owner"} |
| **跨用户删除 PO** | 引擎跳过 binder（`clearProfileOwner` 仅作用于调用方用户，实测跨用户调用会误移除调用方用户的 DO admin）→ 直接走文件通道并如实上报 binderError；同用户且目标为当前 DO admin → 拒绝执行 |
| **SELinux 拒绝 /data/system 文件访问（本 ROM）** | 文件通道命令如实返回 fileWritten=false/fileError（含 root 操作提示），不 crash、不伪装成功；测试/运维以 `adb root` 从主机完成文件级操作（实测闭环通过）；生产部署需 ROM 允许 system_app 写 /data/system（AOSP 默认）或提供特权通道 |
| 文件读写失败或读回不一致 | {success:false, error}（含期望/实际），可重试；写入前备份 .bak（ABX 原始字节） |
| 删除 DO 后 Launcher 失去 DO 身份 | 预期行为（DO 被删除后 DPM 特权接口不可用，直到重新设置 DO）；测试流程按"删除 → 验证 → 重设 → 重启 → 验证"闭环执行 |
| 文件通道未重启前查询 | isDeviceOwnerApp 反映内存态（false），附报 fileHasOwner 反映文件态，如实区分 |
| 框架重启影响 | Launcher/testapp 进程随 framework 重启，重启完成后需重新拉起 HOME 与 testapp 绑定（同既有部署注意） |

## 8. 真机验证记录（2026-08-06，Android 13 / API 33 userdebug，平台签名 + device owner）

（命令级用例结果见测试用例设计文档，此处记录机制核验关键结论）

| 核验项 | 结果 |
|---|---|
| 设备基线 | `dpm list-owners` = `User 0: admin=com.hmdm.launcher/.AdminReceiver,DeviceOwner,Affiliated`；`user_setup_complete=1`；单用户 |
| ASR-0080 激活 | `SetDeviceAdminActive component=com.hmdm.testapp/...TestAdminReceiver active=true` → isAdminActive=true（channel=system，读回核对；testapp 本地 dpm.isAdminActive 端到端 true），无确认弹窗 |
| ASR-0080 注销 | 同组件 active=false → 最终 active=false（框架先送 DEVICE_ADMIN_DISABLED 到 onDisabled 再移除，异步性经轮询如实处理）；注销 DO admin 被预检拒绝（ownerAdminCannotBeRemoved） |
| ASR-0081 强制激活 | 已激活状态重复 ForceSetDeviceAdminActive active=true（refreshing=true）→ 幂等成功 |
| ASR-0083 binder 通道失败核验 | 反射 setDeviceOwner 抛 IllegalStateException（"Cannot set the device owner if the device is already set-up"，AOSP 13 前置条件），与源码核验一致 |
| ASR-0083 删除 DO | clearDeviceOwnerApp 清内存 + 注销 admin；**本 ROM 框架自身删除 device_owner_2.xml**（磁盘无残留，`dpm list-owners` = no owners）；引擎文件通道兜底保留 |
| ASR-0083 重设 DO | 引擎双通道均如实拒绝（binder 已 set-up + 文件通道 **SELinux 拒绝**）；`adb root` 写文本 device_owner_2.xml + `stop`/`start` 后 `dpm list-owners` 恢复 `DeviceOwner,Affiliated`（DPMS resolvePullParser 正确解析文本 XML；框架随后重序列化为 ABX） |
| ASR-0085 用户 0 binder 通道失败核验 | setProfileOwner 抛 IllegalStateException（"Unable to set non-default profile owner post-setup"），与源码核验一致（有 DO 时引擎前置互斥检查先行返回同义错误） |
| ASR-0085 新用户通道 | 创建测试用户 10 → SetProfileOwner component=com.hmdm.launcher/.AdminReceiver userId=10 → isProfileOwner=true（channel=system）→ 跨用户 DeleteProfileOwner 走文件通道（clearProfileOwner 仅作用于调用方用户，防护生效）→ adb root 删 profile_owner.xml + 重启 → PO 删除生效 → pm remove-user 10 清理 |
| **SELinux 限制核验（重要）** | 本 ROM SELinux 拒绝 system_app 访问 /data/system（dmesg：`avc: denied { open } ... scontext=u:r:system_app:s0 tcontext=u:object_r:system_data_file:s0`）；`/system/xbin/su` 仅 root/shell 组可执行——引擎文件通道在本 ROM 被拒（如实上报 fileError/fileWritten），文件级操作需 adb root；/data/system/device_owner_2.xml 为 ABX 二进制格式（应用侧按原始字节 token 校验） |
| **clearProfileOwner 跨用户危害核验（重要）** | DPMS.clearProfileOwner 以调用方 uid 解析目标用户：跨用户删除 PO 实测误移除用户 0 的 DO admin 组件（getCallerIdentity 命中调用方用户 admin map → getProfileOwnerOrDeviceOwnerLocked 回退 DO admin → removeActiveAdminLocked 无 DO 保护）；引擎已加跨用户防护 + 同用户 DO admin 目标拒绝，二次实测安全 |
| 破坏性闭环（用户确认） | DeleteDeviceOwner → 引擎 SetDeviceOwner 如实拒绝 → adb root 写文件 + stop/start → DO 恢复 → 无 DO 幂等删除通过 |
| 测试后设备恢复 | DO=com.hmdm.launcher/.AdminReceiver（DeviceOwner,Affiliated）、admin 激活、无 PO、单用户（user 0）、testapp 连接正常 |
