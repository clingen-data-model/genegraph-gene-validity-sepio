(ns genegraph.transform.gene-validity.website-events
  "Code to populate 'all curation events' topic for website."
  (:require [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.event :as event]
            [genegraph.framework.storage :as storage]
            [genegraph.transform.gene-validity.versioning :as versioning]
            [io.pedestal.log :as log]
            [io.pedestal.interceptor :as interceptor]
            [clojure.java.io :as io]
            [charred.api :as charred]
            [clojure.spec.alpha :as s]))

;; Basic types
(s/def ::non-empty-string (s/and string? #(not (empty? %))))
(s/def ::timestamp-string (s/and string? #(re-matches #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{3})?Z$" %)))
(s/def ::uri-string (s/and string? #(re-matches #"^http.*" %)))
(s/def ::nullable-timestamp (s/nilable ::timestamp-string))

;; schema_version
(s/def ::schema_version ::non-empty-string)

;; affiliation
(s/def ::affiliate_id pos-int?)
(s/def ::affiliation (s/keys :req-un [::affiliate_id]))

;; references
(s/def ::source_uuid ::uri-string)
(s/def ::alternate_uuid ::uri-string)
(s/def ::dx_location ::non-empty-string)

;; additional_properties
(s/def ::gci_snapshot_id ::non-empty-string)
(s/def ::genegraph_proposition_id ::uri-string)
(s/def ::additional_properties
  (s/keys :req-un [::gci_snapshot_id
                   ::genegraph_proposition_id]))

(s/def ::references
  (s/keys :req-un [::source_uuid
                   ::alternate_uuid
                   ::dx_location
                   ::additional_properties]))

;; source
(s/def ::source #{"GENEGRAPH"})

;; activity
(s/def ::activity #{"VALIDITY"})

;; workflow
(s/def ::classification_date ::timestamp-string)
(s/def ::publish_date ::timestamp-string)
(s/def ::unpublish_date ::nullable-timestamp)
(s/def ::workflow (s/keys :req-un [::classification_date ::publish_date]
                          :opt-un [::unpublish_date]))

;; event_type
(s/def ::event_type #{"PUBLISH" "UNPUBLISH" "RETRACT" "RETIRE"})

;; version
(s/def ::display ::non-empty-string)
(s/def ::internal ::non-empty-string)
(s/def ::reasons (s/coll-of #{"RECURATION_TIMING"
                              "ADMIN_UPDATE_DISEASE_NAME"
                              "ADMIN_UPDATE_ERROR_CLASS"
                              "RECURATION_FRAMEWORK"
                              "RECURATION_COMMUNITY_REQUEST"
                              "RECURATION_GENEGRAPH_CALCULATED"
                              "RECURATION_DISCREPANCY_RESOLUTION"
                              "NEW_CURATION"
                              "RECURATION_ERROR_SCORE_CLASS"
                              "ADMIN_UPDATE_OTHER"
                              "ADMIN_UPDATE_GENEGRAPH_CALCULATED"
                              "RECURATION_NEW_EVIDENCE"}
                            :kind vector?))
(s/def ::description (s/nilable string?))

(s/def ::version (s/keys :req-un [::display ::internal ::reasons ::description]))

;; event_subtype
(s/def ::event_subtype #{"CURATION"})

;; Root entity
(s/def ::event-data
  (s/keys :req-un [::schema_version 
                  ::affiliation 
                  ::references 
                  ::source 
                  ::activity 
                  ::workflow 
                  ::event_type 
                  ::version 
                  ::event_subtype]))

(def activity-query
  (rdf/create-query "
select ?act where {
?act :cg/activityType ?activity .
}
"))

(defn activity-date [curation-model activity]
  (if-let [activity (first (activity-query curation-model
                                          {:activity activity}))]
    (rdf/ld1-> activity [:cg/date])))



(defn version-string [version-map]
  (str (:major version-map "0")
       "."
       (:minor version-map "0")
       "."
       (:patch version-map "0")))

(def assertion-query
  (rdf/create-query "select ?x where { ?x a :cg/Statement }"))

(def genegraph-reason->website-reason
  {:cg/NewCuration "NEW_CURATION"
   :cg/DiseaseNameUpdate "ADMIN_UPDATE_DISEASE_NAME"
   :cg/ErrorClarification "ADMIN_UPDATE_ERROR_CLASS"
   :cg/RecurationCommunityRequest "RECURATION_COMMUNITY_REQUEST"
   :cg/RecurationTiming "RECURATION_TIMING"
   :cg/RecurationNewEvidence "RECURATION_NEW_EVIDENCE"
   :cg/RecurationFrameworkChange "RECURATION_FRAMEWORK"
   :cg/RecurationErrorAffectingScoreorClassification "RECURATION_ERROR_SCORE_CLASS"
   :cg/RecurationDiscrepancyResolution "RECURATION_DISCREPANCY_RESOLUTION"})

(defn curation-reasons [assertion {:keys [major minor patch]}]
  (let [gci-reasons (rdf/ld-> assertion [:cg/curationReasons])]
    (if (seq gci-reasons)
      (mapv #(-> % rdf/->kw (genegraph-reason->website-reason "ADMIN_UPDATE_OTHER"))
            gci-reasons)
      (cond
        (and (= 1 major)
             (= 0 minor)) ["NEW_CURATION"]
        (= 0 minor) ["RECURATION_GENEGRAPH_CALCULATED"]
        :else ["ADMIN_UPDATE_GENEGRAPH_CALCULATED"]))))

(defn affiliation-number [curation-model]
  (if-let [approval (first (activity-query curation-model {:activity :cg/Evaluated}))]
    (->> (rdf/ld1-> approval [:cg/contributor])
         str
         (re-find #"\d+$")
         Integer/parseInt)))

(def proposition-query
  (rdf/create-query "
select ?x where {
 ?x a :cg/GeneDiseaseValidityProposition .
}"))

(defn proposition-id [m]
  (some-> (proposition-query m) first str))

;; explain why :change_code is always nil

(def term->website-code
  {:cg/classificationChange "CLASSIFICATION_CHANGE"
   :cg/moiChange "MOI_CHANGE"
   :cg/expertPanelChange "EXPERT_PANEL_CHANGE"
   :cg/SOPChange "SOP_CHANGE"
   :cg/newEvidence "EVIDENCE_ADDED_CHANGE"
   :cg/summaryChange "SUMMARY_TEXT_CHANGE"
   :cg/otherTextChange "OTHER_TEXT_CHANGE"
   :cg/diseaseIDChange "DISEASE_ID_CHANGE"})

(defn ->website-change [{:cg/keys [changeType currentVersion previousVersion] :as c}]
  {:change_code (get term->website-code changeType)
   :from previousVersion
   :to currentVersion})

(defn version-query [event]
  (let [q (rdf/create-query "
select ?x where { ?x a :cg/Statement . }")
        s (first (q (:gene-validity/model event)))]
    (if s
      (rdf/ld1-> s [:cg/version])
      "0.0.0")))

(defn event->base-event [event]
  (let [curation-model (:gene-validity/model event)
        assertion (first (assertion-query curation-model))
        version (:gene-validity/version event)
        version-str (:gene-validity/version-str event)
        snapshot-id (str (rdf/ld1-> assertion [:cg/GCISnapshot]))]
    {:schema_version "1.0"
     :event_subtype "CURATION"
     :workflow {:classification_date (activity-date curation-model
                                                    :cg/Evaluated)
                :publish_date (activity-date curation-model
                                             :cg/Submitted)
                :unpublish_date nil} 
     :source "GENEGRAPH"
     :activity "VALIDITY"
     :references {:source_uuid (str assertion)
                  :alternate_uuid (str assertion)
                  :dx_location "gene-validity-sepio"
                  :additional_properties
                  {:gci_snapshot_id (if (seq snapshot-id)
                                      (re-find #"[^/]+$" snapshot-id)
                                      nil)
                   :genegraph_proposition_id
                   (proposition-id curation-model)
                   :genegraph_version_of
                   (:gene-validity/gdm event)}}
     :affiliation {:affiliate_id (affiliation-number curation-model)}
     :changes (mapv ->website-change (:gene-validity/change-records event))
     :version {:display version-str
               :internal version-str
               :reasons (curation-reasons assertion version
                                          #_(:gene-validity/version event))

               :description (rdf/ld1-> assertion [:cg/curationReasonDescription])}}))

(defn unpublish-event->website-event [event]
  (if-let [previous-event (:gene-validity/previous-website-event event)]
    (-> previous-event
        (assoc-in [:workflow :unpublish_date]
                  (activity-date (:gene-validity/model event)
                                 :cg/Unpublisher))
        (assoc :event_type "UNPUBLISH"))
    nil))

(defn publish-event->website-event [event]
  (assoc (event->base-event event)
         :event_type "PUBLISH"))

(defn event->website-event [event]
  (if (= (:gene-validity/change-type event) :unpublish)
    (unpublish-event->website-event event)
    (publish-event->website-event event)))

(defn add-website-event [e]
  (assoc
   e
   :gene-validity/website-event
   (event->website-event e)))

(defn website-version-interceptor-fn [e]
  (assoc e :gene-validity/website-event (event->website-event e)))

(def website-version-interceptor
  (interceptor/interceptor
   {:name ::website-version-interceptor
    :enter (fn [e] (website-version-interceptor-fn e))}))

