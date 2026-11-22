package org.troop82.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Grid of every TV app that is installed and enabled. */
public class AllAppsActivity extends Activity implements Tiles.FocusListener {

    private static final int COLUMNS = 5;
    private TextView title;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = Tiles.dp(this, 44);

        FrameLayout root = new FrameLayout(this);
        ImageView backdrop = new ImageView(this);
        backdrop.setImageResource(R.drawable.bg);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setColorFilter(0xB3000000);
        root.addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, Tiles.dp(this, 28), pad, 0);

        title = new TextView(this);
        title.setText("All Apps");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        column.addView(title);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(Tiles.dp(this, 12), Tiles.dp(this, 16), Tiles.dp(this, 12), Tiles.dp(this, 24));

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(COLUMNS);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        scroll.addView(grid);
        column.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(column);
        setContentView(root);

        int w = Tiles.dp(this, 150), h = Tiles.dp(this, 84), gap = Tiles.dp(this, 18);
        for (Tiles.App app : loadApps()) {
            View tile = Tiles.appTile(this, app, w, h, this);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = w;
            lp.height = h;
            lp.setMargins(0, 0, gap, gap);
            grid.addView(tile, lp);
        }
        if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();
    }

    private List<Tiles.App> loadApps() {
        PackageManager pm = getPackageManager();
        Set<String> seen = new HashSet<>();
        List<Tiles.App> apps = new ArrayList<>();
        String[] categories = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String category : categories) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
                Tiles.App app = Tiles.App.load(pm, pkg);
                if (app != null) apps.add(app);
            }
        }
        Collections.sort(apps, new Comparator<Tiles.App>() {
            @Override
            public int compare(Tiles.App a, Tiles.App b) {
                return a.label.toString().compareToIgnoreCase(b.label.toString());
            }
        });
        return apps;
    }

    @Override
    public void onTileFocused(View tile, CharSequence label) {
        title.setText(label);
    }
}
