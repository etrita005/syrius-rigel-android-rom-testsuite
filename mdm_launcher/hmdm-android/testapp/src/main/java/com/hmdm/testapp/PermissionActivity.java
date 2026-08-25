package com.hmdm.testapp;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Requests one or more runtime permissions passed via the "permissions" extra
 * (comma-separated) and logs the result. Used from adb shell:
 * <pre>
 *   adb shell am start -n com.hmdm.testapp/.PermissionActivity \
 *       --es permissions POST_NOTIFICATIONS,CAMERA,RECORD_AUDIO
 * </pre>
 */
public class PermissionActivity extends Activity {

    private static final String LOG_TAG = "HYX-TESTAPP-CMD";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String perms = getIntent().getStringExtra("permissions");
        if (TextUtils.isEmpty(perms)) {
            Log.i(LOG_TAG, "PermissionActivity: missing 'permissions' extra");
            finish();
            return;
        }
        List<String> needed = new ArrayList<>();
        for (String p : perms.split(",")) {
            p = p.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) {
                Log.i(LOG_TAG, "PermissionActivity: already granted " + p);
            } else {
                needed.add(p);
            }
        }
        if (needed.isEmpty()) {
            Log.i(LOG_TAG, "PermissionActivity: all requested permissions already granted");
            finish();
            return;
        }
        Log.i(LOG_TAG, "PermissionActivity: requesting " + needed);
        requestPermissions(needed.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        StringBuilder sb = new StringBuilder("PermissionActivity results:");
        for (int i = 0; i < permissions.length; i++) {
            sb.append(' ').append(permissions[i]).append('=').append(grantResults[i]);
        }
        Log.i(LOG_TAG, sb.toString());
        finish();
    }
}
