#!/usr/bin/env bb
;; Builds an uberjar and ships it to dashboard-pi, then restarts the systemd service.

(require '[babashka.process :refer [shell]])

(def host "dashboard-pi")
(def jar-path "target/trmnl-server.jar")
(def unit-path "deploy/trmnl-server.service")

(shell "clojure" "-T:build" "uber")

(shell "scp" jar-path (str host ":~/trmnl-server/trmnl-server.jar"))

(shell "scp" unit-path (str host ":~/trmnl-server.service"))
(shell "ssh" host "sudo mv ~/trmnl-server.service /etc/systemd/system/trmnl-server.service && sudo systemctl daemon-reload")

(shell "ssh" host "sudo systemctl restart trmnl-server")

(defn healthy? []
  (->> (shell {:continue true}
              "ssh" host "curl -sf -o /dev/null http://localhost:8080/api/display")
       :exit
       zero?))

(println "Waiting for server to come back up...")
(loop [attempts-left 20]
  (cond
    (healthy?) (println "Deployed and healthy.")
    (pos? attempts-left) (do (Thread/sleep 2000)
                              (recur (dec attempts-left)))
    :else (do (println "Server did not become healthy within 40s of restart")
              (System/exit 1))))
