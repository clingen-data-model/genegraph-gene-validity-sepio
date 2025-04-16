(ns genegraph.transform.gene-validity
  (:require [genegraph.framework.app :as app]
            [genegraph.framework.protocol :as p]
            [genegraph.framework.event :as event]
            [genegraph.framework.env :as env]
            [genegraph.transform.gene-validity.gci-model :as gci-model]
            [genegraph.transform.gene-validity.sepio-model :as sepio-model]
            [genegraph.transform.gene-validity.versioning :as versioning]
            [genegraph.transform.gene-validity.website-events :as website-event]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage.rdf.jsonld :as jsonld]
            [genegraph.framework.storage :as storage]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.log :as log]
            [io.pedestal.http :as http]
            [clojure.java.io :as io]
            [clojure.set :as set])
  (:gen-class))



(def admin-env
  (if (or (System/getenv "DX_JAAS_CONFIG_DEV")
          (System/getenv "DX_JAAS_CONFIG")) ; prevent this in cloud deployments
    {:platform "stage"
     :dataexchange-genegraph (System/getenv "DX_JAAS_CONFIG")
     :local-data-path "data/"}
    {}))

(def local-env
  (case (or (:platform admin-env) (System/getenv "GENEGRAPH_PLATFORM"))
    "local" {:fs-handle {:type :file :base "data/base/"}
             :local-data-path "data/"}
    "dev" (assoc (env/build-environment "522856288592" ["dataexchange-genegraph"])
                 :version 2
                 :name "dev"
                 :kafka-user "User:2189780"
                 :fs-handle {:type :gcs
                             :bucket "genegraph-framework-dev"}
                 :local-data-path "/data")
    "stage" (assoc (env/build-environment "583560269534" ["dataexchange-genegraph"])
                   :version 1
                   :name "stage"
                   :function (System/getenv "GENEGRAPH_FUNCTION")
                   :kafka-user "User:2592237"
                   :fs-handle {:type :gcs
                               :bucket "genegraph-gene-validity-sepio-stage-1"}
                   :local-data-path "/data")
    "prod" (assoc (env/build-environment "974091131481" ["dataexchange-genegraph"])
                  :function (System/getenv "GENEGRAPH_FUNCTION")
                  :version 1
                  :name "prod"
                  :kafka-user "User:2592237"
                  :fs-handle {:type :gcs
                              :bucket "genegraph-gene-validity-sepio-prod-1"}
                  :local-data-path "/data")
    {}))

(def env
  (merge local-env admin-env))

(defn qualified-kafka-name [prefix]
  (str prefix "-" (:name env) "-" (:version env)))

(def consumer-group
  (qualified-kafka-name "gg-gvs2-nt"))

(def data-exchange
  {:type :kafka-cluster
   :kafka-user (:kafka-user env)
   :common-config {"ssl.endpoint.identification.algorithm" "https"
                   "sasl.mechanism" "PLAIN"
                   "request.timeout.ms" "20000"
                   "bootstrap.servers" "pkc-4yyd6.us-east1.gcp.confluent.cloud:9092"
                   "retry.backoff.ms" "500"
                   "security.protocol" "SASL_SSL"
                   "sasl.jaas.config" (:dataexchange-genegraph env)}
   :consumer-config {"key.deserializer"
                     "org.apache.kafka.common.serialization.StringDeserializer"
                     "value.deserializer"
                     "org.apache.kafka.common.serialization.StringDeserializer"}
   :producer-config {"key.serializer"
                     "org.apache.kafka.common.serialization.StringSerializer"
                     "value.serializer"
                     "org.apache.kafka.common.serialization.StringSerializer"}})

(def gene-validity-version-store
  {:name :gene-validity-version-store
   :type :rocksdb
   :snapshot-handle (assoc (:fs-handle env)
                           :path "genegraph-version-store-snapshot-v5.tar.lz4")
   :path (str (:local-data-path env) "version-store")})

(def prop-query
  (rdf/create-query "select ?x where {?x a ?type}"))

(defn add-iri-fn [event]
  (assoc event
         ::event/iri
         (-> (prop-query
              (:gene-validity/model event)
              {:type :cg/EvidenceStrengthAssertion})
             first
             str)))

(def add-iri
  (interceptor/interceptor
   {:name ::add-iri
    :enter (fn [e] (add-iri-fn e))}))

(defn add-publish-actions-fn [event]
  (-> event
      (event/publish (-> event
                         (set/rename-keys {::event/iri ::event/key
                                           :gene-validity/model ::event/data})
                         (select-keys [::event/key ::event/data])
                         (assoc ::event/topic :gene-validity-sepio)))
      (event/publish (-> event
                         (set/rename-keys {::event/iri ::event/key
                                           :gene-validity/json-ld ::event/data})
                         (select-keys [::event/key ::event/data])
                         (assoc ::event/topic :gene-validity-sepio-jsonld)))))

(def add-publish-actions
  (interceptor/interceptor
   {:name ::add-publish-actions
    :enter (fn [e] (add-publish-actions-fn e))}))

