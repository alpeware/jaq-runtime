(ns jaq.routes
  (:require
   [jaq.api :refer [api-handler]]
   [jaq.repl :refer [repl-handler index-handler]]
   [io.pedestal.http.body-params :as body-params]
   [io.pedestal.http.ring-middlewares :as ring-middlewares]))

(def routes
  (atom #{["/dev" :get [{:name ::remove
                         :leave (fn [{response :response :as context}]
                                  (let [c (->> context
                                               :response
                                               :headers
                                               ((fn [e] (merge e {"content-security-policy" "" })))
                                               (assoc-in context [:response :headers]))]
                                    c)
                                  )}
                        `index-handler]]
          ["/repl" :post [(body-params/body-params) `repl-handler]]
          ["/api" :post [(body-params/body-params) `api-handler]]
          ["/out/*" :get [{:name ::remove
                           :leave (fn [{response :response :as context}]
                                    (let [c (->> context
                                                 :response
                                                 :headers
                                                 ((fn [e] (merge e {"content-security-policy" ""
                                                                    "cache-control" "no-cache"
                                                                    "content-type" "text/javascript"})))
                                                 (assoc-in context [:response :headers]))]
                                      c))}
                          (ring-middlewares/file "/tmp/")]]}))
