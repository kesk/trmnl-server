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

(defonce ^:private cache (atom nil))

(defn- png-bytes [image]
  (let [out (ByteArrayOutputStream.)]
    (ImageIO/write image "png" out)
    (.toByteArray out)))

(defn- md5-hex [bytes]
  (let [digest (.digest (MessageDigest/getInstance "MD5") bytes)]
    (apply str (map #(format "%02x" %) digest))))

(defn current-image
  "Returns the cached {:bytes :filename :generated-at}, regenerating from a fresh
   forecast when the cache is empty or older than cache-ttl-ms."
  []
  (let [entry @cache]
    (if (and entry (< (- (System/currentTimeMillis) (:generated-at entry)) cache-ttl-ms))
      entry
      (let [bytes (png-bytes (img/->1-bit (core/forecast-screen)))
            entry {:bytes bytes
                   :filename (str "forecast-" (md5-hex bytes) ".png")
                   :generated-at (System/currentTimeMillis)}]
        (reset! cache entry)
        entry))))

(defn- image-url [base-url filename]
  (str base-url "/images/" filename))

(defn- json-response [body]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/write-str body)})

(defn- display-response [base-url]
  (let [entry (current-image)]
    (json-response {:filename (:filename entry)
                    :image_url (image-url base-url (:filename entry))
                    :image_url_timeout 0
                    :refresh_rate refresh-rate-seconds
                    :reset_firmware false
                    :update_firmware false
                    :firmware_url nil})))

(defn- setup-response [base-url]
  (let [entry (current-image)]
    (json-response {:image_url (image-url base-url (:filename entry))
                    :message "Welcome to trmnl-server"})))

(defn- log-response [request]
  (println "Device log:" (slurp (:body request)))
  {:status 204})

(defn- image-response []
  {:status 200
   :headers {"Content-Type" "image/png"}
   :body (:bytes (current-image))})

(defn- handler [base-url]
  (fn [{:keys [request-method uri] :as request}]
    (cond
      (and (= request-method :get) (= uri "/api/display")) (display-response base-url)
      (and (= request-method :get) (= uri "/api/setup")) (setup-response base-url)
      (and (= request-method :post) (= uri "/api/log")) (log-response request)
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
  (let [port (or (some-> (System/getenv "PORT") Integer/parseInt) 8080)
        base-url (str "http://" (lan-ip) ":" port)]
    (httpkit/run-server (handler base-url) {:port port})
    (println (str "TRMNL server listening on " base-url))
    (println "Point your TRMNL OG's custom server URL to the above.")))
