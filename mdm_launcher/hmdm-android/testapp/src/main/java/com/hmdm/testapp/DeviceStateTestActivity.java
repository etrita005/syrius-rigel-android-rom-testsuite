package com.hmdm.testapp;

import android.os.Bundle;

import java.util.HashMap;
import java.util.Map;

/**
 * Device state control tests: location service (ASR-0310/0311), passive
 * location (ASR-0313), airplane mode (ASR-0319/0320/0321), navigation bar
 * (ASR-0349), NFC (ASR-0315/0316/0317) and mobile data
 * (ASR-0275/0276/0277/0279).
 */
public class DeviceStateTestActivity extends BaseTestActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_state_test);
        bindLogView();

        findViewById(R.id.btn_location_disable).setOnClickListener(v ->
                setEnabled("SetLocationEnabled", false));
        findViewById(R.id.btn_location_enable).setOnClickListener(v ->
                setEnabled("SetLocationEnabled", true));
        findViewById(R.id.btn_location_query).setOnClickListener(v ->
                runAction("IsLocationEnabled", new HashMap<String, Object>()));
        findViewById(R.id.btn_location_force_on).setOnClickListener(v ->
                setForceOpen("ForceOpenLocation", true));
        findViewById(R.id.btn_location_force_off).setOnClickListener(v ->
                setForceOpen("ForceOpenLocation", false));

        findViewById(R.id.btn_passive_location_forbid).setOnClickListener(v ->
                setAllowed("SetPassiveLocationAllowed", false));
        findViewById(R.id.btn_passive_location_allow).setOnClickListener(v ->
                setAllowed("SetPassiveLocationAllowed", true));
        findViewById(R.id.btn_passive_location_query).setOnClickListener(v ->
                runAction("IsPassiveLocationAllowed", new HashMap<String, Object>()));

        findViewById(R.id.btn_airplane_off).setOnClickListener(v ->
                setEnabled("SetAirplaneMode", false));
        findViewById(R.id.btn_airplane_on).setOnClickListener(v ->
                setEnabled("SetAirplaneMode", true));
        findViewById(R.id.btn_airplane_query).setOnClickListener(v ->
                runAction("IsAirplaneMode", new HashMap<String, Object>()));
        findViewById(R.id.btn_airplane_force_on).setOnClickListener(v ->
                setForceOpen("ForceOpenAirplaneMode", true));
        findViewById(R.id.btn_airplane_force_off).setOnClickListener(v ->
                setForceOpen("ForceOpenAirplaneMode", false));

        findViewById(R.id.btn_nav_disable).setOnClickListener(v ->
                setEnabled("SetNavigationBarEnabled", false));
        findViewById(R.id.btn_nav_enable).setOnClickListener(v ->
                setEnabled("SetNavigationBarEnabled", true));
        findViewById(R.id.btn_nav_query).setOnClickListener(v ->
                runAction("IsNavigationBarEnabled", new HashMap<String, Object>()));

        findViewById(R.id.btn_nfc_off).setOnClickListener(v ->
                setEnabled("SetNfcEnabled", false));
        findViewById(R.id.btn_nfc_on).setOnClickListener(v ->
                setEnabled("SetNfcEnabled", true));
        findViewById(R.id.btn_nfc_query).setOnClickListener(v ->
                runAction("IsNfcEnabled", new HashMap<String, Object>()));
        findViewById(R.id.btn_nfc_force_on).setOnClickListener(v ->
                setForceOpen("ForceOpenNfc", true));
        findViewById(R.id.btn_nfc_force_off).setOnClickListener(v ->
                setForceOpen("ForceOpenNfc", false));

        findViewById(R.id.btn_mobile_data_off).setOnClickListener(v ->
                setEnabled("SetMobileDataEnabled", false));
        findViewById(R.id.btn_mobile_data_on).setOnClickListener(v ->
                setEnabled("SetMobileDataEnabled", true));
        findViewById(R.id.btn_mobile_data_query).setOnClickListener(v ->
                runAction("IsMobileDataEnabled", new HashMap<String, Object>()));
        findViewById(R.id.btn_mobile_data_force_close_on).setOnClickListener(v ->
                setForceClose("ForceCloseMobileData", true));
        findViewById(R.id.btn_mobile_data_force_close_off).setOnClickListener(v ->
                setForceClose("ForceCloseMobileData", false));
        findViewById(R.id.btn_mobile_data_force_open_on).setOnClickListener(v ->
                setForceOpen("ForceOpenMobileData", true));
        findViewById(R.id.btn_mobile_data_force_open_off).setOnClickListener(v ->
                setForceOpen("ForceOpenMobileData", false));
        findViewById(R.id.btn_mobile_data_lock_on).setOnClickListener(v ->
                setLocked("SetMobileDataStateLocked", true));
        findViewById(R.id.btn_mobile_data_lock_off).setOnClickListener(v ->
                setLocked("SetMobileDataStateLocked", false));
        findViewById(R.id.btn_mobile_data_lock_query).setOnClickListener(v ->
                runAction("IsMobileDataStateLocked", new HashMap<String, Object>()));
    }

    private void setEnabled(String event, boolean enabled) {
        Map<String, Object> param = new HashMap<>();
        param.put("enabled", enabled);
        runAction(event, param);
    }

    private void setAllowed(String event, boolean allowed) {
        Map<String, Object> param = new HashMap<>();
        param.put("allowed", allowed);
        runAction(event, param);
    }

    private void setForceOpen(String event, boolean forceOpen) {
        Map<String, Object> param = new HashMap<>();
        param.put("forceOpen", forceOpen);
        runAction(event, param);
    }

    private void setForceClose(String event, boolean forceClose) {
        Map<String, Object> param = new HashMap<>();
        param.put("forceClose", forceClose);
        runAction(event, param);
    }

    private void setLocked(String event, boolean locked) {
        Map<String, Object> param = new HashMap<>();
        param.put("locked", locked);
        runAction(event, param);
    }
}
