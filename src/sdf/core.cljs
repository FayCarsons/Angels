(ns sdf.core
  (:require [sprog.util :as u]
            [sdf.shaders :as s]
            [sdf.config :as c]
            [clojure.string :as str]
            [sprog.input.keyboard :refer [add-key-callback]]
            [sprog.webgl.core :refer [start-sprog!
                                      update-sprog-state!
                                      sprog-context]]
            [sprog.dom.canvas :refer [maximize-gl-canvas
                                      canvas-resolution
                                      save-image
                                      resize-gl-canvas]]
            [sprog.webgl.shaders :refer [run-purefrag-shader!
                                         run-shaders!]]
            [sprog.webgl.textures :refer [create-tex]]
            [fxrng.rng :refer [fxrand]]))



(defn set-resolution [gl]
  (let [manual-resolution (vec
                           (doall
                            (map parse-long
                                 (str/split (.get (js/URLSearchParams.
                                                   js/window.location.search)
                                                  "resolution") 
                                            "x"))))]
    (if (every? some? manual-resolution)
      (do (resize-gl-canvas gl
                            manual-resolution)
          manual-resolution)
      (do (maximize-gl-canvas gl)
          (canvas-resolution gl)))))

(defn init-sketch! [{:keys [gl
                            sketch-pos-texs] :as state}]
  (run-purefrag-shader! gl
                        s/init-frag-source
                        c/sketch-particle-amount
                        {:floats {"size" c/sketch-particle-amount}}
                        {:target (first sketch-pos-texs)})
  state)

(defn march! [{:keys [gl 
                      resolution 
                      normal-map-tex
                      seed-tex] :as state}]
   (run-purefrag-shader! gl
                          s/norm-field-frag-source
                          resolution
                          {:floats {"size" resolution
                                    "time" (u/seconds-since-startup)}
                           :textures {"seedTex" seed-tex}}
                          {:target normal-map-tex})
    state)

(defn init-background! [{:keys [gl
                                background-pos-texs
                                background-field-tex
                                normal-map-tex
                                background-field-copy] :as state}]
  (run-purefrag-shader! gl
                        s/init-frag-source
                        c/background-particle-amount
                        {:floats {"size" c/background-particle-amount}}
                        {:target (first background-pos-texs)})
  (run-purefrag-shader! gl
                        s/background-field-frag
                        c/background-field-resolution
                        {:floats {"size" c/background-field-resolution}}
                        {:target background-field-tex})
  (when c/special?
    (run-purefrag-shader! gl
                          s/copy-source
                          c/background-field-resolution
                          {:floats {"size" c/background-field-resolution}
                           :textures {"tex" background-field-tex}}
                          {:target background-field-copy})
    (run-purefrag-shader! gl
                          s/patchwork-frag
                          c/background-field-resolution
                          {:floats {"size" c/background-field-resolution}
                           :textures {"circleField" background-field-copy
                                      "normalMap" normal-map-tex}}
                          {:target background-field-tex}))
  state)

(defn update-background! [{:keys [gl
                                  resolution
                                  frame
                                  background-pos-texs
                                  background-field-tex
                                  background-trail-texs] :as state}]
  (run-purefrag-shader! gl
                        s/logic-frag-source
                        c/background-particle-amount
                        {:floats {"size" c/background-particle-amount
                                  "now" (u/seconds-since-startup)
                                  "speed" c/background-particle-speed
                                  "randomizationChance" (if (zero? (mod frame
                                                                        c/background-reset-interval))
                                                          (Math/floor 0)
                                                          c/background-randomization-chance)}
                         :textures {"locationTex" (first background-pos-texs)
                                    "fieldTex" background-field-tex}
                         :ints {"sketch" (long 0)}}
                        {:target (second background-pos-texs)})

  (run-shaders! gl
                [s/particle-vert-source s/particle-frag-source]
                resolution
                {:textures {"particleTex" (second background-pos-texs)
                            "field" background-field-tex}
                 :floats {"size" resolution
                          "radius" c/background-radius}
                 :ints {"sketch" (long 0)}}
                {}
                0
                (* 6
                   c/sqrt-background-particle-amount
                   c/sqrt-background-particle-amount)
                {:target (first background-trail-texs)})
  (run-purefrag-shader! gl
                        s/trail-frag-source
                        resolution
                        {:floats {"size" resolution
                                  "fade" c/background-fade}
                         :textures {"tex" (first background-trail-texs)}}
                        {:target (second background-trail-texs)})
  (-> state
      (update :background-pos-texs reverse)
      (update :background-trail-texs reverse)))

