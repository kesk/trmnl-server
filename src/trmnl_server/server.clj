(ns trmnl-server.server
  "Serves the forecast screen to a real TRMNL OG device over HTTP, implementing the
   three endpoints the device's firmware polls when pointed at a custom server:
   GET /api/display, GET /api/setup, POST /api/log. Uses http-kit as both the Ring
   handler convention and the embedded server, rather than a heavier Ring+Jetty stack."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [org.httpkit.server :as httpkit]
            [trmnl-server.core :as core]
            [trmnl-server.image :as img])
  (:import [java.io ByteArrayOutputStream]
           [java.net NetworkInterface]
           [java.security MessageDigest]
           [javax.imageio ImageIO]))

(def ^:private refresh-rate-seconds 900)
(def ^:private cache-ttl-ms (* 10 60 1000))
(def ^:private max-stored-logs 200)

(defonce ^:private cache (atom nil))
(defonce ^:private device-logs (atom []))

(defn- png-bytes [image]
  (let [out (ByteArrayOutputStream.)]
    (ImageIO/write image "png" out)
    (.toByteArray out)))

(defn- md5-hex [bytes]
  (let [digest (.digest (MessageDigest/getInstance "MD5") bytes)]
    (apply str (map #(format "%02x" %) digest))))

(defn- forecast-hours []
  (or (some-> (System/getenv "FORECAST_HOURS") Integer/parseInt) core/default-forecast-hours))

(defn- forecast-location []
  (let [lat (System/getenv "FORECAST_LAT")
        lon (System/getenv "FORECAST_LON")]
    (if (and lat lon)
      {:lat (Double/parseDouble lat) :lon (Double/parseDouble lon)}
      core/default-forecast-location)))

(defn- battery-percent
  "Rough charge estimate from a raw battery_voltage reading (LiPo, ~3V empty to
   ~4.2V full). Not the device's exact curve — just enough to flag a low battery."
  [voltage]
  (when voltage
    (-> (/ (- voltage 3) 0.012) (max 1.0) (min 100.0) Math/round)))

(defn- battery-label [pct]
  (cond
    (nil? pct) "unknown"
    (< pct 15) "LOW"
    (< pct 30) "watch"
    :else "ok"))

(defn- escape-html [s]
  (-> (str s)
    (str/replace "&" "&amp;")
    (str/replace "<" "&lt;")
    (str/replace ">" "&gt;")))

(defn current-image
  "Returns the cached {:bytes :filename :generated-at}, regenerating from a fresh
   forecast when the cache is empty or older than cache-ttl-ms. If regeneration
   throws (e.g. SMHI returns something other than JSON), falls back to serving
   the last successfully rendered image — with a warning badge stamped on a
   copy of it — rather than a bare 500. A stale forecast is more useful to the
   device than none; the badge is what lets you notice at a glance that it's
   stale and go check the logs. Only propagates the exception when there's no
   prior image to fall back on."
  []
  (let [entry @cache]
    (if (and entry (< (- (System/currentTimeMillis) (:generated-at entry)) cache-ttl-ms))
      entry
      (try
        (let [image     (img/->1-bit (core/forecast-screen (core/live-points (forecast-hours) (forecast-location))))
              bytes     (png-bytes image)
              new-entry {:image        image
                         :bytes        bytes
                         :filename     (str "forecast-" (md5-hex bytes) ".png")
                         :generated-at (System/currentTimeMillis)}]
          (reset! cache new-entry)
          new-entry)
        (catch Exception e
          (if entry
            (let [stale-bytes (or (:stale-bytes entry) (png-bytes (core/stamp-stale-badge (:image entry))))
                  stale-entry (assoc entry
                                :stale-bytes stale-bytes
                                :stale-filename (str "forecast-" (md5-hex stale-bytes) "-stale.png"))]
              (println "Forecast regeneration failed, serving stale cache:" (.getMessage e))
              (reset! cache stale-entry)
              stale-entry)
            (throw e)))))))

(defn- serve-bytes [entry] (or (:stale-bytes entry) (:bytes entry)))
(defn- serve-filename [entry] (or (:stale-filename entry) (:filename entry)))

(defn- image-url [base-url filename]
  (str base-url "/images/" filename))

(defn- json-response [body]
  {:status  200
   :headers {"Content-Type" "application/json"}
   :body    (json/write-str body)})

(defn- display-response [base-url]
  (let [entry    (current-image)
        filename (serve-filename entry)]
    (json-response {:filename          filename
                    :image_url         (image-url base-url filename)
                    :image_url_timeout 0
                    :refresh_rate      refresh-rate-seconds
                    :reset_firmware    false
                    :update_firmware   false
                    :firmware_url      nil})))

(defn- setup-response [base-url]
  (let [entry (current-image)]
    (json-response {:image_url (image-url base-url (serve-filename entry))
                    :message   "Welcome to trmnl-server"})))

(defn- log-response [request]
  (let [body    (slurp (:body request))
        entries (:logs (try (json/read-str body :key-fn keyword) (catch Exception _ nil)))]
    (println "Device log:" body)
    (when (seq entries)
      (swap! device-logs #(->> (concat % entries) (take-last max-stored-logs) vec))))
  {:status 204})

(defn- image-response []
  {:status  200
   :headers {"Content-Type" "image/png"}
   :body    (serve-bytes (current-image))})

(defn- html-response [body]
  {:status  200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    body})

(defn- status-response []
  (let [logs            (reverse @device-logs)
        latest-voltage  (some :battery_voltage logs)
        pct             (battery-percent latest-voltage)
        latest-firmware (some :firmware_version logs)]
    (html-response
      (str "<html><head><title>trmnl-server status</title></head><body>"
        "<h1>Battery</h1>"
        "<p>" (if latest-voltage
                (String/format java.util.Locale/US "%.3fV (~%d%%, %s)"
                  (to-array [latest-voltage pct (battery-label pct)]))
                "no data yet")
        "</p>"
        "<h1>Firmware</h1>"
        "<p>" (if latest-firmware (escape-html latest-firmware) "no data yet") "</p>"
        "<h1>Recent device log rows (" (count logs) ")</h1>"
        "<table border=\"1\" cellpadding=\"4\">"
        "<tr><th>time</th><th>message</th><th>source</th><th>battery</th><th>wifi</th><th>retry</th><th>firmware</th></tr>"
        (apply str
          (for [{:keys [created_at message source_path source_line
                        battery_voltage wifi_signal wifi_status retry firmware_version]} logs]
            (str "<tr><td>" (some-> created_at (java.time.Instant/ofEpochSecond)) "</td>"
              "<td>" (escape-html message) "</td>"
              "<td>" (escape-html source_path) ":" source_line "</td>"
              "<td>" battery_voltage "</td>"
              "<td>" (escape-html wifi_status) " (" wifi_signal ")</td>"
              "<td>" retry "</td>"
              "<td>" (escape-html firmware_version) "</td></tr>")))
        "</table></body></html>"))))

(defn- handler [base-url]
  (fn [{:keys [request-method uri] :as request}]
    (cond
      (and (= request-method :get) (= uri "/api/display")) (display-response base-url)
      (and (= request-method :get) (= uri "/api/setup")) (setup-response base-url)
      (and (= request-method :post) (= uri "/api/log")) (log-response request)
      (and (= request-method :get) (= uri "/status")) (status-response)
      (and (= request-method :get) (str/starts-with? uri "/images/")) (image-response)
      :else {:status 404})))

(defn- lan-ip
  "Best-effort first non-loopback IPv4 address, for the startup message. Falls back
   to \"localhost\" if none is found (e.g. no network connection)."
  []
  (or (some->> (NetworkInterface/getNetworkInterfaces)
        enumeration-seq
        (mapcat #(enumeration-seq (.getInetAddresses %)))
        (some #(when (and (instance? java.net.Inet4Address %) (not (.isLoopbackAddress %))) %))
        .getHostAddress)
    "localhost"))

(defn start!
  "Starts the HTTP server. http-kit's worker threads are non-daemon, so the JVM
   stays alive after this (and -main) returns."
  []
  (let [port     (or (some-> (System/getenv "PORT") Integer/parseInt) 8080)
        base-url (str "http://" (lan-ip) ":" port)]
    (httpkit/run-server (handler base-url) {:port port})
    (println (str "TRMNL server listening on " base-url))
    (println "Point your TRMNL OG's custom server URL to the above.")))
