(ns jaq.repl
  (:refer-clojure :exclude [load-string])
  (:require
   [clojure.main :as main]
   [clojure.walk :refer [keywordize-keys]]
   [clojure.core.async :refer [chan <! >! go go-loop close! alts!! timeout]]
   [hiccup.page :refer [html5 include-css include-js]]
   [ring.util.response :as response]
   [jaq.ui.landing-page]
   [jaq.services.datastore :as datastore]
   [jaq.services.deferred :as deferred]
   [jaq.services.memcache :as memcache]
   [jaq.services.storage :as storage]
   [jaq.services.util :as util]
   [taoensso.timbre :as timbre
    :refer [log  trace  debug  info  warn  error  fatal  report]])
  (:import
   [java.net URLEncoder]
   [java.util UUID]))

;;; State
(def repl-sessions (atom {}))
(def repl-cljs-sessions (atom {}))

(def session-entity :core/sessions)
(def session-id 1)
(def session-key (str session-entity "/" session-id))
(defonce session-store (datastore/create-store session-entity))
(def callbacks (atom {:noop (fn [_])}))

;;;
(def all-sessions (atom {:id session-id :v 1 :devices []}))

(defn get-sessions [key]
  @all-sessions)

(defmethod deferred/defer-fn :open-session [{:keys [device-id]}]
  (let [sessions (get-sessions session-key)
        devices (:devices sessions)]
    (when-not (contains? (set devices) device-id)
      (swap! all-sessions update :devices conj device-id))))

(defmethod deferred/defer-fn :close-session [{:keys [device-id]}]
  (swap! all-sessions update :devices (partial remove #(= device-id %))))

#_(defn get-sessions [key]
  (let [m (memcache/peek key)
        sessions (get m key)]
    (if sessions
      sessions
      (let [session-entity {:id session-id :v 1 :devices []}
            sessions (try
                       (datastore/fetch session-store session-id 1)
                       (catch Exception e
                         (datastore/store-all! session-store [session-entity])
                         session-entity))]
        (memcache/push {key sessions})
        sessions))))

#_(
   (in-ns 'jaq.repl)
   (def all-sessions (atom {:id session-id :v 1 :devices []}))

   (defn get-sessions [key]
     @all-sessions)

   (defmethod deferred/defer-fn :open-session [{:keys [device-id]}]
     (let [sessions (get-sessions session-key)
           devices (:devices sessions)]
       (when-not (contains? (set devices) device-id)
         (swap! all-sessions update :devices conj device-id))))

   )

#_(defn bootstrap []
  (get-sessions session-key))

#_(defmethod deferred/defer-fn :open-session [{:keys [device-id]}]
  (let [sessions (get-sessions session-key)
        devices (:devices sessions)]
    (when-not (contains? (set devices) device-id)
      (memcache/push {session-key
                      (datastore/update! session-store session-id update :devices conj device-id)}))))

(defn close-session [device-id]
  (deferred/defer {:fn :close-session :device-id device-id}))

