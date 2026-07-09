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
  (let [client   (-> (HttpClient/newBuilder)
                   (.connectTimeout (Duration/ofSeconds 10))
                   (.build))
        request  (-> (HttpRequest/newBuilder)
                   (.uri (URI/create (forecast-url location)))
                   (.timeout (Duration/ofSeconds 10))
                   (.GET)
                   (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofString))]
    (json/read-str (.body response) :key-fn keyword)))

(defn- ->forecast-point [{:keys [time data]}]
  {:time          time
   :temp          (:air_temperature data)
   :symbol        (:symbol_code data)
   :wind          (:wind_speed data)
   :precip-chance (:probability_of_precipitation data)
   :precip-mm     (:precipitation_amount_mean data)
   :cloud-cover   (:cloud_area_fraction data)})

(defn forecast
  "Returns a seq of forecast points for the given location, sorted by time (nearest first)."
  [location]
  (->> (fetch-raw-forecast location)
    :timeSeries
    (map ->forecast-point)))

(def symbol->description
  {1  "Klart väder"                 2  "Mestadels klart"          3  "Växlande molnighet"       4  "Halvklart"
   5  "Molnigt"                     6  "Mulet"                    7  "Dimma"                    8  "Lätta regnskurar"            9  "Måttliga regnskurar"
   10 "Kraftiga regnskurar"         11 "Åskväder"                 12 "Lätta snöblandade skurar" 13 "Måttliga snöblandade skurar"
   14 "Kraftiga snöblandade skurar" 15 "Lätta snöbyar"            16 "Måttliga snöbyar"         17 "Kraftiga snöbyar"
   18 "Lätt regn"                   19 "Måttligt regn"            20 "Kraftigt regn"            21 "Åska"                        22 "Lätt snöblandat regn"
   23 "Måttligt snöblandat regn"    24 "Kraftigt snöblandat regn" 25 "Lätt snöfall"             26 "Måttligt snöfall"            27 "Kraftigt snöfall"})

(defn night?
  "Fixed-hour heuristic for whether an SMHI timestamp falls at night, used
   only to pick the day/night icon variant — not a real sunrise/sunset
   calculation."
  [iso-time]
  (let [hour (.getHour (.atZone (Instant/parse iso-time) (ZoneId/of "Europe/Stockholm")))]
    (or (< hour 6) (>= hour 21))))

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
