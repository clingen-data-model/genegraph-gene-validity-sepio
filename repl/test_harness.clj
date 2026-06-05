(ns test-harness
  (:require [genegraph.transform.gene-validity :as gv]
            [genegraph.transform.gene-validity.sepio-model :as sepio-model]
            [genegraph.transform.gene-validity.gci-model :as gci-model]
            [genegraph.transform.gene-validity.versioning :as versioning]
            [genegraph.transform.gene-validity.website-events :as website-events]
            [genegraph.transform.gene-validity.event-recorder :as recorder]
            [genegraph.transform.gene-validity.abbreviate :as abbrev]
            [genegraph.transform.gene-validity.validation :as validation]
            [genegraph.framework.app :as app]
            [genegraph.framework.event :as event]
            [genegraph.framework.event.store :as event-store]
            [genegraph.framework.protocol :as p]
            [genegraph.framework.kafka :as kafka]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage.rocksdb :as rocksdb]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.processor :as processor]
            [genegraph.transform.gene-validity.snapshot :as snapshot]
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
            [clojure.walk :as walk]
            [clojure.spec.alpha :as spec])
  (:import [ch.qos.logback.classic Logger Level]
           [org.slf4j LoggerFactory]))

(defn gdm-id [e]
  (or (get-in e [:resourceParent :gdm :PK])
      (get-in e [:resourceParent :gdm :uuid])
      (get-in e [:properties :resourceParent :gdm :uuid])))

(def source-file
  "/Users/tristan/data/genegraph-neo/gene_validity_all-2026-05-12.edn.gz")

