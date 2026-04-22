(ns genegraph.transform.gene-validity
  (:require [genegraph.framework.app :as app]
            [genegraph.framework.protocol :as p]
            [genegraph.framework.event :as event]
            [genegraph.framework.env :as env]
            [genegraph.transform.gene-validity.event-recorder :as recorder]
            [genegraph.transform.gene-validity.gci-model :as gci-model]
            [genegraph.transform.gene-validity.sepio-model :as sepio-model]
            [genegraph.transform.gene-validity.proposition :as proposition]
            [genegraph.transform.gene-validity.versioning :as versioning]
            [genegraph.transform.gene-validity.website-events :as website-event]
            [genegraph.transform.gene-validity.validation :as validation]
            [genegraph.transform.gene-validity.abbreviate :as abbrev]
            [genegraph.transform.gene-validity.changes :as changes]
            [genegraph.transform.gene-validity.status :as status]
            [genegraph.transform.gene-validity.snapshot :as snapshot]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage.rdf.jsonld :as jsonld]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.storage.rocksdb :as rocksdb]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.log :as log]
            [charred.api :as charred]
            [clojure.java.io :as io]
            [clojure.set :as set])
  (:import [java.time Instant])
  (:gen-class))

(def admin-env
  (if (or (System/getenv "DX_JAAS_CONFIG_DEV")
          (System/getenv "DX_JAAS_CONFIG")) ; prevent this in cloud deployments
    {:platform "local"
     :dataexchange-genegraph (System/getenv "DX_JAAS_CONFIG")
     :local-data-path "data/"}
    {}))

