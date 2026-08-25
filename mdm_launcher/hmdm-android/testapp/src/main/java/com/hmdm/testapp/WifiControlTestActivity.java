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
 * WLAN control tests (ASR-0150 AP config lockdown, ASR-0152 enterprise Wi-Fi
 * SSID allowlist, ASR-0153 manual network add, ASR-0155 edit WLAN settings,
 * ASR-0158 minimum Wi-Fi security level, ASR-0160 WLAN direct, ASR-0168
 * hotspot config): policy set/query via the Launcher API.
 */
public class WifiControlTestActivity extends BaseTestActivity {

    // Sample allowlist used by the UI button (IPC can pass any list).
    private static final List<String> SAMPLE_SSIDS = Arrays.asList("HYX-MDM-WIFI");

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
        setContentView(R.layout.activity_wifi_control_test);
        bindLogView();

        findViewById(R.id.btn_ap_config_disabled).setOnClickListener(v -> onSetDisabled("SetApConfigLockdown", true));
        findViewById(R.id.btn_ap_config_enabled).setOnClickListener(v -> onSetDisabled("SetApConfigLockdown", false));
        findViewById(R.id.btn_ap_config_query).setOnClickListener(v -> runAction("IsApConfigLockdown", new HashMap<String, Object>()));

        findViewById(R.id.btn_ssid_whitelist_set).setOnClickListener(v -> onSetSsidWhitelist(true));
        findViewById(R.id.btn_ssid_whitelist_clear).setOnClickListener(v -> onSetSsidWhitelist(false));
        findViewById(R.id.btn_ssid_whitelist_query).setOnClickListener(v -> runAction("GetWifiSsidWhitelist", new HashMap<String, Object>()));

        findViewById(R.id.btn_manual_add_disabled).setOnClickListener(v -> onSetDisabled("SetManualAddWifiDisabled", true));
        findViewById(R.id.btn_manual_add_enabled).setOnClickListener(v -> onSetDisabled("SetManualAddWifiDisabled", false));
        findViewById(R.id.btn_manual_add_query).setOnClickListener(v -> runAction("IsManualAddWifiDisabled", new HashMap<String, Object>()));

        findViewById(R.id.btn_edit_wlan_disabled).setOnClickListener(v -> onSetDisabled("SetUserConfigWifiDisabled", true));
        findViewById(R.id.btn_edit_wlan_enabled).setOnClickListener(v -> onSetDisabled("SetUserConfigWifiDisabled", false));
        findViewById(R.id.btn_edit_wlan_query).setOnClickListener(v -> runAction("IsUserConfigWifiDisabled", new HashMap<String, Object>()));

        findViewById(R.id.btn_security_level_open).setOnClickListener(v -> onSetSecurityLevel(0));
        findViewById(R.id.btn_security_level_personal).setOnClickListener(v -> onSetSecurityLevel(1));
        findViewById(R.id.btn_security_level_eap).setOnClickListener(v -> onSetSecurityLevel(2));
        findViewById(R.id.btn_security_level_192).setOnClickListener(v -> onSetSecurityLevel(3));
        findViewById(R.id.btn_security_level_query).setOnClickListener(v -> runAction("GetMinimumWifiSecurityLevel", new HashMap<String, Object>()));

        findViewById(R.id.btn_wifi_direct_disabled).setOnClickListener(v -> onSetDisabled("SetWifiDirectDisabled", true));
        findViewById(R.id.btn_wifi_direct_enabled).setOnClickListener(v -> onSetDisabled("SetWifiDirectDisabled", false));
        findViewById(R.id.btn_wifi_direct_query).setOnClickListener(v -> runAction("IsWifiDirectDisabled", new HashMap<String, Object>()));

        findViewById(R.id.btn_tethering_config_disabled).setOnClickListener(v -> onSetDisabled("SetUserConfigTetheringDisabled", true));
        findViewById(R.id.btn_tethering_config_enabled).setOnClickListener(v -> onSetDisabled("SetUserConfigTetheringDisabled", false));
        findViewById(R.id.btn_tethering_config_query).setOnClickListener(v -> runAction("IsUserConfigTetheringDisabled", new HashMap<String, Object>()));

        findViewById(R.id.btn_try_add_wifi_network).setOnClickListener(v -> runActionAsync("TryAddWifiNetwork", new HashMap<String, Object>()));
        findViewById(R.id.btn_try_connect_open_wifi).setOnClickListener(v -> runActionAsync("TryConnectOpenWifi", paramSsid()));
    }

    /**
     * Run a probe action off the main thread (the WifiVerifier polls up to
     * 8 s) and post the result to the log view; equivalent to runAction.
     */
    private void runActionAsync(String event, Map<String, Object> param) {
        worker.execute(() -> {
            Map<Object, Object> result = TestActions.execute(this, event, param);
            runOnUiThread(() -> appendLog(event + " RESULT: " + result.get(TestActions.RESULT_KEY)));
        });
    }

    private Map<String, Object> paramSsid() {
        Map<String, Object> param = new HashMap<>();
        param.put("ssid", "Syrius_Guest");
        return param;
    }

    private void onSetDisabled(String event, boolean disabled) {
        Map<String, Object> param = new HashMap<>();
        param.put("disabled", disabled);
        runAction(event, param);
    }

    private void onSetSsidWhitelist(boolean enabled) {
        Map<String, Object> param = new HashMap<>();
        param.put("enabled", enabled);
        if (enabled) {
            param.put("ssids", new ArrayList<String>(SAMPLE_SSIDS));
        }
        runAction("SetWifiSsidWhitelist", param);
    }

    private void onSetSecurityLevel(int level) {
        Map<String, Object> param = new HashMap<>();
        param.put("level", level);
        runAction("SetMinimumWifiSecurityLevel", param);
    }
}
