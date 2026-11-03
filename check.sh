#!/bin/bash
# Compile-checks every Quiet app against the Android 34 API: javac only, no signing, no device.
# Works anywhere with a JDK (the cloud's Java 21 included). Usage: bash check.sh [app ...]
set -u
cd "$(dirname "$0")"
JAR=${ANDROID_JAR:?set ANDROID_JAR to the SDK's platforms/android-34/android.jar}
[ -d /opt/homebrew/opt/openjdk@17 ] && export PATH=/opt/homebrew/opt/openjdk@17/bin:$PATH   # the Mac's JDK
apps=("$@")
[ ${#apps[@]} -eq 0 ] && apps=(quiet-launcher quiet-listen quiet-watch quiet-settings quiet-update quiet-drop)
out=$(mktemp -d)
fail=0
for app in "${apps[@]}"; do
  app=${app%/}
  if javac -source 11 -target 11 -nowarn -Xlint:-options -encoding UTF-8 -classpath "$JAR" -d "$out/$app" \
      $(find "$app/src" quiet-shared/src -name '*.java') 2> "$out/$app.log"; then
    echo "ok    $app"
  else
    echo "FAIL  $app"; head -40 "$out/$app.log"; fail=1
  fi
done
rm -rf "$out"
exit $fail
