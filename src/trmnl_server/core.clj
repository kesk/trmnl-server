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
        idx->x      (fn [i] (+ x (* w (/ i (double (dec n))))))
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

(def ^:private legend-font (img/pixel-font :regular 16))

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
   slot — the same slot-per-point geometry (w/n wide) the rain-background column
   and the precip bar use, NOT the (n-1)-divisor plotting-point spacing of the
   cloud strip / temp-wind chart — so the flash sits centered over the rainy
   column it belongs to rather than drifting off its bar. It sits inside the
   cloud strip (cy is the strip's own centre line), its halo carving it out of
   the checkerboard — the gap below the strip belongs to the temp/wind chart,
   whose box starts just under it."
  [canvas points x cy w]
  (let [n           (count points)
        slot-center (fn [i] (+ x (* w (/ (+ i 0.5) n))))]
    (doseq [[i point] (map-indexed vector points)]
      (when (smhi/thunder? (:symbol point))
        (draw-thunder-flash canvas (slot-center i) cy 22.0 30.0)))))

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
        font    (img/pixel-font :regular 16)
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
    (img/draw-text canvas (str "Regn (0-" (int hi) "mm)") x (- y 6) :font (img/pixel-font :regular 16) :halo? true)
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

(defn precip-mm-labels
  "Draws the wettest-per-day mm labels from precip-bar-chart's returned specs.
   Split out so it runs AFTER precip-probability-line, so what it paints over the
   dashed line wins (paint order is the only masking a 1-bit surface has).

   Knocked out on a **white plaque, not a halo**. A halo strokes the glyph
   outlines, so it only clears ~half of fill-string's 5px stroke around each
   glyph's edge and leaves whatever is behind the label showing through the gaps
   *between* glyphs. That is where the probability line peaks — it is highest at
   the wettest hour almost by definition, which is exactly the hour this label
   sits above — so a dash would survive in the gap beside the decimal separator
   and read as a stray mark on the number. A filled rect over the text's ink
   bounds clears the whole footprint in one go, which is what the halo was
   reaching for and couldn't express.

   The plaque is then outlined 1px black, so its edge is a drawn edge rather than
   wherever the stipple and the dashed line happen to stop. Without it the cleared
   rectangle reads as a hole knocked in the chart; with it the label reads as a
   tile sitting on top, which is what it is. draw-rect sets that 1px itself: this
   is the codebase's first unfilled rect, and an outline whose width depends on
   what the previous caller left on the shared Graphics2D is a trap (it measured
   1px here only because precip-probability-line's dashed pass happens to reset the
   stroke between the last haloed label and this call)."
  [canvas specs]
  (doseq [{:keys [x top mm]} specs]
    (let [text          (format "%.1fmm" (double mm))
          font          (img/pixel-font :bold 16)
          bx            (- x 4)
          by            (- top 6)
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
      (img/draw-rect canvas left top* w h :fill? true :color Color/WHITE :arc mm-label-arc)
      (img/draw-rect canvas left top* w h :fill? false :color Color/BLACK :arc mm-label-arc)
      (img/draw-text canvas text bx by :font font))))

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
          font (img/pixel-font :regular 16)
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
          :font (img/pixel-font :bold 16))))
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
  ([points location]
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
     (img/draw-text canvas (str (int (Math/rint (double (:temp now)))) "°") 122 44 :font (img/pixel-font :bold 32))
     (img/draw-text canvas (str (int (Math/rint (double (:wind now)))) " m/s, " condition) 122 68
       :font (img/pixel-font :regular 16))
     (let [label (str "Uppdaterad " (smhi/local-now-str))
           font  (img/pixel-font :regular 16)
           w     (img/text-width canvas label :font font)]
       (img/draw-text canvas label (- 760 w) 68 :font font))
     (img/draw-line canvas 40 84 760 84)

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
       ;; Box and keep-out rects both come from screen-geometry above, which is
       ;; where the reasoning behind their edges lives -- and which is the copy
       ;; check-labels reads, so it checks this chart and not one of its own.
       (combined-chart canvas points chart-x chart-y chart-w chart-h :keep-out keep-out)
       ;; Three z-layers in the precip strip: bars, then the probability line
       ;; over them, then the mm labels on top -- so the line stays visible
       ;; through a tall bar while each label's white plaque still clears the
       ;; line where they overlap (paint order is the only masking there is).
       (let [{:keys [mm-labels bar-rects]} (precip-bar-chart canvas points 40 precip-y 720 precip-h)]
         (precip-probability-line canvas points 40 precip-y 720 precip-h bar-rects)
         (precip-mm-labels canvas mm-labels)))
     (hour-axis-labels canvas points 40 720 468)
     (day-markers canvas points 40 720 118 440 454)
     canvas)))

(defn- draw-centered
  "Draws `text` horizontally centred on the panel, with its baseline at y."
  [canvas text y & {:keys [font]}]
  (img/draw-text canvas text (/ (- img/og-width (img/text-width canvas text :font font)) 2) y :font font))
