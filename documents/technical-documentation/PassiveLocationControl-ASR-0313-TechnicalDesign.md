# 被动定位管控（ASR-0313）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 实现机制 |
|---|---|---|---|
| ASR-0313 | 定位服务 | 获取/设置是否允许被动定位 | `Settings.Secure.location_mode` LOCATION_MODE 组合（允许=恢复保存的模式，禁止=保存当前模式并写 0） |

**归属**：Launcher（MDM）。落地为受保护系统设置直写（`WRITE_SECURE_SETTINGS` 签名权限，平台签名 Launcher uid=1000 自动授予），与 ASR-0310/0311（禁用/启用定位服务）**共用同一 `LocationPolicyManager` 引擎**，无 ROM 侧代码改动。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署。测试设备基线（2026-08-11 实测）：`location_mode=3`（高精度）、被动 provider enabled=true。

## 2. 技术选型与可行性核验

### 2.1 Android 13 无独立"被动定位"开关（真机 + dex 核验）

"被动定位"指 **passive location provider**——不主动发起定位、仅接收其他应用定位请求结果的特殊 provider。Android 13 已无按 provider 粒度的设置（`Settings.Secure.location_providers_allowed` 在本 ROM 为 null，框架不再消费），provider 集合由 `location_mode` 唯一决定。本批次经**真机核验**（2026-08-11）：

| 验证 | location_mode | 结果 |
|---|---|---|
| 高精度 | 3 | `dumpsys location`：passive provider enabled=true（gps/fused 同 enabled） |
| 仅设备（传感器） | 1 | passive provider enabled=true |
| 省电（仅网络） | 2 | passive provider enabled=true |
| 关闭 | 0 | 历史日志 `passive provider [u0] disabled` + `ProviderRequest[OFF]` |

即：**允许被动定位 ⇔ 定位开启 ⇔ `location_mode ∈ {1,2,3}`**（与第四节 P2 规划"LOCATION_MODE 组合"一致）。因此"设置是否允许被动定位"与 ASR-0310（禁用/启用定位服务）使用同一杠杆，命令复用同一引擎：

- `allowed=false`（禁止）：当前模式非 0 时保存到 Launcher SharedPreferences（`location_policy` 的 `savedMode`），写 0；已是 0 则不写（幂等）；
- `allowed=true`（允许）：当前为 0 时恢复 `savedMode`（无保存记录时默认 3=高精度）；已开启则保持当前模式不动；
- 写后读回核对，一致才 `success=true`。

### 2.2 与 ASR-0310/0311 的关系

| 需求 | 语义 | 命令 | 引擎 |
|---|---|---|---|
| ASR-0310 | 禁用/启用定位服务 | `Set/IsLocationEnabled` | LocationPolicyManager（同 savedMode 账本） |
| ASR-0311 | 关闭/打开/强制打开 | `Set/IsLocationEnabled` + `ForceOpenLocation` | 同引擎 + 10s 纠正器 |
| ASR-0313 | 获取/设置是否允许被动定位 | `Set/IsPassiveLocationAllowed` | **同引擎**（本批次新增命令名） |

三需求共用 `location_policy` SharedPreferences 的 `savedMode` 账本，互不冲突：任一命令禁用（写 0）都会保存当前模式，任一命令启用都会恢复同一保存值。强制打开（ASR-0311）运行时禁止被动定位会被纠正器在 10 秒内重新打开（用户级关闭同等待遇），为预期语义。

## 3. 命令接口定义（SystemApiInterface.onEvent）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetPassiveLocationAllowed` | allowed（Boolean，必填） | Map：{success, passiveAllowed, enabled, location_mode, savedMode} 或 {error} | ASR-0313 |
| `IsPassiveLocationAllowed` | 无 | Map：{success, passiveAllowed, location_mode, savedMode} | ASR-0313 |

**返回结构**（以 SetPassiveLocationAllowed 为例）：

```
{success:boolean, passiveAllowed:boolean, enabled:boolean, location_mode:int, savedMode:int}
# 写入后读回核对，一致才 success=true；不一致 → success=false + error（含期望值/实际值）
```

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> p = new HashMap<>();
p.put("allowed", false);
Map result = api.onEvent("SetPassiveLocationAllowed", p);
// {"RESULT":{"location_mode":0,"savedMode":3,"success":true,"enabled":false,"passiveAllowed":false}}

Map result2 = api.onEvent("IsPassiveLocationAllowed", new HashMap<>());
// {"RESULT":{"location_mode":3,"savedMode":3,"success":true,"passiveAllowed":true}}
```

**广播通道**（TestBroadcast / testapp IPC，与现有命令一致）：

```bash
adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast \
  -a ON_SQA_INTERFACE_TEST --es event SetPassiveLocationAllowed \
  --es param '{"allowed":false}'
