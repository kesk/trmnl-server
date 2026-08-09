(ns trmnl-server.server
  "HTTP surface for the forecast screens: routing, the API a real TRMNL OG device polls
   when pointed at a custom server (GET /api/display, GET /api/setup, POST /api/log),
   the file routes serving rendered and archived PNGs, and the human-facing pages
   (/, the per-display /devices/<id> and /devices/<id>/archive, and the /login form gating
   them). Uses http-kit as both the Ring request/response convention
   and the embedded server — handlers are plain (fn [request] response-map) fns
   dispatched on :request-method/:uri in `handler`, chosen over a Ring+Jetty stack for
   a single, self-contained dependency given how few routes there are.

   Every device-facing route is scoped to one registered display: the firmware's `ID`
   header (its MAC) resolves through server.devices, and its `Access-Token` — issued by
   /api/setup — is checked on every later request. An unrecognised MAC is refused and
   recorded — and shown its own MAC on its own screen, which is how a new display gets
   registered.

   The human pages are scoped the same way but by path — /devices/<id>/… , resolved
   through the same registry — so an unknown display is a 404 here rather than a fallback
   inside the page. That segment is the registry's `:id`, never its `:name` and never its
   MAC. Not the name, so a display can be renamed without breaking its URLs (see
   server.devices); not the MAC, because /api/setup issues a token on presentation of a
   registered MAC alone, so it stays out of reach even for someone past the login.

   The human pages are gated separately, by a single admin password and a signed session
   cookie (server.auth) — a browser can log in, a display cannot, so the two
   authentication schemes stay disjoint: /api/*, /images/* and /health never see the
   session gate.

   Two of those pages are forms rather than reports: /devices/new registers a display that
   has polled without an entry, and /devices/<id>/edit changes where an existing one's
   forecast comes from. They are the only routes here that write anything, so they carry a
   second check on top of the session — see same-origin? — and they are the reason the
   registry is no longer read-only at runtime (server.devices).

   The work behind the routes lives in the six server.* namespaces: devices (the
   registry), render (the screens and their caches), archive (the rolling 24h disk
   archive), telemetry (what the devices report about themselves), auth (the admin
   password gate), aliases (the readable symlinks beside the id-named directories on
   disk), and pages (the HTML)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [org.httpkit.server :as httpkit]
            [ring.util.codec :as codec]
            [trmnl-server.server.aliases :as aliases]
            [trmnl-server.server.archive :as archive]
            [trmnl-server.server.auth :as auth]
            [trmnl-server.server.devices :as devices]
            [trmnl-server.server.pages :as pages]
            [trmnl-server.server.render :as render]
            [trmnl-server.server.telemetry :as telemetry])
  (:import [java.net Inet4Address NetworkInterface]))

(def ^:private refresh-rate-seconds 900)

;; --- Response helpers -------------------------------------------------------------------

(defn- json-response [body]
  {:status  200
   :headers {"Content-Type" "application/json"}
   :body    (json/write-str body)})

(defn- html-response [body]
  {:status  200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    body})

(defn- png-response [bytes-or-file]
  {:status  200
   :headers {"Content-Type" "image/png"}
   :body    bytes-or-file})

(defn- text-response [status body]
  {:status  status
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body    body})

(defn- redirect
  "303 See Other, so the browser follows a POSTed login with a GET."
  ([location] (redirect location nil))
  ([location set-cookie]
   {:status  303
    :headers (cond-> {"Location" location}
               set-cookie (assoc "Set-Cookie" set-cookie))}))

(defn- query-param
  "The value of query-string key `k`, percent-decoded, or nil. Repeated keys (?day=a&day=a)
   decode to a vector; the first wins, so this always hands back a single string.

   Decoded, but not trusted: a caller that turns it into a filename or a path still has to
   validate it — pages/status matches ?day= against the days actually on disk. Decoding
   raises the stakes there rather than lowering them: an escaped `..` arrives here as a
   real one."
  [request k]
  (let [params (codec/form-decode (or (:query-string request) ""))
        value  (when (map? params) (get params k))]
    (if (vector? value) (first value) value)))

(defn- path-segments
  "The non-empty `/`-separated segments of a request path."
  [uri]
  (vec (remove str/blank? (str/split uri #"/"))))

(defn- base-url
  "The externally visible origin of this request, used to build the absolute image_url
   handed to the device.

   Derived per request rather than fixed at startup because the two displays reach this
   server by different routes — one over the LAN, one through a Cloudflare tunnel — and
   each has to be told the URL that works for it. It also has to be exactly right: the
   firmware only attaches its ID and Access-Token headers to the image fetch when the
   image URL string-prefixes the base URL it was given, so a scheme mismatch silently
   costs us those headers. cloudflared terminates TLS but forwards the original Host and
   sets X-Forwarded-Proto: https, so honouring that header is what keeps the tunnel's
   URLs on https. Spoofing it gains an attacker nothing — the worst case is being told
   about an image URL that then doesn't resolve for them."
  [request fallback]
  (if-let [host (get-in request [:headers "host"])]
    (let [proto (or (some-> (get-in request [:headers "x-forwarded-proto"])
                      (str/split #",") first str/trim not-empty)
                  "http")]
      (str proto "://" host))
    fallback))

(defn- image-url [base device-id filename]
  (str base "/images/" device-id "/" filename))

;; --- Device API -------------------------------------------------------------------------

(defn- parse-display-headers
  "Pulls the telemetry the TRMNL firmware sends on every /api/display poll out of the
   request headers (see trmnl-firmware buildDisplayHeaders). http-kit lowercases header
   names; every value is a string, so coerce the numeric ones and let parse-*/nil swallow
   anything malformed (e.g. the OG's -1 or a FAKE_BATTERY_VOLTAGE 4.2). Returns nil when
   no device headers are present, so a browser hitting /api/display doesn't clobber the
   last real snapshot."
  [headers]
  (letfn [(s [k] (get headers k))]
    (when (or (s "id") (s "fw-version") (s "battery-voltage"))
      {:battery-voltage (some-> (s "battery-voltage") parse-double)
       :rssi            (some-> (s "rssi") parse-long)
       :wake-time       (some-> (s "wake-time") parse-long)
       :update-source   (s "update-source")
       :image-cached    (some-> (s "image-cached") (= "true"))   ; tri-state: true/false/nil
       :refresh-rate    (some-> (s "refresh-rate") parse-long)
       :fw-version      (s "fw-version")
       :fw-commit       (s "fw-commit")
       :model           (s "model")
       :received-at     (System/currentTimeMillis)})))

(defn- mac-header
  "The MAC the firmware puts in `ID` on every request, of every kind."
  [request]
  (get-in request [:headers "id"]))

(defn- token-header [request]
  (get-in request [:headers "access-token"]))

(defn- provision!
  "Provisions (or re-provisions) whichever display is asking, and returns what to tell it.
   nil when the `ID` header isn't MAC-shaped, which is a caller's cue to refuse — these
   routes take no password.

   Logs the first sighting only. A display waiting to be configured polls every 5 seconds,
   so logging each one would bury everything else in the file within the hour.

   **Which route provisioned it is the interesting part**, and worth the two branches. Via
   /api/setup the display had no credentials, asked for some, and stored the `friendly_id`
   we just handed it — so the id on its screen is the one below. Via any other route it
   already had credentials, which means it will never ask again (`bl.cpp` only calls
   getDeviceCredentials when the key is missing) and may still be showing a `friendly_id`
   from an earlier pairing — with this server under an older scheme, or with trmnl.com.
   Then the id on the panel and the id on `/` disagree and the whole read-it-and-click-it
   handshake quietly breaks, which is exactly what happened the first time this shipped."
  [request]
  (let [mac   (mac-header request)
        known (devices/provisional-by-mac mac)
        entry (devices/provision! mac (token-header request))]
    (when (and entry (not known))
      (if (= "/api/setup" (:uri request))
        (log/info (str "Provisioned " (:id entry) " for " (:mac entry)
                    " — waiting to be configured"))
        (log/warn (str "Provisioned " (:id entry) " for " (:mac entry) " from "
                    (:uri request) " — it arrived with credentials already, so its screen"
                    " may still show a friendly id from an earlier pairing. A soft reset"
                    " on the device clears that and makes the two agree."))))
    entry))

(defn- setup-logo-url [base]
  (str base "/images/setup-logo.png"))

(defn- provisional-display-response
  "What /api/display tells a display nobody has configured yet: HTTP **200**, carrying
   `\"status\": 202` in the body.

   **The 202 is the body's field, not the HTTP status**, and the difference is the whole
   thing. `fetchApiDisplay` (api-client/display.cpp) tests the HTTP code *before* it reads
   anything, and accepts only 200, 301 and 429:

     if (httpCode < 0 || !(httpCode == HTTP_CODE_OK || … )) → HTTPS_RESPONSE_CODE_INVALID

   which paints WIFI_INTERNAL_ERROR — \"WiFi connected, but API connection cannot be
   established\". Only once past that does bl.cpp parse the payload and switch on
   `request_status = apiResponse.status`, where 202 means \"known but not claimed\". Sending
   a real HTTP 202 therefore never reaches the branch it was meant to trigger. This is the
   same shape as /api/setup, whose `status` is likewise a body field — the transport says
   \"I answered\", the body says what the answer was.

   What the 202 branch then does is exactly what onboarding wants: it paints the Friendly ID
   screen — the id we handed out at /api/setup, plus the message we sent with it — and drops
   the sleep to SLEEP_TIME_WHILE_NOT_CONNECTED, 5 seconds. So the id sits on the panel until
   somebody configures the display, and the first forecast lands seconds after they do. We
   render nothing for any of it; the screen is composed on the device."
  [request]
  (if-let [entry (provision! request)]
    (json-response {:status  202
                    :id      (:id entry)
                    :message "Waiting to be configured."})
    (text-response 404 "unknown device\n")))

(defn- with-device
  "Resolves the request's `ID` header to a *configured* display, checks its Access-Token,
   and calls `f` with it. Wrong or missing token → 401; a MAC that isn't configured → nil
   from `f`'s point of view, so the caller decides (each route wants something different
   for a display that is only provisional)."
  [request f not-configured]
  (if-let [device (devices/for-request request)]
    (if (= (:token device) (token-header request))
      (f device)
      (do
        (log/warn (str "Rejected " (:uri request) " for " (:id device) " — bad Access-Token"))
        (text-response 401 "bad access token\n")))
    (not-configured)))

