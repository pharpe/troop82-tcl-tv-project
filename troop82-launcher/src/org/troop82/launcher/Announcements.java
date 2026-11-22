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

    // One shared worker for the whole app: refreshes never pile up, and the home
    // screen and screensaver never write the cache files at the same time.
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);

    private Announcements() {}

    /**
     * Starts a background refresh unless one is already running. Never throws:
     * any failure simply leaves the previous snapshot on screen.
     */
    static void loadAsync(Context context, final String sheetCsvUrl, final Callback cb) {
        if (!BUSY.compareAndSet(false, true)) return;
        final Context app = context.getApplicationContext();
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    cb.onLoaded(load(app, sheetCsvUrl));
                } catch (Throwable t) {
                    // Keep whatever is already showing.
                } finally {
                    BUSY.set(false);
                }
            }
        });
    }

    /** Downloads (or falls back to cache) and parses everything. Call off the main thread. */
    static Snapshot load(Context c, String sheetCsvUrl) {
        List<String> messages = new ArrayList<>();
        if (sheetCsvUrl != null && sheetCsvUrl.startsWith("https://")) {
            String csv = fetchCached(c, sheetCsvUrl, "sheet.csv", false);
            if (csv != null) messages = parseSheet(csv, new Date());
        }

        List<Event> events = new ArrayList<>();
        Date now = new Date();
        for (int i = 0; i < CALENDARS.length; i++) {
            String ics = fetchCached(c, CALENDARS[i].url, "cal" + i + ".ics", true);
            if (ics != null) events.addAll(parseIcs(ics, CALENDARS[i], now));
        }
        return new Snapshot(messages, upcoming(events, now));
    }

    // ---------------------------------------------------------------- download

    private static String fetchCached(Context c, String url, String cacheName, boolean ics) {
        AtomicFile cache = new AtomicFile(new File(c.getFilesDir(), cacheName));
        try {
            String body = download(url);
            if (!looksValid(body, ics)) throw new IOException("unexpected content");
            FileOutputStream out = cache.startWrite();
            try {
                out.write(body.getBytes(StandardCharsets.UTF_8));
                cache.finishWrite(out);
            } catch (IOException e) {
                cache.failWrite(out);
            }
            return body;
        } catch (IOException | RuntimeException e) {
            try {
                return new String(cache.readFully(), StandardCharsets.UTF_8);
            } catch (IOException none) {
                return null;
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
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
            if (buf.size() > MAX_BYTES) throw new IOException("too large");
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
            return m.find() ? m.group(1) : null;
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
            String block = m.group(1);
            blocks.add(block);
            String uid = null;
            Date recurrenceId = null;
            for (String line : block.split("\n")) {
                String n = name(line);
                if (n.equals("UID")) uid = prop(line).value;
                else if (n.equals("RECURRENCE-ID")) recurrenceId = parseIcsDate(prop(line));
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
            for (Date occ : expand(s, rrule, windowStart, windowEnd, duration)) {
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

    /** Expands DAILY / WEEKLY (with BYDAY) / MONTHLY / YEARLY rules into the window. */
    static List<Date> expand(Date first, String rrule, Date windowStart, Date windowEnd, long duration) {
        String freq = null;
        int interval = 1, count = -1;
        Date until = null;
        List<Integer> byDay = new ArrayList<>();
        for (String part : rrule.split(";")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) continue;
            switch (kv[0]) {
                case "FREQ": freq = kv[1]; break;
                case "INTERVAL": interval = Math.max(1, safeInt(kv[1], 1)); break;
                case "COUNT": count = safeInt(kv[1], -1); break;
                case "UNTIL": until = parseIcsDate(new Prop("", kv[1])); break;
                case "BYDAY":
                    for (String d : kv[1].split(",")) {
                        int dow = dayOfWeek(d.replaceAll("[^A-Z]", ""));
                        if (dow > 0) byDay.add(dow);
                    }
                    break;
                default: break;
            }
        }
        List<Date> out = new ArrayList<>();
        if (freq == null) return out;

        Calendar cal = Calendar.getInstance();
        cal.setTime(first);
        int produced = 0;
        for (int step = 0; step < MAX_OCCURRENCES * 7; step++) {
            List<Date> candidates = new ArrayList<>();
            if (freq.equals("WEEKLY") && !byDay.isEmpty()) {
                Calendar week = (Calendar) cal.clone();
                week.set(Calendar.DAY_OF_WEEK, week.getFirstDayOfWeek());
                for (int i = 0; i < 7; i++) {
                    if (byDay.contains(week.get(Calendar.DAY_OF_WEEK)) && !week.getTime().before(first)) {
                        candidates.add(week.getTime());
                    }
                    week.add(Calendar.DAY_OF_YEAR, 1);
                }
            } else {
                candidates.add(cal.getTime());
            }
            for (Date d : candidates) {
                if (until != null && d.after(until)) return out;
                if (count >= 0 && produced >= count) return out;
                produced++;
                if (!d.before(windowEnd)) return out;
                if (d.getTime() + duration > windowStart.getTime() || !d.before(windowStart)) out.add(d);
            }
            switch (freq) {
                case "DAILY": cal.add(Calendar.DAY_OF_YEAR, interval); break;
                case "WEEKLY": cal.add(Calendar.WEEK_OF_YEAR, interval); break;
                case "MONTHLY": cal.add(Calendar.MONTH, interval); break;
                case "YEARLY": cal.add(Calendar.YEAR, interval); break;
                default: return out;
            }
            if (!cal.getTime().before(windowEnd) && !(freq.equals("WEEKLY") && !byDay.isEmpty())) return out;
            if (cal.getTime().after(addDays(windowEnd, 7))) return out;
        }
        return out;
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
