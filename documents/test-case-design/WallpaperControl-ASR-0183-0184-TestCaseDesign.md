# 壁纸管控（ASR-0183/0184）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner；testapp（`com.hmdm.testapp`，平台签名部署）已安装；
- 部署准备：`adb root`、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`（IPC 与 UI 按钮等效，同一 `TestActions.execute()` 引擎）；也可在 testapp UI 的 "Wallpaper" 页面点按对应按钮；
- 对照命令：`adb shell dumpsys wallpaper`（桌面/锁屏壁纸状态与 id）、`adb shell md5sum /data/system/users/0/wallpaper` 与 `/data/system/users/0/wallpaper_lock`（文件级核对，需 root）、`adb shell screencap`（锁屏可视化）、`adb shell logcat -d | grep WallpaperPolicyManager`（引擎执行日志）；
- 恢复基线（测试开始时记录、结束时恢复）：桌面壁纸白色、锁屏壁纸未设置（渲染回退默认灰底；wallpaper_info.xml 中锁屏 id/颜色元数据残留为本 ROM 持久化副作用，见 TC-M-12）；
- 本 ROM 特性（2026-08-07 核验）：① 桌面/锁屏壁纸由 WallpaperManagerService 持久化于 `/data/system/users/0/wallpaper`/`wallpaper_lock`（+ wallpaper_orig/wallpaper_lock_orig 备份与 wallpaper_info.xml 元数据，后者为 ABX 二进制）；② **`WallpaperManager.getLockWallpaperBitmap()` 本 ROM framework.jar 无该方法**（运行期 NoSuchMethodException 核验，裁剪 SDK android.jar 亦缺——javap 核验），锁屏位图读回不可用，锁屏核验以 id + colors + 文件级 + dumpsys 为准；③ **未设置壁纸时 `getWallpaperId(FLAG_LOCK)` 返回 -1**（非 AOSP 的 0），**首次任意 setBitmap 后锁屏数据对象初始化**（id 与当时桌面 id 同值，观测为 3），此后与 `dumpsys wallpaper` 一致；④ 锁屏记录（id+颜色缓存）持久化于 wallpaper_info.xml——删除 wallpaper_lock 文件并重启 framework 后锁屏渲染回退默认灰底（screencap 像素 204,204,204），但元数据残留；⑤ **HMDM Launcher 主页自绘深灰背景（#303030）完全覆盖桌面壁纸**（主页截图像素全 48,48,48），桌面壁纸可视化以系统文件级核验为准；⑥ 本 ROM testapp 进程 `WallpaperManager.getDrawable()` 需 READ_EXTERNAL_STORAGE（testapp 未授予被拒）——本地探测对单字段容错、如实上报 null；⑦ 输入图像防护：解码字节上限 4 MiB / base64 约 5.6 MiB（binder 事务约 1 MiB 上限，超限参数实际无法到达命令层）＋ **解压炸弹防护**（inJustDecodeBounds 预检——任一维超 8192 或总像素超 40MP 拒绝，合法大图按 inSampleSize 采样解码，解码位图每维 ≤4096，返回 decodedWidth/decodedHeight/sampleSize）；⑧ 测试图像生成参数：color 支持名称（red/green/blue/purple/cyan/yellow/magenta/gray/grey/white/black）、`0xAARRGGBB`/`#RRGGBB`/十进制（**无 alpha 形式按不透明处理**——`#RRGGBB`/24 位值自动补 `0xFF000000`，否则生成全透明图而核对忽略 alpha 会误报匹配；越界/非法回退红）；width/height 默认 800×480、范围 1~4096（越界回退默认）；⑨ TestBroadcast 通道命令派发在**工作线程 + goAsync**（大图命令可达秒级，不阻塞主线程）；壁纸页 UI 按钮同样在工作线程执行。

## 2. 测试用例表

