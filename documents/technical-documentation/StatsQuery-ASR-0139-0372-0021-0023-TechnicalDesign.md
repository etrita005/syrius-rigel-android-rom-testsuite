# 电量/流量/运行查询类（ASR-0139/0372/0021-0023）技术设计文档

## 1. 需求概述

| 需求 ID | 子功能项 | 需求语义 |
|---|---|---|
| ASR-0139 | 应用流量查询 | 查询指定应用（或全部应用）在指定时间段内的流量（收/发字节数） |
| ASR-0372 | 应用耗电量查询 | 查询指定应用（或全部应用）的耗电量（估计 mAh） |
| ASR-0021 | 应用运行时长查询 | 查询指定应用（或全部应用）在指定时间段内的前台运行时长 |
| ASR-0022 | 查询正在运行应用列表 | 查询当前正在运行的应用程序进程列表 |
| ASR-0023 | 应用运行异常查询 | 查询应用的运行异常记录（崩溃 crash / 无响应 ANR / watchdog） |

**归属**：ASR-0139/0021/0022/0023 归属「Launcher（MDM）」；ASR-0372 需求文档备注为「Launcher（MDM）+ 系统 API」——DevicePolicyManager 无对应查询能力，需平台签名/特权应用调用系统服务（本实现为平台签名应用调用系统计算好的 batterystats 估计结果）。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名（`sharedUserId=android.uid.system`，uid=1000）+ device owner 部署；bootloader 锁定，不可改动 system 分区（无 ROM 侧定制能力）。

## 2. 技术选型与可行性核验

### 2.1 各子功能数据源选型

| 子功能 | 方案 | 依赖/权限 | 结论 |
|---|---|---|---|
| ASR-0139 流量 | **NetworkStatsManager.querySummaryForUid**（公开 API，WIFI + MOBILE 模板） | PACKAGE_USAGE_STATS（uid=1000 自动授予） | 采用 |
| ASR-0372 耗电 | **解析 `dumpsys batterystats` 的 "Estimated power use (mAh)" 段**（系统计算值，与设置页一致） | uid=1000 持有 DUMP 权限；appdomain 对本 ROM `system_file` 有 execute_no_trans（sepolicy 核验），可 exec `/system/bin/dumpsys` | 采用 |
| ASR-0021 运行时长 | **UsageStatsManager.queryUsageStats(INTERVAL_BEST)** 按包聚合（公开 API） | PACKAGE_USAGE_STATS | 采用 |
| ASR-0022 正在运行 | **ActivityManager.getRunningAppProcesses()**（公开 API；非特权调用方只返回自身可见进程，uid=1000 返回全量） | 无额外权限 | 采用 |
| ASR-0023 运行异常 | **DropBoxManager.getNextEntry**（tag: crash/anr/watchdog/system_app_crash） | READ_LOGS（平台签名持有） | 采用 |

### 2.2 ASR-0372 技术选型核验（本次实测）

- 目标 ROM 的 `com.android.internal.os.BatteryStatsImpl$Uid`（framework.jar classes3/4.dex 核验）**已移除 PowerProfile 估算方法**（无 `getCpuPowerMaMs`/`getTotalPowerMaMs` 等），进程内自算每应用 mAh 不可行；
- 系统在 dump 时按自身 PowerProfile 计算 "Estimated power use (mAh):" 段（每 UID 一行，含与设置页一致的数值），真机核验输出存在且完整（本设备 Computed drain 640 mAh）；
- exec `dumpsys` 的 SELinux 核验：`/system/bin/dumpsys` 文件标签为 `system_file`，plat_sepolicy.cil 含 `(allow appdomain system_file (file (getattr map execute execute_no_trans)))`，system_app 域可直执，无需域迁移；
- 结论：**解析系统计算结果**，数据来源权威、与 Settings 展示一致，规避隐藏 API 结构差异。

### 2.3 AIDL 通道约束

命令经 `ApiBinder.method2Commands` 注册（不修改 AIDL/lib 模块），返回 `Map{"RESULT": ...}`；返回结构全部使用 JDK 类型（Map/List/String/Long/Double/Integer/Boolean），避免自定义 Bean 跨进程反序列化失败（与 GetLogBufferSize 踩坑一致）。

## 3. 命令接口定义（SystemApiInterface.onEvent）

