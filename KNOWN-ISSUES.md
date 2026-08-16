# Code review findings

Weaknesses in *this repo's own code*, roughly in priority order. None are urgent for
the current 15-minute-poll deployment — this is a backlog, not a bug list.

Its counterpart is `DEVICE-ISSUES.md`, which records device/firmware and upstream-API
problems observed in production. That file is about things outside this codebase; this
one is about the code.

Line numbers are as of 2026-08-09 and will drift — treat them as a starting point, not
a reference. Every entry below was re-verified against the code on that date; what had
been fixed in the meantime moved to *Resolved* at the bottom.

## Worth doing

### 1. The same two x-geometry formulas are written out eight times

`(fn [i] (+ x (* w (/ i (double (dec n))))))` — the plotting-point spacing, which
divides by `n-1` so the first and last points land on the box edges — appears verbatim
in `core/series-layout` (:69), `cloud-cover-strip` (:177), `hour-axis-labels` (:354) and
`day-markers` (:467).

The slot geometry, which divides by `n` because each point owns a column, appears in
four more places and in three different shapes: `slot-center` in `thunder-flashes`
(:232), `slot-edge` in `rain-background` (:254), and `slot-w` plus an inline centre
calculation in `precip-bar-chart` (:383) and `precip-probability-line` (:436-439).

The distinction between the two is real and load-bearing — it is why a lightning bolt
sits over its own rainy column instead of drifting off it — and today it is explained in
prose in three separate docstrings rather than expressed once in code.

**Fix:** two helpers, `plot-x` (the `n-1` divisor) and `slot-center`/`slot-edge` (the `n`
divisor), and let the names carry the distinction.

### 2. Europe/Stockholm is hardcoded while the location is configurable

Every timezone-aware helper in `trmnl-server.smhi` pins `Europe/Stockholm` (:159, :167,
:220, :228), while arbitrary coordinates can be set from two directions. SMHI's coverage
is Nordic-ish so the damage is bounded, but a non-Swedish location silently gets Swedish
local time for hour labels and day boundaries.

**Wider than when this was written.** The entry originally named `--lat`/`--lon` and
`$FORECAST_LAT`/`$FORECAST_LON`. Those env vars **no longer exist** — the only trace of
them left is a stale docstring in `core/forecast-screen` (:487) that still cites them by
name, which is worth deleting on its own. What replaced them is bigger: every display's
`:lat`/`:lon` is a required field of its `devices.edn` entry, and since the registry forms
landed those coordinates are **typed into a web form** by a human who is given no hint that
only Sweden is really supported. `--lat`/`--lon` remain on the CLI path.

**Fix:** carry a timezone alongside the location — derived from the coordinates, or a
`:tz` field on the registry entry defaulting to `Europe/Stockholm` — or at minimum say so
on the configure/edit forms, which are now the main way a location gets set.

### 3. `smhi` rebuilds a DateTimeFormatter on every call

`local-time-str` (:157), `local-date` (:162), `local-day-label` (:218) and `local-now-str`
(:223) each repeat the same `Instant/parse → atZone` pipeline; the three that format
construct a `DateTimeFormatter` inline on every call (:160, :221, :229) — while the
`swedish` locale right above them is correctly a `def`. The server side of this was consolidated into
`pages/format-instant` during the 2026-07-27 refactor; `smhi` did not get the same
treatment.

**Fix:** hoist the formatters to `def`s and factor out the shared pipeline.

### 4. Stale docstrings and an undocumented demo output

Three separate things, all the same class — prose that outlived what it described:

- `core/draw-weather-icon` (:110) still claims the icons' fills "sit above `->1-bit`'s
  threshold and wash to white, leaving just their dark outlines — so the icons need no
  recoloring". That stopped being true at commit `8f66c38`/`7441ec4`, which gave the fills
  ordered-dither texture; CLAUDE.md describes the current behaviour correctly, so the
  docstring now contradicts it.
