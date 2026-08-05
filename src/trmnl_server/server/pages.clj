(ns trmnl-server.server.pages
  "The three human-facing HTML pages — / (landing), /status (device health) and
   /archive (screen gallery) — built with hiccup2.core, which auto-escapes string
   content so there's no hand-rolled escaping here.

   /status and /archive are scoped to one registered device, chosen by ?device= and
   selected with a picker strip; / shows every device's latest screen at once. An
   unknown ?device= falls back to the first registered display, the same way an unknown
   ?day= falls back to today — the registry and the files on disk are the whitelists.

   Each page fn returns an HTML *string*, not a Ring response: HTTP is server's
   business, so these can be called straight from the REPL. Their CSS lives in
   resources/css/ rather than inline string blobs — base.css is the shared shell,
   with archive.css and home.css layered on top for those two pages."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [trmnl-server.server.archive :as archive]
            [trmnl-server.server.devices :as devices]
            [trmnl-server.server.render :as render]
            [trmnl-server.server.telemetry :as telemetry])
  (:import [java.io File]
           [java.time Instant LocalDateTime ZoneId]
           [java.time.format DateTimeFormatter]))

;; Deployed commit, baked into version.edn by build.clj's uber task and bundled into the
;; jar. Absent when running from source (clojure -M:serve), where there's no build step —
;; there's no commit to report there, so /status just shows "dev-local". Read once at load.
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
   for the /status 'Deployed' pill; pass the original through unchanged if it isn't
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
   still says something useful — the /status cards care about 'a while back', not
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

(defn- picker
  "A row of selectable pills. The device picker and the day picker are the same shape,
   so they're the same markup — `href-for` builds each item's link and the selected one
   renders as a plain span."
  [items sel href-for]
  [:div.days
   (for [item items]
     (if (= item sel)
       [:span.day.sel item]
       [:a.day {:href (href-for item)} item]))])

