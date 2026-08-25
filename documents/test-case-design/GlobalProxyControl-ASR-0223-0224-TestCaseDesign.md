# 全局代理配置（ASR-0223/0224）测试用例设计文档

## 1. 前置条件

- 设备：Android 13（API 33）userdebug ROM，已部署平台签名 Launcher（`com.hmdm.launcher`，uid=1000）并设为 device owner，testapp（`com.hmdm.testapp`）已安装；
- 部署准备：`adb root`、屏幕常亮（`svc power stayon true`）；
- 测试通道：`./send_test_command.sh <event> key=value ...`；
- 对照命令：`adb shell settings get global global_http_proxy_host/port/exclusion_list`；
- 恢复基线：代理清除（proxySet=false）、DO 在位；
- 本 ROM 特性（2026-08-13 核验）：dpm.getRecommendedGlobalProxy 方法缺失（MTK fork），读回经框架镜像键（global_http_proxy_*）——设置后 1~2s 同步。

## 2. 测试用例表

### 2.1 ASR-0223/0224 全局代理（SetRecommendedGlobalProxy / GetProxyInfo）

| 用例 | 步骤 | 预期结果 |
|---|---|---|
| TC-0223-01 基线 | `./send_test_command.sh GetProxyInfo`；`adb shell settings get global global_http_proxy_host` | proxySet=false；host 空 |
| TC-0223-02 设置 | `./send_test_command.sh SetRecommendedGlobalProxy host=10.0.0.1 port=8080 'bypass=["127.0.0.1","localhost"]'`；等待 2s；`GetProxyInfo`；`settings get global global_http_proxy_host/port/exclusion_list` | RESULT=true；GetProxyInfo={proxySet:true, host:10.0.0.1, port:8080, bypass:[127.0.0.1,localhost]}（含绕过列表）；镜像键一致 |
| TC-0223-03 清除 | `./send_test_command.sh SetRecommendedGlobalProxy host=`；等待 2s；`GetProxyInfo`；`settings get global global_http_proxy_host` | RESULT=true；proxySet=false（恢复基线） |
| TC-0223-04 缺参 | `./send_test_command.sh SetRecommendedGlobalProxy` | 返回 `missing parameter: host`，不 crash |
| TC-0223-05 非法端口 | `SetRecommendedGlobalProxy host=10.0.0.1 port=99999` | RESULT=false，不 crash |
| TC-B11-01 部署校验 | `adb shell dpm list-owners`；`GetConnectionStatus` | DO 在位；bound=true |

## 3. 硬件受限测试说明

- ASR-0223/0224 无硬件依赖；上述用例全部在本机（Android 13 / API 33 userdebug，平台签名 + device owner）执行通过（2026-08-13）。
- 本 ROM DPM 读方法缺失（getRecommendedGlobalProxy）属 MTK fork 特性，读回经框架镜像键（真机核验一致）；代理设置不影响本机 adb/IPC 通道（代理作用于应用网络请求），测试后已清除。
