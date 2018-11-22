(ns jaq.deploy
  (:require
   [clojure.tools.deps.alpha.util.maven :as mvn]
   [clojure.tools.deps.alpha :as deps]
   [clojure.tools.deps.alpha.script.parse :refer [parse-config]]
   [clojure.string :as string]
   [clojure.java.io :as io]
   [clojure.java.classpath :as cp]
   [clojure.data.xml :as xml]
   [clojure.data.json :as json]
   [cljs.build.api :as build]
   [jaq.services.appengine-admin :as admin]
   [jaq.services.deferred :refer [defer defer-fn]]
   [jaq.services.management :as management]
   [jaq.services.resource :as resource]
   [jaq.services.storage :as storage]
   [jaq.services.util :as util :refer [sleep]]
   [jaq.war :as war]
   [taoensso.timbre :as timbre
    :refer [log  trace  debug  info  warn  error  fatal  report]])
  (:import
   [java.io File]))

#_(
   *ns*
   (in-ns 'jaq.deploy)
   (io/resource "jaq-repl.el")

   )

(defn make-appengine-web-xml [opts]
  (let [{:keys [application-name
                java-runtime
                version]
         :or {application-name "jaq"
              java-runtime "java8"
              version "1"}} opts
        xml (xml/indent-str
             (xml/sexp-as-element
              [:appengine-web-app {:xmlns "http://appengine.google.com/ns/1.0"}
               [:application application-name]
               [:runtime java-runtime]
               [:version version]
               [:url-stream-handler "urlfetch"]
               [:ssl-enabled "true"]
               [:threadsafe "true"]
               [:system-properties
                [:property {:name "appengine.api.urlfetch.defaultDeadline" :value "60"}]]]))]
    (string/replace xml #"xmlns:a=" "xmlns=")))

(defn copy-file [source-path dest-path]
  (debug "copying" source-path dest-path)
  (io/copy (io/file source-path) (io/file dest-path)))

(defn copy-dir [source-path dest-path]
  (->> (io/file source-path)
       (file-seq)
       (filter (fn [e] (.isFile e)))
       (map (fn [e]
              (let [dest-file (-> (.getPath e)
                                  (string/replace (re-pattern source-path) dest-path)
                                  (io/file))]
                (io/make-parents dest-file)
                (copy-file e dest-file))))
       (doall)))

#_(
   (copy-dir "/tmp/.cache/src" "/tmp/war/WEB-INF/classes")
   (->> (io/file "/tmp/.cache/src")
        (file-seq))

   )

(defn write-manifest [opts war-path]
  (let [manifest-path [war-path "META-INF" "MANIFEST.MF"]]
    (apply io/make-parents manifest-path)
    (with-open [manifest-stream (io/output-stream (apply io/file manifest-path))]
      (-> (war/make-manifest (:manifest opts))
          (.write manifest-stream)))))

(defn write-web-xml [opts war-path]
  (let [web-xml-path [war-path "WEB-INF" "web.xml"]]
    (apply io/make-parents web-xml-path)
    (spit (string/join File/separator web-xml-path) (war/make-web-xml opts))))

(defn make-package-json [{:keys [app-name script-name engines scripts deps]
                          :or {app-name "jaq-server"
                               engines {:node ">=8"}
                               scripts {:start "node server.js"}
                               deps {}}}]
  (json/write-str {:name app-name
                   :engines engines
                   :scripts scripts
                   :dependencies deps}))

(defn write-package-json [config target-path]
  (let [path [target-path "package.json"]
        opts (:opts config)]
    (apply io/make-parents path)
    (spit (string/join File/separator path) (make-package-json opts))))

(defn write-nodejs [config target-path]
  (let [script-name "server.js"
        script-path "/tmp/out/server.js"]
    (write-package-json config target-path)
    (io/copy (io/file script-path)
             (io/file (string/join File/separator [target-path script-name])))))

#_(

   (make-package-json {})
   (write-package-json {} "/tmp/nodejs")
   (slurp "/tmp/nodejs/package.json")

   (write-nodejs {} "/tmp/nodejs")
   (-> (io/file "/tmp/nodejs")
       (file-seq))
   )

(defn write-appengine-web-xml [opts war-path]
  (let [xml-path [war-path "WEB-INF" "appengine-web.xml"]]
    (apply io/make-parents xml-path)
    (spit (string/join File/separator xml-path) (make-appengine-web-xml opts))))

(defn copy-libs [opts war-path classpath]
  (let [lib-path (or (:lib-path opts) (string/join File/separator [war-path "WEB-INF" "lib"]))
        classes-path (string/join File/separator [war-path "WEB-INF" "classes"])]
    (prn classpath)
    (.mkdirs (io/file lib-path))
    (.mkdirs (io/file classes-path))
    (->> (string/split classpath #":")
         (map (fn [e]
                (let [source-file (io/file e)
                      is-file (.isFile source-file)
                      path (if is-file lib-path classes-path)
                      dest-file (if is-file
                                  (->> (string/split e #"/")
                                       (last)
                                       (conj [path])
                                       (string/join File/separator)
                                       (io/file))
                                  (->> classes-path
                                       (io/file)))]
                  (if is-file
                    (copy-file source-file dest-file)
                    (copy-dir e (.getPath dest-file))))))
         (doall))))

(defn write-war [opts war-path classpath & postprocess-fns]
  (write-manifest opts war-path)
  (write-web-xml opts war-path)
  (write-appengine-web-xml opts war-path)
  (copy-libs opts war-path classpath))

(defn resolve-deps [opts service war-path]
  (let [default-cache (:default-cache opts "/tmp/.cache")
        paths (->> (:src opts)
                   (concat (get-in opts [:services service :extra-paths]))
                   (distinct))
        src-path (->> paths
                      (map (fn [e]
                             (string/join File/separator [default-cache e]))))
        deps-map {:paths [war-path]
                  :mvn/repos mvn/standard-repos
                  :mvn/local-repo (:mvn/local-repo opts default-cache)
                  :deps (:deps opts)}
        args-map (get-in opts [:services service])]
    (with-redefs [clojure.tools.gitlibs.impl/cache-dir (fn []
                                                         (:git/local-repo opts default-cache))]
      (let [lib-map (deps/resolve-deps deps-map args-map)
            cp (deps/make-classpath lib-map src-path nil)]
        {:lib-map lib-map
         :classpath cp}))))

#_(
   (resolve-deps {:src ["/tmp/src"]
                  :deps {'com.cognitect/transit-java {:mvn/version "0.8.332", :deps/manifest :mvn, :paths ["/tmp/.cache/com/cognitect/transit-java/0.8.332/transit-java-0.8.332.jar"], :dependents ['com.cognitect/transit-clj]}}}
                 "/tmp/war")

   )

