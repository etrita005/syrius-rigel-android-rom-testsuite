# 壁纸管控（ASR-0183/0184）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 实现机制 |
|---|---|---|---|
| ASR-0183 | 壁纸 | 设置桌面壁纸 | 公开 `WallpaperManager.setBitmap`（FLAG_SYSTEM） |
| ASR-0184 | 壁纸 | 设置锁屏壁纸 | 公开 `WallpaperManager.setBitmap`（FLAG_LOCK） |

**归属**：两条需求均为「Launcher（MDM）」，落地为公开 Android SDK API `WallpaperManager.setBitmap(Bitmap, Rect, boolean, int)`（API 24+，目标平台 Android 13 全链路公开），**无需 @SystemApi/@hide/ROM 改动**；manifest 新增声明普通级权限 `SET_WALLPAPER`（WallpaperManagerService 对 setBitmap 调用的权限要求，安装即授予，平台签名自动持有）。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署；bootloader 锁定。测试设备基线（2026-08-07 实测）：出厂桌面壁纸为纯白（`getWallpaperId(FLAG_SYSTEM)`=2、`getWallpaperColors` primary=白色 -1）、未设置锁屏壁纸（`getWallpaperId(FLAG_LOCK)`=-1）；本机屏幕 1600×720（横屏，锁屏 screencap 为 720×1600 竖屏帧）；**HMDM Launcher 主页自绘深灰背景（#303030）完全覆盖桌面壁纸**（主页截图像素全为 48,48,48），壁纸可视化核验以**系统壁纸文件级**（/data/system/users/0/wallpaper*）、**框架 colors/ids 读回**与**锁屏 screencap** 为准。

## 2. 技术选型与可行性核验

### 2.1 本 ROM 壁纸框架特性（2026-08-07 真机/反编译核验）

- **公开 API 完整可用**：`WallpaperManager.setBitmap(Bitmap)`（home）、`setBitmap(Bitmap, Rect, boolean, int)`（FLAG_SYSTEM/FLAG_LOCK/组合）、`getWallpaperId(int)`、`getWallpaperColors(int)`（API 27+）、`getDrawable()`、`isWallpaperSupported()` 均在本 ROM framework.jar 存在（javap + 真机核验）；
- **`getLockWallpaperBitmap()` 本 ROM 不存在**：零参方法经运行期反射核验为 `NoSuchMethodException`（framework.jar 无该方法——MTK fork 差异），锁屏壁纸位图读回不可用；锁屏核验改经 `getWallpaperId(FLAG_LOCK)`、`getWallpaperColors(FLAG_LOCK)`、`dumpsys wallpaper` 与**壁纸文件**（/data/system/users/0/wallpaper_lock）对照；
- **项目 SDK 裁剪差异**：本项目 compileSdk 33 的 android.jar 为裁剪版，`WallpaperManager.getLockWallpaperBitmap()` 不在其中（javap 核验），引擎对该调用一律反射（本 ROM 亦无该方法，恒回退 null，能力缓存为不支持、不重复告警）；
- **锁屏壁纸 id 初始化特性**：未设置任何壁纸时 `getWallpaperId(FLAG_LOCK)` 返回 **-1**（非 AOSP 语义的 0）；**首次执行任意一次 setBitmap 后本 ROM 框架初始化锁屏壁纸数据对象**（观测到锁屏 id 由 -1 变为 3——与当时桌面壁纸 id 相同，疑似共享内部计数器初始化），此后读回与 `dumpsys wallpaper` 一致；仅首次异常、不影响写后核对判定（以设置前后 id 变化 + colors + 文件为准）；
- **锁屏壁纸元数据持久化**：本 ROM 将锁屏壁纸记录（id、颜色缓存 colorValue0 等）持久化于 `/data/system/users/0/wallpaper_info.xml`（ABX 二进制格式，含桌面 + 锁屏两条记录）；**删除 wallpaper_lock 文件并重启 framework 后，锁屏实际渲染回退为默认灰底**（锁屏 screencap 像素 204,204,204 核验），但 info 文件中的锁屏 id/颜色元数据残留（ROM 持久化副作用，恢复基线的注意点，详见测试用例文档 TC-M-12）。

### 2.2 命令设计

