package com.defsw0rd.rm9hp4camfix;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.util.Log;

/**
 * The four switches, mirrored from {@code Settings.Global} into plain booleans.
 *
 * <p>Two rules shape this class:
 *
 * <ul>
 *   <li>hook bodies can run while the WindowManager or ActivityManager lock is held
 *       ({@code canShowWhenLocked}, {@code killOnce}, {@code removeTaskLocked},
 *       {@code getContentProviderImpl}). Doing a cross process read there previously blocked one of
 *       those locks and tripped the watchdog, which restarted system_server. Those paths now only
 *       read {@code volatile} fields;</li>
 *   <li>the read and the {@code ContentObserver} registration live on one dedicated thread per
 *       process, so the steady state costs nothing and a flip in the UI is seen within milliseconds.</li>
 * </ul>
 *
 * <p>{@code Settings.Global} is the store because every process may read it without us exporting a
 * provider. Writing it needs a one off grant at install time:
 * {@code adb shell pm grant com.defsw0rd.rm9hp4camfix android.permission.WRITE_SECURE_SETTINGS}.
 */
final class FixSettings {

    private static final String TAG = CamFixModule.TAG;

    static final String KEY_QUICK_CAMERA = "camfix_quick_camera";
    static final String KEY_LOCK_CAMERA = "camfix_lock_camera";
    static final String KEY_BOOST = "camfix_boost";
    static final String KEY_UNLOCK_SHORTCUTS = "camfix_unlock_shortcuts";

    private static final String[] KEYS = {KEY_QUICK_CAMERA, KEY_LOCK_CAMERA, KEY_BOOST,
            KEY_UNLOCK_SHORTCUTS};

    private static volatile boolean quickCamera = true;
    private static volatile boolean lockCamera = true;
    private static volatile boolean boost = true;
    private static volatile boolean unlockShortcuts = false;

    private static volatile boolean watching;
    private static volatile Handler settingsHandler;

    private FixSettings() {
    }

    static boolean quickCamera() {
        return quickCamera;
    }

    static boolean lockCamera() {
        return lockCamera;
    }

    static boolean boost() {
        return boost;
    }

    static boolean unlockShortcuts() {
        return unlockShortcuts;
    }

    /**
     * Called from module callbacks and periodically from the freeze controller; returns at once.
     *
     * <p>Retries for a while because system_server may still be starting when the module loads.
     * If every attempt fails the flag is cleared again, so a later call can restart the watcher
     * instead of the process silently running on defaults forever.
     */
    static void startWatching() {
        if (watching) {
            return;
        }
        synchronized (FixSettings.class) {
            if (watching) {
                return;
            }
            watching = true;
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Handler handler = handler();
                        for (int attempt = 0; attempt < 150; attempt++) {
                            Context context = systemContext();
                            if (context != null && watch(context, handler)) {
                                Log.i(TAG, "settings watcher active: quick=" + quickCamera
                                        + " lock=" + lockCamera + " boost=" + boost
                                        + " unlock=" + unlockShortcuts);
                                return;
                            }
                            Thread.sleep(2000L);
                        }
                        Log.w(TAG, "settings watcher gave up, defaults in use for now");
                        watching = false;
                    } catch (Throwable t) {
                        watching = false;
                        Log.w(TAG, "settings watcher failed", t);
                    }
                }
            }, "camfix-settings");
            thread.setDaemon(true);
            thread.start();
        }
    }

    /** 每个进程只建一个 HandlerThread：重启 watcher 不再累积线程。 */
    private static Handler handler() {
        Handler existing = settingsHandler;
        if (existing != null) {
            return existing;
        }
        synchronized (FixSettings.class) {
            if (settingsHandler == null) {
                HandlerThread thread = new HandlerThread("camfix-settings");
                thread.start();
                settingsHandler = new Handler(thread.getLooper());
            }
            return settingsHandler;
        }
    }

    private static boolean watch(Context context, Handler handler) {
        try {
            refresh(context);
            ContentObserver observer = new ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    try {
                        refresh(context);
                    } catch (Throwable t) {
                        Log.w(TAG, "refresh after change failed", t);
                    }
                }
            };
            for (String key : KEYS) {
                context.getContentResolver().registerContentObserver(
                        Settings.Global.getUriFor(key), false, observer);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void refresh(Context context) {
        quickCamera = read(context, KEY_QUICK_CAMERA, quickCamera);
        lockCamera = read(context, KEY_LOCK_CAMERA, lockCamera);
        boost = read(context, KEY_BOOST, boost);
        unlockShortcuts = read(context, KEY_UNLOCK_SHORTCUTS, unlockShortcuts);
    }

    private static boolean read(Context context, String key, boolean fallback) {
        try {
            return Settings.Global.getInt(context.getContentResolver(), key, fallback ? 1 : 0) != 0;
        } catch (Throwable t) {
            return fallback;
        }
    }

    static Context systemContext() {
        try {
            Class<?> clazz = Class.forName("android.app.ActivityThread");
            Object thread = clazz.getMethod("currentActivityThread").invoke(null);
            if (thread == null) {
                return null;
            }
            return (Context) clazz.getMethod("getSystemContext").invoke(thread);
        } catch (Throwable t) {
            return null;
        }
    }
}
