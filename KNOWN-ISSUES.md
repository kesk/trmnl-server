# Code review findings

Weaknesses in *this repo's own code*, roughly in priority order. None are urgent for
the current single-device, 15-minute-poll deployment — this is a backlog, not a bug
list.

Not to be confused with `ISSUES.md`, which records device/firmware and upstream-API
problems observed in production. That file is about things outside this codebase; this
one is about the code.

Line numbers are as of 2026-07-27 and will drift — treat them as a starting point, not
a reference.

## Worth doing

### 1. The same two x-geometry formulas are written out eight times

`(fn [i] (+ x (* w (/ i (double (dec n))))))` — the plotting-point spacing, which
divides by `n-1` so the first and last points land on the box edges — appears verbatim
in `core/series-layout` (:44), `cloud-cover-strip` (:152), `hour-axis-labels` (:308) and
`day-markers` (:421).

The slot geometry, which divides by `n` because each point owns a column, appears in
four more places and in three different shapes: `slot-center` in `thunder-flashes`
(:186), `slot-edge` in `rain-background` (:208), and `slot-w` plus an inline centre
calculation in `precip-bar-chart` (:337) and `precip-probability-line` (:390-393).

The distinction between the two is real and load-bearing — it is why a lightning bolt
sits over its own rainy column instead of drifting off it — and today it is explained in
prose in three separate docstrings rather than expressed once in code.

**Fix:** two helpers, `plot-x` (the `n-1` divisor) and `slot-center`/`slot-edge` (the `n`
divisor), and let the names carry the distinction.

### 2. Europe/Stockholm is hardcoded while the location is configurable

Every timezone-aware helper in `trmnl-server.smhi` pins `Europe/Stockholm`, while
`--lat`/`--lon` and `$FORECAST_LAT`/`$FORECAST_LON` invite arbitrary coordinates. SMHI's
coverage is Nordic-ish so the damage is bounded, but a non-Swedish location silently gets
Swedish local time for hour labels and day boundaries.

**Fix:** carry a timezone alongside the location override, or at least document the
constraint where the flags are documented.

### 3. `smhi` rebuilds a DateTimeFormatter on every call

`local-time-str` (:73), `local-date` (:78), `local-day-label` (:134) and `local-now-str`
(:142) each repeat the same `Instant/parse → atZone` pipeline; the three that format
construct a `DateTimeFormatter` inline on every call — while the `swedish` locale right
above them is correctly a `def`. The server side of this was consolidated into
`pages/format-instant` during the 2026-07-27 refactor; `smhi` did not get the same
treatment.

**Fix:** hoist the formatters to `def`s and factor out the shared pipeline.

### 4. A stale docstring and an undocumented demo output

`core/draw-weather-icon` (:76) still claims the icons' fills "sit above `->1-bit`'s
threshold and wash to white, leaving just their dark outlines — so the icons need no
recoloring". That stopped being true at commit `8f66c38`/`7441ec4`, which gave the fills
ordered-dither texture; CLAUDE.md describes the current behaviour correctly, so the
docstring now contradicts it.

Separately, `--demo` also writes `out/demo-stale.png` (the stale-badge sample), which
appears in neither the command list nor the `main` description in CLAUDE.md.

### 5. Landing-page loose ends

Added in `0a83cde`, three small things:

- `pages/home` (:314) builds `"/images/" + filename` by hand while `server/image-url`
  (:75) exists for the same job — though the latter takes a `base-url` the pages don't
  have, so sharing needs a small split rather than a straight call.
- The page embeds `<img src="/images/<hash>.png">`. If the 10-minute cache happens to
  roll over between the HTML response and the image fetch, `image-response` 404s and the
  browser shows a broken image. The device recovers from this by re-polling; a browser
  does not. A stable `/images/latest.png` alias, or inlining the bytes, would close it.
- `GET /` calls `current-image`, so any crawler that hits the root on a cold cache pays
  for a live SMHI fetch and a full render.

