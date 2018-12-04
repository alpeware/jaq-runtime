(ns jaq.ui.landing-page
  (:require #?(:cljs [jaq.browser :as browser])
            #?(:clj [jaq.api :as api])
            #?(:clj [jaq.services.deferred :as deferred])
            #?(:clj [jaq.services.auth :as auth])
            #?(:clj [jaq.services.resource :as resource])
            #?(:clj [jaq.services.compute :as compute])
            #?(:clj [jaq.services.management :as management])
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
       :start-obsolete (let [_ (prn :start step)]
                         (-> params
                             (merge {:fn :ui/update})
                             (assoc-in [:step] :auth)
                             (assoc-in [:payload :message] "Hit the button to get started.")
                             (assoc-in [:payload :next-step] :token)
                             (deferred/add device-id))
                         (prn :done))
       :auth (let [credentials (auth/default-credentials)
                    url (auth/generate-auth-url credentials @auth/cloud-scopes)]
                (-> params
                    (merge {:fn :ui/update})
                    (assoc-in [:step] :auth)
                    (assoc-in [:payload :credentials] credentials)
                    (assoc-in [:payload :url] url)
                    (deferred/add device-id)))
       :auth-obsolete (-> params
                 (merge {:fn :ui/update})
                 (assoc-in [:step] :token)
                 (deferred/add device-id))
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
       :project (let [projects (->
                                (with-redefs [jaq.services.util/get-token (fn []
                                                                            (-> params
                                                                                :payload
                                                                                :credentials
                                                                                (auth/get-valid-credentials)
                                                                                :access-token))]
                                  (resource/projects))
                                :projects)]
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
                                 :projectId)])
       :repl
       (let [{:keys [project projects]} payload
             project-id (some->
                         (filter (fn [e] (-> e
                                             :projectId
                                             (= project))) projects)
                         (first)
                         :projectId)
             zones (with-redefs [jaq.services.util/get-token (fn []
                                                               (-> params
                                                                   :payload
                                                                   :credentials
                                                                   (auth/get-valid-credentials)
                                                                   :access-token))]
                     #_(compute/zones project-id)
                     (resource/projects))]
         (prn ::zones zones)
         #_(-> params
               (merge {:fn :ui/advance})
               #_(assoc-in [:payload :zones] zones)
               (deferred/add device-id))))))

#_(
   *ns*
   (in-ns 'jaq.ui.landing-page)
   (swap! state assoc :step :start)
   (swap! state assoc :step :project)
   (swap! state assoc :step :token)
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
          (reset! state))))

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
   (defn step-ui [{:keys [step url projects zones]}]
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
                                             :on-change (fn [e] (->> e .-target .-value (swap! state assoc :project)))}]]
                  [:div.control
                   [:input.button.is-primary {:type "button" :value "To REPL"
                                              :on-click (fn [e] (browser/enqueue {:fn :ui/process
                                                                                  :step :repl
                                                                                  :payload @state}))}]]]]
       :repl [:div
              [:div.field
               [:label.label "Select a Zone for your REPL"]
               [:div.control
                [:div.select
                 [:select {:on-change (fn [e] (->> e .-target .-value (swap! state assoc :zone)))}
                  (->> zones
                       (map (fn [{:keys [deScription id] :as e}]
                              [:option {:key id :value description} (:name e)])))]]]
               ]])))

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
   (swap! state assoc :projects ["Foo" "Bar" "Baz"])

   (process)
   (->> (get steps (-> @state :step))
        (swap! state assoc :step))
   )

(defn clone-repl []
  (let [opts {:type "button" :value "CLONE ME"}
        opts (merge opts #?(:clj {} :cljs (merge opts {:on-click (fn [e] (clone))})))]
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
