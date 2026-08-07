(ns trmnl-server.server.devices
  "The registry of TRMNL devices this server serves: which MAC address is which
   display, where each one wants its forecast from, and the token that entitles it
   to poll.

   Read once from devices.edn at startup (`load!`, called from server/start!) and
   held in an atom — editing the file means restarting the service. That ceiling is
   deliberate: a household's worth of devices doesn't justify a mutable store or an
   admin UI.

   `:name` is the one field with reach outside this namespace. It is the URL segment
   in /images/<name>/… and /archive/<name>/…, the ?device= value on the pages, and
   the directory name under logs/ and archive/. Validating it against a strict
   [a-z0-9-]+ here, at load, is what lets all of those skip their own sanitising —
   the same move /status makes by matching ?day= against the days actually on disk."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.io File]))

(def ^:private name-pattern
  "What a :name may contain. Strict because it becomes a path segment and a directory
   name; see the namespace docstring."
  #"[a-z0-9-]+")

(def ^:private mac-pattern
  "What we're willing to treat as a MAC address at all. Applied to the `ID` header
   before it's recorded as an unknown device, so a scanner can't stuff arbitrary
   strings into that list — /api/display is reachable from the internet."
  #"[0-9A-F:-]{1,32}")

(def ^:private max-unknown
  "How many unregistered MACs to remember. A cap rather than a window, for the same
   reason: the endpoint is public and the list is only ever an aid to registering a
   real device."
  10)

(defonce ^:private registry (atom {}))

;; MAC -> epoch ms last seen. Devices that polled but aren't registered; surfaced on
;; /status so adding a new display doesn't mean grepping the log for its MAC.
(defonce ^:private unknown (atom {}))

(defn- registry-file ^File []
  (io/file (or (System/getenv "DEVICES_FILE") "devices.edn")))

(defn- normalise-mac
  "Uppercased and trimmed. The firmware sends `ID` as AA:BB:CC:DD:EE:FF, but a
   hand-written devices.edn shouldn't turn on letter case."
  [mac]
  (some-> mac str/trim str/upper-case not-empty))

(defn- validate-entry!
  "Throws unless one devices.edn entry is complete and usable. Loud on purpose: a
   device silently served the wrong town's weather is a worse outcome than a service
   that refuses to start."
  [mac {:keys [name lat lon token hours] :as device}]
  (letfn [(fail! [msg]
            (throw (ex-info (str "devices.edn: " (or mac "(missing MAC)") ": " msg)
                     {:mac mac :device device})))]
    (when-not mac
      (fail! "entry has a blank MAC address key"))
    (when-not (map? device)
      (fail! "entry must be a map"))
    (when-not (and (string? name) (re-matches name-pattern name))
      (fail! ":name is required and must match [a-z0-9-]+ — it becomes a URL segment and a directory name"))
    (when-not (and (number? lat) (number? lon))
      (fail! ":lat and :lon are required and must be numbers"))
    (when-not (and (string? token) (seq (str/trim token)))
      (fail! ":token is required — it's what the device sends back as Access-Token"))
    (when (and (some? hours) (not (pos-int? hours)))
      (fail! ":hours must be a positive integer when present"))))

(defn- parse
  "Validates the raw EDN and keys it by normalised MAC, tucking that MAC into each
   device map so a single value carries everything downstream needs."
  [raw]
  (when-not (map? raw)
    (throw (ex-info "devices.edn must contain a map of MAC address -> device" {:read (type raw)})))
  (let [devices (reduce-kv (fn [acc mac device]
                             (let [mac (normalise-mac mac)]
                               (validate-entry! mac device)
                               (assoc acc mac (assoc device :mac mac))))
                  {} raw)
        names   (map :name (vals devices))]
    (when (and (seq names) (not (apply distinct? names)))
      (throw (ex-info "devices.edn: two devices share a :name" {:names (sort names)})))
    devices))

(defn load!
  "Reads devices.edn into the registry.

   A missing file is a legitimate first-run state: log it and carry on empty, so
   /status still loads and can report the MAC of whatever polls next. A *malformed*
   one throws and takes the service down with it — see validate-entry!."
  []
  (let [f (registry-file)]
    (if (.isFile f)
      (let [devices (parse (edn/read-string (slurp f)))]
        (reset! registry devices)
        (log/info (str "Loaded " (count devices) " device(s) from " (.getPath f) ": "
                    (str/join ", " (sort (map :name (vals devices)))))))
      (do
        (reset! registry {})
        (log/warn (str "No device registry at " (.getPath f)
                    " — every device poll will be rejected until one exists."
                    " Copy devices.example.edn to get started."))))))

(defn all
  "Every registered device, ordered by :name — an EDN map has no order of its own,
   and the pickers and the /status default both want a stable one."
  []
  (sort-by :name (vals @registry)))

(defn by-mac
  "The device with this MAC address, or nil."
  [mac]
  (get @registry (normalise-mac mac)))

(defn by-name
  "The device with this :name, or nil. Used to resolve the ?device= parameter and the
   /images/<name>/ and /archive/<name>/ path segments — a nil here is what turns an
   unknown or hostile segment into a 404."
  [name]
  (first (filter #(= name (:name %)) (vals @registry))))

(defn for-request
  "The device a request's `ID` header names, or nil. http-kit lowercases header names."
  [request]
  (by-mac (get-in request [:headers "id"])))

(defn note-unknown!
  "Records a MAC that polled without being registered, so /status can show it. Ignores
   anything that doesn't look like a MAC, and keeps only the most recent few.

   Returns true the first time a given MAC is seen, so the caller can log it once rather
   than on every poll — /api/display is reachable from the internet, and a scanner
   shouldn't be able to fill the log by varying its ID header."
  [mac]
  (boolean
    (when-let [mac (normalise-mac mac)]
      (when (re-matches mac-pattern mac)
        (let [before @unknown]
          (swap! unknown (fn [seen]
                           (let [seen (assoc seen mac (System/currentTimeMillis))]
                             (if (> (count seen) max-unknown)
                               (into {} (take max-unknown (sort-by val > seen)))
                               seen))))
          (not (contains? before mac)))))))

(defn unknown-macs
  "MACs seen polling that aren't in the registry, newest first, as {:mac :seen-at}."
  []
  (->> @unknown
    (map (fn [[mac seen-at]] {:mac mac :seen-at seen-at}))
    (sort-by :seen-at >)))

(defn seen-unknown?
  "Whether this MAC has actually polled /api/display and been recorded above. The
   unregistered-device screen is rendered per MAC off an unauthenticated route, so this
   is what bounds it: only the handful of MACs already on the capped list can cause a
   render, rather than any ID header a stranger cares to invent."
  [mac]
  (contains? @unknown (normalise-mac mac)))
