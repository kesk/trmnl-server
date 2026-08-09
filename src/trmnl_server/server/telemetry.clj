(ns trmnl-server.server.telemetry
  "Everything the devices tell us about themselves, and where it's kept: each one's
   last /api/display poll headers, its rolling wake-time series, and its raw /api/log
   bodies on disk. Storage and aggregation only — the HTTP endpoints that feed it live
   in server, the device page's rendering of it in server.pages.

   Every fn here is scoped to one device by its :id — its stable identity rather than
   its :name, so a display can be renamed without orphaning the wake-time history and
   log days filed under it (see server.devices). On disk that's a subdirectory per
   device: logs/<id>/device-<date>.log and
   logs/<id>/wake-times.edn. Subdirectories rather than mangled filenames because
   prune-logs! then comes out right for free — its cap is a count of files in a
   directory, so a shared one would let a chatty display evict a quiet one's days.

   Device telemetry is written straight to disk, bypassing logback entirely: one
   raw JSON line per POST into that device's device-<yyyy-MM-dd>.log, the file chosen
   by the UTC date so the filename does the daily partitioning a rolling policy used
   to. Only this module's own failures go through the main logger."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.io File]
           [java.time LocalDate ZoneOffset]))

(def ^:private max-log-files 7)

;; How far back wake-time samples are kept — sets the longest trend window (7d) on the device page.
(def wake-retention-ms (* 7 24 60 60 1000))

(defonce ^:private log-lock (Object.))
(defonce ^:private wake-lock (Object.))

;; Device :id -> that device's latest /api/display header snapshot.
(defonce ^:private poll-state (atom {}))

;; Device :id -> rolling series of {:t <epoch-ms> :ms <awake-ms>} wake durations,
;; oldest→newest, persisted to disk so the device page's trend survives restarts. See
;; record-wake-time! below.
(defonce ^:private wake-history (atom {}))

(def ^:private log-name-re #"device-(\d{4}-\d{2}-\d{2})\.log")

(defn root
  "The root every device's telemetry directory sits under: $DEVICE_LOG_DIR, else logs/
   relative to the process's working dir (the systemd unit's WorkingDirectory in prod).
   Public because server.aliases hangs the readable symlinks here, beside the id-named
   directories."
  ^File []
  (io/file (or (System/getenv "DEVICE_LOG_DIR") "logs")))

(defn dir
  "Directory one device's telemetry lives in: a subdirectory named for its :id under
   the root above."
  ^File [device-id]
  (io/file (root) device-id))

(defn today-utc-date
  "Today's UTC calendar date as a yyyy-MM-dd string — the day a just-received row files
   under, and the device page's default view."
  []
  (str (LocalDate/now ZoneOffset/UTC)))

;; --- Device wake-time trend -------------------------------------------------------------
;; The firmware's Wake-Time header reports how long the device was awake during its previous
;; cycle (ms) — a health signal, since a device fighting weak WiFi stays awake longer and
;; drains the battery. We keep a rolling series of these samples per device (persisted so it
;; survives restarts) and surface the latest value plus moving averages on the device page.

(defn- wake-file
  "Where one device's wake-time series is persisted — a single EDN file in its own
   telemetry directory, alongside its device logs."
  ^File [device-id]
  (io/file (dir device-id) "wake-times.edn"))

