(ns jaq.repl
  (:require
   [clojure.main :as main]
   [clojure.core.server :as server]
   [clojure.edn :as edn]
   [clojure.tools.reader :as reader]
   [cljs.tagged-literals :as tags]
   [clojure.core.async :refer [chan <!! <! >! put! alts! alts!! timeout close! go go-loop]
    :as async]
   [cljs.build.api :as api]
   [cljs.compiler :as comp]
   [cljs.closure :as cljsc]
   [cljs.env :as env]
   [cljs.analyzer :as ana]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [cljs.repl :as cljs-repl :refer [repl* analyze-source]]
   [taoensso.timbre :as timbre
    :refer [log  trace  debug  info  warn  error  fatal  report
            logf tracef debugf infof warnf errorf fatalf reportf
            spy get-env]]
   [jaq.services.deferred :as deferred]
   [jaq.services.util :refer [remote! repl-server prod?]])
  (:import
   #_[com.google.javascript.rhino Node]
   [com.google.javascript.jscomp CompilerOptions CompilationLevel
    CompilerOptions$LanguageMode SourceMap$Format Compiler$CodeBuilder
    SourceMap$DetailLevel ClosureCodingConvention SourceFile
    Result JSError CheckLevel DiagnosticGroups
    CommandLineRunner AnonymousFunctionNamingPolicy
    JSModule SourceMap Es6RewriteModules VariableMap]
   [java.util List Random UUID]
   [java.net ServerSocket]
   [clojure.lang LineNumberingPushbackReader]
   [com.google.appengine.api ThreadManager]
   [java.io
    PipedWriter PipedReader PrintWriter
    File BufferedInputStream BufferedReader PushbackReader
    Writer InputStreamReader IOException StringWriter ByteArrayInputStream]))

;;; CLJ REPL
(defn new-clj-session []
  (atom {:bindings (main/with-bindings
                     (get-thread-bindings))}))
(defn repl-caught
  "Default :caught hook for repl"
  [e]
  (let [ex (main/repl-exception e)
        tr (.getStackTrace ex)
        el (when-not (zero? (count tr)) (aget tr 0))]
    (str (-> ex class .getSimpleName)
         " " (.getMessage ex) " "
         (when-not (instance? clojure.lang.Compiler$CompilerException ex)
           (str " " (if el (main/stack-element-str el) "[trace missing]"))))))

(defn eval-clj [session input]
  {:pre [(instance? String input)
         (instance? clojure.lang.Atom session)]}
  (let [bindings (:bindings @session)
        result (atom nil)]
    (main/with-bindings
      (with-bindings bindings
        (try
          (let [form (read-string input)
                value (eval form)]
            (set! *3 *2)
            (set! *2 *1)
            (set! *1 value)
            (reset! result {:value (pr-str value) :ns (symbol (str *ns*))}))
          (catch Throwable e
            (set! *e e)
            (reset! result {:value (repl-caught e) :ns (symbol (str *ns*))}))
          (finally (swap! session assoc :bindings (get-thread-bindings))))))
    @result))
