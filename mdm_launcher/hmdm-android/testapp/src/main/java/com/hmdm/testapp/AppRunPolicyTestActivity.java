package com.hmdm.testapp;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * App run control tests: blocked-running whitelist (ASR-0017), battery
 * whitelist (ASR-0016/0030) and hide/show of specific applications
 * (ASR-0099/0104/0131).
 */
public class AppRunPolicyTestActivity extends BaseTestActivity {

    private EditText etBlockPkg;
    private EditText etBatteryPkg;
    private EditText etHiddenPkg;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_run_policy_test);
        bindLogView();

        etBlockPkg = findViewById(R.id.et_block_pkg);
        etBatteryPkg = findViewById(R.id.et_battery_pkg);
        etHiddenPkg = findViewById(R.id.et_hidden_pkg);

        findViewById(R.id.btn_block_add).setOnClickListener(v ->
                modifyWhitelist("SetBlockedRunningWhitelist", etBlockPkg, true));
        findViewById(R.id.btn_block_remove).setOnClickListener(v ->
                modifyWhitelist("SetBlockedRunningWhitelist", etBlockPkg, false));
        findViewById(R.id.btn_block_get).setOnClickListener(v ->
                runAction("GetBlockedRunningWhitelist", new HashMap<String, Object>()));
        findViewById(R.id.btn_block_query).setOnClickListener(v -> {
            String pkg = etBlockPkg.getText().toString().trim();
            if (TextUtils.isEmpty(pkg)) {
                appendLog("IsPackageSuspended: missing parameter: packageName");
                return;
            }
            Map<String, Object> param = new HashMap<>();
            param.put("packageName", pkg);
            runAction("IsPackageSuspended", param);
        });

        findViewById(R.id.btn_battery_add).setOnClickListener(v ->
                modifyWhitelist("SetIgnoreBatteryOptimizationWhitelist", etBatteryPkg, true));
        findViewById(R.id.btn_battery_remove).setOnClickListener(v ->
                modifyWhitelist("SetIgnoreBatteryOptimizationWhitelist", etBatteryPkg, false));
        findViewById(R.id.btn_battery_get).setOnClickListener(v ->
                runAction("GetIgnoreBatteryOptimizationWhitelist", new HashMap<String, Object>()));
        findViewById(R.id.btn_battery_query).setOnClickListener(v -> {
            String pkg = etBatteryPkg.getText().toString().trim();
            if (TextUtils.isEmpty(pkg)) {
                appendLog("IsIgnoringBatteryOptimization: missing parameter: packageName");
                return;
            }
            Map<String, Object> param = new HashMap<>();
            param.put("packageName", pkg);
            runAction("IsIgnoringBatteryOptimization", param);
        });

        findViewById(R.id.btn_hide_browser).setOnClickListener(v ->
                setHidden("com.ume.browser", true));
        findViewById(R.id.btn_show_browser).setOnClickListener(v ->
                setHidden("com.ume.browser", false));
        findViewById(R.id.btn_hide_settings).setOnClickListener(v ->
                setHidden("com.android.settings", true));
        findViewById(R.id.btn_show_settings).setOnClickListener(v ->
                setHidden("com.android.settings", false));
        findViewById(R.id.btn_hide_vending).setOnClickListener(v ->
                setHidden("com.android.vending", true));
        findViewById(R.id.btn_show_vending).setOnClickListener(v ->
                setHidden("com.android.vending", false));
        findViewById(R.id.btn_hide_custom).setOnClickListener(v ->
                setHidden(etHiddenPkg.getText().toString().trim(), true));
        findViewById(R.id.btn_show_custom).setOnClickListener(v ->
                setHidden(etHiddenPkg.getText().toString().trim(), false));
        findViewById(R.id.btn_hidden_query).setOnClickListener(v -> {
            String pkg = etHiddenPkg.getText().toString().trim();
            if (TextUtils.isEmpty(pkg)) {
                appendLog("IsApplicationHidden: missing parameter: packageName");
                return;
            }
            Map<String, Object> param = new HashMap<>();
            param.put("packageName", pkg);
            runAction("IsApplicationHidden", param);
        });
    }

    /**
     * Fetch the current whitelist, add/remove the entered package and write
     * the new list back through the set command.
     */
    private void modifyWhitelist(String event, EditText input, boolean add) {
        String pkg = input.getText().toString().trim();
        if (TextUtils.isEmpty(pkg)) {
            appendLog(event + ": missing parameter: packageName");
            return;
        }
        Map<Object, Object> current = apiCall(event.equals("SetBlockedRunningWhitelist")
                ? "GetBlockedRunningWhitelist" : "GetIgnoreBatteryOptimizationWhitelist",
                new HashMap<String, Object>());
        List<String> list = new ArrayList<>();
        Object result = current.get("RESULT");
        if (result instanceof Map) {
            Object rawList = ((Map<?, ?>) result).get("list");
            if (rawList instanceof List) {
                for (Object entry : (List<?>) rawList) {
                    if (entry instanceof Map && ((Map<?, ?>) entry).get("packageName") != null) {
                        list.add(String.valueOf(((Map<?, ?>) entry).get("packageName")));
                    }
                }
            }
        }
        if (add) {
            if (!list.contains(pkg)) {
                list.add(pkg);
            }
        } else {
            list.remove(pkg);
        }
        Map<String, Object> param = new HashMap<>();
        param.put("packageNames", list);
        runAction(event, param);
    }

    private void setHidden(String packageName, boolean hidden) {
        if (TextUtils.isEmpty(packageName)) {
            appendLog("SetApplicationHidden: missing parameter: packageName");
            return;
        }
        Map<String, Object> param = new HashMap<>();
        param.put("packageName", packageName);
        param.put("hidden", hidden);
        runAction("SetApplicationHidden", param);
        Map<String, Object> query = new HashMap<>();
        query.put("packageName", packageName);
        runAction("IsApplicationHidden", query);
    }
}
