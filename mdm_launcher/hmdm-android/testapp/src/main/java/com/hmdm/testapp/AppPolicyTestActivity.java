package com.hmdm.testapp;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * App install/uninstall policy tests: uninstall whitelist/blacklist
 * (ASR-0006/0007), install blacklist reverse mode (ASR-0010), keep-alive
 * switch (ASR-0015), app alive detection (ASR-0029), MANAGE_EXTERNAL_STORAGE
 * grant (ASR-0040), desktop icon hide (ASR-0072), PackageInfo (ASR-0073).
 */
public class AppPolicyTestActivity extends BaseTestActivity {

    private EditText etPkg;
    private EditText etRegex;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_policy_test);
        bindLogView();

        etPkg = findViewById(R.id.et_pkg);
        etRegex = findViewById(R.id.et_regex);

        findViewById(R.id.btn_uninstall_whitelist_add).setOnClickListener(v ->
                modifyList("SetUninstallWhitelist", true));
        findViewById(R.id.btn_uninstall_whitelist_remove).setOnClickListener(v ->
                modifyList("SetUninstallWhitelist", false));
        findViewById(R.id.btn_uninstall_whitelist_get).setOnClickListener(v ->
                runAction("GetUninstallWhitelist", new HashMap<String, Object>()));
        findViewById(R.id.btn_uninstall_blacklist_add).setOnClickListener(v ->
                modifyList("SetUninstallBlacklist", true));
        findViewById(R.id.btn_uninstall_blacklist_remove).setOnClickListener(v ->
                modifyList("SetUninstallBlacklist", false));
        findViewById(R.id.btn_uninstall_blacklist_get).setOnClickListener(v ->
                runAction("GetUninstallBlacklist", new HashMap<String, Object>()));

        findViewById(R.id.btn_install_mode_whitelist).setOnClickListener(v ->
                setMode(1));
        findViewById(R.id.btn_install_mode_blacklist).setOnClickListener(v ->
                setMode(2));
        findViewById(R.id.btn_install_mode_off).setOnClickListener(v ->
                setMode(0));
        findViewById(R.id.btn_install_mode_get).setOnClickListener(v ->
                runAction("GetInstallPolicyMode", new HashMap<String, Object>()));
        findViewById(R.id.btn_install_blacklist_add).setOnClickListener(v ->
                modifyRegexes("SetInstallBlacklist", true));
        findViewById(R.id.btn_install_blacklist_remove).setOnClickListener(v ->
                modifyRegexes("SetInstallBlacklist", false));
        findViewById(R.id.btn_install_blacklist_get).setOnClickListener(v ->
                runAction("GetInstallBlacklist", new HashMap<String, Object>()));

        findViewById(R.id.btn_keep_alive_on).setOnClickListener(v ->
                setKeepAlive(true));
        findViewById(R.id.btn_keep_alive_off).setOnClickListener(v ->
                setKeepAlive(false));
        findViewById(R.id.btn_keep_alive_query).setOnClickListener(v -> {
            String pkg = etPkg.getText().toString().trim();
            if (TextUtils.isEmpty(pkg)) {
                appendLog("IsKeepAliveEnabled: missing parameter: packageName");
                return;
            }
            Map<String, Object> param = new HashMap<>();
            param.put("packageName", pkg);
            runAction("IsKeepAliveEnabled", param);
        });
        findViewById(R.id.btn_keep_alive_list).setOnClickListener(v ->
                runAction("GetKeepAliveList", new HashMap<String, Object>()));

        findViewById(R.id.btn_app_alive).setOnClickListener(v -> {
            String pkg = etPkg.getText().toString().trim();
            if (TextUtils.isEmpty(pkg)) {
                appendLog("IsAppAlive: missing parameter: packageName");
                return;
            }
            Map<String, Object> param = new HashMap<>();
            param.put("packageName", pkg);
            runAction("IsAppAlive", param);
        });

        findViewById(R.id.btn_manage_storage_grant).setOnClickListener(v ->
                setManageExternalStorage(true));
        findViewById(R.id.btn_manage_storage_revoke).setOnClickListener(v ->
                setManageExternalStorage(false));
        findViewById(R.id.btn_manage_storage_query).setOnClickListener(v -> {
            String pkg = etPkg.getText().toString().trim();
            if (TextUtils.isEmpty(pkg)) {
                appendLog("IsManageExternalStorageGranted: missing parameter: packageName");
                return;
            }
            Map<String, Object> param = new HashMap<>();
            param.put("packageName", pkg);
            runAction("IsManageExternalStorageGranted", param);
        });

        findViewById(R.id.btn_icon_hide).setOnClickListener(v ->
                setIconHidden(true));
        findViewById(R.id.btn_icon_show).setOnClickListener(v ->
                setIconHidden(false));
        findViewById(R.id.btn_icon_query).setOnClickListener(v -> {
            String pkg = etPkg.getText().toString().trim();
            if (TextUtils.isEmpty(pkg)) {
                appendLog("IsDesktopIconHidden: missing parameter: packageName");
                return;
            }
            Map<String, Object> param = new HashMap<>();
            param.put("packageName", pkg);
            runAction("IsDesktopIconHidden", param);
        });

        findViewById(R.id.btn_package_info).setOnClickListener(v -> {
            String pkg = etPkg.getText().toString().trim();
            if (TextUtils.isEmpty(pkg)) {
                appendLog("GetPackageInfo: missing parameter: packageName");
                return;
            }
            Map<String, Object> param = new HashMap<>();
            param.put("packageName", pkg);
            runAction("GetPackageInfo", param);
        });
    }

    private void modifyList(String event, boolean add) {
        String pkg = etPkg.getText().toString().trim();
        if (TextUtils.isEmpty(pkg)) {
            appendLog(event + ": missing parameter: packageName");
            return;
        }
        Map<Object, Object> current = apiCall(event.startsWith("SetUninstallWhitelist")
                ? "GetUninstallWhitelist" : "GetUninstallBlacklist", new HashMap<String, Object>());
        List<String> list = new ArrayList<>();
        Object result = current.get("RESULT");
        if (result instanceof Map) {
            Object rawList = ((Map<?, ?>) result).get("list");
            if (rawList instanceof List) {
                for (Object entry : (List<?>) rawList) {
                    if (entry instanceof Map && ((Map<?, ?>) entry).get("packageName") != null) {
                        list.add(String.valueOf(((Map<?, ?>) entry).get("packageName")));
                    }
                }
            }
        }
        if (add) {
            if (!list.contains(pkg)) {
                list.add(pkg);
            }
        } else {
            list.remove(pkg);
        }
        Map<String, Object> param = new HashMap<>();
        param.put("packageNames", list);
        runAction(event, param);
    }

    private void modifyRegexes(String event, boolean add) {
        String regex = etRegex.getText().toString().trim();
        if (TextUtils.isEmpty(regex)) {
            appendLog(event + ": missing parameter: regex");
            return;
        }
        Map<Object, Object> current = apiCall("GetInstallBlacklist", new HashMap<String, Object>());
        List<String> list = new ArrayList<>();
        Object result = current.get("RESULT");
        if (result instanceof List) {
            for (Object entry : (List<?>) result) {
                list.add(String.valueOf(entry));
            }
        }
        if (add) {
            if (!list.contains(regex)) {
                list.add(regex);
            }
        } else {
            list.remove(regex);
        }
        Map<String, Object> param = new HashMap<>();
        param.put("patterns", list);
        runAction(event, param);
    }

    private void setMode(int mode) {
        Map<String, Object> param = new HashMap<>();
        param.put("mode", mode);
        runAction("SetInstallPolicyMode", param);
    }

    private void setKeepAlive(boolean enabled) {
        String pkg = etPkg.getText().toString().trim();
        if (TextUtils.isEmpty(pkg)) {
            appendLog("SetKeepAliveEnabled: missing parameter: packageName");
            return;
        }
        Map<String, Object> param = new HashMap<>();
        param.put("packageName", pkg);
        param.put("enabled", enabled);
        runAction("SetKeepAliveEnabled", param);
    }

    private void setManageExternalStorage(boolean granted) {
        String pkg = etPkg.getText().toString().trim();
        if (TextUtils.isEmpty(pkg)) {
            appendLog("SetManageExternalStorageGranted: missing parameter: packageName");
            return;
        }
        Map<String, Object> param = new HashMap<>();
        param.put("packageName", pkg);
        param.put("granted", granted);
        runAction("SetManageExternalStorageGranted", param);
    }

    private void setIconHidden(boolean hidden) {
        String pkg = etPkg.getText().toString().trim();
        if (TextUtils.isEmpty(pkg)) {
            appendLog("SetDesktopIconHidden: missing parameter: packageName");
            return;
        }
        Map<String, Object> param = new HashMap<>();
        param.put("packageName", pkg);
        param.put("hidden", hidden);
        runAction("SetDesktopIconHidden", param);
    }
}
