(ns sdf.core
  (:require [sprog.util :as u] 
            [sdf.shaders :as s]
            [sdf.config :as c]
            [sprog.input.keyboard :refer [add-key-callback]]
            [sprog.webgl.core :refer [start-sprog!
                                      stop-sprog!
                                      merge-sprog-state!
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
  #_(maximize-gl-canvas gl {:square? false})
  (resize-canvas gl.canvas [1800 1800]))

(defn update-particles! [{:keys [gl
                                 location-texs
                                 norm-tex
                                 trail-texs] :as state}]
  (let [resolution (canvas-resolution gl)] 
    (run-purefrag-shader! gl
                          s/logic-frag-source
                          [c/particle-amount
                           c/particle-amount]
                          {:floats {"size" [c/particle-amount
                                            c/particle-amount]
                                    "now" (u/seconds-since-startup)}
                           :textures {"locationTex" (first location-texs)
                                      "fieldTex" norm-tex}}
                          {:target (second location-texs)})
    
    (run-shaders! gl
                  [s/particle-vert-source s/particle-frag-source]
                  resolution
                  {:textures {"particleTex" (second location-texs)
                              "field" norm-tex}
                   :floats {"size" resolution
                            "radius" c/radius}}
                  {}
                  0
                  (* 6 c/particle-amount c/particle-amount)
                  {:target (first trail-texs)})
    (run-purefrag-shader! gl
                          s/trail-frag-source
                          resolution
                          {:floats {"size" resolution}
                           :textures {"tex" (first trail-texs)}}
                          {:target (second trail-texs)})
    (-> state
        (update :location-texs reverse)
        (update :trail-texs reverse))))

(defn render! [{:keys [gl
                       trail-texs
                       background-tex] :as state}]
  (let [resolution (canvas-resolution gl)]
    (run-purefrag-shader! gl
                          s/render-source
                          resolution
                          {:floats {"size" resolution}
                           :textures {"tex" (first trail-texs)
                                      "noiseTex" background-tex}})
    state))


(defn update-page! [{:keys [gl
                            frame
                            rendered?] :as state}]
  (expand-canvas gl)
  #_(when rendered? 
    (js/alert "rendered!")
    (stop-sprog!))
  (if (or c/unlimit?
          (<= frame c/frame-limit))
    (-> state
        update-particles!
        render!
        (update :frame inc))
    (assoc state :rendered? true)))

(defn init-page! [gl] 
  (expand-canvas gl)
  (let [resolution (canvas-resolution gl)
        norm-tex (create-tex gl :u32 resolution)
        background-tex (create-tex gl :u32 resolution)
        location-texs (u/gen 2 (create-tex gl :u32 c/particle-amount {:channels 2}))
        trail-texs (u/gen 2 (create-tex gl :u32 (u/log resolution)))] 
    (run-purefrag-shader! gl
                          s/init-frag-source
                          c/particle-amount
                          {:floats {"size" [c/particle-amount
                                            c/particle-amount]}}
                          {:target (first location-texs)})
    (run-purefrag-shader! gl
                          s/background-source
                          resolution 
                          {:floats {"size" resolution}}
                          {:target background-tex})
    (run-purefrag-shader! gl
                          s/norm-field-frag-source
                          resolution
                          {:floats {"size" resolution
                                    "time" (u/seconds-since-startup)}}
                          {:target norm-tex}) 
    {:frame 0
     :rendered? false
     :norm-tex norm-tex
     :background-tex background-tex
     :location-texs location-texs
     :trail-texs trail-texs}))

(defn init []
  (start-sprog! init-page! update-page!))

(defn ^:dev/after-load restart! []
  (js/document.body.removeChild (.-canvas (sprog-context)))
  (init))

(defn pre-init []
  (js/window.addEventListener "load" (fn [_] (init))))
