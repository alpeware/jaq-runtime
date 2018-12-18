(ns jaq.ui.landing-page
  (:require #?(:cljs [jaq.browser :as browser])
            #?(:clj [jaq.api :as api])
            #?(:clj [jaq.gae.deferred :as deferred])
            #?(:clj [jaq.services.auth :as auth])
            #?(:clj [jaq.gcp.compute :as compute])
            #?(:clj [jaq.gcp.resource :as resource])
            #?(:clj [jaq.gcp.management :as management])
            #?(:clj [jaq.gcp.storage :as storage])
            #?(:clj [jaq.gcp.iam :as iam])
            #?(:clj [jaq.services.util :as util])
            #?(:clj [clojure.java.io :as io])
            #?(:cljs [reagent.core :as r])))

(def steps {:start :auth
            :auth :token
            :token :project
            :project :repl
            :repl :done
            :done :start})

#?(:cljs
   (defonce state (r/atom {:step :start
                           :message nil
                           :message-class "is-primary"
                           :url nil
                           :token nil
                           :project nil
                           :repl nil})))

#_(
   *ns*
   @state
   (-> @state :message)
   (swap! state assoc :step :start)
   (swap! state assoc :step :auth)

   (swap! state assoc :next-step :start)

   (reset! state {:step :start
                  :url nil
                  :token nil
                  :project nil
                  :repl nil})

   (->> (management/services)
        (map :serviceName)
        (sort)
        )
   )

