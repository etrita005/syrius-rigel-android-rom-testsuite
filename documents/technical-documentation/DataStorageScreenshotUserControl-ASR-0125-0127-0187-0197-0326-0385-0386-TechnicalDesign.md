# 数据/存储/截屏/用户管控（ASR-0125/0127/0187/0197/0326/0385/0386）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 实现机制 |
|---|---|---|---|
| ASR-0125 | 应用数据 | 应用数据备份/恢复 | 反射 `android.app.backup.IBackupManager.adbBackup/adbRestore`（adb backup/restore 同通道，Binder 反射；平台签名 uid=1000 + BACKUP 签名权限） |
| ASR-0127 | 应用数据 | 清除指定应用的缓存 | 反射 `PackageManager.deleteApplicationCacheFiles`（@hide 回调 API，CLEAR_APP_USER_DATA 平台签名自动授予） |
| ASR-0197 | USB 功能 | 卸载 USB 设备 | 反射 `StorageManager.getVolumes/unmount`（@hide/@SystemApi，MOUNT_UNMOUNT_FILESYSTEMS manifest 既有声明） |
| ASR-0326 | SD卡 | 格式化外部SD卡 | 反射 `StorageManager.format`（@hide，MOUNT_FORMAT_FILESYSTEMS 本批次新增声明） |
| ASR-0187 | 截屏 | 执行截屏 | 反射 `SurfaceControl.captureDisplay(DisplayCaptureArgs)`（本 ROM MTK fork 通道，`ScreenshotHardwareBuffer.asBitmap()`）+ AOSP `screenshot(DisplayCaptureArgs)`/`screenshot(int)` 回退（CAPTURE_VIDEO_OUTPUT 本批次新增声明） |
| ASR-0385 | 多用户管理 | 创建用户 | 反射 `UserManager.createUser(String,int)`（@hide，MANAGE_USERS manifest 既有声明）；本 ROM 返回 UserInfo（AOSP 返回 UserHandle），两者兼容 |
| ASR-0386 | 多用户管理 | 删除用户 | 反射 `UserManager.removeUser(int)`（公开接口但 SDK stub 裁剪，反射调用）；拒绝删除主用户与当前用户 |

**归属**：六项均为「Launcher（MDM）+ 系统 API」——公开 SDK 无对应能力，需平台签名/特权应用调用 @SystemApi、@hide、Binder 系统服务或签名权限接口。**无需 ROM 改动**（备份/恢复、存储、截屏、用户管理均为标准 Android 框架能力，仅属性和接口差异需运行时反射适配）。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署；bootloader 锁定。测试前设备基线（2026-08-06 实测）：仅内部存储（无外置 SD / USB OTG 卷）、仅用户 0（Owner）、备份服务启用（backupServiceEnabled=true）、`backup_enabled=0`、屏幕截屏策略未禁用（`Screen capture disallowed users: []`）、无 `no_add_user` 用户限制。

## 2. 技术选型与可行性核验

### 2.1 ASR-0125 应用数据备份/恢复（IBackupManager adbBackup/adbRestore）

**语义**：把指定应用的数据（不含 APK/共享存储，全类型 data 备份）导出为 .ab 备份文件（与 `adb backup` 相同格式），并可从此文件恢复（与 `adb restore` 相同通道）。

**接口核验（重要，本 ROM 与 AOSP 13 差异）**：Sheet1 规划路径为 AOSP 13 `IBackupManager.fullBackup/fullRestore`（`android.app.IBackupManager`）。真机实测 `Class.forName("android.app.IBackupManager$Stub")` **不存在**——本 ROM（MTK fork）将接口迁移至 `android.app.backup.IBackupManager` 且方法集为 **AOSP 12 风格**（framework.jar dex 反编译核验）：

| 本 ROM 方法 | 签名 | 说明 |
|---|---|---|
| `adbBackup` | `(int userId, ParcelFileDescriptor fd, Z×8, String[] packages)` | 8 个布尔：apks/obbs/shared/system/extras/allApps/obsoleteApps/keyValue；本实现全 false = 指定包纯数据备份 |
| `adbRestore` | `(int userId, ParcelFileDescriptor fd)` | 从 .ab 文件恢复 |

