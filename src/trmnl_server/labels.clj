(ns trmnl-server.labels
  "Direct labelling of a plotted series: which points are worth a label, and where
   each label's box can go without landing on anything else already drawn. Pure
   geometry — nothing here knows about temperature, wind or forecasts; the caller
   supplies dots, texts and the rects to stay off (see core/chart-labels).

   It's split out of the drawing so the resulting geometry can be checked without
   rendering — see dev/label_collisions.clj, which asserts no label box ever lands
   on a dot or another label. Extend that when extending this: on a 1-bit surface a
   collision doesn't look like a collision, because a white halo silently erases
   whatever it lands on."
  (:require [trmnl-server.image :as img]))

;; --- Which points deserve a label -------------------------------------------------------

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

(defn peaks
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

(defn pick-extras
  "Up to `cap` extra turning points to label for one series, in prominence order
   across its `{:maxima :minima}` candidates, skipping the two global extrema
   (labelled anyway) and flat shoulders. Whether each one survives is `place`'s
   call: an extra with nowhere clean to put its label is dropped there. That's
   looser than the old rule, which rejected a candidate whenever its dot merely
   sat near another label's dot — even when its label would have fitted fine."
  [candidates global-idxs cap]
  (->> (concat (map #(assoc % :kind :max) (:maxima candidates))
         (map #(assoc % :kind :min) (:minima candidates)))
    (remove #(contains? global-idxs (:i %)))
    (filter #(pos? (:prom %)))  ; skip flat shoulders/plateaus -- not real turning points
    (sort-by :prom >)
    (take cap)))

;; --- What a label and a dot actually cover ----------------------------------------------

(def ^:private label-font (img/pixel-font :bold 16))

(def ^:private dot-radius
  "Radius of the dot marking a labelled point."
  4)

(def ^:private dot-clearance
  "How far a dot's ink reaches from its centre: dot-radius plus the 3px white halo
   draw-dot rings it with. Anything drawn inside this either gets erased by that
   halo or erases the dot itself, depending which is drawn second — so it's the
   exclusion zone label placement has to respect."
  (+ dot-radius 3))

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

;; --- Where the plotted lines are --------------------------------------------------------

(def ^:private line-clearance
  "Half the width a plotted series occupies: draw-series-line's 2px stroke under
   draw-series-halo's 6px white underlay. A label that comes this close to a
   line is sitting on it, not merely near it."
  3)

(defn line-profile
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

;; --- Placement --------------------------------------------------------------------------

(defn- candidates
  "Baseline anchors to try for one label, best first: hugging its dot on the
   side its kind points (up for a max, down for a min), then that same height
   shifted clear to either side, then the opposite side the same three ways,
   then level with the dot beside it, then far enough out to need a leader.
   Every candidate sits clear of the dot's own footprint, which is what stops a
   label ever being drawn through the dot it belongs to.

   The opposite side comes before beside deliberately: both are a last resort
   for a crowded dot, but a sideways label at a first or last point has nowhere
   to go except out into the margin, where it reads as a stray number.

   The last four reach a second step sideways, and only on the rows that already
   draw a leader. They exist for a dot cornered against the panel edge, where one
   step isn't enough to clear what's in the way and there is no room on the other
   side: a series minimum at the last point has the keep-out band below it, the
   panel edge to its right, and the other series' bottom label and dot to its
   left, which is exactly the 14-blocked-candidates case `place` used to resolve
   by dropping its box onto that dot. Reaching further is what a leader is for,
   so these get one; being last, they can only ever be picked when every
   ordinary position is worse."
  [canvas text kind [dot-x dot-y]]
  (let [[x1 y1 x2 y2] (img/text-box canvas text 0 0 :font label-font)
        at            (fn [x y leader?] {:anchor [x y] :leader? leader?})
        centred       (fn [cx] (- cx (/ (+ x1 x2) 2.0)))    ; anchor putting the ink's centre on cx
        step          (+ (/ (- x2 x1) 2.0) dot-clearance 4)
        mid-y         (- dot-y (/ (+ y1 y2) 2.0))
        near          {:max (- dot-y 12) :min (+ dot-y 26)} ; the offsets this chart has always used
        far           {:max (- dot-y 34) :min (+ dot-y 44)}
        other         ({:max :min :min :max} kind)
        row           (fn [y leader? steps]
                        (for [k steps]
                          (at (centred (+ dot-x (* k step))) y leader?)))
        beside        [(at (- (+ dot-x dot-clearance 4) x1) mid-y false)
                       (at (- (- dot-x dot-clearance 4) x2) mid-y false)]]
    (concat (row (near kind) false [0 1 -1])
      (row (near other) false [0 1 -1])
      beside
      (row (far kind) true [0 1 -1])
      (row (far other) true [0 1 -1])
      (row (far kind) true [2 -2])
      (row (far other) true [2 -2]))))

(defn place
  "Positions every label the caller wants in one pass, in importance order: the
   `:required?` ones first (always drawn — with no shared numeric axis, those
   labels are the only place the real values appear), then the optional extras.

   One hard rule, then two soft ones. A candidate is *allowed* only when it
   clears everything already committed: every dot including the label's own,
   every label box already placed, the `:keep-out` rects for whatever is drawn
   around the chart, and `:bounds` (the panel). That much is hard, because a
   halo silently erases whatever it lands on. Among the allowed ones it prefers,
   in order:

     1. inside `:leash` — near enough the plot box to still read as one of its
        labels rather than a number adrift in the margin;
     2. not sitting across either series' line (see line-ink, fed by `:profiles`),
        least-covered first once every position covers one;
     3. earliest in `candidates`' preference order — sort-by is stable, so this
        is what breaks every tie.

   Rank first and the soft rules only as tiebreaks is what keeps placement
   steady between renders: a label doesn't hop sides when the forecast shifts by
   a tenth of a degree, it moves only when the position it wants stops being
   clear. An extra with no allowed position at all is dropped, dot and all; a
   required one falls back to its least-overlapping candidate rather than go
   unlabelled — the one case that can still put ink on ink.

   This replaced three separate ad-hoc guards that all used dot positions as a
   proxy for label positions — and so could neither see a label clamped onto
   its own dot, nor a displaced label landing on the other series' dot. Do not
   add a new \"nudge it a bit when X\" special case here; that is what this
   replaced."
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
                         (candidates canvas text kind dot))
             allowed   (filter (comp zero? :cost) ranked)
             clean     (first (sort-by (juxt #(if (inside? (:box %) leash) 0 1)
                                       ;; everything within tolerance ties, so a
                                       ;; graze never outranks the preferred spot
                                         #(if (<= (:ink %) line-ink-tolerance) 0 (:ink %)))
                                allowed))
             ;; The label's own dot has to clear the labels already placed too:
             ;; a dot dropped onto an existing label erases it just as surely as
             ;; a label dropped onto a dot. (Can only bite an extra — every
             ;; required label's dot is committed before anything is placed.)
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

(defn draw-placed
  "Draws one label where `place` put it: the dot, then — when the label had to be
   displaced far enough that which dot it belongs to would otherwise be a guess —
   a recessive dashed leader to the nearest corner of the label box (before the
   label, so its white halo clears the leader too), then the text."
  [canvas {:keys [dot text anchor leader? box]}]
  (let [[dot-x dot-y] dot
        [x1 y1 x2 y2] box]
    (img/draw-dot canvas dot-x dot-y :radius dot-radius :halo? true)
    (when leader?
      (img/draw-dashed-line canvas dot-x dot-y
        (min (max dot-x x1) x2) (min (max dot-y y1) y2)))
    (img/draw-text canvas text (first anchor) (second anchor) :font label-font :halo? true)))
