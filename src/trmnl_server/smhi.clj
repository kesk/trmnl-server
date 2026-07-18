(ns trmnl-server.smhi
  "Client for SMHI's public point-forecast API (category snow1g, replaced pmp3g on 2026-03-31)."
  (:require [clojure.data.json :as json])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration Instant ZoneId]
           [java.time.format DateTimeFormatter]
           [java.util Locale]))

(def ^:private swedish (Locale/forLanguageTag "sv"))

(def gothenburg {:lat 57.7089 :lon 11.9746})

(defn- forecast-url [{:keys [lat lon]}]
  (format "https://opendata-download-metfcst.smhi.se/api/category/snow1g/version/1/geotype/point/lon/%s/lat/%s/data.json"
    lon lat))

(defn fetch-raw-forecast [location]
  (with-open [client (-> (HttpClient/newBuilder)
                       (.connectTimeout (Duration/ofSeconds 10))
                       (.build))]
    (let [request  (-> (HttpRequest/newBuilder)
                     (.uri (URI/create (forecast-url location)))
                     (.timeout (Duration/ofSeconds 10))
                     (.GET)
                     (.build))
          response (.send client request (HttpResponse$BodyHandlers/ofString))]
      (json/read-str (.body response) :key-fn keyword))))

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

(defn local-time-str [iso-time]
  (-> (Instant/parse iso-time)
    (.atZone (ZoneId/of "Europe/Stockholm"))
    (.format (DateTimeFormatter/ofPattern "HH:mm"))))

(defn local-date
  "The Europe/Stockholm calendar date an SMHI timestamp falls on — the unit
   day/night min-max labels are grouped by."
  [iso-time]
  (-> (Instant/parse iso-time)
    (.atZone (ZoneId/of "Europe/Stockholm"))
    (.toLocalDate)))

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
        n       (- (+ (.toEpochDay (local-date iso-time)) 2440588.0) 2451545.0)
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
  (-> (Instant/parse iso-time)
    (.atZone (ZoneId/of "Europe/Stockholm"))
    (.format (DateTimeFormatter/ofPattern "EEE" swedish))))

(defn local-now-str
  "Current time formatted like local-time-str's siblings, but for 'now' rather
   than a forecast point — used to stamp when a screen was rendered."
  []
  (-> (Instant/now)
    (.atZone (ZoneId/of "Europe/Stockholm"))
    (.format (DateTimeFormatter/ofPattern "d MMM HH:mm" swedish))))

(defn upcoming
  "Picks a spread of upcoming forecast points, every `step`'th entry, `count` of them."
  [points & {:keys [count step] :or {count 6 step 3}}]
  (->> points (take-nth step) (take count)))
