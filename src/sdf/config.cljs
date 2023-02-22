(ns sdf.config
  (:require [sprog.util :as u]
            [fxrng.rng :refer [fxrand fxrand-nth]]))

(def particle-amount 400)
(def radius 0.0002)
(def paretto? false)
(def paretto-scale 1)
(def paretto-shape 0.1)
(def speed 0.00002)
(def randomization-chance 1)
(def sphere-octaves 5)
(def plane-iters (str 8))
(def plane-size (fxrand 0.03 0.05))

(def ao-dist 0.05)
(def ao-samples (str 16))
(def ao-power 4)

(def light-pos '(vec3 1 -0.5 -0.5))
(def light-scale 1)
(def diffusion-power (cons '* (repeat 8 'diff)))

(def fade 0.999)
(def frame-limit 600)
(def unlimit? false)
(def antique-white (cons 'vec3 (map #(/ % 255) (list 250 235 215))))
(def light-brown (cons 'vec3 (map #(/ % 255) (list 204 192 175))))

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