(ns genegraph.user
  (:require [genegraph.transform.gene-validity :as gv]
            [genegraph.framework.app :as app]
            [genegraph.framework.event :as event]
            [genegraph.framework.event.store :as event-store]
            [genegraph.framework.protocol :as p]
            [genegraph.framework.kafka :as kafka]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage.rocksdb :as rocksdb]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.log :as log]
            [portal.api :as portal]
            [clojure.data.json :as json]
            [hato.client :as hc]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io])
  (:import [ch.qos.logback.classic Logger Level]
           [org.slf4j LoggerFactory]
           [java.time Instant LocalDate ]
           [org.apache.jena.rdf.model Model Statement]))

(def prop-query
  (rdf/create-query "select ?x where { ?x a :cg/GeneValidityProposition }" ))


(defn record-gv-curation-fn [e]
  (let [prop (-> e ::event/data prop-query first)
        assertion (rdf/ld1-> prop [[:cg/subject :<]])]
    #_(log/info :prop-id (str prop)
              :assertion (str assertion)
              :version (rdf/ld1-> assertion [:cg/version])
              :sequence (rdf/ld1-> prop [[:cg/subject :<] :cg/sequence]))
    (-> e
        (event/store :curation-output
                     [(str prop)
                      (rdf/ld1-> prop [[:cg/subject :<] :cg/sequence])]
                     (::event/data e))
        (event/store :curation-output
                     [(rdf/ld1-> prop [[:cg/subject :<] :cg/sequence])
                      (str prop)]
                     (::event/data e)))))

(def record-gv-curation
  {:name :record-gv-curation
   :enter (fn [e] (record-gv-curation-fn e))})

(def record-output-processor
  {:type :processor
   :name :record-output-processor
   :subscribe :gene-validity-sepio
   :interceptors [record-gv-curation]})

(def curation-output
  {:type :rocksdb
   :name :curation-output
   :path (str (:local-data-path gv/env) "gv-curation-output")})

(def test-app-def
  {:type :genegraph-app
   :topics {:gene-validity-complete
            {:type :simple-queue-topic
             :name :gene-validity-complete}
            :gene-validity-sepio
            {:type :simple-queue-topic
             :name :gene-validity-sepio}
            :gene-validity-sepio-jsonld
            {:type :simple-queue-topic
             :name :gene-validity-sepio-jsonld}}
   :storage {:gene-validity-version-store gv/gene-validity-version-store
             :curation-output curation-output}
   :processors {:gene-validity-transform gv/transform-processor
                :record-output-processor record-output-processor}})


(def root-data-dir "/Users/tristan/data/genegraph-neo/")

(defn get-events-from-topic [topic]
  ;; topic->event-file redirects stdout
  ;; need to supress kafka logs for the duration
  (.setLevel
   (LoggerFactory/getLogger Logger/ROOT_LOGGER_NAME) Level/ERROR)
  (kafka/topic->event-file
   (assoc topic
          :type :kafka-reader-topic
          :kafka-cluster gv/data-exchange)
   (str root-data-dir
        (:kafka-topic topic)
        "-"
        (LocalDate/now)
        ".edn.gz"))
  (.setLevel (LoggerFactory/getLogger Logger/ROOT_LOGGER_NAME) Level/INFO))

