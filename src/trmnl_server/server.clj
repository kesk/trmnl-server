(ns trmnl-server.server
  "Serves the forecast screen to a real TRMNL OG device over HTTP, implementing the
   three endpoints the device's firmware polls when pointed at a custom server:
   GET /api/display, GET /api/setup, POST /api/log. Uses http-kit as both the Ring
   handler convention and the embedded server, rather than a heavier Ring+Jetty stack."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [hiccup2.core :as h]
            [org.httpkit.server :as httpkit]
            [trmnl-server.core :as core]
            [trmnl-server.image :as img])
  (:import [java.io ByteArrayOutputStream File]
           [java.net NetworkInterface]
           [java.security MessageDigest]
           [javax.imageio ImageIO]))

(def ^:private refresh-rate-seconds 900)
(def ^:private cache-ttl-ms (* 10 60 1000))
(def ^:private archive-retention-ms (* 24 60 60 1000))
(def ^:private max-device-log-files 7)
;; How far back wake-time samples are kept — sets the longest trend window (7d) below.
(def ^:private wake-history-retention-ms (* 7 24 60 60 1000))

(defonce ^:private cache (atom nil))
(defonce ^:private regen-lock (Object.))
(defonce ^:private device-log-lock (Object.))
(defonce ^:private wake-history-lock (Object.))
(defonce ^:private device-status (atom nil))
;; Rolling series of {:t <epoch-ms> :ms <awake-ms>} device wake durations, oldest→newest,
;; persisted to disk so the /status trend survives restarts. See record-wake-time! below.
(defonce ^:private wake-history (atom []))

;; Deployed commit, baked into version.edn by build.clj's uber task and bundled into the
;; jar. Absent when running from source (clojure -M:serve), where there's no build step —
;; there's no commit to report there, so /status just shows "dev-local". Read once at load.
(def ^:private deployed-version
  (or (when-let [r (io/resource "version.edn")]
        (try (read-string (slurp r)) (catch Exception _ nil)))
    {:commit "dev-local"}))

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

(defn- wifi-quality
  "Maps a raw RSSI (dBm — negative, closer to zero is stronger) to a human label and
   the pill class to tint it with. nil → unknown."
  [rssi]
  (cond
    (nil? rssi)   ["unknown" "pill-unknown"]
    (>= rssi -67) ["good" "pill-ok"]
    (>= rssi -78) ["fair" "pill-watch"]
    :else         ["weak" "pill-low"]))

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

;; Defined below, after its device-log-dir dependency; forward-declared for display-response.
(declare record-wake-time!)

