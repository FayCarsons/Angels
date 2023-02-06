(ns sdf.core
  (:require [sprog.util :as u] 
            [sdf.shaders :as s]
            [sdf.config :as c]
            [sprog.input.keyboard :refer [add-key-callback]]
            [sprog.webgl.core :refer [start-sprog!
                                      update-sprog-state!
                                      sprog-context
                                      sprog-state]]
            [sprog.dom.canvas :refer [maximize-gl-canvas 
                                      canvas-resolution
                                      save-image]] 
            [sprog.webgl.shaders :refer [run-purefrag-shader!
                                         run-shaders!]]
            [sprog.webgl.textures :refer [create-tex
                                          html-image-tex]]
            ))

(def init-state  {:frame 0
                  :screenshot? false})
(def u32-max (dec (Math/pow 2 32)))
(def screenshot-atom (atom false))


(defn expand-canvas [gl]
  (maximize-gl-canvas gl {:square? false}))

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
                       frame
                       trail-texs
                       norm-tex
                       location-texs] :as state}]
  (let [resolution (canvas-resolution gl)]
    (run-purefrag-shader! gl
                          s/render-source
                          resolution
                          {:floats {"size" resolution}
                           :textures {"tex" (first trail-texs)
                                      "field" norm-tex
                                      "location" (second location-texs)}})
    state))


(defn update-page! [{:keys [gl
                            frame
                            screenshot?] :as state}]
  (expand-canvas gl) 
  (when screenshot?
    (let [letters "kjfgbaoiuho387gtp9utyn t09ynv9uynv9yniouhnpwdijhn0u09ynpioyamlkjhnlf"]
      (save-image gl.canvas (apply str (take 4 (shuffle (seq letters))))))) 
  
  (if (< frame 60)
    (-> state
        update-particles!
        render!
        (assoc :screenshot? false)
        (update :frame inc))
    (merge state {:screenshot? false})))

(defn init-page! [gl] 
  (expand-canvas gl)
  (let [resolution (canvas-resolution gl)
        norm-tex (create-tex gl :u32 resolution)
        location-texs (u/gen 2 (create-tex gl :u32 c/particle-amount {:channels 2}))
        trail-texs (u/gen 2 (create-tex gl :u32 resolution))] 
    (run-purefrag-shader! gl
                          s/init-frag-source
                          c/particle-amount
                          {:floats {"size" [c/particle-amount
                                            c/particle-amount]}}
                          {:target (first location-texs)})
    (run-purefrag-shader! gl
                          s/norm-field-frag-source
                          resolution
                          {:floats {"size" resolution
                                    "time" (u/seconds-since-startup)}}
                          {:target norm-tex})
    (add-key-callback "s"
                      (fn []
                        (update-sprog-state! #(merge % {:screenshot? true}))))
    {:frame 0
     :norm-tex norm-tex
     :location-texs location-texs
     :trail-texs trail-texs}))

(defn init []
  (start-sprog! init-page! update-page!))

(defn ^:dev/after-load restart! []
  (js/document.body.removeChild (.-canvas (sprog-context)))
  (init))

(defn pre-init []
  (js/window.addEventListener "load" (fn [_] (init))))
