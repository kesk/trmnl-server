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

(defn- local-max?
  "True when vs[i] is a turning point at or above its neighbours (the left edge
   of a plateau, so a run of equal values yields one candidate). Array endpoints
   count, so a still-rising/-falling forecast edge — where the visible high/low
   actually sits — is a candidate too."
  [vs i]
  (let [n (count vs) v (nth vs i)]
    (and (or (zero? i)       (> v (nth vs (dec i))))
      (or (= i (dec n)) (>= v (nth vs (inc i)))))))

(defn- saddle-toward
  "Walking from peak i in step direction (+1/-1), the lowest value passed before
   reaching a point strictly higher than vs[i]. If the edge is reached first (no
   higher ground that way) the descent bottoms out at series-min — so the global
   maximum, hemmed in by nothing on either side, sees series-min both ways."
  [vs i step series-min]
  (let [n (count vs) vi (nth vs i)]
    (loop [j (+ i step) col Double/POSITIVE_INFINITY]
      (cond
        (or (neg? j) (>= j n)) (min col series-min)
        (> (nth vs j) vi)      col
        :else                  (recur (+ j step) (min col (double (nth vs j))))))))

(defn- peaks
  "Local maxima of value-seq vs, each tagged with its topographic prominence —
   height above the higher of the two saddles separating it from taller ground
   (the global max gets the full range, since both saddles bottom out at the
   series minimum). Prominence-descending, so the first is the global maximum.
   Run on the negated series to get minima the same way."
  [vs]
  (let [series-min (double (apply min vs))]
    (->> (range (count vs))
      (filter #(local-max? vs %))
      (map (fn [i]
             {:i    i
              :prom (- (double (nth vs i))
                      (max (saddle-toward vs i -1 series-min)
                        (saddle-toward vs i 1 series-min)))}))
      (sort-by :prom >))))

(defn- series-layout
  "Maps a value-key's series onto the chart box, scaled to its own min/max.
   Independent per-series scaling (rather than one shared numeric axis) is what
   makes it honest to overlay two different units on one chart: there's no
   single y-axis pretending °C and m/s are comparable, so each line's actual
   values only ever appear via its own direct labels. `:candidates` holds the
   series' turning points, prominence-ranked (see peaks) as {:maxima :minima};
   the first of each is the global high/low (always labeled), the rest feed
   combined-chart's greedy pick of a couple of extra local labels."
  [points value-key x y w h round-step floor]
  (let [values      (mapv value-key points)
        [lo hi]     (nice-bounds values round-step :floor floor)
        n           (count points)
        value->y    (fn [v] (+ y (* h (/ (- hi v) (double (- hi lo))))))
        idx->x      (fn [i] (+ x (* w (/ i (double (dec n))))))
        plot-points (map-indexed (fn [i point] [(idx->x i) (value->y (value-key point))]) points)]
    {:plot-points plot-points
     :idx->x      idx->x
     :candidates  {:maxima (peaks values) :minima (peaks (mapv - values))}}))

(defn- draw-series-halo
  "A white underlay stroked wider than the data line, laid down before the
   black line so the line reads cleanly where it crosses the rain-background
   stipple (or the cloud strip) instead of visually merging with the texture --
   the polyline analogue of draw-text/draw-dot's white halo. combined-chart
   lays down BOTH series' halos before either black line (the same ordering it
   uses for the labels), so one series' halo never eats a notch out of the
   other's line where the two cross. On plain-white areas it's a no-op."
  [canvas layout & {:keys [dash]}]
  (img/draw-polyline canvas (:plot-points layout) :width 6.0 :paint Color/WHITE :dash dash))

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

(defn- close-points?
  "True when two plotted points are near enough that same-offset labels
   anchored to them would overlap."
  [[ax ay] [bx by]]
  (and (< (Math/abs (- ax bx)) 60) (< (Math/abs (- ay by)) 30)))

