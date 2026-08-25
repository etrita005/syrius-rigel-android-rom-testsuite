# 清除应用数据（ASR-0126）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 底层机制 |
|---|---|---|---|
| ASR-0126 | 清除应用数据 | 免交互清除指定应用的全部用户数据（files/databases/shared_prefs/cache 等） | 反射 `ActivityManager.clearApplicationUserData(pkg, observer)`（CLEAR_APP_USER_DATA 签名权限） |

**归属**：「Launcher（MDM）」（平台签名隐藏 API）。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署。

## 2. 技术选型与可行性核验

| 方案 | 说明 | 结论 |
|---|---|---|
| 反射 `ActivityManager.clearApplicationUserData` | 平台签名 uid=1000 调用（CLEAR_APP_USER_DATA 签名权限自动授予），PMS/AMS 全量清除目标包数据并强杀其进程 | **采用** |
| `pm clear` exec | shell 通道；本 ROM 应用域 exec 行为不可靠（B1 实测 pm uninstall 从应用域时灵时不灵） | 放弃 |

- **本 ROM 特殊性（2026-08-13 真机核验）**：`clearApplicationUserData` 的完成回调**不触发**（5s 等待超时无回调，与 deleteApplicationCacheFiles/deletePackage 同款 ROM 缺陷）——命令原实现仅依赖回调返回结果，产生**假阴性**（清除成功但 RESULT=false）；本批次按 ASR-0127 先例补**读回核验**：轮询目标数据目录 `/data/user/0/<pkg>` 的标准框架子目录（files/databases/shared_prefs/cache/code_cache）是否全部消失（exists() 探测，避免对目录列出权限的依赖；窗口 30s——本 ROM 删除路径完成慢，实测约数秒至数十秒）。
- **目标约束**：测试禁止对 Launcher（com.hmdm.launcher，DO 应用）执行清除；对 testapp 自身清除时目标进程被杀、IPC 无返回值属预期（以清除后 ReadDataProbe 复核）。

## 3. 命令接口定义

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `ClearAppData` | packageName（String，必填） | **Map**：{success, packageName, cleared（回调）, verified（读回核验）}——2026-08-13 由 Boolean 升级为 Map 并补读回核验 | ASR-0126 |

**调用示例**：

```java
Map<String, Object> p = new HashMap<>();
p.put("packageName", "com.hmdm.probe");
Map result = api.onEvent("ClearAppData", p);
// {"RESULT":{"success":true,"packageName":"com.hmdm.probe","cleared":false,"verified":true}}
// （cleared=false=本 ROM 不回调，verified=true=读回确认数据目录已清空）
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/
├── syrius/utils/AppDataCleaner.java               # clearAppData（反射 clearApplicationUserData + 回调）
├── syrius/service/command/clear_app_data/ClearAppData.java
│                                                  # 2026-08-13：返回 Map + 读回核验（dataDirCleared 轮询）
└── syrius/service/ApiBinder.java                  # 既有注册

testapp/
└── src/main/java/com/hmdm/testapp/
    ├── TestActions.java                          # ClearAppData 事件（packageName 必填）
    ├── AppDataControlTestActivity.java           # 新增测试页（数据探针 + 清除）
    └── src/main/res/layout/activity_app_data_test.xml
    （WriteDataProbe/ReadDataProbe 事件与 StorageUserVerifier 探针为 ASR-0125/0127 批次既有）
```

## 5. 执行逻辑

```
ClearAppData(packageName):
  1. 参数校验（缺 packageName → testapp 侧拦截）
  2. 反射 ActivityManager.clearApplicationUserData(pkg, observer)
  3. 回调等待 5s（本 ROM 不回调，实测特性）
  4. 读回核验（最长 30s 轮询）：/data/user/0/<pkg> 的 files/databases/shared_prefs/cache/code_cache
     全部不存在 → verified=true（目录本身可保留）
  5. 返回 {success=cleared||verified, cleared, verified}
```

**安全设计**：调用方经 ApiBinder 门禁；命令文档明示禁止对 Launcher 执行；testapp 清除自身时进程被杀为预期行为。

## 6. 权限与归属

- 平台签名 uid=1000 反射 `ActivityManager.clearApplicationUserData`（CLEAR_APP_USER_DATA 签名权限自动授予，无需 manifest 新增）；
- testapp：无新增权限（数据探针读写自身 files 目录）；
- 无 ROM 侧代码改动，无 root 依赖。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺参 | testapp 侧返回 missing parameter，不 crash |
| 包不存在/无法清除 | clearApplicationUserData 启动失败回调 false → 读回也无法核验 → success=false + error |
| 回调不触发（本 ROM） | 读回核验兜底（verified），success=verified（2026-08-13 实测修复假阴性） |
| 清除自身（testapp） | 目标进程被杀、IPC 无返回值属预期；以清除后 ReadDataProbe/文件系统核对 |
| 数据目录无标准子目录 | 视为已清除（trivially satisfied）——从未运行过的包本无数据 |
| 对 Launcher 执行 | 文档/测试明确禁止（DO 应用，清除将破坏设备管控） |

## 8. 真机验证记录（2026-08-13，Android 13 / API 33 userdebug，平台签名 + device owner）

| 核验项 | 结果 |
|---|---|
| ASR-0126 自身清除 | 写探针（probe.txt=clear-me）→ `ClearAppData packageName=com.hmdm.testapp` → 目标进程被杀（IPC 无返回值，预期）→ `ReadDataProbe` exists=false（数据已清） |
| ASR-0126 跨包清除（探针包） | 探针写入 probe.txt → ClearAppData → 返回 {success=true, verified=true, cleared=false}（回调不触发、读回确认数据目录子目录全部消失）；`ls /data/data/com.hmdm.probe` 空 |
| 修复记录 | 原命令仅依赖回调（5s）产生假阴性（清除成功却 RESULT=false）；升级为 Map + 30s 读回核验后 success=true（2026-08-13） |
| 测试后状态 | 探针包卸载、testapp 数据保持清除后状态（无害）、DO 在位 |
