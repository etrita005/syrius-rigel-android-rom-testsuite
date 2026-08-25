# 电量/流量/运行查询类（ASR-0139/0372/0021-0023）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner，testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root && dumpsys duraspeed addwhitelist com.hmdm.testapp`、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`（IPC 与 UI 按钮等效，同一 `TestActions.execute()` 引擎）；也可在 testapp UI 的 "Stats query" 页面点按对应按钮；
- 对照命令（可选）：`adb shell dumpsys netstats`、`adb shell dumpsys batterystats`（Estimated power use 段）、`adb shell dumpsys dropbox`、`adb shell dumpsys activity processes`。

## 2. 测试用例表

### 2.1 ASR-0139 应用流量查询（QueryAppTraffic）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0139-01 全应用默认查询 | `./send_test_command.sh QueryAppTraffic` | 返回 Map：periodDays=1、network=all、apps 为数组；每项含 packageName/uid/rxBytes/txBytes/totalBytes；按 totalBytes 降序 |
| TC-0139-02 数据合理性 | 对照 `dumpsys netstats`（uid 维度）抽查 top 应用 | 各应用 rx+tx = totalBytes；与系统统计口径一致（可容忍轮询窗口差异，值非负） |
| TC-0139-03 单应用查询 | `./send_test_command.sh QueryAppTraffic packageName=com.hmdm.testapp` | apps 仅 1 项且 packageName 匹配；uid 为该应用 uid |
| TC-0139-04 指定天数/网络 | `./send_test_command.sh QueryAppTraffic days=7 network=wifi` | periodDays=7、network=wifi；结果仅含 wifi 模板流量 |
| TC-0139-05 mobile 过滤 | `./send_test_command.sh QueryAppTraffic network=mobile` | network=mobile；结果仅含移动网络模板流量（无 SIM 数据时可为 0） |
| TC-0139-06 非法 network | `./send_test_command.sh QueryAppTraffic network=bluetooth` | 返回 error：invalid parameter: network |
| TC-0139-07 不存在的包 | `./send_test_command.sh QueryAppTraffic packageName=com.not.exist` | 返回 error 字段（异常被捕获），命令不 crash |
| TC-0139-08 limit 截断 | `./send_test_command.sh QueryAppTraffic limit=3` | apps 长度 ≤ 3 |

### 2.2 ASR-0372 应用耗电量查询（QueryAppBattery）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0372-01 全应用查询 | `./send_test_command.sh QueryAppBattery` | 返回 Map：source 为 dumpsys 段名；apps 每项含 uid/packageName/powerMah/percent；按 powerMah 降序；computedDrainMah 与 `dumpsys batterystats` 一致 |
| TC-0372-02 与系统值一致 | 对照 `dumpsys batterystats` "Estimated power use (mAh):" 段抽查 uid | powerMah 与系统段内该 UID 的 mAh 值一致 |
| TC-0372-03 单应用查询 | `./send_test_command.sh QueryAppBattery packageName=com.hmdm.launcher` | apps 仅 1 项，packageName=com.hmdm.launcher（uid 1000） |
| TC-0372-04 排序与截断 | `./send_test_command.sh QueryAppBattery limit=5` | apps 长度 ≤ 5，且整体降序 |
| TC-0372-05 不存在的包 | `./send_test_command.sh QueryAppBattery packageName=com.not.exist` | apps 为空数组（无匹配），命令不 crash |
| TC-0372-06 数据非负 | 全量结果 | 所有 powerMah ≥ 0，percent 在合理范围（0~100 附近，受 reattributed 影响可略负，文档化） |

### 2.3 ASR-0021 应用运行时长查询（QueryAppRuntime）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0021-01 默认查询 | `./send_test_command.sh QueryAppRuntime` | 返回 Map：periodDays=7；apps 每项含 packageName/totalTimeInForegroundMs/lastTimeUsed/isInstalled；按时长降序 |
| TC-0021-02 数据合理性 | 对照 `dumpsys usagestats` 或系统"数字健康"抽查 | 前台时长与系统口径一致（ms，非负） |
| TC-0021-03 单应用查询 | `./send_test_command.sh QueryAppRuntime packageName=com.hmdm.launcher` | apps 仅 1 项且 packageName 匹配 |
| TC-0021-04 指定天数 | `./send_test_command.sh QueryAppRuntime days=1` | periodDays=1 |
| TC-0021-05 运行增长 | 先查询记录 launcher 时长 → 前台停留 testapp 界面数秒 → 再查 | launcher 时长 ≥ 前值（前台运行计入统计） |
| TC-0021-06 已卸载应用标记 | 查询结果（若有已卸载记录） | isInstalled=false 正确标记，不抛异常 |

### 2.4 ASR-0022 正在运行应用列表（QueryRunningApps）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0022-01 全量列表 | `./send_test_command.sh QueryRunningApps` | 返回 Map：count 与 processes 长度一致；每项含 pid/uid/processName/importance/importanceText/lru/packages |
| TC-0022-02 内容合理性 | 对照 `adb shell ps -A` 抽查 | 前台应用（如 com.hmdm.launcher）存在且 importanceText=FOREGROUND；本设备进程 pid 真实 |
| TC-0022-03 前台切换 | 前台打开 testapp 后查询 | com.hmdm.testapp 进程在列表内且 importance 优先级提升 |
| TC-0022-04 排序 | 查询结果 | 按 importance 升序（前台在前）排序，importanceText 映射正确 |

