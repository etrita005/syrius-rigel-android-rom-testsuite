package com.hmdm.testapp;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.SystemClock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real-framework verification helpers for the WLAN control policies.
 * <p>
 * {@link #tryAddWifiNetwork} calls WifiManager.addNetwork from this normal
 * (non-device-owner) app - the exact path the Settings app / other apps use
 * to modify AP configs. Under the ASR-0150 AP config lockdown the framework
 * throws SecurityException for every caller that is not the device owner, so
 * a success here proves the lockdown is NOT active and an exception proves it
 * IS active.
 * <p>
 * {@link #tryConnectOpenWifi} enables a saved open network and watches
 * whether the framework really connects to it - used to observe the ASR-0152
 * SSID allowlist and ASR-0158 minimum security level enforcement on the
 * connect path. The saved network itself is never removed.
 */
public final class WifiVerifier {

    private WifiVerifier() {
    }

    /** ASR-0150 enforcement: add a temporary network from a non-device-owner app. */
    public static Map<String, Object> tryAddWifiNetwork(Context context) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            WifiConfiguration config = new WifiConfiguration();
            config.SSID = "\"HYX-MDM-TEMP-" + (System.currentTimeMillis() % 100000) + "\"";
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            int netId = wm.addNetwork(config);
            result.put("addNetworkSucceeded", netId != -1);
            result.put("netId", netId);
            if (netId != -1) {
                result.put("removed", wm.removeNetwork(netId));
            }
        } catch (Exception e) {
            result.put("addNetworkSucceeded", false);
            result.put("exception", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        result.put("success", true);
        return result;
    }

    /**
     * ASR-0152/0158 enforcement: enable a saved open network and report
     * whether the framework really connects to it. The saved network is left
     * untouched; if the connection succeeds the device is disconnected again.
     * Optional networkId skips the saved-network lookup (getConfiguredNetworks
     * is caller-filtered for non-privileged apps).
     */
    public static Map<String, Object> tryConnectOpenWifi(Context context, String ssid, int networkId) {
        Map<String, Object> result = new LinkedHashMap<>();
        WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        int netId = networkId;
        if (netId < 0) {
            try {
                List<WifiConfiguration> networks = wm.getConfiguredNetworks();
                List<String> configured = new java.util.ArrayList<>();
                if (networks != null) {
                    for (WifiConfiguration config : networks) {
                        configured.add(config.SSID == null ? "(null)" : config.SSID);
                        if (config.SSID != null && config.SSID.replace("\"", "").equals(ssid)) {
                            netId = config.networkId;
                        }
                    }
                }
                result.put("configuredSsidCount", configured.size());
                result.put("configuredSsids", configured);
            } catch (Exception e) {
                result.put("getConfiguredNetworksException", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        result.put("savedNetId", netId);
        if (netId == -1) {
            result.put("connected", false);
            result.put("success", true);
            return result;
        }
        try {
            boolean enabled = wm.enableNetwork(netId, true);
            result.put("enableNetworkResult", enabled);
            boolean connected = false;
            long deadline = SystemClock.elapsedRealtime() + 8000;
            while (SystemClock.elapsedRealtime() < deadline) {
                WifiInfo info = wm.getConnectionInfo();
                if (info != null && info.getSSID() != null
                        && info.getSSID().replace("\"", "").equals(ssid)) {
                    connected = true;
                    break;
                }
                SystemClock.sleep(500);
            }
            result.put("connected", connected);
            WifiInfo info = wm.getConnectionInfo();
            result.put("connectedSsid", info == null || info.getSSID() == null ? "" : info.getSSID());
            if (connected) {
                result.put("disconnected", wm.disconnect());
            }
        } catch (Exception e) {
            result.put("exception", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        result.put("success", true);
        return result;
    }

    /** Parse an optional networkId (Number or String), -1 when absent/invalid. */
    public static int parseNetworkId(Map<String, Object> param) {
        Object raw = param.get("networkId");
        if (raw == null) {
            return -1;
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
