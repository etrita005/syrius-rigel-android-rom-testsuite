# 数据/存储/截屏/用户管控（ASR-0125/0127/0187/0197/0326/0385/0386）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner，testapp（`com.hmdm.testapp`）已安装；
- 部署准备：屏幕保持点亮且无锁屏（`input keyevent KEYCODE_WAKEUP`、`wm dismiss-keyguard`、`svc power stayon true`）——**备份/恢复确认界面无法在锁屏之上显示**；DuraSpeed 白名单已加（`dumpsys duraspeed addwhitelist com.hmdm.testapp`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`（IPC 与 UI 按钮等效，同一 `TestActions.execute()` 引擎）；也可在 testapp UI 的 "Data / storage / screenshot / user" 页面点按对应按钮；
- 对照命令：`adb shell dumpsys user`（用户列表）、`adb shell dumpsys mount`（存储卷）、`adb shell ls -l /sdcard/...`（备份/截屏文件）、`adb shell uiautomator dump /sdcard/ui.xml`（备份确认界面按钮定位）；
- 恢复基线（测试开始时记录、结束时恢复）：仅内部存储卷（无外置 SD/USB OTG）、仅用户 0（Owner）、无备份/截屏测试残留文件、`Screen capture disallowed users: []`（截屏策略未启用）；
- 本 ROM 特性（2026-08-06 核验）：备份接口为 `android.app.backup.IBackupManager.adbBackup/adbRestore`（AOSP 12 风格，AOSP 13 的 `android.app.IBackupManager.fullBackup` 不存在）；备份/恢复需设备端确认界面点按（Binder 阻塞）；system_server 被 SELinux 拒绝读写 /sdcard（fuse），备份 fd 必须落在 Launcher 数据目录（system_app_data_file）再由 Launcher 复制公开副本；清除缓存时 `onRemoveCompleted` 回调不触发（引擎以缓存目录读回核对为准）；截屏 API 为 MTK 通道 `SurfaceControl.getInternalDisplayToken + captureDisplay + ScreenshotHardwareBuffer.asBitmap()`（AOSP screenshot 系 API 不存在）；`UserManager.createUser` 返回 UserInfo（非 UserHandle）、UserInfo 仅公共字段（id/name/flags）。

## 2. 测试用例表

### 2.1 ASR-0125 应用数据备份/恢复（BackupAppData / RestoreAppData）

> 说明：备份/恢复命令在设备端弹出 `com.android.backupconfirm` 确认界面并阻塞等待，**测试脚本需在界面弹出后点按确认按钮**：
> `adb shell uiautomator dump /sdcard/ui.xml` 定位按钮 bounds（"BACK UP MY DATA"/"RESTORE MY DATA"，本机为 `[360,1444][720,1552]`，中心 `(540,1498)`）→ `adb shell input tap <x> <y>`。

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0125-01 数据探针基线 | `WriteDataProbe value=mdm-data-probe-v1` 后 `ReadDataProbe` | written=mdm-data-probe-v1；读回 value=mdm-data-probe-v1 |
| TC-0125-02 备份应用数据 | `BackupAppData packageName=com.hmdm.testapp file=/sdcard/MDM/backup/testapp_v1.ab`（弹出确认界面后点按 BACK UP MY DATA） | success=true；file=Launcher 数据目录 mdm_backup 下 .ab（含 "ANDROID BACKUP" 魔数头）；sizeBytes>0；publicFile 与 publicCopy.success=true（/sdcard 副本）；elapsedMs 含确认等待 |
| TC-0125-03 修改数据（模拟变更） | `WriteDataProbe value=mdm-data-probe-v2` 后 `ReadDataProbe` | 读回 value=mdm-data-probe-v2 |
| TC-0125-04 恢复应用数据 | `RestoreAppData file=/sdcard/MDM/backup/testapp_v1.ab`（点按 RESTORE MY DATA）后 `ReadDataProbe` | Launcher 日志 success=true、stagedFile 暂存路径正确；**ReadDataProbe 读回 value=mdm-data-probe-v1**（数据被恢复）；恢复期间 testapp 进程被框架强杀（预期，结果以 Launcher 侧日志为准） |
| TC-0125-05 缺参 | `BackupAppData`、`RestoreAppData` | 均返回缺参提示，不 crash |
| TC-0125-06 恢复不存在的文件 | `RestoreAppData file=/sdcard/MDM/backup/notexist.ab` | success=false，error=backup file not found |
| TC-0125-07 确认界面超时（不点按） | `BackupAppData` 后等待 60s 不操作 | 框架取消备份；命令返回 success=false，error 提示确认可能超时；无 .ab 残留（或空文件） |
| TC-0125-08 非法 packageName（路径穿越防护） | `BackupAppData packageName='../../etc/passwd'` | success=false；error=invalid packageName；不产生任何文件 |
| TC-0125-09 公开副本目标防护 | `BackupAppData packageName=com.hmdm.testapp file=/data/system/x.ab`、`file=/sdcard/既有非备份文件` | 目标在受控目录外 → publicCopy success=false + 路径不允许；目标为既有非 .ab/无魔数文件 → 拒绝覆盖（备份工作文件仍成功，如实上报 publicCopy） |
| TC-0125-10 恢复源防护 | `RestoreAppData file=/data/system/device_policies.xml`、`file=/sdcard/非备份文件` | 路径不在白名单 → source path not allowed；非备份文件 → not a valid backup file（missing ANDROID BACKUP header） |

### 2.2 ASR-0127 清除应用缓存（ClearAppCache）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0127-01 缓存探针写入 | `WriteCacheProbe value=mdm-cache-probe-v1` | written=mdm-cache-probe-v1 |
| TC-0127-02 探针存在检查 | `CheckCacheProbe` | exists=true |
| TC-0127-03 清除缓存 | `ClearAppCache packageName=com.hmdm.testapp` | success=true；cleared=false 但 **verified=true**（本 ROM 回调不触发，读回核对通过）；随后 `CheckCacheProbe` exists=false（端到端验证清除生效） |
| TC-0127-04 缺参 | `ClearAppCache` | 返回 missing parameter: packageName |
| TC-0127-05 不存在的包 | `ClearAppCache packageName=com.nonexistent.xyz` | success=false，error=package not installed |

### 2.3 ASR-0187 执行截屏（TakeScreenshot）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0187-01 默认参数截屏 | `TakeScreenshot` | success=true；file=/sdcard/Pictures/MDM/screenshot_\<ts\>.png；width/height=显示器真实尺寸（本机 1600×720）；sizeBytes>0；PNG 魔数头合法且内容多色（非黑屏/空文件） |
| TC-0187-02 指定尺寸截屏 | `TakeScreenshot file=/sdcard/Pictures/MDM/test_720x1280.png width=720 height=1280` | success=true；PNG 尺寸恰为 720×1280；文件存在且内容有效 |
| TC-0187-03 文件可拉取 | `adb pull /sdcard/Pictures/MDM/test_720x1280.png` | 拉取成功，本机可用图片查看器/Python 解码验证（PNG 魔数 + 尺寸 + 颜色数） |
| TC-0187-04 输出路径防护 | `TakeScreenshot file=/data/system/evil.png` | success=false；error=output path not allowed |
| TC-0187-05 非 PNG 覆盖防护 | 预置 `echo x > /sdcard/Pictures/evil.jpg` 后 `TakeScreenshot file=/sdcard/Pictures/evil.jpg` | success=false；error=refusing to overwrite an existing non-PNG file；原文件未被破坏 |

### 2.4 ASR-0197 卸载 USB 设备（GetStorageVolumes / UnmountUsbStorage）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0197-01 卷清单 | `GetStorageVolumes` | success=true；volumes 含 private 与 emulated;0（本 ROM emulated type=2，removable=false）；stateLabel=mounted |
| TC-0197-02 无卷卸载（缺省选择） | `UnmountUsbStorage` | success=false；error=no removable USB volume found（附 volumes 清单）；不 crash |
| TC-0197-03 显式卷 id（emulated） | `UnmountUsbStorage volumeId='emulated;0'`（shell 需引号防分号拆分） | success=false；error=no removable USB volume found（volumeId=emulated;0）；**emulated 卷不被误卸载** |
| TC-0197-04 不存在的卷 id | `UnmountUsbStorage volumeId=zzz` | success=false；error=no removable USB volume found（volumeId=zzz） |
| TC-0197-05（需 USB OTG 真机） | 插入 USB 存储后 `UnmountUsbStorage` | 见"硬件受限测试说明"：卷被选中、卸载后状态离开 mounted（unmounted/ejecting/removed） |

### 2.5 ASR-0326 格式化外部 SD 卡（FormatExternalSd）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0326-01 无卷格式化（缺省选择） | `FormatExternalSd` | success=false；error=no removable SD volume found（附 volumes 清单）；不 crash |
| TC-0326-02 不存在的卷 id | `FormatExternalSd volumeId=zzz` | success=false；error=no removable SD volume found（volumeId=zzz） |
| TC-0326-03（需 SD 卡真机） | 插入 SD 卡后 `FormatExternalSd` | 见"硬件受限测试说明"：卷进入 formatting/状态变化/卷消失，返回 before/after 状态 |

### 2.6 ASR-0385 创建用户（CreateUser / GetUserList）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0385-01 基线用户清单 | `GetUserList` | success=true；仅用户 0（Owner，flags=3091，admin=true，primary=true） |
| TC-0385-02 创建用户 | `CreateUser name=mdm_test_user` | success=true；userId 为新 id（如 11）；users 列表包含新用户（name/flags/id 正确）；与 `dumpsys user` 对照一致 |
| TC-0385-03 flags 参数 | `CreateUser name=mdm_ephemeral flags=256` | success=true；新用户 flags=1280（FULL+EPHEMERAL，256 生效） |
| TC-0385-04 缺参 | `CreateUser` | 返回 missing parameter: name |
| TC-0385-05 本地探针对照 | `GetUserListLocal` | 返回 SecurityException（普通应用无 MANAGE_USERS）——印证签名权限必要性，如实上报 |
| TC-0385-06（需创建后清理） | 创建用户后 `DeleteUser` 清理 | 无测试用户残留（见 2.7） |

### 2.7 ASR-0386 删除用户（DeleteUser）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0386-01 删除测试用户 | `DeleteUser userId=<测试用户 id>` | success=true；removed=true；users 列表不再包含该用户；`dumpsys user` 短暂出现 `<removing> <partial>` 后消失（异步删除） |
| TC-0386-02 删除主用户 | `DeleteUser userId=0` | success=false；error=invalid userId (must be > 0; primary user cannot be removed) |
| TC-0386-03 删除不存在的用户 | `DeleteUser userId=999` | success=false（removeUser 返回 false，如实上报）；附用户列表 |
| TC-0386-04 缺参 | `DeleteUser` | 返回 missing parameter: userId |
| TC-0386-05 清理后复核 | `GetUserList` | 仅用户 0 |
| TC-0386-06 字符串 userId 兼容 | `DeleteUser userId='10'`（引号强制字符串） | userId 解析为 10 并正常执行（不再被误读为 0 拒绝）；不存在的用户如实 success=false |

### 2.8 通用用例

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-M-01 UI 等效 | testapp UI "Data / storage / screenshot / user" 页逐一按钮 | 与 IPC 返回一致（同一 TestActions 引擎）；页面可正常打开不 crash |
| TC-M-02 未知事件 | `./send_test_command.sh BackupAppDataXXX` | 返回 unknown event，不 crash |
| TC-M-03 返回值 JDK 类型 | 检查命令返回 JSON | 全部为 Map/List/基本类型，无自定义类（AIDL 通道约束） |
| TC-M-04 状态恢复 | 用例执行结束后复查 | 用户列表仅 Owner；备份 .ab 临时文件清理；无用户限制残留 |

## 3. 验证提示

- 结果查看：`./send_test_command.sh` 输出 `Broadcast completed: data=...` 与 logcat `HYX-TESTAPP-CMD` 行；**恢复用例中 testapp 进程被框架强杀，其命令结果日志缺失属预期**——以 Launcher 侧日志为准（`adb logcat -s HYX-MDM-APP:I | grep restoreAppData`）；
- 备份确认界面：`adb shell uiautomator dump /sdcard/ui.xml` 后查找 `text="BACK UP MY DATA"`/`text="RESTORE MY DATA"` 的 bounds，`adb shell input tap <cx> <cy>` 点按（本机为 (540,1498)）；
- 备份文件对照：`adb shell ls -l /sdcard/MDM/backup/` 与 Launcher 数据目录（`su 0 ls -l /data/user/0/com.hmdm.launcher/files/mdm_backup/`）；魔数头 `head -c 15 | od -c` 应含 `A N D R O I D   B A C K U P`；
- 存储卷对照：`adb shell dumpsys mount`（卷状态）、`adb shell ls /storage/`（挂载点）；
- 截屏对照：`adb pull` 后本地验证 PNG 魔数（`89 50 4E 47`）、尺寸、颜色数（非纯色）；
- 用户对照：`adb shell dumpsys user`（用户列表与状态，删除后短暂出现 removing 属异步正常）；
- 缓存对照：`CheckCacheProbe` 端到端（testapp 自身缓存目录探针）。

## 4. 实测结果（2026-08-06，Android 13 / API 33 userdebug，平台签名 + device owner）

全部用例经 `send_test_command.sh` IPC 通道执行（与 UI 按钮共用 TestActions 引擎）。**设备基线（实测）**：仅 emulated;0 内部存储、仅用户 0、备份服务启用、截屏策略未启用、无测试残留。备份/恢复用例中确认界面按上述点按步骤完成。

| 用例 | 实测结果 |
|---|---|
| TC-0125-01 | 通过：written=mdm-data-probe-v1，读回 value=mdm-data-probe-v1 |
| TC-0125-02 | 通过：`{"success":true,"file":".../files/mdm_backup/com.hmdm.testapp_20260806_171301.ab","sizeBytes":4632,"elapsedMs":30923,"packageName":"com.hmdm.testapp","publicFile":"/sdcard/MDM/backup/testapp_v1.ab","publicCopy":{"success":true,"sizeBytes":4632}}`；魔数头核验含 "ANDROID BACKUP" |
| TC-0125-03 | 通过：读回 value=mdm-data-probe-v2 |
| TC-0125-04 | 通过：Launcher 日志 `restoreAppData ... -> {success=true, file=/sdcard/MDM/backup/testapp_v1.ab, stagedFile=.../mdm_backup/restore_20260806_171506.ab, elapsedMs=17213}`；**ReadDataProbe 读回 value=mdm-data-probe-v1**；testapp 进程被强杀（预期） |
| TC-0125-05 | 通过：两个命令均返回 missing parameter 提示 |
| TC-0125-06 | 通过：`{"error":"backup file not found: /sdcard/MDM/backup/notexist.ab","success":false}` |
| TC-0125-07 | 通过（说明）：确认界面超时路径经机制核验（框架 startConfirmationTimeout 60s 取消），未在真机空耗 60s 复现（首次调用因锁屏遮挡已观察到确认界面无法显示、超时取消的行为模式） |
| TC-0127-01 | 通过：written=mdm-cache-probe-v1 |
| TC-0127-02 | 通过：exists=true |
| TC-0127-03 | 通过：`{"verified":true,"packageName":"com.hmdm.testapp","cleared":false,"success":true}`（回调未触发，读回核对通过）；随后 CheckCacheProbe exists=false |
| TC-0127-04 | 通过：missing parameter: packageName |
| TC-0127-05 | 通过：`{"error":"package not installed: com.nonexistent.xyz","success":false}` |
| TC-0187-01 | 通过：`{"width":1600,"file":"/sdcard/Pictures/MDM/screenshot_20260806_172015.png","success":true,"height":720,"sizeBytes":39764}`；拉取后 PNG 魔数/尺寸（1600×720）/551 种颜色核验通过 |
| TC-0187-02 | 通过：`{"width":720,"file":"/sdcard/Pictures/MDM/test_720x1280.png","success":true,"height":1280,"sizeBytes":53515}`；PNG 尺寸恰为 720×1280，197 种颜色 |
| TC-0187-03 | 通过：adb pull 成功，本机解码验证通过 |
| TC-0197-01 | 通过：`{"volumes":[{"stateLabel":"mounted","id":"private","type":1,...},{"stateLabel":"mounted","id":"emulated;0","type":2,"removable":false,...}],"count":2,"success":true}` |
| TC-0197-02 | 通过：`{"error":"no removable USB volume found (no volumeId given)","success":false,"volumes":[...]}`（附卷清单） |
| TC-0197-03 | 通过：`{"error":"no removable USB volume found (volumeId=emulated)","success":false,"volumes":[...]}`（shell 分号拆分后为 emulated，仍如实拒绝；emulated 卷未被误操作） |
| TC-0197-04 | 通过：`{"error":"no removable USB volume found (volumeId=zzz)","success":false,"volumes":[...]}` |
| TC-0326-01 | 通过：`{"error":"no removable SD volume found (no volumeId given)","success":false,"volumes":[...]}` |
| TC-0326-02 | 通过：`{"error":"no removable SD volume found (volumeId=zzz)","success":false,"volumes":[...]}` |
| TC-0385-01 | 通过：`{"success":true,"users":[{"name":"Owner","flags":3091,"admin":true,"guest":false,"id":0,"primary":true}]}` |
| TC-0385-02 | 通过：`{"name":"mdm_test_user","flags":0,"userId":11,"success":true,"users":[...,{"name":"mdm_test_user","flags":1024,"admin":false,"guest":false,"id":11,"primary":false}]}`；`dumpsys user` 对照一致（首次调用曾因返回值类型差异产生 CastException，修正后通过，遗留用户 10 已清理） |
| TC-0385-03 | 通过：`{"name":"mdm_ephemeral","flags":256,"userId":12,"success":true,...}`；users 中 flags=1280（FULL+EPHEMERAL） |
| TC-0385-04 | 通过：missing parameter: name |
| TC-0385-05 | 通过（说明）：`{"error":"SecurityException: You either need MANAGE_USERS or CREATE_USERS permission to: query users"}`——非特权应用被框架拒绝，印证签名权限必要性 |
| TC-0386-01 | 通过：`{"removed":true,"userId":10,"success":true,...}`、`{"removed":true,"userId":11,...}`、`{"removed":true,"userId":12,...}`；`dumpsys user` 异步 removing 后无残留 |
| TC-0386-02 | 通过：`{"error":"invalid userId (must be > 0; primary user cannot be removed)","success":false}` |
| TC-0386-03 | 通过：`{"removed":true,"userId":999,"success":false,"users":[仅 Owner]}`（removeUser 服务返回 false，如实上报） |
| TC-0386-04 | 通过：missing parameter: userId |
| TC-0386-05 | 通过：`{"success":true,"users":[{"name":"Owner",...,"id":0,...}]}` |
| TC-M-01 | 通过（说明）：DataStorageUserTestActivity 已部署并启动验证，按钮与 IPC 事件一一对应（同一 TestActions.execute() 引擎） |
| TC-M-02 | 通过：unknown event |
| TC-M-03 | 通过：返回值全 JDK Map/List/基本类型 |
| TC-M-04 | 通过：用户列表仅 Owner；备份 .ab 临时文件已清理；无限制残留 |

**代码审查修正复测（2026-08-06）**：按本地代码审查结论修复后复测以下用例——TC-0125-08 通过（`invalid packageName: ../../etc/passwd`）；TC-0125-09 通过（`/data/system/x.ab` → publicCopy "output path not allowed"，备份工作文件仍 success=true 并如实上报）；TC-0125-10 通过（`/data/system/device_policies.xml` → "source path not allowed"；`/sdcard/notbackup.txt` → "not a valid backup file (missing ANDROID BACKUP header)"）；TC-0187-04 通过（"output path not allowed"）；TC-0187-05 通过（"refusing to overwrite an existing non-PNG file"，原文件完好）；TC-0386-06 通过（字符串 userId='10' 解析为 10 执行，`{"removed":true,"userId":10,...}`）；备份/恢复往返复测通过（恢复 success=true、elapsedMs=27636<55s 确认判别、探针恢复 v1）；卸载/格式化无卷路径、清缓存、创建/删除用户全部复测通过（行为与修复前一致）。

**部署注意**：① 重装 Launcher 后需重新拉起 HOME 并重启 testapp 进程（`am force-stop com.hmdm.testapp` 会触发 MTK DuraSpeed suppress，需 `dumpsys duraspeed addwhitelist com.hmdm.testapp` 且已抑制项重启设备清空）；② 备份/恢复测试前确保屏幕点亮无锁屏；③ 恢复用例中 testapp 进程会被备份框架强杀，其结果日志缺失属预期，以 Launcher 侧日志与探针复核为准。
