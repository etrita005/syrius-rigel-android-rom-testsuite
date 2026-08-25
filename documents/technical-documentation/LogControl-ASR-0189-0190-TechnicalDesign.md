# 系统日志缓冲区大小与日志级别管控（ASR-0189/0190）技术设计文档

## 1. 需求概述

| 需求 ID | 子功能项 | 需求语义 |
|---|---|---|
| ASR-0189 | 设置/获取缓冲区大小 | 设置/获取系统 logcat 环形缓冲区（main/system/crash）大小 |
| ASR-0190 | 设置 log 级别 | 按 tag 设置系统日志级别（V/D/I/W/E/F/S），并支持查询 |

**归属**：Launcher（MDM）+ 系统 API。需求文档备注：ASR-0189「平台签名应用执行 `logcat -G/-g`（logd 服务）或读写 `persist.logd.size` 属性实现」；ASR-0190「可通过系统 API 实现：`persist.log.tag.<TAG>` 属性或 `logcat -p` 设置按 tag 日志级别」。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名（`sharedUserId=android.uid.system`，uid=1000）+ device owner 部署。

## 2. 技术选型与可行性核验（真机，2026-08-03）

### 2.1 进程 SELinux 域（真机核验）

`ps -Z` 实测 Launcher 运行域为 `u:r:system_app:s0`（sharedUserId=android.uid.system 触发 seapp_contexts 的 `system_app` 域）。反查设备编译策略 `/system/etc/selinux/plat_sepolicy.cil`，`system_app` 具备：

- `(allow system_app log_tag_prop (property_service (set)))` —— 可写 `log.tag.*` / `persist.log.tag.*`
- `(allow system_app logd_prop (property_service (set)))` —— 可写 `persist.logd.size` / `logd.size.*`
- `(allow system_app logd (unix_stream_socket (connectto)))` + `(allow system_app logd_socket (sock_file (write)))` —— 可连接 logd 控制 socket
- `(allow appdomain logcat_exec (file (... execute_no_trans)))` —— 可执行 logcat 二进制

结论：需求备注中的两条路径（logcat 控制命令、persist 属性）在本 ROM 的 SELinux 层面均允许。

### 2.2 logcat 命令行为（真机核验）

`logcat -h` 实测确认：

- `-g, --buffer-size`：获取 logd 环形缓冲区大小；`-G <size>`：设置（可用 K/M 后缀，可配 `-b` 按缓冲区独立设置）；
- `-p, --prune` / `-P '<list>'`：**按 UID 的 prune 规则**（noisiest UID 裁剪策略），**并非按 tag 设置日志级别**。因此需求备注中「`logcat -p` 设置按 tag 日志级别」在本 ROM 不成立，ASR-0190 采用属性机制实现（见 2.4）。

### 2.3 ASR-0189 实现选型

- **设置（立即生效）**：`logcat -G <size> [-b <buffer>]`（logd 控制 socket，即时生效）；
- **设置（重启保持）**：写 `persist.logd.size[.<buffer>]` 属性（字节数）；
- **获取**：解析 `logcat -g` 输出（权威，反映运行时真实值）。

**真机核验发现的本 ROM 限制**：反查 `/system/bin/logd` 二进制字符串，本 ROM logd **不消费** `persist.logd.size`（仅含 `persist.logd.filter`/`persist.logd.security`）。实测：设置 `persist.logd.size=524288` 后重启，缓冲区恢复 ROM 默认 256 KiB（该属性存活但被 logd 忽略）。因此**重启保持在本 ROM 不可用**（属 ROM 侧缺口），`logcat -G` 运行时生效路径正常。如需重启保持，需 Launcher 启动流程（如 BootCompletedReceiver）重放 `logcat -G`，列为后续增强。

### 2.4 ASR-0190 实现选型

采用 Android 标准按 tag 日志级别机制：

