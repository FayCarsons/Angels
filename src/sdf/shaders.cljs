(ns sdf.shaders
  (:require [sprog.util :as u]
            [sdf.chunks :as chunk]
            [sdf.config :as c]
            [sprog.iglu.chunks.noise :refer [rand-chunk
                                             rand-sphere-chunk
                                             simplex-3d-chunk
                                             simplex-2d-chunk
                                             fbm-chunk
                                             gabor-noise-chunk]]
            [sprog.iglu.chunks.raytracing :refer [raymarch-chunk
                                                  perspective-camera-chunk]]
            [sprog.iglu.chunks.sdf :as sdf]
            [sprog.iglu.chunks.transformations :refer [y-rotation-matrix-chunk
                                                       x-rotation-matrix-chunk
                                                       z-rotation-matrix-chunk
                                                       axis-rotation-chunk]]
            [sprog.iglu.chunks.misc :refer [rescale-chunk
                                            sigmoid-chunk
                                            pos-chunk 
                                            gradient-chunk
                                            bilinear-usampler-chunk
                                            paretto-transform-chunk]]
            [sprog.iglu.chunks.postprocessing :refer [create-gaussian-sample-chunk
                                                      square-neighborhood]]
            [sprog.iglu.chunks.colors :refer [mix-oklab-chunk]]

            [sprog.iglu.core :refer [iglu->glsl
                                     combine-chunks]]
            [fxrng.rng :refer [fxrand
                               fxchoice
                               fxchance
                               fxrand-int
                               fxshuffle]]))

(def u32-max (Math/floor (dec (Math/pow 2 32))))

(def header '{:version "300 es"
              :precision {float highp
                          int highp
                          sampler2D highp
                          usampler2D highp}})

(defn ambient-occlusion-chunk [scene-fn-name
                               distance
                               samples
                               power]
  (u/unquotable
   '{:functions 
     {occlusion
      {([vec3 vec3] float)
       ([pos norm]
        (=float dist ~distance)
        (=float o 1)
        (=int smp ~(str (int samples)))
        ("for (int i = 0; i < smp; ++i)"
         (= o (min o (/ (~scene-fn-name (+ pos 
                                               (* dist norm))) 
                            dist)))
         (*= dist ~power))
        (max o 0))}}}))

(def rand-macro-chunk
  (u/unquotable
   (combine-chunks
    rand-chunk
    {:macros {:rand
              (fn [seed]
                '(rand (+ (vec2 ~(- (fxrand 1000) 500)
                                ~(- (fxrand 1000) 500))
                          (* ~seed
                             (vec2 ~(+ (fxrand 1000) 1000)
                                   ~(+ (fxrand 1000) 1000))))))}})))

(def render-source
  (u/unquotable
   (iglu->glsl
    pos-chunk
    bilinear-usampler-chunk
    rand-chunk
    mix-oklab-chunk
    simplex-2d-chunk
    sigmoid-chunk
    (create-gaussian-sample-chunk :u32 (square-neighborhood 4))
    '{:version "300 es"
      :precision {float highp
                  int highp
                  usampler2D highp}
      :uniforms {size vec2
                 normals usampler2D
                 tex usampler2D
                 backgroundTex usampler2D}
      :outputs {fragColor vec4} 
      :main ((=vec2 pos (/ gl_FragCoord.xy size))
             (=float particles (-> tex
                               (textureBilinear pos)
                               .x
                               float
                               (/ ~(long u32-max))))
             (=float background (-> backgroundTex
                                    (textureBilinear pos)
                                    .x
                                    float
                                    (/ ~(long u32-max))
                                    (* ~(second c/background-highlight))))
             (=float sideDist
                     (min (- pos.x ~c/frame-width)
                          (min (- ~(- 1 c/frame-width) pos.x)
                               (min (- pos.y ~c/frame-width)
                                    (- ~(- 1 c/frame-width) pos.y)))))
             (=vec3 col (mixOklab (if (&& (< sideDist 0)
                                          (> sideDist -0.002))
                                    (mix (vec3 0)
                                         ~c/antique-white
                                         0.2)
                                    (mixOklab ~c/antique-white
                                              ~(first c/background-highlight)
                                              background))
                                  (vec3 0.05)
                                  particles)) 
             (= fragColor (vec4 col #_(-> normals
                                          (texture pos)
                                          .xyz
                                          vec3
                                          (/ ~(long u32-max))) 1)))})))

