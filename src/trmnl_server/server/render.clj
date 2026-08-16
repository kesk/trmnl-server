(ns trmnl-server.server.render
  "The served screens and their caches: renders core/forecast-screen to 1-bit PNG bytes
   in memory, hands them out under a content-hash filename, and keeps them for 10
   minutes. The served bytes never touch disk (out/ stays reserved for the batch-render
   modes) — the only thing written out is the rolling archive copy, via server.archive.

   Alongside the cache sits a second, smaller record: the screen each display actually
   downloaded (mark-fetched!/fetched-entry). The cache answers \"what would we serve if
   asked right now\"; that one answers \"what is on the panel\", which the landing page
   needs and the cache can't tell it — a 10-minute cache against a 15-minute poll means
   the two disagree for a third of every cycle.

   Everything here is per *device*: each registered display has its own cache entry, its
   own regeneration lock, and its own archive subdirectory, keyed by the device's :id
   (see server.devices). Two displays therefore fetch SMHI independently even if they
   sat in the same town — deliberate, since sharing an entry would make it ambiguous
   which device's archive a render belongs to, and the displays are in different places
   anyway."
  (:require [clojure.tools.logging :as log]
            [trmnl-server.core :as core]
            [trmnl-server.image :as img]
            [trmnl-server.server.archive :as archive])
  (:import [java.awt.image BufferedImage]
           [java.io ByteArrayOutputStream]
           [java.security MessageDigest]
           [javax.imageio ImageIO]))

(def ^:private cache-ttl-ms (* 10 60 1000))

;; How long a failed regeneration suppresses the next attempt. Without it the failed
;; entry keeps its old (already expired) :generated-at, so *every* request retries the
;; fetch — and during the first outage in DEVICE-ISSUES.md the device was retrying every
;; ~2s, which is the most plausible way we'd trip an SMHI rate limit while it's already
;; unwell. One attempt a minute is still well inside the device's 15-minute poll cycle,
;; so a recovered SMHI shows up on the next poll either way.
(def ^:private failure-cooldown-ms (* 60 1000))

;; Device :id -> cache entry. One entry per registered display.
(defonce ^:private cache (atom {}))

;; Device :id -> the screen that display actually downloaded: {:filename :bytes :fetched-at}.
;; A copy of the bytes rather than a pointer into `cache`, because the whole point is that it
;; outlives the cache entry it came from — a display goes on showing what it fetched for the
;; full 15 minutes until its next poll, long after the 10-minute cache entry that produced it
;; has expired and been replaced. ~15 KB per display, and nothing else is retained (not the
;; BufferedImage the render cache holds).
;;
;; Deliberately in memory only: it describes what is on a panel *right now*, and after a
;; restart we don't know that any more. The landing page says so rather than guessing.
(defonce ^:private fetched (atom {}))

;; Device :id -> lock object, created on demand by lock-for.
(defonce ^:private locks (atom {}))

(defn- lock-for
  "The regeneration lock for one device, created on first use. Per-device rather than
   one global lock so a slow SMHI fetch for one display can't hold another display's
   poll open — the device waits with its radio powered, which is exactly what the
   wake-time trend on the device page is watching for."
  [device-id]
  (-> (swap! locks (fn [m] (cond-> m (not (contains? m device-id)) (assoc device-id (Object.)))))
    (get device-id)))

(defn- png-bytes [^BufferedImage image]
  (let [out (ByteArrayOutputStream.)]
    (ImageIO/write image "png" out)
    (.toByteArray out)))