#?(:clj
   (defmethod deferred/defer-fn :ui/process [{:keys [step payload device-id] :as params}]
     (prn step payload)
     (case step
       :auth (let [credentials (auth/default-credentials)
                   url (auth/generate-auth-url credentials @auth/cloud-scopes)]
               (-> params
                   (merge {:fn :ui/update})
                   (assoc-in [:step] :auth)
                   (assoc-in [:payload :credentials] credentials)
                   (assoc-in [:payload :url] url)
                   (deferred/add device-id)))
       :token (let [credentials (-> params :payload :credentials)
                    code (-> params :payload :code)
                    authed-credentials (try
                                         (auth/exchange-token credentials code)
                                         (catch Exception e (do (prn e) nil)))]

                #_(-> params
                      (merge {:fn :ui/process})
                      (assoc-in [:payload :credentials] authed-credentials)
                      (deferred/add device-id))
                (-> params
                    (assoc-in [:payload :credentials] authed-credentials)
                    (assoc-in [:step] :project)
                    (deferred/defer)))
       :project (let [projects
                      (binding [util/*token-fn* (fn []
                                                  (-> params
                                                      :payload
                                                      :credentials
                                                      (auth/get-valid-credentials)
                                                      :access-token))]
                        (-> (resource/projects)
                            :projects))]
                  (-> params
                      (merge {:fn :ui/update})
                      (assoc-in [:payload :projects] projects)
                      (deferred/add device-id)))
       :enable (let [{:keys [project projects]} payload
                     project-id (some->
                                 (filter (fn [e] (-> e
                                                     :projectId
                                                     (= project))) projects)
                                 (first)
                                 :projectId)]
                 (binding [util/*token-fn* (fn []
                                             (-> params
                                                 :payload
                                                 :credentials
                                                 (auth/get-valid-credentials)
                                                 :access-token))]
                   (let [project-id (or project-id
                                        (do
                                          (loop [op (resource/create project project)]
                                            (prn ::project op)
                                            (when-not (or (:error op) (:done op))
                                              (-> params
                                                  (merge {:fn :ui/update})
                                                  (assoc-in [:payload :message] "Creating project...")
                                                  (assoc-in [:payload :op] (-> op :name))
                                                  (deferred/add device-id))
                                              (util/sleep)
                                              (recur (management/operation (:name op)))))
                                          project))]
                     (loop [op (management/enable iam/service-name project-id)]
                       (prn ::iam op)
                       (when-not (or (:error op) (:done op))
                         (-> params
                             (merge {:fn :ui/update})
                             (assoc-in [:payload :message] "Enabling Cloud IAM...")
                             (assoc-in [:payload :op] (-> op :name))
                             (deferred/add device-id))
                         (util/sleep)
                         (recur (management/operation (:name op)))))
                     (loop [op (management/enable storage/service-name project-id)]
                       (prn ::storage op)
                       (when-not (or (:error op) (:done op))
                         (-> params
                             (merge {:fn :ui/update})
                             (assoc-in [:payload :message] "Enabling Storage API...")
                             (assoc-in [:payload :op] (-> op :name))
                             (deferred/add device-id))
                         (util/sleep)
                         (recur (management/operation (:name op)))))
                     (loop [op (management/enable resource/service-name project-id)]
                       (prn ::resource op)
                       (when-not (or (:error op) (:done op))
                         (-> params
                             (merge {:fn :ui/update})
                             (assoc-in [:payload :message] "Enabling Resource Manager API...")
                             (assoc-in [:payload :op] (-> op :name))
                             (deferred/add device-id))
                         (util/sleep)
                         (recur (management/operation (:name op)))))
                     (loop [op (management/enable compute/service-name project-id)]
                       (prn op)
                       (when-not (or (:error op) (:done op))
                         (-> params
                             (merge {:fn :ui/update})
                             (assoc-in [:payload :message] "Enabling Compute Engine...")
                             (assoc-in [:payload :op] (-> op :name))
                             (deferred/add device-id))
                         (util/sleep)
                         (recur (management/operation (:name op))))))
                   (-> params
                       (assoc-in [:payload :message] nil)
                       (assoc-in [:payload :projects] (->> (resource/projects) :projects))
                       (assoc-in [:step] :vm)
                       (deferred/defer))))
       :vm (let [{:keys [project projects]} payload
                 project-id project
                 _ (def p params)]
             (binding [util/*token-fn* (fn []
                                         (-> params
                                             :payload
                                             :credentials
                                             (auth/get-valid-credentials)
                                             :access-token))]
               (let [zones (->> (compute/zones project-id) (doall))
                     service-accounts (->> (iam/service-accounts project-id) (doall))
                     scopes @auth/cloud-scopes]
                 (-> params
                     (merge {:fn :ui/update})
                     (assoc-in [:payload :zones] zones)
                     (assoc-in [:payload :service-accounts] service-accounts)
                     (assoc-in [:payload :scopes] scopes)
                     (deferred/add device-id)))))
       :repl (let [{:keys [project scope service-account zone instance-name secret]} payload]
               (binding [util/*token-fn* (fn []
                                           (-> params
                                               :payload
                                               :credentials
                                               (auth/get-valid-credentials)
                                               :access-token))]
                 (let [project-id project
                       metadata {:startup-script (-> (io/resource "jaq-startup-vm.sh") (slurp))
                                 :deps (-> (io/resource "jaq-deps-vm.edn") (slurp))
                                 :JAQ_RUNTIME_COMMIT "abc"
                                 :JAQ_REPL_TOKEN secret
                                 :DEFAULT_BUCKET (str project-id ".appspot.com")
                                 :JAQ_KEYSTORE "/root/jaq-repl.jks"
                                 :JAQ_KEYSTORE_PASSWORD "jaqrepl"}
                       repl {:project-id project-id :instance-name instance-name :zone zone
                             :service-account-email service-account
                             :scopes scope :metadata metadata}]
                   (prn repl)
                   (loop [op (compute/create-instance repl)]
                     (prn ::repl op)
                     (let [message (->> ["Creating your REPL: "
                                         (:progress op)
                                         " / "
                                         (:status op)]
                                        (clojure.string/join))]
                       (when-not (or (:error op) (= (:status op) "DONE"))
                         (-> params
                             (merge {:fn :ui/update})
                             (assoc-in [:payload :message] message)
                             (assoc-in [:payload :op] (-> op :name))
                             (deferred/add device-id))
                         (util/sleep)
                         (recur (compute/operation project-id zone (:name op)))))))
                 (let [instance-ip (-> (compute/instances project zone)
                                       (first)
                                       :networkInterfaces
                                       first
                                       :accessConfigs
                                       (first)
                                       :natIP)]
                   (-> params
                       (merge {:fn :ui/update})
                       (assoc-in [:payload :message] "All done!")
                       (assoc-in [:payload :instance-ip] instance-ip)
                       (deferred/add device-id))))))))

#_(

   (->> "https://github.com/alpeware/jaq-runtime/commits/master.atom"
        (slurp)
        (re-seq #"github.com/alpeware/jaq-runtime/commit/([a-z0-9]+)")
        (first)
        (last))
   (-> (io/resource "jaq-startup-vm.sh")
       (slurp))
   p
   (binding [util/*token-fn* (fn [] (-> p
                                        :payload
                                        :credentials
                                        (auth/get-valid-credentials)
                                        :access-token))]
     #_(util/*token-fn*)
     #_(resource/projects)
     (->> (compute/zones "jaq-core")
          (doall))
     #_(->> (iam/service-accounts "jaq-core")
          (doall))
     #_(compute/instances "jaq-core" "us-central1-c")
     #_(compute/zones "alpeware-jaq-runtime"))

   (compute/create-instance {:project-id "alpeware-wealth" :instance-name "alpeware-wealth-vm" :zone :us-central1-c
                             :service-account-email "328831522370-compute@developer.gserviceaccount.com"
                             :scopes ["https://www.googleapis.com/auth/cloud-platform" "https://www.googleapis.com/auth/spreadsheets"]})

   (in-ns 'jaq.ui.landing-page)
   (loop [op (management/enable iam/service-name "alpeware-jaq-runtime")]
     (prn op)
     (when-not (:done op)
       (-> {:fn :ui/update}
           (assoc-in [:payload :message] "Enabling Cloud IAM...")
           (assoc-in [:payload :op] (-> op :name))
           (deferred/add "739ce4ea-9cf0-4b38-b6f8-eba54c440187"))
       (util/sleep)
       (recur (management/operation (:name op)))))

   (-> {:fn :ui/update}
       (assoc-in [:payload :message] "Foo")
       (deferred/add "739ce4ea-9cf0-4b38-b6f8-eba54c440187"))
   )
#_(
   *ns*
   (in-ns 'jaq.ui.landing-page)
   @state
   (->> @state keys)
   (swap! state assoc :step :start)
   (swap! state assoc :step :project)
   (swap! state assoc :step :token)
   (->> @state
        :projects
        :projects
        (swap! state assoc :projects))
   (process)
   )

#?(:cljs
   (defmethod browser/api-fn :ui/advance [{:keys [step payload]}]
     (->> #_(get steps step)
          step
          (assoc payload :step)
          (reset! state))))

#?(:cljs
   (defmethod browser/api-fn :ui/update [{:keys [step payload]}]
     (prn :ui/update step payload)
     (->> #_(get steps step)
          step
          (assoc payload :step)
          (swap! state merge))))

#?(:cljs
   (defn process []
     (browser/enqueue {:fn :ui/process
                       :step (->> @state :step #_(get steps))
                       :payload @state})
     #_(->> @state
            :next-step
            #_(get steps)
            (swap! state assoc :step))))

#?(:cljs
   (defn step-ui [{:keys [step url projects zones service-accounts scopes
                          instance-ip]}]
     (case step
       :start [:div
               [:div.field.is-grouped
                [:div.control
                 [:p "Hit the button"]]
                [:div.control
                 [:input.button.is-primary {:type "button" :value "Clone Me"
                                            :on-click (fn [e] (browser/enqueue {:fn :ui/process
                                                                                :step :auth
                                                                                :payload @state}))}]]]]
       :auth [:div
              [:div.field
               [:div.control
                [:p "Grant access to your Google Compute account and copy the verification token. "]
                [:a {:href url
                     :target "_blank"} "Auth"]]
               [:div.control
                [:input.button.is-primary {:type "button" :value "To Token"
                                           :on-click (fn [e] (swap! state assoc :step :token))}]]]]
       :token [:div.field
               [:label.label "Please enter the verification token"]
               [:div.control
                [:input.input.is-primary {:type "text" :value (-> @state :code)
                                          :on-change (fn [e] (->> e .-target .-value (swap! state assoc :code)))}]]
               [:div.control
                [:input.button.is-primary {:type "button" :value "To Projects"
                                           :on-click (fn [e] (browser/enqueue {:fn :ui/process
                                                                               :step :token
                                                                               :payload @state}))}]]]
       :project [:div
                 [:div.field
                  [:label.label "Select a Project or create a new one"]
                  [:div.control
                   [:div.select
                    [:select {:on-change (fn [e] (->> e .-target .-value (swap! state assoc :project)))}
                     (->> projects
                          (map (fn [{:keys [projectNumber projectId] :as e}]
                                 [:option {:key projectNumber :value projectId} (:name e)])))]]]]
                 [:div.field
                  [:label.label "Project ID"]
                  [:div.control
                   [:input.input.is-primary {:type "text" :value (-> @state :project)
                                             :on-change (fn [e] (->> e .-target .-value (swap! state assoc :project)))}]]]
                 [:div.field
                  [:div.control
                   [:input.button.is-primary {:type "button" :value "To VM"
                                              :on-click (fn [e] (browser/enqueue {:fn :ui/process
                                                                                  :step :enable
                                                                                  :payload @state}))}]]]]
       :enable [:div
                [:p "Preparing ..."]]
       :vm [:div
            [:div.field
             [:label.label "Select a Zone for your REPL"]
             [:div.control
              [:div.select
               [:select {:on-change (fn [e] (->> e .-target .-value (swap! state assoc :zone)))}
                (->> zones
                     (map (fn [{:keys [description id] :as e}]
                            [:option {:key id :value description} (:name e)])))]]]]
            [:div.field
             [:label.label "Select a Service Account for your REPL"]
             [:div.control
              [:div.select
               [:select {:on-change (fn [e] (->> e .-target .-value (swap! state assoc :service-account)))}
                (->> service-accounts
                     (map (fn [{:keys [displayName uniqueId email] :as e}]
                            [:option {:key uniqueId :value email} displayName])))]]]]
            [:div.field
             [:label.label "Select Scopes for your REPL"]
             [:div.control
              [:div.select.is-multiple
               [:select {:on-change (fn [e]
                                      (let [scope (->> e .-target .-value)
                                            selected (->> @state :scope set)
                                            updated (if (contains? selected scope)
                                                      (clojure.set/difference selected (set [scope]))
                                                      (clojure.set/union selected (set [scope])))]
                                        (swap! state assoc :scope updated)))
                         :size (-> scopes count)
                         :value (-> @state :scope)
                         :multiple true}
                (->> scopes
                     (map (fn [e]
                            [:option {:key e :value e} e])))]]]]
            [:div.field
             [:label.label "Give your REPL a name (no spaces)"]
             [:div.control
              [:input.input.is-primary {:type "text" :value (-> @state :instance-name)
                                        :on-change (fn [e] (->> e .-target .-value (swap! state assoc :instance-name)))}]]]
            [:div.field
             [:label.label "Choose a Secret for your REPL"]
             [:div.control
              [:input.input.is-primary {:type "text" :value (-> @state :secret)
                                        :on-change (fn [e] (->> e .-target .-value (swap! state assoc :secret)))}]]]
            [:div.field
             [:div.control
              [:input.button.is-primary {:type "button" :value "Create REPL"
                                         :on-click (fn [e] (browser/enqueue {:fn :ui/process
                                                                             :step :repl
                                                                             :payload @state}))}]]]]
       :repl [:div
              [:p "Congrats! You've got your own Cloud REPL!"]
              [:p "Copy-paste the following into a new emacs buffer " [:a {:href "https://github.com/alpeware/jaq-runtime/raw/master/resources/jaq-repl.el"
                                                                           :target "_blank"} "jaq-repl.el"]]
              [:p "Replace JAQ-REPL-TOKEN with your secret " (-> @state :secret)]
              [:p "Replace *jaq-endpoint* with https://" instance-ip "/repl"]
              [:p "Replace JAQ-DEVICE-ID (TODO)"]])))

#?(:cljs
   (defn clone-wizard []
     (let [step (-> @state :step)]
       [:div
        [:progress.progress {:value (or (-> @state :progress) 0) :max 100}
         (-> @state :progress)]
        [:hr.is-invisible]
        [step-ui @state]
        [:hr.is-invisible]
        [:div {:class (when-not (-> @state :message) "is-invisible")}
         [:div.notification {:class (-> @state :message-class)}
          [:p (-> @state :message)]]]
        #_[:div.field
           [:div.control
            [:input.button.is-primary {:type "button" :value "Clone Me"
                                       :on-click (fn [e] (process))}]]]])))

#_(
   (jaq.app/render!)

   (swap! state assoc :message-class "is-primary")
   (swap! state assoc :message "Foo bar!")
   (swap! state assoc :progress 10)

   @state
   (swap! state assoc :step :start)
   (swap! state assoc :step :project)
   (swap! state assoc :step :vm)
   (swap! state assoc :projects ["Foo" "Bar" "Baz"])

   (swap! state assoc :service-accounts [{:name "projects/alpeware-jaq-runtime/serviceAccounts/alpeware-jaq-runtime@appspot.gserviceaccount.com", :projectId "alpeware-jaq-runtime", :uniqueId "103149893445272188663", :email "alpeware-jaq-runtime@appspot.gserviceaccount.com", :displayName "App Engine default service account", :etag "BwVyuQd9YqE=", :oauth2ClientId "103149893445272188663"} {:name "projects/alpeware-jaq-runtime/serviceAccounts/937599099147-compute@developer.gserviceaccount.com", :projectId "alpeware-jaq-runtime", :uniqueId "113375519035552160146", :email "937599099147-compute@developer.gserviceaccount.com", :displayName "Compute Engine default service account", :etag "BwV3K/36x6U=", :oauth2ClientId "113375519035552160146"}])

   (clojure.set/difference #{"foo"} ["foo" "bar"])
   (swap! state assoc :scopes ["https://www.googleapis.com/auth/cloud-platform" "https://www.googleapis.com/auth/spreadsheets"])
   (->> @state :scope)
   (swap! state assoc :scope nil)
   (process)
   (->> (get steps (-> @state :step))
        (swap! state assoc :step))
   )

(defn clone-repl []
  (let [opts {:type "button" :value "CLONE ME"}
        ;;opts (merge opts #?(:clj {} :cljs (merge opts {:on-click (fn [e] (clone))})))
        ]
    [:div.section
     [#?@(:cljs [clone-wizard] :clj [:p "Loading..."])]
     #_[:div.field
        [:div.control
         [:input.button.is-primary opts]]]]))

(defn hero []
  [:section.hero.is-primary.is-medium
   [:div.hero-body
    [:div.container
     [:h1.title "JAQ - Your Cloud REPL"]
     [:h2.subtitle "Welcome to Cloud hacking."]]]])

(defn footer []
  [:footer.footer
   [:div.content.has-text-centered
    [:p
     [:strong "JAQ"] " by " [:a {:href "http://www.alpeware.com/"} "Alpeware"]]]])

(defn landing-page []
  [:div
   (hero)
   (clone-repl)
   (footer)])

#_(
   (jaq.app/render!)

   (reset! browser/message-outbox [])
   (-> js/document
       (.getElementsByTagName "input")
       (array-seq)
       (first)
       (.click))

   (in-ns 'jaq.ui.landing-page)
   (ns jaq.ui.landing-page)
   *ns*

   (.. js/window -location)
   (-> js/window
       (.-location)
       (.reload true))

   (jaq.app/render!)
   (jaq.browser/stop!)
   (require 'jaq.repl)
   )
