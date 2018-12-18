(ns jaq.browser
  (:require
   [cljs.reader :refer [read-string]]
   [cljs.core.async :refer [put! chan <! >! timeout close!
                            onto-chan alts! go-loop go]
    :include-macros true]
   [taoensso.timbre :as timbre :refer [log info warn error debug]
    :include-macros true]
   [reagent.core :as r]
   [reagent.cookies :as cookies]
   [ajax.core :refer [GET POST]]
   [ajax.edn :refer [edn-response-format edn-request-format]]))

(defonce state (r/atom {:repl {:val "cljs.core => "
                               :type :cljs
                               :broadcast false
                               :class "collapse"
                               :show false}}))

(defn get-device-id []
  (or (:device-id @state)
      (cookies/get :device-id)
      (let [device-id (str (random-uuid))
            _ (cookies/set! :device-id device-id)]
        device-id))
  #_(let [device-id (get :device-id @state (str (random-uuid)))]
      (if (cookies/contains-key? :device-id)
        (cookies/get :device-id)
        (do
          (cookies/set! :device-id device-id)
          device-id))))

(defn get-session-id []
  (let [session-id (get :session-id @state (str (random-uuid)))]
    (when-not (= session-id (:session-id @state))
      (swap! state assoc :session-id session-id))
    session-id))

;;;; Message handling ;;;;
(def polling 5)

(defonce control-ch (chan))
(defonce in-ch (chan))
(defonce event-ch (chan))

(defonce message-outbox (atom []))

;;;;;; Utils ;;;;;;

(defn enqueue [m]
  (swap! message-outbox conj (merge {:device-id (get-device-id)} m)))

(defn current-timestamp
  "Current timestamp in UTC."
  []
  #_(-> (time-core/now)
        (time-coerce/to-long))
  (.now js/Date))

;;;;;

(defmulti api-fn :fn)

(defmethod api-fn ::add-message [{:keys [value timestamp device-id]
                                  :or {timestamp (current-timestamp)
                                       device-id (:device-id @state)}
                                  :as m}]
  (let [message {:value value :timestamp timestamp :device-id device-id}]
    (swap! state update-in [:messages] conj message)
    (enqueue (merge message {:fn :broadcast-message}))))

(defmethod api-fn :new-message [{:keys [value timestamp device-id]
                                 :as m}]
  (info :new-message m)
  (swap! state update-in [:messages] conj m))

(defmethod api-fn :pong [{:keys [device-id cnt]
                          :or {cnt 0}}]
  (enqueue {:fn :ping :device-id device-id :cnt (inc cnt)})
  #_(swap! state assoc-in [:output] cnt))

(defmethod api-fn :default [{:keys [fn]}]
  (error "Unknown fn:" fn))

;;;;
(defn message-processor
  "Process incoming messages."
  [in-ch process-fn control-ch]
  (go-loop []
    (let [[e ch] (alts! [control-ch in-ch])]
      (when-not (= ch control-ch)
        (process-fn e)
        (recur)))))

;; an event: [:entity :action :context :timestamp]

(defn mailman
  "Poor man's bi-directional communication."
  [id in-ch outbox control-ch polling-ms]
  (go-loop []
    (let [[_ ch] (alts! [control-ch (timeout polling-ms)])
          out @outbox
          cnt (count out)
          token (:token @state)]
      (when-not (= ch control-ch)
        (POST "/api" {:format (edn-request-format)
                      :headers {"X-CSRF-Token" token}
                      :response-format (edn-response-format)
                      :keywords? true
                      :params {:fn :syncer :id id :messages out}
                      :handler (fn [{:keys [id messages] :as m}]
                                 (debug "received" m)
                                 (swap! outbox (fn [e] (drop cnt e)))
                                 (onto-chan in-ch messages false))
                      :error-handler (fn [e]
                                       ;; handle 403 by refreshing token
                                       (warn "Transient network error:" e))})
        (recur)))))

(defn start! []
  (let [device-id (get-device-id)
        token (:token (read-string js/STATE))
        _ (swap! state assoc-in [:token] token)]
    (debug "token" token "state" @state)
    (debug "Starting message processor incoming")
    (message-processor in-ch api-fn control-ch)
    (debug "Starting message processor events")
    (message-processor event-ch api-fn control-ch)
    (debug "Starting mailman")
    (mailman device-id in-ch message-outbox control-ch (* 1000 polling))
    (debug "Starting session")
    (enqueue {:fn :open-session :device-id device-id})
    #_(enqueue {:fn :ping :device-id device-id :cnt 0})))
