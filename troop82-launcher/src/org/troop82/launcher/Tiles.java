package org.troop82.launcher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

/** Builds the rounded, zoom-on-focus tiles used on every screen. */
final class Tiles {

    /** Called when a tile gains focus, so the screen can show its name. */
    interface FocusListener {
        void onTileFocused(View tile, CharSequence label);
    }

    static final float FOCUS_SCALE = 1.12f;

    private Tiles() {}

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    /** A launchable app: its banner if it has one, otherwise its icon on a dark tile. */
    static final class App {
        final String pkg;
        final CharSequence label;
        final Intent intent;
        final Drawable banner;
        final Drawable icon;

        App(String pkg, CharSequence label, Intent intent, Drawable banner, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.intent = intent;
            this.banner = banner;
            this.icon = icon;
        }

        /** Returns null when the package is missing, disabled or has no launch activity. */
        static App load(PackageManager pm, String pkg) {
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) return null;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                if (!ai.enabled) return null;
                Drawable banner = null;
                ResolveInfo ri = pm.resolveActivity(launch, 0);
                if (ri != null && ri.activityInfo != null) banner = ri.activityInfo.loadBanner(pm);
                if (banner == null) banner = pm.getApplicationBanner(ai);
                return new App(pkg, pm.getApplicationLabel(ai), launch, banner, pm.getApplicationIcon(ai));
            } catch (PackageManager.NameNotFoundException e) {
                return null;
            }
        }
    }

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private static FrameLayout baseTile(final Context c, int w, int h, final CharSequence label,
                                        final FocusListener listener) {
        final float radius = dp(c, 10);
        final FrameLayout tile = new FrameLayout(c);
        tile.setLayoutParams(new FrameLayout.LayoutParams(w, h));
        tile.setBackground(rounded(0x40FFFFFF, radius));
        tile.setClipToOutline(true);
        tile.setFocusable(true);
        tile.setFocusableInTouchMode(true);
        tile.setContentDescription(label);

        final GradientDrawable ring = new GradientDrawable();
        ring.setColor(Color.TRANSPARENT);
        ring.setCornerRadius(radius);
        ring.setStroke(dp(c, 2), 0xE6FFFFFF);

        tile.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                float s = hasFocus ? FOCUS_SCALE : 1f;
                v.animate().scaleX(s).scaleY(s)
                        .translationZ(hasFocus ? dp(c, 14) : 0)
                        .setDuration(150).start();
                tile.setForeground(hasFocus ? ring : null);
                if (hasFocus && listener != null) listener.onTileFocused(v, label);
            }
        });
        return tile;
    }

    static View appTile(final Activity a, final App app, int w, int h, FocusListener listener) {
        FrameLayout tile = baseTile(a, w, h, app.label, listener);
        ImageView img = new ImageView(a);
        if (app.banner != null) {
            img.setImageDrawable(app.banner);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            tile.addView(img, new FrameLayout.LayoutParams(w, h));
        } else {
            tile.setBackground(rounded(0xCC14213D, dp(a, 10)));
            img.setImageDrawable(app.icon);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int s = Math.round(h * 0.6f);
            tile.addView(img, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        }
        tile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launch(a, app.intent);
            }
        });
        return tile;
    }

    /** A plain text tile for inputs, settings and the like. */
    static View textTile(final Activity a, final CharSequence label, final Intent intent,
                         int w, int h, FocusListener listener) {
        FrameLayout tile = baseTile(a, w, h, label, listener);
        tile.setBackground(rounded(0x9914213D, dp(a, 10)));
        TextView t = new TextView(a);
        t.setText(label);
        t.setTextColor(0xFFF2E8D5);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setGravity(Gravity.CENTER);
        tile.addView(t, new FrameLayout.LayoutParams(w, h));
        tile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launch(a, intent);
            }
        });
        return tile;
    }

    static void launch(Activity a, Intent intent) {
        try {
            a.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(a, "Can't open that right now", Toast.LENGTH_SHORT).show();
        }
    }
}
