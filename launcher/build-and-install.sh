#!/usr/bin/env bash
# Build the DumbTV home screen and install it on the TV as the default home.
# The stock Google TV launcher is left alone here; tools/disable.sh takes care of it.
set -euo pipefail
cd "$(dirname "$0")"
export TV_HOST="${TV_HOST:-192.168.178.36}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:$PATH"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug -q --console=plain
PY=../tools/venv/bin/python
# Remote-button shortcuts: TCL refuses to bind services of apps without its AUTO_START app-op, so grant it,
# then add KeyService to the enabled accessibility services, keeping any others.
SVC=com.dumbtv.home/com.dumbtv.home.KeyService
ENABLE_KEYS="appops set com.dumbtv.home AUTO_START allow; cur=\$(settings get secure enabled_accessibility_services); case \"\$cur\" in *$SVC*) ;; ''|null) settings put secure enabled_accessibility_services $SVC ;; *) settings put secure enabled_accessibility_services \"\$cur:$SVC\" ;; esac; settings put secure accessibility_enabled 1; settings get secure enabled_accessibility_services"
APK=app/build/outputs/apk/debug/app-debug.apk
"$PY" -I ../tools/b64push.py "$APK" /data/local/tmp/dumbtv.apk
printf '%s\n' \
  "pm install -r /data/local/tmp/dumbtv.apk" \
  "rm -f /data/local/tmp/dumbtv.apk" \
  "cmd package set-home-activity --user 0 com.dumbtv.home/.MainActivity" \
  "$ENABLE_KEYS" \
  "echo installed" \
  | "$PY" -I ../tools/tv.py --batch
echo "Installed. Press Home on the remote to see it."
