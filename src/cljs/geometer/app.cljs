(ns geometer.app
  (:require [thi.ng.geom.core                :as g]
            [thi.ng.geom.core.vector         :as v :refer [vec2 vec3]]
            [thi.ng.geom.core.matrix         :as mat :refer [M44]]
            [thi.ng.geom.core.utils          :as gu]
            [thi.ng.geom.rect                :refer [rect]]
            [thi.ng.geom.webgl.core          :as gl]
            [thi.ng.geom.webgl.animator      :refer [animate]]
            [thi.ng.geom.webgl.buffers       :as buf]
            [thi.ng.geom.webgl.shaders       :as sh]
            [thi.ng.geom.webgl.shaders.basic :as basic]
            [thi.ng.geom.webgl.shaders.phong :as phong]
            [thi.ng.math.core                :as m]
            [thi.ng.typedarrays.core         :refer [float32]]
            [thi.ng.geom.polygon :as poly]
            [thi.ng.geom.basicmesh :refer [basic-mesh]]
            [geometer.shapes  :as shapes]
            [geometer.lsystem :as lsystem]
            [geometer.genetic :as genetic]))

(enable-console-print!)

;; we use defonce for the webgl context, mouse tracking atoms and
;; model they won't be re-initialed when the namespace is reloaded
(defonce gl (gl/gl-context "main"))
(defonce view-rect (atom (gl/get-viewport-rect gl)))

(defonce mouse-x (atom 0))
(defonce mouse-y (atom 0))

(defonce projection (atom nil))
(defonce model (atom nil))

(defonce viewpoint (atom (g/translate M44 0 0 -70)))

(defonce shader (sh/make-shader-from-spec gl phong/shader-spec))

;; (defn- vary-color
;;   "Add random variance to an (r,g,b,a) sequence of colors values."
;;   [cols]
;;   (map #(+ (m/random -0.1 0.1) %) cols))

;; (defn- set-model!
;;   "Our model is a `mesh` that will be rendered by the animation loop started in the start function."
;;   [mesh]
;;   (reset! model
;;           (let [vertices (->> (g/center mesh)
;;                               (g/faces)
;;                               (map gu/tessellate-3)
;;                               flatten)
;;                 colors (flatten (repeatedly (/ (count vertices) 3)
;;                                             #(vary-color [0.064 0.63 0.30 0.9])))
;;                 gl (gl/gl-context "main")]
;;             (-> {:attribs {:position {:data (float32 vertices) :size 3}
;;                            :color    {:data (float32 colors) :size 4}}
;;                  :uniforms     {:proj (gl/perspective 45 @view-rect 0.1 1200.0)
;;                                 :view (mat/look-at (v/vec3 0 0 0) (v/vec3) v/V3Y)}
;;                  :mode         gl/triangles
;;                  :num-vertices (/ (count vertices) 3)
;;                  :shader       (->> (basic/make-shader-spec-3d true)
;;                                     (sh/make-shader-from-spec gl))}
;;                 (buf/make-attribute-buffers-in-spec gl gl/static-draw)))))

(defn- set-model!
  "Our model is a `mesh` that will be rendered by the animation loop started in the start function."
  [mesh]
  (reset! model
          (-> (g/center mesh)
              (gl/as-webgl-buffer-spec {})
              (buf/make-attribute-buffers-in-spec gl gl/static-draw)
              (assoc :shader shader)
              (update-in [:uniforms] merge
                         {:proj          @projection
                          :view          (mat/look-at (vec3 0 0 2) (vec3) (vec3 0 1 0))
                          :lightPos      (vec3 2 0 5)
                          :ambientCol    0x181818
                          :diffuseCol    0x10a04c
                          :specularCol   0xaaaaaa
                          :shininess     100
                          :wrap          1
                          :useBlinnPhong true}))))

;; (apply str (map #(format "%x" (int (* 255 %))) [0.064 0.63 0.30]))
;; "10a04c"

(defn ^:export new-model
  "Selects a new model from a set of possibilities (or a cube if we don't recognise the request). This requires some setTimeout silliness for the browser to show a status panel."
  [kind]
  (let [status (.getElementById js/document "status")]
    (set! (.-innerHTML status) (str "Generating new " kind " model..."))
    (set! (.-className status) "visible")
    (js/setTimeout
     #(do
        (case kind
          "algae"   (set-model! (lsystem/algae))
          "koch"    (set-model! (lsystem/koch))
          "toroid"  (set-model! (lsystem/toroid))
          "novelty" (set-model! (genetic/novelty-search))
          "disc"    (set-model! (shapes/disc))
          "sphere"  (set-model! (shapes/sphere))
          (set-model! (shapes/cube)))
        (set! (.-className status) "invisible"))
     20)))

(defn- keypress-handler [e]
  (let [k (.-keyCode e)]
    (case k
      119 (reset! viewpoint (g/translate @viewpoint 0 0 2))  ;; w = forward
      115 (reset! viewpoint (g/translate @viewpoint 0 0 -2)) ;; s = backward
      (print k))))

(defn resize-handler
  []
  (let [w (.-innerWidth js/window)
        h (.-innerHeight js/window)
        c (.getElementById js/document "main")]
    (set! (.-width c) w)
    (set! (.-height c) h)
    (reset! view-rect (rect 0 0 w h))
    (reset! projection (gl/perspective 45 @view-rect 0.1 200.0))))

(defn- update-pos [e]
  (reset! mouse-x (* 0.01 (- (.-clientX e) (/ (.-innerWidth js/window) 2))))
  (reset! mouse-y (* 0.01 (- (.-clientY e) (/ (.-innerHeight js/window) 2)))))

(defn ^:export start
  "This function is called when 'index.html' loads. We use it to kick off mouse tracking, a keyboard handler and the animation loop."
  []
  (.addEventListener js/document "keypress" keypress-handler)
  (.addEventListener js/document "mousemove" update-pos)
  (.addEventListener js/window "touchmove"
                     #(do (.preventDefault %)
                          (update-pos (aget (.-touches %) 0))))
  (.addEventListener js/window "resize" resize-handler)
  (resize-handler)

  ;; initialize with a cube
  (set-model! (shapes/cube))

  (animate
   (fn [[t frame]]
     (gl/set-viewport gl @view-rect)
     (gl/clear-color-buffer gl 0 0 0 0) ;; 0 opacity, so we see the bg gradient
     (gl/clear-depth-buffer gl 1)
     (gl/enable gl gl/depth-test)
     ;; (phong/draw
     ;;  gl (-> model
     ;;         (assoc-in [:uniforms :model] tx2)
     ;;         (assoc-in [:uniforms :diffuseCol] 0x33ff80)))
     (phong/draw
      gl (update @model :uniforms merge
                 {:proj  @projection
                  :model (-> @viewpoint
                             (g/translate 0 0 0)
                             (g/rotate-x @mouse-y)
                             (g/rotate-y @mouse-x))}))

     true)))
