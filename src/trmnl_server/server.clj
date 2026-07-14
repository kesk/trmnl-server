(ns trmnl-server.server
  "Serves the forecast screen to a real TRMNL OG device over HTTP, implementing the
   three endpoints the device's firmware polls when pointed at a custom server:
   GET /api/display, GET /api/setup, POST /api/log. Uses http-kit as both the Ring
   handler convention and the embedded server, rather than a heavier Ring+Jetty stack."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.java.shell]
            [clojure.tools.logging :as log]
            [hiccup2.core :as h]
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

;; Deployed commit, baked into version.edn by build.clj's uber task and bundled into the
;; jar. Absent when running from source (clojure -M:serve), where there's no build step —
;; fall back to reading HEAD off the working tree's .git so /status still shows a commit
;; in dev. Read once at load; nil :commit renders as "unknown".
(def ^:private deployed-version
  (or (when-let [r (io/resource "version.edn")]
        (try (read-string (slurp r)) (catch Exception _ nil)))
    (try
      (let [{:keys [exit out]} (clojure.java.shell/sh "git" "rev-parse" "--short" "HEAD")]
        (when (zero? exit) {:commit (str/trim out)}))
      (catch Exception _ nil))))

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
              (or (str/ends-with? (.getName f) ".png")
                (str/ends-with? (.getName f) ".edn"))
              (< (.lastModified f) cutoff))
        (.delete f)))))

