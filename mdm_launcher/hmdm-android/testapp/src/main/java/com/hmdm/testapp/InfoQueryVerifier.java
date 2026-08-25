package com.hmdm.testapp;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Settings;
import android.telephony.CellInfo;
import android.telephony.TelephonyManager;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Local probes for the device-info query tests: the test app runs its own
 * copies of the root check / cell info / SIM contacts / WebView provider
 * queries to cross-check the Launcher API results from an independent
 * process. The test app is platform-signed but not the system uid, so the
 * telephony probes may hit permission denials - those are reported honestly
 * and prove the permission gate.
 */
public final class InfoQueryVerifier {

    private static final String SIM_CONTACTS_URI = "content://icc/adn";

    private static final String[] SU_PATHS = {
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/vendor/bin/su", "/vendor/xbin/su",
            "/system/bin/.su", "/system/xbin/.su",
            "/data/local/bin/su", "/data/local/xbin/su"
    };

    private static final String[] MAGISK_PATHS = {
            "/data/adb/magisk/magisk", "/data/adb/magisk", "/sbin/.magisk"
    };

    private InfoQueryVerifier() {
    }

    /**
     * Local root status probe: static indicator file checks plus shell
     * probes from this app's process. SELinux hides su_exec from normal
     * app domains too, so the shell probe ("Permission denied" output) is
     * the cross-check evidence; the Launcher (uid 1000) result remains
     * authoritative.
     */
    public static Map<Object, Object> checkRootStatusLocal() {
        Map<Object, Object> result = new LinkedHashMap<>();
        List<String> suFound = new ArrayList<>();
        for (String path : SU_PATHS) {
            File f = new File(path);
            if (f.exists() && !f.isDirectory()) {
                suFound.add(path);
            }
        }
        try {
            String ls = exec("sh -c 'ls -l /system/bin/su /system/xbin/su /sbin/su /vendor/bin/su "
                    + "/vendor/xbin/su 2>&1'");
            if (ls != null) {
                for (String line : ls.split("\n")) {
                    for (String path : SU_PATHS) {
                        if (path.contains("/.") || suFound.contains(path)) {
                            continue;
                        }
                        if (line.contains(path) && line.contains("Permission denied")) {
                            suFound.add(path);
                        }
                    }
                }
            }
            result.put("suLsProbe", ls == null ? "" : ls.trim());
        } catch (Exception ignored) {
        }
        List<String> magiskFound = new ArrayList<>();
        for (String path : MAGISK_PATHS) {
            if (new File(path).exists()) {
                magiskFound.add(path);
            }
        }
        result.put("rooted", !suFound.isEmpty() || !magiskFound.isEmpty());
        result.put("suBinaries", suFound);
        result.put("magisk", magiskFound);
        result.put("note", "local probe from a normal app process; shell-probe 'Permission denied' "
                + "lines count as su evidence, the Launcher (uid 1000) result is authoritative");
        return result;
    }

