package com.hmdm.testapp;

import android.os.Bundle;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wallpaper tests (ASR-0183 set home wallpaper, ASR-0184 set lock wallpaper):
 * generate a solid-color test image locally and set it through the Launcher
 * SetWallpaper API (target home/lock/both), query the wallpaper state and
 * cross-check it with a local WallpaperManager probe.
 *
 * <p>The actions run on a worker thread: image generation plus the
 * synchronous AIDL round trip can take seconds for large images.
 */
public class WallpaperTestActivity extends BaseTestActivity {

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wallpaper_test);
        bindLogView();

        findViewById(R.id.btn_wallpaper_home_red).setOnClickListener(
                v -> onSetColor("home", "red"));
        findViewById(R.id.btn_wallpaper_home_green).setOnClickListener(
                v -> onSetColor("home", "green"));
        findViewById(R.id.btn_wallpaper_lock_blue).setOnClickListener(
                v -> onSetColor("lock", "blue"));
        findViewById(R.id.btn_wallpaper_both_purple).setOnClickListener(
                v -> onSetColor("both", "purple"));
        findViewById(R.id.btn_wallpaper_query).setOnClickListener(
                v -> runActionAsync("GetWallpaper", new HashMap<String, Object>()));
        findViewById(R.id.btn_wallpaper_probe).setOnClickListener(
                v -> runActionAsync("GetWallpaperStateLocal", new HashMap<String, Object>()));
    }

    private void onSetColor(String target, String color) {
        Map<String, Object> param = new HashMap<>();
        param.put("target", target);
        param.put("color", color);
        runActionAsync("SetWallpaper", param);
    }

    /** Run the action off the main thread and append the result when done. */
    private void runActionAsync(String event, Map<String, Object> param) {
        worker.execute(() -> {
            Map<Object, Object> result = TestActions.execute(this, event, param);
            runOnUiThread(() -> appendLog(event + " RESULT: " + result.get(TestActions.RESULT_KEY)));
        });
    }
}
