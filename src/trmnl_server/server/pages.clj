(ns trmnl-server.server.pages
  "The human-facing HTML pages — / (the index of displays), /devices/<id> (one display's
   health dashboard), /devices/<id>/archive (its screen gallery) and /login (the admin
   password form that gates the other three) — built with hiccup2.core, which
   auto-escapes string content so there's no hand-rolled escaping here.

   **Links are built from a device's :id, text from its :name.** The id is the stable
   identity that survives a rename (see server.devices); the name is the label, and these
   pages are the only place it is ever read. So every href here goes through device-path,
   and every visible string is the :name — which hiccup escapes, since a label is
   free-form and may hold anything a human types.

   / is the index and the only switcher: one card per registered display, the whole card
   a link into that display's page. The per-display pages carry the id in the path (see
   device-path) and take the resolved registry entry as an argument, so an unknown id is
   the router's 404 rather than a fallback that would render one display's data at
   another's URL. What stays a query parameter is the dashboard's ?day=, which selects
   between days of the same page and is matched against the files actually on disk.

   Those paths nest — / → display → archive → one screen — and `crumbs` is what walks
   them back up. It doubles as the page heading, so each page has one line naming where
   it is and how to leave, rather than a heading plus a hand-rolled set of back-links.

   Each page fn returns an HTML *string*, not a Ring response: HTTP is server's
   business, so these can be called straight from the REPL. Their CSS lives in
   resources/css/ rather than inline string blobs — base.css is the shared shell,
   with archive.css and home.css layered on top for those two pages.

   These pages are in **English**, unlike the screen they show: the e-ink render is
   Swedish throughout (Temp/Vind/Moln/Regnrisk, weekday abbreviations, \"Uppdaterad\"),
   because it hangs on a wall in Sweden. Don't propagate one language into the other."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [trmnl-server.server.archive :as archive]
            [trmnl-server.server.auth :as auth]
            [trmnl-server.server.devices :as devices]
            [trmnl-server.server.render :as render]
            [trmnl-server.server.telemetry :as telemetry])
  (:import [java.io File]
           [java.time Instant LocalDateTime ZoneId]
           [java.time.format DateTimeFormatter]))

;; Deployed commit, baked into version.edn by build.clj's uber task and bundled into the
;; jar. Absent when running from source (clojure -M:serve), where there's no build step —
;; there's no commit to report there, so the device page just shows "dev-local". Read once at load.
(def ^:private deployed-version
  (or (when-let [r (io/resource "version.edn")]
        (try (read-string (slurp r)) (catch Exception _ nil)))
    {:commit "dev-local"}))

;; Self-contained styling, slurped from resources/css/ at load (bundled into the uberjar
;; via :paths, so io/resource resolves in prod too). base.css is the shared page shell —
;; system fonts, no webfont/CDN, and a prefers-color-scheme dark variant so it adapts to
;; the viewer; /archive and / layer their own rules on top of it.
(defn- css [& names]
  (str/join "\n" (map #(slurp (io/resource (str "css/" %))) names)))

(def ^:private status-css (css "base.css"))
(def ^:private archive-css (css "base.css" "archive.css"))
(def ^:private home-css (css "base.css" "home.css"))
(def ^:private login-css (css "base.css" "login.css"))

(defn- page
  "Wraps page-specific body hiccup in the shared HTML shell (doctype, head, the given
   inline CSS, and the centering .wrap div), returning the rendered HTML string."
  [title css-text body]
  (str "<!doctype html>"
    (h/html {:mode :html}
      [:html {:lang "en"}
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:name "viewport" :content "width=device-width,initial-scale=1"}]
        [:title title]
        [:style (h/raw css-text)]]
       [:body [:div.wrap body]]])))

;; --- Formatting helpers -----------------------------------------------------------------

(def ^:private display-format
  (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))

