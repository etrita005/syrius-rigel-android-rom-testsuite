package com.hmdm.testapp;

import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Local wallpaper state probe (ASR-0183/0184 cross-check): reads the
 * WallpaperManager state from this app's own process — wallpaper ids,
 * framework color stats, lock wallpaper presence and the dominant color of
 * the actually applied content — independently of the Launcher commands.
 */
public class WallpaperVerifier {

    private static volatile Method lockWallpaperBitmapMethod;

    private WallpaperVerifier() {
    }

    public static Map<String, Object> getWallpaperState(Context context) {
        Map<String, Object> state = new LinkedHashMap<>();
        WallpaperManager wm = WallpaperManager.getInstance(context);
        if (wm == null) {
            state.put("error", "WallpaperManager unavailable");
            return state;
        }
        state.put("supported", wm.isWallpaperSupported());
        state.put("homeId", safeInt(() -> wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM)));
        state.put("lockId", safeInt(() -> wm.getWallpaperId(WallpaperManager.FLAG_LOCK)));
        state.put("homeColors", colorsOf(safeColors(
                () -> wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM))));
        state.put("lockColors", colorsOf(safeColors(
                () -> wm.getWallpaperColors(WallpaperManager.FLAG_LOCK))));
        Bitmap lock = getLockWallpaperBitmap(wm);
        state.put("lockSet", lock != null);
        state.put("lockDominantColor", dominantColor(lock));
        state.put("homeDominantColor", safeDrawableDominant(wm));
        return state;
    }

    /** getDrawable() may be permission-gated (e.g. READ_EXTERNAL_STORAGE) in this app. */
    private static Integer safeDrawableDominant(WallpaperManager wm) {
        try {
            return drawableDominant(wm.getDrawable());
        } catch (Exception e) {
            return null;
        }
    }

    private interface ColorSupplier {
        WallpaperColors get();
    }

    private interface IntSupplier {
        int get();
    }

    private static WallpaperColors safeColors(ColorSupplier supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer safeInt(IntSupplier supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * getLockWallpaperBitmap() is absent from the stripped SDK android.jar
     * of this project (javap verified) but present in the ROM framework.jar,
     * so it is called via reflection.
     */
    private static Bitmap getLockWallpaperBitmap(WallpaperManager wm) {
        try {
            if (lockWallpaperBitmapMethod == null) {
                lockWallpaperBitmapMethod = WallpaperManager.class.getMethod("getLockWallpaperBitmap");
            }
            return (Bitmap) lockWallpaperBitmapMethod.invoke(wm);
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> colorsOf(WallpaperColors colors) {
        if (colors == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("primaryColor", colors.getPrimaryColor() != null
                ? colors.getPrimaryColor().toArgb() : null);
        map.put("secondaryColor", colors.getSecondaryColor() != null
                ? colors.getSecondaryColor().toArgb() : null);
        map.put("tertiaryColor", colors.getTertiaryColor() != null
                ? colors.getTertiaryColor().toArgb() : null);
        return map;
    }

    private static Integer drawableDominant(Drawable drawable) {
        if (drawable == null) {
            return null;
        }
        try {
            if (drawable instanceof BitmapDrawable) {
                return dominantColor(((BitmapDrawable) drawable).getBitmap());
            }
            int width = Math.max(1, drawable.getIntrinsicWidth());
            int height = Math.max(1, drawable.getIntrinsicHeight());
            if (width > 2048) {
                width = 2048;
            }
            if (height > 2048) {
                height = 2048;
            }
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, width, height);
            drawable.draw(canvas);
            return dominantColor(bitmap);
        } catch (Exception e) {
            return null;
        }
    }

    /** Dominant color of a sampled pixel grid (5-bit per-channel bucketing). */
    public static Integer dominantColor(Bitmap bitmap) {
        if (bitmap == null) {
            return null;
        }
        try {
            int step = Math.max(1, Math.min(bitmap.getWidth(), bitmap.getHeight()) / 40);
            Map<Integer, Integer> counts = new HashMap<>();
            int bestKey = 0;
            int bestCount = 0;
            for (int y = 0; y < bitmap.getHeight(); y += step) {
                for (int x = 0; x < bitmap.getWidth(); x += step) {
                    int c = bitmap.getPixel(x, y);
                    int key = ((c >>> 19) & 0x1f) << 10
                            | ((c >>> 11) & 0x1f) << 5
                            | ((c >>> 3) & 0x1f);
                    int count = counts.containsKey(key) ? counts.get(key) + 1 : 1;
                    counts.put(key, count);
                    if (count > bestCount) {
                        bestCount = count;
                        bestKey = key;
                    }
                }
            }
            int r = (bestKey >>> 10) & 0x1f;
            int g = (bestKey >>> 5) & 0x1f;
            int b = bestKey & 0x1f;
            return Color.argb(0xff, (r << 3) | (r >> 2), (g << 3) | (g >> 2), (b << 3) | (b >> 2));
        } catch (Exception e) {
            return null;
        }
    }
}
