package com.monolith.notcheyes;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private Button toggle;
    private Button tapFix;
    private LinearLayout panel;
    private boolean pendingStart;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(NotchService.PREFS, MODE_PRIVATE);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        EyesView eyes = new EyesView(this, true);
        FrameLayout.LayoutParams eyesLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(eyes, eyesLp);

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(12), dp(24), dp(24));

        TextView hint = label("Touch to look around · tap to make it smile");
        hint.setGravity(Gravity.CENTER);
        hint.setAlpha(0.5f);
        panel.addView(hint);

        panel.addView(label("Notch size"));
        panel.addView(slider("size", 50));
        panel.addView(label("Move up / down"));
        panel.addView(slider("offset", 50));

        toggle = new Button(this);
        toggle.setAllCaps(false);
        toggle.setTextColor(Color.BLACK);
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(Color.WHITE);
        btnBg.setCornerRadius(dp(28));
        toggle.setBackground(btnBg);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        blp.topMargin = dp(16);
        panel.addView(toggle, blp);
        toggle.setOnClickListener(v -> {
            if (prefs.getBoolean("enabled", false)) stopNotch();
            else startNotch();
        });

        tapFix = new Button(this);
        tapFix.setAllCaps(false);
        tapFix.setTextColor(Color.WHITE);
        tapFix.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        GradientDrawable tapBg = new GradientDrawable();
        tapBg.setColor(Color.TRANSPARENT);
        tapBg.setStroke(dp(1), 0x66FFFFFF);
        tapBg.setCornerRadius(dp(28));
        tapFix.setBackground(tapBg);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        tlp.topMargin = dp(10);
        panel.addView(tapFix, tlp);
        tapFix.setOnClickListener(v -> startActivity(
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        root.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            @SuppressWarnings("deprecation")
            int bottom = insets.getSystemWindowInsetBottom();
            panel.setPadding(dp(24), dp(12), dp(24), dp(24) + bottom);
            return insets;
        });

        setContentView(root);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingStart && Settings.canDrawOverlays(this)) {
            pendingStart = false;
            startNotch();
        }
        refresh();
    }

    private void startNotch() {
        if (NotchAccessibilityService.isRunning()) {
            prefs.edit().putBoolean("enabled", true).apply();
            refresh();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            pendingStart = true;
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        prefs.edit().putBoolean("enabled", true).apply();
        startForegroundService(new Intent(this, NotchService.class));
        refresh();
    }

    private void stopNotch() {
        prefs.edit().putBoolean("enabled", false).apply();
        stopService(new Intent(this, NotchService.class));
        refresh();
    }

    private void refresh() {
        boolean on = prefs.getBoolean("enabled", false);
        toggle.setText(on ? "Hide notch" : "Show notch on home screen");
        boolean acc = NotchAccessibilityService.isRunning();
        tapFix.setText(acc ? "✓ Tapping enabled (Accessibility on)"
                : "Make the notch tappable → Accessibility → Notch Eyes");
        tapFix.setAlpha(acc ? 0.5f : 1f);
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setPadding(0, dp(10), 0, dp(2));
        return t;
    }

    private SeekBar slider(String key, int def) {
        SeekBar s = new SeekBar(this);
        s.setMax(100);
        s.setProgress(prefs.getInt(key, def));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int value, boolean fromUser) {
                prefs.edit().putInt(key, value).apply();
                if (prefs.getBoolean("enabled", false) && !NotchAccessibilityService.isRunning()
                        && Settings.canDrawOverlays(MainActivity.this)) {
                    startForegroundService(new Intent(MainActivity.this, NotchService.class));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        return s;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
