package com.defsw0rd.rm9hp4camfix;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;

/**
 * Tombstone policy for the camera process.
 *
 * <p>Polling based, so a ROM change cannot break AMS through this class. The cost profile is the
 * important part: while the camera process exists a tick only reads one
 * {@code /proc/<pid>/oom_score_adj}; while it is absent the loop backs off instead of scanning the
 * whole {@code /proc} table several times per second.
 *
 * <p>Every entry point swallows Throwable and the loop stops itself after
 * {@link #MAX_CONSECUTIVE_ERRORS} failures, so a broken assumption degrades into "no tombstone"
 * rather than a system_server crash.
 */
final class FreezeController implements Runnable {

    private static final String TAG = CamFixModule.TAG;
    private static final String TARGET_PACKAGE = CameraBoost.CAMERA_PACKAGE;

    private static final long TICK_MS = 500L;
    private static final long DWELL_MS = 3000L;
    private static final int BACKGROUND_ADJ = 200;
    private static final String CGROUP_ROOT = "/sys/fs/cgroup/apps";

    private static final long SCAN_BACKOFF_START_MS = 1000L;
    private static final long SCAN_BACKOFF_MAX_MS = 10000L;
    private static final long PACKAGE_TTL_MS = 15000L;
    private static final long SUMMARY_INTERVAL_MS = 600000L;
    private static final int MAX_CONSECUTIVE_ERRORS = 20;

    private final Context context;

    private static volatile FreezeController instance;

    private volatile int pid = -1;
    private int uid = -1;
    private long backgroundSince;
    private long lastUnfreeze;
    private boolean frozen;
    private int lastAdj = Integer.MIN_VALUE;

    private boolean packageUsableCache = true;
    private long packageAt;
    private long nextScanDelay = SCAN_BACKOFF_START_MS;

    private long freezes;
    private long unfreezes;
    private long failures;
    private long killsObserved;
    private long scansSkipped;
    private int consecutiveErrors;
    private long lastSummaryAt;
    private int watchRetryTicks;
    private boolean nodeMissingLogged;

    FreezeController(Context context) {
        this.context = context;
        instance = this;
    }

    /** Called from the provider hook, before a request reaches the frozen process. */
    static void unfreezeNow() {
        try {
            FreezeController controller = instance;
            if (controller != null) {
                controller.unfreezeSync();
            }
        } catch (Throwable t) {
            Log.w(TAG, "unfreezeNow failed", t);
        }
    }

    private synchronized void unfreezeSync() {
        try {
            if (frozen) {
                setFrozen(false, -1, "provider-request");
            }
        } catch (Throwable t) {
            Log.w(TAG, "unfreezeSync failed", t);
        }
    }

    @Override
    public void run() {
        Log.i(TAG, "freeze controller started");
        lastSummaryAt = android.os.SystemClock.elapsedRealtime();
        while (true) {
            long delay;
            try {
                delay = tick();
                consecutiveErrors = 0;
            } catch (Throwable t) {
                consecutiveErrors++;
                Log.w(TAG, "tick failed (" + consecutiveErrors + ")", t);
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    Log.e(TAG, "tick failed " + consecutiveErrors + " times, controller stops");
                    return;
                }
                delay = TICK_MS;
            }
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private synchronized long tick() {
        long now = android.os.SystemClock.elapsedRealtime();
        maybeLogSummary(now);
        maybeRestartSettingsWatcher();

        if (!FixSettings.boost()) {
            if (frozen) {
                setFrozen(false, -1, "boost-off");
            }
            return TICK_MS;
        }
        if (!isPackageUsable(now)) {
            if (frozen) {
                setFrozen(false, -1, "package-unusable");
            }
            scansSkipped++;
            pid = -1;
            uid = -1;
            return PACKAGE_TTL_MS / 2;
        }
        if (pid > 0 && !isTargetProcess(pid)) {
            killsObserved++;
            Log.i(TAG, "tracked camera process disappeared: pid=" + pid + " adj(last)=" + lastAdj);
            pid = -1;
            uid = -1;
            frozen = false;
            backgroundSince = 0L;
        }
        if (pid <= 0) {
            int found = findPid();
            if (found <= 0) {
                long delay = nextScanDelay;
                nextScanDelay = Math.min(nextScanDelay * 2, SCAN_BACKOFF_MAX_MS);
                return delay;
            }
            pid = found;
            uid = readUid(pid);
            backgroundSince = 0L;
            frozen = false;
            nextScanDelay = SCAN_BACKOFF_START_MS;
            Log.i(TAG, "tracking camera process pid=" + pid + " uid=" + uid);
        }
        if (uid <= 0) {
            uid = readUid(pid);
            if (uid <= 0) {
                pid = -1;
                return TICK_MS;
            }
        }
        int adj = readAdj(pid);
        if (adj == Integer.MIN_VALUE) {
            pid = -1;
            return TICK_MS;
        }
        lastAdj = adj;
        if (frozen) {
            if (adj < BACKGROUND_ADJ) {
                setFrozen(false, adj, "adj<" + BACKGROUND_ADJ);
            }
            return TICK_MS;
        }
        if (adj < BACKGROUND_ADJ) {
            backgroundSince = 0L;
            return TICK_MS;
        }
        if (backgroundSince == 0L) {
            backgroundSince = now;
            return TICK_MS;
        }
        if (now - backgroundSince >= DWELL_MS && now - lastUnfreeze >= DWELL_MS) {
            setFrozen(true, adj, "dwell");
        }
        return TICK_MS;
    }

    private boolean isPackageUsable(long now) {
        if (now - packageAt < PACKAGE_TTL_MS) {
            return packageUsableCache;
        }
        packageAt = now;
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(TARGET_PACKAGE, 0);
            packageUsableCache = info.enabled && (info.flags & ApplicationInfo.FLAG_STOPPED) == 0;
        } catch (Throwable t) {
            packageUsableCache = true;
        }
        return packageUsableCache;
    }

