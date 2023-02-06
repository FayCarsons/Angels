(ns sdf.chunks
  (:require [sprog.util :as u]
            [sprog.iglu.chunks.noise :refer [rand-chunk]]
            [sprog.iglu.core :refer [combine-chunks]]))

(def plane-sdf-chunk
  '{:functions {sdPlane
                {([vec3 vec3 float] float)
                 ([pos norm h]
                  (+ (dot pos norm) h))}}})

(def union-chunk
  '{:functions 
    {union 
     {([float float] float)
      ([d1 d2]
       (min d1 d2))}}})

(def subtraction-chunk 
  '{:functions
    {subtraction
     {([float float] float)
      ([d1 d2]
       (max (- 0 d1) d2))}}})

(def intersection-chunk
  '{:functions
    {intersection
     {([float float] float)
      ([d1 d2]
       (max d1 d2))}}})

(def smooth-union-chunk
  '{:functions
    {smoothUnion
     {([float float float] float)
      ([d1 d2 k]
       (=float h (clamp (+ (/ (* 0.5 (- d2 d1)) k) 0.5) 0 1))
       (- (mix d2 d1 h) (* k h (- 1 h))))}}})

(def smooth-intersectioon-chunk
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

(def onion-chunk
  '{:functions
   {onion
    {([float float] float)
     ([d h]
      (- (abs d) h))}}})
(def twistX-chunk
  '{:functions
    {twistX
    {([vec3 float] vec3)
     ([pos k]
      (=float c (cos (* k pos.x)))
      (=float s (sin (* k pos.x)))
      (=mat2 m (mat2 c (- 0 s) s c))
      (=vec3 q (vec3 (* m pos.yz) pos.x))
      q)}}})

(def twistY-chunk
  '{:functions {twistY
   {([vec3 float] vec3)
    ([pos k]
     (=float c (cos (* k pos.y)))
     (=float s (sin (* k pos.y)))
     (=mat2 m (mat2 c (- 0 s) s c))
     (=vec3 q (vec3 (* m pos.xz) pos.y))
     q)}}})

(def voronoise-3d-chunk 
   '{:functions
    {hash {([vec3] vec3)
           ([x]
            (= x (vec3 (dot x (vec3 127.1 311.7 74.7))
                       (dot x (vec3 269.5 183.3 246.1))
                       (dot x (vec3 113.5 271.9 124.6))))
            (fract (* (sin x) 43758.5453123)))}
     voronoise3D {([vec3] vec3)
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
                      (=vec3 r (- b (- f (hash (+ p b)))))
                      (=float d (dot r r))
                      
                      ("if" (< d res.x)
                       (= id (dot (+ p b) (vec3 1 57 113)))
                       (= res (vec2 d res.x)))
                      ("else if" (< d res.y)
                       (= res.y d)))))
                   (vec3 (sqrt res) (abs id)))}}})

(def bezier-chunk-2d
  '{:functions
    {bezier
     {([vec2 float vec2 vec2 vec2] float)
      ([pos e b0 b1 b2]
       (-= b0 pos)
       (-= b1 pos)
       (-= b2 pos)

       (=float a (determinant (mat2 b0 b2)))
       (=float b (* 2 (determinant (mat2 b1 b0))))
       (=float d (* 2 (determinant (mat2 b2 b1))))

       (=float f (- (* b d)
                    (* a a)))
       (=vec2 d21 (- b2 b1))
       (=vec2 d10 (- b1 b0))
       (=vec2 d20 (- b2 b0))

       (=vec2 gf (* 2 (+ (* b d21)
                         (* d d10)
                         (* a d20))))
       (= gf (vec2 gf.y (- 0 gf.x)))

       (=vec2 pp (/ (* (- 0 f) gf)
                    (dot gf gf)))
       (=vec2 d0p (- b0 pp))
       (=float ap (determinant (mat2 d0p d20)))
       (=float bp (* 2 (determinant (mat2 d10 d0p))))
       (=float t (clamp (/ (+ ap bp) (+ (* 2 a) b d))
                        0
                        1))

       (=vec2 dist (vec2 (length (mix (mix b0 b1 t)
                                      (mix b1 b2 t)
                                      t))
                         (length pp)))
       (=float fo (clamp 0 1 (pow (/ (length b1) (* 4 (length d20)))
                                  4)))
       (min (smoothstep (* e 0.1) (* 2 e) dist.x)
            (mix (+ 0.8 (* 0.2 (smoothstep (* e 0.1) e dist.y))) 1 fo)))}}})

(def repitition-chunk
  '{:functions
    {repitition
     {([float float] float)
      ([pos size]
       (=float halfsize (* size 0.5))
       (=float c (floor (/ (+ pos halfsize) size)))
       (= pos (- (mod (+ pos halfsize) size) halfsize))
       pos)
      ([vec2 vec2] vec2)
      ([pos size]
       (=vec2 halfsize (* size 0.5))
       (=vec2 c (floor (/ (+ pos halfsize) size)))
       (= pos (- (mod (+ pos halfsize) size) halfsize))
       pos)
      ([vec3 vec3] vec3)
      ([pos size]
       (=vec3 q (- (mod (+ pos (* 0.5 size)) size) (* 0.5 size)))
       q)}}})

