#!/usr/bin/env bb
;; One-off migration: convert the OLD logback device logs into the NEW per-date layout that
;; /status now reads, so the dashboard has historical data to show.
;;
;;   old:  device.log            (active, "<receive-ts> {json}" lines)
;;         device.log.<date>.gz  (rolled, gzipped, same line format)
;;   new:  device-<yyyy-MM-dd>.log   (one raw JSON line per POST, no prefix)
;;
;; Each line is bucketed by the UTC day of the entries' `created_at` (the earliest, for a
;; batch), NOT by the line's timestamp prefix: the old logback wrote that prefix in the
;; Pi's LOCAL zone, so bucketing on it shifted evening rows into the next day. created_at is
;; epoch seconds (UTC) and is exactly what /status renders, so bucketing on it keeps each
;; day page honest. The raw JSON (from the first "{") is written verbatim.
;;
;; Re-runnable: it OVERWRITES the device-<date>.log it produces for past days (so a bad
;; earlier run is corrected), but never touches TODAY's file — that one is owned by the
;; live server, and clobbering it would drop telemetry received since the deploy.
;;
;;   bb migrate_device_logs.clj [in-dir] [out-dir]     (both default to "logs")

(require '[clojure.java.io :as io]
         '[clojure.string :as str])
(import '[java.util.zip GZIPInputStream]
        '[java.time LocalDate Instant ZoneOffset])

(defn valid-date [s]
  (when (and s (re-matches #"\d{4}-\d{2}-\d{2}" s))
    (try (LocalDate/parse s) s (catch Exception _ nil))))

(defn line-day
  "The UTC day a legacy line belongs to: the earliest `created_at` among its entries (a POST
   body can batch several), else the line's prefix date as a last resort."
  [line]
  (let [epochs (keep #(parse-long (second %)) (re-seq #"\"created_at\"\s*:\s*(\d+)" line))]
    (if (seq epochs)
      (str (LocalDate/ofInstant (Instant/ofEpochSecond (apply min epochs)) ZoneOffset/UTC))
      (valid-date (when (>= (count line) 10) (subs line 0 10))))))

(defn raw-json [line]
  (when-let [i (str/index-of line "{")]
    (str/trim (subs line i))))

(defn read-lines [^java.io.File f]
  (with-open [r (io/reader (if (str/ends-with? (.getName f) ".gz")
                             (GZIPInputStream. (io/input-stream f))
                             (io/input-stream f)))]
    (vec (line-seq r))))

(defn source-files
  "Legacy device.log + device.log.<date>.gz in in-dir, oldest first (active file last) so
   accumulated lines stay chronological."
  [in-dir]
  (let [dir (io/file in-dir)]
    (when (.isDirectory dir)
      (let [fs     (seq (.listFiles dir))
            active (filter #(= "device.log" (.getName ^java.io.File %)) fs)
            gz     (->> fs
                     (filter #(re-matches #"device\.log\.\d{4}-\d{2}-\d{2}\.gz" (.getName ^java.io.File %)))
                     (sort-by #(.getName ^java.io.File %)))]
        (concat gz active)))))

(let [[in-dir out-dir] *command-line-args*
      in-dir  (or in-dir "logs")
      out-dir (or out-dir "logs")
      today   (str (LocalDate/now ZoneOffset/UTC))
      files   (source-files in-dir)]
  (when-not (seq files)
    (println (str "No legacy device.log / device.log.*.gz found in " in-dir "/ — nothing to do."))
    (System/exit 0))
  (println (str "Reading " (count files) " legacy file(s) from " in-dir "/ ..."))
  (let [by-day (reduce (fn [acc line]
                         (if-let [day (and (raw-json line) (line-day line))]
                           (update acc day (fnil conj []) (raw-json line))
                           acc))
                 {}
                 (mapcat read-lines files))]
    (io/make-parents (io/file out-dir "x"))
    (doseq [[day lines] (sort by-day)]
      (let [target (io/file out-dir (str "device-" day ".log"))]
        (if (= day today)
          (println (str "  skip  device-" day ".log (today — live file, not touched)"))
          (do (spit target (str (str/join "\n" lines) "\n"))
              (println (str "  write device-" day ".log  (" (count lines) " lines)"))))))
    (println (str "Done. " (count by-day) " day(s) covered."))))
