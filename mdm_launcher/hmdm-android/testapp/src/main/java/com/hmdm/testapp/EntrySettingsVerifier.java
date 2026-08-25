package com.hmdm.testapp;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Local probes for the entry / settings locking batch: resolve the same
 * settings pages and role-request intent from this app's process and read the
 * raw Settings values, as an independent cross-check of the Launcher commands.
 * Every field is captured defensively so a single failure never breaks the
 * whole probe.
 */
public final class EntrySettingsVerifier {

    private EntrySettingsVerifier() {
    }

    /** Resolve the settings intents that the Launcher page locks target. */
    public static Map<String, Object> resolveEntries(Context context) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("appPermissionPage", resolve(context, new Intent("android.settings.MANAGE_APP_PERMISSIONS")));
        result.put("appDetailsPage", resolveWithPackage(context, "android.settings.APPLICATION_DETAILS_SETTINGS", "com.android.settings"));
        result.put("allAppsPage", resolve(context, new Intent("android.settings.MANAGE_ALL_APPLICATIONS_SETTINGS")));
        result.put("notificationSettings", resolve(context, new Intent("android.settings.NOTIFICATION_SETTINGS")));
        result.put("appNotificationSettings", resolve(context, new Intent("android.settings.APP_NOTIFICATION_SETTINGS")));
        result.put("allAppsNotificationSettings", resolve(context, new Intent("android.settings.ALL_APPS_NOTIFICATION_SETTINGS")));
        result.put("accessibilitySettings", resolve(context, new Intent("android.settings.ACCESSIBILITY_SETTINGS")));
        result.put("apnSettings", resolve(context, new Intent("android.settings.APN_SETTINGS")));
        result.put("localeSettings", resolve(context, new Intent("android.settings.LOCALE_SETTINGS")));
        result.put("roleRequest", resolve(context, new Intent("android.app.role.action.REQUEST_ROLE")));
        result.put("componentStates", componentStates(context));
        return result;
    }

    /** Raw settings values used by the value locks (ASR-0203/0322/0064/0065/0057). */
    public static Map<String, Object> readValues(Context context) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("adbEnabled", Settings.Global.getInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, -1));
        result.put("airplaneModeOn", Settings.Global.getInt(context.getContentResolver(), "airplane_mode_on", -1));
        result.put("lockScreenShowNotifications",
                Settings.Secure.getInt(context.getContentResolver(), "lock_screen_show_notifications", -1));
        result.put("lockScreenAllowPrivateNotifications",
                Settings.Secure.getInt(context.getContentResolver(), "lock_screen_allow_private_notifications", -1));
        result.put("enabledAccessibilityServices",
                Settings.Secure.getString(context.getContentResolver(), "enabled_accessibility_services"));
        result.put("accessibilityEnabled",
                Settings.Secure.getInt(context.getContentResolver(), "accessibility_enabled", -1));
        return result;
    }

    /** Local audio state probe for ASR-0370. */
    public static Map<String, Object> readAudioState(Context context) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            Map<String, Integer> volumes = new LinkedHashMap<>();
            volumes.put("music", am.getStreamVolume(AudioManager.STREAM_MUSIC));
            volumes.put("ring", am.getStreamVolume(AudioManager.STREAM_RING));
            volumes.put("notification", am.getStreamVolume(AudioManager.STREAM_NOTIFICATION));
            volumes.put("alarm", am.getStreamVolume(AudioManager.STREAM_ALARM));
            volumes.put("system", am.getStreamVolume(AudioManager.STREAM_SYSTEM));
            volumes.put("voiceCall", am.getStreamVolume(AudioManager.STREAM_VOICE_CALL));
            result.put("volumes", volumes);
            try {
                result.put("speakerphoneOn", am.isSpeakerphoneOn());
            } catch (Exception e) {
                result.put("speakerphoneOn", null);
            }
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    private static String resolve(Context context, Intent intent) {
        try {
            intent.addCategory(Intent.CATEGORY_DEFAULT);
            android.content.pm.ResolveInfo info = context.getPackageManager()
                    .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (info == null) {
                return "unresolved";
            }
            if (info.activityInfo == null) {
                return info.toString();
            }
            return new ComponentName(info.activityInfo.packageName, info.activityInfo.name)
                    .flattenToShortString();
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    private static String resolveWithPackage(Context context, String action, String pkg) {
        try {
            Intent intent = new Intent(action, android.net.Uri.parse("package:" + pkg));
            intent.addCategory(Intent.CATEGORY_DEFAULT);
            android.content.pm.ResolveInfo info = context.getPackageManager()
                    .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (info == null || info.activityInfo == null) {
                return "unresolved";
            }
            return new ComponentName(info.activityInfo.packageName, info.activityInfo.name)
                    .flattenToShortString();
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    private static Map<String, Object> componentStates(Context context) {
        Map<String, Object> states = new LinkedHashMap<>();
        addComponentState(states, context, "com.android.permissioncontroller",
                "com.android.permissioncontroller.permission.ui.ManagePermissionsActivity");
        addComponentState(states, context, "com.android.permissioncontroller",
                "com.android.permissioncontroller.permission.ui.legacy.AppPermissionActivity");
        addComponentState(states, context, "com.android.permissioncontroller",
                "com.android.permissioncontroller.role.ui.RequestRoleActivity");
        addComponentState(states, context, "com.android.permissioncontroller",
                "com.android.permissioncontroller.role.ui.DefaultAppActivity");
        addComponentState(states, context, "com.android.settings",
                "com.android.settings.Settings$NotificationAppListActivity");
        addComponentState(states, context, "com.android.settings",
                "com.android.settings.Settings$AppNotificationSettingsActivity");
        addComponentState(states, context, "com.android.settings",
                "com.android.settings.Settings$ConfigureNotificationSettingsActivity");
        addComponentState(states, context, "com.android.settings",
                "com.android.settings.Settings$ManageApplicationsActivity");
        addComponentState(states, context, "com.android.settings",
                "com.android.settings.Settings$AccessibilitySettingsActivity");
        addComponentState(states, context, "com.android.settings",
                "com.android.settings.Settings$ManageAssistActivity");
        addComponentState(states, context, "com.android.settings",
                "com.android.settings.Settings$UsbDetailsActivity");
        addComponentState(states, context, "com.android.settings",
                "com.android.settings.Settings$ApnSettingsActivity");
        addComponentState(states, context, "com.android.settings",
                "com.android.settings.Settings$LocalePickerActivity");
        addComponentState(states, context, "com.android.settings",
                "com.android.settings.localepicker.LocalePickerWithRegionActivity");
        return states;
    }

    private static void addComponentState(Map<String, Object> states, Context context,
                                          String pkg, String cls) {
        ComponentName component = new ComponentName(pkg, cls);
        try {
            int state = context.getPackageManager().getComponentEnabledSetting(component);
            states.put(component.flattenToShortString(), state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED ? "enabled"
                    : state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ? "disabled" : "state_" + state);
        } catch (Exception e) {
            states.put(component.flattenToShortString(), "error");
        }
    }

    /** The installed-package list helper used by probes (name only). */
    public static List<String> installedPackages(Context context) {
        List<String> packages = new ArrayList<>();
        try {
            for (android.content.pm.ApplicationInfo app : context.getPackageManager()
                    .getInstalledApplications(0)) {
                packages.add(app.packageName);
            }
        } catch (Exception e) {
            packages.add("error: " + e.getMessage());
        }
        return packages;
    }
}