### 2.1 ASR-0183 设置桌面壁纸（SetWallpaper target=home / GetWallpaper）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0183-01 基线查询 | `./send_test_command.sh GetWallpaper` | success=true；supported=true；home.wallpaperId 为当前值（基线实测 2）；home.colors.primaryColor=-1（白色）；lock.wallpaperId=-1（未设置）；lock.colors 为回退值 |
| TC-0183-02 设置桌面壁纸 | `./send_test_command.sh SetWallpaper target=home color=red` | success=true；width=800、height=480、imageBytes>0；expectedDominantColor=-65536（0xFFFF0000 红）；applied[0]：flag=home、beforeId<afterId 且 idChanged=true、colors.primaryColor=-65536（框架精确识别红色）、colorsChanged=true、**colorsMatched=true、drawableDominantMatch=true** |
| TC-0183-03 再次设置（颜色覆盖） | `./send_test_command.sh SetWallpaper target=home color=green` | success=true；id 再次递增（beforeId 为上一步 afterId）；colors.primaryColor=-16711936（绿）、colorsChanged=true；colorsMatched/drawableDominantMatch=true |
| TC-0183-04 桌面壁纸文件核验 | `adb shell md5sum /data/system/users/0/wallpaper` + `adb pull` 后 PIL 解码 | 文件字节为设置时下发的 PNG（md5 变化、解码主色 = 设置颜色；本批次实测 800×480 纯色 PNG 文件 2635 字节与 imageBytes 一致） |
| TC-0183-05 自定义图像（显式 base64） | 本机构造 400×200 白色 PNG 的 base64 → `./send_test_command.sh SetWallpaper target=home imageBase64=<b64>` | success=true；width=400、height=200、imageBytes=737；expectedDominantColor=-1（白）；colors.primaryColor=-1、colorsMatched/drawableDominantMatch=true |
| TC-0183-06 自定义尺寸/颜色 | `SetWallpaper target=home color=yellow width=320 height=200`；`color=0xFF800080`（hex） | success=true；width/height 生效（320×200）；expectedDominant 分别为 -256（黄）、-8126332（0x80 系重建，容差内）；colors.primaryColor=-256/-8388480 exact；colorsMatched=true |
| TC-0183-07 照片类图像 | 构造 1080×1920 多色 PNG（约 42KB，base64 约 56KB）→ `SetWallpaper target=home imageBase64=<b64>` | success=true；width=1080、height=1920；idChanged=true；**drawableDominantMatch=true（实际渲染内容主色 = 输入主色，复杂图像的最强证据）**；colorsMatched 可能 false（框架聚类算法与采样主色不同，如实上报不伪装） |
| TC-0183-08 桌面设置不影响锁屏 | 设置桌面壁纸前后 `GetWallpaper` | lock.wallpaperId/lock.colors 不变（实测：桌面多次设置期间 lock id/colors 保持） |

### 2.2 ASR-0184 设置锁屏壁纸（SetWallpaper target=lock / GetWallpaper）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0184-01 设置锁屏壁纸 | `./send_test_command.sh SetWallpaper target=lock color=blue` | success=true；expectedDominantColor=-16776961（0xFF0000FF 蓝）；applied[0]：flag=lock、idChanged=true（基线 -1→正数）、colors.primaryColor=-16776961、colorsChanged=true、colorsMatched=true；**drawableDominantColor=null、drawableDominantMatch=false（本 ROM 无 getLockWallpaperBitmap，如实上报，锁屏核验以 id+colors+文件为准）** |
| TC-0184-02 锁屏壁纸文件核验 | `adb shell md5sum /data/system/users/0/wallpaper_lock` + `adb pull` 后 PIL 解码 | 文件字节为设置时下发的 PNG（解码主色 = 设置颜色，本批次实测与 imageBytes 一致） |
| TC-0184-03 dumpsys 对照 | `adb shell dumpsys wallpaper` | "Lock wallpaper state: User 0: id=<afterId>" 与命令返回一致 |
| TC-0184-04 锁屏设置不影响桌面 | 设置锁屏壁纸前后 `GetWallpaper` | home.wallpaperId/home.colors 不变（实测：lock 多次设置期间 home 保持） |
| TC-0184-05 锁屏可视化 | 设置锁屏壁纸期间 `input keyevent 26`（熄屏）→ `input keyevent 224`（亮屏）→ `screencap` | 锁屏帧显示壁纸内容（颜色级可辨；本批次以文件级证据为主） |
| TC-0184-06 连续设置锁屏 | `SetWallpaper target=lock color=green`、`color=cyan` 连续两次 | id 递增（-1→5→6→…）、colors primary 精确切换（绿→青 -16711681）、colorsChanged=true |

