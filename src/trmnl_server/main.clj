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

(defn- hours-arg
  "Reads an optional `--hours N` flag, falling back to core/default-forecast-hours."
  [args]
  (let [i (.indexOf ^java.util.List (vec args) "--hours")]
    (if (>= i 0)
      (Integer/parseInt (nth args (inc i)))
      core/default-forecast-hours)))

(defn -main [& args]
  (System/setProperty "java.awt.headless" "true")
  (let [hours (hours-arg args)]
    (cond
      (some #{"--demo"} args)
      (doseq [{:keys [label file] :as season} demo/seasons]
        (println (str "Rendering " label "..."))
        (write-screen (core/forecast-screen (demo/season-points season hours)) file))

      (some #{"--serve"} args)
      (server/start!)

      :else
      (write-screen (core/forecast-screen (core/live-points hours)) "preview"))))
