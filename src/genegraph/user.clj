(ns genegraph.user
  (:require [genegraph.transform.gene-validity :as gv]
            [genegraph.transform.gene-validity.sepio-model :as sepio-model]
            [genegraph.transform.gene-validity.gci-model :as gci-model]
            [genegraph.transform.gene-validity.versioning :as versioning]
            [genegraph.transform.gene-validity.website-events :as website-events]
            [genegraph.transform.gene-validity.event-recorder :as recorder]
            [genegraph.framework.app :as app]
            [genegraph.framework.event :as event]
            [genegraph.framework.event.store :as event-store]
            [genegraph.framework.protocol :as p]
            [genegraph.framework.kafka :as kafka]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage.rocksdb :as rocksdb]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.processor :as processor]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.log :as log]
            [portal.api :as portal]
            [clojure.data.json :as json]
            [hato.client :as hc]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.math :as math]
            [clojure.set :as set]
            [charred.api :as charred]
            [clojure.walk :as walk])
  (:import [ch.qos.logback.classic Logger Level]
           [org.slf4j LoggerFactory]
           [java.time Instant LocalDate LocalDateTime ZoneOffset]
           [org.apache.jena.rdf.model Model Statement]
           [java.util.concurrent ThreadPoolExecutor SynchronousQueue TimeUnit
            ThreadPoolExecutor$CallerRunsPolicy Semaphore]))

(def prop-query
  (rdf/create-query "select ?x where { ?x a :cg/GeneValidityProposition }" ))

(def assertion-query
  (rdf/create-query "select ?x where { ?x a :cg/EvidenceStrengthAssertion }" ))

(defn record-gv-curation-fn [e]
  (if-let [assertion (-> e ::event/data assertion-query first)]
    (if-let [original-version (rdf/ld1-> assertion [:dc/isVersionOf])]
      (event/store e
                   :curation-output
                   [(str original-version)
                    (str assertion)]
                   (::event/data e))
      e)
    e))

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
   :path (str (:local-data-path gv/env) "gv-curation-output")
   :reset-opts {}})

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
   :storage {:gene-validity-version-store
             (assoc gv/gene-validity-version-store :reset-opts {})
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


  (defn transform-curation [e]
    (p/process (get-in test-app [:processors :gene-validity-transform])
               (assoc e
                      ::event/completion-promise (promise)
                      ::event/skip-local-effects true
                      ::event/skip-publish-effects true)))
  
  (let [a (p/init test-app-def)]
    (p/reset a))

  (.start
   (Thread.
    #(do
       (println "getting gv-complete")
       (time (get-events-from-topic gv/gene-validity-complete-topic)))))
  (+ 1 1)
  (time
   (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2024-07-16.edn.gz"]
     (->> (event-store/event-seq r)
          #_(take 5)
          (run! #(p/publish (get-in test-app [:topics :gene-validity-complete]) %)))))
  


  (/ 416130.856792 1000 60)

  (->> (rocksdb/range-get @(get-in test-app [:storage :curation-output :instance]) 0 100)
       count)

  (-> (rocksdb/range-get @(get-in test-app [:storage :curation-output :instance])
                        "https://genegraph.clinicalgenome.org/r/gci/93ab3f0b-c5e1-43be-b9ce-9236198e91c2")
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
  (do
    (def portal (portal/open))
    (add-tap #'portal/submit))
  (portal/close)
  (portal/clear)
  )

(+ 1 1 )

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

  (def q1
    (let [start-time (.toEpochMilli (Instant/parse "2025-01-01T00:00:00Z"))
          end-time (.toEpochMilli (Instant/parse "2025-04-01T00:00:00Z"))]
      (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2025-04-01.edn.gz"]
        (->> (event-store/event-seq r)
             (filter #(and (< start-time (::event/timestamp %))
                           (< (::event/timestamp %) end-time)))
             (into [])))))

  (def recuration
    #{:cg/RecurationCommunityRequest
      :cg/RecurationTiming
      :cg/RecurationNewEvidence
      :cg/RecurationDiscrepancyResolution
      :cg/RecurationErrorAffectingScoreorClassification
      :cg/RecurationFrameworkChange})

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
    (def q1-facts
      (->> q1
           (map transform-curation)
           (mapv curation-facts))))
  (with-open [w (io/writer "/Users/tristan/Desktop/q1-gcep-report.csv")]
    (csv/write-csv w
     (->> (remove #(or (nil? (:curation-reason %))
                       (nil? (:source %)))
                  q1-facts)
          (group-by :source)
          (mapv (fn [[k v]]
                  [(affiliations (re-find #"\d+" (str k)))
                   (count (filter new-curation (map rdf/->kw (map :curation-reason v))))
                   (count (filter recuration (map rdf/->kw (map :curation-reason v))))]))
          (cons ["Expert Panel" "New Curations" "Re-curations"]))))

  (->> q1-facts
       (map :curation-reason)
       frequencies
       tap>)
  
  )

(comment
  (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])]
    (storage/read db "https://genegraph.clinicalgenome.org/r/gci/01f588c4-4fef-493d-b5e0-a76fb9492244"))
  )


;;Limited curations that have been recurated and whether the classification has stayed the same, upgraded, downgraded, and the amount of time that had passed.
;;Curations that were downgraded to limited after a recuration occurred

