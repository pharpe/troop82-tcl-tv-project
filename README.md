# Troop 82 TCL TV Project

Setup, debloat and a custom home screen for Troop 82's TCL Google TV
(Realtek RTD6748, Android TV 12). Everything is done over ADB, with no root.

## What's here

| Path | What it is |
|---|---|
| `troop82-launcher/` | Custom home screen + screensaver app (plain Java, no Gradle) |
| `qa.sh` | Health check, about 50 checks. Run it after any TV firmware update |
| `phase1-packages.txt` | Every app disabled during debloat |
| `undo-phase1.sh`, `undo-phase3.sh` | Re-enable debloated apps / restore the Google TV home screen |
| `TCL-TV-plan.md` | Original findings and plan |
| `logo/` | Troop 82 badge and TV background artwork |
| `ui.py`, `ui.sh` | Helper: dump the TV's on-screen text over ADB |

## The launcher

- **Home screen:** Troop 82 background, clock, and Plex / YouTube TV / TiviMate / SmartTube / Netflix as large tiles. A shortcut row has HDMI 1–3, Downloader, Play Store, All Apps and Settings.
- **News panel:**
  - Announcements come from the published Google Sheet *Troop 82 TV Announcements*, with columns Message, Show from, Show until.
  - Upcoming events come from the four public calendars on hptroop82.org/calendar.
  - It refreshes every 15 minutes and keeps the last good copy for offline use.
- **Screensaver (`TroopDream`):** flips through announcements and events full-screen, with Troop 82 branding.

### Build and install

```sh
# one-time: JDK 17 + Android build-tools 34 + platform 34 under C:/tcl/tools (see build.sh paths)
cd troop82-launcher
./build.sh install          # builds, signs, installs on the TV
./set-sheet.sh "<published CSV link>"   # point the TV at a different sheet
```

**Signing key:** `troop82.keystore` is deliberately not in git. Updates must be signed with the same key, so keep the copy you have backed up. If it's lost, the app has to be uninstalled and set up again.

### Tests

```sh
javac -cp android.jar -d out src/org/troop82/launcher/Announcements.java test/ParserTest.java
java  -cp "out;android.jar" org.troop82.launcher.ParserTest
```

## TV changes (summary)

- **35 apps disabled:** TCL junk, Google TV bloat and unused streaming apps.
- **Samba TV removed for user 0.** TCL kept re-enabling it.
- **Disabled:** the Google TV home screen and setup wizard. The Troop 82 launcher is the home screen.
- **Privacy:** AdGuard Private DNS. TCL's User Experience Program and diagnostics are off, and the advertising ID is deleted.
- **Play Protect:** off, at the owner's request.
- **Kept:** inputs/HDMI, tuner, updates, Wi-Fi, remote, Chromecast and voice.