**流程特性（AOSP 12 风格，真机核验）**：
1. 调用 `adbBackup` 后框架启动 `com.android.backupconfirm/.BackupRestoreConfirmation` 确认界面（intent extra `conftoken`），**Binder 调用阻塞等待用户确认**（"BACK UP MY DATA" / "DO NOT BACK UP"），60 秒确认超时则取消；
2. 确认后执行备份任务，任务完成（AdbParams latch）后 Binder 调用返回——调用方线程全程阻塞（真机实测含确认约 30s 完成 4.6KB 应用备份）；
3. 恢复流程同构（"RESTORE MY DATA"），恢复完成后框架会强制结束目标应用进程（真机实测 testapp 进程被杀，结果以 Launcher 侧日志为准）。

**SELinux 约束（真机核验，关键）**：本 ROM 策略拒绝 system_server 读写 `/storage/emulated/0`（fuse 对象）——`avc: denied { read write } ... scontext=u:r:system_server:s0 tcontext=u:object_r:fuse:s0 tclass=file`，直接向 /sdcard 路径打开 fd 传参会导致 Binder 事务失败（`DeadObjectException: Transaction failed on small parcel`）。核验 `plat_sepolicy.cil` 后确认 system_server 可完整读写 `system_app_data_file`（Launcher 自身数据目录类型）。因此引擎设计为：

1. **备份**：fd 打开在 Launcher 自身数据目录（`/data/user/0/com.hmdm.launcher/files/mdm_backup/`，system_app_data_file）→ adbBackup 成功后由 **Launcher 进程自身**（可经 FUSE 访问 /sdcard）复制到请求的公开路径（`publicFile` + `publicCopy` 如实上报）；
2. **恢复**：若传入文件不在 Launcher 数据目录（如 /sdcard），先由 Launcher 复制到 `mdm_backup/restore_<ts>.ab` 暂存（`stagedFile` 如实上报），再以该路径打开 fd 调用 adbRestore。

**验证**：备份文件校验 `ANDROID BACKUP` 魔数头 + 大小稳定轮询；恢复以 testapp 数据探针（probe.txt 内容 v1→v2→恢复→v1）端到端闭环验证。

**输入防护（代码审查后强化，2026-08-06）**：
- `packageName` 先经严格包名校验（`[a-zA-Z0-9._]+`）再参与路径拼接，杜绝 `../` 路径穿越（任意文件 create/truncate 原语）；
- 备份公开副本（`publicFile`）目标路径仅允许 `/sdcard`（规范化为 /storage/emulated/0）与 Launcher 数据目录（canonical 校验），且目标已存在但非 .ab/无备份魔数头时拒绝覆盖；
- 恢复源路径同样受限（仅 /sdcard 与 Launcher 数据目录），且**暂存前校验 "ANDROID BACKUP" 魔数头**，杜绝特权任意文件读取与非备份文件误恢复；
- 恢复成功判定：`adbRestore` Binder 调用在确认拒绝/60s 超时取消时同样返回——引擎以调用耗时区分（<55s=已确认完成，success=true；≥55s=确认窗口超时未恢复，success=false + 如实报错），避免"未恢复却报成功"（注：>55s 的大应用确认后恢复属已知假阴性边界，以探针端到端验证为准）。

### 2.2 ASR-0127 清除应用缓存（deleteApplicationCacheFiles）

**语义**：清除指定应用的缓存目录（/data/user/0/\<pkg\>/cache 与 code_cache），不触碰应用数据。

**实现**：反射 `PackageManager.deleteApplicationCacheFiles(String, IPackageDataObserver)`（@hide，回调式；`IPackageDataObserver` 为仓库既有 stub 类 android.content.pm.IPackageDataObserver），等待回调（30s 超时）。

**本 ROM 实测发现（重要）**：调用后缓存**确实被清除**（testapp 缓存探针文件消失、Launcher 侧直读缓存目录为空），但 **onRemoveCompleted 回调在本 ROM 上不触发**（30 秒内无回调，PMS fork 行为）。引擎因此增加**直接读回核对**：`/data/user/0/<pkg>/cache` 目录可读且为空（uid=1000 可读其他应用数据目录，真机核验通过）即判定成功（`verified=true`），回调结果如实上报（`cleared`），二者其一成立即 success=true，避免回调缺失造成假阴性。包不存在时返回 `package not installed`。

