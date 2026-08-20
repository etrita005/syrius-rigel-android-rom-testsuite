package com.hmdm.testapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;

public class NotificationSender {

    public static final String CHANNEL_ID = "testapp_channel";
    public static final int NOTIFICATION_ID = 1001;

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "TestApp Notifications", NotificationManager.IMPORTANCE_DEFAULT);
            manager.createNotificationChannel(channel);
        }
    }

    /**
     * Send a distinguishable test notification to verify that it no longer appears
     * after notifications are disabled (ASR-0053).
     */
    public static void send(Context context) {
        Log.i("TestAppSender", "send() enter");
        ensureChannel(context);
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("TestApp Test Notification")
                .setContentText("Test notification from " + context.getPackageName())
                .setAutoCancel(true)
                .build();
        try {
            manager.cancel(NOTIFICATION_ID);
            manager.notify(NOTIFICATION_ID, notification);
            Log.i("TestAppSender", "cancel+notify() done");
        } catch (Exception e) {
            Log.i("TestAppSender", "notify() error: " + e);
        }
    }
}
