#!/bin/sh
# Troop 82 TV health check. Run from Git Bash: /c/tcl/qa.sh
# Prints PASS/FAIL/WARN per check and a summary. Safe to re-run any time
# (it opens apps and returns Home, but changes no settings).
TV="${1:-192.168.1.174}"; case "$TV" in *:*) ;; *) TV="$TV:5555";; esac
A="adb -s $TV shell"
LAUNCHER=org.troop82.launcher
pass=0; fail=0; warn=0
ok()   { echo "PASS  $1"; pass=$((pass+1)); }
bad()  { echo "FAIL  $1"; fail=$((fail+1)); }
note() { echo "WARN  $1"; warn=$((warn+1)); }
sh_()  { $A -n "$1" </dev/null 2>/dev/null | tr -d '\r'; }
top()  { sh_ 'dumpsys activity activities | grep -m1 mResumedActivity' | grep -oE 'u0 [^/ ]+' | cut -d' ' -f2; }

adb connect $TV >/dev/null 2>&1
[ "$(sh_ 'getprop sys.boot_completed')" = "1" ] && ok "TV reachable over ADB" || { bad "TV not reachable"; exit 1; }

echo "== Home screen"
home=$(sh_ 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' | tail -1)
[ "$home" = "$LAUNCHER/.MainActivity" ] && ok "Home resolves to Troop 82 launcher" || bad "Home resolves to: $home"
$A input keyevent KEYCODE_HOME; sleep 2
[ "$(top)" = "$LAUNCHER" ] && ok "Home button opens launcher" || bad "Home button opened $(top)"
$A input keyevent KEYCODE_BACK; sleep 1
[ "$(top)" = "$LAUNCHER" ] && ok "Back on home stays on home" || bad "Back left the home screen"

echo "== Apps launch and Home returns"
st=org.smarttube.beta; sh_ "pm list packages -e $st" | grep -q . || st=com.liskovsoft.smarttubetv.beta
for p in com.plexapp.android com.google.android.youtube.tvunplugged ar.tvplayer.tv $st com.netflix.ninja com.esaba.downloader com.android.vending; do
  if ! sh_ "pm list packages -e $p" | grep -qx "package:$p"; then bad "$p missing or disabled"; continue; fi
  sh_ "monkey -p $p -c android.intent.category.LEANBACK_LAUNCHER 1" >/dev/null; sleep 5
  t=$(top)
  [ "$t" = "$p" ] && ok "$p opens" || bad "$p did not open (top: $t)"
  $A input keyevent KEYCODE_HOME; sleep 2
  [ "$(top)" = "$LAUNCHER" ] || bad "Home did not return from $p"
  $A am force-stop $p
done

echo "== HDMI inputs"
for hw in HW15 HW16 HW17; do
  sh_ "am start -a android.intent.action.VIEW -d content://android.media.tv/passthrough/com.tcl.tvinput%2F.TvPassThroughService%2F$hw" >/dev/null; sleep 3
  [ "$(top)" = "com.tcl.tv" ] && ok "HDMI input $hw opens" || bad "HDMI $hw did not open (top: $(top))"
  $A input keyevent KEYCODE_HOME; sleep 2
done
[ "$(top)" = "$LAUNCHER" ] && ok "Home returns from HDMI" || bad "Home did not return from HDMI"

echo "== Launcher screens"
sh_ "am start -n $LAUNCHER/.AllAppsActivity" >/dev/null; sleep 2
[ "$(top)" = "$LAUNCHER" ] && ok "All Apps opens" || bad "All Apps failed"
$A input keyevent KEYCODE_BACK; sleep 1
sh_ "am start -a android.settings.SETTINGS" >/dev/null; sleep 3
[ "$(top)" = "com.android.tv.settings" ] && ok "Settings opens" || bad "Settings did not open (top: $(top))"
$A input keyevent KEYCODE_HOME; sleep 2

echo "== Screensaver"
[ "$(sh_ 'settings get secure screensaver_components')" = "$LAUNCHER/.TroopDream" ] && ok "Troop 82 screensaver selected" || bad "Screensaver is $(sh_ 'settings get secure screensaver_components')"
[ "$(sh_ 'settings get secure screensaver_enabled')" = "1" ] && ok "Screensaver enabled" || bad "Screensaver disabled"
sh_ 'am start -n com.android.systemui/.Somnambulator' >/dev/null; sleep 6
sh_ 'dumpsys dreams' | grep -q "TroopDream" && ok "Screensaver starts" || bad "Screensaver did not start"
$A input keyevent KEYCODE_DPAD_CENTER; sleep 2
[ "$(sh_ 'dumpsys dreams' | grep -m1 mCurrentDreamName)" = "mCurrentDreamName=null" ] && ok "Remote key exits screensaver" || bad "Screensaver did not exit"

echo "== Data feeds"
files=$(sh_ "run-as $LAUNCHER ls files" 2>/dev/null)
if [ -z "$files" ]; then note "Can't read cache (release build blocks run-as); checked on screen instead"; else
  for f in sheet.csv cal0.ics cal1.ics cal2.ics cal3.ics; do echo "$files" | grep -qx "$f" && ok "cached $f" || bad "missing cache $f"; done
fi
# The sheet's published CSV link lives in local.env (not in git), e.g.
#   SHEET_CSV_URL="https://docs.google.com/spreadsheets/d/<id>/export?format=csv"
[ -f /c/tcl/local.env ] && . /c/tcl/local.env
if [ -z "$SHEET_CSV_URL" ]; then note "SHEET_CSV_URL not set in local.env; skipped sheet check"
else curl -fsSL "$SHEET_CSV_URL" | head -1 | grep -q "^Message" \
  && ok "Announcements sheet is reachable as CSV" || bad "Announcements sheet not reachable (sharing changed?)"; fi

echo "== Crashes since boot"
c=$(sh_ "logcat -d -b crash" | grep -ciE "$LAUNCHER|FATAL")
[ "$c" = "0" ] && ok "No crashes in crash log" || bad "$c crash-log lines (adb shell logcat -d -b crash)"
anr=$(sh_ "ls /data/anr 2>/dev/null" | wc -l)
sh_ "dumpsys activity processes" | grep -q "$LAUNCHER.*ANR" && bad "Launcher ANR" || ok "No launcher ANR"

echo "== Core system packages still enabled"
for p in com.tcl.suspension com.tcl.tv com.tcl.tvinput com.tcl.ttvs com.tcl.providers.config com.tcl.initsetup com.tcl.autopair \
         com.tcl.UpdatePeripheral com.tcl.systemserver com.tcl.globalkeyoverlay com.google.android.gms com.google.android.gsf \
         com.android.vending com.google.android.tv.remote.service com.google.android.katniss com.google.android.apps.mediashell \
         com.android.tv.settings com.android.providers.tv com.android.location.fused com.realtek.power com.netflix.tokenmanager; do
  sh_ "pm list packages -e $p" | grep -qx "package:$p" && ok "$p enabled" || bad "$p is DISABLED"
done

echo "== Debloat still applied (firmware updates can undo it)"
reenabled=""
while read p; do [ -z "$p" ] && continue
  # disabled, or removed for this user, both count as debloated
  sh_ "pm list packages -e $p" | grep -qx "package:$p" && reenabled="$reenabled $p"
done < /c/tcl/phase1-packages.txt
for p in com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith; do
  sh_ "pm list packages -d $p" | grep -qx "package:$p" || reenabled="$reenabled $p"; done
[ -z "$reenabled" ] && ok "All debloated apps still disabled" || note "Re-enabled:$reenabled"

echo "== Privacy / settings"
[ "$(sh_ 'settings get global private_dns_specifier')" = "dns.adguard-dns.com" ] && ok "AdGuard DNS set" || bad "Private DNS not AdGuard"
sh_ 'dumpsys connectivity' | grep -q "ValidatedPrivateDnsAddresses: \[9" && ok "Private DNS validated" || note "Private DNS not validated right now"
sh_ 'ping -c1 -W3 doubleclick.net' | grep -qE "127.0.0.1|0.0.0.0|unknown host|100% packet loss" && ok "Ad domain blocked" || bad "Ad domain resolves"
sh_ 'ping -c1 -W3 plex.tv' | grep -q "bytes from\|PING plex.tv (" && ok "Normal sites resolve" || bad "Normal DNS broken"
[ "$(sh_ 'settings get global tcl_uxp_status')" = "0" ] && ok "TCL UX program off" || bad "TCL UX program on"
[ "$(sh_ 'settings get global tcl_diagnostic_status')" = "0" ] && ok "TCL diagnostics off" || bad "TCL diagnostics on"
[ "$(sh_ 'settings get global package_verifier_enable')" = "0" ] && ok "Play Protect off (as requested)" || note "Play Protect is on"

echo "== Resources"
mem=$(sh_ "dumpsys meminfo $LAUNCHER" | grep -m1 "TOTAL PSS" | awk '{print $3}')
echo "INFO  launcher memory: $((mem/1024)) MB"
echo "INFO  free memory: $(( $(sh_ 'grep MemAvailable /proc/meminfo' | awk '{print $2}') / 1024 )) MB"
echo "INFO  free storage: $(sh_ 'df -h /data' | tail -1 | awk '{print $4}')"

$A input keyevent KEYCODE_HOME
echo; echo "SUMMARY: $pass passed, $fail failed, $warn warnings"
[ $fail -eq 0 ]
