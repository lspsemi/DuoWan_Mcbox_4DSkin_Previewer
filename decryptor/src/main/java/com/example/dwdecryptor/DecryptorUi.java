package com.example.dwdecryptor;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;

import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;

final class DecryptorUi {
    private DecryptorUi() {}

    static void applyDynamicColors(Activity activity) {
        DynamicColors.applyToActivityIfAvailable(activity);
        int surface = color(activity, com.google.android.material.R.attr.colorSurface, Color.WHITE);
        activity.getWindow().setStatusBarColor(surface);
        activity.getWindow().setNavigationBarColor(surface);
        boolean light = (0.299 * Color.red(surface) + 0.587 * Color.green(surface)
                + 0.114 * Color.blue(surface)) >= 150;
        activity.getWindow().getDecorView().setSystemUiVisibility(light
                ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                : 0);
    }

    static int color(Activity activity, int attr, int fallback) {
        return MaterialColors.getColor(activity, attr, fallback);
    }

    static int surface(Activity activity) {
        return color(activity, com.google.android.material.R.attr.colorSurface, Color.WHITE);
    }

    static int surfaceContainer(Activity activity) {
        return color(activity, com.google.android.material.R.attr.colorSurfaceContainerLow, 0xfff3f0f7);
    }

    static int onSurface(Activity activity) {
        return color(activity, com.google.android.material.R.attr.colorOnSurface, 0xff1c1b1f);
    }
}