### 6. `/status` reads today's device log twice

`pages/status` (:195-196) reads the selected day, then reads today's file again for the
summary cards whenever you are viewing an older day. Correct, just wasteful — and the
second read is invisible at the call site.

## Cleanup

### 7. Dead code

- `smhi/upcoming` (:144) — no callers anywhere.
- `image/draw-wrapped-text` (:92) — no callers.
- `image/floyd-steinberg` (:261) — no callers; it is a deliberate library primitive for
  photo/gradient dithering, but it also duplicates `->1-bit`'s greyscale loop line for
  line, so if it stays the two should share that loop.

### 8. `migrate_device_logs.clj` is a spent one-off

The babashka migration from the old logback device logs to the per-date layout
(`6f76b85`, July 2026) has been run and is done, but still sits in the repo root.
Delete it, or move it under `dev/`.

### 9. `ISSUES.md` and `KNOWN-ISSUES.md` are easy to confuse

Both files opened with the heading `# Known issues` until this file was retitled. The
filenames are still near-identical for two documents with quite different jobs (upstream
and device problems vs. this repo's code). Worth renaming one on disk — `DEVICE-ISSUES.md`
would say what `ISSUES.md` actually holds.

Note also that this file's *Resolved #5* below describes `core/draw-series-labels` and
`draw-extremum-label`, which no longer exist: that design was superseded twice over (see
Resolved 2026-07-27). The entry is kept as a record of what happened at the time, not as a
description of the code.

### 10. The label checker prints the wrong season name

`dev/label_collisions.clj` (:131) builds its dataset label with `(:name s)`, but the
season maps use `:label`. All four demo seasons therefore report as `"demo "`, so a
failure tells you a season broke without telling you which one. One-word fix.

### 11. `pixel-font` is derived at draw time in eight places

`core` caches `legend-font` (:122) as a `def` but calls `img/pixel-font` inline at :307,
:349, :370, :406, :426, :471, :473 and :475 — five of which are the identical
`(img/pixel-font :regular 16)`, the very font `legend-font` already holds. Each call runs
`Font/.deriveFont`. Two or three cached `def`s would cover every use.

### 12. `fill-string` leaves a 5px stroke on the Graphics2D

`image/fill-string` (:58) sets a `BasicStroke` for the halo and never restores it, unlike
`draw-polyline`, `draw-variable-line` and `draw-dashed-line`, which all reset to 1.0.
Latent today because `draw-rect` and `draw-polygon` are only ever called with
`:fill? true`, but the first `:fill? false` call after a haloed label will draw a 5px
round-joined outline with no warning.

The deeper point: the canvas map threads a mutable `Graphics2D` whose colour, font and
stroke are order-dependent global state. A `with-stroke`-style helper, or a consistent
save/restore discipline, would make that safe rather than merely lucky.

### 13. The mm label's number format depends on the JVM's locale

`core/precip-mm-labels` (:369) uses `(format "%.1fmm" …)`, which follows the default
locale: `1.5mm` on an English JVM, `1,5mm` on a Swedish one. A comma is arguably *right*
for a Swedish screen, but right now it is an environment property rather than a decision,
and the rendered screen differs between a dev machine and the Pi. `server.pages` pins
`Locale/US` explicitly for the battery voltage, so the codebase is inconsistent with
itself. Pick one and pass it explicitly.

### 14. "Moln (%)" labels a series that is not a percentage

The legend in `core/forecast-screen` says `Moln (%)` while the strip's thickness encodes
SMHI's `cloud_area_fraction` in octas (0-8). It is the only unit on the screen that names
something it does not show.

## Minor notes

- `image/->1-bit` allocates a `java.awt.Color` per pixel (~384k per render). The much
  larger cost here was reflection, removed on 2026-07-27; the allocation remains and is
  still irrelevant at one render per 10 minutes. Don't copy the pattern into a hot loop.
- Hand-rolled `--hours`/`--lat`/`--lon` parsing throws a raw `NumberFormatException` on
  bad input. Fine at this scale.
- No test suite; for output that is fundamentally "does the screen look right," the
  `--demo` season renders are the de-facto regression tool. `clojure -M:check-labels`
  covers the one part that can be checked mechanically: that no temp/wind chart label is
  drawn onto a dot or another label. Everything else is still eyes-on-a-PNG.

## Resolved (2026-07-27)

Four commits, each verified render-identical against the demo seasons, the rain-test day,
the checked-in fixtures and a real archived forecast.

### `bb85b46` — the serving path was one 772-line namespace

`server.clj` covered five concerns at once; the giveaway was a forward `declare` needed
because one fn sat above its own dependency chain. Split into `server` (routing, device
API, `start!`) plus `server.render`, `server.pages`, `server.telemetry` and
`server.archive`, none of which know about HTTP. Page fns now return HTML strings rather
than Ring maps, so they can be called from a REPL.

### `4506ddb` — label placement lived in `core`

~200 lines of pure geometry with no trace of the weather domain moved to
`trmnl-server.labels`, whose public surface is five fns. `core` keeps `chart-labels`, the
half that decides which values get labelled. `core.clj`: 778 → 508 lines. The font factory
moved to `image/pixel-font` on the way.

### `59c1fe6` — the render path was reflective throughout

68 reflection warnings, concentrated in `->1-bit`, which makes two Java2D calls per pixel
across 384k pixels: **173ms → 13.5ms per render (12.8x)**. The whole codebase is now silent
under `*warn-on-reflection*`, and a `require :reload` of any namespace must stay that way.

### `3946164` — a required label could still land on a dot

`labels/place`'s documented fallback (a required label with no clear position takes the
least-overlapping one) was firing about once in 6000 generated days, always the same shape:
a series minimum at the *last* point, cornered by the keep-out band below, the panel edge
right, and the other series' label and dot left. Fixed by widening the candidate set — four
more positions reaching a second step sideways on the rows that already draw a leader —
rather than special-casing the fallback. Over 60000 fixed-seed days, failures went 10 → 0
and labels sitting across a line dropped 27%. `dev/fixtures/cornered-min.edn` pins it.

