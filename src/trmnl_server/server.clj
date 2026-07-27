(ns trmnl-server.server
  "HTTP surface for the forecast screen: routing, the API a real TRMNL OG device polls
   when pointed at a custom server (GET /api/display, GET /api/setup, POST /api/log),
   the file routes serving rendered and archived PNGs, and the three human-facing pages
   (/, /status, /archive). Uses http-kit as both the Ring request/response convention
   and the embedded server — handlers are plain (fn [request] response-map) fns
   dispatched on :request-method/:uri in `handler`, chosen over a Ring+Jetty stack for
   a single, self-contained dependency given how few routes there are.

   The work behind the routes lives in the four server.* namespaces: render (the screen
   and its cache), archive (the rolling 24h disk archive), telemetry (what the device
   reports about itself), and pages (the HTML)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [org.httpkit.server :as httpkit]
            [trmnl-server.server.archive :as archive]
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

(defn- query-param
  "The value of query-string key `k`, or nil. Raw — a caller that turns it into a filename
   must validate it (pages/status does, against the days actually on disk) before trusting
   it."
  [request k]
  (some->> (some-> (:query-string request) (str/split #"&"))
    (some #(when (str/starts-with? % (str k "=")) (subs % (inc (count k)))))))

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

(defn- image-url [base-url filename]
  (str base-url "/images/" filename))

(defn- display-response [base-url request]
  (when-let [status (parse-display-headers (:headers request))]
    (telemetry/record-poll! status))
  (let [filename (render/serve-filename (render/current-image))]
    (json-response {:filename          filename
                    :image_url         (image-url base-url filename)
                    :image_url_timeout 0
                    :refresh_rate      refresh-rate-seconds
                    :reset_firmware    false
                    :update_firmware   false
                    :firmware_url      nil})))

(defn- setup-response [base-url]
  (json-response {:image_url (image-url base-url (render/serve-filename (render/current-image)))
                  :message   "Welcome to trmnl-server"}))

(defn- log-response [request]
  (telemetry/append-log! (slurp (:body request)))
  {:status 204})

;; --- File routes ------------------------------------------------------------------------

(defn- image-response [uri]
  (let [requested (subs uri (count "/images/"))]
    (if-let [bytes (render/bytes-for (render/current-image) requested)]
      (png-response bytes)
      {:status 404})))

(defn- archive-file-response
  "Serves one archived file by name from the archive dir — the PNG screen or its sibling
   `.edn` forecast dump. The name is constrained to a flat `forecast-*.{png,edn}` basename
   (no slashes/dots-dots), so it can't escape the dir. The `.edn` is sent as an attachment
   so the gallery's data link downloads rather than renders it inline."
  [uri]
  (let [requested (subs uri (count "/archive/"))]
    (if-let [[_ ext] (re-matches #"forecast-[0-9A-Za-z-]+\.(png|edn)" requested)]
      (let [file (io/file (archive/dir) requested)]
        (if (.isFile file)
          (if (= ext "png")
            (png-response file)
            {:status  200
             :headers {"Content-Type"        "application/edn; charset=utf-8"
                       "Content-Disposition" (str "attachment; filename=\"" requested "\"")}
             :body    file})
          {:status 404}))
      {:status 404})))

;; --- Routing ----------------------------------------------------------------------------

(defn- handler [base-url]
  (fn [{:keys [request-method uri] :as request}]
    (let [get? (= :get request-method)]
      (cond
        (and get? (= uri "/"))                        (html-response (pages/home))
        (and get? (= uri "/api/display"))             (display-response base-url request)
        (and get? (= uri "/api/setup"))               (setup-response base-url)
        (and (= :post request-method)
          (= uri "/api/log"))                         (log-response request)
        (and get? (= uri "/status"))                  (html-response (pages/status (query-param request "day")))
        (and get? (= uri "/archive"))                 (html-response (pages/gallery))
        (and get? (str/starts-with? uri "/archive/")) (archive-file-response uri)
        (and get? (str/starts-with? uri "/images/"))  (image-response uri)
        :else                                         {:status 404}))))

(defn- lan-ip
  "Best-effort first non-loopback IPv4 address, for the startup message. Falls back
   to \"localhost\" if none is found (e.g. no network connection)."
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
    (telemetry/load-wake-history!)
    (httpkit/run-server (handler base-url) {:port port})
    (log/info (str "TRMNL server listening on " base-url))
    (log/info "Point your TRMNL OG's custom server URL to the above.")))