(def copy-source 
  (u/unquotable 
   (iglu->glsl 
    header
    '{:uniforms {size vec2
                 tex usampler2D}
      :outputs {fragColor uvec4}
      :main ((=vec2 pos (/ gl_FragCoord.xy size))
             (= fragColor (texture tex pos)))})))

(def patchwork-frag
  (u/unquotable
   (iglu->glsl
    header
    bilinear-usampler-chunk
    '{:uniforms {size vec2
                circleField usampler2D
                normalMap usampler2D}
      :outputs {fragColor uvec4}
      :main ((=vec2 pos (/ gl_FragCoord.xy size))
             (=vec2 circles (-> circleField
                                (textureBilinear pos)
                                .xy
                                vec2
                                (/ ~(long u32-max))))
             (=vec2 normals (-> normalMap
                                (textureBilinear pos)
                                .xy
                                vec2
                                (/ ~(long u32-max))))
             (= fragColor (-> (if (&& (> normals.x
                                           0)
                                        (> normals.y
                                           0))
                                  (vec4 normals.xy 1 0)
                                  (vec4 circles 0 0))
                              (* ~(long u32-max))
                              uvec4)))})))


(def trail-frag-source
  (u/unquotable
   (iglu->glsl
    header
    bilinear-usampler-chunk
    '{:uniforms {size vec2
                 tex usampler2D
                 fade float}
      :outputs {fragColor uvec4}
      :main
      ((=vec2 pos (/ gl_FragCoord.xy size))
       (= fragColor (-> tex
                        (textureBilinear pos)
                        (vec3)
                        (* fade)
                        (vec4 1) 
                        (uvec4))))})))

(def particle-frag-source
  (u/unquotable
   (iglu->glsl
    header
    paretto-transform-chunk
    rand-chunk
    bilinear-usampler-chunk
    '{:uniforms {radius float
                 size vec2
                 sketch int
                 field usampler2D
                 targetTex usampler2D}
      :inputs {particlePos vec2
               newRadius float}
      :outputs {fragColor uvec4}
      :main ((=vec2 pos (/ gl_FragCoord.xy size))
             (=vec3 f (-> field
                          (textureBilinear pos)
                          .xyz
                          (vec3)
                          (/ ~u32-max)))
             (=float dist (distance pos particlePos))
             ("if" (|| (> dist newRadius)
                       (&& (> sketch "0")
                           (== f.x 0)
                           (== f.y 0)
                           (== f.z 0)))
                   "discard") 
             (=float target (-> targetTex 
                                (textureBilinear pos)
                                .x
                                float
                                (/ ~(long u32-max))))
             (=float col  (if (== sketch "1")
                           (max target (- 1 (smoothstep 0 newRadius dist)))
                           0.999))
             (= fragColor (uvec4 (* (vec3 col)
                                    ~(Math/floor u32-max))
                                 ~(str (long u32-max)))))})))

