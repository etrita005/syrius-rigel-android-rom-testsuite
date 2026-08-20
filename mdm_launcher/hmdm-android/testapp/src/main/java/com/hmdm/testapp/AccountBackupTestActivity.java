package com.hmdm.testapp;

import android.os.Bundle;

import java.util.HashMap;
import java.util.Map;

/**
 * Account &amp; backup control tests (ASR-0124 system backup, ASR-0129 Google
 * accounts, ASR-0132 Google backup &amp; restore, ASR-0128 auto sync master
 * switch, ASR-0130 Google account auto sync): policy set/query via the
 * Launcher API.
 */
public class AccountBackupTestActivity extends BaseTestActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account_backup_test);
        bindLogView();

        findViewById(R.id.btn_backup_disabled).setOnClickListener(v -> onSetDisabled("SetBackupDisabled", true));
        findViewById(R.id.btn_backup_enabled).setOnClickListener(v -> onSetDisabled("SetBackupDisabled", false));
        findViewById(R.id.btn_backup_query).setOnClickListener(v -> runAction("IsBackupDisabled", new HashMap<String, Object>()));

        findViewById(R.id.btn_accounts_disabled).setOnClickListener(v -> onSetDisabled("SetGoogleAccountsDisabled", true));
        findViewById(R.id.btn_accounts_enabled).setOnClickListener(v -> onSetDisabled("SetGoogleAccountsDisabled", false));
        findViewById(R.id.btn_accounts_query).setOnClickListener(v -> runAction("IsGoogleAccountsDisabled", new HashMap<String, Object>()));

        findViewById(R.id.btn_backup_restore_disabled).setOnClickListener(v -> onSetDisabled("SetBackupRestoreDisabled", true));
        findViewById(R.id.btn_backup_restore_enabled).setOnClickListener(v -> onSetDisabled("SetBackupRestoreDisabled", false));
        findViewById(R.id.btn_backup_restore_query).setOnClickListener(v -> runAction("IsBackupRestoreDisabled", new HashMap<String, Object>()));

        findViewById(R.id.btn_auto_sync_enabled).setOnClickListener(v -> onSetEnabled("SetAutoSync", true));
        findViewById(R.id.btn_auto_sync_disabled).setOnClickListener(v -> onSetEnabled("SetAutoSync", false));
        findViewById(R.id.btn_auto_sync_query).setOnClickListener(v -> runAction("IsAutoSync", new HashMap<String, Object>()));

        findViewById(R.id.btn_google_account_sync_enabled).setOnClickListener(v -> onSetEnabled("SetGoogleAccountAutoSync", true));
        findViewById(R.id.btn_google_account_sync_disabled).setOnClickListener(v -> onSetEnabled("SetGoogleAccountAutoSync", false));
        findViewById(R.id.btn_google_account_sync_query).setOnClickListener(v -> runAction("IsGoogleAccountAutoSync", new HashMap<String, Object>()));
    }

    private void onSetDisabled(String event, boolean disabled) {
        Map<String, Object> param = new HashMap<>();
        param.put("disabled", disabled);
        runAction(event, param);
    }

    private void onSetEnabled(String event, boolean enabled) {
        Map<String, Object> param = new HashMap<>();
        param.put("enabled", enabled);
        runAction(event, param);
    }
}
