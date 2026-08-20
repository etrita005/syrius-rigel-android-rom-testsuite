package com.hmdm.testapp;

import android.os.Bundle;

import java.util.HashMap;
import java.util.Map;

/**
 * OTA interface group tests (ASR-0446 check/download FOTA, ASR-0448 local OTA
 * policy switch, ASR-0449 cancel, ASR-0450 suspend/resume, ASR-0451 callback
 * consumption, ASR-0452 A/B slots) via the Launcher API.
 * <p>
 * The config JSON parameters are provided through the IPC channel
 * (send_test_command.sh), exactly like the other test activities.
 */
public class OtaTestActivity extends BaseTestActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ota_test);
        bindLogView();

        OtaCallbackRecorder.ensureRegistered(this);

        findViewById(R.id.btn_fota_check_update).setOnClickListener(v -> runAction("FotaCheckUpdate", new HashMap<String, Object>()));
        findViewById(R.id.btn_fota_download_update).setOnClickListener(v -> runAction("FotaDownloadUpdate", new HashMap<String, Object>()));
        findViewById(R.id.btn_local_ota_enabled).setOnClickListener(v -> onSetEnabled("SetLocalOtaEnabled", true));
        findViewById(R.id.btn_local_ota_disabled).setOnClickListener(v -> onSetEnabled("SetLocalOtaEnabled", false));
        findViewById(R.id.btn_local_ota_query).setOnClickListener(v -> runAction("IsLocalOtaEnabled", new HashMap<String, Object>()));
        findViewById(R.id.btn_fota_apply).setOnClickListener(v -> runAction("FotaApply", new HashMap<String, Object>()));
        findViewById(R.id.btn_fota_cancel).setOnClickListener(v -> runAction("FotaCancel", new HashMap<String, Object>()));
        findViewById(R.id.btn_fota_suspend).setOnClickListener(v -> runAction("FotaSuspend", new HashMap<String, Object>()));
        findViewById(R.id.btn_fota_resume).setOnClickListener(v -> runAction("FotaResume", new HashMap<String, Object>()));
        findViewById(R.id.btn_get_ota_callback_log).setOnClickListener(v -> runAction("GetOtaCallbackLog", new HashMap<String, Object>()));
        findViewById(R.id.btn_clear_ota_callback_log).setOnClickListener(v -> runAction("ClearOtaCallbackLog", new HashMap<String, Object>()));
        findViewById(R.id.btn_get_slot_info).setOnClickListener(v -> runAction("GetSlotInfo", new HashMap<String, Object>()));
        findViewById(R.id.btn_set_switch_slot_on_reboot).setOnClickListener(v -> runAction("SetSwitchSlotOnReboot", new HashMap<String, Object>()));
    }

    private void onSetEnabled(String event, boolean enabled) {
        Map<String, Object> param = new HashMap<>();
        param.put("enabled", enabled);
        runAction(event, param);
    }
}