    /** Restarts the settings watcher if it gave up early (system_server can be slow to settle). */
    private void maybeRestartSettingsWatcher() {
        if (++watchRetryTicks < 60) {
            return;
        }
        watchRetryTicks = 0;
        FixSettings.startWatching();
    }

    private void maybeLogSummary(long now) {
        if (now - lastSummaryAt < SUMMARY_INTERVAL_MS) {
            return;
        }
        lastSummaryAt = now;
        Log.i(TAG, "stats freeze=" + freezes + " unfreeze=" + unfreezes + " fail=" + failures
                + " killed=" + killsObserved + " scanSkipped=" + scansSkipped
                + " pid=" + pid + " adj=" + lastAdj + " frozen=" + frozen);
    }

    private void setFrozen(boolean value, int decisionAdj, String reason) {
        File node = new File(CGROUP_ROOT + "/uid_" + uid + "/pid_" + pid + "/cgroup.freeze");
        try {
            if (!node.exists()) {
                failures++;
                if (!nodeMissingLogged) {
                    nodeMissingLogged = true;
                    Log.w(TAG, "cgroup.freeze missing: " + node);
                }
                return;
            }
            FileOutputStream out = new FileOutputStream(node);
            try {
                out.write(value ? '1' : '0');
                out.flush();
            } finally {
                out.close();
            }
            frozen = value;
            if (value) {
                freezes++;
            } else {
                unfreezes++;
                lastUnfreeze = android.os.SystemClock.elapsedRealtime();
                backgroundSince = 0L;
            }
            Log.i(TAG, "freeze=" + value + " pid=" + pid + " adj=" + decisionAdj
                    + " reason=" + reason);
        } catch (Throwable t) {
            failures++;
            Log.w(TAG, "write cgroup.freeze failed: " + node, t);
        }
    }

    private static int findPid() {
        try {
            String[] entries = new File("/proc").list();
            if (entries == null) {
                return -1;
            }
            for (String entry : entries) {
                int candidate;
                try {
                    candidate = Integer.parseInt(entry);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (TARGET_PACKAGE.equals(readCmdline(candidate))) {
                    return candidate;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "findPid failed", t);
        }
        return -1;
    }

    private static boolean isTargetProcess(int candidate) {
        return TARGET_PACKAGE.equals(readCmdline(candidate));
    }

    private static String readCmdline(int candidate) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream("/proc/" + candidate + "/cmdline"), "UTF-8"));
            String line = reader.readLine();
            return line == null ? null : line.trim();
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(reader);
        }
    }

    private static int readAdj(int candidate) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream("/proc/" + candidate + "/oom_score_adj"), "UTF-8"));
            String line = reader.readLine();
            return line == null ? Integer.MIN_VALUE : Integer.parseInt(line.trim());
        } catch (Throwable t) {
            return Integer.MIN_VALUE;
        } finally {
            closeQuietly(reader);
        }
    }

    private static int readUid(int candidate) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream("/proc/" + candidate + "/status"), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("Uid:")) {
                    String[] parts = line.trim().split("\\s+");
                    return Integer.parseInt(parts[1]);
                }
            }
        } catch (Throwable t) {
            return -1;
        } finally {
            closeQuietly(reader);
        }
        return -1;
    }

    private static void closeQuietly(BufferedReader reader) {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (Throwable ignored) {
            // nothing to do
        }
    }

    /** Pid of the camera process, resolved on demand (used by the hold window). */
    static int cameraPid() {
        try {
            FreezeController controller = instance;
            if (controller != null && controller.pid > 0 && isTargetProcess(controller.pid)) {
                return controller.pid;
            }
            return findPid();
        } catch (Throwable t) {
            return -1;
        }
    }

    static int adjOf(int pid) {
        return readAdj(pid);
    }

    /**
     * Cached pid without touching {@code /proc}; for callers that only want to report state
     * (the hold window samples this every few seconds).
     */
    static int trackedPid() {
        FreezeController controller = instance;
        return controller == null ? -1 : controller.pid;
    }

    static String freezeStateOf(int pid) {
        BufferedReader reader = null;
        try {
            int uid = readUid(pid);
            if (uid <= 0) {
                return "?";
            }
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(
                    CGROUP_ROOT + "/uid_" + uid + "/pid_" + pid + "/cgroup.freeze"), "UTF-8"));
            String line = reader.readLine();
            return line == null ? "?" : line.trim();
        } catch (Throwable t) {
            return "?";
        } finally {
            closeQuietly(reader);
        }
    }
}