- `log.tag.<TAG>` 属性（liblog 客户端即时检查）+ `persist.log.tag.<TAG>` 属性（logd LogTags 过滤 + 重启保持）；
- logd 二进制含 `persist.log.tag` 引用，且实测 logd 层按属性过滤生效（见第 8 节验证记录）；
- 不采用 `logcat -P`（本 ROM 为 UID 级 prune 规则，语义不符）。

## 3. 命令接口定义（SystemApiInterface.onEvent）

命令通过 `ApiBinder.method2Commands` 注册，`CallWithCommand` 包装调用，返回 `Map{"RESULT": 返回值}`。不修改 `SystemApiInterface.aidl` / `lib` 模块，客户端无需升级协议。

| 命令名 | 参数（Map） | 返回 RESULT | 说明 |
|---|---|---|---|
| `SetLogBufferSize` | size: String（必填，如 "16M"/"1M"/"512K"/字节数）；buffer: String（可选，main/system/crash/all，默认 all） | Boolean | ASR-0189 设置缓冲区大小：`logcat -G` 立即生效 + 写 `persist.logd.size[.<buffer>]` 持久属性 |
| `GetLogBufferSize` | - | List\<Map\>（buffer/sizeBytes/consumedBytes/readableBytes） | ASR-0189 解析 `logcat -g` 返回各缓冲区信息 |
| `SetLogLevel` | tag: String（必填）；level: String（可选，V/D/I/W/E/F/S，默认 I；"R"/"RESET"/空 恢复默认） | Boolean | ASR-0190 设置 tag 日志级别：`log.tag.<TAG>`（即时）+ `persist.log.tag.<TAG>`（持久） |
| `GetLogLevel` | tag: String（必填） | String | ASR-0190 查询 tag 日志级别，未设置返回 "" |

**返回结构说明**：`GetLogBufferSize` 返回标准 `java.util.Map`/`Long` 结构而非自定义 Serializable Bean。真机实测：自定义 Bean（如 `LogBufferInfoBean`）经 AIDL `Map` 通道返回时，客户端（testapp）反序列化抛 `ClassNotFoundException`（Bean 类不在客户端 classpath）；改用 HashMap/Long 等两端均可加载的 JDK 类后跨进程返回正常。

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> param = new HashMap<>();
param.put("size", "4M");
param.put("buffer", "main");
Map result = api.onEvent("SetLogBufferSize", param);
boolean ok = (Boolean) result.get("RESULT");
```

```java
Map result = api.onEvent("GetLogBufferSize", new HashMap<String, Object>());
List<Map<String, Object>> buffers = (List<Map<String, Object>>) result.get("RESULT");
```

**广播通道**（TestBroadcast，与现有命令一致）：

```bash
adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast \
  -a ON_SQA_INTERFACE_TEST --es event SetLogBufferSize --es param '{"size":"1M"}'
# 或 ./send_test_broadcast.sh SetLogBufferSize size=1M
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/ShellUtils.java                       # 新增：sh -c 执行并捕获输出（超时保护、防管道阻塞）
├── utils/LogUtils.java                         # 新增：SystemProperties 反射(get/set) + 缓冲区大小/日志级别逻辑
├── service/command/log/
│   ├── GetLogBufferSize.java                   # 新增：logcat -g 解析 → List<Map>
│   ├── SetLogBufferSize.java                   # 新增：logcat -G + persist.logd.size 属性
│   ├── SetLogLevel.java                        # 新增：log.tag.<TAG> + persist.log.tag.<TAG>
│   └── GetLogLevel.java                        # 新增：属性回读
├── service/ApiBinder.java                      # 注册 4 条命令
└── activity/LogMgActivity.java                 # 新增：手动验证管理页

app/src/main/res/layout/activity_log_mg.xml     # 新增：管理页布局
app/src/main/res/layout/activity_test.xml       # TestMainActivity 增加"系统日志管控"入口
app/src/main/java/com/hmdm/launcher/syrius/activity/TestMainActivity.java
app/src/main/AndroidManifest.xml                # 注册 LogMgActivity（exported=false）

