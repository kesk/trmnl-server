#!/usr/bin/env bb
;; Builds an uberjar and ships it to dashboard-pi, then restarts the systemd service.
;;
;; Two targets, deliberately independent of each other on the same Pi:
;;
;;   (default)  the live service — ~/trmnl-server, port 8080, unit trmnl-server
;;   --test     a second instance — ~/trmnl-server-test, port 8081, unit
;;              trmnl-server-test, with its own registry, archive and device logs
;;
;; The test target exists to rehearse a change against a real display before it
;; touches the one on the wall — notably registering a device, which is hard to
;; practise any other way. Nothing is shared between them but the host.
;;
;; The device registry (devices.edn) is *not* shipped by default: the local copy is a
;; development one with placeholder MAC addresses, and overwriting a real registry
;; on every deploy would point displays at the wrong forecasts. Pass --devices to
;; push it deliberately.

(require '[babashka.process :refer [shell]]
         '[babashka.fs :as fs])

(def host "dashboard-pi")
(def jar-path "target/trmnl-server.jar")
(def devices-path "devices.edn")

(def args (set *command-line-args*))
(def push-devices? (contains? args "--devices"))

(def target
  (if (contains? args "--test")
    {:label "test" :dir "trmnl-server-test" :unit "trmnl-server-test" :port 8081
     :unit-src "deploy/trmnl-server-test.service"}
    {:label "live" :dir "trmnl-server" :unit "trmnl-server" :port 8080
     :unit-src "deploy/trmnl-server.service"}))

(let [{:keys [label dir unit port]} target]
  (println (str "Target: " label " (~/" dir ", port " port ", unit " unit ")")))

(def remote-dir (str "~/" (:dir target)))

(println "Build uber jar")
(shell "clojure" "-T:build" "uber")

;; Harmless for the live target, and what makes a first --test deploy work at all.
(shell "ssh" host (str "mkdir -p " remote-dir))

(println "Copy jar")
(shell "scp" jar-path (str host ":" remote-dir "/trmnl-server.jar"))

(if push-devices?
  (do
    (when-not (fs/exists? devices-path)
      (println (str "No " devices-path " to push")) (System/exit 1))
    (println "Copy device registry")
    (shell "scp" devices-path (str host ":" remote-dir "/devices.edn")))
  ;; Not pushing: at least make it obvious when the target has no registry at all, since
  ;; the server will start but refuse every device poll. On a fresh --test deploy that is
  ;; the *intended* state — it's how a device announces its MAC on /status.
  (when-not (zero? (:exit (shell {:continue true}
                            "ssh" host (str "test -f " remote-dir "/devices.edn"))))
    (println "NOTE: no devices.edn on" host (str remote-dir " — every device poll will be rejected"))
    (println "      until one exists. Unregistered MACs are listed on /status.")))

(println "Copy service file")
(shell "scp" (:unit-src target) (str host ":~/" (:unit target) ".service"))
(shell "ssh" host (str "sudo mv ~/" (:unit target) ".service"
                    " /etc/systemd/system/" (:unit target) ".service"
                    " && sudo systemctl daemon-reload"
                    " && sudo systemctl enable " (:unit target)))

(println (str "Restart " (:unit target)))
(shell "ssh" host (str "sudo systemctl restart " (:unit target)))

;; /health rather than /api/display: the device API now resolves the caller's ID header
;; against the registry and 404s an unknown device, so a bare curl is no longer a
;; liveness signal.
(defn healthy? []
  (->> (shell {:continue true}
         "ssh" host (str "curl -sf -o /dev/null http://localhost:" (:port target) "/health"))
    :exit
    zero?))

(println "Waiting for server to come back up...")
(loop [attempts-left 20]
  (cond
    (healthy?) (println (str "Deployed and healthy (" (:label target) ")."))
    (pos? attempts-left) (do (Thread/sleep 1000)
                           (recur (dec attempts-left)))
    :else (do (println "Server did not become healthy within 40s of restart")
            (System/exit 1))))
