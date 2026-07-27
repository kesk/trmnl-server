(ns trmnl-server.server.archive
  "The rolling 24h on-disk archive of rendered screens: where it lives, what gets
   written, and what gets pruned. Pure storage — no HTTP, no HTML. The /archive
   gallery (server.pages) and the file route (server) read through `dir`/`entries`;
   the render cache (server.render) writes through `write!`.

   Every *successful* render is written here (i.e. each new cache entry, ~one per
   10-min cache miss, not the stale-fallback copies), so a problematic screen
   spotted after the fact can still be recovered and saved. Files older than 24h
   are pruned by mtime on each write, so the folder self-manages a rolling window
   with no cron."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.io File]
           [java.time Instant LocalDateTime ZoneId]
           [java.time.format DateTimeFormatter]))

(def ^:private retention-ms (* 24 60 60 1000))

(defn dir
  "Where --serve stows a rolling copy of every rendered screen, relative to the
   process's working directory (the systemd unit's WorkingDirectory in prod, so
   /home/seb/trmnl-server/archive/…) unless ARCHIVE_DIR overrides it — mirroring how
   logs/ is placed. Distinct from out/, which stays reserved for the batch-render modes."
  []
  (or (System/getenv "ARCHIVE_DIR") "archive"))

(def ^:private timestamp-format
  (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss"))

(defn- prune!
  "Deletes archived PNGs older than retention-ms so the folder self-manages a
   rolling 24h window (by file mtime, which is the write time) without any external cron."
  [^File d]
  (let [cutoff (- (System/currentTimeMillis) retention-ms)]
    (doseq [^File f (.listFiles d)]
      (when (and (.isFile f)
              (or (str/ends-with? (.getName f) ".png")
                (str/ends-with? (.getName f) ".edn"))
              (< (.lastModified f) cutoff))
        (.delete f)))))

(def ^:private hash-pattern
  ;; Captures the trailing <8 hex> content-hash segment of an archive filename, tolerant of
  ;; the optional `-run<...>` reference-time segment in the middle (see write!).
  #"-([0-9a-f]{8})\.png\z")

(def ^:private reference-run-format
  (DateTimeFormatter/ofPattern "yyyyMMdd-HHmm"))

(defn- reference-run-token
  "A compact local-time token identifying the SMHI forecast run (its issuance
   `referenceTime`) for the archive filename, e.g. \"run20260713-0945\". nil for a nil
   reference-time (e.g. demo data, which is never archived anyway)."
  [reference-time]
  (when reference-time
    (str "run" (.format (LocalDateTime/ofInstant (Instant/parse reference-time)
                          (ZoneId/systemDefault))
                 reference-run-format))))

(defn- pngs
  "Every archived PNG in `d`, newest first by mtime. The .png filter matters for
   last-hash: the sibling .edn is written just after the PNG, hence newer, and
   would otherwise shadow the content hash and defeat dedupe."
  [^File d]
  (->> (.listFiles d)
    (filter #(and (.isFile ^File %) (str/ends-with? (.getName ^File %) ".png")))
    (sort-by #(- (.lastModified ^File %)))))

(defn- last-hash
  "Short content hash of the most recently written archive file, or nil when the dir is
   empty or its newest file predates the hashed-filename scheme. Lets write! skip
   re-writing a render whose forecast matches the one already on top of the archive."
  [^File d]
  (some->> (pngs d) first (#(.getName ^File %)) (re-find hash-pattern) second))

(defn write!
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
    (let [d          (io/file (dir))
          short-hash (subs hash 0 8)]
      (.mkdirs d)
      (when-not (= short-hash (last-hash d))
        (let [stamp (.format (LocalDateTime/now) timestamp-format)
              run   (reference-run-token reference-time)
              base  (str "forecast-" stamp (when run (str "-" run)) "-" short-hash)]
          (with-open [out (io/output-stream (io/file d (str base ".png")))]
            (.write out ^bytes bytes))
          (spit (io/file d (str base ".edn")) (pr-str points)))
        (prune! d)))
    (catch Exception e
      (log/warn e "Could not archive rendered image"))))

(defn entries
  "Archived PNGs, newest first (by mtime), for the gallery. Empty when the dir is absent.
   The gallery lists only PNGs; each one's sibling .edn is offered as a data link."
  []
  (let [d (io/file (dir))]
    (if (.isDirectory d) (vec (pngs d)) [])))
