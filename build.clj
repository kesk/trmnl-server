(ns build
  (:require [clojure.string :as str]
            [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def uber-file "target/trmnl-server.jar")
(def basis (b/create-basis {:project "deps.edn"}))

(defn clean [_]
  (b/delete {:path "target"}))

(defn- git-commit
  "Short SHA of HEAD, with a \"-dirty\" suffix when the working tree has uncommitted
   changes, so a hand-built deploy is distinguishable from a clean one. nil if git
   isn't available (e.g. building from a source tarball without a .git dir)."
  []
  (try
    (let [sha   (b/git-process {:git-args "rev-parse --short HEAD"})
          dirty (not (str/blank? (b/git-process {:git-args "status --porcelain"})))]
      (when-not (str/blank? sha)
        (str sha (when dirty "-dirty"))))
    (catch Exception _ nil)))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["resources"]
               :target-dir class-dir})
  ;; Bake the deployed commit into a bundled resource so the running server (which
  ;; only has the uberjar, no .git) can report it on /status. Written after copy-dir
  ;; so it lands alongside the copied resources on the classpath.
  (b/write-file {:path (str class-dir "/version.edn")
                 :string (pr-str {:commit (git-commit)
                                  :built-at (str (java.time.Instant/now))})})
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
