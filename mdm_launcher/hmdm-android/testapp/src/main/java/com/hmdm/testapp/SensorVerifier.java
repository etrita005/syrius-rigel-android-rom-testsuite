package com.hmdm.testapp;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Real-hardware verification helpers: open a camera and record audio
 * to verify the effect of ASR-0207 / ASR-0369 policies.
 */
public class SensorVerifier {

    private SensorVerifier() {
    }

    public static String tryOpenCamera(Context context) {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            return "camera service unavailable";
        }
        try {
            String[] ids = manager.getCameraIdList();
            if (ids.length == 0) {
                return "no camera ids";
            }
        } catch (CameraAccessException e) {
            return "camera id list exception: " + e.getMessage();
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final String[] result = new String[1];
        HandlerThread thread = new HandlerThread("camera-verify");
        thread.start();
        try {
            manager.openCamera(manager.getCameraIdList()[0], new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    result[0] = "onOpened camera=" + camera.getId();
                    camera.close();
                    latch.countDown();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    result[0] = "onDisconnected";
                    latch.countDown();
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    result[0] = "onError code=" + error;
                    latch.countDown();
                }
            }, new Handler(thread.getLooper()));

            if (!latch.await(5, TimeUnit.SECONDS)) {
                result[0] = "open timeout";
            }
        } catch (CameraAccessException e) {
            result[0] = "open exception: " + e.getMessage();
        } catch (InterruptedException e) {
            result[0] = "interrupted";
        } catch (SecurityException e) {
            result[0] = "security exception: " + e.getMessage();
        } finally {
            thread.quitSafely();
        }
        return result[0];
    }

    public static String tryRecordAudio() {
        int sampleRate = 44100;
        int minBuffer = AudioRecord.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            return "invalid min buffer: " + minBuffer;
        }

        AudioRecord record = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 2);
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            return "audio record not initialized";
        }

        try {
            record.startRecording();
        } catch (Exception e) {
            record.release();
            return "start failed: " + e.getMessage();
        }

        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            record.release();
            return "not in recording state";
        }

        short[] buffer = new short[1024];
        int total = 0;
        long energy = 0;
        long end = System.currentTimeMillis() + 1000;
        while (System.currentTimeMillis() < end) {
            int n = record.read(buffer, 0, buffer.length);
            if (n > 0) {
                for (int i = 0; i < n; i++) {
                    total++;
                    energy += Math.abs(buffer[i]);
                }
            }
        }
        record.stop();
        record.release();
        return "recorded samples: " + total + ", energy: " + energy
                + (total > 0 ? ", avg amp: " + (energy / total) : "");
    }
}
