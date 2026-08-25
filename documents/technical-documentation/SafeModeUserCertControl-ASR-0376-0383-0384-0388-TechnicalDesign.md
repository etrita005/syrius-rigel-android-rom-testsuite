# 安全模式/多用户/证书管控（ASR-0376/0383/0384/0388）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 底层机制 |
|---|---|---|---|
| ASR-0376 | 禁用安全模式 | 禁止设备进入安全模式 | device owner `DISALLOW_SAFE_BOOT` 用户限制（2026-08-13 补命令注册，Utils.lockSafeBoot 引擎） |
| ASR-0383 | 禁止添加多用户 | 禁止添加用户/切换用户 | device owner `DISALLOW_ADD_USER` + `DISALLOW_USER_SWITCH`（DisallowMulUser 既有） |
| ASR-0384 | 是否显示多用户入口 | 用户切换入口隐藏 | `DISALLOW_USER_SWITCH`（同上；2026-08-13 补查询命令） |
| ASR-0388 | 安装用户证书 | 免交互安装 CA 证书到用户信任库 | `dpm.installCaCert`（2026-08-13 补命令注册） |

**归属**：「Launcher（MDM）」。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署。

## 2. 技术选型与可行性核验

| 方案 | 说明 | 结论 |
|---|---|---|
| `DISALLOW_SAFE_BOOT`（0376） | device owner 用户限制（Utils.lockSafeBoot 引擎）；**2026-08-13 补命令** Set/IsSafeModeDisabled | **采用** |
| `DISALLOW_ADD_USER` + `DISALLOW_USER_SWITCH`（0383/0384） | UserUtils.disable/enableMulUser（既有）；**2026-08-13 补查询命令** IsDisallowMulUser | **采用** |
| `dpm.installCaCert`（0388） | device owner 公开接口（免交互）；**2026-08-13 补命令** InstallCaCert（certPath 参数，读 PEM 文件） | **受限**（见下） |

**2026-08-13 真机核验记录**：
- 0376/0383/0384：限制写入/读回往返一致（Set/Is 闭环）；
- **0388 本 ROM 限制**：`dpm.installCaCert` 返回 true 但**用户 CA 存储无任何落盘**（`/data/misc/apexdata/com.android.conscrypt/cacerts-added` 与 `/data/misc/user/0/cacerts-added` 均不存在/为空；keystore persistent.sqlite 无写入；install 时刻文件系统无新证书文件）——MTK fork DPMS 接受调用但不持久化（与 setStatusBarDisabled 同类）。命令改为**写后读回核对**（轮询用户 CA 存储新文件，5s 窗口）：success=false + note 如实上报，需求维持部分完成（ROM 适配后验证）。

## 3. 命令接口定义

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetSafeModeDisabled` | disabled（Boolean，必填） | Boolean | ASR-0376 |
| `IsSafeModeDisabled` | 无 | Boolean | ASR-0376 |
| `DisallowMulUser` | disallow（Boolean，必填） | Boolean | ASR-0383/0384 |
| `IsDisallowMulUser` | 无 | Boolean（2026-08-13 新增） | ASR-0383/0384 |
| `InstallCaCert` | certPath（String，必填——PEM 文件绝对路径） | **Map**{success, verified, certPath, note?/error?}——写后读回核对；本 ROM 不落盘时 success=false+note（2026-08-13 改造） | ASR-0388 |

**调用示例**：

```java
Map<String, Object> p = new HashMap<>();
p.put("disabled", true);
Map result = api.onEvent("SetSafeModeDisabled", p);
// {"RESULT":true}；IsSafeModeDisabled → true

Map<String, Object> p2 = new HashMap<>();
p2.put("certPath", "/sdcard/MDM/isrg_root_x1.pem");
Map result2 = api.onEvent("InstallCaCert", p2);
// {"RESULT":{"success":false,"verified":false,
//   "note":"this ROM's DPMS accepts installCaCert but no cert file appeared in the user CA store ..."}}
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/
├── util/Utils.java                                # lockSafeBoot（DISALLOW_SAFE_BOOT 引擎）
├── syrius/utils/UserUtils.java                    # disable/enableMulUser（DISALLOW_ADD_USER+USER_SWITCH）
├── helper/CertInstaller.java                      # installCertificate（dpm.installCaCert，assets/配置路径引擎）
├── syrius/service/command/user/
│   ├── SetSafeModeDisabled.java / IsSafeModeDisabled.java   # 新增（ASR-0376）
│   ├── IsDisallowMulUser.java                     # 新增（ASR-0383/0384 查询）
│   └── InstallCaCert.java                         # 新增（ASR-0388；写后读回核对）
└── syrius/service/ApiBinder.java                  # 注册 4 条新命令

testapp/
└── src/main/java/com/hmdm/testapp/
    ├── TestActions.java                           # 5 个事件
    ├── SafeModeUserCertControlTestActivity.java   # 新增测试页
    └── src/main/res/layout/activity_safe_mode_user_cert_test.xml
```

## 5. 执行逻辑

```
SetSafeModeDisabled(disabled): DISALLOW_SAFE_BOOT 加/除 → true；Is 读回核对
DisallowMulUser(disallow): DISALLOW_ADD_USER + DISALLOW_USER_SWITCH 加/除 → true；Is 读回核对
InstallCaCert(certPath):
  1. 读 PEM 文件（不存在 → {success:false, error}）
  2. dpm.installCaCert(admin, cert)（API 24+）
  3. 读回核对（5s 轮询用户 CA 存储新文件）→ {success=installed&&verified, verified}
  4. 本 ROM 不落盘 → verified=false + note（维持部分完成）
```

**安全设计**：certPath 读取受限于平台签名进程；调用方经 ApiBinder 门禁。

## 6. 权限与归属

- 用户限制/installCaCert 为 device owner 公开接口（无需权限声明）；
- testapp：无新增权限；无 ROM 侧代码改动，无 root 依赖。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺参 | testapp 侧返回 missing parameter，不 crash |
| cert 文件不存在 | {success:false, error:cert file not found}（真机验证） |
| 本 ROM installCaCert 不落盘 | 读回核对 verified=false + note（维持部分完成，ROM 适配后验证） |
| API < 24 | installCaCert 返回 {success:false, error:needs API 24+} |
| 非 DO 环境 | 各命令异常捕获返回 false/error |

## 8. 真机验证记录（2026-08-13，Android 13 / API 33 userdebug，平台签名 + device owner）

| 核验项 | 结果 |
|---|---|
| ASR-0376 | 基线 Is=false；SetSafeModeDisabled(true) → Is=true；false → Is=false（往返一致） |
| ASR-0383/0384 | 基线 Is=false；DisallowMulUser(disallow=true) → Is=true（DISALLOW_ADD_USER+USER_SWITCH）；false → Is=false |
| ASR-0388 正常路径 | InstallCaCert(/sdcard/MDM/isrg_root_x1.pem) → true 但**用户 CA 存储无落盘**（apexdata cacerts-added 不存在、keystore 无写入、install 时刻无新证书文件）——改造后命令如实 {success:false, verified:false, note} |
| ASR-0388 异常路径 | certPath=/sdcard/MDM/nonexistent.pem → {success:false, error:cert file not found} |
| 测试后状态 | 限制全部复位；DO 在位 |