| 命令名 | 参数（Map） | 返回 RESULT | 说明 |
|---|---|---|---|
| `QueryAppTraffic` | packageName（可选，单应用查询）、days（可选，默认 1，上限 90）、network（可选，all/wifi/mobile，默认 all）、limit（可选，默认 30，上限 100） | Map（见下） | ASR-0139 应用流量查询 |
| `QueryAppBattery` | packageName（可选）、limit（可选，默认 20，上限 100） | Map（见下） | ASR-0372 应用耗电查询 |
| `QueryAppRuntime` | packageName（可选）、days（可选，默认 7，上限 90）、limit（可选，默认 30，上限 100） | Map（见下） | ASR-0021 运行时长查询 |
| `QueryRunningApps` | - | Map（见下） | ASR-0022 正在运行应用列表 |
| `QueryAppCrashInfo` | packageName（可选过滤）、limit（可选，默认 20，上限 100） | Map（见下） | ASR-0023 运行异常查询 |

**QueryAppTraffic 返回结构**：

```
{periodDays:int, network:String, startTime:long, endTime:long,
 apps:[{packageName:String, uid:int, rxBytes:long, txBytes:long, totalBytes:long}, ...]}
```

**QueryAppBattery 返回结构**：

```
{source:"dumpsys batterystats estimated power use", computedDrainMah:double|null,
 apps:[{uid:int, packageName:String, powerMah:double, percent:double}, ...]}
```

**QueryAppRuntime 返回结构**：

```
{periodDays:int, startTime:long, endTime:long,
 apps:[{packageName:String, totalTimeInForegroundMs:long, lastTimeUsed:long, isInstalled:boolean}, ...]}
```

**QueryRunningApps 返回结构**：

```
{count:int, processes:[{pid:int, uid:int, processName:String,
 importance:int, importanceText:String, lru:int, packages:[String,...]}, ...]}
```

**QueryAppCrashInfo 返回结构**：

```
{count:int, entries:[{tag:String, timeMillis:long, packageName:String, subject:String}, ...]}
```

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> param = new HashMap<>();
param.put("days", 1);
param.put("network", "all");
Map result = api.onEvent("QueryAppTraffic", param);
List<Map<String, Object>> apps = (List<Map<String, Object>>) ((Map) result.get("RESULT")).get("apps");
```

**广播通道**（TestBroadcast，与现有命令一致）：

```bash
adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast \
  -a ON_SQA_INTERFACE_TEST --es event QueryAppTraffic --es param '{"days":1}'
# 或 ./send_test_broadcast.sh QueryAppTraffic days=1
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── service/command/query/
│   ├── QueryUtils.java               # 新增：int 参数解析（Number/String 容错）
│   ├── QueryAppTraffic.java          # 新增：ASR-0139，NetworkStatsManager（模板/查询经反射，@SystemApi 不在公开 SDK）
│   ├── QueryAppBattery.java          # 新增：ASR-0372，exec dumpsys batterystats + 解析估计耗电段
│   ├── QueryAppRuntime.java          # 新增：ASR-0021，UsageStatsManager 按包聚合
│   ├── QueryRunningApps.java         # 新增：ASR-0022，getRunningAppProcesses 全量列表
│   └── QueryAppCrashInfo.java        # 新增：ASR-0023，DropBoxManager crash/anr/watchdog 记录
├── service/ApiBinder.java            # 注册 5 条命令
└── AndroidManifest.xml               # 新增 READ_LOGS / READ_NETWORK_USAGE_HISTORY / DUMP 声明

testapp/
├── src/main/java/com/hmdm/testapp/
│   ├── StatsQueryTestActivity.java   # 新增：统计查询测试页（5 个查询按钮 + 过滤输入框）
│   ├── TestActions.java              # 新增 5 个事件（含 network 参数校验）
│   └── MainActivity.java             # 增加"Stats query"入口
└── src/main/res/layout/activity_stats_query_test.xml  # 新增测试页布局
```

## 5. 查询逻辑

```
QueryAppTraffic(packageName?, days=1, network=all, limit=30):
  start = now - days*24h, end = now
  templates = network∈{wifi→[WIFI], mobile→[MOBILE_WILDCARD], all→两者}
  （NetworkTemplate 经反射构造，MATCH_* 常量运行时反射读取，失败回退 2/4）
  单应用：getApplicationInfo(packageName).uid → querySummaryForUid
  全应用：遍历已安装应用（含禁用/未安装状态）逐 uid 查询
  每个模板累加 rxBytes/txBytes → 按 totalBytes 降序 → limit 截断
  单 uid 内多包（sharedUserId）共享该 uid 全部流量（文档化限制）

