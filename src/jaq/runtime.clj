(ns jaq.runtime
  (:require
   [bidi.ring :refer [make-handler]]
   [jaq.repl :refer [new-clj-session eval-clj]]
   [jaq.services.deferred :refer [defer defer-fn]]
   [jaq.services.storage :as storage]
   [jaq.services.management :as management]
   [jaq.services.util :as util :refer [remote! repl-server credentials]]
   [ring.util.response :as response]
   [ring.util.servlet :refer [defservice]]
   [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
   [ring.middleware.params :refer [wrap-params]]
   [ring.middleware.edn :refer [wrap-edn-params]]
   [clojure.tools.deps.alpha.util.maven :as mvn]
   [clojure.tools.deps.alpha :refer [resolve-deps]]
   [clojure.java.io :as io]
   [taoensso.timbre :as timbre
    :refer [log  trace  debug  info  warn  error  fatal  report
            logf tracef debugf infof warnf errorf fatalf reportf
            spy get-env]]))

#_(

   *ns*
   (in-ns 'jaq.runtime)
   (require '[clojure.tools.deps.alpha.util.maven :as mvn])
   (resolve-deps {:deps {'org.clojure/clojure {:mvn/version "1.8.0"}
                         'org.clojure/core.memoize {:mvn/version "0.5.8"}}
                  :mvn/repos mvn/standard-repos
                  :mvn/local-repo "/tmp"} nil)

   (def s (->
           (clojure.java.io/file "/tmp/.m2")
           (file-seq)))
   (->> s (take 10))
   (->> s
        (filter (fn [e] (-> e .isFile)))
        (filter (fn [e] (-> e .toPath .getFileName (clojure.string/ends-with? ".jar"))))
        (count))
   (->> s count)

   (-> (io/resource "jaq/runtime.clj")
       (.getPath)
       (clojure.string/split #"runtime.clj")
       (first)
       (io/file)
       (file-seq)
       (count))

   javax.servlet.http.HttpServlet
   *compile-path*
   (io/make-parents "/tmp/classes/foo.bar")
   (binding [*compile-path* "/tmp/classes"]
     (compile (symbol "jaq.runtime")))

   (-> (io/file "/tmp/classes")
       (file-seq))

   (System/getProperty "user.home")
   (storage/default-bucket)
   (com.google.auth.appengine.AppEngineCredentials/getApplicationDefault)
   (->> (com.google.appengine.api.appidentity.AppIdentityServiceFactory/getAppIdentityService)
        ((fn [e] (.getAccessToken e jaq.services.auth/cloud-scopes)))
       ((fn [e]
          {:access-token (.getAccessToken e)
           :expires-in (-> e (.getExpirationTime) (.getTime))}))
       (reset! util/credentials))

   (->>
    (management/services)
    (map (fn [e] (-> e :serviceConfig :title)))
    )
   (management/enable "storage-api.googleapis.com" "alpeware-jaq-runtime")
   (storage/buckets "alpeware-jaq-runtime")

   (def deps
     {:paths ["/tmp"]
      :mvn/repos mvn/standard-repos
      :mvn/local-repo "/tmp/.m2"
      :deps {'org.clojure/clojure {:mvn/version "1.9.0"}
             'org.clojure/core.async {:mvn/version "0.4.474"}
             'org.clojure/tools.deps.alpha {:mvn/version "0.5.398"}
             'org.clojure/data.csv {:mvn/version "0.1.4"}
             'org.clojure/math.combinatorics {:mvn/version "0.1.4"}

             'com.alpeware/jaq-services {:mvn/version "0.1.0-SNAPSHOT"}
             'com.alpeware/jaq-runtime {:mvn/version "0.1.0-SNAPSHOT"}

             'bidi {:mvn/version "2.1.3"}
             'hiccup {:mvn/version "1.0.5"}
             'garden {:mvn/version "1.3.3"}

             'com.taoensso/timbre  {:mvn/version "4.10.0"}

             'reagent {:mvn/version "0.8.0-alpha2"}
             'reagent-utils  {:mvn/version "0.2.1"}
             'cljs-ajax  {:mvn/version "0.7.3"}

             'com.google.appengine/appengine-api-1.0-sdk  {:mvn/version "1.9.60"}
             'com.google.appengine/appengine-api-labs  {:mvn/version "1.9.60"}
             'com.google.appengine/appengine-remote-api  {:mvn/version "1.9.60"}
             'com.google.appengine/appengine-tools-sdk  {:mvn/version "1.9.60"}}})

   (defmethod defer-fn ::deps [{:keys [deps]}]
     (debug ::deps)
     (resolve-deps deps nil)
     )

   (defer {:fn ::deps :deps deps})

   )

(def repl-sessions (atom {}))

(defmulti listener-fn :fn)
(defmethod listener-fn :default [_])

(defn init []
  (listener-fn {:fn :init}))

(defn destroy []
  (listener-fn {:fn :destroy}))

(defn repl-handler [{:keys [body params] :as request}]
  (let [{:keys [form device-id repl-type broadcast]} params
        _ (debug "device id" device-id)
        repl-type (read-string repl-type)
        broadcast (read-string broadcast)
        _ (debug repl-type)
        [session eval-fn] (when (= :clj repl-type)
                            [(get @repl-sessions device-id (jaq.repl/new-clj-session))
                             jaq.repl/eval-clj])
        evaled (try
                 (eval-fn session form)
                 (catch Throwable t t))
        result (str (:value evaled) "\n" (:ns evaled) "=> ")]

    (when (= :clj repl-type)
      (swap! repl-sessions assoc device-id session))

    (debug "edn" params evaled)
    (response/content-type
     (response/response result)
     "application/edn")))

(def routes ["" [["/repl" :repl]]])

(def handlers {:repl #'repl-handler})

(def handler
  (make-handler routes #'handlers))

(def app
  (-> #'handler
      (wrap-defaults (assoc-in site-defaults [:security :anti-forgery] false))
      (wrap-edn-params)))

(defservice app)
