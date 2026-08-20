package com.hmdm.testapp;

import android.os.Bundle;

import java.util.HashMap;
import java.util.Map;

/**
 * Data / storage / screenshot / user tests (ASR-0125 app data backup/restore,
 * ASR-0127 clear app cache, ASR-0197 unmount USB storage, ASR-0326 format
 * external SD, ASR-0187 take screenshot, ASR-0385 create user, ASR-0386
 * delete user): command execution via the Launcher API plus local data/cache
 * probes for end-to-end verification.
 */
public class DataStorageUserTestActivity extends BaseTestActivity {

    private static final String BACKUP_FILE = "/sdcard/MDM/backup/testapp_ui.ab";
    private static final String SCREENSHOT_FILE = "/sdcard/Pictures/MDM/screenshot_ui.png";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_data_storage_user_test);
        bindLogView();

        findViewById(R.id.btn_write_data_probe).setOnClickListener(v -> onWriteDataProbe());
        findViewById(R.id.btn_read_data_probe).setOnClickListener(v -> runAction("ReadDataProbe", new HashMap<String, Object>()));
        findViewById(R.id.btn_backup).setOnClickListener(v -> onBackup());
        findViewById(R.id.btn_restore).setOnClickListener(v -> onRestore());

        findViewById(R.id.btn_write_cache_probe).setOnClickListener(v -> onWriteCacheProbe());
        findViewById(R.id.btn_check_cache_probe).setOnClickListener(v -> runAction("CheckCacheProbe", new HashMap<String, Object>()));
        findViewById(R.id.btn_clear_cache).setOnClickListener(v -> onClearCache());

        findViewById(R.id.btn_screenshot).setOnClickListener(v -> onScreenshot());
        findViewById(R.id.btn_storage_volumes).setOnClickListener(v -> runAction("GetStorageVolumes", new HashMap<String, Object>()));
        findViewById(R.id.btn_unmount_usb).setOnClickListener(v -> runAction("UnmountUsbStorage", new HashMap<String, Object>()));
        findViewById(R.id.btn_format_sd).setOnClickListener(v -> runAction("FormatExternalSd", new HashMap<String, Object>()));

        findViewById(R.id.btn_create_user).setOnClickListener(v -> onCreateUser());
        findViewById(R.id.btn_list_users).setOnClickListener(v -> runAction("GetUserList", new HashMap<String, Object>()));
        findViewById(R.id.btn_list_users_local).setOnClickListener(v -> runAction("GetUserListLocal", new HashMap<String, Object>()));
        findViewById(R.id.btn_delete_user).setOnClickListener(v -> onDeleteUser());
    }

    private void onWriteDataProbe() {
        Map<String, Object> param = new HashMap<>();
        param.put("value", "mdm-data-probe-v1");
        runAction("WriteDataProbe", param);
    }

    private void onBackup() {
        Map<String, Object> param = new HashMap<>();
        param.put("packageName", getPackageName());
        param.put("file", BACKUP_FILE);
        runAction("BackupAppData", param);
    }

    private void onRestore() {
        Map<String, Object> param = new HashMap<>();
        param.put("file", BACKUP_FILE);
        runAction("RestoreAppData", param);
    }

    private void onWriteCacheProbe() {
        Map<String, Object> param = new HashMap<>();
        param.put("value", "mdm-cache-probe-v1");
        runAction("WriteCacheProbe", param);
    }

    private void onClearCache() {
        Map<String, Object> param = new HashMap<>();
        param.put("packageName", getPackageName());
        runAction("ClearAppCache", param);
    }

    private void onScreenshot() {
        Map<String, Object> param = new HashMap<>();
        param.put("file", SCREENSHOT_FILE);
        runAction("TakeScreenshot", param);
    }

    private void onCreateUser() {
        Map<String, Object> param = new HashMap<>();
        param.put("name", "mdm_test_user");
        runAction("CreateUser", param);
    }

    private void onDeleteUser() {
        Map<String, Object> param = new HashMap<>();
        param.put("userId", 0);
        runAction("DeleteUser", param);
    }
}
