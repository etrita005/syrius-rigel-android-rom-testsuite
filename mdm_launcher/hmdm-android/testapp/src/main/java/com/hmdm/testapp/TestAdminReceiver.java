package com.hmdm.testapp;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Device admin receiver of this test app. It is the activation/deactivation
 * target of the device-management tests (ASR-0080 non-interactive activate /
 * deactivate and ASR-0081 force activation), and it can be queried from the
 * app side with {@code dpm.isAdminActive(this component)} for end-to-end
 * verification.
 */
public class TestAdminReceiver extends DeviceAdminReceiver {

    private static final String TAG = "TestAdminReceiver";

    @Override
    public void onEnabled(Context context, Intent intent) {
        Log.i(TAG, "onEnabled");
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        Log.i(TAG, "onDisabled");
    }
}
