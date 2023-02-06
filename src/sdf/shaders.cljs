(ns sdf.shaders
  (:require [sprog.util :as u]
            [sdf.chunks :as chunk]
            [sdf.config :as c]
            [sprog.iglu.chunks.noise :refer [rand-chunk
                                             rand-sphere-chunk
                                             simplex-3d-chunk
                                             fbm-chunk
                                             gabor-noise-chunk]]
            [sprog.iglu.chunks.raytracing :refer [raymarch-chunk
                                                  perspective-camera-chunk]]
            [sprog.iglu.chunks.sdf :as sdf]
            [sprog.iglu.chunks.transformations :refer [y-rotation-matrix-chunk
                                                       x-rotation-matrix-chunk
                                                       z-rotation-matrix-chunk]]
            [sprog.iglu.chunks.misc :refer [rescale-chunk
                                            pos-chunk
                                            gradient-chunk
                                            bilinear-usampler-chunk
                                            paretto-transform-chunk]]

            [sprog.iglu.core :refer [iglu->glsl
                                     combine-chunks]]
            [fxrng.rng :refer [fxrand
                               fxrand-int
                               fxshuffle]]))

(def u32-max (Math/floor (dec (Math/pow 2 32))))

(def rand-macro-chunk
  (u/unquotable
   (combine-chunks
    rand-chunk
    {:macros {:rand
              (fn [seed]
                '(rand (+ (vec2 ~(- (fxrand 1000) 500)
                                ~(- (fxrand 1000) 500))
                          (* ~seed
                             (vec2 ~(+ (fxrand 200) 300)
                                   ~(- (fxrand 200) 300))))))}})))

(def render-source
  (u/unquotable
   (iglu->glsl
    pos-chunk
    bilinear-usampler-chunk
    '{:version "300 es"
      :precision {float highp
                  int highp
                  usampler2D highp}
      :uniforms {size vec2
                 tex usampler2D
                 field usampler2D
                 location usampler2D}
      :outputs {fragColor vec4}
      :main ((=vec2 pos (/ gl_FragCoord.xy size))
             (= fragColor (vec4 (- (vec3 1)
                                   (-> tex
                                       (texture pos)
                                       .xyz
                                       (vec3)
                                       (/ ~u32-max))) 1)))})))


(def trail-frag-source
  (u/unquotable
   (iglu->glsl
    bilinear-usampler-chunk
    '{:version "300 es"
      :precision {float highp
                  int highp
                  usampler2D highp}
      :uniforms {size vec2
                 tex usampler2D}
      :outputs {fragColor uvec4}
      :main
      ((=vec2 pos (/ gl_FragCoord.xy size))
       (= fragColor (-> tex
                        (textureBilinear pos)
                        (vec3)
                        (* ~c/fade)
                        (vec4 1) 
                        (uvec4))))})))

(def particle-frag-source
  (u/unquotable
   (iglu->glsl
    paretto-transform-chunk
    rand-chunk
    '{:version "300 es"
      :precision {float highp
                  int highp
                  usampler2D highp}
      :uniforms {radius float
                 size vec2
                 field usampler2D}
      :inputs {particlePos vec2
               newRadius float}
      :outputs {fragColor uvec4}
      :main ((=vec2 pos (/ gl_FragCoord.xy size))
             (=vec3 f (-> field
                          (texture pos)
                          .xyz
                          (vec3)
                          (/ ~u32-max)))
             (=float dist (distance pos particlePos))
             ("if" (|| (> dist newRadius)
                       (&& (== f.x 0)
                           (== f.y 0)
                           (== f.z 0)))
                   "discard") 
             (= fragColor (uvec4 ~(str (Math/floor u32-max)))))})))

