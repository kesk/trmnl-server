(ns trmnl-server.core
  (:require [clojure.java.io :as io]
            [trmnl-server.image :as img]
            [trmnl-server.smhi :as smhi])
  (:import [java.awt Font]
           [java.time ZoneId ZonedDateTime]
           [java.time.format DateTimeFormatter]))

(def ^:private regular-font
  (Font/createFont Font/TRUETYPE_FONT (io/input-stream (io/resource "fonts/PixelOperator.ttf"))))

(def ^:private bold-font
  (Font/createFont Font/TRUETYPE_FONT (io/input-stream (io/resource "fonts/PixelOperator-Bold.ttf"))))

(defn- pixel-font
  "Derives a PixelOperator font at the given size. PixelOperator is a bitmap-style
   font designed on a 16px grid — sizes that are multiples of 16 render as clean
   blocky pixels; other sizes still render but interpolate between grid steps."
  [style size]
  (.deriveFont (if (= style :bold) bold-font regular-font) (float size)))

(defn- nice-bounds
  "Rounds [min max] outward to a multiple of step, with a little padding.
   :floor clamps the low end (e.g. wind speed can't sensibly go below 0)."
  [values step & {:keys [floor]}]
  (let [lo (apply min values)
        hi (apply max values)
        lo' (* step (Math/floor (/ (- lo 2) step)))
        hi' (* step (Math/ceil (/ (+ hi 2) step)))]
    [(if floor (max floor lo') lo') hi']))

(defn- series-layout
  "Maps a value-key's series onto the chart box, scaled to its own min/max.
   Independent per-series scaling (rather than one shared numeric axis) is what
   makes it honest to overlay two different units on one chart: there's no
   single y-axis pretending °C and m/s are comparable, so each line's actual
   values only ever appear via its own direct labels."
  [points value-key x y w h round-step floor]
  (let [values (map value-key points)
        [lo hi] (nice-bounds values round-step :floor floor)
        n (count points)
        value->y (fn [v] (+ y (* h (/ (- hi v) (double (- hi lo))))))
        idx->x (fn [i] (+ x (* w (/ i (double (dec n))))))
        plot-points (map-indexed (fn [i point] [(idx->x i) (value->y (value-key point))]) points)
        max-i (->> points (map-indexed vector) (apply max-key (comp value-key second)) first)
        min-i (->> points (map-indexed vector) (apply min-key (comp value-key second)) first)]
    {:plot-points plot-points :idx->x idx->x :max-i max-i :min-i min-i}))

(defn- draw-series
  [canvas points value-key layout label-fmt & {:keys [dash label-above? label-below?]
                                                :or {label-above? true label-below? true}}]
  (let [{:keys [plot-points max-i min-i]} layout]
    (img/draw-polyline canvas plot-points :width 2.0 :dash dash)
    (doseq [[px py] plot-points]
      (img/draw-dot canvas px py :radius 2))
    (let [[max-x max-y] (nth plot-points max-i)
          [min-x min-y] (nth plot-points min-i)]
      (img/draw-dot canvas max-x max-y :radius 4)
      (when label-above?
        (img/draw-text canvas (label-fmt (value-key (nth points max-i))) (- max-x 16) (- max-y 12)
                        :font (pixel-font :bold 16)))
      (img/draw-dot canvas min-x min-y :radius 4)
      (when label-below?
        (img/draw-text canvas (label-fmt (value-key (nth points min-i))) (- min-x 16) (+ min-y 26)
                        :font (pixel-font :bold 16))))))

(defn draw-legend-key [canvas x y label & {:keys [dash width] :or {width 2.0}}]
  (img/draw-polyline canvas [[x (+ y -6)] [(+ x 30) (+ y -6)]] :width width :dash dash)
  (img/draw-text canvas label (+ x 38) y :font (pixel-font :regular 16)))

(defn cloud-cover-strip
  "Draws a horizontal band along y whose local thickness encodes cloud cover
   (0-100%) at each timestamp — thin where skies are clear, thick where
   they're overcast. Sits above the temp/wind chart as its own row rather
   than sharing the plot box, since it isn't a value series on the same axes."
  [canvas points x y w & {:keys [min-width max-width] :or {min-width 1.0 max-width 10.0}}]
  (let [n (count points)
        idx->x (fn [i] (+ x (* w (/ i (double (dec n))))))
        plot-points (map-indexed (fn [i _] [(idx->x i) y]) points)
        widths (map (fn [p] (double (Math/round (+ min-width (* (- max-width min-width) (/ (:cloud-cover p) 100.0)))))) points)]
    (img/draw-variable-line canvas plot-points widths)))

(defn combined-chart
  "Overlays temperature (solid) and wind speed (dashed) on one 24h chart,
   each scaled to its own range so the two units never share a numeric axis."
  [canvas points x y w h]
  (let [temp-layout (series-layout points :temp x y w h 5 nil)
        wind-layout (series-layout points :wind x y w h 5 0)
        n (count points)
        idx->x (:idx->x temp-layout)]
    ;; x-axis hour labels, every 6 hours
    (doseq [i (concat (range 0 n 6) [(dec n)])]
      (let [px (idx->x i)]
        (img/draw-text canvas (smhi/local-time-str (:time (nth points i))) (- px 18) (+ y h 30)
                        :font (pixel-font :regular 16))))
    (draw-series canvas points :temp temp-layout (fn [t] (str (int t) "°")))
    (draw-series canvas points :wind wind-layout (fn [w] (str (int (Math/round (double w))) " m/s"))
                  :dash [6.0 5.0])))

(defn forecast-screen []
  (let [points (take 24 (smhi/forecast smhi/gothenburg))
        canvas (img/blank-canvas)
        today (-> (ZonedDateTime/now (ZoneId/of "Europe/Stockholm"))
                  (.format (DateTimeFormatter/ofPattern "EEEE, d MMMM")))]
    (img/draw-text canvas "Gothenburg" 40 90 :font (pixel-font :bold 48))
    (img/draw-text canvas today 40 130 :font (pixel-font :regular 16))
    (img/draw-line canvas 40 150 760 150)

    (draw-legend-key canvas 40 190 "Temperature (°C)")
    (draw-legend-key canvas 280 190 "Wind speed (m/s)" :dash [6.0 5.0])
    (draw-legend-key canvas 520 190 "Cloud cover (%)" :width 6.0)

    (cloud-cover-strip canvas points 80 210 640)
    (combined-chart canvas points 80 225 640 185)
    canvas))

(defn -main [& _]
  (let [canvas (forecast-screen)]
    (img/save-image (:image canvas) "out/preview.png")
    (img/save-image (img/->1-bit canvas) "out/preview-1bit.png")
    (println "Wrote out/preview.png and out/preview-1bit.png")))
