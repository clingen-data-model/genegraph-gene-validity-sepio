(ns genegraph.transform.gene-validity.versioning
  (:require [genegraph.framework.event :as event]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.transform.gene-validity.proposition :as proposition]
            [genegraph.transform.gene-validity.changes :as changes]
            [genegraph.transform.gene-validity.abbreviate :as abbrev]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.log :as log]
            [clojure.set :as set])
  (:import [java.time Instant]))

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
           (changes/no-change? event prior-event) :no-change
           :default :minor-change)))

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
  (if-let [prior-version (:gene-validity/previous-model event)]
    (-> event
        (changes/add-changes prior-version)
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

(defn version-map->version-str [version-map]
  (str (:major version-map "0")
       "."
       (:minor version-map "0")
       "."
       (:patch version-map "0")))

(defn add-versioned-model [event]
  (let [model (:gene-validity/model event)
        version-str (version-map->version-str (:gene-validity/version event))
        assertion-root #_(first (proposition/prop-query model)) (:gene-validity/gdm event)
        assertion-with-version (rdf/resource
                                (str assertion-root
                                     "v"
                                     version-str))
        sequence (::event/offset event -1)]
    (assoc event
           :gene-validity/model
           (construct-versioned-model model
                                      {:assertionIRI assertion-with-version
                                       :assertionRoot (rdf/resource assertion-root)
                                       :snapshotIRI (first (assertion-iri model))
                                       :version version-str
                                       :sequence sequence}))))


(defn add-prop-iri [event]
  (assoc event
         ::proposition-iri
         (-> event
             :gene-validity/model
             proposition/prop-query
             first
             str)))

#_(defn calculate-version [event]
  (let [event-with-approval-date (add-approval-date event)]
    (if (and (has-publish-action (:gene-validity/model event))
             (:gene-validity/approval-date event-with-approval-date))
      (-> event-with-approval-date
          add-prop-iri
          add-version-map
          add-versioned-model)
      event-with-approval-date)))

#_(defn update-unpublish-event [event]
  (let [prior-version (read-prior-version )]))

(defn first-curation? [event]
  (nil? (:gene-validity/last-outcome event)))

(defn patch-release? [event]
  (:gene-validity/patch-release event))

(defn gci-recuration? [event]
  (seq (set/intersection recuration-reasons (:gene-validity/curation-reasons event))))

(defn gci-minor-change?
  "Requires gci-recuration? first. If the GCI lists change reasons, and none of them are
  in the recuration-reasons set, the change is by default a minor change."
  [event]
  (seq (:gene-validity/curation-reasons event)))

(defn no-op?
  "Determine if there has been no significant change within a patch release. If this is the case,
  do not increment version."
  [event]
  false)

(defn event->approval-ms [event]
  (-> event
      :gene-validity/approval-date
      Instant/parse
      .toEpochMilli))

(defn calculated-curation-approval-time-change? [event]
  (< six-months
     (- (event->approval-ms event)
        (event->approval-ms (:gene-validity/last-outcome event)))))

(defn add-change-type [event]
  (assoc
   event
   :gene-validity/change-type
   (cond
     (first-curation? event):first-curation
     (no-op? event) :no-op
     (patch-release? event) :patch
     (gci-recuration? event) :gci-recuration
     (gci-minor-change? event) :gci-minor-change
     (calculated-curation-approval-time-change? event) :time-delta-recuration
     :else :default-minor-change)))

(defn inc-version [version change-level]
  (update version change-level inc))

(defn inc-major [{:keys [major]}]
  {:major (inc major)
   :minor 0
   :patch 0})

(defn inc-minor [{:keys [major minor]}]
  {:major major
   :minor (inc minor)
   :patch 0})

(defn inc-patch [v]
  (update v :patch inc))

(defn add-version-fn [event]
  (let [prior-version (get-in event [:gene-validity/last-outcome :gene-validity/version])]
    (assoc
     event
     :gene-validity/version
     (case (:gene-validity/change-type event)
       :first-curation {:major 1 :minor 0 :patch 0}
       :patch (inc-patch prior-version)
       :gci-recuration (inc-major prior-version)
       :time-delta-recuration (inc-major prior-version)
       :no-op (:gene-validity/version event)
       :gci-minor-change (inc-minor prior-version)
       :default-minor-change (inc-minor prior-version)
       (log/info :fn ::version :change-type (:gene-validity/change-type event))))))

(defn calculate-version [event]
  (try
    (if (get (:gene-validity/activity event) :cg/Submitted)
      (-> event
          add-change-type
          add-version-fn
          add-versioned-model)
      event)
    (catch Exception e
      (tap> (abbrev/abbreviate event))
      (log/warn :fn ::calculate-version
                :offset (::event/offset event))
      event)))

(def add-version
  (interceptor/interceptor
   {:name ::add-version
    :enter (fn [e] (calculate-version e))}))
