(ns genegraph.transform.gene-validity.gci-express
  (:require [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage :as storage]
            [genegraph.transform.gene-validity.types]
            [charred.api :as charred]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [genegraph.framework.id :as id])
  (:import [java.time Instant]))

(def gci-express-root "http://genegraph.clinicalgenome.org/r/GCIEX_")

(defn json-content [report]
  (if (< 0 (count (:scoreJsonSerialized report)))
    (:scoreJsonSerialized report)
    (:scoreJsonSerializedSop5 report)))

#_(defn json-content-node [report iri]
  [[iri :rdf/type :cnt/ContentAsText]
   [iri :cnt/chars (json-content report)]])

(defn prop [report]
  (let [gene (rdf/resource (str "https://www.ncbi.nlm.nih.gov/gene/"
                                (:entrez_id report)))
        parsed-json (charred/read-json (json-content report) :key-fn keyword)
        moi-string (or (-> parsed-json :data :ModeOfInheritance)
                       (-> parsed-json :scoreJson :ModeOfInheritance))
        moi (->> moi-string
                 (re-find #"\(HP:(\d+)\)")
                 second
                 (str "http://purl.obolibrary.org/obo/HP_")
                 rdf/resource)]
    {:type :cg/GeneDiseaseValidityProposition
     :cg/gene gene
     :cg/disease (rdf/resource (-> report :conditions :MONDO :iri))
     :cg/modeOfInheritance moi}))

(defn validity-proposition [report iri]
  (let [gene (rdf/resource (str "https://www.ncbi.nlm.nih.gov/gene/"
                                (:entrez_id report)))
        parsed-json (charred/read-json (json-content report) :key-fn keyword)
        moi-string (or (-> parsed-json :data :ModeOfInheritance)
                       (-> parsed-json :scoreJson :ModeOfInheritance))
        moi (->> moi-string
                 (re-find #"\(HP:(\d+)\)")
                 second
                 (str "http://purl.obolibrary.org/obo/HP_")
                 rdf/resource)]
    [[iri :rdf/type :cg/GeneDiseaseValidityProposition]
     [iri :cg/gene gene]
     [iri :cg/disease (rdf/resource (-> report :conditions :MONDO :iri))]
     [iri :cg/modeOfInheritance moi]]))

(defn contribution [report iri]
  [[iri :bfo/realizes :sepio/ApproverRole]
   [iri :sepio/has-agent (rdf/resource (str affiliation-root
                                        (-> report :affiliation :id)))]
   [iri :sepio/activity-date (:dateISO8601 report)]])



(defn sop-version-gci-e [report]
  (if (< 0 (count (:scoreJsonSerialized report)))
    :sepio/ClinGenGeneValidityEvaluationCriteriaSOP4
    :sepio/ClinGenGeneValidityEvaluationCriteriaSOP5))

(defn evidence-level-assertion [report iri id]
  (let [prop-iri (rdf/resource (str gci-express-root "proposition_" id))
        contribution-iri (rdf/blank-node)]
    (concat [[iri :rdf/type :sepio/GeneValidityEvidenceLevelAssertion]
             [iri :sepio/has-subject prop-iri]
             [iri :sepio/has-predicate :sepio/HasEvidenceLevel]
             [iri :sepio/has-object (evidence-level-label-to-concept
                                     (-> report :scores vals first :label))]
             [iri :sepio/qualified-contribution contribution-iri]
             [iri :sepio/is-specified-by (sop-version-gci-e report)]
             [iri :dc/has-format (sop-version-gci-e report)]]
            (validity-proposition report prop-iri)
            (contribution report contribution-iri))))

(defn gci-express-report-to-triples [report]
  (let [content (second report)
        id (-> report first name)
        iri (str gci-express-root "report_" id)
        content-id (rdf/blank-node)
        assertion-id (rdf/resource (str gci-express-root "assertion_" id))]
    (concat [[iri :rdf/type :sepio/GeneValidityReport] 
             [iri :rdfs/label (:title content)]
             [iri :bfo/has-part content-id]
             [iri :bfo/has-part assertion-id]
             [iri :dc/source :cg/GeneCurationExpress]]
            (evidence-level-assertion content assertion-id id)
            #_(json-content-node content content-id))))

(def same-as-query
  (rdf/create-query "select ?x where { ?x :owl/sameAs ?y }"))

(defn replace-hgnc-id-with-entrez [db triples]
  (map (fn [[s p o]]
         (if (re-find #"^((HGNC|hgnc):)?\d{1,5}$" o)
           [s p (first (same-as-query db {:y (rdf/resource o)}))]
           [s p o]))))

(defmethod rdf/as-model :genegraph.api.base/gci-express
  [{:keys [source]}]
  (with-open [r (io/reader (storage/->input-stream source))]
    (->> (charred/read-json r :key-fn keyword)
         (mapcat gci-express-report-to-triples)
         rdf/statements->model)))

(defn prop [report]
  (let [gene (rdf/resource (str "https://www.ncbi.nlm.nih.gov/gene/"
                                (:entrez_id report)))
        parsed-json (charred/read-json (json-content report) :key-fn keyword)
        moi-string (or (-> parsed-json :data :ModeOfInheritance)
                       (-> parsed-json :scoreJson :ModeOfInheritance))
        moi (->> moi-string
                 (re-find #"\(HP:(\d+)\)")
                 second
                 (str "http://purl.obolibrary.org/obo/HP_")
                 rdf/resource)]
    {:type :cg/GeneDiseaseValidityProposition
     :cg/gene gene
     :cg/disease (rdf/resource (-> report :conditions :MONDO :iri))
     :cg/modeOfInheritance moi}))


(def label->classification
  {"Definitive" :cg/Definitive
   "Limited" :cg/Limited
   "Moderate" :cg/Moderate
   "No Reported Evidence" :cg/NoKnownDiseaseRelationship
   "Strong*" :cg/Strong
   "Contradictory (disputed)" :cg/Disputed
   "Strong" :cg/Strong
   "Contradictory (refuted)" :cg/Refuted
   "Refuted" :cg/Refuted
   "Disputed" :cg/Disputed})



(def classification->direction
  {:cg/Limited :cg/Neutral
   :cg/Strong :cg/Supports
   :cg/Refuted :cg/Disputes
   :cg/Definitive :cg/Supports
   :cg/NoKnownDiseaseRelationship :cg/Neutral
   :cg/Disputed :cg/Disputes
   :cg/Moderate :cg/Supports})

(def hgnc-root "https://identifiers.org/hgnc:")

(defn ->gene [src]
  (some-> src :genes first val :curie (string/replace "HGNC:" hgnc-root)))

(defn ->condition [report]
  (get-in report [:conditions :MONDO :iri]))

(defn ->moi [report]
  (->> (or (get-in report [:data :ModeOfInheritance])
           (get-in report [:scoreJson :ModeOfInheritance]))
       (re-find #"\(HP:(\d+)\)")
       second
       (str "http://purl.obolibrary.org/obo/HP_")))

(defn ->proposition [src report]
  (let [p {:type :cg/GeneDiseaseValidityProposition
           :cg/subjectGene (->gene src)
           :cg/objectCondition (->condition src)
           :cg/predicate :cg/GeneValidityPredicate
           :cg/predicateModeOfInheritance (->moi report)}]
    (assoc p :iri (id/iri p))))

(defn ->submitter-id [src]
  (let [aff-root "http://genegraph.clinicalgenome.org/agent/"
        affl-str (get-in src [:affiliation :id])]
    (str aff-root "4" (subs affl-str 1))))

(defn ->contributions [src report]
  (let [base-contrib {:type :cg/Contribution
                      :cg/contributor (->submitter-id src)
                      :cg/date (subs (:dateISO8601 src) 0 10)}]
    (mapv
     #(assoc base-contrib :cg/activityType % :iri (id/random-iri))
     [:cg/Evaluated :cg/Submitted])))

(defn ->classification [src]
  (-> src :scores vals first :label label->classification))

(defn ->sop [src]
  (if (< 0 (count (:scoreJsonSerialized src)))
    :cg/GeneValidityCriteria4
    :cg/GeneValidityCriteria5))

(defn ->statement [[id src]]
  (let [report (charred/read-json (json-content src) :key-fn keyword)
        classification (->classification src)]
    {:iri (str gci-express-root (name id))
     :cg/classification classification
     :cg/direction (get classification->direction classification :cg/Neutral)
     :cg/contributions (->contributions src report)
     :cg/proposition (->proposition src report)
     :cg/specifiedBy (->sop src)}))

(defn gcex-json->statements [r]
  (let [container {:type :cg/Container
                   :cg/items (into
                              []
                              (comp (take 1)
                                    (map ->statement))
                              (charred/read-json r :key-fn keyword))}]
    (assoc container :iri (id/iri container))))

(comment
  (with-open [r (io/reader "/Users/tristan/data/genegraph-base/gci-express-with-entrez-ids.json")
              wr (io/writer "/Users/tristan/data/genegraph-base/gci-express-gks.edn")]
    (.write
     wr
     (with-out-str
       (clojure.pprint/pprint
        (gcex-json->statements r)))))

  (with-open [r (io/reader "/Users/tristan/data/genegraph-base/gci-express-with-entrez-ids.json")
              wr (io/writer "/Users/tristan/data/genegraph-base/gci-express-gks.edn")]
    (tap> (gcex-json->statements r)))
  (rdf/pp-model
   (with-open [r (io/reader "/Users/tristan/data/genegraph-base/gci-express-with-entrez-ids.json")]
     (rdf/edn->model (gcex-json->statements r))))
  )


(comment
  (with-open [r (io/reader "/users/tristan/data/genegraph-neo/gci-express-with-entrez-ids.json")]
    (->> (charred/read-json r :key-fn keyword)
         (take 1)
         (mapcat gci-express-report-to-triples)
         rdf/statements->model))
  
  (-> {:format :genegraph.api.base/gci-express
       :source   {:type :gcs
                  :bucket "genegraph-base"
                  :path "gci-express-with-entrez-ids.json"}}
      rdf/as-model
      rdf/pp-model)
  (-> {:type :gcs
       :bucket "genegraph-base"
       :path "gci-express-with-entrez-ids.json"}
      storage/as-handle
      slurp)

  (with-open [r  (-> {:type :gcs
                      :bucket "genegraph-base"
                      :path "gci-express-with-entrez-ids.json"}
                     storage/as-handle
                     io/reader)]
    (slurp r))

  
  )

;; (defmethod transform-doc :gci-express [doc-def]
;;   (let [raw-report (or (:document doc-def) (slurp (src-path doc-def)))
;;         report-json (json/parse-string raw-report true)]
;;     (rdf/statements-to-model (mapcat gci-express-report-to-triples report-json))))


;; (defmethod add-model :gci-express [event]
;;   (assoc event
;;          :genegraph.database.query/model
;;          (rdf/statements-to-model (gci-express-report-to-triples
;;                                  (:genegraph.sink.event/value event)))))
