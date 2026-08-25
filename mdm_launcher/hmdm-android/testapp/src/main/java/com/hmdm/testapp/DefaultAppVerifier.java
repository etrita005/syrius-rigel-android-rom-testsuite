package com.hmdm.testapp;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Local probes for the component / default-app batch (ASR-0019/0087/0089/0094).
 * <p>
 * TryStartComponent really launches an explicit activity component from this
 * app: while the component (or the whole app) is disabled by the Launcher the
 * framework answers with ActivityNotFoundException, which is the end-to-end
 * proof of ASR-0019. GetAssistantSetting reads the Settings.Secure
 * "assistant" key, which the framework's assistant role controller mirrors
 * from the ASSISTANT role holder (cross-check for ASR-0094).
 */
public final class DefaultAppVerifier {

    private DefaultAppVerifier() {
    }

    /**
     * Try to start an explicit activity component and report the outcome.
     * A disabled component yields ActivityNotFoundException (or a
     * SecurityException for non-exported targets, reported as-is).
     */
    public static Map<Object, Object> tryStartComponent(Context context, String component) {
        Map<Object, Object> result = new LinkedHashMap<>();
        ComponentName name = ComponentName.unflattenFromString(component);
        if (name == null) {
            result.put("started", false);
            result.put("error", "invalid component: " + component);
            return result;
        }
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(name);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
            result.put("started", true);
            result.put("component", name.flattenToShortString());
        } catch (Exception e) {
            result.put("started", false);
            result.put("component", name.flattenToShortString());
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return result;
    }

    /**
     * Resolve an explicit component through the PackageManager; a disabled
     * component resolves to null (cross-check for ASR-0019).
     */
    public static Map<Object, Object> resolveComponent(Context context, String component) {
        Map<Object, Object> result = new LinkedHashMap<>();
        ComponentName name = ComponentName.unflattenFromString(component);
        if (name == null) {
            result.put("resolved", false);
            result.put("error", "invalid component: " + component);
            return result;
        }
        try {
            PackageManager pm = context.getPackageManager();
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setComponent(name);
            boolean resolved = pm.resolveActivity(intent, 0) != null;
            result.put("resolved", resolved);
            result.put("component", name.flattenToShortString());
        } catch (Exception e) {
            result.put("resolved", false);
            result.put("component", name.flattenToShortString());
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return result;
    }

    /**
     * Read the Settings.Secure "assistant" key (the framework mirror of the
     * ASSISTANT role holder; cross-check for ASR-0094).
     */
    public static Map<Object, Object> getAssistantSetting(Context context) {
        Map<Object, Object> result = new LinkedHashMap<>();
        String value = Settings.Secure.getString(context.getContentResolver(), "assistant");
        result.put("assistant", value);
        return result;
    }
}