# testapp 侧（与 UI 按钮等效）：./send_test_command.sh SetPassiveLocationAllowed allowed=false
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/LocationPolicyManager.java        # 扩展：setPassiveLocationAllowed/isPassiveLocationAllowed（复用 setLocationEnabled 引擎）
├── service/command/location/
│   ├── SetPassiveLocationAllowed.java      # 新增：ASR-0313 设置命令
│   └── IsPassiveLocationAllowed.java       # 新增：ASR-0313 查询命令
└── service/ApiBinder.java                  # 注册 2 个新命令

testapp/
├── src/main/java/com/hmdm/testapp/
│   ├── DeviceStateTestActivity.java        # 扩展：被动定位 3 个按钮（Forbid/Allow/Query）
│   ├── TestActions.java                    # 新增 2 个事件（含参数校验）与事件目录
│   └── res/layout/activity_device_state_test.xml  # 新增 ASR-0313 分区
```

## 5. 执行逻辑

```
SetPassiveLocationAllowed(allowed):
  1. 参数校验（缺 allowed → {error}）
  2. allowed=false 且当前模式非 0 → 保存 savedMode（复用 setLocationEnabled 引擎）
  3. 计算目标值：allowed=true → 当前非 0 则保持，为 0 则 savedMode（无记录=3）；allowed=false → 0
  4. Settings.Secure 直写 location_mode，读回核对 → success
  5. 返回 {success, passiveAllowed, enabled, location_mode, savedMode}

IsPassiveLocationAllowed:
  1. 读 location_mode，passiveAllowed = (mode != 0)
  2. 返回 {success, passiveAllowed, location_mode, savedMode}
```

**安全设计**：参数为布尔值，无字符串进入 shell / 系统命令，无命令注入面；设置键名与模式值均为代码内常量。

## 6. 权限与归属

- `WRITE_SECURE_SETTINGS`（manifest 既有声明，平台签名 uid=1000 自动授予，真机核验 granted）；`Settings.Secure.location_mode` 为既有 ASR-0310/0311/0314 同键同权限路径；
- 无 ROM 侧代码改动，无 root 依赖；不修改 AIDL / lib 模块；testapp 无需新增权限；不修改 `device_admin.xml`，无需重启 framework。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺 allowed 参数 | 命令返回 {error："missing parameter: allowed"}，不 crash；testapp 侧同样拦截 |
| 禁止时当前模式已是 0 | 不重复保存，写 0 幂等；启用时恢复上次保存值 |
| 允许但无 savedMode 记录 | 默认 3=高精度（文档化） |
| 允许时用户已自行调整过模式（非 0） | 保持用户模式不动，仅确保非 0（语义=允许被动定位） |
| 模式 1/2 下查询 | passiveAllowed=true（本 ROM 实测被动 provider 在任意非 0 模式均 enabled，见 2.1） |
| Settings 写失败或读回不一致 | success=false + error（含期望值/实际值），如实上报，可重试 |
| 与 ASR-0311 强制打开并存 | 强制打开运行中禁止被动定位会被 10 秒纠正器重新打开（预期：强制优先级更高，与用户关闭同等待遇） |
| 整机重启 | `location_mode` 由 SettingsProvider 持久化（Settings.Secure 存储），重启保持，无需重新武装（无强制标志参与） |

## 8. 真机验证记录（2026-08-11，Android 13 / API 33 userdebug，平台签名 + device owner）

（命令级用例结果见测试用例设计文档，此处记录机制核验关键结论）

| 核验项 | 结果 |
|---|---|
| 设备基线 | location_mode=3（高精度）、被动 provider enabled=true |
| 禁止（allowed=false） | location_mode 3→0，savedMode=3；`settings get secure location_mode`=0；`dumpsys location` 历史日志 `passive provider [u0] disabled` + `ProviderRequest[OFF]` |
| 允许（allowed=true） | 恢复 savedMode=3；`dumpsys location` passive provider enabled=true；gps/fused 同步 enabled |
| 模式 2（省电）往返 | SetLocationMode 2 → 禁止（savedMode=2）→ 允许恢复 2，闭环一致 |
| 模式 1（传感器）查询 | passiveAllowed=true（被动 provider 随非 0 模式保持启用） |
| 幂等 | 连续两次禁止：savedMode 不被覆盖（保持 1），写 0 幂等 |
| 缺参 | allowed 缺失 → missing parameter，不 crash |
| 重启持久化 | location_mode 为 Settings.Secure 存储，整机重启保持（SettingsProvider 持久化，无 Launcher 重新武装需求） |
| 测试后设备恢复 | location_mode=3、被动 provider enabled=true，无残留 |
