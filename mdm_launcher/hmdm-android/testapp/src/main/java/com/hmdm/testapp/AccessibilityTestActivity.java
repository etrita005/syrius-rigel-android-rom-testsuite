package com.hmdm.testapp;

import android.os.Bundle;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Accessibility service control tests (ASR-0074 activate/deactivate without
 * user interaction, ASR-0075 usable services whitelist/blacklist): policy
 * set/query and the testapp's own accessibility service
 * (TestAccessibilityService) as the activation target.
 */
public class AccessibilityTestActivity extends BaseTestActivity {

    private static final String OWN_SERVICE =
            "com.hmdm.testapp/com.hmdm.testapp.TestAccessibilityService";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_accessibility_test);
        bindLogView();

        findViewById(R.id.btn_a11y_service_enable).setOnClickListener(v -> {
            Map<String, Object> param = new HashMap<>();
            param.put("component", OWN_SERVICE);
            param.put("enabled", true);
            runAction("SetAccessibilityServiceEnabled", param);
        });
        findViewById(R.id.btn_a11y_service_disable).setOnClickListener(v -> {
            Map<String, Object> param = new HashMap<>();
            param.put("component", OWN_SERVICE);
            param.put("enabled", false);
            runAction("SetAccessibilityServiceEnabled", param);
        });
        findViewById(R.id.btn_a11y_service_query).setOnClickListener(v -> {
            Map<String, Object> param = new HashMap<>();
            param.put("component", OWN_SERVICE);
            runAction("IsAccessibilityServiceEnabled", param);
        });
        findViewById(R.id.btn_a11y_state).setOnClickListener(v ->
                runAction("GetAccessibilityServiceState", new HashMap<String, Object>()));

        findViewById(R.id.btn_a11y_policy_off).setOnClickListener(v -> onSetMode(0));
        findViewById(R.id.btn_a11y_policy_whitelist).setOnClickListener(v -> onSetMode(1));
        findViewById(R.id.btn_a11y_policy_blacklist).setOnClickListener(v -> onSetMode(2));
        findViewById(R.id.btn_a11y_policy_query).setOnClickListener(v ->
                runAction("GetAccessibilityServicePolicyMode", new HashMap<String, Object>()));
        findViewById(R.id.btn_a11y_policy_apply).setOnClickListener(v ->
                runAction("ApplyAccessibilityServicePolicy", new HashMap<String, Object>()));

        findViewById(R.id.btn_a11y_whitelist_set).setOnClickListener(v -> {
            Map<String, Object> param = new HashMap<>();
            param.put("components", Arrays.asList(OWN_SERVICE));
            runAction("SetAccessibilityServiceWhitelist", param);
        });
        findViewById(R.id.btn_a11y_whitelist_clear).setOnClickListener(v -> {
            Map<String, Object> param = new HashMap<>();
            param.put("components", new java.util.ArrayList<String>());
            runAction("SetAccessibilityServiceWhitelist", param);
        });
        findViewById(R.id.btn_a11y_whitelist_query).setOnClickListener(v ->
                runAction("GetAccessibilityServiceWhitelist", new HashMap<String, Object>()));
        findViewById(R.id.btn_a11y_blacklist_set).setOnClickListener(v -> {
            Map<String, Object> param = new HashMap<>();
            param.put("components", Arrays.asList(OWN_SERVICE));
            runAction("SetAccessibilityServiceBlacklist", param);
        });
        findViewById(R.id.btn_a11y_blacklist_clear).setOnClickListener(v -> {
            Map<String, Object> param = new HashMap<>();
            param.put("components", new java.util.ArrayList<String>());
            runAction("SetAccessibilityServiceBlacklist", param);
        });
        findViewById(R.id.btn_a11y_blacklist_query).setOnClickListener(v ->
                runAction("GetAccessibilityServiceBlacklist", new HashMap<String, Object>()));
    }

    private void onSetMode(int mode) {
        Map<String, Object> param = new HashMap<>();
        param.put("mode", mode);
        runAction("SetAccessibilityServicePolicyMode", param);
    }
}
