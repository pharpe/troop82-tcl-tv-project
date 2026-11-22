#!/bin/sh
# Restore the Google TV home screen
TV=192.168.1.174:5555; adb connect $TV >/dev/null
for p in com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith; do adb -s $TV shell -n pm enable --user 0 $p; done
adb -s $TV shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity
adb -s $TV shell input keyevent KEYCODE_HOME
