package org.troop82.launcher;
import java.text.SimpleDateFormat;
import java.util.*;
public class ParserTest {
  static int fails = 0;
  static void check(String name, boolean ok) { System.out.println((ok ? "PASS  " : "FAIL  ") + name); if (!ok) fails++; }
  static Date d(String s) throws Exception { return new SimpleDateFormat("yyyy-MM-dd HH:mm").parse(s); }
  static String ics(String body) { return "BEGIN:VCALENDAR\r\n" + body + "END:VCALENDAR\r\n"; }
  /** One VEVENT from property lines, e.g. ev("UID:x", "DTSTART:20261201T000000Z", "SUMMARY:y"). */
  static String ev(String... lines) {
    StringBuilder b = new StringBuilder("BEGIN:VEVENT");
    for (String l : lines) b.append("\r\n").append(l);
    return ics(b.append("\r\nEND:VEVENT\r\n").toString());
  }
  public static void main(String[] a) throws Exception {
    TimeZone.setDefault(TimeZone.getTimeZone("America/Chicago"));
    Announcements.Feed f = new Announcements.Feed("Test", 0, "x");
    Date now = d("2026-11-22 15:00");

    // Sheet dates
    Calendar c = Calendar.getInstance(); c.setTime(Announcements.parseSheetDate("12/1/26"));
    check("two-digit year 12/1/26 -> 2026", c.get(Calendar.YEAR) == 2026);
    c.setTime(Announcements.parseSheetDate("12/7/2026"));
    check("four-digit year 12/7/2026 -> 2026", c.get(Calendar.YEAR) == 2026);
    List<String> m = Announcements.parseSheet("Message,Show from,Show until\nA,11/1/26,11/30/26\nB,,11/21/2026\nC,11/22/2026,11/22/2026\n", now);
    check("sheet window filtering (A,C shown; B expired)", m.equals(Arrays.asList("A", "C")));

    // Bad content never accepted
    check("rejects HTML login page", !Announcements.looksValid("<!DOCTYPE html><html>Sign in</html>", false));
    check("rejects empty CSV", !Announcements.looksValid("   ", false));
    check("rejects non-calendar ICS", !Announcements.looksValid("<html>", true));
    check("accepts real CSV", Announcements.looksValid("Message,Show from\nHi,,", false));

    // Weekly meeting series that started in August, with one week cancelled (EXDATE) and one moved (RECURRENCE-ID)
    String series = ics(
      "BEGIN:VEVENT\r\nUID:mtg\r\nDTSTART;TZID=America/Chicago:20260824T191500\r\nDTEND;TZID=America/Chicago:20260824T203000\r\n"
    + "RRULE:FREQ=WEEKLY;BYDAY=MO\r\nEXDATE;TZID=America/Chicago:20261123T191500\r\nSUMMARY:Troop Meeting\r\nEND:VEVENT\r\n"
    + "BEGIN:VEVENT\r\nUID:mtg\r\nRECURRENCE-ID;TZID=America/Chicago:20261207T191500\r\nDTSTART;TZID=America/Chicago:20261207T190000\r\nSUMMARY:Court of Honor\r\nEND:VEVENT\r\n");
    List<Announcements.Event> ev = Announcements.upcoming(Announcements.parseIcs(series, f, now), now);
    SimpleDateFormat out = new SimpleDateFormat("MM/dd HH:mm");
    StringBuilder sb = new StringBuilder(); for (Announcements.Event e : ev) sb.append(out.format(e.start)).append(' ').append(e.title).append("; ");
    System.out.println("      series -> " + sb);
    check("weekly series expands into window", ev.size() >= 4);
    check("EXDATE week (11/23) skipped", !sb.toString().contains("11/23"));
    check("TZID honored (7:15 PM Chicago)", sb.toString().contains("11/30 19:15 Troop Meeting"));
    check("moved instance replaces series (12/07 19:00 Court of Honor only)", sb.toString().contains("12/07 19:00 Court of Honor") && !sb.toString().contains("12/07 19:15"));

    // Multi-day campout Fri-Sun stays visible on Saturday
    String camp = ics("BEGIN:VEVENT\r\nUID:c\r\nDTSTART;VALUE=DATE:20261120\r\nDTEND;VALUE=DATE:20261123\r\nSUMMARY:Campout\r\nEND:VEVENT\r\n");
    check("multi-day campout still shown mid-event", Announcements.upcoming(Announcements.parseIcs(camp, f, now), now).size() == 1);
    check("finished event hidden", Announcements.upcoming(Announcements.parseIcs(camp, f, d("2026-11-23 01:00")), d("2026-11-23 01:00")).isEmpty());

    // UTC times and COUNT
    String utc = ics("BEGIN:VEVENT\r\nUID:u\r\nDTSTART:20261201T011500Z\r\nSUMMARY:UTC\r\nEND:VEVENT\r\n");
    check("UTC time converts to local (11/30 19:15)", out.format(Announcements.parseIcs(utc, f, now).get(0).start).equals("11/30 19:15"));
    String counted = ics("BEGIN:VEVENT\r\nUID:k\r\nDTSTART:20261101T000000Z\r\nRRULE:FREQ=WEEKLY;COUNT=3\r\nSUMMARY:K\r\nEND:VEVENT\r\n");
    check("COUNT=3 series already over", Announcements.upcoming(Announcements.parseIcs(counted, f, now), now).isEmpty());

    // Monthly "first Tuesday" / "last Friday", "on the 31st", biweekly Sunday, malformed lines
    List<Announcements.Event> ft = Announcements.upcoming(Announcements.parseIcs(
        ev("UID:ft", "DTSTART;TZID=America/Chicago:20260901T190000", "RRULE:FREQ=MONTHLY;BYDAY=1TU", "SUMMARY:Committee"), f, now), now);
    check("MONTHLY BYDAY=1TU -> Tue 12/01 19:00", ft.size() == 1 && out.format(ft.get(0).start).equals("12/01 19:00"));
    List<Announcements.Event> lf = Announcements.upcoming(Announcements.parseIcs(
        ev("UID:lf", "DTSTART;TZID=America/Chicago:20260731T180000", "RRULE:FREQ=MONTHLY;BYDAY=-1FR", "SUMMARY:Last Friday"), f, now), now);
    check("MONTHLY BYDAY=-1FR -> Fri 11/27 18:00", lf.size() >= 1 && out.format(lf.get(0).start).equals("11/27 18:00"));
    String d31 = ev("UID:d31", "DTSTART;VALUE=DATE:20260131", "RRULE:FREQ=MONTHLY", "SUMMARY:EOM");
    check("monthly on the 31st: nothing until 12/22 (no 11/28 drift)", Announcements.upcoming(Announcements.parseIcs(d31, f, now), now).isEmpty());
    Date dec20 = d("2026-12-20 12:00");
    List<Announcements.Event> e31 = Announcements.upcoming(Announcements.parseIcs(d31, f, dec20), dec20);
    check("monthly on the 31st lands on 12/31", e31.size() == 1 && out.format(e31.get(0).start).startsWith("12/31"));
    StringBuilder bw = new StringBuilder();
    for (Announcements.Event e : Announcements.upcoming(Announcements.parseIcs(
        ev("UID:bw", "DTSTART;TZID=America/Chicago:20261101T150000", "RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=SU", "SUMMARY:PLC"), f, now), now))
      bw.append(out.format(e.start)).append(' ');
    System.out.println("      biweekly Sundays -> " + bw);
    check("biweekly Sunday from 11/01 -> 11/29 and 12/13", bw.toString().trim().equals("11/29 15:00 12/13 15:00"));
    List<Announcements.Event> tg = Announcements.upcoming(Announcements.parseIcs(
        ev("UID:tg", "DTSTART;VALUE=DATE:20201126", "RRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=4TH", "SUMMARY:Thanksgiving"), f, now), now);
    check("YEARLY BYMONTH=11;BYDAY=4TH -> Thu 11/26", tg.size() == 1 && out.format(tg.get(0).start).startsWith("11/26"));
    List<Announcements.Event> qz = Announcements.parseIcs(
        ev("UID:qz", "DTSTART;TZID=\"America/Chicago\":20261130T191500", "SUMMARY:Quoted TZ"), f, now);
    check("quoted TZID honored (19:15 Chicago)", qz.size() == 1 && out.format(qz.get(0).start).equals("11/30 19:15"));
    List<Announcements.Event> old = Announcements.upcoming(Announcements.parseIcs(
        ev("UID:old", "DTSTART;TZID=America/Chicago:19950102T070000", "RRULE:FREQ=DAILY;BYDAY=MO,TU,WE,TH,FR", "SUMMARY:Since 1995"), f, now), now);
    check("weekday DAILY series from 1995 still expands (next is Mon 11/23)", old.size() > 10 && out.format(old.get(0).start).equals("11/23 07:00"));
    List<Announcements.Event> ud = Announcements.upcoming(Announcements.parseIcs(
        ev("UID:ud", "DTSTART;TZID=America/Chicago:20261123T190000", "RRULE:FREQ=DAILY;UNTIL=20261125", "SUMMARY:Camp nights"), f, now), now);
    check("date-only UNTIL includes its last day (11/23, 11/24, 11/25)", ud.size() == 3);
    List<Announcements.Event> utcWeekly = Announcements.upcoming(Announcements.parseIcs(
        ev("UID:utc", "DTSTART:20260601T001500Z", "RRULE:FREQ=WEEKLY", "SUMMARY:UTC weekly"), f, now), now);
    check("UTC weekly from June keeps UTC clock time across DST (18:15 CST in Nov)", utcWeekly.size() >= 1 && out.format(utcWeekly.get(0).start).endsWith("18:15"));
    List<Announcements.Event> al = Announcements.parseIcs(
        ev("UID:al", "DTSTART:20261201T011500Z", "SUMMARY:Real title", "BEGIN:VALARM", "ACTION:DISPLAY", "SUMMARY:Alarm", "END:VALARM"), f, now);
    check("VALARM summary does not replace the event title", al.size() == 1 && al.get(0).title.equals("Real title"));
    Date jul = d("2027-07-20 12:00");
    List<Announcements.Event> f13 = Announcements.upcoming(Announcements.parseIcs(
        ev("UID:f13", "DTSTART;VALUE=DATE:20260213", "RRULE:FREQ=MONTHLY;BYDAY=FR;BYMONTHDAY=13", "SUMMARY:Friday 13th"), f, jul), jul);
    check("BYDAY=FR + BYMONTHDAY=13 -> only Friday 8/13/2027", f13.size() == 1 && out.format(f13.get(0).start).startsWith("08/13"));
    boolean noCrash = true; List<Announcements.Event> br = null;
    try { br = Announcements.parseIcs(ev("UID", "DTSTART:20261201T011500Z", "SUMMARY:Still shown"), f, now); }
    catch (RuntimeException ex) { noCrash = false; }
    check("malformed line (bare UID) does not crash", noCrash && br != null && br.size() == 1);

    // Real cached feeds still parse
    for (String file : new String[]{"89372f2accd0","d1c8357ab73e","e4449991dea6","fa41553546c6"}) {
      java.nio.file.Path path = java.nio.file.Paths.get("C:/tcl/announce/" + file + ".ics");
      if (!java.nio.file.Files.exists(path)) continue;  // optional: locally downloaded feeds
      String body = new String(java.nio.file.Files.readAllBytes(path), "UTF-8");
      check("real feed " + file + " parses", Announcements.parseIcs(body, f, now).size() > 0);
    }
    System.out.println(fails == 0 ? "ALL PARSER TESTS PASSED" : fails + " FAILED");
    System.exit(fails);
  }
}
