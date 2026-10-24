(ns trmnl-server.core
  (:require [trmnl-server.image :as img]
            [trmnl-server.labels :as labels]
            [trmnl-server.smhi :as smhi])
  (:import [java.awt Color]
           [java.awt.image BufferedImage]))

(defn- nice-bounds
  "Rounds [min max] outward to a multiple of step, after :pad units of breathing
   room on each side. :floor clamps the low end (e.g. wind speed can't sensibly
   go below 0).

   :min-span is the narrowest axis the result may span, and is what lets the
   padding stay tight. The two knobs do separate jobs that used to be conflated:
   generous padding (and a coarse step) kept a flat day looking flat, but it paid
   for that by spending 40% of the plot box on empty margin on an ordinary day,
   so a real 6° swing only travelled 60% of the box. Framing tightly instead and
   putting an explicit floor under the *span* gives an ordinary day most of the
   box while still rendering a degree of overnight jitter as the near-flat line
   it actually is, rather than stretching it into a mountain range.

   Note the min-span case can return bounds that aren't multiples of step (the
   window is grown around its own midpoint). Nothing draws y ticks or gridlines
   off these — they only scale the series into the box — so the multiple is a
   stability device, not a layout one.

   :floor wins over :min-span at the low end: a too-narrow window that would
   reach below the floor is shifted up rather than clamped, so the span is
   honoured either way."
  [values step & {:keys [floor pad min-span] :or {pad 1 min-span 6}}]
  (let [lo    (apply min values)
        hi    (apply max values)
        lo'   (* step (Math/floor (/ (- lo pad) step)))
        hi'   (* step (Math/ceil (/ (+ hi pad) step)))
        lo'   (if floor (max floor lo') lo')
        short (- min-span (- hi' lo'))]
    (if (pos? short)
      (let [a (- lo' (/ short 2.0))
            a (if floor (max floor a) a)]
        [a (+ a min-span)])
      [lo' hi'])))

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

;; Two x-geometries share the same 720px span, and reaching for the wrong one is
;; the bug these names exist to prevent. A forecast point is both an *instant* --
;; plotted as a dot, spread so the first lands on the box's left edge and the last
;; on its right, hence the (n-1) divisor -- and an *hour*, an interval owning a
;; column w/n wide. Instants: the temp/wind series, the cloud strip, the hour
;; labels, the day dividers. Intervals: the rain shading, the precip bars, the
;; probability line, the thunder bolts. At the deployed 23 points the two disagree
;; by up to half a column (~16px), diverging at the ends and crossing in the
;; middle, so a bolt drawn at plot-x drifts off the very column it is marking.

(defn- plot-x
  "X of the i'th of n plotting points, with the first and last on the box edges."
  [x w n i]
  (+ x (* w (/ i (double (dec n))))))

(defn- slot-width
  "Width of one hour's column."
  [w n]
  (/ w (double n)))

(defn- slot-left
  "X of the left edge of the i'th of n hour columns."
  [x w n i]
  (+ x (* w (/ i (double n)))))

(defn- slot-edge
  "slot-left rounded to a whole pixel, for filled columns that must abut. Two
   adjacent rects have to share one computed edge, or a run of them develops
   stray 1px gaps wherever rounding truncates the two sides differently."
  [x w n i]
  (+ x (Math/round (double (* w (/ i (double n)))))))

(defn- slot-center
  "X of the centre of the i'th of n hour columns."
  [x w n i]
  (+ x (* w (/ (+ i 0.5) n))))

(defn- series-layout
  "Maps a value-key's series onto the chart box, scaled to its own min/max.
   Independent per-series scaling (rather than one shared numeric axis) is what
   makes it honest to overlay two different units on one chart: there's no
   single y-axis pretending °C and m/s are comparable, so each line's actual
   values only ever appear via its own direct labels. `:candidates` holds the
   series' turning points, prominence-ranked (see labels/peaks) as
   {:maxima :minima}; the first of each is the global high/low (always labeled),
   the rest feed chart-labels' pick of a couple of extra local labels."
  [points value-key x y w h round-step floor]
  (let [values      (mapv value-key points)
        [lo hi]     (nice-bounds values round-step :floor floor)
        n           (count points)
        value->y    (fn [v] (+ y (* h (/ (- hi v) (double (- hi lo))))))
        idx->x      (fn [i] (plot-x x w n i))
        plot-points (map-indexed (fn [i point] [(idx->x i) (value->y (value-key point))]) points)]
    {:plot-points plot-points
     :idx->x      idx->x
     :candidates  {:maxima (labels/peaks values) :minima (labels/peaks (mapv - values))}}))

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

(defn- weather-icon-path [symbol-code night?]
  (str "icons/" (if night? "night" "day") "-" symbol-code ".png"))

(defn draw-weather-icon
  "Draws SMHI's official icon (bundled as pre-rasterized PNGs under
   resources/icons/, one pair of day/night SVGs per symbol code) for a
   forecast point inside the size x size box at x,y. The PNGs are already
   1-bit: their fills carry identity by texture (sun/moon an ordered-dither
   checkerboard, precip marks a denser dither, cloud body white, outlines
   solid black), baked in at rasterization time so ->1-bit's threshold is a
   no-op on them. See CLAUDE.md for the rsvg-convert recipe that builds them."
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

