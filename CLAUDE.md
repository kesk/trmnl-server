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

# Serve live forecast screens to real TRMNL OG devices over HTTP (see
# trmnl-server.server below). Listens on $PORT or 8080. Each display's location comes
# from the device registry, devices.edn ($DEVICES_FILE). You don't have to write that file
# by hand any more: a display that polls provisions itself, shows an id on its own screen and
# is listed on / — clicking it opens the form that says what it is and where (see the registry
# forms under trmnl-server.server below). That writes the file and takes effect without a
# restart. Hand-editing still works and still needs one. $ID_SALT_FILE (default id-salt)
# holds the key those ids are derived from. $FORECAST_HOURS (default 23) is the server-wide
# fallback for entries that don't set :hours. $ADMIN_PASSWORD_HASH is the login for the human
# pages (/ and /devices/<id>[/archive]); unset — the usual case when running from source — leaves
# them open and says so at startup. $ADMIN_TRUST_LAN=true exempts the home network from
# that login (off by default), and $ADMIN_REQUIRE_TLS_LOGIN (on by default) refuses to
# accept a password over an unencrypted connection.
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
# and restart it — a babashka script, not a JVM Clojure one (see deploy.clj).
# Never ships a secret: the device registry (devices.edn, below) and the admin password
# (admin.env, see deploy/admin.env.example) both live only on the Pi. Deploy just warns
# when the target has neither.
bb deploy.clj

# Same, but also push the local devices.edn to the Pi. NOT the default: the local copy
# is a dev registry with placeholder MACs, and clobbering the real one would point both
# displays at the wrong forecasts. Without the flag, deploy only warns if the Pi has no
# registry at all. Since displays now provision themselves and are configured through the
# web pages — which write the registry *on the Pi*, with tokens issued there — the Pi's copy is the
# authoritative one, so this refuses outright when the target already has a registry.
# It's for seeding a target that hasn't got one; --force overrides it.
bb deploy.clj --devices

# Deploy to the *test* instance instead: a second, independent service on the same Pi
# (~/trmnl-server-test, port 8081, unit trmnl-server-test, see
# deploy/trmnl-server-test.service). Own working directory, so own devices.edn, admin.env,
# archive/ and logs/ — nothing is shared with the live service but the host. It exists to rehearse
# a change against a real display before it touches the one on the wall; registering a
# device is the case that can't be tested any other way. Combines with --devices.
bb deploy.clj --test

# Set the admin password for the human-facing pages. Prompts with echo off, hashes the
# password through the server's own code (salted PBKDF2), writes ~/trmnl-server/admin.env
# on the Pi with umask 077 and restarts the service. The plaintext never reaches the Pi,
# a command line, or the terminal — only the hash does. --test targets the test instance;
# --print just prints the ADMIN_PASSWORD_HASH= line and changes nothing. Setting a
# password logs out every existing session (fresh salt → new session signing key).
bb set-password.clj
bb set-password.clj --test
bb set-password.clj --print

# What that script shells out to: reads one password from *stdin* (never argv, which `ps`
# would expose) and prints the admin.env line. The hashing lives in server.auth so the
# code that writes hashes and the code that checks them can't drift.
printf 'my password' | clojure -M -m trmnl-server.main --hash-password

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

Two docs sit alongside this one: **`KNOWN-ISSUES.md`** is the backlog of weaknesses in this
repo's own code (duplication, dead code, stale docstrings — each with file/line and a
suggested fix), plus a log of what's been resolved and why; **`DEVICE-ISSUES.md`** covers
device/firmware and upstream-API problems seen in production, which are not this codebase's
bugs. Check the former before starting a cleanup — the thing you noticed is probably already
written up there. The latter is mostly *resolved* entries kept as a permanent record of how
the firmware behaves, and several are cited by number from docstrings in `src/`, so its
numbering is load-bearing: append rather than renumber.
The only build step is the uberjar target in `build.clj` (via `tools.build`), used solely to
produce a self-contained jar for deployment. Deployment itself (`deploy.clj`) is a babashka
script that shells out to `clojure -T:build uber` rather than requiring `build.clj` in-process,
since `clojure.tools.build.api` is JVM-only and unavailable under babashka —
this is otherwise a `deps.edn`-only exploratory project (no Leiningen).

## Architecture

Fourteen namespaces, cleanly separated by concern. Six are the domain (`image`, `smhi`,
`demo`, `labels`, `core`, `main`); `server` and the seven `server.*` ones under it are
the serving path, which only `--serve` exercises.

**The server is multi-device.** One Pi serves several TRMNL displays, each with its own
location, and everything in the serving path is scoped by a device `:id` — its render
cache and regeneration lock, its `archive/<id>/` subdirectory, its `logs/<id>/`
telemetry, and its `/images/<id>/…` and `/devices/<id>/…` URLs. The domain
namespaces are untouched by this: they already took location and
points as arguments, so nothing below `server.*` knows there is more than one display.

**A display's `:id` and its `:name` are two different fields, and the split is the whole
point of the `:id`.** The `:id` is the identity — every URL segment, every directory name,
every in-memory cache key. The `:name` is a label, read only by `server.pages` and shown
to a human. That exists so a display can be **renamed**: call it "Hallway" today and
"Kitchen" tomorrow, and its bookmarks, its `archive/` and `logs/` directories, and its
wake-time history all stay exactly where they were. The corollary is that an `:id` is
chosen once and never changed — changing one moves that display's directories on disk and
breaks every link to it, which is precisely the pain the field was added to remove. Only
`:id` is validated (`[a-z0-9-]+`, unique, at load); `:name` is free-form and non-blank,
because it reaches nothing but hiccup, which escapes it. Two displays sharing a `:name`
is a `WARN`, not a refusal — it's now merely confusing, since nothing is keyed on it.

