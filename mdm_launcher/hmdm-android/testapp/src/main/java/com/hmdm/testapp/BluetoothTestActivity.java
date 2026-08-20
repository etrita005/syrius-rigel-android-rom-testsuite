package com.hmdm.testapp;

import android.os.Bundle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bluetooth control tests (ASR-0180 forbid discoverable mode, ASR-0181
 * forbid limited discoverable mode, ASR-0174 Bluetooth settings page
 * availability, ASR-0175 connection black/white list, ASR-0176 forbid file
 * transfer (BluetoothOpp), ASR-0178 forbid calls via Bluetooth peripherals):
 * policy set/query via the Launcher API plus real-framework probes.
 */
public class BluetoothTestActivity extends BaseTestActivity {

    // Sample used by the UI buttons (IPC can pass any values).
    private static final String SAMPLE_ADDRESS = "00:11:22:33:44:55";

    // The discoverable-revert probe polls up to 3 s, so it runs off the
    // main thread to stay far below the 5 s ANR threshold.
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bluetooth_test);
        bindLogView();

        findViewById(R.id.btn_bt_on).setOnClickListener(v -> onSetEnabled("SetBlueOpen", true, "open"));
        findViewById(R.id.btn_bt_off).setOnClickListener(v -> onSetEnabled("SetBlueOpen", false, "open"));
        findViewById(R.id.btn_bt_query).setOnClickListener(v -> runAction("IsBlueOpen", new HashMap<String, Object>()));
        findViewById(R.id.btn_bt_status).setOnClickListener(v -> runAction("GetBluetoothStatus", new HashMap<String, Object>()));

        findViewById(R.id.btn_discoverable_forbid).setOnClickListener(v -> onSetEnabled("SetDiscoverableForbidden", true, "disabled"));
        findViewById(R.id.btn_discoverable_allow).setOnClickListener(v -> onSetEnabled("SetDiscoverableForbidden", false, "disabled"));
        findViewById(R.id.btn_limited_forbid).setOnClickListener(v -> onSetEnabled("SetLimitedDiscoverableForbidden", true, "disabled"));
        findViewById(R.id.btn_limited_allow).setOnClickListener(v -> onSetEnabled("SetLimitedDiscoverableForbidden", false, "disabled"));
        findViewById(R.id.btn_discoverable_query).setOnClickListener(v -> runAction("IsDiscoverableForbidden", new HashMap<String, Object>()));
        findViewById(R.id.btn_limited_query).setOnClickListener(v -> runAction("IsLimitedDiscoverableForbidden", new HashMap<String, Object>()));
        findViewById(R.id.btn_probe_discoverable).setOnClickListener(v -> runActionAsync("TrySetDiscoverable", new HashMap<String, Object>()));
        findViewById(R.id.btn_probe_discovery).setOnClickListener(v -> runActionAsync("TryStartDiscovery", new HashMap<String, Object>()));

        findViewById(R.id.btn_page_disable).setOnClickListener(v -> onSetEnabled("SetBluetoothPageDisabled", true, "disabled"));
        findViewById(R.id.btn_page_enable).setOnClickListener(v -> onSetEnabled("SetBluetoothPageDisabled", false, "disabled"));
        findViewById(R.id.btn_page_query).setOnClickListener(v -> runAction("IsBluetoothPageDisabled", new HashMap<String, Object>()));
        findViewById(R.id.btn_probe_open_page).setOnClickListener(v -> runAction("TryOpenBluetoothSettings", new HashMap<String, Object>()));

        findViewById(R.id.btn_ft_forbid).setOnClickListener(v -> onSetEnabled("SetBluetoothFileTransferDisabled", true, "disabled"));
        findViewById(R.id.btn_ft_allow).setOnClickListener(v -> onSetEnabled("SetBluetoothFileTransferDisabled", false, "disabled"));
        findViewById(R.id.btn_ft_query).setOnClickListener(v -> runAction("IsBluetoothFileTransferDisabled", new HashMap<String, Object>()));
        findViewById(R.id.btn_probe_share).setOnClickListener(v -> runAction("ResolveBluetoothShare", new HashMap<String, Object>()));

        findViewById(R.id.btn_sco_forbid).setOnClickListener(v -> onSetEnabled("SetScoCallDisabled", true, "disabled"));
        findViewById(R.id.btn_sco_allow).setOnClickListener(v -> onSetEnabled("SetScoCallDisabled", false, "disabled"));
        findViewById(R.id.btn_sco_query).setOnClickListener(v -> runAction("IsScoCallDisabled", new HashMap<String, Object>()));

        findViewById(R.id.btn_access_mode_off).setOnClickListener(v -> onSetMode("SetBluetoothAccessPolicy", 0));
        findViewById(R.id.btn_access_mode_whitelist).setOnClickListener(v -> onSetMode("SetBluetoothAccessPolicy", 1));
        findViewById(R.id.btn_access_mode_blacklist).setOnClickListener(v -> onSetMode("SetBluetoothAccessPolicy", 2));
        findViewById(R.id.btn_address_whitelist_sample).setOnClickListener(v -> onSetAddressList("SetBluetoothAddressWhitelist"));
        findViewById(R.id.btn_address_blacklist_sample).setOnClickListener(v -> onSetAddressList("SetBluetoothAddressBlacklist"));
        findViewById(R.id.btn_name_whitelist_sample).setOnClickListener(v -> onSetNameList("SetBluetoothNameWhitelist"));
        findViewById(R.id.btn_name_blacklist_sample).setOnClickListener(v -> onSetNameList("SetBluetoothNameBlacklist"));
        findViewById(R.id.btn_access_query).setOnClickListener(v -> runAction("GetBluetoothAccessPolicy", new HashMap<String, Object>()));
        findViewById(R.id.btn_access_apply).setOnClickListener(v -> runAction("ApplyBluetoothAccessPolicy", new HashMap<String, Object>()));
    }

    /** Run a probe action off the main thread (polls up to 3 s). */
    private void runActionAsync(String event, Map<String, Object> param) {
        worker.execute(() -> {
            Map<Object, Object> result = TestActions.execute(this, event, param);
            runOnUiThread(() -> appendLog(event + " RESULT: " + result.get(TestActions.RESULT_KEY)));
        });
    }

    private void onSetEnabled(String event, boolean enabled, String key) {
        Map<String, Object> param = new HashMap<>();
        param.put(key, enabled);
        runAction(event, param);
    }

    private void onSetMode(String event, int mode) {
        Map<String, Object> param = new HashMap<>();
        param.put("mode", mode);
        runAction(event, param);
    }

    private void onSetAddressList(String event) {
        // Use the address of the first bonded device when available.
        Map<String, Object> param = new HashMap<>();
        Map<Object, Object> query = apiCall("GetBluetoothStatus", new HashMap<String, Object>());
        String address = "";
        Object raw = query.get(TestActions.RESULT_KEY);
        if (raw instanceof Map) {
            Object bonded = ((Map<?, ?>) raw).get("bondedDevices");
            if (bonded instanceof java.util.List && !((java.util.List<?>) bonded).isEmpty()) {
                address = String.valueOf(((java.util.List<?>) bonded).get(0));
            }
        }
        if (address.isEmpty()) {
            appendLog(event + " RESULT: no bonded device available, using sample " + SAMPLE_ADDRESS);
            address = SAMPLE_ADDRESS;
        }
        param.put("addresses", new ArrayList<String>(Arrays.asList(address)));
        runAction(event, param);
    }

    private void onSetNameList(String event) {
        Map<String, Object> param = new HashMap<>();
        param.put("names", new ArrayList<String>(Arrays.asList("HYX-MDM-TEST-DEVICE")));
        runAction(event, param);
    }
}
