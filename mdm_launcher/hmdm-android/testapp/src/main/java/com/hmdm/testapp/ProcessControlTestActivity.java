package com.hmdm.testapp;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Process control tests: end a specified app process (ASR-0027) and clean up
 * background processes (ASR-0028).
 */
public class ProcessControlTestActivity extends BaseTestActivity {

    private EditText etKillPackage;
    private EditText etExcept;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_process_control_test);
        bindLogView();

        etKillPackage = findViewById(R.id.et_kill_package);
        etExcept = findViewById(R.id.et_except);

        findViewById(R.id.btn_kill_app_process).setOnClickListener(v -> {
            String pkg = etKillPackage.getText().toString().trim();
            if (TextUtils.isEmpty(pkg)) {
                appendLog("KillAppProcess: missing parameter: packageName");
                return;
            }
            Map<String, Object> param = new HashMap<>();
            param.put("packageName", pkg);
            runAction("KillAppProcess", param);
        });
        findViewById(R.id.btn_kill_background).setOnClickListener(v -> {
            Map<String, Object> param = new HashMap<>();
            String except = etExcept.getText().toString().trim();
            if (!TextUtils.isEmpty(except)) {
                List<String> exceptList = new ArrayList<>();
                for (String item : except.split(",")) {
                    String trimmed = item.trim();
                    if (!trimmed.isEmpty()) {
                        exceptList.add(trimmed);
                    }
                }
                param.put("except", exceptList);
            }
            runAction("KillBackgroundProcesses", param);
        });
    }
}