(comment
  (.setLevel (LoggerFactory/getLogger Logger/ROOT_LOGGER_NAME) Level/INFO)

  (def test-app (p/init test-app-def))
  (p/start test-app)
  (p/stop test-app)
  

  (time (get-events-from-topic gv/gene-validity-complete-topic))
  (+ 1 1)
  (time
   (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2024-07-16.edn.gz"]
     (->> (event-store/event-seq r)
          #_(take 5)
          (run! #(p/publish (get-in test-app [:topics :gene-validity-complete]) %)))))
  
  (defn transform-curation [e]
    (p/process (get-in test-app [:processors :gene-validity-transform])
               (assoc e
                      ::event/completion-promise (promise)
                      #_#_::event/skip-local-effects true
                      ::event/skip-publish-effects true)))

  (/ 416130.856792 1000 60)

  (->> (rocksdb/range-get @(get-in test-app [:storage :curation-output :instance]) 0 100)
       count)

  (-> (rocksdb/range-get @(get-in test-app [:storage :curation-output :instance])
                        "http://dataexchange.clinicalgenome.org/gci/93ab3f0b-c5e1-43be-b9ce-9236198e91c2")
       last
       rdf/to-turtle
       println)
  
  (time
   (tap>
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2024-07-16.edn.gz"]
      (->> (event-store/event-seq r)
           (take 1)
           (map transform-curation)
           (map (fn [e] (println (rdf/to-turtle (:gene-validity/model e))) e))
           (map #(assoc %
                        ::json-data
                        (json/read-str
                         (:gene-validity/json-ld %)
                         :key-fn keyword)))
           (map #(dissoc % :gene-validity/gci-model :gene-validity/model))
           (into [])))))

  (time
   (tap>
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2024-07-16.edn.gz"]
      (->> (event-store/event-seq r)
           (filter #(re-find #"cb06ff0d-1cc6-494c-9ce5-f7cb26f34620"
                             (::event/value %)))
           (take-last 1)
           (map transform-curation)
           (map (fn [e] (println (rdf/to-turtle (:gene-validity/model e))) e))
           (map #(assoc %
                        ::json-data
                        (json/read-str
                         (:gene-validity/json-ld %)
                         :key-fn keyword)))
           #_(map #(dissoc % :gene-validity/gci-model :gene-validity/model))
           (map ::json-data)
           (into [])))))

  (time
   (tap>
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2024-07-16.edn.gz"]
      (->> (event-store/event-seq r)
           (filter #(re-find #"cb06ff0d-1cc6-494c-9ce5-f7cb26f34620"
                             (::event/value %)))
           (take-last 1)
           (map transform-curation)
           (map (fn [e] (println (rdf/to-turtle (:gene-validity/model e))) e))
           (run! #(do (spit "/users/tristan/desktop/zeb2.ttl"
                            (rdf/to-turtle (:gene-validity/model %)))
                      (spit "/users/tristan/desktop/zeb2.json"
                            (:gene-validity/json-ld %))))))))
  
)


(comment
  (def portal (portal/open))
  (add-tap #'portal/submit)
  (portal/close)
  (portal/clear)
  )


(comment
  (def gv-dev (p/init gv/gv-transformer-def))
  (p/start gv-dev)
  (p/stop gv-dev)
  
)

;; GCEP productivity report


(comment

  (def affiliations-csv
    (-> (hc/get "https://docs.google.com/spreadsheets/d/1IF9GiP8iiFx1CndgqdWNGx4A_uM2GsWnV7GSiUO33bs/gviz/tq?tqx=out:csv&sheet=VCI%2FGCI%20Affiliations%20List")
        :body))
  (def affiliations
    (->> (csv/read-csv affiliations-csv)
         rest
         #_(take 5)
         (mapv (fn [[aff-name id]] [id aff-name]))
         (into {})))

  (def q4
    (let [start-time (.toEpochMilli (Instant/parse "2024-10-01T00:00:00Z"))
          end-time (.toEpochMilli (Instant/parse "2025-01-01T00:00:00Z"))]
      (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2025-01-06.edn.gz"]
        (->> (event-store/event-seq r)
             #_(take 1)
             (filter #(and (< start-time (::event/timestamp %))
                           (< (::event/timestamp %) end-time)))
             (into [])))))

  (def recuration
    #{:cg/RecurationCommunityRequest
      :cg/RecurationTiming
      :cg/RecurationNewEvidence})

  (def new-curation
    #{:cg/NewCuration})
  
  (do
    (defn curation-facts [e]
      (let [m (:gene-validity/model e)
            q (rdf/create-query "select ?x where { ?x a :cg/EvidenceStrengthAssertion }")
            source-q (rdf/create-query "
select ?a where { 
?contrib :cg/role :cg/Approver ;
         :cg/agent ?a
}")
            assertion (first (q m))]
        {:curation-reason (rdf/ld1-> assertion [:cg/curationReasons])
         :source (first (source-q m))}))
    (def q4-facts
      (->> q4
           (map transform-curation)
           (mapv curation-facts))))
  (with-open [w (io/writer "/Users/tristan/Desktop/q4-gcep-report.csv")]
    (csv/write-csv w
     (->> (remove #(or (nil? (:curation-reason %))
                       (nil? (:source %)))
                  q4-facts)
          (group-by :source)
          (mapv (fn [[k v]]
                  [(affiliations (re-find #"\d+" (str k)))
                   (count (filter new-curation (map rdf/->kw (map :curation-reason v))))
                   (count (filter recuration (map rdf/->kw (map :curation-reason v))))]))
          (cons ["Expert Panel" "New Curations" "Re-curations"]))))

  (->> q4-facts
       (map :curation-reason)
       frequencies
       tap>)
  
  )

