package com.hmdm.testapp;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Local VPN probes for the test app: really starting the VPN settings
 * activity (ActivityNotFoundException proves the entry is disabled,
 * ASR-0214) and enumerating the TRANSPORT_VPN networks (connection state
 * evidence, ASR-0218).
 */
public final class VpnVerifier {

    private static final String VPN_SETTINGS_COMPONENT = "com.android.settings/.Settings$VpnSettingsActivity";

    private VpnVerifier() {
    }

    /**
     * Really start the VPN settings activity. ActivityNotFoundException
     * proves the settings entry is disabled (ASR-0214). A visible activity
     * proves it is enabled.
     */
    public static Map<Object, Object> tryOpenVpnSettings(Context context) {
        Map<Object, Object> result = new LinkedHashMap<>();
        try {
            ComponentName component = ComponentName.unflattenFromString(VPN_SETTINGS_COMPONENT);
            ActivityInfo info = context.getPackageManager().getActivityInfo(component, 0);
            result.put("resolved", true);
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setComponent(component);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            result.put("started", true);
            result.put("visible", true);
        } catch (PackageManager.NameNotFoundException e) {
            result.put("resolved", false);
            result.put("started", false);
            result.put("visible", false);
            result.put("reason", "component disabled or missing (NameNotFoundException)");
        } catch (Exception e) {
            result.put("started", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * Enumerate the currently active TRANSPORT_VPN networks: owner package
     * per network plus the interface name. The Launcher's own firewall
     * VpnService appears with the Launcher package.
     */
    public static Map<Object, Object> checkVpnNetworks(Context context) {
        Map<Object, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> networks = new ArrayList<>();
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network[] all = cm.getAllNetworks();
            if (all != null) {
                for (Network network : all) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                    if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        continue;
                    }
                    NetworkInfo info = cm.getNetworkInfo(network);
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("network", network.toString());
                    entry.put("state", info == null ? "unknown" : String.valueOf(info.getState()));
                    entry.put("interface", cm.getLinkProperties(network) == null
                            ? "" : String.valueOf(cm.getLinkProperties(network).getInterfaceName()));
                    int[] uids = null;
                    try {
                        Object uidResult = caps.getClass().getMethod("getUids").invoke(caps);
                        if (uidResult instanceof int[]) {
                            uids = (int[]) uidResult;
                        }
                    } catch (Exception ignored) {
                        // uid list not available on this ROM
                    }
                    if (uids == null || uids.length == 0) {
                        try {
                            Object ownerResult = caps.getClass().getMethod("getOwnerUid").invoke(caps);
                            if (ownerResult instanceof Integer) {
                                uids = new int[]{(Integer) ownerResult};
                            }
                        } catch (Exception ignored) {
                            // owner uid not available either
                        }
                    }
                    List<String> pkgs = new ArrayList<>();
                    if (uids != null) {
                        for (int uid : uids) {
                            String[] names = context.getPackageManager().getPackagesForUid(uid);
                            if (names != null) {
                                for (String name : names) {
                                    if (!pkgs.contains(name)) {
                                        pkgs.add(name);
                                    }
                                }
                            }
                        }
                    }
                    entry.put("packages", pkgs);
                    networks.add(entry);
                }
            }
            result.put("vpnNetworkCount", networks.size());
            result.put("networks", networks);
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }
}
