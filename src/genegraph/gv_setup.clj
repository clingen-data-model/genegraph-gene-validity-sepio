(ns genegraph.gv-setup
  "Functions and procedures generally intended to be run from the REPL
  for the purpose of configuring and maintaining a genegraph gene validity
  instance."
  (:require [genegraph.gene-validity :as gv]
            [genegraph.framework.app :as app]
            [genegraph.framework.protocol :as p]
            [genegraph.framework.event.store :as event-store]
            [genegraph.framework.event :as event]
            [genegraph.framework.kafka.admin :as kafka-admin]
            [genegraph.framework.storage :as storage]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.log :as log]
            [clojure.set :as set]
            [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:import [java.time Instant OffsetDateTime Duration]
           [java.io PushbackReader]))

;; Step 1
;; Select the environment to configure

;; The gv/admin-env var specifies the platform to target and the
;; data exchange credentials to use. Set at least the :platform
;; and :dataexchange-genegraph keys for the correct environment
;; and kafka instance. This should be done in genegraph/gene_validity.clj

;; Step 2
;; Set up kafka topics, configure permissions

;; The kafka-admin/configure-kafka-for-app! function accepts an
;; (initialized) Genegraph app and creates the topics and necessary
;; permissions for those topics.

;; There are four Genegraph instances that need to be set up to create a
;; working installation:

;; gv-base-app-def: listents to fetch topic, retrieves base data and notifies gql endpoint
;; gv-transformer-def: Transforms gene validity curations to SEPIO format, publishes to Kafka
;; gv-graphql-endpoint-def: Ingest curations from various sources, publish via GraphQL endpoint
;; gv-appender-def: Append topics that require pre-seeding (gene_validity_raw, gene_validity)
;;                  with data produced by GCI.

(comment
  (run! #(kafka-admin/configure-kafka-for-app! (p/init %))
        [gv/gv-base-app-def
         gv/gv-transformer-def
         gv/gv-graphql-endpoint-def])

  ;; Delete all (or some) Genegraph-created topics
  ;; Use this to fix mistakes.
  (with-open [admin-client (kafka-admin/create-admin-client gv/data-exchange)]
    (run! #(try
             (kafka-admin/delete-topic admin-client (:kafka-topic %))
             (catch Exception e
               (log/info :msg "Exception deleting topic "
                         :topic (:kafka-topic %))))
          [#_gv/fetch-base-events-topic
           #_gv/base-data-topic
           #_gv/gene-validity-complete-topic
           #_gv/gene-validity-legacy-complete-topic
           #_gv/gene-validity-sepio-topic
           #_gv/api-log-topic]))
  )

;; Step 3
;; Seed newly created topics with initialization data. Use the local Genegraph
;; app definitions and rich comments to set up these topics.

;; Three topics need to be seeded with initialization data:

;; Step 3.1
;; :fetch-base-events: requests to update the base data supporting Genegraph
;; The data needed to seed this topic is stored in version control with
;;    genegraph-gene-validity in resources/base.edn

(def gv-seed-base-event-def
  {:type :genegraph-app
   :kafka-clusters {:data-exchange gv/data-exchange}
   :topics {:fetch-base-events
            (assoc gv/fetch-base-events-topic
                   :type :kafka-producer-topic
                   :create-producer true)}})

(comment
  (def gv-seed-base-event
    (p/init gv-seed-base-event-def))

  (p/start gv-seed-base-event)
  (p/stop gv-seed-base-event)
  
  (->> (-> "base.edn" io/resource slurp edn/read-string)
       (run! #(p/publish (get-in gv-seed-base-event
                                 [:topics :fetch-base-events])
                         {::event/data %
                          ::event/key (:name %)})))

  (->> (-> "base.edn" io/resource slurp edn/read-string)
       (filter #(= "http://purl.obolibrary.org/obo/mondo.owl" (:name %)))
       (run! #(p/publish (get-in gv-seed-base-event
                                 [:topics :fetch-base-events])
                         {::event/data %
                          ::event/key (:name %)})))

  (->> (-> "base.edn" io/resource slurp edn/read-string)
       (filter #(= "http://dataexchange.clinicalgenome.org/gci-express" (:name %)))
       (run! #(p/publish (get-in gv-seed-base-event
                                 [:topics :fetch-base-events])
                         {::event/data %
                          ::event/key (:name %)})))

  (p/stop gv-seed-base-event)
  )

