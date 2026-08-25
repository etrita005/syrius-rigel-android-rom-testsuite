package com.hmdm.testapp;

import android.service.notification.NotificationListenerService;
import android.util.Log;

/**
 * No-op NotificationListenerService used as the binding target of
 * ASR-0045 (grant/revoke notification listener access without user
 * interaction). The framework binds it when the Launcher grants access;
 * its only purpose here is to prove that the binding happened.
 */
public class TestNotificationListenerService extends NotificationListenerService {

    private static final String TAG = "TestNotificationListener";

    @Override
    public void onListenerConnected() {
        Log.i(TAG, "TestNotificationListenerService connected");
    }

    @Override
    public void onListenerDisconnected() {
        Log.i(TAG, "TestNotificationListenerService disconnected");
    }
}