(def ^:private battery-segments 4)

;; The icon's footprint: a 30x14 body plus a 3px nub on its right end. Named like logo-w
;; because forecast-screen lays the header out against it.
(def ^:private battery-w 33)
(def ^:private battery-h 14)

(defn battery-bars
  "How many of the battery icon's four segments a charge estimate (0-100) lights: the
   nearest quarter, so 0 means under about an eighth, which is where the outline goes
   empty. Rounded rather than ceilinged because a 'roughly' that only errs one way is
   wrong more often than one that doesn't — a ceiling shows three bars for 76% and a
   full set for 100%."
  [percent]
  (-> (Math/round (/ (double percent) (/ 100.0 battery-segments)))
    (max 0)
    (min battery-segments)))

(defn draw-battery
  "Draws a battery icon with its top-left at x,y, battery-w x battery-h: a body, a nub on
   its right end, and four segments inside, `battery-bars` of them filled for `percent`.
   Built from filled rects (black body, white inside, black segments) rather than an
   outline stroke, so every edge is a whole pixel and survives ->1-bit untouched."
  [canvas x y percent]
  (img/draw-rect canvas x y 30 battery-h :fill? true)
  (img/draw-rect canvas (+ x 30) (+ y 4) 3 6 :fill? true)
  (img/draw-rect canvas (+ x 2) (+ y 2) 26 10 :fill? true :color Color/WHITE)
  (dotimes [i (battery-bars percent)]
    (img/draw-rect canvas (+ x 4 (* i 6)) (+ y 4) 4 6 :fill? true)))

;; The screen draws in three fonts and nothing else, so derive each once. These
;; were being rebuilt at draw time in eight places -- five of them this same
;; regular 16 -- and every one of those calls runs Font/.deriveFont.
(def ^:private body-font (img/pixel-font :regular 16))
(def ^:private bold-font (img/pixel-font :bold 16))
(def ^:private headline-font (img/pixel-font :bold 32))

(defn draw-legend-key [canvas x y label & {:keys [dash width paint] :or {width 2.0}}]
  (apply img/draw-polyline canvas [[x (+ y -6)] [(+ x 30) (+ y -6)]]
    (concat [:width width :dash dash] (when paint [:paint paint])))
  (img/draw-text canvas label (+ x 38) y :font body-font))

(defn draw-legend-row
  "Lays a row of legend keys out across [x, x+w] so the first key is flush
   left, the last flush right, and the gaps between them are equal — keeping
   the row balanced under the full chart width rather than clustered at the
   left. Each key is `[label opts]` matching draw-legend-key's args; its drawn
   width is the 38px swatch-plus-gap lead-in plus the label's pixel width."
  [canvas x y w keys]
  (let [key-w  (fn [[label _]] (+ 38 (img/text-width canvas label :font body-font)))
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
        idx->x      (fn [i] (plot-x x w n i))
        plot-points (map-indexed (fn [i _] [(idx->x i) y]) points)
        widths      (map (fn [p] (double (Math/round (+ min-width (* (- max-width min-width) (/ (:cloud-cover p) 8.0)))))) points)]
    (img/draw-variable-line canvas plot-points widths :paint (img/checkerboard-paint))))

(defn- draw-thunder-flash
  "A solid lightning bolt in a w×h box centered on (cx, cy), backed by a white
   halo so it stays legible over the cloud strip's checkerboard or the rain
   stipple behind it.

   The halo is the bolt's own outline stroked white at twice the halo radius,
   the same trick draw-text and draw-dot use: the stroke straddles the path, so
   it stands `halo` px proud all the way round, and the black fill then covers
   the half that fell inside.

   **The halo radius has to stay well under half the bolt's narrowest feature**,
   which is what sets the 22x30 box below against a 2.5px halo. Widening the
   halo instead is the obvious move and it does not work: a halo of radius r
   closes off any concavity narrower than 2r, so at r=4 the bolt's mid-step
   simply fills in and the white gap stops being bolt-shaped — it unions into a
   blocky wedge. That's morphology, not a drawing-technique problem, so it
   survives every way of building the halo (a scaled-up copy and a ring of
   stamped offsets were both tried; switching between them moved 29 pixels).
   Growing the bolt so its features outrun the halo is the fix. It matters
   because the bolt sits *inside* the strip, where too thin a surround reads as
   a smudge against the checkerboard and too thick a one eats the shape."
  [canvas cx cy w h]
  (let [;; normalized ⚡ outline (0..1 box), traced clockwise: a wide top "head"
        ;; narrowing to a mid step, then a left-offset lower stroke down to the
        ;; tip — the offset between the two strokes is what reads as a bolt.
        norm  [[0.42 0.00] [0.78 0.00] [0.50 0.42] [0.72 0.42]
               [0.20 1.00] [0.38 0.52] [0.16 0.52]]
        halo  2.5
        verts (mapv (fn [[nx ny]]
                      [(+ cx (* (- nx 0.5) w)) (+ cy (* (- ny 0.5) h))])
                norm)]
    ;; Closed loop, so the outline strokes the last edge back to the first
    ;; vertex; draw-polyline's round join keeps the tip from growing a miter
    ;; spike where the two strokes meet at a sharp angle.
    (img/draw-polyline canvas (conj verts (first verts)) :width (* 2 halo) :paint Color/WHITE)
    (img/draw-polygon canvas verts :fill? true)))

