#!/bin/bash
# Renders the home screen's world on this machine (no TV, no emulator), for checking art changes.
#
#   bash tools/preview/preview.sh out.png [hour] [weather] [scale] [seconds]
#   e.g.  bash tools/preview/preview.sh /tmp/dusk.png 18.5          (x3, after 30 s of life)
#         bash tools/preview/preview.sh /tmp/storm.png 23 95 6
#         BAR=1 bash tools/preview/preview.sh /tmp/bar.png 13       (icon bar focused)
#   Any test switch works as -D: OPTS="-Ddebug.quiet.date=1108 -Ddebug.quiet.music=1" bash ...
#
# It compiles the engine (quiet-shared) against tiny stand-ins for the few Android classes it
# touches; Weather loses only its network fetch. Home's own text is imitated by Render.java.
set -eu
cd "$(dirname "$0")/../.."
out=${1:?usage: preview.sh out.png [hour] [weather] [scale] [seconds]}
hour=${2:-}; weather=${3:-}; scale=${4:-3}; secs=${5:-30}
B=$(mktemp -d)
trap 'rm -rf "$B"' EXIT
mkdir -p "$B/stub" "$B/cls" "$B/gen"
javac -nowarn -d "$B/stub" $(find tools/preview/stub -name '*.java') 2>&1 | grep -v JAVA_TOOL || true
# Weather without its network half (refresh/get), which needs org.json and a Context.
awk '/public static void refresh\(/ {exit} {print}' quiet-shared/src/com/quiet/px/Weather.java \
  | grep -v '^import' > "$B/gen/Weather.java"
echo '    public static void refresh(Object c) {} }' >> "$B/gen/Weather.java"
javac -nowarn -encoding UTF-8 -cp "$B/stub" -d "$B/cls" "$B/gen/Weather.java" tools/preview/Render.java \
  $(ls quiet-shared/src/com/quiet/px/*.java | grep -v -E '/(PxView|PxActivity|Ambience|Groove|Hold|World|Oops|Fine|Weather)\.java$') \
  2>&1 | grep -v JAVA_TOOL || true
java -Ddebug.quiet.hour="$hour" -Ddebug.quiet.weather="$weather" ${BAR:+-Dbar=true} ${OPTS:-} \
  -cp "$B/stub:$B/cls" com.quiet.px.Render "$out" "$secs" "$scale" 2>&1 | grep -v JAVA_TOOL || true
echo "$out"
