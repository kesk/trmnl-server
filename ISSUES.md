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

**Hardened since** (2026-07-30), now that it's clear this recurs:
- `smhi/fetch-raw-forecast` checks the status code and throws `ex-info` with
  `{:url :status :body}`, so the log says what SMHI actually answered instead of
  `unexpected character: <`.
- It retries a transient failure (network/timeout, 429, 5xx, unparseable 2xx) 3
  times, 1s then 2s apart, inside a 15s budget — enough to absorb episodes of the
  length seen so far. A 4xx still fails immediately, since that's our URL being
  wrong, not SMHI being unwell.
- `server.render` holds off further attempts for a minute after a failure
  (`failure-cooldown-ms`). Previously the failed entry stayed expired, so every
  device poll *and* browser hit refetched SMHI mid-outage — the most plausible way
  we'd trip a rate limit, if there is one.
- `/status` has a Forecast card: last successful render, and a pill counting
  consecutive failures while the stale badge is being served.

**To investigate later**:
- Whether passive visibility is enough. The card and the on-screen badge both
  require someone to look; an outage lasting hours would still go unnoticed until
  the screen is read. Active notification is the open question, not detection.
- Whether SMHI has a documented rate limit we might be tripping — the cooldown
  bounds our side of it now, but the limit itself is still unknown; check request
  frequency against `refresh-rate-seconds` (900s) plus the device's own retry
  behavior (it was retrying every ~2s during the first failure window, which may
  have compounded whatever SMHI was doing).

## 2. A 404 from `/api/setup` puts a new display to sleep for 15 minutes

First seen 2026-08-08, the first time a **factory-fresh** display was pointed at the
test instance. Expected: the display shows `core/unregistered-screen` with its own MAC.
Observed: a bare TRMNL logo, one request to the server at 22:57:07, then silence.

The server was blameless — replaying both requests by hand returned a valid JSON body
and a correct 800x480 PNG. The behaviour is in the firmware, and it is two facts
combining:

1. `src/api-client/setup.cpp` does not read the response body on a non-200: it returns
   `.response = {.outcome = StatusError, .status = (uint16_t)httpCode}`, so `status`
   is the *HTTP* code and `message` is empty.
2. `performApiSetup` (`src/bl.cpp`) special-cases that value:

   ```c
   else if (url_status == 404) {
     showMessageWithLogo(MAC_NOT_REGISTERED, apiResponse);   // empty message → bare logo
     preferences.putUInt(PREFERENCES_SLEEP_TIME_KEY, SLEEP_TIME_TO_SLEEP);   // 900s
     display_sleep();
     goToSleep();      // never returns
   }
   ```

`goToSleep()` is called *inside* the branch, so control never returns to `setup()` and
`downloadAndShow()` — the call that would fetch our screen — never runs. All three
symptoms follow: the logo (empty message), the single contact (setup only, no
`/api/display`), and the 15-minute gap (`SLEEP_TIME_TO_SLEEP`, not the 300s we send).

**Why it went unnoticed for so long**: a display holding an `api_key` skips `/api/setup`
entirely (`if (!preferences.isKey(PREFERENCES_API_KEY)) getDeviceCredentials()`). Every
display previously paired with anything — trmnl.com, or an earlier run of this server —
therefore goes straight to `/api/display` and gets the MAC screen. The one case that
fails is the new display the screen exists for. Note the corollary: *resetting* a device's
credentials makes this worse, not better.

**Mitigated** the same evening by answering **403** instead of 404, and then **resolved
outright** by the provisioning redesign that followed: `/api/setup` now always answers 200
for a MAC-shaped ID, because an unknown display is provisioned on the spot rather than
refused. There is no longer a status the firmware could special-case us into sleeping on.

The rule the code still has to keep is narrower and worth stating on its own: **never
answer 404 from `/api/setup`**. Anything else the firmware doesn't special-case (it knows
200, 202, 404, 500) merely falls through to `return false`.

## 3. Re-registering a paired display locks it out permanently

Same evening as #2, immediately after. The display was configured, then deleted from the
registry and added again, and every request from it came back 401:

```
23:22:38 Rejected /api/display for dgb7en — bad Access-Token
23:22:40 Rejected /api/display for dgb7en — bad Access-Token   (retrying every ~2s)
```

This one was **our bug, not the firmware's**, and the firmware detail that makes it fatal
is the same one behind #2: `setup()` calls `getDeviceCredentials()` only when the key is
missing.

```c
if (!preferences.isKey(PREFERENCES_API_KEY) || !preferences.isKey(PREFERENCES_FRIENDLY_ID))
  getDeviceCredentials();
```