(defn exploded-war
  "Write exploded war suitable for deployment to App Engine.
  Supported options
   :server-ns -
   :target-path - where the war files will be saved; defaults to /tmp
   :manifest - a map of override/additional Manifest entries; keys and values are both strings
   :deps - deps map compatible with deps.edn
   :mvn/local-repo - local repo path for mnv deps
   :git/local-repo - local repo path for git deps
   :default-cache - path of local cache; defaults to /tmp/.cache"
  [opts service]
  (let [war-path (:target-path opts "/tmp/war")
        resolved-deps (resolve-deps opts service war-path)
        classpath (:classpath resolved-deps)]
    (write-war opts war-path classpath)
    resolved-deps))

;;;

(defn prepare-src [opts service]
  (let [default-cache (or (:default-cache opts) "/tmp/.cache")
        paths (->> (:src opts)
                   (concat (get-in opts [:services service :extra-paths]))
                   (distinct))]
    (->> paths
         (map (fn [e]
                (storage/get-files (:src-bucket opts) e default-cache)))
         (dorun))))

(defn clear-cache [opts]
  (let [default-cache (or (:default-cache opts) "/tmp/.cache")]
    (->> (io/file default-cache)
         (file-seq)
         (reverse)
         (map (fn [e] (io/delete-file e true)))
         (dorun))))

(defn clear-war [opts]
  (let [target-path (or (:target-path opts) "/tmp/war")]
    (->> (io/file target-path)
         (file-seq)
         (reverse)
         (map (fn [e] (io/delete-file e true)))
         (dorun))))

