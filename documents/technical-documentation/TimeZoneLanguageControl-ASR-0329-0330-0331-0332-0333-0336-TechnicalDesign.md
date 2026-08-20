# 时间/时区/语言管控（ASR-0329/0330/0331/0332/0333/0336）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 底层机制 |
|---|---|---|---|
| ASR-0329 | 获取/设置是否允许修改时间 | 禁止/允许用户修改日期时间设置、查询状态 | device owner `DISALLOW_CONFIG_DATE_TIME` 用户限制（2026-08-13 补查询命令） |
| ASR-0330 | 是否允许用户手动修改时间 | 同 ASR-0329（共用命令） | 同上 |
| ASR-0331 | 设置系统时间 | 免交互设置系统时间 | `SystemClock.setCurrentTimeMillis`（SET_TIME 签名权限——**2026-08-13 补 manifest 声明**） |
| ASR-0332 | 改变时区 | 设置/切换系统时区 | `AlarmManager.setTimeZone`（TimeAndZoneController，SET_TIME_ZONE 既有声明） |
| ASR-0333 | 是否允许用户手动修改时区 | 限制用户修改时区 + 自动时区开关 | `DISALLOW_CONFIG_DATE_TIME` + `dpm.setAutoTimeZoneEnabled` |
| ASR-0336 | 修改系统语言 | 免交互切换系统语言 | 反射 `ActivityManager.updateConfiguration` + LocaleList |

**归属**：「Launcher（MDM）」（平台签名）。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署；本机基线：语言 en/US、时区 Asia/Shanghai、自动时区开、时间与宿主机同步。

## 2. 技术选型与可行性核验

| 方案 | 说明 | 结论 |
|---|---|---|
| `DISALLOW_CONFIG_DATE_TIME`（0329/0330/0333） | device owner 用户限制（TimeAndZoneController.restrict/allowUserTimeSettings） | **采用** |
| `SystemClock.setCurrentTimeMillis`（0331） | SET_TIME 签名权限——**2026-08-13 真机核验：Launcher manifest 缺 SET_TIME 声明，命令返回 false（SecurityException）；补声明后成功**（platform 签名自动授予，无需重启 framework） | **采用**（已补声明） |
| `AlarmManager.setTimeZone`（0332） | 公开 API（SET_TIME_ZONE 既有声明）；写后 TimeZone 读回核对 | **采用** |
| `dpm.setAutoTimeZoneEnabled`（0333） | device owner 公开接口（R+） | **采用** |
| `ActivityManager.updateConfiguration` 反射（0336） | LocaleUtil：getService → getConfiguration → setLocales → updateConfiguration；**语言标签须为完整形式（zh-CN/en-US）**——Locale(language, country) 构造，language 不带地区、country 独立参数（2026-08-13 真机核验："en" 单独传 country=null 时 updateConfiguration 返回 false） | **采用** |

**2026-08-13 本批次修复/补注册**：
1. **补命令** `IsDisallowConfigDataTime`（0329/0330/0333 查询——原仅有设置命令）；
2. **manifest 补 SET_TIME 声明**（0331 原命令恒 false）；
3. testapp 事件参数名核对：`DisallowConfigDataTime` 用 `disallow`（非 disabled）、`SetTimeZone` 用 `timezoneId`（非 timeZone）、`SetTime` 用 `time`（非 timeMillis）——首版误用已修正并记录（与 B12/B13 同类参数名核对项）。

## 3. 命令接口定义

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `DisallowConfigDataTime` | disallow（Boolean，必填） | Boolean | ASR-0329/0330/0333 |
| `IsDisallowConfigDataTime` | 无 | Boolean（2026-08-13 新增） | ASR-0329/0330 |
| `SetTime` | time（long millis，必填） | Boolean | ASR-0331 |
| `SetTimeZone` | timezoneId（String，必填，如 Asia/Shanghai） | Boolean | ASR-0332 |
| `SetAutoTimeZoneEnabled` | enabled（Boolean，必填） | Boolean | ASR-0333 |
| `IsAutoTimeZoneEnabled` | 无 | Boolean | ASR-0333 |
| `SetLanguage` | language（String，必填，如 zh-CN）、country（可选） | Boolean | ASR-0336 |
| `GetLanguage` | 无 | Map{language, country} | ASR-0336 |

**调用示例**：

