(ns trmnl-server.server.render
  "The served screen and its cache: renders core/forecast-screen to 1-bit PNG bytes in
   memory, hands them out under a content-hash filename, and keeps them for 10 minutes.
   The served bytes never touch disk (out/ stays reserved for the batch-render modes) —
   the only thing written out is the rolling archive copy, via server.archive."
  (:require [clojure.tools.logging :as log]
            [trmnl-server.core :as core]
            [trmnl-server.image :as img]
            [trmnl-server.server.archive :as archive])
  (:import [java.awt.image BufferedImage]
           [java.io ByteArrayOutputStream]
           [java.security MessageDigest]
           [javax.imageio ImageIO]))

(def ^:private cache-ttl-ms (* 10 60 1000))

(defonce ^:private cache (atom nil))
(defonce ^:private regen-lock (Object.))

(defn- png-bytes [^BufferedImage image]
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

(defn current-image
  "Returns the cached {:bytes :filename :generated-at}, regenerating from a fresh
   forecast when the cache is empty or older than cache-ttl-ms. If regeneration
   throws (e.g. SMHI returns something other than JSON), falls back to serving
   the last successfully rendered image — with a warning badge stamped on a
   copy of it — rather than a bare 500. A stale forecast is more useful to the
   device than none; the badge is what lets you notice at a glance that it's
   stale and go check the logs. Only propagates the exception when there's no
   prior image to fall back on.

   The `filename` is keyed on an MD5 of the rendered pixels, so it only changes when
   the image actually changes — which is what lets the device skip re-downloading an
   identical screen between polls.

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
                (archive/write! bytes data-hash (:reference-time (meta points)) points)
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

(defn serve-filename
  "The filename the current entry should be served under — the stale one when the last
   regeneration failed, so callers link the badge-stamped copy."
  [entry]
  (or (:stale-filename entry) (:filename entry)))

(defn bytes-for
  "The PNG bytes matching `filename` in the current cache entry, or nil when it names
   neither the fresh nor the stale image. Serving only the bytes whose content hash
   matches the requested filename means a cache rollover between the device's
   /api/display poll and its image fetch 404s (prompting a re-poll) instead of silently
   serving mismatched bytes."
  [entry filename]
  (condp = filename
    (:stale-filename entry) (:stale-bytes entry)
    (:filename entry)       (:bytes entry)
    nil))