### 2.3 双目标与查询（SetWallpaper target=both / GetWallpaper / 本地探测）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0183/0184-01 双目标 | `./send_test_command.sh SetWallpaper target=both color=purple` | success=true；flags=3；applied 两个条目（flag=home + flag=lock）；两 flag id 均递增（实测 home 9→10、lock 6→11）；两 flag colors.primaryColor=-65281（0xFFFF00FF 紫）、colorsMatched=true |
| TC-0183/0184-02 查询 | `./send_test_command.sh GetWallpaper` | success=true；home/lock 各含 wallpaperId/colors/drawableDominantColor（锁屏恒 null）；与 dumpsys 对照一致 |
| TC-0183/0184-03 本地交叉核对 | `./send_test_command.sh GetWallpaperStateLocal` | testapp 独立读回：homeId/lockId 与 Launcher 命令一致、homeColors/lockColors 一致；lockSet=false（本 ROM 无 getLockWallpaperBitmap）、lockDominantColor=null、homeDominantColor=null（testapp getDrawable 权限门禁，如实上报） |
| TC-0183/0184-04 广播通道 | `adb shell am broadcast -n com.hmdm.launcher/.syrius.test.TestBroadcast -a ON_SQA_INTERFACE_TEST --es event GetWallpaper --es param '{}'` | 命令派发成功（logcat `CallWithCommand command :【GetWallpaper】 is going to run`），无异常 |

### 2.4 错误与边界

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-M-01 缺 target | `./send_test_command.sh SetWallpaper color=red` | 返回 `missing parameter: target (home/lock/both)`，不 crash |
| TC-M-02 非法 target | `./send_test_command.sh SetWallpaper target=desktop color=red` | `{"error":"invalid target: desktop (expected home/lock/both)","success":false}`，不写入 |
| TC-M-03 缺 imageBase64（命令层） | 经 Launcher TestBroadcast 直连 `SetWallpaper`（param 仅 target） | `{"error":"missing parameter: imageBase64"}`；**testapp IPC 缺 imageBase64 时为设计行为：自动生成默认红色测试图**（UI 按钮同路径） |
| TC-M-04 非法 base64 | `./send_test_command.sh SetWallpaper target=home imageBase64=not-base64!!` | `{"error":"invalid base64 image data: bad base-64","success":false}`，不 crash |
| TC-M-05 超大 payload | 构造超 1 MiB 的 base64 参数 | adb/binder 事务层拒绝或命令层大小上限拒绝（AIDL 事务约 1 MiB 上限 + 命令层 4 MiB 解码/约 5.6 MiB base64 双保险），不 crash |
| TC-M-05b 超大尺寸（解压炸弹） | 构造小体积但超大尺寸的 PNG（如 9000×9000 纯色，经 python PIL 生成 base64）→ `SetWallpaper target=home imageBase64=<b64>` | `{"error":"image dimensions too large: 9000x9000 (max 8192x8192 / 40MP pixels)","success":false}`，不解码不 crash、不写入；**合法大图采样路径**：4000×4000 纯色 PNG → success=true、width=4000/height=4000（原图尺寸）、decodedWidth/decodedHeight=2000×2000（sampleSize=2，每维 ≤4096）、colorsMatched=true（纯色不受采样影响） |
| TC-M-06 未知颜色名 | `SetWallpaper target=home color=unknown` | 生成默认红色图（回退设计），success=true、primaryColor=-65536 |
| TC-M-06b 无 alpha 十六进制 | `SetWallpaper target=home color=#FF0000`（与 `color=0xFF0000`） | 按不透明处理（自动补 0xFF000000）：success=true、primaryColor=-65536（红）；**不产生全透明壁纸**（修复前 `#RRGGBB` 解析为 alpha=0，壁纸渲染默认/黑但核对忽略 alpha 仍报匹配） |
| TC-M-07 幂等查询 | 连续两次 `GetWallpaper` | 两次返回一致（无状态变更） |

### 2.5 持久化与恢复

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-M-08 Launcher 进程重启 | 设置桌面红/锁屏青 → 重装 Launcher（或 root kill 进程）→ 重启 testapp 进程重建 AIDL → `GetWallpaper` | 命令正常返回；home/lock wallpaperId 与重启前一致（壁纸为系统状态，无需重新下发） |
| TC-M-09 整机重启持久化 | 设置桌面红/锁屏青 → `adb reboot` → 开机后 `adb root` 核对文件 md5 + `GetWallpaper` | /data/system/users/0/wallpaper 与 wallpaper_lock md5 与重启前一致；home/lock wallpaperId/colors 与重启前一致（本批次实测 home=18/red、lock=19/cyan 保持） |
| TC-M-10 UI 渲染 | testapp UI 打开 "Wallpaper" 页（`dumpsys activity top`） | 6 个按钮 + 2 分组标题渲染（btn_wallpaper_home_red/home_green/lock_blue/both_purple/query/probe）；按钮与 IPC 事件一一对应 |
| TC-M-11 事件目录 | `./send_test_command.sh ListEvents` | 目录含 SetWallpaper/GetWallpaper/GetWallpaperStateLocal 三个新事件 |
| TC-M-12 恢复基线 | 桌面 `SetWallpaper target=home color=white`；锁屏：`adb root` 删除 `/data/system/users/0/wallpaper_lock`（与 `wallpaper_lock_orig`）→ `adb shell stop && start` | 桌面壁纸文件 = 白色 PNG、homeId 递增（内容恢复白色）；锁屏渲染回退默认灰底（锁屏 screencap 像素 204,204,204）；**残留说明**：wallpaper_info.xml 中锁屏记录（id/颜色缓存）为本 ROM 持久化副作用仍保留（getWallpaperId(FLAG_LOCK) 非 -1），不影响锁屏渲染与业务语义，如实记录 |

