(ns genegraph.transform.gene-validity
  (:require [genegraph.framework.app :as app]
            [genegraph.framework.protocol :as p]
            [genegraph.framework.event :as event]
            [genegraph.framework.env :as env]
            [genegraph.transform.gene-validity.gci-model :as gci-model]
            [genegraph.transform.gene-validity.sepio-model :as sepio-model]
            [genegraph.transform.gene-validity.versioning :as versioning]
            [genegraph.framework.storage.rdf :as rdf]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.log :as log]
            [io.pedestal.http :as http]
            [clojure.set :as set]))



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
                 :version 7
                 :name "dev"
                 :kafka-user "User:2189780"
                 :fs-handle {:type :gcs
                             :bucket "genegraph-framework-dev"}
                 :local-data-path "/data")
    "stage" (assoc (env/build-environment "583560269534" ["dataexchange-genegraph"])
                   :version 7
                   :name "stage"
                   :function (System/getenv "GENEGRAPH_FUNCTION")
                   :kafka-user "User:2592237"
                   :fs-handle {:type :gcs
                               :bucket "genegraph-gene-validity-stage-1"}
                   :local-data-path "/data")
    "prod" (assoc (env/build-environment "974091131481" ["dataexchange-genegraph"])
                  :function (System/getenv "GENEGRAPH_FUNCTION")
                  :version 4
                  :name "prod"
                  :kafka-user "User:2592237"
                  :fs-handle {:type :gcs
                              :bucket "genegraph-gene-validity-prod-1"}
                  :local-data-path "/data")
    {}))

(def env
  (merge local-env admin-env))

(defn qualified-kafka-name [prefix]
  (str prefix "-" (:name env) "-" (:version env)))

(def consumer-group
  (qualified-kafka-name "gg-gvs2"))

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
                           :path "genegraph-version-store-snapshot-v4.tar.lz4")
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
  (event/publish event
                 (-> event
                     (set/rename-keys {::event/iri ::event/key
                                       :gene-validity/model ::event/data})
                     (select-keys [::event/key ::event/data])
                     (assoc ::event/topic :gene-validity-sepio))))

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

(def transform-processor
  {:type :processor
   :name :gene-validity-transform
   :subscribe :gene-validity-complete
   :backing-store :gene-validity-version-store
   :interceptors [report-transform-errors
                  gci-model/add-gci-model
                  sepio-model/add-model
                  versioning/add-version
                  add-iri
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
                   :type :kafka-producer-topic)}
   :storage {:gene-validity-version-store gene-validity-version-store}
   :processors {:gene-validity-transform
                (assoc transform-processor
                       :kafka-cluster :data-exchange
                       :kafka-transactional-id (qualified-kafka-name "gv-transform"))}
   :http-servers gv-ready-server})