testapp/                                        # 测试 APP 增加日志管控测试分区
└── src/main/
    ├── java/com/hmdm/testapp/MainActivity.java # 4 条命令按钮 + Emit V/D 日志按钮
    └── res/layout/activity_main.xml
```

## 5. 策略逻辑

```
SetLogBufferSize(size, buffer=all):
  size 解析（K/KB/KiB/M/MB/MiB/G/GB/GiB/纯字节）→ bytes；非法/≤0 → false
  buffer 非 all 且非 main/system/crash → false
  sh -c "logcat -G <size> [-b <buffer>]"          # 立即生效（logd 控制 socket）
  输出含 "logcat:" 错误 → false
  SystemProperties.set("persist.logd.size[.<buffer>]", bytes)   # 重启保持意图（本 ROM logd 不消费，见 2.3）
  返回 true

GetLogBufferSize():
  sh -c "logcat -g" → 正则解析每行 "buffer: ring buffer is X (Y consumed, Z readable), ..."
  返回 List<Map{buffer, sizeBytes, consumedBytes, readableBytes}>

SetLogLevel(tag, level=默认 I):
  tag 规范化（剥离 persist.log.tag./log.tag. 前缀；校验 [A-Za-z0-9_.-]+）→ 非法 false
  level 规范化（V/D/I/W/E/F/S 单字符或全称；R/RESET/空 → 清除；非法 → false）
  R/RESET/空：set("log.tag.<TAG>","") + set("persist.log.tag.<TAG>","")   # 空值=删除属性
  其他：set("log.tag.<TAG>", level) + set("persist.log.tag.<TAG>", level)
  返回 true/false

GetLogLevel(tag):
  先读 persist.log.tag.<TAG>，为空回退 log.tag.<TAG>；未设置返回 ""
