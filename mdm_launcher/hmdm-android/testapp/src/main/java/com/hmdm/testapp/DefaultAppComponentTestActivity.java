package com.hmdm.testapp;

import android.os.Bundle;

import java.util.HashMap;
import java.util.Map;

/**
 * Component / default-app tests (ASR-0019 disable/enable application
 * components, ASR-0087 default SMS, ASR-0089 default dialer, ASR-0094 default
 * assistant): component and whole-app enable/disable targeted at this app
 * itself, real start probes, and the default-app role set/query targeted at
 * the ROM's SMS/dialer apps.
 */
public class DefaultAppComponentTestActivity extends BaseTestActivity {

    private static final String SELF = "com.hmdm.testapp";
    private static final String OWN_ACTIVITY =
            "com.hmdm.testapp/com.hmdm.testapp.StatsQueryTestActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_default_app_component_test);
        bindLogView();

        findViewById(R.id.btn_comp_disable_activity).setOnClickListener(v -> onSetComponent(OWN_ACTIVITY, false));
        findViewById(R.id.btn_comp_enable_activity).setOnClickListener(v -> onSetComponent(OWN_ACTIVITY, true));
        findViewById(R.id.btn_comp_query_activity).setOnClickListener(v -> onQueryComponent(OWN_ACTIVITY));
        findViewById(R.id.btn_comp_probe_start).setOnClickListener(v -> runAction("TryStartComponent", param("component", OWN_ACTIVITY)));

        findViewById(R.id.btn_comp_disable_app).setOnClickListener(v -> onSetComponent(null, false));
        findViewById(R.id.btn_comp_enable_app).setOnClickListener(v -> onSetComponent(null, true));
        findViewById(R.id.btn_comp_query_app).setOnClickListener(v -> onQueryComponent(null));

        findViewById(R.id.btn_sms_query).setOnClickListener(v -> runAction("GetDefaultSmsApp", new HashMap<String, Object>()));
        findViewById(R.id.btn_sms_set_mms).setOnClickListener(v -> runAction("SetDefaultSmsApp", param("packageName", "com.android.mms")));
        findViewById(R.id.btn_sms_set_invalid).setOnClickListener(v -> runAction("SetDefaultSmsApp", param("packageName", SELF)));

        findViewById(R.id.btn_dialer_query).setOnClickListener(v -> runAction("GetDefaultDialerApp", new HashMap<String, Object>()));
        findViewById(R.id.btn_dialer_set).setOnClickListener(v -> runAction("SetDefaultDialerApp", param("packageName", "com.android.dialer")));

        findViewById(R.id.btn_assistant_query).setOnClickListener(v -> runAction("GetDefaultAssistant", new HashMap<String, Object>()));
        findViewById(R.id.btn_assistant_set).setOnClickListener(v -> runAction("SetDefaultAssistant", param("packageName", "com.mediatek.voicecommand")));
        findViewById(R.id.btn_assistant_setting).setOnClickListener(v -> runAction("GetAssistantSetting", new HashMap<String, Object>()));
    }

    private void onSetComponent(String component, boolean enabled) {
        Map<String, Object> param = new HashMap<>();
        param.put("packageName", SELF);
        if (component != null) {
            param.put("component", component);
        }
        param.put("enabled", enabled);
        runAction("SetComponentEnabled", param);
    }

    private void onQueryComponent(String component) {
        Map<String, Object> param = new HashMap<>();
        param.put("packageName", SELF);
        if (component != null) {
            param.put("component", component);
        }
        runAction("IsComponentEnabled", param);
    }

    private static Map<String, Object> param(String key, String value) {
        Map<String, Object> param = new HashMap<>();
        param.put(key, value);
        return param;
    }
}