#_(defmethod deferred/defer-fn :close-session [{:keys [device-id]}]
  (let [sessions (datastore/update! session-store session-id update :devices (partial remove #(= device-id %)))]
    (memcache/push {session-key sessions})))

;;;;

(defmethod deferred/defer-fn :callback [{:keys [device-id callback-id result]}]
  (let [_ (debug "callback" callback-id "result" result)
        callback-fn (get @callbacks callback-id)]
    (debug "callback" callback-id "result" result)
    (when-not (= callback-id :noop)
      (callback-fn {:result result :device-id device-id})
      (swap! callbacks dissoc callback-id))))

;;; CLJ REPL
(defn new-clj-session []
  (atom {:bindings (main/with-bindings
                     (get-thread-bindings))}))
(defn repl-caught
  "Default :caught hook for repl"
  [e]
  (let [ex (main/repl-exception e)
        tr (.getStackTrace ex)
        el (when-not (zero? (count tr)) (aget tr 0))]
    (str (-> ex class .getSimpleName)
         " " (.getMessage ex) " "
         (when-not (instance? clojure.lang.Compiler$CompilerException ex)
           (str " " (if el (main/stack-element-str el) "[trace missing]"))))))

(def ^:dynamic *reader-opts* {:read-cond :allow})

;;; TODO(alpeware): support for reader conditionals
(defn load-string [s]
  (clojure.core/load-string s))

#_(
   *ns*
   (in-ns 'jaq.repl)
   )

(defn eval-clj [session input]
  {:pre [(instance? String input)
         (instance? clojure.lang.Atom session)]}
  (let [bindings (:bindings @session)
        result (atom nil)]
    (main/with-bindings
      (with-bindings bindings
        (try
          (let [form (read-string *reader-opts* input)
                value (eval form)]
            (set! *3 *2)
            (set! *2 *1)
            (set! *1 value)
            (reset! result {:value (pr-str value) :ns (symbol (str *ns*))}))
          (catch Throwable e
            (set! *e e)
            (reset! result {:value (repl-caught e) :ns (symbol (str *ns*))}))
          (finally (swap! session assoc :bindings (get-thread-bindings))))))
    @result))

;; cljs
(defn new-cljs-session [device-id]
  (debug "new cljs session" device-id)
  (let [session (atom {:bindings (get-thread-bindings)
                       :device-id device-id})]
    #_(cljs-env-setup session device-id)
    session))

(defn broadcast-eval-js [{:keys [device-id broadcast]} form]
 (let [_ (debug "eval js" device-id form broadcast)
       broadcast (boolean broadcast)
       original-device-id device-id
       original-callback (atom {})
       repl-eval-timeout (* 1000 30) ;; 30s
       repl-timeout (or repl-eval-timeout 18000)
       ;;callback-id (str (UUID/randomUUID))
       channels (atom [])
       callback-fn-factory (fn []
                             (let [out (chan 1)
                                   uuid (str (UUID/randomUUID))]
                               [out
                                uuid
                                (fn [{:keys [result device-id]}]
                                  (go
                                    (>! out {:result result :device-id device-id})
                                    (close! out)))]))]
   (if (true? broadcast)
     (let [devices (->> (get-sessions session-key) :devices)]
       (->> devices
            (map (fn [device-id]
                   (let [[ch callback-id callback-fn] (callback-fn-factory)]
                     (debug "callback" callback-id device-id)
                     (if (= device-id original-device-id)
                       (reset! original-callback {:callback-id callback-id
                                                  :chan ch})
                       (swap! channels conj {:out ch :device-id device-id}))
                     (swap! callbacks assoc callback-id callback-fn)
                     (deferred/add {:fn :eval :code form :callback-id callback-id
                                    :device-id device-id} device-id))))
            (doall))
       (let [{:keys [callback-id]} @original-callback]
         #_(swap! broadcast-callbacks assoc callback-id channels)
         #_(deferred/defer {:fn :broadcast-evaled :callback-id callback-id :repl-timeout repl-timeout}))
       (debug "done sending calbacks"))
     (let [[ch callback-id callback-fn] (callback-fn-factory)]
       #_(swap! channels conj ch)
       (reset! original-callback {:callback-id callback-id
                                  :chan ch})
       (swap! callbacks assoc callback-id callback-fn)
       (deferred/add {:fn :eval :code form :callback-id callback-id
                      :device-id device-id} device-id)))

   #_(debug "waiting for" device-id "to eval" js)
   (let [;;outs (async/merge @channels (count @channels))
         out (:chan @original-callback)
         other (-> @channels first :out)
         ;;_ (debug "channel" (<!! other))
         _ (debug "waiting for results" @original-callback)
         [v ch] (alts!! [out (timeout repl-timeout)])]
     (debug "result original" v)
     (if (= ch out)
       (:result v)
       {:value "Eval timed out!" :ns "cljs.core"}))))

(defn eval-cljs [session input]
  {:pre [(instance? String input)
         (instance? clojure.lang.Atom session)]}
  (broadcast-eval-js @session input))

;;; Handler
(defn save-file [file-name body]
  (let [bucket (storage/default-bucket)
        path file-name
        content-type "text/plain"]
    (storage/put-simple bucket path content-type body)))

(defn get-file [file-name]
  (let [bucket (storage/default-bucket)
        path file-name]
    (storage/get-file bucket path)))

(defn repl-handler [{:keys [body params] :as request}]
  (let [{:keys [form device-id repl-type broadcast repl-token]} (keywordize-keys params)
        _ (debug request)
        _ (debug "form" form)
        _ (debug "device id" device-id)
        _ (debug repl-type)
        repl-type (read-string repl-type)
        broadcast (read-string broadcast)
        [session eval-fn] (if (= :clj repl-type)
                            [(get @repl-sessions device-id (new-clj-session))
                             eval-clj]
                            [(or (get @repl-cljs-sessions device-id) (new-cljs-session device-id))
                             eval-cljs])
        _ (swap! session assoc-in [:broadcast] broadcast)
        evaled (if (= repl-token (:JAQ_REPL_TOKEN util/env))
                   (try
                     (eval-fn session form)
                    (catch Throwable t t))
                   {:value "Unauthorized!" :ns "clojure.core"})
        ;;result (str (:value evaled) "\n" (:ns evaled) "=> ")
        result (str (:value evaled) "\n" (:ns evaled) "=> ")]

    (if (= :clj repl-type)
      (swap! repl-sessions assoc device-id session)
      (swap! repl-cljs-sessions assoc device-id session))

    (debug "edn" params evaled)
    {:status 200 :body result}))

(defn as-html [html]
  (response/content-type
   (response/response html)
   "text/html"))

(defn landing-page [app]
  (as-html
   (html5
    [:head
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
     (include-css "https://cdnjs.cloudflare.com/ajax/libs/bulma/0.7.1/css/bulma.min.css")
     [:title "JAQ Runtime"]]
    [:body
     [:div#app app]
     [:script "var CLOSURE_DEFINES = {'goog.ENABLE_CHROME_APP_SAFE_SCRIPT_LOADING': true};"
      "var STATE = '{:token 123}';"]
     (include-js "/out/goog/base.js")
     (include-js "/out/app.js")
     (include-js "/public/app.js")])))

#_(
   *ns*
   (in-ns 'jaq.repl)

   (get-file "src/jaq/browser.cljs")

   (storage/default-bucket)

   (storage/get-file (storage/default-bucket) "src/jaq/browser.cljs")

   )
(defn index-handler
  [request]
  (landing-page (jaq.ui.landing-page/landing-page)))

(defmethod deferred/defer-fn :eval [{:keys [device-id form repl-type broadcast] :as m}]
  (let [[session eval-fn] (if (= :clj repl-type)
                            [(get @repl-sessions device-id (jaq.repl/new-clj-session))
                             jaq.repl/eval-clj]
                            [(or (get @repl-cljs-sessions device-id) (jaq.repl/new-cljs-session device-id))
                             jaq.repl/eval-cljs])
        _ (swap! session assoc-in [:broadcast] broadcast)
        result (try
                 (eval-fn session form)
                 (catch Throwable t t))]
    (debug "eval" device-id form result)
    ;; want to return as a string otherwise cljs edn reader might get confused ex. bidi routes
    (deferred/add {:fn :evaled :result (pr-str result) :device-id device-id} device-id)
    #_(debug "repl-type" repl-type)
    (if (= :clj repl-type)
      (swap! jaq.repl/repl-sessions assoc device-id session)
      (swap! jaq.repl/repl-cljs-sessions assoc device-id session))))

#_(

   *ns*
   (in-ns 'jaq.repl)

   (slurp "https://alpeware-jaq-runtime.appspot.com/public/foo.txt")

   (require 'cljs.compiler.api)
   jdk.nashorn.api.scripting.NashornException
   (require
    '[cljs.env :as env]
    '[cljs.analyzer :as ana]
    '[cljs.compiler :as comp]
    '[cljs.closure :as cljsc]
    '[cljs.repl :as repl]
    '[cljs.repl.rhino :as rhino]
    '[cljs.repl.nashorn :as nashorn])

   (def repl-env (nashorn/repl-env))
   (def cenv (env/default-compiler-env))
   (def aenv (assoc-in (ana/empty-env) [:ns :name] 'cljs.user))

   (env/with-compiler-env cenv
     (repl/eval-cljs repl-env aenv '(def foo 2)))

   (-> @cenv :cljs.analyzer/namespaces)

   ((set (keys repl/default-special-fns)) (first '(in-ns 'jaq.foo)))

   (env/with-compiler-env cenv
     (binding [ana/*cljs-ns* ana/*cljs-ns*]
       (let [form '(in-ns 'jaq.foo)]
         ((get repl/default-special-fns (first form)) repl-env aenv form {}))))

   (type repl-env)

   (binding [ana/*cljs-ns* 'cljs.user]
     (env/with-compiler-env cenv
       (comp/with-core-cljs {}
         (fn []
           (repl/source-fn aenv 'cljs.core/first)))))


   (clojure.java.io/make-parents "/tmp/out/core.cljs")
   (def opts {:output-dir "/tmp/out"
              :optimizations :none
              :cache-analysis true
              :source-map true})

   (env/with-compiler-env cenv
     (repl/setup repl-env (merge (repl/-repl-options repl-env) (cljsc/add-implicit-options opts)
                                 {:repl-requires '[[cljs.repl :refer-macros [source doc find-doc apropos dir pst]]
                                                   [cljs.pprint :refer [pprint] :refer-macros [pp]]]
                                  :eval repl/eval-cljs
                                  :bind-err true})))

   (env/with-compiler-env cenv
     (repl/evaluate-form repl-env aenv "jaq repl" '(js/alert :foo)))

   (env/with-compiler-env cenv

     (repl/evaluate-form repl-env aenv "jaq repl" '(def js/alert (fn [e] e))))

   (env/with-compiler-env cenv
     (binding [ana/*cljs-ns* "jaq.foo"]
       (repl/evaluate-form repl-env aenv "jaq repl" '(ns jaq.foo))))

   (env/with-compiler-env cenv
     (binding [ana/*cljs-ns* "jaq.foo"]
       (repl/evaluate-form repl-env aenv "jaq repl" '(ns jaq.foo))))


   (env/with-compiler-env cenv
     (let [form '(js/alert :foo)
           ast (ana/analyze repl-env form nil opts)]
       (comp/emit-str ast)))

   env/*compiler*

   (->> (clojure.java.io/file "/tmp/out")
        (file-seq))

   )