(def assertion-query
  (rdf/create-query "select ?x where 
{ ?x a :cg/EvidenceStrengthAssertion . }"))

(def approval-date-query
  (rdf/create-query "select ?c where 
{ ?x :cg/contributions ?c . 
  ?c :cg/role :cg/Approver . } "))

(defn approval-date [x]
  (some-> (approval-date-query x {:x x})
          first
          (rdf/ld1-> [:cg/date])))

(defn has-publish-action [m]
  (< 0 (count ((rdf/create-query "select ?x where { ?x :cg/role :cg/Publisher } ") m))))

(def classification-ordinals
  {:cg/Disputed -1
   :cg/Refuted -1
   :cg/NoKnownDiseaseRelationship 0
   :cg/Limited 1
   :cg/Moderate 2
   :cg/Strong 3
   :cg/Definitive 4})


(defn highest-classification [[k curation-sequence]]
  (reduce
   max
   -2
   (map #(classification-ordinals (:evidenceStrength %))
        curation-sequence)))

(defn lowest-classification [[k curation-sequence]]
  (reduce
   max
   -2
   (map #(classification-ordinals (:evidenceStrength %))
        curation-sequence)))

(defn gci-link [[k _]]
  (str "https://curation.clinicalgenome.org/curation-central/"
       (subs k 43)
       "/"))

(count "3e96651d-5979-416b-abc5-2e6702c35871")

(count "https://genegraph.clinicalgenome.org/r/gci/")
(gci-link
 ["https://genegraph.clinicalgenome.org/r/gci/3e96651d-5979-416b-abc5-2e6702c35871" nil]
 )


(comment
 (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2025-04-01.edn.gz"]
       (->> (event-store/event-seq r)
            (filterv #(re-find #"d1230a85-2a8b-4321-b36d-213daae9a28a"
                               (::event/value %)))
            (map #(transform-curation %))
            (filter #(has-publish-action (:gene-validity/model %)))
            (mapv #(dissoc % :gene-validity/model :gene-validity/gci-model))
            tap>
            #_(mapv #(let [a (first (assertion-query %))
                           gdm (rdf/ld1-> a [:cg/subject])]
                       {:gdm (str gdm)
                        :gene (str (rdf/ld1-> gdm [:cg/gene]))
                        :disease (str (rdf/ld1-> gdm [:cg/disease]))
                        :moi (rdf/->kw (rdf/ld1-> gdm [:cg/modeOfInheritance]))
                        :evidenceStrength (rdf/->kw (rdf/ld1-> a [:cg/evidenceStrength]))
                        :curationReasons (mapv rdf/->kw (rdf/ld-> a [:cg/curationReasons]))
                        :approvalDate (approval-date a)}))))

 (+ 1 1)
  )


(comment
  (time
   (def gdv-summary-events
     (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2025-04-01.edn.gz"]
       (->> (event-store/event-seq r)
            (map #(:gene-validity/model (transform-curation %)))
            (filter has-publish-action)
            (mapv #(let [a (first (assertion-query %))
                         gdm (rdf/ld1-> a [:cg/subject])]
                     {:gdm (str gdm)
                      :gene (str (rdf/ld1-> gdm [:cg/gene]))
                      :disease (str (rdf/ld1-> gdm [:cg/disease]))
                      :moi (rdf/->kw (rdf/ld1-> gdm [:cg/modeOfInheritance]))
                      :evidenceStrength (rdf/->kw (rdf/ld1-> a [:cg/evidenceStrength]))
                      :curationReasons (mapv rdf/->kw (rdf/ld-> a [:cg/curationReasons]))
                      :approvalDate (approval-date a)}))))))

  "d1230a85-2a8b-4321-b36d-213daae9a28a"


  (count gdv-summary-events)
  
  (def curations-with-limited
    (->> gdv-summary-events
         (filter #(= :cg/Limited (:evidenceStrength %)))
         (map :gdm)
         set))

  (count curations-with-limited)

  (->>  gdv-summary-events
        (filter #(curations-with-limited (:gdm %)))
        (group-by :gdm)
        (filter (fn [[k v]]
                  (and (< 1 (count v))
                       (< 1 (-> (map :evidenceStrength v) set count)))))
        #_(take 5)
        (map (fn [gc]
               {:gc gc
                :highest (highest-classification gc)
                :last (-> gc val last :evidenceStrength classification-ordinals)
                :gci-link (gci-link gc)}))
        (filter (fn [{:keys [highest last]}]
                  (= last 0)))
        count
        )

  ;; interval between when classification could have been upgraded
  ;; and when it was upgraded

  ;; number of limited curations where no additional evidence
  ;; has been added over n years/recuration cycles

  ;; Consider especially total points, esp limited with very few points

  ;; Start with the 108 has been evaluated, no change
  ;; provide list where:
  ;; Dates of recuration
  ;; total points


  ;; checkbox for earliest report > earliest paper with
  ;; genetic evidence


  ;; Spreadsheet for full gene curation call
  ;; Recurations done
  ;; GDM GCEP 1st 2nd 3rd reclassification date Total points, genetic points, exp points date of first publication.
  
  ;; Recurations not done
  ;; same columns as above, w/o recurations obviously.

  ;; list of Limited > 3yo, without recuration

  #_(/ 363835.795167 1000 60)
  )



;; WARNING: Non well-formed subject [https://genegraph.clinicalgenome.org/r/gci/FTM/Transman/Transgender Male] has been skipped.

;; 


(comment
  ;; Exploring SHACL testing for data integrity

   (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2025-04-01.edn.gz"]
       (->> (event-store/event-seq r)
            (take 1)
            (mapv #(transform-curation %))
            (run! #(-> % :gene-validity/model rdf/pp-model))))
  )

(defn gdm-id [event]
  (let [gdm-id-paths [[::event/data :resourceParent :gdm :PK]
                      [::event/data :properties :resourceParent :gdm :uuid]
                      [::event/data :resourceParent :gdm :uuid]]]
    (some #(get-in event %) gdm-id-paths)))

(defn add-gdm-id [event]
  (assoc event ::gdm-id (gdm-id event)))


(comment
  (def stat3
    (let [source-file "/Users/tristan/data/genegraph-neo/gene_validity_complete-2025-07-02.edn.gz"
          filter-str "0204e276-fa45-4756-a380-eb494f5237f8"]
      (event-store/with-event-reader [r source-file]
        (->> (event-store/event-seq r)
             (filter #(re-find (re-pattern filter-str) (::event/value %)))
             first))))

  (-> stat3
      transform-curation
      :gene-validity/gci-model
      (rdf/union sepio-model/gdm-sepio-relationships)
      sepio-model/construct-functional-evidence
      rdf/pp-model)

  (-> stat3
      transform-curation
      :gene-validity/gci-model
      rdf/pp-model)

  (rdf/resource :cggv/evidenceScore)
  
  )

(comment
  (def stag1
    (let [source-file "/Users/tristan/data/genegraph-neo/gene_validity_complete-2025-07-02.edn.gz"
          filter-str "4f30eccd-ee01-4dc2-b656-c40caffd7c06"]
      (event-store/with-event-reader [r source-file]
        (->> (event-store/event-seq r)
             (filter #(re-find (re-pattern filter-str) (::event/value %)))
             first))))

  
  (-> stag1
      transform-curation
      :gene-validity/gci-model
      (sepio-model/construct-articles {:pmbase "https://pubmed.ncbi.nlm.nih.gov/"})
      rdf/pp-model)

  (-> stag1
      transform-curation
      :gene-validity/gci-model
      rdf/pp-model)

    (-> stag1
      transform-curation
      :gene-validity/model
      rdf/pp-model)

  (rdf/resource :cggv/evidenceScore)
  
  )

(comment
  "75516cff-17fd-47bd-8873-862b66741de2"
  (def mgme1
    (let [source-file "/Users/tristan/data/genegraph-neo/gene_validity_complete-2025-07-02.edn.gz"
          filter-str "75516cff-17fd-47bd-8873-862b66741de2"]
      (event-store/with-event-reader [r source-file]
        (->> (event-store/event-seq r)
             (filter #(re-find (re-pattern filter-str) (::event/value %)))
             first))))

  (-> mgme1
      transform-curation
      :gene-validity/gci-model
      (rdf/union sepio-model/gdm-sepio-relationships)
      sepio-model/construct-functional-evidence
      rdf/pp-model)

  (-> mgme1
      transform-curation
      :gene-validity/gci-model
      rdf/pp-model)

  (-> mgme1
      transform-curation
      :gene-validity/model
      rdf/pp-model)

  
  )


(comment
  (do
    (defn transform-curation-for-writer [e]
      (spit "/Users/tristan/Desktop/last-record.txt"
            (::event/key e))
      (p/process (get-in test-app [:processors :gene-validity-transform])
                 (assoc e ::event/completion-promise (promise))))

    (defn sepio-publish-event [source-event]
      (-> (filter #(= :gene-validity-sepio (::event/topic %))
                  (::event/publish source-event))
          first
          (assoc ::event/format ::rdf/n-triples
                 ::event/timestamp (::event/timestamp source-event))
          event/serialize
          (dissoc ::event/data)))
    
    (defn write-transformed-events [source-file target-file filter-str]
      ;; topic->event-file redirects stdout
      ;; need to supress kafka logs for the duration
      (.setLevel
       (LoggerFactory/getLogger Logger/ROOT_LOGGER_NAME) Level/ERROR)
      (event-store/with-event-writer [w (io/file target-file)]
        (event-store/with-event-reader [r source-file]
          (->> (event-store/event-seq r)
               (filter #(re-find (re-pattern filter-str) (::event/value %)))
               
               (map #(-> %
                         transform-curation-for-writer
                         sepio-publish-event))
               (run! prn))))
      (.setLevel (LoggerFactory/getLogger Logger/ROOT_LOGGER_NAME) Level/INFO)))

  #_(write-transformed-events "/Users/tristan/data/genegraph-neo/gene_validity_complete-2025-05-21.edn.gz"
                              "1bb8bc84-fe02-4a05-92a0-c0aacf897b6e")

  (write-transformed-events "/Users/tristan/data/genegraph-neo/gene_validity_complete-2025-07-02.edn.gz"
                            "/Users/tristan/data/genegraph-neo/abcd1-events2.edn.gz"
                            "815e0f84-b530-4fd2-81a9-02e02bf352ee")
  (time
   (write-transformed-events "/Users/tristan/data/genegraph-neo/gene_validity_complete-2025-07-29.edn.gz"
                             "/Users/tristan/data/genegraph-neo/gv-sepio-2025-07-29-changes.edn.gz"
                             ""))

  (+ 1 1 )
  
  ;; Versioning identifiers
  (time
   (def gdm-ids
     (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2025-05-21.edn.gz"]
       (->> (event-store/event-seq r)
            #_(take-last 1)
            #_(mapv #(transform-curation %))
            (map #(-> %
                      event/deserialize
                      gdm-id))
            (into [])))))
  
  (def examples
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2025-05-21.edn.gz"]
      (->> (event-store/event-seq r)
           (take 1)
           (mapv #(transform-curation %))
           (mapv #(-> % keys))
           tap>)))

  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2025-05-21.edn.gz"]
    (->> (event-store/event-seq r)
         (take 1)
         (map #(transform-curation %))
         (run! #(-> % :gene-validity/model rdf/pp-model))))

  (-> examples first :gene-validity/model rdf/pp-model)

  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2025-05-21.edn.gz"]
    (->> (event-store/event-seq r)
         (take 1)
         (mapv transform-curation-for-writer)
         (mapv sepio-publish-event)
         #_(mapv :gene-validity/website-event)
         #_(mapv #(dissoc % :gene-validity/gci-model :gene-validity/model))
         
         tap>
         #_(run! #(rdf/pp-model (:gene-validity/model %)))))

  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2025-05-21.edn.gz"]
    (->> (event-store/event-seq r)
         (take 1)
         tap>))

  (let [es-q (rdf/create-query
              "select ?x where { ?x a :cg/EvidenceStrengthAssertion }")]
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/abcd1-events.edn.gz"]
      (->> (event-store/event-seq r)
           (mapv #(-> %
                      event/deserialize
                      ::event/data
                      es-q
                      first
                      str))
           tap>)))

  (let [es-q (rdf/create-query
              "select ?x where { ?x a :cg/EvidenceStrengthAssertion }")]
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/abcd1-events2.edn.gz"]
      (->> (event-store/event-seq r)
           count)))

  "981c47f7-74ed-4cea-8df4-6d8df4bd0383v2.0"

  (def abcd1-events
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2025-07-29.edn.gz"]
      (->> (event-store/event-seq r)
           (filter #(re-find #"1bb8bc84-fe02-4a05-92a0-c0aacf897b6e"
                             (::event/value %)))
           (into []))))

  (def abcd1-publish-events
    (->> abcd1-events
         (map transform-curation)
         (remove #(-> % :gene-validity/model unpulbish-query first))
         (into [])))

  (->> abcd1-publish-events
      (map :gene-validity/changes))
  
  (-> abcd1-publish-events
      last
      :gene-validity/model
      rdf/pp-model)

  (count abcd1-events)
  (->> abcd1-publish-events
       (mapv #(-> %  :gene-validity/model .size)))
  
  (->> abcd1-events
       (map transform-curation)
       (remove #(-> % :gene-validity/model unpulbish-query first))
       (into []))

  (->> abcd1-publish-events
       consecutive-pairs
       (mapv #(apply versioning/add-changes %))
       (take 1)
       (run! #(rdf/pp-model (:gene-validity/model %))))
  
  (let [q (rdf/create-query "select ?s where { ?a :cg/evidenceStrength ?s }")]
    (->> abcd1-publish-events
         (mapv #(-> % :gene-validity/model q first))))

  (let [q (rdf/create-query "
select ?o where {
 ?a :cg/role :cg/Approver ;
 :cg/agent ?o . }")]
    (->> abcd1-publish-events
         (mapv #(-> % :gene-validity/model q first))))


  ;; This appears to be a Clojure namespace for working with gene validity data in the Genegraph system. Here's a concise overview of what this code does:

  ;; ## Main Components

  ;; **Data Processing Pipeline:**
  ;; - Transforms gene validity curations from JSON to RDF/SEPIO format
  ;; - Records curation outputs to RocksDB storage
  ;; - Processes events from Kafka topics

  ;; **Key Functions:**
  ;; - `transform-curation` - Processes gene validity events through the transformation pipeline
  ;; - `record-gv-curation-fn` - Stores curation outputs with versioning
  ;; - `get-events-from-topic` - Extracts events from Kafka topics to files

  ;; **Analysis Tools:**
  ;; - GCEP productivity reporting (Q1/Q4 analysis)
  ;; - Curation classification tracking (Limited, Moderate, Strong, Definitive)
  ;; - Recuration analysis and trend monitoring

  ;; **Data Sources:**
  ;; - Gene validity complete events from Kafka
  ;; - Google Sheets for affiliation data
  ;; - Local RDF storage for versioned curations

  ;; ## Comment Blocks

  ;; The extensive comment blocks show:
  ;; 1. **Development workflow** - Starting/stopping apps, processing test data
  ;; 2. **Productivity analysis** - Generating reports on curation activities by expert panels
  ;; 3. **Classification analysis** - Tracking changes in evidence strength over time
  ;; 4. **Data exploration** - Examining specific curations and their transformations

  ;; This appears to be a development/analysis environment for the Clinical Genome Resource (ClinGen) gene validity curation system, focusing on data transformation, quality analysis, and reporting.

  ;; write clojure code that takes a sequence and transforms it into tuples of the first element paired with the next element and so ons

  ;; Here's a Clojure function that creates pairs of consecutive elements:

  (defn consecutive-pairs [coll]
    (map vector coll (rest coll)))

;; Usage examples:
(consecutive-pairs [1 2 3 4 5])
;; => ([1 2] [2 3] [3 4] [4 5])

(consecutive-pairs "abcde")
;; => ([\a \b] [\b \c] [\c \d] [\d \e])

(consecutive-pairs [:a :b :c])
;; => ([:a :b] [:b :c])
```


(defn consecutive-pairs [coll]
  (partition 2 1 coll))


;; Same results as above


;; The `partition` version creates lazy sequences of pairs, while `map vector` creates vectors. Both work well depending on your needs.

(time
     
 (->> abcd1-events
      (mapv #(-> %
                 transform-curation-for-writer
                 ))
      tap>)

 )
  (def transformed-events
    (mapv transform-curation-for-writer abcd1-events))

  (def unpulbish-query
    (rdf/create-query "select ?x where { ?x :cg/role :cg/Unpublisher }"))

  (->> transformed-events
       (remove #(-> % :gene-validity/model unpulbish-query seq))
       (mapv (fn [e]
               (let [a (-> e :gene-validity/model assertion-query first)
                     app (-> e :gene-validity/model approval-date-query first)]
                 [(rdf/curie a)
                  (str (rdf/curie (rdf/ld1-> a [:cg/GCISnapshot]))
                       (rdf/ld1-> app [:cg/date]))])))
       tap>)

  (-> transformed-events
      #_(remove #(-> % :gene-validity/model unpulbish-query seq))
      #_(mapv #(-> % :gene-validity/model assertion-query first))
      (nth 1)
      :gene-validity/model
      rdf/pp-model)

  (->> transformed-events
       (remove :gene-validity/change-type)
       first
       :gene-validity/model
       rdf/pp-model)
    
  (tap> (mapv :gene-validity/change-type transformed-events))

  
  (->> gdm-ids
       frequencies
       (sort-by val)
       reverse
       (take 20)
       tap>)

  "1bb8bc84-fe02-4a05-92a0-c0aacf897b6e"

  "https://search.clinicalgenome.org/kb/gene-validity/CGGV:assertion_815e0f84-b530-4fd2-81a9-02e02bf352ee-2020-12-18T050000.000Z?page=1&size=25&search="
  )
(comment
  (reduce 
   #(+
     %1
     (*
      (+ 50.0  (* 11.0 80.0))
      (math/pow 1.015 (- %2 300.0))
      (+ 1.0  (* 33.0 0.25))
      2.76
      2.2))
   0
   (range 495 500))
  )




(comment
  (-> "/Users/tristan/Downloads/gene-validity-jsonld-latest-4/cggv_fd43cf88-be31-4fe2-bd4a-7cacad12aeb0v1.0.json" slurp (json/read-str :key-fn keyword) tap>)
  )


(comment
  (do
    (def target-extensions #{".edn" ".clj" ".sparql"})
    (def old-text "https://genegraph.clinicalgenome.org/r/gci/")
    (def new-text "https://genegraph.clinicalgenome.org/r/")

    (defn has-target-extension? [file]
      "Check if file has one of the target extensions"
      (let [filename (.getName file)]
        (some #(str/ends-with? filename %) target-extensions)))

    (defn find-target-files [root-dir]
      "Recursively find all files with target extensions"
      (->> (file-seq (io/file root-dir))
           (filter #(.isFile %))
           (filter has-target-extension?)))

    (defn replace-text-in-file [file]
      "Replace old-text with new-text in the given file"
      (try
        (let [content (slurp file)
              updated-content (str/replace content old-text new-text)]
          (when-not (= content updated-content)
            (spit file updated-content)
            (println "Updated:" (.getPath file))
            true))
        (catch Exception e
          (println "Error processing" (.getPath file) ":" (.getMessage e))
          false)))

    (defn process-directory [root-dir]
      "Process all target files in the directory tree"
      (let [files (find-target-files root-dir)
            total-files (count files)]
        (println "Found" total-files "files with target extensions")
        (println "Searching for:" old-text)
        (println "Replacing with:" new-text)
        (println)
        
        (let [updated-count (->> files
                                 (map replace-text-in-file)
                                 (filter true?)
                                 count)]
          (println)
          (println "Processing complete:")
          (println "- Files examined:" total-files)
          (println "- Files updated:" updated-count)))))

  (process-directory "/Users/tristan/code/genegraph-gene-validity-sepio/src/")
  )

;; testing curation transform

(comment
  (def recent
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2025-07-29.edn.gz"]
      (->> (event-store/event-seq r)
           (take-last 10)
           (into []))))

  (->> recent
       (take-last 3)
       (run! #(-> % transform-curation :gene-validity/model rdf/pp-model)))
  
  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2025-07-29.edn.gz"]
    (->> (event-store/event-seq r)
         (take 1)
         (map transform-curation)
         #_(mapv ::gv/timestamps)
         #_(run! #(-> % :gene-validity/model rdf/pp-model))
         (run! #(-> % :gene-validity/gci-model rdf/pp-model))))
  
  )

;; tracking down issue with errors in transform after
;; adding changes
(comment
  "981c47f7-74ed-4cea-8df4-6d8df4bd0383"

  (def issue1
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2025-07-29.edn.gz"]
      (->> (event-store/event-seq r)
           (filter #(re-find #"981c47f7-74ed-4cea-8df4-6d8df4bd0383"
                             (::event/value %)))
           (into []))))

  (count issue1)

  (def issue1-publish-events
    (->> issue1
         (map transform-curation)
         (remove #(-> % :gene-validity/model unpulbish-query first))
         (into [])))

  (def issue1-t
    (->> issue1
         (mapv transform-curation-for-writer)))

  (->> issue1-t
       (mapv :gene-validity/changes)
       tap>)
  
  (->> issue1-t
       (map sepio-publish-event)
       (mapv type))


  )

(comment
  ;; craps out on this transform 


  (write-transformed-events "/Users/tristan/data/genegraph-neo/gene_validity_complete-2025-07-29.edn.gz"
                            "/Users/tristan/data/genegraph-neo/bad-changes.edn.gz"
                            "f1705bb1-c435-4106-ab9b-422ff2dfe4bf")

  (def bad-events
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2025-07-29.edn.gz"]
      (->> (event-store/event-seq r)
           (filter #(re-find #"f1705bb1-c435-4106-ab9b-422ff2dfe4bf"
                             (::event/value %)))
           (into []))))

  (def abcd1-publish-events
    (->> abcd1-events
         (map transform-curation)
         (remove #(-> % :gene-validity/model unpulbish-query first))
         (into [])))
  
  (def bad-publish-events
    (->> bad-events
         (map transform-curation)
         (remove #(-> % :gene-validity/model unpulbish-query first))
         (into [])))
  
  (count bad-publish-events)

  ;; crashes on moi change, but likely indicates bad data
  ;; may have been there for a while

  ;; mode of inheritance is a string for families
  ;; is an HPO  code for the proposition

  ;; I suppose the question is how often this field is populated. Evidence seems to
  ;; suggest infrequently

  (-> bad-publish-events
      (nth 2)
      :gene-validity/model
      rdf/pp-model)
  
  (->> bad-publish-events
       consecutive-pairs
       (mapv #(apply versioning/add-changes %))
       (take 1)
       (run! #(rdf/pp-model (:gene-validity/model %))))
  
  (+ 1 1)
  
  )

;; exploring why json-ld output not working as expected
(comment
  (time
   (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2025-09-11.edn.gz"]
     (->> (event-store/event-seq r)
          (take 1)
          (mapv transform-curation)
          (mapv #(dissoc % :gene-validity/gci-model :gene-validity/model))
          (mapv #(assoc % ::json-ld-data (json/read-str (:gene-validity/json-ld %))))
          tap>
          #_(run! #(p/publish (get-in test-app [:topics :gene-validity-complete]) %)))))

  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2025-09-11.edn.gz"]
    (->> (event-store/event-seq r)
         (take 1)
         (mapv transform-curation)
         #_(mapv #(dissoc % :gene-validity/gci-model :gene-validity/model))
         #_(mapv #(assoc % ::json-ld-data (json/read-str (:gene-validity/json-ld %))))
         #_tap>
         (run! #(rdf/pp-model (:gene-validity/model  %)))))
  )



;; Addressing Bradford's issues
(comment
  (do
    (defn get-case [c]
      (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_all-2026-01-05.edn.gz"]
        (->> (event-store/event-seq r)
             (filter #(re-find (re-pattern c) (::event/value %)))
             last)))

    (defn json-ld [e]
      (-> e transform-curation :gene-validity/json-ld json/read-str tap>))

    (defn write-json-ld [path e]
      (spit path 
            (-> e transform-curation :gene-validity/json-ld)))

    (defn model [e]
      (-> e transform-curation :gene-validity/model rdf/pp-model))

    (defn data [e]
      (-> e transform-curation ::event/data tap>)))
  
  ;; The following have no mention of probands 
  "cggv_ffe06cdd-813b-423e-8693-bd5fcac657c2v1.0.json"
  (def c1 (get-case "ffe06cdd-813b-423e-8693-bd5fcac657c2"))
  "cggv_ffa85dda-7f2a-40a9-b70b-0b39cb76eb66v1.1.json"
  "cggv_fe99572b-f026-40f1-872d-ea211f5621ccv2.0.json"
  "cggv_fee59e53-d622-4b0f-ad94-5ca026a7cab3v1.0.json"
  "cggv_fe16fb78-9342-40e6-9c7a-5184bdb256d3v1.0.json"
  (json-ld c1)
  (model c1)
  (let [q (rdf/create-query "
select ?x where {
  ?x a :cg/EvidenceLine .
  filter not exists { ?x :cg/specifiedBy ?c }
}")]
    (-> c1
        transform-curation
        :gene-validity/model
        q))

  ;; Differing dc:source
  ;; proband id 07088...
  "cggv_00140591-caa8-4d47-b4ca-3f0577b16d73v2.1.json"
  (def p1 (get-case "00140591-caa8-4d47-b4ca-3f0577b16d73"))

  (json-ld p1)
  (model p1)
  (data p1)
  ;; proband id: cggv:2d5c3368-e5e9-45a4-b8ac-194499f5b684
  "cggv_00cd170a-6097-4b48-9507-479b623b3be4v3.1.json"
  ;; proband id: cggv:466c3844-39f7-4bc6-acc2-cc43af445948
  "cggv_fffa068a-8995-4114-8302-f4530f37fe54v1.1.json"
  ;; proband id: cggv:848e9c86-98bd-4b8e-a5e3-2d5f8eb83c89
  "cggv_018254c5-0d85-4ccf-834e-e665dd223501v1.0.json"

 
  )


(comment
  ;; issue with AD curations GG:c4831487-68ed-4667-95e7-2f1805817dafv1.0
  (def gdi1 (get-case "8afc42b0-6c5e-460b-87d1-035c051fe7ca"))
  (json-ld gdi1)
  (data gdi1)
  (write-json-ld "/users/tristan/Desktop/gdi1.json" gdi1)
  
  (def csf2ra (get-case "6037e055-90a1-4727-be41-fa3295982b12"))
  (def aimp2 (get-case "ba6f8aa3-9aa9-4755-8dec-bb5c69005bbe"))
  (json-ld aimp2)

  (json-ld csf2ra)

  
  )

;; Larry observing two mondo conditions embedded in the same curation
(comment
  "107f9b2d" ;; MYO1C
  "9b0a844b-f968-48e0-8940-35584eb3454b" ;; DFNA5
  (def myo1c (get-case "107f9b2d"))
  (def myo1c (get-case "f27e3d88-0a3d-44f8-bbbc-1f668e596541"))

  (json-ld myo1c)
  (def myo1c-gci-model (-> myo1c transform-curation :gene-validity/gci-model))
  (rdf/pp-model myo1c-gci-model)
  (let [q (rdf/create-query "select ?s where { ?s ?p ?o }")]
    (->> (q myo1c-gci-model {:o (rdf/resource
                                 
                                 "GG:6569447e-62f9-4d74-8224-4ecb279b7e42")})
         (mapv #(rdf/ld-> % [:rdf/type]))))
  (rdf/resource "GG:6569447e-62f9-4d74-8224-4ecb279b7e42")
  (def dfna5 (get-case "9b0a844b-f968-48e0-8940-35584eb3454b"))

  (def dfna5-gci-model (-> dfna5 transform-curation :gene-validity/gci-model))
  ":gg/b73086e7-0125-406b-8baa-771292ccfdd2"
  (rdf/pp-model dfna5-gci-model)
  
  (let [q (rdf/create-query "select ?s where { ?s ?p ?o }")]
    (->> (q dfna5-gci-model {:o (rdf/resource
                                 "GG:6569447e-62f9-4d74-8224-4ecb279b7e42"
                                 #_"GG:b73086e7-0125-406b-8baa-771292ccfdd2")})
         #_(mapv str)
         (mapv #(rdf/ld-> % [:rdf/type]))))
  (data dfna5)
  
  )

;; finalizing versioning for gene validity
(comment
  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_all-2026-01-05.edn.gz"]
    (->> (event-store/event-seq r)
         (take 1)
         (mapv #(-> %
                    transform-curation
                    (dissoc :gene-validity/model :gene-validity/gci-model)))
         tap>))


  ;; 36 events! Probably more noise than we want 
  "f30149c6-d644-430b-8e4b-3c825cfdf333"
  (def trial-set-1
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_all-2025-12-09.edn.gz"]
      (->> (event-store/event-seq r)
           (filterv #(re-find #"f30149c6-d644-430b-8e4b-3c825cfdf333"
                              (::event/value %))))))

  (run! #(p/publish (get-in test-app [:topics :gene-validity-complete]) %)
        trial-set-1)

  (->> trial-set-1
       (mapv transform-curation)
       (remove versioning/valid-event?)
       #_(take 1)
       (run! #(-> % :gene-validity/model rdf/pp-model))
       #_(mapv #(dissoc % :gene-validity/model :gene-validity/gci-model))
       #_tap>
       #_(mapv ::versioning/proposition-iri)
       #_set)

  (run!
   #(storage/range-delete @(get-in test-app [:storage :gene-validity-version-store :instance]) [%])
   #{"https://genegraph.clinicalgenome.org/r/0ed13f17-9636-4e84-b6cd-1ac51fdc5a8c" "https://genegraph.clinicalgenome.org/r/621b0c10-bab1-4848-a89e-b824479a941b" "https://genegraph.clinicalgenome.org/r/1bb8bc84-fe02-4a05-92a0-c0aacf897b6e" "https://genegraph.clinicalgenome.org/r/f30149c6-d644-430b-8e4b-3c825cfdf333" "https://genegraph.clinicalgenome.org/r/54748aa6-6bee-4fec-94e8-19b521447489" "https://genegraph.clinicalgenome.org/r/573a2983-4b49-4d67-b164-a572e0711c3d"})
  


  

  "f31be353-ae5f-4062-85f0-607c45cc38ea"
  (def trial-set-2
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_all-2025-12-09.edn.gz"]
      (->> (event-store/event-seq r)
           (filterv #(re-find #"f31be353-ae5f-4062-85f0-607c45cc38ea"
                              (::event/value %))))))

  (count trial-set-2)

  (get-in test-app [:topics :gene-validity-complete])

  (->> trial-set-2
       (mapv transform-curation)
       (mapv ::versioning/proposition-iri))

  (storage/read @(get-in test-app [:storage :gene-validity-version-store :instance])
                "https://genegraph.clinicalgenome.org/r/f31be353-ae5f-4062-85f0-607c45cc38ea")

  (defn changed-elements [event]
    (let [q (rdf/create-query "select ?x where { ?a :cg/changes ?x } ")]
      (->> (q (:gene-validity/model event))
           (mapv rdf/->kw))))

  (->> (rocksdb/scan @(get-in test-app [:storage :gene-validity-version-store :instance])
                     ["https://genegraph.clinicalgenome.org/r/f31be353-ae5f-4062-85f0-607c45cc38ea"])
       (take-last 1)
       (run! #(-> % :gene-validity/model rdf/pp-model))
       #_(mapv #(assoc (select-keys % [:gene-validity/approval-date
                                       :gene-validity/version
                                       :gene-validity/change-records])
                       :changes (changed-elements %))))

  (run! #(p/publish (get-in test-app [:topics :gene-validity-complete]) %)
        trial-set-2)
  
  (storage/range-delete @(get-in test-app [:storage :gene-validity-version-store :instance])
                        ["https://genegraph.clinicalgenome.org/r/f31be353-ae5f-4062-85f0-607c45cc38ea"])


  ;; Seems to be affected by non-scorable evidence -- should have that as new evidence
  ;; Question to ask today

  ;; CAT -- acatalasia

  ;;anchorx
  
  (def trial-set-3
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_all-2026-01-05.edn.gz"]
      (->> (event-store/event-seq r)
           (filterv #(re-find #"f1a44725-cee2-4377-9ef0-d13cc6b0af63"
                              (::event/value %))))))

  (count trial-set-3)

  (->> (rocksdb/scan @(get-in test-app [:storage :gene-validity-version-store :instance])
                     ["https://genegraph.clinicalgenome.org/r/f1a44725-cee2-4377-9ef0-d13cc6b0af63"])
       #_(take 1)
       (mapv website-events/add-website-event)
       #_(mapv :gene-validity/website-event)
       #_(run! #(-> % :gene-validity/model rdf/pp-model))
       #_(mapv #(assoc (select-keys % [:gene-validity/approval-date
                                     :gene-validity/version
                                     :gene-validity/change-records
                                     :gene-validity/website-event])
                     :changes (changed-elements %)))
       (mapv :gene-validity/website-event)
       tap>)

    (->> (rocksdb/scan @(get-in test-app [:storage :gene-validity-version-store :instance])
                     ["https://genegraph.clinicalgenome.org/r/f1a44725-cee2-4377-9ef0-d13cc6b0af63"])
         last
         keys)

  (run! #(p/publish (get-in test-app [:topics :gene-validity-complete]) %)
        trial-set-3)

  (->> trial-set-3
       (mapv transform-curation)
       (mapv ::versioning/proposition-iri))

  (-> trial-set-3
      last
      transform-curation
      keys)

  (storage/range-delete @(get-in test-app [:storage :gene-validity-version-store :instance])
                        ["https://genegraph.clinicalgenome.org/r/f1a44725-cee2-4377-9ef0-d13cc6b0af63"])


  "ef2d0d7a-4e5a-47ef-ab33-20dcce11e922"


  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_all-2025-12-09.edn.gz"]
    (->> (event-store/event-seq r)
         (run! #(p/publish (get-in test-app [:topics :gene-validity-complete]) %))))

  (+ 1 1)

  ;; DZIP1L -- AR polycystic kidney disease
  (def trial-set-4
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_all-2026-01-05.edn.gz"]
      (->> (event-store/event-seq r)
           (filterv #(re-find #"ef2d0d7a-4e5a-47ef-ab33-20dcce11e922"
                              (::event/value %))))))

  ;; publish all
  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_all-2026-01-05.edn.gz"]
    (->> (event-store/event-seq r)
         (run! #(p/publish (get-in test-app [:topics :gene-validity-complete]) %))))

  (+ 1 1 )

  (count trial-set-4)

  (-> trial-set-4 first tap>)

  (->> trial-set-4
       (mapv transform-curation)
       (mapv ::versioning/proposition-iri))

  (run! #(p/publish (get-in test-app [:topics :gene-validity-complete]) %)
        trial-set-4)

  (->> (rocksdb/scan @(get-in test-app [:storage
                                        :gene-validity-version-store
                                        :instance])
                     [::recorder/event])
       (take 1)
       (map :gene-validity/model)
       (run! rdf/pp-model))


  

  (->> (rocksdb/scan @(get-in test-app [:storage :gene-validity-version-store :instance])
                     ["https://genegraph.clinicalgenome.org/r/ef2d0d7a-4e5a-47ef-ab33-20dcce11e922"])
       (mapv website-events/add-website-event)
       #_(take-last 1)
       #_(run! #(-> % :gene-validity/model rdf/pp-model))
       #_(mapv #(assoc (select-keys % [:gene-validity/approval-date
                                       :gene-validity/version
                                       :gene-validity/change-records])
                       :changes (changed-elements %)))
       (mapv :gene-validity/website-event)
       tap>)
  
  (storage/range-delete @(get-in test-app [:storage :gene-validity-version-store :instance])
                        ["https://genegraph.clinicalgenome.org/r/ef2d0d7a-4e5a-47ef-ab33-20dcce11e922"])

  
  ;; another case of conjoined (or cross-referenced) curations
  "ec13ca39-cecd-4659-8959-fcd8278e480b"
  (def trial-set-5
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_all-2025-12-09.edn.gz"]
      (->> (event-store/event-seq r)
           (filterv #(re-find #"ec13ca39-cecd-4659-8959-fcd8278e480b"
                              (::event/value %))))))

  (count trial-set-5)

  (->> trial-set-5
       (mapv transform-curation)
       (mapv ::versioning/proposition-iri))

  (run!
   #(storage/range-delete @(get-in test-app [:storage :gene-validity-version-store :instance]) [%])
   #{"https://genegraph.clinicalgenome.org/r/2e57707b-458d-4e8a-ac4a-d6d17b98b9e0" "https://genegraph.clinicalgenome.org/r/ec13ca39-cecd-4659-8959-fcd8278e480b" "https://genegraph.clinicalgenome.org/r/d0c3cebf-14f1-486a-b984-3a79da6ea83d"})

  (run! #(p/publish (get-in test-app [:topics :gene-validity-complete]) %)
        trial-set-5)

  (run! 
   (fn [iri]
     (->> (rocksdb/scan @(get-in test-app [:storage
                                           :gene-validity-version-store
                                           :instance])
                        [iri])
          #_(take-last 1)
          #_(run! #(-> % :gene-validity/model rdf/pp-model))
          (mapv #(assoc (select-keys % [:gene-validity/approval-date
                                        :gene-validity/version
                                        :gene-validity/change-records])
                        :changes (changed-elements %)))
          tap>))
   #{"https://genegraph.clinicalgenome.org/r/2e57707b-458d-4e8a-ac4a-d6d17b98b9e0" "https://genegraph.clinicalgenome.org/r/ec13ca39-cecd-4659-8959-fcd8278e480b" "https://genegraph.clinicalgenome.org/r/d0c3cebf-14f1-486a-b984-3a79da6ea83d"})
  
  (set
   ["https://genegraph.clinicalgenome.org/r/ec13ca39-cecd-4659-8959-fcd8278e480b" "https://genegraph.clinicalgenome.org/r/d0c3cebf-14f1-486a-b984-3a79da6ea83d" "https://genegraph.clinicalgenome.org/r/d0c3cebf-14f1-486a-b984-3a79da6ea83d" "https://genegraph.clinicalgenome.org/r/2e57707b-458d-4e8a-ac4a-d6d17b98b9e0" "https://genegraph.clinicalgenome.org/r/ec13ca39-cecd-4659-8959-fcd8278e480b" "https://genegraph.clinicalgenome.org/r/2e57707b-458d-4e8a-ac4a-d6d17b98b9e0" "https://genegraph.clinicalgenome.org/r/ec13ca39-cecd-4659-8959-fcd8278e480b" "https://genegraph.clinicalgenome.org/r/ec13ca39-cecd-4659-8959-fcd8278e480b" "https://genegraph.clinicalgenome.org/r/ec13ca39-cecd-4659-8959-fcd8278e480b"])
  
  
  )

;; Working on transform into updated GA4GH SEPIO
(comment
  (defn get-curations [c]
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_all-2026-01-05.edn.gz"]
      (->> (event-store/event-seq r)
           (filterv #(re-find (re-pattern c) (::event/value %))))))

  (def gdi1-events
    (get-curations "a0a9ec11-ef90-4095-9c9e-696eabd0395b"))

  (def gdi1x
    (-> gdi1-events last transform-curation))

  (keys gdi1x)

  (count gdi1-events)


  
  (def gdi1
    (->> (rocksdb/range-get @(get-in test-app [:storage
                                               :gene-validity-version-store
                                               :instance])
                            {:prefix [::recorder/event]
                             :return :ref})
         (filter (fn [e]
                   (let [data (::event/data @e)]
                     (= "a0a9ec11-ef90-4095-9c9e-696eabd0395b"
                        (get-in data [:resourceParent :gdm :PK])))))
         last))

  (-> (rdf/union (:gene-validity/gci-model @gdi1)
                 sepio-model/gdm-sepio-relationships)
      )
  (-> @gdi1 ::event/data tap>)


  (->> (rocksdb/range-get @(get-in test-app [:storage
                                             :gene-validity-version-store
                                             :instance])
                          {:prefix [::recorder/event]
                           :return :ref})
       #_(take 100)
       (map (fn [e]
              (let [d (::event/data @e)
                    gdm (or (get-in d
                                    [:properties
                                     :resourceParent
                                     :gdm])
                            (get-in d
                                    [:resourceParent
                                     :gdm]))]
                (or (get-in d
                            [:properties
                             :resourceParent
                             :gdm])
                    (get-in d
                            [:resourceParent
                             :gdm])))))
       (mapcat (fn [gdm]
                 (map (fn [anno]
                        (assoc anno :gdm (or (:uuid gdm) (:PK gdm))))
                      (filter #(seq (:experimentalData %))
                              (:annotations gdm)))))
       (mapcat :experimentalData)
       #_(filter (fn [e]
                   (let [data (::event/data @e)]
                     (= "a0a9ec11-ef90-4095-9c9e-696eabd0395b"
                        (get-in data [:resourceParent :gdm :PK])))))
       last
       tap>)

  (+ 1 1)
  )


;; q4 GV productivity report

(comment

  (def oct-1
    (-> (LocalDateTime/of 2025 10 1 0 0)
        (.toInstant ZoneOffset/UTC)
        (.toEpochMilli)))
  (def jan-1
    (-> (LocalDateTime/of 2026 1 1 0 0)
        (.toInstant ZoneOffset/UTC)
        (.toEpochMilli)))

  (def dec-23
    (-> (LocalDateTime/of 2025 12 23 0 0)
        (.toInstant ZoneOffset/UTC)
        (.toEpochMilli)))
  
  (def dec-24
    (-> (LocalDateTime/of 2025 12 24 0 0)
        (.toInstant ZoneOffset/UTC)
        (.toEpochMilli)))
  
  "529f9cae-ac00-47d1-94d5-52c98bf6e2a2"
  (def scn1a
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_all-2026-01-05.edn.gz"]
      (->> (event-store/event-seq r)
           (filterv (fn [e]
                      (re-find #"It was reevaluated on December 16"
                                (::event/value e)))))))

  (count scn1a)

  (->> dec23-curations
       #_(take 1)
       (mapv transform-curation)
       (mapv ->progress-record)
       tap>
       #_(mapv #(dissoc %
                        :gene-validity/model
                        :gene-validity/gci-model))
       

       #_(run! #(rdf/pp-model (:gene-validity/model %))))

  
  (def q4-curations
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_all-2026-01-05.edn.gz"]
      (->> (event-store/event-seq r)
           (filterv (fn [e]
                      (and (< oct-1 (::event/timestamp e))
                           (< (::event/timestamp e) jan-1)))))))

  (count q4-curations)

  (->> q4-curations
       (mapv ::event/offset)
       clojure.pprint/pprint)

  (def dec23-curations
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_all-2026-01-05.edn.gz"]
      (->> (event-store/event-seq r)
           (filterv (fn [e]
                      (and (< dec-23 (::event/timestamp e))
                           (< (::event/timestamp e) dec-24)))))))





  (do
    (defn curation-reason [e]
      (let [q (rdf/create-query "
select ?r where {
?x :cg/curationReasons ?r
}")]
        (->> (-> e :gene-validity/model q)
             (map str)
             set)))

    (defn approval [e]
      (let [q (rdf/create-query "
select ?x where {
?x :cg/activityType :cg/Evaluated ;
}")
            contrib (first (q (:gene-validity/model e)))]
        (if contrib
          {:approval-date (rdf/ld1-> contrib [:cg/date])
           :approver (str (rdf/ld1-> contrib [:cg/contributor]))})))

    (defn publish-date [e]
      (let [q (rdf/create-query "
select ?x where {
?x :cg/activityType :cg/Submitted ;
}")
            contrib (first (q (:gene-validity/model e)))]
        (if contrib
          (rdf/ld1-> contrib [:cg/date])
          nil)))

    (defn secondary-contributor [e]
      (let [q (rdf/create-query "
select ?x where {
?x :cg/role :cg/SecondaryContributor ;
}")
            contrib (first (q (:gene-validity/model e)))]
        (if contrib
          (str (rdf/ld1-> contrib [:cg/agent]))
          nil)))

    (defn gene [e]
      (let [q (rdf/create-query "
select ?x where {
?p a :cg/GeneValidityProposition ;
:cg/subject ?x .
}")]
        (some-> e :gene-validity/model q first str)))

    (defn ->progress-record
      [e]
      (merge {:approver nil
              :secondary-contributor (secondary-contributor e)
              :publish-date (publish-date e)
              :approval-date nil
              :curation-reason (curation-reason e)
              :gene (gene e)}
             (approval e)))
    
    (def q4-curation-records
      (->> q4-curations
           #_(take 1)
           (mapv transform-curation)
           (mapv ->progress-record)
           #_tap>
           #_(mapv #(dissoc %
                            :gene-validity/model
                            :gene-validity/gci-model))
           

           #_(run! #(rdf/pp-model (:gene-validity/model %))))))
  (def gcep-labels
    (with-open [r (io/reader "/Users/tristan/Downloads/affils.json.txt")]
      (->> (charred/read-json r :key-fn keyword)
           (mapv (fn [x] [(str "https://genegraph.clinicalgenome.org/agent/"
                               (:affiliation_id x))
                          (:affiliation_fullname x)]))
           (into {}))))

  (defn add-labels [r]
    (assoc r
           :approver-name (gcep-labels (:approver r))
           :secondary-contributor-name (gcep-labels (:secondary-contributor r))))

  (defn new-curation? [r]
    (get
     (:curation-reason r)
     "https://genegraph.clinicalgenome.org/terms/NewCuration"))

  (defn recuration? [r]
    (let [recuration-type
          #{"https://genegraph.clinicalgenome.org/terms/RecurationTiming"
            "https://genegraph.clinicalgenome.org/terms/RecurationFrameworkChange"
            "https://genegraph.clinicalgenome.org/terms/RecurationErrorAffectingScoreorClassification"
            "https://genegraph.clinicalgenome.org/terms/RecurationNewEvidence"}]
      (seq (set/intersection (:curation-reason r) recuration-type))))

  {"https://genegraph.clinicalgenome.org/terms/NewCuration" 44,
   "https://genegraph.clinicalgenome.org/terms/ErrorClarification" 126,
   "https://genegraph.clinicalgenome.org/terms/RecurationTiming" 30,
   nil 25,
   "https://genegraph.clinicalgenome.org/terms/RecurationErrorAffectingScoreorClassification" 3
   "https://genegraph.clinicalgenome.org/terms/RecurationNewEvidence" 10,
   "https://genegraph.clinicalgenome.org/terms/RecurationFrameworkChange" 1}

  (->> q4-curation-records
       (filter #(= "https://genegraph.clinicalgenome.org/agent/10005"
                   (:approver %)))
       #_(filter :publish-date)
       (mapv add-labels)
       tap>)

  (tap> gcep-labels)

  (defn inc-new [counts]
    (if counts
      (update counts :new inc)
      {:new 1 :recuration 0 :secondary 0}))

  (defn inc-recuration [counts]
    (if counts
      (update counts :recuration inc)
      {:new 0 :recuration 1 :secondary 0}))

  (defn inc-secondary [counts]
    (if counts
      (update counts :secondary inc)
      {:new 0 :recuration 0 :secondary 1}))

  (tap>
   (set/rename-keys
    (reduce
     (fn [m r]
       (let [m1 (cond
                  (new-curation? r) (update m (:approver r) inc-new)
                  (recuration? r) (update m (:approver r) inc-recuration)
                  :default m)]
         (if (and (:secondary-contributor r)
                  (or (new-curation? r) (recuration? r)))
           (update m1 (:secondary-contributor r) inc-secondary)
           m1)))
     {}
     q4-curation-records)
    gcep-labels))

  (with-open [w (io/writer "/Users/tristan/Desktop/gcep-report.csv")]
    (->>
     (set/rename-keys
      (reduce
       (fn [m r]
         (let [m1 (cond
                    (new-curation? r) (update m (:approver r) inc-new)
                    (recuration? r) (update m (:approver r) inc-recuration)
                    :default m)]
           (if (and (:secondary-contributor r)
                    (or (new-curation? r) (recuration? r)))
             (update m1 (:secondary-contributor r) inc-secondary)
             m1)))
       {}
       q4-curation-records)
      gcep-labels)
     (mapv (fn [[k {:keys [new recuration secondary]}]]
             [k new recuration secondary]))
     (cons ["Expert Panel" "New Curations" "Recurations" "Secondary Contributions"])
     (charred/write-csv w)))
  
  (count q4-curation-records)
  )

;; Picking out a few examples for discussion
(comment
  (->> (rocksdb/scan @(get-in test-app [:storage :gene-validity-version-store :instance])
                     ["https://genegraph.clinicalgenome.org/r/ef2d0d7a-4e5a-47ef-ab33-20dcce11e922"])
       (mapv website-events/add-website-event)
       #_(take-last 1)
       #_(run! #(-> % :gene-validity/model rdf/pp-model))
       #_(mapv #(assoc (select-keys % [:gene-validity/approval-date
                                       :gene-validity/version
                                       :gene-validity/change-records])
                       :changes (changed-elements %)))
       (mapv :gene-validity/website-event)
       tap>)
  "http://localhost:8080/#/r/GG%3Ac16423b1-2353-475c-a43e-987a46fa1f00v1.13" ;;zeb2
  (defn tap-history
    [iri]
    (->> (rocksdb/scan @(get-in test-app [:storage :gene-validity-version-store :instance])
                       [iri])
         (mapv website-events/add-website-event)
         #_(take-last 1)
         #_(run! #(-> % :gene-validity/model rdf/pp-model))
         #_(mapv #(assoc (select-keys % [:gene-validity/approval-date
                                         :gene-validity/version
                                         :gene-validity/change-records])
                         :changes (changed-elements %)))
         (mapv :gene-validity/website-event)
         tap>))

  (tap-history "https://genegraph.clinicalgenome.org/r/c16423b1-2353-475c-a43e-987a46fa1f00") ;; zeb2

  (tap-history "https://genegraph.clinicalgenome.org/r/b372c7f6-bbac-488a-812a-0d27002e88a2") ;; just v1 of

  (tap-history "https://genegraph.clinicalgenome.org/r/b1958371-3f4a-43a3-b110-8451cab9de91")

  ;; affiliate_id -> string


  (* 1545 0.20)
  )

(defn version-key [event]
  (let [q (rdf/create-query "
select ?x where { 
?s a :cg/Statement ;
  :dc/isVersionOf ?x .
}")]
    (some-> event :gene-validity/model q first str)))

;; putting together GV versioning set for Phil
(comment
  (let [version-store @(get-in test-app [:storage
                                         :gene-validity-version-store
                                         :instance])]
    (->> (rocksdb/range-get version-store
                            {:prefix [::recorder/event]
                             :return :ref})
         (take-last 1)
         (map #(assoc-in (deref %)
                         [::storage/storage :gene-validity-version-store]
                         version-store))
         #_(mapv #(-> % deref keys))
         #_(mapv #(-> % website-events/add-website-event :gene-validity/website-event))
         #_tap>
         (run! #(-> %  :gene-validity/model rdf/pp-model))))


  (let [version-store @(get-in test-app [:storage
                                         :gene-validity-version-store
                                         :instance])]
    (->> (rocksdb/range-get version-store
                            {:prefix [::recorder/event]
                             :return :ref})
         (take-last 1)
         (map #(assoc-in (deref %)
                         [::storage/storage :gene-validity-version-store]
                         version-store))
         (mapv #(-> % keys))
         #_(mapv #(-> % website-events/add-website-event :gene-validity/website-event))
         tap>
         #_(run! #(-> % deref :gene-validity/model rdf/pp-model))))

  ;; Seed with initial events
  (time
   (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_all-2026-01-05.edn.gz"]
     (let [version-store @(get-in test-app [:storage
                                            :gene-validity-version-store
                                            :instance])]

       (->> (event-store/event-seq r)
            (run! (fn [e]
                    (storage/write version-store
                                   [:events (::event/offset e)]
                                   e)))))))
  ;; set up threadpool 
  (def tp (ThreadPoolExecutor. 30
                               30
                               1
                               TimeUnit/MINUTES
                               (SynchronousQueue. true)
                               (ThreadPoolExecutor$CallerRunsPolicy.)))
  (.close tp) ;; clean up after
  
  ;; Update and write events for model
  (time
   (let [version-store @(get-in test-app [:storage
                                          :gene-validity-version-store
                                          :instance])]
     (->> (rocksdb/range-get version-store
                             {:prefix [:events]
                              :return :ref})
          (run! (fn [e]
                  (.execute tp (fn []
                                 (let [evt @e]
                                   (storage/write version-store
                                                  [:events (::event/offset evt)]
                                                  (-> evt
                                                      event/deserialize
                                                      gci-model/add-gci-model-fn
                                                      sepio-model/add-model-fn))))))))))

  ;; clear index for keys/offsets
  (let [version-store @(get-in test-app [:storage
                                         :gene-validity-version-store
                                         :instance])]
    (storage/range-delete version-store [:offsets]))

  ;; validate index clear
  (let [version-store @(get-in test-app [:storage
                                         :gene-validity-version-store
                                         :instance])]
    (->> (rocksdb/range-get version-store
                            {:prefix [:offsets]})
         count))
  

  ;; build index for keys/offsets
  (time
   (let [version-store @(get-in test-app [:storage
                                          :gene-validity-version-store
                                          :instance])]
     (loop [events (into clojure.lang.PersistentQueue/EMPTY
                         (rocksdb/range-get version-store
                                            {:prefix [:events]
                                             :return :ref}))
            locks {}
            keys []]
       (if-let [eref (peek events)]
         (let [e @eref
               k (version-key e)
               s (get locks k (Semaphore. 1 true))]
           (.execute tp
                     (fn []
                       (.acquire s)
                       (try
                         (let [o (::event/offset e)
                               offsets (storage/read version-store [:offsets k])
                               offset-list (if (= ::storage/miss offsets)
                                             [o]
                                             (conj offsets o))]
                           (storage/write version-store [:offsets k] offset-list))
                         (catch Exception e (log/error :k k))
                         (finally (.release s)))))
           (recur (pop events) (assoc locks k s) (conj keys k)))
         keys))))

  ;; check for a bunch of nil keys at the begining
  (let [version-store @(get-in test-app [:storage
                                         :gene-validity-version-store
                                         :instance])]
    (->> (rocksdb/range-get version-store
                            {:prefix [:offsets]})
         (map count)
         sort
         reverse
         first))


  (let [version-store @(get-in test-app [:storage
                                         :gene-validity-version-store
                                         :instance])]
    (->> (rocksdb/range-get version-store
                            {:prefix [:offsets]})
         (sort-by count)
         reverse
         first
         sort
         (take 1)
         (mapv #(storage/read version-store [:events %]))
         (run! #(rdf/pp-model (:gene-validity/model %)))
         tap>))


  (let [version-store @(get-in test-app [:storage
                                         :gene-validity-version-store
                                         :instance])]
    (->> (rocksdb/range-get version-store
                            {:prefix [:offsets]})
         (sort-by count)
         reverse
         first))

  

  
  

  )

;; Getting back into it 2026-01-30
(comment
  ;; check for prior versions
  (time
   (let [version-store @(get-in test-app [:storage
                                          :gene-validity-version-store
                                          :instance])]
     (->> (rocksdb/range-get version-store
                             {:prefix [::versioning/prior-version]
                              :return :ref})
          (take 1)
          (mapv deref)
          tap>)))

  ;; clear prior versions
  (time
   (let [version-store @(get-in test-app [:storage
                                          :gene-validity-version-store
                                          :instance])]
     (storage/range-delete
      version-store
      [::versioning/prior-version])
     (storage/range-delete
      version-store
      [::website-events/website-event])))

  

  (time
   (let [version-store @(get-in test-app [:storage
                                          :gene-validity-version-store
                                          :instance])]
     (->> (rocksdb/range-get version-store
                             {:prefix [::versioning/prior-version]
                              :return :ref})
          (take 1)
          (mapv deref)
          tap>)))

  ;; Try to add versioning 
  (time
   (let [version-store @(get-in test-app [:storage
                                          :gene-validity-version-store
                                          :instance])]
     (->> (rocksdb/range-get version-store
                             {:prefix [:events]
                              :return :ref})
          (take 1)
          #_(mapv (fn [e]
                    (let [evt @e]
                      (-> evt
                          (assoc-in [::storage/storage :gene-validity-version-store]
                                    version-store)
                          versioning/calculate-version
                          (dissoc ::storage/storage)
                          (update-vals type)))))
          #_tap>
          (run! (fn [e]
                  (let [evt @e
                        versioned (-> evt
                                      (assoc-in [::storage/storage :gene-validity-version-store]
                                                version-store)
                                      versioning/calculate-version)]
                      (storage/write version-store
                                     [:events (::event/offset evt)]
                                     (-> evt
                                         (assoc-in [::storage/storage :gene-validity-version-store]
                                                   version-store)
                                         versioning/calculate-version
                                         (dissoc ::storage/storage ::event/effects)))))))))
  (+ 1 1)

  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_all-2026-02-26.edn.gz"]
    (->> (event-store/event-seq r)
         (take-last 1)
         (mapv event/deserialize)
         tap>))
  )
