# CLAUDE.md

## 1. Project Overview

**Second-Supplier Phone (二供手机)** = the Android component the robot is about to use ("二供" = second supplier). This repository is used to verify and test the **ROM** and the in-house **MDM** of the second-supplier phone.

### Background: Robot Dual-OS Architecture

- The robot operating system consists of **Linux + Android**, and the two can communicate with each other.
- **Android → Linux**: Android can access Linux via SSH (with sudo privileges).
- **Linux → Android**: Linux can access Android via ADB Shell.
- The two are connected via **USB AoA (Android Open Accessory)**.

### Project Goals

1. Test whether the supplier-provided **ROM** meets the requirements (`documents/安卓系统软件需求.md`).
2. Test whether the in-house **MDM** (`mdm_launcher/hmdm-android`, the Launcher app) meets the project requirements.

## 2. Key Directories and Files

| Path                                             | Description                                                                                                                                                |
| ------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `documents/technical-documentation/*.md`         | Technical design docs of the MDM implementation (named by ASR requirement ID, e.g. `PermissionGrantControl-ASR-0037-0042-TechnicalDesign.md`)              |
| `documents/test-case-design/*.md`                | Test case design docs (one-to-one with the technical docs)                                                                                                 |
| `documents/安卓系统软件需求.md`                          | Requirements source for the ROM and MDM (**authoritative requirements**)                                                                                   |
| `documents/requirements-rationality-analysis.md` | Rationality review of the system software requirements in `documents/安卓系统软件需求.md` targeting Android 13 (API 33)                                            |
| `documents/Sheet1需求完成情况对照.md`                    | Comparison doc of currently completed MDM requirements (**authoritative completion status**)                                                               |
| `documents/system_configurations.md`             | Records the content written to system configuration files during MDM feature implementation (app-side declarations, DPM policy state, Settings keys, etc.) |
| `documents/TestApp-UsageGuide.md`                | Explains how to use `mdm_launcher/hmdm-android/testapp` (broadcast events / AIDL interface description)                                                    |
| `mdm_launcher/hmdm-android/`                     | MDM Launcher and testapp source; `send_test_command.sh` / `send_test_broadcast.sh` test channel scripts; `testapp-signed.apk`                              |
| `test_case/`                                     | Output directory for the generated full test cases in Markdown (create it if it does not exist)                                                            |

## 3. Architecture and Constraints

### 3.1 Requirement Handling

- When in doubt about a requirement, the Agent **must confirm with the user and clarify all ambiguities before proceeding**.
- **Do not start implementation** while requirements are unclear.

### 3.2 Documentation Consistency

- When making changes, must check consistency among: requirement docs, design docs, test case design docs, usage manuals, and `README.md`.
- If an inconsistency is found, the Agent **must update README.md** (create it if it does not exist).

### 3.3 Test Case Authoring Constraints

**Format requirements:**

- Must include the fields: case ID, client, module, test case priority, test purpose, preconditions, test steps, expected result, actual result, remarks.
- Test steps must be detailed enough for a tester with no technical background to operate directly.
- Expected results must be specific, verifiable, and unambiguous.

**Content requirements:**

- Cover the normal, boundary, and exceptional scenarios of all requirement-related function points.
- Preconditions must clearly specify the test environment configuration, device state, and necessary preparation.
- Test steps must be numbered in execution order, **with exactly one operation per step**.
- Remarks must include cautions, special configurations, or dependencies during testing.
- Expected results must correspond to the test steps one by one.

**Output requirements:**

- Save the generated full test cases as Markdown into the `test_case/` folder.
- Ensure the test cases are complete and executable without testers needing additional technical knowledge.

## 4. Test Environment and Test Channels