    /** Run a shell command and return up to 4 KB of its output. */
    private static String exec(String command) {
        try {
            Process process = new ProcessBuilder("sh", "-c", command).start();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));
            StringBuilder builder = new StringBuilder();
            String line;
            while (builder.length() < 4096 && (line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            process.destroy();
            return builder.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Local cell info probe: TelephonyManager.getAllCellInfo() from this
     * app. On this ROM the dangerous ACCESS_FINE_LOCATION permission is
     * required, so a SecurityException is the expected honest result unless
     * the permission was granted.
     */
    public static Map<Object, Object> getCellInfoLocal(Context context) {
        Map<Object, Object> result = new LinkedHashMap<>();
        try {
            TelephonyManager tm =
                    (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            List<CellInfo> infos = tm.getAllCellInfo();
            result.put("granted", true);
            result.put("count", infos == null ? 0 : infos.size());
            List<String> types = new ArrayList<>();
            if (infos != null) {
                for (CellInfo info : infos) {
                    types.add(info.getClass().getSimpleName());
                }
            }
            result.put("types", types);
        } catch (SecurityException e) {
            result.put("granted", false);
            result.put("error", "SecurityException: " + e.getMessage());
            result.put("note", "ACCESS_FINE_LOCATION / READ_PHONE_STATE are required for getAllCellInfo");
        } catch (Exception e) {
            result.put("granted", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return result;
    }

    /**
     * Local SIM contacts probe: content://icc/adn from this app. READ_CONTACTS
     * is required by the IccProvider, so a SecurityException is the expected
     * honest result for a non-system-uid app.
     */
    public static Map<Object, Object> getSimContactsLocal(Context context) {
        Map<Object, Object> result = new LinkedHashMap<>();
        try {
            Cursor cursor = context.getContentResolver().query(
                    Uri.parse(SIM_CONTACTS_URI), null, null, null, null);
            int count = cursor == null ? 0 : cursor.getCount();
            if (cursor != null) {
                cursor.close();
            }
            result.put("granted", true);
            result.put("contactsCount", count);
        } catch (SecurityException e) {
            result.put("granted", false);
            result.put("error", "SecurityException: " + e.getMessage());
            result.put("note", "READ_CONTACTS is required for content://icc/adn");
        } catch (Exception e) {
            result.put("granted", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return result;
    }

    /**
     * Local WebView provider probe: read Settings.Global
     * webview_provider_default and scan the installed packages whose name
     * contains "webview" from this app. Note: WebView packages are hidden
     * from non-system apps by package visibility rules, so this probe may
     * see fewer providers than the Launcher (uid 1000); the raw result is
     * reported honestly and the Launcher result is authoritative.
     */
    public static Map<Object, Object> getWebViewInfoLocal(Context context) {
        Map<Object, Object> result = new LinkedHashMap<>();
        String setting = Settings.Global.getString(
                context.getContentResolver(), "webview_provider_default");
        result.put("webviewProviderDefault", setting == null ? "" : setting);
        PackageManager pm = context.getPackageManager();
        List<Map<String, Object>> providers = new ArrayList<>();
        List<String> candidates = new ArrayList<>();
        try {
            for (android.content.pm.ApplicationInfo app :
                    pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS
                            | PackageManager.MATCH_UNINSTALLED_PACKAGES)) {
                if (app.packageName.toLowerCase(Locale.US).contains("webview")
                        && !candidates.contains(app.packageName)) {
                    candidates.add(app.packageName);
                }
            }
        } catch (Exception ignored) {
        }
        for (String candidate : candidates) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("packageName", candidate);
            try {
                PackageInfo info = pm.getPackageInfo(candidate, 0);
                entry.put("installed", true);
                entry.put("versionName", info.versionName == null ? "" : info.versionName);
                entry.put("versionCode", info.getLongVersionCode());
            } catch (PackageManager.NameNotFoundException e) {
                entry.put("installed", false);
            }
            providers.add(entry);
        }
        result.put("providers", providers);
        result.put("note", "WebView packages may be hidden from non-system apps by package visibility; "
                + "the Launcher (uid 1000) result is authoritative");
        return result;
    }

    /**
     * Local user list probe: UserManager.getUsers() from this app (works for
     * any app on API 33 through reflection).
     */
    public static Map<Object, Object> getUserListLocal(Context context) {
        Map<Object, Object> result = new LinkedHashMap<>();
        try {
            android.os.UserManager userManager =
                    (android.os.UserManager) context.getSystemService(Context.USER_SERVICE);
            java.lang.reflect.Method getUsers = userManager.getClass().getMethod("getUsers");
            Object infos = getUsers.invoke(userManager);
            List<Map<String, Object>> users = new ArrayList<>();
            if (infos instanceof List) {
                for (Object info : (List<?>) infos) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", userIdOf(info));
                    entry.put("name", String.valueOf(fieldOrMethod(info, "name", "getName")));
                    entry.put("flags", flagsOf(info));
                    users.add(entry);
                }
            }
            result.put("count", users.size());
            result.put("users", users);
        } catch (Exception e) {
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return result;
    }

    private static int userIdOf(Object info) {
        try {
            return info.getClass().getField("id").getInt(info);
        } catch (Exception ignored) {
        }
        try {
            return ((Number) info.getClass().getMethod("getId").invoke(info)).intValue();
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static int flagsOf(Object info) {
        try {
            return info.getClass().getField("flags").getInt(info);
        } catch (Exception ignored) {
        }
        try {
            return ((Number) info.getClass().getMethod("getFlags").invoke(info)).intValue();
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static Object fieldOrMethod(Object target, String field, String method) {
        try {
            return target.getClass().getField(field).get(target);
        } catch (Exception ignored) {
        }
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Exception ignored) {
        }
        return "";
    }
}
