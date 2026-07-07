# Known issues

Notes from investigating dashboard-pi's `trmnl-server` journal on 2026-07-07.
Both issues below are on the device/firmware or upstream-API side, not bugs in
this repo's rendering logic — recorded here for follow-up, not because
anything needs fixing in this codebase beyond the mitigation already applied.

## 1. Device-side filesystem error, every poll cycle

The TRMNL OG device logs this pair on essentially every ~15-minute wake cycle
(confirmed recurring continuously since at least 2026-07-07 04:21, unaffected
by a `trmnl-server` restart):

```
{"message":"File open ERROR","source_line":228,"source_path":"src/filesystem.cpp", ...}
{"message":"File writing ERROR. Result - 0","source_line":3622,"source_path":"src/bl.cpp", ...}
```

Observed alongside each occurrence: `wifi_status: "no_shield"`, `wifi_signal: 0`
(despite the device clearly having working wifi, since it successfully POSTs
this very log to us — likely a stale/default field rather than a real wifi
fault), and a healthy free heap (~221KB, so not memory pressure).

**Read on it so far**: looks like the device firmware failing to open/write a
local file on its internal flash (LittleFS/SPIFFS) — plausibly for caching the
downloaded PNG before decoding it to the e-ink panel. This is firmware-internal
(`filesystem.cpp` / `bl.cpp` in `usetrmnl/firmware`) and not something our
server's response can cause or fix.

**To investigate later**:
- Check `usetrmnl/firmware` GitHub issues for "File open ERROR" / `filesystem.cpp:228`.
- Try a factory reset of the device to see if it clears (rules out
  flash wear/corruption vs. a firmware bug on 1.8.9).
- Confirm the device still displays the correct forecast image despite the
  error — if so, this is cosmetic/log-noise; if the screen is stale or wrong,
  it's more serious.

## 2. Uncaught exception on a bad SMHI response → bare 500 to the device

At 2026-07-07 04:05:36–04:06:05, `trmnl-server` threw on every `/api/display`
and `/api/setup` request for about 30 seconds:

```
java.lang.Exception: JSON error (unexpected character): <
	at trmnl_server.smhi$fetch_raw_forecast...
	at trmnl_server.core$forecast_screen...
	at trmnl_server.server$current_image...
	at trmnl_server.server$display_response...
```

The `<` strongly suggests SMHI returned an HTML page (error/rate-limit/outage)
instead of JSON. The device's own log corroborates this from its side:
`"Error fetching API display: 8, detail: HTTP Client failed with error: (500)"`.

It didn't recur in the following 4+ hours of normal polling, so it looks like
a transient SMHI-side hiccup rather than a repeat of the `pmp3g` → `snow1g`
migration already documented in `CLAUDE.md` — but the same failure mode
(SMHI returns something unparseable) could happen again at any time, migration
or not.

**Mitigation already shipped** (2026-07-07, commit `08519eb`): `current-image`
in `src/trmnl_server/server.clj` now catches regeneration failures and serves
the last successfully cached image instead of propagating a bare 500 — a
stale forecast beats none. Only the very first request ever (empty cache) can
still surface the exception.

**To investigate later**:
- Whether this was a one-off SMHI outage or something worth alerting on if it
  recurs (e.g. log a warning count, or page if the cache is serving stale data
  for longer than some threshold).
- Whether SMHI has a documented rate limit we might be tripping — check
  request frequency against `refresh-rate-seconds` (900s) plus the device's
  own retry behavior (it was retrying every ~2s during the failure window,
  which may have compounded whatever SMHI was doing).