(defn report-transform-errors-fn [event]
  (Thread/startVirtualThread
   (fn []
     (case (deref (::event/completion-promise event) (* 1000 5) :timeout)
       :timeout (log/warn :fn ::report-transform-errors
                          :msg "timeout"
                          :offset (::event/offset event)
                          :key (::event/key event))
       false (log/warn :fn ::report-transform-errors
                          :msg "processing error"
                          :offset (::event/offset event)
                          :key (::event/key event))
       true)))
  event)


(def report-transform-errors
  {:name ::report-transform-errors
   :enter (fn [e] (report-transform-errors-fn e))
   :error (fn [e ex] (log/warn :fn ::report-transform-errors
                               :msg "error in interceptors"
                               :offset (::event/offset e)
                               :key (::event/key e)
                               :exception ex)
            e)})

(def json-ld-frame
  (jsonld/json-file->doc (io/resource "frame.json")))

(defn add-jsonld-fn [event]
  (assoc event
         :gene-validity/json-ld
         (jsonld/model->json-ld
          (:gene-validity/model event)
          json-ld-frame)))

(def add-jsonld
  (interceptor/interceptor
   {:name ::add-jsonld
    :enter (fn [e] (add-jsonld-fn e))}))

(def transform-processor
  {:type :processor
   :name :gene-validity-transform
   :subscribe :gene-validity-complete
   :backing-store :gene-validity-version-store
   :interceptors [report-transform-errors
                  gci-model/add-gci-model
                  sepio-model/add-model
                  versioning/add-version
                  add-jsonld
                  add-iri
                  website-event/website-version-interceptor
                  add-publish-actions]})

(def gene-validity-complete-topic
  {:name :gene-validity-complete
   :kafka-cluster :data-exchange
   :serialization :json
   :buffer-size 5
   :kafka-topic "gene_validity_complete"
   :kafka-topic-config {}})

(def gene-validity-sepio-topic 
  {:name :gene-validity-sepio
   :kafka-cluster :data-exchange
   :serialization ::rdf/n-triples
   :kafka-topic (qualified-kafka-name "gg-gvs2")
   :kafka-topic-config {}})

(def gene-validity-sepio-jsonld-topic 
  {:name :gene-validity-sepio
   :kafka-cluster :data-exchange
   :kafka-topic (qualified-kafka-name "gg-gvs2-jsonld")
   :kafka-topic-config {}})

(def gv-ready-server
  {:gene-validity-server
   {:type :http-server
    :name :gv-ready-server
    ::http/host "0.0.0.0"
    ::http/allowed-origins {:allowed-origins (constantly true)
                            :creds true}
    ::http/routes
    [["/ready"
      :get (fn [_] {:status 200 :body "server is ready"})
      :route-name ::readiness]
     ["/live"
      :get (fn [_] {:status 200 :body "server is live"})
      :route-name ::liveness]]
    ::http/type :jetty
    ::http/port 8888
    ::http/join? false
    ::http/secure-headers nil}})

(def gv-transformer-def
  {:type :genegraph-app
   :kafka-clusters {:data-exchange data-exchange}
   :topics {:gene-validity-complete
            (assoc gene-validity-complete-topic
                   :type :kafka-consumer-group-topic
                   :kafka-consumer-group consumer-group
                   :buffer-size 5)
            :gene-validity-sepio
            (assoc gene-validity-sepio-topic
                   :type :kafka-producer-topic)
            :gene-validity-sepio-jsonld
            (assoc gene-validity-sepio-jsonld-topic
                   :type :kafka-producer-topic)}
   :storage {:gene-validity-version-store gene-validity-version-store}
   :processors {:gene-validity-transform
                (assoc transform-processor
                       :kafka-cluster :data-exchange
                       :kafka-transactional-id (qualified-kafka-name "gv-transform"))}
   :http-servers gv-ready-server})

(defn store-snapshots! [app]
  (->> (:storage app)
       (map val)
       (filter :snapshot-handle)
       (run! storage/store-snapshot)))

(defn periodically-store-snapshots
  "Start a thread that will create and store snapshots for
   storage instances that need/support it. Adds a variable jitter
   so that similarly configured apps don't try to backup at the same time."
  [app period-hours run-atom]
  (let [period-ms (* 60 60 1000 period-hours)]
    (Thread/startVirtualThread
     (fn []
       (while @run-atom
         (Thread/sleep period-ms)
         (try
           (store-snapshots! app)
           (catch Exception e
             (log/error :fn ::periodically-store-snapshots
                        :exception e))))))))

(defn -main [& args]
  (log/info :msg "starting genegraph gene validity transform")
  (let [app (p/init gv-transformer-def)
        run-atom (atom true)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (log/info :fn ::-main
                                           :msg "stopping genegraph")
                                 (reset! run-atom false)
                                 (p/stop app))))
    (p/start app)
    (periodically-store-snapshots app 6 run-atom)))
