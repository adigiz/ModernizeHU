package com.carhome;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.DisplayMetrics;

/** Icons for Home: the NWD apps ship one 48 px icon, so they get a vector glyph; others their sharpest own. */
final class AppIcons {

    private static final int GREY = 0xFF48484A;

    private final Context context;
    private final PackageManager pm;
    private final int glyphSize;

    AppIcons(Context context) {
        this.context = context;
        pm = context.getPackageManager();
        glyphSize = Math.round(96 * context.getResources().getDisplayMetrics().density);
    }

    Drawable forActivity(ActivityInfo ai) {
        if ("com.android.launcher".equals(ai.packageName)) {
            switch (ai.name) {
                case "com.nwd.screenoff.ScreenOffActivity": return glyph(R.drawable.g_power, GREY);
                case "com.nwd.tools.reboot.RebootActivity": return glyph(R.drawable.g_restart, GREY);
                case "com.nwd.android.lock.screen.ui.LockScreenActivity": return glyph(R.drawable.g_clock, GREY);
                case "com.nwd.wheel.learning.MainActivity": return glyph(R.drawable.g_wheel, 0xFF0A84FF);
                case "com.nwd.android.toolsmanager.ToolsManagerActivity": return glyph(R.drawable.g_toolbox, 0xFF8E8E93);
                case "com.android.launcher2.WallpaperChooser": return glyph(R.drawable.g_image, 0xFFFF9F0A);
                default: break;
            }
        }
        switch (ai.packageName) {
            case "com.didi365.miudrive.navi": return glyph(R.drawable.g_cast, 0xFF5E5CE6);
            case "com.nwd.android.phone": return glyph(R.drawable.g_phone, 0xFF34C759);
            case "com.nwd.bt.music": return glyph(R.drawable.g_bluetooth, 0xFF5E5CE6);
            case "com.android.car.setting": return glyph(R.drawable.g_car, 0xFFFF9F0A);
            case "com.nwd.mycar": return glyph(R.drawable.g_car, 0xFF30B0C7);
            case "com.nwd.can.setting": return glyph(R.drawable.g_info, 0xFF5856D6);
            case "com.android.settings": return glyph(R.drawable.g_settings, 0xFF8E8E93);
            case "com.nwd.auxin": return glyph(R.drawable.g_input, 0xFFAF52DE);
            case "com.nwd.audioset": return glyph(R.drawable.g_equalizer, 0xFFFF375F);
            case "com.nwd.filemanager": return glyph(R.drawable.g_folder, 0xFF0A84FF);
            case "com.nwd.guidebookskin": return glyph(R.drawable.g_book, 0xFFA2845E);
            default: return ownIcon(ai);
        }
    }

    Drawable allApps() {
        return glyph(R.drawable.ic_apps, GREY);
    }

    /** The icon's highest-density version; the screen's own 160 dpi would pick the blurriest. */
    private Drawable ownIcon(ActivityInfo ai) {
        int res = ai.getIconResource();
        if (res != 0) {
            try {
                Resources r = pm.getResourcesForApplication(ai.applicationInfo);
                Drawable d = r.getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null);
                if (d != null) return d;
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
                // Fall back to the system's choice below.
            }
        }
        return ai.loadIcon(pm);
    }

    /** Drawn large so it only ever scales down. */
    private Drawable glyph(int glyphRes, int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(glyphSize * 0.225f);
        bg.setSize(glyphSize, glyphSize);
        LayerDrawable icon = new LayerDrawable(new Drawable[] {bg, context.getDrawable(glyphRes)});
        int inset = Math.round(glyphSize * 0.22f);
        icon.setLayerInset(1, inset, inset, inset, inset);
        return icon;
    }
}