## 3. 验证提示

- 结果查看：`./send_test_command.sh` 输出 `Broadcast completed: data=...` 与 logcat `HYX-TESTAPP-CMD` 行；
- 壁纸状态对照：`adb shell dumpsys wallpaper`（`System wallpaper state: User 0: id=...` / `Lock wallpaper state: User 0: id=...`，与命令 afterId 对照）；
- 文件级对照（需 root）：`adb shell md5sum /data/system/users/0/wallpaper /data/system/users/0/wallpaper_lock`；`adb pull` 后用 PIL/图片查看器解码核对像素 = 下发颜色（纯色图与 imageBytes 字节数一致）；
- 引擎执行日志：`adb shell logcat -d | grep WallpaperPolicyManager`（`setWallpaper target=home 800x480 expectedDominant=-65536 -> ...`）；
- 参数说明：`imageBase64` 为图像字节的 base64（可省——缺省时 testapp 按 color/width/height 生成纯色 PNG，color 支持名称/0xAARRGGBB/#RRGGBB/十进制，未知回退红）；target 支持 home/lock/both；
- 数值对照：-65536=红 0xFFFF0000、-16711936=绿 0xFF00FF00、-16776961=蓝 0xFF0000FF、-65281=紫 0xFFFF00FF、-16711681=青 0xFF00FFFF、-256=黄 0xFFFFFF00、-1=白 0xFFFFFFFF；
- 锁屏可视化：`input keyevent 26` 熄屏 → `input keyevent 224` 亮屏 → `screencap -p /sdcard/xxx.png` → pull 后颜色核验（锁屏帧为竖屏 720×1600）；
- 权限核验：`adb shell dumpsys package com.hmdm.launcher | grep SET_WALLPAPER`（granted=true）；
- UI 注意：本机（银星 ROM）通知栏偶发遮挡（`mCurrentFocus=NotificationShade`），UI 渲染核对用 `dumpsys activity top` 的 ViewHierarchy（按钮 id/文本齐全即通过）。

## 4. 实测结果（2026-08-07，Android 13 / API 33 userdebug，平台签名 + device owner）

全部用例经 `send_test_command.sh` IPC 通道执行（与 UI 按钮共用 TestActions 引擎），关键系统状态经 adb 对照。**设备基线（实测）**：桌面壁纸纯白（homeId=2、primary=-1）、锁屏未设置（lockId=-1）、Launcher 主页自绘深灰背景覆盖桌面壁纸。