(comment

  ;; start portal
  (do
    (def portal (portal/open))
    (add-tap #'portal/submit))
  (portal/close)
  (portal/clear)

  ;; start test app
  (do
    (def test-app (p/init test-app-def))
    (p/start test-app)
    (defn transform-curation [e]
      (p/process (get-in test-app [:processors :gene-validity-transform])
                 (assoc e
                        ::event/completion-promise (promise)
                        ::event/skip-local-effects true
                        ::event/skip-publish-effects true))))
  
  ;; stop test app
  (p/stop test-app)

  (tap> test-app)

  )

(def root-data-dir "/Users/tristan/data/genegraph-gene-validity-sepio/")

;; processes to load event and validate the initial loading
(comment
  ;; load current gene validity events
  (time
   (event-store/with-event-reader [r source-file]
     (run! #(p/publish (get-in test-app [:topics :gene-validity-complete]) %)
           (event-store/event-seq r))))

  ;; write latest events for testing in API



  (+ 1 1)

  (time
   (->> (rocksdb/range-get @(get-in test-app [:storage :gene-validity-version-store :instance])
                           {:prefix [:events :gene-validity-complete]
                            :return :ref})
        (take 1)
        (map deref)
        tap>))

  (time
   (->> (rocksdb/range-get @(get-in test-app [:storage :gene-validity-version-store :instance])
                           {:prefix [:events :gene-validity-complete]
                            :return :ref})
        count))
  
  )

(def prop-query
  (rdf/create-query "select ?x where { ?x a :cg/GeneValidityProposition }" ))

(def assertion-query
  (rdf/create-query "select ?x where { ?x a :cg/EvidenceStrengthAssertion }" ))

(defn create-record-output-processor [topic]
  (let [n (keyword (str (name topic) "-output"))
        i (interceptor/interceptor
           {:name n
            :enter (fn [e] e #_(log/info :output-topic topic))})]
    {:type :processor
     :name n
     :subscribe topic
     :interceptors [i]}))

(defn record-gv-curation-fn [e]
  (log/info :fn :record-curation
            :curation (-> e ::event/data assertion-query first str)))

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
             :name :gene-validity-complete
             :serialization :json}
            :gene-validity-sepio
            {:type :simple-queue-topic
             :name :gene-validity-sepio}
            :gene-validity-sepio-jsonld
            {:type :simple-queue-topic
             :name :gene-validity-sepio-jsonld}
            :all-curation-events
            {:type :simple-queue-topic
             :name :all-curation-events}
            :transform-topic
            {:type :simple-queue-topic
             :name :transform-topic}
            :processing-records-topic
            {:type :simple-queue-topic
             :name :processing-records-topic}
            :trigger-snapshot
            {:type :simple-queue-topic
             :name :trigger-snapshot}}
   :storage {:gene-validity-version-store
             (assoc gv/gene-validity-version-store :reset-opts {})
             :curation-output curation-output}
   :processors {:gene-validity-transform #_gv/transform-processor
                (assoc gv/transform-processor
                       :type :parallel-processor
                       :gate-fn gdm-id)
                :status-processor gv/status-processor
                :gci-event-processor gv/gci-event-processor
                :gene-validity-sepio-output
                (create-record-output-processor :gene-validity-sepio)
                :gene-validity-jsonld-output
                (create-record-output-processor :gene-validity-sepio-jsonld)
                :all-curation-events-output
                (create-record-output-processor :all-curation-events)
                :processing-records-output
                (create-record-output-processor :processing-records-topic)
                :snapshot-writer gv/snapshot-writer}
   :http-servers gv/gv-ready-server})


;; ---------------------------------------------------------------------------
;; GDM UUIDs used as test cases in genegraph.user
;; ---------------------------------------------------------------------------

(def test-uuids
  "All GDM UUIDs referenced as filter patterns or explicit test cases in user.clj.
  Used to build the curated sample corpus for migration validation."
  ["93ab3f0b-c5e1-43be-b9ce-9236198e91c2" ; rocksdb range-get target
   "cb06ff0d-1cc6-494c-9ce5-f7cb26f34620" ; re-find filter (x2)
   "01f588c4-4fef-493d-b5e0-a76fb9492244" ; storage/read target
   "3e96651d-5979-416b-abc5-2e6702c35871" ; gci-link example
   "d1230a85-2a8b-4321-b36d-213daae9a28a" ; filterv (recuration analysis)
   "0204e276-fa45-4756-a380-eb494f5237f8" ; STAT3 filter-str
   "4f30eccd-ee01-4dc2-b656-c40caffd7c06" ; STAG1 filter-str
   "75516cff-17fd-47bd-8873-862b66741de2" ; MGME1 filter-str
   "1bb8bc84-fe02-4a05-92a0-c0aacf897b6e" ; ABCD1 (write-transformed-events, filter)
   "815e0f84-b530-4fd2-81a9-02e02bf352ee" ; ABCD1 (write-transformed-events, filter)
   "981c47f7-74ed-4cea-8df4-6d8df4bd0383" ; re-find filter; also seen as v2.0
   "f1705bb1-c435-4106-ab9b-422ff2dfe4bf" ; filter-str
   "ffe06cdd-813b-423e-8693-bd5fcac657c2" ; get-case c1
   "00140591-caa8-4d47-b4ca-3f0577b16d73" ; get-case p1
   "8afc42b0-6c5e-460b-87d1-035c051fe7ca" ; gdi1 (AD curation)
   "6037e055-90a1-4727-be41-fa3295982b12" ; csf2ra
   "ba6f8aa3-9aa9-4755-8dec-bb5c69005bbe" ; aimp2
   "9b0a844b-f968-48e0-8940-35584eb3454b" ; DFNA5
   "f27e3d88-0a3d-44f8-bbbc-1f668e596541" ; myo1c
   "f30149c6-d644-430b-8e4b-3c825cfdf333" ; re-find filter
   "0ed13f17-9636-4e84-b6cd-1ac51fdc5a8c" ; proposition IRI set
   "621b0c10-bab1-4848-a89e-b824479a941b" ; proposition IRI set
   "54748aa6-6bee-4fec-94e8-19b521447489" ; proposition IRI set
   "573a2983-4b49-4d67-b164-a572e0711c3d" ; proposition IRI set
   "f31be353-ae5f-4062-85f0-607c45cc38ea" ; re-find filter + proposition IRI
   "f1a44725-cee2-4377-9ef0-d13cc6b0af63" ; re-find filter + proposition IRI
   "ef2d0d7a-4e5a-47ef-ab33-20dcce11e922" ; re-find filter + proposition IRI
   "ec13ca39-cecd-4659-8959-fcd8278e480b" ; re-find filter + proposition IRI
   "2e57707b-458d-4e8a-ac4a-d6d17b98b9e0" ; proposition IRI set
   "d0c3cebf-14f1-486a-b984-3a79da6ea83d" ; proposition IRI set
   "a0a9ec11-ef90-4095-9c9e-696eabd0395b" ; GDI1 get-curations
   "c16423b1-2353-475c-a43e-987a46fa1f00" ; ZEB2 tap-history
   "b372c7f6-bbac-488a-812a-0d27002e88a2" ; tap-history
   "b1958371-3f4a-43a3-b110-8451cab9de91" ; tap-history
   ])

#_(map #(assoc % :tap-abbrev true))

(defn gdm->events [gdm app]
  (storage/scan @(get-in app
                         [:storage :gene-validity-version-store :instance])
                [:outcomes gdm]))

;; Revise versioning to pull last version with assistance of
;; outcomes data in RockDB; we are deprecating the earlier (brittle)
;; approach, as it does not lend itself to reprocessing portions of the data
;; incrementally (but rather requires dumping portions of the database).

;; having trouble with the way we're handling renaming the proposition
;; I'm using a Genegraph value object id for the proposition, (rather than the GDM ID)
;; This more accurately reflects its nature, and allows it to fit in more nicely with
;; GV curations from other sources.

;; Discovered the problem is with the way versioning renames things. Will
;; take the unmodified (sepio) model and handle it differently.

  ;; uuids with lots of publish events
  ;; consider portions of this for a versioning
  ;; test set

#{"21433cc1-d3ae-4b62-b189-5611a2ad6f20"
  "7ab659f2-1f7f-40a0-a4b8-6b9dfa3c3ecb"
  "1bb8bc84-fe02-4a05-92a0-c0aacf897b6e"
  "90d2d66b-dc32-4737-a2b3-25fcb6ae3474"
  "b865e2b3-a7ef-4cb9-a342-bb2192df8183"
  "b7ee4cbb-3011-4d6a-b8c5-97d4a2b028c4"
  "c16423b1-2353-475c-a43e-987a46fa1f00"
  "658b0515-e59d-4223-9ba6-cc2afcfb480f"
  "5d4784ce-8f46-4e52-8a76-56fc2b22741b"
  "bdeac672-75cd-4577-b330-7b0d7fa6e147"}

(def test-set
  #{"21433cc1-d3ae-4b62-b189-5611a2ad6f20"
    "7ab659f2-1f7f-40a0-a4b8-6b9dfa3c3ecb"
    "1bb8bc84-fe02-4a05-92a0-c0aacf897b6e"
    "90d2d66b-dc32-4737-a2b3-25fcb6ae3474"
    "b865e2b3-a7ef-4cb9-a342-bb2192df8183"
    "b7ee4cbb-3011-4d6a-b8c5-97d4a2b028c4"
    "c16423b1-2353-475c-a43e-987a46fa1f00"
    "658b0515-e59d-4223-9ba6-cc2afcfb480f"
    "5d4784ce-8f46-4e52-8a76-56fc2b22741b"
    "bdeac672-75cd-4577-b330-7b0d7fa6e147"})

