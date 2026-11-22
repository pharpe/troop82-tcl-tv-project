# TCL TV: findings and plan

Nothing has been changed on the TV yet. Its original app list and home-screen setting are saved in `C:\tcl\backup\`.

## What this TV is
- TCL Google TV, Android 12, security patch from September 2025.
- Realtek RTD6748 chip, 1.8 GB of RAM, 8 GB of storage (4.6 GB free).
- Only about 700 MB of RAM is free, so trimming background apps will make a noticeable difference.
- The bootloader is locked and verified boot passes.

## Can it be rooted? No.
- I found no root method, exploit or bootloader unlock for this Realtek platform.
- The only TCL root that exists is for an older Android 9 Realtek board.
- The common MediaTek root tools don't apply to this chip.
- Android 12 with a 2025 patch has no known public root exploit.
- Trying anyway risks bricking the TV and losing Netflix HD/4K.

Without root, ADB can still disable any app for your user account, so almost everything else is possible.

## What it's running in the background
- **Samba TV** (`tv.samba.ssm.ui`): watches what's on screen (ACR) and sells viewing data. **Disable.**
- **TCL analytics and usage tracking** (`com.tcl.ttvs`, which runs `BIService` and `UsagestService`): **can't be disabled**, because the same package runs system updates. Block its tracking servers by network (DNS) instead.
- **TCL log uploader** (`com.tcl.logkit`): disable.
- **Google TV home-screen ads and recommendation rows**: replace the home screen.

## Phase 1: Remove junk and spyware (low risk)
Every step can be undone with one command. Nothing gets deleted from the system partition.

Disable these:
- Samba TV ACR and the TCL log uploader.
- Store demo mode (`exhibit`), store sticker overlay (`esticker`), hotel mode menu, repair guide, e-manual.
- Alexa integration, TCL partner app pusher (`partnercustomizer`), TCL promo messages (`messagebox`).
- TCL Live / TCLtv+ free channels (`channelplus`, `tv.plus`), TCL dashboard, game bar.
- Google feedback, and Play Games if you don't use it.

**Never touched.** These are reported to break things when disabled:
- Inputs/HDMI and tuner: `suspension`, `tv`, `tvinput`.
- Updates: `ttvs`.
- Wi-Fi: `providers.config`, `initsetup`.
- Remote pairing: `autopair`.
- Remote firmware: `UpdatePeripheral`.
- Core TCL system service: `systemserver`.
- The Realtek system services, Google Play Services, and location (reported to cause boot loops).

## Phase 2: Privacy and ads
- Turn on network-level ad and tracker blocking through the TV's built-in Private DNS (AdGuard DNS). This blocks TCL and Google telemetry and in-app ads. It doesn't block YouTube ads.
- On the TV itself, delete the advertising ID.
- Opt out of TCL's "User Experience Improvement Program" and viewing-information agreements.
- Use **SmartTube** for YouTube. You already have it installed, so the stock YouTube app could go.

## Phase 3: A minimal home screen
- Install **Projectivy Launcher** (feature-rich) or **FLauncher** (open source, very plain).
- Then disable the Google TV home screen. That removes the ads and recommendation rows and frees around 100 MB of RAM.
- Caveats:
  - You lose Google TV's "For You" tab and profiles.
  - A TCL firmware update can turn Google TV back on, and the script would need to be re-run.
  - If something goes wrong, the fix still works over ADB, so keep network debugging on.
- Lighter alternative: keep Google TV and turn on "Apps only" mode. This removes most recommendations but also turns off Google Assistant.

## Phase 4: Speed
- Set animations to 0.5x for snappier menus.
- Disable preinstalled apps you don't use, so they stop running background services.

## Ideas for doing more with it
1. **Mirror and control the TV from your PC** with scrcpy: see the TV screen in a window and type with your keyboard.
2. **One-click scripts in `C:\tcl`:**
   - "Movie mode": sets the picture mode, launches Plex, turns on Do Not Disturb.
   - "Sleep in 30 min".
   - "Kids mode": blocks certain apps after 8pm.
3. **Remap the remote's app buttons** (Netflix, Prime and so on) to apps you actually use, such as Plex, Hulu or SmartTube, using Button Mapper.
4. **Home Assistant integration**, if you run it: pause on doorbell, dim lights when a movie starts, turn the TV on with the PlayStation over HDMI-CEC. CEC auto-wake is currently off and can be turned on.
5. **Ambient display:** when idle, show a photo-frame screensaver from Google Photos or Plex, a weather/calendar dashboard, or a Home Assistant panel.
6. **Remote access from anywhere** with Tailscale: control or fix the TV from your phone away from home.
7. **Self-hosted media:** add Jellyfin or Kodi next to Plex, and use TiviMate or the TV Player app you already have for IPTV.
8. **A health-check script** that re-checks the debloat after each TCL firmware update and re-applies it if the update re-enabled anything.
9. **A "what is my TV doing" monitor** that logs which apps phone home and how often, so you can see the tracking reduction.

## Questions before starting
1. **Which of these preinstalled apps do you use?** Netflix, Prime Video, YouTube (stock), YouTube Music, Google TV (Play Movies), Play Games, Instagram, NCAA, YouTube TV. Anything you don't use gets disabled.
2. **Go ahead with Phases 1, 2 and 4 now?**
3. **For Phase 3: Projectivy, FLauncher, or keep Google TV with "Apps only" mode?**
4. **Which ideas interest you?**

## Sources
- TCL debloat do/don't list: https://tv.cobanov.dev/en/
- TCL debloat script (archived): https://github.com/chrissy-dev/debloat-tlc-android-tv
- Debloat on a TCL 98C7K, with benchmarks: https://hwbusters.com/software/ai-tv-debloating-tcl-98c7k-benchmarks/5/
- TCL root status, Telegram group guide: https://faserf.github.io/TCL-Discussion-Telegram/rooting
- Launcher replacement guide: https://android.gadgethacks.com/how-to/best-google-tv-launcher-replacement-projectivy-setup-guide/
- Google TV update blocking Projectivy's home-button override: https://www.androidauthority.com/google-tv-update-blocks-projectivy-override-3649445
