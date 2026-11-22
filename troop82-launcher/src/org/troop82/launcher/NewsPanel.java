package org.troop82.launcher;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Frosted panel showing rotating announcements and the next few troop events. */
final class NewsPanel extends LinearLayout {

    static final int MAX_EVENTS = 5;
    static final long ROTATE_MS = 10_000;

    private static final int ORANGE = 0xFFE8913A;
    private static final int CREAM = 0xFFF2E8D5;

    private final TextView newsHeader;
    private final TextView message;
    private final TextView eventsHeader;
    private final LinearLayout eventList;

    private List<String> messages;
    private int messageIndex;

    private final Runnable rotate = new Runnable() {
        @Override
        public void run() {
            if (messages == null || messages.size() < 2) return;
            messageIndex = (messageIndex + 1) % messages.size();
            final List<String> current = messages;
            final int shown = messageIndex;
            message.animate().alpha(0f).setDuration(250).withEndAction(new Runnable() {
                @Override
                public void run() {
                    // The list may have been replaced during the fade; only use what we captured.
                    if (shown < current.size()) message.setText(current.get(shown));
                    message.animate().alpha(1f).setDuration(250).start();
                }
            }).start();
            postDelayed(this, ROTATE_MS);
        }
    };

    NewsPanel(Context c) {
        super(c);
        setOrientation(VERTICAL);
        int pad = Tiles.dp(c, 18);
        setPadding(pad, Tiles.dp(c, 14), pad, Tiles.dp(c, 14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x8C0B1630);
        bg.setCornerRadius(Tiles.dp(c, 14));
        bg.setStroke(Tiles.dp(c, 1), 0x33FFFFFF);
        setBackground(bg);

        newsHeader = header(c, "TROOP 82 NEWS");
        addView(newsHeader);

        message = new TextView(c);
        message.setTextColor(0xFFFFFFFF);
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        message.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        message.setMaxLines(3);
        message.setEllipsize(TextUtils.TruncateAt.END);
        message.setLineSpacing(0, 1.1f);
        LayoutParams mp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        mp.topMargin = Tiles.dp(c, 4);
        mp.bottomMargin = Tiles.dp(c, 12);
        addView(message, mp);

        eventsHeader = header(c, "COMING UP");
        addView(eventsHeader);

        eventList = new LinearLayout(c);
        eventList.setOrientation(VERTICAL);
        addView(eventList);

        setVisibility(GONE);
    }

    private static TextView header(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(ORANGE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setLetterSpacing(0.12f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return t;
    }

    void show(Announcements.Snapshot s) {
        removeCallbacks(rotate);
        message.animate().cancel();
        message.setAlpha(1f);
        if (s == null || s.isEmpty()) {
            messageIndex = 0;
            setVisibility(GONE);
            return;
        }
        setVisibility(VISIBLE);

        messages = s.messages;
        boolean hasNews = !messages.isEmpty();
        newsHeader.setVisibility(hasNews ? VISIBLE : GONE);
        message.setVisibility(hasNews ? VISIBLE : GONE);
        if (!hasNews) messageIndex = 0;
        if (hasNews) {
            messageIndex = Math.min(messageIndex, messages.size() - 1);
            message.setText(messages.get(messageIndex));
            message.setAlpha(1f);
            if (messages.size() > 1) postDelayed(rotate, ROTATE_MS);
        }

        eventList.removeAllViews();
        boolean hasEvents = !s.events.isEmpty();
        eventsHeader.setVisibility(hasEvents ? VISIBLE : GONE);
        int n = Math.min(MAX_EVENTS, s.events.size());
        for (int i = 0; i < n; i++) eventList.addView(eventRow(s.events.get(i)));
    }

    private View eventRow(Announcements.Event e) {
        Context c = getContext();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Tiles.dp(c, 5), 0, 0);

        View dot = new View(c);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(e.feed.color);
        dot.setBackground(d);
        int s = Tiles.dp(c, 8);
        LayoutParams dp = new LayoutParams(s, s);
        dp.rightMargin = Tiles.dp(c, 10);
        row.addView(dot, dp);

        TextView when = new TextView(c);
        when.setText(whenText(e));
        when.setTextColor(CREAM);
        when.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        when.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        row.addView(when, new LayoutParams(Tiles.dp(c, 168), LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(c);
        title.setText(e.title);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(title, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        return row;
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
        else day = new SimpleDateFormat("EEE MMM d", Locale.US).format(e.start);

        if (e.allDay) return day;
        String time = DateFormat.is24HourFormat(getContext())
                ? new SimpleDateFormat("H:mm", Locale.US).format(e.start)
                : new SimpleDateFormat("h:mm a", Locale.US).format(e.start);
        return day + " · " + time;
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(rotate);
        super.onDetachedFromWindow();
    }
}