(defn- md5-hex [bytes]
  (let [digest (.digest (MessageDigest/getInstance "MD5") bytes)]
    (apply str (map #(format "%02x" %) digest))))

(defn- forecast-hours
  "How many hourly points to render for a device: its own :hours, else $FORECAST_HOURS,
   else the project default. The env var stays as a server-wide default so a registry
   entry only has to mention hours when it wants something unusual."
  [device]
  (or (:hours device)
    (some-> (System/getenv "FORECAST_HOURS") Integer/parseInt)
    core/default-forecast-hours))

(defn- forecast-location
  "Where a device's forecast comes from. :lat/:lon are required of every registry entry
   (server.devices validates them at load), so there's no fallback to reach for — a
   device that doesn't say where it is, isn't a device we can render for."
  [device]
  (select-keys device [:lat :lon]))

(defn- fresh?
  "Whether an entry is young enough to serve without regenerating."
  [entry]
  (and entry (< (- (System/currentTimeMillis) (:generated-at entry)) cache-ttl-ms)))

(defn- cooling-down?
  "Whether an entry's last regeneration failed recently enough that we shouldn't try
   again yet (see failure-cooldown-ms). Cleared implicitly by the next success, which
   replaces the entry with one that has no :failed-at."
  [entry]
  (when-let [failed-at (:failed-at entry)]
    (< (- (System/currentTimeMillis) failed-at) failure-cooldown-ms)))

(defn- serve-as-is?
  "Whether to hand back the cached entry untouched — either it's still fresh, or it's
   stale but we're inside the cooldown after a failed attempt."
  [entry]
  (and entry (or (fresh? entry) (cooling-down? entry))))

(defn- cached-entry
  "The device's cache entry if it has one, without ever regenerating — for the callers
   here that want to look at the cache rather than guarantee a current screen (bytes-for
   and cache-status). nil before the device's first successful render.

   Private because nothing outside this namespace should be asking: the pages want
   fetched-entry (what the display is showing) and the device wants current-image (a
   screen guaranteed fresh), and a peek at the cache is neither of those."
  [device]
  (get @cache (:id device)))

(defn mark-fetched!
  "Records that the display itself just downloaded these bytes under this filename — the
   screen now on its panel. Called from the /images/<id>/… route, and only for a request
   carrying the device's own credentials (the firmware attaches ID + Access-Token to the
   image fetch, see buildImageHeaders), so a browser loading the landing page doesn't
   record itself as the display and restamp the time."
  [device-id filename bytes]
  (swap! fetched assoc device-id {:filename   filename
                                  :bytes      bytes
                                  :fetched-at (System/currentTimeMillis)}))

(defn fetched-entry
  "The screen this display last downloaded, or nil if it hasn't downloaded one since the
   server started. What the landing page shows: the render cache answers \"what would we
   serve if asked right now\", which is a different question from \"what is on the wall\" —
   it turns over every 10 minutes while the display only collects a screen every 15."
  [device]
  (get @fetched (:id device)))

(defn forget!
  "Drops a device's cached screen, so the next request renders from scratch. Called when
   its registry entry has been edited: the cache is keyed by :id, not by location, so a
   display that has just been moved to another town would otherwise keep being served the
   old one's forecast for the rest of cache-ttl-ms — and the archive would keep collecting
   it. Nothing else in the entry is worth keeping either; the whole point of an edit is
   that the render is now wrong.

   Deliberately leaves `fetched` alone: that isn't a prediction of what we'd render, it's a
   record of what the display downloaded, and an edit here doesn't reach through to the
   panel — the old town's screen really is still on it until the display next polls."
  [device-id]
  (swap! cache dissoc device-id))

(defn current-image
  "Returns the device's cached {:bytes :filename :generated-at}, regenerating from a
   fresh forecast when its cache is empty or older than cache-ttl-ms. If regeneration
   throws (e.g. SMHI returns something other than JSON), falls back to serving the
   last successfully rendered image — with a warning badge stamped on a copy of it —
   rather than a bare 500. A stale forecast is more useful to the device than none;
   the badge is what lets you notice at a glance that it's stale and go check the
   logs. Only propagates the exception when there's no prior image to fall back on.

   A failed attempt also stamps :failed-at, which holds off further attempts for
   failure-cooldown-ms — otherwise the entry stays expired and every incoming
   request (device poll, browser hit on / or a device page) fetches SMHI again while
   it's already struggling.

   The `filename` is keyed on an MD5 of the rendered pixels, so it only changes when
   the image actually changes — which is what lets the device skip re-downloading an
   identical screen between polls.

   The regeneration path is serialized on the device's own lock so two requests
   arriving on an expired cache don't both fetch SMHI and re-render; the second
   re-checks the cache inside the lock and reuses the entry the first one just
   produced."
  [device]
  (let [device-id (:id device)
        entry     (get @cache device-id)]
    (if (serve-as-is? entry)
      entry
      (locking (lock-for device-id)
        (let [entry (get @cache device-id)]
          (if (serve-as-is? entry)
            entry
            (try
              (let [location  (forecast-location device)
                    points    (core/live-points (forecast-hours device) location)
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
                (swap! cache assoc device-id new-entry)
                (archive/write! device-id bytes data-hash (:reference-time (meta points)) points)
                new-entry)
              (catch Exception e
                (if entry
                  (let [stale-bytes (or (:stale-bytes entry) (png-bytes (core/stamp-stale-badge (:image entry))))
                        stale-entry (assoc entry
                                      :stale-bytes stale-bytes
                                      :stale-filename (str "forecast-" (md5-hex stale-bytes) "-stale.png")
                                      :failed-at (System/currentTimeMillis)
                                      :failures (inc (:failures entry 0)))]
                    (log/warn e (str "Forecast regeneration failed for " device-id ", serving stale cache"))
                    (swap! cache assoc device-id stale-entry)
                    stale-entry)
                  (throw e))))))))))

(defn cache-status
  "A read-only snapshot of one device's cache for the device page: when its last
   *successful* render happened, when its last attempt failed (nil once a later one
   succeeds), and how many attempts have failed in a row. nil before its first render.

   Deliberately doesn't call current-image: looking at the status page shouldn't fetch
   SMHI, least of all to regenerate the very thing it's reporting on."
  [device]
  (when-let [entry (cached-entry device)]
    {:generated-at (:generated-at entry)
     :failed-at    (:failed-at entry)
     :failures     (:failures entry 0)}))

(defn serve-filename
  "The filename the given entry should be served under — the stale one when the last
   regeneration failed, so callers link the badge-stamped copy."
  [entry]
  (or (:stale-filename entry) (:filename entry)))

(defn bytes-for
  "The PNG bytes this device can be served under `filename`, or nil when nothing we're
   holding has that content hash. Serving only bytes whose hash matches the requested
   filename means a cache rollover between the device's /api/display poll and its image
   fetch 404s (prompting a re-poll) instead of silently serving mismatched bytes.

   Two places are searched, and the second is what makes the landing page work: the live
   cache entry (fresh or stale-badged), then the copy the display last downloaded. The
   cache turns over every 10 minutes and the display only polls every 15, so for a third
   of every cycle the screen on the panel is no longer the screen in the cache — and the
   page that embeds it would 404 without this.

   Deliberately never regenerates. It couldn't help if it did: a new render gets a new
   content hash (the header's per-render \"Uppdaterad HH:mm\" stamp guarantees the pixels
   differ), so it would still fail to match the filename asked for, having fetched SMHI to
   find that out. That is what a browser hit used to do here — one live fetch and a full
   render per card — and it 404'd anyway."
  [device filename]
  (letfn [(match [entry]
            (condp = filename
              (:stale-filename entry) (:stale-bytes entry)
              (:filename entry)       (:bytes entry)
              nil))]
    (or (match (cached-entry device))
      (match (fetched-entry device)))))