(defn- series-label-specs
  "Builds the {:dot :text :place} label specs for one series: its two global
   extrema (placement decided up front by combined-chart as {:above … :below …}
   -- see draw-extremum-label for the fields) plus any greedily-picked extra
   local turning points. Extras are chosen only when clear of every other label
   (see pick-extras), so they need no collision nudging: a plain offset up for a
   max, down for a min (capped by :max-y, like the globals, so a low near the
   chart floor can't shove its label into whatever's drawn below)."
  [points value-key layout label-fmt {:keys [above below]} global-max-i global-min-i extras below-max-y]
  (let [pp   (:plot-points layout)
        spec (fn [i place] {:dot (nth pp i) :text (label-fmt (value-key (nth points i))) :place place})]
    (concat
      [(spec global-max-i above)
       (spec global-min-i below)]
      (for [{:keys [i kind]} extras]
        (spec i (if (= kind :max)
                  {:dx 0 :dy -12}
                  {:dx 0 :dy 26 :max-y below-max-y}))))))

(defn- pick-extras
  "Greedily picks up to `cap` extra turning-point labels for one series, in
   prominence order across its maxima and minima, skipping the two global
   extrema (already labeled) and any candidate whose dot lands close to a label
   already placed -- the four globals, the other series' extras, and extras
   chosen earlier here, all threaded in via `placed-dots`. Returns
   {:accepted [{:i :kind}…] :dots <placed-dots grown by the accepted ones>}, so
   the next series can seed its own pick with everything placed so far and no
   two extras ever crowd each other."
  [layout global-idxs placed-dots cap]
  (let [{:keys [candidates plot-points]} layout
        pool                             (->> (concat (map #(assoc % :kind :max) (:maxima candidates))
                                                (map #(assoc % :kind :min) (:minima candidates)))
                                           (remove #(contains? global-idxs (:i %)))
                                           (filter #(pos? (:prom %)))  ; skip flat shoulders/plateaus -- not real turning points
                                           (sort-by :prom >))]
    (reduce (fn [{:keys [accepted dots] :as acc} cand]
              (if (>= (count accepted) cap)
                (reduced acc)
                (let [dot (nth plot-points (:i cand))]
                  (if (some #(close-points? dot %) dots)
                    acc
                    {:accepted (conj accepted cand) :dots (conj dots dot)}))))
      {:accepted [] :dots (vec placed-dots)}
      pool)))

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

;; SMHI's wordmark, pre-thresholded to 1-bit black/white and pre-scaled to its
;; native draw size (resources/smhi-logo.png, 95x38) so it blits 1:1 — no runtime
;; scaling, which draw-image would do nearest-neighbour and mangle — and survives
;; ->1-bit crisp. Wide 2.5:1 aspect; height chosen to clear the header's
;; "Uppdaterad" line below it.
(def ^:private logo-w 95)
(def ^:private logo-h 38)

(defn draw-logo
  "Draws the SMHI wordmark with its top-left at x,y (native size logo-w x logo-h)."
  [canvas x y]
  (img/draw-image canvas (img/load-image "smhi-logo.png") x y logo-w logo-h))

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

(def ^:private legend-font (pixel-font :regular 16))

(defn draw-legend-key [canvas x y label & {:keys [dash width paint] :or {width 2.0}}]
  (apply img/draw-polyline canvas [[x (+ y -6)] [(+ x 30) (+ y -6)]]
    (concat [:width width :dash dash] (when paint [:paint paint])))
  (img/draw-text canvas label (+ x 38) y :font legend-font))

(defn draw-legend-row
  "Lays a row of legend keys out across [x, x+w] so the first key is flush
   left, the last flush right, and the gaps between them are equal — keeping
   the row balanced under the full chart width rather than clustered at the
   left. Each key is `[label opts]` matching draw-legend-key's args; its drawn
   width is the 38px swatch-plus-gap lead-in plus the label's pixel width."
  [canvas x y w keys]
  (let [key-w  (fn [[label _]] (+ 38 (img/text-width canvas label :font legend-font)))
        widths (map key-w keys)
        gap    (/ (- w (reduce + widths)) (max 1 (dec (count keys))))
        starts (reductions + x (map #(+ % gap) (butlast widths)))]
    (doseq [[[label opts] kx] (map vector keys starts)]
      (apply draw-legend-key canvas kx y label (apply concat opts)))))

(defn cloud-cover-strip
  "Draws a horizontal band along y whose local thickness encodes cloud cover
   at each timestamp — thin where skies are clear, thick where they're
   overcast. SMHI reports cloud_area_fraction in octas (0-8, 8 = fully
   overcast), so scale against 8, not 100. Sits above the temp/wind chart as
   its own row rather than sharing the plot box, since it isn't a value series
   on the same axes."
  [canvas points x y w & {:keys [min-width max-width] :or {min-width 1.0 max-width 20.0}}]
  (let [n           (count points)
        idx->x      (fn [i] (+ x (* w (/ i (double (dec n))))))
        plot-points (map-indexed (fn [i _] [(idx->x i) y]) points)
        widths      (map (fn [p] (double (Math/round (+ min-width (* (- max-width min-width) (/ (:cloud-cover p) 8.0)))))) points)]
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

(defn combined-chart
  "Overlays temperature (solid) and wind speed (dashed) on one 24h-per-day
   chart, each scaled to its own range so the two units never share a numeric
   axis. Since the series are scaled independently, the temp and wind high (or
   low) can land right on top of each other in pixel space even though the
   underlying values are unrelated — when that happens both labels back away
   from each other (temp left, wind right) rather than just one of them moving,
   with a thin leader line from each displaced label back to its own dot so
   it's still clear which point it belongs to. Each line gets a white halo (see
   draw-series-halo) so it stays legible where it crosses the rain-background
   stipple. Both lines are drawn before either
   series' labels/dots, so a label's white halo (see image/draw-text) always
   sits on top of both lines rather than getting drawn over by whichever
   line is plotted second. Beyond each series' global high/low it adds up to two
   extra prominence-ranked local extrema that stay clear of the other labels
   (see pick-extras). :below-max-y (see series-label-specs) keeps a
   collision-pushed min-label from dropping into whatever's drawn below this
   chart's box -- forecast-screen passes the y where precip-bar-chart starts."
  [canvas points x y w h & {:keys [below-max-y]}]
  (let [temp-layout (series-layout points :temp x y w h 5 nil)
        wind-layout (series-layout points :wind x y w h 5 0)
        temp-max-i  (:i (first (:maxima (:candidates temp-layout))))
        temp-min-i  (:i (first (:minima (:candidates temp-layout))))
        wind-max-i  (:i (first (:maxima (:candidates wind-layout))))
        wind-min-i  (:i (first (:minima (:candidates wind-layout))))
        tp          (:plot-points temp-layout)
        wp          (:plot-points wind-layout)
        max-collide (close-points? (nth tp temp-max-i) (nth wp wind-max-i))
        min-collide (close-points? (nth tp temp-min-i) (nth wp wind-min-i))
        ;; On a temp/wind collision the two overlapping labels back away from each
        ;; other -- temp left (-dx), wind right (+dx) -- and the wind label also
        ;; drops/rises further (its dy grows) so a leadered pair doesn't restack.
        temp-place  {:above {:dx (if max-collide -24 0) :dy -12 :leader? max-collide}
                     :below {:dx (if min-collide -24 0) :dy 26 :leader? min-collide :max-y below-max-y}}
        wind-place  {:above {:dx (if max-collide 24 0) :dy (if max-collide -30 -12) :leader? max-collide}
                     :below {:dx (if min-collide 24 0) :dy (if min-collide 44 26) :leader? min-collide :max-y below-max-y}}
        ;; Beyond the four global extrema, add up to two extra local turning
        ;; points per series, prominence-ranked, each kept clear of every label
        ;; already placed (the globals, then the other series' extras) -- so the
        ;; ~1-day curve shows a secondary peak/valley where there's room without
        ;; the labels bunching up.
        global-dots [(nth tp temp-max-i) (nth tp temp-min-i) (nth wp wind-max-i) (nth wp wind-min-i)]
        temp-extras (pick-extras temp-layout #{temp-max-i temp-min-i} global-dots 2)
        wind-extras (pick-extras wind-layout #{wind-max-i wind-min-i} (:dots temp-extras) 2)]
    ;; Both series' white halos first, then both black lines (like the labels
    ;; below), so neither halo notches the other line where they cross.
    (draw-series-halo canvas temp-layout)
    (draw-series-halo canvas wind-layout :dash [6.0 5.0])
    (draw-series-line canvas temp-layout)
    (draw-series-line canvas wind-layout :dash [6.0 5.0])
    (doseq [{:keys [dot text place]}
            (concat
              (series-label-specs points :temp temp-layout (fn [t] (str (int t) "°"))
                temp-place temp-max-i temp-min-i (:accepted temp-extras) below-max-y)
              (series-label-specs points :wind wind-layout
                (fn [w] (str (int (Math/round (double w))) " m/s"))
                wind-place wind-max-i wind-min-i (:accepted wind-extras) below-max-y))]
      (draw-extremum-label canvas (first dot) (second dot) text place))))

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
   Draws the title, the bars, and the baseline, and RETURNS
   {:mm-labels [{:x :top :mm}...] :bar-rects [[x y w h]...]}: the wettest-bar-
   per-day label specs for precip-mm-labels to draw afterwards (deferred so they
   land on top of the probability line and keep their white halos legible where
   it crosses), and the filled bar rectangles for precip-probability-line to
   clip its white pass to. Wettest-per-day (like the temp/wind extrema above)
   rather than one max across the whole span, so a rainy first day doesn't hide
   a smaller-but-still-notable second-day shower."
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
    (img/draw-line canvas x bottom (+ x w) bottom)
    {:mm-labels (->> (day-groups points)
                  (map (fn [group] (apply max-key :bar-h (map bars group))))
                  (filter #(pos? (:mm %)))
                  (mapv (fn [{:keys [x bar-h mm]}] {:x x :top (- bottom bar-h) :mm mm})))
     :bar-rects (->> bars
                  (filter #(pos? (:bar-h %)))
                  (mapv (fn [{:keys [x bar-h]}] [x (- bottom bar-h) bar-w bar-h])))}))

(defn precip-mm-labels
  "Draws the wettest-per-day mm labels from precip-bar-chart's returned specs.
   Split out so it runs AFTER precip-probability-line, letting each label's white
   halo mask the dashed line where the two overlap (a halo only masks what's
   drawn before it)."
  [canvas specs]
  (doseq [{:keys [x top mm]} specs]
    (img/draw-text canvas (format "%.1fmm" (double mm)) (- x 4) (- top 6)
      :font (pixel-font :bold 16) :halo? true)))

(defn precip-probability-line
  "Overlays probability-of-precipitation as a recessive dashed line across
   precip-bar-chart's box, on a fixed 0-100% scale independent of the mm bars
   underneath -- so an hour that's likely-but-light (high chance, ~0mm, hence no
   visible bar) still shows a signal, which is exactly where amount alone says
   nothing. Dashed rather than solid so it reads as a separate series from the
   solid mm bars on the 1-bit surface, per the texture-not-color rule. Drawn in
   two passes so it stays visible even where a low chance passes through a tall
   mm bar (a lot of rain, low odds): a plain black pass (correct everywhere the
   line is over white or the sparse rain-background stipple -- a black dash over
   a stipple dot is still black, so nothing is erased), then a white pass clipped
   to bar-rects so it reads white-on-black only over the solid bars. This gives
   black-elsewhere/white-over-bars without XOR's side effect of flipping the
   stipple pixels the line crosses. Shares precip-bar-chart's x/y/w/h box and
   plots at slot centers; drawn after the bars but before their mm labels, so it
   sits over the bars yet under the haloed labels."
  [canvas points x y w h bar-rects]
  (let [n      (count points)
        slot-w (/ w (double n))
        bottom (+ y h)
        chance (fn [p] (or (:precip-chance p) 0))
        pt->x  (fn [i] (+ x (* i slot-w) (/ slot-w 2)))
        pct->y (fn [pct] (- bottom (* h (/ pct 100.0))))
        plot   (vec (map-indexed (fn [i p] [(pt->x i) (pct->y (chance p))]) points))
        peak   (apply max (map chance points))]
    ;; Right-aligned tag naming the dashed series and carrying the headline
    ;; number, mirroring precip-bar-chart's left-aligned "Regn (0-Xmm)" title at
    ;; the same y. The peak lives here -- in the guaranteed-clear band above the
    ;; strip -- rather than floating at the line's vertex, where on a rainy hour
    ;; it would land on the wettest bar's mm label (peak chance and peak amount
    ;; coincide) and mash together; and marking *which* hour it falls on with a
    ;; dot doesn't read anyway (the peak sits on a plateau corner and/or atop the
    ;; black bars), so the line's shape carries "when" instead.
    (let [tag  (str "Regnrisk (max " (int peak) "%)")
          font (pixel-font :regular 16)
          tw   (img/text-width canvas tag :font font)]
      (img/draw-text canvas tag (- (+ x w) tw) (- y 6) :font font :halo? true))
    (img/draw-polyline canvas plot :dash [5.0 4.0] :width 2.0)
    (img/draw-polyline canvas plot :dash [5.0 4.0] :width 2.0 :paint Color/WHITE :clip bar-rects)))

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
   many hourly points. Preserves smhi/forecast's `:reference-time` metadata across
   the truncation (plain `take` would drop it), so callers can tag a render with the
   SMHI run it came from."
  [hours location]
  (let [fc (smhi/forecast location)]
    (with-meta (take hours fc) (meta fc))))

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
     ;; Wordmark top-right, right edge flush with the 760 content margin (same
     ;; as the divider/Uppdaterad below it); its 38px height clears that line.
     (draw-logo canvas (- 760 logo-w) 14)
     (img/draw-text canvas (str (int (:temp now)) "°") 110 44 :font (pixel-font :bold 32))
     (img/draw-text canvas (str (int (Math/round (double (:wind now)))) " m/s, " condition) 110 68
       :font (pixel-font :regular 16))
     (let [label (str "Uppdaterad " (smhi/local-now-str))
           font  (pixel-font :regular 16)
           w     (img/text-width canvas label :font font)]
       (img/draw-text canvas label (- 760 w) 68 :font font))
     (img/draw-line canvas 40 84 760 84)

     (draw-legend-row canvas 40 108 720
       [["Temp (°C)" {}]
        ["Vind (m/s)" {:dash [6.0 5.0]}]
        ["Moln (%)" {:width 14.0 :paint (img/checkerboard-paint)}]])

     ;; precip-bar-chart's "Regn (0-Xmm)" title sits at (- precip-y 6); cap
     ;; combined-chart's below-labels a bit above that so a collision-pushed
     ;; min-label can never land on top of it (or the bars/labels beneath).
     (let [precip-y 355
           precip-h 85]
       (rain-background canvas points 40 136 (+ precip-y precip-h) 720)
       (cloud-cover-strip canvas points 40 136 720 :max-width 40.0)
       (combined-chart canvas points 40 172 720 155 :below-max-y (- precip-y 20))
       ;; Three z-layers in the precip strip: bars, then the (XOR) probability
       ;; line over them, then the mm labels on top -- so the line stays visible
       ;; through a tall bar while each label's halo still masks the line where
       ;; they overlap (a halo only masks what's drawn before it).
       (let [{:keys [mm-labels bar-rects]} (precip-bar-chart canvas points 40 precip-y 720 precip-h)]
         (precip-probability-line canvas points 40 precip-y 720 precip-h bar-rects)
         (precip-mm-labels canvas mm-labels)))
     (hour-axis-labels canvas points 40 720 468)
     (day-markers canvas points 40 720 118 440 454)
     canvas)))