## Resolved (2026-07-09)

### 1. No HTTP timeout on the SMHI fetch

`smhi/fetch-raw-forecast` used `java.net.http.HttpClient` with no timeout, and the JDK
client has no default, so a stalled connection blocked the synchronous http-kit handler
forever — and the stale-badge fallback never fired, being keyed on an exception that never
arrived.

**Fixed:** set a 10s `.timeout` on the `HttpRequest` and a 10s `connectTimeout` on the
client.

### 2. `current-image` had no concurrency guard

Two simultaneous requests on an expired cache both fetched SMHI and re-rendered (last write
wins); combined with issue 1, each stuck request piled up another worker thread.

**Fixed:** double-checked locking on a dedicated lock — the fresh-cache fast path stays
lock-free, and the second thread re-checks the cache inside the lock and reuses the entry
the first one just produced.

### 3. `/images/*` ignored the requested filename

The route served whatever the cache held regardless of the filename in the URL, so a cache
rollover between the device's `/api/display` poll and its image fetch served mismatched
bytes, undermining the content-hash contract.

**Fixed:** match the requested filename against the current entry's fresh/stale filename
and serve the corresponding bytes, 404ing on a mismatch so the device re-polls. (Now
`render/bytes-for`.)

### 5. `core/draw-series-labels` was nearing its complexity ceiling

Eleven keyword args in parallel above-/below- pairs, with per-day vectors threaded through
`offset-at`.

**Fixed:** `combined-chart` computed a per-day placement map up front and passed it as a
single arg. *Superseded:* `d4827c4` replaced this with one-pass placement against real
boxes, and `4506ddb` moved the whole thing to `trmnl-server.labels`. Neither
`draw-series-labels` nor `draw-extremum-label` exists any more.
