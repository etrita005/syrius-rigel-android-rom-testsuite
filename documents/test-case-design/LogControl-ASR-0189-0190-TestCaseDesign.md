# 系统日志缓冲区大小与日志级别管控（ASR-0189/0190）测试用例设计文档

## 1. 前置条件

| 项目 | 说明 |
|---|---|
| 设备 | Android 13（API 33）userdebug；`adb root` 可用（清理属性用）；平台签名 + device owner 部署 |
| Launcher | 平台签名（`sharedUserId="android.uid.system"`，uid=1000，SELinux 域 `system_app`）安装 `mdm-launcher-*.apk`；`dpm list-owners` 输出含 `DeviceOwner` |
| 测试 APP | 安装 `testapp-debug.apk`（`com.hmdm.testapp`），自动 bind Launcher `ApiService`（action `syrius.mdm.api_service`），日志显示"connected" |
| 触发方式 | testapp UI 按钮（binder 通道，日志区展示 RESULT）；或 `./send_test_broadcast.sh <命令名> key=value`（logcat TAG `HYX-MDM-APP` 可查执行记录） |
| 状态验证 | `adb shell logcat -g`（各缓冲区大小）、`adb shell getprop persist.logd.size[.main/system/crash]`（持久属性）、`adb shell getprop log.tag.<TAG>` / `persist.log.tag.<TAG>`（日志级别） |
| 已知限制 | ① 本 ROM logd 不消费 `persist.logd.size`，缓冲区大小重启后回 ROM 默认 256 KiB（运行时 `logcat -G` 设置即时生效）；② `logcat -p/-P` 为 UID 级 prune 规则，按 tag 设级别用属性机制 |

## 2. 用例表

### 2.1 ASR-0189 设置/获取缓冲区大小

| 编号 | 用例名称 | 前置/步骤 | 预期结果 |
|---|---|---|---|
| TC-0189-01 | 获取缓冲区大小 | 记录当前基线（默认 256 KiB）；testapp 点击"Get Log Buffer Sizes" | RESULT 为 4 条记录（main/system/crash/kernel），含 sizeBytes/consumedBytes/readableBytes；sizeBytes 与 `logcat -g` 一致 |
| TC-0189-02 | 设置全部缓冲区 | 输入 size=1M，buffer 留空，点击"Set Log Buffer Size" | RESULT=true；`logcat -g` 显示 4 个缓冲区均 1 MiB；`getprop persist.logd.size`=1048576 |
| TC-0189-03 | 设置单个缓冲区 | 输入 size=4M、buffer=main，点击"Set Log Buffer Size" | RESULT=true；仅 main=4 MiB，system/crash 不变；`getprop persist.logd.size.main`=4194304 |
| TC-0189-04 | 广播通道触发 | `send_test_broadcast.sh SetLogBufferSize size=512K`；`GetLogBufferSize` | logcat（TAG `HYX-MDM-APP`）输出命令执行记录；`logcat -g` 全部 512 KiB |
| TC-0189-05 | 非法 size | 广播 `--es param '{"size":"abc"}'`；再试 size=0、负数 | 拒绝（返回 false），缓冲区不变，不 crash；logcat 有 `LogUtils` invalid size 告警 |
| TC-0189-06 | 非法 buffer | 广播 `--es param '{"size":"1M","buffer":"kernel"}'` | 拒绝（返回 false），缓冲区不变；logcat 有 `invalid buffer: kernel` 告警 |
| TC-0189-07 | 设置后立即获取 | 接 TC-0189-03，点击"Get Log Buffer Sizes" | main 的 sizeBytes 立即反映新值（同步生效） |
| TC-0189-08 | 还原 | size=256K 全部设置 | 缓冲区回 256 KiB；`adb root` 后 `setprop persist.logd.size ''`（及 .main/.system/.crash）清除持久属性；`adb unroot` |

### 2.2 ASR-0190 设置 log 级别

