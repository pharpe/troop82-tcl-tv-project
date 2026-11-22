#!/bin/sh
# Point the TV's announcements panel at a published Google Sheet CSV link.
# Usage: ./set-sheet.sh "https://docs.google.com/spreadsheets/d/e/.../pub?output=csv"
#        ./set-sheet.sh ""      (to turn announcements off)
TV=192.168.1.174:5555
adb connect $TV >/dev/null
adb -s $TV shell am start -n org.troop82.launcher/.MainActivity --es sheet_url "'$1'"
