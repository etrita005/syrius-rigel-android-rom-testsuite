package com.hmdm.testapp;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.EditText;

import java.util.HashMap;
import java.util.Map;

/**
 * Lock screen strategy tests: strong auth timeout (ASR-0359), consecutive
 * digit sequence limit (ASR-0363) and password expiration grace period
 * (ASR-0365).
 */
public class LockScreenPolicyTestActivity extends BaseTestActivity {

    private EditText etStrongAuthTimeout;
    private EditText etPasswordExpiration;
    private EditText etDigitsLimit;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lock_screen_policy_test);
        bindLogView();

        etStrongAuthTimeout = findViewById(R.id.et_strong_auth_timeout);
        etPasswordExpiration = findViewById(R.id.et_password_expiration);
        etDigitsLimit = findViewById(R.id.et_digits_limit);

        findViewById(R.id.btn_strong_auth_set).setOnClickListener(v ->
                onSetLong("SetStrongAuthTimeout", etStrongAuthTimeout, "timeoutMs"));
        findViewById(R.id.btn_strong_auth_get).setOnClickListener(v ->
                runAction("GetStrongAuthTimeout", new HashMap<String, Object>()));

        findViewById(R.id.btn_password_expiration_set).setOnClickListener(v ->
                onSetLong("SetPasswordExpirationTimeout", etPasswordExpiration, "timeoutMs"));
        findViewById(R.id.btn_password_expiration_get).setOnClickListener(v ->
                runAction("GetPasswordExpirationTimeout", new HashMap<String, Object>()));

        findViewById(R.id.btn_digits_limit_set).setOnClickListener(v ->
                onSetLimit());
        findViewById(R.id.btn_digits_limit_get).setOnClickListener(v ->
                runAction("GetConsecutiveDigitsLimit", new HashMap<String, Object>()));
    }

    private void onSetLong(String event, EditText editText, String key) {
        String text = editText.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            toast("Please enter a " + key + " value");
            return;
        }
        long value;
        try {
            value = Long.parseLong(text);
        } catch (NumberFormatException e) {
            toast("Invalid number: " + text);
            return;
        }
        Map<String, Object> param = new HashMap<>();
        param.put(key, value);
        runAction(event, param);
    }

    private void onSetLimit() {
        String text = etDigitsLimit.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            toast("Please enter a limit value (0..16)");
            return;
        }
        int limit;
        try {
            limit = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            toast("Invalid number: " + text);
            return;
        }
        Map<String, Object> param = new HashMap<>();
        param.put("limit", limit);
        runAction("SetConsecutiveDigitsLimit", param);
    }
}
