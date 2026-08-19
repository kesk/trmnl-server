(ns trmnl-server.smhi
  "Client for SMHI's public point-forecast API (category snow1g, replaced pmp3g on 2026-03-31)."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.io IOException]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration Instant LocalDate ZoneId ZonedDateTime]
           [java.time.format DateTimeFormatter]
           [java.util Locale]))

(def ^Locale swedish
  "The screen's locale. Everything rendered onto the panel is Swedish — weekday
   names here, and the decimal comma in core's mm labels — so pass this
   explicitly rather than letting the JVM's default locale decide: it differs
   between a dev machine and the Pi, which would make the rendered screen an
   environment property."
  (Locale/forLanguageTag "sv"))

(def ^ZoneId screen-zone
  "The zone every time on the panel is rendered in. Pinned rather than derived
   from the display's coordinates, and that is a decision, not an oversight:
   this server serves Swedish forecasts from a Swedish institute's API to
   displays on Swedish walls, so a second zone would be a configuration knob
   with no correct second setting. It stays a `def` so the decision has one
   home — if a display ever does hang somewhere else, this is the line that
   has to become a per-device field, along with `swedish` above."
  (ZoneId/of "Europe/Stockholm"))

(def gothenburg {:lat 57.7089 :lon 11.9746})

(defn- forecast-url [{:keys [lat lon]}]
  (format "https://opendata-download-metfcst.smhi.se/api/category/snow1g/version/1/geotype/point/lon/%s/lat/%s/data.json"
    lon lat))

