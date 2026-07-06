(ns trmnl-server.core
  (:require [clojure.java.io :as io]
            [trmnl-server.image :as img]
            [trmnl-server.smhi :as smhi])
  (:import [java.awt Font]))

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

(defn- day-groups
  "Splits point indices into runs sharing the same Europe/Stockholm calendar
   date, in order. A lone-point run at either edge (e.g. a single 23:00 point
   before the date rolls over) has no meaningful min vs max to distinguish, so
   it's dropped rather than labeled."
  [points]
  (->> points
       (map-indexed vector)
       (partition-by (fn [[_ point]] (smhi/local-date (:time point))))
       (map #(map first %))
       (filter #(> (count %) 1))))

(defn- series-layout
  "Maps a value-key's series onto the chart box, scaled to its own min/max.
   Independent per-series scaling (rather than one shared numeric axis) is what
   makes it honest to overlay two different units on one chart: there's no
   single y-axis pretending °C and m/s are comparable, so each line's actual
   values only ever appear via its own direct labels. Min/max are found once
   per calendar day (rather than once across the whole multi-day span) so a
   second day's peak still gets its own label even if it's milder than the
   first day's."
  [points value-key x y w h round-step floor]
  (let [values (map value-key points)
        [lo hi] (nice-bounds values round-step :floor floor)
        n (count points)
        value->y (fn [v] (+ y (* h (/ (- hi v) (double (- hi lo))))))
        idx->x (fn [i] (+ x (* w (/ i (double (dec n))))))
        plot-points (map-indexed (fn [i point] [(idx->x i) (value->y (value-key point))]) points)
        extrema (for [group (day-groups points)]
                  {:max-i (apply max-key #(value-key (nth points %)) group)
                   :min-i (apply min-key #(value-key (nth points %)) group)})]
    {:plot-points plot-points :idx->x idx->x :extrema extrema}))

(defn- offset-at [offset i]
  (if (sequential? offset) (nth offset i) offset))

(defn- draw-series
  [canvas points value-key layout label-fmt & {:keys [dash label-above? label-below? above-offset below-offset]
                                                :or {label-above? true label-below? true
                                                     above-offset -12 below-offset 26}}]
  (let [{:keys [plot-points extrema]} layout]
    (img/draw-polyline canvas plot-points :width 2.0 :dash dash)
    (doseq [[i {:keys [max-i min-i]}] (map-indexed vector extrema)]
      (let [[max-x max-y] (nth plot-points max-i)
            [min-x min-y] (nth plot-points min-i)]
        (img/draw-dot canvas max-x max-y :radius 4)
        (when label-above?
          (img/draw-text canvas (label-fmt (value-key (nth points max-i))) (- max-x 16) (+ max-y (offset-at above-offset i))
                          :font (pixel-font :bold 16)))
        (img/draw-dot canvas min-x min-y :radius 4)
        (when label-below?
          (img/draw-text canvas (label-fmt (value-key (nth points min-i))) (- min-x 16) (+ min-y (offset-at below-offset i))
                          :font (pixel-font :bold 16)))))))

(defn draw-legend-key [canvas x y label & {:keys [dash width paint] :or {width 2.0}}]
  (apply img/draw-polyline canvas [[x (+ y -6)] [(+ x 30) (+ y -6)]]
         (concat [:width width :dash dash] (when paint [:paint paint])))
  (img/draw-text canvas label (+ x 38) y :font (pixel-font :regular 16)))

(defn cloud-cover-strip
  "Draws a horizontal band along y whose local thickness encodes cloud cover
   (0-100%) at each timestamp — thin where skies are clear, thick where
   they're overcast. Sits above the temp/wind chart as its own row rather
   than sharing the plot box, since it isn't a value series on the same axes."
  [canvas points x y w & {:keys [min-width max-width] :or {min-width 1.0 max-width 20.0}}]
  (let [n (count points)
        idx->x (fn [i] (+ x (* w (/ i (double (dec n))))))
        plot-points (map-indexed (fn [i _] [(idx->x i) y]) points)
        widths (map (fn [p] (double (Math/round (+ min-width (* (- max-width min-width) (/ (:cloud-cover p) 100.0)))))) points)]
    (img/draw-variable-line canvas plot-points widths :paint (img/checkerboard-paint))))

(defn- close-points?
  "True when two plotted points are near enough that same-offset labels
   anchored to them would overlap."
  [[ax ay] [bx by]]
  (and (< (Math/abs (- ax bx)) 60) (< (Math/abs (- ay by)) 30)))

(defn combined-chart
  "Overlays temperature (solid) and wind speed (dashed) on one 24h-per-day
   chart, each scaled to its own range so the two units never share a numeric
   axis. Since the series are scaled independently, a day's temp and wind
   extrema can land right on top of each other in pixel space even though the
   underlying values are unrelated — when that happens (checked per day, since
   each day has its own label pair), push the wind label further from its dot
   so the two labels stack instead of overlapping."
  [canvas points x y w h]
  (let [temp-layout (series-layout points :temp x y w h 5 nil)
        wind-layout (series-layout points :wind x y w h 5 0)
        day-pairs (map vector (:extrema temp-layout) (:extrema wind-layout))
        max-collides (mapv (fn [[t w]] (close-points? (nth (:plot-points temp-layout) (:max-i t))
                                                        (nth (:plot-points wind-layout) (:max-i w))))
                            day-pairs)
        min-collides (mapv (fn [[t w]] (close-points? (nth (:plot-points temp-layout) (:min-i t))
                                                        (nth (:plot-points wind-layout) (:min-i w))))
                            day-pairs)]
    (draw-series canvas points :temp temp-layout (fn [t] (str (int t) "°")))
    (draw-series canvas points :wind wind-layout (fn [w] (str (int (Math/round (double w))) " m/s"))
                  :dash [6.0 5.0]
                  :above-offset (mapv #(if % -30 -12) max-collides)
                  :below-offset (mapv #(if % 44 26) min-collides))))

(defn- hour-axis-labels
  "Draws hour-of-day labels every 6 hours along a shared x-axis, at a fixed y.
   Shared by combined-chart and precip-bar-chart since both plot the same
   points across the same x span — drawn once here rather than duplicated
   under each chart."
  [canvas points x w y]
  (let [n (count points)
        idx->x (fn [i] (+ x (* w (/ i (double (dec n))))))
        regular (range 0 n 6)
        last-i (dec n)
        ;; Only tack on the final point if it's far enough past the last
        ;; regular tick to read as a separate label instead of overlapping it
        ;; — with n not a multiple of 6, the two can land just 1-2h apart.
        indices (if (>= (- last-i (last regular)) 3) (concat regular [last-i]) regular)]
    (doseq [i indices]
      (let [px (idx->x i)]
        (img/draw-text canvas (smhi/local-time-str (:time (nth points i))) (- px 18) y
                        :font (pixel-font :regular 16))))))

(defn precip-bar-chart
  "Draws precipitation (mm) as one bottom-anchored vertical bar per forecast
   point. Kept as its own row with its own 0-based scale — per the no-shared-axis
   rule, mm must not be folded onto the temp/wind chart's independently-scaled
   pixel box, since 0mm has to mean the same thing as every other 0mm here.
   Labels the wettest bar per calendar day (like the temp/wind extrema above)
   rather than one max across the whole span, so a rainy first day doesn't
   hide a smaller-but-still-notable second-day shower."
  [canvas points x y w h]
  (let [n (count points)
        values (map :precip-mm points)
        raw-max (apply max values)
        ;; Headroom scales with the data instead of nice-bounds' flat +2 padding,
        ;; which is sized for °C/m/s ranges and would swamp typical sub-1mm rain.
        hi (max 1 (Math/ceil (* raw-max 1.15)))
        slot-w (/ w (double n))
        bar-w (* slot-w 0.7)
        bar-gap (* slot-w 0.3)
        bottom (+ y h)
        mm->bar-h (fn [mm] (* h (/ mm (double hi))))
        bars (vec (map-indexed
                   (fn [i point]
                     (let [mm (:precip-mm point)]
                       {:x (+ x (* i slot-w) (/ bar-gap 2))
                        :bar-h (mm->bar-h mm)
                        :mm mm}))
                   points))]
    (img/draw-text canvas (str "Rain (0-" (int hi) "mm)") x (- y 6) :font (pixel-font :regular 16))
    (doseq [{:keys [x bar-h]} bars]
      (when (pos? bar-h)
        (img/draw-rect canvas x (- bottom bar-h) bar-w bar-h :fill? true)))
    (doseq [group (day-groups points)]
      (let [{:keys [x bar-h mm]} (apply max-key :bar-h (map bars group))]
        (when (pos? mm)
          (img/draw-text canvas (format "%.1fmm" (double mm)) (- x 4) (- bottom bar-h 6)
                          :font (pixel-font :bold 16)))))
    (img/draw-line canvas x bottom (+ x w) bottom)))

(defn- day-markers
  "Draws a weekday label centered over each calendar day's x-span, plus a
   hairline dashed divider between consecutive days, spanning the cloud strip
   through the rain chart — so a multi-day forecast reads at a glance without
   decoding hour labels to figure out where 'tomorrow' starts. Reuses
   day-groups' rule of skipping a lone-point sliver day, since there's nothing
   meaningful to center a label over."
  [canvas points x w top bottom label-y]
  (let [n (count points)
        idx->x (fn [i] (+ x (* w (/ i (double (dec n))))))
        groups (day-groups points)]
    (doseq [group groups]
      (let [center-x (/ (+ (idx->x (first group)) (idx->x (last group))) 2)]
        (img/draw-text canvas (smhi/local-day-label (:time (nth points (first group)))) (- center-x 12) label-y
                        :font (pixel-font :bold 16))))
    (doseq [[a b] (partition 2 1 groups)]
      (let [boundary-x (/ (+ (idx->x (last a)) (idx->x (first b))) 2)]
        (img/draw-dashed-line canvas boundary-x top boundary-x bottom)))))

(defn forecast-screen
  ([] (forecast-screen (take 48 (smhi/forecast smhi/gothenburg))))
  ([points]
   (let [canvas (img/blank-canvas)
         ;; The display hangs in a fixed spot (a hallway) — the viewer already
         ;; knows where and roughly when they are, so the header leads with
         ;; current conditions instead of city/date.
         now (first points)
         condition (smhi/symbol->description (:symbol now))]
     (img/draw-text canvas (str (int (:temp now)) "°") 40 44 :font (pixel-font :bold 32))
     (img/draw-text canvas (str (int (Math/round (double (:wind now)))) " m/s, " condition) 40 68
                     :font (pixel-font :regular 16))
     (let [label (str "Updated " (smhi/local-now-str))
           font (pixel-font :regular 16)
           w (img/text-width canvas label :font font)]
       (img/draw-text canvas label (- 760 w) 68 :font font))
     (img/draw-line canvas 40 84 760 84)

     (draw-legend-key canvas 40 108 "Temp (°C)")
     (draw-legend-key canvas 280 108 "Wind (m/s)" :dash [6.0 5.0])
     (draw-legend-key canvas 520 108 "Clouds (%)" :width 14.0 :paint (img/checkerboard-paint))

     (cloud-cover-strip canvas points 40 136 720 :max-width 40.0)
     (combined-chart canvas points 40 172 720 155)
     (precip-bar-chart canvas points 40 355 720 85)
     (hour-axis-labels canvas points 40 720 468)
     (day-markers canvas points 40 720 118 440 454)
     canvas)))
