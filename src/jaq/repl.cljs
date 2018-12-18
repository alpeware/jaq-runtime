(ns jaq.repl
  (:refer-clojure :exclude [compile])
  (:require
   [cljs.reader :refer [read-string]]
   [cljs.js :as cljs]
   [cljs.analyzer :as ana]
   [cljs.env :as env]
   [ajax.core :refer [GET POST]]
   [taoensso.timbre :as timbre :refer [log info warn error debug]
    :include-macros true]
   [cljs.core.async :refer [put! chan <! >! timeout close!
                            onto-chan alts! go-loop go]
    :include-macros true]
   [jaq.browser :refer [enqueue api-fn state in-ch start!]]
   [goog.object :as gobj]))

(set! *warn-on-infer* true)

(enable-console-print!)

;; TODO(alpeware): Do we need this?
(def loaded-libs (atom #{}))

(defn init-runtime [st]
  (let [libs (set
              (js->clj
               (gobj/getKeys (.. js/goog -dependencies_ -nameToPath))))
        ns-map (into {} (map (fn [e] {(symbol e) {:name (symbol e)}}) libs))]
    (reset! loaded-libs libs)
    (set! *loaded-libs* libs)
    (set! (.-require_ js/goog) (.-require js/goog))
    (set! (.-require js/goog)
         (fn [name reload]
           (when (or (not (contains? *loaded-libs* name)) reload)
             (set! *loaded-libs* (conj (or *loaded-libs* #{}) name))
             (.require_ js/goog name))))
    (swap! st assoc :cljs.analyzer/namespaces ns-map)))

(defn skip-load?
  "Indicates namespaces that we either don't need to load,
  shouldn't load, or cannot load (owing to unresolved
  technical issues)."
  [name macros]
  ((if macros
     #{'cljs.core}
     #{'goog.object
       'goog.string
       'goog.string.StringBuffer
       'goog.array
       'cljs.core
       'cljs.env
       ;;'jaq.boot
       ;;'cljs.tools.reader
       ;;'clojure.walk
       }) name))
;;;

(defn get-lang [content-type]
  (condp #(clojure.string/includes? (clojure.string/lower-case (str %2)) %1) content-type
    "clojurescript" :clj
    "clojure" :clj
    "javascript" :js
    :clj))

#_(
   (+ 1 1))

(defn load-fn [{:keys [name macros path]} cb]
  (let [;;p (str "/public/js/dev/out/" (ana/ns->relpath name))
        p (str "/public/js/dev/out/" path)
        ;;path (str "/tmp/out/" (ana/munge-path name))
        ]
    (println p path name macros)
    (if-not (skip-load? name macros)
      (GET p {:params {:macros (= true macros) :path path}
              :response-format
              {:read (fn [xhrio]
                       (let [body (.getResponse xhrio)
                             content-type (.getResponseHeader xhrio "content-type")]
                         [body content-type]))
               :description "raw"}
              :handler
              (fn [[src content-type]]
                (println content-type)
                (cb {:lang (get-lang content-type) :source (str src)}))
              :error-handler
              (fn [e]
                (warn "Error loading cljs source" path e)
                (cb {:lang :clj :source "" :file path}))})
      (cb {:lang :clj :source ""}))))

(def st (cljs/empty-state))

#_(comment
    (ns jaq.boot)
    (-> @st :cljs.analyzer/namespaces keys)

    (-> @cljs.env/*compiler* :cljs.analyzer/namespaces println)

    ((fn [] cljs.analyzer/*cljs-ns*))

    ((fn [] @cljs.js/*loaded*))

    (swap! cljs.js/*loaded* conj (into #{} (-> @cljs.env/*compiler* :cljs.analyzer/namespaces keys)))


    (let [l (set '(frontpageping.app cljs.user cljs.core jaq.boot))]
      (reset! cljs.js/*loaded* l))

    (require 'frontpageping.app)

)

(set! cljs/*eval-fn* cljs/js-eval)
(set! cljs/*load-fn* load-fn)
(set! ana/*cljs-ns* 'jaq.boot)

(set! js/goog.global.COMPILE false)
(set! js/goog.isProvided_ (fn [e] false))

(defn optimize [s]
  (.compile ^js/window (clj->js {:jsCode [:src s]})))

(def eval-ns (atom 'cljs.user))

#_(defmethod api-fn :eval-result [{:keys [val ns]}]
  (println "processing update" val ns)
  (reset! eval-ns ns)
  (swap! state assoc-in
         [:repl :val]
         (str (get-in @state [:repl :val]) ns "=> " val "\n")))

(defmethod api-fn :evaled [{:keys [result]}]
  (let [_ (println "evaled " result)
        {:keys [value ns]} (try
                             (read-string result)
                             (catch js/Error e
                               {:value "nil" :ns "cljs.core"}))]
    #_(println "evaled" result)
    (swap! state assoc-in
           [:repl :val]
           (str (get-in @state [:repl :val]) ns "=> " value "\n" ns "=> "))
    #_(swap! state assoc-in [:output] cnt)))

(defn eval-str [form & [ch]]
  (let [_ (println "channel" ch)
        result-ch (or ch in-ch)]
    (try
      (cljs/eval-str st form
                     ana/*cljs-ns*
                     {:verbose true
                      :def-emits-var true
                      :*compiler* (set! env/*compiler* st)
                      :static-fns false
                      :context :expr
                      :ns @eval-ns
                      :source-map false}
                     (fn [{:keys [ns value error] :as result}]
                       (println "eval" ns value error result-ch)
                       (when-not error
                         (reset! eval-ns ns))
                       (if-not error
                         (put! result-ch {:fn :evaled
                                          :result {:value value :ns ns}})
                         (put! result-ch {:fn :evaled
                                          :result {:value error :ns ns}}))))
      (catch js/Error e
        (put! result-ch {:fn :evaled :result {:value e :ns "cljs.core"}})))))

(defmethod api-fn :eval [{:keys [device-id code callback-id]}]
  (let [result-ch (chan)]
    (go
      #_(println "channel" result-ch)
      (eval-str code result-ch)
      (let [{:keys [result]} (<! result-ch)
            {:keys [value ns]} result]
        #_(println "evaled" val ns)
        (enqueue {:fn :callback :device-id device-id :callback-id callback-id
                  :result {:value (pr-str value) :ns ns}})
        #_(>! in-ch {:fn :eval-result :val value :ns ns})))))

(defn ^:export bootstrap []
  (timbre/set-level! :debug)
  (go
    (start!)
    (eval-str "(require 'frontpageping.app :reload-all) #_(frontpageping.app/main)")
    (<! (timeout 5000))
    (eval-str "(frontpageping.app/main)")))

(bootstrap)
#_(init-runtime st)