| 用例 | 实测结果 |
|---|---|
| TC-0183-01 | 通过：`{"home":{"wallpaperId":2,"colors":{"primaryColor":-1,...}},"lock":{"wallpaperId":-1,...},"success":true,"supported":true}` |
| TC-0183-02 | 通过：`{"applied":[{"beforeId":2,"idChanged":true,"drawableDominantColor":-65536,"flag":"home","success":true,"colorsMatched":true,"drawableDominantMatch":true,"afterId":3,"colors":{"primaryColor":-65536,...},"colorsChanged":true}],"expectedDominantColor":-65536,"width":800,"imageBytes":2635,...}`（框架 primary exact 红） |
| TC-0183-03 | 通过：`beforeId=3→afterId=4`、primary=-16711936 绿、colorsChanged=true、colorsMatched/drawableDominantMatch=true |
| TC-0183-04 | 通过：`md5sum /data/system/users/0/wallpaper` 随设置变化；pull 后 PIL 解码 800×480 主色 = 设置色、文件 2635 字节 = imageBytes |
| TC-0183-05 | 通过：400×200 白 PNG（737 字节）`{"width":400,"height":200,"imageBytes":737,"expectedDominantColor":-1,...colors.primaryColor=-1,colorsMatched=true}` |
| TC-0183-06 | 通过：320×200 黄：`expectedDominantColor=-256,primaryColor=-256,colorsMatched=true`；hex `0xFF800080`：`expectedDominantColor=-8126332`（0x80 系重建）、`primaryColor=-8388480` exact、colorsMatched=true（±16 容差） |
| TC-0183-07 | 通过：1080×1920 多色 PNG（42KB）`{"width":1080,"height":1920,"imageBytes":42296,"expectedDominantColor":-16777216,...drawableDominantMatch=true}`；colorsMatched=false（框架聚类差异，如实上报） |
| TC-0183-08 | 通过：桌面多次设置期间 lock id/colors 保持（lock 恒 6/绿 直到锁屏命令） |
| TC-0184-01 | 通过：`{"beforeId":3,"idChanged":true,"drawableDominantColor":null,"flag":"lock","success":true,"colorsMatched":true,"drawableDominantMatch":false,"afterId":5,"colors":{"primaryColor":-16776961,...},"colorsChanged":true}`（蓝；beforeId=3 为本 ROM 首次初始化特性，见前置条件③） |
| TC-0184-02 | 通过：wallpaper_lock 文件 = 下发 PNG（PIL 解码 = 设置色） |
| TC-0184-03 | 通过：dumpsys `Lock wallpaper state: User 0: id=5` = 命令 afterId |
| TC-0184-04 | 通过：锁屏多次设置期间 home id/colors 保持 |
| TC-0184-05 | 通过：锁屏 screencap 显示壁纸内容（颜色级核验；文件级证据为准） |
| TC-0184-06 | 通过：lock id -1→5→6→…、绿→青（-16711936→-16711681）切换、colorsChanged=true |
| TC-0183/0184-01 | 通过：both 紫 `{"flags":3,"applied":[{flag=home,beforeId=9,afterId=10,...},{flag=lock,beforeId=6,afterId=11,...}],两 flag primary=-65281,colorsMatched=true}` |
| TC-0183/0184-02 | 通过：`{"home":{"wallpaperId":12,...},"lock":{"wallpaperId":11,...}}` 与 dumpsys 一致 |
| TC-0183/0184-03 | 通过：testapp 本地读回 `homeId=12/lockId=11` 与命令一致、homeColors=白/lockColors=紫一致；lockSet=false、lockDominantColor=null、homeDominantColor=null（权限门禁如实上报） |
| TC-0183/0184-04 | 通过：TestBroadcast 派发 `GetWallpaper`（logcat `CallWithCommand command :【GetWallpaper】 is going to run`）；**SetWallpaper 经广播通道执行成功且日志脱敏**：`param:{"target":"home","imageBase64":"***"}`（imageBase64 已入 redactJson，图像内容不落 logcat；命令在工作线程 + goAsync 派发，32×32 绿图 success、id 24→25、colorsMatched=true） |
| TC-M-01 | 通过：`missing parameter: target (home/lock/both)` |
| TC-M-02 | 通过：`{"error":"invalid target: desktop (expected home/lock/both)","success":false}` |
| TC-M-03 | 通过：TestBroadcast 直连缺 imageBase64 → `{"error":"missing parameter: imageBase64"}`；testapp IPC 缺省自动生成默认红色图（`primaryColor=-65536`） |
| TC-M-04 | 通过：`{"error":"invalid base64 image data: bad base-64","success":false}` |
| TC-M-05 | 通过（binder/通道上限拦截路径核验）：超限参数无法经 am broadcast 送达命令层（adb shell 参数约 64KB 上限为实际拦截层，更大载荷须经生产 AIDL 通道；命令层 4 MiB/5.6 MiB 上限为防御性双保险），不 crash |
| TC-M-05b | 通过（尺寸上限）：8193×200 纯色 PNG → `{"error":"image dimensions too large: 8193x200 (max 8192x8192 / 41943040 pixels)","success":false}`，不 crash 不写入；像素上限（>40MP）为同一分支（9000×5000 等载荷超 adb 通道上限，以尺寸分支验证同一代码路径）；**合法大图采样**：4097×100 蓝 → `{"width":4097,"height":100,"decodedWidth":2048,"decodedHeight":50,"sampleSize":2,"primaryColor":-16776961,"colorsMatched":true,"drawableDominantMatch":true}`（原图尺寸如实上报、解码位图采样 ≤4096、纯色主色不受采样影响） |
| TC-M-06 | 通过：`color=unknown` 回退红色（primary=-65536），success=true |
| TC-M-06b | 通过：`color=#FF0000` → 不透明红（primary=-65536、drawableDominantMatch=true）（修复前 alpha=0 生成全透明图，核对忽略 alpha 误报匹配）；`0x`+6 位与 24 位十进制同路径 |
| TC-M-07 | 通过：连续 GetWallpaper 返回一致 |
| TC-M-08 | 通过：Launcher 重装/进程重启后 `GetWallpaper` 正常，home/lock id 与重启前一致（壁纸为系统状态无需重新下发） |
| TC-M-09 | 通过：整机重启后 wallpaper/wallpaper_lock md5 不变（`bc3e366b...`/`a53ea4df...` 前后一致），命令读回 home=18/red、lock=19/cyan 与重启前一致 |
| TC-M-10 | 通过（dumpsys activity top）：WallpaperTestActivity 渲染 6 按钮 + 2 分组标题 |
| TC-M-11 | 通过：ListEvents 目录含 SetWallpaper/GetWallpaper/GetWallpaperStateLocal（logcat 行 4KB 截断为 logcat 限制，单事件可调用验证全过） |
| TC-M-12 | 通过：桌面设回白色（primary=-1）；删除 wallpaper_lock(+_orig) + `stop/start` 后锁屏 screencap 像素 204,204,204（默认灰底回退）；**残留如实记录**：`getWallpaperId(FLAG_LOCK)`=19 与 lockColors 青色缓存保留于 wallpaper_info.xml（本 ROM ABX 持久化副作用，锁屏渲染不受影响） |