```java
Map<String, Object> p = new HashMap<>();
p.put("time", System.currentTimeMillis() + 60000);
Map result = api.onEvent("SetTime", p);
// {"RESULT":true}

Map<String, Object> p2 = new HashMap<>();
p2.put("language", "zh-CN");
Map result2 = api.onEvent("SetLanguage", p2);
// {"RESULT":true}；GetLanguage → {language:zh, country:CN}
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/
├── syrius/utils/TimeAndZoneController.java        # setTime（SystemClock）/ setTimeZone（AlarmManager）
│                                                 # restrict/allowUserTimeSettings（限制）
│                                                 # setAutoTimeZoneEnabled（dpm，R+）
├── syrius/utils/LocaleUtil.java                  # changeSystemLanguage（反射 updateConfiguration）
├── syrius/service/command/time/                  # SetTime/SetTimeZone/SetAutoTimeZoneEnabled/
│                                                 # IsAutoTimeZoneEnabled/DisallowConfigDataTime
│                                                 # IsDisallowConfigDataTime（2026-08-13 新增）
├── syrius/service/command/language/              # SetLanguage/GetLanguage（既有）
├── syrius/service/ApiBinder.java                 # 注册 IsDisallowConfigDataTime
└── AndroidManifest.xml                           # 2026-08-13 补 SET_TIME 声明

testapp/
└── src/main/java/com/hmdm/testapp/
    ├── TestActions.java                          # 9 个事件（含本地探针 GetTimeZoneLocal/SystemTimeLocal）
    ├── TimeLanguageControlTestActivity.java      # 新增测试页
    └── src/main/res/layout/activity_time_language_test.xml
```

## 5. 执行逻辑

```
DisallowConfigDataTime(disallow): DISALLOW_CONFIG_DATE_TIME 加/除（TimeAndZoneController）
SetTime(time): SystemClock.setCurrentTimeMillis(time)（SET_TIME，2026-08-13 补声明）
SetTimeZone(timezoneId): AlarmManager.setTimeZone(timezoneId)；TimeZone.getDefault 读回核对
SetAutoTimeZoneEnabled(enabled): dpm.setAutoTimeZoneEnabled(admin, enabled)
SetLanguage(language, country): LocaleUtil 反射 updateConfiguration
  （语言标签完整形式：language=zh、country=CN；"en" 无 country 时本 ROM 返回 false——文档记录）
```

**安全设计**：SET_TIME/SET_TIME_ZONE 为平台签名自动授予；调用方经 ApiBinder 门禁；SetTime 持久化——测试须恢复原时间。

## 6. 权限与归属

- SET_TIME（2026-08-13 补声明）/SET_TIME_ZONE（既有）签名权限；DISALLOW_CONFIG_DATE_TIME/setAutoTimeZoneEnabled 为 device owner 公开接口；updateConfiguration 经平台签名反射（CHANGE_CONFIGURATION 隐含）；
- testapp：无新增权限；无 ROM 侧代码改动，无 root 依赖。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺参 | testapp 侧返回 missing parameter，不 crash |
| SET_TIME 缺失（历史构建） | 命令返回 false（2026-08-13 已补 manifest 声明） |
| 语言标签不含地区 | Locale(language, null) 本 ROM updateConfiguration 返回 false——文档记录，测试用完整标签（zh-CN/en-US） |
| 自动时区开启时 SetTimeZone | 时区可能被自动同步覆盖——测试先关自动时区再切时区（真机流程） |
| SetTime 持久性 | 测试后必须恢复原时间（与宿主机同步） |
| 参数名核对 | disallow/timezoneId/time 为命令实际参数名（B12/B13 同类项） |

## 8. 真机验证记录（2026-08-13，Android 13 / API 33 userdebug，平台签名 + device owner）

| 核验项 | 结果 |
|---|---|
| ASR-0329/0330 | DisallowConfigDataTime(disallow=true) → Is=true；false → Is=false（往返一致；参数名 disallow） |
| ASR-0333 | SetAutoTimeZoneEnabled(false) → Is=false；true → Is=true（恢复基线） |
| ASR-0332 | 关自动时区后 SetTimeZone(Asia/Shanghai↔Etc/UTC) → true、TimeZone 读回一致（时区切换生效，设备时钟随 UTC 跳变可见）；恢复 Asia/Shanghai |
| ASR-0331 | 原实现因 manifest 缺 SET_TIME 恒 false → **补声明后 SetTime(now+120s) → true、系统时间跳变确认** → 恢复与宿主机同步（参数名 time） |
| ASR-0336 | SetLanguage(zh-CN) → true、GetLanguage={zh,CN}（系统语言切换生效）；SetLanguage(en) 单独无 country 返回 false（记录）；SetLanguage(en-US) → true、恢复 {en,US} 基线 |
| 测试后状态 | 时间/时区（Asia/Shanghai）/语言（en-US）/自动时区（开）/日期时间限制（false）全部恢复基线；DO 在位 |
