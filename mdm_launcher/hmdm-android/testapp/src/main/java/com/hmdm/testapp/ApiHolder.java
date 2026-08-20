package com.hmdm.testapp;

import android.content.Context;

/**
 * Process-wide holder for the single MdmApiClient instance. Keeping the
 * connection bound for the whole process lifetime makes every command (UI or
 * IPC) fast, because there is no bind latency after the first use.
 */
public final class ApiHolder {

    private static MdmApiClient instance;

    private ApiHolder() {
    }

    public static synchronized MdmApiClient get(Context context) {
        if (instance == null) {
            instance = new MdmApiClient(context);
        }
        return instance;
    }
}