(defn- display-response
  "The main device poll. A configured display gets its forecast; anything else is
   provisioned and told to wait — see provisional-display-response."
  [request fallback-base]
  (with-device request
    (fn [device]
      (when-let [status (parse-display-headers (:headers request))]
        (telemetry/record-poll! (:id device) status))
      (let [filename (render/serve-filename (render/current-image device))]
        (json-response {:filename          filename
                        :image_url         (image-url (base-url request fallback-base) (:id device) filename)
                        :image_url_timeout 0
                        :refresh_rate      refresh-rate-seconds
                        :reset_firmware    false
                        :update_firmware   false
                        :firmware_url      nil})))
    #(provisional-display-response request)))

(defn- setup-response
  "First contact. Authenticated by MAC alone — this is the request a display makes
   precisely because it has no token yet — and it **always succeeds** for a MAC-shaped ID:
   an unknown one is provisioned on the spot and handed the credentials it needs to start
   polling, which is what makes a display usable before anybody has told the server
   anything about it.

   Never answer 404 here, whatever the MAC. The firmware reads the HTTP status without
   parsing the body and treats 404 as MAC_NOT_REGISTERED: it paints a logo, stores a
   15-minute sleep, and calls goToSleep() *inside that branch*, so it never reaches
   /api/display at all. That cost a real display an evening (DEVICE-ISSUES.md #2).

   `status` in the body is load-bearing too: parseResponse_apiSetup bails unless it is
   exactly 200, and the firmware only persists api_key/friendly_id on that path. Omitting
   it (as this server once did) leaves the device re-running setup on every wake, forever
   tokenless.

   `friendly_id` is the display's :id — the firmware writes it to its preferences once,
   here, never asks again, and prints it on the Friendly ID screen. That is the string
   somebody reads off the panel and clicks on `/`, which is why it has to be the identifier
   that never changes.

   `image_url` is fetched and stored as the device's logo, not shown as a screen (see
   downloadSetupImage), but it has to resolve: a failed fetch paints API_IMAGE_DOWNLOAD_ERROR.
   `message` is the one line we get to put under the id on that screen, so it carries the
   address to configure the display at."
  [request fallback-base]
  (let [base (base-url request fallback-base)]
    (if-let [device (devices/for-request request)]
      (do
        (log/info (str "Setup: re-issued its token to " (:id device) " (" (:mac device) ")"))
        (json-response {:status      200
                        :api_key     (:token device)
                        :friendly_id (:id device)
                        :image_url   (setup-logo-url base)
                        :message     (str "SMHI forecast · " base)}))
      (if-let [entry (provision! request)]
        (json-response {:status      200
                        :api_key     (:token entry)
                        :friendly_id (:id entry)
                        :image_url   (setup-logo-url base)
                        :message     (str "Configure at " base)})
        (text-response 404 "unknown device\n")))))

(defn- log-response
  "Device telemetry. Accepted from a display that is only provisional as well as a
   configured one — a display that is failing to get past onboarding is exactly when its
   own log is worth having, and it files under the same :id it will keep afterwards."
  [request]
  (with-device request
    (fn [device]
      (telemetry/append-log! (:id device) (slurp (:body request)))
      {:status 204})
    (fn []
      (if-let [entry (provision! request)]
        (do
          (telemetry/append-log! (:id entry) (slurp (:body request)))
          {:status 204})
        (text-response 404 "unknown device\n")))))

;; --- File routes ------------------------------------------------------------------------

(defn- setup-logo-response
  "The image /api/setup points a display at. The firmware downloads it, writes it to its
   own filesystem as a logo, and paints its Friendly ID screen — so what this actually has
   to do is exist and be a valid image: a failed fetch shows API_IMAGE_DOWNLOAD_ERROR
   instead. (The screen itself is drawn with the firmware's built-in logo, not this one —
   `storedLogoOrDefault` reads from flash and never reads the downloaded file back — so
   sending the SMHI wordmark is a bet on a future firmware rather than something visible
   today.)

   Ungated and unauthenticated, like the other /images routes: it's one small static file,
   the same for every caller."
  []
  (if-let [logo (io/resource "smhi-logo.png")]
    (png-response (io/input-stream logo))
    {:status 404}))

(defn- image-response
  "Serves one device's currently cached PNG: /images/<id>/forecast-<hash>.png. The
   device segment has to be a registered display's :id and the filename has to match a hash
   the cache is actually holding (render/bytes-for compares rather than reads), so
   neither segment can name anything off-list."
  [uri]
  (let [[_ device-id filename] (path-segments uri)]
    (if-let [device (and device-id filename (devices/by-id device-id))]
      (if-let [bytes (render/bytes-for (render/current-image device) filename)]
        (png-response bytes)
        {:status 404})
      {:status 404})))

(defn- archive-file-response
  "Serves one archived file by name from a device's archive dir — the PNG screen or its
   sibling `.edn` forecast dump, at /devices/<id>/archive/<file>. The device is the one
   the router already resolved (so its :id is a registry entry's, validated to [a-z0-9-]+
   at load) and the filename is constrained to a flat `forecast-*.{png,edn}` basename, so
   neither segment can escape the directory. The `.edn` is sent as an attachment so the
   gallery's data link downloads rather than renders it."
  [device requested]
  (if-let [[_ ext] (re-matches #"forecast-[0-9A-Za-z-]+\.(png|edn)" requested)]
    (let [file (io/file (archive/dir (:id device)) requested)]
      (if (.isFile file)
        (if (= ext "png")
          (png-response file)
          {:status  200
           :headers {"Content-Type"        "application/edn; charset=utf-8"
                     "Content-Disposition" (str "attachment; filename=\"" requested "\"")}
           :body    file})
        {:status 404}))
    {:status 404}))

;; --- Admin session ----------------------------------------------------------------------

(defn- gated
  "Wraps one of the human pages behind the admin session: served as normal to a request
   carrying a valid session cookie (or when no password is configured at all), otherwise
   a redirect to /login remembering where it was going.

   `page-fn` is a thunk rather than a value so an unauthenticated request costs nothing —
   a device page re-reads a log file off disk and / walks the render caches, and neither should
   happen for a caller that isn't going to see the result."
  [request page-fn]
  (if (auth/authenticated? request)
    (page-fn)
    (redirect (auth/login-url request))))

(defn- auth-state
  "How this request is getting past the gate, for the pill the pages show: :open (no
   password configured at all), :lan (exempt because it came from the home network), or
   :session (it presented a cookie, so there's something to log out of)."
  [request]
  (cond
    (not (auth/enabled?))       :open
    (auth/trusted-peer? request) :lan
    :else                        :session))

(defn- login-page
  "The form itself. Already logged in, exempt, or nothing to log into → straight through to
   the destination, so a bookmarked /login isn't a dead end. On a connection that would
   carry the password in the clear, the form is replaced by the reason why — better than
   letting somebody type it and only then refusing."
  [request]
  (let [next-path (auth/safe-next (query-param request "next"))]
    (cond
      (auth/authenticated? request) (redirect next-path)
      (auth/cleartext-login? request)
      (assoc (html-response (pages/login next-path :insecure)) :status 403)
      :else
      (html-response (pages/login next-path (when (auth/misconfigured?) :misconfigured))))))

(defn- login-submit
  "Checks a submitted password. A success replaces whatever cookie the caller had with a
   fresh session and redirects on; a failure re-renders the form with a 401, and enough
   of them trips auth/locked-out? (which is checked first, so the cooldown can't be
   spent by guessing during it)."
  [request]
  (let [params    (auth/form-params (some-> (:body request) slurp))
        next-path (auth/safe-next (get params "next"))]
    (cond
      ;; Nothing to log into: the GET already redirects, so this is only reachable by
      ;; posting to it directly. Send it the same way rather than failing a check that
      ;; can't succeed.
      (not (auth/enabled?))
      (redirect next-path)

      ;; Refused before the password is even looked at, so a cleartext attempt doesn't
      ;; count against the throttle and — more to the point — a password that has already
      ;; crossed the wire in the clear is never treated as usable.
      (auth/cleartext-login? request)
      (do (log/warn (str "Refused a login over an unencrypted connection from "
                      (:remote-addr request)))
        (assoc (html-response (pages/login next-path :insecure)) :status 403))

      ;; Ahead of the throttle: a broken admin.env isn't the caller's fault, and telling
      ;; them "wrong password" for an hour while they retype a correct one is the worst
      ;; version of this.
      (auth/misconfigured?)
      (assoc (html-response (pages/login next-path :misconfigured)) :status 500)

      (auth/locked-out?)
      (do (log/warn "Admin login attempt during failed-login cooldown")
        (assoc (html-response (pages/login next-path :locked)) :status 429))

      (auth/check-password! (get params "password"))
      (do (log/info "Admin login succeeded")
        (redirect next-path (auth/login-cookie request)))

      :else
      (do (log/warn "Admin login failed — wrong password")
        (assoc (html-response (pages/login next-path :bad-password)) :status 401)))))

(defn- logout-response [request]
  (redirect "/login" (auth/logout-cookie request)))

;; --- The registry forms -----------------------------------------------------------------

(defn- same-origin?
  "Whether a state-changing POST came from this server's own pages.

   Browsers send `Origin` on every POST, cross-site ones included, so comparing it against
   the origin we were reached on is enough to stop a form on someone else's page from
   submitting here with the viewer's ambient credentials. That matters most in the
   $ADMIN_TRUST_LAN case, which is precisely where the session gate isn't standing in the
   way: a LAN request needs no cookie, so without this a page in a browser on the home
   network could register a display. A missing Origin is let through — that's a non-browser
   client (curl, a script), which has no ambient credentials to be borrowed in the first
   place, and which can present the password itself if the gate is on.

   Both sides of the comparison come from the same request's headers, so a forged Host
   forges them equally and gains nothing; the attack this stops is a real browser, which
   sets both honestly."
  [request]
  (let [origin (get-in request [:headers "origin"])]
    (or (nil? origin) (= origin (base-url request nil)))))

(defn- registry-post
  "Runs a registry-mutating POST behind both gates it needs: the admin session, and the
   same-origin check the session alone doesn't cover."
  [request f]
  (gated request #(if (same-origin? request)
                    (f)
                    (do (log/warn (str "Refused a cross-origin POST to " (:uri request)))
                      (text-response 403 "bad origin\n")))))

(defn- sync-aliases!
  "Refreshes the readable symlinks beside the id-named archive/ and logs/ directories (see
   server.aliases). Called here rather than from server.devices, because this is where the
   registry and the two storage roots are known to the same namespace — devices has no
   business knowing where a screen or a log file is kept. Run after every registry change
   and once at startup, which also picks up any rename made by hand in the file."
  []
  (let [entries (devices/all)]
    (aliases/sync! (archive/root) entries)
    (aliases/sync! (telemetry/root) entries)))

(defn- form-field
  "One submitted form value as a string, or nil. Repeated keys decode to a vector (see
   auth/form-params), so this takes the first — a hand-written POST with two lat fields
   reaches the parsers as one value or none, never as a collection."
  [params k]
  (let [v (get params k)]
    (cond
      (string? v) v
      (vector? v) (first (filter string? v))
      :else       nil)))

(defn- form-values
  "The whole submission as a map of plain strings, which is both what the registry fns are
   given (after parsing) and what the form is re-rendered with when it's refused — so what
   somebody typed survives the round trip rather than being blanked by their own typo."
  [body]
  (let [params (auth/form-params body)]
    (into {} (map (fn [k] [k (form-field params k)])) ["name" "lat" "lon" "hours"])))

(defn- form-number
  "A submitted number field: parsed, blank → nil, unparseable → the string itself. Handing
   the raw string on is deliberate — devices/entry-problems then rejects it as 'must be
   numbers', where turning it into nil would report a field somebody clearly filled in as
   missing, or worse, quietly ignore it."
  [s]
  (when-let [s (some-> s str/trim not-empty)]
    (or (parse-double s) s)))

(defn- form-integer
  "Same, for :hours — where nil genuinely means 'not set' and falls back to the
   server-wide default."
  [s]
  (when-let [s (some-> s str/trim not-empty)]
    (or (parse-long s) s)))

(defn- attempt
  "Runs a registry mutation, turning a failed *write* into the same {:errors …} shape the
   validators produce. devices.edn being unwritable is a real state — a stray $DEVICES_FILE,
   a directory the service user doesn't own — and the form saying so is more use than a
   500, since the person reading it is the one who can fix it."
  [f]
  (try
    (f)
    (catch Exception e
      (log/error e "Failed to write the device registry")
      {:errors [(str "Couldn't write the registry file: " (.getMessage e))]})))

(defn- configure-submit
  "POST /devices/<id>/configure. On success the display is serving forecasts within a few
   seconds — it's already polling every 5 seconds waiting for exactly this — so this lands
   on its own page, which is where you'd go next to watch it arrive.

   Neither an id nor a token is passed on: both already exist and belong to the display,
   and devices/configure! carries them over untouched. That is the whole point of the
   redesign, so there is deliberately no way to reach a token-issuing path from here."
  [waiting request]
  (let [values (form-values (some-> (:body request) slurp))
        result (attempt #(devices/configure! (:id waiting)
                           {:name  (get values "name")
                            :lat   (form-number (get values "lat"))
                            :lon   (form-number (get values "lon"))
                            :hours (form-integer (get values "hours"))}))]
    (if-let [errors (:errors result)]
      (assoc (html-response (pages/configure-device waiting values errors)) :status 400)
      (do
        (sync-aliases!)
        (redirect (str "/devices/" (:id (:device result))))))))

