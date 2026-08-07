(ns trmnl-server.server
  "HTTP surface for the forecast screens: routing, the API a real TRMNL OG device polls
   when pointed at a custom server (GET /api/display, GET /api/setup, POST /api/log),
   the file routes serving rendered and archived PNGs, and the three human-facing pages
   (/, /status, /archive). Uses http-kit as both the Ring request/response convention
   and the embedded server — handlers are plain (fn [request] response-map) fns
   dispatched on :request-method/:uri in `handler`, chosen over a Ring+Jetty stack for
   a single, self-contained dependency given how few routes there are.

   Every device-facing route is scoped to one registered display: the firmware's `ID`
   header (its MAC) resolves through server.devices, and its `Access-Token` — issued by
   /api/setup — is checked on every later request. An unrecognised MAC is refused and
   recorded for /status, which is how a new display gets registered.

   The work behind the routes lives in the five server.* namespaces: devices (the
   registry), render (the screens and their caches), archive (the rolling 24h disk
   archive), telemetry (what the devices report about themselves), and pages (the HTML)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [org.httpkit.server :as httpkit]
            [trmnl-server.server.archive :as archive]
            [trmnl-server.server.devices :as devices]
            [trmnl-server.server.pages :as pages]
            [trmnl-server.server.render :as render]
            [trmnl-server.server.telemetry :as telemetry])
  (:import [java.net Inet4Address NetworkInterface]))

(def ^:private refresh-rate-seconds 900)

;; --- Response helpers -------------------------------------------------------------------

(defn- json-response [body]
  {:status  200
   :headers {"Content-Type" "application/json"}
   :body    (json/write-str body)})

(defn- html-response [body]
  {:status  200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    body})

(defn- png-response [bytes-or-file]
  {:status  200
   :headers {"Content-Type" "image/png"}
   :body    bytes-or-file})

(defn- text-response [status body]
  {:status  status
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body    body})

