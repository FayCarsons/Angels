(ns sdf.shaders
  (:require clojure.walk
            [sprog.util :as u]
            [sdf.chunks :as chunk]
            [sdf.config :as c]
            [sprog.iglu.chunks.noise :refer [rand-chunk
                                             simplex-3d-chunk
                                             simplex-2d-chunk
                                             fbm-chunk
                                             gabor-noise-chunk]]
            [sprog.iglu.chunks.raytracing :refer [raymarch-chunk
                                                  perspective-camera-chunk]]
            [sprog.iglu.chunks.sdf :as sdf]
            [sprog.iglu.chunks.transformations :refer [axis-rotation-chunk]]
            [sprog.iglu.chunks.misc :refer [sigmoid-chunk
                                            pos-chunk 
                                            gradient-chunk
                                            bilinear-usampler-chunk]] 
            [sprog.iglu.chunks.colors :refer [mix-oklab-chunk]]

            [sprog.iglu.core :refer [iglu->glsl
                                     combine-chunks]]
            [sprog.tools.math :refer [rand-n-sphere-point]]
            [fxrng.rng :refer [fxrand
                               fxchoice
                               fxchance
                               fxrand-int
                               fxrand-nth]]))

(def u32-max (Math/floor (dec (Math/pow 2 32))))

(def header '{:version "300 es"
              :precision {float highp
                          int highp
                          sampler2D highp
                          usampler2D highp}})

(def rand-macro-chunk
  (u/unquotable
   (combine-chunks
    rand-chunk 
    {:macros {:rand
              (fn [seed]
                '(rand (+ (vec2 ~(fxrand -100 100)
                                ~(fxrand -100 100))
                          (* ~seed
                             (vec2 ~(fxrand 100 1000)
                                   ~(fxrand 100 1000))))))}})))

(def render-source
  (u/unquotable
   (iglu->glsl
    pos-chunk
    bilinear-usampler-chunk
    simplex-2d-chunk
    sigmoid-chunk
    rand-chunk
    mix-oklab-chunk
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
                                    (textureBilinear
                                     (+ pos (* ~(fxrand (if c/special? 0.003 0.002) 
                                                        (if c/special? 0.005 0.0035))
                                               (vec2 (rand (* pos ~(fxrand 200 400)))
                                                       (rand (* pos ~(fxrand 200 400)))))))
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
             (= fragColor (vec4 col 1)))})))

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
             (= newRadius radius)
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
                  (> (rand (* (+ pos particlePos)
                              400))
                     (- randomizationChance
                        field.w)))

            (uvec2 (* (vec2
                       (rand (+ (* pos ~(fxrand 1000)) time))
                       (rand (+ (* pos.yx ~(fxrand 1000)) time))) ~u32-max))

            (uvec2 (* (vec2 (+ particlePos (* field.xy speed)))
                      ~u32-max)))))})))

