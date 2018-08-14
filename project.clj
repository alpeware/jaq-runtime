(def sdk-version "1.9.60")

(defproject com.alpeware/jaq-runtime "0.1.0-SNAPSHOT"
  :description "JAQ - Bringing Clojure to Google App Engine"
  :url "http://www.alpeware.com"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :local-repo ".m2"

  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/clojurescript "1.9.946"]
                 [org.clojure/tools.deps.alpha "0.5.442"]
                 [org.clojure/core.async "0.4.474"]

                 [com.alpeware/jaq-services "0.1.0-SNAPSHOT"]
                 [com.google.appengine/appengine-api-1.0-sdk ~sdk-version]
                 [com.google.appengine/appengine-api-labs ~sdk-version]
                 [com.google.appengine/appengine-remote-api ~sdk-version]
                 [com.google.appengine/appengine-tools-sdk ~sdk-version]

                 [com.taoensso/timbre "4.10.0"]

                 [bidi "2.1.3"]

                 [ring/ring "1.6.3"]
                 [ring/ring-core "1.6.3"]
                 [ring/ring-defaults "0.3.2"]
                 [fogus/ring-edn "0.3.0"]]

  :plugins [[com.alpeware/lein-jaq "0.1.0-SNAPSHOT"]]

  :aot [jaq.runtime]

  :ring {:handler jaq.runtime/app
         :init jaq.runtime/init
         :destroy jaq.runtime/destroy
         :web-xml "war-resources/web.xml"}

  :jaq {:sdk-path "sdk"
        :sdk-version ~sdk-version
        :war-app-path "war"
        :generated-dir "data"
        :default-gcs-bucket "staging.alpeware-jaq-runtime.appspot.com"
        :address "0.0.0.0"
        :port 3000
        :project-id "alpeware-jaq-runtime"
        :project-name "alpeware-jaq-runtime"
        :location-id "us-central"
        :bucket "staging.alpeware-jaq-runtime.appspot.com"
        :prefix "apps/v1"
        :version "v1"})
