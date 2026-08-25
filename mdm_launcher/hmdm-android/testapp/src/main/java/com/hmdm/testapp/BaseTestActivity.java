package com.hmdm.testapp;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Map;

/**
 * Common plumbing for the feature test activities: result log view, toast and
 * the runAction() bridge into the shared TestActions engine.
 */
public abstract class BaseTestActivity extends Activity {

    private TextView tvLog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    protected void bindLogView() {
        tvLog = findViewById(R.id.tv_log);
    }

    /**
     * Execute a test action through the shared engine and append the result.
     * This is the same engine the adb shell IPC (TestCommandReceiver) uses.
     */
    protected Map<Object, Object> runAction(String event, Map<String, Object> param) {
        Map<Object, Object> result = TestActions.execute(this, event, param);
        appendLog(event + " RESULT: " + result.get(TestActions.RESULT_KEY));
        return result;
    }

    /**
     * Call the Launcher API directly without logging the raw result
     * (used to read the current policy state before mutating it).
     */
    protected Map<Object, Object> apiCall(String event, Map<String, Object> param) {
        MdmApiClient client = ApiHolder.get(this);
        client.waitForBound(3000);
        return client.call(event, param);
    }

    protected void appendLog(String msg) {
        if (tvLog == null) {
            return;
        }
        String existing = tvLog.getText().toString();
        if (existing.length() > 20000) {
            existing = existing.substring(existing.length() - 20000);
        }
        tvLog.setText(existing + "\n" + msg);
    }

    protected void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
