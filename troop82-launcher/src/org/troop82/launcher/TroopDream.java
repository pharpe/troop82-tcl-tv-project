package org.troop82.launcher;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Troop 82 screensaver: the troop backdrop with one announcement or upcoming
 * event at a time, in large type, cross-fading every few seconds.
 */
public class TroopDream extends DreamService {

    static final long SLIDE_MS = 12_000;
    static final long REFRESH_MS = 15 * 60 * 1000;
    static final int MAX_EVENTS = 6;

    private static final int ORANGE = 0xFFE8913A;
    private static final int CREAM = 0xFFF2E8D5;

    /** One screen of the slideshow. */
    private static final class Slide {
        final String kicker;
        final int kickerColor;
        final String headline;
        final String detail;

        Slide(String kicker, int kickerColor, String headline, String detail) {
            this.kicker = kicker;
            this.kickerColor = kickerColor;
            this.headline = headline;
            this.detail = detail;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Slide> slides = new ArrayList<>();
    private int index = -1;

    private LinearLayout content;
    private TextView kicker;
    private TextView headline;
    private TextView detail;
    private LinearLayout dots;
    private ValueAnimator drift;
    private final List<ValueAnimator> animators = new ArrayList<>();
    private boolean alive;

    private final Runnable advance = new Runnable() {
        @Override
        public void run() {
            showNext();
            handler.postDelayed(this, SLIDE_MS);
        }
    };

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            load();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    private int dp(float v) {
        return Tiles.dp(this, v);
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        setContentView(buildLayout());
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        alive = true;
        slides.clear();
        index = -1;
        slides.add(welcomeSlide());
        showNext();
        handler.post(refresh);
        handler.postDelayed(advance, SLIDE_MS);
    }

    @Override
    public void onDreamingStopped() {
        stopEverything();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        stopEverything();
        super.onDetachedFromWindow();
    }

    private void stopEverything() {
        alive = false;
        handler.removeCallbacksAndMessages(null);
        for (ValueAnimator a : animators) a.cancel();
        animators.clear();
        if (content != null) content.animate().cancel();
    }

    private View buildLayout() {
        FrameLayout root = new FrameLayout(this);

        // Backdrop with a very slow zoom, so the picture is never perfectly still.
        ImageView backdrop = new ImageView(this);
        backdrop.setImageResource(R.drawable.bg);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        backdrop.setPivotX(0);
        backdrop.setPivotY(0);
        ObjectAnimator zoomX = ObjectAnimator.ofFloat(backdrop, View.SCALE_X, 1f, 1.06f);
        ObjectAnimator zoomY = ObjectAnimator.ofFloat(backdrop, View.SCALE_Y, 1f, 1.06f);
        for (ObjectAnimator z : new ObjectAnimator[]{zoomX, zoomY}) {
            z.setDuration(90_000);
            z.setRepeatMode(ValueAnimator.REVERSE);
            z.setRepeatCount(ValueAnimator.INFINITE);
            z.setInterpolator(new LinearInterpolator());
            z.start();
            animators.add(z);
        }

        // Darken only the lower part, behind the text, so the troop badge stays crisp.
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00050B1A, 0x00050B1A, 0x8C050B1A, 0xCC050B1A}));
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        clock.setTextColor(0xEEFFFFFF);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setShadowLayer(8, 0, 2, 0x99000000);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        cp.topMargin = dp(26);
        cp.rightMargin = dp(44);
        root.addView(clock, cp);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        kicker = new TextView(this);
        kicker.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        kicker.setLetterSpacing(0.16f);
        kicker.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        content.addView(kicker);

        headline = new TextView(this);
        headline.setTextColor(0xFFFFFFFF);
        headline.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        headline.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        headline.setLineSpacing(0, 1.08f);
        headline.setMaxLines(4);
        headline.setEllipsize(TextUtils.TruncateAt.END);
        headline.setShadowLayer(10, 0, 2, 0xAA000000);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(10);
        content.addView(headline, hp);

        detail = new TextView(this);
        detail.setTextColor(CREAM);
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        detail.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        detail.setShadowLayer(8, 0, 2, 0xAA000000);
        LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dp2.topMargin = dp(14);
        content.addView(detail, dp2);

