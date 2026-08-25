package com.hmdm.testapp;

import android.os.Bundle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WLAN config & access control tests (ASR-0144 configure WLAN params,
 * ASR-0151 enterprise Wi-Fi, ASR-0145 delete saved hotspot, ASR-0147 SSID
 * black/white list, ASR-0148 MAC black/white list, ASR-0161 auto-connect
 * policy): policy/config set/query via the Launcher API.
 */
public class WifiAccessTestActivity extends BaseTestActivity {

    // Samples used by the UI buttons (IPC can pass any values).
    private static final String SAMPLE_WPA2_SSID = "HYX-MDM-WIFI";
    private static final String SAMPLE_WPA2_PASSWORD = "HYX@12345";
    private static final String SAMPLE_OPEN_SSID = "HYX-MDM-OPEN";
    private static final String SAMPLE_EAP_SSID = "HYX-MDM-EAP";
    private static final String SAMPLE_SSID = "Syrius_Guest";

    // The framework-enforcement probes poll up to 8 s, far above the 5 s ANR
    // threshold, so they run off the main thread.
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wifi_access_test);
        bindLogView();

        findViewById(R.id.btn_configure_wpa2).setOnClickListener(v -> onConfigureWpa2());
        findViewById(R.id.btn_configure_open).setOnClickListener(v -> onConfigureOpen());
        findViewById(R.id.btn_configure_eap).setOnClickListener(v -> onConfigureEap());
        findViewById(R.id.btn_remove_sample).setOnClickListener(v -> onRemoveSample());
        findViewById(R.id.btn_list_saved).setOnClickListener(v -> runAction("GetSavedWifiNetworks", new HashMap<String, Object>()));

        findViewById(R.id.btn_ssid_mode_off).setOnClickListener(v -> onSetMode("SetSsidAccessPolicy", 0));
        findViewById(R.id.btn_ssid_mode_whitelist).setOnClickListener(v -> onSetMode("SetSsidAccessPolicy", 1));
        findViewById(R.id.btn_ssid_mode_blacklist).setOnClickListener(v -> onSetMode("SetSsidAccessPolicy", 2));
        findViewById(R.id.btn_ssid_whitelist_sample).setOnClickListener(v -> onSetSsidList("SetSsidAccessWhitelist"));
        findViewById(R.id.btn_ssid_blacklist_sample).setOnClickListener(v -> onSetSsidList("SetSsidAccessBlacklist"));
        findViewById(R.id.btn_ssid_policy_query).setOnClickListener(v -> runAction("GetSsidAccessPolicy", new HashMap<String, Object>()));

        findViewById(R.id.btn_mac_mode_off).setOnClickListener(v -> onSetMode("SetMacAccessPolicy", 0));
        findViewById(R.id.btn_mac_mode_whitelist).setOnClickListener(v -> onSetMode("SetMacAccessPolicy", 1));
        findViewById(R.id.btn_mac_mode_blacklist).setOnClickListener(v -> onSetMode("SetMacAccessPolicy", 2));
        findViewById(R.id.btn_mac_whitelist_current).setOnClickListener(v -> onSetMacList("SetMacAccessWhitelist"));
        findViewById(R.id.btn_mac_blacklist_current).setOnClickListener(v -> onSetMacList("SetMacAccessBlacklist"));
        findViewById(R.id.btn_mac_policy_query).setOnClickListener(v -> runAction("GetMacAccessPolicy", new HashMap<String, Object>()));

        findViewById(R.id.btn_autoconnect_forbid).setOnClickListener(v -> onSetEnabled("SetWifiAutoConnectPolicy", true));
        findViewById(R.id.btn_autoconnect_allow).setOnClickListener(v -> onSetEnabled("SetWifiAutoConnectPolicy", false));
        findViewById(R.id.btn_autoconnect_query).setOnClickListener(v -> runAction("GetWifiAutoConnectPolicy", new HashMap<String, Object>()));

        findViewById(R.id.btn_apply_policy).setOnClickListener(v -> runAction("ApplyWifiAccessPolicy", new HashMap<String, Object>()));
        findViewById(R.id.btn_try_connect_open_wifi).setOnClickListener(v -> runActionAsync("TryConnectOpenWifi", paramSsid()));
    }

    /** Run a probe action off the main thread (polls up to 8 s). */
    private void runActionAsync(String event, Map<String, Object> param) {
        worker.execute(() -> {
            Map<Object, Object> result = TestActions.execute(this, event, param);
            runOnUiThread(() -> appendLog(event + " RESULT: " + result.get(TestActions.RESULT_KEY)));
        });
    }

    private Map<String, Object> paramSsid() {
        Map<String, Object> param = new HashMap<>();
        param.put("ssid", SAMPLE_SSID);
        return param;
    }

    private void onConfigureWpa2() {
        Map<String, Object> param = new HashMap<>();
        param.put("ssid", SAMPLE_WPA2_SSID);
        param.put("securityType", 1);
        param.put("password", SAMPLE_WPA2_PASSWORD);
        runAction("ConfigureWifi", param);
    }

    private void onConfigureOpen() {
        Map<String, Object> param = new HashMap<>();
        param.put("ssid", SAMPLE_OPEN_SSID);
        param.put("securityType", 0);
        runAction("ConfigureWifi", param);
    }

    private void onConfigureEap() {
        Map<String, Object> param = new HashMap<>();
        param.put("ssid", SAMPLE_EAP_SSID);
        param.put("eapMethod", 0);
        param.put("phase2", 3);
        param.put("identity", "testuser");
        param.put("anonymousIdentity", "anon@example.com");
        param.put("password", "testpass123");
        runAction("ConfigureEnterpriseWifi", param);
    }

    private void onRemoveSample() {
        Map<String, Object> param = new HashMap<>();
        param.put("ssid", SAMPLE_WPA2_SSID);
        runAction("RemoveWifiNetwork", param);
    }

    private void onSetMode(String event, int mode) {
        Map<String, Object> param = new HashMap<>();
        param.put("mode", mode);
        runAction(event, param);
    }

    private void onSetSsidList(String event) {
        Map<String, Object> param = new HashMap<>();
        param.put("ssids", new ArrayList<String>(Arrays.asList(SAMPLE_SSID)));
        runAction(event, param);
    }

    private void onSetMacList(String event) {
        // Use the BSSID of the currently connected AP when available.
        Map<String, Object> param = new HashMap<>();
        Map<Object, Object> query = apiCall("GetMacAccessPolicy", new HashMap<String, Object>());
        Object raw = query.get(TestActions.RESULT_KEY);
        String bssid = "";
        if (raw instanceof Map) {
            Object current = ((Map<?, ?>) raw).get("currentBssid");
            if (current != null) {
                bssid = String.valueOf(current);
            }
        }
        if (bssid.isEmpty()) {
            appendLog(event + " RESULT: no current BSSID available");
            return;
        }
        param.put("macs", new ArrayList<String>(Arrays.asList(bssid)));
        runAction(event, param);
    }

    private void onSetEnabled(String event, boolean enabled) {
        Map<String, Object> param = new HashMap<>();
        param.put("enabled", enabled);
        runAction(event, param);
    }
}
