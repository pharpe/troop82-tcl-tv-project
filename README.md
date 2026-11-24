# Troop 82 TCL TV Project

Setup, debloat and a custom home screen for Troop 82's TCL Google TV
(Realtek RTD6748, Android TV 12). Everything is done over ADB, with no root.

## What's here

| Path | What it is |
|---|---|
| `troop82-launcher/` | Custom home screen + screensaver app (plain Java, no Gradle) |
| `setup-tv.sh <ip>` | Applies the complete setup to another identical TV (used for the second TV) |
| `qa.sh [ip]` | Health check, about 50 checks per TV |
| `heal.sh` + `install-heal-task.ps1` | Daily Windows task: finds every TV in `tv-addresses.txt` (and rescans if an address changes) and re-applies the setup if a TCL update changed anything |
| `phase1-packages.txt` | Every app disabled during debloat |
| `undo-phase1.sh <ip>`, `undo-phase3.sh <ip>` | Re-enable debloated apps / restore the Google TV home screen on one TV (and pause self-heal for it) |
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
./build.sh install [ip]     # build and install now on registered TVs (or one TV)
./set-sheet.sh "<published CSV link>" [ip]   # change the announcements sheet (only adb can do this)
```

**Signing key:** `troop82.keystore` is deliberately not in git. Updates must be signed with the same key, so keep the copy you have backed up. If it's lost, the app has to be uninstalled and set up again.

### Tests

```sh
javac -cp android.jar -d out src/org/troop82/launcher/Announcements.java test/ParserTest.java
java  -cp "out;android.jar" org.troop82.launcher.ParserTest
```

### Releasing an update to the TVs

```sh
cd troop82-launcher
./build.sh release      # builds, runs the tests, and marks the build for rollout
```

Self-heal (daily task on this PC) installs a released build on every **registered** TV the next
time that TV is on the same network as this PC. A plain `./build.sh` never rolls out.

### Registered TVs and the skip list

- `setup-tv.sh <ip>` registers a TV by serial number in `tv-serials.txt` (local, not in git).
  Self-heal, `build.sh install` and `set-sheet.sh` never touch an unregistered TV, even if it
  answers at a known address.
- `heal-skip.txt` (address or serial, one per line) makes everything leave that TV alone.
  The undo scripts add the TV to it automatically.
- Self-heal pauses entirely while `heal.disabled` exists.
- **Give each TV a fixed address** (a DHCP reservation in the router). Self-heal can find a
  registered TV that moved (by its recorded network hardware address), but a TV that has never
  been registered from this PC can only be found at its known address.

## TV changes (summary)

- **35 apps disabled:** TCL junk, Google TV bloat and unused streaming apps.
- **Samba TV removed for user 0.** TCL kept re-enabling it.
- **Disabled:** the Google TV home screen and setup wizard. The Troop 82 launcher is the home screen.
- **Privacy:** AdGuard Private DNS. TCL's User Experience Program and diagnostics are off, and the advertising ID is deleted.
- **Play Protect:** off, at the owner's request.
- **Kept:** inputs/HDMI, tuner, updates, Wi-Fi, remote, Chromecast and voice.