| 编号 | 用例名称 | 前置/步骤 | 预期结果 |
|---|---|---|---|
| TC-0190-01 | 设置级别 V | testapp 输入 tag=HYX_MDM_TEST、level=V，点击"Set Log Level"；点击"Emit V/D logs with tag" | RESULT=true；`getprop log.tag.HYX_MDM_TEST`/`persist.log.tag.HYX_MDM_TEST`=V；`logcat -s HYX_MDM_TEST` 可见 V/D 日志 |
| TC-0190-02 | 查询级别 | 点击"Get Log Level" | RESULT=V |
| TC-0190-03 | 设置级别 S 抑制日志 | 改 level=S，点击"Set Log Level"；再点击"Emit V/D logs with tag" | RESULT=true；两属性=S；`logcat -s HYX_MDM_TEST` 无新增行（V/D 均被抑制） |
| TC-0190-04 | 查询级别（S） | 点击"Get Log Level" | RESULT=S |
| TC-0190-05 | 缺省级别 | 广播 `--es param '{"tag":"NOLEVEL"}'`（不传 level） | 按 I 处理；`getprop persist.log.tag.NOLEVEL`=I |
| TC-0190-06 | 非法 level | 广播 `--es param '{"tag":"BAD_TAG","level":"Q"}'` | 拒绝（返回 false），属性未写入；logcat 有 `invalid level` 告警 |
| TC-0190-07 | 非法 tag | 广播 `--es param '{"tag":"BAD TAG","level":"V"}'`（含空格） | 拒绝（返回 false），不 crash |
| TC-0190-08 | 未设置 tag 查询 | 广播 `GetLogLevel tag=UNSET_TAG` | RESULT 为空串 "" |
| TC-0190-09 | 恢复默认 | testapp 输入 level=R（或空），点击"Set Log Level" | 两属性被清除（`getprop` 为空），日志按默认级别输出 |
| TC-0190-10 | 广播通道触发 | `send_test_broadcast.sh SetLogLevel tag=HYX_MDM_TEST level=D` | logcat 输出执行记录；属性=D |

### 2.3 通用与边界

| 编号 | 用例名称 | 前置/步骤 | 预期结果 |
|---|---|---|---|
| TC-M-01 | 日志级别重启保持 | 设置 HYX_MDM_TEST=S、NOLEVEL=I 后重启设备 | 属性保持；`log -p d -t HYX_MDM_TEST x` 与 `log -p v -t NOLEVEL x` 均不可见，`log -p i -t NOLEVEL x` 可见（logd 层按属性过滤） |
| TC-M-02 | 缓冲区大小重启行为 | 设置 size=512K（写 persist.logd.size）后重启设备 | `getprop persist.logd.size`=524288 保持；但 `logcat -g` 回 256 KiB（本 ROM logd 不消费该属性，已知限制，见设计文档 2.3） |
| TC-M-03 | 缓冲区/日志级别互不干扰 | 仅改缓冲区后查 tag 级别，反之亦然 | 两功能状态独立 |
| TC-M-04 | device owner 保持 | 重启后 `dpm list-owners` | `User 0: admin=com.hmdm.launcher/.AdminReceiver,DeviceOwner,Affiliated` |
| TC-M-05 | 未连接 Launcher 场景 | 断开后点击任意命令 | 命令入队不 crash；连接后自动重放并输出 RESULT |

## 3. 验证要点提示

- 优先以设备状态（`logcat -g`、`getprop`）复核实际效果；testapp 日志区（底部 TextView）与 `logcat -s MdmApiClient` 展示每条命令的 RESULT。
- `GetLogBufferSize` 返回值为标准 Map 结构（buffer/sizeBytes/consumedBytes/readableBytes）；自定义类经 AIDL Map 通道跨进程会反序列化失败（实测踩坑，见设计文档第 8 节）。
- 日志级别抑制验证建议配合 `adb shell log -p <级别> -t <TAG> <msg>` 直测 logd 层过滤（liblog 客户端过滤与 logd 过滤两级均生效）。
- 恢复默认用 level=R（或空值）：Android 属性写空串即删除属性。
- 清理残留：`adb root` 后 `setprop persist.logd.size ''` 等删除持久属性，`adb unroot` 还原；用例结束恢复缓冲区 256 KiB。