(def ^:private built-format
  (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm"))

(defn- format-instant
  "Renders an Instant as local wall-clock time in the host's zone (the Pi's
   Europe/Stockholm in prod, matching journald and the device screen)."
  [^Instant instant formatter]
  (.format (LocalDateTime/ofInstant instant (ZoneId/systemDefault)) formatter))

(defn- format-millis [millis]
  (format-instant (Instant/ofEpochMilli millis) display-format))

(defn- format-built-at
  "build.clj bakes :built-at into version.edn as a raw ISO-8601 instant string
   (e.g. 2026-07-16T10:41:12.123456Z). Render it as a compact local wall-clock time
   for the device page's 'Deployed' pill; pass the original through unchanged if it isn't
   parseable as an instant, so a hand-edited version.edn can't blow up the page."
  [built]
  (try
    (format-instant (Instant/parse built) built-format)
    (catch Exception _ built)))

(defn- ms->secs
  "Milliseconds to seconds, rounded to one decimal."
  [ms]
  (/ (Math/round (/ (double ms) 100.0)) 10.0))

(defn- ago-str
  "A rough 'how long ago' for a past epoch-millis timestamp, at the coarsest unit that
   still says something useful — the device page's cards care about 'a while back', not
   seconds."
  [millis now]
  (let [mins (quot (- now millis) 60000)]
    (cond
      (< mins 1)    "just now"
      (< mins 60)   (str mins " min ago")
      (< mins 1440) (str (quot mins 60) " h ago")
      :else         (str (quot mins 1440) " d ago"))))

(defn- forecast-quality
  "Maps render/cache-status to a human label and pill class for the Forecast card.
   A live :failed-at means SMHI is currently failing us and the device is being served
   the stale-badged image (see ISSUES.md); the failure count is what distinguishes a
   single blip from an outage worth looking into. How long it's been going on is left
   to the card's value — the timestamp of the last render that did succeed."
  [status now]
  (let [{:keys [generated-at failed-at failures]} status]
    (cond
      (nil? status)    ["not rendered yet" "pill-unknown"]
      (nil? failed-at) [(str "rendered " (ago-str generated-at now)) "pill-ok"]
      :else            [(str "stale · " failures " failed attempt" (when (> failures 1) "s"))
                        (if (> failures 1) "pill-low" "pill-watch")])))

(defn- battery-percent
  "Rough charge estimate from a raw battery_voltage reading (LiPo, ~3V empty to
   ~4.2V full). Not the device's exact curve — just enough to flag a low battery."
  [voltage]
  (when voltage
    (-> (/ (- voltage 3) 0.012) (max 1.0) (min 100.0) Math/round)))

(defn- battery-quality
  "Maps a rough charge percentage to a human label and the pill class to tint it
   with. nil → unknown."
  [pct]
  (cond
    (nil? pct)  ["unknown" "pill-unknown"]
    (< pct 15)  ["LOW" "pill-low"]
    (< pct 30)  ["watch" "pill-watch"]
    :else       ["ok" "pill-ok"]))

(defn- wifi-quality
  "Maps a raw RSSI (dBm — negative, closer to zero is stronger) to a human label and
   the pill class to tint it with. nil → unknown."
  [rssi]
  (cond
    (nil? rssi)   ["unknown" "pill-unknown"]
    (>= rssi -67) ["good" "pill-ok"]
    (>= rssi -78) ["fair" "pill-watch"]
    :else         ["weak" "pill-low"]))

;; --- Shared chrome ----------------------------------------------------------------------

(defn- device-path
  "The URL of a display's own page (/devices/<id>) or one of its sub-pages
   (/devices/<id>/archive).

   The segment is the display's :id, not its :name — that's what lets a display be
   renamed without breaking every link and bookmark that points at it. It's a path
   segment rather than a query filter because it identifies which page this *is*, not
   which subset of it to show — so an unknown id is a 404 from the router, and no page
   has to carry a fallback that would quietly render one display's data at a URL that
   asked for another's. The status dashboard is the display's root rather than a /status
   leaf under it: it's the canonical view of that device, and making it the root is what
   turns the URLs into a hierarchy a breadcrumb can walk back up (/ → display → archive →
   one screen). The id is safe to interpolate for the same reason it's safe as a directory
   name: devices validates it to [a-z0-9-]+ at load."
  ([device-id] (str "/devices/" device-id))
  ([device-id sub] (str "/devices/" device-id "/" sub)))

