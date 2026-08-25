package com.hmdm.testapp;

import android.os.Bundle;
import android.widget.EditText;

import java.util.HashMap;
import java.util.Map;

/**
 * Wired NIC configuration tests (ASR-0424): DHCP / static IP / DNS config,
 * per-interface selection, stack enable/disable and queries.
 */
public class EthernetTestActivity extends BaseTestActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ethernet_test);
        bindLogView();

        findViewById(R.id.btn_ethernet_dhcp).setOnClickListener(v -> {
            Map<String, Object> param = baseParams();
            param.put("mode", "dhcp");
            runAction("SetEthernetConfig", param);
        });
        findViewById(R.id.btn_ethernet_static).setOnClickListener(v -> {
            Map<String, Object> param = baseParams();
            param.put("mode", "static");
            param.put("ipAddress", text(R.id.et_ethernet_ip));
            param.put("prefixLength", text(R.id.et_ethernet_prefix));
            param.put("gateway", text(R.id.et_ethernet_gateway));
            param.put("dns1", text(R.id.et_ethernet_dns1));
            param.put("dns2", text(R.id.et_ethernet_dns2));
            runAction("SetEthernetConfig", param);
        });
        findViewById(R.id.btn_ethernet_get).setOnClickListener(v ->
                runAction("GetEthernetConfig", baseParams()));

        findViewById(R.id.btn_ethernet_enable).setOnClickListener(v ->
                setEnabled("SetEthernetEnabled", true));
        findViewById(R.id.btn_ethernet_disable).setOnClickListener(v ->
                setEnabled("SetEthernetEnabled", false));
        findViewById(R.id.btn_ethernet_is_enabled).setOnClickListener(v ->
                runAction("IsEthernetEnabled", new HashMap<String, Object>()));
    }

    private Map<String, Object> baseParams() {
        Map<String, Object> param = new HashMap<>();
        String iface = text(R.id.et_ethernet_iface);
        if (!iface.isEmpty()) {
            param.put("iface", iface);
        }
        return param;
    }

    private void setEnabled(String event, boolean enabled) {
        Map<String, Object> param = new HashMap<>();
        param.put("enabled", enabled);
        runAction(event, param);
    }

    private String text(int viewId) {
        EditText input = findViewById(viewId);
        return input.getText() != null ? input.getText().toString().trim() : "";
    }
}
