package org.troop82.launcher;
import java.util.*;
public class SheetTest {
  public static void main(String[] a) throws Exception {
    String csv = "Message,Show from,Show until\r\n"
      + "\"Campout at Lake Murray, permission slips due Tuesday\",11/20/2026,12/5/2026\r\n"
      + "Expired item,11/1/2026,11/10/2026\r\n"
      + "Future item,12/20/2026,\r\n"
      + "No dates at all,,\r\n"
      + "\"Bring \"\"10 essentials\"\" to\nnext meeting\",2026-11-22,2026-11-30\r\n";
    Date now = new java.text.SimpleDateFormat("yyyy-MM-dd").parse("2026-11-22");
    for (String m : Announcements.parseSheet(csv, now)) System.out.println("SHOW: " + m.replace("\n","\n"));
  }
}
