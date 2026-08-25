# 静默安装/卸载管控（ASR-0001/0002/0004/0005/0008/0009/0011）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner（`dpm list-owners` 预期 `DeviceOwner,Affiliated`），testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root`、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`（IPC 与 UI 按钮等效，同一 `TestActions.execute()` 引擎）；
- 测试素材：`/sdcard/MDM/testapp.apk`（testapp 平台签名 APK，本机测试安装/卸载目标）；探针包 `com.hmdm.probe`（白名单正则 `com\.hmdm\..*` 命中）与 `com.mdmprobe.test`（不命中，工具脚本构建的纯桩 APK）；
- 对照命令：`adb shell pm list packages`、`adb shell pm uninstall -k <pkg>`、`adb shell dumpsys package <pkg> | grep hidden`、`adb shell strings /data/system/device_policies.xml | grep lock-task-component`、`adb logcat -s HYX-MDM-APP`；
- 恢复基线：安装策略模式 1（白名单，出厂默认）；testapp 版本 versionCode=1；测试结束时探针包全部卸载、`IsDisallowInstallApps=false`、`IsDisallowInstallUnknownSource=false`；
- 本 ROM 特性（2026-08-13 核验）：① `Uninstall` 默认卸载删除应用数据目录（ASR-0003 语义），`keepData=true` 走隐藏 `deletePackage(DELETE_KEEP_DATA)` 保留数据，且本 ROM PMS 不回调删除观察者——命令以读回兜底，卸载完成约 30s（测试需等待）；② `DISALLOW_INSTALL_APPS` 连 adb shell 安装一并拦截；`DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY` 不拦 adb shell（shell 豁免）；③ 白名单接收器处理异步，安装后核对留 3s+ 间隔；④ testapp 包可见性探针依赖 `QUERY_ALL_PACKAGES`（已声明）。

## 2. 测试用例表

### 2.1 ASR-0001 静默安装（Install）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0001-01 基线 | `./send_test_command.sh IsPackageInstalledLocal packageName=com.hmdm.testapp` | installed=true，versionCode=1 |
| TC-0001-02 静默安装（自安装升级） | `./send_test_command.sh Install apkFilePath=/sdcard/MDM/testapp.apk packageName=com.hmdm.testapp`；等待 4s；`IsPackageInstalledLocal packageName=com.hmdm.testapp` | 命令 RESULT=true；安装过程无任何安装界面/确认弹窗（全程 adb 会话无可视交互）；探针 installed=true；Launcher 日志 `install success: true` |
| TC-0001-03 缺参 | `./send_test_command.sh Install` / 仅 `packageName` | 返回 `missing parameter: apkFilePath`（或 packageName），不 crash，系统状态不变 |
| TC-0001-04 路径不存在 | `./send_test_command.sh Install apkFilePath=/sdcard/MDM/no-such.apk packageName=com.hmdm.testapp` | RESULT=false（会话提交失败/文件打开失败），不 crash |

### 2.2 ASR-0002 静默降级安装

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0002-01 构造高版本 | 本地将 testapp `versionCode` 临时改为 2 构建、签名、`adb install -r`；`IsPackageInstalledLocal` | installed=true，versionCode=2 |
| TC-0002-02 降级安装 | `./send_test_command.sh Install apkFilePath=/sdcard/MDM/testapp.apk packageName=com.hmdm.testapp`（v1 APK）；等待 4s；`IsPackageInstalledLocal` | RESULT=true；versionCode 回到 1（setRequestDowngrade 放行低版本） |
| TC-0002-03 恢复 | 本地恢复 versionCode=1 重新构建（最终交付物保持 versionCode=1） | 构建通过，交付版本不变 |