### 2.3 ASR-0197 卸载 USB 设备 / ASR-0326 格式化外部 SD 卡（StorageManager）

**语义**：ASR-0197 卸载可移除 USB 存储卷（挂载状态 → 卸载）；ASR-0326 格式化外部 SD 卡（清空文件系统重新初始化）。两者共用卷枚举/选择逻辑。

**实现**：
- 卷枚举：反射 `StorageManager.getVolumes()`（@SystemApi，公开 SDK 不可见）→ VolumeInfo 反射读取 id/type/state/isRemovable/getDisk；
- 卸载：反射 `StorageManager.unmount(String volId)`（@hide，MOUNT_UNMOUNT_FILESYSTEMS 签名权限，manifest 既有声明）；**卸载成功判定=轮询到达终态（unmounted/ejecting/removed/bad_removal）或卷消失，瞬时状态变化（checking/mounted 回滚）不视为成功**（代码审查后修正）；
- 格式化：反射 `StorageManager.format(String volId)`（@hide，MOUNT_FORMAT_FILESYSTEMS 签名权限，**本批次 manifest 新增声明**；无单参方法时回退 `format(String, String)`）；**成功判定=进入 formatting 后到达终态（重新挂载/卸载/卷消失），formatting 起始态与失败态（unmountable/bad_disk）不视为成功**（代码审查后修正，附 sawFormatting 状态）；
- **安全选择器（防误操作，关键）**：仅 `type==TYPE_PUBLIC` 且可移除（volume.isRemovable 或 disk.isRemovable）的卷可被选中；**显式 volumeId 也必须通过该校验**——本机实测 emulated 卷 type=2（AOSP 为 TYPE_EMULATED=3，本 ROM 重排常量，文档化），即使显式传 `emulated;0` 也不会被卸载/格式化；
- 结果核对：卸载后轮询卷状态离开 mounted（unmounted/ejecting/removed/消失，20s 超时）；格式化后轮询进入 formatting/状态变化/卷消失（30s 超时），格式化本身由 MountService 异步执行（如实上报 note）。

**本机限制**：本机无外置 SD / USB OTG 卷（`dumpsys mount` 仅 emulated;0），命令对无卷场景如实返回 `{success:false, error:"no removable USB/SD volume found ...", volumes:[...]}`（含卷清单辅助排查），真实卸载/格式化行为需插入可移除存储的真机验收（见需求文档"硬件受限测试说明"）。

### 2.4 ASR-0187 执行截屏（SurfaceControl.captureDisplay）

**语义**：捕获当前屏幕内容为 PNG 文件。**本机核验**：`Screen capture disallowed users: []`（ASR-0185 未启用），可正常捕获。

**接口核验（本 ROM 与 AOSP 13 差异）**：AOSP 13 通道 `SurfaceControl.screenshot(DisplayCaptureArgs)`（Builder 构造参数为 Display）与弃用的 `screenshot(int)` **在本 ROM 均不存在**（NoSuchMethodException 实测）；framework.jar dex 反编译核验本 ROM（MTK fork）通道为：

```java
IBinder token = SurfaceControl.getInternalDisplayToken();          // 框架自身取 token 的方式（UiAutomationConnection 同款）
DisplayCaptureArgs args = new DisplayCaptureArgs.Builder(token)    // Builder 构造参数是 IBinder！
        .setSize(w, h).build();
ScreenshotHardwareBuffer buffer = SurfaceControl.captureDisplay(args); // 返回 ScreenshotHardwareBuffer
Bitmap bmp = buffer.asBitmap();                                     // 直接出 Bitmap（免 HardwareBuffer 转换）
```

引擎实现三通道依次尝试：① MTK `getInternalDisplayToken + captureDisplay + asBitmap`（首选，本 ROM 生效）；② AOSP `Builder(Display) + screenshot + Bitmap.wrapHardwareBuffer`；③ `screenshot(int)`。任一失败自动降级并如实上报。

