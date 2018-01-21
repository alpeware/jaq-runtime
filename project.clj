(def sdk-version "1.9.60")

(defproject com.alpeware/jaq-runtime "0.1.0-SNAPSHOT"
  :description "JAQ - Bringing Clojure to Google App Engine"
  :url "http://www.alpeware.com"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :local-repo ".m2"

  :dependencies [[org.clojure/clojure "1.9.0"]

                 [com.alpeware/jaq-services "0.1.0-SNAPSHOT"]
                 [com.google.appengine/appengine-api-1.0-sdk ~sdk-version]
                 [com.google.appengine/appengine-api-labs ~sdk-version]
                 [com.google.appengine/appengine-remote-api ~sdk-version]
                 [com.google.appengine/appengine-tools-sdk ~sdk-version]

                 [ring/ring "1.6.3"]
                 [ring/ring-defaults "0.3.1"]]

  :plugins [[com.alpeware/lein-jaq "0.1.0-SNAPSHOT"]]

  :ring {:handler jaq-test.core/app
         :init jaq-test.core/init
         :destroy jaq-test.core/destroy
         :web-xml "war-resources/web.xml"}

  :jaq {:sdk-path "sdk"
        :sdk-version ~sdk-version
        :war-app-path "war"
        :generated-dir "data"
        :default-gcs-bucket "staging.alpeware-foo-bar.appspot.com"
        :address "0.0.0.0"
        :port 3000
        :project-id "alpeware-foo-bar"
        :project-name "projects/alpeware-foo-bar"
        :location-id "europe-west3"
        :bucket "staging.alpeware-foo-bar.appspot.com"
        :prefix "apps/latest"
        :version "v2"})
