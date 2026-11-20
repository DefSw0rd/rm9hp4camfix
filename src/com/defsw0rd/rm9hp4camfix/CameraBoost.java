package com.defsw0rd.rm9hp4camfix;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Camera side of the module, all inside system_server.
 *
 * <p>Two jobs: warm the camera right after unlock and keep it in the tombstone state, and protect
 * it from the ROM cleanup paths that would otherwise reload it (swipe from recents, "clear all",
 * and the scene cleaner's kill entry points).
 *
 * <p>Every hook body is wrapped in try/catch even though libxposed can run in protective mode: we
 * want a log line and a counter when something breaks instead of a silently swallowed exception,
 * and the threads created here are outside any hook protection at all.
 */
final class CameraBoost {

    static final String CAMERA_PACKAGE = "com.android.camera";

    private static final String TAG = CamFixModule.TAG;
    private static final long LOCKED_POLL_MS = 1000L;
    private static final long LOCKED_WAIT_MAX_MS = 30 * 60 * 1000L;

    private static volatile XposedModule module;
    private static volatile ClassLoader loader;

    private CameraBoost() {
    }

    static void install(XposedModule xposedModule, ClassLoader classLoader) {
        module = xposedModule;
        loader = classLoader;
        try {
            Context context = systemContext();
            if (context == null) {
                Log.w(TAG, "system context unavailable, camera boost disabled");
                return;
            }
            FreezeController controller = new FreezeController(context);
            Thread thread = new Thread(controller, "camfix-freeze");
            thread.setDaemon(true);
            thread.start();

            try {
                installProviderUnfreeze();
            } catch (Throwable t) {
                Log.w(TAG, "provider hook install failed", t);
            }
            try {
                installSwipeKillGuard();
            } catch (Throwable t) {
                Log.w(TAG, "swipe kill guard install failed", t);
            }
            try {
                installClearAllGuards();
            } catch (Throwable t) {
                Log.w(TAG, "clear-all guards install failed", t);
            }
            try {
                startPrewarm(context);
            } catch (Throwable t) {
                Log.w(TAG, "prewarm thread start failed", t);
            }
            Log.i(TAG, "camera boost hooks installed");
        } catch (Throwable t) {
            Log.w(TAG, "camera boost install failed", t);
        }
    }

    // ------------------------------------------------------------------ hooks

    private static void installProviderUnfreeze() throws Throwable {
        Class<?> helper = Class.forName("com.android.server.am.ContentProviderHelper", false, loader);
        int hooked = 0;
        for (Method method : helper.getDeclaredMethods()) {
            if (!method.getName().equals("getContentProviderImpl")) {
                continue;
            }
            hook("ContentProviderHelper." + method.getName(), method,
                    chain -> {
                        try {
                            for (Object arg : chain.getArgs()) {
                                if (arg instanceof String && isCameraProvider((String) arg)) {
                                    FreezeController.unfreezeNow();
                                    break;
                                }
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "provider unfreeze failed", t);
                        }
                        return chain.proceed();
                    });
            hooked++;
        }
        Log.i(TAG, "provider unfreeze hooks = " + hooked);
    }

    private static void installSwipeKillGuard() throws Throwable {
        Class<?> cleaner = Class.forName("com.android.server.am.ProcessSceneCleaner", false, loader);
        int hooked = 0;
        for (Method method : cleaner.getDeclaredMethods()) {
            if (!method.getName().equals("handleSwipeKill")) {
                continue;
            }
            hook("ProcessSceneCleaner.handleSwipeKill", method,
                    chain -> {
                        try {
                            if (FixSettings.boost()) {
                                for (Object arg : chain.getArgs()) {
                                    if (targetsCamera(arg)) {
                                        Log.i(TAG, "blocked swipe kill: " + describe(chain.getArgs()));
                                        return Boolean.FALSE;
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "swipe kill guard failed", t);
                        }
                        return chain.proceed();
                    });
            hooked++;
        }
        Log.i(TAG, "swipe kill hooks = " + hooked);
    }

    private static void installClearAllGuards() throws Throwable {
        // Task removal (recents "clear all" walks every task through this).
        Class<?> utils = Class.forName("com.android.server.wm.WindowProcessUtils", false, loader);
        int taskHooks = 0;
        for (Method method : utils.getDeclaredMethods()) {
            if (!method.getName().equals("removeTaskLocked")) {
                continue;
            }
            hook("WindowProcessUtils.removeTaskLocked", method,
                    chain -> {
                        try {
                            if (FixSettings.boost() && chain.getArgs().size() > 0
                                    && isCameraTask(chain.getArgs().get(0))) {
                                Log.i(TAG, "blocked task removal: " + describe(chain.getArgs()));
                                return null;
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "task removal guard failed", t);
                        }
                        return chain.proceed();
                    });
            taskHooks++;
        }
        Log.i(TAG, "task removal hooks = " + taskHooks);

        // Process kills issued by the scene cleaner.
        Class<?> base = Class.forName("com.android.server.am.ProcessCleanerBase", false, loader);
        int killHooks = 0;
        for (Method method : base.getDeclaredMethods()) {
            String name = method.getName();
            if (!name.equals("killOnce") && !name.equals("killApplicationLock")) {
                continue;
            }
            hook("ProcessCleanerBase." + name, method,
                    chain -> {
                        try {
                            if (FixSettings.boost()) {
                                for (Object arg : chain.getArgs()) {
                                    if (isCameraRecord(arg)) {
                                        Log.i(TAG, "blocked scene kill: " + describe(chain.getArgs()));
                                        return null;
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "scene kill guard failed", t);
                        }
                        return chain.proceed();
                    });
            killHooks++;
        }
        Log.i(TAG, "scene kill hooks = " + killHooks);

        // Whitelist used by the non-"remove all" task path.
        for (Method method : base.getDeclaredMethods()) {
            if (!method.getName().equals("getProcessPolicyWhiteList")) {
                continue;
            }
            hook("ProcessCleanerBase.getProcessPolicyWhiteList", method,
                    chain -> {
                        Object result = chain.proceed();
                        try {
                            if (FixSettings.boost() && result instanceof List) {
                                @SuppressWarnings("unchecked")
                                List<Object> list = (List<Object>) result;
                                if (!list.contains(CAMERA_PACKAGE)) {
                                    list.add(CAMERA_PACKAGE);
                                }
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "whitelist hook failed", t);
                        }
                        return result;
                    });
        }

        // Observation only: lets the log show which path a user action took.
        for (Method method : base.getDeclaredMethods()) {
            if (method.getName().equals("forceStopPackage")) {
                hook("ProcessCleanerBase.forceStopPackage", method,
                        chain -> {
                            try {
                                for (Object arg : chain.getArgs()) {
                                    if (arg instanceof String
                                            && ((String) arg).contains(CAMERA_PACKAGE)) {
                                        Log.i(TAG, "observed forceStopPackage: "
                                                + describe(chain.getArgs()));
                                    }
                                }
                            } catch (Throwable t) {
                                Log.w(TAG, "forceStop observer failed", t);
                            }
                            return chain.proceed();
                        });
            }
        }
        /*
         * 已停用（2026-11-16）：一键清理的观测 hook。
         *
         * 它原本记录的是：
         *   clear-all observed (handleKillAll|handleKillAny): ProcessConfig{...} cameraRunning=<pid>
         * 其中 ProcessConfig 会带出 mPolicy、mReason、mWhiteList、mWhiteListTaskId、
         * mRemovingTaskIdList、mKillingPackage 等字段。这条日志的价值在于"清理发生了但我们没拦到东西"
         * 时仍然留有记录，以及 ROM 改变清理策略时的早期信号。
         *
         * 停用原因：`FreezeController.cameraPid()` 在缓存失效时会遍历 /proc 全表（实测约 20 ms），
         * 而 handleKillAll/handleKillAny 位于 AMS 侧的持锁路径上，属于"钩子里做 I/O"。
         * 当前 ROM 长期稳定，这条预警暂时不需要常驻。
         *
         * 若要重新启用：把最后的 cameraPid() 换成 FreezeController 里的零 I/O 版本
         * （只读缓存的 pid 字段，不校验 /proc），或直接用布尔"是否已跟踪"。
         *
         * 保留拦截逻辑本身不受影响：真正的拦截在 handleSwipeKill、removeTaskLocked、
         * killOnce/killApplicationLock 与 getProcessPolicyWhiteList 四处，均有各自的日志。
         *
        Class<?> cleaner = Class.forName("com.android.server.am.ProcessSceneCleaner", false, loader);
        for (Method method : cleaner.getDeclaredMethods()) {
            String name = method.getName();
            if (!name.equals("handleKillAll") && !name.equals("handleKillAny")) {
                continue;
            }
            hook("ProcessSceneCleaner." + name, method,
                    chain -> {
                        try {
                            Log.i(TAG, "clear-all observed (" + name + "): "
                                    + describe(chain.getArgs()) + " cameraRunning="
                                    + FreezeController.cameraPid());
                        } catch (Throwable t) {
                            Log.w(TAG, "clear-all observer failed", t);
                        }
                        return chain.proceed();
                    });
        }
        */
    }

    private static void hook(String label, Method method, XposedInterface.Hooker body) {
        try {
            module.hook(method).intercept(body);
        } catch (Throwable t) {
            Log.w(TAG, "hook failed: " + label + " (" + method + ")", t);
        }
    }

    // ------------------------------------------------------------------ prewarm

    private static void startPrewarm(final Context context) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    long deadline = SystemClock.elapsedRealtime() + LOCKED_WAIT_MAX_MS;
                    while (!isUserUnlocked(context)) {
                        if (SystemClock.elapsedRealtime() > deadline) {
                            Log.w(TAG, "prewarm skipped: still locked after 30 min");
                            return;
                        }
                        sleepQuietly(LOCKED_POLL_MS);
                    }
                    if (!FixSettings.boost()) {
                        Log.i(TAG, "prewarm skipped: boost disabled");
                        return;
                    }
                    sleepQuietly(1000L);
                    if (FreezeController.cameraPid() > 0) {
                        Log.i(TAG, "prewarm skipped: camera already running");
                        HoldWindow.start(SystemClock.elapsedRealtime());
                        return;
                    }
                    long start = SystemClock.elapsedRealtime();
                    // The hold window performs the actual provider acquisition: its call blocks
                    // until the provider is published, which is exactly the warm up we need.
                    HoldWindow.start(start);
                    Log.i(TAG, "prewarm delegated to hold window");
                } catch (Throwable t) {
                    Log.w(TAG, "prewarm thread failed", t);
                }
            }
        }, "camfix-prewarm");
        thread.setDaemon(true);
        thread.start();
    }


    private static boolean isUserUnlocked(Context context) {
        try {
            android.os.UserManager manager =
                    (android.os.UserManager) context.getSystemService(Context.USER_SERVICE);
            return manager != null && manager.isUserUnlocked();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ matching helpers

    private static boolean isCameraProvider(String value) {
        return value.contains(CAMERA_PACKAGE) || value.contains("cn.nubia.camera")
                || value.contains("com.zte.");
    }

    private static boolean targetsCamera(Object arg) {
        try {
            if (arg == null) {
                return false;
            }
            if (arg instanceof String) {
                return ((String) arg).contains(CAMERA_PACKAGE);
            }
            String className = arg.getClass().getName();
            if (className.endsWith("ProcessConfig")) {
                Object pkg = getField(arg, "mKillingPackage");
                return pkg instanceof String && ((String) pkg).contains(CAMERA_PACKAGE);
            }
            if (className.endsWith(".Task") || className.endsWith("Task")) {
                return taskPackageMatches(arg);
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isCameraTask(Object arg) {
        try {
            if (arg == null || !arg.getClass().getName().endsWith("Task")) {
                return false;
            }
            if (taskPackageMatches(arg)) {
                return true;
            }
            String text = String.valueOf(arg);
            int index = text.indexOf("A=");
            if (index < 0) {
                return false;
            }
            int end = index + 2;
            while (end < text.length() && Character.isDigit(text.charAt(end))) {
                end++;
            }
            if (end == index + 2) {
                return false;
            }
            int uid = Integer.parseInt(text.substring(index + 2, end));
            String[] packages = systemContext().getPackageManager().getPackagesForUid(uid);
            if (packages != null) {
                for (String pkg : packages) {
                    if (CAMERA_PACKAGE.equals(pkg)) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "task match failed", t);
        }
        return false;
    }

    private static boolean taskPackageMatches(Object task) {
        try {
            Object activity = callMethod(task, "getTopActivity");
            if (activity == null) {
                activity = callMethod(task, "getRootActivity");
            }
            if (activity == null) {
                return false;
            }
            Object pkg = getField(activity, "packageName");
            return pkg instanceof String && ((String) pkg).contains(CAMERA_PACKAGE);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isCameraRecord(Object arg) {
        try {
            if (arg == null || !arg.getClass().getName().endsWith("ProcessRecord")) {
                return false;
            }
            Object info = getField(arg, "info");
            if (info != null) {
                Object pkg = getField(info, "packageName");
                if (pkg instanceof String && CAMERA_PACKAGE.equals(pkg)) {
                    return true;
                }
            }
            Object pkgList = getField(arg, "pkgList");
            return pkgList != null && String.valueOf(pkgList).contains(CAMERA_PACKAGE);
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object getField(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored) {
                // try parent
            }
        }
        return null;
    }

    private static Object callMethod(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method method = c.getDeclaredMethod(name);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (Throwable ignored) {
                // try parent
            }
        }
        return null;
    }

    private static String describe(List<Object> args) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(String.valueOf(args.get(i)));
        }
        return builder.append(']').toString();
    }

    private static Context systemContext() {
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
