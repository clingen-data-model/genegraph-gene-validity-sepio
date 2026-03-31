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
                   [:cg/subject :cg/object :cg/qualifier :cg/predicate]})

(defn as-query [query]
  (if (string? query) (rdf/create-query query) query))

(defn summary-change-record [old-model new-model]
  (let [q (rdf/create-query "
select ?o where { ?o a :cg/Statement . }")
        summary (fn [m] (rdf/ld1-> (first (q m)) [:dc/description]))
        old-summary (summary old-model)
        new-summary (summary new-model)]
    (if (not= old-summary new-summary)
      {:cg/changeType :cg/summaryChange
       :cg/previousVersion old-summary
       :cg/currentVersion new-summary})))

(defn new-evidence-change-record [old-model new-model]
  (let [q (rdf/create-query "
select ?o where {
 ?e :dc/source ?o . } ")
        old-evidence (->> (q old-model) (map str) set)
        new-evidence (->> (q new-model) (map str) set)]
    #_(tap> {:old-evidence old-evidence
           :new-evidence new-evidence})
    (if (not= old-evidence new-evidence)
      {:cg/changeType :cg/newEvidence
       :cg/previousVersion old-evidence
       :cg/currentVersion new-evidence})))

(defn simple-query-change-record-fn [{:keys [query change-type]}]
  (fn [old-model new-model]
    (let [q (as-query query)
          old-record (-> old-model q first str)
          new-record (-> new-model q first str)]
      (if (not= (first (q old-model)) (first (q new-model)))
        {:cg/changeType change-type
         :cg/previousVersion old-record
         :cg/currentVersion new-record}))))

(defn summary-query [m]
  (let [q (rdf/create-query "
select ?o where { ?o a :cg/Statement . }")]
    (rdf/ld-> (first (q m)) [:dc/description])))

(def change-record-list
  [{:change-type :cg/classificationChange
    :query "select ?o where { ?a :cg/classification ?o }"
    :required true}
   {:change-type :cg/diseaseIDChange
    :query "select ?o where { ?a a :cg/GeneValidityProposition ; :cg/object ?o }"
    :required true}
   {:change-type :cg/MOIChange
    :query "select ?o where {
 ?a a :cg/GeneValidityProposition ;
 :cg/qualifier ?o }"
    :required true}
   {:change-type :cg/expertPanelChange
    :query "select ?o where {
 ?a :cg/activityType :cg/Evaluated ;
 :cg/contributor ?o . }"
    :required true}
   {:change-type :cg/SOPChange
    :query "select ?o where {
 ?a a :cg/Statement ;
 :cg/specifiedBy ?o . }"
    :required true}
   {:change-type :cg/summaryChange
    #_#_:fn summary-change-record
    :query summary-query
    :required true}
   {:change-type :cg/newEvidence
    :fn new-evidence-change-record}])


