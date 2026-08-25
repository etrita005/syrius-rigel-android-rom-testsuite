package com.hmdm.testapp;

import android.os.Bundle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Email control tests (ASR-0423 email control, planned as an application
 * blacklist): policy mode / blacklist set &amp; query via the Launcher API.
 */
public class EmailControlTestActivity extends BaseTestActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_email_control_test);
        bindLogView();

        findViewById(R.id.btn_email_enabled).setOnClickListener(v -> onSetMode(2));
        findViewById(R.id.btn_email_disabled).setOnClickListener(v -> onSetMode(0));
        findViewById(R.id.btn_email_mode_query).setOnClickListener(v -> runAction("GetEmailPolicyMode", new HashMap<String, Object>()));

        findViewById(R.id.btn_email_blacklist_set).setOnClickListener(v -> onSetBlacklist());
        findViewById(R.id.btn_email_blacklist_query).setOnClickListener(v -> runAction("GetEmailBlacklist", new HashMap<String, Object>()));
        findViewById(R.id.btn_email_apply).setOnClickListener(v -> runAction("ApplyEmailPolicy", new HashMap<String, Object>()));
        findViewById(R.id.btn_email_controlled_query).setOnClickListener(v -> runAction("IsEmailControlled", new HashMap<String, Object>()));
    }

    private void onSetMode(int mode) {
        Map<String, Object> param = new HashMap<>();
        param.put("mode", mode);
        runAction("SetEmailPolicyMode", param);
    }

    private void onSetBlacklist() {
        Map<String, Object> param = new HashMap<>();
        param.put("packageNames", new ArrayList<String>());
        runAction("SetEmailBlacklist", param);
    }
}
