package com.example.dwpreviewer;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;

final class PreviewUi {
    private PreviewUi() {}

    static void applyDynamicColors(Activity activity) {
        DynamicColors.applyToActivityIfAvailable(activity);
        int surface = color(activity, com.google.android.material.R.attr.colorSurface, Color.WHITE);
        activity.getWindow().setStatusBarColor(surface);
        activity.getWindow().setNavigationBarColor(surface);
        int flags = isLight(surface)
                ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                : 0;
        activity.getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    static int color(Activity activity, int attr, int fallback) {
        return MaterialColors.getColor(activity, attr, fallback);
    }

    static int surface(Activity activity) {
        return color(activity, com.google.android.material.R.attr.colorSurface, Color.WHITE);
    }

    static int surfaceContainer(Activity activity) {
        int attr = Build.VERSION.SDK_INT >= 31
                ? com.google.android.material.R.attr.colorSurfaceContainerLow
                : com.google.android.material.R.attr.colorSurfaceVariant;
        return color(activity, attr, 0xfff3f0f7);
    }

    static int onSurface(Activity activity) {
        return color(activity, com.google.android.material.R.attr.colorOnSurface, 0xff1c1b1f);
    }

    static int primary(Activity activity) {
        return color(activity, com.google.android.material.R.attr.colorPrimary, 0xff6750a4);
    }

    static int onPrimary(Activity activity) {
        return color(activity, com.google.android.material.R.attr.colorOnPrimary, Color.WHITE);
    }

    private static boolean isLight(int color) {
        return (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) >= 150;
    }
}
