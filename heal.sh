#!/bin/sh
# Troop 82 TV self-heal. Runs from Windows Task Scheduler (see install-heal-task.ps1).
# Checks every TV in tv-addresses.txt (and finds managed TVs again if they move), then
# quietly re-applies the setup if anything drifted (TCL firmware updates can re-enable
# apps or reset settings), and installs a newer launcher build if one was made on this PC.
# Changes nothing that is already correct. Log: C:/tcl/heal.log
#
# Pausing: create C:/tcl/heal.disabled to pause all TVs, or put a TV's address or serial
# number in C:/tcl/heal-skip.txt to leave just that TV alone (the undo scripts do this).
cd "$(dirname "$0")"
LOG=./heal.log
LAUNCHER=org.troop82.launcher
# only a build that passed `build.sh release` (tests) is ever rolled out
APK=troop82-launcher/release/troop82-launcher.apk
VERFILE=troop82-launcher/release/troop82-launcher.version
RUN_DEADLINE=$(( $(date +%s) + 480 ))   # finish well inside the task's 10-minute limit
log() { echo "$(date '+%Y-%m-%d %H:%M') $*" >> "$LOG"; }
lines() { [ -f "$1" ] && tr -d '\r' < "$1" | grep -v '^[[:space:]]*$'; }
# append a line safely (adds a missing final newline first; skips duplicates)
add_line() { f=$1; v=$2
  lines "$f" | grep -qxF "$v" && return
  [ -s "$f" ] && [ -n "$(tail -c1 "$f")" ] && echo >> "$f"
  echo "$v" >> "$f"; }
# skip-list entries may be "192.168.1.174", "192.168.1.174:5555" or a serial; spaces ignored
norm() { tr -d ' \t\r' | sed 's/:5555$//'; }
skipped() { v=$(printf '%s' "$1" | norm); lines heal-skip.txt | norm | grep -qxF "$v"; }
# Only TVs registered by setup-tv.sh (serial in tv-serials.txt) are ever changed.
registered() { [ -n "$1" ] && lines tv-serials.txt | grep -qxF "$1"; }
sheet_link() { [ -f local.env ] && tr -d '\r' < local.env | sed -n 's/^SHEET_CSV_URL="\{0,1\}\([^"]*\)"\{0,1\}$/\1/p'; }

if [ -f heal.disabled ]; then log "paused (heal.disabled exists) - nothing done"; exit 0; fi

# Run a command with a time limit (coreutils timeout can't wrap Windows adb.exe in Git Bash).
# Prints the command's output; returns 124 if it had to be stopped.
limit() { secs=$1; shift; out=$(mktemp); flag="$out.timedout"
  "$@" >"$out" 2>&1 </dev/null & pid=$!
  # the guard must not hold our output open, or $(...) would wait for its sleep
  ( exec sleep "$secs" ) >/dev/null 2>&1 </dev/null & sleeper=$!
  ( while kill -0 "$sleeper" 2>/dev/null && kill -0 "$pid" 2>/dev/null; do sleep 1; done
    kill -0 "$pid" 2>/dev/null && { : > "$flag"; kill "$pid" 2>/dev/null; } ) >/dev/null 2>&1 </dev/null & guard=$!
  wait "$pid" 2>/dev/null; rc=$?; kill "$sleeper" 2>/dev/null; wait "$guard" 2>/dev/null
  cat "$out"; rm -f "$out"
  if [ -f "$flag" ]; then rm -f "$flag"; return 124; fi
  return $rc; }
# adb's own connection failures (as opposed to an answer from the TV)
adb_failed() { grep -qE '^(error: (device|closed|no devices)|adb(\.exe)?: |error: protocol fault)|device offline|device unauthorized|cannot connect'; }

