# SMHI weather-symbol SVGs

The original vector sources for the header weather icons: SMHI's "stroke/centered"
set, `{day,night}-N.svg` for symbol codes N = 1–27 (54 files).

These are the **source of truth** for `resources/icons/{day,night}-N.png`, which are
rasterized from them to 72×72 1-bit PNGs with textured (dithered) fills. This folder
lives outside `resources/` on purpose, so the SVGs are not bundled into the uberjar —
only the rendered PNGs are.

Originally fetched per icon from e.g.
`https://www.smhi.se/weather-page/weathersymbols/centered/stroke/day/1.svg`
(the `?proxy=wpt-a-<uuid>` query token is a required but transient cache key). Kept
here so the icon set can be regenerated offline — see the full `magick` recipe and the
`+antialias` / low-`-fuzz` / `PNG24:` gotchas in `CLAUDE.md` under "Design constraints".