(defn gdm-id->iri [gdm]
  (str "https://genegraph.clinicalgenome.org/r/" gdm))

(defn gdm-id->outcomes [id app]
  (let [store @(get-in app [:storage :gene-validity-version-store :instance])]
    (->> (storage/scan store [:outcomes (gdm-id->iri id)]))))

(defn gdm-id->events [id app]
  (let [store @(get-in app [:storage :gene-validity-version-store :instance])]
    (->> (gdm-id->outcomes id app)
         (map ::event/offset)
         set
         sort
         (map #(storage/read store [:events :gene-validity-complete %])))))


;; Fetch associated data--maybe these should be in
;; event recorder
(defn outcome->json [event db]
  (if-let [json-str (storage/read db [:transforms
                                      :gene-validity/json-ld
                                      (::event/offset event)
                                      (get-in event [:versions
                                                     :gene-validity/json-ld])])]
    (assoc event ::json (charred/read-json json-str))
    (assoc event ::error :json-not-found)))

(defn outcome->model [event db]
  (if-let [model (storage/read db [:transforms
                                      :gene-validity/model
                                      (::event/offset event)
                                      (get-in event [:versions
                                                     :gene-validity/model])])]
    (assoc event :gene-validity/model model)
    (assoc event ::error :model-not-found)))

(defn outcome->gci-model [event db]
  (if-let [model (storage/read db [:transforms
                                      :gene-validity/gci-model
                                      (::event/offset event)
                                      (get-in event [:versions
                                                     :gene-validity/gci-model])])]
    (assoc event :gene-validity/gci-model model)
    (assoc event ::error :model-not-found)))


(defn outcome->event [outcome db]
  (storage/read db [:events
                    :gene-validity-complete
                    (::event/offset outcome)]))

;; looking at structure of existing sepio event file
(comment
  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene-validity-sepio-2025-10-09.edn.gz"]
    (->> (event-store/event-seq r)
         (take 1)
         (into [])
         tap>))
  )

(def output-events-path "/Users/tristan/data/sepio-events/events.edn.gz")

(comment
  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene-validity-sepio-2025-10-09.edn.gz"]
    (->> (event-store/event-seq r)
         (take 1)
         (into [])
         tap>))
  )

;; testing rsult
(comment
  (event-store/with-event-reader [r output-events-path]
    (->> (event-store/event-seq r)
         (take 1)
         (into [])
         tap>))
  )

(defn write-records-for-api [app]
  (let [store @(get-in app [:storage :gene-validity-version-store :instance])]
    (.setLevel
     (LoggerFactory/getLogger Logger/ROOT_LOGGER_NAME) Level/ERROR)
    (event-store/with-event-writer [ew output-events-path]
      (->> (storage/scan store [:outcomes])
           (map #(outcome->model % store))
           (map (fn [e]
                  (-> {::event/format ::rdf/n-triples
                       ::event/kafka-topic "gene-validity-sepio"
                       ::event/key (::event/key e)
                       ::event/timestamp (::event/timestamp e)
                       ::event/offset (::event/offset e)
                       ::event/data (:gene-validity/model e)}
                      event/serialize
                      (dissoc ::event/data))))
           (run! prn)))
    (.setLevel (LoggerFactory/getLogger Logger/ROOT_LOGGER_NAME) Level/INFO)))


(comment
  (write-records-for-api test-app)
  )

;; adding the take statement
;; seems to prevent a race condition
(comment
  ;;test snapshot generation
  (p/publish (get-in test-app [:topics :trigger-snapshot]) {::event/data {:snapshot :triggered}})

  ;; Evaluating the disconnected evidence lines that show up in production
  ;; Only 5, but seem hard to characterize s-
  (let [store @(get-in test-app [:storage :gene-validity-version-store :instance])
        q (rdf/create-query "
select ?el where {
  ?el a :cg/EvidenceLine .
  ?a a :cg/Statement .
  filter not exists { ?a (:cg/hasEvidenceLines|:cg/hasEvidenceItems|:cg/evidence)* ?el . }
}")]
    (->> (snapshot/latest-records store)
         (remove :gene-validity/valid)
         ;; (take 1)
         ;; (into [])
         ;; tap>
         #_(map #(outcome->json % store))
         #_(map #(outcome->model % store))
         #_(map #(q (:gene-validity/model %)))
         count))
  
  (with-open [w (io/writer "/users/tristan/Desktop/gdi1.json")]
    (let [store @(get-in test-app [:storage :gene-validity-version-store :instance])]
      (->> (snapshot/latest-records store)
           (filter #(= (:gene-validity/gene %)
                       "https://identifiers.org/hgnc:4226"))
           (map #(outcome->json % store))
           (map ::json)
           (run! #(json/write % w :indent :true)))))

  (println (json/write-str {:a "aaa" :b "bbb"} :indent true))

  (+ 1 1)

  ;; x5
  "ff18d307-80a8-44b8-8b1a-e26e8a7d912a"
  (->> (gdm-id->events "ff18d307-80a8-44b8-8b1a-e26e8a7d912a" test-app)
       (take 1)
       #_(map #(assoc % :tap-abbrev true :pp-model true))
       (map #(assoc % :tap-json true))
       (run! #(p/publish (get-in test-app [:topics :transform-topic]) %)))

  ;; x4
  (->> (gdm-id->events (second test-set) test-app)
       (take 1)
       #_(map #(assoc % :tap-abbrev true :pp-model true))
       (map #(assoc % :tap-json true))
       (run! #(p/publish (get-in test-app [:topics :transform-topic]) %)))

  (->> (gdm-id->outcomes (second test-set) test-app)
       tap>)
  
  (->> (storage/scan @(get-in test-app [:storage :gene-validity-version-store :instance])
                     [:outcomes (gdm-id->iri (first test-set))])
       tap>)

  (->> (storage/scan @(get-in test-app [:storage :gene-validity-version-store :instance])
                     [:outcomes])
       (filter #(seq (:gene-validity/curation-reasons %)))
       (take 5)
       tap>)

  (->> (storage/scan @(get-in test-app [:storage :gene-validity-version-store :instance])
                     [:outcomes])
       (map #(:gene-validity/curation-reasons %))
       (reduce set/union)
       tap>)
  

  
  (->> (rocksdb/range-get @(get-in test-app [:storage :gene-validity-version-store :instance])
                          {:prefix [:events :gene-validity-complete]
                           :return :ref})
       (take 1)
       (map deref)
       #_(filter #(test-set (gdm-id %)))
       #_(map #(assoc %
                      :tap-without-models true
                      #_#_:pp-model true))
       (run! #(p/publish (get-in test-app [:topics :transform-topic]) %)))

  (p/publish (get-in test-app [:topics :transform-topic])
             (assoc (storage/read @(get-in test-app [:storage
                                                     :gene-validity-version-store
                                                     :instance])
                                  [:events :gene-validity-complete 4993])
                    :tap-without-models true))

  (->> (rocksdb/range-get @(get-in test-app [:storage :gene-validity-version-store :instance])
                          {:prefix [:events :gene-validity-complete]
                           :return :ref})
       (take 5)
       (map deref)
       #_(map #(get-in % [::event/data :symbol]))
       #_frequencies
       #_(sort-by val)
       #_reverse
       #_(take 10)
       tap>)






  (->> (rocksdb/range-get @(get-in test-app [:storage :gene-validity-version-store :instance])
                          {:prefix [:events :gene-validity-complete]
                           :return :ref})
       #_(take 5)
       (map deref)
       (map gdm-id)
       frequencies
       (sort-by val)
       reverse
       (take 10)
       tap>)

  (->> (rocksdb/range-get @(get-in test-app [:storage :gene-validity-version-store :instance])
                          {:prefix [:events :gene-validity-complete]
                           :return :ref})
       #_(take 5)
       (map deref)
       (remove #(or (get-in % [::event/data :resourceParent :gdm :PK])
                    (get-in % [::event/data :resourceParent :gdm :uuid])))
       (take 5)
       tap>)

  (->> (rocksdb/range-get @(get-in test-app [:storage :gene-validity-version-store :instance])
                          {:prefix [:transforms :gene-validity/json-ld]
                           :return :ref})
       (take 5)
       (mapv #(-> % deref charred/read-json))
       tap>)
  
  (time (gv/reprocess-events test-app {:force-reload #{
                                                       :gene-validity/json-ld
                                                       :gene-validity/model
                                                       :gene-validity/website-event}}))
  
  (+ 1 1)
  (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])]
    (->> (storage/scan db [:outcomes])
         (remove :gene-validity/valid)
         count))

  ;; read json-ld
  (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])]
    (->> (storage/scan db [:outcomes])
         (remove :gene-validity/valid)
         (take 1)
         (map #(-> (storage/read db [:transforms :gene-validity/json-ld (::event/offset %) 1])
                   charred/read-json))
         tap>))

  ;; read website json
  (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])]
    (->> (storage/scan db [:outcomes])
         (remove :gene-validity/valid)
         (take 1)
         (map #(-> (storage/read db [:transforms :gene-validity/website-event (::event/offset %) 1])
                   ))
         tap>))

  (time (gv/reprocess-events test-app {:force-reload #{:gene-validity/json-ld
                                                       :gene-validity/model
                                                       :gene-validity/website-event}}))

  (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])]
    (->> (storage/scan db [:outcomes])
         (map #(assoc %
                      :gene-validity/website-event
                      (storage/read db [:transforms :gene-validity/website-event (::event/offset %) 1])))
         (filter :gene-validity/website-event)
         (remove #(spec/valid? ::website-events/event-data (:gene-validity/website-event %)))
         count))

  (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])]
    (->> (storage/scan db [:outcomes])
         #_(remove :gene-validity/valid)
         #_(take 1)
         (map #(assoc %
                      :gene-validity/website-event
                      (storage/read db [:transforms :gene-validity/website-event (::event/offset %) 1])))
         (filter :gene-validity/website-event)
         (remove #(spec/valid? ::website-events/event-data (:gene-validity/website-event %)))
         #_(filter :gene-validity/last-outcome)
         #_count
         (take 1)
         #_tap>
         (run! #(spec/explain ::website-events/event-data (:gene-validity/website-event %)))))

  ;; read rdf model
  (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])]
    (->> (storage/scan db [:outcomes])
         (remove :gene-validity/valid)
         (take 1)
         (run! #(-> (storage/read db [:transforms :gene-validity/model (::event/offset %) 1])
                    rdf/pp-model))))

  ;; check for type of failed tests
  (->> (storage/scan @(get-in test-app
                              [:storage :gene-validity-version-store :instance])
                     [:outcomes])
       (remove :gene-validity/valid)
       (map :gene-validity/failed-tests)
       (reduce concat)
       set)

  (let [store @(get-in test-app [:storage :gene-validity-version-store :instance])]
    (->> (storage/scan store [:outcomes])
         (filter #(get (:gene-validity/activity %) :cg/Submitted))
         (group-by :gene-validity/gdm)
         (filter #(> (count (val %)) 1))                                                               
         (map (fn [[gdm outcomes]]
                (let [sorted (sort-by ::event/offset outcomes)                                         
                      broken (filter (fn [[prev curr]]
                                       (not= (::event/offset prev)                                     
                                             (::event/offset (:gene-validity/last-outcome curr))))
                                     (partition 2 1 sorted))]                                          
                  {:gdm gdm :broken-links (count broken) :total (count sorted)})))                     
         (filter #(pos? (:broken-links %)))                                                            
         count))

  (tap> gv/env)

  (let [store @(get-in test-app [:storage :gene-validity-version-store :instance])]
    (->> (storage/scan store [:outcomes "https://genegraph.clinicalgenome.org/r/a2d7ac24-7e2d-4a5a-90db-10dd997566bb"])                                    tap>))

  "https://genegraph.clinicalgenome.org/r/a2d7ac24-7e2d-4a5a-90db-10dd997566bb")

;; testing production version 
(comment
  ;; events sourced from Kafka don't publish to transform topic
  (def prod-app (p/init gv/gv-transformer-def))
  (p/start prod-app)
  (p/stop prod-app)


  ;; events sourced locally through simple-queue-topic do publish to transform topic
  (def prod-app-1
    (p/init (assoc-in gv/gv-transformer-def
                      [:topics :gene-validity-complete]
                      {:name :gene-validity-complete
                       :type :simple-queue-topic})))
  (p/start prod-app-1)

  (time
   (event-store/with-event-reader [r source-file]
     (run! #(p/publish (get-in prod-app-1 [:topics :gene-validity-complete]) %)
           (take 1 (event-store/event-seq r)))))
  
  )


(comment
  (storage/scan @(get-in app
                         [:storage :gene-validity-version-store :instance])
                [:outcomes])

  (->> (rocksdb/range-get @(get-in test-app [:storage :gene-validity-version-store :instance])
                          {:prefix [:events :gene-validity-complete]
                           :return :ref})
       (take 1)
       (map deref)
       #_(filter #(test-set (gdm-id %)))
       #_(map #(assoc %
                      :tap-without-models true
                      #_#_:pp-model true))
       (run! #(p/publish (get-in test-app [:topics :transform-topic]) %)))

  (p/publish
   (get-in test-app [:topics :transform-topic])
   (assoc (storage/read @(get-in test-app
                           [:storage :gene-validity-version-store :instance])
                  [:events :gene-validity-complete 8159])
          :tap-json true
          :force-reload #{:gene-validity/json-ld
                          :gene-validity/model
                          :gene-validity/website-event}))
  )


;; Structure for dealing with unpublish events with no prior event.
;; should investigate these and their source, though there are only 10
(comment

  (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])]
    (->> (storage/scan db [:outcomes])
         #_(remove :gene-validity/valid)
         #_(take 1)
         (map #(assoc %
                      :gene-validity/website-event
                      (storage/read db [:transforms :gene-validity/website-event (::event/offset %) 1])))
         (remove :gene-validity/website-event)
         #_(remove #(spec/valid? ::website-events/event-data (:gene-validity/website-event %)))
         #_(filter :gene-validity/last-outcome)
         count
         #_(take 1)
         #_tap>
         #_(run! #(spec/explain ::website-events/event-data (:gene-validity/website-event %)))))
  

  ;; 78 after initial load
  ;; 8 after reprocessing ...

  ;; this is suspicious and needs investigating; should check after production deployment
  ;; 
  
  )

;; testing new validation on GDI1
(comment
  (with-open [w (io/writer "/users/tristan/Desktop/gdi1.json")]
    (let [store @(get-in test-app [:storage :gene-validity-version-store :instance])]
      (->> (snapshot/latest-records store)
           (filter #(= (:gene-validity/gene %)
                       "https://identifiers.org/hgnc:4226"))
           (map #(outcome->json % store))
           (map ::json)
           (run! #(json/write % w :indent :true)))))

  ;; return-gdi1
  (let [store @(get-in test-app [:storage :gene-validity-version-store :instance])]
    (->> (snapshot/latest-records store)
         (filter #(= (:gene-validity/gene %)
                     "https://identifiers.org/hgnc:4226"))
         (map #(outcome->json % store))
         (map ::json)
         tap>))

  ;; latest gdi1
  (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])
        xform-topic (get-in test-app [:topics :transform-topic])
        evt (storage/read db [:events :gene-validity-complete 10957])]
    (p/publish xform-topic
               (assoc evt
                      :tap-json true
                      :force-reload #{:gene-validity/gci-model
                                      :gene-validity/json-ld
                                      :gene-validity/model
                                      :gene-validity/website-event})))

  ;; trmt1 capturing phase status
  (let [store @(get-in test-app [:storage :gene-validity-version-store :instance])
        q (rdf/create-query "
select ?x where {?x a :gg/individual ; :gg/phaseStatus ?p }")]
    (->> (snapshot/latest-records store)
         #_(take 500)
         #_(filter #(= (:gene-validity/gene %)
                     "https://identifiers.org/hgnc:25980"))
         (mapv #(some-> (outcome->gci-model % store)
                        :gene-validity/gci-model 
                        q))
         (mapcat (fn [i] (map #(rdf/ld1-> % [:gg/phaseStatus]) i)))
         set
         #_(run! #(rdf/pp-model (:gene-validity/gci-model %)))))

  #{"UNKNOWN" "SUSPECTED_IN_TRANS" "PROVEN_IN_TRANS"}
  
  (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])
        xform-topic (get-in test-app [:topics :transform-topic])
        evt (storage/read db [:events :gene-validity-complete 13613])]
    
    #_(tap> evt)
    #_(p/publish xform-topic
                 (assoc evt
                        :tap-without-models true
                        :pp-gci-model true
                        :force-reload #{:gene-validity/gci-model
                                        :gene-validity/json-ld
                                        :gene-validity/model
                                        :gene-validity/website-event})))



  (let [store @(get-in test-app [:storage :gene-validity-version-store :instance])]
    (->> (snapshot/latest-records store)
         (filter #(= "https://genegraph.clinicalgenome.org/terms/Definitive"
                     (:gene-validity/classification %)))
         (take 1)
         tap>))
  
  ;; return-esco2 Secondary Approver Issue
  ;; Need to implement GCEP ID Translator
  (let [store @(get-in test-app [:storage :gene-validity-version-store :instance])]
    (->> (snapshot/latest-records store)
         (filter #(= (:gene-validity/gene %)
                     "https://identifiers.org/hgnc:27230"))
         (map #(outcome->json % store))
         (map ::json)
         tap>))

  (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])
        xform-topic (get-in test-app [:topics :transform-topic])
        evt (storage/read db [:events :gene-validity-complete 9879])]
    #_(tap> evt)
    (p/publish xform-topic
                 (assoc evt
                        :tap-without-models true
                        :tap-json true
                        :pp-model true
                        :force-reload #{:gene-validity/gci-model
                                        :gene-validity/json-ld
                                        :gene-validity/model
                                        :gene-validity/website-event})))

  
  (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])
        xform-topic (get-in test-app [:topics :transform-topic])
        evt (storage/read db [:events :gene-validity-complete 10957])]
    (p/publish xform-topic
               (assoc evt
                      :tap-without-models true
                      :pp-gci-model true
                      :force-reload #{:gene-validity/gci-model
                                      :gene-validity/json-ld
                                      :gene-validity/model
                                      :gene-validity/website-event})))

  (time (gv/reprocess-events test-app {:force-reload #{#_:gene-validity/gci-model
                                                       :gene-validity/json-ld
                                                       :gene-validity/model
                                                       :gene-validity/website-event}}))
  )