;; Step 3.2
;; :gene-validity-legacy: old 'summary' format for gene validity data, needs to be seeded with some
;;                        early data that needed to be recovered due to a misconfiguration.
;; The data needed to seed this topic is stored in Google Cloud Storage
;; and must be downloaded and unzipped into a local directory.

;; Set this var for the path on your local system
(def gene-validity-legacy-path
  "/Users/tristan/data/genegraph-neo/neo4j-legacy-events")

;; This step has been flaky on occasion. Remember to verfiy that all historic curations have been
;; successfully written to the topic before moving on.

(def upload-gv-neo4j-def
  {:type :genegraph-app
   :kafka-clusters {:data-exchange gv/data-exchange}
   :topics {:gene-validity-legacy-complete
            (assoc gv/gene-validity-legacy-complete-topic
                   :type :kafka-producer-topic
                   :create-producer true)}})

(defn legacy-gv-edn->event [f]
  (with-open [pbr (PushbackReader. (io/reader f))]
    (let [{:keys [id score-string]} (:genegraph.sink.event/value (edn/read pbr))]
      {::event/data {:iri id
                     :scoreJson score-string}
       ::event/key id})))

(comment
  (def upload-gv-neo4j
    (p/init upload-gv-neo4j-def))
  (p/start upload-gv-neo4j)
  (p/stop upload-gv-neo4j)

  (->> gene-validity-legacy-path
       io/file
       file-seq
       (filter #(.isFile %))
       (run! #(p/publish (get-in upload-gv-neo4j
                                 [:topics  :gene-validity-legacy-complete])
                         (legacy-gv-edn->event %))))


  (->> gene-validity-legacy-path
       io/file
       file-seq
       (filter #(.isFile %))
       (map legacy-gv-edn->event)
       (take 5)
       tap>)

  )

;; Step 3.3
;; :gene-validity-complete: raw gene validity data,
;; needs to be seeded with gene validity curations
;; made prior to release of the GCI
;; The data needed to seed this topic is stored in Google Cloud Storage

;; This is another potentially flaky one. Remember to verfiy that all historic curations have been
;; successfully written to the topic before moving on.

(def gv-setup-def
  {:type :genegraph-app
   :kafka-clusters {:data-exchange gv/data-exchange}
   :topics {:gene-validity-complete
            (assoc gv/gene-validity-complete-topic
                   :type :kafka-producer-topic
                   :create-producer true
                   :serialization nil)}}) ; just copying strings, do not serialize

(def sept-1-2020
  (-> (OffsetDateTime/parse "2020-09-01T00:00Z")
      .toInstant
      .toEpochMilli))

(defn prior-event->publish-fn [file]
  (with-open [pbr (PushbackReader. (io/reader file))]
    (-> (edn/read pbr)
        (set/rename-keys {:genegraph.sink.event/key ::event/key
                          :genegraph.sink.event/value ::event/data})
        (select-keys [::event/key ::event/data])
        (assoc ::event/timestamp sept-1-2020))))

(defn event-files [directory]
  (->> directory
       io/file
       file-seq
       (filter #(re-find #".edn" (.getName %)))))

(comment
  (def gv-setup (p/init gv-setup-def))
  (p/start gv-setup)
  (p/stop gv-setup)

  (run! #(p/publish (get-in gv-setup [:topics :gene-validity-complete])
                   (prior-event->publish-fn %))
        (concat
         (event-files "/users/tristan/data/genegraph/2023-11-07T1617/events/:gci-raw-snapshot")
         (event-files "/users/tristan/data/genegraph/2023-11-07T1617/events/:gci-raw-missing-data")))
  
  (count
   (concat
    (event-files "/users/tristan/data/genegraph/2023-11-07T1617/events/:gci-raw-snapshot")
    (event-files "/users/tristan/data/genegraph/2023-11-07T1617/events/:gci-raw-missing-data")))
  )


