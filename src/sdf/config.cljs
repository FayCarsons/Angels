(ns sdf.config
  (:require [sprog.util :as u]
            [fxrng.rng :refer [fxrand
                               fxrand-nth
                               fxchance
                               fxrand-int
                               fxshuffle]]
            [sprog.tools.math :refer [rand-n-sphere-point]]
            [clojure.math.combinatorics :refer [cartesian-product]]))

;global control
(def frame-limit 600)
(def special? (fxchance (/ 30)))
(def u32-max (dec (Math/pow 2 32)))

;utilities
(def circle-count (inc (fxrand-int 4 8)))
(def max-circle-radius 0.05)

(defn check-overlap [[circle-one circle-two]]
  (let [[pos-one radius-one] circle-one
        [pos-two radius-two] circle-two]
    (empty? (filter #(or (< radius-one %)
                         (< radius-two %))
                    (map (comp abs -) pos-one pos-two)))))
(defn get-circle []
  [[(fxrand 0.1 0.9) (fxrand 0.1 0.9)] (fxrand max-circle-radius)])

(defn check-circles [circle-vec]
  (remove check-overlap 
          (cartesian-product circle-vec 
                             circle-vec)))

(defn pack-circles [circle-vec]
  (let [circles (u/genv circle-count (get-circle))]
    (if (< (count (check-circles (cons circle-vec circles)))
           circle-count)
      (pack-circles (cons circle-vec circles))
      (cons circle-vec circles))))

(def circle-expr
  (let [cljs-circles (pack-circles (get-circle))
        circles (map (fn [circle]
                       (u/unquotable
                        '(- (length (- pos
                                       ~(cons 'vec2 (first circle))))
                            ~(last circle))))
                     cljs-circles)]
    (reduce (fn [expr term]
              (list 'min expr term))
            circles)))

;sketch
(def sqrt-sketch-particle-amount 512)
(def sketch-particle-amount [sqrt-sketch-particle-amount 
                             sqrt-sketch-particle-amount])
(def sketch-speed 0.00002)
(def sketch-fade 0.999)
(def sketch-randomization-chance 0.998)

;background
(def sqrt-background-particle-amount 128)
(def background-particle-amount [sqrt-background-particle-amount
                                 sqrt-background-particle-amount])
(def background-field-resolution (u/genv 2 512))
(def background-fade 0.995)
(def background-reset-interval 5)
(def background-particle-speed 0.0002)
(def background-randomization-chance 0.99)
(def frame-width 0.01)

;colors
(def antique-white (cons 'vec3 (map #(/ % 255) (list 250 235 215))))
(def yellow-beige (cons 'vec3 (map #(/ % 255) [244 212 170])))
(def light-brown (cons 'vec3 (map #(/ % 255) [208 192 175])))
(def olive (cons 'vec3 (map #(/ % 255) [64 64 0])))
(def earth-brown (cons 'vec3 (map #(/ % 255) [188 129 95])))
(def red-brown (cons 'vec3 (map #(/ % 255) [191 107 89])))
(def red (cons 'vec3 (map #(/ % 230) [255 1 1])))
(def yellow (cons 'vec3 (map #(/ % 255) [255 240 1])))
(def special-mode (u/unquotable ['(mixOklab ~red
                                            ~yellow
                                            (sigmoid (+ ~circle-expr
                                                        (-> pos
                                                            (* ~(fxrand 5))
                                                            snoise2D))))
                                 0.9]))

(def background-highlight  (if special?
                             special-mode 
                             (fxrand-nth [[yellow-beige (+ 0.75 (fxrand -0.05 0.05))]
                                          [light-brown (+ 0.5 (fxrand -0.05 0.05))]
                                          [olive (+ 0.15 (fxrand -0.05 0.05))]
                                          [earth-brown (+ 0.3 (fxrand -0.05 0.05))]
                                          [red-brown (+ 0.35 (fxrand -0.05 0.05))]])))

;raymarching
(def seed-tex-dimensions [64 64])
(def light-pos '(vec3 1 -0.5 -0.5))
(def light-scale 0.3)
(def light-max 0.4)
(def diffusion-power (cons '* (repeat 2 'diff)))

(def plane-count (inc (fxrand-int 2 6)))
(def plane-size (fxrand 0.03 0.05))
(def axes [[plane-size 1 1]
           [1 plane-size 1]
           [1 1 plane-size]])
(def planes (u/unquotable
             (u/gen plane-count
                    '(sdBox (* pos
                               (axisRoationMatrix (normalize ~(cons 'vec3
                                                                    (rand-n-sphere-point 3 fxrand)))
                                                  ~(fxrand u/TAU)))
                            (vec3 0)
                            ~(cons 'vec3
                                   (first (fxshuffle axes)))))))

(def plane-expr (reduce (fn [expr term]
                          (list 'min expr term))
                        planes))

(def slab (u/unquotable
           '(max (sdBox pos
                        (vec3 0 -0.7 0.2)
                        (vec3 0.75 0.1 0.75))
                 (- 0 (min (sdBox pos (vec3 0 -0.7 0.375) (vec3 0.375 0.375 0.05))
                           (min (sdBox pos (vec3 0 -0.7 -0.375) (vec3 0.375 0.375 0.05))
                                (min (sdBox pos (vec3 -0.375 -0.7 0) (vec3 0.05 0.375 0.375))
                                     (sdBox pos (vec3 0.375 -0.7 0) (vec3 0.05 0.375 0.375)))))))))
(def round-slab '(max (max (sdSphere pos
                                     (vec3 0 -0.7 0.2)
                                     0.9)
                           (sdBox pos
                                  (vec3 0 -0.7 0.2)
                                  (vec3 0.9 0.1 0.9)))
                      (- 0 (sdTorus pos
                                    (vec3 0 -0.7 0.1)
                                    (vec2 0.55 0.11)))))

