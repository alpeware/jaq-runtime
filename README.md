# JAQ Runtime

A library to bring Clojure to the Google Cloud Platform.

## Installation

Use in ```deps.edn``` -

```
{com.alpeware/jaq-runtime {:git/url "https://github.com/alpeware/jaq-runtime"
                            :sha "LATEST SHA"}}
```

## Status

Alpha quality with some API changes expected.

## Runtime

Runtime to provide a HTTP server on either App Engine or Google Compute Engine.

``` bash
DEFAULT_BUCKET=PROJECT-ID.appspot.com \
JAQ_REPL_TOKEN="REPL TOKEN" \
JAQ_MAIN_NS="MAIN NS" \
JAQ_KEYSTORE=jaq-repl.jks \
JAQ_KEYSTORE_PASSWORD=pwd \
JAQ_SSL_PORT=443 \
JAQ_HTTP_PORT=80 \
clojure -A:vm -m jaq.runtime
```

## License

Copyright © 2017 Alpeware, LLC.

Distributed under the Eclipse Public License, the same as Clojure.
