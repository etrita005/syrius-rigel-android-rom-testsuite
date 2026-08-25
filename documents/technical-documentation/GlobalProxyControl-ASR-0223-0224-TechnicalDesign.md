# 全局代理配置（ASR-0223/0224）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 底层机制 |
|---|---|---|---|
| ASR-0223 | 获取/配置全局代理 | 设置/查询/清除全局 HTTP 代理（host:port + 绕过列表） | device owner `dpm.setRecommendedGlobalProxy`（2026-08-13 补命令注册，ASR-0273 先例） |
| ASR-0224 | 设置 Http Proxy | 同 ASR-0223（共用命令） | 同上 |

**归属**：「Launcher（MDM）」（device owner 公开接口；config.proxy 下发同引擎——Utils.setProxy）。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署。

## 2. 技术选型与可行性核验

| 方案 | 说明 | 结论 |
|---|---|---|
| `dpm.setRecommendedGlobalProxy(admin, ProxyInfo)` | device owner 公开接口（config.proxy 下发引擎同源：Utils.setProxy 解析 "host:port" → ProxyInfo.buildDirectProxy）；null 清除 | **采用** |
| 全局 HTTP 代理镜像键 | 框架在推荐代理应用/清除时同步 `Settings.Global global_http_proxy_host/port/exclusion_list`（真机核验） | **读回通道** |

- **2026-08-13 补命令注册**：`SetRecommendedGlobalProxy`（host/port/bypass 参数，host 为空=清除）与 `GetProxyInfo`；
- **本 ROM 特殊性**：DPM 的 `getRecommendedGlobalProxy` 方法不存在（NoSuchMethodException，MTK fork 移除 @SystemApi 方法）——读回改用框架镜像键（global_http_proxy_host/port/exclusion_list），设置后 1~2s 内同步（真机实测一致）；
- 真机闭环：设置 10.0.0.1:8080 + 绕过 [127.0.0.1, localhost] → GetProxyInfo 全字段一致；host="" 清除 → proxySet=false。

## 3. 命令接口定义

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetRecommendedGlobalProxy` | host（String，必填；空=清除）、port（int，可选）、bypass（String 数组，可选） | Boolean（setRecommendedGlobalProxy 调用结果） | ASR-0223/0224 |
| `GetProxyInfo` | 无 | Map{proxySet, host, port, bypass}（框架镜像键读回） | ASR-0223 |

**调用示例**：

```java
Map<String, Object> p = new HashMap<>();
p.put("host", "10.0.0.1");
p.put("port", 8080);
p.put("bypass", new ArrayList<>(Arrays.asList("127.0.0.1", "localhost")));
Map result = api.onEvent("SetRecommendedGlobalProxy", p);
// {"RESULT":true}
Map result2 = api.onEvent("GetProxyInfo", null);
// {"RESULT":{"host":"10.0.0.1","bypass":["127.0.0.1","localhost"],"proxySet":true,"port":8080}}
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/
├── util/Utils.java                                # setProxy（config 下发引擎，解析 host:port）
├── syrius/service/command/network/
│   ├── SetRecommendedGlobalProxy.java             # 新增（ASR-0223/0224）
│   └── GetProxyInfo.java                          # 新增（镜像键读回，本 ROM DPM 读方法缺失）
└── syrius/service/ApiBinder.java                  # 注册 2 条新命令

testapp/
└── src/main/java/com/hmdm/testapp/
    ├── TestActions.java                           # SetRecommendedGlobalProxy/GetProxyInfo 事件
    ├── NetworkTestActivity.java                   # 扩展：代理按钮组（设置/清除/查询）
    └── src/main/res/layout/activity_network_test.xml
```

## 5. 执行逻辑

```
SetRecommendedGlobalProxy(host, port, bypass):
  1. 参数校验（缺 host → testapp 侧拦截；port 非法 → false）
  2. host 为空 → dpm.setRecommendedGlobalProxy(admin, null)（清除）
     否则 → ProxyInfo.buildDirectProxy(host, port[, bypass数组]) → setRecommendedGlobalProxy
  3. 返回调用结果；GetProxyInfo（镜像键）读回核对（1~2s 同步）
GetProxyInfo(): 读 global_http_proxy_host/port/exclusion_list → {proxySet, host, port, bypass}
```

**安全设计**：调用方经 ApiBinder 门禁；ProxyInfo 参数为纯字符串/整数（无注入面）。

## 6. 权限与归属

- device owner 公开 setRecommendedGlobalProxy（无需权限声明）；镜像键为公开 Settings 读；
- testapp：无新增权限；无 ROM 侧代码改动，无 root 依赖。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺 host | testapp 侧返回 missing parameter，不 crash |
| port 非法（<0 或 >65535） | 返回 false |
| host 为空 | 清除代理（null ProxyInfo） |
| DPM 读方法缺失（本 ROM） | GetProxyInfo 走框架镜像键（global_http_proxy_host/port/exclusion_list），真机核验一致 |
| 镜像键同步延迟 | 设置后 1~2s 内同步（真机实测），测试等待后核对 |
| 非 DO 环境 | setRecommendedGlobalProxy 异常捕获返回 false |

## 8. 真机验证记录（2026-08-13，Android 13 / API 33 userdebug，平台签名 + device owner）

| 核验项 | 结果 |
|---|---|
| 基线 | GetProxyInfo → {proxySet:false, host:"", port:-1, bypass:[]} |
| 设置 | SetRecommendedGlobalProxy(10.0.0.1, 8080, [127.0.0.1, localhost]) → true；GetProxyInfo → {proxySet:true, host:10.0.0.1, port:8080, bypass:[127.0.0.1,localhost]}（含绕过列表，镜像键一致） |
| 清除 | SetRecommendedGlobalProxy(host="") → true；GetProxyInfo → proxySet:false（恢复基线） |
| 本 ROM 特性 | dpm.getRecommendedGlobalProxy 不存在（NoSuchMethodException）；读回经框架镜像键验证有效 |
| 测试后状态 | 代理清除（基线）；DO 在位 |
