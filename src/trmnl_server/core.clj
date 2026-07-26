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
   chart-labels' pick of a couple of extra local labels."
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

(def ^:private label-font (pixel-font :bold 16))

(def ^:private label-dot-radius
  "Radius of the dot marking a labelled point."
  4)

(def ^:private dot-clearance
  "How far a dot's ink reaches from its centre: label-dot-radius plus the 3px
   white halo draw-dot rings it with. Anything drawn inside this either gets
   erased by that halo or erases the dot itself, depending which is drawn
   second — so it's the exclusion zone label placement has to respect."
  (+ label-dot-radius 3))

(def ^:private label-clearance
  "How far a label's white halo reaches past its glyph ink — half of
   draw-text's 5px halo stroke, which is centred on the outline."
  2.5)

(defn- overlap-area
  "Area shared by two [x1 y1 x2 y2] rects; 0 when they're disjoint."
  [[ax1 ay1 ax2 ay2] [bx1 by1 bx2 by2]]
  (* (max 0.0 (- (min ax2 bx2) (max ax1 bx1)))
    (max 0.0 (- (min ay2 by2) (max ay1 by1)))))

(defn- overlaps? [a b] (pos? (overlap-area a b)))

(defn- inside? [[ax1 ay1 ax2 ay2] [bx1 by1 bx2 by2]]
  (and (>= ax1 bx1) (>= ay1 by1) (<= ax2 bx2) (<= ay2 by2)))

(defn- dot-rect
  "A labelled point's drawn footprint. Square rather than round: at this radius
   the corner error is a couple of pixels and it errs toward keeping labels
   clear, which is the safe direction."
  [[x y]]
  [(- x dot-clearance) (- y dot-clearance) (+ x dot-clearance) (+ y dot-clearance)])

(defn- label-rect
  "The footprint `text` will occupy when drawn at baseline anchor: its glyph ink
   (via image/text-box — the real outline, not the font's line box) grown by the
   halo it whites out around itself."
  [canvas text [x y]]
  (let [[x1 y1 x2 y2] (img/text-box canvas text x y :font label-font)]
    [(- x1 label-clearance) (- y1 label-clearance)
     (+ x2 label-clearance) (+ y2 label-clearance)]))

(def ^:private line-clearance
  "Half the width a plotted series occupies: draw-series-line's 2px stroke under
   draw-series-halo's 6px white underlay. A label that comes this close to a
   line is sitting on it, not merely near it."
  3)

(defn- line-profile
  "A series' y at every integer x it spans, as a vector indexed by x (nil outside
   its range) — so asking whether a label box sits on the line is a lookup per
   column rather than a walk over segments, for each of ~14 candidates per
   label."
  [plot-points]
  (reduce (fn [profile [[ax ay] [bx by]]]
            (reduce (fn [p x]
                      (assoc p x (+ ay (* (- by ay) (/ (- x ax) (double (- bx ax)))))))
              profile
              (range (int (Math/ceil ax)) (inc (int (Math/floor bx))))))
    (vec (repeat img/og-width nil))
    (partition 2 1 plot-points)))

(def ^:private line-ink-tolerance
  "Columns of line a label may cover before the placer looks for somewhere else.
   Not zero: a line clipping a box corner for a few columns is invisible, while
   insisting on a pristine position pushes labels out into the margin or onto a
   leader to buy nothing. Tuned by sweeping it over the archived forecasts —
   this is the knee, where the labels that really sit across a line move and the
   ones that merely grazed stay put."
  8)

(defn- line-ink
  "How many pixel columns of the chart's own lines a label box would white out.
   A label stays legible over a line — that is what its halo is for — but it
   only manages that by cutting a notch out of the line, so a position covering
   a line is worse than an equally reachable one that doesn't.

   This is the whole of the chart's sense of \"which side looks better\": no rule
   about peaks or troughs or how close the two series run, just a preference for
   the side of the dot that happens to be empty. It falls out that a label
   usually ends up outside the curve's bend, because that's where the space is."
  [[x1 y1 x2 y2] profiles]
  (count
    (for [profile profiles
          x       (range (max 0 (int x1)) (min img/og-width (inc (int x2))))
          :let    [y (nth profile x)]
          :when   (and y (<= (- y1 line-clearance) y (+ y2 line-clearance)))]
      x)))

