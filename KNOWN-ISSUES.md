# Known issues

Weaknesses noted during a code review (2026-07-09), roughly in priority order.
None are urgent for the current single-device, 15-minute-poll deployment, but
the first two compound each other and are worth fixing before relying on the
server unattended.

## 1. No HTTP timeout on the SMHI fetch

`smhi/fetch-raw-forecast` uses `java.net.http.HttpClient` with no timeout —
and the JDK client has **no default timeout**, so a stalled connection blocks
forever. Since `server/current-image` renders synchronously inside the
http-kit handler, one hung SMHI socket wedges every subsequent device poll
indefinitely. Worse, the stale-badge fallback never triggers, because it is
keyed on an exception that never arrives.

**Fix:** set `.timeout` on the `HttpRequest` (and `connectTimeout` on the
client). This is the single highest-value hardening change available.

## 2. `current-image` has no concurrency guard

Two simultaneous requests arriving on an expired cache both fetch SMHI and
re-render; last write wins. Theoretical with one device polling every 15
minutes, but combined with issue 1 each stuck request piles up another worker
thread.

**Fix:** wrap the regeneration path in `locking` (settles both the duplicate
work and the pile-up cheaply).

## 3. `/images/*` ignores the requested filename

The `/images/*` route serves whatever the cache currently holds, regardless
of the filename in the URL. If the cache rolls over between the device's
`/api/display` poll and its image fetch, the bytes won't match the filename
hash it asked for. Almost certainly harmless in practice (the fetch follows
within seconds), but it quietly undermines the content-hash contract the
caching design is built on.

**Fix:** match the requested filename against the cache entry and 404 on a
miss, or serve the entry whose hash matches.

## 4. Europe/Stockholm is hardcoded while location is configurable

Every timezone-aware helper in `trmnl-server.smhi` pins `Europe/Stockholm`,
while `--lat`/`--lon` and `$FORECAST_LAT`/`$FORECAST_LON` invite arbitrary
coordinates. SMHI's coverage is Nordic-ish so the damage is bounded, but a
non-Swedish location silently gets Swedish local time for hour labels and
day boundaries.

**Fix:** carry a timezone alongside the location override, or at least
document the constraint where the flags are documented.

## 5. `core/draw-series-labels` is nearing its complexity ceiling

Eleven keyword args in parallel above-/below- pairs, with per-day vectors
threaded through `offset-at`. Correct and well-documented, but the next
collision-handling feature will hurt.

**Fix (when next touched):** compute a per-label placement map
(`{:dx :dy :leader?}`) up front in `combined-chart` and pass that, collapsing
the arg surface.

## Minor notes

- `image/->1-bit` allocates a `java.awt.Color` per pixel (~384k per render).
  Irrelevant at one render per 10 minutes; don't reuse it in a hot loop.
- Hand-rolled `--hours`/`--lat`/`--lon` parsing throws a raw
  `NumberFormatException` on bad input. Fine at this scale.
- No test suite; for output that is fundamentally "does the screen look
  right," the `--demo` season renders are the de-facto regression tool.
