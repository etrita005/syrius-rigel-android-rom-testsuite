package com.hmdm.testapp;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Local verifiers for the permission / AppOps batch (ASR-0043/0044/0045/0046/
 * 0149): these run inside the testapp and probe the real framework behavior
 * that each AppOps switch controls, so the effect (not only the stored mode)
 * is proven.
 */
public final class AppOpsVerifier {

    private static final String TAG = "AppOpsVerifier";

    private static final String PROBE_SETTINGS_KEY = "screen_brightness";

    private AppOpsVerifier() {
    }

    /**
     * ASR-0044: try to write a Settings.System value. The probe writes the
     * current value of the public key {@code screen_brightness} back, i.e. a
     * no-op as far as the device state is concerned (arbitrary custom keys
     * are rejected by this ROM's SettingsProvider for targetSdk &gt; 22 apps,
     * see the technical design document). With the WRITE_SETTINGS AppOps
     * ignored the provider rejects the call with a SecurityException; with
     * the op allowed the write succeeds.
     */
    public static Map<Object, Object> tryWriteSettings(Context context) {
        Map<Object, Object> result = new HashMap<>();
        try {
            int current = Settings.System.getInt(context.getContentResolver(), PROBE_SETTINGS_KEY, -1);
            if (current < 0) {
                result.put("wrote", false);
                result.put("message", "probe key not present: " + PROBE_SETTINGS_KEY);
                return result;
            }
            boolean ok = Settings.System.putInt(context.getContentResolver(),
                    PROBE_SETTINGS_KEY, current);
            result.put("wrote", ok);
            result.put("key", PROBE_SETTINGS_KEY);
            result.put("value", current);
            result.put("securityException", false);
            Log.i(TAG, "tryWriteSettings succeeded: " + ok);
        } catch (SecurityException e) {
            result.put("wrote", false);
            result.put("securityException", true);
            result.put("message", e.getMessage());
            Log.i(TAG, "tryWriteSettings blocked: " + e.getMessage());
        } catch (Exception e) {
            result.put("wrote", false);
            result.put("exception", e.getMessage());
            Log.i(TAG, "tryWriteSettings failed: " + e.getMessage());
        }
        return result;
    }

    /**
     * ASR-0149: call WifiManager.setWifiEnabled with the current state as the
     * argument, i.e. a no-op as far as the wifi state is concerned. The
     * WifiService enforces the OP_CHANGE_WIFI_STATE AppOps before doing
     * anything, so with the op ignored the call throws a SecurityException,
     * with the op allowed it returns the (no-op) result.
     */
    public static Map<Object, Object> tryChangeWifiState(Context context) {
        Map<Object, Object> result = new HashMap<>();
        try {
            WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            boolean current = wifiManager.isWifiEnabled();
            boolean returned = wifiManager.setWifiEnabled(current);
            result.put("target", current);
            result.put("setWifiEnabled", returned);
            result.put("securityException", false);
            Log.i(TAG, "tryChangeWifiState succeeded: " + returned);
        } catch (SecurityException e) {
            result.put("securityException", true);
            result.put("message", e.getMessage());
            Log.i(TAG, "tryChangeWifiState blocked: " + e.getMessage());
        } catch (Exception e) {
            result.put("exception", e.getMessage());
            Log.i(TAG, "tryChangeWifiState failed: " + e.getMessage());
        }
        return result;
    }

    /**
     * ASR-0043: try to enter picture-in-picture. Only works when the caller is
     * an Activity that supports PiP (the testapp's
     * PermissionAppOpsTestActivity does); the framework rejects the enter
     * request when the OP_PICTURE_IN_PICTURE AppOps is ignored, so the result
     * is a mechanism probe. Devices without PiP support return false either
     * way, so the AppOps read-back stays the authoritative check.
     */
    public static Map<Object, Object> tryEnterPip(Context context) {
        Map<Object, Object> result = new HashMap<>();
        if (!(context instanceof Activity)) {
            result.put("needsUi", true);
            result.put("message", "PiP probe needs an activity context: start com.hmdm.testapp/.PermissionAppOpsTestActivity and press the button");
            return result;
        }
        Activity activity = (Activity) context;
        try {
            PictureInPictureParams params = new PictureInPictureParams.Builder().build();
            boolean entered = activity.enterPictureInPictureMode(params);
            result.put("entered", entered);
            Log.i(TAG, "tryEnterPip entered: " + entered);
        } catch (Exception e) {
            result.put("entered", false);
            result.put("exception", e.getMessage());
            Log.i(TAG, "tryEnterPip failed: " + e.getMessage());
        }
        return result;
    }

    /**
     * ASR-0046: check whether this app holds the USB permission of an
     * attached host device (UsbManager.hasPermission reflects the granted
     * permission of the calling uid). Returns a map with the device presence
     * and the permission state; without an attached USB host device the
     * permission is always false.
     */
    public static Map<Object, Object> checkUsbPermission(Context context, String deviceName) {
        Map<Object, Object> result = new HashMap<>();
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        Map<String, UsbDevice> devices = usbManager.getDeviceList();
        if (devices == null || devices.isEmpty()) {
            result.put("deviceAttached", false);
            result.put("hasPermission", false);
            result.put("devices", new String[0]);
            return result;
        }
        String selector = deviceName == null ? "" : deviceName.trim();
        UsbDevice target = null;
        if (selector.isEmpty() || selector.equalsIgnoreCase("first")) {
            target = devices.values().iterator().next();
        } else {
            for (UsbDevice device : devices.values()) {
                if (device.getDeviceName().equals(selector)) {
                    target = device;
                    break;
                }
            }
        }
        result.put("deviceAttached", target != null);
        result.put("hasPermission", target != null && usbManager.hasPermission(target));
        result.put("deviceName", target == null ? null : target.getDeviceName());
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        for (UsbDevice device : devices.values()) {
            names.add(device.getDeviceName());
        }
        result.put("devices", names.toArray(new String[0]));
        return result;
    }
}
