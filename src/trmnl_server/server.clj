(ns trmnl-server.server
  "Serves the forecast screen to a real TRMNL OG device over HTTP, implementing the
   three endpoints the device's firmware polls when pointed at a custom server:
   GET /api/display, GET /api/setup, POST /api/log. Uses http-kit as both the Ring
   handler convention and the embedded server, rather than a heavier Ring+Jetty stack."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [org.httpkit.server :as httpkit]
            [trmnl-server.core :as core]
            [trmnl-server.image :as img])
  (:import [ch.qos.logback.classic Logger]
           [ch.qos.logback.core FileAppender]
           [java.io ByteArrayOutputStream File]
           [java.net NetworkInterface]
           [java.security MessageDigest]
           [javax.imageio ImageIO]
           [org.slf4j LoggerFactory]))

;; Dedicated logger name for device telemetry (POST /api/log); logback (resources/logback.xml)
;; routes it to its own device.log rather than the main server log.
(def ^:private device-logger "trmnl-server.device")

(def ^:private refresh-rate-seconds 900)
(def ^:private cache-ttl-ms (* 10 60 1000))
(def ^:private max-stored-logs 200)
(def ^:private archive-retention-ms (* 24 60 60 1000))

(defonce ^:private cache (atom nil))
(defonce ^:private regen-lock (Object.))
(defonce ^:private device-logs (atom []))

(defn- png-bytes [image]
  (let [out (ByteArrayOutputStream.)]
    (ImageIO/write image "png" out)
    (.toByteArray out)))

