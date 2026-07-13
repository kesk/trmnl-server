# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An exploratory Clojure project that generates a weather-forecast screen image for a TRMNL
e-ink display (the OG model: 800x480, 1-bit black/white). It fetches live forecast data
from SMHI (Sweden's meteorological institute), defaulting to Gothenburg, and renders it
with Java2D.

## Commands

```bash
# Run the generator — writes out/preview.png (RGB) and out/preview-1bit.png (thresholded)
clojure -M -m trmnl-server.main
# equivalently:
clojure -M:run

# Render synthetic per-season screens instead of a live fetch — writes
# out/demo-{winter,spring,summer,autumn}(.png|-1bit.png), plus
# out/demo-rain-test(.png|-1bit.png), a chart stress-test day (see demo below)
clojure -M -m trmnl-server.main --demo

# Override how many hourly points are fetched/rendered (default 23) —
# applies to both the live fetch and --demo above.
clojure -M -m trmnl-server.main --hours 24

# Override where the live forecast is fetched for (default Gothenburg,
# 57.7089/11.9746). Has no effect on --demo, which always renders synthetic
# Gothenburg climate normals.
clojure -M -m trmnl-server.main --lat 59.3293 --lon 18.0686

# Serve the live forecast screen to a real TRMNL OG device over HTTP (see
# trmnl-server.server below). Listens on $PORT or 8080, renders $FORECAST_HOURS
# hourly points (default 23) for $FORECAST_LAT/$FORECAST_LON (default Gothenburg).
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

# Reformat source per .cljfmt.edn (dev.weavejester/cljfmt) — run on any
# Clojure files touched before committing
clojure -M:fmt
```

There is no test suite or linter configured, beyond `clojure -M:fmt` (cljfmt) for formatting.
The only build step is the uberjar target in `build.clj` (via `tools.build`), used solely to
produce a self-contained jar for deployment. Deployment itself (`deploy.clj`) is a babashka
script that shells out to `clojure -T:build uber` rather than requiring `build.clj` in-process,
since `clojure.tools.build.api` is JVM-only and unavailable under babashka —
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
  the file extension. `load-image`/`draw-image` composite a raster (e.g. PNG) resource
  onto the canvas — used for the header's weather icon (see core below).

- **`trmnl-server.smhi`** — HTTP client for SMHI's public point-forecast API, using
  `java.net.http.HttpClient` directly (no HTTP dependency needed). Fetches raw JSON,
  normalizes each `timeSeries` entry into a flat `{:time :temp :symbol :wind
  :precip-chance :precip-mm :cloud-cover}` map. Also owns the `symbol_code` → text mapping (1–27) and
  timezone-aware formatting helpers. `forecast` additionally carries the response's
  top-level `referenceTime` (the SMHI forecast run's issuance time) as `:reference-time`
  **metadata on the returned seq** — data the point maps don't need but a caller may want
  to tag a render with. Because plain seq ops (`take`) drop metadata, `core/live-points`
  re-attaches it when truncating; the server uses it only to stamp the archive filename.

  **Important history**: SMHI deprecated the old `pmp3g` API on 2026-03-31 and
  replaced it with `snow1g` (same weather-symbol codes, different JSON shape — flat
  `data` map instead of a `parameters` array). If SMHI requests start 404ing, check
  for another API migration before assuming the code is broken.

  Also owns `sun-times` (astronomical sunrise/sunset for a location + date via
  the NOAA sunrise equation — pure arithmetic, no network call or dependency,
  and it flags polar day/night at high latitudes) and `night?`, which uses it to
  decide whether a timestamp falls between sunset and sunrise. Both feed only the
  choice of day vs. night variant for the header's weather icon; `night?` takes
  the forecast `location` (`{:lat :lon}`), so callers thread that down (see
  `core/forecast-screen`'s location arg).

- **`trmnl-server.demo`** — synthetic per-season datasets (`seasons`, `season-points`,
  which takes an explicit `hours` count) in the same point shape `smhi/forecast`
  produces, so `--demo` can drive `forecast-screen` without hitting the network.
  Values are simple diurnal sine curves around Gothenburg's seasonal norms, not real
  observations — good enough to look like a typical day, not a claim of historical
  accuracy. Also `rain-test-points` (rendered by `--demo` to `out/demo-rain-test`):
  a deliberately unrealistic day that *decouples* rain probability from amount —
  likely-but-light, unlikely-but-heavy, likely-and-heavy, dry — to exercise every
  case the precip chart renders (notably the low-chance/high-mm line crossing tall
  bars), which the season datasets can't show since they tie chance and mm together.

- **`trmnl-server.core`** — composes the above into the actual screen
  (`forecast-screen`, arity-1 accepts any point seq matching smhi's shape, arity-2
  additionally takes the `{:lat :lon}` location that seq is for [used only to place
  the header icon's day/night variant via `smhi/night?`; arity-1 defaults it to
  Gothenburg, which is also what `--demo` renders], arity-0 fetches `live-points` of
  `default-forecast-hours` [23] points for `default-forecast-location` [Gothenburg]),
  and is where domain-specific
  layout/chart logic lives (e.g. `line-chart`/`combined-chart`, `nice-bounds` for
  rounding axis extents). `default-forecast-hours`/`default-forecast-location` are
  the single source of truth for "prognosis length" and "where" — callers override
  them via `--hours`/`--lat`/`--lon` (main) or `$FORECAST_HOURS`/`$FORECAST_LAT`/
  `$FORECAST_LON` (server) rather than hardcoding a point count or coordinates
  themselves.

- **`trmnl-server.server`** — implements the small HTTP API a real TRMNL OG device
  polls when pointed at a custom server: `GET /api/display` (the main poll, returns
  JSON with an `image_url`/`filename`/`refresh_rate`), `GET /api/setup` (first-boot
  welcome screen), `POST /api/log` (device telemetry, replied to with `204`), and
  `GET /images/*` (serves the cached PNG bytes). Plus two human-facing pages:
  `GET /status` (battery/firmware/deployed-commit/device-log dashboard) and `GET /archive` (a gallery
  of the rolling 24h image archive, see below), with `GET /archive/*` serving each
  archived PNG off disk. Uses `http-kit` as both the Ring
  request/response convention and the embedded server (handlers are plain
  `(fn [request] response-map)` fns dispatched on `:request-method`/`:uri` in
  `handler`) — chosen over a Ring+Jetty stack for a single, self-contained
  dependency given there are only 3 routes. The two human-facing HTML pages are
  built with **`hiccup`** (`hiccup2.core`, which auto-escapes string content, so
  there's no hand-rolled `escape-html`) via a shared `page` layout helper; their
  CSS lives in `resources/css/{base,archive}.css` (slurped at load through
  `io/resource`, so it resolves from the uberjar too) rather than inline string
  blobs — `base.css` is the shared shell, `archive.css` the gallery-only rules
  layered on top. The `/status` page also shows the **deployed commit** via
  `deployed-version`, read once at load from a bundled `version.edn` that
  `build.clj`'s uber task bakes in (`git rev-parse --short HEAD`, plus a `-dirty`
  suffix when the tree isn't clean, and a build timestamp). Running from source
  (`clojure -M:serve`, no build step) there's no such resource, so it falls back to
  shelling out to `git` against the working tree — nil renders as "unknown". `current-image` renders via
  `core/forecast-screen` (fed `core/live-points` of `$FORECAST_HOURS`/
  `$FORECAST_LAT`/`$FORECAST_LON`, or `core/default-forecast-hours`/
  `core/default-forecast-location` if unset) + `image/->1-bit`, encodes to PNG bytes in
  memory (the served bytes never touch disk — `out/` stays reserved for the
  batch-render modes) and
  caches them for 10 minutes keyed by an MD5 content hash, so the `filename`
  embedded in `/api/display`'s response only changes when the rendered image
  actually changes — this lets the device skip re-downloading identical screens
  between polls. Server-side diagnostics (startup banner, device-log receipts,
  stale-cache warnings) go through `clojure.tools.logging`, not `println` — see the
  logging note below.

  **Rolling image archive**: every *successful* render (i.e. each new cache entry,
  so ~one per 10-min cache miss, not the stale-fallback copies) is also written to
  disk by `archive-image!` as `forecast-<yyyyMMdd-HHmmss>-run<yyyyMMdd-HHmm>-<hash8>.png`
  under `archive/`
  (relative to the working dir, like `logs/`; override with `$ARCHIVE_DIR`), and
  files older than 24h are pruned by mtime on each write — so the folder self-manages
  a rolling 24h window with no cron. This exists so a problematic screen spotted after
  the fact can still be recovered and saved. `<hash8>` is the first 8 chars of an MD5
  over the *forecast data* (`pr-str` of the point seq) — deliberately **not** the
  rendered pixels, because the header's per-render "Uppdaterad HH:mm" stamp changes the
  pixels on every render and would defeat pixel-level dedupe. The write is **deduped**
  on that data hash: a render whose forecast matches the newest archived file is skipped,
  so the gallery stays a list of *distinct* screens rather than ~100 near-identical ones
  a day — SMHI only republishes the point forecast ~hourly. The `run<...>` segment is
  SMHI's `referenceTime` (issuance time of the forecast run, from the seq metadata above),
  rendered in local time purely as at-a-glance provenance — it labels which run each screen
  came from and plays **no** part in dedupe (that's the content hash's job; the trailing-hash
  match tolerates the segment, and legacy hash-less filenames simply don't match, so they
  never suppress a write). (A consequence:
  in the degenerate all-identical case the single archived file can outlive the 24h
  window, since pruning only runs when something new is written — which is the desired
  behaviour, keeping the last known screen rather than emptying the archive.) The write
  is best-effort (any IO error is logged and swallowed, never breaking the serving path)
  and runs under the same `regen-lock` as the render. Browse/download them via the
  `/archive` gallery (newest first); `archive-file-response` serves only flat
  `forecast-*.{png,edn}` basenames, so the route can't be walked out of the archive dir.
  Alongside each archived PNG, `archive-image!` also `spit`s a sibling `.edn` of the same
  basename — the `pr-str` of the point seq the screen was rendered from — so a screen
  spotted after the fact can be **re-rendered or inspected**, since the 1-bit pixels alone
  can't be reversed into the forecast data. The `.edn` is pruned on the same 24h mtime
  schedule as the PNG, and is downloadable from the gallery via a `data` link on each card
  (served as an `application/edn` attachment). The gallery itself lists only PNGs
  (`archive-entries` restricts to `forecast-*.png`) and shows the data link only when the
  sidecar exists. Note `last-archived-hash` (the dedupe probe) filters to `.png` before
  taking the newest by mtime, so the sidecar — written just after the PNG, hence newer —
  can't shadow the content hash and defeat dedupe.

- **`trmnl-server.main`** — the CLI entry point (`-main`). Kept separate from `core`
  purely so `core` and `server` can each require the other one-way without a cycle
  (`server` requires `core` for `forecast-screen`; `main` requires both). Renders
  the live screen by default, one screen per `demo/seasons` entry plus the
  `demo/rain-test-points` stress-test day when invoked with `--demo` (writing both
  PNG variants of each to `out/`), or starts the HTTP server
  via `server/start!` when invoked with `--serve`. An optional `--hours N` flag
  overrides `core/default-forecast-hours` for both the live and `--demo` paths.
  An optional `--lat LAT --lon LON` pair overrides `core/default-forecast-location`
  for the live path only (`--demo` always renders synthetic Gothenburg data
  regardless).

### Logging

Server-side logging uses `clojure.tools.logging` routed through SLF4J to **logback**
(`ch.qos.logback/logback-classic`) — one of the few places the project departs from its
otherwise dependency-light stance (the other being `hiccup` for the `/status` and
`/archive` HTML, see server above), because file logging on the Pi wanted a real
appender rather than hand-rolled `println` redirection. Config is
`resources/logback.xml` (bundled into the uberjar via `:paths`): a console appender
(so stdout/journald keep the old behaviour) plus two `RollingFileAppender`s. Each rolls
its file **daily** (`TimeBasedRollingPolicy`) to a gzipped, date-stamped archive
(e.g. `trmnl-server.log.2026-07-11.gz`), keeps `maxHistory` 30 (~a month) then deletes
the oldest, with a `totalSizeCap` as a backstop — so the logs self-manage on the Pi's
SD card without any external `logrotate`. The main log path defaults to
`logs/trmnl-server.log` relative to the process's working directory (the systemd unit's
`WorkingDirectory`, so `/home/seb/trmnl-server/logs/…` in prod); override it with the
`LOG_FILE` env var. logback creates the parent dir if missing.

Device telemetry (`POST /api/log`) is deliberately **split into its own file** so the
high-volume, JSON-blob device rows don't drown out the server's own diagnostics: it
logs to the dedicated `trmnl-server.device` logger (see `server/device-logger` and
`log/log` — tools.logging's explicit-logger-name arity, not the namespace default),
which logback routes to `logs/device.log` (override with `DEVICE_LOG_FILE`) with
`additivity="false"` so it stays out of the main log. It's still echoed to the console
so `journalctl`/live tailing shows everything in one stream. The in-memory
`device-logs` atom that backs the `/status` page is **seeded from the tail of the
active `device.log` at startup** (`seed-device-logs!`, which reads the appender's path
straight from logback rather than duplicating it), so `/status` survives a
restart/redeploy instead of starting blank. The CLI
batch-render feedback in `main` (`"Wrote out/…"`, `"Rendering …"`) is deliberately
still `println` — that's interactive terminal output for a human running the command,
not server diagnostics.

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
- **`resources/icons/{day,night}-N.png`** (N = SMHI symbol code 1–27) are official SMHI
  weather-symbol SVGs, pre-rasterized to 56x56 PNGs (flattened onto white, 8-bit) —
  see `core/draw-weather-icon`, which picks the variant via `smhi/night?` and draws it
  in the header. Their colored fills (sun yellow, cloud grays) land above `->1-bit`'s
  128 threshold and wash to white, leaving just the dark outline strokes — this is by
  design, not an accident, so don't "fix" it by recoloring the source PNGs. Regenerate
  from the original SVGs (source archive was `symbols.tgz`) with e.g. `magick day-N.svg
  -background white -flatten -resize 56x56 -depth 8 -define png:color-type=2
  day-N.png` if a different size or the night set needs redoing.
