# 移动网络设置限制（ASR-0278）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 底层机制 |
|---|---|---|---|
| ASR-0278 | 禁止/允许用户改变移动网络设置 | 禁止用户进入/修改移动网络设置（设置页受限） | device owner `DISALLOW_CONFIG_MOBILE_NETWORKS` 用户限制 |

**归属**：「Launcher（MDM）」（device owner 公开接口；与 APN 批次共用 ApnManager 引擎）。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署。

## 2. 技术选型与可行性核验

| 方案 | 说明 | 结论 |
|---|---|---|
| `DISALLOW_CONFIG_MOBILE_NETWORKS` 用户限制 | device owner `addUserRestriction`/`clearUserRestriction`（ApnManager.disallow/allowConfigMobileNetworks），设置页受限（"Blocked by your IT admin"） | **采用** |

- 命令：`DisallowConfigMobileNetworks`（参数 **disallow**，Boolean 必填）与 `IsDisallowConfigMobileNetworks`（读回核对）；
- **2026-08-13 测试事件参数核对**：该命令参数名为 `disallow`（非 `disabled`——testapp 事件首版误用 disabled 导致命令收到默认 false、限制未生效，已修正并记录）；真机往返一致（disallow=true → Is=true；false → Is=false）。

## 3. 命令接口定义

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `DisallowConfigMobileNetworks` | disallow（Boolean，必填） | Boolean | ASR-0278 |
| `IsDisallowConfigMobileNetworks` | 无 | Boolean | ASR-0278 |

**调用示例**：

```java
Map<String, Object> p = new HashMap<>();
p.put("disallow", true);
Map result = api.onEvent("DisallowConfigMobileNetworks", p);
// {"RESULT":true}；IsDisallowConfigMobileNetworks → true
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/
├── syrius/utils/ApnManager.java                   # disallow/allow/isConfigMobileNetworksDisallowed
├── syrius/service/command/apn/                    # DisallowConfigMobileNetworks / IsDisallowConfigMobileNetworks（既有）
└── syrius/service/ApiBinder.java                  # 既有注册

testapp/
└── src/main/java/com/hmdm/testapp/
    ├── TestActions.java                           # 两个事件（参数 disallow）
    ├── DeviceStateTestActivity.java               # 扩展：移动网络设置限制按钮组
    └── src/main/res/layout/activity_device_state_test.xml
```

## 5. 执行逻辑

```
DisallowConfigMobileNetworks(disallow):
  disallow=true → addUserRestriction(DISALLOW_CONFIG_MOBILE_NETWORKS)
  disallow=false → clearUserRestriction(...)
IsDisallowConfigMobileNetworks(): getUserRestrictions 读回
```

**安全设计**：调用方经 ApiBinder 门禁；参数仅 Boolean。

## 6. 权限与归属

- device owner 公开 addUserRestriction/clearUserRestriction（无需权限声明）；
- testapp：无新增权限；无 ROM 侧代码改动，无 root 依赖。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺参（disallow） | testapp 侧返回 missing parameter，不 crash |
| 参数名误用（disabled） | 命令按 disallow 读取；测试事件已修正（2026-08-13 记录） |
| 非 DO 环境 | addUserRestriction 异常捕获返回 true（命令既有实现；查询如实）——文档记录 |

## 8. 真机验证记录（2026-08-13，Android 13 / API 33 userdebug，平台签名 + device owner）

| 核验项 | 结果 |
|---|---|
| 基线 | IsDisallowConfigMobileNetworks=false |
| 禁止 | DisallowConfigMobileNetworks(disallow=true) → true；Is=true |
| 恢复 | disallow=false → true；Is=false（往返一致，无残留） |
| 参数修正 | 首版 testapp 事件误传 disabled（命令默认 false、限制未生效）；修正为 disallow 后生效（记录） |
| 测试后状态 | 限制复位；DO 在位 |
