(ns jaq.deploy
  (:require
   [jaq.services.appengine-admin :as admin]
   [jaq.services.deferred :refer [defer defer-fn]]
   [jaq.services.management :as management]
   [jaq.services.resource :as resource]
   [jaq.services.storage :as storage]
   [jaq.services.util :as util :refer [sleep]]
   [jaq.war :as war]
   [clojure.tools.deps.alpha.util.maven :as mvn]
   [clojure.tools.deps.alpha :as deps]
   [clojure.tools.deps.alpha.script.parse :refer [parse-config]]
   [clojure.string :as string]
   [clojure.java.io :as io]
   [clojure.data.xml :as xml]
   [taoensso.timbre :as timbre
    :refer [log  trace  debug  info  warn  error  fatal  report]])
  (:import
   [java.io File]))

#_(

   (in-ns 'jaq.deploy)

   )

(defn make-appengine-web-xml [opts]
  (let [{:keys [application-name
                java-runtime
                version
                repl-token]
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
                [:property {:name "JAQ_REPL_TOKEN" :value repl-token}]
                [:property {:name "appengine.api.urlfetch.defaultDeadline" :value "60"}]]]))]
    (string/replace xml #"xmlns:a=" "xmlns=")))

(defn copy-file [source-path dest-path]
  (prn "copying" source-path dest-path)
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
  (copy-libs opts war-path classpath)

  #_(with-open [war-stream (-> (io/output-stream war-path)
                               (JarOutputStream. (make-manifest (:manifest opts))))]
      (doto war-stream
        (write-entry "WEB-INF/web.xml" (string-input-stream (make-web-xml opts)))
        (and (:compile-path opts)
             (dir-entry opts "WEB-INF/classes/" (:compile-path opts))))
      (doseq [path (distinct (:resource-paths opts))
              :when path]
        (dir-entry war-stream opts "WEB-INF/classes/" path))
      (doseq [pp-fn postprocess-fns]
        (pp-fn opts war-stream))
      war-stream))

