# 通知管控（ASR-0053/0054/0055/0057）测试用例设计文档

## 1. 前置条件

| 项目 | 说明 |
|---|---|
| 设备 | Android 8.0+（API 26+）验证 testapp；按包通知管控功能需 **API 28+**（Android 9+） |
| Launcher | 平台签名（`sharedUserId=android.uid.system`）安装 `mdm-launcher-*.apk`，建议同时为 deviceOwner |
| 测试 APP | 安装 `testapp-debug.apk`（`com.hmdm.testapp`），首次进入后点击"Request POST_NOTIFICATIONS permission"并允许 |
| 连接 | testapp 自动 bind Launcher `ApiService`（action `syrius.mdm.api_service`），日志显示"connected"；未连接时命令进入队列，连接成功后自动重放 |
| 备选触发方式 | `adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast -a ON_SQA_INTERFACE_TEST --es event <命令名> --es param '<json>'`，或用 `./send_test_broadcast.sh <命令名> packageName=xxx enabled=false` |
| 测试目标包 | testapp 自身 `com.hmdm.testapp`（推荐，可自证通知）；另可指定第三方应用 |

## 2. 用例表

### 2.1 ASR-0053 是否允许指定应用发送通知

| 编号 | 用例名称 | 前置/步骤 | 预期结果 |
|---|---|---|---|
| TC-0053-01 | 禁用指定包通知 | 1. testapp 输入包名 `com.hmdm.testapp` 点击"Disable target package notifications"；2. 点击"Send own test notification" | RESULT=true；通知栏不出现 testapp 通知（系统设置-应用-通知中该包通知开关为关） |
| TC-0053-02 | 启用指定包通知 | 接 TC-0053-01，点击"Enable target package notifications"后发通知 | RESULT=true；通知栏正常出现测试通知 |
| TC-0053-03 | 查询通知状态（禁用态） | 禁用后点击"Query target package notification status" | RESULT=false（或按设备差异为 AppOps 近似结果） |
| TC-0053-04 | 查询通知状态（启用态） | 启用后点击"Query target package notification status" | RESULT=true |
| TC-0053-05 | 异常入参：空包名 | 包名留空点击"Disable target package notifications" | RESULT=false，不 crash |
| TC-0053-06 | 异常入参：未安装包 | 输入不存在的包名点击"Query"/"Disable" | RESULT=false，不 crash |
| TC-0053-07 | 还原 | 测试结束将 testapp 通知恢复为启用 | 通知开关恢复，后续用例不受影响 |

### 2.2 ASR-0054 发送通知的应用白名单

| 编号 | 用例名称 | 前置/步骤 | 预期结果 |
|---|---|---|---|
| TC-0054-01 | 设置白名单并启用模式 | 1. 白名单输入 `com.hmdm.testapp`，点击"Set whitelist"；2. 策略模式输入 1 点击"Set policy mode (apply now)" | 两步 RESULT=true；白名单查询（"Get whitelist"）返回 [com.hmdm.testapp] |
| TC-0054-02 | 名单内应用可发通知 | 接 TC-0054-01，testapp 发送自身测试通知 | 通知正常展示 |
| TC-0054-03 | 名单外应用不发通知 | 接 TC-0054-01，在名单外应用（如浏览器/设置）触发其通知 | 该应用通知被抑制；查询其状态 RESULT=false |
| TC-0054-04 | 白名单模式套用全部应用 | 点击"Apply policy to all apps" | RESULT 为受影响包数（int），白名单外应用通知均被禁用 |
| TC-0054-05 | 新装应用自动套用（白名单） | 白名单模式开启时安装一个非名单内 APK | 新装应用通知自动被禁用（可通过 adb logcat 观察 PackageChangedReceiver 日志） |
| TC-0054-06 | 白名单追加后新装应用可发 | 将新装应用加入白名单并重新套用，发通知 | 通知正常展示 |
| TC-0054-07 | 异常入参：空名单 | 白名单留空点击"Set whitelist" | RESULT=true（清空名单），白名单模式后全部应用禁用通知 |
| TC-0054-08 | 还原 | 策略模式输入 0 点击"Set policy mode (apply now)" | 全部应用通知恢复允许（关闭策略不套用，仅停止管控） |

### 2.3 ASR-0055 发送通知的应用黑名单