(defn valid-event? [e]
  (not
   (some #(-> e :gene-validity/model % seq not)
         (->> change-record-list
              (filter :required)
              (mapv #(as-query (:query %)))))))

(def change-type->object-type
  {:cg/classificationChange :resource
   :cg/diseaseIDChange :resource
   :cg/MOIChange :resource
   :cg/expertPanelChange :resource
   :cg/SOPChange :resource
   :cg/summaryChange :string
   :cg/newEvidence :resource-list})

(def change-record-list-fns
  (mapv (fn [r]
          (if (:fn r)
            r
            (assoc r :fn (simple-query-change-record-fn r))))
        change-record-list))

;; CLASSIFICATION_CHANGE	The classification has changed as a result of this recuration

(defn classification-change? [old-model new-model]
  (let [q (rdf/create-query "select ?o where { ?a :cg/classification ?o }")]
    (not= (first (q old-model)) (first (q new-model)))))

;; DISEASE_ID_CHANGE	The disease ontology ID has changed for this recuration

(defn disease-change? [old-model new-model]
  (let [q (rdf/create-query "select ?o where { ?a a :cg/GeneValidityProposition ; :cg/object ?o }")]
    (not= (first (q old-model)) (first (q new-model)))))

;; MOI_CHANGE	The Mode of Inheritance has changed from the previous curation

(defn moi-change? [old-model new-model]
  (let [q (rdf/create-query "
select ?o where {
 ?a a :cg/GeneValidityProposition ;
 :cg/qualifier ?o }")]
    (not= (first (q old-model)) (first (q new-model)))))

;; EXPERT_PANEL_CHANGE	Ownership of the curation has transferred to a new Expert Panel or CDWG

(defn expert-panel-change? [old-model new-model]
  (let [q (rdf/create-query "
select ?o where {
 ?a :cg/activityType :cg/Evaluated ;
 :cg/contributor ?o . }")]
    (not= (first (q old-model)) (first (q new-model)))))

;; SOP_CHANGE	A new SOP was used for the recuration

(defn sop-change? [old-model new-model]
  (let [q (rdf/create-query "
select ?o where {
 ?a a :cg/Statement ;
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
select ?o where { ?o a :cg/Statement . }")
        summary (fn [m] (rdf/ld1-> (first (q m)) [:dc/description]))]
    (not= (summary old-model) (summary new-model))))




;; OTHER_TEXT_CHANGE	A minor text change was made in the evidence or other text sections

(defn other-text-change? [old-model new-model]
  (let [q (rdf/create-query "
select ?o where { ?o :dc/description ?d 
filter not exists { ?o a :cg/Statement } }")
        text-elements (fn [m] (->> (q m)
                                   (map (fn [r] [r (rdf/ld1-> r [:dc/description])]))
                                   set))]
    (not= (text-elements old-model) (text-elements new-model))))

;; OTHER_CHANGE	A code to use where none other applies but the activity feels it is important to note the change.  This should be used sparingly.  If one or more activities has a frequent need, then a new formal change code should be added

;; maybe not implemented

(defn other-change? [old-model new-model]
  false)

(def change-codes
  [{:phil-term "CLASSIFICATION_CHANGE"
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

(defn change-records [old-model new-model]
  (->> (reduce (fn [records change-record-fn]
                 (conj records (change-record-fn old-model new-model)))
               []
               (mapv :fn change-record-list-fns))
       (remove nil?)
       (into [])))

(defn change-record->statements
  [{:cg/keys [changeType previousVersion currentVersion]} assertion]
  (let [s (rdf/blank-node)
        stmts [[assertion :cg/changeRecords s]
               [s :cg/changeType changeType]]]
    (case (change-type->object-type changeType)
      :string (conj stmts
                    [s :cg/previousVersion previousVersion]
                    [s :cg/currentVersion currentVersion])
      :resource (conj stmts
                      [s :cg/previousVersion (rdf/resource previousVersion)]
                      [s :cg/currentVersion (rdf/resource currentVersion)])
      :resource-list (into
                      []
                      (concat stmts
                              (map (fn [x] [s :cg/previousVersion (rdf/resource x)])
                                   previousVersion)
                              (map (fn [x] [s :cg/currentVersion (rdf/resource x)])
                                   currentVersion))))))

(defn change-records->model [assertion change-records]
  (try
    (->> change-records
         (mapcat #(change-record->statements % assertion))
         (into [])
         rdf/statements->model)
    (catch Exception e
      (tap> {:change-records change-records
             :exception e})
      (rdf/statements->model []))))


(defn add-changes [event old-event]
  (let [old-model (gv-model old-event)
        new-model (gv-model event)
        change-set (changes old-model new-model)
        q (rdf/create-query "select ?o where { ?o a :cg/Statement . }")
        assertion (-> event :gene-validity/model q first)
        records (change-records old-model new-model)
        records-model (change-records->model assertion records)
        change-model (rdf/statements->model (mapv (fn [c] [assertion :cg/changes c])
                                                  change-set))]
    (assoc event
           :gene-validity/changes change-set
           :gene-validity/change-records records
           :gene-validity/model (rdf/union new-model change-model records-model))))

(def prop-query
  (rdf/create-query "select ?x where { ?x a :cg/GeneValidityProposition }"))

(defn proposition-id [model]
  (let [prop (first (prop-query model))]
    (id/iri
     {:type :cg/GeneValidityProposition
      :cg/subject (str (rdf/ld1-> prop [:cg/subject]))
      :cg/object (str (rdf/ld1-> prop [:cg/object]))
      :cg/qualifier (str (rdf/ld1-> prop [:cg/qualifier]))
      :cg/predicate (str (rdf/ld1-> prop [:cg/predicate]))})))

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
{ ?activity :cg/activityType ?role }"))

(def curation-reasons
  (rdf/create-query "select ?reasons where
{ ?curation :cg/curationReasons ?reasons }"))

(def publish-actions
  (rdf/create-query "select ?x where { ?x :cg/activityType :cg/Submitted } "))

(defn has-publish-action [m]
  (seq (publish-actions m)))

(defn approval-date [model]
  (some-> (activity-with-role model {:role :cg/Submitted})
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
  (let [event-elements-to-store
        (select-keys event
                     [:gene-validity/version
                      :gene-validity/model
                      :gene-validity/approval-date
                      :gene-validity/change-records
                      :gene-validity/website-event])]
    (event/store event
                 :gene-validity-version-store
                 [::last-version (::proposition-iri event)]
                 event-elements-to-store)))

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
                      [::prior-version gdm-iri])]
    (if (= ::storage/miss prior-version)
      nil
      prior-version)))

(defn add-version-map [event]
  (if-let [prior-version (read-prior-version event (::proposition-iri event))]
    (-> event
        (add-changes prior-version)
        (calculate-version-given-prior-version prior-version))
    (assoc event
           :gene-validity/version {:major 1 :minor 0}
           :gene-validity/change-type :new-curation)))

(defn add-approval-date [event]
  (assoc event
         :gene-validity/approval-date
         (approval-date (:gene-validity/model event))))

(def assertion-iri
  (rdf/create-query "select ?x where { ?x a :cg/Statement }"))

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
   FILTER NOT EXISTS { ?s a :cg/Statement . } 
 }
 union 
 {
  ?s1 ?p1 ?o1 .
  ?s1 a :cg/Statement .
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