(defn- edit-submit
  "POST /devices/<id>/edit. Drops the display's cached screen on success: the cache is
   keyed by :id and knows nothing about the location that produced it, so an edited entry
   would otherwise keep serving — and archiving — the old town's forecast."
  [device request]
  (let [values (form-values (some-> (:body request) slurp))
        result (attempt #(devices/update! (:id device)
                           {:name  (get values "name")
                            :lat   (form-number (get values "lat"))
                            :lon   (form-number (get values "lon"))
                            :hours (form-integer (get values "hours"))}))]
    (if-let [errors (:errors result)]
      (assoc (html-response (pages/edit-device device values errors)) :status 400)
      (do
        (render/forget! (:id device))
        ;; A rename is the case this exists for: the old link has to go and a new one appear.
        (sync-aliases!)
        (redirect (str "/devices/" (:id device)))))))

;; --- Routing ----------------------------------------------------------------------------

(defn- device-page-response
  "Dispatches the per-display pages: /devices/<id> (the status dashboard, which is the
   display's own page rather than a /status leaf under it), /devices/<id>/archive, and
   /devices/<id>/archive/<file>.

   The id is resolved here, once, and the page fns are handed the entry itself — so an
   unknown id is a 404 at the door rather than something each page has to fall back from.
   Anything else under /devices/ is a 404 too, which is what keeps this from being a prefix
   that quietly accepts whatever it's given.

   An id resolves in one of two places, and which one decides what exists at that path: a
   *configured* display has a dashboard, an archive and an edit form, while one that is only
   provisional has exactly one page — the configure form. Neither set overlaps, so
   /devices/<id> is a 404 for a display that hasn't been configured yet, and
   /devices/<id>/configure is a 404 for one that has."
  [uri request]
  (let [[_ device-id sub file] (path-segments uri)]
    (if-let [device (and device-id (devices/by-id device-id))]
      (cond
        (nil? sub)
        (html-response (pages/status device (query-param request "day") (auth-state request)))

        (and (= sub "edit") (nil? file))
        (html-response (pages/edit-device device nil nil))

        (and (= sub "archive") (nil? file))
        (html-response (pages/gallery device))

        (and (= sub "archive") file)
        (archive-file-response device file)

        :else {:status 404})
      (if-let [waiting (and device-id (= sub "configure") (nil? file)
                         (devices/provisional-by-id device-id))]
        (html-response (pages/configure-device waiting nil nil))
        {:status 404}))))

