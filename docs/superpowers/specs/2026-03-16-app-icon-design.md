# App Icon Design

## Overview

Launcher icon for the Satoshi Signer Android app. A minimal secure element chip with a checkmark on an amber-to-rose gradient, implemented as an Android adaptive icon.

## Visual Concept

The icon represents the app's core function: a hardware secure element (Trezor) approving a Bitcoin transaction. The chip glyph communicates "hardware" and "crypto," while the checkmark communicates "approved/signed."

### Color

- **Gradient:** Linear, Amber `#D97706` → Rose `#E11D48`, diagonal from `(0, 0)` to `(108, 108)` on the 108dp canvas
- **Glyph:** White (`#FFFFFF`), stroke-only lines

### Glyph: Minimal Chip (dimensions in dp, on 108×108dp canvas)

- **Chip body:** 44×44dp rounded rectangle, corner radius 8dp, centered at `(54, 54)`
- **Stroke weight:** 2.5dp for chip body, 2dp for traces and checkmark
- **Connector traces:** 3 per side (12 total), 10dp long, extending outward from the chip body
  - Left/right traces at y = 44, 54, 64 (10dp spacing)
  - Top/bottom traces at x = 44, 54, 64 (10dp spacing)
- **Checkmark:** inside chip body, from `(43, 54)` → `(50, 61)` → `(67, 44)`, stroke weight 3dp
- All strokes white, round line caps and joins

### Reference

The icon was iteratively designed during brainstorming. The final SVG mockup (circle-cropped preview) looks like this:

```
┌──────────────────────┐
│    ╷   ╷   ╷         │  ← 3 top traces
│  ──┌───────┐──       │
│  ──│  ✓    │──       │  ← chip body with checkmark, 3 side traces
│  ──│       │──       │
│  ──└───────┘──       │
│    ╵   ╵   ╵         │  ← 3 bottom traces
└──────────────────────┘
```

## Android Adaptive Icon Structure

Adaptive icons (API 26+) separate foreground and background layers. The launcher applies a device-specific mask (circle, squircle, rounded square, etc.) to both layers together.

### Canvas and Safe Zone

- Full canvas: **108×108dp** per layer
- Masked visible area: varies by launcher (typically ~72dp diameter circle or equivalent)
- Safe zone: **72×72dp** centered — all meaningful content must fit here
- Outer 18dp on each side may be clipped or used for parallax/motion effects

### Files to Create

| File | Purpose |
|------|---------|
| `app/src/main/res/drawable/ic_launcher_background.xml` | Vector drawable: amber→rose gradient filling 108×108dp |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | Vector drawable: white chip glyph centered in 108×108dp canvas (content within 72dp safe zone) |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | Adaptive icon referencing foreground + background |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml` | Same adaptive icon (round variant) |

### Legacy Fallback

The app targets min SDK 28 (API 26+), so all devices support adaptive icons. No legacy PNG fallback is needed.

### Monochrome Layer (Android 13+ Themed Icons)

Android 13+ (API 33) supports a `<monochrome>` layer for themed icons, where the launcher applies the user's wallpaper color to a single-color silhouette. Since `targetSdk = 35`, provide a monochrome variant — the same chip glyph as the foreground, used as a silhouette mask.

Add to both `ic_launcher.xml` and `ic_launcher_round.xml`:

```xml
<monochrome android:drawable="@drawable/ic_launcher_foreground" />
```

### Manifest Changes

Add to the `<application>` tag in `AndroidManifest.xml`:

```xml
android:icon="@mipmap/ic_launcher"
android:roundIcon="@mipmap/ic_launcher_round"
```

## Constraints

- All glyph detail must remain legible at 48×48dp (smallest launcher icon size)
- Stroke weights should be thick enough to survive downscaling — minimum 2dp effective
- The gradient must look good under all launcher masks (circle, squircle, rounded square, teardrop)
- Vector drawables only — no raster assets needed
- The `res/drawable/` and `res/mipmap-anydpi-v26/` directories do not currently exist and must be created
