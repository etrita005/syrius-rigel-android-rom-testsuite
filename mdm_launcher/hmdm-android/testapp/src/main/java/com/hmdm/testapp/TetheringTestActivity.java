package com.hmdm.testapp;

import android.os.Bundle;

import java.util.HashMap;
import java.util.Map;

/**
 * Hotspot / network sharing control tests: Wi-Fi personal hotspot
 * (ASR-0167/0169), USB tethering (ASR-0399/0400/0401), WLAN sharing
 * (ASR-0398/0402), Bluetooth sharing (ASR-0403/0404/0405) and the
 * DISALLOW_CONFIG_TETHERING user restriction (ASR-0400/0405, ASR-0168 engine).
 */
public class TetheringTestActivity extends BaseTestActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tethering_test);
        bindLogView();

        findViewById(R.id.btn_hotspot_off).setOnClickListener(v ->
                setEnabled("SetHotspotEnabled", false));
        findViewById(R.id.btn_hotspot_on).setOnClickListener(v ->
                setEnabled("SetHotspotEnabled", true));
        findViewById(R.id.btn_hotspot_query).setOnClickListener(v ->
                runAction("IsHotspotEnabled", new HashMap<String, Object>()));

        findViewById(R.id.btn_usb_off).setOnClickListener(v ->
                setEnabled("SetUsbTetheringEnabled", false));
        findViewById(R.id.btn_usb_on).setOnClickListener(v ->
                setEnabled("SetUsbTetheringEnabled", true));
        findViewById(R.id.btn_usb_query).setOnClickListener(v ->
                runAction("IsUsbTetheringEnabled", new HashMap<String, Object>()));

        findViewById(R.id.btn_bt_off).setOnClickListener(v ->
                setEnabled("SetBluetoothTetheringEnabled", false));
        findViewById(R.id.btn_bt_on).setOnClickListener(v ->
                setEnabled("SetBluetoothTetheringEnabled", true));
        findViewById(R.id.btn_bt_query).setOnClickListener(v ->
                runAction("IsBluetoothTetheringEnabled", new HashMap<String, Object>()));

        findViewById(R.id.btn_master_disable).setOnClickListener(v ->
                setDisabled("SetTetheringDisabled", true));
        findViewById(R.id.btn_master_enable).setOnClickListener(v ->
                setDisabled("SetTetheringDisabled", false));
        findViewById(R.id.btn_master_query).setOnClickListener(v ->
                runAction("IsTetheringDisabled", new HashMap<String, Object>()));

        findViewById(R.id.btn_usb_forbid_on).setOnClickListener(v ->
                setForbidden("SetUsbTetheringForbidden", true));
        findViewById(R.id.btn_usb_forbid_off).setOnClickListener(v ->
                setForbidden("SetUsbTetheringForbidden", false));
        findViewById(R.id.btn_usb_forbid_query).setOnClickListener(v ->
                runAction("IsUsbTetheringForbidden", new HashMap<String, Object>()));

        findViewById(R.id.btn_wifi_forbid_on).setOnClickListener(v ->
                setForbidden("SetWifiTetheringForbidden", true));
        findViewById(R.id.btn_wifi_forbid_off).setOnClickListener(v ->
                setForbidden("SetWifiTetheringForbidden", false));
        findViewById(R.id.btn_wifi_forbid_query).setOnClickListener(v ->
                runAction("IsWifiTetheringForbidden", new HashMap<String, Object>()));

        findViewById(R.id.btn_bt_forbid_on).setOnClickListener(v ->
                setForbidden("SetBluetoothTetheringForbidden", true));
        findViewById(R.id.btn_bt_forbid_off).setOnClickListener(v ->
                setForbidden("SetBluetoothTetheringForbidden", false));
        findViewById(R.id.btn_bt_forbid_query).setOnClickListener(v ->
                runAction("IsBluetoothTetheringForbidden", new HashMap<String, Object>()));

        findViewById(R.id.btn_user_config_forbid).setOnClickListener(v ->
                setDisabled("SetUserConfigTetheringDisabled", true));
        findViewById(R.id.btn_user_config_allow).setOnClickListener(v ->
                setDisabled("SetUserConfigTetheringDisabled", false));
        findViewById(R.id.btn_user_config_query).setOnClickListener(v ->
                runAction("IsUserConfigTetheringDisabled", new HashMap<String, Object>()));
    }

    private void setEnabled(String event, boolean enabled) {
        Map<String, Object> param = new HashMap<>();
        param.put("enabled", enabled);
        runAction(event, param);
    }

    private void setDisabled(String event, boolean disabled) {
        Map<String, Object> param = new HashMap<>();
        param.put("disabled", disabled);
        runAction(event, param);
    }

    private void setForbidden(String event, boolean forbidden) {
        Map<String, Object> param = new HashMap<>();
        param.put("forbidden", forbidden);
        runAction(event, param);
    }
}