        // Content sits below the badge that is part of the backdrop artwork.
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(600),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL);
        lp.leftMargin = dp(56);
        lp.topMargin = dp(70);
        root.addView(content, lp);

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        TextView site = new TextView(this);
        site.setText("TROOP 82  ·  DALLAS, TEXAS  ·  HPTROOP82.ORG");
        site.setTextColor(0xCCF2E8D5);
        site.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        site.setLetterSpacing(0.14f);
        site.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        footer.addView(site);
        dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dotsLp.leftMargin = dp(24);
        footer.addView(dots, dotsLp);
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        fp.leftMargin = dp(56);
        fp.bottomMargin = dp(30);
        root.addView(footer, fp);

        startDrift();
        return root;
    }

    /** Moves the text a few pixels every so often to avoid any burn-in. */
    private void startDrift() {
        drift = ValueAnimator.ofFloat(0f, 1f);
        drift.setDuration(240_000);
        drift.setRepeatMode(ValueAnimator.REVERSE);
        drift.setRepeatCount(ValueAnimator.INFINITE);
        drift.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                float f = (float) a.getAnimatedValue();
                content.setTranslationX(dp(14) * f);
                content.setTranslationY(-dp(10) * f);
            }
        });
        drift.start();
        animators.add(drift);
    }

    private Slide welcomeSlide() {
        return new Slide("WELCOME", ORANGE, "Troop 82", "Dallas, Texas  ·  Est. 1955");
    }

    private void load() {
        String sheetUrl = getSharedPreferences("config", Context.MODE_PRIVATE)
                .getString(MainActivity.PREF_SHEET_URL, null);
        Announcements.loadAsync(this, sheetUrl, new Announcements.Callback() {
            @Override
            public void onLoaded(final Announcements.Snapshot snap) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (alive) setSlides(snap);
                    }
                });
            }
        });
    }

    private void setSlides(Announcements.Snapshot snap) {
        List<Slide> next = new ArrayList<>();
        for (String m : snap.messages) next.add(new Slide("TROOP 82 NEWS", ORANGE, m, null));
        int n = Math.min(MAX_EVENTS, snap.events.size());
        for (int i = 0; i < n; i++) {
            Announcements.Event e = snap.events.get(i);
            next.add(new Slide("COMING UP  ·  " + e.feed.name.toUpperCase(Locale.US),
                    e.feed.color, e.title, whenText(e)));
        }
        if (next.isEmpty()) next.add(welcomeSlide());
        slides.clear();
        slides.addAll(next);
        if (index >= slides.size()) index = -1;
        buildDots();
    }

    private void buildDots() {
        dots.removeAllViews();
        if (slides.size() < 2) return;
        for (int i = 0; i < slides.size(); i++) {
            View d = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(0xFFFFFFFF);
            d.setBackground(g);
            int s = dp(6);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
            lp.leftMargin = dp(6);
            dots.addView(d, lp);
        }
        updateDots();
    }

    private void updateDots() {
        for (int i = 0; i < dots.getChildCount(); i++) {
            dots.getChildAt(i).setAlpha(i == index ? 1f : 0.3f);
        }
    }

    private void showNext() {
        if (!alive || slides.isEmpty()) return;
        index = (index + 1) % slides.size();
        final Slide s = slides.get(index);
        content.animate().alpha(0f).translationYBy(dp(6)).setDuration(450).withEndAction(new Runnable() {
            @Override
            public void run() {
                kicker.setText(s.kicker);
                kicker.setTextColor(s.kickerColor);
                headline.setText(s.headline);
                headline.setTextSize(TypedValue.COMPLEX_UNIT_SP, s.headline.length() > 90 ? 32 : 40);
                detail.setText(s.detail == null ? "" : s.detail);
                detail.setVisibility(s.detail == null ? View.GONE : View.VISIBLE);
                updateDots();
                content.animate().alpha(1f).translationYBy(-dp(6)).setDuration(650).start();
            }
        }).start();
    }

    private String whenText(Announcements.Event e) {
        Date today = Announcements.startOfDay(new Date());
        Calendar cal = Calendar.getInstance();
        cal.setTime(today);
        cal.add(Calendar.DAY_OF_YEAR, 1);
        Date tomorrow = cal.getTime();
        cal.add(Calendar.DAY_OF_YEAR, 1);
        Date dayAfter = cal.getTime();

        String day;
        if (e.start.before(tomorrow)) day = "Today";
        else if (e.start.before(dayAfter)) day = "Tomorrow";
        else day = new SimpleDateFormat("EEEE, MMMM d", Locale.US).format(e.start);
        if (e.allDay) return day;
        String time = new SimpleDateFormat(DateFormat.is24HourFormat(this) ? "H:mm" : "h:mm a", Locale.US)
                .format(e.start);
        return day + "  ·  " + time;
    }
}