- **Target device**: second-supplier phone, Android 13 (API 33) userdebug, MTK fork of AOSP 13 (MT6771), example serial `P65241284D50911SC00`.
- **MDM deployment**: `com.hmdm.launcher` (platform-signed, uid=1000, device owner); `com.hmdm.testapp` (testapp) is the test IPC client.
- **Test channel**: `./send_test_command.sh <event> [key=value ...]` (internally `am broadcast -n com.hmdm.testapp/.TestCommandReceiver -a com.hmdm.testapp.CMD --es event <E> --es param '<json>'`); results are confirmed via both `Broadcast completed: result=0, data=<json>` and the logcat tag `HYX-TESTAPP-CMD`.
- **Running scripts on Windows**: WSL/bash is available (`C:\Users\Administrator\AppData\Local\Microsoft\WindowsApps\bash.exe`); invoke with `ADB=<absolute path of the Windows adb>` overriding the script's default adb path (verified working).

### 4.1 Windows PowerShell → adb Argument Passing Limitations (important)

- Passing `--es param '{"..."}'` to adb directly from PowerShell strips the double quotes, and the device mksh performs brace expansion, corrupting the JSON (e.g. `dat=granted:`).
- **Reliable channel**: write the command into a `.sh` script → `adb push` to `/data/local/tmp/` → `adb shell sh /data/local/tmp/x.sh`; or call `send_test_command.sh` from WSL/bash.
- Avoid `2>/dev/null` in PowerShell (it redirects to `C:\dev\null`); use `2>$null` or omit it.

### 4.2 Known Platform/Version Limitations (verified; read before writing test cases)

- **testapp v1.0 (versionCode=1) missing events**: the installed version lacks many events described in the technical docs (GrantAllRuntimePermission, CheckPermissionsLocal, GrantOverlay, CheckGrantOverlay, TryShowOverlay, GetZenMode, SetZenMode direct broadcasts, etc.), returning `unknown event`. **Run** **`ListEvents`** **to verify event availability before executing test cases**; the technical/design docs may diverge from the device code — **trust what is actually verified on the device**.

## 5. Test Execution Guidelines (Black-box Ledger)

- Case ledger: `documents/2026_08_19测试用例.xlsx` (fields per section 3.3).
- Result verdicts: **PASS** / **FAIL** (does not match the expected result; must record evidence) / **BLOCK** (environment cannot execute; must state the reason).
- **Substitution channel discipline**: when testapp events are missing, equivalent channels (pm commands, cmd notification, etc.) may be used to verify, but must be explicitly marked as a "substitution channel" in the actual result/remarks; a PASS must not be fabricated.
- **Baseline restoration discipline**: after tests, restore the device baseline (permission granted state, DND off, lock task released, temp files cleaned up), verify it, and reboot the whole device.
- **Evidence discipline**: keep command output / screenshots for key results (e.g. `documents/g37_perm.png`), and record the device serial and time.
- **Excel writing caution**: openpyxl raises `PermissionError` when Excel/WPS has the same file open (ask the user to close it first); after writing, re-read and verify the column alignment (historically the "actual result" shifted into the "remarks" column).
- Delete temporary scripts after use (both local and `/data/local/tmp/`).

## 6. Suggested Workflow

1. **Requirement clarification**: on ambiguity, confirm with the user first (AskUserQuestion); do not make assumptions on your own.
2. **Status comparison**: check `documents/Sheet1需求完成情况对照.md` and `documents/安卓系统软件需求.md` to determine the requirement completion status and verification targets.
3. **Document review**: consult the `documents/technical-documentation/` and `documents/test-case-design/` docs for the corresponding ASR; use `ListEvents` to verify testapp event availability before execution.
4. **Execute and record**: write/execute the test cases, write back to the ledger (with substitution channel notes and evidence), then restore the baseline.
5. **Wrap up**: clean up temporary files, check documentation consistency (including `README.md`), and update if necessary.

## 7. Agent Skills

### Issue tracker

- Issues and specs live as GitHub issues, managed via the `gh` CLI.

### Triage labels

- Default five-role labels: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`.

### Single-context

- Root `CONTEXT.md` + `docs/adr/`.

