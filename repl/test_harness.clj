(ns test-harness
  (:require [genegraph.transform.gene-validity :as gv]
            [genegraph.transform.gene-validity.sepio-model :as sepio-model]
            [genegraph.transform.gene-validity.gci-model :as gci-model]
            [genegraph.transform.gene-validity.versioning :as versioning]
            [genegraph.transform.gene-validity.website-events :as website-events]
            [genegraph.transform.gene-validity.event-recorder :as recorder]
            [genegraph.transform.gene-validity.abbreviate :as abbrev]
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
            [clojure.walk :as walk]
            [clojure.spec.alpha :as spec]))


(comment
  (do
    (def portal (portal/open))
    (add-tap #'portal/submit))
  (portal/close)
  (portal/clear)
  
  (do
    (def test-app (p/init test-app-def))
    (p/start test-app)
    (defn transform-curation [e]
      (p/process (get-in test-app [:processors :gene-validity-transform])
                 (assoc e
                        ::event/completion-promise (promise)
                        ::event/skip-local-effects true
                        ::event/skip-publish-effects true))))

  (p/stop test-app)

  (def source-file
    "/Users/tristan/data/genegraph-neo/gene_validity_all-2026-03-18.edn.gz")
  
  (tap> test-app))

;; processes to load event and validate the initial loading
(comment
    ;; load current gene validity events
  (time
   (event-store/with-event-reader [r source-file]
     (run! #(p/publish (get-in test-app [:topics :gene-validity-complete]) %)
           (event-store/event-seq r))))

  (time
   (->> (rocksdb/range-get @(get-in test-app [:storage :gene-validity-version-store :instance])
                           {:prefix [:events :gene-validity-complete]
                            :return :ref})
        (take 1)
        (map deref)
        tap>))
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
             :name :transform-topic}}
   :storage {:gene-validity-version-store
             (assoc gv/gene-validity-version-store :reset-opts {})
             :curation-output curation-output}
   :processors {:gene-validity-transform (assoc gv/transform-processor
                                                :type :parallel-processor)
                :gci-event-processor gv/gci-event-processor
                :gene-validity-sepio-output
                (create-record-output-processor :gene-validity-sepio)
                :gene-validity-jsonld-output
                (create-record-output-processor :gene-validity-sepio-jsonld)
                :all-curation-events-output
                (create-record-output-processor :all-curation-events)}})


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

(comment
  (->> (rocksdb/range-get @(get-in test-app [:storage :gene-validity-version-store :instance])
                          {:prefix [:events :gene-validity-complete]
                           :return :ref})
       (take 1)
       (map deref)
       (map #(assoc %
                    :tap-without-models true
                    #_#_:pp-model true))
       (run! #(p/publish (get-in test-app [:topics :transform-topic]) %)))
  
  (time (gv/reprocess-events test-app))

  (->> (storage/scan @(get-in test-app
                              [:storage :gene-validity-version-store :instance])
                     [:outcomes])
       count)

  ;; check for type of failed tests
  (->> (storage/scan @(get-in test-app
                              [:storage :gene-validity-version-store :instance])
                     [:outcomes])
       (remove :gene-validity/valid)
       (map :gene-validity/failed-tests)
       (reduce concat)
       set)

  )