**权限**：`CAPTURE_VIDEO_OUTPUT`（signature\|privileged，**本批次 manifest 新增声明**，平台签名自动授予）；SurfaceFlinger 对无 MediaProjection 授权的调用方校验该权限。**FLAG_SECURE 窗口**（ASR-0185 截屏禁用策略置位时）在捕获结果中呈现为黑块——引擎如实保存并可在文件侧验证（文档化，与 ASR-0185/0186 交互）。

**输出防护（代码审查后强化）**：输出路径仅允许 `/sdcard`（规范化为 /storage/emulated/0）与 Launcher 数据目录（canonical 校验），拒绝其他位置（防系统文件被覆盖）；目标已存在且非 PNG（魔数校验）时拒绝覆盖；MTK 通道的 `ScreenshotHardwareBuffer` 在 `asBitmap()` 后立即 `close()`（反射，防 gralloc 显存滞留）。

**尺寸**：默认取 `Display.getRealSize()`（本机实测返回 1600×720，面板物理尺寸为 720×1552 竖屏，真实渲染尺寸以 getRealSize 为准）；可传 width/height 指定。

### 2.5 ASR-0385 创建用户 / ASR-0386 删除用户（UserManager）

**语义**：创建（指定名称与 flags）/ 删除（按 userId）系统用户。MANAGE_USERS 签名权限（manifest 既有声明，平台签名自动授予）。

**接口核验（本 ROM 与 AOSP 13 差异）**：
- `UserManager.createUser(String, int)`（@hide）**返回 UserInfo**（真机实测 CastException：AOSP 返回 UserHandle）——引擎兼容两种返回（UserHandle 取 getIdentifier，UserInfo 取 id 字段）；
- `UserManager.removeUser(int)` 反射调用（公开 API 28+，但本 SDK 的 UserManager stub 被裁剪不可直接编译）；
- `UserManager.getUsers()` 反射调用；**本 ROM 的 UserInfo 类无 getId/getName/getFlags getter**（dex 核验仅剩 getUserHandle/isAdmin/isCloneProfile），改为**公共字段** id/name/flags 优先 + getter 回退 + getUserHandle 兜底读取；
- `removeUser(999)`（不存在用户）返回 false——引擎如实上报 success=false（"removed" 键为服务返回值，附用户列表）；
- 用户删除为异步（真机实测 dumpsys user 出现 `<removing> <partial>` 状态数秒后消失），引擎轮询 getUsers()（默认排除移除中用户）确认消失，成功标准 = 服务返回 true 且列表中已无该用户。

**安全防护**：拒绝删除主用户（userId ≤ 0）与当前用户（Process.myUserHandle）；**userId 参数兼容数值与数字字符串**（DeleteUser 命令 String 解析回退，代码审查后修正——字符串 "10" 不再被 Bundle.getInt 误读为 0 而拒绝）；无 MANAGE_USERS 的普通应用调用 getUsers 被框架拒绝（testapp 本地探针实测 `SecurityException: You either need MANAGE_USERS or CREATE_USERS permission to: query users`——印证本实现必须平台签名）。

### 2.6 系统配置声明

- ASR-0125：无需 device_admin.xml uses-policy；写入文件为 Launcher 自身数据目录 + 用户请求的公开路径（.ab 文件）；
- ASR-0127：无需 uses-policy；清除目标为 PMS 管理的应用缓存目录；
- ASR-0197/0326：无需 uses-policy；卷状态由 MountService 管理（Launcher 不写配置）；
- ASR-0187：无需 uses-policy；输出 PNG 至请求路径；
- ASR-0385/0386：无需 uses-policy；用户增删由 UserManagerService 持久化（/data/system/users/）。
- 本批次不修改 device_admin.xml，**无需重启 framework**。

## 3. 命令接口定义（ApiBinder.method2Commands）

