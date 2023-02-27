(ns sdf.core
  (:require [sprog.util :as u] 
            [sdf.shaders :as s]
            [sdf.config :as c]
            [sprog.input.keyboard :refer [add-key-callback]]
            [sprog.webgl.core :refer [start-sprog! 
                                      sprog-state
                                      update-sprog-state! 
                                      sprog-context]]
            [sprog.dom.canvas :refer [maximize-gl-canvas 
                                      resize-canvas
                                      canvas-resolution
                                      save-image]] 
            [sprog.webgl.shaders :refer [run-purefrag-shader!
                                         run-shaders!]]
            [sprog.webgl.textures :refer [create-tex
                                          html-image-tex]]))

(defn expand-canvas [gl]
  (maximize-gl-canvas gl {:square? false})
  #_(resize-canvas gl.canvas [1400 1400]))

(defn init-sketch! [{:keys [gl 
                            sketch-pos-texs] :as state}]
  (run-purefrag-shader! gl
                        s/init-frag-source
                        c/sketch-particle-amount
                        {:floats {"size" c/sketch-particle-amount}}
                        {:target (first sketch-pos-texs)})
  state)

(defn march! [{:keys [gl normal-map-tex] :as state}]
  (let [resolution (canvas-resolution gl)]
    (run-purefrag-shader! gl
                          s/norm-field-frag-source
                          resolution
                          {:floats {"size" resolution
                                    "time" (u/seconds-since-startup)}}
                          {:target normal-map-tex})
    state))

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
    (let [resolution (canvas-resolution gl)]
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
                            {:target background-field-tex})))
  state)

(defn update-background! [{:keys [gl
                                  frame
                                  background-pos-texs
                                  background-field-tex
                                  background-trail-texs] :as state}]
  (let [resolution (canvas-resolution gl)]
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
        (update :background-trail-texs reverse))))

(defn update-sketch! [{:keys [gl
                              sketch-pos-texs
                              normal-map-tex
                              sketch-trail-texs
                              sketch-trail-copy] :as state}]
  (let [resolution (canvas-resolution gl)] 
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
                            "radius" c/radius}
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
        (update :sketch-trail-texs reverse))))

(defn render! [{:keys [gl
                       sketch-trail-texs
                       background-trail-texs
                       save-image?] :as state}]
  (let [resolution (canvas-resolution gl)]
    (run-purefrag-shader! gl
                          s/render-source
                          resolution
                          {:floats {"size" resolution}
                           :textures {"tex" (first sketch-trail-texs) 
                                      "backgroundTex" (first background-trail-texs)}})
    
    (if save-image?
      (do (save-image gl.canvas "angels")
          (assoc state :save-image? false))
      state)))

(defn resize! [{:keys [gl] :as state}]
  (let [resolution (canvas-resolution gl)]
    (-> state
        (merge {:background-trail-texs (u/gen 2 (create-tex gl :u32 resolution))
                :normal-map-tex (create-tex gl :u32 resolution)
                :sketch-trail-texs (u/gen 2 (create-tex gl :u32 resolution))
                :sketch-trail-copy (create-tex gl :u32 resolution)
                :frame 0
                :resize? false})
        march!
        init-background!
        init-sketch!)))

(defn update-page! [{:keys [gl
                            frame
                            resize?] :as state}]
  (expand-canvas gl) 
  (if (<= frame c/frame-limit)
    (-> state
        update-background!
        update-sketch!
        render!
        (update :frame inc))
    (if resize?
      (resize! state)
      (render! state))))

(defn init-page! [gl] 
  (expand-canvas gl)
  (let [resolution (canvas-resolution gl)] 
    (-> {:gl gl
         :frame 0 
         :resize? false
         :save-image? false
         
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
  #_(js/eval "javascript:(function(){var script=document.createElement('script');script.onload=function(){var stats=new Stats();document.body.appendChild(stats.dom);requestAnimationFrame(function loop(){stats.update();requestAnimationFrame(loop)});};script.src='//mrdoob.github.io/stats.js/build/stats.min.js';document.head.appendChild(script);})()")
  (js/window.addEventListener "load" (fn [_] (init))))