(def particle-vert-source
  (u/unquotable
   (iglu->glsl
    header
    paretto-transform-chunk
    rand-macro-chunk 
    '{:outputs {particlePos vec2
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
             (= newRadius ~(if c/paretto?
                             '(min (paretto (:rand particlePos)
                                            ~c/paretto-shape
                                            radius)
                                   (* ~c/paretto-scale radius))
                             'radius))
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
    header
    rand-chunk
    rescale-chunk
    bilinear-usampler-chunk
    '{:outputs {fragColor uvec2}
      :uniforms {size vec2
                 now float
                 sketch int
                 speed float
                 randomizationChance float
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
          (if (|| (&& (< field.x -0.999)
                      (< field.y -0.999)
                      (< field.z -0.999)
                      (> sketch "0"))
                  (> (+ particlePos.x (* field.x speed)) 1)
                  (> (+ particlePos.y (* field.y speed)) 1)
                  (< (+ particlePos.x (* field.x speed)) 0)
                  (< (+ particlePos.y (* field.y speed)) 0)
                  (> (rand (* (+ pos particlePos) 400))
                     (- randomizationChance
                        field.w)))

            (uvec2 (* (vec2
                       (rand (+ (* pos ~(fxrand 1000)) time))
                       (rand (+ (* pos.yx ~(fxrand 1000)) time))) ~u32-max))

            (uvec2 (* (vec2 (+ particlePos (* field.xy speed)))
                      ~u32-max)))))})))

(defn shuffle-axis []
  (cons 'vec3 
    (fxshuffle (list 1 1 (fxrand 0.1)))))


(defn volcanic [twist-factor]
  (u/unquotable
   '((+ (smoothUnion (smoothUnion (sdTorus (twistX pos ~twist-factor)
                                           (vec3 0)
                                           (vec2 0.75 0.1))
                                  (sdTorus (twistY (* pos (xRotationMatrix ~(* 0.5 Math/PI))) ~twist-factor)
                                           (vec3 0)
                                           (vec2 0.75 0.1))
                                  0.25)
                     (smoothUnion (sdSphere pos
                                            (vec3 0)
                                            0.4)
                                  (sdCapsule pos
                                             (vec3 0)
                                             (vec3 0.75 0.75 0)
                                             (vec3 -0.75 -0.75 0) 0.04)
                                  0.1)
                     0.1)
        (* 0.075
           (sigmoid (fbm gb
                         3
                         pos
                         "5"
                         0.7)))))))

