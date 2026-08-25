package com.hmdm.testapp;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * Minimal accessibility service owned by the testapp, used as a controlled
 * activation target for the ASR-0074 (免交互激活/注销) and ASR-0075
 * (无障碍黑白名单) test cases: enabling/disabling it through the Launcher API
 * must work without any user interaction, and its bind state can be observed
 * via {@code dumpsys accessibility} and {@code settings get secure
 * enabled_accessibility_services}.
 */
public class TestAccessibilityService extends AccessibilityService {

    private static final String TAG = "TestAccessibilityService";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No-op: the service only needs to be bindable and observable.
    }

    @Override
    public void onInterrupt() {
        // No-op.
    }
}
