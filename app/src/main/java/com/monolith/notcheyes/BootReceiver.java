package com.monolith.notcheyes;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** Brings the notch back after a reboot if it was on. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        boolean enabled = context.getSharedPreferences(NotchService.PREFS, Context.MODE_PRIVATE)
                .getBoolean("enabled", false);
        if (enabled && Settings.canDrawOverlays(context)) {
            context.startForegroundService(new Intent(context, NotchService.class));
        }
    }
}