(defn- body-snippet
  "The head of a response body, whitespace-collapsed, for error context — enough to
   recognise an HTML error page in a log line without dumping the whole document."
  [body]
  (let [flat (str/trim (str/replace (str body) #"\s+" " "))]
    (if (> (count flat) 200)
      (str (subs flat 0 200) "…")
      flat)))

(defn- fetch-once
  "One request/response cycle: fetches and parses SMHI's point forecast for `location`.

   Both failure paths throw `ex-info` carrying `{:url :status :body}` rather than
   letting the JSON reader speak for them: SMHI answers an outage, a rate-limit or a
   moved endpoint with an HTML page, and handing that straight to `json/read-str`
   surfaces as `JSON error (unexpected character): <`, which says nothing about what
   actually went wrong (see DEVICE-ISSUES.md). With the status and a snippet of the
   body in the exception, the log line names the real failure, and a caller can tell a
   retryable 5xx/429 from a 404 that means the API moved again — as pmp3g → snow1g
   did on 2026-03-31."
  [location]
  (with-open [client (-> (HttpClient/newBuilder)
                       (.connectTimeout (Duration/ofSeconds 10))
                       (.build))]
    (let [url      (forecast-url location)
          request  (-> (HttpRequest/newBuilder)
                     (.uri (URI/create url))
                     (.timeout (Duration/ofSeconds 10))
                     (.GET)
                     (.build))
          response (.send client request (HttpResponse$BodyHandlers/ofString))
          status   (.statusCode response)
          body     (.body response)]
      (when-not (<= 200 status 299)
        (throw (ex-info (str "SMHI request failed: HTTP " status)
                 {:url url :status status :body (body-snippet body)})))
      (try
        (json/read-str body :key-fn keyword)
        (catch Exception e
          ;; A 2xx that isn't JSON — an error page served with the wrong status, or a
          ;; truncated response. Same diagnosis problem, same context attached.
          (throw (ex-info (str "SMHI returned unparseable JSON (HTTP " status ")")
                   {:url url :status status :body (body-snippet body)}
                   e)))))))

(def ^:private max-attempts 3)
(def ^:private retry-delays-ms [1000 2000])

;; A ceiling on the whole retry sequence, not just the pauses: a new attempt only starts
;; if we're still inside it. Each request already has its own 10s timeout, so without this
;; three hanging attempts could keep a device's /api/display poll waiting ~33s — well past
;; the point where the device gives up and re-polls anyway.
(def ^:private retry-budget-ms 15000)

(defn- retryable?
  "Whether a failed fetch is worth another attempt. Retryable: a network or timeout
   error (no response at all), a 429, a 5xx, or a 2xx whose body wouldn't parse — the
   transient shapes behind every episode in DEVICE-ISSUES.md. Not retryable: any other
   4xx, which means our URL is wrong rather than SMHI being unwell, and a 404 in
   particular most likely means the API moved again (pmp3g → snow1g). Anything else
   propagates immediately — a bug here shouldn't be tried three times."
  [e]
  (if-let [status (:status (ex-data e))]
    (or (= 429 status) (<= 500 status 599) (<= 200 status 299))
    (instance? IOException e)))

(defn fetch-raw-forecast
  "Fetches and parses SMHI's point forecast for `location`, retrying a transient
   failure up to max-attempts times with a short backoff (see fetch-once for the
   error shape, retryable? for what counts as transient).

   SMHI intermittently answers with an HTML error page instead of JSON; the episodes
   logged so far lasted from a single request to ~30 seconds, so a couple of quick
   retries covers the short ones outright and the stale-cache fallback in
   server.render still covers the rest. Gives up early once retry-budget-ms has
   elapsed, and rethrows the last failure so the caller sees the real cause."
  [location]
  (let [deadline (+ (System/currentTimeMillis) retry-budget-ms)]
    (loop [attempt 1]
      (let [result (try
                     {:ok (fetch-once location)}
                     (catch Exception e
                       (let [delay-ms (nth retry-delays-ms (dec attempt) (last retry-delays-ms))]
                         (if (and (< attempt max-attempts)
                               (retryable? e)
                               (< (+ (System/currentTimeMillis) delay-ms) deadline))
                           {:retry e :delay-ms delay-ms}
                           (throw e)))))]
        (if-let [e (:retry result)]
          (do
            (log/warnf "SMHI fetch attempt %d/%d failed (%s), retrying in %dms"
              attempt max-attempts (ex-message e) (:delay-ms result))
            (Thread/sleep (long (:delay-ms result)))
            (recur (inc attempt)))
          (:ok result))))))

(defn- ->forecast-point [{:keys [time data]}]
  {:time          time
   :temp          (:air_temperature data)
   :symbol        (:symbol_code data)
   :wind          (:wind_speed data)
   :precip-chance (:probability_of_precipitation data)
   :precip-mm     (:precipitation_amount_mean data)
   :cloud-cover   (:cloud_area_fraction data)})

(defn forecast
  "Returns a seq of forecast points for the given location, sorted by time (nearest first).
   The seq carries the SMHI forecast run's issuance time as `:reference-time` metadata
   (the response's top-level `referenceTime`), for callers that want to tag a render with
   which run it came from — note plain seq ops like `take` drop metadata, so preserve it
   with `with-meta` when slicing (see core/live-points)."
  [location]
  (let [raw (fetch-raw-forecast location)]
    (with-meta
      (map ->forecast-point (:timeSeries raw))
      {:reference-time (:referenceTime raw)})))

(def symbol->description
  {1  "Klart väder"                 2  "Mestadels klart"          3  "Växlande molnighet"       4  "Halvklart"
   5  "Molnigt"                     6  "Mulet"                    7  "Dimma"                    8  "Lätta regnskurar"            9  "Måttliga regnskurar"
   10 "Kraftiga regnskurar"         11 "Åskväder"                 12 "Lätta snöblandade skurar" 13 "Måttliga snöblandade skurar"
   14 "Kraftiga snöblandade skurar" 15 "Lätta snöbyar"            16 "Måttliga snöbyar"         17 "Kraftiga snöbyar"
   18 "Lätt regn"                   19 "Måttligt regn"            20 "Kraftigt regn"            21 "Åska"                        22 "Lätt snöblandat regn"
   23 "Måttligt snöblandat regn"    24 "Kraftigt snöblandat regn" 25 "Lätt snöfall"             26 "Måttligt snöfall"            27 "Kraftigt snöfall"})

(def thunder-symbol-codes
  "Wsymb2 codes that denote thunder: 11 (Åskväder — thunderstorm showers) and
   21 (Åska — thunder). The point forecast has no dedicated lightning/thunder
   parameter, so the symbol code is the only thunder signal available."
  #{11 21})

(defn thunder?
  "Whether an SMHI symbol code denotes thunder (see thunder-symbol-codes)."
  [symbol-code]
  (contains? thunder-symbol-codes symbol-code))

(def ^:private hour-format (DateTimeFormatter/ofPattern "HH:mm"))
(def ^:private day-format (DateTimeFormatter/ofPattern "EEE" swedish))
(def ^:private stamp-format (DateTimeFormatter/ofPattern "d MMM HH:mm" swedish))

(defn- screen-time
  "An SMHI ISO timestamp as wall-clock time in screen-zone — the pipeline the
   helpers below share. A DateTimeFormatter is immutable and thread-safe, which
   is why the three above are `def`s rather than rebuilt per call."
  ^ZonedDateTime [iso-time]
  (.atZone (Instant/parse iso-time) screen-zone))

(defn local-time-str [iso-time]
  (.format (screen-time iso-time) hour-format))

(defn local-date
  "The Europe/Stockholm calendar date an SMHI timestamp falls on — the unit
   day/night min-max labels are grouped by."
  [iso-time]
  (.toLocalDate (screen-time iso-time)))

(defn- julian->instant [jd]
  (Instant/ofEpochMilli (long (* (- jd 2440587.5) 86400000.0))))

(defn sun-times
  "Sunrise and sunset Instants for `location` ({:lat :lon}) on the
   Europe/Stockholm calendar date that `iso-time` falls on, via the NOAA
   sunrise equation (accurate to ~1 min — plenty for picking an icon, and no
   network call or dependency). Returns {:sunrise Instant :sunset Instant}, or
   {:polar-day? true} / {:polar-night? true} at high latitudes on days when the
   sun never sets / never rises."
  [{:keys [lat lon]} iso-time]
  (let [;; J2000 (2451545.0) is 2000-01-01 12:00 UTC, whose epoch day is 10957,
        ;; so (epoch-day + 2440588.0) is the Julian date at noon UTC and n the
        ;; whole days since J2000 — the mean-solar-noon base the equation wants.
        n       (- (+ (LocalDate/.toEpochDay (local-date iso-time)) 2440588.0) 2451545.0)
        j*      (- n (/ lon 360.0))                       ; mean solar noon (lon east-positive → noon comes earlier in UTC)
        m       (mod (+ 357.5291 (* 0.98560028 j*)) 360.0) ; solar mean anomaly (deg)
        mr      (Math/toRadians m)
        c       (+ (* 1.9148 (Math/sin mr))               ; equation of the center
                  (* 0.0200 (Math/sin (* 2 mr)))
                  (* 0.0003 (Math/sin (* 3 mr))))
        lambda  (Math/toRadians (mod (+ m c 180.0 102.9372) 360.0)) ; ecliptic longitude
        transit (+ 2451545.0 j* (* 0.0053 (Math/sin mr)) (* -0.0069 (Math/sin (* 2 lambda))))
        decl    (Math/asin (* (Math/sin lambda) (Math/sin (Math/toRadians 23.44))))
        latr    (Math/toRadians lat)
        cos-w   (/ (- (Math/sin (Math/toRadians -0.833)) ; -0.833° = refraction + solar radius
                     (* (Math/sin latr) (Math/sin decl)))
                  (* (Math/cos latr) (Math/cos decl)))]
    (cond
      (< cos-w -1.0) {:polar-day? true}
      (> cos-w 1.0)  {:polar-night? true}
      :else (let [w (/ (Math/toDegrees (Math/acos cos-w)) 360.0)]
              {:sunrise (julian->instant (- transit w))
               :sunset  (julian->instant (+ transit w))}))))

(defn night?
  "Whether `iso-time` falls between sunset and sunrise at `location`
   ({:lat :lon}), via the astronomical sun-times calculation — used only to
   pick the day/night icon variant. Correct year-round (unlike a fixed-hour
   cutoff), and handles polar day/night at high latitudes."
  [location iso-time]
  (let [t                                                (Instant/parse iso-time)
        {:keys [sunrise sunset polar-day? polar-night?]} (sun-times location iso-time)]
    (cond
      polar-day?   false
      polar-night? true
      :else        (or (.isBefore t sunrise) (.isAfter t sunset)))))

(defn local-day-label [iso-time]
  (.format (screen-time iso-time) day-format))

(defn local-now-str
  "Current time formatted like local-time-str's siblings, but for 'now' rather
   than a forecast point — used to stamp when a screen was rendered."
  []
  (.format (.atZone (Instant/now) screen-zone) stamp-format))
