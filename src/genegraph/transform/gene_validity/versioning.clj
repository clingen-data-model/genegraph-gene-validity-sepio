(ns genegraph.transform.gene-validity.versioning
  (:require [genegraph.framework.event :as event]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.id :as id]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.log :as log]
            [clojure.set :as set])
  (:import [java.time Instant]))

(id/register-type {:type :cg/GeneValidityProposition
                   :defining-attributes
                   [:cg/gene :cg/disease :cg/modeOfInheritance]})


;; CLASSIFICATION_CHANGE	The classification has changed as a result of this recuration

(defn classification-change? [old-model new-model]
  (let [q (rdf/create-query "select ?o where { ?a :cg/evidenceStrength ?o }")]
    (not= (first (q old-model)) (first (q new-model)))))

;; DISEASE_ID_CHANGE	The disease ontology ID has changed for this recuration

(defn disease-change? [old-model new-model]
  (let [q (rdf/create-query "select ?o where { ?a :cg/disease ?o }")]
    (not= (first (q old-model)) (first (q new-model)))))

;; MOI_CHANGE	The Mode of Inheritance has changed from the previous curation

(defn moi-change? [old-model new-model]
  (let [q (rdf/create-query "
select ?o where {
 ?a a :cg/GeneValidityProposition ;
 :cg/modeOfInheritance ?o }")]
    (not= (first (q old-model)) (first (q new-model)))))

;; EXPERT_PANEL_CHANGE	Ownership of the curation has transferred to a new Expert Panel or CDWG

(defn expert-panel-change? [old-model new-model]
  (let [q (rdf/create-query "
select ?o where {
 ?a :cg/role :cg/Approver ;
 :cg/agent ?o . }")]
    (not= (first (q old-model)) (first (q new-model)))))

;; SOP_CHANGE	A new SOP was used for the recuration

(defn sop-change? [old-model new-model]
  (let [q (rdf/create-query "
select ?o where {
 ?a a :cg/EvidenceStrengthAssertion ;
 :cg/specifiedBy ?o . }")]
    (not= (first (q old-model)) (first (q new-model)))))

;; EVIDENCE_ADDED_CHANGE	A new evidence item was added

(defn new-evidence? [old-model new-model]
  (let [q (rdf/create-query "
select ?o where {
 ?e :dc/source ?o . } ")]
    (< (count (q old-model)) (count (q new-model)))))

;; SUMMARY_TEXT_CHANGE	A minor text change was mane in the Summary section

(defn summary-change? [old-model new-model]
  (let [q (rdf/create-query "
select ?o where { ?o a :cg/EvidenceStrengthAssertion . }")
        summary (fn [m] (rdf/ld1-> (first (q m)) [:dc/description]))]
    (not= (summary old-model) (summary new-model))))

;; OTHER_TEXT_CHANGE	A minor text change was made in the evidence or other text sections

(defn other-text-change? [old-model new-model]
  (let [q (rdf/create-query "
select ?o where { ?o :dc/description ?d 
filter not exists { ?o a :cg/EvidenceStrengthAssertion } }")
        text-elements (fn [m] (->> (q m)
                                   (map (fn [r] [r (rdf/ld1-> r [:dc/description])]))
                                   set))]
    (not= (text-elements old-model) (text-elements new-model))))

;; OTHER_CHANGE	A code to use where none other applies but the activity feels it is important to note the change.  This should be used sparingly.  If one or more activities has a frequent need, then a new formal change code should be added

;; maybe not implemented

(defn other-change? [old-model new-model]
  false)

(def change-codes
  [{:phil-term "CLASSIFICATION-CHANGE"
    :term :cg/classificationChange
    :predicate classification-change?}
   {:phil-term "MOI_CHANGE"
    :term :cg/moiChange
    :predicate moi-change?}
   {:phil-term "EXPERT_PANEL_CHANGE"
    :term :cg/expertPanelChange
    :predicate expert-panel-change?}
   {:phil-term "SOP_CHANGE"
    :term :cg/sopChange
    :predicate sop-change?}
   {:phil-term "EVIDENCE_ADDED_CHANGE"
    :term :cg/newEvidence
    :predicate new-evidence?}
   {:phil-term "SUMMARY_TEXT_CHANGE"
    :term :cg/summaryChange
    :predicate summary-change?}
   {:phil-term "OTHER_TEXT_CHANGE"
    :term :cg/otherTextChange
    :predicate other-text-change?}])

(defn changes [old-model new-model]
  (reduce (fn [existing-changes change-code]
            (if ((:predicate change-code) old-model new-model)
              (conj existing-changes (:term change-code))
              existing-changes))
          []
          change-codes))

(defn gv-model [event]
  (or (:gene-validity/model event)
      (::event/data event)))

(defn add-changes [event old-event]
  #_(println "add-changes")
  (let [old-model (gv-model old-event)
        new-model (gv-model event)
        change-set (changes old-model new-model)
        q (rdf/create-query "select ?o where { ?o a :cg/EvidenceStrengthAssertion . }")
        assertion (-> event :gene-validity/model q first)
        change-model (rdf/statements->model (mapv (fn [c] [assertion :cg/changes c])
                                                  change-set))]
    (assoc event
           :gene-validity/changes change-set
           :gene-validity/model (rdf/union new-model change-model))))

(def prop-query
  (rdf/create-query "select ?x where { ?x a :cg/GeneValidityProposition }"))

(defn proposition-id [model]
  (let [prop (first (prop-query model))]
    (id/iri
     {:type :cg/GeneValidityProposition
      :cg/gene (str (rdf/ld1-> prop [:cg/gene]))
      :cg/disease (str (rdf/ld1-> prop [:cg/disease]))
      :cg/modeOfInheritance (str (rdf/ld1-> prop [:cg/modeOfInheritance]))})))

(def rename-proposition-query
  (rdf/create-query "
construct {
  ?s ?p ?o .
  ?propIRI ?p1 ?o1 .
  ?s2 ?p2 ?propIRI .
} where {
 { ?s ?p ?o .
   FILTER NOT EXISTS { ?s a :cg/GeneValidityProposition . }
   FILTER NOT EXISTS { ?o a :cg/GeneValidityProposition . } 
 }
 union 
 {
  ?s1 a :cg/GeneValidityProposition .
  ?s1 ?p1 ?o1 .
  ?s2 ?p2 ?s1 .
 }
}
"))

(defn rename-proposition [model]
  (rename-proposition-query
   model
   {:propIRI (rdf/resource (proposition-id model))}))

#_(-> genegraph.user/examples
    first
    :gene-validity/model
    rename-proposition
    rdf/pp-model)

(def activity-with-role
  (rdf/create-query "select ?activity where
{ ?activity :cg/role ?role }"))

(def curation-reasons
  (rdf/create-query "select ?reasons where
{ ?curation :cg/curationReasons ?reasons }"))

(def publish-actions
  (rdf/create-query "select ?x where { ?x :cg/role :cg/Publisher } "))

(defn has-publish-action [m]
  (seq (publish-actions m)))

(defn approval-date [model]
  (some-> (activity-with-role model {:role :cg/Approver})
          first
          (rdf/ld1-> [:cg/date])))

(defn no-change? [event prior-event]
  (rdf/is-isomorphic? (:gene-validity/model event)
                      (:gene-validity/model prior-event)))

(defn event->approval-ms [event]
  (-> event
      :gene-validity/approval-date
      Instant/parse
      .toEpochMilli))

(def six-months
  (* 1000 60 60 24 30 6))

(def recuration-reasons
  #{:cg/RecurationCommunityRequest
    :cg/RecurationTiming
    :cg/RecurationNewEvidence
    :cg/RecurationFrameworkChange
    :cg/RecurationErrorAffectingScoreorClassification
    :cg/RecurationDiscrepancyResolution})

(defn recuration-from-gci-reasons? [event]
  (let [reasons (set (map rdf/->kw
                          (curation-reasons
                           (:gene-validity/model event))))]
    (if (seq reasons)
      (if (seq (set/intersection recuration-reasons reasons))
        :recuration
        :no-recuration)
      :no-gci-reasons)))

(defn estimated-recuration? [event prior-event]
  (let [event-time (event->approval-ms event)
        prior-time (event->approval-ms prior-event)]
    (< six-months (- event-time prior-time))))

(defn recuration? [event prior-event]
  (let [gci-recuration (recuration-from-gci-reasons? event)]
    (or (= :recuration gci-recuration)
        (and (= :no-gci-reasons gci-recuration)
             (estimated-recuration? event prior-event)))))

(defn add-change-type [event prior-event]
  (assoc event
         :gene-validity/change-type
         (cond
           (recuration? event prior-event) :major-change
           (no-change? event prior-event) :no-change
           :default :minor-change)))

(defn store-this-version [event]
  (event/store event
               :gene-validity-version-store
               (::proposition-iri event)
               (select-keys event
                            [:gene-validity/version
                             :gene-validity/model
                             :gene-validity/approval-date])))

(defn add-version-increment-given-change [event prior-event]
  (let [prior-version (:gene-validity/version prior-event)]
    (assoc event
           :gene-validity/version
           (case (:gene-validity/change-type event)
             :no-change prior-version
             :major-change {:major (inc (:major prior-version)) :minor 0}
             (update prior-version :minor inc)))))

(defn calculate-version-given-prior-version [event prior-version]
  (-> event
      (add-change-type prior-version)
      (add-version-increment-given-change prior-version)))

(defn read-prior-version [event gdm-iri]
  (let [prior-version
        (storage/read (get-in event [::storage/storage :gene-validity-version-store])
                      gdm-iri)]
    (if (= ::storage/miss prior-version)
      nil
      prior-version)))

(defn add-version-map [event]
  (if-let [prior-version (read-prior-version event (::proposition-iri event))]
    (-> event
        (add-changes prior-version)
        (calculate-version-given-prior-version prior-version))
    (assoc event :gene-validity/version {:major 1 :minor 0})))

(defn add-approval-date [event]
  (assoc event
         :gene-validity/approval-date
         (approval-date (:gene-validity/model event))))

(def assertion-iri
  (rdf/create-query "select ?x where { ?x a :cg/EvidenceStrengthAssertion }"))

(def construct-versioned-model
  (rdf/create-query "
construct {
  ?s ?p ?o .
  ?assertionIRI ?p1 ?o1 ;
  :cg/version ?version ;
  :cg/sequence ?sequence ;
  :cg/GCISnapshot ?snapshotIRI ;
  :dc/isVersionOf ?assertionRoot .

} where {
 { ?s ?p ?o .
   FILTER NOT EXISTS { ?s a :cg/EvidenceStrengthAssertion . } 
 }
 union 
 {
  ?s1 ?p1 ?o1 .
  ?s1 a :cg/EvidenceStrengthAssertion .
 }
}
"))


(defn add-versioned-model [event]
  (let [model (:gene-validity/model event)
        version-str (str (get-in event [:gene-validity/version :major])
                         "."
                         (get-in event [:gene-validity/version :minor]))
        assertion-root (first (prop-query model))
        assertion-with-version (rdf/resource
                                (str assertion-root
                                     "v"
                                     version-str))
        sequence (::event/offset event -1)
        model-with-renamed-proposition (rename-proposition model)]
    (assoc event
           :gene-validity/model
           (construct-versioned-model model-with-renamed-proposition
                                      {:assertionIRI assertion-with-version
                                       :assertionRoot assertion-root
                                       :snapshotIRI (first (assertion-iri model))
                                       :version version-str
                                       :sequence sequence}))))


(defn add-prop-iri [event]
  (assoc event
         ::proposition-iri
         (-> event
             :gene-validity/model
             prop-query
             first
             str)))

(defn calculate-version [event]
  (let [event-with-approval-date (add-approval-date event)] 
    (if (and (has-publish-action (:gene-validity/model event))
             (:gene-validity/approval-date event-with-approval-date))
      (-> event-with-approval-date
          add-prop-iri
          add-version-map
          store-this-version
          add-versioned-model)
      event-with-approval-date)))

#_(defn update-unpublish-event [event]
  (let [prior-version (read-prior-version )]))

(def add-version
  (interceptor/interceptor
   {:name ::add-version
    :enter (fn [e] (calculate-version e))}))
