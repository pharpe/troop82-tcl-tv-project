#!/bin/sh
# Restore the Google TV home screen and screensaver on one TV, and stop self-heal from
# undoing it (adds the TV's address AND serial number to heal-skip.txt).
# Usage: /c/tcl/undo-phase3.sh 192.168.1.158
cd "$(dirname "$0")"
IP=${1:?usage: undo-phase3.sh <tv-ip>}; TV="$IP:5555"
add_line() { f=$1; v=$2; [ -f "$f" ] && tr -d '\r' < "$f" | grep -qxF "$v" && return
  [ -s "$f" ] && [ -n "$(tail -c1 "$f")" ] && echo >> "$f"; echo "$v" >> "$f"; }
adb connect "$TV" >/dev/null
serial=$(adb -s "$TV" shell getprop ro.serialno | tr -d '\r\n')
[ -n "$serial" ] || { echo "Cannot reach $TV"; exit 1; }
add_line heal-skip.txt "$TV"; add_line heal-skip.txt "$serial"
for p in com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith com.google.android.apps.tv.dreamx; do
  adb -s "$TV" shell -n pm enable --user 0 "$p" </dev/null
done
adb -s "$TV" shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity
adb -s "$TV" shell settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop
adb -s "$TV" shell settings put secure screensaver_default_component com.google.android.apps.tv.dreamx/.service.Backdrop
adb -s "$TV" shell input keyevent KEYCODE_HOME
echo "Self-heal will now skip this TV (remove $TV and $serial from heal-skip.txt to resume)."
