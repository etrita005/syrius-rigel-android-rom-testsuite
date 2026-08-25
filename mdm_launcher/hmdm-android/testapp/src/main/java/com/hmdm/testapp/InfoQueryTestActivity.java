package com.hmdm.testapp;

import android.os.Bundle;
import android.widget.EditText;

import java.util.HashMap;
import java.util.Map;

/**
 * Device info query tests (ASR-0108 file attribute, ASR-0110 root status,
 * ASR-0219 VPN service status, ASR-0262 number attribution, ASR-0265 cell
 * id, ASR-0290 SIM contacts, ASR-0387 user list, ASR-0442 WebView provider):
 * command execution via the Launcher API plus local probes for independent
 * cross checks.
 */
public class InfoQueryTestActivity extends BaseTestActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_info_query_test);
        bindLogView();

        EditText etFilePath = findViewById(R.id.et_file_path);
        EditText etNumber = findViewById(R.id.et_number);

        findViewById(R.id.btn_get_file_attribute).setOnClickListener(v -> onGetFileAttribute(etFilePath, false));
        findViewById(R.id.btn_get_file_attribute_list).setOnClickListener(v -> onGetFileAttribute(etFilePath, true));
        findViewById(R.id.btn_check_root_status).setOnClickListener(v -> runAction("CheckRootStatus", new HashMap<String, Object>()));
        findViewById(R.id.btn_check_root_status_local).setOnClickListener(v -> runAction("CheckRootStatusLocal", new HashMap<String, Object>()));
        findViewById(R.id.btn_get_vpn_status).setOnClickListener(v -> runAction("GetVpnStatus", new HashMap<String, Object>()));
        findViewById(R.id.btn_query_number_attribution).setOnClickListener(v -> onQueryNumberAttribution(etNumber));
        findViewById(R.id.btn_get_cell_info).setOnClickListener(v -> runAction("GetCellInfo", new HashMap<String, Object>()));
        findViewById(R.id.btn_get_cell_info_local).setOnClickListener(v -> runAction("GetCellInfoLocal", new HashMap<String, Object>()));
        findViewById(R.id.btn_get_sim_contacts).setOnClickListener(v -> runAction("GetSimContacts", new HashMap<String, Object>()));
        findViewById(R.id.btn_get_sim_contacts_local).setOnClickListener(v -> runAction("GetSimContactsLocal", new HashMap<String, Object>()));
        findViewById(R.id.btn_list_users).setOnClickListener(v -> runAction("GetUserList", new HashMap<String, Object>()));
        findViewById(R.id.btn_list_users_local).setOnClickListener(v -> runAction("GetUserListLocal", new HashMap<String, Object>()));
        findViewById(R.id.btn_get_webview_info).setOnClickListener(v -> runAction("GetWebViewInfo", new HashMap<String, Object>()));
        findViewById(R.id.btn_get_webview_info_local).setOnClickListener(v -> runAction("GetWebViewInfoLocal", new HashMap<String, Object>()));
    }

    private void onGetFileAttribute(EditText input, boolean list) {
        String path = input.getText().toString().trim();
        if (path.isEmpty()) {
            path = "/sdcard/MDM";
            input.setText(path);
        }
        Map<String, Object> param = new HashMap<>();
        param.put("path", path);
        param.put("list", list);
        runAction("GetFileAttribute", param);
    }

    private void onQueryNumberAttribution(EditText input) {
        String number = input.getText().toString().trim();
        if (number.isEmpty()) {
            number = "13910001234";
            input.setText(number);
        }
        Map<String, Object> param = new HashMap<>();
        param.put("number", number);
        runAction("QueryNumberAttribution", param);
    }
}
