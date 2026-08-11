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
            [clojure.spec.alpha :as spec]
            [clojure.edn :as edn])
  (:import [ch.qos.logback.classic Logger Level]
           [org.slf4j LoggerFactory]
           [java.time Instant]
           [java.io PushbackReader]))

(defn gdm-id [e]
  (or (get-in e [:resourceParent :gdm :PK])
      (get-in e [:resourceParent :gdm :uuid])
      (get-in e [:properties :resourceParent :gdm :uuid])))

(def source-file
  "/Users/tristan/data/genegraph-neo/gene_validity_all-2026-07-01.edn.gz")

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

  (+ 1 1)

  (event-store/with-event-reader [r source-file]
    (count (event-store/event-seq r)))

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
            :enter (fn [e]
                     (event/store e
                                  :gene-validity-version-store
                                  [::output
                                   topic
                                   (or (::batch e) 0)
                                   (or (:gene-validity/sequence e) 0)]
                                  (::event/data e)))})]
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
                                      (:transform-version event)])]
    (assoc event ::json (charred/read-json json-str))
    (assoc event ::error :json-not-found)))

(defn outcome->website-event [event db]
  (if-let [json-str (storage/read db [:transforms
                                      :gene-validity/website-event
                                      (::event/offset event)
                                      (:transform-version event)])]
    (assoc event :gene-validity/website-event json-str)
    (assoc event ::error :website-event-not-found)))

(defn outcome->model [event db]
  (if-let [model (storage/read db [:transforms
                                   :gene-validity/model
                                   (::event/offset event)
                                   (:transform-version event)])]
    (assoc event :gene-validity/model model)
    (assoc event ::error :model-not-found)))

(defn outcome->gci-model [event db]
  (if-let [model (storage/read db [:transforms
                                      :gene-validity/gci-model
                                      (::event/offset event)
                                   (:transform-version event)])]
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
  (+ 1 1)
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

  (let [store @(get-in test-app [:storage :gene-validity-version-store :instance])]
    (->> (snapshot/latest-records store)
         (filter #(= (:gene-validity/gene %)
                     "https://identifiers.org/hgnc:15766"))
         (map #(outcome->gci-model % store))
         (run! #(rdf/pp-model (:gene-validity/gci-model %)))))

  (let [store @(get-in test-app [:storage :gene-validity-version-store :instance])]
    (->> (snapshot/latest-records store)
         (filter #(= (:gene-validity/gene %)
                     "https://identifiers.org/hgnc:15766"))
         (map #(outcome->event % store))
         tap>
         #_(map #(outcome->gci-model % store))
         #_(run! #(rdf/pp-model (:gene-validity/gci-model %)))))
  ;; https://genegraph.clinicalgenome.org/r/8955f461-a2dd-47a7-8f42-09df354bf073
  (let [store @(get-in test-app [:storage :gene-validity-version-store :instance])]
    (->> #_(snapshot/latest-records store)
         #_(gdm-id->events "ff18d307-80a8-44b8-8b1a-e26e8a7d912a" test-app)
         (storage/scan store [:outcomes])
         #_(filter #(= (:gene-validity/gene %)
                     "https://identifiers.org/hgnc:15766"))
         (filter #(= (:gene-validity/gdm %)
                     "https://genegraph.clinicalgenome.org/r/8955f461-a2dd-47a7-8f42-09df354bf073"))
         (mapv #(assoc % :date (Instant/ofEpochMilli (::event/timestamp %))))
         #_(map #(outcome->event % store))
         tap>
         #_(map #(outcome->gci-model % store))
         #_(run! #(rdf/pp-model (:gene-validity/gci-model %)))))

  (println (json/write-str {:a "aaa" :b "bbb"} :indent true))

  (+ 1 1)

  ;; x5
  "ff18d307-80a8-44b8-8b1a-e26e8a7d912a"
  (->> (gdm-id->events "ff18d307-80a8-44b8-8b1a-e26e8a7d912a" test-app)
       (take 1)
       #_(map #(assoc % :tap-abbrev true :pp-model true))
       (map #(assoc % :tap-without-models))
       (run! #(p/publish (get-in test-app [:topics :transform-topic]) %)))

  ;; x4
  (->> (gdm-id->events (second test-set) test-app)
       (take 1)
       #_(map #(assoc % :tap-abbrev true :pp-model true))
       (map #(assoc % :tap-without-models true))
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
                    :tap-abbrev true))

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
  
  (time (gv/reprocess-events test-app {:force-reload #{:gene-validity/gci-model
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
         (mapv (fn [o]
                 (event/deserialize
                  {::event/value (storage/read db
                                               [:transforms
                                                :gene-validity/json-ld
                                                (::event/offset o)
                                                1])
                   ::event/format ::rdf/json-ld})))
         (run! #(rdf/pp-model (::event/data %)))))
  (def cmp
    (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])]
      (->> (storage/scan db [:outcomes])
           (remove :gene-validity/valid)
           (take 1)
           (mapv (fn [o]
                   {:jsonld-model
                    (::event/data
                     (event/deserialize
                      {::event/value (storage/read db
                                                   [:transforms
                                                    :gene-validity/json-ld
                                                    (::event/offset o)
                                                    1])
                       ::event/format ::rdf/json-ld}))
                    :model (storage/read db
                                         [:transforms
                                          :gene-validity/model
                                          (::event/offset o)
                                          1])}))
           (first))))
  (.size (:jsonld-model cmp))
  (.size (:model cmp))

  (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])]
    (->> (storage/scan db [:outcomes])
         (filter :gene-validity/valid)
         (take 10)
         (run! (fn [o]
                 (rdf/pp-model
                  (rdf/difference
                   (storage/read db
                                 [:transforms
                                  :gene-validity/model
                                  (::event/offset o)
                                  1])
                   (::event/data
                    (event/deserialize
                     {::event/value (storage/read db
                                                  [:transforms
                                                   :gene-validity/json-ld
                                                   (::event/offset o)
                                                   1])
                      ::event/format ::rdf/json-ld}))))))))
  (rdf/pp-model (rdf/difference (:model cmp) (:jsonld-model cmp)))

  (rdf/pp-model (rdf/difference (:jsonld-model cmp) (:model cmp)))
  (rdf/is-isomorphic? (:jsonld-model cmp) (:model cmp))

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
                   charred/read-json))
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

  ;; trmt1 capturing phase status -- evolving into developing genotype concept
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
         (mapcat (fn [i] (map #(rdf/->kw (rdf/ld1-> % [:gg/phaseStatus])) i)))
         frequencies
         #_set
         #_count
         #_(run! #(rdf/pp-model (:gene-validity/gci-model %)))))
  
  #{"UNKNOWN" "SUSPECTED_IN_TRANS" "PROVEN_IN_TRANS"}

  {:ProvenInTrans 1047, :SuspectedInTrans 230, :Unknown 12}
  
  (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])
        xform-topic (get-in test-app [:topics :transform-topic])
        evt (storage/read db [:events :gene-validity-complete 13613])]
    
    #_(tap> evt)
    (p/publish xform-topic
               (assoc evt
                      :pp-model true
                      :pp-gci-model true
                      :tap-json true
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
                      :tap-json true
                      :pp-model true
                      #_#_:pp-gci-model true
                      :force-reload #{:gene-validity/gci-model
                                      :gene-validity/json-ld
                                      :gene-validity/model
                                      :gene-validity/website-event})))

  (time (gv/reprocess-events test-app {:force-reload #{:gene-validity/json-ld
                                                       :gene-validity/model
                                                       :gene-validity/website-event}}))
  
  )

;; migrating age
(comment
  ;; ageUnit
  (def unitset #{:cg/Months :cg/WeeksGestation :cg/Days :cg/Hours :cg/Weeks :cg/Years})
  ;; ageType
  (def typeset #{:cg/AgeAtReport :cg/AgeAtOnset :cg/AgeAtDeath :cg/AgeAtDiagnosis})

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


(comment
  (def ndd-genehub
    (with-open [r (io/reader "/Users/tristan/Downloads/Full-Data.csv")]
      (->> (charred/read-csv r)
           (take 5)
           tap>)))
  
 )

;; promise experimentation
(comment
  (do
    (let [p (promise)]
      (Thread/startVirtualThread #(println @p))
      (Thread/sleep 500)
      (deliver p "Hi there!")))
  
  (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])
        events (take 1 (rocksdb/range-get db                
                                         {:prefix [:events :gene-validity-complete]
                                          :return :ref}))
        event-promises (mapv (fn [e] [e (promise)]) events)]
    (Thread/startVirtualThread (fn []
                                 (clojure.pprint/pprint
                                  (frequencies
                                   (map #(-> % second deref) event-promises)))
                                 #_(run! deref (map second event-promises))
                                 (println "promises delivered")))
    (run! (fn [[e p]]
            (p/publish (get-in test-app [:topics :gene-validity-complete])
                       (assoc @e
                              ::event/completion-promise p
                              :batch 1)))
          event-promises))

  (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])]
      (->> (rocksdb/range-get db
                              {:prefix [::output]
                               :return :ref})
           count))

  )

;; testing validation of system changes
(comment
  (time (gv/reprocess-events test-app {:force-reload #{:gene-validity/gci-model
                                                       :gene-validity/json-ld
                                                       :gene-validity/model
                                                       :gene-validity/website-event}}))
  
  (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])]
    (->> (rocksdb/range-get db
                            {:prefix [:outcomes]
                             :return :ref})
         count))

  (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])]
    (->> (rocksdb/range-get db
                            {:prefix [:transforms]
                             :return :ref})
         count))
  
  (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])]
    (->> (storage/scan db [:outcomes])
         (take 10)
         tap>))

  
  )
