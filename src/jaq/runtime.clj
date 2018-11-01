(ns jaq.runtime
  (:require
   [jaq.repl :refer [repl-handler index-handler]]
   [jaq.deploy :as deploy]
   [jaq.services.deferred :as deferred :refer [defer defer-fn]]
   [jaq.services.datastore :as datastore]
   [jaq.services.storage :as storage]
   [jaq.services.management :as management]
   [jaq.services.memcache :as memcache]
   [jaq.services.util :as util]
   [io.pedestal.http :as http]
   [io.pedestal.http.route :as route]
   [io.pedestal.http.body-params :as body-params]
   [ring.util.response :as ring-response]
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

   (with-redefs [util/*credentials-file* (io/resource ".credentials")])

   (->> (io/resource "jaq/runtime.clj")
        (slurp))

   (->> (io/resource ".credentials")
        (slurp)
        (clojure.edn/read-string)
        (reset! util/credentials))

   (swap! routes conj ["/baz" :get [`hello-world] :route-name :baz])

   (reset! storage/file-counter 0)
   @storage/file-counter

   (spit "/tmp/foo.txt" "foo bar")

   (in-ns 'jaq.runtime)
   (storage/put-large (storage/default-bucket) "/tmp/foo.txt" "/tmp" "tmp" {:callback storage/file-upload-done})

   (-> #'repl-handler meta :name)


   (def s (->
           (clojure.java.io/file "/tmp/.m2")
           (file-seq)))
   (->> s (take 10))
   (->> s
        (filter (fn [e] (-> e .isFile)))
        (filter (fn [e] (-> e .toPath .getFileName (clojure.string/ends-with? ".jar"))))
        (count))
   (->> s count)

   *compile-path*
   (io/make-parents "/tmp/classes/foo.bar")
   (binding [*compile-path* "/tmp/classes"]
     (compile (symbol "jaq.runtime")))

   (-> (io/file "/tmp/classes")
       (file-seq))

   (System/getProperty "user.home")
   (System/getProperties)
   (System/getenv)

   (->> (io/file "/base/java8_runtime")
        (file-seq)
        (filter (fn [e] (-> e (.getName) (clojure.string/ends-with? ".jar"))))
        (map (fn [e]
               (->> e
                    #_(.getName)
                    (java.util.jar.JarFile.)
                    (.entries)
                    (iterator-seq)
                    (map (fn [e] (.getName e)))
                    (count)
                    (conj [(.getName e)])))))

   (->> (io/file "/base/java8_runtime/java_runtime_launcher_ex")
        (.length))

   (->> (java.util.jar.JarFile. "/base/java8_runtime/runtime-impl.jar")
        (.entries)
        (iterator-seq)
        (map (fn [e] (.getName e)))
        #_(count))

   (->> com.google.apphosting.base.AppinfoPb$AppInfo
        clojure.reflect/reflect
        :members
        (filter #(contains? (:flags %) :public))
        clojure.pprint/print-table)

   (storage/default-bucket)

   *ns*
   (in-ns 'jaq.runtime)
   (->> (com.google.appengine.api.appidentity.AppIdentityServiceFactory/getAppIdentityService)
        ((fn [e] (.getAccessToken e jaq.services.auth/cloud-scopes)))
        ((fn [e]
           {:access-token (.getAccessToken e)
            :expires-in (-> e (.getExpirationTime) (.getTime))}))
        (reset! util/credentials))

   (->>
    (management/services)
    (map (fn [e] (-> e :serviceConfig :title))))

   (in-ns 'jaq.runtime)
   (management/enable "servicemanagement.googleapis.com" "alpeware-jaq-runtime")
   (management/enable "storage-api.googleapis.com" "alpeware-jaq-runtime")
   (management/enable "cloudtasks.googleapis.com" "alpeware-jaq-runtime")
   (management/enable "appengine.googleapis.com" "alpeware-jaq-runtime")
   (management/enable "cloudresourcemanager.googleapis.com" "alpeware-jaq-runtime")
   (management/enable "script.googleapis.com" "alpeware-jaq-runtime")
   (management/enable "pubsub.googleapis.com" "alpeware-jaq-runtime")

   (management/enable "memcache.googleapis.com" "alpeware-jaq-runtime")

   (storage/buckets "alpeware-jaq-runtime")
   (storage/list (storage/default-bucket))
   (storage/list "staging.alpeware-jaq-runtime.appspot.com")
   (->> (storage/objects (storage/default-bucket) {:prefix "src"})
        (map :name)
        (map (fn [e] (-> e (java.net.URLEncoder/encode "UTF-8")))))

   (storage/action :get [:b (storage/default-bucket) :o])
   (util/substitute [:b (storage/default-bucket) :o] storage/default-endpoint)

   (storage/default-bucket)

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

   *ns*
   (in-ns 'jaq.runtime)
   (defmethod defer-fn ::deps [{:keys [deps]}]
     (debug ::deps)
     #_(resolve-deps deps nil)
     )

   (defer {:fn ::deps :deps deps})

   (jaq.runtime/save-file "foo.txt" "foo bar baz")
   (jaq.runtime/get-file "foo/bar.txt")

   (java.net.URLEncoder/encode "src/foo/bar.txt" "UTF-8")

   )


(defmulti listener-fn :fn)
(defmethod listener-fn :default [_])

;;;;

(defmulti api-fn :fn)
(defmethod api-fn :default [params]
  {:error "Unknown fn call"
   :params params})

(defmethod api-fn :syncer [{:keys [id messages]}]
  (let [_ (->> messages
               (map deferred/defer)
               (doall))
        tasks (deferred/lease {:tag id})
        out (deferred/process tasks)
        _ (-> (deferred/delete tasks)
              (doall))]
    ;;(log/info (into [] out))
    {:fn :syncer :id id :messages out}))

#_(
   *ns*
   (in-ns 'jaq.runtime)
   (api-fn {:fn :syncer :id :foo :messages nil})
   (deferred/process (deferred/lease {:tag "739ce4ea-9cf0-4b38-b6f8-eba54c440187"}))
   )

(defn api-handler [{:keys [body edn-params] :as request}]
  (let [;;p (clojure.edn/read-string body)
        _ (debug edn-params)
        res (api-fn edn-params)]
    {:status 200 :body (pr-str res)}))

;;;

(def routes
  (atom #{["/dev" :get [{:name ::remove
                         :leave (fn [{response :response :as context}]
                                  (let [c (->> context
                                               :response
                                               :headers
                                               #_((fn [e] (merge e {:content-security-policy "script-src 'unsafe-eval' 'unsafe-inline'" })))
                                               ((fn [e] (merge e {"content-security-policy" "" })))
                                               (assoc-in context [:response :headers]))]
                                    #_(debug ::remove context response c)
                                    c)
                                  )}
                        `index-handler]]
          ["/repl" :post [(body-params/body-params) `repl-handler]]
          ["/api" :post [(body-params/body-params) `api-handler]]
          ["/out/*" :get [{:name ::remove
                           :leave (fn [{response :response :as context}]
                                    (let [c (->> context
                                                 :response
                                                 :headers
                                                 ((fn [e] (merge e {"content-security-policy" ""
                                                                    "cache-control" "no-cache"
                                                                    "content-type" "text/javascript"})))
                                                 (assoc-in context [:response :headers]))]
                                      #_(debug ::remove c)
                                      c))}
                          (io.pedestal.http.ring-middlewares/file "/tmp/")]]}))

#_(

   *ns*

   (io.pedestal.interceptor/interceptor? (io.pedestal.interceptor/interceptor remove-secure-headers))

   (-> (io.pedestal.interceptor/interceptor remove-secure-headers)
       :name)
   )

(def service {:env :prod
              ;; You can bring your own non-default interceptors. Make
              ;; sure you include routing and set it up right for
              ;; dev-mode. If you do, many other keys for configuring
              ;; default interceptors will be ignored.
              ;; ::http/interceptors []
              ::http/routes (fn [] (route/expand-routes @routes))

              ;; Uncomment next line to enable CORS support, add
              ;; string(s) specifying scheme, host and port for
              ;; allowed source(s):
              ;;
              ;; "http://localhost:8080"
              ;;
              ;;::http/allowed-origins ["scheme://host:port"]

              ;; Root for resource interceptor that is available by default.
              ;;::http/resource-path "/public"
              ::file-path "/tmp/"
              })

#_(
   (clojure.java.io/make-parents "/tmp/out/foo.txt")
   (spit "/tmp/out/foo.txt" "foo bar")
   )

#_(
   *ns*
   (require 'jaq.runtime)
   (in-ns 'jaq.runtime)
   (def s
     (-> service
         (merge {::http/host "0.0.0.0"
                 ::http/port 80
                 ::http/join? false
                 ::http/type :jetty})
         (http/create-server)
         (http/start)))
   ((::http/stop-fn s))
   )

(defonce servlet (atom nil))

(defn servlet-init
  [_ config]
  ;; Initialize your app here.
  (debug "init servlet")
  (listener-fn {:fn :init})
  (reset! servlet (http/servlet-init service config)))

(defn servlet-service
  [_ request response]
  (debug "servlet service")
  (http/servlet-service @servlet request response))

(defn servlet-destroy
  [_]
  (debug "servlet destroy")
  (listener-fn {:fn :destroy})
  (http/servlet-destroy @servlet)
  (reset! servlet nil))

(defn -main [& args]
  (-> service
      (merge {::http/host "0.0.0.0"
              ::http/port 80
              ::http/join? false
              ::http/type :jetty})
      (http/create-server)
      (http/start)))

#_(

   (declare s)
   (def s :foo)
   *ns*
   (in-ns 'jaq.runtime)

   (->> @servlet
        keys)

   (let [servlet-fn (:io.pedestal.http/servlet @servlet)
         service-fn (:io.pedestal.http/service-fn @servlet)]
     (-> (http/servlet-init service nil)
         #_(assoc :io.pedestal.http/servlet servlet-fn)
         #_(assoc :io.pedestal.http/service-fn service-fn)
         ((fn [e] (reset! servlet e)))))



   )
