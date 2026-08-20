package com.hmdm.testapp;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.EditText;

import java.util.HashMap;
import java.util.Map;

/**
 * Log buffer size &amp; log level tests (ASR-0189 / ASR-0190).
 */
public class LogControlTestActivity extends BaseTestActivity {

    private EditText etLogSize;
    private EditText etLogBuffer;
    private EditText etLogTag;
    private EditText etLogLevel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_control_test);
        bindLogView();

        etLogSize = findViewById(R.id.et_log_size);
        etLogBuffer = findViewById(R.id.et_log_buffer);
        etLogTag = findViewById(R.id.et_log_tag);
        etLogLevel = findViewById(R.id.et_log_level);

        findViewById(R.id.btn_log_buf_set).setOnClickListener(v -> onSetLogBufferSize());
        findViewById(R.id.btn_log_buf_get).setOnClickListener(v -> runAction("GetLogBufferSize", new HashMap<String, Object>()));
        findViewById(R.id.btn_log_level_set).setOnClickListener(v -> onSetLogLevel());
        findViewById(R.id.btn_log_level_get).setOnClickListener(v -> onGetLogLevel());
        findViewById(R.id.btn_log_emit_verbose).setOnClickListener(v -> onEmitVerboseLog());
    }

    private void onSetLogBufferSize() {
        String size = etLogSize.getText().toString().trim();
        if (TextUtils.isEmpty(size)) {
            toast("Please enter a buffer size");
            return;
        }
        Map<String, Object> param = new HashMap<>();
        param.put("size", size);
        String buffer = etLogBuffer.getText().toString().trim();
        if (!TextUtils.isEmpty(buffer)) {
            param.put("buffer", buffer);
        }
        runAction("SetLogBufferSize", param);
    }

    private void onSetLogLevel() {
        String tag = etLogTag.getText().toString().trim();
        if (TextUtils.isEmpty(tag)) {
            toast("Please enter a log tag");
            return;
        }
        Map<String, Object> param = new HashMap<>();
        param.put("tag", tag);
        String level = etLogLevel.getText().toString().trim();
        if (!TextUtils.isEmpty(level)) {
            param.put("level", level);
        }
        runAction("SetLogLevel", param);
    }

    private void onGetLogLevel() {
        String tag = etLogTag.getText().toString().trim();
        if (TextUtils.isEmpty(tag)) {
            toast("Please enter a log tag");
            return;
        }
        Map<String, Object> param = new HashMap<>();
        param.put("tag", tag);
        runAction("GetLogLevel", param);
    }

    private void onEmitVerboseLog() {
        String tag = etLogTag.getText().toString().trim();
        if (TextUtils.isEmpty(tag)) {
            toast("Please enter a log tag");
            return;
        }
        Map<String, Object> param = new HashMap<>();
        param.put("tag", tag);
        runAction("EmitTestLogs", param);
    }
}
