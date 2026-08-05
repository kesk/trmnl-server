#!/usr/bin/env bb
;; Builds an uberjar and ships it to dashboard-pi, then restarts the systemd service.
;;
;; The device registry (devices.edn) is *not* shipped by default: the local copy is a
;; development one with placeholder MAC addresses, and overwriting the Pi's real registry
;; on every deploy would point both displays at the wrong forecasts. Pass --devices to
;; push it deliberately.

(require '[babashka.process :refer [shell]]
         '[babashka.fs :as fs])

(def host "dashboard-pi")
(def jar-path "target/trmnl-server.jar")
(def unit-path "deploy/trmnl-server.service")
(def devices-path "devices.edn")

(def push-devices? (contains? (set *command-line-args*) "--devices"))

(println "Build uber jar")
(shell "clojure" "-T:build" "uber")

(println "Copy jar")
(shell "scp" jar-path (str host ":~/trmnl-server/trmnl-server.jar"))

(if push-devices?
  (do
    (when-not (fs/exists? devices-path)
      (println (str "No " devices-path " to push")) (System/exit 1))
    (println "Copy device registry")
    (shell "scp" devices-path (str host ":~/trmnl-server/devices.edn")))
  ;; Not pushing: at least make it obvious when the Pi has no registry at all, since the
  ;; server will start but refuse every device poll.
  (when-not (zero? (:exit (shell {:continue true}
                            "ssh" host "test -f ~/trmnl-server/devices.edn")))
    (println "WARNING: no devices.edn on" host "— every device poll will be rejected.")
    (println "         Create one there, or re-run with --devices to push the local copy.")))

(println "Copy service file")
(shell "scp" unit-path (str host ":~/trmnl-server.service"))
(shell "ssh" host "sudo mv ~/trmnl-server.service /etc/systemd/system/trmnl-server.service && sudo systemctl daemon-reload")

(println "Restart trmnl-server")
(shell "ssh" host "sudo systemctl restart trmnl-server")

;; /health rather than /api/display: the device API now resolves the caller's ID header
;; against the registry and 404s an unknown device, so a bare curl is no longer a
;; liveness signal.
(defn healthy? []
  (->> (shell {:continue true}
         "ssh" host "curl -sf -o /dev/null http://localhost:8080/health")
    :exit
    zero?))

(println "Waiting for server to come back up...")
(loop [attempts-left 20]
  (cond
    (healthy?) (println "Deployed and healthy.")
    (pos? attempts-left) (do (Thread/sleep 1000)
                           (recur (dec attempts-left)))
    :else (do (println "Server did not become healthy within 40s of restart")
            (System/exit 1))))
