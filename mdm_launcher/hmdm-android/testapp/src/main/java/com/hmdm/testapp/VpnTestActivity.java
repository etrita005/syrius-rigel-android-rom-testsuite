package com.hmdm.testapp;

import android.os.Bundle;

import java.util.HashMap;
import java.util.Map;

/**
 * VPN control tests (ASR-0214 disable/enable, ASR-0215 configure,
 * ASR-0216 delete, ASR-0217 list, ASR-0218 disconnect): profile and
 * connection commands via the Launcher API plus local probes for the
 * settings entry visibility and the VPN networks.
 */
public class VpnTestActivity extends BaseTestActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vpn_test);
        bindLogView();

        findViewById(R.id.btn_vpn_add_pptp).setOnClickListener(v -> onAddProfile("pptp"));
        findViewById(R.id.btn_vpn_add_l2tp).setOnClickListener(v -> onAddProfile("l2tp_ipsec_psk"));
        findViewById(R.id.btn_vpn_add_ipsec).setOnClickListener(v -> onAddProfile("ipsec_xauth_psk"));
        findViewById(R.id.btn_vpn_list).setOnClickListener(v -> runAction("GetVpnProfileList", new HashMap<String, Object>()));
        findViewById(R.id.btn_vpn_delete).setOnClickListener(v -> onDeleteProfile());
        findViewById(R.id.btn_vpn_start).setOnClickListener(v -> onStartProfile());
        findViewById(R.id.btn_vpn_disconnect).setOnClickListener(v -> runAction("DisconnectVpn", new HashMap<String, Object>()));
        findViewById(R.id.btn_vpn_disabled).setOnClickListener(v -> onSetDisabled(true));
        findViewById(R.id.btn_vpn_enabled).setOnClickListener(v -> onSetDisabled(false));
        findViewById(R.id.btn_vpn_query).setOnClickListener(v -> runAction("IsVpnDisabled", new HashMap<String, Object>()));
        findViewById(R.id.btn_vpn_try_open_settings).setOnClickListener(v -> runAction("TryOpenVpnSettings", new HashMap<String, Object>()));
        findViewById(R.id.btn_vpn_check_networks).setOnClickListener(v -> runAction("CheckVpnNetworks", new HashMap<String, Object>()));
    }

    private void onAddProfile(String type) {
        Map<String, Object> param = new HashMap<>();
        param.put("name", "test_vpn");
        param.put("type", type);
        param.put("server", "10.10.10.10");
        param.put("username", "mdm_user");
        param.put("password", "mdm_pass");
        param.put("dnsServers", "8.8.8.8");
        runAction("AddVpnProfile", param);
    }

    private void onDeleteProfile() {
        Map<String, Object> param = new HashMap<>();
        param.put("name", "test_vpn");
        runAction("DeleteVpnProfile", param);
    }

    private void onStartProfile() {
        Map<String, Object> param = new HashMap<>();
        param.put("name", "test_vpn");
        runAction("StartVpnProfile", param);
    }

    private void onSetDisabled(boolean disabled) {
        Map<String, Object> param = new HashMap<>();
        param.put("disabled", disabled);
        runAction("SetVpnDisabled", param);
    }
}