(defn thunder-flashes
  "Marks each thundery hour with a lightning bolt, centered vertically on cy.
   The point forecast has no lightning parameter, so thunder is read off the
   symbol code alone (see smhi/thunder?). Bolts are x-centered on that hour's
   slot (slot-center, not plot-x — see the note above those fns), so the flash
   sits over the rainy column it belongs to rather than drifting off its bar.
   It sits inside the cloud strip (cy is the strip's own centre line), its halo
   carving it out of the checkerboard — the gap below the strip belongs to the
   temp/wind chart, whose box starts just under it."
  [canvas points x cy w]
  (let [n (count points)]
    (doseq [[i point] (map-indexed vector points)]
      (when (smhi/thunder? (:symbol point))
        (draw-thunder-flash canvas (slot-center x w n i) cy 22.0 30.0)))))

(defn- rain-background
  "Shades a light stippled column behind every hour that has any
   precipitation, spanning from the cloud strip all the way down to the
   axis line above the hour labels -- purely decorative, flagging \"it's
   raining this hour\" across the whole chart rather than just on its own
   bar below. Columns use the same slot geometry as the bars underneath them, so
   the two line up, and adjacent rainy hours share their dividing edge — see
   slot-edge, which is what keeps a run of them from developing stray 1px gaps.
   Drawn first, before the cloud strip/combined chart/rain bars, so their own
   fills, lines and text all render on top of the light stipple instead of
   competing with it."
  [canvas points x cloud-y bottom-y w]
  (let [n (count points)]
    (doseq [[i point] (map-indexed vector points)]
      (when (pos? (:precip-mm point))
        (let [left  (slot-edge x w n i)
              right (slot-edge x w n (inc i))]
          (img/draw-rect canvas left cloud-y (- right left) (- bottom-y cloud-y)
            :fill? true :paint (img/stipple-paint)))))))

(defn chart-labels
  "What combined-chart will draw, resolved but not yet drawn: {:temp-layout
   :wind-layout :labels}, where each label is {:dot :text :anchor :leader? :box}
   as positioned by labels/place. Each series contributes its global high and
   low (always labelled) plus up to two prominence-ranked local extrema (see
   labels/pick-extras), so the ~1-day curve shows a secondary peak or valley
   wherever there's room for one.

   This is the domain half of the labelling — which values get labelled, how they
   read, and what they have to stay off; the geometry that turns that into
   positions lives in trmnl-server.labels. Split out of the drawing so the
   resulting geometry can be checked without rendering — see
   dev/label_collisions.clj, which asserts no label box ever lands on a dot or
   another label."
  [canvas points x y w h & {:keys [keep-out]}]
  (let [temp-layout (series-layout points :temp x y w h 2 nil)
        wind-layout (series-layout points :wind x y w h 2 0)
        series      [{:layout temp-layout                                     :value-key :temp
                      :fmt    (fn [t] (str (int (Math/rint (double t))) "°"))}
                     {:layout wind-layout                                        :value-key :wind
                      :fmt    (fn [v] (str (int (Math/rint (double v))) " m/s"))}]
        global-i    (fn [layout kind]
                      (:i (first ((if (= kind :max) :maxima :minima) (:candidates layout)))))
        spec        (fn [{:keys [layout value-key fmt]} i kind required?]
                      {:dot       (nth (:plot-points layout) i)
                       :text      (fmt (value-key (nth points i)))
                       :kind      kind
                       :required? required?})
        globals     (for [s series kind [:max :min]]
                      (spec s (global-i (:layout s) kind) kind true))
        extras      (for [s                series
                          :let             [layout (:layout s)
                                            globals #{(global-i layout :max) (global-i layout :min)}]
                          {:keys [i kind]} (labels/pick-extras (:candidates layout) globals 2)]
                      (spec s i kind false))]
    {:temp-layout temp-layout
     :wind-layout wind-layout
     :labels      (labels/place canvas (concat globals extras)
                    {;; Hard: stay on the panel, above whatever keep-out covers.
                     :bounds   [4 (- y 12) (- img/og-width 4) (+ y h 26)]
                     ;; Soft: a first- or last-point label is centred on the box
                     ;; edge, so half of it is always outside — but only this far.
                     ;; Without the leash a label dodging a line will happily set
                     ;; off into the margin, where it reads as a stray number
                     ;; rather than as one of the chart's labels.
                     :leash    [(- x 24) (- y 12) (+ x w 24) (+ y h 26)]
                     :keep-out keep-out
                     :profiles (map (comp labels/line-profile :plot-points) [temp-layout wind-layout])})}))

(defn combined-chart
  "Overlays temperature (solid) and wind speed (dashed) on one 24h-per-day
   chart, each scaled to its own range so the two units never share a numeric
   axis. Each line gets a white halo (see draw-series-halo) so it stays legible
   where it crosses the rain-background stipple.

   Draw order carries the layering: both halos, then both black lines, then the
   labels — so a label's own white halo (see image/draw-text) always sits on top
   of both lines rather than getting drawn over by whichever line is plotted
   second, and neither halo notches the other line where they cross.

   Since the series are scaled independently, the temp and wind extrema can land
   on top of each other in pixel space even though the underlying values are
   unrelated. Resolving that is chart-labels/labels-place's job, not this fn's:
   every label is positioned against the real boxes of every dot and every other
   label first, and only then drawn. :keep-out takes [x1 y1 x2 y2] rects for
   whatever else the screen draws around this box — forecast-screen passes the
   band holding precip-bar-chart's titles and bars — so a label can't be pushed
   out of the chart and onto them."
  [canvas points x y w h & {:keys [keep-out]}]
  (let [{:keys [temp-layout wind-layout] placed :labels}
        (chart-labels canvas points x y w h :keep-out keep-out)]
    (draw-series-halo canvas temp-layout)
    (draw-series-halo canvas wind-layout :dash [6.0 5.0])
    (draw-series-line canvas temp-layout)
    (draw-series-line canvas wind-layout :dash [6.0 5.0])
    (doseq [label placed]
      (labels/draw-placed canvas label))))

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
        font    body-font
        idx->x  (fn [i] (plot-x x w n i))
        indices (distinct (for [k (range axis-label-count)]
                            (Math/round (* k (/ (dec n) (double (dec axis-label-count)))))))]
    (doseq [i indices]
      (let [px    (idx->x i)
            label (smhi/local-time-str (:time (nth points i)))
            lw    (img/text-width canvas label :font font)]
        (img/draw-text canvas label (- px (/ lw 2.0)) y :font font)))))