**图像通道**：AIDL `onEvent(String, Map)` 的 JSON 参数携带 **base64 编码图像字节**（`imageBase64`，BASE64.DEFAULT 解码），Launcher 侧 `BitmapFactory.decodeByteArray` 解码为 Bitmap 后 `setBitmap`。选型理由：MDM 管理端下发壁纸的通用通道为图像字节流；公开路径文件（/sdcard）受 SELinux/FUSE 限制（uid=1000 经 FUSE 可写但读取场景复杂，且 testapp 无写 Launcher 数据目录权限），base64 参数与既有命令参数风格一致、通道自包含。

**大小防护（双重）**：① 解码后字节上限 4 MiB（`MAX_IMAGE_BYTES`）、base64 长度上限约 5.6 MiB（`MAX_BASE64_LENGTH`）——AIDL/binder 事务本身约 1 MiB 上限，超限参数实际无法到达命令层（防御性双保险）；② **解压炸弹防护**：字节上限只约束压缩载荷，极小体积的纯色 PNG 可携带超大尺寸（如 16000×16000 仅数十 KB），解码成 GB 级 ARGB 可打垮 Launcher 进程——引擎先 `BitmapFactory.Options.inJustDecodeBounds` 探测尺寸，任一维超 8192 或总像素超 40MP 直接拒绝（结构化 error），随后按 `inSampleSize` 采样解码（解码位图每维 ≤4096，纯色/照片主色不受影响）；`setBitmap` 调用另捕获 OutOfMemoryError 如实上报（代码审查后修复）；解码 OOM 捕获并如实上报，不 crash。

**写后核对（三重读回）**：

1. **id 变化**：设置前 `getWallpaperId(flag)` → 设置后读回，`idChanged = before != after`（本 ROM id 为单调递增计数器，设置必变）；
2. **框架颜色匹配**：`getWallpaperColors(flag).getPrimaryColor()` 与输入图像**采样主色**（`dominantColor()`：按步长网格采样像素、5bit/通道量化分桶、最多桶重建代表色）做通道容差比较（±16/通道，容忍缩放/量化差异）→ `colorsMatched`；纯色图像恒精确匹配（真机全量验证），复杂图像框架聚类算法不同可能不匹配（如实上报，不伪装成功）；
3. **实际内容读回**：桌面经 `getDrawable()`（系统缩放后的实际壁纸位图）采样主色与输入主色比较 → `drawableDominantMatch`（真机对纯色/照片类图像全部命中——实际渲染内容与下发图像一致的最强证据）；锁屏因本 ROM 无 `getLockWallpaperBitmap()` 恒为 null（ROM 限制，如实上报，锁屏以文件级 + id + colors 核验）。

**持久化**：壁纸内容由 WallpaperManagerService 持久化（`/data/system/users/0/wallpaper`（桌面，重启后系统写入 wallpaper_orig 备份）/`wallpaper_lock`（锁屏）+ `wallpaper_info.xml`（元数据）），**Launcher 无 SharedPreferences 持久化、无 syncPolicy、进程重启/开机无需重新下发**（与策略类批次不同——壁纸是系统状态而非策略）。

### 2.3 查询设计

`GetWallpaper` 返回桌面/锁屏两 flag 的 {wallpaperId, colors（primary/secondary/tertiary）, drawableDominantColor（锁屏为 null）} 与 supported；testapp 侧另有本地探测 `GetWallpaperStateLocal`（testapp 自身进程的 WallpaperManager 独立读回，与 Launcher 命令交叉核对——注意：本 ROM testapp 进程 `getDrawable()` 需 READ_EXTERNAL_STORAGE 被拒（如实上报 null），其余字段正常）。

## 3. 命令接口定义（SystemApiInterface.onEvent）

### 3.1 新增命令（本批次，2 条）

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetWallpaper` | target（String，必填：home/lock/both）、imageBase64（String，必填：图像字节的 base64） | Map：{success, target, flags, width, height（原图尺寸）, decodedWidth, decodedHeight, sampleSize（inSampleSize 采样，大图时 >1）, imageBytes, expectedDominantColor, applied:[{flag, success, beforeId, afterId, idChanged, colors, colorsChanged, colorsMatched, drawableDominantColor, drawableDominantMatch}]} 或 {success:false, error} | ASR-0183/0184 |
| `GetWallpaper` | 无 | Map：{success, supported, home:{flag, wallpaperId, colors, drawableDominantColor}, lock:{...}} | ASR-0183/0184（查询） |

**调用示例**（testapp 侧，AIDL `onEvent(String, in Map)`）：

```java
Map<String, Object> p = new HashMap<>();
p.put("target", "home");
p.put("imageBase64", "iVBORw0KGgo...");  // 纯色/照片 PNG 的 base64
Map result = api.onEvent("SetWallpaper", p);
// {"RESULT":{"applied":[{"beforeId":3,"idChanged":true,"drawableDominantColor":-65536,
//   "flag":"home","success":true,"colorsMatched":true,"drawableDominantMatch":true,"afterId":4,
//   "colors":{"primaryColor":-65536,"tertiaryColor":null,"secondaryColor":null},
//   "colorsChanged":true}],"success":true,"expectedDominantColor":-65536,"flags":1,
//   "width":800,"imageBytes":2635,"target":"home","height":480}}