```

## 6. 权限与归属

- 归属：Launcher（MDM）+ 系统 API。无 ROM 侧代码改动。
- 依赖平台签名（uid=1000 → `system_app` SELinux 域）：
  - `logcat -g/-G`：logd 控制 socket 连接（`system_app` 显式 allow）；
  - 属性写入：`log_tag_prop` / `logd_prop` 的 `property_service set`（`system_app` 显式 allow）。
- 不依赖 device owner（与麦克风禁用类似）；不修改 AIDL / lib 模块；不新增 manifest 权限。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| size 非法（"abc"/"1.5M"/0/负数） | 返回 false，Logger 记录，不 crash |
| buffer 非法（如 kernel/radio） | 返回 false（本 ROM 仅 main/system/crash 可配），Logger 记录 |
| tag 非法（空/含空格/超长） | 返回 false，Logger 记录 |
| level 非法（如 "Q"/"X"） | 返回 false，Logger 记录；缺省按 "I" 处理 |
| tag 带属性前缀（persist.log.tag.X / log.tag.X） | 自动剥离前缀后处理 |
| 设置后立即查询 | 状态一致（logcat -G 同步生效；属性同步生效） |
| logcat 命令超时/异常 | ShellUtils 超时销毁进程，返回 null/false，不 crash |
| SystemProperties 反射不可用 | 属性读写返回失败，不影响 logcat -G/-g 路径 |
| 重启持久化 | `persist.log.tag.*` 重启保持且 logd 生效；`persist.logd.size` 属性存活但**本 ROM logd 不消费**，缓冲区回 ROM 默认 256 KiB（ROM 限制，见 2.3；后续可在 BootCompletedReceiver 重放 `logcat -G` 补偿） |
| AIDL 通道返回自定义类 | 客户端无法反序列化（ClassNotFoundException 实测）→ 统一用 JDK Map/包装类结构 |

## 8. 真机验证记录（2026-08-03，Android 13 / API 33 userdebug，平台签名 + device owner）

| 用例 | 结果 |
|---|---|
| 设置缓冲区 size=1M（广播） | RESULT=true；`logcat -g`：main/system/crash/kernel 均 1 MiB；`persist.logd.size=1048576` |
| 设置缓冲区 size=4M buffer=main（testapp UI） | RESULT=true；仅 main=4 MiB，system/crash 不变；`persist.logd.size.main=4194304` |
| 获取缓冲区（testapp UI binder 通道） | `RESULT=[{consumedBytes=..., buffer=main, readableBytes=..., sizeBytes=1048576}, {system...}, {crash...}, {kernel...}]` 4 条完整 |
| 非法 size "abc" | 拒绝（返回 false，状态不变），Logger 记录 invalid size |
| 非法 buffer "kernel" | 拒绝，Logger 记录 `invalid buffer: kernel` |
| 设置日志级别 V（广播 + UI） | RESULT=true；`log.tag.HYX_MDM_TEST=V`、`persist.log.tag.HYX_MDM_TEST=V`；testapp Emit 的 V/D 日志在 `logcat -s HYX_MDM_TEST` 可见 |
| 设置日志级别 S（UI） | RESULT=true；两属性=S；再次 Emit 后 `logcat -s HYX_MDM_TEST` 无新增行（抑制生效） |
| GetLogLevel 回读 | RESULT=V → RESULT=S，与属性一致 |
| 缺省 level（不传 level 参数） | 按 I 处理：`persist.log.tag.NOLEVEL=I` |
| 非法 level "Q" | 拒绝，Logger 记录 `invalid level: Q` |
| 重启持久化（log 级别） | 重启后 `persist.log.tag.HYX_MDM_TEST=S`、`persist.log.tag.NOLEVEL=I` 保持；`log -p` 直测：HYX_MDM_TEST 任意级别均被 logd 丢弃（S），NOLEVEL 仅 I 及以上可见（I 阈值生效）——证明 logd 层按属性过滤 |
| 重启持久化（缓冲区） | `persist.logd.size=524288` 属性重启后存活，但缓冲区回 256 KiB——**本 ROM logd 不消费 persist.logd.size**（二进制反查确认，见 2.3），记录为 ROM 限制 |
| device owner 保持 | 重启后 `dpm list-owners`：`User 0: admin=com.hmdm.launcher/.AdminReceiver,DeviceOwner,Affiliated` |
| 环境还原 | 恢复缓冲区 256 KiB；清除 persist.logd.size* 与 log.tag/persist.log.tag 测试属性（adb root setprop 空值）；adbd 还原非 root |

**部署与实现注意（本次实测踩坑）**：
1. **AIDL 通道返回自定义 Serializable Bean 会跨进程反序列化失败**（testapp 无该类）：`GetLogBufferSize` 初版返回 `List<LogBufferInfoBean>`，客户端抛 `Parcelable encountered ClassNotFoundException`。改为返回 `List<Map<String,Object>>`（JDK 类型）后正常——文档记录为通用约束，后续命令设计应避免在返回值中使用自定义类。
2. **`logcat -p/-P` 在本 ROM 是按 UID 的 prune 规则**，不是按 tag 设置日志级别；ASR-0190 采用 `log.tag.*`/`persist.log.tag.*` 属性机制（Android 标准按 tag 级别机制，logd 二进制含 `persist.log.tag` 引用，实测 logd 层过滤生效）。
3. **`persist.logd.size` 在本 ROM 不被 logd 消费**（logd 二进制反查仅含 `persist.logd.filter`/`persist.logd.security`），缓冲区大小无法跨重启保持——属 ROM 侧缺口，运行时设置（`logcat -G`）不受影响；如需重启保持，可在 Launcher 启动流程重放 `logcat -G`（后续增强）。
4. 属性写空值（`setprop name ''`）可删除属性，用于"恢复默认"语义。
