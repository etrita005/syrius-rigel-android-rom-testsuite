package com.hmdm.testapp;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for every test action of this app.
 * <p>
 * Both the test activities (UI buttons) and the TestCommandReceiver
 * (adb shell IPC) invoke these exact same methods, so the IPC interface stays
 * fully equivalent to the on-screen test content.
 */
public final class TestActions {

    public static final String RESULT_KEY = "RESULT";

    private TestActions() {
    }

    /**
     * Dispatch a test action by its event name and return the result.
     * The result map always contains a RESULT_KEY entry with either the
     * Launcher response, a verification outcome, or an error/usage message.
     */
    public static Map<Object, Object> execute(Context context, String event, Map<String, Object> param) {
        Map<Object, Object> result = new HashMap<>();
        Map<String, Object> p = param != null ? param : new HashMap<String, Object>();
        try {
            if (event == null || event.trim().isEmpty()) {
                result.put(RESULT_KEY, "missing event");
            } else if (event.equalsIgnoreCase("ListEvents")) {
                result.put(RESULT_KEY, listEvents());
            } else if (event.equals("GetConnectionStatus")) {
                result.put(RESULT_KEY, getConnectionStatus(context));
            } else if (event.equals("SetNotificationsEnabledForPackage")) {
                String pkg = str(p, "packageName");
                if (pkg.isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: packageName");
                } else if (!p.containsKey("enabled")) {
                    result.put(RESULT_KEY, "missing parameter: enabled");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("IsNotificationsEnabledForPackage")) {
                String pkg = str(p, "packageName");
                if (pkg.isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: packageName");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("SetNotificationsWhitelist") || event.equals("SetNotificationsBlacklist")) {
                if (p.containsKey("packageNames") && p.get("packageNames") instanceof List) {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                } else {
                    result.put(RESULT_KEY, "missing parameter: packageNames (array)");
                }
            } else if (event.equals("GetNotificationsWhitelist") || event.equals("GetNotificationsBlacklist")
                    || event.equals("GetNotificationsPolicyMode") || event.equals("ApplyNotificationsPolicy")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("SetNotificationsPolicyMode")) {
                if (!p.containsKey("mode")) {
                    result.put(RESULT_KEY, "missing parameter: mode (0/1/2)");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("SetLockscreenNotificationsDisabled") || event.equals("IsLockscreenNotificationsDisabled")) {
                if (event.equals("IsLockscreenNotificationsDisabled")) {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                } else if (!p.containsKey("disabled")) {
                    result.put(RESULT_KEY, "missing parameter: disabled");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("SendTestNotification")) {
                NotificationSender.send(context);
                result.put(RESULT_KEY, "test notification sent");
            } else if (event.equals("SetCameraDisabled") || event.equals("SetMicrophoneDisabled")) {
                if (!p.containsKey("disabled")) {
                    result.put(RESULT_KEY, "missing parameter: disabled");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("IsCameraDisabled") || event.equals("IsMicrophoneDisabled")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("TryOpenCamera")) {
                result.put(RESULT_KEY, SensorVerifier.tryOpenCamera(context));
            } else if (event.equals("TryRecordAudio")) {
                result.put(RESULT_KEY, SensorVerifier.tryRecordAudio());
            } else if (event.equals("GetLogBufferSize")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("SetLogBufferSize")) {
                String size = str(p, "size");
                if (size.isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: size");
                } else {
                    if (str(p, "buffer").isEmpty()) {
                        p.remove("buffer");
                    }
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("SetLogLevel") || event.equals("GetLogLevel")) {
                String tag = str(p, "tag");
                if (tag.isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: tag");
                } else {
                    if (event.equals("SetLogLevel") && str(p, "level").isEmpty()) {
                        p.remove("level");
                    }
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("EmitTestLogs")) {
                String tag = str(p, "tag");
                if (tag.isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: tag");
                } else {
                    Log.v(tag, "HYX-MDM-TESTAPP verbose log " + System.currentTimeMillis());
                    Log.d(tag, "HYX-MDM-TESTAPP debug log " + System.currentTimeMillis());
                    result.put(RESULT_KEY, "emitted V/D logs with tag " + tag);
                }
            } else if (event.equals("RequestPostNotificationsPermission")) {
                result.put(RESULT_KEY, "permission requests need UI: adb shell am start -n com.hmdm.testapp/.PermissionActivity --es permissions POST_NOTIFICATIONS");
            } else if (event.equals("RequestSensorPermissions")) {
                result.put(RESULT_KEY, "permission requests need UI: adb shell am start -n com.hmdm.testapp/.PermissionActivity --es permissions CAMERA,RECORD_AUDIO");
            } else if (event.equals("SetDomainWhitelist") || event.equals("SetDomainBlacklist")) {
                if (p.containsKey("domains") && p.get("domains") instanceof List) {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                } else {
                    result.put(RESULT_KEY, "missing parameter: domains (array)");
                }
            } else if (event.equals("GetDomainWhitelist") || event.equals("GetDomainBlacklist")
                    || event.equals("GetDomainPolicyMode") || event.equals("GetIpWhitelist")
                    || event.equals("GetIpBlacklist") || event.equals("GetIpPolicyMode")
                    || event.equals("GetNetworkFirewallStatus")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("SetDomainPolicyMode") || event.equals("SetIpPolicyMode")) {
                if (!p.containsKey("mode")) {
                    result.put(RESULT_KEY, "missing parameter: mode (0/1/2)");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("SetIpWhitelist") || event.equals("SetIpBlacklist")) {
                if (p.containsKey("ips") && p.get("ips") instanceof List) {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                } else {
                    result.put(RESULT_KEY, "missing parameter: ips (array)");
                }
            } else if (event.equals("TestDnsLookup")) {
                String host = str(p, "host");
                if (host.isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: host");
                } else {
                    result.put(RESULT_KEY, NetworkVerifier.dnsLookup(host));
                }
            } else if (event.equals("TestTcpConnect")) {
                String host = str(p, "host");
                if (host.isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: host");
                } else {
                    String port = str(p, "port");
                    result.put(RESULT_KEY, NetworkVerifier.tcpConnect(host, port.isEmpty() ? "80" : port));
                }
            } else if (event.equals("TestHttpGet")) {
                String url = str(p, "url");
                if (url.isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: url");
                } else {
                    result.put(RESULT_KEY, NetworkVerifier.httpGet(url));
                }
            } else if (event.equals("QueryAppTraffic")) {
                if (!checkNetworkParam(p)) {
                    result.put(RESULT_KEY, "invalid parameter: network (all/wifi/mobile)");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("QueryAppBattery") || event.equals("QueryAppRuntime")
                    || event.equals("QueryRunningApps") || event.equals("QueryAppCrashInfo")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("KillAppProcess")) {
                String pkg = str(p, "packageName");
                if (pkg.isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: packageName");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("KillBackgroundProcesses")) {
                if (p.containsKey("except") && !(p.get("except") instanceof List)) {
                    result.put(RESULT_KEY, "invalid parameter: except (array)");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("SetBlockedRunningWhitelist")) {
                if (p.containsKey("packageNames") && p.get("packageNames") instanceof List) {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                } else {
                    result.put(RESULT_KEY, "missing parameter: packageNames (array)");
                }
            } else if (event.equals("GetBlockedRunningWhitelist") || event.equals("IsPackageSuspended")) {
                if (event.equals("IsPackageSuspended") && str(p, "packageName").isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: packageName");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("SetIgnoreBatteryOptimizationWhitelist")) {
                if (p.containsKey("packageNames") && p.get("packageNames") instanceof List) {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                } else {
                    result.put(RESULT_KEY, "missing parameter: packageNames (array)");
                }
            } else if (event.equals("GetIgnoreBatteryOptimizationWhitelist") || event.equals("IsIgnoringBatteryOptimization")) {
                if (event.equals("IsIgnoringBatteryOptimization") && str(p, "packageName").isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: packageName");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("SetApplicationHidden")) {
                String pkg = str(p, "packageName");
                if (pkg.isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: packageName");
                } else if (!p.containsKey("hidden")) {
                    result.put(RESULT_KEY, "missing parameter: hidden");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("IsApplicationHidden")) {
                String pkg = str(p, "packageName");
                if (pkg.isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: packageName");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("SetCaptivePortalDisabled") || event.equals("SetAlwaysFinishActivitiesDisabled")
                    || event.equals("SetMockLocationDisabled") || event.equals("SetGestureNavigationDisabled")
                    || event.equals("SetAnimationsDisabled")) {
                if (!p.containsKey("disabled")) {
                    result.put(RESULT_KEY, "missing parameter: disabled");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("IsCaptivePortalDisabled") || event.equals("IsAlwaysFinishActivitiesDisabled")
                    || event.equals("IsMockLocationDisabled") || event.equals("IsGestureNavigationDisabled")
                    || event.equals("IsAnimationsDisabled")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("SetLocationMode")) {
                if (!p.containsKey("mode")) {
                    result.put(RESULT_KEY, "missing parameter: mode (0/1/2/3)");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("GetLocationMode")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("SetStrongAuthTimeout") || event.equals("SetPasswordExpirationTimeout")) {
                if (!p.containsKey("timeoutMs")) {
                    result.put(RESULT_KEY, "missing parameter: timeoutMs");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("GetStrongAuthTimeout") || event.equals("GetPasswordExpirationTimeout")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("SetConsecutiveDigitsLimit")) {
                if (!p.containsKey("limit")) {
                    result.put(RESULT_KEY, "missing parameter: limit");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("GetConsecutiveDigitsLimit")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("SetBackupDisabled") || event.equals("SetGoogleAccountsDisabled")
                    || event.equals("SetBackupRestoreDisabled")) {
                if (!p.containsKey("disabled")) {
                    result.put(RESULT_KEY, "missing parameter: disabled");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("IsBackupDisabled") || event.equals("IsGoogleAccountsDisabled")
                    || event.equals("IsBackupRestoreDisabled")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("SetAutoSync") || event.equals("SetGoogleAccountAutoSync")) {
                if (!p.containsKey("enabled")) {
                    result.put(RESULT_KEY, "missing parameter: enabled");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("IsAutoSync") || event.equals("IsGoogleAccountAutoSync")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("SetAccessibilityShortcutDisabled") || event.equals("SetScreenshotsDisabled")
                    || event.equals("SetOnlineFotaDisabled") || event.equals("SetStatusBarNotificationsDisabled")
                    || event.equals("SetBackKeyDisabled")) {
                if (!p.containsKey("disabled")) {
                    result.put(RESULT_KEY, "missing parameter: disabled");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("IsAccessibilityShortcutDisabled") || event.equals("IsScreenshotsDisabled")
                    || event.equals("IsOnlineFotaDisabled") || event.equals("IsStatusBarNotificationsDisabled")
                    || event.equals("IsBackKeyDisabled")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("FotaCheckUpdate") || event.equals("FotaDownloadUpdate")
                    || event.equals("FotaApply") || event.equals("SetSwitchSlotOnReboot")) {
                OtaCallbackRecorder.ensureRegistered(context);
                Object configValue = p.get("config");
                String config;
                if (configValue instanceof Map) {
                    // send_test_command.sh passes JSON-object values unquoted,
                    // so config arrives as a Map; serialize it back to JSON.
                    config = new org.json.JSONObject((Map) configValue).toString();
                } else if (configValue != null) {
                    config = String.valueOf(configValue);
                } else {
                    // No config key: the caller passed the config fields
                    // directly as the param map; serialize them to JSON.
                    config = new org.json.JSONObject(p).toString();
                }
                if (config.isEmpty() || config.equals("{}")) {
                    result.put(RESULT_KEY, "missing parameter: config (JSON)");
                } else {
                    Map<String, Object> callParam = new HashMap<>();
                    callParam.put("config", config);
                    result.put(RESULT_KEY, apiCall(context, event, callParam).get(RESULT_KEY));
                }
            } else if (event.equals("SetLocalOtaEnabled")) {
                if (!p.containsKey("enabled")) {
                    result.put(RESULT_KEY, "missing parameter: enabled");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("IsLocalOtaEnabled") || event.equals("FotaCancel")
                    || event.equals("FotaSuspend") || event.equals("FotaResume")
                    || event.equals("GetSlotInfo")) {
                OtaCallbackRecorder.ensureRegistered(context);
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("FotaStartLocal")) {
                OtaCallbackRecorder.ensureRegistered(context);
                // Exercises FotaStart with a real content:// Uri (the IPC
                // broadcast channel cannot carry a Parcelable Uri).
                Map<String, Object> callParam = new HashMap<>();
                callParam.put("actionId", str(p, "actionId"));
                callParam.put("otaFileUri",
                        android.net.Uri.parse("content://com.hmdm.testapp/ota/update.zip"));
                result.put(RESULT_KEY, apiCall(context, "FotaStart", callParam).get(RESULT_KEY));
            } else if (event.equals("GetOtaCallbackLog")) {
                OtaCallbackRecorder.ensureRegistered(context);
                result.put(RESULT_KEY, OtaCallbackRecorder.getLog());
            } else if (event.equals("ClearOtaCallbackLog")) {
                OtaCallbackRecorder.ensureRegistered(context);
                result.put(RESULT_KEY, OtaCallbackRecorder.clear());
            } else if (event.equals("SetAccessibilityServiceEnabled")) {
                if (str(p, "component").isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: component");
                } else if (!p.containsKey("enabled")) {
                    result.put(RESULT_KEY, "missing parameter: enabled");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("IsAccessibilityServiceEnabled")) {
                if (str(p, "component").isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: component");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("GetAccessibilityServiceState") || event.equals("GetAccessibilityServicePolicyMode")
                    || event.equals("GetAccessibilityServiceWhitelist") || event.equals("GetAccessibilityServiceBlacklist")
                    || event.equals("ApplyAccessibilityServicePolicy")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("SetAccessibilityServicePolicyMode")) {
                if (!p.containsKey("mode")) {
                    result.put(RESULT_KEY, "missing parameter: mode (0/1/2)");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("SetAccessibilityServiceWhitelist") || event.equals("SetAccessibilityServiceBlacklist")) {
                if (p.containsKey("components") && p.get("components") instanceof List) {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                } else {
                    result.put(RESULT_KEY, "missing parameter: components (array)");
                }
            } else if (event.equals("SetApConfigLockdown") || event.equals("SetManualAddWifiDisabled")
                    || event.equals("SetUserConfigWifiDisabled") || event.equals("SetWifiDirectDisabled")
                    || event.equals("SetUserConfigTetheringDisabled")) {
                if (!p.containsKey("disabled")) {
                    result.put(RESULT_KEY, "missing parameter: disabled");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("IsApConfigLockdown") || event.equals("IsManualAddWifiDisabled")
                    || event.equals("IsUserConfigWifiDisabled") || event.equals("IsWifiDirectDisabled")
                    || event.equals("IsUserConfigTetheringDisabled")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("SetWifiSsidWhitelist")) {
                if (!p.containsKey("enabled")) {
                    result.put(RESULT_KEY, "missing parameter: enabled");
                } else if (Boolean.parseBoolean(str(p, "enabled"))
                        && !(p.containsKey("ssids") && p.get("ssids") instanceof List)) {
                    result.put(RESULT_KEY, "missing parameter: ssids (array)");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("GetWifiSsidWhitelist")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("SetMinimumWifiSecurityLevel")) {
                if (!p.containsKey("level")) {
                    result.put(RESULT_KEY, "missing parameter: level");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("GetMinimumWifiSecurityLevel")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("TryAddWifiNetwork")) {
                result.put(RESULT_KEY, WifiVerifier.tryAddWifiNetwork(context));
            } else if (event.equals("TryConnectOpenWifi")) {
                String ssid = str(p, "ssid");
                if (ssid.isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: ssid");
                } else {
                    result.put(RESULT_KEY, WifiVerifier.tryConnectOpenWifi(context, ssid,
                            WifiVerifier.parseNetworkId(p)));
                }
            } else if (event.equals("ConfigureWifi")) {
                if (str(p, "ssid").isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: ssid");
                } else if (!p.containsKey("securityType")) {
                    result.put(RESULT_KEY, "missing parameter: securityType (0=open, 1=wpa2, 2=wep)");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("ConfigureEnterpriseWifi")) {
                if (str(p, "ssid").isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: ssid");
                } else if (!p.containsKey("eapMethod")) {
                    result.put(RESULT_KEY, "missing parameter: eapMethod (0=PEAP 1=TLS 2=TTLS 3=PWD 4=SIM 5=AKA 6=AKA_PRIME)");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("RemoveWifiNetwork") || event.equals("GetSavedWifiNetworks")
                    || event.equals("GetSsidAccessPolicy") || event.equals("GetMacAccessPolicy")
                    || event.equals("GetWifiAutoConnectPolicy") || event.equals("ApplyWifiAccessPolicy")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("SetSsidAccessPolicy") || event.equals("SetMacAccessPolicy")) {
                if (!p.containsKey("mode")) {
                    result.put(RESULT_KEY, "missing parameter: mode (0=off, 1=whitelist, 2=blacklist)");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("SetSsidAccessWhitelist") || event.equals("SetSsidAccessBlacklist")) {
                if (p.containsKey("ssids") && p.get("ssids") instanceof List) {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                } else {
                    result.put(RESULT_KEY, "missing parameter: ssids (array)");
                }
            } else if (event.equals("SetMacAccessWhitelist") || event.equals("SetMacAccessBlacklist")) {
                if (p.containsKey("macs") && p.get("macs") instanceof List) {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                } else {
                    result.put(RESULT_KEY, "missing parameter: macs (array)");
                }
            } else if (event.equals("SetWifiAutoConnectPolicy")) {
                if (!p.containsKey("enabled")) {
                    result.put(RESULT_KEY, "missing parameter: enabled");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("SetLocationEnabled") || event.equals("SetAirplaneMode")
                    || event.equals("SetNavigationBarEnabled") || event.equals("SetNfcEnabled")
                    || event.equals("SetMobileDataEnabled")) {
                if (!p.containsKey("enabled")) {
                    result.put(RESULT_KEY, "missing parameter: enabled");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("SetPassiveLocationAllowed")) {
                if (!p.containsKey("allowed")) {
                    result.put(RESULT_KEY, "missing parameter: allowed");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("IsPassiveLocationAllowed")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("SetFontScale")) {
                String scale = str(p, "scale");
                if (scale.isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: scale (0.5..2.0)");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("GetFontScale")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("IsLocationEnabled") || event.equals("IsAirplaneMode")
                    || event.equals("IsNavigationBarEnabled") || event.equals("IsNfcEnabled")
                    || event.equals("IsMobileDataEnabled")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("ForceOpenLocation") || event.equals("ForceOpenAirplaneMode")
                    || event.equals("ForceOpenNfc") || event.equals("ForceOpenMobileData")) {
                if (!p.containsKey("forceOpen")) {
                    result.put(RESULT_KEY, "missing parameter: forceOpen");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("ForceCloseMobileData")) {
                if (!p.containsKey("forceClose")) {
                    result.put(RESULT_KEY, "missing parameter: forceClose");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("SetMobileDataStateLocked")) {
                if (!p.containsKey("locked")) {
                    result.put(RESULT_KEY, "missing parameter: locked");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("IsMobileDataStateLocked")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("SetHotspotEnabled") || event.equals("SetUsbTetheringEnabled")
                    || event.equals("SetBluetoothTetheringEnabled")) {
                if (!p.containsKey("enabled")) {
                    result.put(RESULT_KEY, "missing parameter: enabled");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("IsHotspotEnabled") || event.equals("IsUsbTetheringEnabled")
                    || event.equals("IsBluetoothTetheringEnabled")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("SetTetheringDisabled")) {
                if (!p.containsKey("disabled")) {
                    result.put(RESULT_KEY, "missing parameter: disabled");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("IsTetheringDisabled")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("SetUsbTetheringForbidden") || event.equals("SetWifiTetheringForbidden")
                    || event.equals("SetBluetoothTetheringForbidden")) {
                if (!p.containsKey("forbidden")) {
                    result.put(RESULT_KEY, "missing parameter: forbidden");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("IsUsbTetheringForbidden") || event.equals("IsWifiTetheringForbidden")
                    || event.equals("IsBluetoothTetheringForbidden")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("WakeUp") || event.equals("GoToSleep") || event.equals("GetPowerState")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("SetDozeDisabled")) {
                if (!p.containsKey("disabled")) {
                    result.put(RESULT_KEY, "missing parameter: disabled");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("IsDozeDisabled")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("SetDozeWhitelist")) {
                if (p.containsKey("packageNames") && p.get("packageNames") instanceof List) {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                } else {
                    result.put(RESULT_KEY, "missing parameter: packageNames (array)");
                }
            } else if (event.equals("GetDozeWhitelist")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("SetVoiceAssistantDisabled")) {
                if (!p.containsKey("disabled")) {
                    result.put(RESULT_KEY, "missing parameter: disabled");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("IsVoiceAssistantDisabled")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("SetEthernetConfig")) {
                String mode = str(p, "mode").toLowerCase();
                if (mode.isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: mode (dhcp/static)");
                } else if (!mode.equals("dhcp") && !mode.equals("static")) {
                    result.put(RESULT_KEY, "invalid parameter: mode (dhcp/static)");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("GetEthernetConfig") || event.equals("IsEthernetEnabled")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("SetEthernetEnabled")) {
                if (!p.containsKey("enabled")) {
                    result.put(RESULT_KEY, "missing parameter: enabled");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("SetPictureInPictureDisabled") || event.equals("SetWriteSettingsDisabled")) {
                String pkg = str(p, "packageName");
                if (pkg.isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: packageName");
                } else if (!p.containsKey("disabled")) {
                    result.put(RESULT_KEY, "missing parameter: disabled");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("IsPictureInPictureDisabled") || event.equals("IsWriteSettingsDisabled")) {
                String pkg = str(p, "packageName");
                if (pkg.isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: packageName");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("SetNotificationListenerAccessGranted")) {
                String component = str(p, "component");
                if (component.isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: component");
                } else if (!p.containsKey("granted")) {
                    result.put(RESULT_KEY, "missing parameter: granted");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("IsNotificationListenerAccessGranted")) {
                String component = str(p, "component");
                if (component.isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: component");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("SetWifiPermissionBlacklist")) {
                if (p.containsKey("packageNames") && p.get("packageNames") instanceof List) {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                } else {
                    result.put(RESULT_KEY, "missing parameter: packageNames (array)");
                }
            } else if (event.equals("GetWifiPermissionBlacklist") || event.equals("GetWifiPermissionPolicyMode")
                    || event.equals("ApplyWifiPermissionPolicy")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("SetWifiPermissionPolicyMode")) {
                if (!p.containsKey("mode")) {
                    result.put(RESULT_KEY, "missing parameter: mode (0=off, 2=blacklist)");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("GrantUsbPermission")) {
                String pkg = str(p, "packageName");
                if (pkg.isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: packageName");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("GetUsbDeviceList")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("TryWriteSettings")) {
                result.put(RESULT_KEY, AppOpsVerifier.tryWriteSettings(context));
            } else if (event.equals("TryChangeWifiState")) {
                result.put(RESULT_KEY, AppOpsVerifier.tryChangeWifiState(context));
            } else if (event.equals("TryEnterPip")) {
                result.put(RESULT_KEY, AppOpsVerifier.tryEnterPip(context));
            } else if (event.equals("CheckUsbPermission")) {
                result.put(RESULT_KEY, AppOpsVerifier.checkUsbPermission(context, str(p, "deviceName")));
            } else if (event.equals("SetDeviceAdminActive") || event.equals("ForceSetDeviceAdminActive")) {
                if (str(p, "component").isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: component");
                } else if (!p.containsKey("active")) {
                    result.put(RESULT_KEY, "missing parameter: active");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("IsDeviceAdminActive")) {
                if (str(p, "component").isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: component");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("SetDeviceOwner")) {
                if (str(p, "packageName").isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: packageName");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("DeleteDeviceOwner") || event.equals("IsDeviceOwner")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("SetProfileOwner")) {
                if (str(p, "component").isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: component");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("DeleteProfileOwner") || event.equals("IsProfileOwner")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("QueryOwnAdminLocal")) {
                result.put(RESULT_KEY, DeviceAdminVerifier.isOwnAdminActive(context));
            } else if (event.equals("SetComponentEnabled")) {
                String pkg = str(p, "packageName");
                if (pkg.isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: packageName");
                } else if (!p.containsKey("enabled")) {
                    result.put(RESULT_KEY, "missing parameter: enabled");
                } else {
                    if (str(p, "component").isEmpty()) {
                        p.remove("component");
                    }
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("IsComponentEnabled")) {
                String pkg = str(p, "packageName");
                if (pkg.isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: packageName");
                } else {
                    if (str(p, "component").isEmpty()) {
                        p.remove("component");
                    }
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("SetDefaultSmsApp") || event.equals("SetDefaultDialerApp")
                    || event.equals("SetDefaultAssistant")) {
                String pkg = str(p, "packageName");
                if (pkg.isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: packageName");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("GetDefaultSmsApp") || event.equals("GetDefaultDialerApp")
                    || event.equals("GetDefaultAssistant")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("TryStartComponent") || event.equals("ResolveComponent")) {
                String component = str(p, "component");
                if (component.isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: component");
                } else if (event.equals("TryStartComponent")) {
                    result.put(RESULT_KEY, DefaultAppVerifier.tryStartComponent(context, component));
                } else {
                    result.put(RESULT_KEY, DefaultAppVerifier.resolveComponent(context, component));
                }
            } else if (event.equals("GetAssistantSetting")) {
                result.put(RESULT_KEY, DefaultAppVerifier.getAssistantSetting(context));
            } else if (event.equals("AddVpnProfile")) {
                if (str(p, "name").isEmpty() || str(p, "type").isEmpty() || str(p, "server").isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: name/type/server");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("StartVpnProfile") || event.equals("DeleteVpnProfile")) {
                if (str(p, "name").isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: name");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("GetVpnProfileList") || event.equals("DisconnectVpn")
                    || event.equals("IsVpnDisabled")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("SetVpnDisabled")) {
                if (!p.containsKey("disabled")) {
                    result.put(RESULT_KEY, "missing parameter: disabled");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("TryOpenVpnSettings")) {
                result.put(RESULT_KEY, VpnVerifier.tryOpenVpnSettings(context));
            } else if (event.equals("CheckVpnNetworks")) {
                result.put(RESULT_KEY, VpnVerifier.checkVpnNetworks(context));
            } else if (event.equals("BackupAppData")) {
                String pkg = str(p, "packageName");
                if (pkg.isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: packageName");
                } else {
                    if (str(p, "file").isEmpty()) {
                        p.remove("file");
                    }
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("RestoreAppData")) {
                if (str(p, "file").isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: file");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("ClearAppCache")) {
                String pkg = str(p, "packageName");
                if (pkg.isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: packageName");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("TakeScreenshot")) {
                if (str(p, "file").isEmpty()) {
                    p.remove("file");
                }
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("GetStorageVolumes") || event.equals("UnmountUsbStorage")
                    || event.equals("FormatExternalSd")) {
                if ((event.equals("UnmountUsbStorage") || event.equals("FormatExternalSd"))
                        && str(p, "volumeId").isEmpty()) {
                    p.remove("volumeId");
                }
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("CreateUser")) {
                String name = str(p, "name");
                if (name.isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: name");
                } else {
                    if (str(p, "flags").isEmpty()) {
                        p.remove("flags");
                    }
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("DeleteUser")) {
                String userId = str(p, "userId");
                if (userId.isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: userId");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("GetUserList")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("WriteDataProbe")) {
                String value = str(p, "value");
                if (value.isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: value");
                } else {
                    result.put(RESULT_KEY, StorageUserVerifier.writeDataProbe(context, value));
                }
            } else if (event.equals("ReadDataProbe")) {
                result.put(RESULT_KEY, StorageUserVerifier.readDataProbe(context));
            } else if (event.equals("WriteCacheProbe")) {
                String value = str(p, "value");
                if (value.isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: value");
                } else {
                    result.put(RESULT_KEY, StorageUserVerifier.writeCacheProbe(context, value));
                }
            } else if (event.equals("CheckCacheProbe")) {
                result.put(RESULT_KEY, StorageUserVerifier.checkCacheProbe(context));
            } else if (event.equals("GetUserListLocal")) {
                result.put(RESULT_KEY, StorageUserVerifier.getUserListLocal(context));
            } else if (event.equals("SetBlueOpen")) {
                if (!p.containsKey("open")) {
                    result.put(RESULT_KEY, "missing parameter: open");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("IsBlueOpen")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("SetDiscoverableForbidden") || event.equals("SetLimitedDiscoverableForbidden")
                    || event.equals("SetBluetoothPageDisabled") || event.equals("SetBluetoothFileTransferDisabled")
                    || event.equals("SetScoCallDisabled")) {
                if (!p.containsKey("disabled")) {
                    result.put(RESULT_KEY, "missing parameter: disabled");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("IsDiscoverableForbidden") || event.equals("IsLimitedDiscoverableForbidden")
                    || event.equals("IsBluetoothPageDisabled") || event.equals("IsBluetoothFileTransferDisabled")
                    || event.equals("IsScoCallDisabled") || event.equals("GetBluetoothStatus")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("SetBluetoothAccessPolicy")) {
                if (!p.containsKey("mode")) {
                    result.put(RESULT_KEY, "missing parameter: mode (0=off, 1=whitelist, 2=blacklist)");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("GetBluetoothAccessPolicy") || event.equals("ApplyBluetoothAccessPolicy")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("SetBluetoothAddressWhitelist") || event.equals("SetBluetoothAddressBlacklist")) {
                if (p.containsKey("addresses") && p.get("addresses") instanceof List) {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                } else {
                    result.put(RESULT_KEY, "missing parameter: addresses (array)");
                }
            } else if (event.equals("SetBluetoothNameWhitelist") || event.equals("SetBluetoothNameBlacklist")) {
                if (p.containsKey("names") && p.get("names") instanceof List) {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                } else {
                    result.put(RESULT_KEY, "missing parameter: names (array)");
                }
            } else if (event.equals("TrySetDiscoverable")) {
                result.put(RESULT_KEY, BluetoothVerifier.trySetDiscoverable(context));
            } else if (event.equals("TryStartDiscovery")) {
                result.put(RESULT_KEY, BluetoothVerifier.tryStartDiscovery(context));
            } else if (event.equals("TryOpenBluetoothSettings")) {
                result.put(RESULT_KEY, BluetoothVerifier.tryOpenBluetoothSettings(context));
            } else if (event.equals("ResolveBluetoothShare")) {
                result.put(RESULT_KEY, BluetoothVerifier.resolveBluetoothShare(context));
            } else if (event.equals("GetBluetoothStateLocal")) {
                result.put(RESULT_KEY, BluetoothVerifier.getBluetoothState(context));
            } else if (event.equals("SetWallpaper")) {
                String target = str(p, "target");
                if (target.isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: target (home/lock/both)");
                } else {
                    String imageBase64 = str(p, "imageBase64");
                    if (imageBase64.isEmpty()) {
                        imageBase64 = generateTestImage(p);
                    }
                    Map<String, Object> apiParam = new HashMap<>();
                    apiParam.put("target", target);
                    apiParam.put("imageBase64", imageBase64);
                    result.put(RESULT_KEY, apiCall(context, event, apiParam).get(RESULT_KEY));
                }
            } else if (event.equals("GetWallpaper")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("GetWallpaperStateLocal")) {
                result.put(RESULT_KEY, WallpaperVerifier.getWallpaperState(context));
            } else if (event.equals("GetFileAttribute")) {
                if (str(p, "path").isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: path");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("CheckRootStatus")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("CheckRootStatusLocal")) {
                result.put(RESULT_KEY, InfoQueryVerifier.checkRootStatusLocal());
            } else if (event.equals("GetVpnStatus")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("QueryNumberAttribution")) {
                if (!p.containsKey("number") || String.valueOf(p.get("number")).isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: number");
                } else {
                    Map<String, Object> apiParam = new HashMap<>();
                    apiParam.put("number", String.valueOf(p.get("number")));
                    result.put(RESULT_KEY, apiCall(context, event, apiParam).get(RESULT_KEY));
                }
            } else if (event.equals("GetCellInfo")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("GetCellInfoLocal")) {
                result.put(RESULT_KEY, InfoQueryVerifier.getCellInfoLocal(context));
            } else if (event.equals("GetSimContacts")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("GetSimContactsLocal")) {
                result.put(RESULT_KEY, InfoQueryVerifier.getSimContactsLocal(context));
            } else if (event.equals("GetWebViewInfo")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("GetWebViewInfoLocal")) {
                result.put(RESULT_KEY, InfoQueryVerifier.getWebViewInfoLocal(context));
            } else if (event.startsWith("Set") && (event.equals("SetAppPermissionPageDisabled")
                    || event.equals("SetAppNotificationUiDisabled")
                    || event.equals("SetUserNotificationSettingsDisabled")
                    || event.equals("SetAppManagementPageDisabled")
                    || event.equals("SetAccessibilityUiEntryDisabled")
                    || event.equals("SetLanguageSwitchingDisabled")
                    || event.equals("SetUserLanguageModificationDisabled")
                    || event.equals("SetSpeakerDisabled")
                    || event.equals("SetNotificationWhitelistLocked")
                    || event.equals("SetStatusBarNotificationSettingLocked")
                    || event.equals("SetLockscreenNotificationSettingLocked")
                    || event.equals("SetSmsAppSettingLocked")
                    || event.equals("SetDialerAppSettingLocked")
                    || event.equals("SetAssistantModificationLocked")
                    || event.equals("SetDefaultBrowserModificationLocked")
                    || event.equals("SetUsbSettingsLocked")
                    || event.equals("SetUsbDebuggingSettingLocked")
                    || event.equals("SetUserAirplaneModeChangeLocked"))) {
                if (!p.containsKey("locked") && !p.containsKey("disabled")) {
                    result.put(RESULT_KEY, "missing parameter: locked or disabled");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.startsWith("Is") && (event.equals("IsAppPermissionPageDisabled")
                    || event.equals("IsAppNotificationUiDisabled")
                    || event.equals("IsUserNotificationSettingsDisabled")
                    || event.equals("IsAppManagementPageDisabled")
                    || event.equals("IsAccessibilityUiEntryDisabled")
                    || event.equals("IsLanguageSwitchingDisabled")
                    || event.equals("IsUserLanguageModificationDisabled")
                    || event.equals("IsSpeakerDisabled")
                    || event.equals("IsNotificationWhitelistLocked")
                    || event.equals("IsStatusBarNotificationSettingLocked")
                    || event.equals("IsLockscreenNotificationSettingLocked")
                    || event.equals("IsSmsAppSettingLocked")
                    || event.equals("IsDialerAppSettingLocked")
                    || event.equals("IsAssistantModificationLocked")
                    || event.equals("IsDefaultBrowserModificationLocked")
                    || event.equals("IsUsbSettingsLocked")
                    || event.equals("IsUsbDebuggingSettingLocked")
                    || event.equals("IsUserAirplaneModeChangeLocked")
                    || event.equals("IsSpecifiedAppPermissionPageDisabled")
                    || event.equals("IsApnSettingsEnabled"))) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("SetSpecifiedAppPermissionPageDisabled")) {
                if (!p.containsKey("disabled")) {
                    result.put(RESULT_KEY, "missing parameter: disabled");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("SetApnSettingsEnabled")) {
                if (!p.containsKey("enabled")) {
                    result.put(RESULT_KEY, "missing parameter: enabled");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("SetAppManagementPolicyMode")) {
                if (!p.containsKey("mode")) {
                    result.put(RESULT_KEY, "missing parameter: mode (0/1/2)");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("GetAppManagementPolicyMode") || event.equals("GetAppManagementWhitelist")
                    || event.equals("GetAppManagementBlacklist") || event.equals("ApplyAppManagementPolicy")
                    || event.equals("GetEnabledAccessibilityServices")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("SetAppManagementWhitelist") || event.equals("SetAppManagementBlacklist")) {
                if (p.containsKey("packageNames") && p.get("packageNames") instanceof List) {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                } else {
                    result.put(RESULT_KEY, "missing parameter: packageNames (array)");
                }
            } else if (event.equals("SetEmailPolicyMode")) {
                if (!p.containsKey("mode")) {
                    result.put(RESULT_KEY, "missing parameter: mode (0=off, 2=blacklist)");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("GetEmailPolicyMode") || event.equals("GetEmailBlacklist")
                    || event.equals("ApplyEmailPolicy")) {
                result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
            } else if (event.equals("SetEmailBlacklist")) {
                if (p.containsKey("packageNames") && p.get("packageNames") instanceof List) {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                } else {
                    result.put(RESULT_KEY, "missing parameter: packageNames (array)");
                }
            } else if (event.equals("IsEmailControlled")) {
                if (str(p, "packageName").isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: packageName");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("GetEntriesLocal")) {
                result.put(RESULT_KEY, EntrySettingsVerifier.resolveEntries(context));
            } else if (event.equals("GetValuesLocal")) {
                result.put(RESULT_KEY, EntrySettingsVerifier.readValues(context));
            } else if (event.equals("GetAudioStateLocal")) {
                result.put(RESULT_KEY, EntrySettingsVerifier.readAudioState(context));
            } else if (event.equals("SetUninstallWhitelist") || event.equals("SetUninstallBlacklist")) {
                if (p.containsKey("packageNames") && p.get("packageNames") instanceof List) {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                } else {
                    result.put(RESULT_KEY, "missing parameter: packageNames (array)");
                }
            } else if (event.equals("GetUninstallWhitelist") || event.equals("GetUninstallBlacklist")
                    || event.equals("GetInstallPolicyMode") || event.equals("GetInstallBlacklist")
                    || event.equals("GetKeepAliveList") || event.equals("IsAppAlive")
                    || event.equals("IsUsbDataTransferDisabled") || event.equals("IsUsbExternalStorageDisabled")
                    || event.equals("IsSdCardMountDisabled") || event.equals("IsDataRoamingDisabled")
                    || event.equals("IsDefaultLauncherSettingLocked") || event.equals("GetDefaultVideoPlayer")
                    || event.equals("GetDefaultAppForFileType") || event.equals("GetPackageInfo")
                    || event.equals("IsUserVolumeSettingDisabled") || event.equals("IsVolumeKeyDisabled")
                    || event.equals("IsMediaVolumeModificationLocked") || event.equals("IsNotificationVolumeModificationLocked")
                    || event.equals("IsAlarmVolumeModificationLocked") || event.equals("IsAutoSleepDisabled")
                    || event.equals("IsAlwaysFullscreen")) {
                if ((event.equals("IsAppAlive") || event.equals("IsKeepAliveEnabled")
                        || event.equals("IsManageExternalStorageGranted")
                        || event.equals("IsDesktopIconHidden") || event.equals("GetPackageInfo"))
                        && str(p, "packageName").isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: packageName");
                } else if (event.equals("GetDefaultAppForFileType") && str(p, "mimeType").isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: mimeType");
                } else if (event.equals("ClearDefaultAppForFileType")
                        && (str(p, "mimeType").isEmpty() || str(p, "packageName").isEmpty())) {
                    result.put(RESULT_KEY, "missing parameter: mimeType/packageName");
                } else if (event.equals("ClearDefaultVideoPlayer") && str(p, "packageName").isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: packageName");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("SetInstallPolicyMode")) {
                if (!p.containsKey("mode")) {
                    result.put(RESULT_KEY, "missing parameter: mode (0=off, 1=whitelist, 2=blacklist)");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("SetUserVolumeSettingDisabled") || event.equals("SetVolumeKeyDisabled")
                    || event.equals("SetUsbDataTransferDisabled") || event.equals("SetUsbExternalStorageDisabled")
                    || event.equals("SetSdCardMountDisabled") || event.equals("SetDataRoamingDisabled")
                    || event.equals("SetAutoSleepDisabled") || event.equals("SetDefaultLauncherSettingLocked")) {
                if (!p.containsKey("disabled") && !p.containsKey("locked")) {
                    result.put(RESULT_KEY, "missing parameter: disabled or locked");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("SetInstallBlacklist")) {
                if (p.containsKey("patterns") && p.get("patterns") instanceof List) {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                } else {
                    result.put(RESULT_KEY, "missing parameter: patterns (array of regexes)");
                }
            } else if (event.equals("SetKeepAliveEnabled") || event.equals("SetManageExternalStorageGranted")
                    || event.equals("SetDesktopIconHidden")) {
                if (str(p, "packageName").isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: packageName");
                } else if (!p.containsKey(event.equals("SetManageExternalStorageGranted") ? "granted" : "enabled")
                        && !p.containsKey("hidden")) {
                    result.put(RESULT_KEY, "missing parameter: enabled/granted/hidden");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("IsKeepAliveEnabled") || event.equals("IsManageExternalStorageGranted")
                    || event.equals("IsDesktopIconHidden")) {
                if (str(p, "packageName").isEmpty()) {
                    result.put(RESULT_KEY, "missing parameter: packageName");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("SetDefaultVideoPlayer") || event.equals("SetDefaultAppForFileType")) {
                if (str(p, "packageName").isEmpty()
                        || (event.equals("SetDefaultAppForFileType") && str(p, "mimeType").isEmpty())) {
                    result.put(RESULT_KEY, "missing parameter: " + (event.equals("SetDefaultAppForFileType")
                            ? "mimeType/packageName" : "packageName"));
                } else {
                    if (str(p, "activityName").isEmpty()) {
                        p.remove("activityName");
                    }
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("SetVolume")) {
                if (!p.containsKey("streamType") || !p.containsKey("volume")) {
                    result.put(RESULT_KEY, "missing parameter: streamType/volume");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("GetVolume")) {
                if (!p.containsKey("streamType")) {
                    result.put(RESULT_KEY, "missing parameter: streamType");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else if (event.equals("SetMediaVolumeModificationLocked")
                    || event.equals("SetNotificationVolumeModificationLocked")
                    || event.equals("SetAlarmVolumeModificationLocked") || event.equals("SetAlwaysFullscreen")) {
                if (!p.containsKey("locked") && !p.containsKey("fullscreen")) {
                    result.put(RESULT_KEY, "missing parameter: locked or fullscreen");
                } else {
                    result.put(RESULT_KEY, apiCall(context, event, p).get(RESULT_KEY));
                }
            } else {
                result.put(RESULT_KEY, "unknown event: " + event + " (try ListEvents)");
            }
        } catch (Exception e) {
            result.put(RESULT_KEY, "exception: " + e.getMessage());
        }
        return result;
    }

    private static Map<Object, Object> apiCall(Context context, String event, Map<String, Object> param) {
        MdmApiClient client = ApiHolder.get(context);
        client.waitForBound(3000);
        return client.call(event, param);
    }

    private static Map<Object, Object> getConnectionStatus(Context context) {
        MdmApiClient client = ApiHolder.get(context);
        boolean bound = client.waitForBound(2000);
        Map<Object, Object> info = new LinkedHashMap<>();
        info.put("bound", bound);
        info.put("zenMode", client.call("GetZenMode", new HashMap<String, Object>()).get(RESULT_KEY));
        return info;
    }

    private static List<Map<String, Object>> listEvents() {
        List<Map<String, Object>> events = new ArrayList<>();
        events.add(desc("GetConnectionStatus", "none",
                "Probe the Launcher API connection (bound state + GetZenMode)"));
        events.add(desc("SetNotificationsEnabledForPackage", "packageName, enabled",
                "Enable/disable notifications for one package (ASR-0053)"));
        events.add(desc("IsNotificationsEnabledForPackage", "packageName",
                "Query notification status of one package (ASR-0053)"));
        events.add(desc("SetNotificationsWhitelist", "packageNames (array)",
                "Replace the whitelist (ASR-0054)"));
        events.add(desc("GetNotificationsWhitelist", "none", "Get the whitelist (ASR-0054)"));
        events.add(desc("SetNotificationsBlacklist", "packageNames (array)",
                "Replace the blacklist (ASR-0055)"));
        events.add(desc("GetNotificationsBlacklist", "none", "Get the blacklist (ASR-0055)"));
        events.add(desc("SetNotificationsPolicyMode", "mode (0=off, 1=whitelist, 2=blacklist)",
                "Set the policy mode and apply it now (ASR-0054/0055)"));
        events.add(desc("GetNotificationsPolicyMode", "none", "Get the policy mode"));
        events.add(desc("ApplyNotificationsPolicy", "none",
                "Apply the current policy to all packages (ASR-0054/0055)"));
        events.add(desc("SetLockscreenNotificationsDisabled", "disabled",
                "Disable/enable lockscreen notifications (ASR-0057)"));
        events.add(desc("IsLockscreenNotificationsDisabled", "none",
                "Query lockscreen notification status (ASR-0057)"));
        events.add(desc("SetStatusBarNotificationsDisabled", "disabled",
                "Hide/restore the notification icons in the status bar (cmd statusbar send-disable-flag, ASR-0059)"));
        events.add(desc("IsStatusBarNotificationsDisabled", "none",
                "Query whether the status bar notifications are disabled (ASR-0059)"));
        events.add(desc("SetBackKeyDisabled", "disabled",
                "Disable/enable the BACK key (StatusBarManager DISABLE_BACK, 3-button nav bar; ASR-0346)"));
        events.add(desc("IsBackKeyDisabled", "none",
                "Query whether the BACK key is disabled (ASR-0346)"));
        events.add(desc("SendTestNotification", "none",
                "Send a distinguishable test notification from this app"));
        events.add(desc("SetCameraDisabled", "disabled", "Disable/enable camera (ASR-0207)"));
        events.add(desc("IsCameraDisabled", "none", "Query camera policy state (ASR-0207)"));
        events.add(desc("TryOpenCamera", "none",
                "Really open the camera hardware to verify the policy (ASR-0207)"));
        events.add(desc("SetMicrophoneDisabled", "disabled", "Disable/enable microphone (ASR-0369)"));
        events.add(desc("IsMicrophoneDisabled", "none", "Query microphone policy state (ASR-0369)"));
        events.add(desc("TryRecordAudio", "none",
                "Really record audio to verify the policy (ASR-0369)"));
        events.add(desc("SetLogBufferSize", "size (e.g. 1M/512K), buffer (main/system/crash/all)",
                "Set the log buffer size (ASR-0189)"));
        events.add(desc("GetLogBufferSize", "none", "Get the log buffer sizes (ASR-0189)"));
        events.add(desc("SetLogLevel", "tag, level (V/D/I/W/E/F/S, empty resets)",
                "Set the log level for a tag (ASR-0190)"));
        events.add(desc("GetLogLevel", "tag", "Get the log level of a tag (ASR-0190)"));
        events.add(desc("EmitTestLogs", "tag", "Emit V/D logs with the given tag (ASR-0190)"));
        events.add(desc("RequestPostNotificationsPermission", "none",
                "UI-only action, use: am start -n com.hmdm.testapp/.PermissionActivity --es permissions POST_NOTIFICATIONS"));
        events.add(desc("RequestSensorPermissions", "none",
                "UI-only action, use: am start -n com.hmdm.testapp/.PermissionActivity --es permissions CAMERA,RECORD_AUDIO"));
        events.add(desc("SetDomainWhitelist", "domains (array)",
                "Replace the domain whitelist (ASR-0135)"));
        events.add(desc("GetDomainWhitelist", "none", "Get the domain whitelist (ASR-0135)"));
        events.add(desc("SetDomainBlacklist", "domains (array)",
                "Replace the domain blacklist (ASR-0135)"));
        events.add(desc("GetDomainBlacklist", "none", "Get the domain blacklist (ASR-0135)"));
        events.add(desc("SetDomainPolicyMode", "mode (0=off, 1=whitelist, 2=blacklist)",
                "Set the domain policy mode and apply it now (ASR-0135)"));
        events.add(desc("GetDomainPolicyMode", "none", "Get the domain policy mode (ASR-0135)"));
        events.add(desc("SetIpWhitelist", "ips (array, exact/CIDR/*)",
                "Replace the IP whitelist (ASR-0136)"));
        events.add(desc("GetIpWhitelist", "none", "Get the IP whitelist (ASR-0136)"));
        events.add(desc("SetIpBlacklist", "ips (array, exact/CIDR/*)",
                "Replace the IP blacklist (ASR-0136)"));
        events.add(desc("GetIpBlacklist", "none", "Get the IP blacklist (ASR-0136)"));
        events.add(desc("SetIpPolicyMode", "mode (0=off, 1=whitelist, 2=blacklist)",
                "Set the IP policy mode and apply it now (ASR-0136)"));
        events.add(desc("GetIpPolicyMode", "none", "Get the IP policy mode (ASR-0136)"));
        events.add(desc("GetNetworkFirewallStatus", "none",
                "Get policy + VPN state + block counters (ASR-0135/0136)"));
        events.add(desc("TestDnsLookup", "host",
                "Resolve a host through the firewall VPN (ASR-0135)"));
        events.add(desc("TestTcpConnect", "host, port (default 80)",
                "TCP-connect to a host/IP through the firewall VPN (ASR-0135/0136)"));
        events.add(desc("TestHttpGet", "url",
                "HTTP GET through the firewall VPN (ASR-0135/0136)"));
        events.add(desc("QueryAppTraffic", "packageName (optional), days (default 1), network (all/wifi/mobile, default all), limit (default 30)",
                "Query per-app traffic rx/tx bytes of the period (ASR-0139)"));
        events.add(desc("QueryAppBattery", "packageName (optional), limit (default 20)",
                "Query per-app estimated battery drain in mAh (ASR-0372)"));
        events.add(desc("QueryAppRuntime", "packageName (optional), days (default 7), limit (default 30)",
                "Query per-app total foreground runtime of the period (ASR-0021)"));
        events.add(desc("QueryRunningApps", "none",
                "Query the currently running app processes (ASR-0022)"));
        events.add(desc("QueryAppCrashInfo", "packageName (optional), limit (default 20)",
                "Query crash/ANR/watchdog history from DropBox (ASR-0023)"));
        events.add(desc("KillAppProcess", "packageName",
                "End the process(es) of a package, works even in foreground (ASR-0027)"));
        events.add(desc("KillBackgroundProcesses", "except (optional array of packages to keep)",
                "Clean up all background/cached/empty processes (ASR-0028)"));
        events.add(desc("SetBlockedRunningWhitelist", "packageNames (array, full replacement)",
                "Replace the blocked-running whitelist: suspend + hide listed apps (ASR-0017)"));
        events.add(desc("GetBlockedRunningWhitelist", "none",
                "Get the blocked-running whitelist with live suspended/hidden state (ASR-0017)"));
        events.add(desc("IsPackageSuspended", "packageName",
                "Query whether a package is suspended + hidden (ASR-0017)"));
        events.add(desc("SetIgnoreBatteryOptimizationWhitelist", "packageNames (array, full replacement)",
                "Replace the ignore-battery-optimization whitelist, DO grants are silent (ASR-0016/0030)"));
        events.add(desc("GetIgnoreBatteryOptimizationWhitelist", "none",
                "Get the whitelist with live isIgnoringBatteryOptimizations state (ASR-0016/0030)"));
        events.add(desc("IsIgnoringBatteryOptimization", "packageName",
                "Query whether a package ignores battery optimizations (ASR-0016/0030)"));
        events.add(desc("SetApplicationHidden", "packageName, hidden",
                "Hide/unhide an application (used by ASR-0099/0104/0131)"));
        events.add(desc("IsApplicationHidden", "packageName",
                "Query the hidden state of an application (ASR-0099/0104/0131)"));
        events.add(desc("SetCaptivePortalDisabled", "disabled",
                "Block/allow the WIFI captive portal dialog (ASR-0166)"));
        events.add(desc("IsCaptivePortalDisabled", "none",
                "Query whether the captive portal dialog is blocked (ASR-0166)"));
        events.add(desc("SetAlwaysFinishActivitiesDisabled", "disabled",
                "Forbid/allow the 'don't keep activities' developer option (ASR-0204)"));
        events.add(desc("IsAlwaysFinishActivitiesDisabled", "none",
                "Query the always-finish-activities state (ASR-0204)"));
        events.add(desc("SetMockLocationDisabled", "disabled",
                "Forbid/allow mock locations (ASR-0205)"));
        events.add(desc("IsMockLocationDisabled", "none",
                "Query whether mock locations are forbidden (ASR-0205)"));
        events.add(desc("SetLocationMode", "mode (0=off, 1=sensors, 2=battery saving, 3=high accuracy)",
                "Set the location mode (ASR-0314)"));
        events.add(desc("GetLocationMode", "none",
                "Get the current location mode (ASR-0314)"));
        events.add(desc("SetGestureNavigationDisabled", "disabled",
                "Disable/enable the full-screen gesture navigation (ASR-0345)"));
        events.add(desc("IsGestureNavigationDisabled", "none",
                "Query whether gesture navigation is disabled (ASR-0345)"));
        events.add(desc("SetAnimationsDisabled", "disabled",
                "Disable/enable the window animations (all scales 0 / 1.0, ASR-0426)"));
        events.add(desc("IsAnimationsDisabled", "none",
                "Query whether the window animations are disabled (ASR-0426)"));
        events.add(desc("SetStrongAuthTimeout", "timeoutMs (long, >= 0; 0 = immediately)",
                "Set the required strong auth timeout (ASR-0359)"));
        events.add(desc("GetStrongAuthTimeout", "none",
                "Get the required strong auth timeout in ms (ASR-0359)"));
        events.add(desc("SetPasswordExpirationTimeout", "timeoutMs (long, >= 0; 0 = never)",
                "Set the password expiration grace period (ASR-0365)"));
        events.add(desc("GetPasswordExpirationTimeout", "none",
                "Get the password expiration timeout and expiry date (ASR-0365)"));
        events.add(desc("SetConsecutiveDigitsLimit", "limit (int, 0..16; 0 = unrestricted)",
                "Set the consecutive digit sequence limit, DPM quality/length approximation (ASR-0363)"));
        events.add(desc("GetConsecutiveDigitsLimit", "none",
                "Get the effective digit limit and DPM password policy (ASR-0363)"));
        events.add(desc("SetBackupDisabled", "disabled",
                "Disable/enable the system backup feature (DISALLOW_BACKUP, ASR-0124)"));
        events.add(desc("IsBackupDisabled", "none",
                "Query the system backup restriction state (ASR-0124)"));
        events.add(desc("SetGoogleAccountsDisabled", "disabled",
                "Disable/enable Google (any) accounts: restriction + remove existing accounts (ASR-0129)"));
        events.add(desc("IsGoogleAccountsDisabled", "none",
                "Query the account restriction state and current accounts (ASR-0129)"));
        events.add(desc("SetBackupRestoreDisabled", "disabled",
                "Disable/enable Google backup and restore toggles (backup_enabled / backup_auto_restore, ASR-0132)"));
        events.add(desc("IsBackupRestoreDisabled", "none",
                "Query the backup & restore toggle state (ASR-0132)"));
        events.add(desc("SetAutoSync", "enabled",
                "Enable/disable the global auto sync master switch (ContentResolver.setMasterSyncAutomatically, ASR-0128)"));
        events.add(desc("IsAutoSync", "none",
                "Query the global auto sync master switch state (ASR-0128)"));
        events.add(desc("SetGoogleAccountAutoSync", "enabled",
                "Enable/disable Google account auto sync: per-account syncAutomatically for every com.google account and authority (ASR-0130)"));
        events.add(desc("IsGoogleAccountAutoSync", "none",
                "Query the Google account auto sync state (per-account syncAutomatically toggles, ASR-0130)"));
        events.add(desc("SetAccessibilityShortcutDisabled", "disabled",
                "Disable/enable the accessibility shortcut (accessibility_shortcut keys, ASR-0078)"));
        events.add(desc("IsAccessibilityShortcutDisabled", "none",
                "Query whether the accessibility shortcut is disabled (ASR-0078)"));
        events.add(desc("SetAccessibilityServiceEnabled", "component (package/class), enabled",
                "Activate/deactivate one accessibility service without user interaction (ASR-0074)"));
        events.add(desc("IsAccessibilityServiceEnabled", "component (package/class)",
                "Query whether one accessibility service is enabled (ASR-0074)"));
        events.add(desc("GetAccessibilityServiceState", "none",
                "Full accessibility state: master switch, enabled/installed services, policy (ASR-0074/0075)"));
        events.add(desc("SetAccessibilityServicePolicyMode", "mode (0=off, 1=whitelist, 2=blacklist)",
                "Set the usable accessibility services policy mode and reconcile now (ASR-0075)"));
        events.add(desc("GetAccessibilityServicePolicyMode", "none",
                "Get the policy mode plus the configured lists (ASR-0075)"));
        events.add(desc("SetAccessibilityServiceWhitelist", "components (array of package/class)",
                "Replace the usable services whitelist (ASR-0075)"));
        events.add(desc("GetAccessibilityServiceWhitelist", "none",
                "Get the usable services whitelist (ASR-0075)"));
        events.add(desc("SetAccessibilityServiceBlacklist", "components (array of package/class)",
                "Replace the usable services blacklist (ASR-0075)"));
        events.add(desc("GetAccessibilityServiceBlacklist", "none",
                "Get the usable services blacklist (ASR-0075)"));
        events.add(desc("ApplyAccessibilityServicePolicy", "none",
                "Apply the current policy to the enabled services right now (ASR-0075)"));
        events.add(desc("SetScreenshotsDisabled", "disabled",
                "Disable/enable screenshots (setScreenCaptureDisabled / FLAG_SECURE, ASR-0185/0186)"));
        events.add(desc("IsScreenshotsDisabled", "none",
                "Query whether screenshots are disabled (ASR-0185/0186)"));
        events.add(desc("SetOnlineFotaDisabled", "disabled",
                "Disable/enable online FOTA (setSystemUpdatePolicy POSTPONE / automatic, ASR-0444)"));
        events.add(desc("IsOnlineFotaDisabled", "none",
                "Query the system update (FOTA) policy state (ASR-0444)"));
        events.add(desc("FotaCheckUpdate", "config (JSON)",
                "Check the available system version: download OTA package metadata and verify payload signature + compatibility (ASR-0446)"));
        events.add(desc("FotaDownloadUpdate", "config (JSON)",
                "Download the complete FOTA package to the Launcher data dir and verify it (ASR-0446)"));
        events.add(desc("SetLocalOtaEnabled", "enabled",
                "Set the SD card / local file OTA policy switch; disabled rejects local OTA installs (ASR-0448)"));
        events.add(desc("IsLocalOtaEnabled", "none",
                "Query the SD card / local file OTA policy switch state (ASR-0448)"));
        events.add(desc("FotaApply", "config (JSON)",
                "Start OTA payload application via the A/B UpdateEngine with the 4 callbacks (ASR-0447 engine path / ASR-0451 callbacks)"));
        events.add(desc("FotaStartLocal", "actionId (optional)",
                "Local OTA install test: sends FotaStart with a real content:// Uri to verify the ASR-0448 policy gate (-104 when disabled)"));
        events.add(desc("FotaCancel", "none",
                "Cancel the running OTA payload application and report state (ASR-0449)"));
        events.add(desc("FotaSuspend", "none",
                "Suspend the running OTA payload application and report state (ASR-0450)"));
        events.add(desc("FotaResume", "none",
                "Resume the suspended OTA payload application and report state (ASR-0450)"));
        events.add(desc("GetSlotInfo", "none",
                "Query A/B slot state: current/next slot, bootable and marked-successful flags (ASR-0452)"));
        events.add(desc("SetSwitchSlotOnReboot", "config (JSON)",
                "Switch the A/B slot on next reboot (re-apply payload with SWITCH_SLOT_ON_REBOOT, ASR-0452)"));
        events.add(desc("GetOtaCallbackLog", "none",
                "Return the OTA callback events received from the Launcher broadcasts (ASR-0451)"));
        events.add(desc("ClearOtaCallbackLog", "none",
                "Clear the recorded OTA callback events (ASR-0451)"));
        events.add(desc("SetApConfigLockdown", "disabled",
                "Forbid/allow user AP config changes (DISALLOW_CONFIG_WIFI, ASR-0150/0141/0155 engine)"));
        events.add(desc("IsApConfigLockdown", "none",
                "Query the AP config lockdown state (ASR-0150)"));
        events.add(desc("SetWifiSsidWhitelist", "enabled, ssids (array, required when enabled)",
                "Install/clear the enterprise Wi-Fi SSID allowlist (setWifiSsidPolicy, ASR-0152)"));
        events.add(desc("GetWifiSsidWhitelist", "none",
                "Query the current Wi-Fi SSID policy (ASR-0152)"));
        events.add(desc("SetManualAddWifiDisabled", "disabled",
                "Forbid/allow manually adding networks (DISALLOW_ADD_WIFI_CONFIG, ASR-0153)"));
        events.add(desc("IsManualAddWifiDisabled", "none",
                "Query the manual network add restriction (ASR-0153)"));
        events.add(desc("SetUserConfigWifiDisabled", "disabled",
                "Forbid/allow editing the WLAN settings (DISALLOW_CONFIG_WIFI, ASR-0155/0141)"));
        events.add(desc("IsUserConfigWifiDisabled", "none",
                "Query the edit-WLAN restriction state (ASR-0155/0141)"));
        events.add(desc("SetMinimumWifiSecurityLevel", "level (0=OPEN, 1=PERSONAL, 2=ENTERPRISE_EAP, 3=ENTERPRISE_192)",
                "Set the minimum required Wi-Fi security level (setMinimumRequiredWifiSecurityLevel, ASR-0158)"));
        events.add(desc("GetMinimumWifiSecurityLevel", "none",
                "Query the minimum Wi-Fi security level (ASR-0158)"));
        events.add(desc("SetWifiDirectDisabled", "disabled",
                "Forbid/allow WLAN direct (DISALLOW_WIFI_DIRECT, ASR-0160)"));
        events.add(desc("IsWifiDirectDisabled", "none",
                "Query the WLAN direct restriction state (ASR-0160)"));
        events.add(desc("SetUserConfigTetheringDisabled", "disabled",
                "Forbid/allow modifying the hotspot config (DISALLOW_CONFIG_TETHERING, ASR-0168)"));
        events.add(desc("IsUserConfigTetheringDisabled", "none",
                "Query the hotspot config restriction state (ASR-0168)"));
        events.add(desc("TryAddWifiNetwork", "none",
                "Add a temporary network from this non-device-owner app: SecurityException proves the AP config lockdown (ASR-0150)"));
        events.add(desc("TryConnectOpenWifi", "ssid (saved open network)",
                "Enable a saved open network and report whether the framework really connects (ASR-0152/0158 enforcement)"));
        events.add(desc("ConfigureWifi", "ssid, securityType (0=open 1=wpa2 2=wep), password (wpa2/wep), connect (optional)",
                "Configure a WLAN network via addNetwork; same SSID+security replaced (ASR-0144)"));
        events.add(desc("ConfigureEnterpriseWifi", "ssid, eapMethod (0=PEAP 1=TLS 2=TTLS 3=PWD 4=SIM 5=AKA 6=AKA_PRIME), phase2 (optional, default 0), identity, anonymousIdentity, password (not for SIM/AKA), connect (optional)",
                "Configure an enterprise Wi-Fi network via addNetwork + WifiEnterpriseConfig (ASR-0151)"));
        events.add(desc("RemoveWifiNetwork", "networkId (optional, wins) or ssid",
                "Delete a saved hotspot via removeNetwork (ASR-0145)"));
        events.add(desc("GetSavedWifiNetworks", "none",
                "List saved networks with networkId/ssid/securityType/enabled (ASR-0145/0161 helper)"));
        events.add(desc("SetSsidAccessPolicy", "mode (0=off, 1=whitelist, 2=blacklist)",
                "Set the SSID black/white list policy; disconnects violating networks via network callback (ASR-0147)"));
        events.add(desc("GetSsidAccessPolicy", "none",
                "Query the SSID access policy state (ASR-0147)"));
        events.add(desc("SetSsidAccessWhitelist", "ssids (array, full replacement)",
                "Replace the SSID access whitelist (ASR-0147)"));
        events.add(desc("SetSsidAccessBlacklist", "ssids (array, full replacement)",
                "Replace the SSID access blacklist (ASR-0147)"));
        events.add(desc("SetMacAccessPolicy", "mode (0=off, 1=whitelist, 2=blacklist)",
                "Set the AP MAC (BSSID) black/white list policy; disconnects violating networks via network callback (ASR-0148)"));
        events.add(desc("GetMacAccessPolicy", "none",
                "Query the AP MAC access policy state (ASR-0148)"));
        events.add(desc("SetMacAccessWhitelist", "macs (array AA:BB:CC:DD:EE:FF, full replacement)",
                "Replace the AP MAC access whitelist (ASR-0148)"));
        events.add(desc("SetMacAccessBlacklist", "macs (array AA:BB:CC:DD:EE:FF, full replacement)",
                "Replace the AP MAC access blacklist (ASR-0148)"));
        events.add(desc("SetWifiAutoConnectPolicy", "enabled (true=forbid auto-connect)",
                "Forbid/allow auto-connect: disables/enables all saved networks via disableNetwork/enableNetwork (ASR-0161)"));
        events.add(desc("GetWifiAutoConnectPolicy", "none",
                "Query the auto-connect policy and per-network enabled states (ASR-0161)"));
        events.add(desc("ApplyWifiAccessPolicy", "none",
                "Apply the SSID/MAC/auto-connect policy to the current connection right now (ASR-0147/0148/0161)"));
        events.add(desc("SetLocationEnabled", "enabled",
                "Disable/enable the location service: disable saves the mode and writes 0, enable restores it (ASR-0310/0311)"));
        events.add(desc("IsLocationEnabled", "none",
                "Query whether the location service is enabled (ASR-0310/0311)"));
        events.add(desc("SetPassiveLocationAllowed", "allowed",
                "Allow/forbid passive location: forbid saves the mode and writes location_mode 0, allow restores it (LOCATION_MODE combination, ASR-0313)"));
        events.add(desc("IsPassiveLocationAllowed", "none",
                "Query whether passive location is allowed (location_mode != 0, ASR-0313)"));
        events.add(desc("SetFontScale", "scale (float, 0.5..2.0; Settings presets 0.85/1.0/1.15/1.30)",
                "Set the system font scale (Settings.System font_scale, ASR-0407)"));
        events.add(desc("GetFontScale", "none",
                "Get the current system font scale (ASR-0407)"));
        events.add(desc("ForceOpenLocation", "forceOpen",
                "Force-open the location service: re-opens it every 10 s while a user turns it off (ASR-0311)"));
        events.add(desc("SetAirplaneMode", "enabled",
                "Turn the airplane mode on/off: write airplane_mode_on + AIRPLANE_MODE_CHANGED broadcast (ASR-0319/0320)"));
        events.add(desc("IsAirplaneMode", "none",
                "Query whether the airplane mode is on (ASR-0319/0320)"));
        events.add(desc("ForceOpenAirplaneMode", "forceOpen",
                "Force-open the airplane mode: re-applies it every 10 s while a user turns it off (ASR-0321)"));
        events.add(desc("SetNavigationBarEnabled", "enabled",
                "Show/hide the navigation bar (Settings.System navigation_visible, ASR-0349)"));
        events.add(desc("IsNavigationBarEnabled", "none",
                "Query whether the navigation bar is enabled (ASR-0349)"));
        events.add(desc("SetNfcEnabled", "enabled",
                "Turn NFC on/off (reflection NfcAdapter.enable/disable, ASR-0315/0316)"));
        events.add(desc("IsNfcEnabled", "none",
                "Query the NFC state (adapter state + nfc_on mirror, ASR-0315/0316)"));
        events.add(desc("ForceOpenNfc", "forceOpen",
                "Force-open NFC: re-enables it every 10 s while a user turns it off (ASR-0317)"));
        events.add(desc("SetMobileDataEnabled", "enabled",
                "Turn mobile data on/off (reflection ConnectivityManager.setMobileDataEnabled, ASR-0275/0276)"));
        events.add(desc("IsMobileDataEnabled", "none",
                "Query the mobile data state (setting + mobile_data mirror + policy flags, ASR-0275/0276)"));
        events.add(desc("ForceCloseMobileData", "forceClose",
                "Force-close mobile data: re-applies OFF every 10 s while a user turns it on (ASR-0276)"));
        events.add(desc("ForceOpenMobileData", "forceOpen",
                "Force-open mobile data: re-applies ON every 10 s while a user turns it off (ASR-0277)"));
        events.add(desc("SetMobileDataStateLocked", "locked",
                "Lock the mobile data state: snapshots the current state and reverts any change every 10 s (ASR-0279)"));
        events.add(desc("IsMobileDataStateLocked", "none",
                "Query whether the mobile data state is locked (ASR-0279)"));
        events.add(desc("SetHotspotEnabled", "enabled",
                "Start/stop the Wi-Fi personal hotspot (TetheringManager start/stop, ASR-0167/0169)"));
        events.add(desc("IsHotspotEnabled", "none",
                "Query whether the Wi-Fi personal hotspot is active (ASR-0167/0169)"));
        events.add(desc("SetUsbTetheringEnabled", "enabled",
                "Silently open/close USB tethering (ASR-0401)"));
        events.add(desc("IsUsbTetheringEnabled", "none",
                "Query whether USB tethering is active (ASR-0401)"));
        events.add(desc("SetBluetoothTetheringEnabled", "enabled",
                "Silently open/close Bluetooth tethering (ASR-0404)"));
        events.add(desc("IsBluetoothTetheringEnabled", "none",
                "Query whether Bluetooth tethering is active (ASR-0404)"));
        events.add(desc("SetTetheringDisabled", "disabled",
                "Disable/enable network sharing as a whole: stops all types and the 10 s corrector keeps them stopped (ASR-0398)"));
        events.add(desc("IsTetheringDisabled", "none",
                "Query whether network sharing is disabled as a whole (ASR-0398)"));
        events.add(desc("SetUsbTetheringForbidden", "forbidden",
                "Forbid/allow USB sharing: stops it and the 10 s corrector keeps it stopped (ASR-0399)"));
        events.add(desc("IsUsbTetheringForbidden", "none",
                "Query whether USB sharing is forbidden (ASR-0399)"));
        events.add(desc("SetWifiTetheringForbidden", "forbidden",
                "Forbid/allow WLAN sharing: stops it and the 10 s corrector keeps it stopped (ASR-0402)"));
        events.add(desc("IsWifiTetheringForbidden", "none",
                "Query whether WLAN sharing is forbidden (ASR-0402)"));
        events.add(desc("SetBluetoothTetheringForbidden", "forbidden",
                "Forbid/allow Bluetooth sharing: stops it and the 10 s corrector keeps it stopped (ASR-0403)"));
        events.add(desc("IsBluetoothTetheringForbidden", "none",
                "Query whether Bluetooth sharing is forbidden (ASR-0403)"));
        events.add(desc("SetPictureInPictureDisabled", "packageName, disabled",
                "Disable/enable picture-in-picture of one package (AppOps OP_PICTURE_IN_PICTURE, ASR-0043)"));
        events.add(desc("IsPictureInPictureDisabled", "packageName",
                "Query the picture-in-picture AppOps state of one package (ASR-0043)"));
        events.add(desc("SetWriteSettingsDisabled", "packageName, disabled",
                "Disable/enable write settings of one package (AppOps OP_WRITE_SETTINGS, ASR-0044)"));
        events.add(desc("IsWriteSettingsDisabled", "packageName",
                "Query the write settings AppOps state of one package (ASR-0044)"));
        events.add(desc("SetNotificationListenerAccessGranted", "component (pkg/class), granted",
                "Grant/revoke the notification listener binding of a service (ASR-0045)"));
        events.add(desc("IsNotificationListenerAccessGranted", "component (pkg/class)",
                "Query whether a notification listener service is granted access (ASR-0045)"));
        events.add(desc("SetWifiPermissionBlacklist", "packageNames (array, full replacement)",
                "Replace the WLAN permission blacklist (OP_CHANGE_WIFI_STATE, ASR-0149)"));
        events.add(desc("GetWifiPermissionBlacklist", "none",
                "Get the WLAN permission blacklist (ASR-0149)"));
        events.add(desc("SetWifiPermissionPolicyMode", "mode (0=off, 2=blacklist)",
                "Set the WLAN permission policy mode and apply it now (ASR-0149)"));
        events.add(desc("GetWifiPermissionPolicyMode", "none",
                "Get the WLAN permission policy mode (ASR-0149)"));
        events.add(desc("ApplyWifiPermissionPolicy", "none",
                "Apply the current WLAN permission policy to all packages (ASR-0149)"));
        events.add(desc("GrantUsbPermission", "packageName, deviceName (optional: name / vid:pid / first)",
                "Grant the USB permission of an attached device to one package (ASR-0046)"));
        events.add(desc("GetUsbDeviceList", "none",
                "List the currently attached USB host devices (ASR-0046)"));
        events.add(desc("TryWriteSettings", "none",
                "Probe: write a Settings.System value; SecurityException proves the write settings AppOps denial (ASR-0044)"));
        events.add(desc("TryChangeWifiState", "none",
                "Probe: no-op setWifiEnabled(current); SecurityException proves the WLAN AppOps denial (ASR-0149)"));
        events.add(desc("TryEnterPip", "none",
                "Probe: try to enter picture-in-picture (needs UI activity context; ASR-0043)"));
        events.add(desc("CheckUsbPermission", "deviceName (optional)",
                "Probe: check this app's USB permission on an attached device (ASR-0046)"));
        events.add(desc("SetDeviceAdminActive", "component (pkg/class), active",
                "Non-interactively activate/deactivate a device admin component (setActiveAdmin / removeActiveAdmin, ASR-0080)"));
        events.add(desc("IsDeviceAdminActive", "component (pkg/class)",
                "Query whether a device admin component is active (ASR-0080)"));
        events.add(desc("ForceSetDeviceAdminActive", "component (pkg/class), active",
                "Force-activate/deactivate a device admin component (refreshing semantics, ASR-0081)"));
        events.add(desc("SetDeviceOwner", "packageName, component (optional, default pkg/.AdminReceiver), ownerName (optional)",
                "Set the device owner: binder channel pre-setup, device_owner_2.xml file channel post-setup (restartRequired, ASR-0083)"));
        events.add(desc("DeleteDeviceOwner", "packageName (optional, default current DO)",
                "Delete the device owner: clearDeviceOwnerApp + rewrite device_owner_2.xml (ASR-0083)"));
        events.add(desc("IsDeviceOwner", "packageName (optional)",
                "Query the device owner state incl. the device_owner_2.xml disk state (ASR-0083)"));
        events.add(desc("SetProfileOwner", "component (pkg/class), ownerName (optional), userId (optional, default 0)",
                "Set the profile owner of a user: setActiveAdmin + setProfileOwner binder channel, profile_owner.xml file channel fallback (ASR-0085)"));
        events.add(desc("DeleteProfileOwner", "component (optional), userId (optional, default 0)",
                "Delete the profile owner of a user: clearProfileOwner binder channel, profile_owner.xml deletion fallback (ASR-0085)"));
        events.add(desc("IsProfileOwner", "packageName (optional), userId (optional, default 0)",
                "Query the profile owner state of a user (ASR-0085)"));
        events.add(desc("QueryOwnAdminLocal", "none",
                "Probe: this app's own dpm.isAdminActive(TestAdminReceiver) (ASR-0080 end-to-end)"));
        events.add(desc("SetComponentEnabled", "packageName, component (optional pkg/class = whole app), enabled",
                "Disable/enable an application component or a whole application via PackageManager setComponentEnabledSetting / setApplicationEnabledSetting (CHANGE_COMPONENT_ENABLED_STATE, ASR-0019)"));
        events.add(desc("IsComponentEnabled", "packageName, component (optional pkg/class = whole app)",
                "Query the enabled state of an application component or application (ASR-0019)"));
        events.add(desc("SetDefaultSmsApp", "packageName",
                "Set the default SMS app (RoleManager.setRoleHolder android.app.role.SMS, @SystemApi reflection; framework qualifies the target, ASR-0087)"));
        events.add(desc("GetDefaultSmsApp", "none",
                "Get the default SMS app role holder (ASR-0087)"));
        events.add(desc("SetDefaultDialerApp", "packageName",
                "Set the default dialer app (RoleManager.setRoleHolder android.app.role.DIALER, @SystemApi reflection; framework qualifies the target, ASR-0089)"));
        events.add(desc("GetDefaultDialerApp", "none",
                "Get the default dialer app role holder (ASR-0089)"));
        events.add(desc("SetDefaultAssistant", "packageName",
                "Set the default assistant (RoleManager.setRoleHolder android.app.role.ASSISTANT, @SystemApi reflection; framework qualifies the target, ASR-0094)"));
        events.add(desc("GetDefaultAssistant", "none",
                "Get the default assistant role holder (ASR-0094)"));
        events.add(desc("TryStartComponent", "component (pkg/class)",
                "Probe: really start an explicit activity component; ActivityNotFoundException proves it is disabled (ASR-0019 end-to-end)"));
        events.add(desc("ResolveComponent", "component (pkg/class)",
                "Probe: resolve an explicit component through the PackageManager; null proves it is disabled (ASR-0019 cross-check)"));
        events.add(desc("GetAssistantSetting", "none",
                "Probe: read the Settings.Secure assistant key (the framework mirror of the ASSISTANT role holder, ASR-0094 cross-check)"));
        events.add(desc("AddVpnProfile",
                "name, type (pptp/l2tp_ipsec_psk/l2tp_ipsec_rsa/ipsec_xauth_psk/ipsec_xauth_rsa/ipsec_hybrid_rsa/ikev2_ipsec_user_pass/ikev2_ipsec_psk/ikev2_ipsec_rsa or 0..8), server (required), username, password, dnsServers, searchDomains, routes, mppe, l2tpSecret, ipsecIdentifier, ipsecSecret, ipsecUserCert, ipsecCaCert, ipsecServerCert, saveLogin, maxMtu, excludeLocalRoutes",
                "Add or update a VPN profile: store-verified write-back (ASR-0215)"));
        events.add(desc("StartVpnProfile", "name",
                "Connect a stored legacy-type profile via VpnManager.startLegacyVpn, verification aid for disconnect (ASR-0218)"));
        events.add(desc("DeleteVpnProfile", "name",
                "Delete a VPN profile with read-back verification (ASR-0216)"));
        events.add(desc("GetVpnProfileList", "none",
                "List the VPN profiles with details and connection state (ASR-0217)"));
        events.add(desc("DisconnectVpn", "none",
                "Disconnect the profile-based VPN (legacy teardown + provisioned stop, ASR-0218)"));
        events.add(desc("SetVpnDisabled", "disabled",
                "Disable/enable VPN: hide the VPN settings entry + always-on lockdown of the configured VPN, restored on enable (ASR-0214)"));
        events.add(desc("IsVpnDisabled", "none",
                "Query the VPN disabled state plus the VPN connection state (ASR-0214)"));
        events.add(desc("TryOpenVpnSettings", "none",
                "Probe: really start com.android.settings/.Settings$VpnSettingsActivity; ActivityNotFoundException proves the hidden entry (ASR-0214)"));
        events.add(desc("CheckVpnNetworks", "none",
                "Probe: enumerate the active TRANSPORT_VPN networks with owner packages (ASR-0218 cross-check)"));
        events.add(desc("BackupAppData", "packageName, file (optional, default /sdcard/MDM/backup/<pkg>_<ts>.ab)",
                "Back up the data of one app into an .ab file (IBackupManager.fullBackup, adb backup channel; ASR-0125)"));
        events.add(desc("RestoreAppData", "file (path to the .ab file)",
                "Restore app data from an .ab backup file (IBackupManager.fullRestore, adb restore channel; ASR-0125)"));
        events.add(desc("ClearAppCache", "packageName",
                "Clear the cache of one app (PackageManager.deleteApplicationCacheFiles, ASR-0127)"));
        events.add(desc("TakeScreenshot", "file (optional, default /sdcard/Pictures/MDM/screenshot_<ts>.png), width (optional), height (optional)",
                "Capture the current screen into a PNG file (SurfaceControl.screenshot, ASR-0187)"));
        events.add(desc("GetStorageVolumes", "none",
                "List the storage volumes with type/state/removable flags (ASR-0197/0326 read-back)"));
        events.add(desc("UnmountUsbStorage", "volumeId (optional, default first removable public volume)",
                "Unmount a USB storage volume (StorageManager.unmount, ASR-0197)"));
        events.add(desc("FormatExternalSd", "volumeId (optional, default first removable public volume)",
                "Format the external SD card (StorageManager.format, ASR-0326)"));
        events.add(desc("CreateUser", "name, flags (optional int, default 0)",
                "Create a new user (UserManager.createUser, MANAGE_USERS; ASR-0385)"));
        events.add(desc("DeleteUser", "userId (int > 0)",
                "Delete a user (UserManager.removeUser; ASR-0386)"));
        events.add(desc("GetUserList", "none",
                "List the users with id/name/flags (ASR-0385/0386 read-back)"));
        events.add(desc("WriteDataProbe", "value",
                "Probe: write probe.txt into this app's files dir (ASR-0125 backup/restore round trip)"));
        events.add(desc("ReadDataProbe", "none",
                "Probe: read probe.txt from this app's files dir (ASR-0125 verification)"));
        events.add(desc("WriteCacheProbe", "value",
                "Probe: write cache_probe.txt into this app's cache dir (ASR-0127 verification)"));
        events.add(desc("CheckCacheProbe", "none",
                "Probe: check whether cache_probe.txt still exists (ASR-0127 verification)"));
        events.add(desc("GetUserListLocal", "none",
                "Probe: this app's own UserManager.getUsers() list (ASR-0385/0386 cross-check)"));
        events.add(desc("WakeUp", "none",
                "Wake the device up (reflection PowerManager.wakeUp, DEVICE_POWER; ASR-0415)"));
        events.add(desc("GoToSleep", "none",
                "Put the device to sleep (reflection PowerManager.goToSleep, DEVICE_POWER; ASR-0416)"));
        events.add(desc("GetPowerState", "none",
                "Query the power state: isInteractive + dumpsys power wakefulness (ASR-0415/0416 read-back)"));
        events.add(desc("SetDozeDisabled", "disabled",
                "Forbid/allow the Doze battery saving mode: device_idle_constants 7-day timeouts / restore (ASR-0373)"));
        events.add(desc("IsDozeDisabled", "none",
                "Query whether Doze is forbidden plus the effective constants (ASR-0373)"));
        events.add(desc("SetDozeWhitelist", "packageNames (array, full replacement)",
                "Replace the Doze whitelist (PowerWhitelistManager, ignores-battery-optimizations; ASR-0374)"));
        events.add(desc("GetDozeWhitelist", "none",
                "Get the persisted whitelist with live state and the full system whitelist (ASR-0374)"));
        events.add(desc("SetVoiceAssistantDisabled", "disabled",
                "Disable/enable the voice assistant: voice_interaction_service emptied with backup / restored (ASR-0420)"));
        events.add(desc("IsVoiceAssistantDisabled", "none",
                "Query whether the voice assistant is disabled (ASR-0420)"));
        events.add(desc("SetEthernetConfig",
                "mode (dhcp/static), iface (optional), static: ipAddress, prefixLength (0-32), gateway, dns1 (required), dns2, domains (optional), proxy: proxyHost, proxyPort, proxyExclusionList (optional)",
                "Configure the wired NIC: DHCP or static IP/DNS/proxy per interface (ASR-0424)"));
        events.add(desc("GetEthernetConfig", "iface (optional)",
                "Get the wired NIC configuration and environment: availability/interfaces/enabled (ASR-0424)"));
        events.add(desc("SetEthernetEnabled", "enabled",
                "Turn the ethernet stack on/off (EthernetManager.setEthernetEnabled; ASR-0424 companion)"));
        events.add(desc("IsEthernetEnabled", "none",
                "Query whether the ethernet stack is enabled (ASR-0424 companion)"));
        events.add(desc("SetBlueOpen", "open",
                "Enable/disable Bluetooth (ASR-0171/0172)"));
        events.add(desc("IsBlueOpen", "none",
                "Query whether Bluetooth is enabled (ASR-0171/0172)"));
        events.add(desc("SetDiscoverableForbidden", "disabled",
                "Forbid/allow the discoverable mode: scan mode forced to CONNECTABLE, discovery cancelled (ASR-0180)"));
        events.add(desc("IsDiscoverableForbidden", "none",
                "Query whether the discoverable mode is forbidden (ASR-0180)"));
        events.add(desc("SetLimitedDiscoverableForbidden", "disabled",
                "Forbid/allow the limited discoverable mode (same scan-mode enforcement, ASR-0181)"));
        events.add(desc("IsLimitedDiscoverableForbidden", "none",
                "Query whether the limited discoverable mode is forbidden (ASR-0181)"));
        events.add(desc("SetBluetoothPageDisabled", "disabled",
                "Disable/restore the Bluetooth settings page (Settings$BluetoothSettingsActivity, ASR-0174)"));
        events.add(desc("IsBluetoothPageDisabled", "none",
                "Query whether the Bluetooth settings page is disabled (ASR-0174)"));
        events.add(desc("SetBluetoothFileTransferDisabled", "disabled",
                "Forbid/allow Bluetooth file transfer by disabling/restoring every BluetoothOpp component (ASR-0176)"));
        events.add(desc("IsBluetoothFileTransferDisabled", "none",
                "Query whether Bluetooth file transfer is forbidden (ASR-0176)"));
        events.add(desc("SetScoCallDisabled", "disabled",
                "Forbid/allow calls routed to Bluetooth peripherals: communication device kept on earpiece/speaker, SCO stopped (ASR-0178)"));
        events.add(desc("IsScoCallDisabled", "none",
                "Query whether calls via Bluetooth peripherals are forbidden (ASR-0178)"));
        events.add(desc("SetBluetoothAccessPolicy", "mode (0=off, 1=whitelist, 2=blacklist)",
                "Set the Bluetooth connection policy mode and evaluate now (ASR-0175)"));
        events.add(desc("GetBluetoothAccessPolicy", "none",
                "Get the Bluetooth connection policy (mode, lists, current devices; ASR-0175)"));
        events.add(desc("SetBluetoothAddressWhitelist", "addresses (array AA:BB:CC:DD:EE:FF)",
                "Replace the device address whitelist (ASR-0175)"));
        events.add(desc("SetBluetoothAddressBlacklist", "addresses (array AA:BB:CC:DD:EE:FF)",
                "Replace the device address blacklist (ASR-0175)"));
        events.add(desc("SetBluetoothNameWhitelist", "names (array)",
                "Replace the device name whitelist (ASR-0175)"));
        events.add(desc("SetBluetoothNameBlacklist", "names (array)",
                "Replace the device name blacklist (ASR-0175)"));
        events.add(desc("ApplyBluetoothAccessPolicy", "none",
                "Run one evaluation round against the current connected/bonded devices (ASR-0175)"));
        events.add(desc("GetBluetoothStatus", "none",
                "Overall Bluetooth state: adapter/scan mode, policy flags, page/OPP components, audio routing (auxiliary)"));
        events.add(desc("TrySetDiscoverable", "none",
                "Probe: set the discoverable scan mode and observe the policy revert (ASR-0180/0181)"));
        events.add(desc("TryStartDiscovery", "none",
                "Probe: start discovery (limited discoverable state) and observe the policy revert (ASR-0180/0181)"));
        events.add(desc("TryOpenBluetoothSettings", "none",
                "Probe: launch the Bluetooth settings page (ASR-0174)"));
        events.add(desc("ResolveBluetoothShare", "none",
                "Probe: resolve the share-via-Bluetooth SEND intent (ASR-0176)"));
        events.add(desc("GetBluetoothStateLocal", "none",
                "Probe: local adapter state (scan mode, bonded devices) from this app"));
        events.add(desc("SetWallpaper",
                "target (home/lock/both, required), imageBase64 (optional; default: generated solid-color PNG), color (optional, name or 0xAARRGGBB; default red), width (optional, default 800), height (optional, default 480)",
                "Set the home/lock wallpaper from a base64 image via WallpaperManager.setBitmap (ASR-0183/0184)"));
        events.add(desc("GetWallpaper", "none",
                "Query the wallpaper state: ids, framework color stats, applied-content dominant color (ASR-0183/0184)"));
        events.add(desc("GetWallpaperStateLocal", "none",
                "Probe: this app's own WallpaperManager state as an independent cross-check (ASR-0183/0184)"));
        events.add(desc("GetFileAttribute", "path (required), list (optional boolean, directory listing)",
                "Get the attributes of a file system entry (ASR-0108)"));
        events.add(desc("CheckRootStatus", "none",
                "Check the device root status: su/Magisk/Superuser indicators (ASR-0110)"));
        events.add(desc("CheckRootStatusLocal", "none",
                "Probe: same root indicators checked from this app's process (ASR-0110 cross-check)"));
        events.add(desc("GetVpnStatus", "none",
                "Query the VPN service status: TRANSPORT_VPN networks + always-on package (ASR-0219)"));
        events.add(desc("QueryNumberAttribution", "number (required)",
                "Look up a phone number's attribution from the offline database (ASR-0262)"));
        events.add(desc("GetCellInfo", "none",
                "Get the cell id / cell info via TelephonyManager getAllCellInfo + getCellLocation (ASR-0265)"));
        events.add(desc("GetCellInfoLocal", "none",
                "Probe: getAllCellInfo from this app (permission gate evidence, ASR-0265 cross-check)"));
        events.add(desc("GetSimContacts", "none",
                "Get the SIM contacts via IccProvider content://icc/adn (ASR-0290)"));
        events.add(desc("GetSimContactsLocal", "none",
                "Probe: icc/adn query from this app (permission gate evidence, ASR-0290 cross-check)"));
        events.add(desc("GetWebViewInfo", "none",
                "Report the current WebView provider: package/version/enabled + settings + dumpsys cross check (ASR-0442)"));
        events.add(desc("GetWebViewInfoLocal", "none",
                "Probe: webview_provider_default + provider package info from this app (ASR-0442 cross-check)"));
        events.add(desc("SetAppPermissionPageDisabled", "disabled",
                "Disable/enable the app permission page entry (PermissionController, ASR-0048)"));
        events.add(desc("IsAppPermissionPageDisabled", "none",
                "Query the app permission page entry lock (ASR-0048)"));
        events.add(desc("SetSpecifiedAppPermissionPageDisabled", "packageName (optional), disabled",
                "Disable/enable the specified-app permission page (ASR-0049)"));
        events.add(desc("IsSpecifiedAppPermissionPageDisabled", "none",
                "Query the specified-app permission page lock (ASR-0049)"));
        events.add(desc("SetAppNotificationUiDisabled", "disabled",
                "Disable/enable the app notification management UI (ASR-0058)"));
        events.add(desc("IsAppNotificationUiDisabled", "none",
                "Query the app notification management UI lock (ASR-0058)"));
        events.add(desc("SetNotificationWhitelistLocked", "locked",
                "Lock the notification whitelist: whitelisted apps cannot be muted by the user (ASR-0062)"));
        events.add(desc("IsNotificationWhitelistLocked", "none",
                "Query the notification whitelist close lock (ASR-0062)"));
        events.add(desc("SetUserNotificationSettingsDisabled", "disabled",
                "Disallow the user to modify notification settings (pages + state revert, ASR-0063)"));
        events.add(desc("IsUserNotificationSettingsDisabled", "none",
                "Query the user notification settings lock (ASR-0063)"));
        events.add(desc("SetStatusBarNotificationSettingLocked", "locked",
                "Lock the status bar notification setting to the ASR-0059 state (ASR-0064)"));
        events.add(desc("IsStatusBarNotificationSettingLocked", "none",
                "Query the status bar notification setting lock (ASR-0064)"));
        events.add(desc("SetLockscreenNotificationSettingLocked", "locked",
                "Lock the lockscreen notification setting to the ASR-0057 state (ASR-0065)"));
        events.add(desc("IsLockscreenNotificationSettingLocked", "none",
                "Query the lockscreen notification setting lock (ASR-0065)"));
        events.add(desc("SetAppManagementPageDisabled", "disabled",
                "Disable/enable the Settings app management page (ASR-0066)"));
        events.add(desc("IsAppManagementPageDisabled", "none",
                "Query the app management page lock (ASR-0066)"));
        events.add(desc("SetAppManagementPolicyMode", "mode (0=off, 1=whitelist, 2=blacklist)",
                "Set the app management visibility policy mode and apply it (ASR-0067/0068)"));
        events.add(desc("GetAppManagementPolicyMode", "none",
                "Get the app management policy mode + lists (ASR-0067/0068)"));
        events.add(desc("SetAppManagementWhitelist", "packageNames (array)",
                "Replace the app management whitelist (ASR-0067)"));
        events.add(desc("GetAppManagementWhitelist", "none",
                "Get the app management whitelist (ASR-0067)"));
        events.add(desc("SetAppManagementBlacklist", "packageNames (array)",
                "Replace the app management blacklist (ASR-0068)"));
        events.add(desc("GetAppManagementBlacklist", "none",
                "Get the app management blacklist (ASR-0068)"));
        events.add(desc("ApplyAppManagementPolicy", "none",
                "Apply the app management visibility policy now (ASR-0067/0068)"));
        events.add(desc("SetEmailPolicyMode", "mode (0=off, 2=blacklist)",
                "Set the email control policy mode and apply it now (ASR-0423)"));
        events.add(desc("GetEmailPolicyMode", "none",
                "Get the email control policy mode + blacklist + controlled list (ASR-0423)"));
        events.add(desc("SetEmailBlacklist", "packageNames (array, full replacement)",
                "Replace the email application blacklist (ASR-0423)"));
        events.add(desc("GetEmailBlacklist", "none",
                "Get the email application blacklist (ASR-0423)"));
        events.add(desc("ApplyEmailPolicy", "none",
                "Reconcile the whole installed app set with the email blacklist now (ASR-0423)"));
        events.add(desc("IsEmailControlled", "packageName",
                "Query whether one package is under email control with its live suspended/hidden state (ASR-0423)"));
        events.add(desc("GetEnabledAccessibilityServices", "none",
                "Query the enabled accessibility services list (ASR-0076)"));
        events.add(desc("SetAccessibilityUiEntryDisabled", "disabled",
                "Disable/enable the accessibility UI entry (Settings page, ASR-0077)"));
        events.add(desc("IsAccessibilityUiEntryDisabled", "none",
                "Query the accessibility UI entry lock (ASR-0077)"));
        events.add(desc("SetSmsAppSettingLocked", "locked",
                "Lock changing the default SMS app (role picker, ASR-0086)"));
        events.add(desc("IsSmsAppSettingLocked", "none",
                "Query the SMS app setting lock (ASR-0086)"));
        events.add(desc("SetDialerAppSettingLocked", "locked",
                "Lock changing the default dialer app (role picker, ASR-0088)"));
        events.add(desc("IsDialerAppSettingLocked", "none",
                "Query the dialer app setting lock (ASR-0088)"));
        events.add(desc("SetAssistantModificationLocked", "locked",
                "Lock changing the Assistant (role picker + assist page, ASR-0093)"));
        events.add(desc("IsAssistantModificationLocked", "none",
                "Query the Assistant modification lock (ASR-0093)"));
        events.add(desc("SetDefaultBrowserModificationLocked", "locked",
                "Lock changing the default browser (role picker, ASR-0100)"));
        events.add(desc("IsDefaultBrowserModificationLocked", "none",
                "Query the default browser modification lock (ASR-0100)"));
        events.add(desc("SetUsbSettingsLocked", "locked",
                "Lock the USB settings page (ASR-0199)"));
        events.add(desc("IsUsbSettingsLocked", "none",
                "Query the USB settings lock (ASR-0199)"));
        events.add(desc("SetUsbDebuggingSettingLocked", "locked",
                "Lock the USB debugging setting (adb_enabled value revert, ASR-0203)"));
        events.add(desc("IsUsbDebuggingSettingLocked", "none",
                "Query the USB debugging setting lock (ASR-0203)"));
        events.add(desc("SetApnSettingsEnabled", "enabled",
                "Enable/disable the APN settings item (ASR-0308)"));
        events.add(desc("IsApnSettingsEnabled", "none",
                "Query the APN settings item state (ASR-0308)"));
        events.add(desc("SetUserAirplaneModeChangeLocked", "locked",
                "Lock the airplane mode against user changes (ASR-0322)"));
        events.add(desc("IsUserAirplaneModeChangeLocked", "none",
                "Query the airplane mode change lock (ASR-0322)"));
        events.add(desc("SetLanguageSwitchingDisabled", "disabled",
                "Disallow system language switching (pages + SetLanguage gate, ASR-0334)"));
        events.add(desc("IsLanguageSwitchingDisabled", "none",
                "Query the language switching lock (ASR-0334)"));
        events.add(desc("SetUserLanguageModificationDisabled", "disabled",
                "Disallow the user to modify the system language (pages, ASR-0335)"));
        events.add(desc("IsUserLanguageModificationDisabled", "none",
                "Query the user language modification lock (ASR-0335)"));
        events.add(desc("SetSpeakerDisabled", "disabled",
                "Disable/enable the speaker (zero volumes + setSpeakerphoneOn(false), ASR-0370)"));
        events.add(desc("IsSpeakerDisabled", "none",
                "Query the speaker policy state (ASR-0370)"));
        events.add(desc("GetEntriesLocal", "none",
                "Probe: resolve the locked settings pages / role request + component states from this app"));
        events.add(desc("GetValuesLocal", "none",
                "Probe: raw Settings values (adb_enabled / airplane / lockscreen / accessibility)"));
        events.add(desc("GetAudioStateLocal", "none",
                "Probe: local AudioManager volumes + speakerphone state (ASR-0370 cross-check)"));
        events.add(desc("SetUninstallWhitelist", "packageNames (array, full replacement)",
                "Replace the uninstall whitelist: listed apps become uninstall-blocked, others unblocked (ASR-0006)"));
        events.add(desc("GetUninstallWhitelist", "none",
                "Get the uninstall whitelist with live block states (ASR-0006)"));
        events.add(desc("SetUninstallBlacklist", "packageNames (array, full replacement)",
                "Replace the uninstall blacklist: listed apps become uninstall-blocked (ASR-0007)"));
        events.add(desc("GetUninstallBlacklist", "none",
                "Get the uninstall blacklist with live block states (ASR-0007)"));
        events.add(desc("SetInstallPolicyMode", "mode (0=off, 1=whitelist, 2=blacklist)",
                "Set the install policy mode; blacklist = auto-uninstall matched apps (ASR-0010)"));
        events.add(desc("GetInstallPolicyMode", "none",
                "Get the install policy mode + blacklist regexes (ASR-0010)"));
        events.add(desc("SetInstallBlacklist", "patterns (array of regexes, full replacement)",
                "Replace the install blacklist regexes (ASR-0010)"));
        events.add(desc("GetInstallBlacklist", "none",
                "Get the install blacklist regexes (ASR-0010)"));
        events.add(desc("SetKeepAliveEnabled", "packageName, enabled",
                "Enable/disable keep-alive of one app (Doze/App Standby whitelist approximation, ASR-0015)"));
        events.add(desc("IsKeepAliveEnabled", "packageName",
                "Query the keep-alive flag + live ignoring state of one app (ASR-0015)"));
        events.add(desc("GetKeepAliveList", "none",
                "List all keep-alive flagged apps with live states (ASR-0015)"));
        events.add(desc("IsAppAlive", "packageName",
                "Detect whether an application is alive (running process, ASR-0029)"));
        events.add(desc("SetManageExternalStorageGranted", "packageName, granted",
                "Grant/revoke the MANAGE_EXTERNAL_STORAGE (all files access) of one app (AppOps, ASR-0040)"));
        events.add(desc("IsManageExternalStorageGranted", "packageName",
                "Query the MANAGE_EXTERNAL_STORAGE state of one app (ASR-0040)"));
        events.add(desc("SetDesktopIconHidden", "packageName, hidden",
                "Hide/show the desktop icon of one app (setApplicationHidden; launcher-dependent, ASR-0072)"));
        events.add(desc("IsDesktopIconHidden", "packageName",
                "Query whether one app's icon is hidden (ASR-0072)"));
        events.add(desc("GetPackageInfo", "packageName",
                "Get the PackageInfo of one app: version/uid/flags/permissions/signature (ASR-0073)"));
        events.add(desc("SetDefaultLauncherSettingLocked", "locked",
                "Lock/unlock the user's ability to change the default launcher (no_config_home_app, ASR-0091)"));
        events.add(desc("IsDefaultLauncherSettingLocked", "none",
                "Query the default launcher change lock (ASR-0091)"));
        events.add(desc("SetDefaultVideoPlayer", "packageName, activityName (optional)",
                "Set the default video player (persistent preferred activity for video/*, ASR-0095)"));
        events.add(desc("GetDefaultVideoPlayer", "none",
                "Get the current default video player (ASR-0095)"));
        events.add(desc("ClearDefaultVideoPlayer", "packageName",
                "Clear the video player persistent preferred activity of one package (ASR-0095)"));
        events.add(desc("SetDefaultAppForFileType", "mimeType, packageName, activityName (optional)",
                "Set the default app for a file type (persistent preferred activity, ASR-0102)"));
        events.add(desc("GetDefaultAppForFileType", "mimeType",
                "Get the current default app for a file type (ASR-0102)"));
        events.add(desc("ClearDefaultAppForFileType", "mimeType, packageName",
                "Clear the file-type default binding of one package (ASR-0102)"));
        events.add(desc("SetUsbDataTransferDisabled", "disabled",
                "Disable/enable USB data transfer (no_usb_file_transfer + function charging/restore, ASR-0191)"));
        events.add(desc("IsUsbDataTransferDisabled", "none",
                "Query the USB data transfer lock + current USB function (ASR-0191)"));
        events.add(desc("SetUsbExternalStorageDisabled", "disabled",
                "Disable/enable USB external storage (no_physical_media, ASR-0196)"));
        events.add(desc("IsUsbExternalStorageDisabled", "none",
                "Query the USB external storage lock (ASR-0196)"));
        events.add(desc("SetSdCardMountDisabled", "disabled",
                "Disable/enable the SD card mount (no_physical_media shared + unmount, ASR-0325)"));
        events.add(desc("IsSdCardMountDisabled", "none",
                "Query the SD card mount lock (ASR-0325)"));
        events.add(desc("SetDataRoamingDisabled", "disabled",
                "Disable/enable the data roaming service (DISALLOW_DATA_ROAMING, ASR-0273)"));
        events.add(desc("IsDataRoamingDisabled", "none",
                "Query whether data roaming is disabled (ASR-0273)"));
        events.add(desc("SetUserVolumeSettingDisabled", "disabled",
                "Disable/enable the user volume setting (DISALLOW_ADJUST_VOLUME shared, ASR-0390)"));
        events.add(desc("IsUserVolumeSettingDisabled", "none",
                "Query the user volume setting lock (ASR-0390)"));
        events.add(desc("SetVolumeKeyDisabled", "disabled",
                "Disable/enable the volume physical keys (DISALLOW_ADJUST_VOLUME shared, ASR-0391)"));
        events.add(desc("IsVolumeKeyDisabled", "none",
                "Query the volume key lock (ASR-0391)"));
        events.add(desc("SetMediaVolumeModificationLocked", "locked",
                "Lock/unlock media volume modification (capture + 10 s corrector, ASR-0393)"));
        events.add(desc("IsMediaVolumeModificationLocked", "none",
                "Query the media volume modification lock (ASR-0393)"));
        events.add(desc("SetNotificationVolumeModificationLocked", "locked",
                "Lock/unlock notification volume modification (capture + 10 s corrector, ASR-0395)"));
        events.add(desc("IsNotificationVolumeModificationLocked", "none",
                "Query the notification volume modification lock (ASR-0395)"));
        events.add(desc("SetAlarmVolumeModificationLocked", "locked",
                "Lock/unlock alarm volume modification (capture + 10 s corrector, ASR-0397)"));
        events.add(desc("IsAlarmVolumeModificationLocked", "none",
                "Query the alarm volume modification lock (ASR-0397)"));
        events.add(desc("SetAutoSleepDisabled", "disabled",
                "Disable/enable the auto sleep (screen_off_timeout max / restore, ASR-0412)"));
        events.add(desc("IsAutoSleepDisabled", "none",
                "Query the auto-sleep state (ASR-0412)"));
        events.add(desc("SetAlwaysFullscreen", "fullscreen",
                "Enable/disable always fullscreen (hide/restore status bar + nav bar, ASR-0431)"));
        events.add(desc("IsAlwaysFullscreen", "none",
                "Query the always-fullscreen state (ASR-0431)"));
        events.add(desc("SetVolume", "streamType, volume (index)",
                "Set one stream volume (verification aid for the stream locks)"));
        events.add(desc("GetVolume", "streamType",
                "Get one stream volume: current/max (verification aid for the stream locks)"));
        return events;
    }

    private static boolean checkNetworkParam(Map<String, Object> p) {
        Object network = p.get("network");
        if (network == null) {
            return true;
        }
        String value = String.valueOf(network).toLowerCase();
        return value.equals("all") || value.equals("wifi") || value.equals("mobile");
    }

    /**
     * Generate a solid-color PNG test image and return it base64 encoded
     * (NO_WRAP). Params: color (name or 0xAARRGGBB, default red), width
     * (default 800), height (default 480).
     */
    private static String generateTestImage(Map<String, Object> p) {
        int color = parseColor(str(p, "color"), 0xFFFF0000);
        int width = parseInt(str(p, "width"), 800);
        int height = parseInt(str(p, "height"), 480);
        if (width <= 0 || width > 4096 || height <= 0 || height > 4096) {
            width = 800;
            height = 480;
        }
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                width, height, android.graphics.Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        bitmap.recycle();
        return android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP);
    }

    private static int parseColor(String value, int fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        String v = value.trim().toLowerCase();
        if (v.equals("red")) {
            return 0xFFFF0000;
        }
        if (v.equals("green")) {
            return 0xFF00FF00;
        }
        if (v.equals("blue")) {
            return 0xFF0000FF;
        }
        if (v.equals("purple")) {
            return 0xFFFF00FF;
        }
        if (v.equals("cyan")) {
            return 0xFF00FFFF;
        }
        if (v.equals("yellow")) {
            return 0xFFFFFF00;
        }
        if (v.equals("magenta")) {
            return 0xFFFF00FF;
        }
        if (v.equals("gray") || v.equals("grey")) {
            return 0xFF808080;
        }
        if (v.equals("white")) {
            return 0xFFFFFFFF;
        }
        if (v.equals("black")) {
            return 0xFF000000;
        }
        try {
            long parsed;
            if (v.startsWith("0x")) {
                parsed = Long.parseLong(v.substring(2), 16);
            } else if (v.startsWith("#")) {
                parsed = Long.parseLong(v.substring(1), 16);
            } else {
                parsed = Long.parseLong(v);
            }
            if (parsed < 0 || parsed > 0xFFFFFFFFL) {
                return fallback;
            }
            // Alpha-less forms (#RRGGBB / 0xRRGGBB / decimal <= 24-bit) are
            // documented as opaque colors; without this the wallpaper would
            // be fully transparent while verification (which ignores alpha)
            // still reports a match.
            if (parsed <= 0xFFFFFFL) {
                parsed |= 0xFF000000L;
            }
            return (int) parsed;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static Map<String, Object> desc(String event, String params, String description) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("event", event);
        entry.put("params", params);
        entry.put("description", description);
        return entry;
    }

    private static String str(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v != null ? String.valueOf(v) : "";
    }
}
