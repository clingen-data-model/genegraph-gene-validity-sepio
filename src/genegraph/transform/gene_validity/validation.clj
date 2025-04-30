(ns genegraph.transform.gene-validity.validation
  (:require [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.event :as event]))

(def disconnected-evidence-lines-query
  (rdf/create-query "
select ?el where {
?el a :cg/EvidenceLine .
?a a :cg/EvidenceStrengthAssertion .
filter not exists {
?a :cg/evidence * ?el .
}
}"))

(defn has-disconnected-evidence-lines [m]
  (let [disconnected-evidence-lines (disconnected-evidence-lines-query m)]
    (if (seq disconnected-evidence-lines)
      {:result :fail
       :test ::has-disconnected-evidence-lines
       :items (mapv str disconnected-evidence-lines)}
      {:result :pass
       :test ::has-disconnected-evidence-lines})))

(def tests
  [has-disconnected-evidence-lines])

(defn validate [data]
  (let [test-results (mapv #(% data) tests)]
    (if (some #(= :fail (:result %)) test-results)
      {:result :fail
       :tests test-results}
      {:result :pass
       :tests test-results})))

(defn add-validation [event]
  (assoc event
         :gene-validity/validation
         (validate (:gene-validity/model event))))


(comment
  ;; Exploring SHACL testing for data integrity

  (genegraph.framework.event.store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2025-04-01.edn.gz"]
    (->> (genegraph.framework.event.store/event-seq r)
         (take 10)
         (map #(genegraph.user/transform-curation %))
         (map add-validation)
         (filterv #(= :fail (get-in % [:gene-validity/validation :result])))
         #_(take 1)
         #_(mapv #(dissoc % :gene-validity/model :gene-validity/gci-model))
         #_(mapv #(get-in % [::event/data :properties :resourceParent]))
         #_tap>
         #_(run! #(-> % :gene-validity/model rdf/pp-model))
         count))
  )


;; Detected errors

;; some evidence lines not connecting appropriately
;; may be a problem with evidence lines with 'review' status
;; consider pruning, do not think these belong in final published classification
;; but they do currently exist in display--need to make a judgement call

;; These scores may not have a gciCaseInfoType associated with them, and therefore
;; did not have a criteria associated with them either.
;; Modified proband score for < SOP 8 to include an option for
;; cg:GeneValidityUncategorizedProbandCriteria , 

;; also noticing unscorable hasn't been updated to new types yet

;; Undetected errors:
;; ageUnit and ageType not translating appropriately

;; :cggv/bbdc5900-9059-4dd6-ad78-dca575da6242
;;         a       :cg/Proband;
;;         :rdfs/label
;;                 "1";
;;         :cg/ageType
;;                 :cggv/;
;;         :cg/ageUnit
;;                 :cggv/;
;;         :cg/detectionMethod
;;                 "";
;;         :cg/firstTestingMethod
;;                 "Exome sequencing";
;;         :cg/phenotypeFreeText
;;                 "Hypotonia, chorea, motor delay";
;;         :cg/phenotypes
;;                 :hp/0002072 , :hp/0001252 , :hp/0002194;
;;         :cg/previousTesting
;;                 false;
;;         :cg/previousTestingDescription
;;                 "";
;;         :cg/sex
;;                 :cg/Male;
;;         :cg/variant
;;                 :cggv/608cf1b6-cd1c-4db6-9570-aa89460505fa_variant_evidence_item;
;;         :dc/source
;;                 :pmid/26060304 .
