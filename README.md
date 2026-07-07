# trmnl-server

An exploratory Clojure project that generates a weather-forecast screen for a
[TRMNL](https://usetrmnl.com/) e-ink display (the OG model: 800x480, 1-bit black/white).
It fetches live forecast data from [SMHI](https://www.smhi.se/) (Sweden's meteorological
institute), defaulting to Gothenburg, renders it with Java2D, and can serve it directly
to a real TRMNL device over HTTP.

## Requirements

- [Clojure CLI](https://clojure.org/guides/install_clojure) (`clojure`/`clj`)
- [babashka](https://babashka.org/) (`bb`) — only needed for [deploy.clj](deploy.clj)

## Usage

```bash
# Generate out/preview.png (RGB) and out/preview-1bit.png (thresholded) from a
# live SMHI forecast
clojure -M -m trmnl-server.main
# equivalently:
clojure -M:run

# Render synthetic per-season screens instead of a live fetch — writes
# out/demo-{winter,spring,summer,autumn}(.png|-1bit.png)
clojure -M -m trmnl-server.main --demo

# Override where the live forecast is fetched for (default Gothenburg)
clojure -M -m trmnl-server.main --lat 59.3293 --lon 18.0686

# Serve the live forecast screen over HTTP to a real TRMNL OG device pointed
# at a custom server. Listens on $PORT or 8080, defaulting to Gothenburg unless
# $FORECAST_LAT/$FORECAST_LON are set.
clojure -M -m trmnl-server.main --serve
# equivalently:
clojure -M:serve

# REPL for iterating on drawing/layout code
clojure -M -r
```

Point a TRMNL OG's custom server URL at `http://<this-host>:8080` — the server implements
the `/api/display`, `/api/setup`, and `/api/log` endpoints the device's firmware polls.

## Building and deploying

```bash
# Build a standalone uberjar (target/trmnl-server.jar) via tools.build
clojure -T:build uber
java -jar target/trmnl-server.jar --serve

# Build, ship to the Raspberry Pi running the live server, and restart its
# systemd service (see deploy.clj and deploy/trmnl-server.service)
bb deploy.clj
```

## Architecture

Six namespaces under `src/trmnl_server/`, cleanly separated by concern:

- **`image`** — generic Java2D drawing primitives (text, lines, dots, rects), plus
  conversions from the RGB working canvas to what an e-ink panel needs: `->1-bit`
  (hard threshold) and `floyd-steinberg` (error-diffusion dithering).
- **`smhi`** — HTTP client for SMHI's public point-forecast API, normalizing raw JSON
  into flat forecast points.
- **`demo`** — synthetic per-season datasets in the same shape as `smhi/forecast`, so
  `--demo` can render without hitting the network.
- **`core`** — composes the above into the actual screen (`forecast-screen`) and holds
  the domain-specific layout/chart logic.
- **`server`** — the small HTTP API (`GET /api/display`, `GET /api/setup`,
  `POST /api/log`, `GET /images/*`) a real TRMNL OG device polls, built on `http-kit`.
- **`main`** — the CLI entry point, dispatching to a live render, `--demo`, or `--serve`.

See [CLAUDE.md](CLAUDE.md) for a more detailed guide to the codebase and its design
constraints (this is a 1-bit monochrome display — no color, no gray).

There is no test suite or linter configured; this is a `deps.edn`-only exploratory
project (no Leiningen).
