(ns trmnl-server.smhi
  "Client for SMHI's public point-forecast API (category snow1g, replaced pmp3g on 2026-03-31)."
  (:require [clojure.data.json :as json])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.time Instant ZoneId]
           [java.time.format DateTimeFormatter]))

(def gothenburg {:lat 57.7089 :lon 11.9746})

(defn- forecast-url [{:keys [lat lon]}]
  (format "https://opendata-download-metfcst.smhi.se/api/category/snow1g/version/1/geotype/point/lon/%s/lat/%s/data.json"
          lon lat))

(defn fetch-raw-forecast [location]
  (let [client (HttpClient/newHttpClient)
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (forecast-url location)))
                    (.GET)
                    (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofString))]
    (json/read-str (.body response) :key-fn keyword)))

(defn- ->forecast-point [{:keys [time data]}]
  {:time time
   :temp (:air_temperature data)
   :symbol (:symbol_code data)
   :wind (:wind_speed data)
   :precip-chance (:probability_of_precipitation data)
   :cloud-cover (:cloud_area_fraction data)})

(defn forecast
  "Returns a seq of forecast points for the given location, sorted by time (nearest first)."
  [location]
  (->> (fetch-raw-forecast location)
       :timeSeries
       (map ->forecast-point)))

(def symbol->description
  {1 "Clear sky" 2 "Nearly clear" 3 "Variable cloudiness" 4 "Halfclear sky"
   5 "Cloudy sky" 6 "Overcast" 7 "Fog" 8 "Light rain showers" 9 "Moderate rain showers"
   10 "Heavy rain showers" 11 "Thunderstorm" 12 "Light sleet showers" 13 "Moderate sleet showers"
   14 "Heavy sleet showers" 15 "Light snow showers" 16 "Moderate snow showers" 17 "Heavy snow showers"
   18 "Light rain" 19 "Moderate rain" 20 "Heavy rain" 21 "Thunder" 22 "Light sleet"
   23 "Moderate sleet" 24 "Heavy sleet" 25 "Light snowfall" 26 "Moderate snowfall" 27 "Heavy snowfall"})

(defn local-time-str [iso-time]
  (-> (Instant/parse iso-time)
      (.atZone (ZoneId/of "Europe/Stockholm"))
      (.format (DateTimeFormatter/ofPattern "HH:mm"))))

(defn upcoming
  "Picks a spread of upcoming forecast points, every `step`'th entry, `count` of them."
  [points & {:keys [count step] :or {count 6 step 3}}]
  (->> points (take-nth step) (take count)))
