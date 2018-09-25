(ns jaq.repl
  (:require
   [clojure.main :as main]
   [clojure.walk :refer [keywordize-keys]]
   [hiccup.page :refer [html5 include-css include-js]]
   [ring.util.response :as response]
   [jaq.services.storage :as storage]
   [jaq.services.util :as util]
   [taoensso.timbre :as timbre
    :refer [log  trace  debug  info  warn  error  fatal  report]])
  (:import
   [java.net URLEncoder]))

;;; State
(def repl-sessions (atom {}))

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

(defn eval-clj [session input]
  {:pre [(instance? String input)
         (instance? clojure.lang.Atom session)]}
  (let [bindings (:bindings @session)
        result (atom nil)]
    (main/with-bindings
      (with-bindings bindings
        (try
          (let [form (read-string input)
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

;;; Handler
(defn save-file [file-name body]
  (let [bucket (storage/default-bucket)
        path file-name
        content-type "text/plain"]
    (storage/put-simple bucket path content-type body)))

(defn get-file [file-name]
  (let [bucket (storage/default-bucket)
        path (-> file-name
                 (URLEncoder/encode "UTF-8"))]
    (storage/get-file bucket path)))

(defn repl-handler [{:keys [body params] :as request}]
  (let [{:keys [form device-id repl-type broadcast repl-token]} (keywordize-keys params)
        _ (debug request)
        _ (debug "form" form)
        _ (debug "device id" device-id)
        _ (debug repl-type)
        repl-type (read-string repl-type)
        broadcast (read-string broadcast)
        [session eval-fn] (when (= :clj repl-type)
                            [(get @repl-sessions device-id (new-clj-session))
                             eval-clj])
        evaled (if (= repl-token (:JAQ_REPL_TOKEN util/env))
                   (try
                     (eval-fn session form)
                    (catch Throwable t t))
                   {:value "Unauthorized!" :ns "clojure.core"})
        ;;result (str (:value evaled) "\n" (:ns evaled) "=> ")
        result (str (:value evaled) "\n" (:ns evaled) "=> ")
        ]

    (when (= :clj repl-type)
      (swap! repl-sessions assoc device-id session))

    (debug "edn" params evaled)
    {:status 200 :body result}))

(defn as-html [html]
  (response/content-type
   (response/response html)
   "text/html"))

(defn landing-page []
  (as-html
   (html5
    [:head
     [:title "JAQ Runtime"]]
    [:body
     [:h1 "JAQ Runtime REPL"]
     [:p "Introducing a Clojure Cloud REPL for the Google App Engine."]])))

(defn index-handler
  [request]
  (landing-page))

#_(

   *ns*
   (in-ns 'jaq.repl)

   (slurp "https://alpeware-jaq-runtime.appspot.com/public/foo.txt")


   )