QueryAppBattery(packageName?, limit=20):
  exec /system/bin/dumpsys batterystats（20s 超时，进程销毁兜底）
  定位 "Estimated power use (mAh):" 段：
    行匹配 ^\s*UID (\S+):\s+([0-9.]+) → {uidToken: mAh}
    遇到两空格缩进的下一顶层段（如 "  All kernel wake locks:"）→ 段结束
  解析 "Computed drain: N" 计算 percent
  uidToken → uid（"1000"→1000；"u0a98"→10098；"u0a40126"→40126）
  uid → 包名（getPackagesForUid 取第一个；无映射记 "uid:<token>"）
  按 powerMah 降序 → limit 截断

QueryAppRuntime(packageName?, days=7, limit=30):
  queryUsageStats(INTERVAL_BEST, start, end) → 按包聚合：
    totalTimeInForegroundMs = Σ getTotalTimeInForeground()
    lastTimeUsed = max
  按运行时长降序 → limit 截断；附 isInstalled

QueryRunningApps():
  getRunningAppProcesses() → 每进程 {pid, uid, processName, importance,
  importanceText, lru, packages[]}；按 importance 升序 + lru 升序

QueryAppCrashInfo(packageName?, limit=20):
  遍历 tag ∈ {data_app_crash, data_app_anr, system_app_crash,
  system_server_crash, watchdog, crash, anr}：
    getNextEntry(tag, 0) 迭代（time+1 推进），getText(64KB 截断)
  提取：crash 系→"Process: <pkg>" + "Subject:"/"Exception" 行；
        anr 系→"ANR in <pkg>" + "Reason:" 行；watchdog→"WATCHDOG KILLING SYSTEM PROCESS: <pkg>"
  按时间降序 → 可选按包过滤 → limit 截断
