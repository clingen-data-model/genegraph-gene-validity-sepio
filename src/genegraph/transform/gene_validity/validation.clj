(ns genegraph.transform.gene-validity.validation
  (:require [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.event :as event]
            [io.pedestal.interceptor :as interceptor]))

(defn publish? [event]
  (= :cg/Submitted (:cg/activityType event)))

(defn unpublish? [event]
  (= :cg/Unpublished (:cg/activityType event)))

(def tests
  [{:name :has-statement
    :check-fn (fn [{:gene-validity/keys [model]}]
                (= 1 (count ((rdf/create-query "select ?x where { ?x a :cg/Statement }") model))))}
   {:name :has-kafka-iri
    :check-fn (fn [event] (seq (::event/iri event)))}
   #_{:name :has-legacy-website-id
    :when publish?
    :check-fn (fn [{:gene-validity/keys [model]}]
                (seq ((rdf/create-query
                       "select ?id where { ?a :cg/websiteLegacyID ?id }") model)))}
   {:name :has-evaluated-contribution
    :when publish?
    :check-fn (fn [{:gene-validity/keys [model]}]
                (seq ((rdf/create-query
                       "select ?c where { ?c :cg/activityType :cg/Evaluated }") model)))}
   {:name :has-submitted-contribution
    :when publish?
    :check-fn (fn [{:gene-validity/keys [model]}]
                (seq ((rdf/create-query
                       "select ?c where { ?c :cg/activityType :cg/Submitted }") model)))}
   {:name :no-disconnected-evidence-lines
    :when publish?
    :check-fn (fn [{:gene-validity/keys [model]}]
                (let [q (rdf/create-query "
select ?el where {
  ?el a :cg/EvidenceLine .
  ?a a :cg/Statement .
  filter not exists { ?a (:cg/hasEvidenceLines|:cg/hasEvidenceItems|:cg/evidence)* ?el . }
}")]
                  (empty? (q model))))}
   {:name :has-evidence-lines
    :when publish?
    :check-fn (fn [{:gene-validity/keys [model]}]
                (seq ((rdf/create-query
                       "select ?el where { ?a a :cg/Statement ; :cg/hasEvidenceLines ?el }") model)))}
   {:name :has-classification
    :when publish?
    :check-fn (fn [{:gene-validity/keys [model]}]
                (seq ((rdf/create-query
                       "select ?c where { ?a a :cg/Statement ; :cg/classification ?c }") model)))}
   {:name :has-proposition
    :when publish?
    :check-fn (fn [{:gene-validity/keys [model]}]
                (seq ((rdf/create-query
                       "select ?p where { ?p a :cg/GeneValidityProposition }") model)))}
   {:name :unpublish-has-date
    :when unpublish?
    :check-fn (fn [{:gene-validity/keys [model]}]
                (let [unpub-contributions
                      ((rdf/create-query
                        "select ?c where { ?c :cg/activityType :cg/Unpublished }") model)]
                  (or (empty? unpub-contributions)
                      (seq ((rdf/create-query
                             "select ?c where { ?c :cg/activityType :cg/Unpublished ; :cg/date ?d }") model)))))}])

(defn validate-fn [event]
  (let [results (reduce
                 (fn [res {:keys [name when check-fn]}]
                   (if (and when (not (when event)))
                     res
                     (let [tr (if (check-fn event) :pass :fail)]
                       (update res tr conj name))))
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