(def ^:private archive-hash-pattern
  ;; Captures the trailing <8 hex> content-hash segment of an archive filename, tolerant of
  ;; the optional `-run<...>` reference-time segment in the middle (see archive-image!).
  #"-([0-9a-f]{8})\.png\z")

(def ^:private reference-run-format
  (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmm"))

(defn- reference-run-token
  "A compact local-time token identifying the SMHI forecast run (its issuance
   `referenceTime`) for the archive filename, e.g. \"run20260713-0945\". nil for a nil
   reference-time (e.g. demo data, which is never archived anyway)."
  [reference-time]
  (when reference-time
    (str "run" (.format (java.time.LocalDateTime/ofInstant
                          (java.time.Instant/parse reference-time)
                          (java.time.ZoneId/systemDefault))
                 reference-run-format))))

(defn- last-archived-hash
  "Short content hash of the most recently written archive file, or nil when the dir is
   empty or its newest file predates the hashed-filename scheme. Lets archive-image! skip
   re-writing a render whose forecast matches the one already on top of the archive."
  [^File dir]
  (some->> (.listFiles dir)
    (filter #(and (.isFile ^File %)
               (str/ends-with? (.getName ^File %) ".png")))
    (sort-by #(- (.lastModified ^File %)))
    first
    (#(.getName ^File %))
    (re-find archive-hash-pattern)
    second))

(defn- archive-image!
  "Persists a freshly rendered PNG to the rolling archive dir as
   `forecast-<ts>-<hash8>.png` and prunes the 24h window, so a problematic screen spotted
   after the fact can still be recovered and saved. Dedupes on `hash` — the caller's hash
   of the *forecast data* (not the rendered pixels, which carry a per-render 'Uppdaterad'
   timestamp that would otherwise make every render look distinct): a render whose forecast
   matches the newest archived one is skipped, so the gallery stays a list of *distinct*
   screens rather than ~100 near-duplicates a day. Best-effort: any IO failure is logged and
   swallowed so it never breaks the serving path. Runs under current-image's regen-lock, so
   writes are already serialized. `reference-time` (SMHI's run issuance time, or nil) is
   stamped into the filename as a `run<...>` segment for at-a-glance provenance; it does not
   affect dedupe. `points` (the forecast seq the PNG was rendered from) is dumped verbatim
   to a sibling `.edn` alongside the PNG, so an archived screen can be re-rendered/inspected
   later — the pixels alone can't be reconstructed into the underlying data."
  [bytes hash reference-time points]
  (try
    (let [dir        (io/file (archive-dir))
          short-hash (subs hash 0 8)]
      (.mkdirs dir)
      (when-not (= short-hash (last-archived-hash dir))
        (let [stamp (.format (java.time.LocalDateTime/now) archive-timestamp)
              run   (reference-run-token reference-time)
              base  (str "forecast-" stamp (when run (str "-" run)) "-" short-hash)]
          (with-open [out (io/output-stream (io/file dir (str base ".png")))]
            (.write out ^bytes bytes))
          (spit (io/file dir (str base ".edn")) (pr-str points)))
        (prune-archive! dir)))
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
                    points    (core/live-points (forecast-hours) location)
                    image     (img/->1-bit (core/forecast-screen points location))
                    bytes     (png-bytes image)
                    ;; Cache/download key is the pixel hash; the archive dedupe key is the
                    ;; forecast *data* hash instead, so the per-render "Uppdaterad HH:mm"
                    ;; stamp (which changes the pixels every render) doesn't defeat dedupe —
                    ;; two renders of the same forecast collapse to one archived screen.
                    data-hash (md5-hex (.getBytes (pr-str points) "UTF-8"))
                    new-entry {:image        image
                               :bytes        bytes
                               :filename     (str "forecast-" (md5-hex bytes) ".png")
                               :generated-at (System/currentTimeMillis)}]
                (reset! cache new-entry)
                (archive-image! bytes data-hash (:reference-time (meta points)) points)
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
  "Serves one archived file by name from the archive dir — the PNG screen or its sibling
   `.edn` forecast dump. The name is constrained to a flat `forecast-*.{png,edn}` basename
   (no slashes/dots-dots), so it can't escape the dir. The `.edn` is sent as an attachment
   so the gallery's data link downloads rather than renders it inline."
  [uri]
  (let [requested (subs uri (count "/archive/"))]
    (if-let [[_ ext] (re-matches #"forecast-[0-9A-Za-z-]+\.(png|edn)" requested)]
      (let [file (io/file (archive-dir) requested)]
        (if (.isFile file)
          (if (= ext "png")
            (png-response file)
            {:status  200
             :headers {"Content-Type"        "application/edn; charset=utf-8"
                       "Content-Disposition" (str "attachment; filename=\"" requested "\"")}
             :body    file})
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

;; Self-contained styling, slurped from resources/css/ at load (bundled into the uberjar
;; via :paths, so io/resource resolves in prod too). base.css is the shared page shell —
;; system fonts, no webfont/CDN, and a prefers-color-scheme dark variant so it adapts to
;; the viewer; the /archive gallery layers archive.css on top of it.
(defn- css [& names]
  (str/join "\n" (map #(slurp (io/resource (str "css/" %))) names)))

(def ^:private status-css (css "base.css"))
(def ^:private archive-css (css "base.css" "archive.css"))

(defn- page
  "Wraps page-specific body hiccup in the shared HTML shell (doctype, head, the given
   inline CSS, and the centering .wrap div), returning the rendered HTML string."
  [title css-text body]
  (str "<!doctype html>"
    (h/html {:mode :html}
      [:html {:lang "en"}
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:name "viewport" :content "width=device-width,initial-scale=1"}]
        [:title title]
        [:style (h/raw css-text)]]
       [:body [:div.wrap body]]])))

(defn- pill-class [label]
  (case label "ok" "pill-ok" "watch" "pill-watch" "LOW" "pill-low" "pill-unknown"))

(defn- row-class
  "Tints a log row by severity so the noisy device errors are glanceable: red for any
   ERROR message, amber for a WARN, nil (no class) otherwise."
  [message]
  (let [m (str/lower-case (str message))]
    (cond
      (str/includes? m "error") "err"
      (str/includes? m "warn")  "warn"
      :else nil)))

(defn- log-row [{:keys [created_at message source_path source_line
                        battery_voltage wifi_signal wifi_status retry firmware_version]}]
  [:tr {:class (row-class message)}
   [:td.mono (some-> created_at (java.time.Instant/ofEpochSecond))]
   [:td message]
   [:td.mono source_path ":" source_line]
   [:td.num.mono battery_voltage]
   [:td wifi_status " (" wifi_signal ")"]
   [:td.num.mono retry]
   [:td.mono firmware_version]])

(defn- log-table [logs]
  [:div.tw
   [:table
    [:thead
     [:tr [:th "time"] [:th "message"] [:th "source"]
      [:th.num "battery"] [:th "wifi"] [:th.num "retry"] [:th "firmware"]]]
    [:tbody (map log-row logs)]]])

(defn- status-response []
  (let [logs            (reverse @device-logs)
        latest-voltage  (some :battery_voltage logs)
        pct             (battery-percent latest-voltage)
        label           (battery-label pct)
        latest-firmware (some :firmware_version logs)]
    (html-response
      (page "trmnl-server status" status-css
        (list
          [:h1 "trmnl-server status"]
          [:p {:style "font-size:13px;margin:-8px 0 18px"}
           [:a {:href "/archive" :style "color:var(--muted);text-decoration:none"}
            "Archived screens →"]]
          [:div.cards
           [:div.card
            [:div.k "Battery"]
            [:div.v (if latest-voltage
                      (String/format java.util.Locale/US "%.3f V" (to-array [latest-voltage]))
                      "—")]
            [:span {:class (str "pill " (pill-class label))}
             (if latest-voltage (str "~" pct "% · " label) "no data yet")]]
           [:div.card
            [:div.k "Firmware"]
            [:div.v.mono (or latest-firmware "—")]
            [:span.pill.pill-unknown (count logs) " rows logged"]]
           [:div.card
            [:div.k "Deployed"]
            [:div.v.mono (or (:commit deployed-version) "unknown")]
            (when-let [built (:built-at deployed-version)]
              [:span.pill.pill-unknown "built " built])]]
          [:div.h-row
           [:div.h "Recent device log"]
           (when (seq logs)
             [:form {:method "post" :action "/status/clear"}
              [:button.clear {:type "submit"} "Clear"]])]
          (if (seq logs)
            (log-table logs)
            [:p.empty "No device logs yet."]))))))

(defn- clear-logs-response
  "Empties the in-memory device-logs atom that backs /status, then 303s back to it.
   Clears only the display buffer — the persistent device.log on disk is untouched, so a
   later restart re-seeds /status from its tail (see seed-device-logs!). Handy for wiping
   stale rows from a since-fixed issue without waiting for them to age out."
  []
  (reset! device-logs [])
  {:status 303 :headers {"Location" "/status"}})

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

(defn- archive-card [^File f]
  (let [name (.getName f)
        edn  (str/replace name #"\.png\z" ".edn")]
    [:div.shot
     [:a {:href (str "/archive/" name) :title name}
      [:img {:loading "lazy" :src (str "/archive/" name) :alt name}]]
     [:div.cap.mono
      (format-mtime (.lastModified f))
      (when (.isFile (io/file (archive-dir) edn))
        (list " · " [:a {:href (str "/archive/" edn)} "data"]))]]))

(defn- archive-response []
  (let [entries (archive-entries)]
    (html-response
      (page "trmnl-server archive" archive-css
        (list
          [:h1 "Archived screens"]
          [:p.nav
           [:a {:href "/status"} "← status"]
           " · " (count entries) " screens · rolling 24h"]
          (if (seq entries)
            [:div.grid (map archive-card entries)]
            [:p.empty "No archived screens yet."]))))))

(defn- handler [base-url]
  (fn [{:keys [request-method uri] :as request}]
    (cond
      (and (= request-method :get) (= uri "/api/display")) (display-response base-url)
      (and (= request-method :get) (= uri "/api/setup")) (setup-response base-url)
      (and (= request-method :post) (= uri "/api/log")) (log-response request)
      (and (= request-method :get) (= uri "/status")) (status-response)
      (and (= request-method :post) (= uri "/status/clear")) (clear-logs-response)
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
