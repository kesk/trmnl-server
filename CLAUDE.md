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

# Assert the temp/wind chart never draws a label onto a dot or another label,
# across the demo seasons, dev/fixtures/, any local archive/ forecasts, and N
# generated days (default 3000). Exits non-zero with a reproducer on failure.
clojure -M:check-labels
clojure -M:check-labels 500
```

There is no test suite or linter configured, beyond `clojure -M:fmt` (cljfmt) for formatting
and `clojure -M:check-labels` (see `dev/`) for the one rendering property that can be checked
without eyes.
The only build step is the uberjar target in `build.clj` (via `tools.build`), used solely to
produce a self-contained jar for deployment. Deployment itself (`deploy.clj`) is a babashka
script that shells out to `clojure -T:build uber` rather than requiring `build.clj` in-process,
since `clojure.tools.build.api` is JVM-only and unavailable under babashka —
this is otherwise a `deps.edn`-only exploratory project (no Leiningen).

## Architecture

Eleven namespaces, cleanly separated by concern. Six are the domain (`image`, `smhi`,
`demo`, `labels`, `core`, `main`); `server` and the four `server.*` ones under it are
the serving path, which only `--serve` exercises.

- **`trmnl-server.image`** — generic Java2D drawing primitives, independent of any
  weather/domain concepts. A "canvas" is a plain map `{:image BufferedImage, :graphics
  Graphics2D}` threaded through every draw fn (`draw-text`, `draw-wrapped-text`,
  `draw-line`, `draw-dashed-line`, `draw-polyline`, `draw-dot`, `draw-rect`). Also owns
  `pixel-font`, which derives the bundled PixelOperator bitmap font at a given size —
  every namespace that draws text goes through it, rather than loading fonts itself. Two
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

- **`trmnl-server.labels`** — direct labelling of a plotted series, as pure geometry:
  which turning points are worth a label (`peaks`, ranked by topographic prominence,
  and `pick-extras`) and where each label's box can go without landing on anything
  already drawn (`place`, plus `line-profile` and `draw-placed`). Nothing here knows
  about temperature, wind or forecasts — the caller hands it dots, texts and the rects
  to stay off. Split out of `core` because it's the subtlest code in the project and the
  only part with a mechanical test (`clojure -M:check-labels`); see the label-placement
  entry under design constraints below before touching it.

- **`trmnl-server.core`** — composes the above into the actual screen
  (`forecast-screen`, arity-1 accepts any point seq matching smhi's shape, arity-2
  additionally takes the `{:lat :lon}` location that seq is for [used only to place
  the header icon's day/night variant via `smhi/night?`; arity-1 defaults it to
  Gothenburg, which is also what `--demo` renders], arity-0 fetches `live-points` of
  `default-forecast-hours` [23] points for `default-forecast-location` [Gothenburg]),
  and is where domain-specific
  layout/chart logic lives (e.g. `combined-chart`, `nice-bounds` for
  rounding axis extents, and `chart-labels`, the domain half of the labelling: which
  values get labelled, how they read, and what they have to stay off — the geometry that
  turns that into positions is `labels` above). `default-forecast-hours`/`default-forecast-location` are
  the single source of truth for "prognosis length" and "where" — callers override
  them via `--hours`/`--lat`/`--lon` (main) or `$FORECAST_HOURS`/`$FORECAST_LAT`/
  `$FORECAST_LON` (server) rather than hardcoding a point count or coordinates
  themselves.

- **`trmnl-server.server`** — the HTTP surface only: routing, response helpers, the
  device API, and `start!`. It implements the small API a real TRMNL OG device
  polls when pointed at a custom server: `GET /api/display` (the main poll, returns
  JSON with an `image_url`/`filename`/`refresh_rate`), `GET /api/setup` (first-boot
  welcome screen), `POST /api/log` (device telemetry, replied to with `204`), and
  `GET /images/*` (serves the cached PNG bytes). Plus three human-facing pages:
  `GET /` (landing page — title, the screen being served right now, link to status),
  `GET /status` (battery/firmware/awake-trend/deployed-commit/device-log dashboard) and
  `GET /archive` (a gallery of the rolling 24h image archive), with `GET /archive/*`
  serving each archived PNG (or its `.edn` sidecar) off disk. Uses `http-kit` as both the Ring
  request/response convention and the embedded server (handlers are plain
  `(fn [request] response-map)` fns dispatched on `:request-method`/`:uri` in
  `handler`) — chosen over a Ring+Jetty stack for a single, self-contained
  dependency given how few routes there are. The traversal guards live here, in the
  namespace the untrusted URI arrives in: `archive-file-response` constrains the name to a
  flat `forecast-*.{png,edn}` basename, and `?day` is validated in `pages/status` against
  the days actually on disk, so neither route can be walked out of its directory.

  The work behind the routes is four namespaces under `trmnl-server.server.*`, none of
  which know about HTTP:

- **`trmnl-server.server.render`** — the served screen and its cache. `current-image`
  renders via
  `core/forecast-screen` (fed `core/live-points` of `$FORECAST_HOURS`/
  `$FORECAST_LAT`/`$FORECAST_LON`, or `core/default-forecast-hours`/
  `core/default-forecast-location` if unset) + `image/->1-bit`, encodes to PNG bytes in
  memory (the served bytes never touch disk — `out/` stays reserved for the
  batch-render modes) and
  caches them for 10 minutes keyed by an MD5 content hash, so the `filename`
  embedded in `/api/display`'s response only changes when the rendered image
  actually changes — this lets the device skip re-downloading identical screens
  between polls. `bytes-for` is what enforces that contract on the way back out: it
  serves only the bytes whose hash matches the requested filename, so a cache rollover
  mid-fetch 404s (prompting a re-poll) instead of serving mismatched bytes. On a failed
  regeneration it falls back to the last good image with a stale badge stamped on it
  (see `core/stamp-stale-badge`), and only rethrows when there's nothing to fall back on.

- **`trmnl-server.server.pages`** — the three human-facing HTML pages, built with
  **`hiccup`** (`hiccup2.core`, which auto-escapes string content, so
  there's no hand-rolled `escape-html`) via a shared `page` layout helper. Each page fn
  returns an HTML *string*, not a Ring response — HTTP is `server`'s business, so
  `(pages/status nil)` can be called straight from the REPL. Their
  CSS lives in `resources/css/{base,archive,home}.css` (slurped at load through
  `io/resource`, so it resolves from the uberjar too) rather than inline string
  blobs — `base.css` is the shared shell, with `archive.css` and `home.css`
  layered on top for those two pages. The `/status` page also shows the **deployed commit** via
  `deployed-version`, read once at load from a bundled `version.edn` that
  `build.clj`'s uber task bakes in (`git rev-parse --short HEAD`, plus a `-dirty`
  suffix when the tree isn't clean, and a build timestamp). Running from source
  (`clojure -M:serve`, no build step) there's no such resource and no commit to report,
  so it shows `dev-local` — there is deliberately **no** git fallback here, since
  shelling out to `git` at load time cost the CLI a ~60s exit hang.

- **`trmnl-server.server.telemetry`** — everything the device reports about itself and
  where it's kept: the latest `/api/display` header snapshot (`record-poll!`/`poll-status`),
  the rolling wake-time series, and the raw `/api/log` bodies on disk. See the logging
  note below.

- **`trmnl-server.server.archive`** — the rolling 24h on-disk archive, as pure storage
  (`dir`, `write!`, `entries`). Every *successful* render (i.e. each new cache entry,
  so ~one per 10-min cache miss, not the stale-fallback copies) is also written to
  disk by `write!` as `forecast-<yyyyMMdd-HHmmss>-run<yyyyMMdd-HHmm>-<hash8>.png`
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
  and runs under `render`'s `regen-lock`, so writes are already serialized. Browse/download
  them via the `/archive` gallery (newest first).
  Alongside each archived PNG, `write!` also `spit`s a sibling `.edn` of the same
  basename — the `pr-str` of the point seq the screen was rendered from — so a screen
  spotted after the fact can be **re-rendered or inspected**, since the 1-bit pixels alone
  can't be reversed into the forecast data. The `.edn` is pruned on the same 24h mtime
  schedule as the PNG, and is downloadable from the gallery via a `data` link on each card
  (served as an `application/edn` attachment). The gallery itself lists only PNGs
  (`entries` restricts to `forecast-*.png`) and shows the data link only when the
  sidecar exists. Note `last-hash` (the dedupe probe) filters to `.png` before
  taking the newest by mtime, so the sidecar — written just after the PNG, hence newer —
  can't shadow the content hash and defeat dedupe.

- **`trmnl-server.main`** — the CLI entry point (`-main`). Kept separate from `core`
  purely so the screen composition and the HTTP serving can each require the other
  one-way without a cycle (`server.render` requires `core` for `forecast-screen`;
  `main` requires both). Renders
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
appender rather than hand-rolled `println` redirection. This covers the server's **own
diagnostics only** — device telemetry is hand-written to disk without logback (see below).
Config is `resources/logback.xml` (bundled into the uberjar via `:paths`): a console
appender (so stdout/journald keep the old behaviour) plus one `RollingFileAppender`
(`FILE`). It rolls **daily** (`TimeBasedRollingPolicy`) to a gzipped, date-stamped archive
(e.g. `trmnl-server.log.2026-07-11.gz`), keeps `maxHistory` 30 (~a month) then deletes
the oldest, with a `totalSizeCap` as a backstop — so the log self-manages on the Pi's
SD card without any external `logrotate`. The main log path defaults to
`logs/trmnl-server.log` relative to the process's working directory (the systemd unit's
`WorkingDirectory`, so `/home/seb/trmnl-server/logs/…` in prod); override it with the
`LOG_FILE` env var. logback creates the parent dir if missing. Neither the systemd unit nor
the JVM pins a zone, so these timestamps are the Pi's **local** wall-clock (Europe/Stockholm),
matching journald and the device screen (whose on-screen times are separately hardcoded to
Europe/Stockholm in `smhi`, so the host zone doesn't affect the display either way).

Device telemetry (`POST /api/log`) is **written straight to disk, bypassing
logback entirely** (`server.telemetry/append-log!`): each received body is collapsed to one
line and appended as **raw JSON** (no timestamp prefix) to `logs/device-<yyyy-MM-dd>.log`,
the file picked by the **UTC** date (`today-utc-date`), so the filename does the daily
partitioning a rolling policy used to. The dir is `$DEVICE_LOG_DIR` (default `logs/`), created
on demand; writes are serialised under a private lock and are best-effort (an IO error is
logged via the main logger and swallowed, so the device still gets its `204`). Old days
self-prune: `prune-logs!` (run on each write) keeps only the newest `max-log-files`
(7) `device-<date>.log` files — a count cap, not a calendar window, so a device that skips
days still retains its last 7 *reporting* days. This replaced a
logback `DEVICE` appender + dedicated `trmnl-server.device` logger — dropped because once
`/status` had become "just show one on-disk file" (below), logback's rolling/gzip/retention
was the only remaining complexity and the hand-written path is simpler. Two consequences of
the switch: device rows are **no longer echoed to journald** (they live only in the files +
`/status`), and the `DEVICE_LOG_FILE` env var is gone (use `DEVICE_LOG_DIR`).

The `/status` **device-log table just shows the contents of one day's file**. `telemetry/log-days`
lists the `device-<date>.log` files (newest first) as the day-picker strip above the table;
`?day=<date>` selects one, defaulting to today (`today-utc-date`), which is always shown as a
tab even before it has a file. `telemetry/read-log` reads the chosen file (plain read — the DIY
files aren't gzipped) and renders its rows newest first, time column headed "time (UTC)" (it
renders `created_at` through `Instant`, always UTC — matching the UTC filename dates). `sel` is
constrained to a day that actually exists on disk (or today), so a bogus/traversal `?day=` just
falls back to today and can never name an off-list file. There's **no "clear" button, no
`device-logs` atom, no `seed-device-logs!`**: the files *are* the source of truth, re-read each
load. The **summary cards** (latest battery/firmware) read the newest row of **today's** file
directly (falling back further to the `device-status` poll telemetry), so they always reflect
the *current* day even while you're viewing an older one.

The **Awake card** surfaces the firmware's `Wake-Time` header (how long the device was
awake during its previous cycle, ms — a health signal, since fighting weak WiFi keeps it
awake longer and drains the battery): the latest value in seconds plus moving averages over
1h/6h/24h/7d windows. Every device `/api/display` poll feeds one sample into `telemetry/record-poll!`,
which keeps a rolling `wake-history` series of `{:t :ms}` maps **persisted to disk** as
`wake-times.edn` (in `$DEVICE_LOG_DIR`, alongside the device logs) so the trend survives
restarts/redeploys — `load-wake-history!` reloads it in `start!`. Samples are pruned to a 7-day
window (`wake-retention-ms`, which also sets the longest average window) and non-positive
values are dropped (the firmware sends `0` on a fresh boot with no previous cycle). Writes are
best-effort under `wake-history-lock` and never break the serving path. Unlike the other cards
this one is history-based, not a single snapshot — an empty series shows "no samples yet".

The CLI batch-render feedback in `main` (`"Wrote out/…"`, `"Rendering …"`) is deliberately
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
- **Chart labels are positioned in one pass, against real boxes.** `core/chart-labels`
  → `labels/place` walks every label the chart wants in importance order (each series'
  global high/low, which are always drawn, then up to two prominence-ranked extras per
  series) and scores `labels/candidates`' ~14 positions for each: above/below its dot,
  the same shifted sideways, the opposite side, level beside it, then displaced with a
  leader. One hard rule and two soft ones pick the winner:
  - **hard** — must clear **every dot including its own**, every label already placed,
    the `:keep-out` rects `forecast-screen` passes for what's drawn around the box, and
    the panel. An extra with no such position is dropped, dot and all; a global falls
    back to its least-overlapping candidate rather than go unlabelled.
  - **soft** — prefer inside `:leash` (near enough the plot box not to read as a stray
    number in the margin), then not lying across a series line (`line-ink`, with
    `line-ink-tolerance` columns of grace so a corner graze doesn't count).

  The geometry all lives in `trmnl-server.labels`, which knows nothing about weather;
  `core/chart-labels` is the domain half that decides which values get labelled and
  what they must stay off.
  - **ties** — earliest in preference order, `sort-by` being stable. This is what keeps
    a label from hopping sides when the forecast shifts by a tenth of a degree.

  There is deliberately **no rule about which side of a line looks better** — no peak/
  trough special-casing, no "when the series run close together". A label ends up on
  the open side because that's the side with no ink in it, and it usually ends up
  outside the curve's bend because that's where the space is.

  Do not add a new "nudge it a bit when X" special case here. That's what this replaced:
  three separate guards (a 60x30 `close-points?` test between *dots*, an unconditional
  temp-left/wind-right back-away, and a `:max-y` clamp) that all used dot positions as a
  proxy for label positions. Because nothing compared a label's box to a dot, two
  collisions got through — a low label clamped up onto its own dot, and a displaced
  label landing on the other series' dot. **On a 1-bit surface a collision doesn't look
  like a collision**: white halos (`draw-text`'s 5px stroke, `draw-dot`'s 3px ring) mean
  whichever is drawn second silently erases the other, so the symptom is a number lying
  on the line with no dot, or a label with its digits sliced off. Nothing warns you.
  `clojure -M:check-labels` is what catches it; extend that when extending this.
- Hex color literals like `0xFF000000` overflow Java's signed `int` in Clojure (they
  read as a `Long`); use the signed equivalents (`-16777216` for opaque black, `-1`
  for opaque white) when working with packed ARGB ints via `.setRGB`.
- **`resources/icons/{day,night}-N.png`** (N = SMHI symbol code 1–27) are official SMHI
  weather-symbol SVGs, pre-rasterized to **72x72** 1-bit PNGs (8-bit RGB storage) —
  see `core/draw-weather-icon`, which picks the variant via `smhi/night?` and draws it
  in the header. Unlike the old approach (which let the colored fills wash to white under
  `->1-bit`, leaving bare outlines), the fills now carry **identity by texture**: the SVG
  set uses only four flat colors, mapped to distinct 1-bit treatments — outline `#2c404b`
  → solid black, sun/moon `#ffea00` → ~50% ordered-dither checkerboard, precip marks
  `#cfd6dc` → a denser dither, cloud body `#f5f6f7` → white. The dithered black/white
  pixels are baked into the PNG, so `->1-bit`'s 128 threshold is a no-op on them (the
  render path is unchanged).

  **Rasterize with `rsvg-convert` (librsvg), not ImageMagick's SVG delegate.** The SVGs
  are `34pt` intrinsic, so `magick file.svg -resize 72x72` renders them at ~45px and
  upscales — lumpy, octagonal shapes. `rsvg-convert -w -h` rasterizes the vector directly
  at the target size. The recipe supersamples to 288 (4×) then builds the icon in **two
  layers** so outlines stay crisp while fills get texture:
    - **Palette-remap, don't fuzz-recolor.** `rsvg-convert` antialiases its edges, so
      snapping the four flat colors back needs a nearest-color `-remap` against a 5-swatch
      palette (the four SMHI colors + white background) with `+dither`. A high `-fuzz`
      instead would swallow the near-white background into the `#cfd6dc` precip texture
      (`#cfd6dc` is within ~19% of white).
    - **Outline layer:** `#2c404b` → solid black, everything else white, downscaled and
      hard-thresholded → crisp solid strokes (no dither softening on the outline).
    - **Fill layer:** sun/moon `#ffea00` → `gray50`, precip `#cfd6dc` → `gray35`, outline +
      cloud + background → white; box-downscaled to 72 then `-ordered-dither o4x4` → texture
      only inside the fills.
    - Composite fill under outline with `-compose Darken`, and write with the **`PNG24:`**
      prefix — a plain threshold output is a 1-bit-depth / alpha PNG that Java2D's
      `drawImage` silently refuses to blit (the icon renders blank); `PNG24:` forces the
      8-bit RGB, no-alpha storage.

  The SVG source is SMHI's "stroke/centered" set, checked in under
  **`assets/icons-svg/{day,night}-N.svg`** (a top-level dir, deliberately *not* under
  `resources/`, so the SVGs don't get bundled into the uberjar — only the rasterized PNGs
  do). They were originally fetched per icon from e.g.
  `https://www.smhi.se/weather-page/weathersymbols/centered/stroke/day/1.svg` (the
  `?proxy=wpt-a-<uuid>` query token there is a required but transient cache key), but the
  local copies are the source of truth now — regenerate from them, no network needed.
  Full regeneration recipe (per `{day,night}` × N; `pal.png` is the 5-swatch palette):

  ```
  magick -size 1x1 xc:'#ffffff' xc:'#f5f6f7' xc:'#cfd6dc' xc:'#ffea00' xc:'#2c404b' +append pal.png

  rsvg-convert -w 288 -h 288 assets/icons-svg/day-N.svg -o b.png
  magick b.png -background white -flatten +dither -remap pal.png flat.png
  magick flat.png -fuzz 2% -fill black -opaque '#2c404b' -fill white +opaque black \
    -colorspace Gray -resize 72x72 -threshold 55% outline.png
  magick flat.png -fuzz 2% -fill white -opaque '#2c404b' -fill white -opaque '#f5f6f7' \
    -fill 'gray50' -opaque '#ffea00' -fill 'gray35' -opaque '#cfd6dc' \
    -colorspace Gray -filter Box -resize 72x72 -ordered-dither o4x4 fill.png
  magick fill.png outline.png -compose Darken -composite PNG24:day-N.png
  ```