(def ^:private rain-period-min-hours
  "How many consecutive wet hours make a rain period worth a total. A one- or
   two-hour shower is already fully told by its own bars and wettest-hour label;
   what a total adds is the sum over a stretch too long to add up by eye."
  3)

(defn- rain-periods
  "Runs of consecutive points with any precipitation, at least
   rain-period-min-hours long, as {:start i :end i :total mm} (end inclusive).
   Wet means the same as rain-background's stipple -- any mm at all -- so a
   period's bracket spans exactly the shaded columns above it. SMHI's
   precipitation_amount_mean is per hour at this resolution, so the period's
   total is the sum of its points."
  [points]
  (->> points
    (map-indexed vector)
    (partition-by (fn [[_ point]] (pos? (:precip-mm point))))
    (filter (fn [run] (pos? (:precip-mm (second (first run))))))
    (filter #(>= (count %) rain-period-min-hours))
    (mapv (fn [run] {:start (ffirst run)
                     :end   (first (last run))
                     :total (reduce + (map (comp :precip-mm second) run))}))))

(defn- mm-str
  "An mm figure as the screen prints it, with Swedish's decimal comma (see
   smhi/swedish for why that's a decision rather than the host's locale)."
  [mm]
  (String/format smhi/swedish "%.1fmm" (to-array [(double mm)])))