(def sliced-torus
  (u/unquotable
   '((onion
     (onion
      (+ (unionStair (sdTorus (* pos (xRotationMatrix (* 0.25 ~u/TAU)))
                              (vec3 0)
                              (vec2 0.5 0.25))
                     (smoothUnion (sdBoxframe (twistX (* pos
                                                         (axisRoationMatrix (randSphere ~(cons 'vec3 (u/gen 3 (fxrand))))
                                                                            ~(fxrand u/TAU)))
                                                      ~(fxrand-int 5 15))
                                              (vec3 0)
                                              (vec3 0.45)
                                              0.1)
                                  (sdBoxframe (twistY (* pos
                                                         (axisRoationMatrix (randSphere ~(cons 'vec3 (u/gen 3 (fxrand))))
                                                                            ~(fxrand u/TAU)))
                                                      ~(fxrand-int 5 15))
                                              (vec3 0)
                                              (vec3 0.25)
                                              0.2)
                                  0.2)
                     0.5
                     8)
         (* 0.1
            (sigmoid (gaborNoise 3
                                 ~fxrand
                                 ~(mapv #(* % 0.25 (inc (fxrand-int 8)))
                                        [0.5 1 4 7])
                                 pos))))
      0.1)
     0.06))))

(def voronoise-sphere
  (u/unquotable
   '((=float sphere (sdSphere pos (vec3 0) 1))
     (min (max (max (+ sphere
                       (-> pos
                           (* (zRotationMatrix ~(fxrand u/TAU)))
                           fVoronoi
                           (pow 0.75)
                           (* ~(fxrand 0.1 0.25))))
                    (- 0 (sdSphere pos (vec3 0) 0.85)))
               (- 0 (sdBox (* pos
                              (axisRoationMatrix (randSphere ~(cons 'vec3 (u/gen 3 (fxrand))))
                                                 ~(fxrand u/TAU))
                              #_(zRotationMatrix (* 0.25 ~Math/PI)))
                           (vec3 0)
                           (vec3 0.1 10 10))))
          (sdSphere pos (vec3 0) 0.25)))))

(def menger-box
  (u/unquotable
   '((=float d 1024)
     (~(str "for(int i = 0; i <= " c/sphere-octaves "; ++i)")
      (=vec3 rot-pos (* pos
                        (axisRoationMatrix
                         (randSphere
                          (* 10 (vec3 (* 33.6987298572 (fract (* (float i)
                                                                 (float i)
                                                                 ~(fxrand))))
                                      (* 42.54869 (rand (vec2 (float i)
                                                              (* (float i) (- 0 (pow ~(fxrand) (* (float i)
                                                                                                  (float i))))))))
                                      (* 51.9028746908274 (rand (vec2 (* (float i)
                                                                         (float i)
                                                                         (float i))
                                                                      (fract (* 0.333 (float i)))))))))
                         (* ~u/TAU (rand (vec2 (pow 2 (float i))
                                               (rand (vec2 (mod (* 0.1 (float i)) ~(fxrand))
                                                           (- 0 (float i))))))))))
      (=vec3 c (vec3 (* 2 (pow 0.5 (float i)))))
      (=vec3 q (- (mod (+ rot-pos (* 0.5 c)) c) (* 0.5 c)))
      (=vec3 index (floor (/ (+ rot-pos (* 0.5 c)) (* 0.5 c))))
      (=float s #_(sdBoxframe q
                            (vec3 0)
                            (vec3 (* 0.5
                                     #_(mix 0.5 1 (rand (+ index.xy (* ~(fxrand) index.z))))
                                     (pow 0.5 (float i))))
                            (* 0.5 (pow 0.5 (float i))))
              (sdSphere q
                          (* 0 (vec3 (rand (* index.xy ~(fxrand 400)))
                                     (rand (* index.yx ~(fxrand 400)))
                                     (rand (* index.zx ~(fxrand 400)))))
                          (* 0.9
                             #_(mix 0.5 1 (rand (+ index.xy (* ~(fxrand 400) index.z))))
                             (pow 0.5 (float i)))))
      (= d (min d s)))
     (min (smoothSubtraction d
                        (max (sdSphere pos (vec3  0) 1)
                             (- 0 (sdSphere pos (vec3 0) 0.95)))
                        #_(sdBox pos
                                 (vec3 0)
                                 (vec3 0.75))
                        0.00001)
          (+ (sdSphere pos (vec3 0) 0.5)
             (* 0.1 
                (sigmoid (gaborNoise 3
                                     ~fxrand
                                     ~[0.25 0.5 1 4 7]
                                     pos))))))))

(def sliced-sphere
  (u/unquotable 
   '((=int iters ~c/plane-iters)
     (=float d 1024)
     ("for(int i = 1; i <= iters; ++i)"
      (=vec3 rot-pos (* pos
                        (axisRoationMatrix (randSphere
                                            (* 100 (vec3 (* 3 (fract (* (float i)
                                                                        (float i)
                                                                        ~(fxrand))))
                                                         (rand (vec2 (float i)
                                                                     (* (float i) (- 0 (pow ~(fxrand) (* (float i)
                                                                                                         (float i)))))))
                                                         (rand (vec2 (* (float i)
                                                                        (float i)
                                                                        (float i))
                                                                     (fract (* 0.333 (float i))))))))
                                           (* ~u/TAU (rand (vec2 (pow 2 (float i))
                                                                 (rand (vec2 (mod (* 0.1 (float i)) ~(fxrand))
                                                                             (- 0 (float i))))))))))
      (=float planes (sdBox rot-pos
                            (vec3 0)
                            (if (== (% i (* iters (int (floor (rand (vec2 (* (float i) ~(+ 250 (rand 1000)))
                                                                          (* (float i) ~(+ 250 (rand 1000)))))))))
                                    ~(str (rand-int c/plane-iters)))
                              (if (== (% i (* iters (int (floor (rand (vec2 (* (float i) ~(+ 250 (rand 1000)))
                                                                            (* (float i) ~(+ 250 (rand 1000)))))))))
                                      ~(str (rand-int c/plane-iters)))
                                (vec3 ~c/plane-size 1 1)
                                (vec3 1 ~c/plane-size 1))
                              (vec3 1 1 ~c/plane-size))))
      (= d (min d planes)))


     (=float sphere (sdSphere pos (vec3 0) 1))
     (+ (max sphere
             (- 0 d))
        (* 0.03
           (smoothstep -0.1 0 sphere)
           #_(sigmoid (fbm gb
                           3
                           pos
                           "5"
                           0.75))
           (fbm snoise4D
                4
                (vec4 (* 3 pos) ~(fxrand 1000))
                "5"
                0.8))))))

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
    raymarch-chunk
    gradient-chunk
    simplex-3d-chunk
    gabor-noise-chunk
    sigmoid-chunk
    fbm-chunk
    rand-chunk
    rand-sphere-chunk
    axis-rotation-chunk
    x-rotation-matrix-chunk
    y-rotation-matrix-chunk
    z-rotation-matrix-chunk
    chunk/voronoise-3d-chunk
    chunk/twistX-chunk
    chunk/twistY-chunk
    chunk/subtraction-stair-chunk
    sdf/onion-chunk
    (ambient-occlusion-chunk 'sdf c/ao-dist c/ao-samples c/ao-power)
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
                  polar
                  {([vec2 float float float float] vec2)
                   ([pos reps sm correction displacement]
                    (*= reps 0.5)
                    (=float k (length pos))
                    (=float x (* (asin (* (sin (* (atan pos.x pos.y)
                                                  reps))
                                          (- 1 sm)))
                                 k))
                    (=float ds (* k reps))
                    (=float y (mix ds
                                   (- (* 2 ds)
                                      (sqrt (+ (* x x)
                                               (* ds ds))))
                                   correction))
                    (vec2 (/ x reps)
                          (- (/ y reps)
                             displacement)))}
                  gb
                  {([vec3] float)
                   ([x]
                    (gaborNoise 3
                                ~fxrand
                                ~(u/genv (inc (fxrand-int 10))
                                         (* (fxrand 0.1 0.25)
                                            (Math/pow 10 (fxrand))))
                                x))}
                  gb2
                  {([vec3] float)
                   ([x]
                    (gaborNoise 3
                                ~fxrand
                                ~(u/genv (inc (fxrand-int 10))
                                         (* (fxrand 0.1 0.25)
                                            (Math/pow 10 (fxrand))))
                                x))}
                  gb3
                  {([vec3] float)
                   ([x]
                    (gaborNoise 3
                                ~fxrand
                                ~(u/genv (inc (fxrand-int 10))
                                         (* (fxrand 0.1 0.25)
                                            (Math/pow 10 (fxrand))))
                                x))}
                  fVoronoi
                  {([vec3] float)
                   ([pos]
                    (=vec2 vor (.xy (voronoise3D (* 5 pos))))
                    (distance vor.x vor.y))}
                  sdf
                  {([vec3] float)
                   ~(cons '[pos]
                          '((=int iters ~c/plane-iters)
                            (=float d 1024)
                            ("for(int i = 1; i <= iters; ++i)"
                             (=vec3 rot-pos (* pos
                                               (axisRoationMatrix (randSphere
                                                                   (* 100 (vec3 (* 3 (fract (* (float i)
                                                                                               (float i)
                                                                                               ~(fxrand))))
                                                                                (rand (vec2 (float i)
                                                                                            (* (float i) (- 0 (pow ~(fxrand) (* (float i)
                                                                                                                                (float i)))))))
                                                                                (rand (vec2 (* (float i)
                                                                                               (float i)
                                                                                               (float i))
                                                                                            (fract (* 0.333 (float i))))))))
                                                                  (* ~u/TAU (rand (vec2 (pow 2 (float i))
                                                                                        (rand (vec2 (mod (* 0.1 (float i)) ~(fxrand))
                                                                                                    (- 0 (float i))))))))))
                             (=float planes (sdBox rot-pos
                                                   (vec3 0)
                                                   (if (== (% i (* iters (int (floor (rand (vec2 (* (float i) ~(+ 250 (fxrand 1000)))
                                                                                                 (* (float i) ~(+ 250 (fxrand 1000)))))))))
                                                           ~(str (fxrand-int c/plane-iters)))
                                                     (if (== (% i (* iters (int (floor (rand (vec2 (* (float i) ~(+ 250 (fxrand 1000)))
                                                                                                   (* (float i) ~(+ 250 (fxrand 1000)))))))))
                                                             ~(str (fxrand-int c/plane-iters)))
                                                       (vec3 ~c/plane-size 1 1)
                                                       (vec3 1 ~c/plane-size 1))
                                                     (vec3 1 1 ~c/plane-size))))
                             (= d (min d planes)))
                            (=float shape ~(if (fxchance 0.5)
                                             '(sdBox pos (vec3 0 0.25 -0.15) (vec3 0.5))
                                             '(sdSphere pos (vec3 0 0.3 -0.3) 0.55)))
                            (=float pedastal ~(if (fxchance 0.5)
                                                c/slab
                                                c/round-slab))
                            (min (+ (smoothSubtraction d
                                                       shape
                                                       ~(fxrand 0.001 0.075))
                                    (* ~(fxrand 0.01 0.2)
                                       (smoothstep ~(- 0 (fxrand 0.5))
                                                   1.25
                                                   ~(let [n (fxrand)]
                                                      (cond
                                                        (< n 0.333) 'pos.y
                                                        (< n 0.666) 'pos.x
                                                        :else '(* -1 pos.x))))
                                       (smoothstep -0.1 0.001 shape)
                                       (-> ~(fxchoice {'(fbm gb
                                                             3
                                                             (+ (* ~(fxrand 0.5 1) pos)
                                                                (* 0.1 (fbm gb2
                                                                            3
                                                                            (+ (* ~(fxrand 0.25 0.5) pos)
                                                                               (* 0.1 (fbm gb3
                                                                                           3
                                                                                           (* ~(fxrand 0.1 0.3) pos)
                                                                                           "5"
                                                                                           0.25)))
                                                                            "5"
                                                                            0.5)))
                                                             "5"
                                                             0.75)
                                                       1
                                                       '(gaborNoise 3
                                                                    ~fxrand
                                                                    ~(u/genv (inc (fxrand-int 4))
                                                                             (Math/pow 8 (fxrand)))
                                                                    (+ pos (* 0.5
                                                                              (gaborNoise 3
                                                                                          ~fxrand
                                                                                          ~(u/genv (inc (fxrand-int 4))
                                                                                                   (Math/pow 4 (fxrand)))
                                                                                          (+ pos (* 0.5
                                                                                                    (gaborNoise 3
                                                                                                                ~fxrand
                                                                                                                ~(u/genv (inc (fxrand-int 4))
                                                                                                                         (Math/pow 2 (fxrand)))
                                                                                                                pos)))))))
                                                       1
                                                       '(gaborNoise 3
                                                                    ~fxrand
                                                                    ~(u/genv (inc (fxrand-int 4))
                                                                             (Math/pow 3 (fxrand)))
                                                                    (+ pos (* 0.5
                                                                              (gaborNoise 3
                                                                                          ~fxrand
                                                                                          ~(u/genv (inc (fxrand-int 4))
                                                                                                   (Math/pow 6 (fxrand)))
                                                                                          pos))))
                                                       1
                                                       '(-> (gaborNoise 3
                                                                        ~fxrand
                                                                        ~(u/genv (inc (fxrand-int 4))
                                                                                 (Math/pow 7 (fxrand)))
                                                                        (+ pos (* 0.5 (gaborNoise 3
                                                                                                  ~fxrand
                                                                                                  ~(u/genv (inc (fxrand-int 4))
                                                                                                           (Math/pow 5 (fxrand)))
                                                                                                  pos))))
                                                            sigmoid
                                                            (* 2)
                                                            (- 1))
                                                       1})
                                           (* 2)
                                           sigmoid
                                           (* 2)
                                           (- 1))))
                                 (+ pedastal
                                    (* ~(fxrand 0.01 0.025)
                                       (smoothstep -0.1 0.01 pedastal)
                                       ~(fxchoice {'(-> (gaborNoise 3
                                                                    ~fxrand
                                                                    ~(u/genv (inc (fxrand-int 4))
                                                                             (Math/pow 15 (fxrand)))
                                                                    (+ pos (* 0.5 (gaborNoise 3
                                                                                              ~fxrand
                                                                                              ~(u/genv (inc (fxrand-int 4))
                                                                                                       (Math/pow 10 (fxrand)))
                                                                                              pos))))
                                                        sigmoid
                                                        (* 2)
                                                        (- 1)) 1
                                                   '(snoise3D (* pos ~(fxrand-int 4 10))) 1}))))))}
                  }
      :main ((=vec2 pos (getPos))

                    ;setup camera
             (=vec3 cam-pos (vec3 0 0 -2))
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
             (=float ao 1)


             (=float distance (raymarch sdf
                                        ray
                                        10
                                        {:step-size 0.1
                                         :termination-threshold 0.001}))
                    ;do distance estimation if object hit
             ("if" (> distance 0)
                   (=vec3 surfacePos (+ ray.pos (* ray.dir distance)))
                   (=vec3 surfaceNorm (findGradient 3
                                                    sdf
                                                    0.001
                                                    surfacePos))
                   (= diff (max 0 (dot light-dir surfaceNorm)))
                   (= ao (occlusion surfacePos surfaceNorm))
                   (=float white-noise (* 0.01 (rand (* pos ~(fxrand 200 800)))))

                   (= col (-> surfaceNorm
                              normalize
                              (* 0.5)
                              (+ 0.5))))

              ;output 
             (= fragColor (-> col
                              (vec4 (clamp (* ao ~c/diffusion-power ~c/light-scale)
                                           0
                                           ~c/light-max))
                              (* ~(long u32-max))
                              uvec4)))})))

(def background-field-frag
  (u/unquotable
   (iglu->glsl
    header
    gradient-chunk
    simplex-2d-chunk
    '{:uniforms {size vec2}
      :outputs {fragColor uvec4}
      :functions {getCircles
                  {([vec2] float)
                   ([pos]
                    ~c/circle-expr)}
                  rot
                  {([float] mat2)
                   ([angle]
                    (=float s (sin angle))
                    (=float c (cos angle))
                    (mat2 c (- 0 s) s c))}}
      :main ((=vec2 pos (/ gl_FragCoord.xy size))
             (=vec2 circles (findGradient 2
                                          getCircles
                                          0.1
                                          pos))
             (=vec2 noi (* circles (rot (* ~(fxrand 0.5 (* 0.5 Math/PI))
                                           (snoise2D (* pos ~(fxrand 1.5 3)))))))
             (= fragColor (-> noi 
                              (* 0.5)
                              (+ 0.5)
                              (vec4 1 0)
                              (* ~(Math/floor u32-max))
                              uvec4)))})))

(def init-frag-source
  (u/unquotable
   (iglu->glsl
    header
    rand-macro-chunk
    '{:uniforms {size vec2}
      :outputs {fragColor uvec2}
      :main ((=vec2 pos (/ gl_FragCoord.xy size))
             (= fragColor (uvec2 (* (vec2 (:rand pos)
                                          (:rand pos)) ~u32-max))))})))