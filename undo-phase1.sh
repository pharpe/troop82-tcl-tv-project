#!/bin/sh
# Re-enable everything Phase 1 disabled
TV=192.168.1.174:5555
adb connect $TV >/dev/null
while read p; do [ -n "$p" ] && echo "$p: $(adb -s $TV shell -n pm enable --user 0 $p 2>&1)"; done < "$(dirname "$0")/phase1-packages.txt"
# Samba TV was removed for user 0 (TCL kept re-enabling it); restore with:
adb -s $TV shell cmd package install-existing tv.samba.ssm.ui