(comment
  ;; Github #7 Age type and age unit
  
  (let [store @(get-in test-app [:storage :gene-validity-version-store :instance])
        q (rdf/create-query "select ?p where { ?p a :cg/Proband } ")]
    (->> (snapshot/latest-records store)
         #_(take 100)
         (map #(outcome->model % store))
         (map (fn [e]
                (->> (q (:gene-validity/model e))
                     (map #(some-> (rdf/ld1-> % [:cg/ageType]) rdf/->kw))
                     set)))
         (reduce set/union)
         tap>))

  (let [store @(get-in test-app [:storage :gene-validity-version-store :instance])
        q (rdf/create-query "select ?p where { ?p a :cg/Proband } ")]
    (->> (snapshot/latest-records store)
         #_(take 1)
         (map #(outcome->model % store))
         (map validation/validate-fn)
         (remove #(get-in % [:gene-validity/shacl-report :conforms?]))
         (map #(dissoc % :gene-validity/model))
         (take 1)
         tap>))


  ;; issues with reprocessing
  (let [store @(get-in test-app [:storage :gene-validity-version-store :instance])]
    (->> (snapshot/latest-records store)
         #_(take 1)
         (map #(outcome->model % store))
         (remove :gene-validity/model)
         #_count
         (mapv ::event/offset)))

  ;; x3
  (let [store @(get-in test-app [:storage :gene-validity-version-store :instance])]
    (->> (snapshot/latest-records store)
         (remove :gene-validity/valid)
         count))


  ;; first run
  ;; second run has same issues
  ;; issue (effectively) resolved--many probands have events without time units
  [963 592 1511 1311 1369 227]

  ;; x2
  (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])
        xform-topic (get-in test-app [:topics :transform-topic])
        evt (storage/read db [:events :gene-validity-complete 963])]
    #_(tap> evt)
    (p/publish xform-topic
                 (assoc evt
                        :tap-without-models true
                        :pp-model true
                        #_#_:pp-gci-model true
                        :force-reload #{:gene-validity/gci-model
                                        :gene-validity/json-ld
                                        :gene-validity/model
                                        :gene-validity/website-event})))

  (let [store @(get-in test-app [:storage :gene-validity-version-store :instance])]
    (->> (snapshot/latest-records store)
         #_(take 1000)
         #_(storage/scan store [:outcomes])
         (map #(outcome->model % store))
         (map validation/validate-fn)
         (remove #(get-in % [:gene-validity/shacl-report :conforms?]))
         (mapv #(dissoc % :gene-validity/model))
         #_(mapv #(into #{} (map :constraint (get-in % [:gene-validity/shacl-report :entries]))))
         count
         #_(take 1)
         #_(run! #(rdf/pp-model (:gene-validity/model %)))
         #_tap>))


  (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])
        xform-topic (get-in test-app [:topics :transform-topic])
        evt (storage/read db [:events :gene-validity-complete 10329])]
    (p/publish xform-topic
               (assoc evt
                      :tap-without-models true
                      :tap-json
                      :pp-model true
                      #_#_:pp-gci-model true
                      :force-reload #{:gene-validity/gci-model
                                      :gene-validity/json-ld
                                      :gene-validity/model
                                      :gene-validity/website-event})))

  (time (gv/reprocess-events test-app {:force-reload #{:gene-validity/gci-model
                                                       :gene-validity/json-ld
                                                       :gene-validity/model
                                                       :gene-validity/website-event}}))
  
  )


(str/trim "\n")

;; migrating age
(comment
  ;; ageUnit
  (def unitset #{:cg/Months :cg/WeeksGestation :cg/Days :cg/Hours :cg/Weeks :cg/Years})
;; ageType
(def typeset #{:cg/AgeAtReport :cg/AgeAtOnset :cg/AgeAtDeath :cg/AgeAtDiagnosis})

("Months" "WeeksGestation" "Days" "Hours" "Weeks" "Years")

("AgeAtReport" "AgeAtOnset" "AgeAtDeath" "AgeAtDiagnosis")

(let [store @(get-in test-app [:storage :gene-validity-version-store :instance])
      q (rdf/create-query "
select ?p where {
 ?p a :cg/Proband ;
 :cg/ageUnit :cg/Hours .
  } ")]
  "filter (?unit NOT IN ( :cg/WeeksGestation ) )"
  (->> (snapshot/latest-records store)
       #_(take 100)
       (map #(outcome->model % store))
       (map (fn [e]
              (->> (q (:gene-validity/model e))
                   count
                   #_(map #(some-> (rdf/ld1-> % [:cg/ageType]) rdf/->kw))
                   #_set)))
       (reduce +)
       #_(reduce set/union)
       tap>))




  )


;; observing report on fields in sex

(comment
  (let [store @(get-in test-app [:storage :gene-validity-version-store :instance])
        q (rdf/create-query "
select ?p where {
 ?p a :cg/Proband .
  } ")]
    (->> (snapshot/latest-records store)
         #_(take 100)
         (map #(outcome->model % store))
         (map (fn [e]
                (->> (q (:gene-validity/model e))
                     (map #(some-> (rdf/ld1-> % [:cg/sex]) rdf/->kw))
                     set)))
         (reduce set/union)
         tap>))
  )


;; todo

;; have (I think) finally addressed issues with parallel processor -- should validate
;; create and deploy production deployment
;; create and deploy 


"ff18d307-80a8-44b8-8b1a-e26e8a7d912a"
;; investingating reports of missing PMIDs
(comment
  (let [store @(get-in test-app [:storage :gene-validity-version-store :instance])]
    (->> (snapshot/latest-records store)
         #_(take 1000)
         #_(storage/scan store [:outcomes])
         (map #(outcome->model % store))
         (map validation/validate-fn)
         (remove #(get-in % [:gene-validity/shacl-report :conforms?]))
         (mapv #(dissoc % :gene-validity/model))
         #_(mapv #(into #{} (map :constraint (get-in % [:gene-validity/shacl-report :entries]))))
         count
         #_(take 1)
         #_(run! #(rdf/pp-model (:gene-validity/model %)))
         #_tap>))

  (let [store @(get-in test-app [:storage :gene-validity-version-store :instance])]
    (->> (snapshot/latest-records store)
         (pmap (fn [e]
                 (-> (outcome->model e store)
                     validation/validate-fn)))
         (remove #(get-in % [:gene-validity/shacl-report :conforms?]))
         count))

  ;;   HGNC:4288 gjb6
  (let [store @(get-in test-app [:storage :gene-validity-version-store :instance])
        q (rdf/create-query " select ?pmid where { ?ev :dc/source ?pmid } ")]
    (->> (snapshot/latest-records store)
         (filter #(= "https://genegraph.clinicalgenome.org/r/e055960a-a364-4c03-85ff-30f93730c380"
                     (:gene-validity/gdm %)))
         #_(mapv #(-> %
                    (outcome->model store)
                    (outcome->json store)))
         #_(mapv #(outcome->event % store))
         (mapv (fn [e] [(set/difference
                         (set (mapv #(get-in % [:article :pmid])
                                    (get-in (outcome->event e store)
                                            [::event/data
                                             :properties
                                             :resourceParent
                                             :gdm
                                             :annotations])))
                         (set (mapv #(re-find #"\d+$" (str %))
                                    (q (:gene-validity/model (outcome->model e store))))))]))
         #_tap>
         #_(mapv #(q (:gene-validity/model %)))))


)

;; testing restoration of event snapshot
(comment
  (def storage-app
    (p/init
     {:type :genegraph-app
      :storage {:gene-validity-version-store
                (assoc gv/gene-validity-version-store
                       :reset-opts {}
                       :load-snapshot true)}}))

  (p/start storage-app)
  (+ 1 1)
  
  (p/stop storage-app)
  
  )
;; one time export of GCI-EX to web addresses
(comment
  (let [path "/Users/tristan/data/genegraph-base/gci-express-with-entrez-ids.json"
        out-path "/Users/tristan/Desktop/gciex-links.csv"]
    (with-open [r (io/reader path)
                w (io/writer out-path)]
      (->> (charred/read-json r)
           (mapv (fn [[k v]] [(str "https://search.clinicalgenome.org/kb/gene-validity/CGGCIEX:assertion_" k)
                              (get v "title")]))
           (charred/write-csv w))))

  (let [path "/Users/tristan/Downloads/ClinGen-Gene-Expess-Data-03272019.json"]
    (with-open [r (io/reader path)]
      (->> (charred/read-json r)
           (mapv (fn [[k v]] [(str "https://search.clinicalgenome.org/kb/gene-validity/CGGCIEX:assertion_" k)
                              (get v "title")]))
           tap>)))

  (let [path "/Users/tristan/Downloads/ClinGen-Gene-Expess-Data-03272019.json"
        out-path "/Users/tristan/Desktop/gciex-legacy-links.csv"]
    (with-open [r (io/reader path)
                w (io/writer out-path)]
      (->> (charred/read-json r)
           (mapv (fn [[k v]] [(str "https://search.clinicalgenome.org/kb/gene-validity/CGGCIEX:assertion_" k)
                              (get v "title")]))
           (charred/write-csv w))))

  )