(def polar-repitition-chunk
  (u/unquotable
   '{:functions
     {polarRepitition
      {([vec2 float] float)
       ([axis reps]
        (=float angle (/ (* 2 ~Math/PI) reps))
        (=float a (+ (atan axis.y axis.x)
                     (/ angle 2)))
        (=float r (length axis))
        (=float c (floor (/ a angle)))
        (= a (- (mod a angle) (/ angle 2)))
        (= axis (* (vec2 (cos a)
                         (sin a))
                   r))
        ("if" (>= (abs c) (/ reps 2))
              (= c (abs c)))
        c)}}}))
; reflects space over one axis
(def domain-reflection-chunk
  '{:functions
    {opReflect
     {([vec3 vec3 float] float)
      ([pos normal offset]
       (=float t (+ (dot p normal) offset))
       ("if" (< t 0)
             (= pos (- pos (* 2 t normal))))
       (if (< t 0)
         -1
         1))}}})
; column and stair operations that produce those shapes at interection point
(def union-column-chunk
  (combine-chunks
   repitition-chunk
   '{:functions
     {unionColumn
      {([float float float float] float)
       ([d1 d2 r n]
        ("if" (&& (< d1 r) (< d2 r))
              (=vec2 p (vec2 d1 d2))
              (=float columnRadius (/ (* r (sqrt 2))
                                      (+ (* (- n 1) 2)
                                         (sqrt 2))))
              (= p (* (+ p (vec2 p.y (- 0 p.x)))
                      (sqrt 0.5)))
              (-= p.x (/ (sqrt 2) (* 2 r)))
              (+= p.x (* columnRadius (sqrt 2)))
              ("if" (== (mod n 2) 1)
                    (+= p.y columnRadius))
              (repition p.y (* columnRadius 2))
              (=float result (- (length p) columnRadius))
              (= result (min result p.x))
              (= result (min result d1))
              (return (min result b)))
        ("else"
         (return (min d1 d2))))}}}))

(def subtraction-column-chunk
  (combine-chunks
   repitition-chunk
   '{:functions
     {subtractionColumn
      {([float float float float] float)
       ([d1 d2 r n]
        (= d1 (- 0 d1))
        (=float m (min d1 d2))
        ("if" (&& (< d1 r) (< d2 r))
              (=vec2 p (vec2 d1 d2))
              (=float columnRadius (/ (* r (sqrt 2))
                                      (* n 0.5)))
              (= columnRadius (/ (* r (sqrt 2))
                                 (+ (* (- n 1) 2)
                                    (sqrt 2))))
              (= p (* (+ p (vec2 p.y (- 0 p.x)))
                      (sqrt 0.5)))
              (+= p.y columnRadius)
              (-= p.x (/ (sqrt 2)
                         (* r 2)))
              (+= p.x (/ (* (- 0 columnRadius) (sqrt 2)) 2))
              ("if" (== (mod n 2) 1)
                    (+= p.y columnRadius))
              (repitition p.y (* columnRadius 2))

              (=float result (+ (- 0 (length p)) columnRadius))
              (= result (max result p.x))
              (= result (min result d1))
              (return (- 0 (min result b))))
        ("else"
         (return (- 0 m))))}}}))
(def intersection-column-chunk
  (combine-chunks
   subtraction-column-chunk
   '{:functions
     {intersectionChamfer
      {([float float float float] float)
       ([d1 d2 r n]
        (subtractioonColumn d1 (- 0 d2) r n))}}}))
(def union-stair-chunk
  '{:functions
    {unionStair
     {([float float float float] float)
      ([d1 d2 r n]
       (=float s (/ r n))
       (=float u (- d2 r))
       (min (min d1 d2) (* 0.5 (+ u d1 (abs (- (mod (- u (+ d1 s))
                                                    (* 2 s)) s))))))}}})
(def intersection-stair-chunk
  (combine-chunks
   union-stair-chunk
   '{:functions
     {intersectionStair
      {([float float float float] float)
       ([d1 d2 r n]
        (- 0 (unionStair (- 0 d1) (- 0 d2) r n)))}}}))
(def subtraction-stair-chunk
  (combine-chunks
   union-stair-chunk
   '{:functions
     {subtractionStair
      {([float float float float] float)
       ([d1 d2 r n]
        (- 0 (unionStair (- 0 d1) d2 r n)))}}}))


(def union-chamfer-chunk
  '{:functions
    {unionChamfer
     {([float float float] float)
      ([d1 d2 r]
       (min (min d1 d2) (* (- d1 (+ r d2)) (sqrt 0.5))))}}})

(def intersection-chamfer-chunk
  '{:functions
    {intersectionChamfer
     {([float float float] float)
      ([d1 d2 r]
       (max (max d1 d2) (* (+ d1 d2 r) (sqrt 0.5))))}}})

(def subtraction-chamfer-chunk
  (combine-chunks
   intersection-chamfer-chunk
   '{:functions
     {intersectionChamfer
      {([float float float] float)
       ([d1 d2 r]
        (intersectionChamfer d1 (- 0 d2) r))}}}))