```

## 6. 权限与归属

- 归属：Launcher（MDM）。无 ROM 侧代码改动，无 root 依赖。
- 新增权限声明（均为签名级，平台签名自动授予）：
  - `android.permission.READ_LOGS`：DropBoxManager 读取 crash/anr 记录（签名|特权）；
  - `android.permission.READ_NETWORK_USAGE_HISTORY`：流量历史读取（签名|特权）；
  - `android.permission.DUMP`：batterystats dump 服务权限检查（签名）；
  - `PACKAGE_USAGE_STATS`（原已声明）：NetworkStatsManager/UsageStatsManager 数据访问（uid=1000 系统豁免 appop）。
- exec `dumpsys`：本 ROM plat_sepolicy.cil 允许 appdomain 对 `system_file` execute_no_trans（真机核验），system_app 域可直接执行 `/system/bin/dumpsys`，无需域迁移。
- 不修改 AIDL / lib 模块；testapp 无需新增权限。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| days/limit/network 参数非法 | days/limit 钳制到合法区间；network 非 all/wifi/mobile 返回 `{"error":"invalid parameter: network"}` |
| 单应用查询包不存在 | getApplicationInfo 抛异常 → 返回 `{"error": ...}`，不 crash |
| 流量查询权限/模板反射失败 | 单 uid 查询异常仅记录日志（该应用计 0），不中断整体；模板构造失败记日志 |
| sharedUserId 多包共享 uid | 各包均返回该 uid 汇总流量（文档化限制，匹配系统流量统计口径） |
| 流量/运行时历史数据缺失（重启/未轮询） | 返回 0 或仅含已有数据；文档提示统计窗口依赖系统 netstats/usage 轮询 |
| dumpsys 执行失败/超时（SELinux 变更等） | 返回 `{"error":"dumpsys batterystats execution failed"}`，20s 超时后 destroy 进程 |
| 无耗电数据的 uid（无包名映射） | 记 `uid:<token>` 仍返回，保证数据不丢失 |
| batterystats 段格式漂移（ROM 升级） | 解析器按行模式匹配，匹配不到时 apps 为空列表，不 crash；文档记录格式来源 |
| DropBox 读取权限缺失 | SecurityException → 返回 `{"error": ...}` |
| crash/anr 文本超 64KB | getText(64KB) 截断，解析前部关键行不受影响 |
| AIDL 通道返回自定义类 | 全部 JDK Map/List 结构（见第 2.3 节） |

## 8. 真机验证记录（2026-08-04，Android 13 / API 33 userdebug，平台签名 + device owner）

| 用例 | 结果 |
|---|---|
| 流量全应用查询（TC-0139-01） | 返回 30 项（默认 limit=30），按 totalBytes 降序；top 为 com.android.networkstack（uid 1073，rx 46585/tx 30981）；launcher（uid 1000）rx 311/tx 887 |
| 流量数值核验（TC-0139-02） | 与 `dumpsys netstats detail` 逐字节一致（uid=1000 FOREGROUND: rb=311 tb=887） |
| 单应用/天数/网络过滤（TC-0139-03/04/05） | packageName=com.hmdm.launcher 返回 1 项；days=7 network=wifi 正常；network=mobile 正常（本机无 SIM 流量为 0） |
| 非法入参（TC-0139-06/07/08） | network=bluetooth → `invalid parameter: network`；包不存在 → error NameNotFoundException；limit=3 → 3 项 |
| 耗电全应用查询（TC-0372-01/02） | computedDrainMah=640；UID 1000=3.23、UID 0=2.82、u0a98=0.0181 等与系统 Estimated power use 段逐一一致 |
| 耗电单应用/截断（TC-0372-03/04） | packageName=com.hmdm.launcher（uid 1000 共享 uid，按任一包匹配）→ 3.23 mAh/0.505%；limit=5 → 5 项降序 |
| 耗电异常入参（TC-0372-05/06） | 包不存在 → apps 空数组；数值非负，percent 合理 |
| 运行时长（TC-0021-01~04） | 默认 7 天降序：launcher 2683146ms、testapp 1740747ms；单应用/天数过滤正常 |
| 运行时长增长（TC-0021-05） | testapp 前台停留前后对比（会话内 1740747→1741069ms 增长；usage 统计周期性落盘，短窗口内不即时刷新，文档化） |
| 正在运行列表（TC-0022-01/02/04） | 20 个进程全量返回（含 system、launcher FGS、各应用），importance/importanceText/lru/packages 正确，排序正确 |
| 前台进程识别（TC-0022-03） | testapp 前台时 importance=100 FOREGROUND（pid 7716）；后台时为 SERVICE |
| 异常查询（TC-0023-01） | 默认返回 17 条历史（系统自带 com.mediatek.camera system_app_crash 等），按时间降序 |
| 构造 crash（TC-0023-02/03） | `am crash com.hmdm.testapp` 后查询：新增 data_app_crash 记录（subject=RemoteServiceException$CrashedByAdbException: shell-induced crash），按包过滤正确 |
| 无匹配（TC-0023-04/05） | 过滤不存在的包 → count=0；subject/packageName 与 `dumpsys dropbox` 一致 |
| 通用（TC-M-01/02/04） | 全部命令缺参可用；未知事件返回 unknown event；返回值全 JDK 类型 |

**部署与实现注意（本次实测踩坑）**：
1. `NetworkTemplate`、`NetworkStats.getTotalBucket()`/`getBucketIterator()` 均不在公开 SDK 中；且本 ROM 的 `NetworkStats` 只暴露旧版 `hasNextBucket()`/`getNextBucket(Bucket reuse)` 迭代 API（getTotalBucket/getBucketIterator 不存在，真机方法枚举核验）。实现经反射动态匹配：模板构造遍历所有首参为 int 的构造函数（5/3/2 参版本兼容），MATCH_* 常量运行时反射读取（回退 2/4），bucket 汇总先试 getBucketIterator 再回退旧 API。
2. 目标 ROM 的 `BatteryStatsImpl$Uid` 已移除 PowerProfile 估算方法（framework.jar 4 个 dex 逐一核验），进程内自算 mAh 不可行；`dumpsys batterystats` 的 "Estimated power use (mAh):" 段是唯一权威来源，数值与设置页一致。exec `/system/bin/dumpsys` 已核验 SELinux 允许（appdomain 对 system_file execute_no_trans）。
3. `dumpsys batterystats` 完整输出约 4MB/68k 行，exec 读取约 2~3s；在 ApiBinder 调用线程执行，testapp IPC 广播 10s 窗口内完成。
4. batterystats 段中 `u0a` 前缀 uid 需加 10000 偏移（u0a98→10098）；共享 uid（如 1000）下多包共享同一耗电/流量数值，按包过滤时按任一包匹配。
5. 设备要求同网络控制功能：`dumpsys duraspeed addwhitelist com.hmdm.testapp`（否则后台广播不投递）、屏幕常亮；本机 NotificationShade 偶发卡住遮挡前台（输入 swipe 可解除），非本功能问题。
