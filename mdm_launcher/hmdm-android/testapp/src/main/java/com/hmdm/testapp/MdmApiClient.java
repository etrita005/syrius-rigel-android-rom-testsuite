package com.hmdm.testapp;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import syrius.mdm.mobile_operator.SystemApiInterface;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Client that binds to the Launcher (com.hmdm.launcher) ApiService,
 * with an event queue and retry on failure.
 */
public class MdmApiClient {

    private static final String TAG = "MdmApiClient";
    private static final String MDM_SERVICE_ACTION = "syrius.mdm.api_service";
    private static final String MDM_PACKAGE = "com.hmdm.launcher";
    private static final String MDM_SERVICE_CLASS = "com.hmdm.launcher.syrius.service.ApiService";

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<PendingCall> pendingCalls = new ArrayList<>();
    private final CountDownLatch boundLatch = new CountDownLatch(1);
    private SystemApiInterface systemApiInterface;
    private BindListener listener;

    public interface BindListener {
        void onBindStateChanged(boolean bound);
    }

    private static class PendingCall {
        final String event;
        final Map<String, Object> param;

        PendingCall(String event, Map<String, Object> param) {
            this.event = event;
            this.param = param;
        }
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i(TAG, "onServiceConnected: " + name + ", pending:" + pendingCalls.size());
            systemApiInterface = SystemApiInterface.Stub.asInterface(service);
            boundLatch.countDown();
            List<PendingCall> copy = new ArrayList<>(pendingCalls);
            pendingCalls.clear();
            for (PendingCall call : copy) {
                doCall(call.event, call.param);
            }
            if (listener != null) {
                listener.onBindStateChanged(true);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(TAG, "onServiceDisconnected: " + name);
            systemApiInterface = null;
            if (listener != null) {
                listener.onBindStateChanged(false);
            }
        }
    };

    public MdmApiClient(Context context) {
        this.context = context.getApplicationContext();
        tryBind();
    }

    public void setBindListener(BindListener listener) {
        this.listener = listener;
    }

    public boolean isBound() {
        return systemApiInterface != null;
    }

    /**
     * Block until the Launcher ApiService connection is established or the timeout
     * expires. Returns true if the connection is up when the wait finishes.
     */
    public boolean waitForBound(long timeoutMs) {
        if (systemApiInterface != null) {
            return true;
        }
        try {
            boundLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Log.e(TAG, "waitForBound interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
        }
        return systemApiInterface != null;
    }

    private void tryBind() {
        if (bind()) {
            Log.i(TAG, "bind success");
        } else {
            Log.i(TAG, "bind failed, retry after 3000ms");
            handler.postDelayed(this::tryBind, 3000);
        }
    }

    private boolean bind() {
        Intent intent = new Intent(MDM_SERVICE_ACTION);
        intent.setPackage(MDM_PACKAGE);
        intent.setComponent(new ComponentName(MDM_PACKAGE, MDM_SERVICE_CLASS));
        try {
            return context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            Log.e(TAG, "bind error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Invoke a command; the returned Map contains "RESULT". While disconnected the call is
     * queued and replayed once connected.
     */
    public Map<Object, Object> call(String event, Map<String, Object> param) {
        if (systemApiInterface == null) {
            pendingCalls.add(new PendingCall(event, param));
            return new HashMap<>();
        }
        return doCall(event, param);
    }

    private Map<Object, Object> doCall(String event, Map<String, Object> param) {
        try {
            Map<String, Object> map = param != null ? param : new HashMap<String, Object>();
            Map result = systemApiInterface.onEvent(event, map);
            Log.i(TAG, "call " + event + " result: " + result);
            return result != null ? result : new HashMap<>();
        } catch (Exception e) {
            Log.e(TAG, "call " + event + " error: " + e.getMessage());
            return new HashMap<>();
        }
    }

    public void unbind() {
        try {
            context.unbindService(connection);
        } catch (Exception e) {
            Log.e(TAG, "unbind error: " + e.getMessage());
        }
    }
}