(defn- label-candidates
  "Baseline anchors to try for one label, best first: hugging its dot on the
   side its kind points (up for a max, down for a min), then that same height
   shifted clear to either side, then the opposite side the same three ways,
   then level with the dot beside it, then far enough out to need a leader.
   Every candidate sits clear of the dot's own footprint, which is what stops a
   label ever being drawn through the dot it belongs to.

   The opposite side comes before beside deliberately: both are a last resort
   for a crowded dot, but a sideways label at a first or last point has nowhere
   to go except out into the margin, where it reads as a stray number."
  [canvas text kind [dot-x dot-y]]
  (let [[x1 y1 x2 y2] (img/text-box canvas text 0 0 :font label-font)
        at            (fn [x y leader?] {:anchor [x y] :leader? leader?})
        centred       (fn [cx] (- cx (/ (+ x1 x2) 2.0)))    ; anchor putting the ink's centre on cx
        step          (+ (/ (- x2 x1) 2.0) dot-clearance 4)
        mid-y         (- dot-y (/ (+ y1 y2) 2.0))
        near          {:max (- dot-y 12) :min (+ dot-y 26)} ; the offsets this chart has always used
        far           {:max (- dot-y 34) :min (+ dot-y 44)}
        other         ({:max :min :min :max} kind)
        row           (fn [y leader?]
                        [(at (centred dot-x) y leader?)
                         (at (centred (+ dot-x step)) y leader?)
                         (at (centred (- dot-x step)) y leader?)])
        beside        [(at (- (+ dot-x dot-clearance 4) x1) mid-y false)
                       (at (- (- dot-x dot-clearance 4) x2) mid-y false)]]
    (concat (row (near kind) false)
      (row (near other) false)
      beside
      (row (far kind) true)
      (row (far other) true))))

