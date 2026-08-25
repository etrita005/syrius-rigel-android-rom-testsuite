package com.hmdm.testapp;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Network access control tests: domain black/whitelist (ASR-0135),
 * IP black/whitelist (ASR-0136), firewall status, and real network
 * verification (DNS lookup / TCP connect / HTTP GET through the VPN).
 */
public class NetworkTestActivity extends BaseTestActivity {

    private EditText etDomainsWhitelist;
    private EditText etDomainsBlacklist;
    private EditText etDomainMode;
    private EditText etIpsWhitelist;
    private EditText etIpsBlacklist;
    private EditText etIpMode;
    private EditText etHost;
    private EditText etTcpHost;
    private EditText etTcpPort;
    private EditText etUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_network_test);
        bindLogView();

        etDomainsWhitelist = findViewById(R.id.et_domains_whitelist);
        etDomainsBlacklist = findViewById(R.id.et_domains_blacklist);
        etDomainMode = findViewById(R.id.et_domain_mode);
        etIpsWhitelist = findViewById(R.id.et_ips_whitelist);
        etIpsBlacklist = findViewById(R.id.et_ips_blacklist);
        etIpMode = findViewById(R.id.et_ip_mode);
        etHost = findViewById(R.id.et_host);
        etTcpHost = findViewById(R.id.et_tcp_host);
        etTcpPort = findViewById(R.id.et_tcp_port);
        etUrl = findViewById(R.id.et_url);

        findViewById(R.id.btn_set_domain_whitelist).setOnClickListener(v -> onSetDomains(true));
        findViewById(R.id.btn_get_domain_whitelist).setOnClickListener(v ->
                runAction("GetDomainWhitelist", new HashMap<String, Object>()));
        findViewById(R.id.btn_set_domain_blacklist).setOnClickListener(v -> onSetDomains(false));
        findViewById(R.id.btn_get_domain_blacklist).setOnClickListener(v ->
                runAction("GetDomainBlacklist", new HashMap<String, Object>()));
        findViewById(R.id.btn_set_domain_mode).setOnClickListener(v -> onSetDomainMode());
        findViewById(R.id.btn_get_domain_mode).setOnClickListener(v ->
                runAction("GetDomainPolicyMode", new HashMap<String, Object>()));

        findViewById(R.id.btn_set_ip_whitelist).setOnClickListener(v -> onSetIps(true));
        findViewById(R.id.btn_get_ip_whitelist).setOnClickListener(v ->
                runAction("GetIpWhitelist", new HashMap<String, Object>()));
        findViewById(R.id.btn_set_ip_blacklist).setOnClickListener(v -> onSetIps(false));
        findViewById(R.id.btn_get_ip_blacklist).setOnClickListener(v ->
                runAction("GetIpBlacklist", new HashMap<String, Object>()));
        findViewById(R.id.btn_set_ip_mode).setOnClickListener(v -> onSetIpMode());
        findViewById(R.id.btn_get_ip_mode).setOnClickListener(v ->
                runAction("GetIpPolicyMode", new HashMap<String, Object>()));

        findViewById(R.id.btn_firewall_status).setOnClickListener(v ->
                runAction("GetNetworkFirewallStatus", new HashMap<String, Object>()));

        findViewById(R.id.btn_dns_lookup).setOnClickListener(v -> onDnsLookup());
        findViewById(R.id.btn_tcp_connect).setOnClickListener(v -> onTcpConnect());
        findViewById(R.id.btn_http_get).setOnClickListener(v -> onHttpGet());
    }

    private void onSetDomains(boolean whitelist) {
        String text = (whitelist ? etDomainsWhitelist : etDomainsBlacklist).getText().toString().trim();
        Map<String, Object> param = new HashMap<>();
        param.put("domains", parseList(text));
        runAction(whitelist ? "SetDomainWhitelist" : "SetDomainBlacklist", param);
    }

    private void onSetDomainMode() {
        int mode = parseMode(etDomainMode.getText().toString());
        if (mode < 0) {
            toast("Please enter a valid policy mode 0/1/2");
            return;
        }
        Map<String, Object> param = new HashMap<>();
        param.put("mode", mode);
        runAction("SetDomainPolicyMode", param);
    }

    private void onSetIps(boolean whitelist) {
        String text = (whitelist ? etIpsWhitelist : etIpsBlacklist).getText().toString().trim();
        Map<String, Object> param = new HashMap<>();
        param.put("ips", parseList(text));
        runAction(whitelist ? "SetIpWhitelist" : "SetIpBlacklist", param);
    }

    private void onSetIpMode() {
        int mode = parseMode(etIpMode.getText().toString());
        if (mode < 0) {
            toast("Please enter a valid policy mode 0/1/2");
            return;
        }
        Map<String, Object> param = new HashMap<>();
        param.put("mode", mode);
        runAction("SetIpPolicyMode", param);
    }

    private void onDnsLookup() {
        String host = etHost.getText().toString().trim();
        if (TextUtils.isEmpty(host)) {
            toast("Please enter a host");
            return;
        }
        Map<String, Object> param = new HashMap<>();
        param.put("host", host);
        runAction("TestDnsLookup", param);
    }

    private void onTcpConnect() {
        String host = etTcpHost.getText().toString().trim();
        if (TextUtils.isEmpty(host)) {
            toast("Please enter a host");
            return;
        }
        String port = etTcpPort.getText().toString().trim();
        Map<String, Object> param = new HashMap<>();
        param.put("host", host);
        if (!TextUtils.isEmpty(port)) {
            param.put("port", port);
        }
        runAction("TestTcpConnect", param);
    }

    private void onHttpGet() {
        String url = etUrl.getText().toString().trim();
        if (TextUtils.isEmpty(url)) {
            toast("Please enter a URL");
            return;
        }
        Map<String, Object> param = new HashMap<>();
        param.put("url", url);
        runAction("TestHttpGet", param);
    }

    private List<String> parseList(String text) {
        List<String> list = new ArrayList<>();
        if (TextUtils.isEmpty(text)) {
            return list;
        }
        String[] parts = text.split("[,，]");
        for (String part : parts) {
            String item = part.trim();
            if (!item.isEmpty()) {
                list.add(item);
            }
        }
        return list;
    }

    private int parseMode(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return -1;
        }
    }
}
