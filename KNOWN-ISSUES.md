# Known issues

Weaknesses noted during a code review (2026-07-09), roughly in priority order.
None are urgent for the current single-device, 15-minute-poll deployment.

Issues 1, 2, 3, and 5 were fixed on 2026-07-09 (see the resolved section
below); issue 4 and the minor notes remain open.

## 4. Europe/Stockholm is hardcoded while location is configurable

Every timezone-aware helper in `trmnl-server.smhi` pins `Europe/Stockholm`,
while `--lat`/`--lon` and `$FORECAST_LAT`/`$FORECAST_LON` invite arbitrary
coordinates. SMHI's coverage is Nordic-ish so the damage is bounded, but a
non-Swedish location silently gets Swedish local time for hour labels and
day boundaries.

**Fix:** carry a timezone alongside the location override, or at least
document the constraint where the flags are documented.

## Minor notes

- `image/->1-bit` allocates a `java.awt.Color` per pixel (~384k per render).
  Irrelevant at one render per 10 minutes; don't reuse it in a hot loop.
- Hand-rolled `--hours`/`--lat`/`--lon` parsing throws a raw
  `NumberFormatException` on bad input. Fine at this scale.
- No test suite; for output that is fundamentally "does the screen look
  right," the `--demo` season renders are the de-facto regression tool.

## Resolved (2026-07-09)

### 1. No HTTP timeout on the SMHI fetch

`smhi/fetch-raw-forecast` used `java.net.http.HttpClient` with no timeout, and
the JDK client has no default, so a stalled connection blocked the synchronous
http-kit handler forever — and the stale-badge fallback never fired, being
keyed on an exception that never arrived.

**Fixed:** set a 10s `.timeout` on the `HttpRequest` and a 10s `connectTimeout`
on the client.

### 2. `current-image` had no concurrency guard

Two simultaneous requests on an expired cache both fetched SMHI and re-rendered
(last write wins); combined with issue 1, each stuck request piled up another
worker thread.

**Fixed:** double-checked locking on a dedicated lock — the fresh-cache fast
path stays lock-free, and the second thread re-checks the cache inside the lock
and reuses the entry the first one just produced.

### 3. `/images/*` ignored the requested filename

The route served whatever the cache held regardless of the filename in the URL,
so a cache rollover between the device's `/api/display` poll and its image fetch
served mismatched bytes, undermining the content-hash contract.

**Fixed:** match the requested filename against the current entry's fresh/stale
filename and serve the corresponding bytes, 404ing on a mismatch so the device
re-polls.

### 5. `core/draw-series-labels` was nearing its complexity ceiling

Eleven keyword args in parallel above-/below- pairs, with per-day vectors
threaded through `offset-at`.

**Fixed:** `combined-chart` now computes a per-day placement map
(`{:above {:dx :dy :leader? :max-y} :below {…}}`) up front and passes it as a
single arg; a new `draw-extremum-label` helper renders one placement, and the
scalar-or-vector `offset-at` shim is gone. Verified render-identical against the
`--demo` seasons.