(comment
  (do
    (defn fetch-xform [{:keys [db transform-type offset transform-version] :as req}]
      (tap> req)
      (storage/read db [:transforms transform-type offset transform-version]))

    (defn fetch-comparision-data [fetch-request]
      (reduce (fn [a v]
                (assoc a
                       v
                       (fetch-xform (assoc fetch-request :transform-version v))))
              {}
              (:transform-versions fetch-request)))

    (defn fetch-all-transforms [fetch-request]
      (reduce (fn [a t]
                (assoc a
                       t
                       (fetch-comparision-data (assoc fetch-request :transform-type t))))
              {}
              (:transform-types fetch-request)))


    
    (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])]
      (->> (storage/scan db [:outcomes])
           (take 1)
           (mapv #(fetch-all-transforms
                   {:db db
                    :transform-types [:gene-validity/json-ld :gene-validity/model]
                    :transform-versions [1 2]
                    :offset (::event/offset %)}))
           tap>)))

  
  )


;; compare JSON-LD
;; compare website events
;; compare transformed models
;; compare outcomes
;; Look into versioning... recurations may be getting
;; marked as patches.
(comment
  ;; Case control exploration
  (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])
        q (rdf/create-query "select ?x where { ?x a :cg/CaseControlStudyResult }")]
    (->> (storage/scan db [:outcomes])
         (filter #(and (= 2 (:transform-version %))
                       (get-in % [:gene-validity/classes :cg/CaseControlStudyResult])))
         (take 10)
         (map #(outcome->model % db))
         (mapcat (fn [e] (mapcat #(rdf/ld-> % [:cg/upperConfidenceLimit])
                                 (q (:gene-validity/model e)))))
         #_frequencies
         #_tap>))

  ;; cg:statisticalSignificanceValueType values
  {"Odds Ratio" 447, "Other" 115, "" 51, "Relative Risk" 17}

  ;; cg:statisticalSignificanceType values -- almost entirely free text
  {"" 593, "Burden testing" 11, "Chi Square" 3, "Chi-squared test" 1, "Cohort Allelic Sums Test (CAST)" 1, "chi-square" 1, "No evidence for significant association" 1, "Two-sided Fisher's exact test" 2, "P<0.0005" 1, "Association analysis" 1, "Adjusted Odds Ratio by age and sex" 1, "Standard incidence ratios" 1, "Standard incidence rations" 2, "Fischer's exact test" 1, "Cumulative risk" 2, "enrichment of de novo mutations, LOF mutations, splice site mutations in RB1 compared to mutational rate expected by statistical model and over control population" 1, "Hazard ratio adjusted for age, sex, study center and % European ancestry" 1, "two-sided Fisher’s exact test" 2, "Fisher’s exact test for rare variants (n<5) or Chi-square test for common variants" 1, "Sequence Kernel Association test (SKAT-O)" 2, "Fisher Exact Test" 5, "Fishers exact " 2, "Hardy-Weinberg" 1, "mutations/subject in cases v. controls" 4, "Fisher’s exact test with Bonferroni’s  correction" 2, "Chi-Sq" 4, "Generalized estimating equations (GEE)" 1, "hazard ratios (HRs) and age-specific cumulative risks (penetrance)" 2, "zero cases were found to have a variant so they did not do the statistical tests. " 1, "Fisher’s exact test,  two-tailed P-values " 2, "TDT, Chi-square" 2, "Fisher’s exact test / Cohort Allelic Sums Test (CAST)" 1, "chi square " 1, "Fisher’sexacttest" 2, "p value only" 2, "Fisher exact test" 1, "None provided" 2, "Authors comment non-significant p-value" 1, "TDT" 2, "Hazard Ratio" 2, "chi-square analysis: enrichment in DCM in Pham et al. cohort versus gnomAD" 2, "Chi-square" 1, "SKAT-O - Significantly enriched" 1, "Joint likely gene disruptive (LGD) events" 2, "etiological fraction" 3, "Analyses of Hardy–Weinberg equilibrium and the case–control association" 1, "Fisher's exact" 5, "Fisher´s exact" 1, "Analyses of Hardy–Weinberg equilibrium and the case–control association study" 1, "Rare variants burden test: two-tailed Fisher's exact test with significance level of p<0.05 was applied to compare frequencies between total number of variants in cases and contrls." 3, "SKAT-O FDR" 1, "Chi Squared" 3, "Mann-Whitney test for compariosn of two groups, ANOVA Kruskal-Wallis for compariosn of several groups, Spearman correlation. " 1, "Association Analysis" 1}

  ;; 

  )
(comment
  ;; segregation exploration
  (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])
        q (rdf/create-query "select ?x where { ?x a :cg/FamilyCosegregation }")]
    (->> (storage/scan db [:outcomes])
         (filter #(and (= 2 (:transform-version %))
                       (get-in % [:gene-validity/classes :cg/FamilyCosegregation])))
         #_(take 10)
         (map #(outcome->model % db))
         (mapcat (fn [e] (mapcat #(map rdf/->kw (rdf/ld-> % [:cg/sequencingMethod]))
                                 (q (:gene-validity/model e)))))
         frequencies
         #_tap>))
  
  ;; cg:sequencingMethod
  #:cg{:CandidateGeneSequencing 2688, :AllGenesSequencing 1566}
  )


