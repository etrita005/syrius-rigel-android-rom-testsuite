package com.hmdm.testapp;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import java.util.HashMap;
import java.util.Map;

/**
 * Hub of the test app: connection probe, navigation into the per-feature test
 * activities and a shortcut for sending the own test notification.
 * Keeps the documented am start --ez send_test_notification true shortcut.
 */
public class MainActivity extends BaseTestActivity {

    private static final int REQUEST_POST_NOTIFICATIONS = 1;

    private MdmApiClient apiClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getIntent().getBooleanExtra("send_test_notification", false)) {
            NotificationSender.send(this);
            finish();
            return;
        }

        setContentView(R.layout.activity_main);
        bindLogView();

        apiClient = ApiHolder.get(this);
        apiClient.setBindListener(bound -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            appendLog("Connection changed: " + (bound ? "connected" : "disconnected"));
        });

        findViewById(R.id.btn_bind_status).setOnClickListener(v -> onBindStatus());
        findViewById(R.id.btn_open_notification).setOnClickListener(v -> startActivity(new Intent(this, NotificationTestActivity.class)));
        findViewById(R.id.btn_open_camera_mic).setOnClickListener(v -> startActivity(new Intent(this, CameraMicTestActivity.class)));
        findViewById(R.id.btn_open_log_control).setOnClickListener(v -> startActivity(new Intent(this, LogControlTestActivity.class)));
        findViewById(R.id.btn_open_network).setOnClickListener(v -> startActivity(new Intent(this, NetworkTestActivity.class)));
        findViewById(R.id.btn_open_stats_query).setOnClickListener(v -> startActivity(new Intent(this, StatsQueryTestActivity.class)));
        findViewById(R.id.btn_open_process_control).setOnClickListener(v -> startActivity(new Intent(this, ProcessControlTestActivity.class)));
        findViewById(R.id.btn_open_app_run_policy).setOnClickListener(v -> startActivity(new Intent(this, AppRunPolicyTestActivity.class)));
        findViewById(R.id.btn_open_system_settings).setOnClickListener(v -> startActivity(new Intent(this, SystemSettingsTestActivity.class)));
        findViewById(R.id.btn_open_lock_screen_policy).setOnClickListener(v -> startActivity(new Intent(this, LockScreenPolicyTestActivity.class)));
        findViewById(R.id.btn_open_account_backup).setOnClickListener(v -> startActivity(new Intent(this, AccountBackupTestActivity.class)));
        findViewById(R.id.btn_open_accessibility_screenshot_update).setOnClickListener(v -> startActivity(new Intent(this, AccessibilityScreenshotUpdateTestActivity.class)));
        findViewById(R.id.btn_open_accessibility_service).setOnClickListener(v -> startActivity(new Intent(this, AccessibilityTestActivity.class)));
        findViewById(R.id.btn_open_wifi_control).setOnClickListener(v -> startActivity(new Intent(this, WifiControlTestActivity.class)));
        findViewById(R.id.btn_open_wifi_access).setOnClickListener(v -> startActivity(new Intent(this, WifiAccessTestActivity.class)));
        findViewById(R.id.btn_open_device_state).setOnClickListener(v -> startActivity(new Intent(this, DeviceStateTestActivity.class)));
        findViewById(R.id.btn_open_tethering).setOnClickListener(v -> startActivity(new Intent(this, TetheringTestActivity.class)));
        findViewById(R.id.btn_open_permission_appops).setOnClickListener(v -> startActivity(new Intent(this, PermissionAppOpsTestActivity.class)));
        findViewById(R.id.btn_open_device_admin).setOnClickListener(v -> startActivity(new Intent(this, DeviceAdminTestActivity.class)));
        findViewById(R.id.btn_open_default_app_component).setOnClickListener(v -> startActivity(new Intent(this, DefaultAppComponentTestActivity.class)));
        findViewById(R.id.btn_open_vpn).setOnClickListener(v -> startActivity(new Intent(this, VpnTestActivity.class)));
        findViewById(R.id.btn_open_data_storage_user).setOnClickListener(v -> startActivity(new Intent(this, DataStorageUserTestActivity.class)));
        findViewById(R.id.btn_open_power_doze).setOnClickListener(v -> startActivity(new Intent(this, PowerDozeTestActivity.class)));
        findViewById(R.id.btn_open_ethernet).setOnClickListener(v -> startActivity(new Intent(this, EthernetTestActivity.class)));
        findViewById(R.id.btn_open_bluetooth).setOnClickListener(v -> startActivity(new Intent(this, BluetoothTestActivity.class)));
        findViewById(R.id.btn_open_wallpaper).setOnClickListener(v -> startActivity(new Intent(this, WallpaperTestActivity.class)));
        findViewById(R.id.btn_open_info_query).setOnClickListener(v -> startActivity(new Intent(this, InfoQueryTestActivity.class)));
        findViewById(R.id.btn_open_entry_settings).setOnClickListener(v -> startActivity(new Intent(this, EntrySettingsTestActivity.class)));
        findViewById(R.id.btn_open_email_control).setOnClickListener(v -> startActivity(new Intent(this, EmailControlTestActivity.class)));
        findViewById(R.id.btn_open_app_policy).setOnClickListener(v -> startActivity(new Intent(this, AppPolicyTestActivity.class)));
        findViewById(R.id.btn_open_default_intent).setOnClickListener(v -> startActivity(new Intent(this, DefaultIntentTestActivity.class)));
        findViewById(R.id.btn_open_usb_storage).setOnClickListener(v -> startActivity(new Intent(this, UsbStorageTestActivity.class)));
        findViewById(R.id.btn_open_volume_display).setOnClickListener(v -> startActivity(new Intent(this, VolumeDisplayTestActivity.class)));
        findViewById(R.id.btn_open_ota).setOnClickListener(v -> startActivity(new Intent(this, OtaTestActivity.class)));
        findViewById(R.id.btn_send_notification).setOnClickListener(v -> runAction("SendTestNotification", new HashMap<String, Object>()));
        findViewById(R.id.btn_request_permission).setOnClickListener(v -> onRequestPermission());
    }

    @Override
    protected void onDestroy() {
        apiClient.setBindListener(null);
        super.onDestroy();
    }

    private void onBindStatus() {
        boolean bound = apiClient.waitForBound(2000);
        Map<Object, Object> result = apiClient.call("GetZenMode", new HashMap<String, Object>());
        appendLog("Connection: " + (bound ? "connected" : "not connected")
                + ", GetZenMode RESULT: " + result.get("RESULT"));
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
}
