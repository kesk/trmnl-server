(ns trmnl-server.image
  (:require [clojure.string :as str])
  (:import [java.awt BasicStroke Color RenderingHints TexturePaint]
           [java.awt.geom Rectangle2D$Double]
           [java.awt.image BufferedImage]
           [java.io File]
           [javax.imageio ImageIO]))

;; TRMNL's original (OG) display is 800x480, 1-bit black/white e-ink.
(def og-width 800)
(def og-height 480)

(defn blank-canvas
  "Returns a white RGB canvas map: {:image BufferedImage, :graphics Graphics2D}.
   Draw on it with the other fns, then convert with ->1-bit or floyd-steinberg."
  ([] (blank-canvas og-width og-height))
  ([w h]
   (let [image (BufferedImage. w h BufferedImage/TYPE_INT_RGB)
         g (.createGraphics image)]
     (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_OFF)
     (.setRenderingHint g RenderingHints/KEY_TEXT_ANTIALIASING RenderingHints/VALUE_TEXT_ANTIALIAS_OFF)
     (.setColor g Color/WHITE)
     (.fillRect g 0 0 w h)
     (.setColor g Color/BLACK)
     {:image image :graphics g})))

(defn- fill-string
  "Fills text as glyph outlines rather than Graphics2D/drawString. Some JDK/platform
   font rasterizers antialias drawString regardless of KEY_TEXT_ANTIALIASING, so this
   routes text through the ordinary shape-fill path (governed by KEY_ANTIALIASING,
   which IS honored) to guarantee hard, un-antialiased glyph edges."
  [graphics ^String text x y]
  (let [frc (.getFontRenderContext graphics)
        gv (.createGlyphVector (.getFont graphics) frc text)
        outline (.getOutline gv (float x) (float y))]
    (.fill graphics outline)))

(defn draw-text [{:keys [graphics]} text x y & {:keys [font color] :or {color Color/BLACK}}]
  (when font (.setFont graphics font))
  (.setColor graphics color)
  (fill-string graphics text (int x) (int y)))