(def local-env
  (case (or (:platform admin-env) (System/getenv "GENEGRAPH_PLATFORM"))
    "local" {:fs-handle {:type :file :base "data/base/"}
             :public-fs-handle {:type :file :base "data/public/"}
             :versions {:gene-validity/gci-model 1
                        :gene-validity/unmodified-model 1
                        :gene-validity/model 1
                        :gene-validity/json-ld 1
                        :gene-validity/website-event 1}
             :local-data-path "data/"}
    "dev" (assoc (env/build-environment "522856288592" ["dataexchange-genegraph"])
                 :version 2
                 :name "dev"
                 :kafka-user "User:2189780"
                 :fs-handle {:type :gcs
                             :bucket "genegraph-framework-dev"}
                 :public-fs-handle {:type :gcs
                                    :bucket "genegraph-dev-public"}
                 :local-data-path "/data")
    "stage" (assoc (env/build-environment #_"583560269534"
                                          "974091131481"
                                          ["dataexchange-genegraph"])
                   :version 1
                   :name "stage"
                   :kafka-user "User:2592237"
                   :versions {:gene-validity/gci-model 1
                              :gene-validity/unmodified-model 1
                              :gene-validity/model 1
                              :gene-validity/json-ld 1
                              :gene-validity/website-event 1}
                   :fs-handle {:type :gcs
                               :bucket "genegraph-gene-validity-sepio-stage"}
                   :rdf-topic "gene-validity-sepio-stage"
                   :json-topic "gene-validity-sepio-jsonld-stage"
                   :records-topic "gene-validity-records-stage"
                   :public-fs-handle {:type :gcs
                                      :bucket "genegraph-stage-public"}
                   :local-data-path "/data")
    "prod" (assoc (env/build-environment "974091131481" ["dataexchange-genegraph"])
                  :version 1
                  :name "prod"
                  :kafka-user "User:2592237"
                  :public-fs-handle {:type :gcs
                                     :bucket "genegraph-public"}
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

(defn reprocess-events
  ([app]
   (reprocess-events app {}))
  ([app opts]
   (->> (rocksdb/range-get @(get-in app [:storage :gene-validity-version-store :instance])
                           {:prefix [:events :gene-validity-complete]
                            :return :ref})
        (map (fn [e] (merge @e opts)))
        (run! #(p/publish (get-in app [:topics :transform-topic]) %)))))

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
              {:type :cg/Statement})
             first
             str)))

(def add-iri
  (interceptor/interceptor
   {:name ::add-iri
    :enter (fn [e]
             (add-iri-fn e))}))

(defn add-publish-actions-fn [{:gene-validity/keys [model json-ld website-event] :as event}]
  (cond-> event
    model (event/publish (-> event
                             (set/rename-keys {::event/iri ::event/key
                                               :gene-validity/model ::event/data})
                             (select-keys [::event/key ::event/data])
                             (assoc ::event/topic :gene-validity-sepio)))
    json-ld (event/publish (-> event
                               (set/rename-keys {::event/iri ::event/key
                                                 :gene-validity/json-ld ::event/data})
                               (select-keys [::event/key ::event/data])
                               (assoc ::event/topic :gene-validity-sepio-jsonld)))
    website-event (event/publish (-> event
                                     (set/rename-keys {::event/iri ::event/key
                                                       :gene-validity/website-event ::event/data})
                                     (select-keys [::event/key ::event/data])
                                     (assoc ::event/topic :all-curation-events)))
    true (event/publish {::event/data (abbrev/abbreviate event)
                    ::event/topic :processing-records-topic})))

(def add-publish-actions
  (interceptor/interceptor
   {:name ::add-publish-actions
    :enter (fn [e]
             (add-publish-actions-fn e))}))

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

(defn add-timestamp-fn [e n a]
  (let [t (.toEpochMilli (Instant/now))]
    (if (::timestamps e)
      (update e ::timestamps conj {:name n
                                   :action a
                                   :time (.toEpochMilli (Instant/now))
                                   :delta (- t (-> e ::timestamps last :time))})
      (assoc e ::timestamps [{:name n
                              :action a
                              :time (.toEpochMilli (Instant/now))}]))))

(defn add-timestamp [n]
  (interceptor/interceptor
   {:name n
    :enter (fn [e] (add-timestamp-fn e n :enter))
    :leave (fn [e] (add-timestamp-fn e n :leave))}))


(defn tap-interceptor-fn [e]
  (when (:pp-model e) (rdf/pp-model (:gene-validity/model e)))
  (when (:tap-json e) (-> e
                          :gene-validity/json-ld
                          charred/read-json
                          tap>))
  (cond (:tap-abbrev e) (tap> (abbrev/abbreviate e))
        (:tap-without-models e) (tap> (dissoc e
                                              :gene-validity/gci-model
                                              :gene-validity/model
                                              :gene-validity/unmodified-model
                                              :gene-validity/previous-model))
        (:tap-all e) (tap> e))
  e)

(defn log-incoming-message [e]
  #_(log/info :interceptor ::tap-interceptor
            :on :enter
            :key (::event/key e))
  e)

(def tap-interceptor
  (interceptor/interceptor
   {:name :tap-interceptor
    :enter (fn [e] (log-incoming-message e))
    :leave (fn [e] (tap-interceptor-fn e))}))

(def saved-keys
  #{:gene-validity/gci-model
    :gene-validity/json-ld
    :gene-validity/model
    :gene-validity/unmodified-model
    :gene-validity/website-event})

(def transform-processor
  {:type :processor
   :name :gene-validity-transform
   :subscribe :transform-topic
   #_#_:backing-store :gene-validity-version-store
   ::event/metadata (select-keys env [:versions :public-fs-handle])
   :interceptors [tap-interceptor
                  #_recorder/record-event
                  (recorder/add-saved-data saved-keys)
                  report-transform-errors
                  abbrev/add-initial-attributes
                  gci-model/add-gci-model
                  abbrev/add-gci-model-attributes
                  sepio-model/add-model
                  recorder/add-previous-version
                  proposition/rename-proposition-interceptor
                  add-iri
                  abbrev/add-model-attributes
                  changes/add-changes
                  versioning/add-version
                  website-event/website-version-interceptor
                  validation/validate
                  add-jsonld
                  add-publish-actions]})

(def snapshot-writer
  {:type :processor
   :name :snapshot-writer
   :subscribe :trigger-snapshot
   ::event/metadata (select-keys env [:public-fs-handle :versions])
   :interceptors [snapshot/write-snapshots]})

(defn gci-event-fn [event]
  (let [e1 (select-keys event [::event/key
                               ::event/data
                               ::event/offset
                               ::event/kafka-topic
                               ::event/timestamp])]
    (-> event
        (event/store
         :gene-validity-version-store
         [:events :gene-validity-complete (::event/offset event)]
         e1)
        (event/publish (assoc e1 ::event/topic :transform-topic)))))

(def gci-event
  (interceptor/interceptor
   {:name ::gci-event
    :enter (fn [e] (gci-event-fn e))}))

(def gci-event-processor
  {:type :processor
   :name :gci-event-processor
   :subscribe :gene-validity-complete
   :backing-store :gene-validity-version-store
   :interceptors [gci-event]})

(def all-curation-events
  {:name :all-curation-events
   :kafka-cluster :data-exchange
   :serialization :json
   :buffer-size 5
   :kafka-topic "all-curation-events"
   :kafka-topic-config {}})

(def gene-validity-complete-topic
  {:name :gene-validity-complete
   :kafka-cluster :data-exchange
   :serialization :json
   :buffer-size 5
   :kafka-topic "gene_validity_all"
   :kafka-topic-config {}})

(def gene-validity-sepio-topic 
  {:name :gene-validity-sepio
   :kafka-cluster :data-exchange
   :serialization ::rdf/n-triples
   :kafka-topic (:rdf-topic env "gene-validity-sepio-stage")
   :kafka-topic-config {"cleanup.policy" "compact"
                        "delete.retention.ms" "100"}})

(def gene-validity-sepio-jsonld-topic 
  {:name :gene-validity-sepio-jsonld
   :kafka-cluster :data-exchange
   :kafka-topic (:json-topic env "gene-validity-sepio-jsonld-stage")
   :kafka-topic-config {"cleanup.policy" "compact"
                        "delete.retention.ms" "100"}})

(def processing-records-topic
  {:name :processing-records-topic
   :kafka-cluster :data-exchange
   :serialization :edn
   :kafka-topic (:records-topic env "gene-validity-records-stage")
   :kafka-topic-config {}})

(def status-processor
  {:name :status-processor
   :type :processor
   :interceptors [status/report-status]})


(def gv-ready-server
  {:gene-validity-server
   {:type :http-server
    :name :gv-ready-server
    :endpoints [{:path "/status"
                 :processor :status-processor
                 :method :get}]
    :routes
    [["/ready"
      :get (fn [_] {:status 200 :body "server is ready"})
      :route-name ::readiness]
     ["/live"
      :get (fn [_] {:status 200 :body "server is live"})
      :route-name ::liveness]]
    :port 8888}})

(def gv-transformer-def
  {:type :genegraph-app
   :kafka-clusters {:data-exchange data-exchange}
   :topics {:gene-validity-complete
            (assoc gene-validity-complete-topic
                   :type :kafka-reader-topic
                   :buffer-size 5
                   :reset-opts {})
            :transform-topic
            {:name :transform-topic
             :type :simple-queue-topic}
            :gene-validity-sepio
            (assoc gene-validity-sepio-topic
                   :type :kafka-producer-topic
                   :reset-opts {:clear-topic true})
            :gene-validity-sepio-jsonld
            (assoc gene-validity-sepio-jsonld-topic
                   :type :kafka-producer-topic
                   :reset-opts {:clear-topic true})
            :processing-records-topic
            (assoc processing-records-topic
                   :type :kafka-producer-topic
                   :reset-opts {:clear-topic true})
            :all-curation-events
            (assoc all-curation-events
                   :type :kafka-producer-topic
                   :reset-opts {:clear-topic true})
            :trigger-snapshot
            {:name :trigger-snapshot
             :type :timer-topic
             :interval (* 1000 60 60)}}
   :storage {:gene-validity-version-store (assoc gene-validity-version-store
                                                 :reset-opts {:destroy-snapshot true})}
   :processors {:gene-validity-transform
                (assoc transform-processor
                       :kafka-cluster :data-exchange
                       :kafka-transactional-id (qualified-kafka-name "gv-transform"))
                :gci-event-processor gci-event-processor
                :snapshot-writer snapshot-writer
                :status-processor status-processor}
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
