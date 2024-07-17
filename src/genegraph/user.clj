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
            [portal.api :as portal])
  (:import [ch.qos.logback.classic Logger Level]
           [org.slf4j LoggerFactory]
           [java.time Instant LocalDate]
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
             :name :gene-validity-sepio}}
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

  (time
   (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2024-07-16.edn.gz"]
     (->> (event-store/event-seq r)
          #_(take 5)
          (run! #(p/publish (get-in test-app [:topics :gene-validity-complete]) %)))))
  
  (defn transform-curation [e]
    (p/process (get-in test-app [:processors :gene-validity-transform])
               (assoc e
                      ::event/completion-promise (promise)
                      ::event/skip-local-effects true
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
           (map #(dissoc % :gene-validity/gci-model :gene-validity/model))
           (into [])))))
)


(comment
  (def portal (portal/open))
  (add-tap #'portal/submit)
  (portal/close)
  (portal/clear)
  )
