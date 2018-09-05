 (def sdk-version "1.9.64")

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

                 [io.pedestal/pedestal.service "0.5.4"]
                 [io.pedestal/pedestal.service-tools "0.5.4"]]

  :plugins [[com.alpeware/lein-jaq "0.1.0-SNAPSHOT"]]

  :aliases {"bootstrap" ["run" "-m" "jaq.deploy"]}

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
        :prefix "apps/v17"
        :version "v17"})