(defn- place-labels
  "Positions every label the chart wants in one pass, in importance order: each
   series' global high/low first (always drawn — with no shared numeric axis,
   those labels are the only place the real values appear), then the
   prominence-ranked extras.

   One hard rule, then two soft ones. A candidate is *allowed* only when it
   clears everything already committed: every dot including the label's own,
   every label box already placed, the `:keep-out` rects for whatever is drawn
   around the chart, and `:bounds` (the panel). That much is hard, because a
   halo silently erases whatever it lands on. Among the allowed ones it prefers,
   in order:

     1. inside `:leash` — near enough the plot box to still read as one of its
        labels rather than a number adrift in the margin;
     2. not sitting across either series' line (see line-ink), least-covered
        first once every position covers one;
     3. earliest in label-candidates' preference order — sort-by is stable, so
        this is what breaks every tie.

   Rank first and the soft rules only as tiebreaks is what keeps placement
   steady between renders: a label doesn't hop sides when the forecast shifts by
   a tenth of a degree, it moves only when the position it wants stops being
   clear. An extra with no allowed position at all is dropped, dot and all; a
   global falls back to its least-overlapping candidate rather than go
   unlabelled — the one case that can still put ink on ink.

   This replaced three separate ad-hoc guards that all used dot positions as a
   proxy for label positions — and so could neither see a label clamped onto
   its own dot, nor a displaced label landing on the other series' dot."
  [canvas specs {:keys [bounds leash keep-out profiles]}]
  (:placed
   (reduce
     (fn [{:keys [placed dots] :as acc} {:keys [dot text kind required?]}]
       (let [boxes     (map :box placed)
             obstacles (concat (map dot-rect (cons dot dots)) boxes keep-out)
             cost      (fn [box]
                         (+ (reduce + (map #(overlap-area box %) obstacles))
                           (if (inside? box bounds) 0.0 1e6)))
             ranked    (mapv (fn [c]
                               (let [box (label-rect canvas text (:anchor c))]
                                 (assoc c :box box :cost (cost box) :ink (line-ink box profiles))))
                         (label-candidates canvas text kind dot))
             allowed   (filter (comp zero? :cost) ranked)
             clean     (first (sort-by (juxt #(if (inside? (:box %) leash) 0 1)
                                        ;; everything within tolerance ties, so a
                                        ;; graze never outranks the preferred spot
                                         #(if (<= (:ink %) line-ink-tolerance) 0 (:ink %)))
                                allowed))
              ;; The label's own dot has to clear the labels already placed too:
              ;; a dot dropped onto an existing label erases it just as surely as
              ;; a label dropped onto a dot. (Can only bite an extra — every
              ;; global's dot is committed before anything is placed.)
             dot-free? (not-any? #(overlaps? (dot-rect dot) %) boxes)
             chosen    (cond
                         (and clean dot-free?) clean
                         required?             (first (sort-by :cost ranked))
                         :else                 nil)]
         (if chosen
           {:placed (conj placed (-> chosen
                                   (dissoc :cost :ink)   ; scoring scratch, not part of the result
                                   (assoc :dot dot :text text)))
            :dots   (if required? dots (conj dots dot))}
           acc)))
     {:placed [] :dots (mapv :dot (filter :required? specs))}
     specs)))

(defn- draw-placed-label
  "Draws one label where place-labels put it: the dot, then — when the label had
   to be displaced far enough that which dot it belongs to would otherwise be a
   guess — a recessive dashed leader to the nearest corner of the label box
   (before the label, so its white halo clears the leader too), then the text."
  [canvas {:keys [dot text anchor leader? box]}]
  (let [[dot-x dot-y] dot
        [x1 y1 x2 y2] box]
    (img/draw-dot canvas dot-x dot-y :radius label-dot-radius :halo? true)
    (when leader?
      (img/draw-dashed-line canvas dot-x dot-y
        (min (max dot-x x1) x2) (min (max dot-y y1) y2)))
    (img/draw-text canvas text (first anchor) (second anchor) :font label-font :halo? true)))

(defn- pick-extras
  "Up to `cap` extra turning points to label for one series, in prominence order
   across its maxima and minima, skipping the two global extrema (labelled
   anyway) and flat shoulders. Whether each one survives is place-labels' call:
   an extra with nowhere clean to put its label is dropped there. That's looser
   than the old rule, which rejected a candidate whenever its dot merely sat
   near another label's dot — even when its label would have fitted fine."
  [layout global-idxs cap]
  (->> (concat (map #(assoc % :kind :max) (:maxima (:candidates layout)))
         (map #(assoc % :kind :min) (:minima (:candidates layout))))
    (remove #(contains? global-idxs (:i %)))
    (filter #(pos? (:prom %)))  ; skip flat shoulders/plateaus -- not real turning points
    (sort-by :prom >)
    (take cap)))

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

(defn- draw-thunder-flash
  "A solid lightning bolt in a w×h box centered on (cx, cy). Backed by a white
   halo — the same bolt scaled up ~2px on each side — so it stays legible where
   it overlaps the cloud strip's checkerboard or the rain stipple behind it."
  [canvas cx cy w h]
  (let [;; normalized ⚡ outline (0..1 box), traced clockwise: a wide top "head"
        ;; narrowing to a mid step, then a left-offset lower stroke down to the
        ;; tip — the offset between the two strokes is what reads as a bolt.
        norm  [[0.42 0.00] [0.78 0.00] [0.50 0.42] [0.72 0.42]
               [0.20 1.00] [0.38 0.52] [0.16 0.52]]
        verts (fn [bw bh]
                (map (fn [[nx ny]]
                       [(+ cx (* (- nx 0.5) bw)) (+ cy (* (- ny 0.5) bh))])
                  norm))]
    (img/draw-polygon canvas (verts (+ w 4) (+ h 4)) :fill? true :color Color/WHITE)
    (img/draw-polygon canvas (verts w h) :fill? true)))

(defn thunder-flashes
  "Marks each thundery hour with a lightning bolt, centered vertically on cy.
   The point forecast has no lightning parameter, so thunder is read off the
   symbol code alone (see smhi/thunder?). Bolts are x-centered on that hour's
   slot — the same slot-per-point geometry (w/n wide) the rain-background column
   and the precip bar use, NOT the (n-1)-divisor plotting-point spacing of the
   cloud strip / temp-wind chart — so the flash sits centered over the rainy
   column it belongs to rather than drifting off its bar. It overlaps the cloud
   strip's lower edge (its halo carves it out of the checkerboard) and hangs
   into the gap above the temp/wind chart."
  [canvas points x cy w]
  (let [n           (count points)
        slot-center (fn [i] (+ x (* w (/ (+ i 0.5) n))))]
    (doseq [[i point] (map-indexed vector points)]
      (when (smhi/thunder? (:symbol point))
        (draw-thunder-flash canvas (slot-center i) cy 17.0 23.0)))))

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
   as positioned by place-labels. Each series contributes its global high and
   low (always labelled) plus up to two prominence-ranked local extrema (see
   pick-extras), so the ~1-day curve shows a secondary peak or valley wherever
   there's room for one.

   Split out of the drawing so the resulting geometry can be checked without
   rendering — see dev/label_collisions.clj, which asserts no label box ever
   lands on a dot or another label."
  [canvas points x y w h & {:keys [keep-out]}]
  (let [temp-layout (series-layout points :temp x y w h 5 nil)
        wind-layout (series-layout points :wind x y w h 5 0)
        series      [{:layout temp-layout                :value-key :temp
                      :fmt    (fn [t] (str (int t) "°"))}
                     {:layout wind-layout                                         :value-key :wind
                      :fmt    (fn [v] (str (int (Math/round (double v))) " m/s"))}]
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
                          {:keys [i kind]} (pick-extras layout globals 2)]
                      (spec s i kind false))]
    {:temp-layout temp-layout
     :wind-layout wind-layout
     :labels      (place-labels canvas (concat globals extras)
                    {;; Hard: stay on the panel, above whatever keep-out covers.
                     :bounds   [4 (- y 12) (- img/og-width 4) (+ y h 26)]
                     ;; Soft: a first- or last-point label is centred on the box
                     ;; edge, so half of it is always outside — but only this far.
                     ;; Without the leash a label dodging a line will happily set
                     ;; off into the margin, where it reads as a stray number
                     ;; rather than as one of the chart's labels.
                     :leash    [(- x 24) (- y 12) (+ x w 24) (+ y h 26)]
                     :keep-out keep-out
                     :profiles (map (comp line-profile :plot-points) [temp-layout wind-layout])})}))

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
   unrelated. Resolving that is chart-labels/place-labels' job, not this fn's:
   every label is positioned against the real boxes of every dot and every other
   label first, and only then drawn. :keep-out takes [x1 y1 x2 y2] rects for
   whatever else the screen draws around this box — forecast-screen passes the
   band holding precip-bar-chart's titles and bars — so a label can't be pushed
   out of the chart and onto them."
  [canvas points x y w h & {:keys [keep-out]}]
  (let [{:keys [temp-layout wind-layout labels]}
        (chart-labels canvas points x y w h :keep-out keep-out)]
    (draw-series-halo canvas temp-layout)
    (draw-series-halo canvas wind-layout :dash [6.0 5.0])
    (draw-series-line canvas temp-layout)
    (draw-series-line canvas wind-layout :dash [6.0 5.0])
    (doseq [label labels]
      (draw-placed-label canvas label))))

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
     (draw-weather-icon canvas now location 38 8 72)
     ;; Wordmark top-right, right edge flush with the 760 content margin (same
     ;; as the divider/Uppdaterad below it); its 38px height clears that line.
     (draw-logo canvas (- 760 logo-w) 14)
     (img/draw-text canvas (str (int (:temp now)) "°") 122 44 :font (pixel-font :bold 32))
     (img/draw-text canvas (str (int (Math/round (double (:wind now)))) " m/s, " condition) 122 68
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

     (let [precip-y    355
           precip-h    85
           ;; precip-bar-chart's "Regn (0-Xmm)" title sits at (- precip-y 6), so
           ;; its ink starts a little above that: fence off everything from there
           ;; down, across the full panel width, and combined-chart will place a
           ;; low label beside its dot rather than on top of the titles or bars.
           precip-band [0 (- precip-y 20) img/og-width (+ precip-y precip-h)]]
       (rain-background canvas points 40 136 (+ precip-y precip-h) 720)
       (cloud-cover-strip canvas points 40 136 720 :max-width 40.0)
       ;; Bolts are centered at y 158: they overlap the cloud strip's lower
       ;; edge (max extent y 136 + max-width/2 = 156) and hang into the gap
       ;; above the chart box top (172), the halo carving them out cleanly.
       (thunder-flashes canvas points 40 158 720)
       (combined-chart canvas points 40 172 720 155 :keep-out [precip-band])
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