### 3.1 新增命令（本批次，10 条）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `BackupAppData` | packageName（String，必填）、file（String，可选公开路径，缺省仅存 Launcher 数据目录） | Map：{success, file, sizeBytes, elapsedMs, packageName, publicFile?, publicCopy?} 或 {error} | ASR-0125 |
| `RestoreAppData` | file（String，必填 .ab 路径） | Map：{success, file, stagedFile?, elapsedMs} 或 {error} | ASR-0125 |
| `ClearAppCache` | packageName（String，必填） | Map：{success, packageName, cleared, verified} 或 {error} | ASR-0127 |
| `GetStorageVolumes` | 无 | Map：{success, count, volumes:[{id,type,state,stateLabel,removable,description}]} | ASR-0197/0326 辅助 |
| `UnmountUsbStorage` | volumeId（String，可选；缺省第一个可移除公共卷） | Map：{success, volumeId, before, after} 或 {error, volumes} | ASR-0197 |
| `FormatExternalSd` | volumeId（String，可选；缺省第一个可移除公共卷） | Map：{success, volumeId, before, after, note} 或 {error, volumes} | ASR-0326 |
| `TakeScreenshot` | file（String，可选；缺省 /sdcard/Pictures/MDM/screenshot_\<ts\>.png）、width/height（int，可选） | Map：{success, file, width, height, sizeBytes} 或 {error} | ASR-0187 |
| `CreateUser` | name（String，必填）、flags（int，可选，缺省 0） | Map：{success, userId, name, flags, users:[...]} 或 {error} | ASR-0385 |
| `DeleteUser` | userId（int，必填，>0） | Map：{success, userId, removed, users:[...]} 或 {error} | ASR-0386 |
| `GetUserList` | 无 | Map：{success, users:[{id,name,flags,admin,guest,primary}]} | ASR-0385/0386 辅助 |

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> p = new HashMap<>();
p.put("packageName", "com.hmdm.testapp");
p.put("file", "/sdcard/MDM/backup/testapp_v1.ab");
Map result = api.onEvent("BackupAppData", p);
// {"RESULT":{"success":true,"file":"/data/user/0/com.hmdm.launcher/files/mdm_backup/com.hmdm.testapp_20260806_171301.ab",
//   "sizeBytes":4632,"elapsedMs":30923,"packageName":"com.hmdm.testapp",
//   "publicFile":"/sdcard/MDM/backup/testapp_v1.ab","publicCopy":{"success":true,"sizeBytes":4632}}}

Map r2 = api.onEvent("CreateUser", p);  // name=mdm_test_user
// {"RESULT":{"success":true,"userId":11,"name":"mdm_test_user","flags":0,"users":[...]}}
```

**广播通道**（TestBroadcast，与现有命令一致）：

```bash
adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast \
  -a ON_SQA_INTERFACE_TEST --es event TakeScreenshot \
  --es param '{"file":"/sdcard/Pictures/MDM/shot.png"}'
```

**备份/恢复命令的阻塞说明**：BackupAppData/RestoreAppData 在等待设备端备份确认界面期间阻塞（含确认最长约 60s + 任务执行时间），调用方需耐心等待；自动化测试在确认界面弹出后由测试脚本点按确认按钮完成闭环（详见测试用例设计文档）。

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/
│   ├── DataStorageUserPolicyManager.java  # 新增：数据/存储/截屏/用户引擎（备份恢复、清缓存、卷枚举/卸载/格式化、截屏、用户增删，写后读回核对）
│   └── AppDataCleaner.java                # 修改：新增 clearAppCache（deleteApplicationCacheFiles 回调封装）
├── service/command/
│   ├── backup_app_data/BackupAppData.java    # 新增：ASR-0125 备份
│   ├── backup_app_data/RestoreAppData.java   # 新增：ASR-0125 恢复
│   ├── clear_app_cache/ClearAppCache.java    # 新增：ASR-0127
│   ├── storage/GetStorageVolumes.java        # 新增：卷清单辅助
│   ├── storage/UnmountUsbStorage.java        # 新增：ASR-0197
│   ├── storage/FormatExternalSd.java         # 新增：ASR-0326
│   ├── screenshot/TakeScreenshot.java        # 新增：ASR-0187
│   └── user_manage/CreateUser.java           # 新增：ASR-0385
│       user_manage/DeleteUser.java           # 新增：ASR-0386
│       user_manage/GetUserList.java          # 新增：用户清单辅助
└── service/ApiBinder.java             # 注册 10 个新命令
app/src/main/AndroidManifest.xml        # 新增声明 BACKUP / MOUNT_FORMAT_FILESYSTEMS / CAPTURE_VIDEO_OUTPUT

testapp/
├── src/main/java/com/hmdm/testapp/
│   ├── DataStorageUserTestActivity.java # 新增：测试页（探针/备份恢复/清缓存/截屏/存储卷/用户）
│   ├── StorageUserVerifier.java         # 新增：本地探针（数据/缓存文件读写、本地用户清单）
│   ├── TestActions.java                 # 新增 15 个事件（含参数校验）与事件目录
│   └── MainActivity.java                # 增加 "Data / storage / screenshot / user" 入口
└── src/main/res/layout/activity_data_storage_user_test.xml # 新增测试页布局
```