### 2.3 ASR-0004 保留数据卸载（Uninstall keepData=true）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0004-01 安装探针并写数据 | `adb install -r /tmp/kilo/probe-hmdm.apk`；`am start -n com.hmdm.probe/.Stub`；root 下 `echo probe-data > /data/data/com.hmdm.probe/probe.txt`；`IsPackageInstalledLocal` | installed=true；数据文件写入成功 |
| TC-0004-02 保留数据卸载 | `./send_test_command.sh Uninstall packageName=com.hmdm.probe keepData=true`；等待命令返回（约 30s 读回窗口）；`IsPackageInstalledLocal`；`adb shell "cat /data/data/com.hmdm.probe/probe.txt"` | RESULT=true；包已卸载（探针 installed=false）；数据目录 `/data/data/com.hmdm.probe` 保留且 probe.txt 内容 `probe-data` 完好 |
| TC-0004-03 默认卸载（对照组，ASR-0003 语义） | 重新安装探针+写数据后 `./send_test_command.sh Uninstall packageName=com.hmdm.probe`；`adb shell "ls /data/data/com.hmdm.probe"` | RESULT=true；包卸载且数据目录**被删除**（`No such file or directory`）——与 keepData=true 行为对比明确 |
| TC-0004-04 卸载不存在的包 | `./send_test_command.sh Uninstall packageName=com.nonexistent.xyz` | RESULT=false（异常捕获如实返回），不 crash |

### 2.4 ASR-0005 设置是否禁止卸载（SetUninstallBlocked / IsUninstallBlocked）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0005-01 基线查询 | `./send_test_command.sh IsUninstallBlocked packageName=com.hmdm.probe`（先安装探针） | RESULT=true（白名单模式受保护）或 false（模式 0 时）；以模式 1 基线为准记录 |
| TC-0005-02 禁止卸载 | `./send_test_command.sh SetUninstallBlocked packageName=com.hmdm.probe canUninstall=false`；`IsUninstallBlocked`；`adb shell pm uninstall com.hmdm.probe` | Set RESULT=true；IsUninstallBlocked=true；`pm uninstall` 返回 `DELETE_FAILED_OWNER_BLOCKED`（禁止生效） |
| TC-0005-03 解除禁止 | `./send_test_command.sh SetUninstallBlocked packageName=com.hmdm.probe canUninstall=true`；`IsUninstallBlocked`；`adb shell pm uninstall com.hmdm.probe` | Set RESULT=true；IsUninstallBlocked=false；pm uninstall 成功（Success） |
| TC-0005-04 缺参 | `SetUninstallBlocked packageName=com.hmdm.probe` | 返回 `missing parameter: canUninstall`，不 crash |
| TC-0005-05 与白名单交互 | 模式 1 下重装探针（命中白名单）→ `IsUninstallBlocked` | true（接收器自动设 lock+block）；`Uninstall` 命令仍可卸载（命令路径自动解 lock+block）——记录交互行为 |

### 2.5 ASR-0008 设置是否禁止安装（SetDisallowInstallApps / IsDisallowInstallApps）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0008-01 基线 | `./send_test_command.sh IsDisallowInstallApps` | false |
| TC-0008-02 禁止安装 | `./send_test_command.sh SetDisallowInstallApps disallow=true`；`IsDisallowInstallApps`；`adb install -r /tmp/kilo/probe-hmdm.apk` | Set RESULT=true；Is=true；adb install 报 `SecurityException: User restriction prevents installing`（含 adb 通道） |
| TC-0008-03 恢复允许 | `./send_test_command.sh SetDisallowInstallApps disallow=false`；`IsDisallowInstallApps`；`adb install -r /tmp/kilo/probe-hmdm.apk` | Is=false；安装恢复成功（Success） |
| TC-0008-04 缺参 | `SetDisallowInstallApps` | 返回 `missing parameter: disallow`，不 crash |

