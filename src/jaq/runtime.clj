(ns jaq.runtime
  (:require
   [ring.util.response :as response]
   [ring.util.servlet :refer [defservice]]
   [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
   [ring.middleware.params :refer [wrap-params]]
   [jaq.services.util :refer [remote! repl-server]]))

(declare handler)

(defmulti listener-fn :fn)
(defmethod listener-fn :default [_])

(defn init []
  (require 'jaq.bootstrap)
  (listener-fn {:fn :init}))

(defn destroy []
  (listener-fn {:fn :destroy}))

(def app
  (-> #'handler
      (wrap-defaults site-defaults)))

(defservice app)