## 5. 执行逻辑

```
BackupAppData:
  1. 参数校验（缺 packageName → {error}）
  2. 目标文件 = Launcher 数据目录 files/mdm_backup/<pkg>_<ts>.ab（system_app_data_file，SELinux 允许 system_server 写入）
  3. 反射 adbBackup(0, fd, 8×false, [pkg]) —— 设备弹出备份确认界面，Binder 调用阻塞至确认/超时
  4. 返回后轮询文件大小稳定（≤120s），校验 "ANDROID BACKUP" 魔数头
  5. 成功且指定了公开路径 → Launcher 复制到 /sdcard（publicFile/publicCopy 如实上报）
  6. success = 魔数头有效且 size>0

RestoreAppData:
  1. 参数校验（缺 file → {error}）；文件不存在 → {error}
  2. 路径不在 Launcher 数据目录 → 先复制暂存（stagedFile）
  3. 反射 adbRestore(0, fd) —— 设备弹出恢复确认界面，Binder 调用阻塞至完成/超时
  4. 完成后延时 3s（数据落盘），success=true（恢复内容以探针端到端验证）

ClearAppCache:
  1. 参数校验；包不存在 → {error:"package not installed"}
  2. 反射 deleteApplicationCacheFiles(pkg, observer)，等待回调 30s
  3. 读回核对 /data/user/0/<pkg>/cache 为空（verified）
  4. success = cleared || verified

UnmountUsbStorage / FormatExternalSd:
  1. 枚举 getVolumes()；按 volumeId 或第一个可移除公共卷选择（显式 id 亦须通过公共+可移除校验）
  2. 无卷 → {success:false, error:"no removable ... volume found", volumes:[...]}
  3. 反射 unmount(volId) / format(volId)；轮询状态变化（卸载 20s / 格式化 30s 超时）
  4. 返回 before/after 状态（stateLabel），变化或卷消失才 success=true

TakeScreenshot:
  1. 取默认 Display + getRealSize（或指定宽高）
  2. 三通道捕获：MTK captureDisplay（首选）→ AOSP screenshot(DisplayCaptureArgs) → screenshot(int)
  3. 转软件位图压缩 PNG（自动建目录）；success = 文件存在且 size>0

CreateUser:
  1. 参数校验（缺 name → {error}）
  2. 反射 createUser(name, flags)；返回 UserInfo/UserHandle 兼容取 id
  3. 返回 userId + 全量用户清单（写后读回核对）

DeleteUser:
  1. 参数校验（缺 userId → {error}）；userId≤0 → {error:"...primary user cannot be removed"}；当前用户 → 拒绝
  2. 反射 removeUser(userId)；轮询 getUsers() 确认消失（20s）
  3. success = 服务返回 true 且用户已不在列表
```

**安全设计**：① 卸载/格式化卷选择器双重校验（公共类型 + 可移除），emulated/private 卷永不可被误操作；② 用户删除拒绝主用户与当前用户；③ 备份/恢复/截屏文件路径均可由调用方指定，引擎只做 mkdirs 与读写，无 shell 拼接（无命令注入面）；④ 所有反射调用包 try/catch，任何失败如实返回 success=false + error（含真实异常类型），不伪装成功。

## 6. 权限与归属

