(ns jaq.runtime
  (:require
   [clojure.edn :as edn]
   [io.pedestal.http :as http]
   [io.pedestal.http.route :as route]
   [jaq.gcp.iam :as iam]
   [jaq.api :as api]
   [jaq.routes :as routes]
   [jaq.gcp.management :as management]
   [jaq.services.env :as env]
   [taoensso.timbre :as timbre
    :refer [log  trace  debug  info  warn  error  fatal  report
            logf tracef debugf infof warnf errorf fatalf reportf
            spy get-env]]))

#_(
   *ns*
   (in-ns 'jaq.runtime)

   *compile-path*
   (io/make-parents "/tmp/classes/foo.bar")
   (binding [*compile-path* "/tmp/classes"]
     (compile (symbol "jaq.runtime")))

   (->>
    (management/services)
    (map (fn [e] (-> e :serviceConfig :title))))

   (management/enable "servicemanagement.googleapis.com" "alpeware-jaq-runtime")
   (management/enable "storage-api.googleapis.com" "alpeware-jaq-runtime")
   (management/enable "cloudtasks.googleapis.com" "alpeware-jaq-runtime")
   (management/enable "appengine.googleapis.com" "alpeware-jaq-runtime")
   (management/enable "cloudresourcemanager.googleapis.com" "alpeware-jaq-runtime")
   (management/enable "script.googleapis.com" "alpeware-jaq-runtime")
   (management/enable "pubsub.googleapis.com" "alpeware-jaq-runtime")

   (management/enable "memcache.googleapis.com" "alpeware-jaq-runtime")

   )

(def service {:env :prod
              ;; You can bring your own non-default interceptors. Make
              ;; sure you include routing and set it up right for
              ;; dev-mode. If you do, many other keys for configuring
              ;; default interceptors will be ignored.
              ;; ::http/interceptors []
              ::http/routes (fn [] (route/expand-routes @routes/routes))

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

(defn load-app-ns []
  (let [app-ns (symbol (:JAQ_MAIN_NS env/env))]
    (prn ::app-ns app-ns)
    (require app-ns)))

;; start on appengine container
(defn servlet-init
  [_ config]
  ;; Initialize your app here.
  (debug "init servlet")
  (load-app-ns)
  (api/api-fn {:fn :init})
  (reset! servlet (http/servlet-init service config)))

(defn servlet-service
  [_ request response]
  (http/servlet-service @servlet request response))

(defn servlet-destroy
  [_]
  (debug "servlet destroy")
  (api/api-fn {:fn :destroy})
  (http/servlet-destroy @servlet)
  (reset! servlet nil))

;; start standalone jetty server
(defonce server (atom nil))

;; TODO: register :destroy shutdown hook

(defn -main [& args]
  (debug "loading app namespace")
  (load-app-ns)
  (api/api-fn {:fn :init})
  (-> service
      (merge {::http/host "0.0.0.0"
              ::http/port (or (-> (:JAQ_HTTP_PORT env/env)
                                  (edn/read-string))
                              80)
              ::http/join? false
              ::http/type :jetty
              ::http/container-options {:keystore (:JAQ_KEYSTORE env/env)
                                        :key-password (:JAQ_KEYSTORE_PASSWORD env/env)
                                        :ssl? true
                                        :ssl-port (or (-> (:JAQ_SSL_PORT env/env)
                                                          (edn/read-string))
                                                      443)}})
      (http/create-server)
      (http/start)
      (->> (reset! server)))
  (debug "server started"))

#_(

   (clojure.edn/read-string nil)
   *ns*
   (in-ns 'jaq.runtime)

)