(defn- display-response [base-url request]
  (when-let [status (parse-display-headers (:headers request))]
    (reset! device-status status)
    (record-wake-time! (:wake-time status)))
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

;; Device telemetry (POST /api/log) is written to disk directly — no logback — one raw
;; JSON line per POST into logs/device-<yyyy-MM-dd>.log, the file chosen by the UTC date
;; so the filename does the daily partitioning. /status reads these back. The main server
;; log still goes through logback (tools.logging); only device telemetry is hand-written.
(def ^:private device-log-name-re #"device-(\d{4}-\d{2}-\d{2})\.log")

(defn- device-log-dir
  "Directory device-<date>.log files live in — $DEVICE_LOG_DIR, else logs/ relative to the
   process's working dir (the systemd unit's WorkingDirectory in prod)."
  []
  (io/file (or (System/getenv "DEVICE_LOG_DIR") "logs")))

(defn- today-utc-date
  "Today's UTC calendar date as a yyyy-MM-dd string — the day a just-received row files
   under, and the /status default view."
  []
  (str (java.time.LocalDate/now java.time.ZoneOffset/UTC)))

;; --- Device wake-time trend -------------------------------------------------------------
;; The firmware's Wake-Time header reports how long the device was awake during its previous
;; cycle (ms) — a health signal, since a device fighting weak WiFi stays awake longer and
;; drains the battery. We keep a rolling series of these samples (persisted so it survives
;; restarts) and surface the latest value plus moving averages on /status.

(defn- wake-history-file
  "Where the wake-time series is persisted — a single EDN file alongside the device logs."
  []
  (io/file (device-log-dir) "wake-times.edn"))

(defn- prune-wake-history
  "Drops samples older than the retention window (by their :t timestamp)."
  [samples now]
  (let [cutoff (- now wake-history-retention-ms)]
    (filterv #(>= (:t %) cutoff) samples)))

(defn- load-wake-history!
  "Reads the persisted wake-time series into the atom at startup, pruning stale samples.
   Best-effort: a missing or corrupt file just leaves the history empty."
  []
  (let [f (wake-history-file)]
    (when (.isFile f)
      (try
        (reset! wake-history
          (prune-wake-history (vec (read-string (slurp f))) (System/currentTimeMillis)))
        (catch Exception e
          (log/warn e "Could not read wake-time history"))))))

(defn- record-wake-time!
  "Appends one wake-duration sample (ms), prunes to the retention window, and persists.
   Ignores nil/non-positive values — the firmware sends 0 on a fresh boot with no previous
   cycle, which would otherwise drag the averages down. Persistence is best-effort: an IO
   error is logged and swallowed so the device poll still succeeds."
  [wake-ms]
  (when (and wake-ms (pos? wake-ms))
    (locking wake-history-lock
      (let [now     (System/currentTimeMillis)
            samples (prune-wake-history (conj @wake-history {:t now :ms wake-ms}) now)]
        (reset! wake-history samples)
        (try
          (let [dir (device-log-dir)]
            (.mkdirs dir)
            (spit (wake-history-file) (pr-str samples)))
          (catch Exception e
            (log/warn e "Could not write wake-time history")))))))

(defn- ms->secs
  "Milliseconds to seconds, rounded to one decimal."
  [ms]
  (/ (Math/round (/ (double ms) 100.0)) 10.0))

(def ^:private wake-windows
  "Trend windows shown on /status, label + span in ms — short, day, and week."
  [["1h" (* 60 60 1000)]
   ["6h" (* 6 60 60 1000)]
   ["24h" (* 24 60 60 1000)]
   ["7d" (* 7 24 60 60 1000)]])

(defn- wake-average
  "Mean awake-time in seconds (one decimal) over samples within the last window-ms, or nil
   when the window holds no samples."
  [samples now window-ms]
  (let [cutoff (- now window-ms)
        xs     (keep (fn [{:keys [t ms]}] (when (>= t cutoff) ms)) samples)]
    (when (seq xs)
      (ms->secs (/ (reduce + xs) (count xs))))))

(defn- wake-sparkline
  "An inline SVG polyline of the wake-time series (ms over time) scaled to a small box,
   for the /status Awake card. nil when there are fewer than two samples to connect. Pure
   server-rendered hiccup — no JS, no axis, no dependency; a glanceable trend read next to
   the numeric averages, not a precise chart. x maps each sample's :t across the width so
   irregular poll spacing shows; y maps :ms so taller = longer awake (inverted). Colour is
   left to CSS (currentColor → --muted) to stay legible in light and dark."
  [samples]
  (when (> (count samples) 1)
    (let [w    240
          h    34
          pad  3
          ts   (map :t samples)
          vs   (map :ms samples)
          tmin (apply min ts)
          tmax (apply max ts)
          vmin (apply min vs)
          vmax (apply max vs)
          trng (double (max 1 (- tmax tmin)))
          vrng (double (max 1 (- vmax vmin)))
          pts  (->> samples
                 (map (fn [{:keys [t ms]}]
                        (let [x (+ pad (* (- w (* 2 pad)) (/ (- t tmin) trng)))
                              y (+ pad (* (- h (* 2 pad)) (- 1 (/ (- ms vmin) vrng))))]
                          (str (Math/round (double x)) "," (Math/round (double y))))))
                 (str/join " "))]
      [:svg {:class               "spark" :viewBox     (str "0 0 " w " " h) :width "100%" :height h
             :preserveAspectRatio "none"  :aria-hidden "true"}
       [:polyline {:points pts}]])))

(defn- device-log-file-for
  "The device-log file for one UTC day (a yyyy-MM-dd string)."
  [day]
  (io/file (device-log-dir) (str "device-" day ".log")))

(defn- prune-device-logs!
  "Keeps only the max-device-log-files newest device-<date>.log files, deleting any older
   ones — so the folder self-caps at N days *with data* regardless of calendar gaps (a quiet
   device that skips days still keeps its last N reporting days). Filenames sort
   chronologically (ISO date), so this is a plain name sort. Best-effort; runs under
   device-log-lock via the caller."
  [^File dir]
  (->> (.listFiles dir)
    (filter #(re-matches device-log-name-re (.getName ^File %)))
    (sort-by #(.getName ^File %))              ; oldest first (ISO date sorts chronologically)
    (drop-last max-device-log-files)           ; drop the N newest to keep → leaves the surplus
    (run! #(.delete ^File %))))

(defn- append-device-log!
  "Appends one received telemetry body as a single line to today's device-<date>.log,
   creating the dir as needed, then prunes old days. Line breaks in the body are collapsed
   so each POST stays one physical line (line-based reading in read-device-log depends on
   it). Best-effort: any IO error is logged and swallowed so the POST still gets its 204."
  [body]
  (try
    (let [line (str/replace (str/trim body) #"\R+" " ")
          dir  (device-log-dir)]
      (locking device-log-lock
        (.mkdirs dir)
        (spit (device-log-file-for (today-utc-date)) (str line "\n") :append true)
        (prune-device-logs! dir)))
    (catch Exception e
      (log/warn e "Could not write device log"))))

(defn- log-response [request]
  (append-device-log! (slurp (:body request)))
  {:status 204})

(defn- parse-device-log-line
  "Pulls the entry maps out of one device-log line — the raw POST body (`{\"logs\":[…]}`).
   Returns that :logs seq, or nil for a blank/malformed line. Tolerates a leading prefix by
   scanning to the first `{`."
  [line]
  (when-let [i (str/index-of line "{")]
    (:logs (try (json/read-str (subs line i) :key-fn keyword)
             (catch Exception _ nil)))))

(defn- read-device-log
  "Parsed entries from one device-<date>.log file, in file (chronological) order. nil on any
   read error (rendered as an empty log)."
  [^File file]
  (try
    (with-open [r (io/reader file)]
      (->> (line-seq r) (mapcat parse-device-log-line) vec))
    (catch Exception e
      (log/warn e (str "Could not read device log " (.getName file)))
      nil)))

(defn- device-log-days
  "The days with a device-<date>.log on disk, newest first — each `{:day <date> :file
   <File>}` for the /status day picker. Ignores names that don't match (any legacy
   device.log/.gz). Empty when the dir is absent."
  []
  (let [dir (device-log-dir)]
    (when (.isDirectory dir)
      (->> (.listFiles dir)
        (keep (fn [^File f]
                (when-let [[_ d] (re-matches device-log-name-re (.getName f))]
                  (when (try (java.time.LocalDate/parse d) (catch Exception _ nil))
                    {:day d :file f}))))
        (sort-by :day #(compare %2 %1))
        vec))))

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
     [:tr [:th "time (UTC)"] [:th "message"] [:th "source"]
      [:th.num "battery"] [:th "wifi"] [:th.num "retry"] [:th "firmware"]]]
    [:tbody (map log-row logs)]]])

(def ^:private archive-display-format
  (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))

(defn- format-mtime [millis]
  (.format (java.time.LocalDateTime/ofInstant
             (java.time.Instant/ofEpochMilli millis)
             (java.time.ZoneId/systemDefault))
    archive-display-format))

(def ^:private built-display-format
  (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm"))

(defn- format-built-at
  "build.clj bakes :built-at into version.edn as a raw ISO-8601 instant string
   (e.g. 2026-07-16T10:41:12.123456Z). Render it as a compact local wall-clock time
   for the /status 'Deployed' pill; pass the original through unchanged if it isn't
   parseable as an instant, so a hand-edited version.edn can't blow up the page."
  [built]
  (try
    (.format (java.time.LocalDateTime/ofInstant
               (java.time.Instant/parse built)
               (java.time.ZoneId/systemDefault))
      built-display-format)
    (catch Exception _ built)))

(defn- query-param
  "The value of query-string key `k`, or nil. Raw — a caller that turns it into a filename
   must validate it (here, against the available days) before trusting it."
  [request k]
  (some->> (some-> (:query-string request) (str/split #"&"))
    (some #(when (str/starts-with? % (str k "=")) (subs % (inc (count k)))))))

(defn- status-response [request]
  (let [today           (today-utc-date)
        files           (device-log-days)
        day-set         (into #{} (map :day) files)
        ;; ?day selects a day to view; anything not on disk (a bogus/traversal value) falls
        ;; back to today, so ?day can only ever name a real device-<date>.log file.
        sel             (if (contains? day-set (query-param request "day"))
                          (query-param request "day")
                          today)
        days            (->> (cons today (map :day files)) distinct (sort #(compare %2 %1)) vec)
        sel-file        (device-log-file-for sel)
        rows            (reverse (when (.isFile sel-file) (read-device-log sel-file)))
        today-file      (device-log-file-for today)
        latest          (if (= sel today) ; cards always reflect the current (today's) log
                          rows
                          (reverse (when (.isFile today-file) (read-device-log today-file))))
        dev             @device-status
        latest-voltage  (or (:battery-voltage dev) (some :battery_voltage latest))
        pct             (battery-percent latest-voltage)
        label           (battery-label pct)
        latest-firmware (or (:fw-version dev) (some :firmware_version latest))
        [wifi-lbl
         wifi-pill]     (wifi-quality (:rssi dev))
        wakes           @wake-history
        now             (System/currentTimeMillis)
        latest-wake     (:ms (last wakes))
        wake-avg-cells  (for [[lbl w] wake-windows]
                          [lbl (wake-average wakes now w)])]
    (html-response
      (page "trmnl-server status" status-css
        (list
          [:div.top
           [:h1 "trmnl-server status"]
           [:a.top-link {:href "/archive"} "Archived screens →"]]
          [:section.group
           [:div.sec "Device health"]
           [:div.cards.cards-health
            [:div.card
             [:div.k "Battery"]
             [:div.v (if latest-voltage
                       (String/format java.util.Locale/US "%.3f V" (to-array [latest-voltage]))
                       "—")]
             [:span {:class (str "pill " (pill-class label))}
              (if latest-voltage (str "~" pct "% · " label) "no data yet")]]
            [:div.card
             [:div.k "WiFi"]
             [:div.v.mono (if (:rssi dev) (str (:rssi dev) " dBm") "—")]
             [:span {:class (str "pill " wifi-pill)} wifi-lbl]]
            [:div.card.awake
             [:div.k "Awake · last cycle"]
             [:div.v (if latest-wake (str (ms->secs latest-wake) " s") "—")]
             (if (seq wakes)
               (list
                 (wake-sparkline wakes)
                 [:div.avgs
                  (for [[lbl a] wake-avg-cells]
                    [:div.avg
                     [:div.al lbl]
                     [:div.av (if a (str a "s") "—")]])])
               [:span.pill.pill-unknown "no samples yet"])]]]
          [:section.group
           [:div.sec "Server · build"]
           [:div.cards.cards-build
            [:div.card
             [:div.k "Firmware"]
             [:div.v.mono (or latest-firmware "—")]
             (when-let [model (:model dev)]
               [:span.pill.pill-unknown model])]
            [:div.card
             [:div.k "Deployed"]
             [:div.v.mono (or (:commit deployed-version) "unknown")]
             (when-let [built (:built-at deployed-version)]
               [:span.pill.pill-unknown "built " (format-built-at built)])]
            [:div.card
             [:div.k "Last poll"]
             [:div.v.mono (if dev (format-mtime (:received-at dev)) "—")]
             [:span {:class (str "pill " (if dev "pill-ok" "pill-unknown"))}
              (if dev
                (str (or (:update-source dev) "unknown source")
                  " · " (case (:image-cached dev)
                          true  "image cached"
                          false "image refreshed"
                          "—"))
                "no poll yet")]]]]
          [:div.h-row
           [:div.h "Device log"
            (when (seq rows)
              [:span {:style "color:var(--muted);font-weight:400"}
               (str "  ·  " (count rows) " rows")])]
           [:div.days
            (for [d days]
              (if (= d sel)
                [:span.day.sel d]
                [:a.day {:href (str "/status?day=" d)} d]))]]
          (if (seq rows)
            (log-table rows)
            [:p.empty (str "No device logs for " sel ".")]))))))

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
      (and (= request-method :get) (= uri "/api/display")) (display-response base-url request)
      (and (= request-method :get) (= uri "/api/setup")) (setup-response base-url)
      (and (= request-method :post) (= uri "/api/log")) (log-response request)
      (and (= request-method :get) (= uri "/status")) (status-response request)
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
    (load-wake-history!)
    (httpkit/run-server (handler base-url) {:port port})
    (log/info (str "TRMNL server listening on " base-url))
    (log/info "Point your TRMNL OG's custom server URL to the above.")))