Map<String, Object> q = new HashMap<>();
Map result2 = api.onEvent("GetWallpaper", q);
// {"RESULT":{"lock":{"drawableDominantColor":null,"flag":"lock","colors":{...},"wallpaperId":19},
//   "success":true,"supported":true,"home":{"drawableDominantColor":-1,"flag":"home","colors":{...},"wallpaperId":20}}}
```

**广播通道**（TestBroadcast，与现有命令一致，真机核验可用）：

```bash
adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast \
  -a ON_SQA_INTERFACE_TEST --es event GetWallpaper --es param '{}'
```

**安全设计**：`imageBase64` 加入 `ApiBinder.SENSITIVE_KEYS` 脱敏名单（ApiBinder 日志只打 `***`，图像内容不落 logcat）；参数仅经位图解码进入 WallpaperManagerService，无字符串进入 shell/系统命令，无注入面；解码失败/超限/缺参均返回结构化 error，不 crash。

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/syrius/
├── utils/
│   └── WallpaperPolicyManager.java          # 新增：ASR-0183/0184 引擎（base64 解码 + setBitmap + 三重写后核对 + 主色采样）
├── service/command/wallpaper/
│   ├── SetWallpaper.java                    # 新增：ASR-0183/0184 设置命令
│   └── GetWallpaper.java                    # 新增：ASR-0183/0184 查询命令
└── service/ApiBinder.java                   # 注册 SetWallpaper/GetWallpaper；SENSITIVE_KEYS 增加 imageBase64
app/src/main/AndroidManifest.xml             # 新增 SET_WALLPAPER 权限声明

testapp/
├── src/main/java/com/hmdm/testapp/
│   ├── WallpaperTestActivity.java           # 新增：测试页（home 红/绿、lock 蓝、both 紫、查询、本地探测）
│   ├── WallpaperVerifier.java               # 新增：本地 WallpaperManager 交叉核对（按字段容错）
│   ├── TestActions.java                     # 新增 3 个事件（SetWallpaper 含测试图像生成、GetWallpaper、GetWallpaperStateLocal）与事件目录
│   ├── MainActivity.java                    # 增加 "Wallpaper" 入口
│   └── AndroidManifest.xml                  # 注册 WallpaperTestActivity
└── src/main/res/layout/activity_wallpaper_test.xml  # 新增测试页布局（6 按钮 + 2 分组）
```

## 5. 执行逻辑

### 5.1 SetWallpaper

```
1. 缺 target / 缺 imageBase64 → {error:"missing parameter: ..."}
2. target 非 home/lock/both → {error:"invalid target: ... (expected home/lock/both)"}
3. base64 长度超限 → {error}; Base64.decode 失败 → {error:"invalid base64 image data: ..."}
4. 解码字节 0 或超 4MiB → {error}; BitmapFactory.decodeByteArray 失败/OOM → {error}
5. WallpaperManager 不可用/isWallpaperSupported()=false → {error:"wallpaper not supported on this device"}
6. 计算输入图像采样主色 expectedDominantColor
7. target=both → 分别 applyFlag(FLAG_SYSTEM) + applyFlag(FLAG_LOCK)；否则 applyFlag(目标 flag)
8. applyFlag：
   a. 记录 beforeId/beforeColors → setBitmap(bitmap, null, true, flag)
   b. 异常 → {success:false, error:"setBitmap failed: ..."}（如实上报）
   c. 读回 afterId/afterColors/实际内容主色（桌面 getDrawable；锁屏反射 getLockWallpaperBitmap——本 ROM 无该方法，恒 null）
   d. 输出 idChanged/colorsChanged/colorsMatched/drawableDominantMatch
```

### 5.2 GetWallpaper