(defn- md5-hex [bytes]
  (let [digest (.digest (MessageDigest/getInstance "MD5") bytes)]
    (apply str (map #(format "%02x" %) digest))))

(defn- forecast-hours []
  (or (some-> (System/getenv "FORECAST_HOURS") Integer/parseInt) core/default-forecast-hours))

(defn- forecast-location []
  (let [lat (System/getenv "FORECAST_LAT")
        lon (System/getenv "FORECAST_LON")]
    (if (and lat lon)
      {:lat (Double/parseDouble lat) :lon (Double/parseDouble lon)}
      core/default-forecast-location)))

(defn- battery-percent
  "Rough charge estimate from a raw battery_voltage reading (LiPo, ~3V empty to
   ~4.2V full). Not the device's exact curve — just enough to flag a low battery."
  [voltage]
  (when voltage
    (-> (/ (- voltage 3) 0.012) (max 1.0) (min 100.0) Math/round)))

(defn- battery-label [pct]
  (cond
    (nil? pct) "unknown"
    (< pct 15) "LOW"
    (< pct 30) "watch"
    :else "ok"))

(defn- escape-html [s]
  (-> (str s)
    (str/replace "&" "&amp;")
    (str/replace "<" "&lt;")
    (str/replace ">" "&gt;")))

(defn- archive-dir
  "Where --serve stows a rolling copy of every rendered screen, relative to the
   process's working directory (the systemd unit's WorkingDirectory in prod, so
   /home/seb/trmnl-server/archive/…) unless ARCHIVE_DIR overrides it — mirroring how
   logs/ is placed. Distinct from out/, which stays reserved for the batch-render modes."
  []
  (or (System/getenv "ARCHIVE_DIR") "archive"))

(def ^:private archive-timestamp
  (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss"))

(defn- prune-archive!
  "Deletes archived PNGs older than archive-retention-ms so the folder self-manages a
   rolling 24h window (by file mtime, which is the write time) without any external cron."
  [^File dir]
  (let [cutoff (- (System/currentTimeMillis) archive-retention-ms)]
    (doseq [^File f (.listFiles dir)]
      (when (and (.isFile f)
              (str/ends-with? (.getName f) ".png")
              (< (.lastModified f) cutoff))
        (.delete f)))))

(defn- archive-image!
  "Persists freshly rendered PNG bytes to the rolling archive dir and prunes the 24h
   window, so a problematic screen spotted after the fact can still be recovered and
   saved. Best-effort: any IO failure is logged and swallowed so it never breaks the
   serving path. Runs under current-image's regen-lock, so writes are already serialized."
  [bytes]
  (try
    (let [dir (io/file (archive-dir))]
      (.mkdirs dir)
      (let [stamp (.format (java.time.LocalDateTime/now) archive-timestamp)]
        (with-open [out (io/output-stream (io/file dir (str "forecast-" stamp ".png")))]
          (.write out ^bytes bytes)))
      (prune-archive! dir))
    (catch Exception e
      (log/warn e "Could not archive rendered image"))))

(defn current-image
  "Returns the cached {:bytes :filename :generated-at}, regenerating from a fresh
   forecast when the cache is empty or older than cache-ttl-ms. If regeneration
   throws (e.g. SMHI returns something other than JSON), falls back to serving
   the last successfully rendered image — with a warning badge stamped on a
   copy of it — rather than a bare 500. A stale forecast is more useful to the
   device than none; the badge is what lets you notice at a glance that it's
   stale and go check the logs. Only propagates the exception when there's no
   prior image to fall back on.

   The regeneration path is serialized on regen-lock so two requests arriving on
   an expired cache don't both fetch SMHI and re-render; the second re-checks the
   cache inside the lock and reuses the entry the first one just produced."
  []
  (let [fresh? (fn [entry] (and entry (< (- (System/currentTimeMillis) (:generated-at entry)) cache-ttl-ms)))
        entry  @cache]
    (if (fresh? entry)
      entry
      (locking regen-lock
        (let [entry @cache]
          (if (fresh? entry)
            entry
            (try
              (let [location  (forecast-location)
                    image     (img/->1-bit (core/forecast-screen (core/live-points (forecast-hours) location) location))
                    bytes     (png-bytes image)
                    new-entry {:image        image
                               :bytes        bytes
                               :filename     (str "forecast-" (md5-hex bytes) ".png")
                               :generated-at (System/currentTimeMillis)}]
                (reset! cache new-entry)
                (archive-image! bytes)
                new-entry)
              (catch Exception e
                (if entry
                  (let [stale-bytes (or (:stale-bytes entry) (png-bytes (core/stamp-stale-badge (:image entry))))
                        stale-entry (assoc entry
                                      :stale-bytes stale-bytes
                                      :stale-filename (str "forecast-" (md5-hex stale-bytes) "-stale.png"))]
                    (log/warn e "Forecast regeneration failed, serving stale cache")
                    (reset! cache stale-entry)
                    stale-entry)
                  (throw e))))))))))

(defn- serve-filename [entry] (or (:stale-filename entry) (:filename entry)))

(defn- image-url [base-url filename]
  (str base-url "/images/" filename))

(defn- json-response [body]
  {:status  200
   :headers {"Content-Type" "application/json"}
   :body    (json/write-str body)})

(defn- display-response [base-url]
  (let [entry    (current-image)
        filename (serve-filename entry)]
    (json-response {:filename          filename
                    :image_url         (image-url base-url filename)
                    :image_url_timeout 0
                    :refresh_rate      refresh-rate-seconds
                    :reset_firmware    false
                    :update_firmware   false
                    :firmware_url      nil})))

(defn- setup-response [base-url]
  (let [entry (current-image)]
    (json-response {:image_url (image-url base-url (serve-filename entry))
                    :message   "Welcome to trmnl-server"})))

(defn- log-response [request]
  (let [body    (slurp (:body request))
        entries (:logs (try (json/read-str body :key-fn keyword) (catch Exception _ nil)))]
    (log/log device-logger :info nil body)
    (when (seq entries)
      (swap! device-logs #(->> (concat % entries) (take-last max-stored-logs) vec))))
  {:status 204})

(defn- device-log-file
  "The active device.log path, read straight from logback's DEVICE appender so it stays
   in sync with resources/logback.xml (including any DEVICE_LOG_FILE override) rather
   than being duplicated here. nil when logback isn't the backend or the appender is
   absent (e.g. a REPL under a different config), so seeding just no-ops."
  []
  (let [logger   (LoggerFactory/getLogger device-logger)
        appender (when (instance? Logger logger)
                   (Logger/.getAppender logger "DEVICE"))]
    (when (instance? FileAppender appender)
      (FileAppender/.getFile appender))))

(defn- parse-device-log-line
  "Pulls the entry maps out of one device.log line: a timestamp prefix followed by the
   raw POST body (`{\"logs\":[…]}`). Returns that :logs seq, or nil for a blank/malformed
   line."
  [line]
  (when-let [i (str/index-of line "{")]
    (:logs (try (json/read-str (subs line i) :key-fn keyword)
             (catch Exception _ nil)))))

(defn- seed-device-logs!
  "Warms the device-logs atom from the tail of the active device.log at startup, so
   /status isn't blank after a restart/redeploy — the atom is otherwise only filled by
   live POSTs and lost on exit. Reads just the active file (the current daily-rolled
   window, normally already ≥ the max-stored-logs cap), not the gzipped archives.
   Best-effort: any read/parse problem just leaves the atom as it was."
  []
  (try
    (when-let [path (device-log-file)]
      (let [file (File. ^String path)]
        (when (.isFile file)
          (let [entries (->> (str/split-lines (slurp file))
                          (mapcat parse-device-log-line)
                          (take-last max-stored-logs)
                          vec)]
            (when (seq entries)
              (swap! device-logs #(->> (concat entries %) (take-last max-stored-logs) vec)))))))
    (catch Exception e
      (log/warn e "Could not seed device-logs from device.log"))))

(defn- png-response [bytes]
  {:status  200
   :headers {"Content-Type" "image/png"}
   :body    bytes})

(defn- archive-file-response
  "Serves one archived PNG by name from the archive dir. The name is constrained to a
   flat `forecast-*.png` basename (no slashes/dots-dots), so it can't escape the dir."
  [uri]
  (let [requested (subs uri (count "/archive/"))]
    (if (re-matches #"forecast-[0-9A-Za-z-]+\.png" requested)
      (let [file (io/file (archive-dir) requested)]
        (if (.isFile file)
          (png-response file)
          {:status 404}))
      {:status 404})))

(defn- image-response [uri]
  ;; Serve only the bytes whose content hash matches the requested filename, so a
  ;; cache rollover between the device's /api/display poll and its image fetch
  ;; 404s (prompting a re-poll) instead of silently serving mismatched bytes.
  (let [requested (subs uri (count "/images/"))
        entry     (current-image)]
    (condp = requested
      (:stale-filename entry) (png-response (:stale-bytes entry))
      (:filename entry)       (png-response (:bytes entry))
      {:status 404})))

(defn- html-response [body]
  {:status  200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    body})

;; Self-contained styling for the /status page — system fonts, no webfont/CDN, and a
;; prefers-color-scheme dark variant so it adapts to the viewer. Kept as a def to keep
;; status-response readable.
(def ^:private status-style
  (str "*{box-sizing:border-box}"
    ":root{--bg:#fff;--fg:#1c1c1a;--muted:#87867e;--card:#f7f7f4;--bd:#e6e5df;--zebra:#faf9f6;"
    "--err-bg:#fcecea;--err-fg:#9f332d;--warn-bg:#fbf1dd;--warn-fg:#875812;--ok-bg:#e6f2e6;--ok-fg:#2f6b32}"
    "@media(prefers-color-scheme:dark){:root{--bg:#0f1113;--fg:#e7e7e4;--muted:#8b8a82;--card:#191b1e;"
    "--bd:#2a2d31;--zebra:#141619;--err-bg:#37201e;--err-fg:#efa6a2;--warn-bg:#33290f;--warn-fg:#e3c079;"
    "--ok-bg:#1d2e1d;--ok-fg:#8ecb8e}}"
    "body{margin:0;background:var(--bg);color:var(--fg);padding:28px 22px;"
    "font:400 14px/1.5 -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif}"
    ".wrap{max-width:960px;margin:0 auto}"
    "h1{font-size:20px;font-weight:500;margin:0 0 18px}"
    ".cards{display:flex;gap:12px;flex-wrap:wrap;margin-bottom:24px}"
    ".card{flex:1;min-width:150px;background:var(--card);border-radius:10px;padding:12px 15px}"
    ".k{font-size:11px;letter-spacing:.04em;text-transform:uppercase;color:var(--muted)}"
    ".v{font-size:24px;font-weight:500;margin:3px 0 7px}"
    ".mono{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}"
    ".pill{display:inline-block;font-size:11px;font-weight:500;padding:2px 9px;border-radius:20px}"
    ".pill-ok{background:var(--ok-bg);color:var(--ok-fg)}"
    ".pill-watch{background:var(--warn-bg);color:var(--warn-fg)}"
    ".pill-low{background:var(--err-bg);color:var(--err-fg)}"
    ".pill-unknown{color:var(--muted);border:.5px solid var(--bd)}"
    ".h{font-size:13px;font-weight:500;margin:0 0 8px}"
    ".tw{overflow-x:auto;border:.5px solid var(--bd);border-radius:10px}"
    "table{border-collapse:collapse;width:100%;font-size:12.5px}"
    "th,td{text-align:left;padding:8px 11px;white-space:nowrap}"
    "thead th{color:var(--muted);font-weight:500;border-bottom:.5px solid var(--bd)}"
    "tbody tr:nth-child(even){background:var(--zebra)}"
    "tbody tr.err{background:var(--err-bg)}tbody tr.err td{color:var(--err-fg)}"
    "tbody tr.warn{background:var(--warn-bg)}tbody tr.warn td{color:var(--warn-fg)}"
    "td.num{text-align:right}"
    ".empty{color:var(--muted)}"))

(defn- pill-class [label]
  (case label "ok" "pill-ok" "watch" "pill-watch" "LOW" "pill-low" "pill-unknown"))

(defn- row-class
  "Tints a log row by severity so the noisy device errors are glanceable: red for any
   ERROR message, amber for a WARN, plain otherwise."
  [message]
  (let [m (str/lower-case (str message))]
    (cond
      (str/includes? m "error") " class=\"err\""
      (str/includes? m "warn")  " class=\"warn\""
      :else "")))

(defn- status-response []
  (let [logs            (reverse @device-logs)
        latest-voltage  (some :battery_voltage logs)
        pct             (battery-percent latest-voltage)
        label           (battery-label pct)
        latest-firmware (some :firmware_version logs)]
    (html-response
      (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        "<title>trmnl-server status</title><style>" status-style "</style></head>"
        "<body><div class=\"wrap\">"
        "<h1>trmnl-server status</h1>"
        "<p style=\"font-size:13px;margin:-8px 0 18px\"><a href=\"/archive\" "
        "style=\"color:var(--muted);text-decoration:none\">Archived screens &rarr;</a></p>"
        "<div class=\"cards\">"
        "<div class=\"card\"><div class=\"k\">Battery</div>"
        "<div class=\"v\">" (if latest-voltage
                              (String/format java.util.Locale/US "%.3f V" (to-array [latest-voltage]))
                              "—")
        "</div><span class=\"pill " (pill-class label) "\">"
        (if latest-voltage (str "~" pct "% · " label) "no data yet") "</span></div>"
        "<div class=\"card\"><div class=\"k\">Firmware</div>"
        "<div class=\"v mono\">" (if latest-firmware (escape-html latest-firmware) "—") "</div>"
        "<span class=\"pill pill-unknown\">" (count logs) " rows logged</span></div>"
        "</div>"
        "<div class=\"h\">Recent device log</div>"
        (if (seq logs)
          (str "<div class=\"tw\"><table>"
            "<thead><tr><th>time</th><th>message</th><th>source</th>"
            "<th class=\"num\">battery</th><th>wifi</th><th class=\"num\">retry</th><th>firmware</th></tr></thead>"
            "<tbody>"
            (apply str
              (for [{:keys [created_at message source_path source_line
                            battery_voltage wifi_signal wifi_status retry firmware_version]} logs]
                (str "<tr" (row-class message) ">"
                  "<td class=\"mono\">" (some-> created_at (java.time.Instant/ofEpochSecond)) "</td>"
                  "<td>" (escape-html message) "</td>"
                  "<td class=\"mono\">" (escape-html source_path) ":" source_line "</td>"
                  "<td class=\"num mono\">" battery_voltage "</td>"
                  "<td>" (escape-html wifi_status) " (" wifi_signal ")</td>"
                  "<td class=\"num mono\">" retry "</td>"
                  "<td class=\"mono\">" (escape-html firmware_version) "</td></tr>")))
            "</tbody></table></div>")
          "<p class=\"empty\">No device logs yet.</p>")
        "</div></body></html>"))))

(defn- archive-entries
  "Archived PNGs, newest first (by mtime), for the gallery. Empty when the dir is absent."
  []
  (let [dir (io/file (archive-dir))]
    (if (.isDirectory dir)
      (->> (.listFiles dir)
        (filter #(and (.isFile ^File %) (str/ends-with? (.getName ^File %) ".png")))
        (sort-by #(- (.lastModified ^File %)))
        vec)
      [])))

(def ^:private archive-display-format
  (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))

(defn- format-mtime [millis]
  (.format (java.time.LocalDateTime/ofInstant
             (java.time.Instant/ofEpochMilli millis)
             (java.time.ZoneId/systemDefault))
    archive-display-format))

;; A grid of the archived screens, appended to status-style so it shares the page shell,
;; colors and dark-mode handling. Shots sit on white (#fff) so the 1-bit PNGs stay legible
;; in dark mode too.
(def ^:private archive-style
  (str status-style
    ".nav{font-size:13px;margin:0 0 18px}.nav a{color:var(--muted);text-decoration:none}"
    ".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:14px}"
    ".shot{background:var(--card);border:.5px solid var(--bd);border-radius:10px;overflow:hidden}"
    ".shot a{display:block}.shot img{display:block;width:100%;height:auto;background:#fff}"
    ".shot .cap{padding:8px 11px;font-size:12px;color:var(--muted)}"))

(defn- archive-response []
  (let [entries (archive-entries)]
    (html-response
      (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        "<title>trmnl-server archive</title><style>" archive-style "</style></head>"
        "<body><div class=\"wrap\">"
        "<h1>Archived screens</h1>"
        "<p class=\"nav\"><a href=\"/status\">&larr; status</a> · " (count entries)
        " screens · rolling 24h</p>"
        (if (seq entries)
          (str "<div class=\"grid\">"
            (apply str
              (for [^File f entries
                    :let    [name (.getName f)]]
                (str "<div class=\"shot\">"
                  "<a href=\"/archive/" name "\" title=\"" name "\">"
                  "<img loading=\"lazy\" src=\"/archive/" name "\" alt=\"" name "\"></a>"
                  "<div class=\"cap mono\">" (format-mtime (.lastModified f)) "</div></div>")))
            "</div>")
          "<p class=\"empty\">No archived screens yet.</p>")
        "</div></body></html>"))))

(defn- handler [base-url]
  (fn [{:keys [request-method uri] :as request}]
    (cond
      (and (= request-method :get) (= uri "/api/display")) (display-response base-url)
      (and (= request-method :get) (= uri "/api/setup")) (setup-response base-url)
      (and (= request-method :post) (= uri "/api/log")) (log-response request)
      (and (= request-method :get) (= uri "/status")) (status-response)
      (and (= request-method :get) (= uri "/archive")) (archive-response)
      (and (= request-method :get) (str/starts-with? uri "/archive/")) (archive-file-response uri)
      (and (= request-method :get) (str/starts-with? uri "/images/")) (image-response uri)
      :else {:status 404})))

(defn- lan-ip
  "Best-effort first non-loopback IPv4 address, for the startup message. Falls back
   to \"localhost\" if none is found (e.g. no network connection)."
  []
  (or (some->> (NetworkInterface/getNetworkInterfaces)
        enumeration-seq
        (mapcat #(enumeration-seq (.getInetAddresses %)))
        (some #(when (and (instance? java.net.Inet4Address %) (not (.isLoopbackAddress %))) %))
        .getHostAddress)
    "localhost"))

(defn start!
  "Starts the HTTP server. http-kit's worker threads are non-daemon, so the JVM
   stays alive after this (and -main) returns."
  []
  (let [port     (or (some-> (System/getenv "PORT") Integer/parseInt) 8080)
        base-url (str "http://" (lan-ip) ":" port)]
    (seed-device-logs!)
    (httpkit/run-server (handler base-url) {:port port})
    (log/info (str "TRMNL server listening on " base-url))
    (log/info "Point your TRMNL OG's custom server URL to the above.")))
