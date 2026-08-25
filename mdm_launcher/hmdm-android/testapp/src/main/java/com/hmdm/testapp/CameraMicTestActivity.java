package com.hmdm.testapp;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import java.util.HashMap;
import java.util.Map;

/**
 * Camera &amp; microphone control tests (ASR-0207 / ASR-0369): policy
 * set/query via the Launcher API plus real hardware verification.
 */
public class CameraMicTestActivity extends BaseTestActivity {

    private static final int REQUEST_SENSOR_PERMISSIONS = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_mic_test);
        bindLogView();

        findViewById(R.id.btn_camera_disabled).setOnClickListener(v -> onSetCameraDisabled(true));
        findViewById(R.id.btn_camera_enabled).setOnClickListener(v -> onSetCameraDisabled(false));
        findViewById(R.id.btn_camera_query).setOnClickListener(v -> runAction("IsCameraDisabled", new HashMap<String, Object>()));
        findViewById(R.id.btn_camera_open).setOnClickListener(v -> runAction("TryOpenCamera", new HashMap<String, Object>()));
        findViewById(R.id.btn_mic_disabled).setOnClickListener(v -> onSetMicrophoneDisabled(true));
        findViewById(R.id.btn_mic_enabled).setOnClickListener(v -> onSetMicrophoneDisabled(false));
        findViewById(R.id.btn_mic_query).setOnClickListener(v -> runAction("IsMicrophoneDisabled", new HashMap<String, Object>()));
        findViewById(R.id.btn_mic_record).setOnClickListener(v -> runAction("TryRecordAudio", new HashMap<String, Object>()));
        findViewById(R.id.btn_request_sensor_permissions).setOnClickListener(v -> onRequestSensorPermissions());
    }

    private void onSetCameraDisabled(boolean disabled) {
        Map<String, Object> param = new HashMap<>();
        param.put("disabled", disabled);
        runAction("SetCameraDisabled", param);
    }

    private void onSetMicrophoneDisabled(boolean disabled) {
        Map<String, Object> param = new HashMap<>();
        param.put("disabled", disabled);
        runAction("SetMicrophoneDisabled", param);
    }

    private void onRequestSensorPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO}, REQUEST_SENSOR_PERMISSIONS);
            appendLog("CAMERA/RECORD_AUDIO permissions requested");
        } else {
            appendLog("Runtime permissions not required below API 23");
        }
    }
}
