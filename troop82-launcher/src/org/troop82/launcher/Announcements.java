package org.troop82.launcher;

import android.content.Context;
import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads troop announcements (a published Google Sheet CSV) and upcoming events
 * (the troop's public Google Calendar iCal feeds). Every good download is cached
 * on disk, so the TV keeps showing the last known items when it is offline or a
 * source returns an error page.
 */
final class Announcements {

    /** The four public calendars linked from hptroop82.org/calendar. */
    static final Feed[] CALENDARS = {
            new Feed("Meetings", 0xFF4FA3E0, "https://calendar.google.com/calendar/ical/"
                    + "89372f2accd09104f908c3248f15fc9100b6265c172388248406c817b0c2952b%40group.calendar.google.com/public/basic.ics"),
            new Feed("Campouts", 0xFF6DBE6A, "https://calendar.google.com/calendar/ical/"
                    + "d1c8357ab73e6fadfbfcaff3a49a0cadd3776197d5c99d8332dd8077dd46c01d%40group.calendar.google.com/public/basic.ics"),
            new Feed("Eagle", 0xFFE8913A, "https://calendar.google.com/calendar/ical/"
                    + "fa41553546c6e3a91f67a7e84bc0f236ddaf6ff139e2c78cef1f6f4676c292cc%40group.calendar.google.com/public/basic.ics"),
            new Feed("Deadlines", 0xFFD9675F, "https://calendar.google.com/calendar/ical/"
                    + "e4449991dea6251b345fec47c701c7fb97b90e5a42bba2816ec708acd8b4d163%40group.calendar.google.com/public/basic.ics"),
    };

    /** How far ahead to look for events. */
    static final int EVENT_DAYS_AHEAD = 30;
    /** Ignore absurdly large downloads instead of running out of memory. */
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    /** Safety cap on generated occurrences per recurring event. */
    private static final int MAX_OCCURRENCES = 1000;

    static final class Feed {
        final String name;
        final int color;
        final String url;

        Feed(String name, int color, String url) {
            this.name = name;
            this.color = color;
            this.url = url;
        }
    }

    static final class Event {
        final Date start;
        final Date end;      // exclusive; equals start when the feed gives no end
        final boolean allDay;
        final String title;
        final Feed feed;

        Event(Date start, Date end, boolean allDay, String title, Feed feed) {
            this.start = start;
            this.end = end;
            this.allDay = allDay;
            this.title = title;
            this.feed = feed;
        }
    }

    /** What the home screen and screensaver show. */
    static final class Snapshot {
        final List<String> messages;
        final List<Event> events;

        Snapshot(List<String> messages, List<Event> events) {
            this.messages = messages;
            this.events = events;
        }

        boolean isEmpty() {
            return messages.isEmpty() && events.isEmpty();
        }
    }

    /** Result callback, always delivered on the background thread. */
    interface Callback {
        void onLoaded(Snapshot snapshot);
    }

    // One shared worker for the whole app: downloads never pile up, and the home
    // screen and screensaver never write the cache files at the same time.
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    /** Cache reads get their own thread so they never wait behind a slow download. */
    private static final ExecutorService CACHE_READER = Executors.newSingleThreadExecutor();
    /** A download that is still trickling in after this long is abandoned. */
    private static final long DOWNLOAD_DEADLINE_MS = 60_000;
    private static final Object FILE_LOCK = new Object();
    private static final Object LOCK = new Object();
    private static boolean busy;
    /** Callers that asked while a download was already running; they get its result. */
    private static final List<Callback> WAITING = new ArrayList<>();

    private Announcements() {}

    /**
     * Delivers the cached snapshot right away (so nothing waits on the network),
     * then downloads fresh data and delivers again. If a download is already
     * running, the caller simply receives that download's result too.
     * Never throws: any failure leaves the previous snapshot on screen.
     */
    static void loadAsync(Context context, final String sheetCsvUrl, final Callback cb) {
        final Context app = context.getApplicationContext();
        final boolean startDownload;
        synchronized (LOCK) {
            WAITING.add(cb);
            startDownload = !busy;
            busy = true;
        }
        CACHE_READER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    cb.onLoaded(load(app, sheetCsvUrl, false));
                } catch (Throwable ignored) {
                    // no cache yet
                }
            }
        });
        if (!startDownload) return;
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                Snapshot fresh = null;
                try {
                    fresh = load(app, sheetCsvUrl, true);
                } catch (Throwable ignored) {
                    // keep whatever is showing
                }
                List<Callback> deliver;
                synchronized (LOCK) {
                    deliver = new ArrayList<>(WAITING);
                    WAITING.clear();
                    busy = false;
                }
                if (fresh == null) return;
                for (Callback c : deliver) {
                    try {
                        c.onLoaded(fresh);
                    } catch (Throwable ignored) {
                        // one bad receiver must not stop the others
                    }
                }
            }
        });
    }

    /**
     * Parses everything; with {@code network} it downloads first and falls back to
     * the cache, without it it reads the cache only. Call off the main thread.
     */
    static Snapshot load(Context c, String sheetCsvUrl, boolean network) {
        Date now = new Date();
        List<String> messages = new ArrayList<>();
        if (sheetCsvUrl != null && sheetCsvUrl.startsWith("https://")) {
            try {
                // cache name follows the link, so a changed link never shows the old sheet
                String cacheName = "sheet-" + Integer.toHexString(sheetCsvUrl.hashCode()) + ".csv";
                String csv = fetchCached(c, sheetCsvUrl, cacheName, false, network);
                if (csv != null) messages = parseSheet(csv, now);
            } catch (RuntimeException e) {
                // a broken sheet must not hide the calendar
            }
        }

        List<Event> events = new ArrayList<>();
        for (int i = 0; i < CALENDARS.length; i++) {
            try {
                String ics = fetchCached(c, CALENDARS[i].url, "cal" + i + ".ics", true, network);
                if (ics != null) events.addAll(parseIcs(ics, CALENDARS[i], now));
            } catch (RuntimeException e) {
                // one broken feed must not hide the others
            }
        }
        return new Snapshot(messages, upcoming(events, now));
    }

    // ---------------------------------------------------------------- download

    private static String fetchCached(Context c, String url, String cacheName, boolean ics, boolean network) {
        AtomicFile cache = new AtomicFile(new File(c.getFilesDir(), cacheName));
        try {
            if (!network) throw new IOException("cache only");
            String body = download(url);
            if (!looksValid(body, ics)) throw new IOException("unexpected content");
            // Only the file operations are locked (never the download), so a cache read
            // can't delete a write in progress, and reads never wait on the network.
            synchronized (FILE_LOCK) {
                FileOutputStream out = cache.startWrite();
                try {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                    cache.finishWrite(out);
                } catch (IOException e) {
                    cache.failWrite(out);
                }
            }
            return body;
        } catch (IOException | RuntimeException e) {
            synchronized (FILE_LOCK) {
                try {
                    return new String(cache.readFully(), StandardCharsets.UTF_8);
                } catch (IOException none) {
                    return null;
                }
            }
        }
    }

    /** Rejects login pages, captive portals and empty bodies so they never replace good data. */
    static boolean looksValid(String body, boolean ics) {
        if (body == null) return false;
        String head = body.trim();
        if (ics) return head.startsWith("BEGIN:VCALENDAR");
        if (head.isEmpty() || head.startsWith("<")) return false;
        String lower = head.substring(0, Math.min(200, head.length())).toLowerCase(Locale.US);
        return !lower.contains("<html") && !lower.contains("<!doctype");
    }

    private static String download(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "Troop82Launcher/1.0");
        try {
            if (conn.getResponseCode() != 200) throw new IOException("HTTP " + conn.getResponseCode());
            String type = conn.getContentType();
            if (type != null && type.toLowerCase(Locale.US).contains("html")) throw new IOException("HTML response");
            return readAll(conn.getInputStream());
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        long deadline = System.currentTimeMillis() + DOWNLOAD_DEADLINE_MS;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
            if (buf.size() > MAX_BYTES) throw new IOException("too large");
            if (System.currentTimeMillis() > deadline) throw new IOException("download too slow");
        }
        return buf.toString("UTF-8");
    }

    // ------------------------------------------------------------ sheet (CSV)

    // "M/d/yy" also reads four-digit years literally, and expands two-digit years
    // to this century, so it must come before any "yyyy" pattern.
    private static final String[] SHEET_DATE_FORMATS = {"M/d/yy", "yyyy-MM-dd", "MMM d, yyyy", "MMMM d, yyyy"};

    /**
     * Columns: Message, Show from (optional), Show until (optional, inclusive).
     * A header row whose first cell is "Message" is skipped.
     */
    static List<String> parseSheet(String csv, Date now) {
        List<String> out = new ArrayList<>();
        Date today = startOfDay(now);
        for (List<String> row : parseCsv(csv)) {
            if (row.isEmpty()) continue;
            String msg = row.get(0).trim();
            if (msg.isEmpty() || msg.equalsIgnoreCase("message")) continue;
            Date from = row.size() > 1 ? parseSheetDate(row.get(1)) : null;
            Date until = row.size() > 2 ? parseSheetDate(row.get(2)) : null;
            if (from != null && today.before(startOfDay(from))) continue;
            if (until != null && today.after(startOfDay(until))) continue;
            out.add(msg);
        }
        return out;
    }

    static Date parseSheetDate(String s) {
        s = s.trim();
        if (s.isEmpty()) return null;
        for (String f : SHEET_DATE_FORMATS) {
            SimpleDateFormat fmt = new SimpleDateFormat(f, Locale.US);
            fmt.setLenient(false);
            try {
                return fmt.parse(s);
            } catch (ParseException ignored) {
                // try the next format
            }
        }
        return null;
    }

    /** Minimal RFC 4180 CSV parser (quoted fields, doubled quotes, newlines in quotes). */
    static List<List<String>> parseCsv(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char ch = csv.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') i++;
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(ch);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    // ------------------------------------------------------------ calendar (iCal)

    private static final Pattern VEVENT = Pattern.compile("BEGIN:VEVENT(.*?)END:VEVENT", Pattern.DOTALL);

    /** One property line: NAME;PARAMS:VALUE */
    private static final class Prop {
        final String params;
        final String value;

        Prop(String params, String value) {
            this.params = params;
            this.value = value;
        }

        String tzid() {
            Matcher m = Pattern.compile("TZID=([^;:]+)").matcher(params);
            return m.find() ? m.group(1).replace("\"", "") : null;   // TZID may be quoted
        }
    }

    private static Prop prop(String line) {
        int colon = line.indexOf(':');
        if (colon < 0) return null;
        String left = line.substring(0, colon);
        int semi = left.indexOf(';');
        return new Prop(semi < 0 ? "" : left.substring(semi + 1), line.substring(colon + 1).trim());
    }

    private static String name(String line) {
        int end = line.length();
        int colon = line.indexOf(':');
        int semi = line.indexOf(';');
        if (colon >= 0) end = colon;
        if (semi >= 0 && semi < end) end = semi;
        return line.substring(0, end);
    }

    static List<Event> parseIcs(String ics, Feed feed, Date now) {
        // Unfold continuation lines (RFC 5545 3.1).
        String text = ics.replace("\r\n", "\n").replace("\n ", "").replace("\n\t", "");
        Date windowStart = startOfDay(now);
        Date windowEnd = addDays(windowStart, EVENT_DAYS_AHEAD);

        // Moved or edited single occurrences of a series, by UID: their original start times.
        Set<String> overridden = new HashSet<>();
        List<String> blocks = new ArrayList<>();
        Matcher m = VEVENT.matcher(text);
        while (m.find()) {
            // drop reminders nested in the event (their SUMMARY is not the event's title)
            String block = m.group(1).replaceAll("(?s)BEGIN:VALARM.*?END:VALARM", "");
            blocks.add(block);
            String uid = null;
            Date recurrenceId = null;
            for (String line : block.split("\n")) {
                Prop p = prop(line);
                if (p == null) continue;  // malformed line: ignore it
                String n = name(line);
                if (n.equals("UID")) uid = p.value;
                else if (n.equals("RECURRENCE-ID")) recurrenceId = parseIcsDate(p);
            }
            if (uid != null && recurrenceId != null) overridden.add(uid + "@" + recurrenceId.getTime());
        }

        List<Event> out = new ArrayList<>();
        for (String block : blocks) {
            Prop start = null, end = null;
            String summary = null, uid = null, rrule = null;
            boolean cancelled = false, isOverride = false;
            List<Date> exdates = new ArrayList<>();
            for (String line : block.split("\n")) {
                String n = name(line);
                Prop p = prop(line);
                if (p == null) continue;
                switch (n) {
                    case "DTSTART": start = p; break;
                    case "DTEND": end = p; break;
                    case "SUMMARY": summary = unescape(p.value); break;
                    case "UID": uid = p.value; break;
                    case "RRULE": rrule = p.value; break;
                    case "RECURRENCE-ID": isOverride = true; break;
                    case "STATUS": cancelled = p.value.equalsIgnoreCase("CANCELLED"); break;
                    case "EXDATE":
                        for (String v : p.value.split(",")) {
                            Date d = parseIcsDate(new Prop(p.params, v.trim()));
                            if (d != null) exdates.add(d);
                        }
                        break;
                    default: break;
                }
            }
            if (start == null || summary == null || cancelled) continue;
            Date s = parseIcsDate(start);
            if (s == null) continue;
            boolean allDay = start.value.length() == 8;
            Date e = end != null ? parseIcsDate(end) : null;
            long duration = (e != null && e.after(s)) ? e.getTime() - s.getTime() : (allDay ? 86_400_000L : 0);

            if (rrule == null || isOverride) {
                out.add(new Event(s, new Date(s.getTime() + duration), allDay, summary, feed));
                continue;
            }
            // repeat in the event's own time zone (UTC for "Z" times), so DST is handled right
            TimeZone tz = start.value.endsWith("Z") ? TimeZone.getTimeZone("UTC")
                    : start.tzid() != null ? TimeZone.getTimeZone(start.tzid()) : TimeZone.getDefault();
            for (Date occ : expand(s, rrule, windowStart, windowEnd, duration, tz)) {
                if (containsTime(exdates, occ)) continue;
                if (uid != null && overridden.contains(uid + "@" + occ.getTime())) continue;
                out.add(new Event(occ, new Date(occ.getTime() + duration), allDay, summary, feed));
            }
        }
        return out;
    }

    private static boolean containsTime(List<Date> list, Date d) {
        for (Date x : list) if (x.getTime() == d.getTime()) return true;
        return false;
    }

    /**
     * Expands an RRULE into occurrences inside the window. Supports FREQ=DAILY,
     * WEEKLY (BYDAY, WKST), MONTHLY (BYDAY with ordinals like 1TU/-1FR, BYMONTHDAY)
     * and YEARLY, with INTERVAL, COUNT and UNTIL. Each period is computed from
     * DTSTART, so dates never drift (e.g. "the 31st" skips short months instead
     * of sliding to the 28th). Wall-clock time of DTSTART is kept.
     */
    static List<Date> expand(Date first, String rrule, Date windowStart, Date windowEnd, long duration) {
        return expand(first, rrule, windowStart, windowEnd, duration, TimeZone.getDefault());
    }

    static List<Date> expand(Date first, String rrule, Date windowStart, Date windowEnd, long duration, TimeZone tz) {
        String freq = null;
        int interval = 1, count = -1, wkst = Calendar.MONDAY;
        Date until = null;
        List<int[]> byDay = new ArrayList<>();      // {ordinal (0 = every), Calendar day-of-week}
        List<Integer> byMonthDay = new ArrayList<>();
        List<Integer> byMonth = new ArrayList<>();
        for (String part : rrule.split(";")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) continue;
            switch (kv[0]) {
                case "FREQ": freq = kv[1]; break;
                case "INTERVAL": interval = Math.max(1, safeInt(kv[1], 1)); break;
                case "COUNT": count = safeInt(kv[1], -1); break;
                case "UNTIL":
                    until = parseIcsDate(new Prop("", kv[1]));
                    // a date-only UNTIL includes that whole day
                    if (until != null && kv[1].length() == 8) until = new Date(addDays(until, 1).getTime() - 1);
                    break;
                case "BYMONTH":
                    for (String mo : kv[1].split(",")) {
                        int v = safeInt(mo, 0);
                        if (v >= 1 && v <= 12) byMonth.add(v);
                    }
                    break;
                case "WKST": { int d = dayOfWeek(kv[1]); if (d > 0) wkst = d; break; }
                case "BYDAY":
                    for (String d : kv[1].split(",")) {
                        int dow = dayOfWeek(d.replaceAll("[^A-Z]", ""));
                        String ord = d.replaceAll("[A-Z]", "");
                        if (dow > 0) byDay.add(new int[]{ord.isEmpty() ? 0 : safeInt(ord, 0), dow});
                    }
                    break;
                case "BYMONTHDAY":
                    for (String d : kv[1].split(",")) {
                        int md = safeInt(d, 0);
                        if (md != 0) byMonthDay.add(md);
                    }
                    break;
                default: break;
            }
        }
        List<Date> out = new ArrayList<>();
        if (freq == null) return out;

        Calendar base = Calendar.getInstance(tz);
        base.setTime(first);
        Date stopAfter = addDays(windowEnd, 400);   // safety: a period this far out can't matter
        // Without COUNT, jump close to the window instead of walking from a years-old DTSTART.
        int kStart = 0;
        if (count < 0 && windowStart.after(first)) {
            long days = (windowStart.getTime() - first.getTime()) / 86_400_000L;
            long perPeriod = freq.equals("DAILY") ? interval : freq.equals("WEEKLY") ? 7L * interval
                    : freq.equals("MONTHLY") ? 31L * interval : 366L * interval;
            kStart = (int) Math.max(0, days / perPeriod - 2);
        }
        int produced = 0;
        for (int k = kStart; k < kStart + MAX_OCCURRENCES * 10; k++) {
            List<Calendar> cands = new ArrayList<>();
            Calendar period = (Calendar) base.clone();
            switch (freq) {
                case "DAILY":
                    period.add(Calendar.DAY_OF_YEAR, k * interval);
                    if (byDay.isEmpty() || hasDay(byDay, period.get(Calendar.DAY_OF_WEEK))) cands.add(period);
                    break;
                case "WEEKLY": {
                    while (period.get(Calendar.DAY_OF_WEEK) != wkst) period.add(Calendar.DAY_OF_YEAR, -1);
                    period.add(Calendar.DAY_OF_YEAR, 7 * k * interval);
                    for (int i = 0; i < 7; i++) {
                        Calendar d = (Calendar) period.clone();
                        d.add(Calendar.DAY_OF_YEAR, i);
                        int dow = d.get(Calendar.DAY_OF_WEEK);
                        boolean match = byDay.isEmpty() ? dow == base.get(Calendar.DAY_OF_WEEK) : hasDay(byDay, dow);
                        if (match) cands.add(d);
                    }
                    break;
                }
                case "MONTHLY": {
                    period.set(Calendar.DAY_OF_MONTH, 1);
                    period.add(Calendar.MONTH, k * interval);
                    cands.addAll(daysInMonth(period, byMonthDay, byDay, base.get(Calendar.DAY_OF_MONTH)));
                    break;
                }
                case "YEARLY": {
                    // e.g. "4th Thursday of November" = BYMONTH=11;BYDAY=4TH
                    period.set(Calendar.DAY_OF_MONTH, 1);
                    period.add(Calendar.YEAR, k * interval);
                    List<Integer> months = byMonth.isEmpty()
                            ? Collections.singletonList(base.get(Calendar.MONTH) + 1) : byMonth;
                    for (int mo : months) {
                        Calendar monthStart = (Calendar) period.clone();
                        monthStart.set(Calendar.MONTH, mo - 1);
                        cands.addAll(daysInMonth(monthStart, byMonthDay, byDay, base.get(Calendar.DAY_OF_MONTH)));
                    }
                    break;
                }
                default:
                    return out;
            }
            Collections.sort(cands);
            for (Calendar c : cands) {
                Date d = c.getTime();
                if (d.before(first)) continue;
                if (until != null && d.after(until)) return out;
                if (count >= 0 && produced >= count) return out;
                produced++;
                if (!d.before(windowEnd)) return out;
                if (d.getTime() + Math.max(duration, 1) > windowStart.getTime()) out.add(d);
            }
            if (period.getTime().after(stopAfter)) return out;
        }
        return out;
    }

    /** Days of one month (monthStart = the 1st) selected by BYMONTHDAY, BYDAY (with ordinals), or the start day. */
    private static List<Calendar> daysInMonth(Calendar monthStart, List<Integer> byMonthDay, List<int[]> byDay, int baseDay) {
        List<Calendar> out = new ArrayList<>();
        int max = monthStart.getActualMaximum(Calendar.DAY_OF_MONTH);
        if (!byMonthDay.isEmpty()) {
            for (int md : byMonthDay) {
                int day = md > 0 ? md : max + md + 1;
                if (day < 1 || day > max) continue;
                Calendar c = withDay(monthStart, day);
                // with BYDAY too, both must match (e.g. Friday the 13th)
                if (byDay.isEmpty() || hasDay(byDay, c.get(Calendar.DAY_OF_WEEK))) out.add(c);
            }
        } else if (!byDay.isEmpty()) {
            for (int[] bd : byDay) {
                List<Integer> days = new ArrayList<>();
                for (int day = 1; day <= max; day++) {
                    if (withDay(monthStart, day).get(Calendar.DAY_OF_WEEK) == bd[1]) days.add(day);
                }
                if (bd[0] == 0) for (int day : days) out.add(withDay(monthStart, day));
                else if (bd[0] > 0 && bd[0] <= days.size()) out.add(withDay(monthStart, days.get(bd[0] - 1)));
                else if (bd[0] < 0 && -bd[0] <= days.size()) out.add(withDay(monthStart, days.get(days.size() + bd[0])));
            }
        } else if (baseDay <= max) {
            out.add(withDay(monthStart, baseDay));
        }
        return out;
    }

    private static boolean hasDay(List<int[]> byDay, int dow) {
        for (int[] bd : byDay) if (bd[1] == dow) return true;
        return false;
    }

    private static Calendar withDay(Calendar monthStart, int day) {
        Calendar c = (Calendar) monthStart.clone();
        c.set(Calendar.DAY_OF_MONTH, day);
        return c;
    }

    private static int safeInt(String s, int dflt) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static int dayOfWeek(String code) {
        switch (code) {
            case "SU": return Calendar.SUNDAY;
            case "MO": return Calendar.MONDAY;
            case "TU": return Calendar.TUESDAY;
            case "WE": return Calendar.WEDNESDAY;
            case "TH": return Calendar.THURSDAY;
            case "FR": return Calendar.FRIDAY;
            case "SA": return Calendar.SATURDAY;
            default: return -1;
        }
    }

    /** Parses DATE (all-day, local), DATE-TIME with Z (UTC), with TZID, or floating (local). */
    private static Date parseIcsDate(Prop p) {
        if (p == null) return null;
        String v = p.value;
        try {
            if (v.length() == 8) {
                return new SimpleDateFormat("yyyyMMdd", Locale.US).parse(v);
            }
            SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US);
            if (v.endsWith("Z")) {
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                v = v.substring(0, v.length() - 1);
            } else if (p.tzid() != null) {
                f.setTimeZone(TimeZone.getTimeZone(p.tzid()));
            }
            return f.parse(v);
        } catch (ParseException e) {
            return null;
        }
    }

    private static String unescape(String s) {
        return s.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",")
                .replace("\\;", ";").replace("\\\\", "\\");
    }

    /** Events not yet finished (multi-day events stay until their last day), in the next 30 days. */
    static List<Event> upcoming(List<Event> all, Date now) {
        Date limit = addDays(startOfDay(now), EVENT_DAYS_AHEAD);
        List<Event> out = new ArrayList<>();
        for (Event e : all) {
            boolean notOver = e.allDay ? e.end.after(now) : !e.start.before(now) || e.end.after(now);
            if (notOver && e.start.before(limit)) out.add(e);
        }
        Collections.sort(out, new Comparator<Event>() {
            @Override
            public int compare(Event a, Event b) {
                return a.start.compareTo(b.start);
            }
        });
        return out;
    }

    static Date addDays(Date d, int days) {
        Calendar c = Calendar.getInstance();
        c.setTime(d);
        c.add(Calendar.DAY_OF_YEAR, days);
        return c.getTime();
    }

    static Date startOfDay(Date d) {
        Calendar c = Calendar.getInstance();
        c.setTime(d);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTime();
    }
}
