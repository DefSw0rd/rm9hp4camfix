package com.defsw0rd.rm9hp4camfix;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

/**
 * CamFix 模块入口：锁屏部分逐行沿用 rm9hp4lockfix 的实现，仅在开关处做判断；
 * system_server 中另外安装相机加速。
 */
public final class CamFixModule extends XposedModule {
    static final String TAG = "RM9HP4CamFix";
    private static final String ACTIVITY_RECORD_CLASS = "com.android.server.wm.ActivityRecord";
    private static final String NOTES_PACKAGE = "com.miui.notes";
    private static final String AOD_PACKAGE = "com.miui.aod";
    private static final String DATA_UTILS_CLASS = "com.miui.keyguard.shortcuts.utils.DataUtils";
    private static final String PACKAGE_UTILS_CLASS = "com.miui.keyguard.shortcuts.utils.PackageUtils";
    private static final String ADAPTER_CLASS = "com.miui.keyguard.shortcuts.list.ShortcutListRecyclerAdapter";
    private static final String SHORTCUT_ITEM_CLASS = "com.miui.keyguard.shortcuts.list.ShortcutItem";
    private static final String CAMERA_PACKAGE = "com.android.camera";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";

    /** 锁屏右下角快捷方式视图 id（dumpsys SystemUIService：#7f0b055d shortcut_view_right）。 */
    private static final int SHORTCUT_VIEW_RIGHT_ID = 0x7f0b055d;
    /** AOD camera_icon 固有 88dp，在锁屏上按此比例缩小后与左侧手电筒 glyph 等高。 */
    private static final float CAMERA_ICON_SCALE = 0.66f;
    private static final String SHORTCUT_SETTING_RIGHT = "keyguard_shortcut_right";

    /** AOD 的相机矢量图（drawable/camera_icon，0x7f08048f）：候选列表里显示用。 */
    private static final int AOD_CAMERA_ICON = 0x7f08048f;

    /**
     * 与系统白名单里的相机条目保持一致（packageName / iconId / nameId / tag 都取原生值），
     * 否则条目会在选择页的合并阶段被丢掉。tag 必须是白名单里的 CAMERA_CAPTURE：
     * 非白名单 tag 的条目会在校验阶段被清成空壳。
     */
    private static final String CAMERA_SHORTCUT_JSON =
            "{\"action\":\"android.media.action.STILL_IMAGE_CAMERA_SECURE\","
            + "\"iconId\":\"@2131232426\",\"isOverlay\":false,\"isSystemDefault\":false,"
            + "\"nameId\":\"@2131755173\",\"packageName\":\"com.android.camera\","
            + "\"tag\":\"CAMERA_CAPTURE\",\"targetClass\":\"com.android.camera.Camera\","
            + "\"targetPackage\":\"com.android.camera\"}";