(comment
  ;; Expanding GeneFunctionStudyResult
  (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])
        q (rdf/create-query "select ?fa where { ?fa :cg/interpretation ?x }")]
    (->> (storage/scan db [:outcomes])
         (filter #(and (= 2 (:transform-version %))
                       (get-in % [:gene-validity/classes
                                  :cg/GeneFunctionStudyResult])))
         #_(take 10)
         (map #(outcome->model % db))
         (mapcat (fn [e] (mapcat #(map rdf/->kw 
                                       (rdf/ld-> % [:cg/interpretation]))
                                 (q (:gene-validity/model e)))))
         #_count
         #_(mapcat #(map rdf/->kw (q (:gene-validity/gci-model %))))
         frequencies))

  {:http://purl.obolibrary.org/obo/MI_0933 22, :http://purl.obolibrary.org/obo/MI_0935 39, :cg/GeneFunctionConsistentWithPhenotype 2087, :cg/GeneFunctionSimilarToOtherKnownDiseaseCausingGene 1098, :http://purl.obolibrary.org/obo/MI_0208 140, :cg/GeneAlteredInAffectedPatients 813, :http://purl.obolibrary.org/obo/MI_0915 1881, :cg/GeneSpecificTreatmentRescuesPhenotype 1696, :cg/ProteinAlterationDisruptsModelOrganism 8047, :cg/GeneAlterationProducesDiseaseConsistentPhenotype 4600, :cg/GeneExpressedInDiseaseRelevantTissues 3315}
  {:http://purl.obolibrary.org/obo/MI_0933 22, :http://purl.obolibrary.org/obo/MI_0935 39, :cg/GeneFunctionConsistentWithPhenotype 2087, :cg/GeneFunctionSimilarToOtherKnownDiseaseCausingGene 1098, :http://purl.obolibrary.org/obo/MI_0208 140, :cg/GeneAlteredInAffectedPatients 813, :http://purl.obolibrary.org/obo/MI_0915 1881, :cg/ProteinAlterationDisruptsModelOrganism 8047, :cg/GeneAlterationProducesDiseaseConsistentPhenotype 4600, :cg/GeneExpressedInDiseaseRelevantTissues 3315}
  
  {:http://purl.obolibrary.org/obo/MI_0933 22,
   :http://purl.obolibrary.org/obo/MI_0935 39,
   :cg/GeneFunctionConsistentWithPhenotype 2087,
   :cg/GeneFunctionSimilarToOtherKnownDiseaseCausingGene 1098,
   :http://purl.obolibrary.org/obo/MI_0208 140,
   :cg/GeneAlteredInAffectedPatients 813,
   :http://purl.obolibrary.org/obo/MI_0915 1881,
   :cg/ProteinAlterationDisruptsOrganismFunction 7039,
   :cg/GeneAlterationProducesDiseaseConsistentPhenotype 4600,
   :cg/GeneExpressedInDiseaseRelevantTissues 3315}
  
  {:cg/BiochemicalFunctionA 1098,
   :cg/GeneExpressionB 813,
   :http://purl.obolibrary.org/obo/MI_0933 22,
   :http://purl.obolibrary.org/obo/MI_0935 39,
   :http://purl.obolibrary.org/obo/MI_0208 140,
   :http://purl.obolibrary.org/obo/MI_0915 1881,
   :cg/ProteinAlterationDisruptsOrganismFunction 7039,
   :cg/GeneAlterationProducesDiseaseConsistentPhenotype 4600,
   :cg/BiochemicalFunctionB 2087,
   :cg/GeneExpressionA 3315}

  {:cg/GeneExpressionB 813, :http://purl.obolibrary.org/obo/MI_0933 22, :http://purl.obolibrary.org/obo/MI_0935 39, :cg/GeneFunctionConsistentWithPhenotype 2087, :http://purl.obolibrary.org/obo/MI_0208 140, :http://purl.obolibrary.org/obo/MI_0915 1881, :cg/ProteinAlterationDisruptsOrganismFunction 7039, :cg/GeneFunctionSimilarToOtherKnownGenesCausingDisease 1098, :cg/GeneAlterationProducesDiseaseConsistentPhenotype 4600, :cg/GeneExpressionA 3315}
  
  ;; "none" and "review" map to neutral -- only one but seems an error
  {:gg/gcixform/PatientCells 1339, :gg/gcixform/NonPatientCells 1910, :cg/Neutral 1}
  
  ;; cg:sequencingMethod
  #:cg{:CandidateGeneSequencing 2688, :AllGenesSequencing 1566}

  (time
   (gv/reprocess-events
    test-app
    {:force-reload #{#_:gene-validity/gci-model
                     :gene-validity/json-ld
                     :gene-validity/model
                     :gene-validity/website-event}}))
  (+ 1 1)
  )


;; Constructing sets of classes and properties used by
;; Gene Validity transform.
(comment

  ;; Get Classes
  (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])
        q (rdf/create-query "select ?x where { ?x a ?c }")]
    (->> (storage/scan db [:outcomes])
         (filter #(and (= 1 (:transform-version %))
                       #_(get-in % [:gene-validity/classes
                                    :cg/GeneFunctionStudyResult])))
         #_(take 10)
         (map #(outcome->model % db))
         (mapcat (fn [e] (mapcat #(map rdf/->kw 
                                       (rdf/ld-> % [:rdf/type]))
                                 (q (:gene-validity/model e)))))
         #_count
         #_(mapcat #(map rdf/->kw (q (:gene-validity/model %))))
         frequencies
         ))

  ;; Get Predicates contained in specific classes
  (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])
        q (rdf/create-query "select ?p where { ?s a ?c ; ?p ?o}")]
    (->> (storage/scan db [:outcomes])
         (filter #(and (= 1 (:transform-version %))
                       #_(get-in % [:gene-validity/classes
                                    :cg/GeneFunctionStudyResult])))
         #_(take 1)
         (map #(outcome->model % db))
         (mapcat (fn [e]
                   (map rdf/->kw (q (:gene-validity/model e) {:c :cg/VariantFunctionalImpactEvidence}))))
         #_(mapcat (fn [e] (mapcat #(map rdf/->kw 
                                         (rdf/ld-> % [:rdf/type]))
                                   )))
         #_count
         #_(mapcat #(map rdf/->kw (q (:gene-validity/model %))))
         set
         ))

  ;; Examine objects of predicates
  (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])
        q (rdf/create-query "select ?s where { ?s a ?c ; ?p ?o}")]
    (->> (storage/scan db [:outcomes])
         (filter #(and (= 1 (:transform-version %))))
         (take 100)
         (map #(outcome->model % db))
         (mapcat (fn [e]
                   (mapcat
                    #(rdf/ld-> % [:cg/paternityMaternityConfirmed])
                    (q (:gene-validity/model e)
                       {:p :cg/paternityMaternityConfirmed}))))
         set
         ))

  (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])
        q (rdf/create-query "select ?s where { ?s a ?c ; ?p ?o}")]
    (->> (storage/scan db [:outcomes])
         (filter #(and (= 1 (:transform-version %))))
         (take 100)
         #_(map #(outcome->model % db))
         (map #(outcome->gci-model % db))
         (mapcat (fn [e]
                   (mapcat
                    #(rdf/ld-> % [:rdf/type])
                    (q (:gene-validity/gci-model e)
                       {:p :gg/maternityPaternityConfirmed}))))
         set
         (mapv rdf/->kw)
         ))
  
  #{"Autosomal dominant/X-linked" "Semidominant" "Autosomal recessive"}
  ;; Build value sets
  (do 
    (defn value-set-for-property
      ([property] (value-set-for-property property {}))
      ([property opts]
       (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])
             q (rdf/create-query "select ?s where { ?s a ?c ; ?p ?o}")
             set-members (->> (storage/scan db [:outcomes])
                              (filter #(and (= 1 (:transform-version %))))
                              (take (:max opts 100000))
                              (map #(outcome->model % db))
                              (mapcat (fn [e]
                                        (mapcat
                                         (if (:dont-keywordize opts)
                                           #(rdf/ld-> % [property])
                                           #(map rdf/->kw (rdf/ld-> % [property])))
                                         (q (:gene-validity/model e)
                                            {:p property}))))
                              set)]
         (conj (mapv (fn [v]
                       {:id v
                        :type :skos/Concept})
                     set-members)
               {:id (keyword (namespace property)
                             (str (str/capitalize (name property)) "ValueSet"))
                :type :skos/Collection
                :skos/member (into [] set-members)}))))
    (value-set-for-property :cg/phaseStatusConfidence {#_#_:max 10 #_#_:dont-keywordize true}))

  ;; for determining the arity of attributes
  (do
    (def schema-path "/Users/tristan/code/genegraph-schema/resources/schema.edn")
    (def schema-edn
      (with-open [r (-> schema-path io/reader PushbackReader.)]
        (->> (edn/read r))))
    (defn arity-for-properties-in-class
      [class-schema]
      (apply
       merge-with
       conj
       (into {} (mapv (fn [a] [a []])
                      (:attributes class-schema)))
       (mapv 
        #(reduce (fn [m attr]
                   (assoc m attr (count (rdf/ld-> % [attr]))))
                 {}
                 (:attributes class-schema))
        (:resources class-schema))))
    (defn arity-for-properties
      [model schema-classes]
      (let [type-query (rdf/create-query "select ?s where { ?s a ?t }")]
        (->> schema-classes
             (mapv (fn [c] (assoc c :resources (type-query model {:t (:id c)}))))
             (reduce (fn [m c] (assoc m (:id c) (arity-for-properties-in-class c))) {}))))
    (defn arity-for-models [schema]
      (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])
            schema-classes (filterv #(= :rdfs/Class (:type %)) schema)]
        (update-vals 
         (->> (storage/scan db [:outcomes])
              (filter #(and (= 1 (:transform-version %))))
              (take 500)
              (map #(outcome->model % db))
              (mapv #(arity-for-properties (:gene-validity/model %) schema-classes))
              (reduce (fn [m m1]
                        (merge-with 
                         (fn [m2 m3] (merge-with concat m2 m3))
                         m
                         m1))
                      {}))
         #(update-vals % frequencies))))
    (tap> (arity-for-models schema-edn)))

  (+ 1 1)


  :cg/modelSystem
  [{:id :cg/NonPatientCells, :type :skos/Concept} {:id :cg/PatientCells, :type :skos/Concept} {:id :cg/ModelsystemValueSet, :type :skos/Collection, :skos/member [:cg/NonPatientCells :cg/PatientCells]}]

  ;; used in CaseControlStudyResult
  :cg/method
  [{:id :cg/SingleVariantAnalysis, :type :skos/Concept} {:id :cg/AggregateVariantAnalysis, :type :skos/Concept} {:id :cg/MethodValueSet, :type :skos/Collection, :skos/member [:cg/SingleVariantAnalysis :cg/AggregateVariantAnalysis]}]

  :cg/interpretation
  [{:id :http://purl.obolibrary.org/obo/MI_0933, :type :skos/Concept} {:id :http://purl.obolibrary.org/obo/MI_0935, :type :skos/Concept} {:id :cg/GeneFunctionConsistentWithPhenotype, :type :skos/Concept} {:id :cg/GeneFunctionSimilarToOtherKnownDiseaseCausingGene, :type :skos/Concept} {:id :http://purl.obolibrary.org/obo/MI_0208, :type :skos/Concept} {:id :cg/GeneAlteredInAffectedPatients, :type :skos/Concept} {:id :http://purl.obolibrary.org/obo/MI_0915, :type :skos/Concept} {:id :cg/GeneSpecificTreatmentRescuesPhenotype, :type :skos/Concept} {:id :cg/ProteinAlterationDisruptsModelOrganism, :type :skos/Concept} {:id :cg/GeneAlterationProducesDiseaseConsistentPhenotype, :type :skos/Concept} {:id :cg/GeneExpressedInDiseaseRelevantTissues, :type :skos/Concept} {:id :cg/InterpretationValueSet, :type :skos/Collection, :skos/member [:http://purl.obolibrary.org/obo/MI_0933 :http://purl.obolibrary.org/obo/MI_0935 :cg/GeneFunctionConsistentWithPhenotype :cg/GeneFunctionSimilarToOtherKnownDiseaseCausingGene :http://purl.obolibrary.org/obo/MI_0208 :cg/GeneAlteredInAffectedPatients :http://purl.obolibrary.org/obo/MI_0915 :cg/GeneSpecificTreatmentRescuesPhenotype :cg/ProteinAlterationDisruptsModelOrganism :cg/GeneAlterationProducesDiseaseConsistentPhenotype :cg/GeneExpressedInDiseaseRelevantTissues]}]



  :cg/sequencingMethod
  [{:id :cg/CandidateGeneSequencing, :type :skos/Concept} {:id :cg/AllGenesSequencing, :type :skos/Concept} {:id :cg/SequencingmethodValueSet, :type :skos/Collection, :skos/member [:cg/CandidateGeneSequencing :cg/AllGenesSequencing]}]

  ;; genetictestingmethod
  [{:id :cg/GenePanels, :type :skos/Concept} {:id :cg/SangerSequencing, :type :skos/Concept} {:id :cg/ChromosomalMicroarray, :type :skos/Concept} {:id :cg/RestrictionDigest, :type :skos/Concept} {:id :cg/Genotyping, :type :skos/Concept} {:id :cg/SSCP, :type :skos/Concept} {:id :cg/ExomeSequencing, :type :skos/Concept} {:id :cg/LinkageAnalysis, :type :skos/Concept} {:id :cg/Other, :type :skos/Concept} {:id :cg/PCR, :type :skos/Concept} {:id :cg/WholeGenomeSequencing, :type :skos/Concept} {:id :cg/DenaturingGradientGel, :type :skos/Concept} {:id :cg/HomozygosityMapping, :type :skos/Concept} {:id :cg/HighResolutionMelting, :type :skos/Concept} {:id :cg/GenotypeconfirmationmethodValueSet, :type :skos/Collection, :skos/member [:cg/GenePanels :cg/SangerSequencing :cg/ChromosomalMicroarray :cg/RestrictionDigest :cg/Genotyping :cg/SSCP :cg/ExomeSequencing :cg/LinkageAnalysis :cg/Other :cg/PCR :cg/WholeGenomeSequencing :cg/DenaturingGradientGel :cg/HomozygosityMapping :cg/HighResolutionMelting]}]

  [{:id :cg/ProvenInTrans, :type :skos/Concept} {:id :cg/SuspectedInTrans, :type :skos/Concept} {:id :cg/PhaseValueSet, :type :skos/Collection, :skos/member [:cg/ProvenInTrans :cg/SuspectedInTrans]}]

  [{:id :cg/ProvenInTrans, :type :skos/Concept}
   {:id :cg/SuspectedInTrans, :type :skos/Concept}
   {:id :cg/PhaseValueSet, :type :skos/Collection, :skos/member [:cg/ProvenInTrans :cg/SuspectedInTrans]}
   ]

  ([:cg/Approved 90] [:cg/Evaluated 85] [:cg/Submitted 85])

  (with-open [r (PushbackReader. (io/reader "/Users/tristan/code/genegraph-api/schema/schema.edn"))]
    (->> (edn/read r)
         count))

  (with-open [r (PushbackReader. (io/reader "/Users/tristan/code/genegraph-api/schema/schema.edn"))]
    (->> (edn/read r)
         (filterv :value-set)
         (mapv :id)
         #_(take 1)
         (mapcat value-set-for-property)
         ))
  (def value-sets
    '({:id :cg/Evaluated, :type :skos/Concept} {:id :cg/Approved, :type :skos/Concept} {:id :cg/Submitted, :type :skos/Concept} {:id :cg/ActivitytypeValueSet, :skos/member [:cg/Evaluated :cg/Approved :cg/Submitted]} {:id :cg/AgeAtReport, :type :skos/Concept} {:id :cg/AgeAtOnset, :type :skos/Concept} {:id :cg/AgeAtDeath, :type :skos/Concept} {:id :cg/AgeAtDiagnosis, :type :skos/Concept} {:id :cg/AgetypeValueSet, :skos/member [:cg/AgeAtReport :cg/AgeAtOnset :cg/AgeAtDeath :cg/AgeAtDiagnosis]} {:id :cg/Months, :type :skos/Concept} {:id :cg/WeeksGestation, :type :skos/Concept} {:id :cg/Days, :type :skos/Concept} {:id :cg/Hours, :type :skos/Concept} {:id :cg/Weeks, :type :skos/Concept} {:id :cg/Years, :type :skos/Concept} {:id :cg/AgeunitValueSet, :skos/member [:cg/Months :cg/WeeksGestation :cg/Days :cg/Hours :cg/Weeks :cg/Years]} {:id :cg/DeNovoAlleleOrigin, :type :skos/Concept} {:id :cg/AlleleOrigin, :type :skos/Concept} {:id :cg/GermlineAlleleOrigin, :type :skos/Concept} {:id :cg/VariantoriginValueSet, :skos/member [:cg/DeNovoAlleleOrigin :cg/AlleleOrigin :cg/GermlineAlleleOrigin]} {:id :cg/Limited, :type :skos/Concept} {:id :cg/Strong, :type :skos/Concept} {:id :cg/Definitive, :type :skos/Concept} {:id :cg/NoKnownDiseaseRelationship, :type :skos/Concept} {:id :cg/Moderate, :type :skos/Concept} {:id :cg/CalculatedclassificationValueSet, :skos/member [:cg/Limited :cg/Strong :cg/Definitive :cg/NoKnownDiseaseRelationship :cg/Moderate]} {:id :cg/Limited, :type :skos/Concept} {:id :cg/Strong, :type :skos/Concept} {:id :cg/Refuted, :type :skos/Concept} {:id :cg/Definitive, :type :skos/Concept} {:id :cg/NoKnownDiseaseRelationship, :type :skos/Concept} {:id :cg/Disputed, :type :skos/Concept} {:id :cg/Moderate, :type :skos/Concept} {:id :cg/ClassificationValueSet, :skos/member [:cg/Limited :cg/Strong :cg/Refuted :cg/Definitive :cg/NoKnownDiseaseRelationship :cg/Disputed :cg/Moderate]} {:id :cg/RecurationFrameworkChange, :type :skos/Concept} {:id :cg/RecurationCommunityRequest, :type :skos/Concept} {:id :cg/DiseaseNameUpdate, :type :skos/Concept} {:id :cg/RecurationDiscrepancyResolution, :type :skos/Concept} {:id :cg/RecurationErrorAffectingScoreorClassification, :type :skos/Concept} {:id :cg/RecurationTiming, :type :skos/Concept} {:id :cg/RecurationNewEvidence, :type :skos/Concept} {:id :cg/ErrorClarification, :type :skos/Concept} {:id :cg/NewCuration, :type :skos/Concept} {:id :cg/CurationreasonsValueSet, :skos/member [:cg/RecurationFrameworkChange :cg/RecurationCommunityRequest :cg/DiseaseNameUpdate :cg/RecurationDiscrepancyResolution :cg/RecurationErrorAffectingScoreorClassification :cg/RecurationTiming :cg/RecurationNewEvidence :cg/ErrorClarification :cg/NewCuration]} {:id :cg/Neutral, :type :skos/Concept} {:id :cg/Supports, :type :skos/Concept} {:id :cg/DirectionValueSet, :skos/member [:cg/Neutral :cg/Supports]} {:id :cg/Disputes, :type :skos/Concept} {:id :cg/Neutral, :type :skos/Concept} {:id :cg/Supports, :type :skos/Concept} {:id :cg/DirectionofevidenceprovidedValueSet, :skos/member [:cg/Disputes :cg/Neutral :cg/Supports]} {:id :cg/NotHispanicOrLatino, :type :skos/Concept} {:id :cg/Unknown, :type :skos/Concept} {:id :cg/HispanicOrLatino, :type :skos/Concept} {:id :cg/EthnicityValueSet, :skos/member [:cg/NotHispanicOrLatino :cg/Unknown :cg/HispanicOrLatino]} {:id :cg/AmbiguousSex, :type :skos/Concept} {:id :cg/Unknown, :type :skos/Concept} {:id :cg/Other, :type :skos/Concept} {:id :cg/Male, :type :skos/Concept} {:id :cg/Female, :type :skos/Concept} {:id :cg/TransMale, :type :skos/Concept} {:id :cg/Intersex, :type :skos/Concept} {:id :cg/SexValueSet, :skos/member [:cg/AmbiguousSex :cg/Unknown :cg/Other :cg/Male :cg/Female :cg/TransMale :cg/Intersex]} {:id :cg/GeneValidityProbandADDeNovoCriteria, :type :skos/Concept} {:id :cg/GeneValidityCriteria11, :type :skos/Concept} {:id :cg/GeneValidityOverallExperimentalEvidenceCriteria, :type :skos/Concept} {:id :cg/GeneValidityNonNullVariantCriteria, :type :skos/Concept} {:id :cg/GeneValidityPatientCellRescueCriteria, :type :skos/Concept} {:id :cg/GeneValidityOverallFunctionalEvidenceCriteria, :type :skos/Concept} {:id :cg/GeneValidityBiochemicalFunctionCriteria, :type :skos/Concept} {:id :cg/GeneValidityUncategorizedProbandCriteria, :type :skos/Concept} {:id :cg/GeneValidityCriteria8, :type :skos/Concept} {:id :cg/GeneValidityProteinInteractionCriteria, :type :skos/Concept} {:id :cg/GeneValidityCriteria6, :type :skos/Concept} {:id :cg/GeneValidityNonHumanModelOrganismCriteria, :type :skos/Concept} {:id :cg/GeneValidityOverallAutosomalDominantDeNovoVariantEvidenceCriteria, :type :skos/Concept} {:id :cg/GeneValidityOverallGeneticEvidenceCriteria, :type :skos/Concept} {:id :cg/GeneValidityMaximumProbandScoreCriteria, :type :skos/Concept} {:id :cg/GeneValiditySegregationEvidenceCriteria, :type :skos/Concept} {:id :cg/GeneValidityCriteria10, :type :skos/Concept} {:id :cg/GeneValidityCellCultureRescueCriteria, :type :skos/Concept} {:id :cg/GeneValidityOverallFunctionalAlterationEvidenceCriteria, :type :skos/Concept} {:id :cg/GeneValidityNonHumanRescueCriteria, :type :skos/Concept} {:id :cg/GeneValidityCriteria5, :type :skos/Concept} {:id :cg/GeneValidityCriteria9, :type :skos/Concept} {:id :cg/GeneValidityNullVariantCriteria, :type :skos/Concept} {:id :cg/GeneValidityHumanRescueCriteria, :type :skos/Concept} {:id :cg/GeneValidityOverallModelAndRescueEvidenceCriteria, :type :skos/Concept} {:id :cg/GeneValidityOverallAutosomalDominantOtherVariantEvidenceCriteria, :type :skos/Concept} {:id :cg/GeneValidityProbandARDeNovoNullVariantCriteria, :type :skos/Concept} {:id :cg/GeneValidityCriteria12, :type :skos/Concept} {:id :cg/GeneValidityPatientCellFunctionalAlterationCriteria, :type :skos/Concept} {:id :cg/GeneValidityGeneExpressionCriteria, :type :skos/Concept} {:id :cg/GeneValidityOverallCaseControlEvidenceCriteria, :type :skos/Concept} {:id :cg/GeneValidityCellCultureModelOrganismCriteria, :type :skos/Concept} {:id :cg/GeneValidityCriteria4, :type :skos/Concept} {:id :cg/GeneValidityNonPatientCellFunctionalAlterationCriteria, :type :skos/Concept} {:id :cg/GeneValidityProbandADNonNullCriteria, :type :skos/Concept} {:id :cg/GeneValidityProbandADNullCriteria, :type :skos/Concept} {:id :cg/GeneValidityCriteria7, :type :skos/Concept} {:id :cg/GeneValidityOverallAutosomalDominantNullVariantEvidenceCriteria, :type :skos/Concept} {:id :cg/GeneValidityCaseControlAggregateVariantAnalysisCriteria, :type :skos/Concept} {:id :cg/GeneValidityProbandARNonNullCriteria, :type :skos/Concept} {:id :cg/GeneValidityCaseControlSingleVariantAnalysisCriteria, :type :skos/Concept} {:id :cg/SpecifiedbyValueSet, :skos/member [:cg/GeneValidityProbandADDeNovoCriteria :cg/GeneValidityCriteria11 :cg/GeneValidityOverallExperimentalEvidenceCriteria :cg/GeneValidityNonNullVariantCriteria :cg/GeneValidityPatientCellRescueCriteria :cg/GeneValidityOverallFunctionalEvidenceCriteria :cg/GeneValidityBiochemicalFunctionCriteria :cg/GeneValidityUncategorizedProbandCriteria :cg/GeneValidityCriteria8 :cg/GeneValidityProteinInteractionCriteria :cg/GeneValidityCriteria6 :cg/GeneValidityNonHumanModelOrganismCriteria :cg/GeneValidityOverallAutosomalDominantDeNovoVariantEvidenceCriteria :cg/GeneValidityOverallGeneticEvidenceCriteria :cg/GeneValidityMaximumProbandScoreCriteria :cg/GeneValiditySegregationEvidenceCriteria :cg/GeneValidityCriteria10 :cg/GeneValidityCellCultureRescueCriteria :cg/GeneValidityOverallFunctionalAlterationEvidenceCriteria :cg/GeneValidityNonHumanRescueCriteria :cg/GeneValidityCriteria5 :cg/GeneValidityCriteria9 :cg/GeneValidityNullVariantCriteria :cg/GeneValidityHumanRescueCriteria :cg/GeneValidityOverallModelAndRescueEvidenceCriteria :cg/GeneValidityOverallAutosomalDominantOtherVariantEvidenceCriteria :cg/GeneValidityProbandARDeNovoNullVariantCriteria :cg/GeneValidityCriteria12 :cg/GeneValidityPatientCellFunctionalAlterationCriteria :cg/GeneValidityGeneExpressionCriteria :cg/GeneValidityOverallCaseControlEvidenceCriteria :cg/GeneValidityCellCultureModelOrganismCriteria :cg/GeneValidityCriteria4 :cg/GeneValidityNonPatientCellFunctionalAlterationCriteria :cg/GeneValidityProbandADNonNullCriteria :cg/GeneValidityProbandADNullCriteria :cg/GeneValidityCriteria7 :cg/GeneValidityOverallAutosomalDominantNullVariantEvidenceCriteria :cg/GeneValidityCaseControlAggregateVariantAnalysisCriteria :cg/GeneValidityProbandARNonNullCriteria :cg/GeneValidityCaseControlSingleVariantAnalysisCriteria]} {:id :cg/Homozygous, :type :skos/Concept} {:id :cg/BiallelicCompoundHeterozygous, :type :skos/Concept} {:id :cg/Heterozygous, :type :skos/Concept} {:id :cg/Hemizygous, :type :skos/Concept} {:id :cg/MonoallelicHeterozygous, :type :skos/Concept} {:id :cg/TwoVariantsInTrans, :type :skos/Concept} {:id :cg/BiallelicHomozygous, :type :skos/Concept} {:id :cg/ZygosityValueSet, :skos/member [:cg/Homozygous :cg/BiallelicCompoundHeterozygous :cg/Heterozygous :cg/Hemizygous :cg/MonoallelicHeterozygous :cg/TwoVariantsInTrans :cg/BiallelicHomozygous]}))

  ;; Ethnicity has a :gg/ value--may need to investigate Otherwise everything works pretty well.
  (clojure.pprint/pprint value-sets)
  (->>  (concat '(["Exome sequencing" 17369] ["PCR" 9519] ["Sanger sequencing" 8263] ["Next generation sequencing panels" 5284] ["Other" 1746] ["Linkage analysis" 1286] ["Genotyping" 1157] ["SSCP" 1050] ["Whole genome shotgun sequencing" 839] ["Homozygosity mapping" 731] ["Chromosomal microarray" 507] ["Denaturing gradient gel" 206] ["Restriction digest" 200] ["High resolution melting" 130])
                '(["Sanger sequencing" 14820] ["PCR" 1123] ["Exome sequencing" 713] ["Other" 701] ["Restriction digest" 634] ["Next generation sequencing panels" 382] ["SSCP" 328] ["Genotyping" 316] ["Chromosomal microarray" 180] ["Linkage analysis" 175] ["Whole genome shotgun sequencing" 144] ["Homozygosity mapping" 124] ["High resolution melting" 58] ["Denaturing gradient gel" 40]))
        (mapv first)
        set)

  (->> #{"Linkage analysis" "Restriction digest" "Denaturing gradient gel" "Next generation sequencing panels" "Homozygosity mapping" "Exome sequencing" "High resolution melting" "Sanger sequencing" "PCR" "Whole genome shotgun sequencing" "SSCP" "Genotyping" "Other" "Chromosomal microarray"}
       (mapv #(->> (str/split % #" ") (map str/capitalize) str/join (str "cg:"))))
  
  :cg/secondTestingMethod
  (["Sanger sequencing" 14820] ["PCR" 1123] ["Exome sequencing" 713] ["Other" 701] ["Restriction digest" 634] ["Next generation sequencing panels" 382] ["SSCP" 328] ["Genotyping" 316] ["Chromosomal microarray" 180] ["Linkage analysis" 175] ["Whole genome shotgun sequencing" 144] ["Homozygosity mapping" 124] ["High resolution melting" 58] ["Denaturing gradient gel" 40])


  
  :cg/Statement
  #{:cg/calculatedClassification :rdf/type :cg/contributions :cg/classification :dc/isVersionOf :cg/GCISnapshot :cg/proposition :cg/curationReasons :cg/sequence :cg/curationReasonDescription :cg/specifiedBy :cg/score :cg/direction :cg/hasEvidenceLines :cg/version :dc/description}
  :cg/GeneFunctionStudyResult
  #{:rdf/type :cg/interpretation :rdfs/label :cg/modelSystem :dc/source :cg/demonstrates :dc/description}
  :cg/GeneDiseaseValidityProposition
  #{:rdf/type :cg/predicate :cg/modeOfInheritanceQualifier :cg/objectCondition :cg/subjectGene}
  :cg/CaseControlStudyResult
  #{:cg/pValue :cg/method :cg/caseCohort :cg/lowerConfidenceLimit :cg/upperConfidenceLimit :cg/statisticalSignificanceValueType :rdfs/label :cg/statisticalSignificanceType :dc/source :cg/controlCohort :cg/statisticalSignificanceValue :dc/description}
  :cg/VariantObservationStudyResult
  #{:rdf/type :cg/allele :cg/observedIn :cg/alleleOrigin :dc/source :cg/zygosity :cg/paternityMaternityConfirmed}
  :cg/Family
  #{:rdf/type :cg/member :cg/ethnicity :rdfs/label :cg/modeOfInheritance}
  :cg/Cohort
  #{:rdf/type :cg/allGenotypedSequenced :cg/numWithVariant :cg/alleleFrequency :cg/relatedCondition :cg/hasEvidenceItems :cg/detectionMethod}
  :cg/UnscoreableEvidence
  #{:rdf/type :dc/source :dc/description}
  :cg/EvidenceLine
  #{:rdf/type :cg/scoreOfEvidenceProvided :cg/directionOfEvidenceProvided :cg/hasEvidenceItems :cg/calculatedScore :cg/specifiedBy :dc/description}
  :cg/SegregationStudyResult
  #{:rdf/type :cg/publishedLodScore :cg/phenotypes :cg/family :cg/phenotype :cg/sequencingMethod :cg/meetsInclusionCriteria :rdfs/label :cg/phenotypeNegativeAlleleNegative :cg/phenotypePositiveAllelePositive :dc/source :cg/proband :cg/phenotypeFreeText :cg/estimatedLodScore :dc/description}
  :cg/VariationDescriptor
  #{:rdf/type :skos/prefLabel :cg/canonicalReference}
  :cg/ProbandStudyResult
  #{:rdf/type :cg/ageType :cg/variant :cg/hasVariant :cg/phenotypes :cg/allele :cg/secondTestingMethod :cg/ethnicity :cg/phase :rdfs/label :cg/firstTestingMethod :cg/previousTesting :dc/source :cg/phenotypeFreeText :cg/sex :cg/detectionMethod :cg/ageUnit :cg/zygosity :cg/ageValue :cg/previousTestingDescription}
  :cg/Contribution
  #{:rdf/type :cg/date :cg/activityType :cg/contributor}
  :cg/VariantFunctionalImpactEvidence
  #{:rdf/type :cg/functionalDataSupport :dc/description}

  (->> #{:cg/calculatedClassification :rdf/type :cg/contributions :cg/predicate :cg/ageType :sks/prefLabel :cg/variant :cg/modeOfInheritanceQualifier :cg/pValue :cg/classification :cg/interpretation :dc/isVersionOf :cg/functionalDataSupport :cg/scoreOfEvidenceProvided :cg/hasVariant :cg/method :cg/caseCohort :cg/publishedLodScore :cg/allGenotypedSequenced :cg/phenotypes :cg/GCISnapshot :cg/date :cg/allele :cg/proposition :cg/numWithVariant :cg/lowerConfidenceLimit :cg/curationReasons :cg/family :cg/alleleFrequency :cg/sequence :cg/canonicalReference :cg/phenotype :cg/upperConfidenceLimit :cg/observedIn :cg/member :cg/secondTestingMethod :cg/statisticalSignificanceValueType :cg/alleleOrigin :cg/objectCondition :cg/curationReasonDescription :cg/ethnicity :cg/phase :cg/sequencingMethod :cg/meetsInclusionCriteria :cg/subjectGene :cg/directionOfEvidenceProvided :cg/relatedCondition :cg/hasEvidenceItems :rdfs/label :cg/phenotypeNegativeAlleleNegative :cg/statisticalSignificanceType :cg/calculatedScore :cg/phenotypePositiveAllelePositive :cg/modelSystem :cg/firstTestingMethod :cg/previousTesting :dc/source :cg/activityType :cg/modeOfInheritance :cg/demonstrates :cg/specifiedBy :cg/contributor :cg/proband :cg/score :cg/phenotypeFreeText :cg/controlCohort :cg/sex :cg/direction :cg/detectionMethod :cg/estimatedLodScore :cg/ageUnit :cg/zygosity :cg/statisticalSignificanceValue :cg/paternityMaternityConfirmed :cg/ageValue :cg/hasEvidenceLines :cg/previousTestingDescription :cg/version :dc/description}
       sort
       (mapv (fn [p] {:id p :type :rdf/Property}))
       clojure.pprint/pprint)
  
  (->> 
   #:cg{:Statement 7374,
        :GeneFunctionStudyResult 24247,
        :GeneDiseaseValidityProposition 6204,
        :CaseControlStudyResult 699,
        :VariantObservationStudyResult 63213,
        :Family 13494,
        :Cohort 1398,
        :UnscoreableEvidence 3679,
        :EvidenceLine 134614,
        :SegregationStudyResult 13493,
        :VariationDescriptor 55030,
        :ProbandStudyResult 57935,
        :Contribution 19151,
        :VariantFunctionalImpactEvidence 16388}
   (mapv (fn [[k v]]
           {:id k
            :type :rdfs/Class}))
   clojure.pprint/pprint)
  
  {:cg/Statement 7200,
   :cg/GeneFunctionStudyResult 23712,
   :ga4gh/VariationDescriptor 53441,
   :cg/GeneDiseaseValidityProposition 6051,
   :cg/CaseControlStudyResult 695,
   :cg/VariantObservationStudyResult 61522,
   :cg/Family 13267,
   :cg/Cohort 1390,
   :cg/UnscoreableEvidence 3612,
   :cg/EvidenceLine 131315,
   :cg/SegregationStudyResult 13266,
   :cg/ProbandStudyResult 56370,
   :cg/Contribution 18689,
   :cg/VariantFunctionalImpactEvidence 15855}

  )

(comment
  ;; investigating versioning

  (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])
        q (rdf/create-query "select ?x where { ?x a :cg/Statement }")]
    (->> (storage/scan db [:outcomes])
         (filter #(and (= 1 (:transform-version %))
                       (= "1.0.0" (:gene-validity/version-str %))
                       (not= #{:cg/NewCuration} (:gene-validity/curation-reasons %))
                       (not= #{} (:gene-validity/curation-reasons %))
                       #_(get-in % [:gene-validity/classes
                                    :cg/GeneFunctionStudyResult])))
         (take 1)
         #_(mapv #(outcome->event % db))
         (mapv #(outcome->website-event % db))
         #_(mapv #(outcome->model % db))
         #_(run! #(rdf/pp-model (:gene-validity/model %)))
         tap>

         #_(mapcat (fn [e] (mapcat #(map rdf/->kw 
                                       (rdf/ld-> % [:cg/version]))
                                 (q (:gene-validity/model e)))))
         
         #_(mapcat (fn [e] (map #(rdf/ld1-> % [:cg/version])
                              (q (:gene-validity/model e)))))
         #_count
         #_(mapcat #(map rdf/->kw (q (:gene-validity/model %))))
         #_(into [])
         #_(mapv :gene-validity/curation-reasons)
         #_frequencies
         #_(sort-by val)
         #_reverse
         ))
  
  ;; write all-curation-events
  (with-open [w (io/writer "/Users/tristan/Desktop/all-curation-events-sample.ndjson")]
    (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])]
      (->> (storage/scan db [:outcomes])
           #_(take 1)
           (map #(-> (outcome->website-event % db)
                     :gene-validity/website-event
                     charred/write-json-str))
           (run! (fn [r] (.write w r) (.write w "\n")))
           #_tap>
           ))

    )

  (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])]
    (->> (storage/scan db [:outcomes])
         #_(take 10)
         (map #(-> (outcome->website-event % db)))
         (remove :gene-validity/website-event)
         count))

  (let [db @(get-in test-app [:storage :gene-validity-version-store :instance])]
    (->> (storage/scan db [:outcomes])
         (filterv #(= "https://identifiers.org/hgnc:10294"
                      (:gene-validity/gene %)))
         (mapv #(-> (outcome->model % db)
                    :gene-validity/model))
         #_(map #(-> (outcome->website-event % db)))
         #_(filter :payload)
         (take-last 1)
         (run! #(rdf/pp-model %))
         ))

  (with-open [r (io/reader "/Users/tristan/Downloads/all-curation-events-sample.ndjson")]
    (->> (line-seq r)
         #_(take 10)
         (map #(->> (charred/read-json % :key-fn keyword)
                    keys
                    count))
         
         frequencies))
  (contains? {:payload nil} :payload)
  (with-open [r (io/reader "/Users/tristan/Downloads/ClinGen-Gene-Expess-Data-03272019.json")]
    (->> (charred/read-json r :key-fn keyword)
         (filter #(re-find #"RAD51C" (-> % val :title)))
         tap>))
  )

