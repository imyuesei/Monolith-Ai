package com.monolith.notcheyes;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Hosts the notch in an accessibility overlay. That window layer sits above
 * the status bar, which is what lets the notch receive taps at the top of
 * the screen. It reads nothing from the screen.
 */
public class NotchAccessibilityService extends AccessibilityService {

    private static volatile boolean running;
    private NotchController notch;
    private SharedPreferences prefs;

    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (p, key) -> {
        if ("enabled".equals(key)) sync();
    };

    public static boolean isRunning() {
        return running;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        running = true;
        prefs = getSharedPreferences(NotchController.PREFS, MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(listener);
        notch = new NotchController(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
        // the plain overlay is no longer needed
        stopService(new Intent(this, NotchService.class));
        prefs.edit().putBoolean("enabled", true).apply();
        sync();
    }

    private void sync() {
        if (notch == null) return;
        if (prefs.getBoolean("enabled", false)) notch.show();
        else notch.hide();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        running = false;
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(listener);
        if (notch != null) notch.hide();
        super.onDestroy();
    }
}