# --- find the TVs (saved addresses first; look for moved managed TVs if any is missing) ---
reachable() { [ "$(limit 15 adb -s "$1" shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; }
if [ ! -f tv-addresses.txt ]; then log "ERROR tv-addresses.txt is missing - no TVs to check"; exit 1; fi
TVS=$(lines tv-addresses.txt)
missing=0
for t in $TVS; do
  skipped "$t" && continue            # never contact a TV the owner asked us to leave alone
  limit 15 adb connect "$t" >/dev/null; reachable "$t" || missing=1
done
if [ $missing = 1 ] && [ -s tv-macs.txt ]; then
  # A registered TV may have a new address. Look it up by its network hardware address
  # (recorded while it was reachable) in Windows' neighbour table: no probing of other
  # devices, so nothing else on the network ever sees an "Allow debugging?" prompt.
  macs=$(lines tv-macs.txt | tr 'A-F:' 'a-f-' | paste -sd'|' -)
  # A quick ping of the local subnet first (plain ICMP, nothing adb) so the neighbour table
  # knows every current address, then match the recorded MACs.
  found=$(limit 60 powershell.exe -NoProfile -Command "
    \$ip = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { \$_.InterfaceAlias -like 'Wi-Fi*' -or \$_.InterfaceAlias -like 'Ethernet*' } | Where-Object { \$_.IPAddress -notlike '169.*' } | Select-Object -First 1).IPAddress
    if (\$ip) {
      \$base = (\$ip -split '\.')[0..2] -join '.'
      \$tasks = 1..254 | ForEach-Object { (New-Object System.Net.NetworkInformation.Ping).SendPingAsync(\"\$base.\$_\", 800) }
      try { [System.Threading.Tasks.Task]::WaitAll(\$tasks) } catch { }
    }
    Get-NetNeighbor -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object { \$_.LinkLayerAddress -match '^($macs)\$' } | ForEach-Object { \$_.IPAddress }" 2>/dev/null | tr -d '\r')
  for ip in $found; do
    lines tv-addresses.txt | grep -qxF "$ip:5555" && continue
    skipped "$ip" && continue
    limit 15 adb connect "$ip:5555" >/dev/null
    if ! adb devices | tr -d '\r' | grep -qE "^$ip:5555[[:space:]]+device$"; then
      limit 15 adb disconnect "$ip:5555" >/dev/null; continue
    fi
    s=$(limit 15 adb -s "$ip:5555" shell getprop ro.serialno | tr -d '\r\n')
    # adopt only a TV this script already manages (never an unknown or skipped TV)
    if [ -n "$s" ] && lines tv-serials.txt | grep -qxF "$s" && ! skipped "$s"; then
      # forget the TV's old address (tv-map.txt: "serial address"), then record the new one
      old=$(lines tv-map.txt | awk -v s="$s" '$1 == s { print $2; exit }')
      # drop the old address only if nothing answers there any more (another TV may have it now)
      if [ -n "$old" ] && ! reachable "$old"; then
        lines tv-addresses.txt | grep -vxF "$old" > tv-addresses.tmp; mv tv-addresses.tmp tv-addresses.txt
      fi
      add_line tv-addresses.txt "$ip:5555"; log "found managed TV $s at new address $ip${old:+ (was $old)}"
    else
      limit 15 adb disconnect "$ip:5555" >/dev/null
    fi
  done
  TVS=$(lines tv-addresses.txt)
fi

heal_tv() {
TV=$1
if skipped "$TV"; then log "$TV skipped (listed in heal-skip.txt)"; return; fi
if ! reachable "$TV"; then log "$TV not reachable (off, standby, or address changed) - skipped"; return; fi
serial=$(limit 15 adb -s "$TV" shell getprop ro.serialno | tr -d '\r\n')
if [ -n "$serial" ] && skipped "$serial"; then log "$TV skipped (serial is in heal-skip.txt)"; return; fi
# One-time registration of TVs set up before serials were recorded: tv-preregister.txt holds
# "address package" lines; a TV at that address that has that (otherwise rare) package
# installed is registered once, and its line is removed.
if ! registered "$serial" && [ -n "$serial" ]; then
  # marker = comma list of packages that must ALL be installed; "!pkg" must be absent
  marker=$(lines tv-preregister.txt | awk -v a="$TV" '$1 == a { print $2; exit }')
  if [ -n "$marker" ]; then
    pk=$(limit 20 adb -s "$TV" shell -n "pm list packages" | tr -d '\r' | sed 's/^package://')
    match=1
    for m in $(echo "$marker" | tr ',' ' '); do
      case "$m" in
        !*) printf '%s\n' "$pk" | grep -qxF "${m#!}" && match=0;;
        *)  printf '%s\n' "$pk" | grep -qxF "$m" || match=0;;
      esac
    done
    if [ -n "$pk" ] && [ $match = 1 ]; then
      add_line tv-serials.txt "$serial"
      lines tv-preregister.txt | awk -v a="$TV" '$1 != a' > tv-preregister.tmp; mv tv-preregister.tmp tv-preregister.txt
      log "$TV registered (serial $serial) by its one-time fingerprint"
    fi
  fi
fi
# Addresses can change hands: only touch the TV if it is one setup-tv.sh registered.
if ! registered "$serial"; then
  log "$TV is not a registered Troop 82 TV (serial ${serial:-unknown}) - not touched. Run setup-tv.sh to register it."; return
fi
OFF=$(mktemp); rm -f "$OFF"   # exists = stop talking to this TV (a file, so it survives $(...) subshells)
offline() { [ -f "$OFF" ]; }
# Every TV command has a time limit. A command that has to be stopped, or the run's
# deadline passing, means: stop talking to this TV for this run.
sh_() {
  offline && return 1
  if [ "$(date +%s)" -gt "$RUN_DEADLINE" ]; then : > "$OFF"; return 1; fi
  r=$(limit 20 adb -s "$TV" shell -n "$1"); rc=$?
  if [ $rc = 124 ]; then : > "$OFF"; return 1; fi
  # a lost connection is not an answer: never treat its error text as TV output
  if printf '%s\n' "$r" | adb_failed; then : > "$OFF"; return 1; fi
  printf '%s\n' "$r" | tr -d '\r'
}
fixed=""
enabled_pkgs=$(sh_ "pm list packages -e" | sed 's/^package://')
all_pkgs=$(sh_ "pm list packages" | sed 's/^package://')
if [ -z "$all_pkgs" ]; then log "$TV did not answer the package query - skipped"; rm -f "$OFF"; return; fi
is_enabled() { printf '%s\n' "$enabled_pkgs" | grep -qxF "$1"; }
is_present() { printf '%s\n' "$all_pkgs" | grep -qxF "$1"; }
# remember this registered TV's network hardware address, to find it again if its IP changes
# and which address it is at now (tv-map.txt: "serial address")
if ! lines tv-map.txt | grep -qxF "$serial $TV"; then
  # this TV used to be at another address: forget it if nothing answers there now
  prev=$(lines tv-map.txt | awk -v s="$serial" '$1 == s { print $2; exit }')
  if [ -n "$prev" ] && [ "$prev" != "$TV" ] && ! reachable "$prev"; then
    lines tv-addresses.txt | grep -vxF "$prev" > tv-addresses.tmp; mv tv-addresses.tmp tv-addresses.txt
    log "$TV: dropped this TV's old address $prev"
  fi
  # one line per TV and per address (a TV that took over an address replaces the old owner's line)
  lines tv-map.txt | awk -v s="$serial" -v a="$TV" '$1 != s && $2 != a' > tv-map.tmp; echo "$serial $TV" >> tv-map.tmp; mv tv-map.tmp tv-map.txt
fi
# (both Wi-Fi and wired, whichever the TV uses)
for mac in $(sh_ "cat /sys/class/net/wlan0/address /sys/class/net/eth0/address 2>/dev/null" | grep -E '^([0-9a-f]{2}:){5}[0-9a-f]{2}$' | grep -v '^00:00:00:00:00:00$'); do
  add_line tv-macs.txt "$mac"
done

# --- launcher first: present, enabled and up to date ------------------------
local_ver=""; [ -f "$VERFILE" ] && local_ver=$(tr -d '\r\n' < "$VERFILE")
installed=""
is_present "$LAUNCHER" && installed=$(sh_ "dumpsys package $LAUNCHER" | grep -m1 -oE 'versionCode=[0-9]+' | cut -d= -f2)
need_install=0
if ! is_present "$LAUNCHER"; then need_install=1
elif [ -n "$installed" ] && [ -n "$local_ver" ] && [ "$local_ver" -gt "$installed" ]; then need_install=1; fi
if [ $need_install = 1 ] && [ ! -f "$APK" ]; then
  log "$TV WARNING launcher needs installing but there is no APK on this PC (run troop82-launcher/build.sh)"
elif [ $need_install = 1 ] && ! offline && [ $(( $(date +%s) + 120 )) -lt "$RUN_DEADLINE" ]; then
  was_present=0; is_present "$LAUNCHER" && was_present=1
  result=$(limit 120 adb -s "$TV" install -r "$APK")
  if printf '%s' "$result" | grep -q Success; then
    fixed="$fixed launcher:${installed:-none}->$local_ver"
    # a brand-new install has no sheet link yet: queue it (sent and confirmed below)
    [ $was_present = 0 ] && [ -n "$serial" ] && : > "sheet-pending-$serial"
    all_pkgs=$(sh_ "pm list packages" | sed 's/^package://')
  else
    log "$TV WARNING launcher install failed: $(printf '%s' "$result" | tail -1)"
  fi
  enabled_pkgs=$(sh_ "pm list packages -e" | sed 's/^package://')
fi
if is_present "$LAUNCHER" && ! is_enabled "$LAUNCHER"; then
  sh_ "pm enable --user 0 $LAUNCHER" >/dev/null && fixed="$fixed enabled:launcher"
  enabled_pkgs=$(sh_ "pm list packages -e" | sed 's/^package://')
fi
launcher_ok=0; is_enabled "$LAUNCHER" && launcher_ok=1
# Sheet link for a fresh install: retried every run until the TV confirms it.
if [ $launcher_ok = 1 ] && [ -n "$serial" ] && [ -f "sheet-pending-$serial" ] && ! offline; then
  url=$(sheet_link)   # empty = announcements deliberately turned off
  if [ -f local.env ] && sh_ "am broadcast -f 0x20 -n $LAUNCHER/.ConfigReceiver --es sheet_url '$url'" | grep -qE 'data="sheet (set|cleared)"'; then
    rm -f "sheet-pending-$serial"; fixed="$fixed sheet-link"
  else
    log "$TV WARNING sheet link not confirmed yet - will retry next run"
  fi
fi

# --- debloat: re-disable anything a firmware update turned back on ----------
for p in $(lines phase1-packages.txt); do
  is_enabled "$p" && { sh_ "pm disable-user --user 0 $p" >/dev/null; fixed="$fixed disabled:$p"; }
done
# Only take away the Google TV home screen when ours is definitely there.
if [ $launcher_ok = 1 ]; then
  for p in $(lines phase3-packages.txt); do
    is_enabled "$p" && { sh_ "pm disable-user --user 0 $p" >/dev/null; fixed="$fixed disabled:$p"; }
  done
else
  log "$TV WARNING Troop 82 launcher not available - leaving the Google TV home screen enabled"
fi
# Samba TV (viewing tracker) is removed for the user because TCL re-enables it.
is_present tv.samba.ssm.ui && { sh_ "pm uninstall -k --user 0 tv.samba.ssm.ui" >/dev/null; fixed="$fixed removed:samba"; }

if [ $launcher_ok = 1 ]; then
  home=$(sh_ 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' | tail -1)
  if [ -n "$home" ] && [ "$home" != "$LAUNCHER/.MainActivity" ]; then
    sh_ "cmd package set-home-activity $LAUNCHER/.MainActivity" >/dev/null; fixed="$fixed home"
  fi
fi
setting() { # namespace key value
  cur=$(sh_ "settings get $1 $2")
  [ -z "$cur" ] && return   # no answer from the TV: don't guess
  [ "$cur" = "$3" ] || { sh_ "settings put $1 $2 $3" >/dev/null; fixed="$fixed $2"; }
}
if [ $launcher_ok = 1 ]; then
  setting secure screensaver_components "$LAUNCHER/.TroopDream"
  setting secure screensaver_default_component "$LAUNCHER/.TroopDream"
  setting secure screensaver_enabled 1
fi

# --- privacy and ad blocking -------------------------------------------------
setting global private_dns_mode hostname
setting global private_dns_specifier dns.adguard-dns.com
setting global tcl_uxp_status 0
setting global tcl_diagnostic_status 0
setting global tcl_app_ssm_switch 0
# Play Protect app scanning off (owner's choice, so sideloaded troop apps aren't blocked)
setting global package_verifier_enable 0
setting global verifier_verify_adb_installs 0

if offline; then
  rm -f "$OFF"; log "$TV stopped answering during the check (standby?) - partial:${fixed:- nothing}; will retry next run"; return
fi
if [ -n "$fixed" ]; then log "$TV healed:$fixed"; else log "$TV ok - nothing to fix"; fi
}

for t in $TVS; do
  if [ "$(date +%s)" -gt "$RUN_DEADLINE" ]; then log "$t not checked this run (time limit) - next run"; continue; fi
  heal_tv "$t"
done
# keep the log small
tail -n 500 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
