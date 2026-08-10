(ns trmnl-server.dev.label-collisions
  "Checks that nothing the temp/wind chart draws collides with anything else it
   draws: no label box overlapping a dot (its own included), and no two label
   boxes overlapping each other.

   This is the mechanical half of what `--demo`'s season renders do by eye. It
   exists because the failure mode is invisible until the wrong forecast comes
   along — a white halo silently eats whatever was drawn under it, so a
   collision doesn't look like a collision, it looks like a number lying on the
   line with its dot missing, or a label with its digits sliced off.

   Runs against the demo seasons, the checked-in fixtures, any local archive/
   forecasts, and a few thousand generated days:

       clojure -M:check-labels [days]

   Exits non-zero on the first dataset that collides, and prints enough to
   reproduce it (a fixture name, an archive filename, or a seed)."
  (:require [clojure.java.io :as io]
            [trmnl-server.core :as core]
            [trmnl-server.demo :as demo]
            [trmnl-server.image :as img])
  (:import [java.util Random]))

;; The chart box and keep-out rects forecast-screen renders with, read out of
;; core. These were restated here for a while, on the theory that a copy would
;; fail loudly if core's idea of them drifted. It drifted and nothing failed: the
;; check quietly moved to a chart 14px taller than the real one, with the cloud
;; band missing from its keep-out entirely. A check that plots into its own box
;; isn't checking the screen that ships, so there is one definition now
;; (core/screen-geometry) and this reads it.
(def ^:private chart-box (:chart-box core/screen-geometry))
(def ^:private keep-out (:keep-out core/screen-geometry))
;; The footprint draw-dot gives a labelled point. Still a local constant: it's a
;; property of image/draw-dot's radius plus halo, which core doesn't name either.
(def ^:private dot-clearance 7)

(defn- dot-rect [[x y]]
  [(- x dot-clearance) (- y dot-clearance) (+ x dot-clearance) (+ y dot-clearance)])

(defn- overlap [[ax1 ay1 ax2 ay2] [bx1 by1 bx2 by2]]
  (let [w (- (min ax2 bx2) (max ax1 bx1))
        h (- (min ay2 by2) (max ay1 by1))]
    (when (and (pos? w) (pos? h)) [(Math/round (double w)) (Math/round (double h))])))

(defn placed
  "The labels the chart would draw for `points`, in the box forecast-screen uses."
  [canvas points]
  (:labels (apply core/chart-labels canvas points (concat chart-box [:keep-out keep-out]))))

(defn- covered-columns
  "Pixel columns where a plotted series passes through the box, walking the
   segments and interpolating — the check's own reading of the line, independent
   of core's profile lookup."
  [[x1 y1 x2 y2] plot-points]
  (count
    (for [[[ax ay] [bx by]] (partition 2 1 plot-points)
          x                 (range (int (max x1 ax)) (inc (int (min x2 bx))))
          :let              [y (+ ay (* (- by ay) (/ (- x ax) (double (- bx ax)))))]
          :when             (<= (- y1 3) y (+ y2 3))]
      x)))

(defn line-crossings
  "How many labels sit across a series line rather than beside it.

   Not a failure: a halo keeps such a label legible, and on a crowded enough
   chart every candidate position crosses something, so the placer takes the
   least bad one. Reported as a number to watch — it stays at 0 for the demo
   seasons and the rain-test day, and at 1 for two fixtures: calm-hour, where the
   wind minimum sits on the chart floor and there is genuinely nowhere clean, and
   flat-start, where both series run flat and close together at the left edge so
   the first-point labels have to crowd. A real forecast can do it too —
   flat-start came off the archive.

   flat-start reads as 0 under the geometry this checker used to invent for
   itself, which is what the drift was hiding: on the real box its last wind
   label lies across the temperature line for 40 columns, against a tolerance of
   8. The generated-day total roughly doubles for the same reason. Nothing about
   the placement changed — this is the first honest count."
  [canvas points]
  (let [{:keys [labels temp-layout wind-layout]}
        (apply core/chart-labels canvas points (concat chart-box [:keep-out keep-out]))]
    (count
      (for [{:keys [box]} labels
            :let          [n (reduce + (map #(covered-columns box (:plot-points %))
                                         [temp-layout wind-layout]))]
            ;; same tolerance core places to: a few columns is a graze
            :when         (> n 8)]
        box))))