- `core/forecast-screen` (:487) cites `FORECAST_LAT`/`FORECAST_LON` as a way to override
  the location. Those env vars do not exist anywhere in the codebase (see #2).
- `--demo` also writes `out/demo-stale.png` (the stale-badge sample, `main/write-stale-demo`
  :17-25), which appears in neither the command list nor the `main` description in CLAUDE.md.

### 5. Landing-page loose ends

Added in `0a83cde`. One of the original three remains (the others are under *Resolved
2026-08-09* and *Resolved 2026-08-15*):

- `pages/screen-card` builds `"/images/" + id + "/" + filename` by hand while
  `server/image-url` (:126-127) exists for the same job — though the latter takes a
  `base-url` the pages don't have, so sharing needs a small split rather than a straight
  call.

### 6. The device page reads today's log twice

`pages/status` (:508-509) reads the selected day, then reads today's file again for the
summary cards whenever you are viewing an older day. Correct, just wasteful — and the
second read is invisible at the call site. (Viewing *today*, the common case, already
reuses the first read.)

## Cleanup

### 7. Dead code

- `smhi/upcoming` (:231) — no callers anywhere.
- `image/draw-wrapped-text` (:92) — no callers.
- `image/floyd-steinberg` (:261) — no callers; it is a deliberate library primitive for
  photo/gradient dithering, but it also duplicates `->1-bit`'s greyscale loop line for
  line, so if it stays the two should share that loop.

### 8. `migrate_device_logs.clj` is a spent one-off

The babashka migration from the old logback device logs to the per-date layout
(`6f76b85`, July 2026) has been run and is done, but still sits in the repo root.
Delete it, or move it under `dev/`.

### 9. A resolved entry below describes code that no longer exists

*(The filename half of this entry — `ISSUES.md` vs `KNOWN-ISSUES.md` — is done; see
Resolved 2026-08-09.)*

This file's *Resolved #5* below describes `core/draw-series-labels` and
`draw-extremum-label`, which no longer exist: that design was superseded twice over (see
Resolved 2026-07-27). The entry is kept as a record of what happened at the time, not as a
description of the code.

### 10. `pixel-font` is derived at draw time in eight places

`core` caches `legend-font` (:147) as a `def` but calls `img/pixel-font` inline at :353,
:395, :416, :452, :472, :564, :566 and :568 — five of which (:353, :395, :452, :566, :568)
are the identical `(img/pixel-font :regular 16)`, the very font `legend-font` already
holds. Each call runs `Font/.deriveFont`. Two or three cached `def`s would cover every
use.

### 11. `fill-string` leaves a 5px stroke on the Graphics2D

`image/fill-string` (:58) sets a `BasicStroke` for the halo and never restores it, unlike
`draw-polyline`, `draw-variable-line` and `draw-dashed-line`, which all reset to 1.0.
Latent today because `draw-rect` and `draw-polygon` are only ever called with
`:fill? true`, but the first `:fill? false` call after a haloed label will draw a 5px
round-joined outline with no warning.

The deeper point: the canvas map threads a mutable `Graphics2D` whose colour, font and
stroke are order-dependent global state. A `with-stroke`-style helper, or a consistent
save/restore discipline, would make that safe rather than merely lucky.

### 12. The mm label's number format depends on the JVM's locale

`core/precip-mm-labels` (:415) uses `(format "%.1fmm" …)`, which follows the default
locale: `1.5mm` on an English JVM, `1,5mm` on a Swedish one. A comma is arguably *right*
for a Swedish screen, but right now it is an environment property rather than a decision,
and the rendered screen differs between a dev machine and the Pi. `server.pages` (:538)
pins `Locale/US` explicitly for the battery voltage, so the codebase is inconsistent with
itself. Pick one and pass it explicitly.

### 13. "Moln (%)" labels a series that is not a percentage

The legend in `core/forecast-screen` (:576) says `Moln (%)` while the strip's thickness
encodes SMHI's `cloud_area_fraction` (`smhi.clj`:124) in octas (0-8). It is the only unit
on the screen that names something it does not show.

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

## Resolved (2026-08-15)

### `/` showed a screen no display had ever shown, and 404'd a third of the time

Two entries closed at once, because they were the same mistake seen from both ends: the
*Landing-page loose ends* bullet about the cache rolling over under a live page, and the
caveat left on *`GET /` paid for a live render* below (that the `<img>` sub-resource still
called `current-image`).

`screen-card` embedded `<img src="/images/<id>/<hash>.png">` built from the **render
cache**, and the filename is a hash of the pixels. The cache TTL is 10 minutes and the
device polls every 15, so for 5 minutes in every 15 the page's own image request arrived on
an expired entry, `image-response` regenerated, the new render got a new hash — the
"Uppdaterad HH:mm" stamp guarantees it — and `bytes-for` correctly refused to serve bytes
under a filename that no longer named them. Broken image, roughly a third of visits, self-
healing on reload (the broken load is what refreshed the cache). The regeneration was also
pure loss: it fetched SMHI and rendered a full screen only to 404 anyway, since a fresh
render can never match the hash being asked for.

The framing was the actual bug. `/` was showing "what we would serve if asked now", which
is not a thing anyone wants to look at — the display collects a screen every 15 minutes and
shows it until the next one, so the cached entry is routinely a picture no panel has ever
displayed.

Resolved by recording what the display **downloads**: `render/mark-fetched!` +
`fetched-entry`, filled from the `/images/<id>/…` route and only for a request carrying the
device's own `ID` + `Access-Token` (the firmware's `buildImageHeaders` attaches both —
confirmed in `../trmnl-firmware`), so a browser loading the page can't record itself as the
display. `bytes-for` now takes a device and searches the cache *and* that copy, and never
regenerates; `image-response` therefore no longer calls `current-image` at all, which is
what closes the sub-resource caveat. `/` reads `fetched-entry`, captions it "Fetched HH:mm",
and falls back to a placeholder.

