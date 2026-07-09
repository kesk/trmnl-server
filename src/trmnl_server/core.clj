(ns trmnl-server.core
  (:require [clojure.java.io :as io]
            [trmnl-server.image :as img]
            [trmnl-server.smhi :as smhi])
  (:import [java.awt Color Font]
           [java.awt.image BufferedImage]))

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
  (let [lo  (apply min values)
        hi  (apply max values)
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
  (let [values      (map value-key points)
        [lo hi]     (nice-bounds values round-step :floor floor)
        n           (count points)
        value->y    (fn [v] (+ y (* h (/ (- hi v) (double (- hi lo))))))
        idx->x      (fn [i] (+ x (* w (/ i (double (dec n))))))
        plot-points (map-indexed (fn [i point] [(idx->x i) (value->y (value-key point))]) points)
        extrema     (for [group (day-groups points)]
                      {:max-i (apply max-key #(value-key (nth points %)) group)
                       :min-i (apply min-key #(value-key (nth points %)) group)})]
    {:plot-points plot-points :idx->x idx->x :extrema extrema}))

(defn- draw-series-line
  "Just the polyline, no dots/labels -- combined-chart draws both series'
   lines before either series' labels, so a label (with its white halo) always
   ends up on top of both lines rather than getting drawn over by whichever
   line comes second."
  [canvas layout & {:keys [dash]}]
  (img/draw-polyline canvas (:plot-points layout) :width 2.0 :dash dash))

(defn- draw-extremum-label
  "Draws one extremum's dot and (nudged) label. placement is a
   {:dx :dy :leader? :max-y} map decided up front by combined-chart: :dx/:dy
   nudge the label off its dot to dodge the other series' colliding label,
   :leader? draws a recessive dashed hairline from the dot to a displaced label
   (before the label, so the label's white halo clears the leader too) so it's
   still clear which point it belongs to, and :max-y (optional) caps how far
   down the label can be pushed, so a min value near the bottom of the chart
   box can't shove its label into whatever's drawn below the chart (e.g.
   precip-bar-chart's own bars and labels)."
  [canvas dot-x dot-y text {:keys [dx dy leader? max-y]}]
  (img/draw-dot canvas dot-x dot-y :radius 4 :halo? true)
  (let [label-x (+ (- dot-x 16) dx)
        label-y (cond-> (+ dot-y dy) max-y (min max-y))]
    (when leader?
      (img/draw-dashed-line canvas dot-x dot-y label-x (- label-y 6)))
    (img/draw-text canvas text label-x label-y :font (pixel-font :bold 16) :halo? true)))

(defn- draw-series-labels
  "Dots + extrema labels for one series. Placement is decided up front by
   combined-chart and handed in as a per-day
   {:above {:dx :dy :leader? :max-y} :below {…}} seq (one entry per day/extrema
   pair) -- see draw-extremum-label for what each placement field does."
  [canvas points value-key layout label-fmt placements]
  (let [{:keys [plot-points extrema]} layout]
    (doseq [[i {:keys [max-i min-i]}] (map-indexed vector extrema)]
      (let [[max-x max-y]         (nth plot-points max-i)
            [min-x min-y]         (nth plot-points min-i)
            {:keys [above below]} (nth placements i)]
        (draw-extremum-label canvas max-x max-y (label-fmt (value-key (nth points max-i))) above)
        (draw-extremum-label canvas min-x min-y (label-fmt (value-key (nth points min-i))) below)))))

(defn- weather-icon-path [symbol-code night?]
  (str "icons/" (if night? "night" "day") "-" symbol-code ".png"))

(defn draw-weather-icon
  "Draws SMHI's official icon (bundled as pre-rasterized PNGs under
   resources/icons/, one pair of day/night SVGs per symbol code) for a
   forecast point inside the size x size box at x,y. Their fills (sun
   yellow, cloud grays) sit above ->1-bit's threshold and wash to white,
   leaving just their dark outlines — so the icons need no recoloring to fit
   the 1-bit pipeline."
  [canvas point location x y size]
  (let [image (img/load-image (weather-icon-path (:symbol point) (smhi/night? location (:time point))))]
    (img/draw-image canvas image x y size size)))

(defn draw-stale-badge
  "Draws a filled warning-triangle-with-exclamation-mark badge in an x,y size
   box. Stamped onto a served image when it's a stale last-known-good render
   (server.clj falling back because a live SMHI fetch just failed) — the
   frozen 'Uppdaterad' timestamp alone is too easy to miss at a glance on the
   device."
  [canvas x y size]
  (img/draw-polygon canvas
    [[(+ x (/ size 2.0)) y] [x (+ y size)] [(+ x size) (+ y size)]]
    :fill? true)
  (img/draw-rect canvas (- (+ x (/ size 2.0)) 1.5) (+ y (* size 0.34)) 3 (* size 0.26)
    :fill? true :color Color/WHITE)
  (img/draw-dot canvas (+ x (/ size 2.0)) (+ y (* size 0.82)) :radius 1.6 :color Color/WHITE))

(defn stamp-stale-badge
  "Returns a copy of a rendered BufferedImage (e.g. a final ->1-bit screen)
   with draw-stale-badge stamped onto its top-right corner — the original is
   left untouched. Used both by server.clj's stale-cache fallback and by
   --demo (to produce a sample stale image without needing a real SMHI
   outage)."
  [^BufferedImage image]
  (let [copy (BufferedImage. (.getWidth image) (.getHeight image) (.getType image))]
    (doto (.createGraphics copy) (.drawImage image 0 0 nil) (.dispose))
    (draw-stale-badge (img/canvas-from copy) 766 4 20)
    copy))

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
  (let [n           (count points)
        idx->x      (fn [i] (+ x (* w (/ i (double (dec n))))))
        plot-points (map-indexed (fn [i _] [(idx->x i) y]) points)
        widths      (map (fn [p] (double (Math/round (+ min-width (* (- max-width min-width) (/ (:cloud-cover p) 100.0)))))) points)]
    (img/draw-variable-line canvas plot-points widths :paint (img/checkerboard-paint))))

(defn- rain-background
  "Shades a light stippled column behind every hour that has any
   precipitation, spanning from the cloud strip all the way down to the
   axis line above the hour labels -- purely decorative, flagging \"it's
   raining this hour\" across the whole chart rather than just on its own
   bar below. Columns line up with precip-bar-chart's own slot-per-point
   geometry (w/n wide, not the (n-1)-divisor spacing series-layout uses for
   plotting points), so the shading matches the bar underneath it. Adjacent
   rainy hours share their dividing edge (both computed via the same
   slot-edge fn) rather than each rect getting an independently-rounded
   x/width, so a run of consecutive rainy hours doesn't develop stray 1px
   gaps where rounding happens to truncate the two sides differently. Drawn
   first, before the cloud strip/combined chart/rain bars, so their own
   fills, lines, and text all render on top of the light stipple instead of
   competing with it."
  [canvas points x cloud-y bottom-y w]
  (let [n         (count points)
        slot-edge (fn [i] (+ x (Math/round (* w (/ i (double n))))))]
    (doseq [[i point] (map-indexed vector points)]
      (when (pos? (:precip-mm point))
        (let [left  (slot-edge i)
              right (slot-edge (inc i))]
          (img/draw-rect canvas left cloud-y (- right left) (- bottom-y cloud-y)
            :fill? true :paint (img/stipple-paint)))))))

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
   each day has its own label pair), both labels back away from each other
   (temp left, wind right) rather than just one of them moving, with a thin
   leader line from each displaced label back to its own dot so it's still
   clear which point it belongs to. Both lines are drawn before either
   series' labels/dots, so a label's white halo (see image/draw-text) always
   sits on top of both lines rather than getting drawn over by whichever
   line is plotted second. :below-max-y (see draw-series-labels) keeps a
   collision-pushed min-label from dropping into whatever's drawn below this
   chart's box -- forecast-screen passes the y where precip-bar-chart starts."
  [canvas points x y w h & {:keys [below-max-y]}]
  (let [temp-layout  (series-layout points :temp x y w h 5 nil)
        wind-layout  (series-layout points :wind x y w h 5 0)
        day-pairs    (map vector (:extrema temp-layout) (:extrema wind-layout))
        max-collides (mapv (fn [[t w]] (close-points? (nth (:plot-points temp-layout) (:max-i t))
                                         (nth (:plot-points wind-layout) (:max-i w))))
                       day-pairs)
        min-collides (mapv (fn [[t w]] (close-points? (nth (:plot-points temp-layout) (:min-i t))
                                         (nth (:plot-points wind-layout) (:min-i w))))
                       day-pairs)
        ;; On a temp/wind collision the two overlapping labels back away from each
        ;; other -- temp left (-dx), wind right (+dx) -- and the wind label also
        ;; drops/rises further (its dy grows) so a leadered pair doesn't restack.
        temp-place   (mapv (fn [max? min?]
                             {:above {:dx (if max? -24 0) :dy -12 :leader? max?}
                              :below {:dx (if min? -24 0) :dy 26 :leader? min? :max-y below-max-y}})
                       max-collides min-collides)
        wind-place   (mapv (fn [max? min?]
                             {:above {:dx (if max? 24 0) :dy (if max? -30 -12) :leader? max?}
                              :below {:dx (if min? 24 0) :dy (if min? 44 26) :leader? min? :max-y below-max-y}})
                       max-collides min-collides)]
    (draw-series-line canvas temp-layout)
    (draw-series-line canvas wind-layout :dash [6.0 5.0])
    (draw-series-labels canvas points :temp temp-layout (fn [t] (str (int t) "°")) temp-place)
    (draw-series-labels canvas points :wind wind-layout
      (fn [w] (str (int (Math/round (double w))) " m/s")) wind-place)))

(def ^:private axis-label-count
  "How many hour-of-day labels hour-axis-labels always draws, evenly spaced
   from the first to the last point — fixed rather than hour-interval-based,
   so changing --hours/FORECAST_HOURS changes label spacing, not label count."
  12)

(defn- hour-axis-labels
  "Draws axis-label-count hour-of-day labels evenly spaced along a shared
   x-axis, at a fixed y. Shared by combined-chart and precip-bar-chart since
   both plot the same points across the same x span — drawn once here rather
   than duplicated under each chart."
  [canvas points x w y]
  (let [n       (count points)
        font    (pixel-font :regular 16)
        idx->x  (fn [i] (+ x (* w (/ i (double (dec n))))))
        indices (distinct (for [k (range axis-label-count)]
                            (Math/round (* k (/ (dec n) (double (dec axis-label-count)))))))]
    (doseq [i indices]
      (let [px    (idx->x i)
            label (smhi/local-time-str (:time (nth points i)))
            lw    (img/text-width canvas label :font font)]
        (img/draw-text canvas label (- px (/ lw 2.0)) y :font font)))))

(defn precip-bar-chart
  "Draws precipitation (mm) as one bottom-anchored vertical bar per forecast
   point. Kept as its own row with its own 0-based scale — per the no-shared-axis
   rule, mm must not be folded onto the temp/wind chart's independently-scaled
   pixel box, since 0mm has to mean the same thing as every other 0mm here.
   Labels the wettest bar per calendar day (like the temp/wind extrema above)
   rather than one max across the whole span, so a rainy first day doesn't
   hide a smaller-but-still-notable second-day shower."
  [canvas points x y w h]
  (let [n         (count points)
        values    (map :precip-mm points)
        raw-max   (apply max values)
        ;; Headroom scales with the data instead of nice-bounds' flat +2 padding,
        ;; which is sized for °C/m/s ranges and would swamp typical sub-1mm rain.
        hi        (max 1 (Math/ceil (* raw-max 1.15)))
        slot-w    (/ w (double n))
        bar-w     (* slot-w 0.7)
        bar-gap   (* slot-w 0.3)
        bottom    (+ y h)
        mm->bar-h (fn [mm] (* h (/ mm (double hi))))
        bars      (vec (map-indexed
                         (fn [i point]
                           (let [mm (:precip-mm point)]
                             {:x     (+ x (* i slot-w) (/ bar-gap 2))
                              :bar-h (mm->bar-h mm)
                              :mm    mm}))
                         points))]
    (img/draw-text canvas (str "Regn (0-" (int hi) "mm)") x (- y 6) :font (pixel-font :regular 16) :halo? true)
    (doseq [{:keys [x bar-h]} bars]
      (when (pos? bar-h)
        (img/draw-rect canvas x (- bottom bar-h) bar-w bar-h :fill? true)))
    (doseq [group (day-groups points)]
      (let [{:keys [x bar-h mm]} (apply max-key :bar-h (map bars group))]
        (when (pos? mm)
          (img/draw-text canvas (format "%.1fmm" (double mm)) (- x 4) (- bottom bar-h 6)
            :font (pixel-font :bold 16) :halo? true))))
    (img/draw-line canvas x bottom (+ x w) bottom)))

(defn- day-markers
  "Draws a weekday label centered over each calendar day's x-span, plus a
   hairline dashed divider between consecutive days, spanning the cloud strip
   through the rain chart — so a multi-day forecast reads at a glance without
   decoding hour labels to figure out where 'tomorrow' starts. Reuses
   day-groups' rule of skipping a lone-point sliver day, since there's nothing
   meaningful to center a label over."
  [canvas points x w top bottom label-y]
  (let [n      (count points)
        idx->x (fn [i] (+ x (* w (/ i (double (dec n))))))
        groups (day-groups points)]
    (doseq [group groups]
      (let [center-x (/ (+ (idx->x (first group)) (idx->x (last group))) 2)]
        (img/draw-text canvas (smhi/local-day-label (:time (nth points (first group)))) (- center-x 12) label-y
          :font (pixel-font :bold 16))))
    (doseq [[_a b] (partition 2 1 groups)]
      (let [boundary-x (idx->x (first b))]
        (img/draw-dashed-line canvas boundary-x top boundary-x bottom)))))

(def default-forecast-hours
  "How many hourly points forecast-screen renders when fetching live data or
   generating a demo season, absent an explicit override (e.g. --hours or
   FORECAST_HOURS). 23 rather than a round 24/48: hour-axis-labels' 12 labels
   only land at perfectly even pixel spacing when (hours - 1) is a multiple of
   11, and 23 is the smallest such count above a day."
  23)

(def default-forecast-location
  "Where forecast-screen fetches live data for, absent an explicit override
   (e.g. --lat/--lon or FORECAST_LAT/FORECAST_LON)."
  smhi/gothenburg)

(defn live-points
  "Fetches a live forecast for `location` ({:lat :lon}), truncated to `hours`
   many hourly points."
  [hours location]
  (take hours (smhi/forecast location)))

(defn forecast-screen
  ([] (forecast-screen (live-points default-forecast-hours default-forecast-location)
        default-forecast-location))
  ;; `location` is only used to place the header icon's day/night variant via
  ;; sunrise/sunset; it defaults to Gothenburg, which is also what --demo's
  ;; synthetic data represents, so demo callers can omit it.
  ([points] (forecast-screen points default-forecast-location))
  ([points location]
   (let [canvas    (img/blank-canvas)
         ;; The display hangs in a fixed spot (a hallway) — the viewer already
         ;; knows where and roughly when they are, so the header leads with
         ;; current conditions instead of city/date.
         now       (first points)
         condition (smhi/symbol->description (:symbol now))]
     (draw-weather-icon canvas now location 40 14 56)
     (img/draw-text canvas (str (int (:temp now)) "°") 110 44 :font (pixel-font :bold 32))
     (img/draw-text canvas (str (int (Math/round (double (:wind now)))) " m/s, " condition) 110 68
       :font (pixel-font :regular 16))
     (let [label (str "Uppdaterad " (smhi/local-now-str))
           font  (pixel-font :regular 16)
           w     (img/text-width canvas label :font font)]
       (img/draw-text canvas label (- 760 w) 68 :font font))
     (img/draw-line canvas 40 84 760 84)

     (draw-legend-key canvas 40 108 "Temp (°C)")
     (draw-legend-key canvas 280 108 "Vind (m/s)" :dash [6.0 5.0])
     (draw-legend-key canvas 520 108 "Moln (%)" :width 14.0 :paint (img/checkerboard-paint))

     ;; precip-bar-chart's "Regn (0-Xmm)" title sits at (- precip-y 6); cap
     ;; combined-chart's below-labels a bit above that so a collision-pushed
     ;; min-label can never land on top of it (or the bars/labels beneath).
     (let [precip-y 355
           precip-h 85]
       (rain-background canvas points 40 136 (+ precip-y precip-h) 720)
       (cloud-cover-strip canvas points 40 136 720 :max-width 40.0)
       (combined-chart canvas points 40 172 720 155 :below-max-y (- precip-y 20))
       (precip-bar-chart canvas points 40 precip-y 720 precip-h))
     (hour-axis-labels canvas points 40 720 468)
     (day-markers canvas points 40 720 118 440 454)
     canvas)))
