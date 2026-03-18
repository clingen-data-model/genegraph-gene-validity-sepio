(ns genegraph.transform.gene-validity.validation
  (:require [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.event :as event]
            [io.pedestal.interceptor :as interceptor]))

(defn publish? [event]
  (= :cg/Submitted (:cg/activityType event)))

(def tests
  [{:name :no-disconnected-evidence-lines
    :check-fn (fn [{:gene-validity/keys [model]}]
                (let [q (rdf/create-query "
select ?el where {
  ?el a :cg/EvidenceLine .
  ?a a :cg/Statement .
  filter not exists { ?a (:cg/hasEvidenceLines|:cg/hasEvidenceItems|:cg/evidence)* ?el . }
}")]
                  (empty? (q model))))}])

(defn validate-fn [event]
  (let [results (reduce
                 (fn [res t]
                   (let [tr (if ((:check-fn t) event) :pass :fail)]
                     (update res :pass conj (:name t))))
                 {:pass []
                  :fail []}
                 tests)]
    (assoc event
           :gene-validity/passed-tests (:pass results)
           :gene-validity/failed-tests (:fail results)
           :gene-validity/valid (empty? (:fail results)))))

(def validate
  (interceptor/interceptor
   {:name ::validation
    :enter (fn [e] (validate-fn e))}))


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
