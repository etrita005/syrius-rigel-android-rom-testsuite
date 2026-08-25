package com.hmdm.testapp;

import android.os.Bundle;

import java.util.HashMap;

/**
 * Volume / display lock tests: user volume setting lock (ASR-0390), volume
 * key lock (ASR-0391), media/notification/alarm volume modification locks
 * (ASR-0393/0395/0397), auto sleep switch (ASR-0412) and always fullscreen
 * (ASR-0431).
 */
public class VolumeDisplayTestActivity extends BaseTestActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_volume_display_test);
        bindLogView();

        findViewById(R.id.btn_user_volume_disable).setOnClickListener(v ->
                setDisabled("SetUserVolumeSettingDisabled", true));
        findViewById(R.id.btn_user_volume_enable).setOnClickListener(v ->
                setDisabled("SetUserVolumeSettingDisabled", false));
        findViewById(R.id.btn_user_volume_query).setOnClickListener(v ->
                runAction("IsUserVolumeSettingDisabled", new HashMap<String, Object>()));

        findViewById(R.id.btn_key_disable).setOnClickListener(v ->
                setDisabled("SetVolumeKeyDisabled", true));
        findViewById(R.id.btn_key_enable).setOnClickListener(v ->
                setDisabled("SetVolumeKeyDisabled", false));
        findViewById(R.id.btn_key_query).setOnClickListener(v ->
                runAction("IsVolumeKeyDisabled", new HashMap<String, Object>()));

        findViewById(R.id.btn_media_lock).setOnClickListener(v ->
                setLocked("SetMediaVolumeModificationLocked", true));
        findViewById(R.id.btn_media_unlock).setOnClickListener(v ->
                setLocked("SetMediaVolumeModificationLocked", false));
        findViewById(R.id.btn_media_query).setOnClickListener(v ->
                runAction("IsMediaVolumeModificationLocked", new HashMap<String, Object>()));

        findViewById(R.id.btn_notification_lock).setOnClickListener(v ->
                setLocked("SetNotificationVolumeModificationLocked", true));
        findViewById(R.id.btn_notification_unlock).setOnClickListener(v ->
                setLocked("SetNotificationVolumeModificationLocked", false));
        findViewById(R.id.btn_notification_query).setOnClickListener(v ->
                runAction("IsNotificationVolumeModificationLocked", new HashMap<String, Object>()));

        findViewById(R.id.btn_alarm_lock).setOnClickListener(v ->
                setLocked("SetAlarmVolumeModificationLocked", true));
        findViewById(R.id.btn_alarm_unlock).setOnClickListener(v ->
                setLocked("SetAlarmVolumeModificationLocked", false));
        findViewById(R.id.btn_alarm_query).setOnClickListener(v ->
                runAction("IsAlarmVolumeModificationLocked", new HashMap<String, Object>()));

        findViewById(R.id.btn_auto_sleep_disable).setOnClickListener(v ->
                setDisabled("SetAutoSleepDisabled", true));
        findViewById(R.id.btn_auto_sleep_enable).setOnClickListener(v ->
                setDisabled("SetAutoSleepDisabled", false));
        findViewById(R.id.btn_auto_sleep_query).setOnClickListener(v ->
                runAction("IsAutoSleepDisabled", new HashMap<String, Object>()));

        findViewById(R.id.btn_fullscreen_on).setOnClickListener(v ->
                setFullscreen(true));
        findViewById(R.id.btn_fullscreen_off).setOnClickListener(v ->
                setFullscreen(false));
        findViewById(R.id.btn_fullscreen_query).setOnClickListener(v ->
                runAction("IsAlwaysFullscreen", new HashMap<String, Object>()));
    }

    private void setDisabled(String event, boolean disabled) {
        HashMap<String, Object> param = new HashMap<>();
        param.put("disabled", disabled);
        runAction(event, param);
    }

    private void setLocked(String event, boolean locked) {
        HashMap<String, Object> param = new HashMap<>();
        param.put("locked", locked);
        runAction(event, param);
    }

    private void setFullscreen(boolean fullscreen) {
        HashMap<String, Object> param = new HashMap<>();
        param.put("fullscreen", fullscreen);
        runAction("SetAlwaysFullscreen", param);
    }
}
