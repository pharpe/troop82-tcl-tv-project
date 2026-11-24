package org.troop82.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Receives the announcements sheet link from the maintenance PC:
 *   adb shell am broadcast -n org.troop82.launcher/.ConfigReceiver --es sheet_url "<link>"
 * The manifest protects this receiver with android.permission.DUMP, which ADB's
 * shell has but ordinary apps can never be granted, so no app installed on the
 * TV can redirect the troop's announcements.
 */
public class ConfigReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !intent.hasExtra(MainActivity.EXTRA_SHEET_URL)) return;
        String url = intent.getStringExtra(MainActivity.EXTRA_SHEET_URL);
        url = url == null ? "" : url.trim();
        if (!url.isEmpty() && !url.startsWith("https://")) {
            setResultData("rejected: link must start with https://");
            return;
        }
        context.getSharedPreferences("config", Context.MODE_PRIVATE).edit()
                .putString(MainActivity.PREF_SHEET_URL, url).commit();
        setResultData(url.isEmpty() ? "sheet cleared" : "sheet set");
    }
}