```
1. 按 FLAG_SYSTEM/FLAG_LOCK 读回 {wallpaperId, colors, drawableDominantColor}
2. 返回 {success, supported, home, lock}
```

**安全设计**：本批次命令参数为字符串（target/base64），图像字节仅进入 WallpaperManagerService Binder 与位图解码器；无文件路径参数、无 shell 命令拼接；`imageBase64` 日志脱敏；输入图像大小与解码内存均有上限，OOM 有捕获路径。

## 6. 权限与归属

- 两条需求归属「Launcher（MDM）」：全部使用公开 `WallpaperManager` API（API 24+/27+，目标平台 API 33 全量存在）；
- manifest **新增声明** `android.permission.SET_WALLPAPER`（protectionLevel normal，安装即授予；WallpaperManagerService.setBitmap 的权限校验要求，uid=1000 平台签名自动持有）；
- `getWallpaperId`/`getWallpaperColors`/`getDrawable` 无需权限；`getLockWallpaperBitmap` 反射调用本 ROM 无该方法（ROM 限制，引擎缓存"不支持"态不重复告警）；
- **无 uses-policy 声明、无 device_admin.xml 变更、无 Settings 键写入、无需重启 framework**；不修改 AIDL / lib 模块；
- 壁纸内容由 WallpaperManagerService 持久化（/data/system/users/0/wallpaper*），**无需 Launcher 侧持久化与开机重新下发**（与策略类批次不同）。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺 target / 缺 imageBase64 | {error："missing parameter: ..."}，不 crash（testapp IPC 缺 imageBase64 时自动生成默认测试图——UI 按钮同路径） |
| target 非法（非 home/lock/both） | {error："invalid target: ... (expected home/lock/both)"}，不写入 |
| base64 解码失败 | {error："invalid base64 image data: ..."} |
| 图像字节超 4 MiB / base64 超约 5.6 MiB | {error}（binder 1 MiB 事务上限下超限参数实际无法到达，防御性双保险） |
| 图像尺寸超 8192×8192 / 40MP（解压炸弹） | {error："image dimensions too large: WxH (max 8192x8192 / 40MP pixels)"}，不解码不 crash；合法大图按 inSampleSize 采样解码（每维 ≤4096，返回 decodedWidth/decodedHeight/sampleSize 如实上报） |
| 解码失败/OOM；setBitmap OOM | {error："failed to decode image data" / "image too large to decode" / "setBitmap out of memory: ..."}，不 crash |
| 设备不支持壁纸 | {success:false, error:"wallpaper not supported on this device"} |
| setBitmap 异常（权限/服务异常） | 该 flag {success:false, error:"setBitmap failed: ..."}，其余 flag 继续 |
| 锁屏位图读回（本 ROM 无 getLockWallpaperBitmap） | drawableDominantColor=null、drawableDominantMatch=false 如实上报；锁屏核验以 id 变化 + colors 匹配 + 文件级（/data/system/users/0/wallpaper_lock）+ dumpsys 为准 |
| 复杂图像（非纯色）框架颜色聚类与采样主色不一致 | colorsMatched=false 如实上报（drawableDominantMatch 仍为实际内容最强证据） |
| 首次 setBitmap 后锁屏 id 由 -1 初始化 | 本 ROM 观测特性（与桌面 id 同源初始化），写后核对以"设置前后变化 + colors + 文件"判定，不受影响 |
| 进程重启/整机重启 | 壁纸由 WallpaperManagerService 持久化，命令无需重新下发（真机 MD5/文件 + id 级核验通过） |
| 恢复基线 | 桌面设回白色；锁屏删除 /data/system/users/0/wallpaper_lock(+_orig) 并软重启 framework 后锁屏渲染回退默认灰底（wallpaper_info.xml 中锁屏 id/颜色元数据残留为本 ROM 持久化副作用，记录于测试用例文档） |

## 8. 真机验证记录（2026-08-07，Android 13 / API 33 userdebug，平台签名 + device owner）

（命令级用例结果见测试用例设计文档，此处记录机制核验关键结论）

