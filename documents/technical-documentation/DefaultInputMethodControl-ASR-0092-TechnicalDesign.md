# 默认输入法管控（ASR-0092）技术设计文档

## 1. 需求概述

| 需求 ID | 功能项 | 需求语义 | 底层机制 |
|---|---|---|---|
| ASR-0092 | 设置默认输入法应用 | 查询可用输入法、设置/查询默认输入法 | device owner `dpm.setSecureSetting(DEFAULT_INPUT_METHOD)` + `InputMethodManager` 查询 |

**归属**：「Launcher（MDM）+ 系统 API」（setSecureSetting 为 device owner 能力）。

**基线**：目标平台 Android 13（API 33）userdebug，平台签名 + device owner 部署。

## 2. 技术选型与可行性核验

| 方案 | 说明 | 结论 |
|---|---|---|
| device owner `dpm.setSecureSetting(Settings.Secure.DEFAULT_INPUT_METHOD, imeId)` | 公开接口，免交互写入默认输入法（系统关键设置通道，AOSP 白名单含该键）；SettingsProvider 持久化，重启保持 | **采用** |
| `InputMethodManager.setInputMethod`（运行时切换） | 仅切换当前输入会话，不改变默认设置，且非免交互 | 放弃 |
| `cmd input_method` shell 通道 | 本 ROM 无 set-default 等价命令（ime 命令仅 list/enable/disable 子集） | 放弃 |

- 查询：`Settings.Secure.DEFAULT_INPUT_METHOD` 直读（GetDefaultInputMethod）；可用输入法列表经公开 `InputMethodManager.getInputMethodList()`（GetAvailableInputMethods）；
- 写后核对：设置 → `settings get secure default_input_method` / `GetDefaultInputMethod` / testapp 本地 `InputMethodsLocal`（getEnabledInputMethodList）三方对照；
- **边界（2026-08-13 真机核验）**：写入不存在的 imeId 时 `setSecureSetting` 不校验（返回 true、secure 键持有该值、GetDefaultInputMethod 如实返回），但框架运行态无该 IME 时 `mCurMethodId=null` 回落到系统默认——命令如实反映设置值，文档说明该行为；测试结束必须切回原默认输入法（本机 com.android.inputmethod.latin/.LatinIME），避免影响后续批次文本输入。

## 3. 命令接口定义

| 命令名 | 参数（Map） | 返回 RESULT | 需求 |
|---|---|---|---|
| `SetDefaultInputMethod` | imeId（String，必填） | Boolean（setSecureSetting 结果） | ASR-0092 |
| `GetDefaultInputMethod` | 无 | String（secure 键当前值） | ASR-0092 |
| `GetAvailableInputMethods` | 无 | List\<String\>（可用 IME id 列表） | ASR-0092 |

**调用示例**：

```java
Map<String, Object> p = new HashMap<>();
p.put("imeId", "org.fcitx.fcitx5.android/.input.FcitxInputMethodService");
Map result = api.onEvent("SetDefaultInputMethod", p);
// {"RESULT":true}
Map result2 = api.onEvent("GetDefaultInputMethod", null);
// {"RESULT":"org.fcitx.fcitx5.android/.input.FcitxInputMethodService"}
```

## 4. 实现结构

```
app/src/main/java/com/hmdm/launcher/
├── syrius/utils/InputMethodManagerHelper.java     # setDefaultInputMethod（dpm.setSecureSetting）
│                                                 # getCurrentDefaultInputMethod / getAvailableInputMethods
├── syrius/service/command/input_method/
│   ├── SetDefaultInputMethod.java / GetDefaultInputMethod.java / GetAvailableInputMethods.java
│   ├── DisableOtherInputMethods.java / EnableAllInputMethods.java（既有，辅助）
└── syrius/service/ApiBinder.java                 # 既有注册

testapp/
└── src/main/java/com/hmdm/testapp/
    ├── TestActions.java                          # Set/GetDefaultInputMethod、GetAvailableInputMethods、
    │                                             # InputMethodsLocal 事件
    ├── InputMethodVerifier.java                  # 新增：本地已启用 IME 列表探针
    ├── DefaultInputMethodTestActivity.java       # 新增测试页
    └── src/main/res/layout/activity_default_input_method_test.xml
```

## 5. 执行逻辑

```
SetDefaultInputMethod(imeId):
  1. 参数校验（空 imeId → false；testapp 侧拦截 missing parameter）
  2. dpm.setSecureSetting(admin, Settings.Secure.DEFAULT_INPUT_METHOD, imeId)
  3. 返回调用结果；GetDefaultInputMethod + settings get 读回核对
  4. 不存在的 imeId：设置值如实写入并如实返回（框架运行态回落默认，文档说明）

GetDefaultInputMethod(): Settings.Secure.DEFAULT_INPUT_METHOD 直读
GetAvailableInputMethods(): InputMethodManager.getInputMethodList() → id 列表
```

**安全设计**：imeId 为字符串参数，经 dpm.setSecureSetting 白名单键写入（无注入面）；调用方经 ApiBinder 门禁。

## 6. 权限与归属

- device owner 公开 `setSecureSetting`（无需权限声明）；InputMethodManager 查询为公开 API；
- testapp 本地探针用公开 InputMethodManager（无新增权限）；
- 无 ROM 侧代码改动，无 root 依赖。

## 7. 边界与异常处理

| 场景 | 处理 |
|---|---|
| 缺 imeId | testapp 侧返回 missing parameter，不 crash |
| 无效 imeId（不存在） | setSecureSetting 不校验（true），secure 键持有该值、GetDefaultInputMethod 如实返回；框架运行态 mCurMethodId=null 回落系统默认（真机实测，文档记录）；测试后立即切回有效 IME |
| 非 DO 环境 | setSecureSetting 抛 SecurityException → 捕获返回 false（引擎内 catch） |
| 测试间相互影响 | 切换输入法后必须切回原默认（本机 latin），避免影响后续批次 |

## 8. 真机验证记录（2026-08-13，Android 13 / API 33 userdebug，平台签名 + device owner）

| 核验项 | 结果 |
|---|---|
| 可用输入法 | GetAvailableInputMethods → [com.android.inputmethod.latin/.LatinIME, org.fcitx.fcitx5.android/.input.FcitxInputMethodService]（本机 fcitx 已装未启用为默认） |
| 基线 | GetDefaultInputMethod → com.android.inputmethod.latin/.LatinIME |
| 切换默认输入法 | SetDefaultInputMethod(org.fcitx.fcitx5.android/.input.FcitxInputMethodService) → true；GetDefaultInputMethod 与 `settings get secure default_input_method` 一致返回 fcitx ID |
| 切回恢复 | SetDefaultInputMethod(latin) → true；GetDefaultInputMethod → latin（基线恢复） |
| 无效 ID 边界 | SetDefaultInputMethod(com.nonexistent.ime/.FakeIme) → true；secure 键持有该值、GetDefaultInputMethod 如实返回；`dumpsys input_method` mCurMethodId=null（框架回落）；随后切回 latin 恢复 |
| 测试后状态 | 默认输入法恢复 com.android.inputmethod.latin/.LatinIME；DO 在位 |