(def particle-vert-source
  (u/unquotable
   (iglu->glsl
    paretto-transform-chunk
    rand-macro-chunk
    '{:version "300 es"
      :precision {float highp
                  int highp
                  usampler2D highp}
      :outputs {particlePos vec2
                newRadius float}
      :uniforms {particleTex usampler2D
                 radius float
                 size vec2
                 field usampler2D}
      :main ((=int agentIndex (/ gl_VertexID i6))
             (=int corner (% gl_VertexID i6))

             (=ivec2 texSize (textureSize particleTex i0))

             (=vec2 texPos
                    (/ (+ 0.5 (vec2 (% agentIndex texSize.x)
                                    (/ agentIndex texSize.x)))
                       (vec2 texSize)))

             (=uvec4 particleColor (texture particleTex texPos))
             (= particlePos (/ (vec2 particleColor.xy) ~u32-max))
             (= newRadius (min (paretto (:rand particlePos)
                                        0.1
                                        radius)
                               (* 16 radius)))
             (= gl_Position
                (vec4 (- (* (+ particlePos
                               (* newRadius
                                  (- (* 2
                                        (if (|| (== corner i0)
                                                (== corner i3))
                                          (vec2 0 1)
                                          (if (|| (== corner i1)
                                                  (== corner i4))
                                            (vec2 1 0)
                                            (if (== corner i2)
                                              (vec2 0 0)
                                              (vec2 1 1)))))
                                     1)))
                            2)
                         1)
                      0
                      1)))})))


(def logic-frag-source
  (u/unquotable
   (iglu->glsl
    rand-chunk
    rescale-chunk
    bilinear-usampler-chunk
    '{:version "300 es"
      :precision {float highp
                  int highp
                  usampler2D highp}
      :outputs {fragColor uvec2}
      :uniforms {size vec2
                 now float
                 locationTex usampler2D
                 fieldTex usampler2D}
      :main
      ((=vec2 pos (/ gl_FragCoord.xy size))
       (=float time (* now 0.2))

       ; bringing u16 position tex range down to 0-1
       (=vec2 particlePos (/ (vec2 (.xy (texture locationTex pos))) ~u32-max))

       ; bringing u16 flowfield tex range down to -1 1
       (=vec4 field  (-> (textureBilinear fieldTex particlePos)
                         (vec4)
                         (/ ~u32-max)
                         (* 2)
                         (- 1)))



       (= fragColor
          (if (|| (&& (< field.x -0.99)
                      (< field.y -0.99)
                      (< field.z -0.99))
                  #_(&& (== field.x -1)
                        (== field.y -1)
                        (== field.z -1))
                  (> (+ particlePos.x (* field.x ~c/speed)) 1)
                  (> (+ particlePos.y (* field.y ~c/speed)) 1)
                  (< (+ particlePos.x (* field.x ~c/speed)) 0)
                  (< (+ particlePos.y (* field.y ~c/speed)) 0)
                  (> (rand (* (+ pos particlePos) 400))
                     (- ~c/randomization-chance
                        field.w)))

            (uvec2 (* (vec2
                       (rand (+ (* pos ~(fxrand 1000)) time))
                       (rand (+ (* pos.yx ~(fxrand 1000)) time))) ~u32-max))

            (uvec2 (* (vec2 (+ particlePos (* field.xy ~c/speed)))
                      ~u32-max)))))})))

(def noi-scale 0)

(defn shuffle-axis []
  (cons 'vec3 
    (fxshuffle (list 1 1 (fxrand 0.1)))))