(defn remaining-files [opts]
  (let [bucket (get-in opts [:code-bucket])
        prefix (get-in opts [:code-prefix])
        remotes (->> (storage/objects bucket {:prefix prefix})
                     (map :name)
                     (map (fn [e] (-> e (clojure.string/replace (re-pattern prefix) "") #_(last))))
                     (set))
        target-path (:target-path opts)]
    (->> (io/file target-path)
         (file-seq)
         (filter (fn [e] (.isFile e)))
         (filter (fn [e] (->> (clojure.string/replace (.getPath e) (re-pattern target-path) "") (contains? remotes) (not))))
         #_(map (fn [e]
                  (storage/put-large "staging.alpeware-jaq-runtime.appspot.com" (.getPath e) "/tmp/war" "apps/v19" {:callback storage/file-upload-done}))))
    #_remotes))

#_(

   (let [config (parse-config (jaq.repl/get-file "jaq-config.edn"))
         config (merge config
                       {:target-path "/tmp/nodejs"
                        :defaults {:runtime "nodejs8"}})]

     (->> (remaining-files config)
          #_(take 4)
          count))

   )

#_(defn upload-cb [{:keys [callback-args]}]
    (debug "upload-cb" callback-args)
    (upload callback-args))

;; TODO(alpeware): use deferred
(defn create-project
  "Create project."
  [opts]
  (let [project-id (get-in opts [:project-id])
        project-name (get-in opts [:project-name])]
    (loop [op (resource/create project-id project-name)]
      (debug op)
      (when-not (or (:done op) (not (:error op)))
        (sleep)
        (recur (resource/operation (:name op)))))))

;; TODO(alpeware): use deferred
(defn create-application
  "Create application."
  [opts]
  (let [project-id (get-in opts [:project-id])
        location-id (get-in opts [:location-id])]
    (loop [op (admin/create project-id location-id)]
      (debug op)
      (when-not (:done op)
        (sleep)
        (recur (admin/operation (:name op)))))))

;;;

(defmethod defer-fn ::deps [{:keys [service config cont] :as params}]
  (debug ::deps config)
  (clear-war config)
  #_(prepare-src config service)
  (exploded-war config service)
  (prepare-src config service)
  #_(clear-cache config)
  (when-not (empty? cont)
    (defer (merge params
                  {:fn (first cont)
                   :cont (rest cont)})
      {:delay-ms (* 5 1000)})))

;; TODO(alpeware): consider re-factoring storage callback signature to require a
;;                 deferred instead of a fn
(defn upload
  "Callback fn for storage"
  [params]
  (debug :callback params)
  (defer (merge (:callback-args params) {:fn ::upload})))

(defmethod defer-fn ::upload [{:keys [service config cont] :as params}]
  (debug ::upload params)
  (let [config (or (:callback-args params) config)
        bucket (get-in config [:code-bucket])
        prefix (get-in config [:code-prefix])
        src-dir (:target-path config "/tmp")
        file (->> (remaining-files config)
                  (sort-by (fn [e] (.length e)))
                  (take 1)
                  (first))
        uploaded-file (io/file (:uploaded-file config))]
    (debug "uploading" file)
    (when uploaded-file
      (debug "deleting" uploaded-file)
      (io/delete-file uploaded-file true))
    (if file
      (storage/put-large bucket (.getPath file) src-dir prefix
                         {:callback jaq.deploy/upload
                          :args (merge params {:uploaded-file (.getPath file)})})
      (when-not (empty? cont)
        (defer (merge params
                      {:fn (first cont) :cont (rest cont)}))))))

(defmethod defer-fn ::deploy [{:keys [service config cont] :as params}]
  (debug ::deploy params)
  (let [op (admin/deploy-app (merge config {:service service}))]
    (defer (merge params {:fn ::op :op op}))))

(defmethod defer-fn ::migrate [{:keys [service config cont] :as params}]
  (debug ::migrate params)
  (let [project-id (get-in config [:project-id])
        version (get-in config [:version])
        op (admin/migrate project-id service version)]
    (defer (merge params {:fn ::op :op op}))))

(defmethod defer-fn ::op [{:keys [op service config cont] :as params}]
  (debug ::op params)
  (if-not (or (:done op) (:error op))
    (defer (merge params {:op (admin/operation (:name op))}) {:delay-ms 2000})
    (when (and (not (empty? cont)) (not (:error op)))
      (defer (merge params {:fn (first cont) :cont (rest cont)})))))

(defn deploy-all [config]
  (let [services (->> config :services (keys))]
    (defer {:fn ::deploy-all :config config :services services})))

;; TODO(alpeware): add static assets
(defmethod defer-fn ::deploy-all [{:keys [config services] :as params}]
  (let [service (->> services (first))
        services (->> services (rest))]
    (debug ::deploy-all service config)
    ;;; clear cljs cache
    (clear-cache {:default-cache "/tmp/out"})
    (when service
      (defer (merge params {:fn ::deps :service service :services services
                            :cont [::upload ::deploy ::migrate ::deploy-all]})))))

(defmethod defer-fn ::build [{:keys [src opts cont cont-params] :as params}]
  (let [compiler-opts (merge {:optimizations :advanced
                              :verbose true
                              :compiler-stats true
                              :cache-analysis true
                              :source-map false
                              :aot-cache true
                              :asset-path "out"
                              :output-dir "/tmp/out"
                              :output-to "/tmp/out/app.js"}
                             opts)]
    (build/build src compiler-opts)
    (when-not (empty? cont)
      (defer (merge params (first cont-params) {:fn (first cont) :cont (rest cont)
                                                :cont-params (rest cont-params)})))))

(defn add-system-classpath
  "Add an url path to the system class loader"
  [url]
  (let [^java.net.URLClassLoader cl (-> (ClassLoader/getSystemClassLoader))
        clazz (.getClass cl)
        method (-> clazz
                   (.getSuperclass)
                   (.getDeclaredMethod "addURL" (into-array Class [java.net.URL])))
        u (if (string? url) (java.net.URL. url) url)]
    (.setAccessible method true)
    (. method (invoke cl (into-array Object [u])))))

(defn contains-class-path? [path]
  (->> (cp/classpath)
       (map (fn [e] (.getPath e)))
       (filter (fn [e] (string/includes? e path)))
       (last)))

(defn fix-classpath [base dirs]
  (->> dirs
       (map (fn [e] (string/join File/separator [base e])))
       (filter (fn [e] (-> e
                           (contains-class-path?)
                           (not))))
       (map (fn [e] (add-system-classpath (str "file:" e "/"))))
       (doall)))

(defn deploy-new-version [{:keys [config service] :as params}]
  (let [default-cache (or (:default-cache config) "/tmp/.cache")
        paths (->> (:src config)
                   (concat (get-in config [:services service :extra-paths]))
                   (distinct))
        src (->> (get-in config [:services service :client-src])
                 (conj [default-cache])
                 (string/join File/separator))
        opts {:main (get-in config [:services service :client-ns])}]
    (prepare-src config service)
    (fix-classpath default-cache paths)
    (defer {:fn ::build
            :src src
            :opts (merge {:optimizations :none} opts)
            :config config
            :service service
            :cont [::build ::deploy-all]
            :cont-params [{:opts (merge {:optimizations :advanced
                                         :output-to "/tmp/.cache/resources/public/app.js"}
                                        opts)}
                          {:services [service]
                           :config config}]})))

#_(
   *ns*
   (in-ns 'jaq.deploy)

   (let [config (parse-config (jaq.repl/get-file "jaq-config.edn"))
         config (merge config
                       {:target-path "/tmp/war"})]
     (deploy-new-version {:config config :service :default})
     )

   (->> (io/file "/tmp/.cache/src/")
        (file-seq)
        #_(count))

   (add-system-classpath "file:/tmp/.cache/src/")
   (contains-class-path? "/tmp/.cache/src/")

   (let [src "/tmp/.cache/src/"]
     (->> (io/file src)
          (file-seq)
          (map (fn [e] (.getPath e)))
          (map (fn [e] (string/replace e (re-pattern src) "")))
          (map (fn [e] (io/resource e)))
          (remove nil?)
          #_(count)))

   (seq (.getURLs (java.lang.ClassLoader/getSystemClassLoader)))

   (=
    (->> (io/file "/tmp/src/")
         (file-seq)
         (reverse)
         (map (fn [e] (.getPath e)))
         (map (fn [e] (string/replace e (re-pattern "/tmp/src/") "")))
         (map (fn [e] (io/resource e)))
         (remove nil?)
         (count))
    (->> (io/file "/tmp/src/")
         (file-seq)
         (count)))

   )

#_(

   *ns*
   (in-ns 'jaq.deploy)

   (compile-cljs  {:opts {:main 'jaq.app}})

   (storage/get-files (storage/default-bucket) "src" "/tmp")
   (->> (clojure.java.io/file "/tmp/src")
        (file-seq)
        (map (fn [e] (.getPath e)))
        #_(filter (fn [e] (clojure.string/ends-with? e ".clj")))
        #_(filter (fn [e] (clojure.string/includes? e "macro")))
        #_(count))

   (io/delete-file "/tmp/src/jaq/browser.cljs")
   (io/delete-file "/tmp/src/jaq/app.cljs")
   (io/delete-file "/tmp/src/jaq/repl.cljs")
   (io/delete-file "/tmp/src/jaq/server.cljs")

   (add-system-classpath "file:/tmp/src/")
   (contains-class-path? "/tmp/.cache/src")
   (io/resource "jaq/app.cljs")

   (build/build "/tmp/src"
                {:optimizations :advanced
                 :output-dir "/tmp/out"
                 :output-to "/tmp/out/index.js"})

   (defmethod defer-fn ::build [{:keys [src opts]}]
     (let [compiler-opts (merge {:optimizations :advanced
                                 :output-dir "/tmp/out"
                                 :output-to "/tmp/out/app.js"}
                                opts)])
     (build/build src compiler-opts))

   ;; requires src files on classpath
   (storage/get-files (storage/default-bucket) "src" "/tmp")

   (compile-cljs  {:opts {:main 'jaq.app}})

   (defer {:fn ::build :src "/tmp/src" :opts {:optimizations :none
                                              :asset-path "out"
                                              :main 'jaq.app}})

   @*1

   *ns*
   (storage/get-files (storage/default-bucket) "src" "/tmp")
   (defer {:fn ::build :src "/tmp/src" :opts {:optimizations :none
                                              :output-to "/tmp/out/server.js"
                                              :target :nodejs
                                              :asset-path ""
                                              :language-in :ecmascript5
                                              :main 'jaq.server}})

   (defer {:fn ::build :src "/tmp/src" :opts {:optimizations :simple
                                              :output-to "/tmp/nodejs/server.js"
                                              :target :nodejs
                                              :asset-path "."
                                              :language-in :ecmascript5
                                              :main 'jaq.server}})


   (write-nodejs {:opts {:deps {"concat-stream" "1.6.2"
                                "content-type" "1.0.4"
                                "cookies" "0.7.1"
                                "etag" "1.8.1"
                                "lru" "3.1.0"
                                "multiparty" "4.1.3"
                                "random-bytes" "1.0.0"
                                "qs" "6.5.1"
                                "simple-encryptor" "1.2.0"
                                "url" "0.11.0"
                                "ws" "5.1.1"}}} "/tmp/nodejs")

   (copy-dir "/tmp/out" )

   (defer {:fn ::build :src "/tmp/src" :opts {:optimizations :advanced
                                              :main 'jaq.app}})

   (io/delete-file "/tmp/war/WEB-INF/classes/jaq/ui/landing_page.cljc")
   (io/delete-file "/tmp/war/WEB-INF/classes/jaq/repl.clj")
   (storage/get-files (storage/default-bucket) "src" "/tmp")

   (io/copy (io/file "/tmp/src/jaq/ui/landing_page.cljc")
            (io/file "/tmp/war/WEB-INF/classes/jaq/ui/landing_page.cljc"))

   (io/copy (io/file "/tmp/src/jaq/repl.clj")
            (io/file "/tmp/war/WEB-INF/classes/jaq/repl.clj"))

   (-> (slurp "/tmp/src/jaq/app.cljs")
       (clojure.edn/read-string)
       (clojure.pprint/pprint)
       (clojure.pprint/code-dispatch)
       (with-out-str))

   (-> {:foo :bar}
       (clojure.pprint/pprint)
       (with-out-str))

   (clear-cache {:default-cache "/tmp/out"})


   (->> (io/file "/tmp/out/app.js")
        (.length))

   (->> (io/file "/tmp/.cache/resources/public/app.js")
        (.length))

   (defer {:fn ::build :src "/tmp/src" :opts {:optimizations :simple
                                              :main 'jaq.app}})

   (io/make-parents "/tmp/.cache/resources/public/app.js")
   (io/copy (io/file "/tmp/out/app.js")
            (io/file "/tmp/.cache/resources/public/app.js"))

   (slurp "/tmp/out/app.js")
   (-> (io/file "/tmp/out/app.js")
       (.length))
   (-> (io/file "/tmp/war/WEB-INF/classes/public/app.js")
       (.length))


   *ns*
   (in-ns 'jaq.deploy)
   (let [config (parse-config (jaq.repl/get-file "jaq-config.edn"))
         config (merge config
                       {:target-path "/tmp/nodejs"
                        :defaults {:runtime "nodejs8"}})]
     #_(defer {:fn ::upload :config config :service :service})
     #_(defer {:fn ::deps :config config :service :default :cont [::upload ::deploy ::migrate]})

     #_(defer {:fn ::deps :config config :service :service})

     #_(defer {:fn ::upload :config config :service :service :cont [::deploy ::migrate]})

     (defer {:fn ::upload :config config :service :default :cont [::deploy]})
     #_(defer {:fn ::upload :config config :service :default})

     #_(defer {:fn ::deploy :config config :service :default})

     #_(defer {:fn ::deploy :config config :service :service :cont [::migrate]})
     #_(defer {:fn ::deploy :config config :service :default :cont [::migrate]}))

   (clear-cache {})

   (-> (Runtime/getRuntime)
       (.gc))

   (let [config (parse-config (jaq.repl/get-file "jaq-config.edn"))
         config (merge config
                       {:server-ns "jaq.runtime"
                        :target-path "/tmp/war"})]
     (deploy-all config)
     #_(defer {:fn ::deploy-all :config config}))

   *ns*
   (in-ns 'jaq.deploy)
   (let [config (parse-config (jaq.repl/get-file "jaq-config.edn"))
         config (merge config
                       {:target-path "/tmp/war"})]
     (defer {:fn ::deps :config config :service :default})
     #_(defer {:fn ::upload :config config :service :default})
     #_(defer {:fn ::deploy-all :config config :services [:default]}))
   @*1

   (slurp "https://v28-dot-alpeware-jaq-runtime.appspot.com/public/baz.txt")
   (slurp "https://v28-dot-alpeware-jaq-runtime.appspot.com/")
   (slurp "https://v28-dot-alpeware-jaq-runtime.appspot.com/repl")

   (->> (slurp "https://service-dot-alpeware-jaq-runtime.appspot.com/")
        (storage/put-simple (str "staging." (storage/default-bucket))
                            "apps/v28/WEB-INF/classes/public/index.html"
                            "text/html"))

   (io/resource "public/foo.txt")

   (admin/delete "alpeware-jaq-runtime" :service1)

   (defn service-defaults [version servlet]
     {:id version
      :runtime "java8"
      :threadsafe true
      :basicScaling {:maxInstances 1}
      :instanceClass "B1"
      :handlers [{:urlRegex "/.*"
                  :script {:scriptPath servlet}}]})

   (defn deploy-service [project-id app-map]
     (admin/action :post
                   [:apps project-id :services :service1 :versions]
                   {:content-type :json
                    :body (clojure.data.json/write-str app-map)}))

   (with-redefs [jaq.services.appengine-admin/app-defaults jaq.deploy/service-defaults
                 jaq.services.appengine-admin/deploy jaq.deploy/deploy-service]
     (admin/deploy-app (:project-id jaq)
                       (:bucket jaq)
                       (:prefix jaq)
                       (:version jaq)
                       "servlet"))

   (defn delete [project-id service]
     (admin/action :delete [:apps project-id :services service]))
   (delete (:project-id jaq) :service1)

   (def rd (clojure.edn/read-string (slurp "/tmp/.cache/resolved-deps.edn")))

   (resolve-deps {})
   (->> rd
        :lib-map
        (keys)
        (first)
        #_(get 'com.cognitect/transit-java)
        (get (:lib-map rd)))

   (->> rd
        :lib-map
        (vals)
        (filter (fn [e] (->> e :dependents (empty?))))
        #_(first)
        #_(count))

   (->> rd
        :classpath
        )

   (keys rd)


   (->> "{:foo foo.bar}"
        #_(parse-config)
        #_(pr-str)
        (read-string))

   (->> (io/file "/tmp/war/WEB-INF/lib")
        (file-seq)
        (filter #(.isFile %))
        count)

   (-> "https://raw.githubusercontent.com/clojure/java.classpath/39854b7f9751f99b49a0644aa611d18f0c07dfe6/src/main/clojure/clojure/java/classpath.clj"
       (slurp)
       (load-string))

   (clojure.java.classpath/classpath)

   *ns*
   (->> "https://raw.githubusercontent.com/clojure/tools.deps.alpha/add-lib/src/main/clojure/clojure/tools/deps/alpha/libmap.clj"
        (slurp)
        (load-string))
   (->> "https://raw.githubusercontent.com/clojure/tools.deps.alpha/add-lib/src/main/clojure/clojure/tools/deps/alpha/repl.clj"
        (slurp)
        (load-string))

   (->> ["/tmp/src"]
        (map io/file)
        (map (fn [e] (.toURL e)))
        (clojure.tools.deps.alpha.repl/add-loader-url))

   (->> (clojure.java.classpath/classpath)
        (map (fn [e] (.getPath e))))

   (defn add-system-classpath
     "Add an url path to the system class loader"
     [url]
     (let [^java.net.URLClassLoader cl (-> (ClassLoader/getSystemClassLoader))
           clazz (.getClass cl)
           method (-> clazz
                      (.getSuperclass)
                      (.getDeclaredMethod "addURL" (into-array Class [java.net.URL])))
           u (if (string? url) (java.net.URL. url) url)]
       (.setAccessible method true)
       (. method (invoke cl (into-array Object [u])))))

   (with-redefs [clojure.tools.deps.alpha.repl/add-loader-url jaq.deploy/add-system-classpath]
     (clojure.tools.deps.alpha.repl/add-lib 'org.clojure/clojurescript {:mvn/version "1.10.339"}))

   (with-redefs [clojure.tools.deps.alpha.repl/add-loader-url jaq.deploy/add-system-classpath]
     (clojure.tools.deps.alpha.repl/add-lib 'enlive {:mvn/version "1.1.6"}))

   ;; enlive {:mvn/version "1.1.6"}
   (add-system-classpath "http://central.maven.org/maven2/com/cemerick/pomegranate/1.0.0/pomegranate-1.0.0.jar")


   (add-system-classpath "file:/tmp/pomegranate1.jar")
   (add-system-classpath "file:/tmp/src/")
   (spit "/tmp/foo.txt" "foo bar")

   (io/resource "jaq/app.cljs")
   (spit "/tmp/foo.clj" "(ns foo) (defn bar [] :bar)")

   (require 'foo)
   (foo.bar)

   (io/resource "foo.clj")
   (io/resource "pomegranate.jar")

   (seq (.getURLs (java.lang.ClassLoader/getSystemClassLoader)))

   (-> (ClassLoader/getSystemClassLoader)
       (.getURLs)
       (seq)
       (count))

   (io/resource "pomegranate.jar")

   (load "/jaq/foo")
   (io/resource "jaq/foo.clj")
   (jaq.foo/bar)

   (->> "/base"
        (io/file)
        (file-seq))



   (->> (io/file "/tmp/war")
        (file-seq)
        #_(count))

   (defn add-loader-url
     "Add url string or URL to the highest level DynamicClassLoader url set."
     [url]
     (let [u (if (string? url) (java.net.URL. url) url)
           loader (loop [loader (.getContextClassLoader (Thread/currentThread))]
                    (let [parent (.getParent loader)]
                      (if (instance? clojure.lang.DynamicClassLoader parent)
                        (recur parent)
                        loader)))]
       #_(.getExtURLs loader)
       #_(->> loader
              (clojure.reflect/reflect)
              :members
              #_(filter (fn [e] (contains? (:flags e) :public)))
              #_(map :name))
       #_(class loader)
       #_(instance? java.net.URLClassLoader loader)
       #_(.addURL loader u)
       #_(.addExtURL u)
       #_(instance? clojure.lang.DynamicClassLoader loader)
       (if (instance? clojure.lang.DynamicClassLoader loader)
         (.addURL ^clojure.lang.DynamicClassLoader loader u)
         (throw (IllegalAccessError. "Context classloader is not a DynamicClassLoader")))))


   (let [_ (->> (.getContextClassLoader (Thread/currentThread))
                (clojure.lang.DynamicClassLoader.)
                (.setContextClassLoader (Thread/currentThread)))]
     (add-loader-url "http://central.maven.org/maven2/com/cemerick/pomegranate/1.0.0/pomegranate-1.0.0.jar"))

   (add-system-classpath "http://central.maven.org/maven2/com/cemerick/pomegranate/1.0.0/pomegranate-1.0.0.jar")
   (add-system-classpath "/tmp/pomegranate.jar")

   (->> (slurp "http://central.maven.org/maven2/com/cemerick/pomegranate/1.0.0/pomegranate-1.0.0.jar")
        (spit "/tmp/pomegranate1.jar"))

   (let [uri "http://central.maven.org/maven2/org/clojure/clojurescript/1.10.339/clojurescript-1.10.339.jar"
         file "/tmp/.jar"]
     (with-open [in (io/input-stream uri)
                 out (io/output-stream file)]
       (io/copy in out)))

   (io/copy
    (.getFile (io/as-url "http://central.maven.org/maven2/com/cemerick/pomegranate/1.0.0/pomegranate-1.0.0.jar"))
    (io/file "/tmp/pomegranate.jar"))

   (io/copy (io/file "/tmp/src/jaq/browser.cljs")
            (io/file "/tmp/out/jaq/browser.cljs"))

   (->> (io/file "/tmp")
        (file-seq)
        (map (fn [e] [(.getPath e) (.length e)])))

   (slurp "/tmp/pomegranate.jar")

   (require 'cemerick.pomegranate)



   (java.lang.ClassLoader/getSystemClassLoader)

   (let [_ (->> (.getContextClassLoader (Thread/currentThread))
                (clojure.lang.DynamicClassLoader.)
                (.setContextClassLoader (Thread/currentThread)))]
     (->> (loop [v []
                 loader (.getContextClassLoader (Thread/currentThread))]
            (let [parent (.getParent loader)]
              (if (instance? java.net.URLClassLoader parent)
                (recur (conj v loader) parent)
                v)))
          (map (fn [e] (class e)))
          ))

   (->> (loop [v []
               loader (.getContextClassLoader (Thread/currentThread))]
          (let [parent (.getParent loader)]
            (if (instance? java.net.URLClassLoader parent)
              (recur (conj v loader) parent)
              v)))
        (map (fn [e] (class e)))
        )

   (->> (loop [v []
               loader (.getContextClassLoader (Thread/currentThread))]
          (let [parent (.getParent loader)]
            (if (instance? java.net.URLClassLoader parent)
              (recur (conj v loader) parent)
              v)))
        #_(map (fn [e] (class e)))
        (first)
        (clojure.reflect/reflect)
        (:members)
        (map :name))

   (let [_ (->> (.getContextClassLoader (Thread/currentThread))
                (clojure.lang.DynamicClassLoader.)
                (.setContextClassLoader (Thread/currentThread)))]
     (->> (loop [v []
                 loader (.getContextClassLoader (Thread/currentThread))]
            (let [parent (.getParent loader)]
              (if (instance? java.net.URLClassLoader parent)
                (recur (conj v loader) parent)
                v)))
          #_(first)
          #_(.getURLs)
          #_(map (fn [e] (.getPath e)))
          ))

   (let [_ (->> (.getContextClassLoader (Thread/currentThread))
                (clojure.lang.DynamicClassLoader.)
                (.setContextClassLoader (Thread/currentThread)))]
     (.addURL (.getContextClassLoader (Thread/currentThread)) (-> (io/file "/tmp/pomegranate.jar") (.toURL)))
     (require 'cemerick.pomegranate)
     )

   (->> (io/file "/tmp/")
        (file-seq))

   (->> (.getContextClassLoader (Thread/currentThread))
        (clojure.lang.DynamicClassLoader.)
        (.setContextClassLoader (Thread/currentThread)))

   (->> (.getContextClassLoader (Thread/currentThread))
        (clojure.reflect/reflect)
        :members
        (map :name))

   (->> (.getContextClassLoader (Thread/currentThread))
        (reduce (fn [e f]))
        (clojure.reflect/reflect)
        :members
        (map :name))

   (->> (.getContextClassLoader (Thread/currentThread))
        (.getURLs)
        (map (fn [e] (.getPath e)))
        (filter (fn [e] (string/includes? e "/tmp/src")))
        (count))


   (->> (java.lang.ClassLoader/getSystemClassLoader)
        (clojure.reflect/reflect)
        #_:members
        #_(filter (fn [e] (contains? (:flags e) :public)))
        #_(map :name))

   (->> com.google.apphosting.runtime.ApplicationClassLoader
        (clojure.reflect/reflect)
        :members
        #_(filter (fn [e] (contains? (:flags e) :public)))
        (map :name))

   (supers com.google.apphosting.runtime.ApplicationClassLoader)

   (->> (.getDeclaredFields com.google.apphosting.runtime.ApplicationClassLoader)
        (map (fn [e] (.getName e))))

   (->> (.getDeclaredMethods com.google.apphosting.runtime.ApplicationClassLoader)
        (map (fn [e] (.getName e))))

   *ns*
   (java.net.InetAddress/getAllByName "repo1.maven.org")
   (java.net.InetAddress/getAllByName "google.com")
   (System/getProperty "sun.net.spi.nameservice.nameservers")

   (->> (System/getProperties)
        (keys)
        (sort))

   (->> (System/getenv)
        (into {})
        (clojure.walk/keywordize-keys))

   util/env

   (def env
     (delay
      (->> (System/getenv)
           (into {})
           (clojure.walk/keywordize-keys))))

   (admin/locations "alpeware-jaq-runtime")
   (resource/projects)

   (storage/get-files (storage/default-bucket) "src" "/tmp")
   (->> (clojure.java.io/file "/tmp/out")
        (file-seq))

   (io/delete-file "/tmp/src/jaq/browser.cljs")
   (io/delete-file "/tmp/src/jaq/repl.cljs")

   (build/build "/tmp/src"
                {:optimizations :advanced
                 :output-dir "/tmp/out"
                 :output-to "/tmp/out/index.js"})

   (defmethod defer-fn ::build [{:keys [src opts]
                                 :or {opts {:optimizations :advanced
                                            :output-dir "/tmp/out"
                                            :output-to "/tmp/out/app.js"}}}]
     (build/build src opts))

   (defer {:fn ::build :src "/tmp/src"})

   (-> (io/file "/tmp/out/app.js")
       (.length))

   )


;;; bootstrap alias for lein
(defn -main []
  (let [deps-edn (parse-config (slurp "/opt/deps.edn"))
        opts {:server-ns "jaq.runtime"
              :target-path "/opt/war"
              :src ["/opt/src" "/opt/resources"]
              :repl-token "foobarbaz"}]
    (exploded-war (merge deps-edn opts))))
