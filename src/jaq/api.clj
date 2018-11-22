(ns jaq.api)

(defmulti api-fn :fn)

(defmethod api-fn :default [params]
  {:error "Unknown fn call"
   :params params})
