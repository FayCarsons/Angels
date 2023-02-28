(ns sdf.chunks
  (:require clojure.walk
            [sprog.util :as u]
            [sdf.config :as c]
            [sprog.iglu.core :refer [combine-chunks]]))

(def smooth-union-chunk
  '{:functions
    {smoothUnion
     {([float float float] float)
      ([d1 d2 k]
       (=float h (clamp (+ (/ (* 0.5 (- d2 d1)) k) 0.5) 0 1))
       (- (mix d2 d1 h) (* k h (- 1 h))))}}})

(def smooth-intersection-chunk
 '{:functions
   {smoothIntersection
    {([float float float] float)
     ([d1 d2 k]
      (=float h (clamp (- (/ (* 0.5 (- d2 d1)) k) 0.5) 0 1))
      (+ (mix d2 d1 h) (* k h (- 1 h))))}}})

(def smooth-subtraction-chunk
  '{:functions
    {smoothSubtraction
     {([float float float] float)
      ([d1 d2 k]
       (=float h (clamp (- 0.5 (/ (* 0.5 (+ d2 d1)) k)) 0 1))
       (+ (mix d2 (- 0 d1) h) (* k h (- 1 h))))}}})

(def voronoise-3d-chunk 
   (u/unquotable
    '{:functions
      {voronoise3D
       {([vec3] vec3)
        ([pos]
         (=vec3 p (floor pos))
         (=vec3 f (fract pos))

         (=float id 0)
         (=vec2 res (vec2 100))
         ("for (int k = -1; k <= 1; k++)"
          ("for (int j = -1; j <= 1; j++)"
           ("for (int i = -1; i <= 1; i++)"
            (=vec3 b (vec3 (float i)
                           (float j)
                           (float k)))
            (=ivec3 voxelCoords (ivec3 (+ p b))) 
            (=uvec4 voronoiColor
                    (texture seedTex
                             (/ (vec2 (+ voxelCoords.xy
                                         (ivec2 "0"
                                                (* "23"
                                                   voxelCoords.z))))
                                ~(cons 'vec2 (seq c/seed-tex-dimensions)))))
            (=vec3 r (- b (- f (-> voronoiColor
                                   .xyz
                                   vec3
                                   (/ ~c/u32-max)))))

            (=float d (dot r r))

            ("if" (< d res.x)
                  (= id (dot (+ p b) (vec3 1 57 113)))
                  (= res (vec2 d res.x)))
            ("else if" (< d res.y)
                       (= res.y d)))))
         (vec3 (sqrt res) (abs id)))}}}))

#_(def safe-simplex-3d-chunk
  (u/unquotable
   '{:functions {texRand
                 {([vec2 float] vec2)
                  ([pos t] 
                   (=float rand (-> seedTex 
                                    (texture (/ pos 64))
                                    .x
                                    float
                                    (/ ~c/u32-max)))
                   (=float angle (* ~u/TAU (+ rand (* 4 t rand))))
                   (vec2 (cos angle) 
                         (sin angle)))}
                 snoise3D
                 {([vec3] float)
                  ([pos]
                   (=vec2 i (floor pos.xy))
                   (=vec2 f (- pos.x i))
                   (=vec2 blend (* f f (- 3  (* 2 f))))
                   (=float noi (mix (mix (dot (texRand (+ i (vec2 0)) pos.z) 
                                              (- f (vec2 0)))
                                         (dot (texRand (+ i (vec2 1 0)) pos.z)
                                              (- f (vec2 1 0)))
                                         blend.x)
                                    (mix (dot (texRand (+ i (vec2 0 1)) pos.z)
                                              (- f (vec2 0 1)))
                                         (dot (texRand (+ i (vec2 1)) pos.z)
                                              (- f (vec2 1)))
                                         blend.x)
                                    blend.y))
                   (/ noi 0.7))}}}))

(def union-stair-chunk
  '{:functions
    {unionStair
     {([float float float float] float)
      ([d1 d2 r n]
       (=float s (/ r n))
       (=float u (- d2 r))
       (min (min d1 d2) (* 0.5 (+ u d1 (abs (- (mod (- u (+ d1 s))
                                                    (* 2 s)) s))))))}}})
(def subtraction-stair-chunk
  (combine-chunks
   union-stair-chunk
   '{:functions
     {subtractionStair
      {([float float float float] float)
       ([d1 d2 r n]
        (- 0 (unionStair (- 0 d1) d2 r n)))}}}))