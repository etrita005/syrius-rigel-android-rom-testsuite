package com.hmdm.testapp;

import android.content.Context;
import android.os.UserManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Local (in-testapp) probes for the data / storage / screenshot / user batch:
 * <ul>
 *   <li>data file probe written into the app's files dir, used to verify the
 *       ASR-0125 backup/restore round trip end to end;</li>
 *   <li>cache file probe written into the app's cache dir, used to verify the
 *       ASR-0127 cache clear end to end;</li>
 *   <li>own user list read through the public UserManager API, used as a
 *       cross-check for the ASR-0385/0386 commands.</li>
 * </ul>
 */
public final class StorageUserVerifier {

    public static final String DATA_PROBE_FILE = "probe.txt";
    public static final String CACHE_PROBE_FILE = "cache_probe.txt";

    private StorageUserVerifier() {
    }

    public static Map<String, Object> writeDataProbe(Context context, String value) {
        Map<String, Object> result = new LinkedHashMap<>();
        File file = new File(context.getFilesDir(), DATA_PROBE_FILE);
        try {
            FileOutputStream out = new FileOutputStream(file);
            out.write(value.getBytes("UTF-8"));
            out.flush();
            out.close();
            result.put("written", value);
            result.put("file", file.getAbsolutePath());
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    public static Map<String, Object> readDataProbe(Context context) {
        Map<String, Object> result = new LinkedHashMap<>();
        File file = new File(context.getFilesDir(), DATA_PROBE_FILE);
        if (!file.exists()) {
            result.put("exists", false);
            return result;
        }
        try {
            FileInputStream in = new FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            int read = in.read(data);
            in.close();
            result.put("exists", true);
            result.put("value", new String(data, 0, read, "UTF-8"));
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    public static Map<String, Object> writeCacheProbe(Context context, String value) {
        Map<String, Object> result = new LinkedHashMap<>();
        File file = new File(context.getCacheDir(), CACHE_PROBE_FILE);
        try {
            FileOutputStream out = new FileOutputStream(file);
            out.write(value.getBytes("UTF-8"));
            out.flush();
            out.close();
            result.put("written", value);
            result.put("file", file.getAbsolutePath());
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    public static Map<String, Object> checkCacheProbe(Context context) {
        Map<String, Object> result = new LinkedHashMap<>();
        File file = new File(context.getCacheDir(), CACHE_PROBE_FILE);
        result.put("exists", file.exists());
        if (file.exists()) {
            result.put("file", file.getAbsolutePath());
        }
        return result;
    }

    public static Map<String, Object> getUserListLocal(Context context) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
            Method getUsers = UserManager.class.getMethod("getUsers");
            List<?> infos = (List<?>) getUsers.invoke(userManager);
            List<Map<String, Object>> users = new ArrayList<>();
            if (infos != null) {
                for (Object info : infos) {
                    Class<?> clazz = info.getClass();
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", userIdOf(info));
                    Object name = fieldOrMethod(info, "name", "getName");
                    entry.put("name", name == null ? "" : String.valueOf(name));
                    Object flags = fieldOrMethod(info, "flags", "getFlags");
                    entry.put("flags", flags instanceof Number ? ((Number) flags).intValue() : 0);
                    users.add(entry);
                }
            }
            result.put("users", users);
            result.put("count", users.size());
        } catch (Exception e) {
            Throwable t = e instanceof java.lang.reflect.InvocationTargetException
                    && e.getCause() != null ? e.getCause() : e;
            result.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return result;
    }

    /**
     * Deliberately independent from the Launcher's reading logic: this probe
     * tries the getter methods first, then public fields, then UserHandle, so
     * a broken Launcher-side reflection path is not mirrored here.
     */
    private static int userIdOf(Object info) {
        try {
            return ((Number) info.getClass().getMethod("getId").invoke(info)).intValue();
        } catch (Exception ignored) {
        }
        try {
            return info.getClass().getField("id").getInt(info);
        } catch (Exception ignored) {
        }
        try {
            Object handle = info.getClass().getMethod("getUserHandle").invoke(info);
            return ((Number) handle.getClass().getMethod("getIdentifier").invoke(handle)).intValue();
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static Object fieldOrMethod(Object target, String field, String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Exception ignored) {
        }
        try {
            return target.getClass().getField(field).get(target);
        } catch (Exception ignored) {
        }
        return null;
    }
}
