(ns rentpath.dev
  "Dev mode utilities fns. Includes a -main for dev server mode."
  (:require [clojure.core.async :as a]
            [clojure.tools.namespace.repl :as r]
            [integrant.core :as ig]
            [rentpath.scores.http-server :as server]
            [rentpath.scores.datomic :as dat]
            [rentpath.score-events-gen :as gevent]
            [clojure.test.check.generators :as gen]
            [datomic.api :as d]))

(defonce system nil)

(def config
  ^{:doc
    "The config for dev mode. We simulate a durable queue
     by directly hooking up a core.async/chan to the appserver
     and the async-writer.

     The upside of going w/ core.async here is that we can
     change the reader of this chan to put to a durable queue,
     without changing any surrounding code."}
  {::server/jetty {:port 5000
                   :join? false
                   :handler (ig/ref ::server/handler)}
   :datomic/uri "datomic:mem://rentpath-scores"
   ::async-chan {}
   ::server/handler {:datomic/uri (ig/ref :datomic/uri)
                     ::server/async-chan (ig/ref ::async-chan)}
   ::dat/async-writer {:datomic/uri (ig/ref :datomic/uri)
                       ::dat/chan-in (ig/ref ::async-chan)}})

(defmethod ig/init-key ::async-chan [_ _]
  (a/chan 1024))

(defmethod ig/halt-key! ::async-chan [_ c]
  (a/close! c))

(defmethod ig/init-key :datomic/uri [_ uri]
  ;; prep uri w/ schema in dev mode
  (d/create-database uri)
  @(d/transact-async (d/connect uri)
                     dat/schema)
  uri)

(defn go []
  (alter-var-root #'system (constantly config))
  (alter-var-root #'system ig/init))

(defn stop []
  (ig/halt! system))

(defn reset []
  (stop)
  (r/refresh :after `go))

(defn -main
  "Starts an in-memory version of the score service. Includes
  both the Http Server and the Async Writer."
  [& args]
  (let [{:keys [::server/jetty] :as sys} (ig/init config)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(ig/halt! sys)))
    (.join jetty)))

(comment
  (def c (d/connect (:datomic/uri system))))
