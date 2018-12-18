(ns jaq.api
  (:require
   #?(:clj [jaq.services.deferred :as deferred])))

(defmulti api-fn :fn)

(defmethod api-fn :default [params]
  {:error "Unknown fn call"
   :params params})

#?(:clj
   (defmethod api-fn :syncer [{:keys [id messages]}]
     (let [_ (->> messages
                  (map deferred/defer)
                  (doall))
           tasks (deferred/lease {:tag id})
           out (deferred/process tasks)
           _ (-> (deferred/delete tasks)
                 (doall))]
       {:fn :syncer :id id :messages out})))

#?(:clj
   (defn api-handler [{:keys [body edn-params] :as request}]
     (let [res (api-fn edn-params)]
       {:status 200 :body (pr-str res)})))

#_(

   ;; extend :init to boot your code
   (defmethod api/api-fn :init [{:keys []}]
     (prn ::init))

   ;; gracefully shutdown here
   (defmethod api/api-fn :destroy [{:keys []}]
     (prn ::destroy))

   ;; deploy new version here
   (defmethod api/api-fn :deploy [{:keys []}]
     (prn ::deploy))

   )