(def sliced-cube
  '(+ (smoothSubtraction (min (sdBox (* pos
                                        (xRotationMatrix ~(fxrand u/TAU))
                                        (yRotationMatrix ~(fxrand u/TAU))
                                        (zRotationMatrix ~(fxrand u/TAU)))
                                     (vec3 0)
                                     ~(shuffle-axis))
                              (min (sdBox (* pos
                                             (xRotationMatrix ~(fxrand u/TAU))
                                             (yRotationMatrix ~(fxrand u/TAU))
                                             (zRotationMatrix ~(fxrand u/TAU)))
                                          (vec3 0)
                                          ~(shuffle-axis))
                                   (min (sdBox (* pos
                                                  (xRotationMatrix ~(fxrand u/TAU))
                                                  (yRotationMatrix ~(fxrand u/TAU))
                                                  (zRotationMatrix ~(fxrand u/TAU)))
                                               (vec3 0)
                                               ~(shuffle-axis))
                                        (min (sdBox (* pos
                                                       (xRotationMatrix ~(fxrand u/TAU))
                                                       (yRotationMatrix ~(fxrand u/TAU))
                                                       (zRotationMatrix ~(fxrand u/TAU)))
                                                    (vec3 0)
                                                    ~(shuffle-axis))
                                             (min (sdBox (* pos
                                                            (xRotationMatrix ~(fxrand u/TAU))
                                                            (yRotationMatrix ~(fxrand u/TAU))
                                                            (zRotationMatrix ~(fxrand u/TAU)))
                                                         (vec3 0)
                                                         ~(shuffle-axis))
                                                  (min (sdBox (* pos
                                                                 (xRotationMatrix ~(fxrand u/TAU))
                                                                 (yRotationMatrix ~(fxrand u/TAU))
                                                                 (zRotationMatrix ~(fxrand u/TAU)))
                                                              (vec3 0)
                                                              ~(shuffle-axis))
                                                       (sdBox (* pos
                                                                 (xRotationMatrix ~(fxrand u/TAU))
                                                                 (yRotationMatrix ~(fxrand u/TAU))
                                                                 (zRotationMatrix ~(fxrand u/TAU)))
                                                              (vec3 0)
                                                              ~(shuffle-axis))))))))
                         (sdBox pos (vec3 0) (vec3 0.75))
                         0.1)
      (* 0.01
         (gaborNoise 3
                     ~fxrand
                     ~(mapv #(* % (inc (fxrand-int 4)))
                            [0.25 0.5 1 (/ 5 4) (/ 3 2)])
                     pos
                     0.1))))

(def torii-surround-sphere 
  '(min (min (sdTorus pos (vec3 0) (vec2 1.2 0.1))
             (min (sdTorus pos (vec3 0) (vec2 1.5 0.1))
                  (min (sdTorus pos (vec3 0) (vec2 1.8 0.1))
                       (min (sdTorus pos (vec3 0) (vec2 2.1 0.1))
                            (min (sdTorus pos (vec3 0) (vec2 2.4 0.1))
                                 (min (sdTorus pos (vec3 0) (vec2 2.7 0.1))
                                      (min (sdTorus pos (vec3 0) (vec2 3 0.1))
                                           (min (sdTorus pos (vec3 0) (vec2 3.3 0.1))
                                                (min (sdTorus pos (vec3 0) (vec2 3.6 0.1))
                                                     (min (sdTorus pos (vec3 0) (vec2 3.9 0.1))
                                                          (sdTorus pos (vec3 0) (vec2 4.2 0.1))))))))))))
        (sdSphere pos (vec3 0) 0.95)))

(def three-torii 
  '{:functions
    {shape
     {([vec3] float)
      ([pos]
       (unionStair (min (sdTorus pos (vec3 0) (vec2 1.5 0.1))
                        (min (min (sdBox pos (vec3 0) (vec3 1 0.3 0.00001))
                                  (sdBox pos (vec3 0 0 0.5) (vec3 1 0.3 0.00001)))
                             (sdBox pos (vec3 0 0 -0.5) (vec3 1 0.3 0.00001))))
                   (sdCapsule pos (vec3 0) (vec3 0 0 -1.3) (vec3 0 0 1.3) 0.05)
                   0.15
                   17))}
     sdf
     {([vec3] float)
      ([pos]
       (*= pos (xRotationMatrix (* 0.5 ~Math/PI)))
       (=float one (shape pos))
       (=float two (shape (vec3 pos.x (+ pos.y 0.75) pos.z)))
       (=float three (shape (vec3 pos.x (- pos.y 0.75) pos.z)))
       (+ (min one (min two three))
          (* 0.01
             (fbm snoise4D
                  4
                  (vec4 (* 5 pos) ~(fxrand 1000))
                  "5"
                  0.5))))}}})