(def ^:private period-band-h
  "Height reserved at the top of the precip strip for the rain-period brackets,
   taken from the bars' travel when there is at least one period to bracket.
   Just the bracket and its total: an hourly label set above its bar reaches 19px
   up, which would need about 4px more than this to clear the bracket line, but
   only bars under mm-label-sink-min-h keep their label above, and those are
   too low to get near it. The tall ones have theirs sunk into the bar."
  18)

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
   a smaller-but-still-notable second-day shower.

   `periods` (from rain-periods) makes the bars give up the top period-band-h of
   the strip to precip-period-brackets. Hours inside a period stay in the
   wettest-per-day pick: they were left out for a while, so that a period was
   told by its total alone, and in practice that took the hourly labels off the
   screen -- real forecasts trail a run of 0,1mm hours into every rain spell,
   so most wet hours are in a period, and a third of a sample of 176 archived
   screens had no hourly label at all. How hard it rains and how much falls in
   total are different answers, and the screen wants both."
  [canvas points x y w h periods]
  (let [n         (count points)
        values    (map :precip-mm points)
        title-y   (- y 6)
        band      (if (seq periods) period-band-h 0)
        y         (+ y band)
        h         (- h band)
        raw-max   (apply max values)
        ;; Headroom scales with the data instead of nice-bounds' flat +2 padding,
        ;; which is sized for °C/m/s ranges and would swamp typical sub-1mm rain.
        hi        (max 1 (Math/ceil (* raw-max 1.15)))
        slot-w    (slot-width w n)
        bar-w     (* slot-w 0.7)
        bar-gap   (* slot-w 0.3)
        bottom    (+ y h)
        mm->bar-h (fn [mm] (* h (/ mm (double hi))))
        bars      (vec (map-indexed
                         (fn [i point]
                           (let [mm (:precip-mm point)]
                             {:x     (+ (slot-left x w n i) (/ bar-gap 2))
                              :bar-h (mm->bar-h mm)
                              :mm    mm}))
                         points))]
    (img/draw-text canvas (str "Regn (0-" (int hi) "mm)") x title-y :font body-font :halo? true)
    (doseq [{:keys [x bar-h]} bars]
      (when (pos? bar-h)
        (img/draw-rect canvas x (- bottom bar-h) bar-w bar-h :fill? true)))
    (img/draw-line canvas x bottom (+ x w) bottom)
    {:mm-labels (->> (day-groups points)
                  (map (fn [group] (apply max-key :bar-h (map bars group))))
                  (filter #(pos? (:mm %)))
                  (mapv (fn [{:keys [x bar-h mm]}] {:x x :w bar-w :top (- bottom bar-h) :bottom bottom :mm mm})))
     :bar-rects (->> bars
                  (filter #(pos? (:bar-h %)))
                  (mapv (fn [{:keys [x bar-h]}] [x (- bottom bar-h) bar-w bar-h])))}))

(def ^:private mm-label-pad
  "Clear pixels between an mm label's ink and the inside of its plaque border.
   Wide enough that the plaque reads as a deliberate gap in the dashed
   probability line rather than a nick taken out of it, tight enough that it
   doesn't swallow the bar top underneath. An exact pixel count, not an
   approximate one -- see precip-mm-labels for why that needs saying."
  3)

(def ^:private mm-label-arc
  "Corner rounding on the mm label's plaque, as Java2D's arc *diameter* -- so this
   is a 2px radius, which on an un-antialiased 1-bit surface costs one pixel off
   each corner. Enough to stop the tile reading as a hard-edged cut-out, small
   enough that it still reads as a rectangle at arm's length, which is the only
   distance this screen is ever looked at from."
  4)

(declare mm-plaque-box)

(defn- draw-mm-plaque
  "Draws `text` in bold at baseline bx,by, knocked out on a **white plaque, not
   a halo**. A halo strokes the glyph outlines, so it only clears ~half of
   fill-string's 5px stroke around each glyph's edge and leaves whatever is
   behind the label showing through the gaps *between* glyphs. That is where the
   probability line peaks — it is highest at the wettest hour almost by
   definition, which is exactly the hour an mm label sits above — so a dash would
   survive in the gap beside the decimal separator and read as a stray mark on
   the number. A filled rect over the text's ink bounds clears the whole
   footprint in one go, which is what the halo was reaching for and couldn't
   express.

   The plaque is then outlined 1px black, so its edge is a drawn edge rather than
   wherever the stipple and the dashed line happen to stop. Without it the cleared
   rectangle reads as a hole knocked in the chart; with it the label reads as a
   tile sitting on top, which is what it is. draw-rect sets that 1px itself: an
   outline whose width depends on what the previous caller left on the shared
   Graphics2D is a trap (it measured 1px here only because
   precip-probability-line's dashed pass happens to reset the stroke between the
   last haloed label and this call)."
  [canvas text bx by]
  (let [[left top w h] (mm-plaque-box canvas text bx by)]
    (img/draw-rect canvas left top w h :fill? true :color Color/WHITE :arc mm-label-arc)
    (img/draw-rect canvas left top w h :fill? false :color Color/BLACK :arc mm-label-arc)
    (img/draw-text canvas text bx by :font bold-font)))

(defn- mm-plaque-box
  "The [x y w h] draw-mm-plaque will clear for `text` at baseline bx,by, so a
   caller can keep two plaques apart before drawing either."
  [canvas text bx by]
  (let [font          bold-font
        [x1 _ x2 y2]  (img/text-box canvas text bx by :font font)
        ;; Vertical extent measured from a digit, not from the whole string. The
        ;; decimal separator is the only glyph here that drops below the baseline,
        ;; so sizing the box to the full ink bounds spends a couple of rows on its
        ;; tail and pushes the digits -- which are what the eye reads as "the
        ;; text" -- above centre. Centre the digit block instead and let the tail
        ;; hang into the bottom padding, which is deeper than the tail is long.
        [_ dy1 _ dy2] (img/text-box canvas "0" bx by :font font)
        first-px      (fn [v] (long (Math/floor (double v))))
        ;; Ink bounds are a continuous rect, so the last *pixel* it covers is one
        ;; short of its ceiling. Rounding both edges instead put the plaque a pixel
        ;; right of centre -- correct to within a pixel, and a pixel is 6% of the
        ;; gap at this size.
        last-px       (fn [v] (dec (long (Math/ceil (double v)))))
        left          (- (first-px x1) mm-label-pad 1)
        right         (+ (last-px x2) mm-label-pad 1)
        top*          (- (first-px dy1) mm-label-pad 1)
        bottom        (max (+ (last-px dy2) mm-label-pad 1)
                        (+ (last-px y2) 1))
        w             (- right left)
        h             (- bottom top*)]
    [left top* w h]))

(def ^:private mm-label-peek
  "How far a bar sticks up above the mm label sunk into its top."
  4)

(def ^:private mm-label-sink-min-h
  "A bar at least this tall gets its mm label sunk into its top rather than set
   above it. About twice a plaque's height, so a sunk label still leaves most of
   its bar showing; on a shorter bar the plaque -- wider than the bar -- would
   hide nearly all of it, and the bar has room above it anyway. That room is
   what period-band-h relies on: every bar short enough to keep its label above
   is low enough for that label to clear a bracket line."
  36)

(defn- mm-label-placements
  "Where precip-mm-labels will put each of precip-bar-chart's label specs, as
   {:text :bx :by :box} -- worked out before anything is drawn, so
   precip-period-brackets can steer its totals clear of them. Centred on its
   bar, and either set just above it or, on a bar of mm-label-sink-min-h or
   more, sunk into its top with mm-label-peek px of bar showing above. Sinking
   the tall ones is what lets the bars keep their height under a bracket: set
   above, the tallest bar's label would need the band to grow to clear it."
  [canvas specs]
  (mapv (fn [{:keys [x w top bottom mm]}]
          (let [text     (mm-str mm)
                tw       (img/text-width canvas text :font bold-font)
                bx       (- (+ x (/ w 2.0)) (/ tw 2.0))
                above    (- top 6)
                [_ ptop] (mm-plaque-box canvas text bx above)
                sunk     (+ above (- (+ top mm-label-peek) ptop))
                by       (if (>= (- bottom top) mm-label-sink-min-h) sunk above)]
            {:text text :bx bx :by by :box (mm-plaque-box canvas text bx by)}))
    specs))

(defn precip-mm-labels
  "Draws the wettest-per-day mm labels placed by mm-label-placements, each on
   draw-mm-plaque's plaque. Runs AFTER precip-probability-line and the period
   brackets, so what it paints over them wins (paint order is the only masking
   a 1-bit surface has) -- where a label sits over a bracket's end tick, the
   number is what has to survive."
  [canvas placements]
  (doseq [{:keys [text bx by]} placements]
    (draw-mm-plaque canvas text bx by)))

(defn precip-period-brackets
  "Draws a bracket over each rain period's columns, in the band precip-bar-chart
   reserved at the top of the strip, with the period's total on a plaque centred
   on it. The bracket alone is what says \"sum\": the plaque reads just \"7,2mm\",
   like an hourly label, and sitting on a bracket that spans several bars is
   what tells the two apart. It spans the period's slot edges inset 3px, so two
   periods a single dry hour apart still read as two brackets.

   Drawn after the probability line, so the solid bracket sits over the dashed
   one where they cross at 100%, and before the hourly labels, which win where
   they meet. The total starts centred on its bracket and slides along it to
   the nearest position clear of every hourly label (`avoid`, boxes from
   mm-label-placements) -- the wettest hour of a period is often its middle,
   and its label then reaches up to exactly where a centred total sits. It
   keeps its centre over the bracket and on the panel; when no such position is
   clear it stays centred, and the hourly label is drawn over it."
  [canvas points x y w periods avoid]
  (let [n      (count points)
        line-y (+ y 7)
        font   bold-font]
    (doseq [{:keys [start end total]} periods]
      (let [left   (+ (slot-edge x w n start) 3)
            right  (- (slot-edge x w n (inc end)) 3)
            text   (mm-str total)
            tw     (img/text-width canvas text :font font)
            by     (+ line-y 6)
            clamp  #(-> % (max (+ x 4)) (min (- (+ x w) tw 4)))
            mid    (clamp (- (/ (+ left right) 2.0) (/ tw 2.0)))
            clear? (fn [bx]
                     (let [[px py pw ph] (mm-plaque-box canvas text bx by)]
                       (not-any? (fn [[ax ay aw ah]]
                                   (and (< (- px 2) (+ ax aw)) (< ax (+ px pw 2))
                                     (< (- py 2) (+ ay ah)) (< ay (+ py ph 2))))
                         avoid)))
            bx     (or (->> (range 0 (- right left) 2)
                         (mapcat (fn [d] [(+ mid d) (- mid d)]))
                         (filter #(= % (clamp %)))
                         (filter #(<= left (+ % (/ tw 2.0)) right))
                         (filter clear?)
                         first)
                     mid)]
        (img/draw-rect canvas left line-y (- right left) 2 :fill? true)
        (img/draw-rect canvas left line-y 2 11 :fill? true)
        (img/draw-rect canvas (- right 2) line-y 2 11 :fill? true)
        (draw-mm-plaque canvas text bx by)))))

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
        bottom (+ y h)
        chance (fn [p] (or (:precip-chance p) 0))
        pt->x  (fn [i] (slot-center x w n i))
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
          font body-font
          tw   (img/text-width canvas tag :font font)]
      (img/draw-text canvas tag (- (+ x w) tw) (- y 6) :font font :halo? true))
    (img/draw-polyline canvas plot :dash [5.0 4.0] :width 2.0)
    (img/draw-polyline canvas plot :dash [5.0 4.0] :width 2.0 :paint Color/WHITE :clip bar-rects)))