(defn collisions
  "Every overlap among the labels the chart would draw for `points`, as
   {:label :hits :px} maps. Empty means the screen is clean."
  [canvas points]
  (let [labels (placed canvas points)]
    (concat
      (for [l     labels
            d     labels
            :let  [px (overlap (:box l) (dot-rect (:dot d)))]
            :when px]
        {:label (:text l) :hits (str (:text d) "'s dot") :px px})
      (for [[a b] (partition 2 1 labels)
            :let  [px (overlap (:box a) (:box b))]
            :when px]
        {:label (:text a) :hits (str "label " (:text b)) :px px}))))

(defn- generated-day
  "A day of plausible-shaped weather: a diurnal sine per series with jitter,
   deliberately ranging wider than Gothenburg usually does — including the calm
   hours and big swings that put an extremum right on the chart floor, which is
   where the placement has to work hardest."
  [^Random rng]
  (let [rnd    (fn [n] (* n (.nextDouble rng)))
        series (fn [lo hi jitter floor]
                 (mapv (fn [i]
                         (let [v (+ (/ (+ lo hi) 2.0)
                                   (* (/ (- hi lo) 2.0) (Math/sin (- (* 2 Math/PI (/ (+ i 4) 24.0)) 1.9)))
                                   (- (rnd jitter) (/ jitter 2)))]
                           (cond-> (/ (Math/round (* 10.0 v)) 10.0) floor (max floor))))
                   (range core/default-forecast-hours)))]
    (mapv (fn [t w i]
            {:time          (str (.plusSeconds (java.time.Instant/parse "2026-01-01T00:00:00Z") (* 3600 i)))
             :temp          t
             :wind          w
             :symbol        1
             :precip-chance 0
             :precip-mm     0
             :cloud-cover   0})
      (series (- (rnd 12) 12) (+ 8 (rnd 14)) 2.0 nil)
      (series (rnd 2) (+ 4 (rnd 10)) 2.0 0.0)
      (range))))

(defn- edn-datasets [dir]
  (when (.isDirectory (io/file dir))
    (for [f     (sort (.listFiles (io/file dir)))
          :when (.endsWith (.getName f) ".edn")]
      [(str dir "/" (.getName f)) (read-string (slurp f))])))

(defn -main [& args]
  (let [days      (if (seq args) (parse-long (first args)) 3000)
        canvas    (img/blank-canvas)
        seed      (System/currentTimeMillis)
        rng       (Random. seed)
        datasets  (concat
                    (for [s demo/seasons] [(str "demo " (:label s)) (demo/season-points s core/default-forecast-hours)])
                    [["demo rain-test" (demo/rain-test-points core/default-forecast-hours)]]
                    (edn-datasets "dev/fixtures")
                    (edn-datasets "archive")
                    (for [i (range days)] [(str "generated day (seed " seed ", #" i ")") (generated-day rng)]))
        failures  (->> datasets
                    (keep (fn [[label points]]
                            (when-let [c (seq (collisions canvas points))] [label c])))
                    (take 5))
        crossings (reduce + (map (fn [[_ points]] (line-crossings canvas points)) datasets))]
    (println (format "checked %d datasets (%d generated, seed %d)" (count datasets) days seed))
    (println (format "%d labels sitting across a line (not a failure — see line-crossings)" crossings))
    (if (seq failures)
      (do (doseq [[label cs] failures]
            (println "\nFAIL" label)
            (doseq [{:keys [label hits px]} cs]
              (println (format "  \"%s\" overlaps %s by %dx%dpx" label hits (first px) (second px)))))
        (System/exit 1))
      (println "no label/dot or label/label collisions"))))