;; GDV quarterly productivity
(comment
  (->> (storage/scan db [:outcomes])
       
         )  
  )



;; TODO Finish evaluating schema. Fix outstanding flags. Add definition for items.
;; Class/subclass relationships, needed for certain types of inheritance based restrictions.

;; Definitions, class/subclass relationships,
;; documentation generation
;; SHACL generation
;; 
(comment
  "Evaluating schema for completeness"
  
  (def schema-path "/Users/tristan/code/genegraph-schema/resources/schema.edn")
  (with-open [r (-> schema-path io/reader PushbackReader.)]
    (->> (edn/read r)
         (filterv #(and (= :rdf/Property (:type %))
                        (not (:range %))))
         tap>))
  )

;; quick GC Express exploration
(comment
  (let [gcex "/Users/tristan/Downloads/ClinGen-Gene-Expess-Data-03272019.json"]
    (with-open [r (io/reader gcex)]
      (->> (charred/read-json r)
           (filterv #(some-> % val (get-in ["genes" "HGNC:338"])))
           tap>))))


;; One-off query for Deb Ritter:

;; I am looking to get some data on gene curation from either the website or the ClinGen data exchange. I would like to get gene curation summary text, PMIDs used for the curation, the GCEP,  gene name and disease (diseases, maybe more than one?)

;; Awesome — a one off would be great. It's to do some basic query of gene curations about the length of summaries vs the classification etc. I was curious too about  the number of PMIDs i.e. does it take more or less PMIDs for definitive vs refuted and things like PMID reuse, are groups using the same PMID over and over or different ones?  I have some of this data for VCEP variant classification but not for GCEP, and will present on one of the AI Working group calls. Just wanted to have something for GCEPs too. 

;; Having looked through I would need:  

;; GCEP name, gene name, disease curated, classification (i.e. definitive, limited etc. I forgot this rather important item in the last email..), summary text and pmids. 

;; Add length of summary text

(comment
  (def mondo (rdf/read-rdf "file:///Users/tristan/data/genegraph-base/mondo.owl" ::rdf/rdf-xml))
  (def hgnc
    (with-open [r (io/reader "/Users/tristan/data/genegraph-base/hgnc.json")]
      (->> (get-in (charred/read-json r :key-fn keyword) [:response :docs])
           #_(take 10)
           (mapv (fn [{:keys [hgnc_id symbol]}]
                   [(str "https://identifiers.org/"
                         (str/lower-case hgnc_id))
                    symbol]))
           (into {}))))
  (tap> hgnc)

  (with-open [w (io/writer "/users/tristan/Desktop/gcep-summaries.csv")]
    (let [store @(get-in test-app [:storage :gene-validity-version-store :instance])
          type-query (rdf/create-query "select ?x where { ?x a ?t } ")
          source-query (rdf/create-query "select ?x where { ?s :dc/source ?x }")]
      (->> (snapshot/latest-records store)
           #_(map #(outcome->model % store))
           (filter #(contains? (:gene-validity/activity %) :cg/Approved))
           #_(take 10)
           (map #(outcome->model % store))
           (mapv (fn [{:gene-validity/keys [model gene disease gcep]}]
                   (let [statement (first (type-query model {:t :cg/Statement}))
                         summary (rdf/ld1-> statement [:dc/description])
                         sources (source-query model)]
                     [(get hgnc gene)
                      (rdf/ld1-> (rdf/resource disease mondo) [:rdfs/label])
                      (get gcep-report/gcep-labels gcep)
                      (name (rdf/->kw (rdf/ld1-> statement [:cg/classification])))
                      (count sources)
                      (mapv str sources)
                      (count summary)
                      summary])))
           (cons ["gene" "disease" "gcep" "classification" "reference count" "references" "summary length (characters)" "summary"])
           (into [])
           (charred/write-csv w))))



  


  )


(comment
  ;; object value frequencies
  #:cg{:Statement {:cg/calculatedClassification {1 6204, 0 1170}, :cg/contributions {3 5457, 1 1170, 4 655, 6 8, 5 84}, :cg/classification {1 6204, 0 1170}, :dc/isVersionOf {1 7374}, :cg/GCISnapshot {1 6204, 0 1170}, :cg/proposition {1 6204, 0 1170}, :cg/curationReasons {1 2923, 2 134, 0 4294, 3 23}, :cg/sequence {1 6204, 0 1170}, :cg/curationReasonDescription {0 5485, 1 1889}, :cg/specifiedBy {1 6202, 0 1170, 2 2}, :cg/score {1 6204, 0 1170}, :cg/direction {1 6204, 0 1170}, :cg/hasEvidenceLines {2 6202, 0 1170, 4 2}, :cg/version {1 6204, 0 1170}, :dc/description {1 6199, 0 1173, 2 2}}, :GeneFunctionStudyResult {:cg/modelSystem {1 4711, 0 19536}, :cg/interpretation {1 24247}, :rdfs/label {1 24247}, :dc/source {1 24247}, :dc/description {1 24123, 0 124}}, :GeneDiseaseValidityProposition #:cg{:predicate {1 6204}, :modeOfInheritanceQualifier {1 6204}, :objectCondition {1 6202, 2 2}, :subjectGene {1 6204}}, :CaseControlStudyResult {:cg/pValue {1 525, 0 174}, :cg/caseCohort {1 699}, :cg/lowerConfidenceLimit {1 404, 0 295}, :cg/upperConfidenceLimit {1 404, 0 295}, :cg/statisticalSignificanceValueType {1 634, 0 65}, :rdfs/label {1 699}, :cg/statisticalSignificanceType {1 699}, :dc/source {1 699}, :cg/supportingMethodTypes {1 699}, :cg/controlCohort {1 699}, :cg/statisticalSignificanceValue {1 486, 0 213}, :dc/description {0 671, 1 28}}, :VariantObservationStudyResult {:rdf/type {1 63213}, :cg/allele {0 63213}, :cg/variantObservedIn {1 46018, 0 17195}, :cg/alleleOrigin {0 63213}, :dc/source {1 63213}, :cg/zygosity {1 46489, 0 16724}, :cg/paternityMaternityConfirmed {1 21842, 0 41371}}, :Family {:rdf/type {1 13494}, :cg/member {1 12224, 0 1270}, :cg/ethnicity {1 5611, 0 7883}, :rdfs/label {1 13494}, :cg/modeOfInheritance {0 13060, 1 434}}, :Cohort {:rdf/type {1 1398}, :cg/numberGenotyped {1 1398}, :cg/numWithVariant {0 1398}, :cg/alleleFrequency {1 1355, 0 43}, :cg/relatedCondition {0 712, 1 686}, :cg/hasEvidenceItems {1 1398}, :cg/detectionMethodText {1 1028, 0 370}}, :UnscoreableEvidence {:rdf/type {1 3679}, :dc/source {1 3679}, :dc/description {1 3538, 0 141}}, :EvidenceLine {:rdf/type {1 134614}, :cg/scoreOfEvidenceProvided {1 134303, 0 311}, :cg/directionOfEvidenceProvided {0 30846, 1 103750, 2 18}, :cg/hasEvidenceItems {0 1, 65 1, 70 1, 7 688, 20 111, 72 2, 27 21, 1 89772, 24 44, 102 2, 39 4, 4 1486, 77 1, 54 1, 15 267, 48 2, 50 2, 21 55, 31 11, 32 6, 40 2, 33 21, 13 276, 22 76, 36 9, 41 2, 43 2, 29 20, 44 6, 6 696, 28 31, 25 45, 34 21, 17 177, 3 8901, 12 401, 2 26444, 23 43, 47 1, 35 8, 19 107, 68 1, 11 434, 9 595, 5 2042, 14 299, 45 6, 26 34, 16 197, 81 1, 38 10, 30 36, 10 483, 18 149, 42 3, 80 2, 37 4, 8 550, 49 1}, :cg/calculatedScore {0 47344, 1 87270}, :cg/specifiedBy {1 134614}, :dc/description {0 85649, 1 48965}}, :SegregationStudyResult {:rdf/type {1 13493}, :cg/phenotypes {0 4850, 7 367, 20 11, 27 7, 1 2313, 24 2, 4 787, 15 63, 21 12, 31 1, 13 93, 22 7, 36 1, 6 497, 28 1, 25 1, 17 29, 3 1036, 12 110, 2 1548, 23 4, 19 13, 11 157, 9 194, 5 818, 14 61, 26 2, 16 21, 30 1, 10 181, 18 9, 8 296}, :cg/estimatedLODScore {1 8750, 0 4743}, :cg/family {1 13493}, :cg/LODScore {1 9128, 0 4365}, :cg/publishedLODScore {0 11756, 1 1737}, :cg/meetsInclusionCriteria {1 9011, 0 4482}, :rdfs/label {1 13493}, :cg/phenotypeNegativeAlleleNegative {0 8949, 1 4544}, :cg/phenotypePositiveAllelePositive {1 13413, 0 80}, :dc/source {1 13493}, :cg/supportingMethodTypes {0 13493}, :cg/proband {1 12339, 0 1154}, :cg/phenotypeFreeText {1 6466, 0 7027}, :dc/description {1 3936, 0 9557}}, :VariationDescriptor {:rdf/type {1 55030}, :skos/prefLabel {1 55024, 0 6}, :cg/canonicalReference {1 54833, 0 197}}, :ProbandStudyResult {:rdf/type {1 57935}, :cg/ageType {1 35949, 0 21986}, :cg/hasVariant {1 52657, 2 5278}, :cg/phenotypes {0 18419, 7 2477, 20 214, 27 92, 1 6966, 24 98, 39 1, 4 3852, 54 2, 15 530, 50 6, 21 225, 31 43, 32 36, 40 14, 33 6, 13 735, 22 110, 36 7, 41 2, 43 4, 29 35, 44 2, 6 2953, 28 34, 25 118, 34 18, 17 388, 3 4064, 12 820, 2 4612, 23 94, 47 4, 35 20, 19 228, 11 1054, 9 1414, 5 3543, 14 581, 26 56, 16 442, 38 21, 30 38, 10 1194, 18 367, 52 1, 42 1, 37 15, 63 1, 8 1977, 49 1}, :cg/ethnicity {0 50716, 1 7219}, :cg/genotypeConfirmationMethod {1 19738, 0 38197}, :cg/geneticTestingMethod {1 48287, 0 9648}, :rdfs/label {1 57935}, :cg/previousTesting {0 29155, 1 28780}, :cg/phaseStatusConfidence {0 56321, 1 1614}, :dc/source {1 57935}, :cg/phenotypeFreeText {0 28564, 1 29371}, :cg/sex {1 57890, 0 45}, :cg/detectionMethodText {1 35582, 0 22353}, :cg/ageUnit {1 35929, 0 22006}, :cg/zygosity {1 41231, 0 16704}, :cg/ageValue {1 35615, 0 22320}, :cg/previousTestingDescription {0 35439, 1 22496}}, :Contribution {:rdf/type {1 19151}, :cg/date {1 19149, 2 2}, :cg/activityType {1 19151}, :cg/contributor {1 19151}}, :VariantFunctionalImpactEvidence {:rdf/type {1 16388}, :cg/functionalDataSupport {1 16388}, :dc/description {1 16388}}}
  
  )


(comment
  (let [store @(get-in test-app [:storage :gene-validity-version-store :instance])
        q (rdf/create-query "
select ?proband ?source where {
?proband a :cg/ProbandStudyResult ;
   :dc/source ?source .
}")]
    (->> (snapshot/latest-records store)
         (take 5)
         #_(remove :gene-validity/valid)
         #_(map #(outcome->json % store))
         #_(map #(outcome->model % store))
         #_(mapv #(q (:gene-validity/model %) {::rdf/params {:type :table}}))
         tap>
         ))

  ;; Adapting to this request:

  ;; We're really just looking for a way to quickly identify a set of ~20 papers with increasing numbers of probands, so like 2 papers with a single proband, 2 papers with 2 probands, etc., up to like 5 probands max.  They also don't need to necessarily be from the same curation. We don't want to create any more work, certainly!  We were hypothesizing that those curations with older dates of first report might be more likely to have single proband case reports, or maybe even that Limited curations with few probands might be a good source of papers with small numbers...

  (with-open [w (io/writer "/users/tristan/Desktop/papers-with-proband-count.csv")]
    (let [store @(get-in test-app [:storage :gene-validity-version-store :instance])
          type-query (rdf/create-query "select ?x where { ?x a ?t } ")
          source-query (rdf/create-query "select ?x where { ?s :dc/source ?x }")
          proband-source-query (rdf/create-query "
select ?proband ?source where {
?proband a :cg/ProbandStudyResult ;
   :dc/source ?source .
}")]
      (->> (snapshot/latest-records store)
           #_(map #(outcome->model % store))
           (filter #(contains? (:gene-validity/activity %) :cg/Approved))
           #_(take 5)
           (map #(outcome->model % store))
           (mapcat (fn [{:gene-validity/keys [model gene disease gcep]}]
                     (let [statement (first (type-query model {:t :cg/Statement}))
                           probands (proband-source-query model {::rdf/params {:type :table}})
                           proband-sources (group-by :source probands)
                           gene-symbol (get hgnc gene)
                           disease-name (rdf/ld1-> (rdf/resource disease mondo) [:rdfs/label])
                           gcep-name (get gcep-report/gcep-labels gcep)
                           classification (name (rdf/->kw (rdf/ld1-> statement [:cg/classification])))]
                       (mapv (fn [[k v]]
                               [(str k)
                                (count v)
                                gene-symbol
                                disease-name
                                gcep-name
                                classification])
                             proband-sources)
                       #_[(get hgnc gene)
                          (rdf/ld1-> (rdf/resource disease mondo) [:rdfs/label])
                          (get gcep-report/gcep-labels gcep)
                          (name (rdf/->kw (rdf/ld1-> statement [:cg/classification])))])))
           (cons ["source" "num probands" "gene" "disease" "gcep" "classification"])
           (into [])
           (charred/write-csv w))))
  )
