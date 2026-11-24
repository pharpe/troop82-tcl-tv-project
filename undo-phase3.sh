#!/bin/sh
# Restore the Google TV home screen and screensaver on one TV, and unregister the TV
# so self-heal doesn't undo it (run setup-tv.sh to bring it back).
# Usage: /c/tcl/undo-phase3.sh 192.168.1.158
cd "$(dirname "$0")"
IP=${1:?usage: undo-phase3.sh <tv-ip>}; TV="$IP:5555"
add_line() { f=$1; v=$2; [ -f "$f" ] && tr -d '\r' < "$f" | grep -qxF "$v" && return
  [ -s "$f" ] && [ -n "$(tail -c1 "$f")" ] && echo >> "$f"; echo "$v" >> "$f"; }
adb connect "$TV" >/dev/null
serial=$(adb -s "$TV" shell getprop ro.serialno | tr -d '\r\n')
[ -n "$serial" ] || { echo "Cannot reach $TV"; exit 1; }
# unregister it, so self-heal and the other scripts leave it as it is
if [ -f tv-serials.txt ]; then tr -d '\r' < tv-serials.txt | grep -vxF "$serial" > tv-serials.tmp; mv tv-serials.tmp tv-serials.txt; fi
for p in com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith com.google.android.apps.tv.dreamx; do
  adb -s "$TV" shell -n pm enable --user 0 "$p" </dev/null
done
adb -s "$TV" shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity
adb -s "$TV" shell settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop
adb -s "$TV" shell settings put secure screensaver_default_component com.google.android.apps.tv.dreamx/.service.Backdrop
adb -s "$TV" shell input keyevent KEYCODE_HOME
echo "$TV is no longer managed by self-heal (run setup-tv.sh $IP to bring it back)."
