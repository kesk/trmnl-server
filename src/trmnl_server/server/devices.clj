(ns trmnl-server.server.devices
  "The registry of TRMNL devices this server serves: which MAC address is which
   display, where each one wants its forecast from, and the token that entitles it
   to poll.

   **A display arrives before anybody configures it.** The first time a MAC calls
   /api/setup it is *provisioned*: it is handed an :id and a token on the spot, stores both
   forever, and starts polling — showing that id on its own screen. It has no name and no
   location yet, so there is nothing to render for it and nothing to write down; it lives in
   the `provisional` atom, in memory, capped and expiring. Configuring it on the web is what
   writes devices.edn, and that carries the same :id and the same :token across untouched.

   So a display is in one of three states, and only the last is on disk:

     unknown       never seen, or long gone
     provisional   has an :id and a :token, no :name/:lat/:lon — memory only
     configured    an entry in devices.edn, serving forecasts

   **A token is minted once per MAC and never rotated.** That is the invariant this design
   exists for. The firmware calls /api/setup only when it has no stored key
   (`if (!preferences.isKey(PREFERENCES_API_KEY))`), so a display that is issued a *second*
   token never learns it: it keeps presenting the first one and is refused forever, with no
   way back but wiping its credentials. An earlier version of this namespace generated a
   token when a human registered a display, which did exactly that to any display that had
   already been paired. Nothing in the web UI issues a token now.

   **The :id is derived from the MAC**, as six characters of an HMAC keyed by a salt kept
   beside devices.edn (see `derived-id`). Deriving rather than storing is what lets a
   provisional display survive a restart: the device never tells us its id — it isn't in any
   header — so an id we only held in memory would be lost while the display went on showing
   it. Recomputing it from the MAC costs nothing and always agrees with the screen. The salt
   is what stops the id being ground back into a MAC, which matters because ids appear on
   the ungated /images/<id>/… route while /api/setup hands a token to anyone presenting a
   registered MAC.

   Deriving from the MAC is not the thing the id/name split forbids. That rule is about
   deriving an id from a *label* people change; a MAC is immutable hardware identity, so
   there is no rename to be confused by.

   **A display's identity and its label are two different fields.** `:id` is the identity:
   the URL segment in /images/<id>/… and in every human page under /devices/<id>/…, the
   directory name under logs/ and archive/, and the key of every in-memory cache in the
   serving path. `:name` is only ever shown to a human. That split exists so a display can
   be *renamed* — call it \"Hallway\" today and \"Kitchen\" tomorrow — without moving its
   archive and telemetry directories on disk, breaking every bookmark, and orphaning its
   wake-time history.

   Only :id is validated against a strict [a-z0-9-]+ here, at load, and that one check
   is what lets every path and directory downstream skip its own sanitising — the same
   move the device page makes by matching ?day= against the days actually on disk.
   :name needs no such rule: it reaches nothing but hiccup, which escapes it.

   devices.edn stays a hand-editable file and a hand edit still needs a restart — the file
   is the source of truth, the atom is a cache of it, and nothing watches it for changes.
   What the web writes is the configuring of a display that is already talking to us;
   removing one, or changing an :id, is still an edit and a restart, because each of those
   strands something on disk."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.io File]
           [java.nio.charset StandardCharsets]
           [java.nio.file CopyOption Files StandardCopyOption]
           [java.nio.file.attribute FileAttribute]
           [java.security SecureRandom]
           [java.util Base64]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(def ^:private id-pattern
  "What an :id may contain. Strict because it becomes a path segment and a directory
   name; see the namespace docstring. :name has no such pattern — it's a label."
  #"[a-z0-9-]+")

(def ^:private mac-pattern
  "What we're willing to treat as a MAC address at all. Applied to the `ID` header before
   anything is done with it, so a scanner can't provision itself a slot by inventing
   identifiers — /api/setup and /api/display are reachable from the internet."
  #"[0-9A-F:-]{1,32}")

(def ^:private max-provisional
  "How many displays may be waiting to be configured at once. Small on purpose: this is a
   list of things somebody is about to click, not an inventory, and the endpoint that fills
   it takes no password. Eviction drops the least recently seen, so a display that is
   actually polling can't be pushed off it by anything else."
  5)

(def ^:private provisional-retention-ms
  "How long after its last poll a provisional display stays on the list.

   A provisional display polls every 5 seconds — the firmware's own constant for the
   not-yet-claimed state — so it restamps itself far faster than this and only ages out when
   it genuinely stops. Half an hour then means \"switched off, flat, or out of range\", not
   \"having a bad minute\", and it errs long deliberately: a display that has just gone quiet
   costs nothing to keep listed, while dropping one early takes away the thing somebody was
   about to click."
  (* 30 60 1000))

(def ^:private id-alphabet
  "Characters an :id is built from: lower-case letters and digits, minus the look-alikes
   (0/o, 1/l/i). Nobody has to type one — every page is a link — but they are read off a
   screen and said out loud, which is the whole point of showing one."
  "23456789abcdefghjkmnpqrstuvwxyz")

(def ^:private id-length 6)

(defonce ^:private registry (atom {}))

;; MAC -> {:id :token :first-seen :last-seen}: displays that have been through /api/setup
;; but that nobody has configured yet. Memory only, and deliberately so — the endpoint that
;; creates these takes no password, and this is the one place that would otherwise let an
;; unauthenticated caller write to disk.
;;
;; Nothing here is precious, which is what makes memory-only safe: the :id is recomputed
;; from the MAC (derived-id), and the :token comes back from the device itself on the next
;; request, since it sends Access-Token on every poll. A restart therefore costs one poll
;; cycle and changes nothing the display is showing.
;;
;; :last-seen is restamped by every poll, so the list reads as "these are asking to be
;; configured right now" — see provisional-retention-ms.
(defonce ^:private provisional (atom {}))

;; The id-derivation salt, 16 bytes, read (or created) on first use. See derived-id.
(defonce ^:private id-salt (atom nil))

;; Mutations are serialised: each one reads the registry, writes the whole file, and only
;; then swaps the atom, so two forms submitted at once can't interleave a read-modify-write
;; and lose one of the two entries. Writing before swapping is deliberate too — a failed
;; write leaves memory and disk still agreeing, which is the state the next restart reads.
(defonce ^:private write-lock (Object.))

;; --- Files ------------------------------------------------------------------------------

(defn- registry-file ^File []
  (io/file (or (System/getenv "DEVICES_FILE") "devices.edn")))

(defn- salt-file ^File []
  (io/file (or (System/getenv "ID_SALT_FILE") "id-salt")))

(defn- write-atomically!
  "Writes `content` to `f` through a temp file in the same directory, renamed over the old
   one — so neither a crash nor a concurrent read can see a half-written file, and a failed
   write leaves the previous one intact rather than truncated. Files/createTempFile creates
   it owner-only on POSIX and the rename carries those permissions over, which both files
   written here want: one holds every display's token, the other the salt that keeps ids
   from being ground back into MAC addresses."
  [^File f ^String content]
  (let [dir (or (.getParentFile (.getAbsoluteFile f)) (io/file "."))
        tmp (Files/createTempFile (File/.toPath dir) ".trmnl" ".tmp"
              (into-array FileAttribute []))]
    (try
      (spit (.toFile tmp) content)
      (Files/move tmp (File/.toPath f)
        (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
      (catch Exception e
        (Files/deleteIfExists tmp)
        (throw e)))))

(defn- normalise-mac
  "Uppercased and trimmed. The firmware sends `ID` as AA:BB:CC:DD:EE:FF, but a
   hand-written devices.edn shouldn't turn on letter case."
  [mac]
  (some-> mac str/trim str/upper-case not-empty))

;; --- Deriving an id ---------------------------------------------------------------------

(defn- load-salt!
  "Reads the id salt, creating it on first run. 16 bytes from SecureRandom, base64 in a file
   of its own beside devices.edn ($ID_SALT_FILE to move it).

   It's a file rather than a value in memory because ids have to survive a restart: a
   provisional display goes on showing the id it was given, and the only way to agree with it
   afterwards is to recompute the same one. It's a file of its own rather than a field in
   devices.edn because devices.edn is hand-edited and rewritten, and this is a key that
   silently re-ids every unconfigured display if it changes."
  []
  (let [f (salt-file)]
    (if (.isFile f)
      (reset! id-salt (.decode (Base64/getDecoder) (str/trim (slurp f))))
      (let [raw (byte-array 16)]
        (.nextBytes (SecureRandom.) raw)
        (write-atomically! f (str (.encodeToString (Base64/getEncoder) raw) "\n"))
        (log/info (str "Created a new device-id salt at " (.getPath f)))
        (reset! id-salt raw)))))

(defn- salt-bytes ^bytes []
  (or @id-salt (locking write-lock (or @id-salt (load-salt!)))))

(defn derived-id
  "The :id belonging to a MAC address: six characters of HMAC-SHA256(salt, MAC), mapped
   through id-alphabet.

   A pure function of the MAC and the salt, which is the point — the device never tells us
   its id (buildDisplayHeaders sends the MAC and the token, nothing else), so anything we
   only remembered would be lost on restart while the display went on showing it. Keyed
   rather than a plain hash because a bare MAC hash is brute-forceable — a known OUI leaves
   ~16M candidates — and ids are visible on /images/<id>/…, which can't sit behind the
   login; recovering a MAC from one would hand over a display's token, since /api/setup
   issues one on presentation of a MAC alone.

   Six characters of a 31-character alphabet is ~887M, so a collision between a household's
   displays is somewhere near impossible. It also isn't retryable — this is a function, not
   a draw — so `configure!` refuses the clash rather than papering over it, and a
   hand-written :id remains the way out."
  [mac]
  (when-let [mac (normalise-mac mac)]
    (let [hmac (Mac/getInstance "HmacSHA256")]
      (.init hmac (SecretKeySpec. (salt-bytes) "HmacSHA256"))
      (->> (.doFinal hmac (String/.getBytes mac StandardCharsets/UTF_8))
        (take id-length)
        (map #(nth id-alphabet (mod (Byte/toUnsignedInt %) (count id-alphabet))))
        (apply str)))))

;; --- Loading devices.edn ----------------------------------------------------------------

(defn- entry-problems
  "Everything wrong with one devices.edn entry, as messages written to be read by a person.

   Both callers need the same rules but not the same shape: `validate-entry!` throws the
   first problem and takes the service down with it, while the configure form wants to list
   all of them next to the fields that caused them. Keeping one function to produce them is
   what stops a form from accepting an entry that the next restart then refuses to load.

   Every field is required, including the ones a provisional display hasn't got: an entry
   only reaches this — or the file — once somebody has configured it. `mac` is already
   normalised."
  [mac {:keys [id name lat lon token hours] :as device}]
  (cond-> []
    (not mac)
    (conj "the MAC address is blank")

    (and mac (not (re-matches mac-pattern mac)))
    (conj (str (pr-str mac) " doesn't look like a MAC address"))

    (not (map? device))
    (conj "the entry must be a map")

    (not (and (string? id) (re-matches id-pattern id)))
    (conj ":id is required and must match [a-z0-9-]+ — it becomes a URL segment and a directory name")

    (not (and (string? name) (seq (str/trim name))))
    (conj ":name is required — it's the label the pages show for this display")

    ;; One message per field rather than one for the pair: they're two boxes on a form, and
    ;; "one of these two is wrong" is the sort of error that sends you round the loop twice.
    (not (number? lat))
    (conj ":lat is required and must be a number — decimal degrees")

    (not (number? lon))
    (conj ":lon is required and must be a number — decimal degrees")

    ;; Range-checked because the form is a way to typo them: a latitude of 577.15 is never a
    ;; place, and the failure it causes otherwise is a 404 from SMHI hours later.
    (and (number? lat) (not (<= -90 lat 90)))
    (conj ":lat must be between -90 and 90")

    (and (number? lon) (not (<= -180 lon 180)))
    (conj ":lon must be between -180 and 180")

    (not (and (string? token) (seq (str/trim token))))
    (conj ":token is required — it's what the device sends back as Access-Token")

    (and (some? hours) (not (pos-int? hours)))
    (conj ":hours must be a positive integer when present")))

(defn- validate-entry!
  "Throws unless one devices.edn entry is complete and usable. Loud on purpose: a
   device silently served the wrong town's weather is a worse outcome than a service
   that refuses to start."
  [mac device]
  (when-let [problem (first (entry-problems mac device))]
    (throw (ex-info (str "devices.edn: " (or mac "(missing MAC)") ": " problem)
             {:mac mac :device device}))))

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
        ids     (map :id (vals devices))
        names   (map :name (vals devices))]
    (when (and (seq ids) (not (apply distinct? ids)))
      (throw (ex-info "devices.edn: two devices share an :id" {:ids (sort ids)})))
    ;; A clash of *labels* is only confusing, not wrong — nothing is keyed on :name any
    ;; more — so it's worth saying out loud without refusing to serve any display over it.
    (when (and (seq names) (not (apply distinct? names)))
      (log/warn (str "devices.edn: two devices share a :name — the pages will show the same "
                  "label for both. Their URLs and directories are unaffected (those follow :id).")))
    devices))

(defn load!
  "Reads devices.edn into the registry, and the id salt alongside it.

   A missing registry is a legitimate state, and more obviously so now: a server that has
   never been configured still provisions whatever polls it, lists it on /, and works the
   moment somebody fills in the form. A *malformed* one throws and takes the service down
   with it — see validate-entry!."
  []
  (load-salt!)
  (let [f (registry-file)]
    (if (.isFile f)
      (let [devices (parse (edn/read-string (slurp f)))]
        (reset! registry devices)
        (log/info (str "Loaded " (count devices) " device(s) from " (.getPath f) ": "
                    (str/join ", " (sort (map :id (vals devices)))))))
      (do
        (reset! registry {})
        (log/info (str "No device registry at " (.getPath f)
                    " — displays that poll will be offered for configuration on /."))))))

(defn all
  "Every configured device, ordered by :name — an EDN map has no order of its own, and
   the index wants a stable one. By label rather than by :id because this order is the
   one a human reads on /, and case-insensitively because a label is free to be
   capitalised."
  []
  (sort-by (comp str/lower-case :name) (vals @registry)))

(defn by-mac
  "The configured device with this MAC address, or nil."
  [mac]
  (get @registry (normalise-mac mac)))

(defn by-id
  "The configured device with this :id, or nil. Used to resolve the /devices/<id> and
   /images/<id>/ path segments — a nil here is what turns an unknown or hostile segment
   into a 404."
  [id]
  (first (filter #(= id (:id %)) (vals @registry))))

(defn for-request
  "The configured device a request's `ID` header names, or nil. http-kit lowercases header
   names."
  [request]
  (by-mac (get-in request [:headers "id"])))

(defn generate-token
  "A fresh Access-Token for a display being provisioned: 24 random bytes as URL-safe base64.

   Called from exactly one place — the first /api/setup a MAC ever makes — and never again
   for that MAC. See the namespace docstring: a display that is issued a second token has no
   way to learn it."
  []
  (let [raw (byte-array 24)]
    (.nextBytes (SecureRandom.) raw)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) raw)))

;; --- Displays waiting to be configured --------------------------------------------------

(defn- live-provisional
  "The provisional map with the entries that have gone quiet dropped — see
   provisional-retention-ms. Filtered on read rather than swept on a timer, so an entry
   expires on time even when nothing is polling to trigger a sweep; the map is capped, so
   there's nothing to reclaim by deleting them."
  []
  (let [cutoff (- (System/currentTimeMillis) provisional-retention-ms)]
    (into {} (remove #(< (:last-seen (val %)) cutoff)) @provisional)))

(defn provision!
  "Records that `mac` is asking to be served, and returns what it should be told:
   {:mac :id :token :first-seen :last-seen}. nil for anything that isn't MAC-shaped, which
   is the caller's cue to refuse — these routes are open to the internet.

   The :id is derived, so it is the same on every call and across restarts. The token is
   whichever of these exists first: the one already recorded, the one the device is
   presenting (`presented`, from its Access-Token header — that's how a display provisioned
   before a restart re-teaches us its own token), or a fresh one. Adopting a presented token
   costs nothing, since a provisional display is served no data to protect; once configured,
   the registry's token is enforced strictly.

   Restamps :last-seen on every call and evicts the least recently seen past
   max-provisional, so a display that is actually polling holds its place."
  [mac presented]
  (when-let [mac (normalise-mac mac)]
    (when (re-matches mac-pattern mac)
      (let [id  (derived-id mac)
            now (System/currentTimeMillis)]
        (when-let [clash (by-id id)]
          (when-not (= mac (:mac clash))
            (log/error (str "Derived id " id " for " mac " is already configured for "
                         (:mac clash) " — configuring this display will be refused"))))
        (-> (swap! provisional
              (fn [m]
                (let [existing (get m mac)
                      entry    {:mac        mac
                                :id         id
                                :token      (or (not-empty (some-> presented str/trim))
                                              (:token existing)
                                              (generate-token))
                                :first-seen (:first-seen existing now)
                                :last-seen  now}
                      m'       (assoc m mac entry)]
                  (if (> (count m') max-provisional)
                    (into {} (take max-provisional (sort-by (comp :last-seen val) > m')))
                    m'))))
          (get mac))))))

(defn provisional-by-mac
  "What we're holding for this MAC, or nil if it isn't waiting to be configured."
  [mac]
  (get (live-provisional) (normalise-mac mac)))

(defn provisional-by-id
  "The provisional display with this :id, or nil — how the configure page resolves its
   path segment."
  [id]
  (first (filter #(= id (:id %)) (vals (live-provisional)))))

(defn provisional-list
  "Displays waiting to be configured, newest sighting first."
  []
  (sort-by :last-seen > (vals (live-provisional))))

;; --- Writing the registry ---------------------------------------------------------------

(def ^:private file-header
  "The comment block at the top of a generated devices.edn. Short on purpose: the field
   documentation lives in devices.example.edn, and this file is rewritten in full on every
   change, so a comment written here by hand wouldn't survive the next one."
  (str ";; trmnl-server device registry — MAC address -> display.\n"
    ";;\n"
    ";; Written by the server when a display is configured or edited through the web pages,\n"
    ";; and rewritten in full each time: hand-written comments below this block do not\n"
    ";; survive that. Editing by hand is still fine — the file is the source of truth and\n"
    ";; is read at startup, so restart the service afterwards. See devices.example.edn for\n"
    ";; what each field means.\n\n"))

(def ^:private written-keys
  "The fields written out, in the order they appear in the file. Also what keeps :mac from
   being written *inside* an entry: parse tucks it in there for the convenience of callers
   downstream, but on disk it is the key, and writing it twice would let the two disagree."
  [:id :name :lat :lon :token :hours])

(defn- entry-str
  "One `\"MAC\" {…}` pair, laid out the way devices.example.edn is — fixed key order, one
   field per line, values aligned. Generated rather than pretty-printed so that the file a
   human opens after the server has written it looks like one they'd have written
   themselves; this is still a file meant to be read and edited by hand."
  [mac device]
  (let [ks     (filter #(some? (get device %)) written-keys)
        width  (apply max (map #(count (str %)) ks))
        head   (str (pr-str mac) " {")
        indent (apply str (repeat (inc (count head)) \space))
        field  (fn [k] (str (format (str "%-" width "s") (str k)) " " (pr-str (get device k))))]
    (str head (str/join (str "\n" indent) (map field ks)) "}")))

(defn- registry-str
  "The whole registry as the text of a devices.edn, entries ordered by :id so that a
   diff of two versions of the file shows only what actually changed."
  [devices]
  (str file-header
    "{" (str/join "\n\n " (map (fn [[mac device]] (entry-str mac device))
                            (sort-by (comp :id val) devices)))
    "}\n"))

(defn- save! [devices]
  (write-atomically! (registry-file) (registry-str devices)))

(defn- commit!
  "Persists a proposed registry and makes it live, or hands back the reasons it can't be.
   The shared tail of configure! and update!, and the only place either one writes."
  [devices entry errors]
  (if (seq errors)
    {:errors errors}
    (do
      (save! devices)
      (reset! registry devices)
      {:device entry})))

(defn configure!
  "Turns a display that has been polling into a configured one: writes devices.edn with the
   name and location somebody has just supplied.

   The :id and the :token come from the provisional entry, unchanged — this function issues
   neither. That is what makes configuring a display safe to do twice, or after a restart,
   or on a display that was configured before and removed: the credential it is already
   using goes on working.

   Returns {:device …} on success or {:errors [msg …]} on invalid input; bad input is an
   ordinary answer to a form, not an exception. A failed *write* does throw — the caller
   can't fix that by resubmitting, and it needs to reach the log."
  [id {:keys [name lat lon hours]}]
  (locking write-lock
    (if-let [waiting (provisional-by-id id)]
      (let [mac    (:mac waiting)
            device (cond-> {:id    id
                            :name  (some-> name str/trim)
                            :lat   lat
                            :lon   lon
                            :token (:token waiting)}
                     (some? hours) (assoc :hours hours))
            errors (cond-> (entry-problems mac device)
                     (some #(= id (:id %)) (vals @registry))
                     (conj (str ":id " (pr-str id) " is already configured for another display")))
            entry  (assoc device :mac mac)
            result (commit! (assoc @registry mac entry) entry errors)]
        (when (:device result)
          (swap! provisional dissoc mac)
          (log/info (str "Configured display " id " (" mac ")")))
        result)
      {:errors [(str "No display with id " (pr-str id) " is waiting to be configured. "
                  "If it stopped polling it will reappear here within a few seconds of "
                  "coming back.")]})))

(defn update!
  "Changes the fields of a configured display that are safe to change: :name, :lat, :lon
   and :hours. Deliberately not :id (it is the directory name and every URL — see the
   namespace docstring), not the MAC (it is the identity the firmware presents, so changing
   it means a different display), and not :token (nothing would tell the display its new
   one). Those three are still a hand edit and a restart.

   Same contract as configure!. The caller is responsible for dropping the display's cached
   screen afterwards — moving it to a new town leaves a render of the old one behind."
  [id {:keys [name lat lon hours]}]
  (locking write-lock
    (if-let [current (by-id id)]
      (let [mac    (:mac current)
            device (cond-> (assoc (dissoc current :mac :hours)
                             :name (some-> name str/trim)
                             :lat  lat
                             :lon  lon)
                     (some? hours) (assoc :hours hours))
            entry  (assoc device :mac mac)
            result (commit! (assoc @registry mac entry) entry (entry-problems mac device))]
        (when (:device result)
          (log/info (str "Updated display " id " from the web")))
        result)
      {:errors [(str "no display with :id " (pr-str id))]})))