| 需求 | 权限/身份 | manifest 变更 |
|---|---|---|
| ASR-0125 | `BACKUP`（signature\|privileged）、平台签名 uid=1000、SELinux system_app_data_file 通道 | 新增 `android.permission.BACKUP` |
| ASR-0127 | `CLEAR_APP_USER_DATA`（signature，既有声明） | 无 |
| ASR-0197 | `MOUNT_UNMOUNT_FILESYSTEMS`（signature，既有声明） | 无 |
| ASR-0326 | `MOUNT_FORMAT_FILESYSTEMS`（signature） | 新增 `android.permission.MOUNT_FORMAT_FILESYSTEMS` |
| ASR-0187 | `CAPTURE_VIDEO_OUTPUT`（signature\|privileged） | 新增 `android.permission.CAPTURE_VIDEO_OUTPUT` |
| ASR-0385/0386 | `MANAGE_USERS`（signature，既有声明） | 无 |

不修改 AIDL / lib 模块；testapp 无需新增权限（本地探针仅读写自身目录；本地用户清单探针在无权限时如实报 SecurityException）。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺参数（packageName/file/name/userId） | 命令与 testapp 双侧拦截，返回缺参提示，不 crash |
| 非法 packageName（含 `../` 等路径穿越字符） | 严格包名正则校验拒绝（备份/清缓存），不进入路径拼接 |
| 备份/恢复确认界面 60s 超时未确认 | 框架取消操作；备份侧文件无魔数头 → success=false + 提示确认可能超时；**恢复侧按调用耗时判别：≥55s 判为未确认 → success=false + 如实报错**（确认后恢复耗时 >55s 的大应用为已知假阴性边界，以探针验证为准） |
| 恢复源非备份文件/位于受控目录外 | 魔数头校验 + 路径白名单（/sdcard、Launcher 数据目录）双重拒绝，杜绝特权任意文件读取 |
| 截屏/备份公开副本目标位于受控目录外或为既有非 PNG/.ab 文件 | canonical 路径白名单校验拒绝；既有非目标类型文件拒绝覆盖（防用户数据被破坏） |
| **本 ROM 无 android.app.IBackupManager** | 引擎按 android.app.backup.IBackupManager 反射（dex 核验），方法集 adbBackup/adbRestore |
| **SELinux 拒 system_server 写 /sdcard** | 备份 fd 用 Launcher 自身数据目录（system_app_data_file），成功后由 Launcher 复制公开副本；恢复先暂存后执行 |
| 恢复时框架强杀目标应用进程 | 目标进程内命令结果丢失（日志在 Launcher 侧完整），引擎用 3s 延时保证数据落盘，探针独立验证 |
| 清除缓存回调不触发（本 ROM PMS fork） | 引擎以缓存目录读回核对为准（verified），回调结果如实上报（cleared），任一为真即 success |
| 包不存在（清缓存/备份） | 清缓存返回 "package not installed"；备份如实上报失败 |
| 无可移除存储卷（卸载/格式化） | {success:false, error:"no removable ... volume found", volumes:[...]}，不 crash 不误操作 |
| 卸载后仅瞬时状态变化（未达终态回滚） | 轮询仅终态（0/5/8/6）或卷消失判成功，回滚如实报错（代码审查后修正） |
| 格式化仅进入 formatting 未完成 | 轮询至 formatting 后的终态（重新挂载/卸载/消失）才判成功；失败态（7/9）如实报错（代码审查后修正） |
| 显式 volumeId 指向 emulated/private 卷 | 选择器双重校验拒绝（防误卸载系统存储） |
| 本 ROM VolumeInfo 常量重排（emulated type=2） | 引擎以 isRemovable 为主判据 + TYPE_PUBLIC 常量运行时反射（文档化差异） |
| 本 ROM 无 AOSP screenshot API | 三通道降级，MTK captureDisplay 生效；FLAG_SECURE 窗口捕获为黑块（与 ASR-0185 交互，如实保存）；**ScreenshotHardwareBuffer 用后立即 close（反射）防显存滞留** |
| createUser 返回 UserInfo（非 UserHandle） | 返回类型兼容；id 经字段/getter/UserHandle 三级兜底读取 |
| 本 ROM UserInfo 无 getId/getName/getFlags | 公共字段 id/name/flags 优先读取 |
| 删除主用户/当前用户 | 引擎预检拒绝，返回明确错误 |
| 删除不存在用户（removeUser 返回 false） | success=false，附用户列表（如实上报） |
| 用户删除异步（removing/partial 状态） | 引擎轮询 getUsers() 确认消失后才报成功 |

