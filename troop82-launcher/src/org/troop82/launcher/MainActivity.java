package org.troop82.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Troop 82 home screen: full-screen backdrop, clock, a row of apps and a row of shortcuts. */
public class MainActivity extends Activity implements Tiles.FocusListener {

    /** Apps on the top row, in this order. Missing or disabled apps are skipped. */
    static final String[] FAVORITES = {
            "com.plexapp.android",
            "com.google.android.youtube.tvunplugged",
            "ar.tvplayer.tv",
            "org.smarttube.beta",
            "com.liskovsoft.smarttubetv.beta",  // older SmartTube builds use this package name
            "com.netflix.ninja",
    };

    /** Utility apps shown as small tiles in the bottom row. */
    static final String[] SHORTCUT_APPS = {
            "com.esaba.downloader",
            "com.android.vending",
    };

    /** Re-download announcements and events this often while the home screen is showing. */
    static final long REFRESH_MS = 15 * 60 * 1000;

    /** Set from a PC with: adb shell am start -n org.troop82.launcher/.MainActivity --es sheet_url "<published CSV link>" */
    static final String EXTRA_SHEET_URL = "sheet_url";
    static final String PREF_SHEET_URL = "sheet_url";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            loadNews();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    private NewsPanel newsPanel;
    private LinearLayout appRow;
    private LinearLayout shortcutRow;
    private TextView focusLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());
        handleConfigIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleConfigIntent(intent);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("config", MODE_PRIVATE);
    }

    private void handleConfigIntent(Intent intent) {
        if (intent == null || !intent.hasExtra(EXTRA_SHEET_URL)) return;
        String url = intent.getStringExtra(EXTRA_SHEET_URL);
        // Apply once, so a later re-creation of the activity can't undo a newer setting.
        intent.removeExtra(EXTRA_SHEET_URL);
        prefs().edit().putString(PREF_SHEET_URL, url == null ? "" : url.trim()).apply();
        loadNews();
    }

    /** Refreshes on the shared background worker, then updates the panel on the main thread. */
    private void loadNews() {
        String sheetUrl = prefs().getString(PREF_SHEET_URL, null);
        Announcements.loadAsync(this, sheetUrl, new Announcements.Callback() {
            @Override
            public void onLoaded(final Announcements.Snapshot snap) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!isDestroyed()) newsPanel.show(snap);
                    }
                });
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Rebuild every time so newly installed or disabled apps show up correctly.
        populateRows();
        if (appRow.getChildCount() > 0) appRow.getChildAt(0).requestFocus();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back does nothing.
    }

    private int dp(float v) {
        return Tiles.dp(this, v);
    }

    private View buildLayout() {
        FrameLayout root = new FrameLayout(this);

        ImageView backdrop = new ImageView(this);
        backdrop.setImageResource(R.drawable.bg);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Darken the bottom so tiles and text stay readable over the artwork.
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x99000000, 0xD9000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300), Gravity.BOTTOM));

        root.addView(buildClock(), clockParams());

        // Announcements sit in the open sky to the right of the troop badge.
        newsPanel = new NewsPanel(this);
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(dp(490),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        np.leftMargin = dp(180);
        np.topMargin = dp(34);
        root.addView(newsPanel, np);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);

        appRow = newRow();
        bottom.addView(wrapInScroller(appRow, dp(14)));

        focusLabel = new TextView(this);
        focusLabel.setTextColor(0xFFFFFFFF);
        focusLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        focusLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        focusLabel.setShadowLayer(6, 0, 1, 0xCC000000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(2);
        lp.bottomMargin = dp(6);
        bottom.addView(focusLabel, lp);

        shortcutRow = newRow();
        bottom.addView(wrapInScroller(shortcutRow, dp(10)));

        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        bp.bottomMargin = dp(18);
        root.addView(bottom, bp);
        return root;
    }

    private View buildClock() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.END);

        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("H:mm");
        time.setTextColor(0xFFFFFFFF);
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        time.setShadowLayer(8, 0, 2, 0x99000000);
        box.addView(time);

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, MMMM d");
        date.setFormat24Hour("EEEE, MMMM d");
        date.setTextColor(0xDDFFFFFF);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        date.setShadowLayer(6, 0, 1, 0x99000000);
        box.addView(date);
        return box;
    }

    private FrameLayout.LayoutParams clockParams() {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        p.topMargin = dp(24);
        p.rightMargin = dp(44);
        return p;
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        return row;
    }

    /** Horizontal scroller with padding so zoomed tiles aren't clipped. */
    private View wrapInScroller(LinearLayout row, int vPad) {
        HorizontalScrollView sv = new HorizontalScrollView(this);
        sv.setHorizontalScrollBarEnabled(false);
        sv.setClipChildren(false);
        sv.setClipToPadding(false);
        sv.setPadding(dp(44), vPad, dp(44), vPad);
        sv.addView(row);
        return sv;
    }

    private void addToRow(LinearLayout row, View tile, int gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                tile.getLayoutParams().width, tile.getLayoutParams().height);
        if (row.getChildCount() > 0) lp.leftMargin = gap;
        row.addView(tile, lp);
    }

    private void populateRows() {
        PackageManager pm = getPackageManager();

        appRow.removeAllViews();
        int aw = dp(152), ah = dp(86);
        for (String pkg : FAVORITES) {
            Tiles.App app = Tiles.App.load(pm, pkg);
            if (app != null) addToRow(appRow, Tiles.appTile(this, app, aw, ah, this), dp(18));
        }

        shortcutRow.removeAllViews();
        int sw = dp(104), sh = dp(46);
        for (TvInputInfo input : hdmiInputs()) {
            CharSequence label = input.loadCustomLabel(this);
            if (label == null || label.length() == 0) label = input.loadLabel(this);
            Intent i = new Intent(Intent.ACTION_VIEW,
                    TvContract.buildChannelUriForPassthroughInput(input.getId()));
            addToRow(shortcutRow, Tiles.textTile(this, label, i, sw, sh, this), dp(14));
        }
        for (String pkg : SHORTCUT_APPS) {
            Tiles.App app = Tiles.App.load(pm, pkg);
            if (app != null) addToRow(shortcutRow, Tiles.appTile(this, app, sw, sh, this), dp(14));
        }
        addToRow(shortcutRow, Tiles.textTile(this, "All Apps",
                new Intent(this, AllAppsActivity.class), sw, sh, this), dp(14));
        addToRow(shortcutRow, Tiles.textTile(this, "Settings",
                new Intent(Settings.ACTION_SETTINGS), sw, sh, this), dp(14));
    }

    private List<TvInputInfo> hdmiInputs() {
        List<TvInputInfo> out = new ArrayList<>();
        TvInputManager tim = getSystemService(TvInputManager.class);
        if (tim == null) return out;
        for (TvInputInfo info : tim.getTvInputList()) {
            if (info.getType() == TvInputInfo.TYPE_HDMI && !info.isHidden(this)) out.add(info);
        }
        Collections.sort(out, new Comparator<TvInputInfo>() {
            @Override
            public int compare(TvInputInfo a, TvInputInfo b) {
                return a.getId().compareTo(b.getId());
            }
        });
        return out;
    }

    @Override
    public void onTileFocused(View tile, CharSequence label) {
        // Only the top row shows a name under the focused tile, like on Apple TV.
        if (tile.getParent() != appRow) {
            focusLabel.setText("");
            return;
        }
        focusLabel.setText(label);
        focusLabel.post(new Runnable() {
            @Override
            public void run() {
                int[] tileLoc = new int[2];
                int[] parentLoc = new int[2];
                tile.getLocationOnScreen(tileLoc);
                ((View) focusLabel.getParent()).getLocationOnScreen(parentLoc);
                float x = tileLoc[0] - parentLoc[0]
                        + tile.getWidth() * tile.getScaleX() / 2f - focusLabel.getWidth() / 2f;
                focusLabel.setTranslationX(Math.max(0, x));
            }
        });
    }
}