(def norm-field-frag-source
  (u/unquotable 
   (iglu->glsl
    header
    pos-chunk
    perspective-camera-chunk
    sdf/sphere-sdf-chunk
    sdf/box-sdf-chunk 
    sdf/torus-sdf-chunk
    chunk/smooth-intersection-chunk
    chunk/smooth-subtraction-chunk
    sdf/smooth-union-chunk
    raymarch-chunk
    gradient-chunk
    simplex-3d-chunk
    gabor-noise-chunk
    sigmoid-chunk
    fbm-chunk
    rand-chunk
    rand-macro-chunk 
    axis-rotation-chunk
    chunk/voronoise-3d-chunk 
    chunk/subtraction-stair-chunk
    '{:uniforms {size vec2
                 time float
                 seedTex usampler2D}
      :outputs {fragColor uvec4}
      :functions
      {rot
       {([float] mat2)
        ([angle]
         (=float s (sin angle))
         (=float c (cos angle))
         (mat2 c (- 0 s) s c))}
       gb
       {([vec3] float)
        ([x]
         (gaborNoise 3
                     ~fxrand
                     ~(u/genv (inc (fxrand-int 4))
                              (Math/pow 4 (fxrand)))
                     x))}
       gb2
       {([vec3] float)
        ([x]
        (gaborNoise 3
                    ~fxrand
                    ~(u/genv (inc (fxrand-int 4))
                             (Math/pow 4 (fxrand)))
                    x))}
       fVoronoi
       {([vec3] float)
        ([pos]
         (=vec2 vor (.xy (voronoise3D pos)))
         (distance vor.x vor.y))}
       sdf
       {([vec3] float)
        ~(cons
          '[pos]
          '((=float d ~c/plane-expr)
            (=float shape ~(if (fxchance 0.5)
                             '(sdBox pos (vec3 0 0.25 -0.15) (vec3 0.5))
                             '(sdSphere pos (vec3 0 0.3 -0.3) 0.55)))
            (=float pedastal ~(if (fxchance 0.5)
                                c/slab
                                c/round-slab))
            (min (+ ~(if (fxchance 0.5)
                       '(subtractionStair shape
                                          d
                                          ~(fxrand 0.05 0.2)
                                          ~(inc (fxrand-int 3
                                                            8)))
                       '(smoothSubtraction d
                                           shape
                                           ~(fxrand 0.001 0.075)))
                   ~(if false #_(fxchance 0.2)
                     '(-> pos
                          (* ~(fxrand 1 5))
                          fVoronoi
                          (* ~(fxrand 0.05 0.15 0.75)))
                     '(* ~(fxrand 0.05 0.1 0.75)
                            (smoothstep ~(- 0 (fxrand 1))
                                        2
                                        ~(fxrand-nth ['pos.y
                                                      'pos.x
                                                      '(* -1 pos.x)]))
                            
                            (-> ~(fxrand-nth
                                  ['(* (fbm gb
                                            3
                                            (+ (* ~(fxrand 1 2) pos)
                                               (* 0.1
                                                  (fbm gb2
                                                       3
                                                       (* ~(fxrand 1 2) pos)
                                                       "5"
                                                       0.75)))
                                            "3"
                                            0.9)
                                       5)
                                   '(gaborNoise 3
                                                ~fxrand
                                                ~(u/genv (inc (fxrand-int 3))
                                                         (Math/pow 3 (fxrand)))
                                                (+ pos (* 0.5
                                                          (gaborNoise 3
                                                                      ~fxrand
                                                                      ~(u/genv (inc (fxrand-int 3))
                                                                               (Math/pow 6 (fxrand)))
                                                                      pos))))
                                   '(gaborNoise 3
                                                ~fxrand
                                                ~(u/genv (inc (fxrand-int 4))
                                                         (Math/pow 3 (fxrand)))
                                                (+ pos (* 0.5 (gaborNoise 3
                                                                          ~fxrand
                                                                          ~(u/genv (inc (fxrand-int 4))
                                                                                   (Math/pow 5 (fxrand)))
                                                                          pos))))])
                                (* 2)
                                sigmoid
                                (* 2)
                                (- 1)))))
                 (+ pedastal
                    ~(if (fxchance 0.15)
                       '(-> pos
                            (* (axisRoationMatrix (normalize ~(cons 'vec3
                                                                    (rand-n-sphere-point 3 fxrand)))
                                                  ~(fxrand u/TAU)))
                            (* ~(fxrand 2 7.5))
                            fVoronoi
                            (* ~(fxrand 0.05 0.15)))
                       '(* ~(fxrand 0.01 0.02)
                           ~(fxchoice {'(-> (gaborNoise 3
                                                        ~fxrand
                                                        ~(u/genv (inc (fxrand-int 3))
                                                                 (Math/pow 15 (fxrand)))
                                                        (+ pos (* 0.5 (gaborNoise 3
                                                                                  ~fxrand
                                                                                  ~(u/genv (inc (fxrand-int 4))
                                                                                           (Math/pow 10 (fxrand)))
                                                                                  pos))))
                                            sigmoid
                                            (* 2)
                                            (- 1))
                                       1
                                       '(snoise3D (* pos ~(fxrand 2 10)))
                                       1
                                       '0
                                       0.05})))))))}}
      :main ((=vec2 pos (getPos))
             
             (=vec3 cam-pos (vec3 0 0 -2))
             (=vec3 origin (vec3 0))
             (=vec3 light-pos ~c/light-pos)
             (=vec3 light-dir (normalize (- cam-pos light-pos)))
             
             (=Ray ray (cameraRay pos origin cam-pos 0.75)) 

             (=vec3 surfacePos (vec3 0))
             (=vec3 surfaceNorm (vec3 0))
             (=vec3 col (vec3 0))
             (=float diff 0)


             (=float distance (raymarch sdf
                                        ray
                                        15
                                        {:step-size 0.05
                                         :termination-threshold 0.001})) 
             ("if" (> distance 0)
                   (=vec3 surfacePos (+ ray.pos (* ray.dir distance)))
                   (=vec3 surfaceNorm (findGradient 3
                                                    sdf
                                                    0.001
                                                    surfacePos))
                   (= diff (max 0 (dot light-dir surfaceNorm)))
                   (=vec3 white-noise (* 0.01 (vec3 (:rand pos)
                                                    (:rand pos)
                                                    (:rand pos))))
                   (= col (-> surfaceNorm
                              (+ white-noise)
                              normalize
                              (* 0.5)
                              (+ 0.5))))
             (= fragColor (-> col
                              (vec4 (clamp (* ~c/diffusion-power ~c/light-scale)
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