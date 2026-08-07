(ns trmnl-server.server.auth
  "The admin password gate in front of the human-facing pages (/ and /device/<name>/…).

   One password, no users: the server is a household dashboard that happens to be
   reachable from the internet, and the thing worth gating is read-only telemetry.

   It is stored **salted and hashed**, never in the clear: $ADMIN_PASSWORD_HASH holds a
   self-describing `pbkdf2-sha256$<iterations>$<salt>$<hash>` string, read once at startup.
   An env var rather than a file because the systemd unit is checked into this repo and
   overwritten on every deploy, so the secret has to live somewhere deploy.clj never
   touches — `admin.env`, pulled in by the unit's optional EnvironmentFile (see
   deploy/admin.env.example), and written by `bb set-password.clj`. Nothing configured
   means no gate, with a warning at startup and an 'auth disabled' pill on the device page:
   running from source is a legitimate state, the same way a missing devices.edn is, but a
   production deploy that lost its env file shouldn't be able to quietly stop asking.

   A session is a signed cookie, not a server-side session table: `<expiry>.<hmac>`,
   HMAC-SHA256 over the expiry with a key derived from that stored hash string. Two
   consequences, both wanted — sessions survive a restart (this service is redeployed
   often, and being logged out by every `bb deploy.clj` would train you to pick a
   short password), and re-setting the password draws a fresh salt and so invalidates
   every outstanding session without anything having to be revoked.

   The device API is untouched by all of this: /api/*, /images/* and /health stay
   open, since a display cannot do an interactive login. They have their own
   authentication — the registered MAC plus the Access-Token issued by /api/setup."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [ring.util.codec :as codec])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest SecureRandom]
           [java.util Base64]
           [javax.crypto Mac SecretKeyFactory]
           [javax.crypto.spec PBEKeySpec SecretKeySpec]))

(def ^:private cookie-name "trmnl_session")

(def ^:private session-ttl-ms
  "How long a login lasts. Long, on purpose: this gates a wall-dashboard's status page
   from a phone, and re-typing a 24-character random password weekly is how you end up
   with a four-character one."
  (* 30 24 60 60 1000))

;; {:encoded "<the raw $ADMIN_PASSWORD_HASH string>", :parsed {:iterations :salt :hash}}.
;; The encoded string is kept because it doubles as the session signing key (see hmac-hex);
;; :parsed is nil when the variable is set but unreadable, which enabled? and
;; misconfigured? then tell apart.
(defonce ^:private credential (atom nil))

;; $ADMIN_TRUST_LAN — exempt requests coming from the home network from the login. Off by
;; default; see trusted-peer?.
(defonce ^:private trust-lan? (atom false))

;; $ADMIN_REQUIRE_TLS_LOGIN — refuse to accept a password over an unencrypted connection.
;; On by default; see cleartext-login?.
(defonce ^:private require-tls-login? (atom true))

(defn- utf8 ^bytes [^String s]
  (String/.getBytes s StandardCharsets/UTF_8))

;; --- Password hashing -------------------------------------------------------------------

(def ^:private pbkdf2-iterations
  "Deliberately below OWASP's 600k floor for PBKDF2-HMAC-SHA256, because the machine that
   has to verify it is a **Raspberry Pi 3 Model B** from 2016. Measured on that Pi with
   jshell: 50k → 575ms, 100k → 1.1s, 600k → **5.4s**. 600k would mean a five-second login
   and, worse, five free CPU-seconds per unauthenticated request for the handful the
   throttle lets through each minute.

   100k (~1.1s there, ~10ms on a dev Mac) is the compromise. What makes that acceptable
   isn't the iteration count: it's that set-password.clj enforces a 12-character floor, so
   the password has enough entropy that offline cracking is hopeless at any iteration
   count, while a *weak* password wouldn't be saved by 600k either. The count is stored
   *in* each hash, so a future Pi can raise it without invalidating what's already set.

   Cost is paid only on a real login attempt, and only on the ones the throttle lets
   through — locked-out? is checked before any hashing happens."
  100000)

(def ^:private pbkdf2-key-bits 256)
(def ^:private salt-bytes 16)

(def ^:private hash-pattern
  #"pbkdf2-sha256\$(\d{1,9})\$([A-Za-z0-9+/=]{1,128})\$([A-Za-z0-9+/=]{1,128})")

;; Plain interop rather than the qualified-method form used elsewhere: every receiver here
;; comes straight from a constructor or a static method, so the compiler already knows its
;; type and none of these calls reflect.
(defn- pbkdf2 ^bytes [^String password ^bytes salt iterations]
  (-> (SecretKeyFactory/getInstance "PBKDF2WithHmacSHA256")
    (.generateSecret
      (PBEKeySpec. (.toCharArray password) salt (int iterations) (int pbkdf2-key-bits)))
    (.getEncoded)))

(defn- b64 [^bytes raw] (.encodeToString (Base64/getEncoder) raw))
(defn- unb64 ^bytes [^String s] (.decode (Base64/getDecoder) s))

(defn hash-password
  "Salts and hashes a password into the single self-describing string that goes in
   admin.env: `pbkdf2-sha256$<iterations>$<salt>$<hash>`, base64 for the two byte fields.
   The salt is 16 fresh bytes from SecureRandom, so setting the same password twice gives
   two different strings — and re-setting it invalidates every session, since the encoded
   string is also the session signing key.

   Called by `main --hash-password` (and so by set-password.clj), never by the server
   itself: it is the only writer of this format, which is what keeps the algorithm from
   drifting between the thing that writes hashes and the thing that checks them."
  [password]
  (let [salt (byte-array salt-bytes)]
    (.nextBytes (SecureRandom.) salt)
    (str "pbkdf2-sha256$" pbkdf2-iterations "$" (b64 salt) "$"
      (b64 (pbkdf2 password salt pbkdf2-iterations)))))

(defn- parse-hash
  "Splits an encoded hash back into its parts, or nil if it isn't one. nil is a
   configuration error, not a wrong password — see load!."
  [encoded]
  (when-let [[_ iterations salt hash] (re-matches hash-pattern (str encoded))]
    (try
      {:iterations (parse-long iterations) :salt (unb64 salt) :hash (unb64 hash)}
      (catch Exception _ nil))))

;; --- Configuration ----------------------------------------------------------------------

(defn load!
  "Reads $ADMIN_PASSWORD_HASH into the gate. Called from server/start! alongside the other
   startup loads. Unset leaves the pages open — see the namespace docstring.

   Set but unparseable is treated as *enabled and broken* rather than either extreme: the
   pages stay shut (failing open would silently unprotect them, and the whole point of the
   variable is that somebody meant to protect them), the service stays up (failing hard
   would take the displays down over a typo in an admin password), and the login page says
   so. The ERROR here is the diagnosis, since the device page is behind the very gate that's
   broken."
  []
  (let [flag    (fn [name default]
                  (if-let [v (some-> (System/getenv name) str/trim str/lower-case not-empty)]
                    (contains? #{"true" "1" "yes"} v)
                    default))
        encoded (some-> (System/getenv "ADMIN_PASSWORD_HASH") str/trim not-empty)]
    (reset! credential (when encoded {:encoded encoded :parsed (parse-hash encoded)}))
    (reset! trust-lan? (flag "ADMIN_TRUST_LAN" false))
    (reset! require-tls-login? (flag "ADMIN_REQUIRE_TLS_LOGIN" true))
    ;; Logged unconditionally, at INFO, because both change who can see the pages and the
    ;; only other place that's visible is a pill on a page you may not be able to reach.
    (log/info (str "Admin gate: trust-LAN=" @trust-lan?
                " require-TLS-login=" @require-tls-login?))
    (cond
      (:parsed @credential)
      (log/info "Admin password hash loaded — the human pages require a login.")

      encoded
      (log/error (str "$ADMIN_PASSWORD_HASH is set but is not a valid "
                   "pbkdf2-sha256$<iterations>$<salt>$<hash> string. No login can succeed "
                   "until it is fixed — regenerate it with `bb set-password.clj`."))

      :else
      (do
        (when (System/getenv "ADMIN_PASSWORD")
          (log/warn (str "$ADMIN_PASSWORD is set but is no longer used — the password is "
                      "now stored salted and hashed in $ADMIN_PASSWORD_HASH. Run "
                      "`bb set-password.clj` to convert it.")))
        (log/warn (str "No $ADMIN_PASSWORD_HASH — / and every /device/ page are open to anyone "
                    "who can reach this server. Run `bb set-password.clj` to require a login."))))))

(defn enabled?
  "Whether a password is configured at all — including one that's configured wrongly, which
   still means somebody meant these pages to be shut. Also what the device page reads to show its
   'auth disabled' pill."
  []
  (some? @credential))

(defn misconfigured?
  "Whether a password is configured but unreadable, so no login can possibly succeed. Only
   the login page cares: it's the difference between 'you typed it wrong' and 'nobody can
   get in until admin.env is fixed'."
  []
  (and (some? @credential) (nil? (:parsed @credential))))

;; --- Session tokens ---------------------------------------------------------------------

(defn- hmac-hex
  "HMAC-SHA256 of `payload`, hex, keyed on the stored credential — the whole encoded hash
   string, salt and all. Which is the good half of hashing at rest arriving for free: the
   signing key is now derived from a value that isn't the password, so the cookie can't be
   a password oracle even in principle, and re-setting the password draws a fresh salt and
   invalidates every outstanding session."
  [^String payload]
  (let [key (SecretKeySpec. (utf8 (str "trmnl-server/session/v1\0" (:encoded @credential)))
              "HmacSHA256")
        mac (doto (Mac/getInstance "HmacSHA256") (.init key))]
    (apply str (map #(format "%02x" %) (.doFinal mac (utf8 payload))))))

(defn- issue-token []
  (let [expiry (str (+ (System/currentTimeMillis) session-ttl-ms))]
    (str expiry "." (hmac-hex expiry))))

(defn- valid-token?
  "Whether a cookie value is one we signed and hasn't expired. The expiry is carried in
   the clear and covered by the signature, so it can't be edited forward."
  [token]
  (boolean
    (when-let [[expiry signature] (some-> token not-empty (str/split #"\." 2))]
      (when-let [expires-at (parse-long expiry)]
        (and (> expires-at (System/currentTimeMillis))
          (MessageDigest/isEqual (utf8 (str signature)) (utf8 (hmac-hex expiry))))))))

;; --- Failed-login throttle --------------------------------------------------------------

(def ^:private max-failures 5)
(def ^:private lockout-ms 60000)

;; Consecutive failed logins, and when the last one was. Deliberately global rather than
;; per-IP: the login form is internet-reachable and behind a tunnel that rewrites the
;; source address anyway, so per-IP counting would be both spoofable and wrong. The
;; trade-off is that someone hammering the form can keep *you* out too — of a read-only
;; status page, for a minute at a time, while the displays keep updating regardless.
(defonce ^:private failures (atom {:count 0 :last 0}))

(defn locked-out?
  "Whether the login form is in its cooldown. Once max-failures is reached, each further
   wrong password re-arms it, so the sustained rate settles at one attempt per minute."
  []
  (let [{:keys [count last]} @failures]
    (and (>= count max-failures)
      (< (- (System/currentTimeMillis) last) lockout-ms))))

(defn- note-failure! []
  (swap! failures #(-> % (update :count inc) (assoc :last (System/currentTimeMillis)))))

(defn- note-success! []
  (reset! failures {:count 0 :last 0}))

(defn check-password!
  "Checks a submitted password against the stored hash and records the attempt for the
   throttle. Re-derives PBKDF2 with the stored salt and iteration count, then compares in
   constant time.

   The caller checks locked-out? first, which is what keeps this — the one deliberately
   expensive thing the server does — from being a way to burn the Pi's CPU from the
   internet: at most a handful of derivations get run before the cooldown starts refusing
   them outright."
  [candidate]
  (let [{:keys [salt hash iterations]} (:parsed @credential)
        ok                             (and (some? hash)
                                         (string? candidate)
                                         (MessageDigest/isEqual hash (pbkdf2 candidate salt iterations)))]
    (if ok (note-success!) (note-failure!))
    ok))

;; --- Cookies and requests ---------------------------------------------------------------

(defn- cookie-value
  "The value of one cookie from the request's Cookie header, or nil."
  [request name]
  (some->> (get-in request [:headers "cookie"])
    (#(str/split % #";"))
    (map str/trim)
    (some #(when (str/starts-with? % (str name "="))
             (not-empty (subs % (inc (count name))))))))

;; --- Where the request came from --------------------------------------------------------

(def ^:private lan-v4-pattern
  "RFC1918 space: 10/8, 172.16/12, 192.168/16. Note what is *absent* — loopback. See
   lan-peer? for why that omission is the whole point."
  #"(?:10|192\.168)\..*|172\.(?:1[6-9]|2\d|3[01])\..*")

(def ^:private lan-v6-pattern
  "IPv6 unique-local (fc00::/7) and link-local (fe80::/10). Again, not ::1."
  #"f[cd][0-9a-f]{2}:.*|fe[89ab][0-9a-f]:.*")

(defn- lan-peer?
  "Whether a request's TCP peer address is on the home network.

   The peer address is the one signal here that cannot be forged: a spoofed source address
   never completes a TCP handshake, so anything that talks HTTP to us is telling the truth
   about where it is. Headers are not — X-Forwarded-Proto and Host are set by Cloudflare,
   and hanging an auth bypass on a third party's header hygiene is not a thing to do.

   **Loopback is deliberately NOT trusted, and that inversion is the entire subtlety.**
   cloudflared runs on the Pi and proxies to localhost, so every request arriving from the
   public internet has a peer address of ::1 — measured, not assumed. The intuitive
   'trust localhost' rule would therefore trust the internet and challenge the laptop in
   the next room, which is exactly backwards. Anything unrecognised falls through to
   'not LAN', so the failure direction is to ask for a password."
  [addr]
  (boolean
    (when-let [addr (some-> addr str/trim str/lower-case not-empty)]
      (let [addr (str/replace addr #"[\[\]]|%.*$" "")            ; brackets, zone id
            v4   (second (re-matches #"(?:::ffff:)?(\d{1,3}(?:\.\d{1,3}){3})" addr))]
        (if v4
          (re-matches lan-v4-pattern v4)
          (re-matches lan-v6-pattern addr))))))

(defn trusted-peer?
  "Whether this request is exempt from the login because it came from the home network and
   $ADMIN_TRUST_LAN says that's good enough. Off unless explicitly turned on: the failure
   mode of getting this wrong is a silent, total bypass from the internet, so it doesn't
   get to happen by default."
  [request]
  (and @trust-lan? (lan-peer? (:remote-addr request))))

(defn authenticated?
  "Whether this request may see the human pages: no password configured, a trusted LAN
   peer, or a valid session cookie.

   Note the LAN exemption sits *ahead* of the credential, so it still applies when the
   stored hash is unreadable — from home you can still reach a device page to find out why
   nobody can log in."
  [request]
  (or (not (enabled?))
    (trusted-peer? request)
    (valid-token? (cookie-value request cookie-name))))

(defn- secure-request?
  "Whether the client reached us over TLS. Read from X-Forwarded-Proto, which cloudflared
   sets on the tunnel; the other display's owner reaches the same server over plain http
   on the LAN, so `Secure` can't be set unconditionally — it would make the cookie
   unusable there."
  [request]
  (some-> (get-in request [:headers "x-forwarded-proto"])
    (str/split #",") first str/trim str/lower-case
    (= "https")))

(defn- loopback-peer? [addr]
  (contains? #{"127.0.0.1" "::1" "0:0:0:0:0:0:0:1" "::ffff:127.0.0.1"}
    (some-> addr str/trim str/lower-case)))

(defn cleartext-login?
  "Whether accepting a password on this request would mean accepting it in the clear.

   True for a plain-http request from anywhere but this machine — notably a browser on the
   LAN, where the POST crosses the WiFi unencrypted. False over TLS, and false for
   loopback, which keeps `clojure -M:serve` usable in development without punching a hole:
   a request through the tunnel carries X-Forwarded-Proto: https and is already secure by
   the first test, so it never reaches the loopback exemption.

   Trusting that header here is safe in a way it wouldn't be for authorisation: it decides
   only whether we'll accept a password the caller is already sending, so forging it buys
   an attacker nothing they don't already have."
  [request]
  (and @require-tls-login?
    (not (secure-request? request))
    (not (loopback-peer? (:remote-addr request)))))

(defn- cookie-header [request value max-age-seconds]
  (str cookie-name "=" value
    "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" max-age-seconds
    (when (secure-request? request) "; Secure")))

(defn login-cookie
  "Set-Cookie value that starts a session for this request."
  [request]
  (cookie-header request (issue-token) (quot session-ttl-ms 1000)))

(defn logout-cookie
  "Set-Cookie value that ends it."
  [request]
  (cookie-header request "" 0))

;; --- Form and redirect helpers ----------------------------------------------------------

(defn form-params
  "Parses an application/x-www-form-urlencoded body into a map of decoded params. A body
   with no `=` in it decodes to a string rather than a map (an empty one to \"\"), so
   anything that isn't a map becomes {} and every caller simply misses its key.

   Repeated keys decode to a vector of values, deliberately left that way: `check-password!`
   and `safe-next` both require a string and fall back when handed one, which is the right
   answer for a form with a single field."
  [body]
  (let [params (codec/form-decode (or body ""))]
    (if (map? params) params {})))

(defn safe-next
  "Constrains a ?next= destination to a path on this server. An absolute URL, a
   protocol-relative //evil.example one, or anything with whitespace or a backslash in it
   falls back to / — a login form is exactly the place an open redirect gets used.

   The whitespace half of that does double duty now that both sources of this value arrive
   already decoded (form-params for the field, query-param for the URL): a CR or LF can
   reach here as a real control character, and this value goes out in a Location header."
  [path]
  (if (and (string? path)
        (str/starts-with? path "/")
        (not (str/starts-with? path "//"))
        (not (re-find #"[\s\\]" path)))
    path
    "/"))

(defn login-url
  "Where to send an unauthenticated request, remembering where it was headed. The
   destination is percent-encoded as a single query value; server's query-param decodes it
   symmetrically on the way back in."
  [request]
  (let [{:keys [uri query-string]} request
        target                     (str uri (when (not-empty query-string) (str "?" query-string)))]
    (str "/login?next=" (codec/url-encode target))))
