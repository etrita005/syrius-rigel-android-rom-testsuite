# 当前位置查询（ASR-0312）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 底层机制 |
|---|---|---|---|
| ASR-0312 | 获取当前位置信息 | 查询设备当前位置（经纬度/精度） | `LocationManager.getLastKnownLocation`（GPS → Network 回退）（2026-08-13 补命令注册） |

**归属**：「Launcher（MDM）」（公开 LocationManager API；opensource 构建下 pro 定位上报管线为桩）。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署。

## 2. 技术选型与可行性核验

| 方案 | 说明 | 结论 |
|---|---|---|
| `LocationManager.getLastKnownLocation` | 公开 API（ACCESS_FINE_LOCATION/COARSE 既有声明，平台签名授予）；返回框架缓存的最近定位 | **采用**（opensource 构建） |
| pro 管线（LocationService 前台 GPS/Network 监听 + lastKnownLocation 上报） | 商用版机制；**opensource 构建中 ProUtils.processLocation 为桩**（真机核验代码） | 说明（pro 版生效） |

- **2026-08-13 补命令注册**：`GetLastKnownLocation`——读取框架最近定位（GPS 优先、Network 回退），返回 {available, provider, latitude, longitude, accuracy, time[, altitude]}；
- **真机核验**：本机无缓存定位（室内无 GPS 信号、无网络定位源）→ available=false 如实返回；开启定位（location_mode=3）后仍无缓存 → 如实 false——命令行为诚实，真实定位值需室外/GPS 或模拟注入环境（硬件受限说明）。

## 3. 命令接口定义

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `GetLastKnownLocation` | 无 | Map{available, provider, latitude, longitude, accuracy, time[, altitude, error]} | ASR-0312 |

**调用示例**：

```java
Map result = api.onEvent("GetLastKnownLocation", null);
// {"RESULT":{"available":false,"provider":""}}（无缓存定位时如实）
// {"RESULT":{"available":true,"provider":"gps","latitude":31.23,"longitude":121.47,
//            "accuracy":10.0,"time":1720000000000}}（有缓存定位时）
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/
├── service/LocationService.java                    # pro 管线（前台监听；opensource 构建 processLocation 为桩）
├── pro/ProUtils.java                               # processLocation（opensource 桩）
├── syrius/service/command/location/GetLastKnownLocation.java   # 新增（ASR-0312）
└── syrius/service/ApiBinder.java                   # 注册新命令

testapp/
└── src/main/java/com/hmdm/testapp/
    ├── TestActions.java                            # GetLastKnownLocation 事件
    ├── DeviceStateTestActivity.java                # 扩展：定位查询按钮
    └── src/main/res/layout/activity_device_state_test.xml
```

## 5. 执行逻辑

```
GetLastKnownLocation(): GPS_PROVIDER → NETWORK_PROVIDER 依次 getLastKnownLocation
  → 有值：{available:true, provider, latitude, longitude, accuracy, time[, altitude]}
  → 无值：{available:false, provider:""}（如实；等待真实定位或 pro 管线）
```

**安全设计**：只读查询；调用方经 ApiBinder 门禁。

## 6. 权限与归属

- ACCESS_FINE_LOCATION/ACCESS_COARSE_LOCATION（manifest 既有声明，平台签名授予）；
- testapp：无新增权限；无 ROM 侧代码改动，无 root 依赖。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 无缓存定位 | available=false 如实返回（真机：室内无信号 + 无网络定位源） |
| 定位关闭 | 同无缓存（available=false） |
| LocationManager 异常 | 捕获返回 {error} 字段 |
| opensource 构建 | pro 定位上报管线为桩——命令以框架 API 提供等价查询（文档说明） |

## 8. 真机验证记录（2026-08-13，Android 13 / API 33 userdebug，平台签名 + device owner）

| 核验项 | 结果 |
|---|---|
| 基线（定位关闭） | GetLastKnownLocation → {available:false, provider:""} |
| 开启定位 | SetLocationEnabled(true) → location_mode=3（成功）；GetLastKnownLocation 仍 available=false（本机无真实定位源，如实） |
| 恢复 | SetLocationEnabled(false) → location_mode=0（基线恢复） |
| 测试后状态 | 定位关闭（会话基线）；DO 在位 |
