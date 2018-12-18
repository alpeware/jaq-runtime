(ns jaq.app
  (:require
   [jaq.repl :as repl]
   [jaq.browser :refer [start! state]]
   [cljsjs.react]
   [cljs.core.async :refer [put! chan <! >!]
    :include-macros true]
   [jaq.ui.landing-page :as landing-page]
   [reagent.core :as r]
   [reagent.cookies :as cookies]))

(defn render! []
  (r/render
   [landing-page/landing-page]
   (.getElementById js/document "app")))

(defn init-device-id []
  (-> js/window
      (.-location)
      (.-hash)
      (clojure.string/replace #"#" "")
      (as-> e (when-not (empty? e)
                (swap! state assoc :device-id e)))))

(defn ^:export main [& args]
  (init-device-id)
  (render!)
  (start!))

#_(-> js/window
    (.addEventListener "DOMContentLoaded" (fn [] (.log js/console :foo))))

(main)
#_(
   *ns*
   (require 'jaq.app)
   (ns jaq.app)
   (ns jaq.browser)
   (init-device-id)
   @state
   (+ 1 1)

   (-> js/window
       (.-location)
       (.-hash)
       (clojure.string/replace #"#" "")
       (as-> e (when-not (empty? e) e)))

   (-> "#foo"
       (clojure.string/replace #"#" "")
       (as-> e (when-not (empty? e) e)))

   @jaq.browser/state
   (ns jaq.app)
   (landing-page)
   (js/alert :foo)
   (render!)
   (jaq.browser/stop!)
   )