(defn- prune-wake
  "Drops samples older than the retention window (by their :t timestamp)."
  [samples now]
  (let [cutoff (- now wake-retention-ms)]
    (filterv #(>= (:t %) cutoff) samples)))

(defn load-wake-history!
  "Reads each listed device's persisted wake-time series into the atom at startup, pruning
   stale samples. Best-effort per device: a missing or corrupt file just leaves that one
   empty rather than taking the others down with it."
  [device-ids]
  (let [now (System/currentTimeMillis)]
    (reset! wake-history
      (reduce (fn [acc device-id]
                (let [f (wake-file device-id)]
                  (if (.isFile f)
                    (try
                      (assoc acc device-id (prune-wake (vec (read-string (slurp f))) now))
                      (catch Exception e
                        (log/warn e (str "Could not read wake-time history for " device-id))
                        acc))
                    acc)))
        {} device-ids))))

(defn- record-wake-time!
  "Appends one wake-duration sample (ms) to a device's series, prunes to the retention
   window, and persists. Ignores nil/non-positive values — the firmware sends 0 on a fresh
   boot with no previous cycle, which would otherwise drag the averages down. Persistence
   is best-effort: an IO error is logged and swallowed so the device poll still succeeds."
  [device-id wake-ms]
  (when (and wake-ms (pos? wake-ms))
    (locking wake-lock
      (let [now     (System/currentTimeMillis)
            samples (prune-wake (conj (get @wake-history device-id []) {:t now :ms wake-ms}) now)]
        (swap! wake-history assoc device-id samples)
        (try
          (.mkdirs (dir device-id))
          (spit (wake-file device-id) (pr-str samples))
          (catch Exception e
            (log/warn e (str "Could not write wake-time history for " device-id))))))))

(defn wake-samples
  "One device's rolling wake-time series, oldest→newest, as {:t :ms} maps."
  [device-id]
  (get @wake-history device-id []))

(defn wake-average
  "Mean awake-time in *milliseconds* over samples within the last window-ms, or nil when
   the window holds no samples. Left in ms so the presentation layer owns the unit and
   rounding (see pages/ms->secs)."
  [samples now window-ms]
  (let [cutoff (- now window-ms)
        xs     (keep (fn [{:keys [t ms]}] (when (>= t cutoff) ms)) samples)]
    (when (seq xs)
      (/ (reduce + xs) (count xs)))))

;; --- Latest poll snapshot ---------------------------------------------------------------

(defn record-poll!
  "Takes the telemetry parsed off one device's /api/display poll: keeps it as that
   device's latest snapshot for the device page's summary cards, and feeds its Wake-Time into
   that device's rolling trend."
  [device-id status]
  (swap! poll-state assoc device-id status)
  (record-wake-time! device-id (:wake-time status)))

(defn poll-status
  "One device's most recent /api/display poll telemetry, or nil if it hasn't polled since
   startup."
  [device-id]
  (get @poll-state device-id))

;; --- Device log files -------------------------------------------------------------------

(defn log-file-for
  "The device-log file for one device on one UTC day (a yyyy-MM-dd string)."
  ^File [device-id day]
  (io/file (dir device-id) (str "device-" day ".log")))

(defn- prune-logs!
  "Keeps only the max-log-files newest device-<date>.log files in one device's directory,
   deleting any older ones — so each device self-caps at N days *with data* regardless of
   calendar gaps (a quiet device that skips days still keeps its last N reporting days),
   and no device can evict another's. Filenames sort chronologically (ISO date), so this
   is a plain name sort. Best-effort; runs under log-lock via the caller."
  [^File d]
  (->> (.listFiles d)
    (filter #(re-matches log-name-re (.getName ^File %)))
    (sort-by #(.getName ^File %))              ; oldest first (ISO date sorts chronologically)
    (drop-last max-log-files)                  ; drop the N newest to keep → leaves the surplus
    (run! #(.delete ^File %))))

(defn append-log!
  "Appends one received telemetry body as a single line to a device's today's
   device-<date>.log, creating its dir as needed, then prunes its old days. Line breaks in
   the body are collapsed so each POST stays one physical line (line-based reading in
   read-log depends on it). Best-effort: any IO error is logged and swallowed so the POST
   still gets its 204."
  [device-id body]
  (try
    (let [line (str/replace (str/trim body) #"\R+" " ")
          d    (dir device-id)]
      (locking log-lock
        (.mkdirs d)
        (spit (log-file-for device-id (today-utc-date)) (str line "\n") :append true)
        (prune-logs! d)))
    (catch Exception e
      (log/warn e (str "Could not write device log for " device-id)))))

(defn- parse-log-line
  "Pulls the entry maps out of one device-log line — the raw POST body (`{\"logs\":[…]}`).
   Returns that :logs seq, or nil for a blank/malformed line. Tolerates a leading prefix by
   scanning to the first `{`."
  [line]
  (when-let [i (str/index-of line "{")]
    (:logs (try (json/read-str (subs line i) :key-fn keyword)
             (catch Exception _ nil)))))

(defn read-log
  "Parsed entries from one device's log for one UTC day, in file (chronological) order.
   Empty when that day has no file; nil on a read error (rendered as an empty log either
   way)."
  [device-id day]
  (let [file (log-file-for device-id day)]
    (when (.isFile file)
      (try
        (with-open [r (io/reader file)]
          (->> (line-seq r) (mapcat parse-log-line) vec))
        (catch Exception e
          (log/warn e (str "Could not read device log " (.getName file)))
          nil)))))

(defn log-days
  "The UTC days one device has a device-<date>.log for, newest first — the device page's day
   picker. Ignores names that don't match. Empty when its dir is absent."
  [device-id]
  (let [d (dir device-id)]
    (if (.isDirectory d)
      (->> (.listFiles d)
        (keep (fn [^File f]
                (when-let [[_ day] (re-matches log-name-re (.getName f))]
                  (when (try (LocalDate/parse day) (catch Exception _ nil))
                    day))))
        (sort #(compare %2 %1))
        vec)
      [])))