Two deliberate consequences. The record is **in-memory only**, so a restart blanks every
card until each display next polls (up to 15 min) — it is a claim about a panel in another
room, and after a restart it isn't one we can make; saying so beats showing a render nobody
has seen. That is also why the archive fallback from `87d6ddd` is **gone**: it existed to
paper over exactly this gap, with a file that was only ever an approximation of what the
device held. And `render/forget!` deliberately does *not* clear it — editing a registry
entry doesn't change what is on the glass.

Verified offline (the route no longer touches the network, so the whole path is testable
without SMHI): placeholder before any fetch; the embedded filename serves 200; a browser
fetch and a wrong-token fetch both leave `:fetched-at` untouched while the display's own
fetch updates it; a cache rollover underneath a live page still serves the fetched copy;
and an unknown filename still 404s.

## Resolved (2026-08-09)

*(Headings here name the entry rather than its number: the open list renumbers whenever
something leaves it, so a number in a resolved heading points at whatever moved up into
its place. This section was already carrying two such stale numbers.)*

### `check-labels` validated a chart that isn't the one rendered

The checker restated the chart box and keep-out rects instead of reading them out of
`core`, deliberately — "this is the check, so it should fail loudly if core's idea of any
of them drifts". They drifted, and nothing failed loudly: it was checking a box 14px
taller than the real one (`[40 172 720 155]` against `[40 158 720 176]`) with the cloud
band missing from its keep-out entirely. A copy can only fail loudly if something compares
it, and nothing did.

Resolved by `core/screen-geometry`, one map holding the cloud/precip strip positions, the
`:chart-box` and the `:keep-out` rects derived from them. `forecast-screen` destructures
it; the checker reads `:chart-box`/`:keep-out` off it. Verified render-identical: all 11
`--demo` PNGs byte-for-byte unchanged.

What the honest geometry shows, over 3010 datasets (demo seasons, rain-test, the five
fixtures, 3000 generated days): still **0 hard collisions** — no label lands on a dot or
another label, so nothing has been silently eating ink — but labels sitting *across* a
line roughly double, since the checker had been scoring against a chart with more room in
it. `dev/fixtures/flat-start.edn` goes from 0 crossings to 1: its last wind label lies
across the temperature line for 40 columns against a tolerance of 8, which is what ships
and what the invented geometry was hiding. That's a soft-rule violation, not a failure —
left open as a placement question, now visible.

`line-crossings`' docstring was corrected to match (it claimed 0 for every fixture but
calm-hour, true only of the geometry it invented).

### The label checker printed the wrong season name

`(:name s)` against season maps keyed `:label`, so all four demo seasons reported as
`"demo "` — a failing season told you a season broke, not which one. One word.

### `ISSUES.md` renamed to `DEVICE-ISSUES.md`

The two filenames were near-identical for two documents with quite different jobs.
`git mv ISSUES.md DEVICE-ISSUES.md`, with its heading retitled to match and all nine
references updated (four in CLAUDE.md, five in `src/`).

Renaming rather than *merging* the two was deliberate, and the reason is worth keeping:
`DEVICE-ISSUES.md` is cited **by number from source docstrings** (`server.clj` "That cost
a real display an evening (DEVICE-ISSUES.md #2)", plus CLAUDE.md's #2 and #5), while
nothing in `src/` cites this file at all. The device notes are reference documentation
that code points at to explain its own shape; this file is a worklist. They also have
opposite lifecycles — entries leave here when fixed, and stay there *because* they're
resolved, the firmware traps being permanent facts about the hardware. Merging would have
forced a renumber across both files' 1–5 ranges and broken those citations, which are
exactly the kind that rot unnoticed.

### `GET /` paid for a live render (a *Landing-page loose ends* bullet)

`/` called `current-image` per registered display, so a crawler hitting the root on a cold
cache triggered a live SMHI fetch and a full render for each one. Resolved by
`render/cached-entry` (`render.clj`:91), a peek that returns the cache entry or `nil` and
never regenerates — its docstring names this case. `87d6ddd` then added the archive
fallback behind it, so the page still shows something after a restart rather than going
blank for up to 15 minutes.

Note the sub-resource is a separate question and is *not* covered: the `<img>` src still
points at `/images/<id>/…`, which does call `current-image`. A crawler that fetches the
page's images can still trigger a render. What changed is that fetching the HTML alone no
longer does — which is what the entry described.

*(That caveat is closed as of 2026-08-15 — see the entry above. `image-response` no longer
calls `current-image`, so nothing reachable from a browser regenerates. `cached-entry` is
now private and no longer the landing page's source; the page reads `fetched-entry`.)*

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
