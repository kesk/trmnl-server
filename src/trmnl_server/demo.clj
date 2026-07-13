(ns trmnl-server.demo
  "Synthetic day-in-the-life datasets for the --demo flag: representative
   (not live) Gothenburg weather for each season, built in the same point
   shape trmnl-server.smhi/forecast produces so they can run through the exact
   same rendering pipeline as a real forecast."
  (:import [java.time LocalDate ZoneId]))

(def seasons
  "Rough Gothenburg climate normals per season: a mean/swing for the diurnal
   temp, wind and cloud-cover curves, a symbol pair (clear vs. precipitating),
   and which hours-of-day see that precipitation. Cloud cover is in octas
   (0-8), matching SMHI's cloud_area_fraction, so it renders identically to a
   live forecast."
  [{:label        "Winter" :file          "demo-winter" :start-date "2026-01-15"
    :base-temp    0.0      :temp-swing    2.0
    :base-wind    6.0      :wind-swing    2.0
    :base-cloud   6.4      :cloud-swing   1.2
    :clear-symbol 6        :precip-symbol 15            :precip-mm  0.4          :precip-window #{10 11 12}}
   {:label        "Spring" :file          "demo-spring" :start-date "2026-04-15"
    :base-temp    7.0      :temp-swing    4.0
    :base-wind    5.0      :wind-swing    2.0
    :base-cloud   4.4      :cloud-swing   2.0
    :clear-symbol 3        :precip-symbol 8             :precip-mm  0.6          :precip-window #{15 16}}
   {:label        "Summer" :file          "demo-summer" :start-date "2026-07-15"
    :base-temp    18.0     :temp-swing    5.0
    :base-wind    4.0      :wind-swing    1.5
    :base-cloud   2.8      :cloud-swing   1.6
    :clear-symbol 2        :precip-symbol 11            :precip-mm  1.2          :precip-window #{17}}
   {:label        "Autumn" :file          "demo-autumn" :start-date "2026-10-15"
    :base-temp    9.0      :temp-swing    3.0
    :base-wind    7.0      :wind-swing    3.0
    :base-cloud   6.8      :cloud-swing   0.8
    :clear-symbol 6        :precip-symbol 19            :precip-mm  1.5          :precip-window (set (range 9 17))}])

(defn- clamp [v lo hi] (max lo (min hi v)))

(defn season-points
  "Generates `hours` hourly points for a season map from `seasons`, starting
   at local midnight on start-date. Temp/wind/cloud follow simple diurnal
   sine curves around the season's normals rather than real observations —
   enough to look like a typical day, not a claim of historical accuracy."
  [{:keys [start-date base-temp temp-swing base-wind wind-swing
           base-cloud cloud-swing clear-symbol precip-symbol precip-mm precip-window]}
   hours]
  (let [start (-> (LocalDate/parse start-date)
                (.atStartOfDay (ZoneId/of "Europe/Stockholm"))
                (.toInstant))]
    (for [h (range hours)]
      (let [hour-of-day (mod h 24)
            temp        (+ base-temp (* temp-swing (Math/sin (* 2 Math/PI (/ (- hour-of-day 9) 24.0)))))
            wind        (clamp (+ base-wind (* wind-swing (Math/sin (* 2 Math/PI (/ hour-of-day 9.0))))) 0.0 30.0)
            cloud       (clamp (+ base-cloud (* cloud-swing (Math/sin (* 2 Math/PI (/ (+ hour-of-day 3) 17.0))))) 0.0 8.0)
            raining?    (contains? precip-window hour-of-day)]
        {:time          (str (.plusSeconds start (* h 3600)))
         :temp          (Math/round temp)
         :wind          (/ (Math/round (* wind 10)) 10.0)
         :symbol        (if raining? precip-symbol clear-symbol)
         :cloud-cover   (Math/round cloud)
         :precip-mm     (if raining? precip-mm 0.0)
         :precip-chance (if raining? 80 5)}))))

(defn rain-test-points
  "A deliberately unrealistic day that decouples rain probability from rain
   amount to exercise every case the precipitation chart has to render — the one
   thing the season datasets can't show, since season-points ties chance and mm
   together. Across the day: likely-but-light (high chance, ~0mm — a tall
   probability line over no bar), unlikely-but-heavy (low chance, big mm — the
   low line crossing tall black bars, which tests the white-over-black line
   pass), likely-and-heavy (both high), and dry. Hour-of-day based so it still
   honours --hours like the seasons do."
  [hours]
  (let [start (-> (LocalDate/parse "2026-07-12")
                (.atStartOfDay (ZoneId/of "Europe/Stockholm"))
                (.toInstant))]
    (for [h (range hours)]
      (let [hod         (mod h 24)
            [chance mm] (cond
                          (<= 6 hod 9)   [75 0.05] ; likely but light
                          (<= 10 hod 13) [20 2.2]  ; heavy but unlikely
                          (<= 14 hod 17) [85 1.5]  ; likely and heavy
                          :else          [8 0.0])  ; dry
            wet?        (or (pos? mm) (>= chance 50))
            temp        (+ 16 (* 4 (Math/sin (* 2 Math/PI (/ (- hod 9) 24.0)))))]
        {:time          (str (.plusSeconds start (* h 3600)))
         :temp          (Math/round temp)
         :wind          (+ 3.0 (double (mod h 3)))
         :symbol        (if wet? 18 3)
         :cloud-cover   (if wet? 7 3)
         :precip-mm     mm
         :precip-chance chance}))))
