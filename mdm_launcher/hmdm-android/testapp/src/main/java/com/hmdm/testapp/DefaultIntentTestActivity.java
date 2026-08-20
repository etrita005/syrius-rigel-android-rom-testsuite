package com.hmdm.testapp;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.EditText;

import java.util.HashMap;
import java.util.Map;

/**
 * Default-intent policy tests: default launcher change lock (ASR-0091),
 * default video player (ASR-0095) and default app for a file type
 * (ASR-0102).
 */
public class DefaultIntentTestActivity extends BaseTestActivity {

    private EditText etPkg;
    private EditText etActivity;
    private EditText etMimeType;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_default_intent_test);
        bindLogView();

        etPkg = findViewById(R.id.et_pkg);
        etActivity = findViewById(R.id.et_activity);
        etMimeType = findViewById(R.id.et_mime_type);

        findViewById(R.id.btn_launcher_lock).setOnClickListener(v ->
                setLauncherLock(true));
        findViewById(R.id.btn_launcher_unlock).setOnClickListener(v ->
                setLauncherLock(false));
        findViewById(R.id.btn_launcher_query).setOnClickListener(v ->
                runAction("IsDefaultLauncherSettingLocked", new HashMap<String, Object>()));

        findViewById(R.id.btn_video_set).setOnClickListener(v ->
                setDefaultApp("SetDefaultVideoPlayer", "video/*"));
        findViewById(R.id.btn_video_get).setOnClickListener(v ->
                runAction("GetDefaultVideoPlayer", new HashMap<String, Object>()));
        findViewById(R.id.btn_video_clear).setOnClickListener(v -> {
            String pkg = etPkg.getText().toString().trim();
            if (TextUtils.isEmpty(pkg)) {
                appendLog("ClearDefaultVideoPlayer: missing parameter: packageName");
                return;
            }
            Map<String, Object> param = new HashMap<>();
            param.put("packageName", pkg);
            runAction("ClearDefaultVideoPlayer", param);
        });

        findViewById(R.id.btn_filetype_set).setOnClickListener(v -> {
            String mimeType = etMimeType.getText().toString().trim();
            if (TextUtils.isEmpty(mimeType)) {
                appendLog("SetDefaultAppForFileType: missing parameter: mimeType");
                return;
            }
            setDefaultApp("SetDefaultAppForFileType", mimeType);
        });
        findViewById(R.id.btn_filetype_get).setOnClickListener(v -> {
            String mimeType = etMimeType.getText().toString().trim();
            if (TextUtils.isEmpty(mimeType)) {
                appendLog("GetDefaultAppForFileType: missing parameter: mimeType");
                return;
            }
            Map<String, Object> param = new HashMap<>();
            param.put("mimeType", mimeType);
            runAction("GetDefaultAppForFileType", param);
        });
        findViewById(R.id.btn_filetype_clear).setOnClickListener(v -> {
            String mimeType = etMimeType.getText().toString().trim();
            String pkg = etPkg.getText().toString().trim();
            if (TextUtils.isEmpty(mimeType) || TextUtils.isEmpty(pkg)) {
                appendLog("ClearDefaultAppForFileType: missing parameter: mimeType/packageName");
                return;
            }
            Map<String, Object> param = new HashMap<>();
            param.put("mimeType", mimeType);
            param.put("packageName", pkg);
            runAction("ClearDefaultAppForFileType", param);
        });
    }

    private void setLauncherLock(boolean locked) {
        Map<String, Object> param = new HashMap<>();
        param.put("locked", locked);
        runAction("SetDefaultLauncherSettingLocked", param);
    }

    private void setDefaultApp(String event, String mimeType) {
        String pkg = etPkg.getText().toString().trim();
        if (TextUtils.isEmpty(pkg)) {
            appendLog(event + ": missing parameter: packageName");
            return;
        }
        Map<String, Object> param = new HashMap<>();
        param.put("packageName", pkg);
        String activity = etActivity.getText().toString().trim();
        if (!TextUtils.isEmpty(activity)) {
            param.put("activityName", activity);
        }
        if (mimeType != null) {
            param.put("mimeType", mimeType);
        }
        runAction(event, param);
    }
}