(defn- day-dividers
  "Draws a 1px dashed divider between consecutive days, spanning the cloud strip
   down to the x-axis — so a multi-day forecast reads at a glance
   without decoding hour labels to figure out where 'tomorrow' starts. Drawn
   under the charts, so every label's halo or plaque cuts the divider rather
   than being struck through by it; the series lines cross over it. Reuses
   day-groups' rule of skipping a lone-point sliver day, matching day-labels."
  [canvas points x w top bottom]
  (let [n (count points)]
    (doseq [[_a b] (partition 2 1 (day-groups points))]
      (let [boundary-x (plot-x x w n (first b))]
        (img/draw-dashed-line canvas boundary-x top boundary-x bottom :dash [6.0 4.0])))))

(defn- day-labels
  "Draws a weekday label centered over each calendar day's x-span. Skips a
   lone-point sliver day (day-groups), since there's nothing meaningful to
   center a label over."
  [canvas points x w label-y]
  (let [n      (count points)
        idx->x (fn [i] (plot-x x w n i))]
    (doseq [group (day-groups points)]
      (let [center-x (/ (+ (idx->x (first group)) (idx->x (last group))) 2)]
        (img/draw-text canvas (smhi/local-day-label (:time (nth points (first group)))) (- center-x 12) label-y
          :font bold-font)))))

