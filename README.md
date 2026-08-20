# syrius-rigel-android-rom-testsuite

## 1. 项目简介

**二供手机**（Second-Supplier Phone）= 机器人即将使用的 Android 组件（"二供"= 第二供应商）。本仓库用于对二供手机的 **ROM** 与自研 **MDM** 进行验证与测试。

### 机器人双系统架构背景

- 机器人操作系统由 **Linux + Android** 组成，两者可互相通信：
  - **Android → Linux**：Android 通过 SSH 访问 Linux（拥有 sudo 权限）；
  - **Linux → Android**：Linux 通过 ADB Shell 访问 Android；
  - 两者通过 **USB AoA（Android Open Accessory）** 连接。

### 项目目标

1. 验证供应商提供的 ROM 是否满足需求（见 `documents/安卓系统软件需求.md`）；
2. 验证自研 MDM（`mdm_launcher/hmdm-android`，即 Launcher 应用）是否满足项目需求。

## 2. 目录结构

| 路径 | 说明 |
| --- | --- |
| `documents/安卓系统软件需求.md` | 系统软件需求来源（**权威需求**，ASR-0001 ~ ASR-0477，共 466 项有效需求） |
| `documents/Sheet1需求完成情况对照.md` | MDM 需求完成情况对照表（**权威完成状态**：已完成 256 / 部分完成 13 / 未完成 197） |
| `documents/requirements-rationality-analysis.md` | 面向 Android 13（API 33）的需求合理性审查 |
| `documents/system_configurations.md` | MDM 功能实现中写入系统配置文件内容的记录（应用侧声明、DPM 策略状态、Settings 键等） |
| `documents/TestApp-UsageGuide.md` | testapp 使用指南（IPC 事件 / AIDL 接口说明） |
| `documents/technical-documentation/*.md` | MDM 各功能模块技术设计文档（按 ASR 需求 ID 命名） |
| `documents/test-case-design/*.md` | 测试用例设计文档（与技术设计文档一一对应） |
| `mdm_launcher/hmdm-android/` | MDM Launcher 与 testapp 源码；`send_test_command.sh` / `send_test_broadcast.sh` 测试通道脚本；`testapp-signed.apk` |
| `test_case/` | 生成的完整测试用例 Markdown 输出目录（如不存在则创建） |
| `CLAUDE.md` | 本仓库的工程协作规范（需求处理、文档一致性、测试用例编写约束、测试执行纪律等） |

## 3. 需求完成情况

| 判定 | 数量 | 占比 |
| --- | --- | --- |
| 已完成 | 256 | 54.9% |
| 部分完成 | 13 | 2.8% |
| 未完成 | 197 | 42.3% |
| 合计（有效需求） | 466 | 100% |

> 最新统计与逐项状态以 [Sheet1需求完成情况对照.md](documents/Sheet1需求完成情况对照.md) 为准。

## 4. 目标设备与测试环境

- **目标设备**：二供手机，Android 13（API 33）userdebug，MTK fork 版 AOSP 13（MT6771），示例序列号 `P65241284D50911SC00`；
- **MDM 部署形态**：`com.hmdm.launcher`（平台签名，uid=1000，device owner）；`com.hmdm.testapp`（testapp）为测试 IPC 客户端（普通应用，与 Launcher 同平台密钥签名）；
- **测试通道**：`./send_test_command.sh <event> [key=value ...]`（内部为 `am broadcast -n com.hmdm.testapp/.TestCommandReceiver -a com.hmdm.testapp.CMD --es event <E> --es param '<json>'`），结果通过 `Broadcast completed: result=0, data=<json>` 与 logcat tag `HYX-TESTAPP-CMD` 双重确认；
- **Windows 运行脚本**：WSL/bash 可用（`C:\Users\Administrator\AppData\Local\Microsoft\WindowsApps\bash.exe`），通过 `ADB=<Windows adb 绝对路径>` 覆盖脚本默认 adb 路径（已验证可用）。

### 已知平台/版本限制（已验证）

- **testapp v1.0（versionCode=1）缺失部分事件**：已安装版本缺少技术文档中描述的许多事件（GrantAllRuntimePermission、CheckPermissionsLocal、GrantOverlay、CheckGrantOverlay、TryShowOverlay、GetZenMode、SetZenMode 直接广播等），返回 `unknown event`。**执行测试用例前请先运行 `ListEvents` 验证事件可用性**；技术/设计文档可能与设备代码存在偏差——**以设备上实际验证结果为准**。

## 5. 测试通道与测试 App

testapp（`com.hmdm.testapp`）是 MDM Launcher 各需求功能的真机验证工具，具有**双重入口**：

1. **UI 入口**：按功能集合划分的测试 Activity（人工点按验证）；
2. **IPC 入口**：adb shell 广播命令，与 UI 按钮**完全等效**，供自动化测试（无需模拟点击）。

IPC 与 UI 共用同一测试引擎 `TestActions.execute()`，行为、参数、返回值完全一致。

### 快速开始

```bash
# 1. 探测连接（bound 状态）
./send_test_command.sh GetConnectionStatus

# 2. 获取完整事件目录（验证事件可用性，重要）
./send_test_command.sh ListEvents

# 3. 执行具体功能事件
./send_test_command.sh SetNotificationsPolicyMode mode=1
./send_test_command.sh SetNotificationsWhitelist 'packageNames=["com.hmdm.testapp"]'
```

### 注意事项

- **testapp 为普通应用**，直接 `adb install -r` 即可（Launcher 安装白名单已含 `com\.hmdm\..*`，不会被自动卸载）；
- 测试期间**不要对 testapp 执行 `am force-stop`**——本 ROM MTK DuraSpeed 会把 force-stop 的应用加入静态 receiver 抑制名单（广播被 AMS 丢弃，IPC 失效，需重启设备清空），进程重启用 `adb root` + `kill <pid>`；
- 完整用法见 [TestApp-UsageGuide.md](documents/TestApp-UsageGuide.md)。

## 6. 常用文档速查

- 需求源头：[安卓系统软件需求.md](documents/安卓系统软件需求.md)
- 完成状态对照：[Sheet1需求完成情况对照.md](documents/Sheet1需求完成情况对照.md)
- 需求合理性分析：[requirements-rationality-analysis.md](documents/requirements-rationality-analysis.md)
- 系统配置写入记录：[system_configurations.md](documents/system_configurations.md)
- testapp 使用指南：[TestApp-UsageGuide.md](documents/TestApp-UsageGuide.md)
- 功能实现细节：`documents/technical-documentation/`（按 ASR ID 命名）
- 测试用例设计：`documents/test-case-design/`（与技术设计文档一一对应）

## 7. 文档一致性说明

本仓库文档之间需保持一致性（需求文档、设计文档、测试用例设计文档、使用手册与 README）。若发现不一致，将同步更新 README 及相关文档。
