(ns jaq.ui.landing-page)

(defn clone-repl []
  (let [opts {:type "button" :value "CLONE ME"}
        opts (merge opts #?(:clj {} :cljs (merge opts {:on-click (fn [e] (js/alert "not yet implemented"))})))]
    [:div
     [:input.button.is-primary opts]]))

(defn landing-page []
  [:div.main
   [:section.section
    [:div.container
     [:h1.title "JAQ - Your Cloud REPL"]
     [:p "Welcome to Cloud hacking."]
     (clone-repl)]]])

#_(

   *ns*
   (slurp "/tmp/war/WEB-INF/classes/jaq/ui/landing_page.cljc")

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