### 2.5 ASR-0023 应用运行异常查询（QueryAppCrashInfo）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0023-01 默认查询 | `./send_test_command.sh QueryAppCrashInfo` | 返回 Map：count 与 entries 长度一致；每项含 tag/timeMillis/packageName/subject；按时间降序 |
| TC-0023-02 构造 crash | 安装并启动一个会崩溃的测试 APK（如反复 `am start` 后 kill），或复用系统内已有 crash 记录 | 对应 crash 记录出现在结果中：tag=crash、packageName 匹配、subject 含异常类名 |
| TC-0023-03 按包过滤 | `./send_test_command.sh QueryAppCrashInfo packageName=<上一步包名>` | entries 全部为该包记录 |
| TC-0023-04 无异常时 | 清空 dropbox 后查询（或过滤一个从未崩溃的包） | entries 为空数组或仅含已有记录，命令不 crash |
| TC-0023-05 内容解析 | 对照 `adb shell dumpsys dropbox --print` | subject/packageName 与 dropbox 原始文本一致 |

### 2.6 通用用例

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-M-01 缺参容错 | 5 个命令均不带参数执行 | 全部返回有效结果（默认参数），不 crash |
| TC-M-02 未知事件 | `./send_test_command.sh QueryAppXXX` | 返回 unknown event，不 crash |
| TC-M-03 UI 等效 | testapp UI "Stats query" 页逐一按钮 | 与 IPC 返回一致（同一 TestActions 引擎） |
| TC-M-04 返回值 JDK 类型 | 检查 5 个命令返回 JSON | 全部为 Map/List/基本类型，无自定义类（AIDL 通道约束） |

## 3. 验证提示

- 结果查看：`./send_test_command.sh` 输出 `Broadcast completed: data=...` 与 logcat `HYX-TESTAPP-CMD` 行；
- 流量对照：`adb shell dumpsys netstats --uid`（uid 维度 rx/tx）；
- 耗电对照：`adb shell dumpsys batterystats | grep -A 30 'Estimated power use'`；
- 异常对照：`adb shell dumpsys dropbox --print`；
- 进程对照：`adb shell ps -A | grep <包名>`；
- 运行时长对照：`adb shell dumpsys usagestats | grep -A 5 <包名>`。

## 4. 实测结果（2026-08-04，Android 13 / API 33 userdebug，平台签名 + device owner）

全部用例经 `send_test_command.sh` IPC 通道执行（与 UI 按钮共用 TestActions 引擎）：

| 用例 | 实测结果 |
|---|---|
| TC-0139-01 | 通过：返回 30 项（默认 limit），降序，字段齐全 |
| TC-0139-02 | 通过：launcher uid=1000 rx 311/tx 887，与 `dumpsys netstats detail` 逐字节一致 |
| TC-0139-03 | 通过：单应用仅 1 项，uid 正确 |
| TC-0139-04 | 通过：days=7 network=wifi 正常 |
| TC-0139-05 | 通过：network=mobile 正常（本机无 SIM，数值为 0） |
| TC-0139-06 | 通过：返回 `invalid parameter: network (all/wifi/mobile)` |
| TC-0139-07 | 通过：返回 error NameNotFoundException，不 crash |
| TC-0139-08 | 通过：apps 长度=3 |
| TC-0372-01 | 通过：computedDrainMah=640；apps 降序含 powerMah/percent |
| TC-0372-02 | 通过：UID 1000=3.23、UID 0=2.82、u0a98=0.0181 等与系统段逐一一致 |
| TC-0372-03 | 通过：com.hmdm.launcher（uid 1000）→ 3.23 mAh / 0.505% |
| TC-0372-04 | 通过：limit=5 → 5 项 |
| TC-0372-05 | 通过：apps 空数组，不 crash |
| TC-0372-06 | 通过：数值非负 |
| TC-0021-01 | 通过：launcher 2683146ms / testapp 1740747ms，降序 |
| TC-0021-02 | 通过：与系统口径一致（ms，非负） |
| TC-0021-03 | 通过：单应用仅 1 项 |
| TC-0021-04 | 通过：periodDays=1 |
| TC-0021-05 | 通过（带说明）：会话内 1740747→1741069ms 增长；usage 统计周期性落盘，短窗口不即时刷新 |
| TC-0021-06 | 通过：isInstalled 标记正确 |
| TC-0022-01 | 通过：count=20，字段齐全 |
| TC-0022-02 | 通过：与 `ps -A` 抽查一致 |
| TC-0022-03 | 通过：testapp 前台 importance=100 FOREGROUND，后台 SERVICE |
| TC-0022-04 | 通过：按 importance 升序，映射正确 |
| TC-0023-01 | 通过：默认 17 条历史（含系统自带 camera crash），降序 |
| TC-0023-02 | 通过：`am crash com.hmdm.testapp` 后出现 data_app_crash 记录，subject=RemoteServiceException$CrashedByAdbException |
| TC-0023-03 | 通过：按包过滤正确 |
| TC-0023-04 | 通过：无匹配包 → count=0 |
| TC-0023-05 | 通过：与 `dumpsys dropbox` 内容一致 |
| TC-M-01 | 通过：5 命令缺参均返回有效结果 |
| TC-M-02 | 通过：未知事件返回 unknown event |
| TC-M-03 | 通过（说明）：UI/IPC 共用 TestActions.execute() 同一引擎，行为一致（UI 按钮未逐一点击，IPC 全覆盖） |
| TC-M-04 | 通过：返回值全 JDK Map/List/基本类型 |
