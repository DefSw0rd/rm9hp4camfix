package com.defsw0rd.rm9hp4camfix;

import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

/**
 * Holds a provider connection to the camera for a short window after the prewarm.
 *
 * <p>The ROM runs one bulk low-memory reclaim shortly after unlock (measured 30-40s after unlock,
 * ~48 processes in one burst). While a client holds a provider connection AMS itself keeps the
 * camera out of the cached bucket, so the process survives that burst without any oom_score_adj
 * write and without fighting the OomAdjuster.
 *
 * <p>Verified on device: adj stays 0 for the whole window, the pid never changes, and the process
 * is idle (0 CPU ticks over 30s) even though it is not frozen. The connection is released when the
 * window ends and the tombstone takes over again.
 */
final class HoldWindow {

    private static final String TAG = CamFixModule.TAG;
    private static final String AUTHORITY = "com.android.camera.cardprovider";
    private static final long DEFAULT_WINDOW_MS = 90000L;
    private static final long SAMPLE_MS = 5000L;

    private HoldWindow() {
    }

    static void start(final long warmStart) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runWindow(warmStart);
                } catch (Throwable t) {
                    Log.w(TAG, "hold window failed", t);
                }
            }
        }, "camfix-hold");
        thread.setDaemon(true);
        thread.start();
    }

    private static void runWindow(long warmStart) {
        if (!FixSettings.boost()) {
            return;
        }
        Object token = new android.os.Binder();
        Object activityManager;
        try {
            activityManager = Class.forName("android.app.ActivityManager")
                    .getMethod("getService")
                    .invoke(null);
        } catch (Throwable t) {
            Log.w(TAG, "hold window: ActivityManager unavailable", t);
            return;
        }
        if (activityManager == null) {
            Log.w(TAG, "hold window: no ActivityManager");
            return;
        }
        int pidBefore = FreezeController.cameraPid();
        Log.i(TAG, "hold window started pid=" + pidBefore
                + " adj=" + (pidBefore > 0 ? FreezeController.adjOf(pidBefore) : -999));
        boolean acquired = false;
        try {
            Object holder = activityManager.getClass()
                    .getMethod("getContentProviderExternal",
                            String.class, int.class, IBinder.class, String.class)
                    .invoke(activityManager, AUTHORITY, 0, token, "camfix-hold");
            acquired = holder != null;
            Log.i(TAG, "hold window: provider acquired holder=" + acquired
                    + " warmup=" + (SystemClock.elapsedRealtime() - warmStart) + "ms");
        } catch (Throwable t) {
            Log.w(TAG, "hold window: provider acquire failed", t);
        }
        if (!acquired) {
            return;
        }

        long end = SystemClock.elapsedRealtime() + DEFAULT_WINDOW_MS;
        int lastPid = pidBefore;
        int killed = 0;
        while (SystemClock.elapsedRealtime() < end) {
            try {
                Thread.sleep(SAMPLE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (!FixSettings.boost()) {
                Log.i(TAG, "hold window: boost disabled, releasing early");
                break;
            }
            try {
                int pid = FreezeController.trackedPid();
                if (pid <= 0) pid = lastPid;
                if (pid > 0 && lastPid > 0 && pid != lastPid) {
                    killed++;
                }
                lastPid = pid;
                Log.i(TAG, "hold pid=" + pid
                        + " adj=" + (pid > 0 ? FreezeController.adjOf(pid) : -999)
                        + " frozen=" + (pid > 0 ? FreezeController.freezeStateOf(pid) : "?")
                        + (killed > 0 ? " pidChanges=" + killed : ""));
            } catch (Throwable t) {
                // sampling must never abort the window
            }
        }

        try {
            activityManager.getClass()
                    .getMethod("removeContentProviderExternal", String.class, IBinder.class)
                    .invoke(activityManager, AUTHORITY, token);
            Log.i(TAG, "hold window closed, connection released, pidChanges=" + killed);
        } catch (Throwable t) {
            Log.w(TAG, "hold window: release failed", t);
        }
    }
}