(defn- query-param
  "The value of query-string key `k`, or nil. Raw — a caller that turns it into a filename
   or a path must validate it (pages/status matches ?day= against the days actually on
   disk, and ?device= against the registry) before trusting it."
  [request k]
  (some->> (some-> (:query-string request) (str/split #"&"))
    (some #(when (str/starts-with? % (str k "=")) (subs % (inc (count k)))))))

(defn- path-segments
  "The non-empty `/`-separated segments of a request path."
  [uri]
  (vec (remove str/blank? (str/split uri #"/"))))

(defn- base-url
  "The externally visible origin of this request, used to build the absolute image_url
   handed to the device.

   Derived per request rather than fixed at startup because the two displays reach this
   server by different routes — one over the LAN, one through a Cloudflare tunnel — and
   each has to be told the URL that works for it. It also has to be exactly right: the
   firmware only attaches its ID and Access-Token headers to the image fetch when the
   image URL string-prefixes the base URL it was given, so a scheme mismatch silently
   costs us those headers. cloudflared terminates TLS but forwards the original Host and
   sets X-Forwarded-Proto: https, so honouring that header is what keeps the tunnel's
   URLs on https. Spoofing it gains an attacker nothing — the worst case is being told
   about an image URL that then doesn't resolve for them."
  [request fallback]
  (if-let [host (get-in request [:headers "host"])]
    (let [proto (or (some-> (get-in request [:headers "x-forwarded-proto"])
                      (str/split #",") first str/trim not-empty)
                  "http")]
      (str proto "://" host))
    fallback))

(defn- image-url [base device-name filename]
  (str base "/images/" device-name "/" filename))

;; --- Device API -------------------------------------------------------------------------

(defn- parse-display-headers
  "Pulls the telemetry the TRMNL firmware sends on every /api/display poll out of the
   request headers (see trmnl-firmware buildDisplayHeaders). http-kit lowercases header
   names; every value is a string, so coerce the numeric ones and let parse-*/nil swallow
   anything malformed (e.g. the OG's -1 or a FAKE_BATTERY_VOLTAGE 4.2). Returns nil when
   no device headers are present, so a browser hitting /api/display doesn't clobber the
   last real snapshot."
  [headers]
  (letfn [(s [k] (get headers k))]
    (when (or (s "id") (s "fw-version") (s "battery-voltage"))
      {:battery-voltage (some-> (s "battery-voltage") parse-double)
       :rssi            (some-> (s "rssi") parse-long)
       :wake-time       (some-> (s "wake-time") parse-long)
       :update-source   (s "update-source")
       :image-cached    (some-> (s "image-cached") (= "true"))   ; tri-state: true/false/nil
       :refresh-rate    (some-> (s "refresh-rate") parse-long)
       :fw-version      (s "fw-version")
       :fw-commit       (s "fw-commit")
       :model           (s "model")
       :received-at     (System/currentTimeMillis)})))

(defn- authorized?
  "Whether a request carries the device's own token. The firmware sends it as
   Access-Token on every request after collecting it from /api/setup."
  [device request]
  (= (:token device) (get-in request [:headers "access-token"])))

(def ^:private unregistered-refresh-seconds
  "How soon an unregistered device comes back. Shorter than the normal 15 minutes so
   that adding it to devices.edn and restarting shows up on the display promptly — this
   is the one situation where somebody is standing in front of it waiting."
  300)

(defn- note-unregistered!
  "Records the MAC of a device we don't have in the registry, so /status can offer it for
   pasting into devices.edn. Logged the first time only: /api/display is reachable from
   the internet and a scanner shouldn't be able to fill the log by varying its ID."
  [request]
  (let [mac (get-in request [:headers "id"])]
    (when (devices/note-unknown! mac)
      (log/warn (str "Request from unregistered device " (pr-str mac) " — add it to devices.edn")))
    mac))

(defn- unregistered-response
  "Reply to an unregistered device on a route that has nothing to offer it."
  [request]
  (note-unregistered! request)
  (text-response 404 "unknown device\n"))

(defn- unregistered-display-response
  "What an unregistered device gets from /api/display: a normal 200 pointing at a screen
   showing its own MAC, rather than a bare 404 that leaves it displaying a firmware
   error.

   The MAC is the one thing needed to register a device and the one thing that's hard to
   come by — it isn't on the case, and the alternative is watching /status for it. The
   firmware reaches this route even when /api/setup has just 404'd (bl.cpp runs
   downloadAndShow unconditionally after getDeviceCredentials), so the screen shows up on
   the next wake."
  [request fallback-base]
  (note-unregistered! request)
  (json-response {:filename          render/unregistered-filename
                  :image_url         (str (base-url request fallback-base)
                                       "/images/" render/unregistered-filename)
                  :image_url_timeout 0
                  :refresh_rate      unregistered-refresh-seconds
                  :reset_firmware    false
                  :update_firmware   false
                  :firmware_url      nil}))

(defn- unregistered-image-response
  "Serves the unregistered-device screen for whichever MAC is asking. The firmware sends
   its ID on the image fetch too (buildImageHeaders), so the MAC never has to appear in
   the URL — and only a MAC that has actually polled gets a render, which is what keeps
   this unauthenticated route from being a way to make the Pi draw 800x480 images on
   demand."
  [request fallback-base]
  (let [mac (get-in request [:headers "id"])]
    (if (devices/seen-unknown? mac)
      (png-response (render/unregistered-image mac (base-url request fallback-base)))
      {:status 404})))

(defn- with-device
  "Resolves the request's `ID` header to a registered device, checks its Access-Token,
   and calls `f` with the device. Unknown MAC → 404 (and recorded for /status); wrong or
   missing token → 401. Every device route except /api/setup goes through here — setup
   is the one request a device makes before it has a token."
  [request f]
  (if-let [device (devices/for-request request)]
    (if (authorized? device request)
      (f device)
      (do
        (log/warn (str "Rejected " (:uri request) " for " (:name device) " — bad Access-Token"))
        (text-response 401 "bad access token\n")))
    (unregistered-response request)))

(defn- display-response
  "The main device poll. An unregistered MAC gets a screen showing its own MAC rather
   than a 404 — see unregistered-display-response; everything else goes through the
   normal token check."
  [request fallback-base]
  (if-not (devices/for-request request)
    (unregistered-display-response request fallback-base)
    (with-device request
      (fn [device]
        (when-let [status (parse-display-headers (:headers request))]
          (telemetry/record-poll! (:name device) status))
        (let [filename (render/serve-filename (render/current-image device))]
          (json-response {:filename          filename
                          :image_url         (image-url (base-url request fallback-base) (:name device) filename)
                          :image_url_timeout 0
                          :refresh_rate      refresh-rate-seconds
                          :reset_firmware    false
                          :update_firmware   false
                          :firmware_url      nil}))))))

(defn- setup-response
  "First-boot registration. Authenticated by MAC alone — this is the request a device
   makes precisely because it has no token yet, so there is nothing else to check it
   with; being in devices.edn is what entitles it to collect one.

   `status` is load-bearing: parseResponse_apiSetup bails unless it is exactly 200, and
   the firmware only persists api_key/friendly_id on that path. Omitting it (as this
   server used to) means the device silently never stores its credentials and re-runs
   setup on every wake."
  [request fallback-base]
  (if-let [device (devices/for-request request)]
    (let [filename (render/serve-filename (render/current-image device))]
      (log/info (str "Setup: issued token to " (:name device) " (" (:mac device) ")"))
      (json-response {:status      200
                      :api_key     (:token device)
                      :friendly_id (:name device)
                      :image_url   (image-url (base-url request fallback-base) (:name device) filename)
                      :message     "Welcome to trmnl-server"}))
    (unregistered-response request)))

(defn- log-response [request]
  (with-device request
    (fn [device]
      (telemetry/append-log! (:name device) (slurp (:body request)))
      {:status 204})))

;; --- File routes ------------------------------------------------------------------------

(defn- image-response
  "Serves one device's currently cached PNG: /images/<device>/forecast-<hash>.png. The
   device segment has to name a registered display and the filename has to match a hash
   the cache is actually holding (render/bytes-for compares rather than reads), so
   neither segment can name anything off-list."
  [uri]
  (let [[_ device-name filename] (path-segments uri)]
    (if-let [device (and device-name filename (devices/by-name device-name))]
      (if-let [bytes (render/bytes-for (render/current-image device) filename)]
        (png-response bytes)
        {:status 404})
      {:status 404})))

(defn- archive-file-response
  "Serves one archived file by name from a device's archive dir — the PNG screen or its
   sibling `.edn` forecast dump, at /archive/<device>/<name>. The device segment is
   resolved through the registry (whose names are validated to [a-z0-9-]+ at load) and
   the name is constrained to a flat `forecast-*.{png,edn}` basename, so neither can
   escape the directory. The `.edn` is sent as an attachment so the gallery's data link
   downloads rather than renders it."
  [uri]
  (let [[_ device-name requested] (path-segments uri)]
    (if-let [device (and device-name requested (devices/by-name device-name))]
      (if-let [[_ ext] (re-matches #"forecast-[0-9A-Za-z-]+\.(png|edn)" requested)]
        (let [file (io/file (archive/dir (:name device)) requested)]
          (if (.isFile file)
            (if (= ext "png")
              (png-response file)
              {:status  200
               :headers {"Content-Type"        "application/edn; charset=utf-8"
                         "Content-Disposition" (str "attachment; filename=\"" requested "\"")}
               :body    file})
            {:status 404}))
        {:status 404})
      {:status 404})))

;; --- Routing ----------------------------------------------------------------------------

(defn- handler [fallback-base]
  (fn [{:keys [request-method uri] :as request}]
    (let [get? (= :get request-method)]
      (cond
        (and get? (= uri "/"))                        (html-response (pages/home))
        (and get? (= uri "/health"))                  (text-response 200 "ok\n")
        (and get? (= uri "/api/display"))             (display-response request fallback-base)
        (and get? (= uri "/api/setup"))               (setup-response request fallback-base)
        (and (= :post request-method)
          (= uri "/api/log"))                         (log-response request)
        (and get? (= uri "/status"))                  (html-response (pages/status (query-param request "device")
                                                                       (query-param request "day")))
        (and get? (= uri "/archive"))                 (html-response (pages/gallery (query-param request "device")))
        (and get? (str/starts-with? uri "/archive/")) (archive-file-response uri)
        (and get? (= uri (str "/images/" render/unregistered-filename)))
        (unregistered-image-response request fallback-base)
        (and get? (str/starts-with? uri "/images/"))  (image-response uri)
        :else                                         {:status 404}))))

(defn- lan-ip
  "Best-effort first non-loopback IPv4 address, for the startup message and as the
   base-url fallback when a request arrives without a Host header. Falls back to
   \"localhost\" if none is found (e.g. no network connection)."
  []
  (or (some->> (NetworkInterface/getNetworkInterfaces)
        enumeration-seq
        (mapcat #(enumeration-seq (.getInetAddresses ^NetworkInterface %)))
        (some #(when (and (instance? Inet4Address %)
                       (not (Inet4Address/.isLoopbackAddress %))) %))
        Inet4Address/.getHostAddress)
    "localhost"))

(defn start!
  "Starts the HTTP server. http-kit's worker threads are non-daemon, so the JVM
   stays alive after this (and -main) returns."
  []
  (let [port     (or (some-> (System/getenv "PORT") Integer/parseInt) 8080)
        base-url (str "http://" (lan-ip) ":" port)]
    (devices/load!)
    (telemetry/load-wake-history! (map :name (devices/all)))
    (httpkit/run-server (handler base-url) {:port port})
    (log/info (str "TRMNL server listening on " base-url))
    (log/info "Point your TRMNL OG's custom server URL to the above.")))
