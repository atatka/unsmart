#!/usr/bin/env python3
"""Check that the TV is still a dumb TV, and repair what drifted (e.g. after a firmware update).

Usage: tools/check.sh [--dry-run]      (TV_HOST defaults to 192.168.178.36)

Checks: firmware, debloat list, DumbTV as Home, remote-button service, HDMI-CEC settings,
eARC pass-through, internet access. Repairs everything except the sound setting, which only the
TV's menu applies properly. Exit code 1 if anything still needs attention.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tv  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEBLOAT = os.path.join(ROOT, "debloat", "tcl-smart-tv-pro-g08.txt")
EXPECTED_FIRMWARE = "UTT2.250416.001"
PKG = "com.dumbtv.home"
HOME = PKG + "/.MainActivity"
KEYS = PKG + "/" + PKG + ".KeyService"
# CEC setting -> value we want (see README, "Sound and HDMI-CEC")
CEC = {"hdmi_cec_enabled": "1", "earc_enabled": "1", "tv_wake_on_one_touch_play": "1",
       "tv_send_standby_on_sleep": "1", "set_menu_language": "0"}

dry_run = "--dry-run" in sys.argv[1:]
problems = 0


def sh(cmd):
    return dev.shell(cmd, read_timeout_s=60.0).strip()


def ok(msg):
    print(f"  ok     {msg}")


def fixed(msg, *cmds):
    """Runs the repair commands (unless --dry-run) and reports it."""
    global problems
    if dry_run:
        problems += 1
        print(f"  DRIFT  {msg}  (would fix)")
        return
    for c in cmds:
        sh(c)
    print(f"  FIXED  {msg}")


def warn(msg):
    global problems
    problems += 1
    print(f"  WARN   {msg}")


def debloat_list():
    pkgs = []
    for line in open(DEBLOAT):
        line = line.split("#", 1)[0].strip()
        if line:
            pkgs.append(line)
    return pkgs


dev = tv.connect()

print("Firmware")
build = sh("getprop ro.build.display.id")
if EXPECTED_FIRMWARE in build:
    ok(build)
else:
    warn(f"firmware changed: {build} (was {EXPECTED_FIRMWARE}); check everything below and the picture/sound menus")

print("DumbTV")
installed = PKG in sh(f"pm list packages {PKG}")
if installed:
    ok("installed")
else:
    warn("DumbTV is not installed: run launcher/build-and-install.sh, then this check again")

print("Debloat list")
disabled = {l.replace("package:", "") for l in sh("pm list packages -d").splitlines()}
present = {l.replace("package:", "") for l in sh("pm list packages").splitlines()}
checked = 0
for p in debloat_list():
    if p not in present:
        continue  # removed by an update; nothing to disable
    checked += 1
    if p in disabled:
        continue
    if p == "com.google.android.apps.tv.launcherx" and not installed:
        warn(f"{p} is enabled, but stays on until DumbTV is installed")
        continue
    fixed(f"{p} was enabled again", f"pm disable-user --user 0 {p}")
ok(f"{checked} packages checked")

print("Home")
if installed:
    home = sh("cmd package resolve-activity --brief -a android.intent.action.MAIN "
              "-c android.intent.category.HOME").splitlines()[-1]
    if home == HOME:
        ok("Home opens DumbTV")
    else:
        fixed(f"Home opened {home}", f"cmd package set-home-activity --user 0 {HOME}")

print("Remote buttons")
if installed:
    if "AUTO_START: allow" in sh(f"appops get {PKG} AUTO_START"):
        ok("TCL auto-start allowed")
    else:
        fixed("TCL auto-start was blocked", f"appops set {PKG} AUTO_START allow")
    services = sh("settings get secure enabled_accessibility_services")
    if KEYS in services and sh("settings get secure accessibility_enabled") == "1":
        ok("key service enabled")
    else:
        rest = [s for s in services.split(":") if s and s != "null" and s != KEYS]
        fixed("key service was off",
              f"settings put secure enabled_accessibility_services {':'.join(rest + [KEYS])}",
              "settings put secure accessibility_enabled 1")
    if "label=DumbTV remote buttons" in sh("dumpsys accessibility | grep 'Bound services'"):
        ok("key service running")
    else:
        # Switching it off and on makes the system bind it again.
        rest = [s for s in sh("settings get secure enabled_accessibility_services").split(":")
                if s and s != "null" and s != KEYS]
        fixed("key service was not running",
              f"settings put secure enabled_accessibility_services '{':'.join(rest)}'",
              f"settings put secure enabled_accessibility_services {':'.join(rest + [KEYS])}")

print("HDMI-CEC")
for name, want in CEC.items():
    have = sh(f"cmd hdmi_control cec_setting get {name}").split("=")[-1].strip()
    if have == want:
        ok(f"{name} = {have}")
    else:
        fixed(f"{name} was {have}", f"cmd hdmi_control cec_setting set {name} {want}")

print("Sound")
if sh("settings get system tcl_sys_audio_spdif_outmode") == "4":
    ok("eARC Digital Audio Out = Pass Through")
else:
    warn("Digital Audio Out is not Pass Through: Settings > Display & Sound > Audio Output Settings")
earc = sh("dumpsys hdmi_control | grep -oE 'mEarcStatus: [0-9]'")
if earc.endswith("3"):
    ok("soundbar connected over eARC")
else:
    print(f"  info   soundbar not connected right now ({earc}); fine if it's asleep")

print("Internet")
if sh("timeout 4 toybox nc -w 3 1.1.1.1 443 </dev/null >/dev/null 2>&1 && echo open || echo blocked") == "blocked":
    ok("blocked (good: no ads, no data collection, no updates)")
else:
    warn("the TV can reach the internet; block it in the router (see README, Going further)")

dev.close()
print()
if problems:
    print(f"{problems} thing(s) need attention.")
    sys.exit(1)
print("All good.")
