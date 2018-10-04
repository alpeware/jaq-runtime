(ns jaq.ui.landing-page)

(defn clone-repl []
  (let [opts {:type "button" :value "CLONE ME"}
        opts (merge opts #?(:clj {} :cljs (merge opts {:on-click (fn [e] (js/alert "not yet implemented"))})))]
    [:div
     [:input.button.is-primary opts]]))

#_(

   (merge {} #?@(:clj {} :cljs (merge opts {:on-click (fn [e] (js/alert "not yet implemented"))})))
   )

(defn landing-page []
  [:div.main
   [:section.section
    [:div.container
     [:h1.title "JAQ - Your Cloud REPL"]
     [:p "Welcome to Cloud hacking."]
     [:p
      "Our hope is to help democratize Web development and usher in the next "
      "generation of Web apps."]
     (clone-repl)]]])

#_(
   (jaq.app/render!)

   (-> js/document
       (.getElementsByTagName "input")
       (array-seq)
       (first)
       (.click))

   (in-ns 'jaq.ui.landing-page)
   (ns jaq.ui.landing-page)
   *ns*

   (.. js/window -location)
   (-> js/window
       (.-location)
       (.reload true))

   (jaq.app/render!)
   (jaq.browser/stop!)
   (require 'jaq.repl)
   )
