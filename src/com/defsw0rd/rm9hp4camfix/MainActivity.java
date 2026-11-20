package com.defsw0rd.rm9hp4camfix;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Switch board: four toggles plus a footer.
 *
 * <p>Values are written to {@code Settings.Global} (one time grant needed:
 * {@code adb shell pm grant com.defsw0rd.rm9hp4camfix android.permission.WRITE_SECURE_SETTINGS});
 * the hooked processes mirror them into memory, so flipping a switch takes effect within
 * milliseconds. A restart is still what makes every already running process pick the new value up,
 * hence the hint on each toggle.
 */
public final class MainActivity extends Activity {

    private static final String TAG = "RM9HP4CamFix";
    private static final String PREFERENCES = "camfix_switches";
    private static final String RESTART_HINT = "重启生效";
    private static final String PROJECT_URL = "https://github.com/DefSw0rd/rm9hp4camfix";

    private SharedPreferences preferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFERENCES, 0);
        seedIfNeeded();

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(8), pad, dp(20));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("RM9HP4 Cam/Lock Fix");
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, dp(4), 0, dp(2));
        root.addView(title);

        section(root, "相机类");
        toggle(root, FixSettings.KEY_QUICK_CAMERA, "修复急速相机",
                "修复双击音量下键快速调出相机的功能", true);
        toggle(root, FixSettings.KEY_LOCK_CAMERA, "修复锁屏相机",
                "可以在 锁屏-自定义快捷功能 里选定锁屏相机并启用", true);
        toggle(root, FixSettings.KEY_BOOST, "相机启动加速",
                "通过后台预载相机来加快相机启动速度（开机后台预载 + 常态化 cgroup v2 冻结）", true);

        section(root, "锁屏类");
        toggle(root, FixSettings.KEY_UNLOCK_SHORTCUTS, "常用功能免密",
                "允许在不输入锁屏密码的情况下使用锁屏左/右下角的自定义快捷功能"
                        + "（计算器、计时、笔记、秒表等）", false);

        TextView link = new TextView(this);
        link.setText("项目地址：" + PROJECT_URL);
        link.setTextSize(12);
        link.setPadding(0, dp(28), 0, 0);
        link.setTextColor(0xFF1A73E8);
        link.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View view) {
                openProjectPage();
            }
        });
        root.addView(link);

        TextView note = new TextView(this);
        note.setText("项目应该不会更新，开放源代码仅供大伙放心用审计用途！");
        note.setTextSize(12);
        root.addView(note);

        setContentView(scroll);
    }

    /**
     * Publish the defaults on first start so the hooked processes have something to read.
     *
     * <p>只写**尚不存在**的键：清应用数据后重装不会把用户已有的设置覆盖回默认值。
     */
    private void seedIfNeeded() {
        if (preferences.getBoolean("seeded", false)) {
            return;
        }
        seedIfMissing(FixSettings.KEY_QUICK_CAMERA, true);
        seedIfMissing(FixSettings.KEY_LOCK_CAMERA, true);
        seedIfMissing(FixSettings.KEY_BOOST, true);
        seedIfMissing(FixSettings.KEY_UNLOCK_SHORTCUTS, false);
        preferences.edit().putBoolean("seeded", true).apply();
    }

    private void seedIfMissing(String key, boolean defaultValue) {
        try {
            if (Settings.Global.getString(getContentResolver(), key) == null) {
                writeGlobal(key, defaultValue);
            }
        } catch (Throwable t) {
            Log.w(TAG, "seed " + key + " skipped", t);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyStatusBarAppearance();
    }

    /**
     * 状态栏图标明暗只由 APPEARANCE_LIGHT_STATUS_BARS 这一位决定（setStatusBarColor 在 Android 15+
     * 已废弃且 MIUI 上是透明的）。窗口背景来自 Theme.DeviceDefault.DayNight，所以按当前是否夜间模式
     * 决定：浅底 → 黑字（声明 LIGHT_STATUS_BARS），深底 → 白字（清掉该位）。
     */
    private void applyStatusBarAppearance() {
        boolean lightBackground = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES;
        try {
            Window window = getWindow();
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
                    controller.setSystemBarsAppearance(lightBackground ? mask : 0, mask);
                }
            } else {
                View decor = window.getDecorView();
                int flags = decor.getSystemUiVisibility();
                flags = lightBackground
                        ? (flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
                        : (flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
                decor.setSystemUiVisibility(flags);
            }
        } catch (Throwable t) {
            Log.w(TAG, "status bar appearance failed", t);
        }
    }

    private void section(LinearLayout parent, String text) {
        TextView header = new TextView(this);
        header.setText(text);
        header.setTextSize(14);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(0, dp(18), 0, dp(6));
        parent.addView(header);
    }

    private void toggle(LinearLayout parent, final String key, String title, String description,
            boolean defaultValue) {
        final Switch toggle = new Switch(this);
        toggle.setText(title);
        toggle.setTextSize(16);
        toggle.setChecked(readGlobal(key, defaultValue));
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                writeGlobal(key, checked);
                preferences.edit().putBoolean(key, checked).apply();
                Toast.makeText(MainActivity.this, RESTART_HINT, Toast.LENGTH_SHORT).show();
            }
        });

        TextView desc = new TextView(this);
        desc.setText(description);
        desc.setTextSize(13);
        desc.setPadding(dp(8), dp(2), 0, dp(10));

        parent.addView(toggle);
        parent.addView(desc);
    }

    private boolean readGlobal(String key, boolean defaultValue) {
        try {
            return Settings.Global.getInt(getContentResolver(), key, defaultValue ? 1 : 0) != 0;
        } catch (Throwable t) {
            return preferences.getBoolean(key, defaultValue);
        }
    }

    private void writeGlobal(String key, boolean value) {
        try {
            Settings.Global.putInt(getContentResolver(), key, value ? 1 : 0);
        } catch (Throwable t) {
            Log.w(TAG, "write " + key + " failed", t);
            Toast.makeText(this, "缺少 WRITE_SECURE_SETTINGS 权限，请用 adb 授权",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void openProjectPage() {
        try {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(PROJECT_URL)));
        } catch (Throwable t) {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
