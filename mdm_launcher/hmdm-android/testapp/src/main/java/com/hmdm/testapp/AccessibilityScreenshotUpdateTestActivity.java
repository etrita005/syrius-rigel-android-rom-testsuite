package com.hmdm.testapp;

import android.os.Bundle;

import java.util.HashMap;
import java.util.Map;

/**
 * Accessibility shortcut / screenshot / system update policy tests
 * (ASR-0078 accessibility shortcut, ASR-0185/0186 screenshots,
 * ASR-0444 online FOTA): policy set/query via the Launcher API.
 */
public class AccessibilityScreenshotUpdateTestActivity extends BaseTestActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_accessibility_screenshot_update_test);
        bindLogView();

        findViewById(R.id.btn_a11y_shortcut_disabled).setOnClickListener(v -> onSetDisabled("SetAccessibilityShortcutDisabled", true));
        findViewById(R.id.btn_a11y_shortcut_enabled).setOnClickListener(v -> onSetDisabled("SetAccessibilityShortcutDisabled", false));
        findViewById(R.id.btn_a11y_shortcut_query).setOnClickListener(v -> runAction("IsAccessibilityShortcutDisabled", new HashMap<String, Object>()));

        findViewById(R.id.btn_screenshots_disabled).setOnClickListener(v -> onSetDisabled("SetScreenshotsDisabled", true));
        findViewById(R.id.btn_screenshots_enabled).setOnClickListener(v -> onSetDisabled("SetScreenshotsDisabled", false));
        findViewById(R.id.btn_screenshots_query).setOnClickListener(v -> runAction("IsScreenshotsDisabled", new HashMap<String, Object>()));

        findViewById(R.id.btn_fota_disabled).setOnClickListener(v -> onSetDisabled("SetOnlineFotaDisabled", true));
        findViewById(R.id.btn_fota_enabled).setOnClickListener(v -> onSetDisabled("SetOnlineFotaDisabled", false));
        findViewById(R.id.btn_fota_query).setOnClickListener(v -> runAction("IsOnlineFotaDisabled", new HashMap<String, Object>()));
    }

    private void onSetDisabled(String event, boolean disabled) {
        Map<String, Object> param = new HashMap<>();
        param.put("disabled", disabled);
        runAction(event, param);
    }
}
