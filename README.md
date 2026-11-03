# Quiet TV

A custom home for one Android TV, drawn entirely in pixel art: six small apps that replace the stock launcher
and bloat, all set in one living world, **the old road**: an underground road taken back by green, seen from its
bank, where a dog, a firefly, a frog and a few other creatures go about their days with the real time, weather and
moon.

| Folder | What it is |
|---|---|
| `quiet-launcher` | The home screen, the screensaver, and the voice: an assistant over every app (speech → OpenAI → one action, or an agent that looks things up first) |
| `quiet-listen` | Apple Music, through Apple's web player in a hidden WebView |
| `quiet-watch` | A front end for Stremio: Continue Watching, catalogs, search |
| `quiet-settings` | Sound, music party, motion, screensaver, sleep, network |
| `quiet-update` | Updates for sideloaded apps from their official feeds, signature-checked |
| `quiet-drop` | Send files and links to the TV from a phone; also the phone app (a remote and a mic) |
| `quiet-shared` | The engine and the world, compiled into every app |

Read `DESIGN.md` (the design system: a 320×180 canvas scaled ×6, the "Wellspring" palette, our own pixel fonts),
`WORLD.md` (the world) and `LIFE.md` (who lives there and how they get along).

## Building

No Gradle: each app's `build.sh` runs aapt2 → javac → d8 → zipalign → apksigner against the Android 34 SDK
(minSdk 28, targetSdk 30, Java 11). Set `KS_PASS` (and optionally `QUIET_KEYSTORE`); a keystore is made if missing.
All six apps must share one signing key (they share a signature permission). `bash check.sh` compile-checks them all
(`ANDROID_JAR` = the SDK's `platforms/android-34/android.jar`). `tools/preview/preview.sh out.png [hour] [weather]`
renders the home screen's world on any machine with a JDK.

## What this copy leaves out

This is a shared copy of a private project. It has no history, no keys, and nothing about the home it runs in. The
world keeps a few secrets for the people who live with it: in this copy those are placeholders (the old script's
sayings, its key, what opens the door, a few small surprises), so the code builds and the world runs, but the
mysteries stay where they belong: on the TV.
