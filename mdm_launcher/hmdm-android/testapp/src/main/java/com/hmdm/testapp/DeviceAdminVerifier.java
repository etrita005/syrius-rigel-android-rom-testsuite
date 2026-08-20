package com.hmdm.testapp;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;

/**
 * App-side end-to-end probe for the device-management tests: checks the
 * active-admin state of this app's own TestAdminReceiver through the public
 * DevicePolicyManager API, independent of the Launcher command result.
 */
public final class DeviceAdminVerifier {

    private DeviceAdminVerifier() {
    }

    public static boolean isOwnAdminActive(Context context) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context
                    .getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName component = new ComponentName(context, TestAdminReceiver.class);
            return dpm != null && dpm.isAdminActive(component);
        } catch (Exception e) {
            return false;
        }
    }
}
