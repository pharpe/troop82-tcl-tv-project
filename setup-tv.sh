#!/bin/sh
# Apply the complete Troop 82 setup to a TCL Google TV (model G10_4K_US_NF).
# Usage: /c/tcl/setup-tv.sh 192.168.1.158
# The TV must have network debugging on and this PC approved ("Always allow").
# Safe to re-run: every step checks the current state first.
set -u
cd "$(dirname "$0")"
IP=${1:?usage: setup-tv.sh <tv-ip>}
TV="$IP:5555"
LAUNCHER=org.troop82.launcher
A="adb -s $TV shell"
sh_() { $A -n "$1" </dev/null 2>/dev/null | tr -d '\r'; }
step() { echo "== $*"; }

adb connect "$TV" >/dev/null 2>&1
[ "$(sh_ 'getprop ro.product.name')" = "G10_4K_US_NF" ] || { echo "Not the expected TCL model (or not authorized) at $TV"; exit 1; }

# Apps every Troop 82 TV keeps. Netflix is a system app and is never touched.
KEEP="com.plexapp.android com.google.android.youtube.tvunplugged ar.tvplayer.tv org.smarttube.beta com.liskovsoft.smarttubetv.beta com.esaba.downloader $LAUNCHER"

step "Debloat (same list as the first TV)"
for list in phase1-packages.txt phase3-packages.txt; do
  while read -r p; do p=$(echo "$p" | tr -d '\r'); [ -z "$p" ] && continue
    case "$p" in com.google.android.apps.tv.launcherx|com.google.android.tungsten.setupwraith) continue;; esac  # after launcher install
    sh_ "pm list packages -e $p" | grep -qx "package:$p" && echo "  disable $p: $(sh_ "pm disable-user --user 0 $p")"
  done < "$list"
done
step "Disable other user-installed apps not on the keep list"
for p in $(sh_ 'pm list packages -3 -e' | sed 's/package://'); do
  case " $KEEP " in *" $p "*) ;; *) echo "  disable $p: $(sh_ "pm disable-user --user 0 $p")";; esac
done
if sh_ "pm list packages tv.samba.ssm.ui" | grep -qx "package:tv.samba.ssm.ui"; then
  step "Remove Samba TV viewing tracker for the user"; sh_ "pm uninstall -k --user 0 tv.samba.ssm.ui"
fi

step "Privacy, ad blocking, Play Protect off"
for kv in "global private_dns_mode hostname" "global private_dns_specifier dns.adguard-dns.com" \
          "global tcl_uxp_status 0" "global tcl_diagnostic_status 0" "global tcl_app_ssm_switch 0" \
          "global package_verifier_enable 0" "global verifier_verify_adb_installs 0" "global package_verifier_user_consent -1"; do
  set -- $kv; sh_ "settings put $1 $2 $3" >/dev/null
done

step "Install Troop 82 launcher and make it the home screen"
adb -s "$TV" install -r troop82-launcher/troop82-launcher.apk | tail -1
for p in com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith com.spocky.projengmenu; do
  sh_ "pm list packages -e $p" | grep -qx "package:$p" && echo "  disable $p: $(sh_ "pm disable-user --user 0 $p")"
done
sh_ "settings delete secure enabled_accessibility_services" >/dev/null
sh_ "cmd package set-home-activity $LAUNCHER/.MainActivity"
sh_ "settings put secure screensaver_components $LAUNCHER/.TroopDream"
sh_ "settings put secure screensaver_default_component $LAUNCHER/.TroopDream"
sh_ "settings put secure screensaver_enabled 1"

step "Point announcements at the troop sheet"
[ -f local.env ] && . ./local.env
if [ -n "${SHEET_CSV_URL:-}" ]; then
  sh_ "am start -n $LAUNCHER/.MainActivity --es sheet_url '$SHEET_CSV_URL'" >/dev/null && echo "  sheet set"
else echo "  WARNING: SHEET_CSV_URL missing from local.env"; fi

# --- On-screen opt-outs (TCL agreements, Google ad ID) -----------------------
ui() { MSYS_NO_PATHCONV=1 adb -s "$TV" shell 'uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml' | python ui.py; }
step "TCL Terms & Privacy: untick User Experience Program and Diagnostics"
sh_ "am start -n com.tcl.useragreement/.module_oversea.activity.TCLUserAgreementActivity" >/dev/null; sleep 3
screen=$(ui)
for item in "Terms of User Experience Improvement Program" "Share Diagnostics Data"; do
  # the checkbox sits on the same row, just left of the label
  row=$(echo "$screen" | grep -F "$item" | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1)
  y=$(echo "$row" | sed -E 's/\[[0-9]+,([0-9]+)\]\[[0-9]+,([0-9]+)\]/\1 \2/' | awk '{print int(($1+$2)/2)}')
  state=$(echo "$screen" | awk -v y="$y" 'match($0,/\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]/){b=substr($0,RSTART,RLENGTH); split(b,c,/[^0-9]+/); if (c[3]<=y && c[5]>=y && $0 ~ /Selected/) print}' | grep -oE "Not Selected|Selected" | head -1)
  if [ "$state" = "Selected" ]; then $A input tap 1220 "$y"; sleep 1; echo "  unticked: $item"; else echo "  already off: $item"; fi
done
agree=$(ui | grep -E " Agree$" | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1)
if [ -n "$agree" ]; then
  set -- $(echo "$agree" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/')
  $A input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 2
fi
echo "  uxp=$(sh_ 'settings get global tcl_uxp_status') diagnostics=$(sh_ 'settings get global tcl_diagnostic_status')"

step "Delete Google advertising ID"
sh_ "am start -a com.google.android.gms.settings.ADS_PRIVACY" >/dev/null; sleep 3
if ui | grep -q "Delete advertising ID"; then
  del=$(ui | grep -E " Delete advertising ID$" | head -1 | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]')
  set -- $(echo "$del" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/')
  $A input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 2
  $A input keyevent KEYCODE_DPAD_CENTER; sleep 2
fi
ui | grep -q "Get new advertising ID" && echo "  advertising ID deleted" || echo "  WARNING: check Settings > Privacy > Ads"

$A input keyevent KEYCODE_HOME
grep -qx "$TV" tv-addresses.txt 2>/dev/null || echo "$TV" >> tv-addresses.txt
step "Done: $TV. Run /c/tcl/qa.sh $IP to verify."
