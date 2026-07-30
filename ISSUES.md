# Known issues

Problems seen in production that live on the device/firmware or upstream-API side,
not in this repo's rendering logic — recorded for follow-up, not because anything
needs fixing in this codebase beyond the mitigations already applied. (Weaknesses in
*our own* code belong in `KNOWN-ISSUES.md` instead.)

## 1. SMHI intermittently answers with HTML instead of JSON

First seen 2026-07-07 04:05:36–04:06:05, when `trmnl-server` threw on every
`/api/display` and `/api/setup` request for about 30 seconds:

```
java.lang.Exception: JSON error (unexpected character): <
	at trmnl_server.smhi$fetch_raw_forecast...
	at trmnl_server.core$forecast_screen...
	at trmnl_server.server.render$current_image...
	at trmnl_server.server$display_response...
```

The `<` strongly suggests SMHI returned an HTML page (error/rate-limit/outage)
instead of JSON. The device's own log corroborated this from its side:
`"Error fetching API display: 8, detail: HTTP Client failed with error: (500)"`.

**It is not a one-off.** The same failure has recurred twice since, both times
handled by the mitigation below rather than reaching the device:

```
2026-07-16 22:06:13 WARN  server - Forecast regeneration failed, serving stale cache
java.lang.Exception: JSON error (unexpected character): <
2026-07-17 01:08:17 WARN  server - Forecast regeneration failed, serving stale cache
java.lang.Exception: JSON error (unexpected character): <
```

So it's a recurring upstream hiccup (three episodes in the ~3 weeks of logs kept),
distinct from the `pmp3g` → `snow1g` migration documented in `CLAUDE.md` — but the
same failure mode is exactly what a future migration would look like too, so a burst
of these is worth a glance at the SMHI API status before assuming it'll pass.

**Mitigation shipped** (2026-07-07, commit `08519eb`; since moved and extended):
`current-image` in `src/trmnl_server/server/render.clj` catches regeneration
failures and serves the last successfully rendered image instead of propagating a
bare 500 — a stale forecast beats none. The fallback copy is stamped with a warning
badge (`core/stamp-stale-badge`) and served under its own `-stale.png` content-hash
filename, so a stale screen is recognisable on the wall without reading the logs.
Only the very first request ever (empty cache) can still surface the exception.

**To investigate later**:
- Whether prolonged staleness is worth alerting on now that we know it recurs —
  e.g. page/notify if the cache has been serving the stale badge for longer than
  some threshold, rather than relying on noticing the badge.
- Whether SMHI has a documented rate limit we might be tripping — check request
  frequency against `refresh-rate-seconds` (900s) plus the device's own retry
  behavior (it was retrying every ~2s during the first failure window, which may
  have compounded whatever SMHI was doing).