### 2.6 ASR-0009 应用可安装白名单（SetInstallPolicyMode 模式 1 / InstallWhitelistManager）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0009-01 模式确认 | `./send_test_command.sh GetInstallPolicyMode` | mode=1（whitelist），modeName=whitelist |
| TC-0009-02 白名单包受保护 | `adb install -r /tmp/kilo/probe-hmdm.apk`（命中 `com\.hmdm\..*`）；等待 4s；`IsPackageInstalledLocal packageName=com.hmdm.probe`；`IsUninstallBlocked packageName=com.hmdm.probe`；`adb shell strings /data/system/device_policies.xml \| grep -a com.hmdm.probe` | 包保留 installed=true；IsUninstallBlocked=true；device_policies.xml lock-task-component 含 com.hmdm.probe；Launcher 日志 `matched:true` + `setUninstallBlocked true` |
| TC-0009-03 非白名单包自动卸载 | `adb install -r /tmp/kilo/probe-mdm.apk`（com.mdmprobe.test 不命中）；等待 5s；`IsPackageInstalledLocal packageName=com.mdmprobe.test`；`adb shell pm list packages \| grep mdmprobe` | 安装命令显示 Success（先装上），随后被自动静默卸载（探针 installed=false、pm list 无该包）；Launcher 日志 `not matched silentUninstallApplication` |
| TC-0009-04 模式 0（off） | `./send_test_command.sh SetInstallPolicyMode mode=0`；`GetInstallPolicyMode`；`adb install -r /tmp/kilo/probe-mdm.apk`；等待 4s；`IsPackageInstalledLocal packageName=com.mdmprobe.test` | mode=0/off；非白名单包**保留**（不再自动卸载）——2026-08-13 修正后的行为 |
| TC-0009-05 恢复模式 1 | `SetInstallPolicyMode mode=1`；`GetInstallPolicyMode` | mode=1，与基线一致 |

### 2.7 ASR-0011 禁止未知来源应用安装（Set/IsDisallowInstallUnknownSource）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0011-01 基线 | `./send_test_command.sh IsDisallowInstallUnknownSource` | false |
| TC-0011-02 禁止 | `./send_test_command.sh SetDisallowInstallUnknownSource disallow=true`；`IsDisallowInstallUnknownSource`；`adb shell dumpsys device_policy \| grep -a DISALLOW_INSTALL_UNKNOWN` | Set RESULT=true；Is=true；dumpsys 用户限制含 DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY |
| TC-0011-03 限制范围（shell 豁免，如实记录） | 限制生效时 `adb install -r /tmp/kilo/probe-mdm.apk` | adb shell 安装不受该限制（shell 通道豁免，AOSP 语义）；非白名单包随后被白名单机制卸载（模式 1 时）——记录 |
| TC-0011-04 恢复 | `SetDisallowInstallUnknownSource disallow=false`；`IsDisallowInstallUnknownSource` | false，与基线一致 |
| TC-0011-05 缺参 | `SetDisallowInstallUnknownSource` | 返回 `missing parameter: disallow`，不 crash |

### 2.8 恢复基线（测试结束）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-B1-01 清理探针包 | `./send_test_command.sh Uninstall packageName=com.hmdm.probe`（如存在） | 探针包全部卸载 |
| TC-B1-02 策略复位 | `SetInstallPolicyMode mode=1`、`SetDisallowInstallApps disallow=false`、`SetDisallowInstallUnknownSource disallow=false` | 与测试前基线一致（模式 1 / 均允许） |
| TC-B1-03 部署校验 | `adb shell dpm list-owners`；`./send_test_command.sh GetConnectionStatus` | DeviceOwner,Affiliated 在位；bound=true |

## 3. 硬件受限测试说明

- ASR-0001/0002/0004/0005/0008/0009/0011 均无硬件依赖；上述用例全部在本机（Android 13 / API 33 userdebug，平台签名 + device owner）执行通过（2026-08-13）。
- 探针包 `com.hmdm.probe` / `com.mdmprobe.test` 为本批次用工具脚本构建的纯桩 APK（含一个空 Activity），用于安装/卸载/白名单/数据保留验证，非交付物。
- 测试中发现的 ROM/实现特性已如实写入设计文档第 2/7/8 节：默认卸载删数据（0004 需 keepData 补实现）、`pm uninstall` 受 lock task 拦截、`deletePackage` 观察者不回调、应用域 exec `pm` 不可靠、包可见性需 QUERY_ALL_PACKAGES、shell 安装豁免未知来源限制等。
