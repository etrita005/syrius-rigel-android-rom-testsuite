package com.hmdm.testapp;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.EditText;

import java.util.HashMap;
import java.util.Map;

/**
 * Stats query tests: per-app traffic (ASR-0139), per-app battery drain
 * (ASR-0372), per-app foreground runtime (ASR-0021), running app processes
 * (ASR-0022) and crash/ANR history (ASR-0023).
 */
public class StatsQueryTestActivity extends BaseTestActivity {

    private EditText etPackage;
    private EditText etDays;
    private EditText etNetwork;
    private EditText etLimit;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stats_query_test);
        bindLogView();

        etPackage = findViewById(R.id.et_package);
        etDays = findViewById(R.id.et_days);
        etNetwork = findViewById(R.id.et_network);
        etLimit = findViewById(R.id.et_limit);

        findViewById(R.id.btn_query_traffic).setOnClickListener(v -> runAction("QueryAppTraffic", baseParams()));
        findViewById(R.id.btn_query_battery).setOnClickListener(v -> runAction("QueryAppBattery", baseParams()));
        findViewById(R.id.btn_query_runtime).setOnClickListener(v -> runAction("QueryAppRuntime", baseParams()));
        findViewById(R.id.btn_query_running).setOnClickListener(v ->
                runAction("QueryRunningApps", new HashMap<String, Object>()));
        findViewById(R.id.btn_query_crash).setOnClickListener(v -> runAction("QueryAppCrashInfo", baseParams()));
    }

    private Map<String, Object> baseParams() {
        Map<String, Object> param = new HashMap<>();
        String pkg = etPackage.getText().toString().trim();
        if (!TextUtils.isEmpty(pkg)) {
            param.put("packageName", pkg);
        }
        String days = etDays.getText().toString().trim();
        if (!TextUtils.isEmpty(days)) {
            param.put("days", days);
        }
        String network = etNetwork.getText().toString().trim();
        if (!TextUtils.isEmpty(network)) {
            param.put("network", network);
        }
        String limit = etLimit.getText().toString().trim();
        if (!TextUtils.isEmpty(limit)) {
            param.put("limit", limit);
        }
        return param;
    }
}