(defn volcanic [twist-factor]
  (u/unquotable
   '(+ (min (smoothUnion (smoothUnion (sdTorus (twistX pos ~twist-factor) (vec3 0) (vec2 1 0.1))
                                      (sdTorus (twistY (* pos (xRotationMatrix ~(* 0.5 Math/PI))) ~twist-factor) (vec3 0) (vec2 1 0.1))
                                      0.25)
                         (smoothUnion (sdSphere pos (vec3 0) 0.5)
                                      (sdCapsule pos (vec3 0) (vec3 0.75 1 0) (vec3 -0.75 -1 0) 0.0175)
                                      0.25)
                         0.1)
            (min (min (sdSphere pos ~c/light-pos 0.1)
                      (sdSphere pos (+ ~c/light-pos
                                       (vec3 0.3 0.1 0)) 0.04))
                 (min  (sdSphere pos (* -1 ~c/light-pos) 0.1)
                       (sdSphere pos (* -1 (+ ~c/light-pos
                                              (vec3 0.3 0.1 0))) 0.04))))
       (* 0.02
          (fbm gb
               3
               pos
               "5"
               0.7)))))

(def sliced-torus '(onion
                    (onion
                     (+ (subtractionStair (sdTorus (* pos (xRotationMatrix (* 0.25 ~u/TAU))) (vec3 0) (vec2 0.6 0.05))
                                          (smoothUnion (sdBoxframe (twistX (* pos
                                                                              (xRotationMatrix ~(fxrand u/TAU))
                                                                              (yRotationMatrix ~(fxrand u/TAU))
                                                                              (zRotationMatrix ~(fxrand u/TAU))) 10)
                                                                   (vec3 0) (vec3 0.65) 0.35)
                                                       (sdBoxframe (twistX (* pos
                                                                              (xRotationMatrix ~(fxrand u/TAU))
                                                                              (yRotationMatrix ~(fxrand u/TAU))
                                                                              (zRotationMatrix ~(fxrand u/TAU))) 10)
                                                                   (vec3 0) (vec3 0.55) 0.2)
                                                       ~(fxrand 0.5))
                                          0.1
                                          8)
                        (* 0.01 (gaborNoise 3
                                            ~fxrand
                                            ~[0.5 1 4 7 11]
                                            pos))) 0.1) 0.06))

