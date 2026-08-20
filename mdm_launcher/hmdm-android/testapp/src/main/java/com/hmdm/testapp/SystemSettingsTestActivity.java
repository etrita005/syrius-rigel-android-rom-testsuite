package com.hmdm.testapp;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.EditText;

import java.util.HashMap;
import java.util.Map;

/**
 * System settings control tests: captive portal (ASR-0166), don't keep
 * activities (ASR-0204), mock location (ASR-0205), location mode (ASR-0314),
 * font scale (ASR-0407), gesture navigation (ASR-0345) and window animations
 * (ASR-0426).
 */
public class SystemSettingsTestActivity extends BaseTestActivity {

    private EditText etLocationMode;
    private EditText etFontScale;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_system_settings_test);
        bindLogView();

        etLocationMode = findViewById(R.id.et_location_mode);
        etFontScale = findViewById(R.id.et_font_scale);

        findViewById(R.id.btn_captive_disable).setOnClickListener(v ->
                setDisabled("SetCaptivePortalDisabled", true));
        findViewById(R.id.btn_captive_enable).setOnClickListener(v ->
                setDisabled("SetCaptivePortalDisabled", false));
        findViewById(R.id.btn_captive_query).setOnClickListener(v ->
                runAction("IsCaptivePortalDisabled", new HashMap<String, Object>()));

        findViewById(R.id.btn_afa_disable).setOnClickListener(v ->
                setDisabled("SetAlwaysFinishActivitiesDisabled", true));
        findViewById(R.id.btn_afa_enable).setOnClickListener(v ->
                setDisabled("SetAlwaysFinishActivitiesDisabled", false));
        findViewById(R.id.btn_afa_query).setOnClickListener(v ->
                runAction("IsAlwaysFinishActivitiesDisabled", new HashMap<String, Object>()));

        findViewById(R.id.btn_mock_disable).setOnClickListener(v ->
                setDisabled("SetMockLocationDisabled", true));
        findViewById(R.id.btn_mock_enable).setOnClickListener(v ->
                setDisabled("SetMockLocationDisabled", false));
        findViewById(R.id.btn_mock_query).setOnClickListener(v ->
                runAction("IsMockLocationDisabled", new HashMap<String, Object>()));

        findViewById(R.id.btn_location_set).setOnClickListener(v -> onSetLocationMode());
        findViewById(R.id.btn_location_get).setOnClickListener(v ->
                runAction("GetLocationMode", new HashMap<String, Object>()));

        findViewById(R.id.btn_font_scale_set).setOnClickListener(v -> onSetFontScale());
        findViewById(R.id.btn_font_scale_get).setOnClickListener(v ->
                runAction("GetFontScale", new HashMap<String, Object>()));

        findViewById(R.id.btn_gesture_disable).setOnClickListener(v ->
                setDisabled("SetGestureNavigationDisabled", true));
        findViewById(R.id.btn_gesture_enable).setOnClickListener(v ->
                setDisabled("SetGestureNavigationDisabled", false));
        findViewById(R.id.btn_gesture_query).setOnClickListener(v ->
                runAction("IsGestureNavigationDisabled", new HashMap<String, Object>()));

        findViewById(R.id.btn_anim_disable).setOnClickListener(v ->
                setDisabled("SetAnimationsDisabled", true));
        findViewById(R.id.btn_anim_enable).setOnClickListener(v ->
                setDisabled("SetAnimationsDisabled", false));
        findViewById(R.id.btn_anim_query).setOnClickListener(v ->
                runAction("IsAnimationsDisabled", new HashMap<String, Object>()));
    }

    private void setDisabled(String event, boolean disabled) {
        Map<String, Object> param = new HashMap<>();
        param.put("disabled", disabled);
        runAction(event, param);
    }

    private void onSetLocationMode() {
        String mode = etLocationMode.getText().toString().trim();
        if (TextUtils.isEmpty(mode)) {
            toast("Please enter a location mode (0/1/2/3)");
            return;
        }
        Map<String, Object> param = new HashMap<>();
        param.put("mode", Integer.parseInt(mode));
        runAction("SetLocationMode", param);
    }

    private void onSetFontScale() {
        String scale = etFontScale.getText().toString().trim();
        if (TextUtils.isEmpty(scale)) {
            toast("Please enter a font scale (0.5..2.0, presets 0.85/1.0/1.15/1.30)");
            return;
        }
        Map<String, Object> param = new HashMap<>();
        param.put("scale", Float.parseFloat(scale));
        runAction("SetFontScale", param);
    }
}