(defn resolve-deps [opts war-path]
  (let [default-cache (:default-cache opts "/tmp/.cache")
        src-path (:src opts)
        deps-map {:paths [war-path]
                  :mvn/repos mvn/standard-repos
                  :mvn/local-repo (:mvn/local-repo opts default-cache)
                  :deps (:deps opts)}]
    (with-redefs [clojure.tools.gitlibs.impl/cache-dir (fn []
                                                         (:git/local-repo opts default-cache))]
      (let [lib-map (deps/resolve-deps deps-map nil)
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
  [opts]
  (let [war-path (:target-path opts "/tmp")
        resolved-deps (resolve-deps opts war-path)
        classpath (:classpath resolved-deps)]
    (write-war opts war-path classpath)
    resolved-deps))

;;;

(defn remaining-files [opts]
  (let [bucket (get-in opts [:code-bucket])
        prefix (get-in opts [:code-prefix])
        remotes (->> (storage/objects bucket {:prefix prefix})
                     (map :name)
                     (map (fn [e] (-> e (clojure.string/split #"/") (last))))
                     (set))
        target-path (:target-path opts)]
    (->> (io/file target-path)
         (file-seq)
         (filter (fn [e] (.isFile e)))
         (filter (fn [e] (->> (.getName e) (contains? remotes) (not))))
         #_(map (fn [e]
                  (storage/put-large "staging.alpeware-jaq-runtime.appspot.com" (.getPath e) "/tmp/war" "apps/v19" {:callback storage/file-upload-done}))))))

#_(defn upload-cb [{:keys [callback-args]}]
    (debug "upload-cb" callback-args)
    (upload callback-args))

(defn upload
  "Upload app to storage bucket."
  [opts]
  (let [opts (or (:callback-args opts) opts)
        bucket (get-in opts [:code-bucket])
        prefix (get-in opts [:code-prefix])
        src-dir (:target-path opts "/tmp")
        file (->> (remaining-files opts)
                  (sort-by (fn [e] (.length e)))
                  (take 1)
                  (first))
        uploaded-file (io/file (:uploaded-file opts))]
    (debug "uploading" file)
    (when uploaded-file
      (debug "deleting" uploaded-file)
      (io/delete-file uploaded-file true))
    (when file
      (storage/put-large bucket (.getPath file) src-dir prefix
                         {:callback jaq.deploy/upload
                          :args (merge opts {:uploaded-file (.getPath file)})})
      #_(storage/put-large bucket (.getPath file) src-dir prefix))
    #_(debug "Uploading app [this may take a while]...")
    #_(storage/copy src-dir bucket prefix)
    #_(debug "File counter" @storage/file-counter)
    #_(loop []
        (debug "File counter" @storage/file-counter)
        (when-not (zero? @storage/file-counter)
          (sleep)
          (recur)))))

#_(
   (clojure.repl/demunge (str upload))
   (io/file nil)
   )

(defn deploy
  "Deploys to App Engine."
  [service opts]
  (let [project-id (get-in opts [:project-id])
        bucket (get-in opts [:code-bucket])
        prefix (get-in opts [:code-prefix])
        version (get-in opts [:version])
        servlet (get-in opts [:servlet] "servlet")]
    (debug "Deploying app...")
    #_(with-redefs [jaq.services.appengine-admin/app-defaults jaq.deploy/service-defaults
                  jaq.services.appengine-admin/deploy jaq.deploy/deploy-service]
      (defer {:fn ::op
              :op (admin/deploy-app project-id
                                    bucket
                                    prefix
                                    version
                                    servlet)}))
    (defer {:fn ::op :op (admin/deploy-app project-id service bucket prefix version servlet)})))

(defn migrate
  "Migrate traffic to application version."
  [opts]
  (let [project-id (get-in opts [:project-id])
        version (get-in opts [:version])]
    (defer {:fn ::op :op (admin/migrate project-id version)})))

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

(defn prepare-src [opts]
  (let [default-cache (or (:default-cache opts) "/tmp/.cache")]
    (storage/get-files (:src-bucket opts) (:src-prefix opts) default-cache)
    (storage/get-files (:resource-bucket opts) (:resource-prefix opts) default-cache)))

(defn clear-cache [opts]
  (let [default-cache (or (:default-cache opts) "/tmp/.cache")]
    (->> (io/file default-cache)
         (file-seq)
         (reverse)
         (map (fn [e] (io/delete-file e)))
         (count))))
;;;

(defmethod defer-fn ::deps [{:keys [config]}]
  (debug ::deps config)
  (prepare-src config)
  (exploded-war config)
  #_(clear-cache config)
  #_(defer {:fn ::upload :config config :delay-ms (* 5 1000)})
  #_(upload config))

(defmethod defer-fn ::upload [{:keys [config]}]
  (debug ::upload config)
  (upload config))

(defmethod defer-fn ::deploy [{:keys [service config]}]
  (debug ::deploy config)
  (deploy service config))

(defmethod defer-fn ::migrate [{:keys [config]}]
  (debug ::migrate config)
  (migrate config))

(defmethod defer-fn ::op [{:keys [op]}]
  (debug ::op op)
  (when-not (or (:done op) (:error op))
    (defer {:fn ::op :op (admin/operation (:name op))} {:delay-ms 2000})))

#_(
   *ns*
   (in-ns 'jaq.deploy)

   (let [config (merge {:server-ns "jaq.runtime"
                        :target-path "/tmp/war"
                        :src ["/tmp/.cache/resources" "/tmp/.cache/src"]}
                       (parse-config (jaq.repl/get-file "jaq-config.edn")))]
     #_(defer {:fn ::deps :config config})
     #_(defer {:fn ::upload :config config})
     (defer {:fn ::deploy :service :default :config config})
     #_(defer {:fn ::migrate :config config}))

   (slurp "https://alpeware-jaq-runtime.appspot.com/public/foo.txt")

   (io/resource "public/foo.txt")

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

   *ns*
   (let [remotes
         (->> (storage/objects (str "staging." (storage/default-bucket)) {:prefix "apps/v19"})
              #_(take 2)
              (map :name)
              (map (fn [e] (-> e (clojure.string/split #"/") (last))))
              (set)
              #_(count))]
     (->> (io/file "/tmp/war")
          (file-seq)
          (filter (fn [e] (.isFile e)))
          (filter (fn [e] (->> (.getName e) (contains? remotes) (not))))
          (take 1)
          #_(map (fn [e] (.getName e)))
          (map (fn [e]
                 (storage/put-large "staging.alpeware-jaq-runtime.appspot.com" (.getPath e) "/tmp/war" "apps/v19" {:callback storage/file-upload-done})))
          (doall)
          #_(count)))

   *ns*
   (storage/put-large "staging.alpeware-jaq-runtime.appspot.com" "/tmp/war/WEB-INF/lib/clojure-1.9.0.jar" "/tmp/war" "apps/v18" {:callback storage/file-upload-done})
   (storage/put-large "staging.alpeware-jaq-runtime.appspot.com" "/tmp/war/WEB-INF/lib/appengine-api-1.0-sdk-1.9.64.jar" "/tmp/war" "apps/v19")
   (storage/put-large "staging.alpeware-jaq-runtime.appspot.com" "/tmp/war/WEB-INF/lib/appengine-tools-sdk-1.9.64.jar" "/tmp/war" "apps/v19")

   @storage/file-counter
   (reset! storage/file-counter 0)


   (->> (range 10)
        (map (fn [e]
               (let [s (str "/tmp/foo/" e)]
                 (io/make-parents s)
                 (spit s e)))))
   (->> (io/file "/tmp/test")
        (file-seq))

   (->> (storage/copy "/tmp/foo" "staging.alpeware-jaq-runtime.appspot.com" "bar")
        #_((fn [& e]) @storage/file-counter))

   *ns*
   (->> (io/file "/tmp/.cache")
        (file-seq)
        (reverse)
        (map (fn [e] (io/delete-file e)))
        (count)
        )

   (->> (io/file "/tmp/.cache/")
        (file-seq)
        (reverse)
        (filter (fn [e] (-> (.getPath e) (clojure.string/ends-with? "cp"))))
        #_(map (fn [e] (io/delete-file e)))
        #_(count)
        )
   (slurp "/tmp/war/WEB-INF/web.xml")
   (slurp "https://repo1.maven.org/")


   (-> "https://raw.githubusercontent.com/clojure/java.classpath/39854b7f9751f99b49a0644aa611d18f0c07dfe6/src/main/clojure/clojure/java/classpath.clj"
       (slurp)
       (jaq.war/string-input-stream)
       (java.io.InputStreamReader.)
       (load-reader))

   (-> "https://raw.githubusercontent.com/clojure/java.classpath/39854b7f9751f99b49a0644aa611d18f0c07dfe6/src/main/clojure/clojure/java/classpath.clj"
       (slurp)
       (load-string))

   (clojure.java.io/make-parents "/tmp/clojure/java/classpath.clj")
   (->> "https://raw.githubusercontent.com/clojure/java.classpath/39854b7f9751f99b49a0644aa611d18f0c07dfe6/src/main/clojure/clojure/java/classpath.clj"
        (slurp)
        (spit "/tmp/clojure/java/classpath.clj")
        )

   (load "/clojure/java/classpath")
   (io/make-parents "/tmp/src/jaq/foo.clj")
   (spit "/tmp/src/jaq/foo.clj" "(ns jaq.foo) (defn bar [] :bar)")

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

   (clojure.java.classpath/classpath)

   (defn add-system-classpath
     "Add an url path to the system class loader"
     [s]
     (let [field (aget (.getDeclaredFields java.net.URLClassLoader) 0)
           url (-> s (io/file) (.toURL))]
       (.setAccessible field true)
       (let [ucp (.get field (ClassLoader/getSystemClassLoader))]
         (.addURL ucp url))))

   (add-system-classpath "/tmp/.classes/")
   (load "/jaq/foo")
   (io/resource "jaq/foo.clj")
   (jaq.foo/bar)

   (->> "/tmp/src"
        (io/file))
   (file-seq)

   (->> (io/file "/tmp/war")
        (file-seq)
        #_(count))

   *ns*
   (java.net.InetAddress/getAllByName "repo1.maven.org")
   (java.net.InetAddress/getAllByName "google.com")
   (System/getProperty "sun.net.spi.nameservice.nameservers")

   (->> (System/getProperties)
        (keys))

   (admin/locations "alpeware-jaq-runtime")
   (resource/projects)


   )


;;; bootstrap alias for lein
(defn -main []
  (let [deps-edn (parse-config (slurp "/opt/deps.edn"))
        opts {:server-ns "jaq.runtime"
              :target-path "/opt/war"
              :src ["/opt/src" "/opt/resources"]
              :repl-token "foobarbaz"}]
    (exploded-war (merge deps-edn opts))))