So a display that already holds a token never asks for another one. Registering it minted a
*fresh* token; the display went on presenting the old one; nothing could reconcile them
short of wiping the device's credentials (which also wipes its WiFi and server URL). The
2-second retry loop meanwhile drains the battery.

**Resolved** by moving token issuance to first contact and never rotating it: a token is
minted once per MAC by `devices/provision!`, and nothing reachable from a browser mints one
at all — `configure!` carries the existing token into `devices.edn` unchanged. An
unconfigured MAC also has whatever token it presents *adopted*, so a display whose entry was
deleted re-provisions itself with the credential it already holds. Delete-and-re-add is now
a recoverable operation rather than a trap; verified end to end, including across a restart.

**To investigate later**: whether the 5-second poll a provisional display uses
(`SLEEP_TIME_WHILE_NOT_CONNECTED`, the firmware's constant, not ours) is worth interrupting
for one left unconfigured for hours. It's the right cadence while somebody is standing
there and a battery drain afterwards.

## 4. The device API's `status` is a body field; the HTTP code must be 200

Found immediately after the provisioning redesign (#3): a freshly reset display, correctly
provisioned by the server, sat on `WIFI_INTERNAL_ERROR` — "WiFi connected, but API
connection cannot be established. Try to refresh, or scan QR Code for help."

The server was answering `/api/display` with a real **HTTP 202**, on the strength of this
branch in `bl.cpp`:

```c
case 202:
  result = HTTPS_NO_REGISTER;
  preferences.putUInt(PREFERENCES_SLEEP_TIME_KEY, SLEEP_TIME_WHILE_NOT_CONNECTED);
```

But that switch runs on `request_status = apiResponse.status` — the **JSON body's** field —
and it is only reached if the HTTP status already passed this gate in
`src/api-client/display.cpp`:

```c
if (httpCode < 0 ||
    !(httpCode == HTTP_CODE_OK ||                // 200
      httpCode == HTTP_CODE_MOVED_PERMANENTLY || // 301
      httpCode == HTTP_CODE_TOO_MANY_REQUESTS))  // 429
  return { .error = HTTPS_RESPONSE_CODE_INVALID, … };
```

`HTTPS_RESPONSE_CODE_INVALID` is what paints that screen. So the HTTP 202 was rejected at
the transport layer and never reached the branch it was aimed at.

**Fixed** by answering HTTP 200 with `{"status": 202, …}` in the body.

`/api/setup` has exactly the same split — `parseResponse_apiSetup` reads the body's `status`
and the client only parses a body at all on HTTP 200 — which makes this the same mistake
twice in one day, in opposite directions: #2 was a *transport* status the firmware
special-cases (404), this was a *body* status sent as a transport one. The rule worth
keeping: **the HTTP status says whether we answered; the body's `status` says what the
answer is.**

## 5. A previously paired display keeps showing its old Friendly ID

Seen right after #4 was fixed, on the same display. The server had provisioned it as
`8j544n` and `/` listed that; the panel said `rmqr5v` — the id this server had given it the
night before, under the earlier random-id scheme.

Same root cause as #2 and #3, once more: the firmware asks for credentials only when it has
none.

```c
if (!preferences.isKey(PREFERENCES_API_KEY) || !preferences.isKey(PREFERENCES_FRIENDLY_ID))
  getDeviceCredentials();
```

A display that already holds a `friendly_id` never learns a new one, and it never tells us
which one it holds — `buildDisplayHeaders` sends the MAC and the token and nothing else. So
the id on the screen and the id on `/` can disagree with nothing to reconcile them, and the
"read six characters off the panel, click the matching row" handshake silently stops
working.

**Not a problem for a new display**, which learns its id from `/api/setup` at first contact.
It only bites a display that was paired before — with this server under an older scheme, or
with trmnl.com. **The fix is a soft reset on the device**, which clears the stored id; it
then re-runs setup and adopts the derived one. Verified: after a soft reset the two agreed,
and configuring from the form started forecasts immediately.

`server/provision!` now logs a WARN naming this case whenever a display is provisioned from
a route *other* than `/api/setup`, since arriving with credentials already is exactly the
signal that its screen may be stale.

**Worth knowing about that screen**: its text is hardcoded in the firmware
(`display.cpp`, `case FRIENDLY_ID`) to "Please visit trmnl.com/start with Friendly ID
&lt;id&gt; to finish setup". The `message` we send at `/api/setup` is passed to that function
and never used, so a self-hosted display will always name trmnl.com. The id is right; the
URL isn't, and can't be from the server side. Rendering our own screen instead of using the
firmware's 202 path is the way out if that ever matters enough.

