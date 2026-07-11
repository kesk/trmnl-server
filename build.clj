(ns build
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def uber-file "target/trmnl-server.jar")
(def basis (b/create-basis {:project "deps.edn"}))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis basis
                  :src-dirs ["src"]
                  :class-dir class-dir
                  ;; Quiet logback's startup status dump (incl. the build-only
                  ;; "logback.xml occurs multiple times on the classpath" WARN,
                  ;; from resources/ also being copied into target/classes) that
                  ;; AOT compilation triggers when it loads the logging nses.
                  ;; Build-scoped only — runtime logback config on the Pi is
                  ;; untouched, so a genuine misconfig still surfaces at startup.
                  :java-opts ["-Dlogback.statusListenerClass=ch.qos.logback.core.status.NopStatusListener"]})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis
           :main 'trmnl-server.main}))
