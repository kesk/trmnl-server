# SMHI weather-symbol SVGs

The original vector sources for the header weather icons: SMHI's "stroke/centered"
set, `{day,night}-N.svg` for symbol codes N = 1–27 (54 files).

Alongside each SVG is a `{day,night}-N.png` — a faithful **full-color** 72×72 raster of
the original (transparent background), kept purely as a browsable reference/preview of
what the source looks like. These are **not** used at runtime and are **not** the
dithered 1-bit icons the display uses (those live in `resources/icons/`); regenerate
them from the SVGs with `rsvg-convert -w 72 -h 72 <name>.svg -o <name>.png`. Use
`rsvg-convert` (librsvg), **not** `magick <name>.svg -resize 72x72` — the SVGs are `34pt`
intrinsic, so ImageMagick rasterizes them small and upscales, giving a blurry result;
librsvg rasterizes the vector directly at the target size.

These are the **source of truth** for `resources/icons/{day,night}-N.png`, which are
rasterized from them to 72×72 1-bit PNGs with textured (dithered) fills. This folder
lives outside `resources/` on purpose, so the SVGs are not bundled into the uberjar —
only the rendered PNGs are.

Originally fetched per icon from e.g.
`https://www.smhi.se/weather-page/weathersymbols/centered/stroke/day/1.svg`
(the `?proxy=wpt-a-<uuid>` query token is a required but transient cache key). Kept
here so the icon set can be regenerated offline — see the full `magick` recipe and the
`+antialias` / low-`-fuzz` / `PNG24:` gotchas in `CLAUDE.md` under "Design constraints".
