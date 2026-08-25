package com.hmdm.testapp;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Notification control tests: per-package control (ASR-0053),
 * whitelist/blacklist + policy mode (ASR-0054/0055), lockscreen notifications
 * (ASR-0057), status bar notifications (ASR-0059) and the app's own test
 * notification.
 */
public class NotificationTestActivity extends BaseTestActivity {

    private static final int REQUEST_POST_NOTIFICATIONS = 1;

    private EditText etPackage;
    private EditText etWhitelist;
    private EditText etBlacklist;
    private EditText etMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notification_test);
        bindLogView();

        etPackage = findViewById(R.id.et_package);
        etWhitelist = findViewById(R.id.et_whitelist);
        etBlacklist = findViewById(R.id.et_blacklist);
        etMode = findViewById(R.id.et_mode);

        findViewById(R.id.btn_enable).setOnClickListener(v -> onSetEnabled(true));
        findViewById(R.id.btn_disable).setOnClickListener(v -> onSetEnabled(false));
        findViewById(R.id.btn_query).setOnClickListener(v -> onQuery());
        findViewById(R.id.btn_set_whitelist).setOnClickListener(v -> onSetList(true));
        findViewById(R.id.btn_get_whitelist).setOnClickListener(v -> runAction("GetNotificationsWhitelist", new HashMap<String, Object>()));
        findViewById(R.id.btn_set_blacklist).setOnClickListener(v -> onSetList(false));
        findViewById(R.id.btn_get_blacklist).setOnClickListener(v -> runAction("GetNotificationsBlacklist", new HashMap<String, Object>()));
        findViewById(R.id.btn_set_mode).setOnClickListener(v -> onSetMode());
        findViewById(R.id.btn_get_mode).setOnClickListener(v -> runAction("GetNotificationsPolicyMode", new HashMap<String, Object>()));
        findViewById(R.id.btn_apply_policy).setOnClickListener(v -> runAction("ApplyNotificationsPolicy", new HashMap<String, Object>()));
        findViewById(R.id.btn_lockscreen_off).setOnClickListener(v -> onSetLockscreen(true));
        findViewById(R.id.btn_lockscreen_on).setOnClickListener(v -> onSetLockscreen(false));
        findViewById(R.id.btn_lockscreen_query).setOnClickListener(v -> runAction("IsLockscreenNotificationsDisabled", new HashMap<String, Object>()));
        findViewById(R.id.btn_statusbar_off).setOnClickListener(v -> onSetStatusBar(true));
        findViewById(R.id.btn_statusbar_on).setOnClickListener(v -> onSetStatusBar(false));
        findViewById(R.id.btn_statusbar_query).setOnClickListener(v -> runAction("IsStatusBarNotificationsDisabled", new HashMap<String, Object>()));
        findViewById(R.id.btn_request_permission).setOnClickListener(v -> onRequestPermission());
        findViewById(R.id.btn_send_notification).setOnClickListener(v -> runAction("SendTestNotification", new HashMap<String, Object>()));
    }

    private void onSetEnabled(boolean enabled) {
        String pkg = etPackage.getText().toString().trim();
        if (TextUtils.isEmpty(pkg)) {
            toast("Please enter a package name");
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            appendLog("Note: per-package control requires API 28+, current API " + Build.VERSION.SDK_INT);
        }
        Map<String, Object> param = new HashMap<>();
        param.put("packageName", pkg);
        param.put("enabled", enabled);
        runAction("SetNotificationsEnabledForPackage", param);
    }

    private void onQuery() {
        String pkg = etPackage.getText().toString().trim();
        if (TextUtils.isEmpty(pkg)) {
            toast("Please enter a package name");
            return;
        }
        Map<String, Object> param = new HashMap<>();
        param.put("packageName", pkg);
        runAction("IsNotificationsEnabledForPackage", param);
    }

    private void onSetList(boolean whitelist) {
        String text = (whitelist ? etWhitelist : etBlacklist).getText().toString().trim();
        List<String> list = parseList(text);
        Map<String, Object> param = new HashMap<>();
        param.put("packageNames", new ArrayList<String>(list));
        runAction(whitelist ? "SetNotificationsWhitelist" : "SetNotificationsBlacklist", param);
    }

    private void onSetMode() {
        int mode = parseMode(etMode.getText().toString());
        if (mode < 0) {
            toast("Please enter a valid policy mode 0/1/2");
            return;
        }
        Map<String, Object> param = new HashMap<>();
        param.put("mode", mode);
        runAction("SetNotificationsPolicyMode", param);
    }

    private void onSetLockscreen(boolean disabled) {
        Map<String, Object> param = new HashMap<>();
        param.put("disabled", disabled);
        runAction("SetLockscreenNotificationsDisabled", param);
    }

    private void onSetStatusBar(boolean disabled) {
        Map<String, Object> param = new HashMap<>();
        param.put("disabled", disabled);
        runAction("SetStatusBarNotificationsDisabled", param);
    }

    private void onRequestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATIONS);
            appendLog("POST_NOTIFICATIONS permission requested");
        } else {
            appendLog("POST_NOTIFICATIONS already granted (not required below API 33)");
        }
    }

    private List<String> parseList(String text) {
        List<String> list = new ArrayList<>();
        if (TextUtils.isEmpty(text)) {
            return list;
        }
        String[] parts = text.split("[,，]");
        for (String part : parts) {
            String pkg = part.trim();
            if (!pkg.isEmpty()) {
                list.add(pkg);
            }
        }
        return list;
    }

    private int parseMode(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return -1;
        }
    }
}
