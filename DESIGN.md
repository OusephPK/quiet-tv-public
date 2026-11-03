# Quiet — the TV's design system

Every screen on this TV that we build follows these rules. The shared code lives in
`quiet-shared/src/com/quiet/px/`; nothing here is a guideline that only exists on paper.

## The canvas

- **320 × 180 art pixels**, scaled ×6 to 1920 × 1080 with no smoothing. One art pixel is always
  exactly 6 screen pixels. Never draw at a fractional or mixed scale.
- Every screen is set **in the well** (`Stage`): near wall with an arch, the tunnel beyond, a
  pool along the bottom, the sky through the opening above. UI sits on top of the world; the
  world is never replaced by a flat background.
- The world follows the **real time of day**: indigo night with stars and the true moon phase,
  pink-gold dawn, blue day, orange-violet dusk. Light on the stone follows the sky.

## Palette — "Wellspring" (`Pal`)

| Role | Token | Use |
|---|---|---|
| Ink | `VOID` #06060C | Shadows, outlines, text shadow |
| Paper | `TEXT` #F4EEDC | Selected text, titles |
| Soft | `SOFT` #9298B0 | List items at rest |
| Dim | `DIM` #6A7090 | Secondary text, labels at rest |
| Faint | `FAINT` #363B52 | Hints, dividers, disabled |
| Signal | `AMBER` #FFB44A | Focus frames, active states, the one accent |
| Glow | `MINT` #4EF0C8 | Living things, "playing", runes |
| Alert | `PINK` #FF5C8A | Errors only |

Stone is never painted a colour: it has reflectance, and light picks a step from the `STONE`,
`WARM`, `COOL` or `FAR` ramps with Bayer dithering. Never use gradients or alpha on stone.

## Type

| Voice | Font | Size | Use |
|---|---|---|---|
| Display | Wick (7 px) ×4 | 28 px | The clock |
| Title | Wick ×2–3 | 14–21 px | Screen titles ("DROP", "UPDATES", "MUSIC") |
| Content | Wick ×1 | 7 px | Things you choose: apps, songs, files |
| Label | Ember (5 px), tracking 2 | 5 px | Section names, dates, button text, icon labels |
| Note | Ember, tracking 1 | 5 px | Durations, sizes, versions, hints |

All caps, always. Every word gets a 1-pixel `VOID` drop shadow (`Stage.text` / `Stage.small`).
Curly quotes and accents fold to the plain glyphs the fonts have.

## Hierarchy — content first

1. **Content** (apps, songs, stations, files) is the main list or grid, in the Content voice.
   Nothing that isn't content goes in it.
2. **System controls** (inputs, settings, sound, sign-in, refresh, store, now playing) live in the
   **icon bar**, top-right, in every app. The bar appears only on screens that have such
   controls (Drop has none, so it has no bar).
3. **Navigation** between sections (Music's rail) sits on the left in the Label voice.

## Components

- **List row** (`Menu`): Content voice, `SOFT` at rest, `TEXT` selected. Mote hovers to its left.
  Notes trail in the Note voice (`AMBER` when they matter: an update, a move in progress).
- **Icon bar** (`IconBar`): 13 × 13 cells, 4 px apart, right edge at x = 308, top at y = 10.
  Press **UP** from the top of the list to enter it, LEFT/RIGHT to move, DOWN or BACK to leave.
  The label shows only under the selected icon. An amber dot in the corner means "on" or
  "happening" (music playing, sound on, a mode active).
- **Icons**: 9 × 9, one family: 1-pixel strokes, square corners, no fills, drawn in `SOFT`
  at rest, `TEXT` when selected, always with a shadow. Current set: inputs, apps, settings,
  sound on/off, playing, refresh, store, search, user.
- **Button** (Play, Shuffle): Label voice in a 1-pixel frame; selected = filled `AMBER` with
  `VOID` text. Buttons are verbs; list rows are nouns.
- **Tile** (covers): 40 × 40 art, pixelated to 8 levels per channel with gentle Bayer dither,
  1-pixel frame (`COOL[2]`, `AMBER` when selected, lifted 2 px). Title and subtitle beneath in
  the Note voice over a dithered shadow.
- **Panel backing** (`Stage.scrim`): a checkerboard of `VOID` behind text that crosses the
  busy tunnel. Never a solid box.
- **Carved runes**: the selected item's name carved into the tunnel wall, glowing mint with a
  dark outline. Decorative echo only; never the only place information appears.

## Focus and motion

- There is always exactly one focus, and **Mote** (the firefly) is always next to it: left of a
  list row, above a tile, under an icon, over a keyboard key.
- Selection changes are instant; Mote glides (critically damped spring, ~0.2 s).
- OK gives a small flash (Mote's spark ring) and a note; holding OK means "rearrange" or "pin".
- BACK always goes one level out; at the top level it leaves the app (music keeps playing).

## Sound

- Every move through a list plays a note on the D-major pentatonic; choosing plays a rising pair.
- The home screen has a quiet generative soundtrack, which stops the instant an app or real
  music plays. The world's creatures make their own small sounds.

## Performance budget

- ~20 fps while you press buttons, ~12 fps while you watch, 0 fps when an app is in front.
- Static light is baked; only moving lights are recalculated each frame.
- No images are stored: every creature, plant and icon is drawn from code.
