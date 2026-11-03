#!/bin/zsh
# Builds this Quiet app without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
set -e
cd "${0:a:h}"
SDK=${ANDROID_SDK:-/opt/homebrew/share/android-commandlinetools}
BT=${ANDROID_BT:-$SDK/build-tools/34.0.0}
JAR=${ANDROID_JAR:-$SDK/platforms/android-34/android.jar}
[ -d /opt/homebrew/opt/openjdk@17 ] && export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && export PATH=$JAVA_HOME/bin:$PATH
# The signing key never lives in git (QUIET_KEYSTORE; one is made if missing, with KS_PASS).
KS=${QUIET_KEYSTORE:-quiet.keystore}
: ${KS_PASS:?set KS_PASS to your keystore password}
rm -rf build && mkdir -p build/gen build/classes build/dex
$BT/aapt2 compile --dir res -o build/res.zip
$BT/aapt2 link build/res.zip -I $JAR --manifest AndroidManifest.xml --java build/gen -o build/unsigned.apk
javac -source 11 -target 11 -nowarn -classpath $JAR -d build/classes $(find src build/gen ../quiet-shared/src -name '*.java')
$BT/d8 --release --min-api 28 --lib $JAR --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q ../unsigned.apk classes.dex)
$BT/zipalign -f 4 build/unsigned.apk build/aligned.apk
[ -f $KS ] || keytool -genkeypair -keystore $KS -storepass "$KS_PASS" -keypass "$KS_PASS" \
  -alias quiet -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Quiet" >/dev/null 2>&1
$BT/apksigner sign --ks $KS --ks-pass env:KS_PASS --out app.apk build/aligned.apk
ls -la app.apk