## 8. 真机验证记录（2026-08-06，Android 13 / API 33 userdebug，平台签名 + device owner）

（命令级用例结果见测试用例设计文档，此处记录机制核验关键结论）

| 核验项 | 结果 |
|---|---|
| 设备基线 | 仅 emulated;0 内部存储（无外置 SD/USB OTG）；仅用户 0；`Screen capture disallowed users: []`；备份服务启用；`backup_enabled=0` |
| **IBackupManager 类名/方法集核验** | `android.app.IBackupManager$Stub` ClassNotFoundException；framework.jar dex 反编译确认 `android.app.backup.IBackupManager` + `adbBackup(int,Pfd,Z×8,String[])/adbRestore(int,Pfd)`（AOSP 12 风格，无 fullBackup/fullRestore/isRestoreInProgress） |
| **SELinux 备份通道核验** | 直写 /sdcard fd → system_server avc denied（fuse）；plat_sepolicy.cil 确认 system_server 可写 system_app_data_file → 改用 Launcher 数据目录（实测 avc 不再出现，备份成功） |
| **备份确认界面核验** | adbBackup 触发 `com.android.backupconfirm/.BackupRestoreConfirmation`（BACK UP MY DATA/DO NOT BACK UP），Binder 阻塞至确认；点按确认后备份完成（约 30s 含确认），文件 4632B 且含 "ANDROID BACKUP" 魔数头；publicCopy 复制到 /sdcard/MDM/backup 成功（uid=1000 可经 FUSE 写 /sdcard） |
| **恢复闭环核验** | probe.txt v1→备份→改 v2→adbRestore（点按 RESTORE MY DATA）→probe.txt 恢复 v1；框架强杀 testapp 进程（预期），Launcher 侧日志完整（elapsedMs=17213，stagedFile 暂存路径正确） |
| **ASR-0127 回调行为核验** | deleteApplicationCacheFiles 调用后缓存确实清除（探针消失 + 缓存目录读回为空），但 **onRemoveCompleted 回调 30s 内不触发**（本 ROM PMS fork 行为）→ 引擎 verified 读回机制落地 |
| **截屏通道核验** | `SurfaceControl.screenshot(DisplayCaptureArgs)`/`screenshot(int)` 均 NoSuchMethodException；dex 核验 MTK 通道 `getInternalDisplayToken + Builder(IBinder) + captureDisplay + ScreenshotHardwareBuffer.asBitmap()`；三通道降级生效；默认 1600×720（getRealSize）、指定 720×1280 均生成有效 PNG（PNG 魔数 + 内容多色验证，非黑屏非空文件） |
| **存储卷核验** | getVolumes 返回 private/emulated;0（本 ROM emulated type=2，AOSP=3）；emulated 卷 removable=false → 卸载/格式化命令如实拒绝（含显式 volumeId 传参路径） |
| **用户管理核验** | createUser 返回 UserInfo（CastException 修正后兼容）；UserInfo 公共字段 id/name/flags 读取成功；创建（id 10/11/12，flags 0 与 256→1280 均生效）→ 删除闭环，dumpsys user 无残留；主用户（0）拒绝删除；`removeUser(999)` 返回 false 如实上报；testapp 本地 getUsers 探针报 SecurityException（无 MANAGE_USERS）印证签名权限必要性 |
| 测试后设备恢复 | 用户列表仅 Owner；备份 .ab 临时文件清理；无用户限制残留 |

**部署注意**：`adb install -r` 重装 Launcher 后需重新拉起 HOME 并重启 testapp 进程以重建 AIDL 绑定（否则 RESULT:null DeadObjectException）；**勿对 testapp 使用 `am force-stop`**——MTK DuraSpeed 会将 force-stop 的应用加入 suppress 列表，其 manifest 广播被 AMS 丢弃（白名单 `dumpsys duraspeed addwhitelist com.hmdm.testapp` 仅防后续抑制，已抑制项需重启设备清空，本批次实测）；备份/恢复测试需屏幕点亮且无锁屏（确认界面无法在锁屏之上显示）。
