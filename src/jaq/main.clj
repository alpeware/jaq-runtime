(ns jaq.main
  (:require
   [jaq.routes :as routes]
   [jaq.api :as api]))

(defmethod api/api-fn :init [{:keys []}]
  #_(swap! routes/routes )
  (prn ::init))


#_(

   (defn test-handler
     [request]
     {:status 200 :body "foo bar baz"})

   *ns*
   (in-ns 'jaq.main)
   (->> @routes/routes
        (map first))
   (swap! routes/routes conj ["/test" :get `test-handler])
   jaq.main/test-handler

   )
