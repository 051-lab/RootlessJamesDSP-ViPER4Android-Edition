#!/bin/bash
set +e
PKG=me.timschneeberger.rootlessjamesdsp.debug
adb install -r app/build/outputs/apk/rootlessFdroid/debug/*x86_64*.apk
adb shell am start -n $PKG/me.timschneeberger.rootlessjamesdsp.activity.MainActivity
sleep 15
printf '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map>\n<boolean name="bassex_enable" value="true" />\n</map>\n' > /tmp/bassex.xml
printf '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map>\n<boolean name="first_boot" value="false" />\n</map>\n' > /tmp/var.xml
adb shell "run-as $PKG mkdir -p shared_prefs"
adb shell "run-as $PKG sh -c 'cat > shared_prefs/dsp_bassex.xml'" < /tmp/bassex.xml
adb shell "run-as $PKG sh -c 'cat > shared_prefs/variable.xml'" < /tmp/var.xml
adb shell am force-stop $PKG
adb logcat -c
adb shell am start -n $PKG/me.timschneeberger.rootlessjamesdsp.activity.MainActivity
sleep 20
adb logcat -d > logcat-main.txt
adb logcat -d -b crash > logcat-crash.txt
echo "===== crash buffer tail ====="
tail -60 logcat-crash.txt
exit 0