(def norm-field-frag-source
  (u/unquotable
   (iglu->glsl
    pos-chunk
    rescale-chunk
    chunk/plane-sdf-chunk
    perspective-camera-chunk
    sdf/sphere-sdf-chunk
    sdf/box-sdf-chunk
    sdf/box-frame-sdf-chunk
    sdf/torus-sdf-chunk
    sdf/capsule-sdf-chunk
    chunk/smooth-intersectioon-chunk
    chunk/smooth-subtraction-chunk
    sdf/smooth-union-chunk
    sdf/pyramid-sdf-chunk
    chunk/intersection-stair-chunk
    chunk/subtraction-stair-chunk
    chunk/repitition-chunk
    chunk/union-chamfer-chunk
    chunk/union-stair-chunk
    chunk/onion-chunk
    chunk/polar-repitition-chunk
    raymarch-chunk
    gradient-chunk
    simplex-3d-chunk
    gabor-noise-chunk
    rand-macro-chunk
    fbm-chunk
    rand-chunk
    rand-sphere-chunk
    x-rotation-matrix-chunk
    y-rotation-matrix-chunk
    z-rotation-matrix-chunk
    chunk/voronoise-3d-chunk
    chunk/twistX-chunk
    chunk/twistY-chunk
    '{:version "300 es"
      :precision {float highp
                  int highp}
      :uniforms {size vec2
                 time float
                 mouse vec2}
      :outputs {fragColor uvec4}
      :functions {rot
                  {([float] mat2)
                   ([angle]
                    (=float s (sin angle))
                    (=float c (cos angle))
                    (mat2 c (- 0 s) s c))}
                  smin
                  {([float float float] float)
                   ([d1 d2 k]
                    (=float h (clamp (+ 0.5 (* 0.5 (/ (- d2 d1) k))) 0 1))
                    (- (mix d2 d1 h) (* k h (- 1 h))))}
                  gb
                  {([vec3] float)
                   ([x]
                    (gaborNoise 3
                                ~fxrand
                                ~[0.25 0.5 1 4 7 11 14]
                                x))}
                  sdf
                  {([vec3] float)
                   ([pos]
                    #_(= pos (* pos
                              (xRotationMatrix ~(* 0.25 u/TAU))
                              (yRotationMatrix ~(fxrand u/TAU))))
                    (=float d 1024)
                    (~(str "for(int i = 1; i <= " c/sphere-octaves "; i++)")
                     (=vec3 rot-pos pos #_(* pos
                                       (xRotationMatrix (* ~Math/PI (rand (vec2 (* (float i)
                                                                                   (float i))
                                                                                (rand (vec2 (mod (* 0.1 (float i)) ~(fxrand))
                                                                                            (- 0 (float i))))))))
                                       (yRotationMatrix (* ~Math/PI (rand (vec2 (* (float i)
                                                                                   (float i))
                                                                                (rand (vec2 (float i)
                                                                                            (* (float i) (- 0 (pow ~(fxrand) (* (float i)
                                                                                                                          (float i)))))))))))))
                     (=vec3 c (vec3 (* 8 (pow 0.5 (float i)))))
                     (=vec3 q (- (mod (+ rot-pos (* 0.5 c)) c) (* 0.5 c)))
                     (=vec3 index (floor (/ (+ rot-pos (* 0.5 c)) (* 0.5 c))))
                     (=float s (sdBoxframe q
                                         (vec3 0)#_(* 0.1 (vec3 (rand (* index.xy ~(rand 100)))
                                                       (rand (* index.yx ~(rand 100)))
                                                       (rand (* index.zx ~(rand 100)))))
                                         (vec3 (* 4
                                            #_(mix 0.99 1 (rand (+ index.xy (* ~(fxrand) index.z))))
                                            (pow 0.5 (float i))))
                                           (* 1
                                              #_(mix 0.9 1 (rand index.yz))
                                              (pow 0.5 (float i)))))
                     (= d (smoothUnion d s 0.0001)))
                    (smoothSubtraction d
                                       (sdBox pos (vec3 0) (vec3 2.2))
                                       0.0001))}}
      :main ((=vec2 pos (getPos))
            ;setup camera
             (=vec3 cam-pos (vec3 0 0 -5.75))
             #_(*= cam-pos (xRotationMatrix (* 0.05 ~Math/PI)))
             #_(*= cam-pos (yRotationMatrix (* 0 ~Math/PI)))
             (=vec3 origin (vec3 0))
             (=vec3 light-pos ~c/light-pos)
             (=vec3 light-dir (normalize (- cam-pos light-pos)))

            ;create ray 
             (=Ray ray (cameraRay pos origin cam-pos 0.75))

            ;initialize variables  
             (=vec3 surfacePos (vec3 0))
             (=vec3 surfaceNorm (vec3 0))
             (=vec3 col (vec3 0))
             (=float diff 0)
             (=float spec 0)

             (=float distance (raymarch sdf
                                        ray
                                        10000
                                        {:step-size 0.1
                                         :termination-threshold 0.001}))
            ;do distance estimation if inside bounding volume
             ("if" (> distance 0)
                   (=vec3 surfacePos (+ ray.pos (* ray.dir distance)))
                   (=vec3 surfaceNorm (findGradient 3
                                                    sdf
                                                    0.001
                                                    surfacePos))
                   (= diff (max 0 (dot light-dir surfaceNorm))) 
                   #_(= spec (-> (smoothstep 0.97 0.975 diff)
                               (pow 10)
                               (* 0.5)))

                   (= col (-> surfaceNorm
                              (* 0.5)
                              (+ 0.5)
                              (clamp 0 1))))

            ;output
             (= fragColor (-> col
                              (vec4 (* 0.5 diff))
                              (* ~u32-max)
                              (uvec4))))})))

(def init-frag-source
  (u/unquotable
   (iglu->glsl
    rand-macro-chunk
    '{:version "300 es"
      :precision {float highp
                  usampler2D highp}
      :uniforms {size vec2}
      :outputs {fragColor uvec2}
      :main ((=vec2 pos (/ gl_FragCoord.xy size))
             (= fragColor (uvec2 (* (vec2 (:rand pos)
                                          (:rand pos)) ~u32-max))))})))