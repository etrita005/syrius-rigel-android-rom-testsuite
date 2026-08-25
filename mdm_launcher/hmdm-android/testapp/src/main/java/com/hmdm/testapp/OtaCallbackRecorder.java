package com.hmdm.testapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Consumes the OTA callback broadcasts emitted by the Launcher
 * (ExportUpdaterService relays the four update engine callbacks, FotaStart
 * emits install status events) and keeps a bounded log of the last events.
 * <p>
 * This is the ASR-0451 "client consumption" verification point: a business
 * app subscribes to com.hmdm.launcher.ACTION_OTA_STATUS and receives the OTA
 * state / engine status / completion / progress callbacks.
 */
public final class OtaCallbackRecorder {

    private static final String TAG = "OtaCallbackRecorder";

    private static final String ACTION_OTA_STATUS = "com.hmdm.launcher.ACTION_OTA_STATUS";
    private static final int MAX_EVENTS = 100;

    private static final Object LOCK = new Object();
    private static final List<Map<String, Object>> EVENTS = new ArrayList<>();
    private static boolean registered = false;

    private OtaCallbackRecorder() {
    }

    /**
     * Registers the receiver once (process-wide). Idempotent.
     */
    public static void ensureRegistered(Context context) {
        synchronized (LOCK) {
            if (registered) {
                return;
            }
            Context appContext = context.getApplicationContext();
            try {
                IntentFilter filter = new IntentFilter(ACTION_OTA_STATUS);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appContext.registerReceiver(RECEIVER, filter, Context.RECEIVER_EXPORTED);
                } else {
                    appContext.registerReceiver(RECEIVER, filter);
                }
                registered = true;
                Log.i(TAG, "OTA status receiver registered");
            } catch (Exception e) {
                Log.e(TAG, "registerReceiver failed", e);
            }
        }
    }

    private static final BroadcastReceiver RECEIVER = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_OTA_STATUS.equals(intent.getAction())) {
                return;
            }
            Bundle extras = intent.getExtras();
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("time", System.currentTimeMillis());
            event.put("callbackType", intent.getStringExtra("callbackType"));
            event.put("value", intent.getStringExtra("value"));
            if (extras != null) {
                if (extras.containsKey("actionId")) {
                    event.put("actionId", extras.getString("actionId"));
                }
                if (extras.containsKey("code")) {
                    event.put("code", extras.getInt("code"));
                }
                if (extras.containsKey("msg")) {
                    event.put("msg", extras.getString("msg"));
                }
            }
            synchronized (LOCK) {
                EVENTS.add(event);
                while (EVENTS.size() > MAX_EVENTS) {
                    EVENTS.remove(0);
                }
            }
            Log.i(TAG, "OTA status event: " + event);
        }
    };

    /**
     * Returns the recorded callback events (newest last), with human-readable
     * names for the well-known callback types.
     */
    public static Map<String, Object> getLog() {
        synchronized (LOCK) {
            Map<String, Object> result = new LinkedHashMap<>();
            List<Map<String, Object>> events = new ArrayList<>();
            for (Map<String, Object> event : EVENTS) {
                Map<String, Object> copy = new LinkedHashMap<>(event);
                String type = (String) copy.get("callbackType");
                if (type != null) {
                    copy.put("callbackTypeName", callbackTypeName(type));
                    if ("updater_state".equals(type)) {
                        copy.put("valueText", updaterStateText(copy.get("value")));
                    }
                }
                events.add(copy);
            }
            result.put("count", EVENTS.size());
            result.put("events", events);
            return result;
        }
    }

    /** Clears the recorded events and returns the number cleared. */
    public static Map<String, Object> clear() {
        synchronized (LOCK) {
            int cleared = EVENTS.size();
            EVENTS.clear();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("cleared", cleared);
            return result;
        }
    }

    private static String callbackTypeName(String type) {
        switch (type) {
            case "updater_state":
                return "UpdaterState";
            case "engine_status":
                return "EngineStatus";
            case "engine_complete":
                return "EngineComplete";
            case "progress":
                return "Progress";
            default:
                return "FotaStatus";
        }
    }

    private static String updaterStateText(Object value) {
        try {
            int state = Integer.parseInt(String.valueOf(value));
            switch (state) {
                case 0:
                    return "IDLE";
                case 1:
                    return "ERROR";
                case 2:
                    return "RUNNING";
                case 3:
                    return "PAUSED";
                case 4:
                    return "SLOT_SWITCH_REQUIRED";
                case 5:
                    return "REBOOT_REQUIRED";
                default:
                    return "UNKNOWN(" + state + ")";
            }
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