    /** 免密开关放行的快捷方式应用；相机由两个相机开关单独放行。 */
    private static final Set<String> SHORTCUT_PACKAGES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "com.miui.calculator", "com.android.deskclock",
                    "com.miui.notes", "com.android.soundrecorder", "com.miui.compass")));

    private static volatile Context cachedContext;
    private static volatile Object dataUtilsInstance;
    private static volatile Method shortcutFromJson;
    private static volatile Constructor<?> shortcutItemCtor;

    private static final Object ICON_LOCK = new Object();
    private static String iconKey;
    private static Drawable iconCache;
    private static volatile boolean hookReentry;

    /**
     * 右槽配置串的内存镜像。hook 只读它，避免在 SystemUI 渲染路径上做 Settings 的跨进程读；
     * null 表示尚未知（此时允许一次性的兜底直读，之后仍未知就按"不替换图标"处理）。
     */
    private static volatile String rightSetting;
    private static volatile boolean rightSettingWatching;
    private static volatile boolean rightSettingFallbackTried;
    private static volatile boolean iconReplaceLogged;

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "loaded in " + param.getProcessName() + ", api " + getApiVersion());
        // Mirror the switches into memory once per process; hooks must never query Settings.
        FixSettings.startWatching();
    }

    // ------------------------------------------------------------------ system_server

    @Override public void onSystemServerStarting(SystemServerStartingParam param) {
        Class<?> activityRecord;
        try {
            activityRecord = Class.forName(ACTIVITY_RECORD_CLASS, false, param.getClassLoader());
        } catch (ClassNotFoundException e) {
            log(Log.ERROR, TAG, "ActivityRecord not found", e);
            return;
        }
        Method target = null;
        for (Method candidate : activityRecord.getDeclaredMethods()) {
            if (candidate.getName().equals("canShowWhenLocked")
                    && candidate.getParameterCount() == 0
                    && candidate.getReturnType() == boolean.class) {
                target = candidate;
                break;
            }
        }
        if (target == null) {
            log(Log.ERROR, TAG, "canShowWhenLocked() not found");
            return;
        }
        hook(target).intercept(chain -> {
            try {
                if (isAllowed(chain.getThisObject())) return true;
            } catch (Throwable t) {
                log(Log.WARN, TAG, "canShowWhenLocked hook failed", t);
            }
            return chain.proceed();
        });
        log(Log.INFO, TAG, "hooked canShowWhenLocked");
        try {
            CameraBoost.install(this, param.getClassLoader());
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "camera boost install failed", t);
        }
    }

    // ------------------------------------------------------------------ app processes

    @Override public void onPackageReady(PackageReadyParam param) {
        if (!param.isFirstPackage()) return;
        String pkg = param.getPackageName();
        if (NOTES_PACKAGE.equals(pkg)) fixNotesKeyguard(param.getClassLoader());
        else if (AOD_PACKAGE.equals(pkg)) injectCameraShortcut(param);
        else if (SYSTEMUI_PACKAGE.equals(pkg)) fixLockscreenCameraIconSize(param.getClassLoader());
    }

    /** 笔记不再认为自己在锁屏上。 */
    private void fixNotesKeyguard(ClassLoader loader) {
        Class<?> keyguardManager;
        try {
            keyguardManager = Class.forName("android.app.KeyguardManager", false, loader);
        } catch (ClassNotFoundException e) {
            log(Log.ERROR, TAG, "KeyguardManager not found", e);
            return;
        }
        int hooked = 0;
        for (Method candidate : keyguardManager.getDeclaredMethods()) {
            if (candidate.getParameterCount() != 0 || candidate.getReturnType() != boolean.class) continue;
            String name = candidate.getName();
            if (!name.equals("isKeyguardLocked") && !name.equals("inKeyguardRestrictedInputMode")) continue;
            hook(candidate).intercept(chain -> FixSettings.unlockShortcuts()
                    ? false : chain.proceed());
            hooked++;
        }
        log(Log.INFO, TAG, "notes: keyguard-locked faked false (" + hooked + ")");
    }

    // ------------------------------------------------------------------ SystemUI: 锁屏右下角图标尺寸

    /**
     * 快捷方式视图在 SystemUI 进程渲染（此前所有图标 hook 装在 AOD 进程因此无效）。
     * 只拦右下角视图：右槽配置仍是相机时，把系统给的图标换成缩放后的 AOD 相机图标。
     */
    private void fixLockscreenCameraIconSize(ClassLoader loader) {
        Class<?> imageView;
        try {
            imageView = Class.forName("android.widget.ImageView", false, loader);
        } catch (ClassNotFoundException e) {
            log(Log.ERROR, TAG, "systemui: ImageView not found", e);
            return;
        }
        int hooked = 0;
        for (Method candidate : imageView.getDeclaredMethods()) {
            if (!candidate.getName().equals("setImageDrawable") || candidate.getParameterCount() != 1) continue;
            hook(candidate).intercept(chain -> {
                // 整段包 try/catch：资源 id 变更、createPackageContext 失败等异常都不能穿过 hook。
                try {
                    if (hookReentry || !FixSettings.lockCamera()) return chain.proceed();
                    Object view = chain.getThisObject();
                    if (!(view instanceof View) || ((View) view).getId() != SHORTCUT_VIEW_RIGHT_ID) {
                        return chain.proceed();
                    }
                    View v = (View) view;
                    String setting = currentRightSetting(v.getContext());
                    if (setting == null || !setting.contains(CAMERA_PACKAGE)) return chain.proceed();
                    Drawable icon = cameraIcon(v.getContext(), setting);
                    if (icon == null) return chain.proceed();
                    if (!iconReplaceLogged) {
                        iconReplaceLogged = true;
                        log(Log.INFO, TAG, "systemui: camera icon rescaled on the right slot");
                    }
                    hookReentry = true;
                    try {
                        ((android.widget.ImageView) v).setImageDrawable(icon);
                    } finally {
                        hookReentry = false;
                    }
                    return null;
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "systemui: icon replacement failed", t);
                    return chain.proceed();
                }
            });
            hooked++;
            log(Log.INFO, TAG, "systemui: hooked ImageView#setImageDrawable");
        }
        log(hooked > 0 ? Log.INFO : Log.WARN, TAG, "systemui: icon size hooks = " + hooked);
        startRightSettingWatch();
        preventShortcutClearing(loader);
    }

    /**
     * 任何应用包变更（安装/更新/清数据）都会让快捷方式管理器重新校验条目；红魔相机
     * 不响应查询，右槽存储实体会被降级成只剩 isOverlay/isSystemDefault 的空壳，图标
     * 随之消失。这里拦 Settings$Secure.putString：右槽配置被写成不含 iconId 的空壳时，
     * 改写为注入用的相机配置，保证锁屏右下角始终是相机。用户在设置页换成其他快捷
     * 方式（配置带完整字段）则原样放行。
     */
    private void preventShortcutClearing(ClassLoader loader) {
        Class<?> secure;
        try {
            secure = Class.forName("android.provider.Settings$Secure", false, loader);
        } catch (ClassNotFoundException e) {
            log(Log.ERROR, TAG, "systemui: Settings$Secure not found", e);
            return;
        }
        int hooked = 0;
        for (Method candidate : secure.getDeclaredMethods()) {
            String name = candidate.getName();
            Class<?>[] ps = candidate.getParameterTypes();
            boolean candidatePut = (name.equals("putString") || name.equals("putStringForUser"))
                    && ps.length >= 3
                    && String.class.equals(ps[1]) && String.class.equals(ps[2]);
            if (!candidatePut) continue;
            hook(candidate).intercept(chain -> {
                try {
                    if (hookReentry || !FixSettings.lockCamera()) return chain.proceed();
                    List<Object> args = chain.getArgs();
                    if (args.size() < 3 || !SHORTCUT_SETTING_RIGHT.equals(args.get(1))) {
                        return chain.proceed();
                    }
                    if (!(args.get(2) instanceof String) || ((String) args.get(2)).contains("iconId")) {
                        return chain.proceed();
                    }
                    if (!(args.get(0) instanceof android.content.ContentResolver)) {
                        return chain.proceed();
                    }
                    hookReentry = true;
                    try {
                        Settings.Secure.putString(
                                (android.content.ContentResolver) args.get(0),
                                SHORTCUT_SETTING_RIGHT, CAMERA_SHORTCUT_JSON);
                        log(Log.INFO, TAG, "systemui: restored cleared shortcut config");
                    } finally {
                        hookReentry = false;
                    }
                    return null;
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "systemui: shortcut restore failed", t);
                    return chain.proceed();
                }
            });
            hooked++;
        }
        log(hooked > 0 ? Log.INFO : Log.WARN, TAG, "systemui: shortcut-clear guards = " + hooked);
    }

    /**
     * 当前右槽配置：优先读内存镜像；镜像还没就绪时允许一次兜底直读（每个进程一次），
     * 之后仍未知就返回 null，调用方按"不替换图标"处理（与读失败时的行为一致）。
     */
    private static String currentRightSetting(Context ctx) {
        String cached = rightSetting;
        if (cached != null) {
            return cached;
        }
        if (rightSettingFallbackTried) {
            return null;
        }
        rightSettingFallbackTried = true;
        String direct = readRightSetting(ctx);
        if (direct != null) {
            rightSetting = direct;
        }
        return direct;
    }

    private static String readRightSetting(Context ctx) {
        try {
            return Settings.Secure.getString(ctx.getContentResolver(), SHORTCUT_SETTING_RIGHT);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 后台线程读取一次并注册观察者；失败只记日志，hook 侧有兜底。 */
    private static void startRightSettingWatch() {
        if (rightSettingWatching) {
            return;
        }
        synchronized (CamFixModule.class) {
            if (rightSettingWatching) {
                return;
            }
            rightSettingWatching = true;
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Context context = appContext();
                        if (context == null) {
                            rightSettingWatching = false;
                            return;
                        }
                        // 观察者回调也走自己的 looper：onChange 里是 Settings 的跨进程读，
                        // 不能落在主线程上。
                        android.os.HandlerThread looper =
                                new android.os.HandlerThread("camfix-shortcut-setting");
                        looper.start();
                        android.os.Handler handler = new android.os.Handler(looper.getLooper());
                        for (int attempt = 0; attempt < 10; attempt++) {
                            String value = readRightSetting(context);
                            if (value != null) {
                                rightSetting = value;
                                context.getContentResolver().registerContentObserver(
                                        Settings.Secure.getUriFor(SHORTCUT_SETTING_RIGHT), false,
                                        new android.database.ContentObserver(handler) {
                                            @Override
                                            public void onChange(boolean selfChange) {
                                                String updated = readRightSetting(context);
                                                if (updated != null) {
                                                    rightSetting = updated;
                                                }
                                            }
                                        });
                                return;
                            }
                            Thread.sleep(1000L);
                        }
                        looper.quitSafely();
                        rightSettingWatching = false;
                    } catch (Throwable t) {
                        rightSettingWatching = false;
                    }
                }
            }, "camfix-shortcut-setting");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private static Context appContext() {
        Context cached = cachedContext;
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> clazz = Class.forName("android.app.ActivityThread");
            Object application = clazz.getMethod("currentApplication").invoke(null);
            if (application instanceof Context) {
                cachedContext = (Context) application;
                return cachedContext;
            }
            Object thread = clazz.getMethod("currentActivityThread").invoke(null);
            if (thread == null) {
                return null;
            }
            Context system = (Context) clazz.getMethod("getSystemContext").invoke(thread);
            cachedContext = system;
            return system;
        } catch (Throwable t) {
            return null;
        }
    }

    /** AOD 相机图标按固定比例缩放（结果随配置串缓存）。 */
    private static Drawable cameraIcon(Context ctx, String setting) {
        synchronized (ICON_LOCK) {
            if (iconCache != null && setting.equals(iconKey)) return iconCache;
            try {
                Context aod = ctx.createPackageContext(AOD_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
                Drawable base = aod.getDrawable(AOD_CAMERA_ICON);
                iconCache = new ScaledDrawable(base, CAMERA_ICON_SCALE);
                iconKey = setting;
            } catch (Throwable t) {
                iconCache = null;
            }
            return iconCache;
        }
    }

    /** 把子 drawable 缩到 scale 倍居中呈现：intrinsic 报告缩小后的尺寸，绘制时子图填满 bounds。 */
    private static final class ScaledDrawable extends Drawable {
        private final Drawable child;
        private final float scale;

        ScaledDrawable(Drawable child, float scale) {
            this.child = child;
            this.scale = scale;
        }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            child.setBounds(b.left, b.top, b.right, b.bottom);
            child.draw(canvas);
        }

        @Override public int getIntrinsicWidth() {
            int w = child.getIntrinsicWidth();
            return w < 0 ? -1 : Math.round(w * scale);
        }

        @Override public int getIntrinsicHeight() {
            int h = child.getIntrinsicHeight();
            return h < 0 ? -1 : Math.round(h * scale);
        }

        @Override public void setAlpha(int alpha) { child.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { child.setColorFilter(cf); }
        @Override public int getOpacity() { return child.getOpacity(); }
        @Override public boolean setState(int[] stateSet) { return child.setState(stateSet); }
        @Override public boolean isStateful() { return child.isStateful(); }
    }

    // ------------------------------------------------------------------ AOD: 候选列表

    private void injectCameraShortcut(PackageReadyParam param) {
        Class<?> dataUtils;
        try {
            dataUtils = Class.forName(DATA_UTILS_CLASS, false, param.getClassLoader());
        } catch (ClassNotFoundException e) {
            log(Log.ERROR, TAG, "DataUtils not found in aod", e);
            return;
        }

        Object instance = staticField(dataUtils, "INSTANCE");
        Method fromJson = method(dataUtils, "getShortcutDataFromJson", 1);
        if (instance == null || fromJson == null) {
            log(Log.WARN, TAG, "aod: DataUtils unavailable, injection skipped");
            return;
        }
        fromJson.setAccessible(true);
        dataUtilsInstance = instance;
        shortcutFromJson = fromJson;

        // 设置页按白名单里的「包-TAG」来问，相机没有对应条目 → 直接供给
        int hooked = 0;
        for (Method candidate : dataUtils.getDeclaredMethods()) {
            String name = candidate.getName();
            if (!name.equals("fetchShortcutFromApp") && !name.equals("getShortcutDataFromApplication")) continue;
            if (candidate.getParameterCount() < 2) continue;
            final boolean returnsList = name.endsWith("Application");
            hook(candidate).intercept(chain -> {
                if (!FixSettings.lockCamera()) return chain.proceed();
                boolean askingCamera = false;
                for (Object arg : chain.getArgs()) {
                    if (CAMERA_PACKAGE.equals(arg)) {
                        askingCamera = true;
                        break;
                    }
                }
                if (!askingCamera) return chain.proceed();
                Object entity = buildCameraEntity();
                if (entity == null) return chain.proceed();
                log(Log.INFO, TAG, "aod: supplied camera shortcut on " + name);
                return returnsList ? java.util.Collections.singletonList(entity) : entity;
            });
            hooked++;
            log(Log.INFO, TAG, "aod: hooked " + name);
        }
        log(hooked > 0 ? Log.INFO : Log.WARN, TAG, "aod: camera query hooks = " + hooked);

        hookAdapter(param.getClassLoader());
    }

    /**
     * 兜底路径：适配器构造时若列表里还没有相机就补一条。
     *
     * <p>正常情况下选择页的候选列表由 {@code fetchShortcutFromApp} /
     * {@code getShortcutDataFromApplication} 两个 hook 供给，这里只是保险——ROM 若改了列表
     * 的装配方式，靠它仍然能看到相机条目。可以删除而不影响已验证的行为。
     */
    private void hookAdapter(ClassLoader loader) {
        try {
            Class<?> adapter = Class.forName(ADAPTER_CLASS, false, loader);
            Class<?> itemClass = Class.forName(SHORTCUT_ITEM_CLASS, false, loader);
            Constructor<?> ctor = null;
            for (Constructor<?> c : adapter.getDeclaredConstructors()) {
                if (c.getParameterCount() == 2) {
                    ctor = c;
                    break;
                }
            }
            if (ctor == null) return;
            shortcutItemCtor = itemClass.getDeclaredConstructors()[0];
            shortcutItemCtor.setAccessible(true);

            hook(ctor).intercept(chain -> {
                try {
                    if (!FixSettings.lockCamera()) return chain.proceed();
                    Object items = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                    if (items instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<Object> list = (List<Object>) items;
                        boolean hasCamera = false;
                        for (Object item : list) {
                            Object e = invoke(item, "getShortcutEntity");
                            if (e != null && CAMERA_PACKAGE.equals(stringOf(e, "getTargetPackage"))) {
                                hasCamera = true;
                                break;
                            }
                        }
                        // list 可能是 ROM 传进来的不可变集合，加不进去就算了
                        if (!hasCamera && shortcutItemCtor != null) {
                            Object entity = buildCameraEntity();
                            if (entity != null) {
                                list.add(shortcutItemCtor.newInstance(entity, false));
                                log(Log.INFO, TAG, "aod: camera entry added to adapter");
                            }
                        }
                    }
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "aod: adapter injection failed", t);
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "aod: adapter hooked");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "aod: adapter hook failed: " + t);
        }
    }

    /** 构造带名字和图标的相机条目（候选列表里显示用）。 */
    private Object buildCameraEntity() {
        try {
            Object entity = shortcutFromJson.invoke(dataUtilsInstance, CAMERA_SHORTCUT_JSON);
            if (entity == null) return null;
            Context ctx = currentContext();
            if (ctx != null) applyCameraNameAndIcon(entity, ctx);
            forceAvailable(entity);
            return entity;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "aod: build camera entity failed: " + t);
            return null;
        }
    }

    // ------------------------------------------------------------------ helpers

    /** 候选列表条目的名字和图标（图标用 AOD 自己的相机矢量图）。 */
    private void applyCameraNameAndIcon(Object entity, Context context) {
        String label = "相机";
        Drawable icon = null;
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(CAMERA_PACKAGE, 0);
            CharSequence appLabel = pm.getApplicationLabel(info);
            if (appLabel != null && appLabel.length() > 0) label = appLabel.toString();
        } catch (Throwable ignored) {
            // 用默认名
        }
        icon = loadAodCameraIcon();
        if (icon == null) {
            try {
                icon = context.getPackageManager().getApplicationIcon(CAMERA_PACKAGE);
            } catch (Throwable ignored) {
                // 没有图标也能用
            }
        }
        try {
            Method setter = entity.getClass().getMethod("setShortcutName", String.class);
            setter.invoke(entity, label);
            Method tracker = entity.getClass().getMethod("setShortcutTrackName", String.class);
            tracker.invoke(entity, label);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "aod: setShortcutName failed: " + t);
        }
        if (icon != null) setDrawable(entity, icon);
    }

    /** 加载 AOD 的相机矢量图（当前进程是 AOD 时直接用本进程 Context）。 */
    private static Drawable loadAodCameraIcon() {
        try {
            Context base = currentContext();
            if (base == null) return null;
            Context aod = AOD_PACKAGE.equals(base.getPackageName())
                    ? base
                    : base.createPackageContext(AOD_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
            return aod.getDrawable(AOD_CAMERA_ICON);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void setDrawable(Object entity, Drawable icon) {
        try {
            Method setter = entity.getClass().getMethod("setDrawable", Drawable.class);
            setter.setAccessible(true);
            setter.invoke(entity, icon);
        } catch (Throwable ignored) {
            // 设不上就算了
        }
    }

    private static void forceAvailable(Object entity) {
        try {
            Method m = entity.getClass().getMethod("setAvailable", boolean.class);
            m.setAccessible(true);
            m.invoke(entity, true);
        } catch (Throwable ignored) {
            // 无所谓
        }
    }

    private static Context currentContext() {
        Context cached = cachedContext;
        if (cached != null) return cached;
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method m = activityThread.getMethod("currentApplication");
            Object app = m.invoke(null);
            if (app instanceof Context) {
                cachedContext = (Context) app;
                return (Context) app;
            }
        } catch (Throwable ignored) {
            // 拿不到就算了
        }
        return null;
    }

    private static String stringOf(Object target, String getter) {
        try {
            Method m = target.getClass().getMethod(getter);
            Object v = m.invoke(target);
            return v instanceof String ? (String) v : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Object invoke(Object target, String getter) {
        try {
            Method m = target.getClass().getMethod(getter);
            return m.invoke(target);
        } catch (Exception e) {
            return null;
        }
    }

    private static Object staticField(Class<?> type, String name) {
        try {
            Field f = type.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static Method method(Class<?> type, String name, int paramCount) {
        for (Method m : type.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == paramCount) return m;
        }
        return null;
    }

    private static boolean isAllowed(Object record) {
        Object packageName = readField(record, "packageName");
        String pkg = packageName instanceof String ? (String) packageName : null;
        if (pkg == null) {
            Object info = readField(record, "info");
            Object infoPackage = info == null ? null : readField(info, "packageName");
            pkg = infoPackage instanceof String ? (String) infoPackage : null;
        }
        if (pkg == null) return false;
        if (CAMERA_PACKAGE.equals(pkg)) {
            return FixSettings.quickCamera() || FixSettings.lockCamera();
        }
        return SHORTCUT_PACKAGES.contains(pkg) && FixSettings.unlockShortcuts();
    }

    private static Object readField(Object target, String name) {
        if (target == null) return null;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

}