**The whole codebase is reflection-free** — `(set! *warn-on-reflection* true)` plus a
`require :reload` of any namespace must stay silent. On the Java2D-heavy fns that means
a `^Graphics2D`/`^BufferedImage` hint on the destructured canvas binding; for one-off
calls on an untyped value, the Clojure 1.12 qualified-method form (`Font/.deriveFont`,
`BufferedImage/.createGraphics`). It isn't just tidiness: `->1-bit` runs two Java2D calls
per pixel over 384k pixels, and reflection there cost ~160ms per render (12x the hinted
version, and 3.6x the entire screen composition).

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
  :precip-chance :precip-mm :cloud-cover}` map. A non-2xx status, or a body that
  won't parse, throws `ex-info` with `{:url :status :body}` (the body truncated to a
  whitespace-collapsed 200-char snippet) rather than letting SMHI's HTML error pages
  reach the JSON reader as a bare `unexpected character: <` — see DEVICE-ISSUES.md, this
  is a failure that recurs. Which is also why `fetch-raw-forecast` retries: 3 attempts,
  1s then 2s apart, but only for the transient shapes (`retryable?` — network/timeout,
  429, 5xx, or an unparseable 2xx), never for a plain 4xx, and never past a 15s total
  budget so a hung SMHI can't hold a device poll open for three 10s timeouts. Short
  outages are absorbed here; longer ones fall through to `server.render`'s stale cache.
  Also owns the `symbol_code` → text mapping (1–27) and
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
  turns that into positions is `labels` above). Where those things sit on the panel is
  `screen-geometry`: one map holding the cloud/precip strip positions plus the
  `:chart-box` and `:keep-out` rects derived from them. It's a `def` rather than
  `let` bindings inside `forecast-screen` because `clojure -M:check-labels` reads it —
  the checker used to restate the box and bands, they drifted, and it spent a while
  quietly checking a chart that isn't rendered anywhere (see the resolved entry in
  KNOWN-ISSUES.md). Change the layout here and the check follows.
  `default-forecast-hours`/`default-forecast-location` are
  the single source of truth for "prognosis length" and "where" — callers override
  them via `--hours`/`--lat`/`--lon` (main) rather than hardcoding a point count or
  coordinates themselves. **`even-label-hours`** is the rule behind that 23 rather
  than the value: the point counts `hour-axis-labels` can label at even spacing, i.e.
  those where `(hours - 1)` divides by `(dec axis-label-count)` — `[12 23 34 45 56]`,
  derived from the label count so the two can't drift. 24 is the instructive near
  miss (ten 2-hour gaps and one 3-hour one). It's what the registry forms offer in
  their Hours dropdown and **not** a validation rule: `entry-problems` still takes
  any positive integer, and `--hours`/`$FORECAST_HOURS` are unconstrained. Note also
  that every x-coordinate on the screen comes from a point's *index* (`idx->x`,
  five copies of the same linear expression), so the chart assumes points are evenly
  spaced **in time** — safe at 23, but if SMHI's series coarsens to 3-hour steps past
  a day, a long count would plot those at the same pitch as hourly ones. Verify the
  breakpoint against the live API before relying on 45 or 56.

  The server no longer reaches for
  `default-forecast-location` at all: every display's coordinates are required fields
  of its `devices.edn` entry. `$FORECAST_HOURS` survives as the server-wide fallback
  for an entry that omits `:hours`.

- **`trmnl-server.server`** — the HTTP surface only: routing, response helpers, the
  device API, and `start!`. It implements the small API a real TRMNL OG device
  polls when pointed at a custom server: `GET /api/display` (the main poll, returns
  JSON with an `image_url`/`filename`/`refresh_rate`), `GET /api/setup` (first-boot
  registration), `POST /api/log` (device telemetry, replied to with `204`), and
  `GET /images/<id>/*` (serves the rendered PNG bytes, and notes when the fetch is the
  display's own — that's where `/` learns what is on each panel). Plus `GET /health` (a bare
  200 for `deploy.clj`'s post-restart check — `/api/display` can't serve that job any
  more, since it now 404s a caller it doesn't recognise) and three human-facing pages:
  `GET /` (the index — a card per registered display showing the screen it last
  downloaded, each
  one linking into that display's status page, which links back),
  `GET /devices/<id>` (per-device battery/firmware/awake-trend/deployed-commit/
  forecast-health/device-log dashboard — the display's *own* page, not a `/status` leaf
  under it, so the URLs nest into a hierarchy) and
  `GET /devices/<id>/archive` (a gallery of that device's rolling 24h image archive), with
  `GET /devices/<id>/archive/<file>`
  serving each archived PNG (or its `.edn` sidecar) off disk — all three behind the admin
  password (`GET`/`POST /login`, `POST /logout`, see `server.auth`).

  **Displays are configured from the web**, which is the one place this server writes
  anything a human typed: `GET`/`POST /devices/<id>/configure` fills in the `:name` and
  `:lat`/`:lon` of a display that is already polling and waiting (they're listed at the
  bottom of `/`, each showing the id it is printing on its own screen), and
  `GET`/`POST /devices/<id>/edit` changes those same fields afterwards. Both write
  `devices.edn` through `server.devices` and swap the in-memory registry, so **no restart
  is involved** — a provisional display polls every 5 seconds, so it picks up its first
  forecast within seconds of the form being submitted. An edit calls `render/forget!` —
  the render cache is keyed by `:id` and knows nothing of the location that produced its
  entry, so a display moved to another town would otherwise be served the old one's
  forecast for the rest of the TTL.

  **The configure form issues nothing.** The `:id` and the `:token` already exist — the
  display was handed both at first contact and has been using them since — and
  `devices/configure!` carries them across unchanged. That is deliberate and load-bearing:
  the previous design minted a token when a human registered a display, and since the
  firmware only calls `/api/setup` when it has *no* stored key, any display that had ever
  been paired could never learn the new one. It presented the old token forever and was
  refused forever. Nothing reachable from a browser mints a token now.

  The password is still what makes any of this legitimate, and the ordering matters: the
  registry was deliberately read-only for as long as these pages were open to anyone who
  could reach the server (see `server.devices`), because a write endpoint behind no
  authentication is not a thing to build. The two POSTs carry a **same-origin check**
  (`same-origin?`) on top of the session, because the session isn't always the thing
  standing in the way: under `$ADMIN_TRUST_LAN` a LAN request needs no cookie at all, so
  without it a page open in a browser on the home network could configure a display. A
  missing `Origin` is allowed through — that's a non-browser client, which has no ambient
  credentials to borrow. What is deliberately *not* offered is deleting a display, changing
  an `:id`, or rotating a token: each of those strands or moves something on disk
  (`archive/<id>/`, `logs/<id>/`, a display's stored credentials), so they remain a hand
  edit and a restart. Deleting is now recoverable, though — a display removed from the file
  re-provisions itself with the same derived id and the token it still holds, so it comes
  back rather than needing its credentials wiped.

  **The display is a path segment, not a `?device=` filter.** It identifies which pages
  these *are*, so an unknown id is a 404 from the router (`device-page-response`
  resolves it once and hands the entry to the page fn) rather than a fallback inside each
  page — the old query form silently rendered the *first* display's data at a URL that had
  asked for another's. `?day=` on the device page stays a query parameter, because it
  picks between days of the same page rather than naming a different one. The segment is
  the registry's `:id` — never its `:name`, so a rename doesn't break every link (see the
  id/name split above), and never the MAC, because `/api/setup` issues a token on
  presentation of a registered MAC alone, so it stays out of reach even past the login. It's
  safe to interpolate for the same reason it's safe as a directory name: `devices`
  validates it to `[a-z0-9-]+` at load.

  There are **no `/status` or `/archive` compatibility routes**, and `?device=` is gone
  from the codebase entirely — this server has one user, who said so. They existed briefly
  as 303s; deleting them took the human side down to two routes (`/` and `/devices/*`) and
  left `query-param` with only `?day=` and `?next=` to read. Don't reintroduce them for
  tidiness: `/` is the entry point.

  The one 303 that *is* there is **`/devices` → `/`**, and it's a different thing from
  those: not a stale path kept alive, but the collection URL of a live hierarchy. `/` is
  already the list of displays, so truncating `/devices/<id>` back to its parent should
  land on it rather than 404. It redirects rather than serving a second copy of the index,
  so that page keeps one canonical URL and `crumbs` has a single `/` to point at — a page
  reachable at two URLs would have a breadcrumb linking to itself under another name. It
  sits **outside** the admin gate, alone among the human paths: the response is a constant,
  so it discloses nothing a login would protect, and gating it would only mean logging in
  to be told where to go.
  **No page has a device picker any more** either — `/` is the index and the only switcher,
  and a second one on every page was just a different way to do the same thing.

  **The routes nest, and `pages/crumbs` is what walks them back up**: `/` → `/devices/<id>`
  → `/devices/<id>/archive` → one archived screen. Making the dashboard the display's own
  page rather than `/devices/<id>/status` is what buys that — a breadcrumb needs each
  ancestor to be a real page, and a bare `/devices/<id>` that 404s isn't one. The
  breadcrumb *is* the heading (ancestors as muted links, current page as the `h1`, all one
  size), which is why there's no separate title line and no per-page back-links: those
  were three different hand-rolled chains ("← All displays", "← status · all displays")
  that each page had to get right on its own.

  Uses `http-kit` as both the Ring
  request/response convention and the embedded server (handlers are plain
  `(fn [request] response-map)` fns dispatched on `:request-method`/`:uri` in
  `handler`) — chosen over a Ring+Jetty stack for a single, self-contained
  dependency given how few routes there are.

  **A routing library has been declined twice**, most recently after the login routes
  landed, so don't re-derive it: the second look asked whether `reitit` would simplify the
  session handling, and the answer is that it's a *router* — every subtle line in
  `server.auth` (the HMAC token, the conditional `Secure`, the throttle, `safe-next`)
  would be identical under it. What it would buy is route-data middleware, so the gate
  couldn't be forgotten on a new route; what it costs is `reitit-core` + `meta-merge` +
  all of `ring-core` (commons-io, commons-fileupload2, crypto-random/equality) for 13
  routes whose gated group is visible in one `cond`. `ring-core`'s session middleware was
  weighed too and is a worse fit than what's here: its cookie store generates a *random*
  key per boot unless given one, so every deploy would log you out. What did earn its
  place is **`ring/ring-codec`** — see `server.auth` below.

  **Device identity and auth.** Every device route resolves the firmware's `ID` header
  (the MAC) through `server.devices`. A *configured* display's `Access-Token` is checked
  against its registered `:token` and a mismatch is a 401; a display that hasn't been
  configured yet is provisioned instead, and whatever token it presents is adopted (see
  below). A MAC-shaped `ID` is required throughout — anything else is a 404, which is what
  keeps an internet-reachable pair of routes from being filled with invented identifiers.

  **A display provisions itself on first contact.** `/api/setup` never refuses a
  MAC-shaped `ID`: it derives an `:id`, mints a token, hands both back with `status: 200`,
  and the display stores them permanently and starts polling. `/api/display` then answers
  `"status": 202` until somebody configures it. That is the firmware's own "known but not
  claimed" signal — it paints its Friendly ID screen from the id and `message` it got at
  setup, and drops its sleep to `SLEEP_TIME_WHILE_NOT_CONNECTED` (5s), so the id sits on
  the panel and the first forecast lands seconds after the form is submitted. **We render
  nothing for any of this**; the screen is composed on the device.

  Two things about that screen are the firmware's and not ours: its text is hardcoded to
  "Please visit trmnl.com/start with Friendly ID &lt;id&gt;" (the `message` we send at
  `/api/setup` is passed to that case and never used), and the id it prints is whatever the
  device has *stored*, which it only ever learns from `/api/setup`. So a display that was
  paired before — under an older scheme here, or with trmnl.com — goes on showing its old
  id while `/` shows the derived one, and the read-and-click handshake breaks. A soft reset
  on the device clears it; see DEVICE-ISSUES.md #5, and the WARN `provision!` logs when a
  display turns up already holding credentials.

  **Both of those statuses are fields in the JSON body, and the HTTP status must be 200.**
  `fetchApiDisplay` checks the HTTP code before reading anything and accepts only 200, 301
  and 429 — anything else is `HTTPS_RESPONSE_CODE_INVALID`, which paints `WIFI_INTERNAL_ERROR`
  ("WiFi connected, but API connection cannot be established"). Only past that gate does
  `bl.cpp` parse the payload and switch on `request_status = apiResponse.status`. So a real
  HTTP 202 never reaches the 202 branch it was meant to trigger — measured on a real display,
  which sat on that error screen. The transport says "I answered"; the body says what the
  answer was. `/api/setup` has the identical split (`parseResponse_apiSetup` requires body
  `status` 200), and it is the same trap twice.

  `image_url` in the setup response is fetched and stored by the firmware as a *logo*
  (`downloadSetupImage` → `writeImageToFile("/logo.png", …)`), not shown as a screen, so
  what it has to do is resolve — a failed fetch paints `API_IMAGE_DOWNLOAD_ERROR`. We point
  it at `/images/setup-logo.png` (the bundled SMHI wordmark). Note the firmware then draws
  the screen with `storedLogoOrDefault`, which reads flash and never reads that file back,
  so the visible logo is TRMNL's; ours is a bet on a future firmware. The one thing we do
  control on that screen is `message`, which carries the address to configure the display
  at.

  **Never answer 404 from `/api/setup`**, whatever the MAC. The firmware reads the HTTP
  status without parsing the body and treats 404 as `MAC_NOT_REGISTERED`: it paints a bare
  logo, stores a 15-minute sleep, and calls `goToSleep()` *inside that branch*, so
  `downloadAndShow()` never runs. That cost a real display an evening — see
  DEVICE-ISSUES.md #2, which also records the earlier claim (that `bl.cpp` "runs
  `downloadAndShow` unconditionally after `getDeviceCredentials`") being simply wrong.

  This replaced a scheme where the server rendered an 800x480 screen showing the display's
  **MAC**, for a human to copy into an onboarding form. Three things were wrong with it and
  are worth not rebuilding: it needed `/api/setup` to fail in a very particular way to be
  reachable at all; the MAC is the one credential `/api/setup` accepts on its own, so the
  scheme's whole premise was to publish one on a wall; and registering minted a *fresh*
  token, which locks out any display that already holds one (see `server.devices`).

  `/api/setup` is the one route authenticated by MAC alone, because it's
  precisely the request a device makes *before* it has a token. Its response **must**
  carry `"status": 200` — the firmware's `parseResponse_apiSetup` bails otherwise and
  never persists `api_key`/`friendly_id`, which is why an earlier version of this server
  left the device re-running setup on every wake, forever tokenless.

  **`base-url` is derived per request** from `Host`, honouring `X-Forwarded-Proto`,
  rather than being fixed to a LAN IP at startup: one display reaches the Pi over the
  LAN and the other through a Cloudflare tunnel, and each has to be handed the URL that
  works for it. It has to be exactly right, too — the firmware only attaches its `ID`
  and `Access-Token` to the *image* fetch when the image URL string-prefixes the base
  URL it was given, so a scheme mismatch silently drops those headers.

  **The server is internet-reachable** at `https://trmnl.kluft.io` via a Cloudflare
  tunnel (`cloudflared` on the Pi, fronting `localhost:8080`), alongside the LAN address
  the hallway display keeps using. `cloudflared` preserves `Host` and sets
  `X-Forwarded-Proto: https`, which is what makes the per-request `base-url` above come
  out right for both routes — verified: the same poll returns an `https://trmnl.kluft.io/…`
  image URL through the tunnel and an `http://192.168.86.232:8080/…` one over the LAN.

  The human pages (`/`, everything under `/devices/`, and the archived files there) sit
  behind **one admin password and a signed session cookie** — `server.auth`, wired in at
  `handler` via `gated`. Which is what being internet-reachable bought: they were
  deliberately open before, on the grounds that they exposed only read-only telemetry, and
  a login was still cheaper than Cloudflare Access and didn't need the device routes carved
  out of a hostname policy. **That "read-only" premise is now gone** — the registry forms
  write `devices.edn` — which is why the gate had to come first and the forms second, and
  why the no-password fallback (no `$ADMIN_PASSWORD_HASH` → no gate, a startup warning, an
  "auth disabled" pill) means more than it used to: it now leaves the forms open as well,
  so someone reaching the server could point the hallway display at another town. It stays
  a legitimate state — running from source has no password and the forms have to work
  there, and a LAN-only deployment with no tunnel was the original setup — but "auth
  disabled" on a machine anyone else can reach is now worth acting on rather than noting.
  The device API is what makes this a two-scheme server rather than a one-scheme one:
  `/api/*`, `/images/*` and `/health` **must stay outside the gate**, because a display
  cannot do an interactive login — they authenticate by registered MAC + `Access-Token`
  instead. What the pages deliberately **never** show is a device's **MAC** — not even for
  a display waiting to be configured, which is listed by its derived `:id` instead. That
  matters because `/api/setup` hands out a token on presentation of a MAC alone, so it stays
  out of reach even for someone who gets past the login. The old onboarding flow did print
  unregistered MACs, on the grounds that one without a registry entry was worth nothing;
  provisioning removed the need and the exception with it.

  The traversal guards live here, in the
  namespace the untrusted URI arrives in: `archive-file-response` constrains the name to a
  flat `forecast-*.{png,edn}` basename, `?day` is validated in `pages/status` against
  the days actually on disk, and every `<id>` path segment is resolved through the
  registry — whose `:id`s are themselves validated to `[a-z0-9-]+`
  at load. So no route can be walked out of its directory.

  The work behind the routes is seven namespaces under `trmnl-server.server.*`, none of
  which know about HTTP:

- **`trmnl-server.server.devices`** — the device registry, and the only namespace that
  knows a MAC address from a display. A display is in one of **three states**, and only the
  last is on disk:

  - **unknown** — never seen, or long gone.
  - **provisional** — has an `:id` and a `:token` and is polling, but nobody has said what
    it is or where. Held in the in-memory `provisional` atom, capped at 5 with a 30-minute
    TTL on the last sighting.
  - **configured** — an entry in `devices.edn`: MAC → `{:id :name :lat :lon :token :hours?}`.

  Read at startup from `devices.edn` (`$DEVICES_FILE`) into an atom. A *missing* file is an
  ordinary state now rather than a warning — a server that has never been configured still
  provisions whatever polls it and offers it on `/`; a *malformed* one throws and takes the
  service down with it, on the grounds that a display silently served the wrong town's
  weather is the worse outcome.

  **The `:id` is derived from the MAC**, as six characters of `HMAC-SHA256(salt, MAC)`
  mapped through an alphabet with the look-alikes (`0/o`, `1/l/i`) removed. Derived rather
  than stored because the device never tells us its id — `buildDisplayHeaders` sends the MAC
  and the token and nothing else — so an id kept only in memory would be lost on restart
  while the display went on printing it. Recomputing always agrees with the screen, which is
  the whole handshake. It also means a display removed from the file and re-provisioned gets
  its *old* directories back rather than orphaning them.

  Keyed by a salt (`id-salt`, `$ID_SALT_FILE`, 16 bytes, created on first run) because a
  bare MAC hash is brute-forceable — a known OUI leaves ~16M candidates — and ids are
  visible on the ungated `/images/<id>/…` route, so recovering a MAC from one would hand
  over a display's token. Two consequences: the live and test instances have separate salts
  and so disagree about ids for the same display, and losing `id-salt` re-ids every
  *unconfigured* display (configured ones carry their id in the file). Deriving from the MAC
  is not what the `:id`/`:name` split forbids — that rule is about deriving an id from a
  mutable *label*; a MAC is immutable hardware identity, so there is no rename to be
  confused by.

  **A token is minted once per MAC, by `provision!`, and never rotated.** `generate-token`
  (`SecureRandom`, 24 bytes, URL-safe base64) is called from exactly one place — a MAC's
  first `/api/setup` — and nothing reachable from a browser calls it at all. When an
  unconfigured MAC presents an `Access-Token`, that token is *adopted*: a provisional
  display re-teaches the server its own credential after a restart, and nothing is protected
  before configuration anyway. Once configured, the check is strict again.

  `configure!` is what writes the file, carrying the provisional `:id` and `:token` across
  untouched; `update!` changes `:name`/`:lat`/`:lon`/`:hours` afterwards. Both return
  `{:device …}` or `{:errors [msg …]}` — bad input is an ordinary answer to a form, not an
  exception — and both validate through the *same* `entry-problems` that `load!` throws on,
  which is what stops a form accepting an entry the next restart would refuse. (Provisional
  entries never reach the file, so `entry-problems` still requires every field; there is no
  half-configured shape on disk.)

  The whole file is rewritten on each change (so hand-written comments don't survive) and
  the atom is replaced, in that order: a failed write leaves memory and disk still agreeing.
  The write is atomic (temp file in the same directory, `ATOMIC_MOVE` over the old one) and
  owner-only — `Files/createTempFile`'s POSIX permissions carry over the rename — which both
  files written here want: one holds every display's token, the other the id salt. Mutations
  hold a lock so two submissions can't interleave a read-modify-write. The output is
  *generated to look hand-written* — fixed key order, aligned values, entries sorted by
  `:id` — because it is still a file meant to be opened and edited, and a diff of two
  server-written versions should show only what changed.

  `:id` is validated against `[a-z0-9-]+` **at load**, and that one check is what makes
  everything downstream safe: the id is a URL segment (`/images/<id>/…` and every human page
  under `/devices/<id>/…`) and a directory name (`archive/<id>/`, `logs/<id>/`), none of
  which then need their own sanitising. `:name` gets no such rule — it's a free-form label
  that reaches only hiccup, which escapes it — so the two clashing is a `WARN` while two
  clashing `:id`s refuse to load. A derived id can't collide in practice (31⁶ ≈ 887M) and
  can't be retried if it did, since it's a function rather than a draw; `configure!` refuses
  the clash and a hand-written `:id` remains the way out.

- **`trmnl-server.server.aliases`** — readable symlinks beside the id-named directories:
  `archive/Köket -> k9jx8v`, and the same under `logs/`. It exists because a web-registered
  display's `:id` is generated, which is right for a permanent identifier and useless to
  someone reading a directory listing over ssh. A symlink squares that circle: the id keeps
  naming the thing, and the name — free to change — names a pointer that is simply recreated
  on a rename. **That's why deriving this from `:name` is safe when deriving an `:id` from it
  isn't**: a link is disposable, so it can follow a rename that a directory full of history
  can't.

  This is the **one place a `:name` reaches the filesystem**, which cuts against the rule
  that makes every other path safe (only `:id` is validated, at load, so nothing downstream
  sanitises anything). So `link-name` does that job explicitly and conservatively: runs of
  anything outside letters/digits/dot/dash/underscore collapse to a dash, leading dots and
  dashes are stripped — which disposes of `.`, `..` and hidden files by turning them into
  nothing — accents are kept (`Köket` stays `Köket`, these filesystems are UTF-8), and the
  result is capped at 64 chars. A name that reduces to another display's `:id`, or to its
  own, gets no link at all; those comparisons are **case-insensitive**, because one of the
  two filesystems involved is — a case-sensitive test gives a pointless `Hallway -> hallway`
  on the Pi's ext4 and a `FileAlreadyExistsException` on APFS. Two displays reducing to the
  same link name is a `WARN` and one link, matching the registry's existing stance on
  duplicate `:name`s.

  Only symlinks whose target is a **plain sibling name** are ever deleted (`ours?`), so no
  amount of misbehaviour here can remove a directory holding a display's archive, or a link
  somebody else put there. A link whose target is no longer a registered display is
  deliberately **kept**: after a display is removed from `devices.edn` by hand, that link is
  the only thing on disk still explaining what the generated-id directory was. It yields the
  moment a live display wants that name, so keeping it can't block anything. `sync!` is
  idempotent and runs from `server`'s `sync-aliases!` at startup (which also picks up a
  rename made by hand in the file) and after every registry change. Best-effort throughout:
  a filesystem that won't do symlinks costs a `WARN` and nothing else, since no part of
  serving a screen depends on these.

- **`trmnl-server.server.auth`** — the admin password gate on the human pages. One
  password, no users, **stored salted and hashed**: `$ADMIN_PASSWORD_HASH` holds a
  self-describing `pbkdf2-sha256$<iterations>$<salt>$<hash>` string, read once at startup.
  An env var rather than a file because the systemd unit is checked into this repo and
  overwritten on every deploy, so the secret has to live where `deploy.clj` never treads
  (`deploy/admin.env.example` → `~/trmnl-server/admin.env`, pulled in by the unit's
  optional `EnvironmentFile=-`); `EnvironmentFile` rather than `Environment=` in the unit
  because **`systemctl show` prints `Environment=` values to any local user** — verified
  on the Pi — while it shows only the *path* of an environment file.

  **Set it with `bb set-password.clj`**, never by hand. The password is read with terminal
  echo off, piped on **stdin** into `main --hash-password` (an argv is visible in `ps`),
  and only the hash travels to the Pi — the plaintext never reaches it at all. `auth`
  owning `hash-password` is what keeps the writer and the checker on the same algorithm.

  **The iteration count is 100k, deliberately under OWASP's 600k**, because the verifier
  is a Raspberry Pi 3 from 2016: measured there, 600k costs **5.4s** per login (and per
  unauthenticated request the throttle lets through), 100k costs ~1.1s. What carries the
  security is the script's 12-character floor, not the iteration count — a random password
  that long is uncrackable offline at any count, and a weak one wouldn't be saved by 600k.
  The count lives *in* the hash, so faster hardware can raise it without invalidating
  what's set.

  **The login is only required from the internet** (`$ADMIN_TRUST_LAN=true`, set in the
  unit; off by default). The decision is made on the request's **TCP peer address**, never
  on a header — a spoofed source address can't complete a handshake, whereas
  `X-Forwarded-Proto` and `Host` are Cloudflare's to set, and an auth bypass resting on a
  third party's header hygiene is not a thing to build.

  **Loopback is deliberately untrusted, and that inversion is the whole subtlety.**
  cloudflared runs on the Pi and proxies to localhost, so every request from the public
  internet arrives with a peer address of `::1` — measured, by watching `ss` while curling
  the tunnel, not assumed. The reflexive "trust localhost" rule would therefore trust the
  internet and challenge the laptop in the next room. `lan-peer?` matches RFC1918 v4 plus
  IPv6 ULA/link-local and nothing else; anything unrecognised falls through to "ask for a
  password". The pill on `/status` says which way you got in, so an unexpected exemption is
  visible rather than silent.

  **A password is never accepted over cleartext** (`$ADMIN_REQUIRE_TLS_LOGIN`, on by
  default). A POST that isn't TLS and isn't from loopback is refused with 403 *before the
  password is examined* — it doesn't even reach the throttle — and the GET renders the
  reason with **no password field at all**, rather than inviting somebody to type it and
  refusing afterwards. Loopback is exempt so `clojure -M:serve` still works in development;
  a tunnel request is already secure by the header test and never reaches that exemption.
  Trusting `X-Forwarded-Proto` *here* is safe in a way it isn't for authorisation: it only
  decides whether we'll accept a password the caller is already sending, so forging it
  gains nothing. Together with the LAN exemption this is the real prize — the password is
  only ever typed on the tunnel, so it can't cross the WiFi in the clear even by mistake.
  A LAN-only deployment with no tunnel would set `ADMIN_REQUIRE_TLS_LOGIN=false`.

  Three configuration states, not two. **Nothing set → no gate**, with a startup `WARN` and
  an "auth disabled" pill on `/status`: running from source is a legitimate state, the same
  way a missing `devices.edn` is, but a deploy whose env file went missing shouldn't look
  identical to one that never had a password. **Set but unparseable → enabled and broken**:
  the pages stay shut (failing open would silently unprotect what someone meant to
  protect), the service stays up (failing hard would take the *displays* down over a typo
  in an admin password), an `ERROR` goes to the log, and the login page says so — which it
  has to, because `/status` is behind the very gate that's broken. A stale `$ADMIN_PASSWORD`
  from before hashing gets its own warning pointing at the script.

  A session is a **signed cookie, not a session table**: `<expiry>.<hmac>`, HMAC-SHA256
  over the expiry with a key derived from the stored hash string. That choice buys two
  things — logins survive a restart (this service is redeployed constantly, and being
  logged out by every `bb deploy.clj` is how you end up with a four-character password),
  and re-setting the password draws a fresh salt, which invalidates every outstanding
  session with nothing to revoke. Keying on the hash rather than the password is the
  incidental win of hashing at rest: the cookie can't be a password oracle even in
  principle. Both properties are verified end to end — a cookie survives a restart, and
  stops working when the password is re-set to *the same value*.
  30-day expiry, `HttpOnly`, `SameSite=Lax`, and `Secure` **only when the request came in
  over TLS** (`X-Forwarded-Proto`), since the same server is reached over plain http on
  the LAN and an unconditional `Secure` would make the cookie unusable there. Logging out
  is a `POST`, so a link prefetch can't do it. Wrong passwords are counted and throttled
  (5, then one attempt a minute); the counter is **global rather than per-IP** because the
  form is behind a tunnel that rewrites the source address — the trade-off, someone
  hammering the form locking you out of a read-only page for a minute, is the cheaper
  side. `?next=` is constrained to a local path (`safe-next`): a login form is exactly
  where an open redirect gets used.

  Percent-decoding — `form-params` here and `query-param` in `server` — is
  **`ring.util.codec`**, not hand-rolled. That's the one dependency the login work earned
  (8 KB, *zero* transitive deps, unlike the `ring-core` tree behind `reitit`): `?day=`
  had never needed decoding because it's matched against a whitelist
  immediately after, so a missing decode couldn't show — `?next=` is the first parameter
  whose *value* has to survive intact, and the hand-rolled version promptly dropped it on
  the floor. Two behaviours of `form-decode` the callers rely on: a string with no `=`
  decodes to a *string*, not a map (hence the `map?` guards), and a repeated key decodes
  to a *vector*, which `check-password!` and `safe-next` reject by requiring a string.
  Decoding also means `safe-next`'s whitespace check now blocks a real CR/LF, not an
  escaped one — that value goes out in a `Location` header.

- **`trmnl-server.server.render`** — the served screens and their caches, one entry per
  registered display keyed by `:id`, each with its own regeneration lock so a slow
  SMHI fetch for one display can't hold another's poll open (the device waits with its
  radio powered — exactly what `/status`'s wake-time trend is watching for). Keying on
  the device rather than on `[lat lon hours]` means two displays in the same town would
  fetch twice; that's accepted deliberately, since sharing an entry would make it
  ambiguous which device's archive a render belongs to. `current-image` takes a device,
  renders via
  `core/forecast-screen` (fed `core/live-points` of the device's `:lat`/`:lon` and its
  `:hours`, else `$FORECAST_HOURS`, else `core/default-forecast-hours`)
  + `image/->1-bit`, encodes to PNG bytes in
  memory (the served bytes never touch disk — `out/` stays reserved for the
  batch-render modes) and
  caches them for 10 minutes keyed by an MD5 content hash, so the `filename`
  embedded in `/api/display`'s response only changes when the rendered image
  actually changes — which would let the device skip re-downloading identical screens
  between polls. **In practice it never does**, for two measured reasons worth knowing
  before optimising here: the 10-min TTL is shorter than the device's 15-min
  `refresh-rate`, so every device poll lands on an expired cache and pays a full
  SMHI-fetch-plus-render inline (~0.83s on the Pi, vs ~0.07s warm) — the TTL only ever
  benefits browser hits on `/`; and the header's per-render "Uppdaterad HH:mm" stamp
  changes the pixels every time, so the hash always differs and the firmware reports
  `image-cached: false` ("image refreshed" on `/status`) on every poll. Neither is
  obviously worth fixing — see the archive dedupe note below for why the forecast data
  really does change every 15 min, so a stable image isn't available for free.
  `bytes-for` is what enforces that contract on the way back out: it
  serves only the bytes whose hash matches the requested filename, so a cache rollover
  mid-fetch 404s (prompting a re-poll) instead of serving mismatched bytes. On a failed
  regeneration it falls back to the last good image with a stale badge stamped on it
  (see `core/stamp-stale-badge`), and only rethrows when there's nothing to fall back on.
  A failure also stamps `:failed-at`, which holds the next attempt off for a minute
  (`failure-cooldown-ms`): the failed entry keeps its expired `:generated-at`, so
  without that cooldown every incoming request — device poll *and* browser hit on
  `/` — would refetch SMHI while it's already failing. Nothing clears `:failed-at`
  explicitly; the next successful render replaces the whole entry. `cache-status`
  reports that bookkeeping (last success, last failure, consecutive failures) to
  `/status`'s Forecast card, deliberately without calling `current-image` — looking
  at the status page shouldn't fetch SMHI, least of all to regenerate what it's
  reporting on.

  **The cache is not what `/` shows.** Beside it sits a second, smaller record — the
  screen each display actually *downloaded* (`mark-fetched!`/`fetched-entry`, a copy of
  the PNG bytes plus a timestamp, ~15 KB per display). The cache answers "what would we
  serve if asked right now"; that one answers "what is on the panel", and with a 10-minute
  TTL against a 15-minute poll the two disagree for a third of every cycle. It's filled
  only from `server`'s `/images/<id>/…` route, and only for a request carrying the
  display's own `ID` + `Access-Token` (the firmware's `buildImageHeaders`), so a browser
  loading `/` doesn't record itself as the display. Deliberately **in memory only**: it is
  a claim about a panel in another room, and after a restart we don't know what's on it —
  `/` says so rather than guessing (which is what the archive fallback there used to do).
  `forget!` leaves it alone for the same reason: moving a display to another town doesn't
  reach through to the glass, so the old town's screen really is still up there until the
  next poll.

  `bytes-for` searches both, which is what makes that page work — the filename `/` embeds
  is a hash of pixels the cache may already have replaced. It **never regenerates**, and
  couldn't usefully: a new render gets a new hash (the "Uppdaterad HH:mm" stamp guarantees
  it), so it would still miss the filename asked for, having fetched SMHI to find out. That
  is what a browser hit used to do here, one live fetch and a full render per card, and it
  404'd anyway — see the resolved entry in KNOWN-ISSUES.md.

- **`trmnl-server.server.pages`** — the six human-facing HTML pages, built with
  **`hiccup`** (`hiccup2.core`, which auto-escapes string content, so
  there's no hand-rolled `escape-html`) via a shared `page` layout helper. Each page fn
  returns an HTML *string*, not a Ring response — HTTP is `server`'s business, so
  `(pages/status (devices/by-id "hallway") nil :session)` can be called straight from
  the REPL. The two per-display pages take the **resolved registry entry**, not an id:
  the router already had to resolve the path segment to decide between a page and a 404,
  so re-doing it here would mean a second answer to the same question — and it's that
  split that lets them drop the fallback they used to need. Neither carries a device
  picker (see the routing note above). `/` is the index of all of
  them: one card per display showing the screen that display **last downloaded** — what is
  on the panel, not what we would render for it now (see `render/fetched-entry` above) —
  the whole
  card a link into that display's status page — so it doubles as the switcher, and both
  per-display pages link back. A display that hasn't fetched anything since the server
  started gets a placeholder saying so, which is also what every card shows for the first
  minutes after a restart. Its grid is `auto-fit` with the width cap on the
  *card* rather than the track (a `minmax(…,560px)` track would need 1136px before it
  made a second column, and the two displays would stack for good inside the 1120px
  `.wrap`). `device-path` builds every per-display URL, so the shape is written down once;
  it's private, because with the compatibility redirects gone nothing outside this
  namespace constructs one. **This is the only namespace that reads a device's `:name`**,
  and the rule throughout is: hrefs from `:id` (via `device-path`), visible text from
  `:name`. hiccup's escaping is what makes the second half safe now that a label is
  free-form — verified with a `<script>` tag as a device name, which is also what makes
  the *forms* safe, since a rejected submission is re-rendered with what was typed in the
  `value` attributes. `/` also carries the list of displays **waiting to be configured**,
  and only it does: that list is server-wide rather than about any one display, which is why
  the device pages carry no copy of it, and `/` is the page you're on when you plug
  something in. Each row shows the `:id` the display is printing on its own screen — read
  six characters off the panel, click the matching row — and never its MAC.
  `/login` is the password form `server.auth` gates the rest with, plus the "Log out"
  control and the "auth disabled" pill they carry in return. `configure-device` and
  `edit-device` are the two registry forms — the only pages here that ask for something
  rather than report it. Both ask for the same two things (what to call this display, where
  it is, plus an optional forecast length); `configure-device` is the one a provisional
  display gets, and it issues nothing. They share a `field` helper — and a `select-field`
  one, used by exactly one field: **Hours is a dropdown, not a number box**, offering blank
  ("server default") plus `core/even-label-hours`, because every other count gives a
  visibly uneven hour axis and a free box mostly invited typing `24`. It still isn't a
  check (`entry-problems` accepts any positive integer, and a hand-written `devices.edn`
  may say anything), which is why a current value that *isn't* one of the offered counts is
  kept as an option of its own: a `<select>` with no matching option selects its **first**,
  so dropping it would mean opening the edit form on a display set to 24 and pressing Save
  silently reset it to the default. Both take `values`/`errors`, so a
  refused submission comes back with every box still holding what was typed (`errors`
  being non-nil is also what suppresses the prefilled defaults, so a box somebody
  deliberately cleared doesn't silently refill itself); the *validation* is
  `devices/entry-problems`, never here. The edit form shows the display's `:id` as
  static text and its MAC not at all — a MAC is the one credential `/api/setup` accepts on
  its own, so the rule that keeps it off these pages holds even on the page that edits the
  entry it belongs to. Their
  CSS lives in `resources/css/{base,archive,home,login,form}.css` (slurped at load through
  `io/resource`, so it resolves from the uberjar too) rather than inline string
  blobs — `base.css` is the shared shell, with `archive.css`, `home.css`,
  `login.css` and `form.css` layered on top for the pages that need more than it. The `/status` page also shows the **deployed commit** via
  `deployed-version`, read once at load from a bundled `version.edn` that
  `build.clj`'s uber task bakes in (`git rev-parse --short HEAD`, plus a `-dirty`
  suffix when the tree isn't clean, and a build timestamp). Running from source
  (`clojure -M:serve`, no build step) there's no such resource and no commit to report,
  so it shows `dev-local` — there is deliberately **no** git fallback here, since
  shelling out to `git` at load time cost the CLI a ~60s exit hang.

- **`trmnl-server.server.telemetry`** — everything each device reports about itself and
  where it's kept: that device's latest `/api/display` header snapshot
  (`record-poll!`/`poll-status`),
  its rolling wake-time series, and its raw `/api/log` bodies on disk. Every fn takes a
  device `:id`, and on disk that's a subdirectory per device
  (`logs/<id>/device-<date>.log`, `logs/<id>/wake-times.edn`). Subdirectories rather
  than mangled filenames specifically because `prune-logs!` then comes out right for
  free — its cap is a count of files in a directory, so a shared one would let a chatty
  display evict a quiet one's days. See the logging note below.

- **`trmnl-server.server.archive`** — the rolling 24h on-disk archive, as pure storage
  (`dir`, `write!`, `entries`). Every *successful* render (i.e. each new cache entry,
  one per cache miss, not the stale-fallback copies) is also written to
  disk by `write!` as `forecast-<yyyyMMdd-HHmmss>-run<yyyyMMdd-HHmm>-<hash8>.png`
  under `archive/<id>/`
  (relative to the working dir, like `logs/`; override the root with `$ARCHIVE_DIR`) —
  a subdirectory per display, since both the 24h prune and the dedupe probe work off
  the directory's own newest file, so a shared one would let one device's render
  suppress another's as a duplicate. Files older than 24h are pruned by mtime on each
  write — so each folder self-manages a rolling 24h window with no cron. This exists so a problematic screen spotted after
  the fact can still be recovered and saved. `<hash8>` is the first 8 chars of an MD5
  over the *forecast data* (`pr-str` of the point seq) — deliberately **not** the
  rendered pixels, because the header's per-render "Uppdaterad HH:mm" stamp changes the
  pixels on every render and would defeat pixel-level dedupe. The write is **deduped**
  on that data hash: a render whose forecast matches the newest archived file is skipped,
  so the gallery stays a list of *distinct* screens. This dedupe is a **backstop, not the
  common case** — SMHI republishes the point forecast roughly every **15 minutes**, not
  hourly, and each new run genuinely moves the rendered pixels. Measured over a 23.8h
  archive window (2026-08-02): 85 archived screens against ~95 device polls, so dedupe
  suppressed ~10% of writes; re-rendering all 85 with the "Uppdaterad" stamp masked out
  gave 85 distinct images and *zero* identical consecutive pairs. (15 min is an upper
  bound on the republish interval, not a measurement of it — renders only happen once
  per 15-min poll, so a faster rate would be invisible here.) So don't read this passage
  as saying consecutive screens are near-identical: they aren't, which also rules out
  saving device battery by making the served image stable enough for the firmware to
  skip its redraw. The `run<...>` segment is
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
otherwise dependency-light stance (the others being `hiccup` for the `/status` and
`/archive` HTML and `ring/ring-codec` for query/form decoding, see server above),
because file logging on the Pi wanted a real
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
line and appended as **raw JSON** (no timestamp prefix) to
`logs/<id>/device-<yyyy-MM-dd>.log`,
the file picked by the **UTC** date (`today-utc-date`), so the filename does the daily
partitioning a rolling policy used to. The root dir is `$DEVICE_LOG_DIR` (default `logs/`)
and each display gets a subdirectory named for its `:id`, created
on demand; writes are serialised under a private lock and are best-effort (an IO error is
logged via the main logger and swallowed, so the device still gets its `204`). Old days
self-prune: `prune-logs!` (run on each write) keeps only the newest `max-log-files`
(7) `device-<date>.log` files **in that device's own directory** — a count cap, not a
calendar window, so a device that skips
days still retains its last 7 *reporting* days, and no display can evict another's. (That
per-device directory is why the cap is still correct with more than one display; a shared
directory would have made 7 files mean 3½ days each.) This replaced a
logback `DEVICE` appender + dedicated `trmnl-server.device` logger — dropped because once
`/status` had become "just show one on-disk file" (below), logback's rolling/gzip/retention
was the only remaining complexity and the hand-written path is simpler. Two consequences of
the switch: device rows are **no longer echoed to journald** (they live only in the files +
`/status`), and the `DEVICE_LOG_FILE` env var is gone (use `DEVICE_LOG_DIR`).

The device page's **device-log table just shows the contents of one day's file**, for one
display. `telemetry/log-days`
lists that device's `device-<date>.log` files (newest first) as the day-picker strip above
the table;
`?day=<date>` selects one, defaulting to today (`today-utc-date`), which is always shown as a
tab even before it has a file. Which display it is comes from the path, so each day link is
just `/devices/<id>?day=…`. `telemetry/read-log` reads the chosen file (plain read — the DIY
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
which keeps a rolling `wake-history` series of `{:t :ms}` maps per device, **persisted to
disk** as
`wake-times.edn` (in that display's `$DEVICE_LOG_DIR/<id>/`, alongside its device logs) so
the trend survives
restarts/redeploys — `load-wake-history!` reloads every registered device's series in
`start!`. Samples are pruned to a 7-day
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
  Because each axis is fitted to its own data, amplitude is **not** comparable
  between two renders either; only the labels carry real values. `nice-bounds`
  frames tightly (1 unit of padding, rounded to a multiple of 2) so an ordinary
  day uses most of the box, and puts a floor under the *span* rather than under
  the padding — that `:min-span` is the one thing keeping a near-flat day from
  being stretched into a mountain range, so don't trade it away for more travel.
- **Chart labels are positioned in one pass, against real boxes.** `core/chart-labels`
  → `labels/place` walks every label the chart wants in importance order (each series'
  global high/low, which are always drawn, then up to two prominence-ranked extras per
  series) and scores `labels/candidates`' 18 positions for each: above/below its dot,
  the same shifted sideways, the opposite side, level beside it, then displaced with a
  leader — the last four of those reaching a *second* step sideways, which is what a dot
  cornered against the panel edge needs (see the hard rule below). One hard rule and two
  soft ones pick the winner:
  - **hard** — must clear **every dot including its own**, every label already placed,
    the `:keep-out` rects `forecast-screen` passes for what's drawn around the box, and
    the panel. An extra with no such position is dropped, dot and all; a global falls
    back to its least-overlapping candidate rather than go unlabelled — the one path
    that can still put ink on ink. That fallback used to fire for real, roughly once in
    6000 generated days, always the same shape: a wind minimum at the *last* point has
    the keep-out band below it, the panel edge to its right, and the temperature
    minimum's label and dot to its left, so every position was blocked and its box
    landed on that dot. The two double-width leader positions are what resolved it —
    the sweep that found 10 such collisions in 60000 days now finds none, and
    `dev/fixtures/cornered-min.edn` pins the case. Prefer widening the candidate set
    like that over special-casing the fallback.
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