| 核验项 | 结果 |
|---|---|
| 设备基线 | 桌面壁纸纯白（home id=2、primary=-1 白）、锁屏未设置（lock id=-1）；HMDM Launcher 主页自绘深灰背景覆盖桌面壁纸（主页截图像素 48,48,48）；锁屏 screencap 720×1600 |
| **框架通道核验** | setBitmap(Bitmap,Rect,boolean,int)/getWallpaperId/getWallpaperColors/getDrawable/isWallpaperSupported 全链路可用；**getLockWallpaperBitmap 本 ROM framework.jar 无该方法（运行期 NoSuchMethodException 核验）**；裁剪 SDK android.jar 亦缺该方法（javap 核验）——反射调用 + 能力缓存；**本 ROM getWallpaperId(FLAG_LOCK) 未设置时返回 -1（非 AOSP 的 0），首次任意 setBitmap 后锁屏数据对象初始化（id 与当时桌面 id 同值 3），此后与 dumpsys 一致**；锁屏记录（id+颜色缓存）持久化于 wallpaper_info.xml（ABX），删除 wallpaper_lock 文件重启 framework 后锁屏渲染回退默认灰底但元数据残留 |
| ASR-0183 桌面壁纸 | 设置纯色（红/绿/黄/紫/hex 0xFF800080）与照片类 1080×1920 PNG（42KB）全部 success：id 单调递增（2→21）、colorsMatched=true（纯色）、drawableDominantMatch=true（全部——实际渲染内容与下发图像一致）、桌面设置不影响锁屏（lock id/colors 不变）；**文件级最强证据**：/data/system/users/0/wallpaper 字节与下发 PNG 完全一致（md5 对照 + PIL 解码像素 = 输入主色） |
| ASR-0184 锁屏壁纸 | 设置纯色（蓝/绿/青）全部 success：lock id -1→5→6→…→19、colors primary=目标色 exact、`dumpsys wallpaper` Lock wallpaper state id 与命令 afterId 一致；**文件级证据**：/data/system/users/0/wallpaper_lock 字节与下发 PNG 一致（PIL 解码 = 输入色）；锁屏设置不影响桌面（home id/colors 不变） |
| target=both | 一次命令同时设置桌面+锁屏（purple：home 9→10、lock 6→11，两 flag 颜色均匹配） |
| 查询与交叉核对 | GetWallpaper 返回两 flag id/colors/drawable 主色；testapp GetWallpaperStateLocal 独立读回（homeId/lockId/colors 与 Launcher 命令一致；getDrawable 因 READ_EXTERNAL_STORAGE 被拒如实上报 null——本 ROM testapp 进程权限门禁）；TestBroadcast 广播通道派发核验 |
| 错误路径 | 缺 target / 缺 imageBase64（命令层）/ 非法 target / 非法 base64（bad base-64）全部结构化 error 不 crash；testapp IPC 缺 imageBase64 时自动生成默认图（设计行为） |
| 持久化 | 整机重启后桌面/锁屏壁纸文件 md5 不变、命令读回 id/colors 与重启前一致（home=18 red、lock=19 cyan 用例）；Launcher 进程重启/重装后命令正常（壁纸为系统状态无需重新下发） |
| 锁屏可视化 | 锁屏壁纸设置期间锁屏 screencap 为壁纸内容/删除文件后回退默认灰底（204,204,204） |
| **开发期修复缺陷** | ① 主色采样位运算错误：初版 5bit 分桶误取 alpha/red 高位（红图输出 0xFFFFFF00 黄），重建色与输入不符——改 r/g/b 各取字节高 5 位（(c>>>19)/(c>>>11)/(c>>>3)&0x1f），纯色主色精确恢复；② testapp 本地探测整包抛异常（getDrawable 权限门禁）——改按字段容错（safe 包装），单字段失败如实上报 null；③ getLockWallpaperBitmap 缺失导致每次锁屏读回都打 W 日志——能力缓存（lockBitmapSupported）仅首次探测；④ 代码审查后修复：**解压炸弹防护**（inJustDecodeBounds 预检 8192/40MP + inSampleSize 采样解码 + setBitmap OOM 捕获）；**TestBroadcast 通道 imageBase64 日志脱敏**（redactJson 正则补入 imageBase64，与 ApiBinder SENSITIVE_KEYS 对齐）；**TestBroadcast 命令派发改工作线程 + goAsync**（SetWallpaper 大图可达秒级，避免主线程阻塞/广播超时）；**testapp 壁纸页按钮改工作线程执行**（图像生成 + 同步 AIDL 往返不阻塞 UI 线程）；均经真机复核 |
| 测试后设备恢复 | 桌面壁纸恢复白色；锁屏壁纸文件删除（wallpaper_lock/wallpaper_lock_orig）+ framework 软重启，锁屏渲染回退默认灰底；无策略类残留（本批次无 SharedPreferences 策略） |
