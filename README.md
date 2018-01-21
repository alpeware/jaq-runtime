# JAQ Runtime

A Clojure library designed to make using Google App Engine and Google Cloud
Platform usage idiomatic.

## Installation

Available on Clojars:

```
[com.alpeware/jaq-runtime "0.1.0-SNAPSHOT"]
```

## Status

Alpha quality with some API changes expected.

## Runtime

Create new file ```src/jaq/bootstrap.clj````

``` clojure
(ns jaq.bootstrap
  (:require
   [ring.util.response :as response]
   [jaq.runtime :refer [listener-fn]]
   [jaq.services.util :refer [remote! repl-server]]))

(defmethod listener-fn :init [_]
  (repl-server))

(defmethod listener-fn :destroy [_])

(defn handler [request]
  (println request)
  (-> (response/response "foo")
      (response/content-type "text/plain")))

(intern 'jaq.runtime 'handler handler)
```

Add to ```project.clj``` -

``` clojure
  :dependencies
    [[com.alpeware/jaq-runtime "0.1.0-SNAPSHOT"]
    [com.google.appengine/appengine-api-1.0-sdk ~sdk-version]
    [com.google.appengine/appengine-api-labs ~sdk-version]
    [com.google.appengine/appengine-remote-api ~sdk-version]
    [com.google.appengine/appengine-tools-sdk ~sdk-version]]
```

## License

Copyright © 2017 Alpeware, LLC.

Distributed under the Eclipse Public License, the same as Clojure.