(defn- device-post-response
  "The two POSTs under /devices/<id>: configuring a display that is waiting, and editing one
   that is already configured. Resolved the same way the GETs are, so an unknown id is the
   same 404 here as there."
  [uri request]
  (let [[_ device-id sub file] (path-segments uri)]
    (cond
      (nil? device-id) {:status 404}

      (and (= sub "edit") (nil? file))
      (if-let [device (devices/by-id device-id)]
        (edit-submit device request)
        {:status 404})

      (and (= sub "configure") (nil? file))
      (if-let [waiting (devices/provisional-by-id device-id)]
        (configure-submit waiting request)
        {:status 404})

      :else {:status 404})))

(defn- handler [fallback-base]
  (fn [{:keys [request-method uri] :as request}]
    (let [get?  (= :get request-method)
          post? (= :post request-method)]
      (cond
        ;; Device API and health check: never gated by the admin session — a display
        ;; can't log in, and authenticates by registered MAC + Access-Token instead.
        (and get? (= uri "/health"))                  (text-response 200 "ok\n")
        (and get? (= uri "/api/display"))             (display-response request fallback-base)
        (and get? (= uri "/api/setup"))               (setup-response request fallback-base)
        (and post? (= uri "/api/log"))                (log-response request)
        ;; The admin session: the form is the one human page that can't be gated.
        (and get? (= uri "/login"))                   (login-page request)
        (and post? (= uri "/login"))                  (login-submit request)
        (and post? (= uri "/logout"))                 (logout-response request)
        ;; Human pages, behind that session. Two of them: the index, and everything
        ;; per-display under /devices/<id>/….
        (and get? (= uri "/"))                        (gated request #(html-response (pages/home (auth-state request))))
        ;; The collection URL of that hierarchy: / already *is* the list of displays, so
        ;; truncating /devices/<id> back to its parent should land on it rather than
        ;; 404. A redirect rather than a second copy of the page, so the index keeps one
        ;; canonical URL and `crumbs` has one "/" to point at. Ungated on purpose — the
        ;; response is a constant, so it discloses nothing that a login would protect.
        (and get? (#{"/devices" "/devices/"} uri))    (redirect "/")
        (and get? (str/starts-with? uri "/devices/")) (gated request #(device-page-response uri request))
        (and post? (str/starts-with? uri "/devices/"))
        (registry-post request #(device-post-response uri request))
        ;; /images/* is the display's own fetch, so it stays outside the session gate —
        ;; and the gated pages embed the same URLs, which the browser then loads with no
        ;; cookie of interest. What's reachable there is one rendered screen per device,
        ;; named by a content hash the cache has to be holding right now, plus the one
        ;; static logo /api/setup hands out.
        (and get? (= uri "/images/setup-logo.png")) (setup-logo-response)
        (and get? (str/starts-with? uri "/images/")) (image-response uri)
        :else                                         {:status 404}))))

(defn- lan-ip
  "Best-effort first non-loopback IPv4 address, for the startup message and as the
   base-url fallback when a request arrives without a Host header. Falls back to
   \"localhost\" if none is found (e.g. no network connection)."
  []
  (or (some->> (NetworkInterface/getNetworkInterfaces)
        enumeration-seq
        (mapcat #(enumeration-seq (.getInetAddresses ^NetworkInterface %)))
        (some #(when (and (instance? Inet4Address %)
                       (not (Inet4Address/.isLoopbackAddress %))) %))
        Inet4Address/.getHostAddress)
    "localhost"))

(defn start!
  "Starts the HTTP server. http-kit's worker threads are non-daemon, so the JVM
   stays alive after this (and -main) returns."
  []
  (let [port     (or (some-> (System/getenv "PORT") Integer/parseInt) 8080)
        base-url (str "http://" (lan-ip) ":" port)]
    (devices/load!)
    (auth/load!)
    (telemetry/load-wake-history! (map :id (devices/all)))
    (sync-aliases!)
    (httpkit/run-server (handler base-url) {:port port})
    (log/info (str "TRMNL server listening on " base-url))
    (log/info "Point your TRMNL OG's custom server URL to the above.")))