| 编号 | 用例名称 | 前置/步骤 | 预期结果 |
|---|---|---|---|
| TC-0055-01 | 设置黑名单并启用模式 | 1. 黑名单输入 `com.hmdm.testapp`，点击"Set blacklist"；2. 策略模式输入 2 点击"Set policy mode (apply now)" | 两步 RESULT=true；黑名单查询返回 [com.hmdm.testapp] |
| TC-0055-02 | 名单内应用不发通知 | 接 TC-0055-01，testapp 发送自身测试通知 | 通知被抑制，不展示 |
| TC-0055-03 | 名单外应用可发通知 | 接 TC-0055-01，非名单内应用发通知 | 通知正常展示 |
| TC-0055-04 | 黑名单模式套用全部应用 | 点击"Apply policy to all apps" | RESULT 为受影响包数，黑名单内应用通知被禁用 |
| TC-0055-05 | 新装应用自动套用（黑名单） | 黑名单模式开启时安装一个 APK | 新装应用不在名单内，通知保持允许（自动套用不产生禁用） |
| TC-0055-06 | 系统应用黑名单 | 黑名单加入某系统应用并套用 | 该系统应用通知被禁用（全量管控预期行为） |
| TC-0055-07 | 异常入参：空名单 | 黑名单留空点击"Set blacklist" | RESULT=true（清空），黑名单模式退化为全部允许 |
| TC-0055-08 | 还原 | 策略模式输入 0 | 管控停止，通知恢复 |

### 2.4 ASR-0057 是否禁用锁屏时通知

| 编号 | 用例名称 | 前置/步骤 | 预期结果 |
|---|---|---|---|
| TC-0057-01 | 禁用锁屏通知 | testapp 点击"Disable lockscreen notifications"，锁屏后触发通知 | RESULT=true；锁屏界面不显示任何通知 |
| TC-0057-02 | 查询锁屏通知状态（禁用态） | 接 TC-0057-01 点击"Query lockscreen notification status" | RESULT=true |
| TC-0057-03 | 启用锁屏通知 | 点击"Enable lockscreen notifications"，锁屏后触发通知 | RESULT=true；锁屏界面恢复显示通知 |
| TC-0057-04 | 查询锁屏通知状态（启用态） | 点击"Query lockscreen notification status" | RESULT=false |
| TC-0057-05 | 真机验收点 | 厂商 ROM 不响应 `lock_screen_show_notifications` 时 | 命令返回 putInt 结果，实际效果以真机为准（可在设置-通知中复核），文档记录为已知厂商差异 |

### 2.5 策略模式切换与通用

| 编号 | 用例名称 | 前置/步骤 | 预期结果 |
|---|---|---|---|
| TC-M-01 | 非法模式入参 | 策略模式输入 3 或留空点击"Set policy mode (apply now)" | RESULT=false（非法值拒绝），不 crash |
| TC-M-02 | 模式 0 关闭策略 | 白名单/黑名单模式后设置模式 0 | RESULT=true；不自动套用，已禁用包通知仍为禁用状态（直到手动启用或重新套用） |
| TC-M-03 | 模式切换 1→2 | 白名单模式直接切黑名单模式 | 立即套用，黑名单生效 |
| TC-M-04 | 模式重启保持 | 设置模式后重启设备，点击"Get policy mode" | 模式值保持（SharedPreferences 持久化） |
| TC-M-05 | 断开 Launcher 场景 | 未连接时点击任意命令 | 命令入队不 crash；连接后自动重放并输出 RESULT |
| TC-M-06 | 广播通道触发 | 用 `send_test_broadcast.sh GetNotificationsPolicyMode` 调用 | logcat 输出对应命令与 RESULT |

## 3. 验证要点提示

- testapp 日志区（底部 TextView）展示每次命令的 RESULT 与连接状态，优先以系统设置（设置-应用-通知）复核实际开关。
- 白/黑名单输入支持中英文逗号分隔，全量替换语义。
- 套用策略对系统应用同样生效，属预期全量管控行为。
- Launcher 自身（`com.hmdm.launcher`）被策略排除，其前台服务通知不受管控。
- 按包管控依赖 API 28+；低版本设备命令返回 false，锁屏管控（Settings.Secure）与黑白名单存储仍可用。