(defn update-sketch! [{:keys [gl
                              resolution
                              sketch-pos-texs
                              normal-map-tex
                              sketch-trail-texs
                              sketch-trail-copy] :as state}]
  (run-purefrag-shader! gl
                        s/logic-frag-source
                        c/sketch-particle-amount
                        {:floats {"size" c/sketch-particle-amount
                                  "now" (u/seconds-since-startup)
                                  "speed" c/sketch-speed
                                  "randomizationChance" c/sketch-randomization-chance}
                         :textures {"locationTex" (first sketch-pos-texs)
                                    "fieldTex" normal-map-tex}
                         :ints {"sketch" 1}}
                        {:target (second sketch-pos-texs)})

  (run-purefrag-shader! gl
                        s/copy-source
                        resolution
                        {:floats {"size" resolution}
                         :textures {"tex" (first sketch-trail-texs)}}
                        {:target sketch-trail-copy})

  (run-shaders! gl
                [s/particle-vert-source s/particle-frag-source]
                resolution
                {:textures {"particleTex" (second sketch-pos-texs)
                            "field" normal-map-tex
                            "targetTex" sketch-trail-copy}
                 :floats {"size" resolution
                          "radius" c/sketch-radius}
                 :ints {"sketch" (long 1)}}
                {}
                0
                (* 6
                   c/sqrt-sketch-particle-amount
                   c/sqrt-sketch-particle-amount)
                {:target (first sketch-trail-texs)})
  (run-purefrag-shader! gl
                        s/trail-frag-source
                        resolution
                        {:floats {"size" resolution
                                  "fade" c/sketch-fade}
                         :textures {"tex" (first sketch-trail-texs)}}
                        {:target (second sketch-trail-texs)})
  (-> state
      (update :sketch-pos-texs reverse)
      (update :sketch-trail-texs reverse)))

(defn render! [{:keys [gl
                       resolution
                       sketch-trail-texs
                       background-trail-texs
                       save-image?] :as state}]
  (run-purefrag-shader! gl
                        s/render-source
                        resolution
                        {:floats {"size" resolution}
                         :textures {"tex" (first sketch-trail-texs)
                                    "backgroundTex" (first background-trail-texs)}})

  (if save-image?
    (do (save-image gl.canvas "angels")
        (assoc state :save-image? false))
    state))

(defn resize! [{:keys [gl] :as state}]
  (let [resolution (canvas-resolution gl)]
    (-> state
        (merge {:resolution resolution
                :background-trail-texs (u/gen 2 (create-tex gl :u32 resolution))
                :normal-map-tex (create-tex gl :u32 resolution)
                :sketch-trail-texs (u/gen 2 (create-tex gl :u32 resolution))
                :sketch-trail-copy (create-tex gl :u32 resolution)
                :frame 0
                :resize? false})
        march!
        init-background!
        init-sketch!)))

(defn update-page! [{:keys [frame
                            resize?] :as state}]
  (if (< frame c/frame-limit)
    (-> state
        update-background!
        update-sketch!
        render!
        (update :frame inc))
    (do (js/fxpreview)
        (if resize?
          (resize! state)
          (render! state)))))

(defn init-page! [gl]
  (let [resolution (set-resolution gl)]
    (-> {:gl gl
         :frame 0
         :resize? false
         :save-image? false
         :resolution resolution

         :seed-tex
         (create-tex gl
                     :u32
                     c/seed-tex-dimensions
                     {:data (js/Uint32Array.
                             (u/gen (* 4 (apply * c/seed-tex-dimensions))
                                    (* (fxrand)
                                       s/u32-max)))})

         :background-field-tex (create-tex gl :u32 c/background-field-resolution)
         :background-field-copy (when c/special? (create-tex gl :u32 c/background-field-resolution))
         :background-pos-texs (u/gen 2 (create-tex gl :u32 c/background-particle-amount {:channels 2}))
         :background-trail-texs (u/gen 2 (create-tex gl :u32 resolution))

         :normal-map-tex (create-tex gl :u32 resolution)
         :sketch-pos-texs (u/gen 2 (create-tex gl :u32 c/sketch-particle-amount {:channels 2}))
         :sketch-trail-texs (u/gen 2 (create-tex gl :u32 resolution))
         :sketch-trail-copy (create-tex gl :u32 resolution)}
        march!
        init-background!
        init-sketch!)))

(defn init []
  (add-key-callback "r" (fn []
                          (update-sprog-state!
                           #(assoc % :resize? true))))
  (add-key-callback "s" (fn []
                          (update-sprog-state!
                           #(assoc % :save-image? true))))
  (start-sprog! init-page! update-page!))

(defn ^:dev/after-load restart! []
  (js/document.body.removeChild (.-canvas (sprog-context)))
  (init))

(defn pre-init []
  (js/eval "javascript:(function(){var script=document.createElement('script');script.onload=function(){var stats=new Stats();document.body.appendChild(stats.dom);requestAnimationFrame(function loop(){stats.update();requestAnimationFrame(loop)});};script.src='//mrdoob.github.io/stats.js/build/stats.min.js';document.head.appendChild(script);})()")
  (js/window.addEventListener "load" (fn [_] (init))))
