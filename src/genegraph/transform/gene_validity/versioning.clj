(ns genegraph.transform.gene-validity.versioning
  (:require [genegraph.framework.event :as event]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.storage.rdf :as rdf]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.log :as log]
            [clojure.set :as set])
  (:import [java.time Instant]))

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

(defn add-version-map [event]
  (let [prior-version (storage/read (get-in event [::storage/storage :gene-validity-version-store])
                                    (::proposition-iri event))]
    (if (= ::storage/miss prior-version)
      (assoc event :gene-validity/version {:major 1 :minor 0})
      (calculate-version-given-prior-version event prior-version))))

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
  :dc/isVersionOf ?s1 .

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
        assertion-with-version (rdf/resource
                                (str (first (assertion-iri model))
                                     "v"
                                     version-str))
        sequence (::event/offset event -1)]
    (assoc event
           :gene-validity/model
           (construct-versioned-model model {:assertionIRI assertion-with-version
                                             :version version-str
                                             :sequence sequence}))))

(def prop-query
  (rdf/create-query "select ?x where { ?x a :cg/GeneValidityProposition }"))

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

(def add-version
  (interceptor/interceptor
   {:name ::add-version
    :enter (fn [e] (calculate-version e))}))