(defn- crumbs
  "The breadcrumb, which is also the page heading: ancestors as muted links, the current
   page as the h1, at one size so the whole thing reads as a path. `trail` is
   [[label href] …] for the ancestors and `current` is this page's own label.

   One line rather than a trail above a heading, because the alternative names the display
   twice on every page — and it replaces what used to be an ad-hoc set of back-links
   (\"← All displays\" on the device page, \"← status · all displays\" on the gallery) that
   each page had to get right on its own."
  [trail current]
  [:nav.crumbs {:aria-label "Breadcrumb"}
   (for [[label href] trail]
     (list [:a {:href href} label]
       [:span.sep {:aria-hidden "true"} "/"]))
   [:h1 current]])

(defn- picker
  "A row of selectable pills — the day strip on a display's page. `href-for` builds each
   item's link and the selected one renders as a plain span."
  [items sel href-for]
  [:div.days
   (for [item items]
     (if (= item sel)
       [:span.day.sel item]
       [:a.day {:href (href-for item)} item]))])

(defn- no-devices
  "What every page shows when devices.edn is missing or empty. Lists any MACs that have
   polled, since that's exactly what you need to write the file — a device you've just
   plugged in announces itself here rather than only in the log."
  [title css-text]
  (page title css-text
    (list
      [:h1 "No devices registered"]
      [:p.empty "Create a devices.edn (see devices.example.edn) and restart the server."]
      (when-let [macs (seq (devices/unknown-macs))]
        (list
          [:div.h "Seen polling"]
          [:pre.reg (str/join "\n" (map :mac macs))])))))

(defn- logout-form
  "The way out of a session. A POST, not a link: a GET /logout would let a link
   prefetch sign you out."
  []
  [:form.linkform {:method "post" :action "/logout"}
   [:button.linkbtn {:type "submit"} "Log out"]])

(defn- session-chrome
  "The auth corner of a page, given how the viewer got in (see server's auth-state).

   :session gets a way out. :open gets a standing warning, because no password is also the
   state a production deploy lands in if its admin.env goes missing and the pages would
   otherwise look exactly as they did before. :lan says so rather than offering a logout
   button that would do nothing — there is no session to end, and clicking it would just
   bounce you back to the page you were already allowed to see."
  [auth-state]
  (case auth-state
    :open [:span.pill.pill-watch
           {:title "No $ADMIN_PASSWORD_HASH is set, so these pages are open to anyone who can reach this server."}
           "auth disabled"]
    :lan  [:span.pill.pill-unknown
           {:title "$ADMIN_TRUST_LAN exempts the home network from the login. Reaching this page from the internet requires a password."}
           "LAN · no login required"]
    (logout-form)))

;; A registered display's page used to end with a list of *un*registered MACs seen
;; polling. It's gone: core/unregistered-screen puts a device's own MAC on the device, in
;; 48px type, which is both a better answer to "what do I paste into devices.edn" and one
;; you get by looking at the display you just plugged in. The list also had nothing to do
;; with the display whose page it was on — it was server-wide, so it appeared identically
;; on every one. devices/unknown-macs itself stays: no-devices still lists them for the
;; first-run case (no registry at all, so no device page to visit), seen-unknown? gates
;; the unregistered-screen render, and the first sighting of a MAC is still logged.

;; --- The device page --------------------------------------------------------------------

(def ^:private wake-windows
  "Trend windows shown on the device page, label + span in ms — short, day, and week."
  [["1h" (* 60 60 1000)]
   ["6h" (* 6 60 60 1000)]
   ["24h" (* 24 60 60 1000)]
   ["7d" telemetry/wake-retention-ms]])

(defn- wake-sparkline
  "An inline SVG polyline of the wake-time series (ms over time) scaled to a small box,
   for the device page's Awake card. nil when there are fewer than two samples to connect. Pure
   server-rendered hiccup — no JS, no axis, no dependency; a glanceable trend read next to
   the numeric averages, not a precise chart. x maps each sample's :t across the width so
   irregular poll spacing shows; y maps :ms so taller = longer awake (inverted). Colour is
   left to CSS (currentColor → --muted) to stay legible in light and dark."
  [samples]
  (when (> (count samples) 1)
    (let [w    240
          h    34
          pad  3
          ts   (map :t samples)
          vs   (map :ms samples)
          tmin (apply min ts)
          tmax (apply max ts)
          vmin (apply min vs)
          vmax (apply max vs)
          trng (double (max 1 (- tmax tmin)))
          vrng (double (max 1 (- vmax vmin)))
          pts  (->> samples
                 (map (fn [{:keys [t ms]}]
                        (let [x (+ pad (* (- w (* 2 pad)) (/ (- t tmin) trng)))
                              y (+ pad (* (- h (* 2 pad)) (- 1 (/ (- ms vmin) vrng))))]
                          (str (Math/round (double x)) "," (Math/round (double y))))))
                 (str/join " "))]
      [:svg {:class               "spark" :viewBox     (str "0 0 " w " " h) :width "100%" :height h
             :preserveAspectRatio "none"  :aria-hidden "true"}
       [:polyline {:points pts}]])))

(defn- row-class
  "Tints a log row by severity so the noisy device errors are glanceable: red for any
   ERROR message, amber for a WARN, nil (no class) otherwise."
  [message]
  (let [m (str/lower-case (str message))]
    (cond
      (str/includes? m "error") "err"
      (str/includes? m "warn")  "warn"
      :else nil)))

(defn- log-row [{:keys [created_at message source_path source_line
                        battery_voltage wifi_signal wifi_status retry firmware_version]}]
  [:tr {:class (row-class message)}
   [:td.mono (some-> created_at (Instant/ofEpochSecond))]
   [:td message]
   [:td.mono source_path ":" source_line]
   [:td.num.mono battery_voltage]
   [:td wifi_status " (" wifi_signal ")"]
   [:td.num.mono retry]
   [:td.mono firmware_version]])

(defn- log-table [logs]
  [:div.tw
   [:table
    [:thead
     [:tr [:th "time (UTC)"] [:th "message"] [:th "source"]
      [:th.num "battery"] [:th "wifi"] [:th.num "retry"] [:th "firmware"]]]
    [:tbody (map log-row logs)]]])

(defn status
  "The /devices/<id> dashboard for one device and one day. `device` is the
   registry entry the router already resolved the path segment to, so there's no unknown
   id to handle here. `requested-day` is a ?day= value resolved against that device's
   log files on disk, falling back to today — a day is a filter over this page rather than
   a different page, which is why it stays a query parameter, and matching it against the
   files means a bogus or traversal value can never name something off-list.

   There's no device picker: / is the index that switches between displays, and a second
   switcher here would just be a different way to do the same thing.

   The device-log table just shows the contents of one day's file, re-read on each load:
   the files are the source of truth, there's no in-memory buffer to clear. The summary
   cards, though, always read *today's* newest row (falling back to the /api/display poll
   telemetry), so they reflect the current day even while you're viewing an older one."
  [device requested-day auth-state]
  (let [id          (:id device)
        label       (:name device)
        today       (telemetry/today-utc-date)
        on-disk     (telemetry/log-days id)
        sel         (if (some #{requested-day} on-disk) requested-day today)
        ;; Today is always offered as a tab, even before it has a file.
        days        (->> (cons today on-disk) distinct (sort #(compare %2 %1)) vec)
        rows        (reverse (telemetry/read-log id sel))
        latest      (if (= sel today) rows (reverse (telemetry/read-log id today)))
        dev         (telemetry/poll-status id)
        voltage     (or (:battery-voltage dev) (some :battery_voltage latest))
        pct         (battery-percent voltage)
        [batt-lbl
         batt-pill] (battery-quality pct)
        firmware    (or (:fw-version dev) (some :firmware_version latest))
        [wifi-lbl
         wifi-pill] (wifi-quality (:rssi dev))
        wakes       (telemetry/wake-samples id)
        now         (System/currentTimeMillis)
        latest-wake (:ms (last wakes))
        fcast       (render/cache-status device)
        [fc-lbl
         fc-pill]   (forecast-quality fcast now)]
    (page (str "trmnl-server · " label) status-css
      (list
        [:div.top
         (crumbs [["trmnl-server" "/"]] label)
         [:div.top-nav
          [:a.top-link {:href (device-path id "archive")} "Archived screens →"]
          (session-chrome auth-state)]]
        [:section.group
         [:div.sec "Device health"]
         [:div.cards.cards-health
          [:div.card
           [:div.k "Battery"]
           [:div.v (if voltage
                     (String/format java.util.Locale/US "%.3f V" (to-array [voltage]))
                     "—")]
           [:span {:class (str "pill " batt-pill)}
            (if voltage (str "~" pct "% · " batt-lbl) "no data yet")]]
          [:div.card
           [:div.k "WiFi"]
           [:div.v.mono (if (:rssi dev) (str (:rssi dev) " dBm") "—")]
           [:span {:class (str "pill " wifi-pill)} wifi-lbl]]
          [:div.card.awake
           [:div.k "Awake · last cycle"]
           [:div.v (if latest-wake (str (ms->secs latest-wake) " s") "—")]
           (if (seq wakes)
             (list
               (wake-sparkline wakes)
               [:div.avgs
                (for [[lbl window] wake-windows
                      :let         [avg (telemetry/wake-average wakes now window)]]
                  [:div.avg
                   [:div.al lbl]
                   [:div.av (if avg (str (ms->secs avg) "s") "—")]])])
             [:span.pill.pill-unknown "no samples yet"])]]]
        [:section.group
         [:div.sec "Server · build"]
         [:div.cards.cards-build
          [:div.card
           [:div.k "Firmware"]
           [:div.v.mono (or firmware "—")]
           (when-let [model (:model dev)]
             [:span.pill.pill-unknown model])]
          [:div.card
           [:div.k "Deployed"]
           [:div.v.mono (or (:commit deployed-version) "unknown")]
           (when-let [built (:built-at deployed-version)]
             [:span.pill.pill-unknown "built " (format-built-at built)])]
          [:div.card
           [:div.k "Forecast"]
           [:div.v.mono (if (:generated-at fcast) (format-millis (:generated-at fcast)) "—")]
           [:span {:class (str "pill " fc-pill)} fc-lbl]]
          [:div.card
           [:div.k "Last poll"]
           [:div.v.mono (if dev (format-millis (:received-at dev)) "—")]
           [:span {:class (str "pill " (if dev "pill-ok" "pill-unknown"))}
            (if dev
              (str (or (:update-source dev) "unknown source")
                " · " (case (:image-cached dev)
                        true  "image cached"
                        false "image refreshed"
                        "—"))
              "no poll yet")]]]]
        [:div.h-row
         [:div.h "Device log"
          (when (seq rows)
            [:span {:style "color:var(--muted);font-weight:400"}
             (str "  ·  " (count rows) " rows")])]
         (picker days sel #(str (device-path id) "?day=" %))]
        (if (seq rows)
          (log-table rows)
          [:p.empty (str "No device logs for " label " on " sel ".")])))))

;; --- /archive ---------------------------------------------------------------------------

(defn- archive-card [device-id ^File f]
  (let [name (.getName f)
        edn  (str/replace name #"\.png\z" ".edn")
        href (str (device-path device-id "archive") "/")]
    [:div.shot
     [:a {:href (str href name) :title name}
      [:img {:loading "lazy" :src (str href name) :alt name}]]
     [:div.cap.mono
      (format-millis (.lastModified f))
      ;; The data link only appears when the sidecar forecast dump is still there —
      ;; it's pruned on the same 24h schedule as the PNG.
      (when (.isFile (io/file (archive/dir device-id) edn))
        (list " · " [:a {:href (str href edn)} "data"]))]]))

(defn gallery
  "The /devices/<id>/archive gallery: every screen of that display's still inside the
   rolling 24h window, newest first. `device` is the registry entry the router resolved
   the path segment to, and each card's files sit under this same path — see
   archive-card. Like the device page it has no device picker; / is the switcher, and the
   breadcrumb is the way back up."
  [device]
  (let [id      (:id device)
        label   (:name device)
        entries (archive/entries id)]
    (page (str "trmnl-server · " label " · archive") archive-css
      (list
        (crumbs [["trmnl-server" "/"] [label (device-path id)]] "archive")
        [:p.nav (count entries) (if (= 1 (count entries)) " screen" " screens") " · rolling 24h"]
        (if (seq entries)
          [:div.grid (map #(archive-card id %) entries)]
          [:p.empty "No archived screens yet."])))))

;; --- / ----------------------------------------------------------------------------------

(defn- screen-card
  "One device's latest screen on the landing page — the whole card is the link into that
   display's own page, so the index doubles as the device picker.

   Never regenerates: / is the page a crawler or a passer-by lands on, and a visit
   shouldn't turn into a live SMHI fetch and a full render per registered device. So it
   takes the last screen from wherever one already exists, in order:

     1. the render cache, which is what the device is being served *right now*;
     2. failing that, the newest file in the device's archive.

   The fallback exists because the cache is in memory and dies with the process, so
   without it every deploy left this page showing placeholders until each display's next
   poll — up to 15 minutes of looking like nothing works. The archived copy is a real
   render (only successful ones are written) and, right after a restart, is also what the
   display itself is still showing, since the device holds the last image it downloaded.
   Its mtime is when that render happened, so the caption stays true either way.

   Only when both are empty has this display genuinely never rendered — which is a
   question for its own page, hence the card is still a link."
  [device]
  (let [id       (:id device)
        label    (:name device)
        entry    (render/cached-entry device)
        archived (when-not entry (first (archive/entries id)))
        [src at] (cond
                   entry    [(str "/images/" id "/" (render/serve-filename entry))
                             (:generated-at entry)]
                   archived [(str (device-path id "archive") "/" (.getName ^File archived))
                             (.lastModified ^File archived)])]
    [:a.screen {:href (device-path id)}
     [:div.screen-name label]
     (if src
       (list
         [:img {:loading "lazy"
                :src     src
                :alt     (str "Latest rendered forecast screen for " label)}]
         ;; "Updated", not the screen's own Swedish "Uppdaterad": these pages are English
         ;; throughout, and the two timestamps mean different things anyway — the one
         ;; painted into the image is when the forecast was rendered for the *device*.
         [:p.caption (str "Updated " (format-millis at))])
       (list
         [:div.noshot "No screen yet"]
         ;; Not "waiting for its first poll": with the archive fallback above, reaching
         ;; here means nothing has ever rendered for this display — which a poll alone
         ;; doesn't fix if the renders are failing.
         [:p.caption "Nothing rendered for this display yet."]))]))

(defn home
  "The landing page: an index of every registered display, each showing the screen it's
   being served right now and linking into its own page. `auth-state` is how the viewer
   got past the gate — see session-chrome."
  [auth-state]
  (let [all-devices (devices/all)]
    (if (seq all-devices)
      (page "trmnl-server" home-css
        (list
          [:div.top
           [:h1 "trmnl-server"]
           [:div.top-nav (session-chrome auth-state)]]
          [:p.tag "Weather forecast screens for TRMNL e-ink displays"]
          [:div.screens (map screen-card all-devices)]))
      (no-devices "trmnl-server" home-css))))

;; --- /login -----------------------------------------------------------------------------

(defn login
  "The password form standing in front of the other pages. `next-path` is where a
   successful login lands — already constrained to a local path by auth/safe-next, since
   it arrives as a query parameter. `state` is nil on a first visit, :bad-password after
   a wrong one, :locked while the failed-login cooldown is running, or :misconfigured when
   admin.env holds something that isn't a usable hash — that last one is worth saying out
   loud rather than reporting as a wrong password, because no password can work until it's
   fixed and the device page (where you'd normally look) is behind this very form."
  [next-path state]
  (if (= state :insecure)
    ;; No form at all: there is nothing safe to type here, and offering the field anyway
    ;; would just invite somebody to send the password before reading the warning.
    (page "trmnl-server · log in" login-css
      [:div.login
       [:h1 "trmnl-server"]
       [:p.tag "This connection isn't encrypted."]
       [:p.msg.pill.pill-low
        "The password would cross the network in the clear, so this server won't accept "
        "one here. Open the same page over https and log in there."]])
    (page "trmnl-server · log in" login-css
      [:div.login
       [:h1 "trmnl-server"]
       [:p.tag "Admin password required."]
       [:form {:method "post" :action "/login"}
        [:input {:type "hidden" :name "next" :value next-path}]
        [:input {:type         "password"         :name       "password"       :placeholder "Password"
                 :autocomplete "current-password" :aria-label "Admin password" :autofocus   true}]
        [:button {:type "submit"} "Log in"]
        (case state
          :bad-password   [:p.msg.pill.pill-low "Wrong password."]
          :locked         [:p.msg.pill.pill-low "Too many attempts. Wait a minute, then try again."]
          :misconfigured  [:p.msg.pill.pill-low
                           "$ADMIN_PASSWORD_HASH is unreadable — no login can succeed until "
                           "admin.env is fixed. Run bb set-password.clj."]
          nil)]])))
