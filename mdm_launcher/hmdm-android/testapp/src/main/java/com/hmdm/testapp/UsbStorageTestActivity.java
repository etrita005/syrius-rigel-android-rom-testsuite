package com.hmdm.testapp;

import android.os.Bundle;

import java.util.HashMap;

/**
 * USB / storage / SIM policy tests: USB data transfer lock (ASR-0191), USB
 * external storage lock (ASR-0196), data roaming disable (ASR-0273) and SD
 * card mount lock (ASR-0325).
 */
public class UsbStorageTestActivity extends BaseTestActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_usb_storage_test);
        bindLogView();

        findViewById(R.id.btn_usb_data_disable).setOnClickListener(v ->
                setDisabled("SetUsbDataTransferDisabled", true));
        findViewById(R.id.btn_usb_data_enable).setOnClickListener(v ->
                setDisabled("SetUsbDataTransferDisabled", false));
        findViewById(R.id.btn_usb_data_query).setOnClickListener(v ->
                runAction("IsUsbDataTransferDisabled", new HashMap<String, Object>()));

        findViewById(R.id.btn_usb_ext_disable).setOnClickListener(v ->
                setDisabled("SetUsbExternalStorageDisabled", true));
        findViewById(R.id.btn_usb_ext_enable).setOnClickListener(v ->
                setDisabled("SetUsbExternalStorageDisabled", false));
        findViewById(R.id.btn_usb_ext_query).setOnClickListener(v ->
                runAction("IsUsbExternalStorageDisabled", new HashMap<String, Object>()));

        findViewById(R.id.btn_roaming_disable).setOnClickListener(v ->
                setDisabled("SetDataRoamingDisabled", true));
        findViewById(R.id.btn_roaming_enable).setOnClickListener(v ->
                setDisabled("SetDataRoamingDisabled", false));
        findViewById(R.id.btn_roaming_query).setOnClickListener(v ->
                runAction("IsDataRoamingDisabled", new HashMap<String, Object>()));

        findViewById(R.id.btn_sd_disable).setOnClickListener(v ->
                setDisabled("SetSdCardMountDisabled", true));
        findViewById(R.id.btn_sd_enable).setOnClickListener(v ->
                setDisabled("SetSdCardMountDisabled", false));
        findViewById(R.id.btn_sd_query).setOnClickListener(v ->
                runAction("IsSdCardMountDisabled", new HashMap<String, Object>()));
    }

    private void setDisabled(String event, boolean disabled) {
        HashMap<String, Object> param = new HashMap<>();
        param.put("disabled", disabled);
        runAction(event, param);
    }
}
