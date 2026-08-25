package com.hmdm.testapp;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.os.Bundle;

import java.util.HashMap;
import java.util.Map;

/**
 * Device management tests (ASR-0080 non-interactive activate/deactivate of a
 * device admin component, ASR-0081 force activation, ASR-0083 device owner
 * set/delete, ASR-0085 profile owner set/delete): policy set/query via the
 * Launcher API, plus an app-side end-to-end probe of its own admin state.
 */
public class DeviceAdminTestActivity extends BaseTestActivity {

    private static final String OWN_COMPONENT =
            "com.hmdm.testapp/com.hmdm.testapp.TestAdminReceiver";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_admin_test);
        bindLogView();

        findViewById(R.id.btn_admin_activate).setOnClickListener(
                v -> onAdminAction("SetDeviceAdminActive", true));
        findViewById(R.id.btn_admin_deactivate).setOnClickListener(
                v -> onAdminAction("SetDeviceAdminActive", false));
        findViewById(R.id.btn_admin_force).setOnClickListener(
                v -> onAdminAction("ForceSetDeviceAdminActive", true));
        findViewById(R.id.btn_admin_query).setOnClickListener(v -> runAction(
                "IsDeviceAdminActive", param("component", OWN_COMPONENT)));
        findViewById(R.id.btn_admin_query_local).setOnClickListener(v -> queryOwnAdminLocal());

        findViewById(R.id.btn_do_query).setOnClickListener(
                v -> runAction("IsDeviceOwner", new HashMap<String, Object>()));
        findViewById(R.id.btn_do_delete).setOnClickListener(
                v -> runAction("DeleteDeviceOwner", new HashMap<String, Object>()));
        findViewById(R.id.btn_do_set).setOnClickListener(
                v -> runAction("SetDeviceOwner", param("packageName", "com.hmdm.launcher")));

        findViewById(R.id.btn_po_query).setOnClickListener(
                v -> runAction("IsProfileOwner", new HashMap<String, Object>()));
        findViewById(R.id.btn_po_set).setOnClickListener(
                v -> runAction("SetProfileOwner", param("component",
                        "com.hmdm.launcher/com.hmdm.launcher.AdminReceiver")));
        findViewById(R.id.btn_po_delete).setOnClickListener(
                v -> runAction("DeleteProfileOwner", new HashMap<String, Object>()));
    }

    private void onAdminAction(String event, boolean active) {
        Map<String, Object> param = param("component", OWN_COMPONENT);
        param.put("active", active);
        runAction(event, param);
    }

    private void queryOwnAdminLocal() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        ComponentName component = new ComponentName(this, TestAdminReceiver.class);
        boolean active = dpm != null && dpm.isAdminActive(component);
        appendLog("Local dpm.isAdminActive(" + component.flattenToShortString()
                + ") = " + active);
    }

    private static Map<String, Object> param(String key, String value) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return map;
    }
}
