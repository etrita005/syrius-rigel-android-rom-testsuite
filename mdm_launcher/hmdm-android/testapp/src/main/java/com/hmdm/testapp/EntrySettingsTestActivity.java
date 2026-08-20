package com.hmdm.testapp;

import android.os.Bundle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Entry / settings locking tests (2026-08-07 batch): app permission pages
 * (ASR-0048/0049), notification management UI and user modification locks
 * (ASR-0058/0062/0063/0064/0065), app management page and visibility policy
 * (ASR-0066/0067/0068), enabled accessibility list and UI entry
 * (ASR-0076/0077), default-app setting locks (ASR-0086/0088/0093/0100), USB
 * settings / USB debugging locks (ASR-0199/0203), APN settings (ASR-0308),
 * airplane mode change lock (ASR-0322), language switching locks
 * (ASR-0334/0335) and speaker disable (ASR-0370).
 *
 * <p>The actions run on a worker thread: the synchronous AIDL round trips
 * can take a moment.
 */
public class EntrySettingsTestActivity extends BaseTestActivity {

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_entry_settings_test);
        bindLogView();

        findViewById(R.id.btn_set_permission_page).setOnClickListener(
                v -> runAsync("SetAppPermissionPageDisabled", MapOf.of("disabled", true), false));
        findViewById(R.id.btn_set_spec_permission_page).setOnClickListener(
                v -> runAsync("SetSpecifiedAppPermissionPageDisabled",
                        MapOf.of("packageName", "com.android.settings", "disabled", true), false));
        findViewById(R.id.btn_set_app_notification_ui).setOnClickListener(
                v -> runAsync("SetAppNotificationUiDisabled", MapOf.of("disabled", true), false));
        findViewById(R.id.btn_set_whitelist_locked).setOnClickListener(
                v -> runAsync("SetNotificationWhitelistLocked", MapOf.of("locked", true), false));
        findViewById(R.id.btn_set_user_notification_settings).setOnClickListener(
                v -> runAsync("SetUserNotificationSettingsDisabled", MapOf.of("disabled", true), false));
        findViewById(R.id.btn_set_statusbar_setting_locked).setOnClickListener(
                v -> runAsync("SetStatusBarNotificationSettingLocked", MapOf.of("locked", true), false));
        findViewById(R.id.btn_set_lockscreen_setting_locked).setOnClickListener(
                v -> runAsync("SetLockscreenNotificationSettingLocked", MapOf.of("locked", true), false));
        findViewById(R.id.btn_set_app_management_page).setOnClickListener(
                v -> runAsync("SetAppManagementPageDisabled", MapOf.of("disabled", true), false));
        findViewById(R.id.btn_set_app_mgmt_mode).setOnClickListener(
                v -> runAsync("SetAppManagementPolicyMode", MapOf.of("mode", 1), false));
        findViewById(R.id.btn_set_app_mgmt_lists).setOnClickListener(
                v -> runAsync("SetAppManagementWhitelist",
                        MapOf.of("packageNames", new ArrayList<String>(Arrays.asList("com.hmdm.testapp"))), false));
        findViewById(R.id.btn_get_enabled_accessibility).setOnClickListener(
                v -> runAsync("GetEnabledAccessibilityServices", new HashMap<String, Object>(), false));
        findViewById(R.id.btn_set_accessibility_ui).setOnClickListener(
                v -> runAsync("SetAccessibilityUiEntryDisabled", MapOf.of("disabled", true), false));
        findViewById(R.id.btn_set_sms_locked).setOnClickListener(
                v -> runAsync("SetSmsAppSettingLocked", MapOf.of("locked", true), false));
        findViewById(R.id.btn_set_dialer_locked).setOnClickListener(
                v -> runAsync("SetDialerAppSettingLocked", MapOf.of("locked", true), false));
        findViewById(R.id.btn_set_assistant_locked).setOnClickListener(
                v -> runAsync("SetAssistantModificationLocked", MapOf.of("locked", true), false));
        findViewById(R.id.btn_set_browser_locked).setOnClickListener(
                v -> runAsync("SetDefaultBrowserModificationLocked", MapOf.of("locked", true), false));
        findViewById(R.id.btn_set_usb_settings_locked).setOnClickListener(
                v -> runAsync("SetUsbSettingsLocked", MapOf.of("locked", true), false));
        findViewById(R.id.btn_set_usb_debugging_locked).setOnClickListener(
                v -> runAsync("SetUsbDebuggingSettingLocked", MapOf.of("locked", true), false));
        findViewById(R.id.btn_set_apn_enabled).setOnClickListener(
                v -> runAsync("SetApnSettingsEnabled", MapOf.of("enabled", false), false));
        findViewById(R.id.btn_set_airplane_locked).setOnClickListener(
                v -> runAsync("SetUserAirplaneModeChangeLocked", MapOf.of("locked", true), false));
        findViewById(R.id.btn_set_language_disabled).setOnClickListener(
                v -> runAsync("SetLanguageSwitchingDisabled", MapOf.of("disabled", true), false));
        findViewById(R.id.btn_set_user_language_disabled).setOnClickListener(
                v -> runAsync("SetUserLanguageModificationDisabled", MapOf.of("disabled", true), false));
        findViewById(R.id.btn_set_speaker_disabled).setOnClickListener(
                v -> runAsync("SetSpeakerDisabled", MapOf.of("disabled", true), false));
        findViewById(R.id.btn_probe_entries).setOnClickListener(
                v -> runAsync("GetEntriesLocal", new HashMap<String, Object>(), true));
    }

    /** Run a Launcher command (or local probe) off the main thread. */
    private void runAsync(final String event, final Map<String, Object> param, final boolean local) {
        worker.execute(() -> {
            Map<Object, Object> result;
            if (local) {
                result = new HashMap<>();
                result.put(TestActions.RESULT_KEY, TestActions.execute(this, event, param).get(TestActions.RESULT_KEY));
            } else {
                result = TestActions.execute(this, event, param);
            }
            final String text = event + " RESULT: " + result.get(TestActions.RESULT_KEY);
            runOnUiThread(() -> appendLog(text));
        });
    }

    /** Minimal literal map builder (avoids Java 9 Map.of on this toolchain). */
    private static final class MapOf {
        static Map<String, Object> of(Object... kv) {
            Map<String, Object> map = new HashMap<>();
            for (int i = 0; i + 1 < kv.length; i += 2) {
                map.put(String.valueOf(kv[i]), kv[i + 1]);
            }
            return map;
        }
    }
}
