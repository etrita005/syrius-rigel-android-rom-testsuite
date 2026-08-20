package com.hmdm.testapp;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real-framework verification helpers for the Bluetooth control policies.
 * <p>
 * {@link #trySetDiscoverable} switches the scan mode to
 * SCAN_MODE_CONNECTABLE_DISCOVERABLE exactly like the Settings visibility
 * toggle does, then watches whether the Launcher's discoverable forbid
 * policy (ASR-0180/0181) reverts it to CONNECTABLE - a closed-loop check of
 * the enforcement engine.
 * <p>
 * {@link #tryOpenBluetoothSettings} launches the Bluetooth settings page
 * (android.settings.BLUETOOTH_SETTINGS) to observe the ASR-0174 entry
 * disable; {@link #resolveBluetoothShare} resolves the "share via Bluetooth"
 * SEND intent against com.android.bluetooth to observe the ASR-0176
 * BluetoothOpp component disable.
 */
public final class BluetoothVerifier {

    private BluetoothVerifier() {
    }

    /**
     * Set the adapter to the discoverable scan mode (the exact action of the
     * Settings visibility toggle) and observe whether the policy engine
     * reverts it. Polls the scan mode for up to 3 seconds.
     */
    public static Map<String, Object> trySetDiscoverable(Context context) {
        Map<String, Object> result = new LinkedHashMap<>();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            result.put("success", false);
            result.put("error", "no bluetooth hardware");
            return result;
        }
        if (!adapter.isEnabled()) {
            result.put("success", false);
            result.put("error", "bluetooth is disabled");
            return result;
        }
        boolean set = false;
        String exception = null;
        try {
            Method method = BluetoothAdapter.class.getMethod("setScanMode", int.class);
            Object ret = method.invoke(adapter, BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE);
            if (ret instanceof Integer) {
                set = (Integer) ret != -1;
            } else {
                set = Boolean.TRUE.equals(ret);
            }
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            exception = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }
        result.put("success", true);
        result.put("setDiscoverableSucceeded", set);
        if (exception != null) {
            result.put("exception", exception);
        }
        result.put("scanModeAfterSet", scanModeName(adapter.getScanMode()));
        long deadline = SystemClock.uptimeMillis() + 3000;
        int last = adapter.getScanMode();
        while (SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(250);
            last = adapter.getScanMode();
            if (last != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
                break;
            }
        }
        result.put("scanModeSettled", scanModeName(last));
        result.put("revertedByPolicy", last != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE);
        result.put("discovering", adapter.isDiscovering());
        return result;
    }

    /**
     * Start discovery and observe the scan mode: during discovery the
     * adapter becomes (limited) discoverable, which is exactly the state
     * the forbid policies must revert. Reports the settled scan mode.
     */
    public static Map<String, Object> tryStartDiscovery(Context context) {
        Map<String, Object> result = new LinkedHashMap<>();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            result.put("success", false);
            result.put("error", "no bluetooth hardware");
            return result;
        }
        if (!adapter.isEnabled()) {
            result.put("success", false);
            result.put("error", "bluetooth is disabled");
            return result;
        }
        boolean started = false;
        try {
            started = adapter.startDiscovery();
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            result.put("exception", cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
        result.put("success", true);
        result.put("discoveryStarted", started);
        result.put("scanModeAfterStart", scanModeName(adapter.getScanMode()));
        long deadline = SystemClock.uptimeMillis() + 3000;
        int last = adapter.getScanMode();
        while (SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(250);
            last = adapter.getScanMode();
            if (!adapter.isDiscovering()) {
                break;
            }
        }
        result.put("scanModeSettled", scanModeName(last));
        result.put("discoveringSettled", adapter.isDiscovering());
        result.put("revertedByPolicy", last != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE);
        return result;
    }

    /**
     * ASR-0174: probe the Bluetooth settings page. Tries both the action
     * android.settings.BLUETOOTH_SETTINGS and a direct launch of the page
     * component Settings$BluetoothSettingsActivity. On this ROM the action
     * falls through to the connected-devices dashboard (MTK quirk), so the
     * page-availability verdict is the direct page launch.
     */
    public static Map<String, Object> tryOpenBluetoothSettings(Context context) {
        Map<String, Object> result = new LinkedHashMap<>();
        Intent actionIntent = new Intent("android.settings.BLUETOOTH_SETTINGS");
        actionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(actionIntent);
            result.put("actionStarted", true);
        } catch (ActivityNotFoundException e) {
            result.put("actionStarted", false);
            result.put("actionBlocked", true);
        } catch (Exception e) {
            result.put("actionStarted", false);
            result.put("actionException", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        Intent pageIntent = new Intent();
        pageIntent.setComponent(new ComponentName("com.android.settings",
                "com.android.settings.Settings$BluetoothSettingsActivity"));
        pageIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(pageIntent);
            result.put("pageStarted", true);
        } catch (ActivityNotFoundException e) {
            result.put("pageStarted", false);
            result.put("pageBlocked", true);
        } catch (Exception e) {
            result.put("pageStarted", false);
            result.put("pageException", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        result.put("success", true);
        result.put("pageComponent", "com.android.settings/.Settings$BluetoothSettingsActivity");
        return result;
    }

    /**
     * ASR-0176: resolve the share-via-Bluetooth SEND intent. Returns the
     * resolved OPP component (or nothing when the components are disabled).
     */
    public static Map<String, Object> resolveBluetoothShare(Context context) {
        Map<String, Object> result = new LinkedHashMap<>();
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("*/*");
        intent.setPackage("com.android.bluetooth");
        try {
            ResolveInfo info = context.getPackageManager().resolveActivity(intent, 0);
            if (info != null && info.activityInfo != null) {
                result.put("success", true);
                result.put("resolved", true);
                result.put("component", new ComponentName(info.activityInfo.packageName,
                        info.activityInfo.name).flattenToString());
            } else {
                result.put("success", true);
                result.put("resolved", false);
            }
        } catch (Exception e) {
            result.put("success", true);
            result.put("resolved", false);
            result.put("exception", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return result;
    }

    /** Adapter state from this app (scan mode, bonded devices, discovery). */
    public static Map<String, Object> getBluetoothState(Context context) {
        Map<String, Object> result = new LinkedHashMap<>();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            result.put("bluetoothSupported", false);
            return result;
        }
        result.put("bluetoothSupported", true);
        result.put("bluetoothEnabled", adapter.isEnabled());
        if (adapter.isEnabled()) {
            result.put("scanMode", scanModeName(adapter.getScanMode()));
            result.put("discovering", adapter.isDiscovering());
            List<String> bonded = new ArrayList<>();
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                bonded.add(device.getAddress());
            }
            result.put("bondedDevices", bonded);
        }
        result.put("success", true);
        return result;
    }

    private static String scanModeName(int mode) {
        switch (mode) {
            case BluetoothAdapter.SCAN_MODE_NONE:
                return "none";
            case BluetoothAdapter.SCAN_MODE_CONNECTABLE:
                return "connectable";
            case BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE:
                return "discoverable";
            default:
                return String.valueOf(mode);
        }
    }
}