(defn- device-picker
  "The device strip, shown only when there's more than one display to choose between —
   with a single device it would be a row of one pill that's always selected, and the
   page heading already names it."
  [all-devices sel path]
  (when (> (count all-devices) 1)
    [:div.dev-strip (picker (map :name all-devices) sel #(str path "?device=" %))]))

(defn- select-device
  "The device a ?device= parameter names, falling back to the first registered one. The
   registry is the whitelist, so a bogus or hostile value can only ever land on a real
   device — the same guarantee /status's ?day= gets from matching against files on disk.
   nil when nothing is registered at all."
  [requested]
  (or (devices/by-name requested) (first (devices/all))))

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

(defn- unregistered-block
  "A copy-pasteable list of MACs that have polled without being in the registry. Adding a
   display otherwise means grepping the log for its MAC; this puts it where you're
   already looking."
  []
  (when-let [macs (seq (devices/unknown-macs))]
    (list
      [:div.h "Unregistered devices"]
      [:p.empty "Seen polling but not in devices.edn. Add an entry and restart:"]
      [:pre.reg
       (str/join "\n" (map (fn [{:keys [mac seen-at]}]
                             (str "\"" mac "\"  ; seen " (format-millis seen-at)))
                        macs))])))

;; --- /status ----------------------------------------------------------------------------

(def ^:private wake-windows
  "Trend windows shown on /status, label + span in ms — short, day, and week."
  [["1h" (* 60 60 1000)]
   ["6h" (* 6 60 60 1000)]
   ["24h" (* 24 60 60 1000)]
   ["7d" telemetry/wake-retention-ms]])

(defn- wake-sparkline
  "An inline SVG polyline of the wake-time series (ms over time) scaled to a small box,
   for the /status Awake card. nil when there are fewer than two samples to connect. Pure
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
  "The /status dashboard for one device and one day. `requested-device` is a ?device=
   value resolved against the registry (falling back to the first registered display);
   `requested-day` is a ?day= value resolved against that device's log files on disk
   (falling back to today). Anything not on either whitelist — a bogus or traversal
   value included — falls back, so neither can ever name something off-list.

   The device-log table just shows the contents of one day's file, re-read on each load:
   the files are the source of truth, there's no in-memory buffer to clear. The summary
   cards, though, always read *today's* newest row (falling back to the /api/display poll
   telemetry), so they reflect the current day even while you're viewing an older one."
  [requested-device requested-day]
  (if-let [device (select-device requested-device)]
    (let [name        (:name device)
          all-devices (devices/all)
          today       (telemetry/today-utc-date)
          on-disk     (telemetry/log-days name)
          sel         (if (some #{requested-day} on-disk) requested-day today)
          ;; Today is always offered as a tab, even before it has a file.
          days        (->> (cons today on-disk) distinct (sort #(compare %2 %1)) vec)
          rows        (reverse (telemetry/read-log name sel))
          latest      (if (= sel today) rows (reverse (telemetry/read-log name today)))
          dev         (telemetry/poll-status name)
          voltage     (or (:battery-voltage dev) (some :battery_voltage latest))
          pct         (battery-percent voltage)
          [batt-lbl
           batt-pill] (battery-quality pct)
          firmware    (or (:fw-version dev) (some :firmware_version latest))
          [wifi-lbl
           wifi-pill] (wifi-quality (:rssi dev))
          wakes       (telemetry/wake-samples name)
          now         (System/currentTimeMillis)
          latest-wake (:ms (last wakes))
          fcast       (render/cache-status device)
          [fc-lbl
           fc-pill]   (forecast-quality fcast now)]
      (page (str "trmnl-server status · " name) status-css
        (list
          [:div.top
           [:h1 "trmnl-server status · " name]
           [:a.top-link {:href (str "/archive?device=" name)} "Archived screens →"]]
          (device-picker all-devices name "/status")
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
           (picker days sel #(str "/status?device=" name "&day=" %))]
          (if (seq rows)
            (log-table rows)
            [:p.empty (str "No device logs for " name " on " sel ".")])
          (unregistered-block))))
    (no-devices "trmnl-server status" status-css)))

;; --- /archive ---------------------------------------------------------------------------

(defn- archive-card [device-name ^File f]
  (let [name (.getName f)
        edn  (str/replace name #"\.png\z" ".edn")
        href (str "/archive/" device-name "/")]
    [:div.shot
     [:a {:href (str href name) :title name}
      [:img {:loading "lazy" :src (str href name) :alt name}]]
     [:div.cap.mono
      (format-millis (.lastModified f))
      ;; The data link only appears when the sidecar forecast dump is still there —
      ;; it's pruned on the same 24h schedule as the PNG.
      (when (.isFile (io/file (archive/dir device-name) edn))
        (list " · " [:a {:href (str href edn)} "data"]))]]))

(defn gallery
  "The /archive gallery for one device: every screen of its still inside the rolling 24h
   window, newest first."
  [requested-device]
  (if-let [device (select-device requested-device)]
    (let [name        (:name device)
          all-devices (devices/all)
          entries     (archive/entries name)]
      (page (str "trmnl-server archive · " name) archive-css
        (list
          [:h1 "Archived screens · " name]
          [:p.nav
           [:a {:href (str "/status?device=" name)} "← status"]
           " · " (count entries) " screens · rolling 24h"]
          (device-picker all-devices name "/archive")
          (if (seq entries)
            [:div.grid (map #(archive-card name %) entries)]
            [:p.empty "No archived screens yet."]))))
    (no-devices "trmnl-server archive" archive-css)))

;; --- / ----------------------------------------------------------------------------------

(defn- screen-block
  "One device's latest screen on the landing page, from the cache only — never
   regenerating. / is public and a crawler shouldn't be able to turn a visit into a live
   SMHI fetch and a full render, once per registered device at that."
  [device]
  (let [name  (:name device)
        entry (render/cached-entry device)]
    [:div.screen
     [:div.screen-name name]
     (if entry
       (let [src (str "/images/" name "/" (render/serve-filename entry))]
         (list
           [:a.shot-link {:href src}
            [:img {:src src :alt (str "Latest rendered forecast screen for " name)}]]
           [:p.caption (str "Uppdaterad " (format-millis (:generated-at entry)))]))
       [:p.caption "Not rendered yet — waiting for this device's first poll."])]))

(defn home
  "The landing page: what this server is, the screen each registered device is being
   served right now, and a way into /status."
  []
  (let [all-devices (devices/all)]
    (page "trmnl-server" home-css
      [:div.hero
       [:h1 "trmnl-server"]
       [:p.tag "Weather forecast screens for TRMNL e-ink displays"]
       (if (seq all-devices)
         (map screen-block all-devices)
         [:p.caption "No devices registered — see devices.example.edn."])
       [:p.cta [:a {:href "/status"} "Status →"]]])))
