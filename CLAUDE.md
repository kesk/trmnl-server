# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An exploratory Clojure project that generates a weather-forecast screen image for a TRMNL
e-ink display (the OG model: 800x480, 1-bit black/white). It fetches live forecast data
from SMHI (Sweden's meteorological institute) for Gothenburg and renders it with Java2D.

## Commands

```bash
# Run the generator — writes out/preview.png (RGB) and out/preview-1bit.png (thresholded)
clojure -M -m trmnl-server.main
# equivalently:
clojure -M:run

# Render synthetic per-season screens instead of a live fetch — writes
# out/demo-{winter,spring,summer,autumn}(.png|-1bit.png)
clojure -M -m trmnl-server.main --demo

# Serve the live forecast screen to a real TRMNL OG device over HTTP (see
# trmnl-server.server below). Listens on $PORT or 8080.
clojure -M -m trmnl-server.main --serve
# equivalently:
clojure -M:serve

# REPL for iterating on drawing/layout code
clojure -M -r

# Build a standalone uberjar (target/trmnl-server.jar) via tools.build (see build.clj)
clojure -T:build uber
# Run it directly, e.g.:
java -jar target/trmnl-server.jar --serve

# Build the uberjar, ship it to the Raspberry Pi running the live server
# (host "dashboard-pi", systemd unit trmnl-server.service, see deploy/trmnl-server.service),
# and restart it — a babashka script, not a JVM Clojure one (see deploy.clj)
bb deploy.clj
```

There is no test suite or linter configured. The only build step is the uberjar target in
`build.clj` (via `tools.build`), used solely to produce a self-contained jar for deployment.
Deployment itself (`deploy.clj`) is a babashka script that shells out to `clojure -T:build uber`
rather than requiring `build.clj` in-process, since `clojure.tools.build.api` is JVM-only and
unavailable under babashka —
this is otherwise a `deps.edn`-only exploratory project (no Leiningen).

## Architecture

Six namespaces, cleanly separated by concern:

- **`trmnl-server.image`** — generic Java2D drawing primitives, independent of any
  weather/domain concepts. A "canvas" is a plain map `{:image BufferedImage, :graphics
  Graphics2D}` threaded through every draw fn (`draw-text`, `draw-wrapped-text`,
  `draw-line`, `draw-dashed-line`, `draw-polyline`, `draw-dot`, `draw-rect`). Two
  conversions turn the RGB working canvas into what the e-ink panel actually needs:
  `->1-bit` (hard threshold — good for text/UI) and `floyd-steinberg` (error-diffusion
  dithering — good for photos/gradients). `save-image` infers the output format from
  the file extension.

- **`trmnl-server.smhi`** — HTTP client for SMHI's public point-forecast API, using
  `java.net.http.HttpClient` directly (no HTTP dependency needed). Fetches raw JSON,
  normalizes each `timeSeries` entry into a flat `{:time :temp :symbol :wind
  :precip-chance :precip-mm :cloud-cover}` map. Also owns the `symbol_code` → text mapping (1–27) and
  timezone-aware formatting helpers.

  **Important history**: SMHI deprecated the old `pmp3g` API on 2026-03-31 and
  replaced it with `snow1g` (same weather-symbol codes, different JSON shape — flat
  `data` map instead of a `parameters` array). If SMHI requests start 404ing, check
  for another API migration before assuming the code is broken.

- **`trmnl-server.demo`** — synthetic 48-point-per-season datasets (`seasons`,
  `season-points`) in the same point shape `smhi/forecast` produces, so `--demo` can
  drive `forecast-screen` without hitting the network. Values are simple diurnal sine
  curves around Gothenburg's seasonal norms, not real observations — good enough to
  look like a typical day, not a claim of historical accuracy.

- **`trmnl-server.core`** — composes the above into the actual screen
  (`forecast-screen`, arity-1 accepts any point seq matching smhi's shape, arity-0
  fetches live), and is where domain-specific layout/chart logic lives (e.g.
  `line-chart`/`combined-chart`, `nice-bounds` for rounding axis extents).

- **`trmnl-server.server`** — implements the small HTTP API a real TRMNL OG device
  polls when pointed at a custom server: `GET /api/display` (the main poll, returns
  JSON with an `image_url`/`filename`/`refresh_rate`), `GET /api/setup` (first-boot
  welcome screen), `POST /api/log` (device telemetry, replied to with `204`), and
  `GET /images/*` (serves the cached PNG bytes). Uses `http-kit` as both the Ring
  request/response convention and the embedded server (handlers are plain
  `(fn [request] response-map)` fns dispatched on `:request-method`/`:uri` in
  `handler`) — chosen over a Ring+Jetty stack for a single, self-contained
  dependency given there are only 3 routes. `current-image` renders via
  `core/forecast-screen` + `image/->1-bit`, encodes to PNG bytes in memory (no
  disk writes — `out/` stays reserved for the batch-render modes), and caches them
  for 10 minutes keyed by an MD5 content hash, so the `filename` embedded in
  `/api/display`'s response only changes when the rendered image actually changes —
  this lets the device skip re-downloading identical screens between polls.

- **`trmnl-server.main`** — the CLI entry point (`-main`). Kept separate from `core`
  purely so `core` and `server` can each require the other one-way without a cycle
  (`server` requires `core` for `forecast-screen`; `main` requires both). Renders
  the live screen by default, one screen per `demo/seasons` entry when invoked with
  `--demo` (writing both PNG variants of each to `out/`), or starts the HTTP server
  via `server/start!` when invoked with `--serve`.

### Design constraints worth knowing before extending

- **The final artifact is 1-bit monochrome.** There is no gray and no color to lean
  on for chart "recessiveness" or series identity — those are done here with texture
  instead: dashed vs. solid lines, dot size, hairline dashed gridlines vs. solid data
  lines. Keep that in mind before reaching for `Color` as a distinguishing channel;
  it will disappear (or invert unpredictably) after `->1-bit`/`floyd-steinberg`.
- **Two series with different units (°C vs m/s) are deliberately NOT on a shared
  numeric y-axis.** `combined-chart` scales each series independently to the same
  pixel box and leans on direct min/max labels (with units) to keep it honest. If
  adding a third series or a shared axis, preserve this — a dual-axis chart that
  implies comparability between unrelated units is worse than two separate charts.
- Hex color literals like `0xFF000000` overflow Java's signed `int` in Clojure (they
  read as a `Long`); use the signed equivalents (`-16777216` for opaque black, `-1`
  for opaque white) when working with packed ARGB ints via `.setRGB`.
