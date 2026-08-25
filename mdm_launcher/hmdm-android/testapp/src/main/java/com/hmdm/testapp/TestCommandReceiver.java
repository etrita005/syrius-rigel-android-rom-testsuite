package com.hmdm.testapp;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * adb shell IPC entry point for this app. It executes the exact same
 * TestActions methods that the UI buttons call and returns the result in two
 * ways:
 * <pre>
 *   adb shell am broadcast -n com.hmdm.testapp/.TestCommandReceiver \
 *       -a com.hmdm.testapp.CMD --es event &lt;event&gt; --es param '&lt;json&gt;'
 * </pre>
 * 1. "Broadcast completed: result=0, data=&lt;json&gt;" printed by am broadcast
 * 2. a logcat line with tag HYX-TESTAPP-CMD
 */
public class TestCommandReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.hmdm.testapp.CMD";
    public static final String LOG_TAG = "HYX-TESTAPP-CMD";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(Context context, Intent intent) {
        final String event = intent.getStringExtra("event");
        final String paramJson = intent.getStringExtra("param");
        final PendingResult pendingResult = goAsync();
        EXECUTOR.execute(() -> {
            String output;
            try {
                Map<String, Object> param = parseParam(paramJson);
                Map<Object, Object> result = TestActions.execute(context.getApplicationContext(), event, param);
                output = new JSONObject(result).toString();
            } catch (Exception e) {
                output = "{\"error\":\"" + safe(e.getMessage() == null ? e.toString() : e.getMessage()) + "\"}";
            }
            Log.i(LOG_TAG, event + " => " + output);
            pendingResult.setResultCode(Activity.RESULT_OK);
            pendingResult.setResultData(output);
            pendingResult.finish();
        });
    }

    private static Map<String, Object> parseParam(String paramJson) throws Exception {
        Map<String, Object> param = new HashMap<>();
        if (paramJson == null || paramJson.trim().isEmpty()) {
            return param;
        }
        JSONObject obj = new JSONObject(paramJson);
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            param.put(key, unwrap(obj.get(key)));
        }
        return param;
    }

    private static Object unwrap(Object value) throws Exception {
        if (value instanceof JSONObject) {
            Map<String, Object> map = new HashMap<>();
            JSONObject obj = (JSONObject) value;
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                map.put(key, unwrap(obj.get(key)));
            }
            return map;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                list.add(unwrap(array.get(i)));
            }
            return list;
        }
        if (value == JSONObject.NULL) {
            return null;
        }
        return value;
    }

    private static String safe(String text) {
        return text.replace("\"", "'");
    }
}
