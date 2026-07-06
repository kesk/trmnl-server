(ns trmnl-server.main
  "CLI entry point. Kept separate from trmnl-server.core so core (screen composition)
   and server (HTTP serving) can each require the other one-way without a cycle:
   server requires core for forecast-screen, and this namespace requires both."
  (:require [trmnl-server.core :as core]
            [trmnl-server.demo :as demo]
            [trmnl-server.image :as img]
            [trmnl-server.server :as server]))

(defn- write-screen [canvas name]
  (img/save-image (:image canvas) (str "out/" name ".png"))
  (img/save-image (img/->1-bit canvas) (str "out/" name "-1bit.png"))
  (println (str "Wrote out/" name ".png and out/" name "-1bit.png")))

(defn -main [& args]
  (System/setProperty "java.awt.headless" "true")
  (cond
    (some #{"--demo"} args)
    (doseq [{:keys [label file] :as season} demo/seasons]
      (println (str "Rendering " label "..."))
      (write-screen (core/forecast-screen (demo/season-points season)) file))

    (some #{"--serve"} args)
    (server/start!)

    :else
    (write-screen (core/forecast-screen) "preview")))
