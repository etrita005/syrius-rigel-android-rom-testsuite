package com.hmdm.testapp;

import android.os.Bundle;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Permission / AppOps control tests (ASR-0043 picture-in-picture,
 * ASR-0044 write settings, ASR-0045 notification listener access,
 * ASR-0046 USB permission grant, ASR-0149 WLAN permission blacklist):
 * per-app AppOps switches, the notification listener binding and the USB
 * grant, all targeted at this app itself, plus the real-framework effect
 * probes (Settings write, wifi state call, PiP enter, USB hasPermission).
 * This activity supports picture-in-picture so the PiP probe can run.
 */
public class PermissionAppOpsTestActivity extends BaseTestActivity {

    private static final String SELF = "com.hmdm.testapp";
    private static final String OWN_LISTENER =
            "com.hmdm.testapp/com.hmdm.testapp.TestNotificationListenerService";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_permission_appops_test);
        bindLogView();

        findViewById(R.id.btn_pip_disable).setOnClickListener(v -> onSetAppOp("SetPictureInPictureDisabled", true));
        findViewById(R.id.btn_pip_enable).setOnClickListener(v -> onSetAppOp("SetPictureInPictureDisabled", false));
        findViewById(R.id.btn_pip_query).setOnClickListener(v -> onQueryAppOp("IsPictureInPictureDisabled"));
        findViewById(R.id.btn_pip_probe).setOnClickListener(v ->
                runAction("TryEnterPip", new HashMap<String, Object>()));

        findViewById(R.id.btn_write_settings_disable).setOnClickListener(v -> onSetAppOp("SetWriteSettingsDisabled", true));
        findViewById(R.id.btn_write_settings_enable).setOnClickListener(v -> onSetAppOp("SetWriteSettingsDisabled", false));
        findViewById(R.id.btn_write_settings_query).setOnClickListener(v -> onQueryAppOp("IsWriteSettingsDisabled"));
        findViewById(R.id.btn_write_settings_probe).setOnClickListener(v ->
                runAction("TryWriteSettings", new HashMap<String, Object>()));

        findViewById(R.id.btn_listener_grant).setOnClickListener(v -> onSetListener(true));
        findViewById(R.id.btn_listener_revoke).setOnClickListener(v -> onSetListener(false));
        findViewById(R.id.btn_listener_query).setOnClickListener(v -> onQueryListener());

        findViewById(R.id.btn_wifi_blacklist_mode_off).setOnClickListener(v -> onWifiMode(0));
        findViewById(R.id.btn_wifi_blacklist_mode_on).setOnClickListener(v -> onWifiMode(2));
        findViewById(R.id.btn_wifi_blacklist_query).setOnClickListener(v ->
                runAction("GetWifiPermissionPolicyMode", new HashMap<String, Object>()));
        findViewById(R.id.btn_wifi_blacklist_set).setOnClickListener(v -> {
            Map<String, Object> param = new HashMap<>();
            param.put("packageNames", Arrays.asList(SELF));
            runAction("SetWifiPermissionBlacklist", param);
        });
        findViewById(R.id.btn_wifi_blacklist_clear).setOnClickListener(v -> {
            Map<String, Object> param = new HashMap<>();
            param.put("packageNames", new java.util.ArrayList<String>());
            runAction("SetWifiPermissionBlacklist", param);
        });
        findViewById(R.id.btn_wifi_blacklist_get).setOnClickListener(v ->
                runAction("GetWifiPermissionBlacklist", new HashMap<String, Object>()));
        findViewById(R.id.btn_wifi_blacklist_apply).setOnClickListener(v ->
                runAction("ApplyWifiPermissionPolicy", new HashMap<String, Object>()));
        findViewById(R.id.btn_wifi_state_probe).setOnClickListener(v ->
                runAction("TryChangeWifiState", new HashMap<String, Object>()));

        findViewById(R.id.btn_usb_list).setOnClickListener(v ->
                runAction("GetUsbDeviceList", new HashMap<String, Object>()));
        findViewById(R.id.btn_usb_grant_self).setOnClickListener(v -> {
            Map<String, Object> param = new HashMap<>();
            param.put("packageName", SELF);
            param.put("deviceName", "first");
            runAction("GrantUsbPermission", param);
        });
        findViewById(R.id.btn_usb_check_self).setOnClickListener(v ->
                runAction("CheckUsbPermission", new HashMap<String, Object>()));
    }

    private void onSetAppOp(String event, boolean disabled) {
        Map<String, Object> param = new HashMap<>();
        param.put("packageName", SELF);
        param.put("disabled", disabled);
        runAction(event, param);
    }

    private void onQueryAppOp(String event) {
        Map<String, Object> param = new HashMap<>();
        param.put("packageName", SELF);
        runAction(event, param);
    }

    private void onSetListener(boolean granted) {
        Map<String, Object> param = new HashMap<>();
        param.put("component", OWN_LISTENER);
        param.put("granted", granted);
        runAction("SetNotificationListenerAccessGranted", param);
    }

    private void onQueryListener() {
        Map<String, Object> param = new HashMap<>();
        param.put("component", OWN_LISTENER);
        runAction("IsNotificationListenerAccessGranted", param);
    }

    private void onWifiMode(int mode) {
        Map<String, Object> param = new HashMap<>();
        param.put("mode", mode);
        runAction("SetWifiPermissionPolicyMode", param);
    }
}
