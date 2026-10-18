# dumbtv

Turns the TCL Smart TV Pro (Google TV, Android 14) into a dumb monitor for HDMI boxes. Built on the
ideas and tools in [`.ref/`](.ref/) ([tv-vibehack](https://github.com/Gathin23/tv-vibehack)).

- **Power on** → straight to the last used HDMI input. No Google TV home, no ads, no recommendations.
- **Home** → a black screen with one tile per HDMI port (logo, name via HDMI-CEC, live on/standby state)
  and a Settings tile showing the soundbar link. **Back** returns to the input you were on, **1–4** jump
  to that HDMI port.
- **Empty ports are hidden**: a port shows up only when something is plugged in (a CEC device or a
  picture), so HDMI 4 appears by itself once the Switch 2 is connected (`hide_empty_ports`).
- **Remote app buttons** are input shortcuts (see below), from anywhere, also while a box is playing.
- **HDMI-CEC** still works: turning on the PS5 or the Android box switches the TV to it, and the sound
  system on eARC keeps working.
- Streaming apps, TCL Channel, ads, telemetry, AirPlay/casting and the Google TV launcher are
  **disabled, never uninstalled**. One command undoes everything.

| Port | Device |
|---|---|
| HDMI 1 (eARC) | Sound system (Poseidon M60B), hidden from the picker |
| HDMI 2 | PlayStation 5 |
| HDMI 3 | Android TV box, to be replaced by an Apple TV (the tile picks up the name and logo by itself) |
| HDMI 4 | Nintendo Switch 2 (named "Switch 2" in `config.xml` until it's connected) |

## Layout

| Path | What it is |
|---|---|
| [`tv`](tv) | `./tv '<shell cmd>'`, `./tv --batch < file`, `./tv --probe`. ADB to the TV (defaults to 192.168.178.36). |
| [`ui`](ui) | `./ui` prints the text on screen (marks focus with `>`). Works over HDMI video, unlike screenshots. |
| [`shot`](shot) | `./shot name` saves a TV screenshot to `shots/name.png`. HDMI pictures come out empty (Android blocks capture of HDMI video). |
| [`launcher/`](launcher/) | DumbTV (`com.dumbtv.home`), plain Java, no dependencies: the picker (`MainActivity`), HDMI port logic (`Inputs`) and the remote-button service (`KeyService`). |
| [`debloat/tcl-smart-tv-pro-g08.txt`](debloat/tcl-smart-tv-pro-g08.txt) | What is disabled and why. |
| [`tools/disable.sh`](tools/disable.sh), [`tools/restore.sh`](tools/restore.sh) | Apply or undo a debloat list. |
| `backup/` | Package lists, `getprop` and all settings from before any change. |
| `.ref/` | Upstream tv-vibehack clone, kept read-only as reference (its own git repo). |

## Common tasks

### Push a new version of DumbTV after changing `config.xml`

1. Edit [`launcher/app/src/main/res/values/config.xml`](launcher/app/src/main/res/values/config.xml):
   tile names (`input_names`), logos (`input_icons`), hidden ports (`hidden_inputs`), remote buttons
   (`button_inputs`), `hide_empty_ports`, `resume_last_input`, `show_clock`.
2. Make sure the TV is on (standby is fine as long as it's on the network).
3. From the project folder run:

```bash
launcher/build-and-install.sh
```

It builds the app, uploads it, installs it over the old one (keeping its settings), sets it as Home and
re-enables the remote-button service. It doesn't interrupt what's on screen; press Home on the remote to
see the new picker. Takes about 30 seconds. For a TV at another address:
`TV_HOST=192.168.178.50 launcher/build-and-install.sh`.

Re-enable a single app, e.g. YouTube:

```bash
./tv 'pm enable com.google.android.youtube.tv'
```

Undo everything and get the stock Google TV home back:

```bash
tools/restore.sh debloat/tcl-smart-tv-pro-g08.txt
```

Needs: the venv in `tools/venv` (mise Python 3.12, see below), and for building the launcher JDK 17 plus
Android SDK platform 34 / build-tools 34.0.0 (`brew install openjdk@17 android-commandlinetools`,
then `sdkmanager "platforms;android-34" "build-tools;34.0.0"`).

## Remote buttons

| Button | Goes to |
|---|---|
| Netflix, Disney+ | HDMI 3 (Android box, later Apple TV) |
| YouTube | HDMI 2 (PS5) |
| Prime Video | HDMI 4 (Switch 2; does nothing while the port is empty) |
| Blue app button | the DumbTV picker |
| TCL | TV Settings |
| 1–4 (in the picker) | HDMI 1–4 |

Change it in `button_inputs` in [`config.xml`](launcher/app/src/main/res/values/config.xml) (key code =
`HDMI n`, `picker` or `settings`) and run `launcher/build-and-install.sh`. The key codes of this remote
are listed there too. It works through an accessibility service ("DumbTV remote buttons", Settings →
Accessibility), which the install script grants and enables.

The logos come from [Simple Icons](https://simpleicons.org) (PlayStation, Apple TV, Android 16.35.0;
Nintendo Switch from 13.21.0, it was removed later) and Material Symbols (gear, HDMI). They are
trademarks of their owners, used here only to label the ports on this TV.

## Gotchas we hit on this TV

- **macOS Local Network privacy** blocks the Xcode Python (`/usr/bin/python3`) with "No route to host"
  even though `nc` reaches port 5555. The venv uses mise's Python 3.12, which is allowed:
  `~/.local/share/mise/installs/python/3.12.14/bin/python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt`.
- **Use port 5555, not the "wireless debugging" port.** With USB debugging on, this TCL also listens
  on classic ADB 5555 (`service.adb.tcp.port`), which survives reboots. The wireless-debugging port
  (e.g. 32819) needs TLS pairing and switches itself off when the network drops.
- **Google TV ignores the Home role.** `cmd package set-home-activity` and `cmd role add-role-holder`
  both "succeed", but `launcherx` keeps Home. Only disabling it works; after that Google's setup app
  (`setupwraith`, `RecoveryActivity`, priority 1) would win, so it is disabled too.
- **Disabling `setupwraith` deletes the Wi-Fi network it saved during setup.** The TV drops off the
  network immediately. Re-join Wi-Fi once in Settings → Network; the new entry belongs to the user and
  stays. (Already done here on 2026-10-18.)
- **No internet time.** The TV sees the network as a captive portal, so its clock is wrong (it thinks
  it's May). The picker hides the clock for that reason (`show_clock` in `config.xml`).
- **TCL blocks background services** of sideloaded apps ("TclAppBoot: forbid bind service ...
  callee doesn't have OP_AUTO_START"). The accessibility service only binds after
  `appops set com.dumbtv.home AUTO_START allow`; the install script does it.
- **Keys injected over adb skip accessibility** on this firmware, even with `input keyboard keyevent`,
  so `KeyService` can only be tested with the real remote. The picker's own keys (1–4, Back) work with
  injection.
- **Keep:** `com.tcl.tvinput`, `com.tcl.tv` (plays the HDMI inputs), `com.tcl.suspension` (Quick
  Panel on the remote's settings key), `com.tcl.hearaid` (sound settings), `com.tcl.gamebar`.

## Sound and HDMI-CEC

Soundbar: **Ultimea Poseidon M60** (5.1 with wired sub, Dolby Atmos, no DTS) on HDMI 1 over eARC.
eARC is connected (`mEarcStatus: 3`) and the soundbar reports Atmos via DD+ (JOC) and TrueHD/MAT, plus
up to 8-channel PCM.

Changed on 2026-10-18 (values before the change are in `backup/av-*/`):

| Setting | Was | Now | Where |
|---|---|---|---|
| Digital Audio Out (eARC) | Auto | **Pass Through** | Settings → Display & Sound → Audio Output Settings |
| Power on the TV in sync | Off | **On**: turning on the PS5/box/Apple TV wakes the TV | Settings → Channels & Inputs → External Inputs → HDMI (CEC) |
| CEC "set menu language" | On | **Off**: the TV no longer forces its English menu language onto the boxes | `./tv 'cmd hdmi_control cec_setting set set_menu_language 0'` |

Verified afterwards: the Android box's audio leaves the TV as a direct `E_AC3_JOC` (Atmos) stream to
`HDMI_EARC`, and the PS5 as direct 8-channel PCM. The TV doesn't re-encode either one.

Left as they were, on purpose: HDMI Control on, eARC on, Device standby in sync on (TV off → boxes and
soundbar off), TV goes to standby when the playing device turns off, TV remote volume controls the
soundbar, HDMI Mode Auto (gives the PS5 HDMI 2.1), Auto Switch off, QMS off.

Undo: set Digital Audio Out back to Auto and Power on the TV in sync back to Off in those screens, and
`./tv 'cmd hdmi_control cec_setting set set_menu_language 1'`.

Helpers: `./ui` prints the on-screen text (screenshots come out empty over HDMI video), and
`./tv 'cmd hdmi_control cec_setting get <name>'` reads any CEC setting.

On the boxes (they decide what gets sent):
- **PS5:** Settings → Sound → Audio Output → Output Device "HDMI Device (TV)", Audio Format (Priority)
  "Linear PCM". The soundbar is 5.1, so 5.1 channels is enough.
- **Android TV box:** Surround sound "Auto", or "Manual" with Dolby Digital Plus / Atmos enabled.
- **Apple TV (later):** Video and Audio → Audio Format → Dolby Atmos on; Remotes and Devices → Control
  TVs and Receivers on, so it wakes the TV and the soundbar volume works from the Siri remote.

## Picture

Picture settings on this TV are stored per input and per signal type (SDR, HDR10, Dolby Vision).

- **PS5 (HDMI 2):** the PS5 sends ALLM (auto low latency), and the TV switches to **Game** picture mode by
  itself (checked on 2026-10-18: `ALLM: 1`, Picture Mode "Game"). Nothing to set.
- **Android box / Apple TV (HDMI 3):** Dolby Vision stays on **Dolby Vision IQ** (accurate, adapts to room
  light). **Motion Clarity set from Medium to Off**, which removes the "soap opera" smoothing. For SDR/HDR10
  content pick FILMMAKER MODE once while such content plays (Settings → Display & Sound → Picture →
  Picture Mode); the TV only offers the modes of the signal currently showing.
- **Switch 2 (HDMI 4):** once it's connected, check Picture Mode while it shows a game and pick **Game**
  if the TV didn't switch by itself.

Privacy, checked in Settings → Privacy → Terms & Privacy: TCL's "User Experience Improvement Program"
and "Share Diagnostics Data" (which Automatic Content Recognition depends on) are **not selected**. Leave
them that way, and don't press "Agree" on that screen.

## Going further

The TV no longer needs the internet at all. In the FRITZ!Box (Internet → Filters → Parental Controls),
give `Smart-TV-Pro` a profile that blocks internet access: it stays reachable on the LAN for `./tv`,
but can't phone home, show ads or pull firmware updates. Lift the block when you want an update.
