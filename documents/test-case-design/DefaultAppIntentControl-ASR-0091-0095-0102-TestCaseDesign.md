# 默认应用与意图管控（ASR-0091/0095/0102）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner（`dpm list-owners` = DeviceOwner,Affiliated），testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root && dumpsys duraspeed addwhitelist com.hmdm.testapp`（MTK DuraSpeed 白名单；**`am force-stop com.hmdm.testapp` 会使 testapp 进入 DuraSpeed suppress list、广播被静默丢弃——整机重启清空恢复**，测试期间避免 force-stop）、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`（IPC 与 UI 按钮等效，同一 `TestActions.execute()` 引擎）；也可在 testapp UI 的 "Default intent" 页点按；
- 对照命令：`adb shell dumpsys package preferred-activities`（persistent preferred 绑定）、`adb shell dumpsys user` / `dumpsys device_policy`（no_config_home_app 限制）、`adb shell cmd package resolve-activity --brief -a android.intent.action.VIEW -t video/*`（解析对照）、`adb shell settings get secure` / `dumpsys role`；
- 本机视频应用占位：`com.android.gallery3d`（图库，含 ACTION_VIEW video 能力）或 `com.android.music`；文件类型占位：`com.android.documentsui`（文档应用，处理部分 MIME）或 `com.android.htmlviewer`（text/html）；**若占位应用无对应能力，用例以"引擎拒绝/如实报错"路径为准**（机制验证优先）；
- 恢复基线（测试开始时记录、结束时恢复）：launcher 修改锁关闭、video/* 与文件类型绑定清除。

## 2. 测试用例表

### 2.1 ASR-0091 管控修改默认桌面（Set/IsDefaultLauncherSettingLocked）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0091-01 基线查询 | `./send_test_command.sh IsDefaultLauncherSettingLocked` | locked=false |
| TC-0091-02 锁定 | `./send_test_command.sh SetDefaultLauncherSettingLocked locked=true` | success=true；readBack=true；`dumpsys device_policy` 用户限制含 no_config_home_app |
| TC-0091-03 锁定后解析验证 | 尝试经 RoleManager 修改 HOME 角色（testapp 无权限通道可省，以系统限制状态为准） | 限制生效：Settings 默认应用页桌面选择入口隐藏/拒绝（如可核验） |
| TC-0091-04 解锁 | `SetDefaultLauncherSettingLocked locked=false` | success=true；readBack=false；限制清除 |
| TC-0091-05 缺参 | 不带 locked | testapp 侧 missing parameter；不 crash |

### 2.2 ASR-0095 默认视频播放器（SetDefaultVideoPlayer / GetDefaultVideoPlayer / ClearDefaultVideoPlayer）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0095-01 基线查询 | `./send_test_command.sh GetDefaultVideoPlayer` | success=true；mimeType=video/*；component 为当前解析（可能 null） |
| TC-0095-02 设置（自动解析 Activity） | `./send_test_command.sh SetDefaultVideoPlayer packageName=com.android.gallery3d` | success=true；component=com.android.gallery3d/...；`dumpsys package preferred-activities` 出现 video/* 绑定 |
| TC-0095-03 解析读回 | GetDefaultVideoPlayer | packageName/component 与设置一致 |
| TC-0095-04 显式 Activity | `SetDefaultVideoPlayer packageName=com.android.gallery3d activityName=com.android.gallery3d.app.MovieActivity` | 若该 Activity 存在则 success=true（如不存在则 error 如实上报，属设备差异） |
| TC-0095-05 清除 | `./send_test_command.sh ClearDefaultVideoPlayer packageName=com.android.gallery3d` | success=true；GetDefaultVideoPlayer 的 component 不再是该包（或 null） |
| TC-0095-06 无匹配能力包 | `SetDefaultVideoPlayer packageName=com.android.settings` | error "no ACTION_VIEW activity accepting video/* found"；不写绑定 |
| TC-0095-07 未安装包 | `SetDefaultVideoPlayer packageName=com.not.installed` | error（无可用 activity） |
| TC-0095-08 缺参 | 不带 packageName | testapp 侧 missing parameter：packageName |

### 2.3 ASR-0102 打开指定文件类型默认应用（Set/Get/ClearDefaultAppForFileType）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0102-01 设置 text/html | `./send_test_command.sh SetDefaultAppForFileType mimeType=text/html packageName=com.android.htmlviewer` | success=true；`dumpsys package preferred-activities` 出现 text/html 绑定 |
| TC-0102-02 查询 | `GetDefaultAppForFileType mimeType=text/html` | packageName=com.android.htmlviewer |
| TC-0102-03 清除 | `./send_test_command.sh ClearDefaultAppForFileType mimeType=text/html packageName=com.android.htmlviewer` | success=true；查询回 null 或非该包 |
| TC-0102-04 非法 MIME | `SetDefaultAppForFileType mimeType='bad/mime/type' packageName=com.android.htmlviewer` | error "malformed MIME type"；不写绑定 |
| TC-0102-05 无匹配包 | `SetDefaultAppForFileType mimeType=application/x-unknown-xyz packageName=com.android.settings` | error（无可用 activity） |
| TC-0102-06 缺参 | 不带 mimeType | testapp 侧 missing parameter：mimeType/packageName |

### 2.4 通用用例

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-M-01 未知事件 | `./send_test_command.sh SetDefaultVideoPlayerXXX` | 返回 unknown event，不 crash |
| TC-M-02 UI 等效 | testapp UI "Default intent" 页点按各按钮 | 页面 resumed；与 IPC 共用 TestActions 引擎；结果一致 |
| TC-M-03 返回值类型 | 检查命令返回 JSON | 全部为 Map/List/基本类型 |
| TC-M-04 事件目录 | `./send_test_command.sh ListEvents` | 目录含本批次 8 个新事件 |

## 3. 验证提示

- 结果查看：`./send_test_command.sh` 输出 `Broadcast completed: data=...` 与 logcat `HYX-TESTAPP-CMD` 行（result 码恒为 -1 以 data 为准；result=0 无日志先查 DuraSpeed suppress_list）；
- 绑定核验：`dumpsys package preferred-activities` 的 persistent preferred activities 段（Action=android.intent.action.VIEW, MIME 类型, 目标 ComponentName）；`cmd package resolve-activity --brief -a android.intent.action.VIEW -t <mime>` 交叉对照；
- 限制核验：`dumpsys device_policy` 用户限制清单含 no_config_home_app；
- UI 输入：EditText 用 `input text`（键盘先 `input keyevent 4` 收起再点按钮）。

## 4. 实测结果（2026-08-11，Android 13 / API 33 userdebug，平台签名 + device owner）

（部署完成后经 `send_test_command.sh` IPC 通道执行并回填）

| 用例 | 实测结果 |
|---|---|
| TC-0091-01 ~ 05 | 通过（首版 no_config_home_app 限制方案真机验证失败——本 ROM framework 无该限制键、写入静默丢弃；重构为组件锁方案后全量通过：02 锁定三组件 DISABLED + `resolve-activity -a android.settings.HOME_SETTINGS` = No activity found；03 选择器入口失效证据；04 解锁恢复 DEFAULT + 解析恢复；05 缺参错误路径；标志持久化重启保持） |
| TC-0095-01 ~ 08 | **本 ROM DPMS 静默丢弃 PPTA 写入**：01 基线正常；02/03 写入读回恒 null（resolveActivity type-only 口径；受控实验确认非引擎问题——video/mp4 绑定非视频包 com.android.music 后解析仍为 manifest 结果）；05 清除接口正常调用；06/07 无匹配包错误路径通过；**按用户决策命令保留、ASR-0095 维持部分完成待 ROM 适配**（08 缺参路径通过） |
| TC-0102-01 ~ 06 | 01~03 同 ASR-0095 受 PPTA 静默丢弃限制（命令如实 success=false）；04 非法 MIME 拒绝路径通过（malformed MIME type）；05 无匹配包错误路径通过；06 缺参路径通过；维持部分完成待 ROM 适配 |
| TC-M-01 ~ 04 | 通过（未知事件/返回值类型/事件目录/UI 页正常） |

**机制核验补充**：persistent preferred activity 由框架持久化（preferred_activities.xml），整机重启保持；no_config_home_app 由 RoleManagerService 在 HOME 角色变更路径强制。
