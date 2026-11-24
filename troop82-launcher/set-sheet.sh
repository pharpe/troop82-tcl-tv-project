#!/bin/sh
# Point the TVs' announcements panel at a published Google Sheet CSV link.
# Usage: ./set-sheet.sh "<published CSV link>" [tv-ip ...]   (default: every TV in ../tv-addresses.txt)
#        ./set-sheet.sh ""                                  (turn announcements off)
cd "$(dirname "$0")"
[ $# -ge 1 ] || { echo 'usage: ./set-sheet.sh "<published CSV link>" [tv-ip ...]   (use "" to turn announcements off)'; exit 1; }
url=$1; shift
case "$url" in ""|https://*) ;; *) echo "The link must start with https:// (the TVs reject anything else)"; exit 1;; esac
# changing it for all TVs also updates local.env, so future fresh installs get the same link
if [ $# -eq 0 ]; then
  printf 'SHEET_CSV_URL="%s"\n' "$url" > ../local.env
  echo "local.env updated"
  # queue it for every registered TV; self-heal delivers it to TVs that are off right now
  for s in $(tr -d '\r' < ../tv-serials.txt 2>/dev/null); do : > "../sheet-pending-$s"; done
fi
targets="$*"; [ -z "$targets" ] && targets=$(tr -d '\r' < ../tv-addresses.txt)
for t in $targets; do
  case "$t" in *:*) ;; *) t="$t:5555";; esac
  if tr -d '\r \t' < ../heal-skip.txt 2>/dev/null | sed 's/:5555$//' | grep -qxF "${t%:5555}"; then echo "$t: skipped (in heal-skip.txt)"; continue; fi
  adb connect "$t" >/dev/null 2>&1
  s=$(adb -s "$t" shell getprop ro.serialno 2>/dev/null | tr -d '\r\n')
  if ! tr -d '\r' < ../tv-serials.txt 2>/dev/null | grep -qxF "$s"; then echo "$t: not a registered Troop 82 TV (run setup-tv.sh) - skipped"; continue; fi
  if tr -d '\r \t' < ../heal-skip.txt 2>/dev/null | grep -qxF "$s"; then echo "$t: skipped (serial in heal-skip.txt)"; continue; fi
  r=$(adb -s "$t" shell am broadcast -f 0x20 -n org.troop82.launcher/.ConfigReceiver --es sheet_url "'$url'" | grep -oE 'data="[^"]*"')
  echo "$t: ${r:-no reply}"
  case "$r" in *"sheet set"*|*"sheet cleared"*) rm -f "../sheet-pending-$s";; esac
done