(def default-forecast-hours
  "How many hourly points forecast-screen renders when fetching live data or
   generating a demo season, absent an explicit override (e.g. --hours or
   FORECAST_HOURS). 23 rather than a round 24/48: hour-axis-labels' 12 labels
   only land at perfectly even pixel spacing when (hours - 1) is a multiple of
   11, and 23 is the smallest such count above a day."
  23)

(def default-forecast-location
  "Where forecast-screen fetches live data for, absent an explicit override
   (--lat/--lon). Not a fallback for the server: every display's :lat/:lon is a
   required field of its devices.edn entry."
  smhi/gothenburg)

(defn live-points
  "Fetches a live forecast for `location` ({:lat :lon}), truncated to `hours`
   many hourly points. Preserves smhi/forecast's `:reference-time` metadata across
   the truncation (plain `take` would drop it), so callers can tag a render with the
   SMHI run it came from."
  [hours location]
  (let [fc (smhi/forecast location)]
    (with-meta (take hours fc) (meta fc))))

(def screen-geometry
  "Where forecast-screen puts the cloud strip, the temp/wind chart and the precip
   strip, and — derived from those — the `:chart-box` combined-chart plots into
   and the `:keep-out` rects its labels have to stay off.

   Lifted out of forecast-screen's `let` because it has a second reader:
   dev/label_collisions.clj checks the placement this geometry produces, and it
   can only check the chart that ships if it plots into the same box. It used to
   restate these numbers instead, on the theory that a copy would fail loudly
   when core's drifted. It drifted; the copy quietly checked a chart nothing
   renders, missing the cloud band entirely and understating labels-across-a-line
   by ~45%. One definition, two readers."
  (let [cloud-y     136
        cloud-max-w 40.0
        precip-y    355
        precip-h    85]
    {:cloud-y     cloud-y
     :cloud-max-w cloud-max-w
     :precip-y    precip-y
     :precip-h    precip-h
     ;; Top edge sits just under the cloud band. The axis padding in nice-bounds
     ;; is what makes that safe: it guarantees the extreme value lands a unit or
     ;; more inside the box, so no dot (with its 3px halo ring) ever reaches up
     ;; over the strip.
     ;; Bottom edge runs right up to the precip band, reclaiming the dead strip
     ;; that used to sit between the box and the titles. Same padding argument as
     ;; the top edge: no dot reaches the box edge, so nothing collides with the
     ;; band. What it does cost is label room *below* the box -- a label is ~16px
     ;; tall and there is no longer 16px between box bottom and band, so a low
     ;; minimum near the bottom now places its label beside or above its dot
     ;; instead. That's a placement the label code already handles; it's
     ;; check-labels, not inspection, that confirms it stays collision-free.
     :chart-box   [40 158 720 176]
     :keep-out    [;; The cloud strip is centered on cloud-y and can reach half
                   ;; its max width either side (116..156). Fenced off like the
                   ;; precip band below, because the chart box starts just under
                   ;; it: without this a high label would sit on the
                   ;; checkerboard, and check-labels would never see it -- that
                   ;; check only knows about the rects it's handed.
                   [0 (- cloud-y (/ cloud-max-w 2)) img/og-width (+ cloud-y (/ cloud-max-w 2))]
                   ;; precip-bar-chart's "Regn (0-Xmm)" title sits at (- precip-y
                   ;; 6), so its ink starts a little above that: fence off
                   ;; everything from there down, across the full panel width,
                   ;; and combined-chart will place a low label beside its dot
                   ;; rather than on top of the titles or bars.
                   [0 (- precip-y 20) img/og-width (+ precip-y precip-h)]]}))