**实现决策记录（2026-08-07 真机核验）**：① 桌面/锁屏壁纸经公开 `WallpaperManager.setBitmap` 实现（FLAG_SYSTEM/FLAG_LOCK，target=both 一次调用两 flag），无 @SystemApi/ROM 依赖，manifest 新增 SET_WALLPAPER（normal 级）；② **本 ROM 无 `getLockWallpaperBitmap()`**（framework.jar 运行期 NoSuchMethodException + 裁剪 SDK javap 双核验），锁屏位图读回不可用——锁屏核验改经 id（`getWallpaperId(FLAG_LOCK)`）+ colors（`getWallpaperColors`）+ 文件（wallpaper_lock）+ `dumpsys wallpaper` 四重对照，命令对不可用路径如实上报 null 不伪装；③ **本 ROM `getWallpaperId(FLAG_LOCK)` 未设置时为 -1**（非 AOSP 的 0），首次任意 setBitmap 后锁屏数据对象初始化（id 与当时桌面 id 同值，观测 3），此后读回与 dumpsys 一致——写后核对以"设置前后变化 + colors + 文件"判定，不受初始化值影响；④ 壁纸内容由 WallpaperManagerService 持久化（/data/system/users/0/wallpaper*），**无需 Launcher SharedPreferences 持久化/开机重新下发**（与策略类批次本质不同）；⑤ 主色采样 5bit/通道分桶重建（本批次修复位运算错误：误取 alpha 高位 → 改 r/g/b 各取字节高 5 位），纯色图像与框架 `getWallpaperColors` primary exact 匹配，照片类图像以 `drawableDominantMatch`（实际渲染内容采样）为最强证据，框架聚类差异如实上报 colorsMatched=false；⑥ testapp 本地探测 `getDrawable()` 在本 ROM 需 READ_EXTERNAL_STORAGE（testapp 未授予被拒）——按字段容错、单字段失败如实上报 null，id/colors 字段独立交叉核对；⑦ 锁屏壁纸元数据（id+颜色缓存）持久化于 wallpaper_info.xml（ABX），删除 wallpaper_lock 文件后锁屏渲染回退默认灰底但元数据残留——恢复基线路径与残留如实记录（TC-M-12）。

**部署注意**：本批次不修改 `device_admin.xml`、无 Settings 键写入、**无需重启 framework**（恢复基线删除锁屏文件除外——`stop/start` 软重启后 device owner 保持，验证通过）；manifest 新增 SET_WALLPAPER（normal 权限，安装即授予）；`adb install -r` 重装 Launcher 会结束其进程且不会自动重启，需重新拉起 HOME（`am start -a android.intent.action.MAIN -c android.intent.category.HOME`）后 testapp 进程亦需重启（root `kill <pid>` 后 `am start`，勿用 force-stop——MTK DuraSpeed 会抑制其 manifest receiver）以重建 AIDL 绑定。
