(ns trmnl-server.server.telemetry
  "Everything the device tells us about itself, and where it's kept: the last
   /api/display poll's headers, the rolling wake-time series, and the raw
   /api/log bodies on disk. Storage and aggregation only — the HTTP endpoints
   that feed it live in server, the /status rendering of it in server.pages.

   Device telemetry is written straight to disk, bypassing logback entirely: one
   raw JSON line per POST into logs/device-<yyyy-MM-dd>.log, the file chosen by
   the UTC date so the filename does the daily partitioning a rolling policy used
   to. Only this module's own failures go through the main logger."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.io File]
           [java.time LocalDate ZoneOffset]))

(def ^:private max-log-files 7)

;; How far back wake-time samples are kept — sets the longest trend window (7d) on /status.
(def wake-retention-ms (* 7 24 60 60 1000))

(defonce ^:private log-lock (Object.))
(defonce ^:private wake-lock (Object.))
(defonce ^:private poll-state (atom nil))
;; Rolling series of {:t <epoch-ms> :ms <awake-ms>} device wake durations, oldest→newest,
;; persisted to disk so the /status trend survives restarts. See record-wake-time! below.
(defonce ^:private wake-history (atom []))

(def ^:private log-name-re #"device-(\d{4}-\d{2}-\d{2})\.log")

(defn dir
  "Directory device-<date>.log files live in — $DEVICE_LOG_DIR, else logs/ relative to the
   process's working dir (the systemd unit's WorkingDirectory in prod)."
  ^File []
  (io/file (or (System/getenv "DEVICE_LOG_DIR") "logs")))

(defn today-utc-date
  "Today's UTC calendar date as a yyyy-MM-dd string — the day a just-received row files
   under, and the /status default view."
  []
  (str (LocalDate/now ZoneOffset/UTC)))

;; --- Device wake-time trend -------------------------------------------------------------
;; The firmware's Wake-Time header reports how long the device was awake during its previous
;; cycle (ms) — a health signal, since a device fighting weak WiFi stays awake longer and
;; drains the battery. We keep a rolling series of these samples (persisted so it survives
;; restarts) and surface the latest value plus moving averages on /status.

(defn- wake-file
  "Where the wake-time series is persisted — a single EDN file alongside the device logs."
  ^File []
  (io/file (dir) "wake-times.edn"))

(defn- prune-wake
  "Drops samples older than the retention window (by their :t timestamp)."
  [samples now]
  (let [cutoff (- now wake-retention-ms)]
    (filterv #(>= (:t %) cutoff) samples)))

(defn load-wake-history!
  "Reads the persisted wake-time series into the atom at startup, pruning stale samples.
   Best-effort: a missing or corrupt file just leaves the history empty."
  []
  (let [f (wake-file)]
    (when (.isFile f)
      (try
        (reset! wake-history
          (prune-wake (vec (read-string (slurp f))) (System/currentTimeMillis)))
        (catch Exception e
          (log/warn e "Could not read wake-time history"))))))

(defn- record-wake-time!
  "Appends one wake-duration sample (ms), prunes to the retention window, and persists.
   Ignores nil/non-positive values — the firmware sends 0 on a fresh boot with no previous
   cycle, which would otherwise drag the averages down. Persistence is best-effort: an IO
   error is logged and swallowed so the device poll still succeeds."
  [wake-ms]
  (when (and wake-ms (pos? wake-ms))
    (locking wake-lock
      (let [now     (System/currentTimeMillis)
            samples (prune-wake (conj @wake-history {:t now :ms wake-ms}) now)]
        (reset! wake-history samples)
        (try
          (.mkdirs (dir))
          (spit (wake-file) (pr-str samples))
          (catch Exception e
            (log/warn e "Could not write wake-time history")))))))

(defn wake-samples
  "The rolling wake-time series, oldest→newest, as {:t :ms} maps."
  []
  @wake-history)

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
  "Takes the telemetry parsed off one /api/display poll: keeps it as the latest snapshot
   for /status's summary cards, and feeds its Wake-Time into the rolling trend."
  [status]
  (reset! poll-state status)
  (record-wake-time! (:wake-time status)))

(defn poll-status
  "The most recent /api/display poll's telemetry, or nil if the device hasn't polled since
   startup."
  []
  @poll-state)

;; --- Device log files -------------------------------------------------------------------

(defn log-file-for
  "The device-log file for one UTC day (a yyyy-MM-dd string)."
  ^File [day]
  (io/file (dir) (str "device-" day ".log")))

(defn- prune-logs!
  "Keeps only the max-log-files newest device-<date>.log files, deleting any older
   ones — so the folder self-caps at N days *with data* regardless of calendar gaps (a quiet
   device that skips days still keeps its last N reporting days). Filenames sort
   chronologically (ISO date), so this is a plain name sort. Best-effort; runs under
   log-lock via the caller."
  [^File d]
  (->> (.listFiles d)
    (filter #(re-matches log-name-re (.getName ^File %)))
    (sort-by #(.getName ^File %))              ; oldest first (ISO date sorts chronologically)
    (drop-last max-log-files)                  ; drop the N newest to keep → leaves the surplus
    (run! #(.delete ^File %))))

(defn append-log!
  "Appends one received telemetry body as a single line to today's device-<date>.log,
   creating the dir as needed, then prunes old days. Line breaks in the body are collapsed
   so each POST stays one physical line (line-based reading in read-log depends on it).
   Best-effort: any IO error is logged and swallowed so the POST still gets its 204."
  [body]
  (try
    (let [line (str/replace (str/trim body) #"\R+" " ")
          d    (dir)]
      (locking log-lock
        (.mkdirs d)
        (spit (log-file-for (today-utc-date)) (str line "\n") :append true)
        (prune-logs! d)))
    (catch Exception e
      (log/warn e "Could not write device log"))))

(defn- parse-log-line
  "Pulls the entry maps out of one device-log line — the raw POST body (`{\"logs\":[…]}`).
   Returns that :logs seq, or nil for a blank/malformed line. Tolerates a leading prefix by
   scanning to the first `{`."
  [line]
  (when-let [i (str/index-of line "{")]
    (:logs (try (json/read-str (subs line i) :key-fn keyword)
             (catch Exception _ nil)))))

(defn read-log
  "Parsed entries from one UTC day's device log, in file (chronological) order. Empty when
   the day has no file; nil on a read error (rendered as an empty log either way)."
  [day]
  (let [file (log-file-for day)]
    (when (.isFile file)
      (try
        (with-open [r (io/reader file)]
          (->> (line-seq r) (mapcat parse-log-line) vec))
        (catch Exception e
          (log/warn e (str "Could not read device log " (.getName file)))
          nil)))))

(defn log-days
  "The UTC days with a device-<date>.log on disk, newest first — the /status day picker.
   Ignores names that don't match (any legacy device.log/.gz). Empty when the dir is absent."
  []
  (let [d (dir)]
    (if (.isDirectory d)
      (->> (.listFiles d)
        (keep (fn [^File f]
                (when-let [[_ day] (re-matches log-name-re (.getName f))]
                  (when (try (LocalDate/parse day) (catch Exception _ nil))
                    day))))
        (sort #(compare %2 %1))
        vec)
      [])))
