(ns trmnl-server.main
  "CLI entry point. Kept separate from trmnl-server.core so core (screen composition)
   and server (HTTP serving) can each require the other one-way without a cycle:
   server requires core for forecast-screen, and this namespace requires both."
  (:require [trmnl-server.core :as core]
            [trmnl-server.demo :as demo]
            [trmnl-server.image :as img]
            [trmnl-server.server :as server])
  (:gen-class))

(defn- write-screen [canvas name]
  (img/save-image (:image canvas) (str "out/" name ".png"))
  (img/save-image (img/->1-bit canvas) (str "out/" name "-1bit.png"))
  (println (str "Wrote out/" name ".png and out/" name "-1bit.png")))

(defn- write-stale-demo
  "Writes out/demo-stale.png: one season's screen with the stale-warning badge
   (server.clj's SMHI-fetch-failure fallback) stamped on it, so the badge can
   be eyeballed without needing a real SMHI outage."
  [hours]
  (let [bw     (img/->1-bit (core/forecast-screen (demo/season-points (first demo/seasons) hours)))
        marked (core/stamp-stale-badge bw)]
    (img/save-image marked "out/demo-stale.png")
    (println "Wrote out/demo-stale.png")))

(defn- hours-arg
  "Reads an optional `--hours N` flag, falling back to core/default-forecast-hours."
  [args]
  (let [i (.indexOf ^java.util.List (vec args) "--hours")]
    (if (>= i 0)
      (Integer/parseInt (nth args (inc i)))
      core/default-forecast-hours)))

(defn- location-arg
  "Reads optional `--lat LAT --lon LON` flags, falling back to
   core/default-forecast-location."
  [args]
  (let [args  (vec args)
        lat-i (.indexOf ^java.util.List args "--lat")
        lon-i (.indexOf ^java.util.List args "--lon")]
    (if (and (>= lat-i 0) (>= lon-i 0))
      {:lat (Double/parseDouble (nth args (inc lat-i)))
       :lon (Double/parseDouble (nth args (inc lon-i)))}
      core/default-forecast-location)))

(defn -main [& args]
  (System/setProperty "java.awt.headless" "true")
  (let [hours    (hours-arg args)
        location (location-arg args)]
    (cond
      (some #{"--demo"} args)
      (do
        (doseq [{:keys [label file] :as season} demo/seasons]
          (println (str "Rendering " label "..."))
          (write-screen (core/forecast-screen (demo/season-points season hours)) file))
        (write-stale-demo hours))

      (some #{"--serve"} args)
      (server/start!)

      :else
      (write-screen (core/forecast-screen (core/live-points hours location) location) "preview"))))