(defn forecast-screen
  ([] (forecast-screen (live-points default-forecast-hours default-forecast-location)
        default-forecast-location))
  ;; `location` is only used to place the header icon's day/night variant via
  ;; sunrise/sunset; it defaults to Gothenburg, which is also what --demo's
  ;; synthetic data represents, so demo callers can omit it.
  ([points] (forecast-screen points default-forecast-location))
  ([points location] (forecast-screen points location {}))
  ;; `:battery-percent` (0-100) is the display's charge estimate, drawn as the header's
  ;; battery icon. Optional because most callers have none to give: the CLI render isn't
  ;; for any particular display, and a display that hasn't reported a voltage yet has
  ;; nothing to show. No estimate means no icon, not an empty one — an empty battery is
  ;; a claim.
  ([points location {:keys [battery-percent]}]
   (let [canvas    (img/blank-canvas)
         ;; The display hangs in a fixed spot (a hallway) — the viewer already
         ;; knows where and roughly when they are, so the header leads with
         ;; current conditions instead of city/date.
         now       (first points)
         condition (smhi/symbol->description (:symbol now))]
     (draw-weather-icon canvas now location 38 8 72)
     ;; Wordmark top-right, right edge flush with the 760 content margin (same
     ;; as the divider/Uppdaterad below it); its 38px height clears that line.
     (draw-logo canvas (- 760 logo-w) 14)
     ;; Beside the wordmark, on its vertical centre, where a status bar keeps one.
     (when battery-percent
       (draw-battery canvas (- 760 logo-w 14 battery-w) (- 33 (/ battery-h 2)) battery-percent))
     (img/draw-text canvas (str (int (Math/rint (double (:temp now)))) "°") 122 44 :font headline-font)
     (img/draw-text canvas (str (int (Math/rint (double (:wind now)))) " m/s, " condition) 122 68
       :font body-font)
     (let [label (str "Uppdaterad " (smhi/local-now-str))
           font  body-font
           w     (img/text-width canvas label :font font)]
       (img/draw-text canvas label (- 760 w) 68 :font font))
     (img/draw-line canvas 40 84 760 84)

     ;; "Moln (%)" names the dimension, not the datum. SMHI reports cloud cover in
     ;; octas and cloud-cover-strip scales the strip's thickness against 8, so this
     ;; legend briefly read "Moln (0-8)" -- on the reasoning that % labelled a series
     ;; that isn't one. Reverted deliberately: the strip prints no number anywhere, so
     ;; a range invites the eye to hunt for figures that don't exist and hands the
     ;; reader an upstream unit they have no use for. 8 octas *is* 100% of the sky,
     ;; and % is how a person reads "how much of it is covered". The octas live in
     ;; cloud-cover-strip, which is where the scaling happens.
     (draw-legend-row canvas 40 108 720
       [["Temp (°C)" {}]
        ["Vind (m/s)" {:dash [6.0 5.0]}]
        ["Moln (%)" {:width 14.0 :paint (img/checkerboard-paint)}]])

     (let [{:keys [cloud-y cloud-max-w precip-y precip-h chart-box keep-out]} screen-geometry
           [chart-x chart-y chart-w chart-h]                                  chart-box]
       (rain-background canvas points 40 cloud-y (+ precip-y precip-h) 720)
       (cloud-cover-strip canvas points 40 cloud-y 720 :max-width cloud-max-w)
       ;; Bolts sit *inside* the strip, centered on it (27x35 including halo, so
       ;; 118..154 against the strip's 116..156) rather than hanging below it --
       ;; their halo carves them out of the checkerboard the same way either way,
       ;; and the gap under the strip is the chart's now. A thundery hour is an
       ;; overcast one, so there's always strip to carve.
       (thunder-flashes canvas points 40 cloud-y 720)
       ;; Over the strip but under everything else, so labels and plaques cut
       ;; the dividers rather than being struck through.
       (day-dividers canvas points 40 720 118 (+ precip-y precip-h))
       ;; Box and keep-out rects both come from screen-geometry above, which is
       ;; where the reasoning behind their edges lives -- and which is the copy
       ;; check-labels reads, so it checks this chart and not one of its own.
       (combined-chart canvas points chart-x chart-y chart-w chart-h :keep-out keep-out)
       ;; Four z-layers in the precip strip: bars, then the probability line
       ;; over them, then the period brackets, then the hourly mm labels on top
       ;; -- so the line stays visible through a tall bar while each label's
       ;; white plaque still clears what it overlaps (paint order is the only
       ;; masking there is).
       (let [periods                       (rain-periods points)
             {:keys [mm-labels bar-rects]} (precip-bar-chart canvas points 40 precip-y 720 precip-h periods)
             placed                        (mm-label-placements canvas mm-labels)]
         (precip-probability-line canvas points 40 precip-y 720 precip-h bar-rects)
         (precip-period-brackets canvas points 40 precip-y 720 periods (map :box placed))
         (precip-mm-labels canvas placed)))
     (day-labels canvas points 40 720 454)
     (hour-axis-labels canvas points 40 720 468)
     canvas)))