(defn draw-wrapped-text
  "Draws text wrapped to fit within max-width, one line per line-height."
  [{:keys [graphics]} text x y max-width line-height & {:keys [font color] :or {color Color/BLACK}}]
  (when font (.setFont graphics font))
  (.setColor graphics color)
  (let [fm (.getFontMetrics graphics)
        lines (loop [words (str/split text #"\s+") line "" lines []]
                (if (empty? words)
                  (cond-> lines (seq line) (conj line))
                  (let [word (first words)
                        candidate (if (seq line) (str line " " word) word)]
                    (if (and (seq line) (> (.stringWidth fm candidate) max-width))
                      (recur words "" (conj lines line))
                      (recur (rest words) candidate lines)))))]
    (doseq [[i line] (map-indexed vector lines)]
      (fill-string graphics line (int x) (int (+ y (* i line-height)))))))

(defn draw-rect [{:keys [graphics]} x y w h & {:keys [fill? color] :or {color Color/BLACK}}]
  (.setColor graphics color)
  (if fill?
    (.fillRect graphics x y w h)
    (.drawRect graphics x y w h)))

(defn draw-line [{:keys [graphics]} x1 y1 x2 y2 & {:keys [color] :or {color Color/BLACK}}]
  (.setColor graphics color)
  (.setStroke graphics (BasicStroke. 1.0))
  (.drawLine graphics (int x1) (int y1) (int x2) (int y2)))

(defn draw-dashed-line
  "A recessive hairline for gridlines/axes — dashed since a 1-bit surface can't
   fall back to a lighter gray the way a color surface would."
  [{:keys [graphics]} x1 y1 x2 y2 & {:keys [color] :or {color Color/BLACK}}]
  (.setColor graphics color)
  (.setStroke graphics (BasicStroke. 1.0 BasicStroke/CAP_BUTT BasicStroke/JOIN_MITER
                                     1.0 (float-array [2.0 3.0]) 0.0))
  (.drawLine graphics (int x1) (int y1) (int x2) (int y2))
  (.setStroke graphics (BasicStroke. 1.0)))

(defn draw-polyline
  "Connects a sequence of [x y] points with a 2px round-joined line.
   Pass :dash [on-length off-length] to draw it dashed instead of solid —
   useful for telling two series apart on a 1-bit surface without color."
  [{:keys [graphics]} points & {:keys [width color dash] :or {width 2.0 color Color/BLACK}}]
  (.setColor graphics color)
  (.setStroke graphics (if dash
                          (BasicStroke. width BasicStroke/CAP_BUTT BasicStroke/JOIN_ROUND
                                        1.0 (float-array dash) 0.0)
                          (BasicStroke. width BasicStroke/CAP_ROUND BasicStroke/JOIN_ROUND)))
  (doseq [[[x1 y1] [x2 y2]] (partition 2 1 points)]
    (.drawLine graphics (int x1) (int y1) (int x2) (int y2)))
  (.setStroke graphics (BasicStroke. 1.0)))

(defn checkerboard-paint
  "A 2x2 black/white checkerboard tile, anchored at the device origin — used
   to fill a shape with a regular dither pattern that reads as flat 50% gray.
   A 1-bit surface can't hold an actual gray value (->1-bit/floyd-steinberg
   would just threshold it away), but this survives untouched since every
   pixel is already pure black or white. Anchoring at (0,0) keeps the tile
   grid-aligned across separately drawn shapes so segments meet seamlessly."
  []
  (let [tile (BufferedImage. 2 2 BufferedImage/TYPE_INT_RGB)]
    (doto tile
      (.setRGB 0 0 -1)
      (.setRGB 1 0 -16777216)
      (.setRGB 0 1 -16777216)
      (.setRGB 1 1 -1))
    (TexturePaint. tile (Rectangle2D$Double. 0.0 0.0 2.0 2.0))))

(defn draw-variable-line
  "Connects a sequence of [x y] points where each point has its own stroke
   width (interpolated across each segment) — useful for encoding a magnitude
   in line thickness on a 1-bit surface where color/gray isn't available.
   :paint accepts any java.awt.Paint (a Color for solid fill, or e.g.
   checkerboard-paint for a dithered fill)."
  [{:keys [graphics]} points widths & {:keys [paint] :or {paint Color/BLACK}}]
  (.setPaint graphics paint)
  (doseq [[[x1 y1 w1] [x2 y2 w2]] (partition 2 1 (map (fn [[x y] w] [x y w]) points widths))]
    (.setStroke graphics (BasicStroke. (float (/ (+ w1 w2) 2.0)) BasicStroke/CAP_ROUND BasicStroke/JOIN_ROUND))
    (.drawLine graphics (int x1) (int y1) (int x2) (int y2)))
  (.setStroke graphics (BasicStroke. 1.0)))

(defn draw-dot [{:keys [graphics]} x y & {:keys [radius color] :or {radius 4 color Color/BLACK}}]
  (.setColor graphics color)
  (.fillOval graphics (int (- x radius)) (int (- y radius)) (int (* 2 radius)) (int (* 2 radius))))

(defn ->1-bit
  "Hard-threshold an RGB canvas down to 1-bit black/white. Good for text/UI screens."
  [{:keys [image]} & {:keys [threshold] :or {threshold 128}}]
  (let [w (.getWidth image) h (.getHeight image)
        bw (BufferedImage. w h BufferedImage/TYPE_BYTE_BINARY)]
    (dotimes [y h]
      (dotimes [x w]
        (let [c (Color. (.getRGB image x y))
              gray (/ (+ (.getRed c) (.getGreen c) (.getBlue c)) 3)]
          (.setRGB bw x y (if (< gray threshold) -16777216 -1)))))
    bw))

(defn floyd-steinberg
  "Dither an RGB canvas down to 1-bit using Floyd-Steinberg error diffusion.
   Better than ->1-bit for photos/gradients since it preserves perceived shading."
  [{:keys [image]}]
  (let [w (.getWidth image) h (.getHeight image)
        gray (double-array (* w h))]
    (dotimes [y h]
      (dotimes [x w]
        (let [c (Color. (.getRGB image x y))]
          (aset gray (+ x (* y w)) (double (/ (+ (.getRed c) (.getGreen c) (.getBlue c)) 3))))))
    (let [bw (BufferedImage. w h BufferedImage/TYPE_BYTE_BINARY)]
      (dotimes [y h]
        (dotimes [x w]
          (let [i (+ x (* y w))
                old (aget gray i)
                new (if (< old 128.0) 0.0 255.0)
                err (- old new)]
            (.setRGB bw x y (if (zero? new) -16777216 -1))
            (when (< (inc x) w)
              (aset gray (+ i 1) (+ (aget gray (+ i 1)) (* err (/ 7.0 16)))))
            (when (and (pos? x) (< (inc y) h))
              (aset gray (+ i w -1) (+ (aget gray (+ i w -1)) (* err (/ 3.0 16)))))
            (when (< (inc y) h)
              (aset gray (+ i w) (+ (aget gray (+ i w)) (* err (/ 5.0 16)))))
            (when (and (< (inc x) w) (< (inc y) h))
              (aset gray (+ i w 1) (+ (aget gray (+ i w 1)) (* err (/ 1.0 16))))))))
      bw)))

(defn save-image [image path]
  (let [file (File. ^String path)
        fmt (-> path (subs (inc (.lastIndexOf ^String path "."))) )]
    (.mkdirs (.getParentFile file))
    (ImageIO/write image fmt file)))
