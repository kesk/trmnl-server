(ns trmnl-server.server.battery
  "What a raw Battery-Voltage reading means: the discharge curve, the voltage that counts as
   empty, and the percent estimate read off them. Two readers — the device page, which prints
   the percent and fits a days-left line down to `cutoff-volts`, and the screen render, which
   draws it as the header's battery icon — so it lives here rather than in either.")

(def cutoff-volts
  "Where the battery counts as empty: 0% on lipo-curve and the target pages/battery-forecast
   extrapolates to. A provisional figure — a typical LiPo protection circuit cuts out
   somewhere around 3.0 V, and this pack's real cutoff is only known once a display has
   actually died on it; set it to the last voltage that display reported."
  3.10)

(def ^:private lipo-curve
  "Open-circuit voltage → state of charge for a single LiPo cell, as [volts percent]
   pairs from full to empty. A generic curve for the chemistry, not one measured on this
   pack. The firmware takes its reading right after waking, before WiFi is turned on
   (bl.cpp: \"BEFORE WiFi is turned on\"), so it is nearer rest than a reading mid-transmit
   would be, but the ESP32 is awake and drawing, so it still sits a little below it — the
   percent it yields is a \"~\" figure. The long plateau through the 3.9–3.7 V range is
   where a straight 3.0–4.2 V line used to read \"72%\" for a cell that's nearer 60. Below
   3.6 V the cell is on its knee and the voltage falls away fast while the last few percent
   last; the bottom of the curve runs to cutoff-volts rather than to the 3.27 V it used to
   end at, which read a display still running happily at 3.63 V as nearly empty."
  [[4.20 100] [4.15 95] [4.11 90] [4.08 85] [4.02 80] [3.98 75] [3.95 70] [3.91 65]
   [3.87 60] [3.85 55] [3.84 50] [3.82 45] [3.80 40] [3.79 35] [3.77 30] [3.75 25]
   [3.73 20] [3.71 15] [3.69 10] [3.61 5] [3.40 2] [3.10 0]])

(defn percent
  "Charge estimate for a raw battery_voltage reading: linear interpolation along
   lipo-curve, clamped to its ends. A double — callers round for display. nil for nil.

   Takes the reading at face value, so a caller holding the OG's \"no reading\" -1 has to
   filter it first: that clamps to 0.0, which is a perfectly good answer for 3.0 V and a
   wrong one for a sensor that didn't report."
  [voltage]
  (when voltage
    (let [v (double voltage)]
      (cond
        (>= v (ffirst lipo-curve))       100.0
        (<= v (first (last lipo-curve))) 0.0
        :else
        (some (fn [[[v1 p1] [v2 p2]]]
                (when (and (<= v v1) (>= v v2))
                  (+ p2 (* (- p1 p2) (/ (- v v2) (- v1 v2))))))
          (partition 2 1 lipo-curve))))))
