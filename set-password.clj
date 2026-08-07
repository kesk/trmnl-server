#!/usr/bin/env bb
;; Sets the admin password for the human-facing pages (/, /status, /archive).
;;
;;   bb set-password.clj            live service  — ~/trmnl-server/admin.env on the Pi
;;   bb set-password.clj --test     test instance — ~/trmnl-server-test/admin.env
;;   bb set-password.clj --print    print the line, change nothing (local dev, or paste
;;                                  it somewhere yourself)
;;
;; The password is never stored, transmitted, or echoed in the clear: it is read from the
;; terminal with echo off, piped on *stdin* into the server's own hashing code
;; (`main --hash-password` — an argv would be visible in `ps` to every user on this
;; machine), and only the resulting salted PBKDF2 hash leaves this script. That hash is
;; what goes in admin.env; the plaintext exists only in this process's memory, and only
;; until it has been hashed.
;;
;; Hashing runs through the JVM project rather than being reimplemented here, so the code
;; that writes hashes and the code that checks them cannot drift apart — the format, the
;; salt length and the iteration count all live in trmnl-server.server.auth.
;;
;; Setting a password invalidates every existing session (the signing key is derived from
;; the stored hash, which gets a fresh salt each time), so everyone logged in is logged out.

(require '[babashka.process :refer [shell process]]
         '[clojure.string :as str])

(def host "dashboard-pi")

(def args (set *command-line-args*))
(def print-only? (contains? args "--print"))

(def target
  (if (contains? args "--test")
    {:label "test" :dir "trmnl-server-test" :unit "trmnl-server-test" :port 8081}
    {:label "live" :dir "trmnl-server" :unit "trmnl-server" :port 8080}))

(def remote-dir (str "~/" (:dir target)))

;; --- Reading the password -----------------------------------------------------------------

(defn read-hidden
  "Prompts and reads one line with terminal echo off, so the password never appears on
   screen or in scrollback. `stty` is restored on the way out even if the read throws —
   leaving a terminal with echo off is a nasty thing to do to somebody."
  [prompt]
  (print prompt)
  (flush)
  (let [tty? (zero? (:exit (shell {:continue true :out :string :err :string} "test" "-t" "0")))]
    (when-not tty?
      (println)
      (println "Not a terminal — the password would be echoed. Run this from a shell.")
      (System/exit 1))
    (try
      (shell {:inherit true} "stty" "-echo")
      (read-line)
      (finally
        (shell {:inherit true} "stty" "echo")
        (println)))))

(def password
  (let [first-try (read-hidden "New admin password: ")
        confirm   (read-hidden "Repeat it: ")]
    (when (str/blank? first-try)
      (println "Empty password — nothing done.")
      (System/exit 1))
    (when-not (= first-try confirm)
      (println "The two entries don't match — nothing done.")
      (System/exit 1))
    ;; Not a policy, just a floor. The login form is reachable from the internet; the
    ;; throttle slows guessing but doesn't excuse a four-character password.
    (when (< (count first-try) 12)
      (println (str "That's " (count first-try) " characters. Use at least 12 — this form is "
                 "reachable from the internet."))
      (System/exit 1))
    first-try))

;; --- Hashing ------------------------------------------------------------------------------

;; The pause here is the JVM starting, not the hashing — PBKDF2 at this iteration count is
;; milliseconds on a dev machine. It's the Pi that spends ~1.1s on it, once per login.
(println "Hashing (starting the JVM, a few seconds)...")

(def env-line
  (let [{:keys [exit out err]}
        @(process {:in password :out :string :err :string}
           "clojure" "-M" "-m" "trmnl-server.main" "--hash-password")]
    (when-not (zero? exit)
      (println "Hashing failed:")
      (println err)
      (System/exit 1))
    (str/trim out)))

(when-not (str/starts-with? env-line "ADMIN_PASSWORD_HASH=pbkdf2-sha256$")
  (println "Unexpected output from --hash-password — refusing to write it:")
  (println env-line)
  (System/exit 1))

(when print-only?
  (println)
  (println "Add this line to the service's admin.env (chmod 600) and restart it:")
  (println)
  (println env-line)
  (System/exit 0))

;; --- Writing it to the Pi -----------------------------------------------------------------

(let [{:keys [label dir unit port]} target]
  (println (str "Target: " label " (" host ":~/" dir ", port " port ", unit " unit ")")))

;; Written by piping into `tee` over ssh rather than interpolating the hash into the remote
;; command line: it keeps the value out of the Pi's process table and out of its shell
;; history, the same reason the password itself went in on stdin. umask 077 so the file is
;; 600 from the moment it exists, rather than briefly world-readable.
(println "Writing admin.env")
(let [{:keys [exit err]}
      @(process {:in (str env-line "\n") :out :string :err :string}
         "ssh" host (str "umask 077 && mkdir -p " remote-dir " && cat > " remote-dir "/admin.env"))]
  (when-not (zero? exit)
    (println "Could not write admin.env:")
    (println err)
    (System/exit 1)))

(println (str "Restart " (:unit target)))
(shell "ssh" host (str "sudo systemctl restart " (:unit target)))

(defn healthy? []
  (->> (shell {:continue true}
         "ssh" host (str "curl -sf -o /dev/null http://localhost:" (:port target) "/health"))
    :exit
    zero?))

(println "Waiting for the server to come back up...")
(loop [attempts-left 20]
  (cond
    (healthy?)
    (do
      (println (str "Password set on the " (:label target) " instance. Everyone previously"
                 " logged in has been logged out."))
      ;; The LAN address is plain http, so a login there crosses the network in the clear.
      ;; The tunnel is TLS end to end — worth saying at the moment somebody is about to
      ;; type this password for the first time.
      (println "Log in at https://trmnl.kluft.io — not the LAN address, which is unencrypted."))

    (pos? attempts-left)
    (do (Thread/sleep 1000) (recur (dec attempts-left)))

    :else
    (do (println "Server did not become healthy within 40s of restart — check the log.")
      (System/exit 1))))
