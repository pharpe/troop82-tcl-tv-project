#!/bin/sh
# Re-enable everything the debloat disabled on one TV, and unregister the TV so
# self-heal doesn't re-disable it (run setup-tv.sh to bring it back).
# Usage: /c/tcl/undo-phase1.sh 192.168.1.158
cd "$(dirname "$0")"
IP=${1:?usage: undo-phase1.sh <tv-ip>}; TV="$IP:5555"
add_line() { f=$1; v=$2; [ -f "$f" ] && tr -d '\r' < "$f" | grep -qxF "$v" && return
  [ -s "$f" ] && [ -n "$(tail -c1 "$f")" ] && echo >> "$f"; echo "$v" >> "$f"; }
adb connect "$TV" >/dev/null
serial=$(adb -s "$TV" shell getprop ro.serialno | tr -d '\r\n')
[ -n "$serial" ] || { echo "Cannot reach $TV"; exit 1; }
# unregister it, so self-heal and the other scripts leave it as it is
if [ -f tv-serials.txt ]; then tr -d '\r' < tv-serials.txt | grep -vxF "$serial" > tv-serials.tmp; mv tv-serials.tmp tv-serials.txt; fi
for p in $(tr -d '\r' < phase1-packages.txt; tr -d '\r' < "setup-extra-$IP.txt" 2>/dev/null); do
  echo "$p: $(adb -s "$TV" shell -n pm enable --user 0 "$p" </dev/null 2>&1 | tr -d '\r')"
done
# Samba TV was removed for the user (TCL kept re-enabling it); restore it:
adb -s "$TV" shell cmd package install-existing tv.samba.ssm.ui
echo "$TV is no longer managed by self-heal (run setup-tv.sh $IP to bring it back)."
