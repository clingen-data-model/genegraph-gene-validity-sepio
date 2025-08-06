(ns genegraph.user
  (:require [genegraph.transform.gene-validity :as gv]
            [genegraph.transform.gene-validity.sepio-model :as sepio-model]
            [genegraph.transform.gene-validity.versioning :as versioning]
            [genegraph.framework.app :as app]
            [genegraph.framework.event :as event]
            [genegraph.framework.event.store :as event-store]
            [genegraph.framework.protocol :as p]
            [genegraph.framework.kafka :as kafka]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage.rocksdb :as rocksdb]
            [genegraph.framework.storage :as storage]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.log :as log]
            [portal.api :as portal]
            [clojure.data.json :as json]
            [hato.client :as hc]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.math :as math])
  (:import [ch.qos.logback.classic Logger Level]
           [org.slf4j LoggerFactory]
           [java.time Instant LocalDate ]
           [org.apache.jena.rdf.model Model Statement]))

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
                             "/Users/tristan/data/genegraph-neo/gv-sepio-2025-07-29.edn.gz"
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
