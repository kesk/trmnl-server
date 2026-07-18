# SMHI weather-symbol SVGs

The original vector sources for the header weather icons: SMHI's "stroke/centered"
set, `{day,night}-N.svg` for symbol codes N = 1–27 (54 files).

These are the **source of truth** for `resources/icons/{day,night}-N.png`, the 72×72
1-bit icons the display uses (textured/dithered fills). This folder lives outside
`resources/` on purpose, so the SVGs are not bundled into the uberjar — only the
rendered PNGs are.

Regenerate the runtime icons from these SVGs with the two-layer `rsvg-convert` +
ImageMagick recipe documented in `CLAUDE.md` under "Design constraints". Use
`rsvg-convert` (librsvg) for rasterization, **not** `magick <name>.svg -resize 72x72` —
the SVGs are `34pt` intrinsic, so ImageMagick rasterizes them small and upscales,
giving lumpy/blurry shapes; librsvg rasterizes the vector directly at the target size.

Originally fetched per icon from e.g.
`https://www.smhi.se/weather-page/weathersymbols/centered/stroke/day/1.svg`
(the `?proxy=wpt-a-<uuid>` query token is a required but transient cache key). Kept
here so the icon set can be regenerated offline, no network needed.
