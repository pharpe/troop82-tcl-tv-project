#!/bin/sh
# dump current TV UI text
MSYS_NO_PATHCONV=1 adb -s 192.168.1.174:5555 shell 'uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml' | python /c/tcl/ui.py
