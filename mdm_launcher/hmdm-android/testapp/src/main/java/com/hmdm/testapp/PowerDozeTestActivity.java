package com.hmdm.testapp;

import android.os.Bundle;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Power / Doze / voice assistant tests: wake &amp; sleep the device
 * (ASR-0415/0416), forbid/allow the Doze battery saving mode (ASR-0373),
 * Doze whitelist management (ASR-0374) and the voice assistant disable
 * (ASR-0420).
 */
public class PowerDozeTestActivity extends BaseTestActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_power_doze_test);
        bindLogView();

        findViewById(R.id.btn_wake_up).setOnClickListener(v ->
                runAction("WakeUp", new HashMap<String, Object>()));
        findViewById(R.id.btn_go_sleep).setOnClickListener(v ->
                runAction("GoToSleep", new HashMap<String, Object>()));
        findViewById(R.id.btn_power_state).setOnClickListener(v ->
                runAction("GetPowerState", new HashMap<String, Object>()));

        findViewById(R.id.btn_doze_disabled_on).setOnClickListener(v ->
                setDisabled("SetDozeDisabled", true));
        findViewById(R.id.btn_doze_disabled_off).setOnClickListener(v ->
                setDisabled("SetDozeDisabled", false));
        findViewById(R.id.btn_doze_disabled_query).setOnClickListener(v ->
                runAction("IsDozeDisabled", new HashMap<String, Object>()));

        findViewById(R.id.btn_doze_whitelist_set).setOnClickListener(v -> {
            Map<String, Object> param = new HashMap<>();
            param.put("packageNames", parsePackages());
            runAction("SetDozeWhitelist", param);
        });
        findViewById(R.id.btn_doze_whitelist_clear).setOnClickListener(v -> {
            Map<String, Object> param = new HashMap<>();
            param.put("packageNames", new ArrayList<String>());
            runAction("SetDozeWhitelist", param);
        });
        findViewById(R.id.btn_doze_whitelist_get).setOnClickListener(v ->
                runAction("GetDozeWhitelist", new HashMap<String, Object>()));

        findViewById(R.id.btn_assistant_disable).setOnClickListener(v ->
                setDisabled("SetVoiceAssistantDisabled", true));
        findViewById(R.id.btn_assistant_enable).setOnClickListener(v ->
                setDisabled("SetVoiceAssistantDisabled", false));
        findViewById(R.id.btn_assistant_query).setOnClickListener(v ->
                runAction("IsVoiceAssistantDisabled", new HashMap<String, Object>()));
    }

    private void setDisabled(String event, boolean disabled) {
        Map<String, Object> param = new HashMap<>();
        param.put("disabled", disabled);
        runAction(event, param);
    }

    private List<String> parsePackages() {
        EditText input = findViewById(R.id.et_doze_whitelist);
        String text = input.getText() != null ? input.getText().toString().trim() : "";
        if (text.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(text.split("[,，]")));
    }
}
