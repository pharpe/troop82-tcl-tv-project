#!/bin/sh
# Troop 82 TV self-heal. Runs daily from Windows Task Scheduler (see install-heal-task.ps1).
# Finds the TV on the network, then quietly re-applies the setup if anything drifted
# (TCL firmware updates can re-enable apps or reset settings). Changes nothing that
# is already correct. Log: C:/tcl/heal.log
cd "$(dirname "$0")"
LOG=./heal.log
LAUNCHER=org.troop82.launcher
log() { echo "$(date '+%Y-%m-%d %H:%M') $*" >> "$LOG"; }

# Run a command with a time limit (coreutils timeout can't wrap Windows adb.exe in Git Bash).
limit() { secs=$1; shift; out=$(mktemp)
  "$@" >"$out" 2>/dev/null </dev/null & pid=$!
  # the guard must not hold our output open, or $(...) would wait for its sleep
  ( sleep "$secs"; kill "$pid" 2>/dev/null ) >/dev/null 2>&1 </dev/null & guard=$!
  wait "$pid" 2>/dev/null; kill "$guard" 2>/dev/null
  cat "$out"; rm -f "$out"; }

# --- find the TVs (saved addresses first; rescan the network if any went missing) ---
reachable() { [ "$(limit 15 adb -s "$1" shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; }
TVS=$(tr -d '\r' < tv-addresses.txt 2>/dev/null)
missing=0
for t in $TVS; do adb connect "$t" >/dev/null 2>&1; reachable "$t" || missing=1; done
if [ $missing = 1 ]; then
  found=$(powershell.exe -NoProfile -Command '
    $ip = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.InterfaceAlias -like "Wi-Fi*" -or $_.InterfaceAlias -like "Ethernet*" } | Where-Object { $_.IPAddress -notlike "169.*" } | Select-Object -First 1).IPAddress
    if (-not $ip) { exit }
    $base = ($ip -split "\.")[0..2] -join "."
    $jobs = 1..254 | ForEach-Object { $c = New-Object Net.Sockets.TcpClient; @{ip="$base.$_"; c=$c; t=$c.ConnectAsync("$base.$_",5555)} }
    Start-Sleep -Milliseconds 1500
    $jobs | Where-Object { $_.t.Status -eq "RanToCompletion" -and $_.c.Connected } | ForEach-Object { $_.ip }
    $jobs | ForEach-Object { $_.c.Dispose() }' 2>/dev/null | tr -d '\r')
  for ip in $found; do
    adb connect "$ip:5555" >/dev/null 2>&1
    if adb -s "$ip:5555" shell getprop ro.product.name 2>/dev/null | grep -q G10_4K_US_NF; then
      grep -qx "$ip:5555" tv-addresses.txt || { echo "$ip:5555" >> tv-addresses.txt; log "found TV at new address $ip"; }
    fi
  done
  TVS=$(tr -d '\r' < tv-addresses.txt)
fi

heal_tv() {
TV=$1
if ! reachable "$TV"; then log "$TV not reachable (off, or address changed) - skipped"; return; fi
A="adb -s $TV shell"
# Every TV command has a time limit, so a TV going into standby can't hang the run.
sh_() { limit 20 adb -s "$TV" shell -n "$1" | tr -d '\r'; }
fixed=""

# --- debloat: re-disable anything a firmware update turned back on ----------
for list in phase1-packages.txt phase3-packages.txt; do
  while read -r p; do
    p=$(echo "$p" | tr -d '\r'); [ -z "$p" ] && continue
    if sh_ "pm list packages -e $p" | grep -qx "package:$p"; then
      sh_ "pm disable-user --user 0 $p" >/dev/null; fixed="$fixed disabled:$p"
    fi
  done < "$list"
done
# Samba TV (viewing tracker) is removed for the user because TCL re-enables it.
if sh_ "pm list packages tv.samba.ssm.ui" | grep -qx "package:tv.samba.ssm.ui"; then
  sh_ "pm uninstall -k --user 0 tv.samba.ssm.ui" >/dev/null; fixed="$fixed removed:samba"
fi

# --- home screen and screensaver --------------------------------------------
if ! sh_ "pm list packages -e $LAUNCHER" | grep -qx "package:$LAUNCHER"; then
  if [ -f troop82-launcher/troop82-launcher.apk ]; then
    adb -s "$TV" install -r troop82-launcher/troop82-launcher.apk >/dev/null 2>&1 && fixed="$fixed reinstalled:launcher"
  else
    log "WARNING launcher missing and no APK to reinstall (run troop82-launcher/build.sh install)"
  fi
fi
home=$(sh_ 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' | tail -1)
if [ -n "$home" ] && [ "$home" != "$LAUNCHER/.MainActivity" ]; then
  sh_ "cmd package set-home-activity $LAUNCHER/.MainActivity" >/dev/null; fixed="$fixed home"
fi
setting() { # namespace key value
  cur=$(sh_ "settings get $1 $2")
  [ -z "$cur" ] && return   # no answer from the TV: don't guess
  [ "$cur" = "$3" ] || { sh_ "settings put $1 $2 $3" >/dev/null; fixed="$fixed $2"; }
}
setting secure screensaver_components "$LAUNCHER/.TroopDream"
setting secure screensaver_default_component "$LAUNCHER/.TroopDream"
setting secure screensaver_enabled 1

# --- privacy and ad blocking -------------------------------------------------
setting global private_dns_mode hostname
setting global private_dns_specifier dns.adguard-dns.com
setting global tcl_uxp_status 0
setting global tcl_diagnostic_status 0
setting global tcl_app_ssm_switch 0

reachable "$TV" || { log "$TV went offline during the check (standby?) - will retry next run"; return; }
if [ -n "$fixed" ]; then log "$TV healed:$fixed"; else log "$TV ok - nothing to fix"; fi
}

for t in $TVS; do heal_tv "$t"; done
# keep the log small
tail -n 500 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
