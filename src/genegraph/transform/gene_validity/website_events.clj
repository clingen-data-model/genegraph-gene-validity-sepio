(ns genegraph.transform.gene-validity.website-events
  "Code to populate 'all curation events' topic for website."
  (:require [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage :as storage]))

(def sample-message
  {:schema_version "1.0" ;req-fixed
   :event_type "PUBLISH | UNPUBLISH | RETRACT | RETIRE" ;req
   :event_subtype "CURATION | TEST" ;req 
   :workflow {:classification_date "ISO8601" ;req 
              :publish_date "ISO8601" ;req -- publish 
              :unpublish_date "ISO8601" ;req -- unpublish
              } 
   :source "GCI | VCI | DCI | ACI | EREPO | GENEGRAPH"  ;req 
   :activity "ACTIONABILITY | DOSAGE | VALIDITY | VARIANT" ;req 
   :references {:source_uuid "string" ;req ?
                :dx_location "string" ;req -- gene-validity-sepio
                :alternate_uuid "string"  ;; ??? leave this one out 
                :additional_properties [{:key "value"}] ; also leave out
                }
   :affiliation {:affiliate_id "number" ; req , must be 40series integer 
                 :affiliate_name "string" ; may leave out 
                 }
   :version {:display "decimal string" ; req
             :internal "decimal string" ; req -- same as display
             :reasons "array" ; req
             :description "string" ; req -- think in reasons 
             :additional_properties [{:key "value"}] ; probably leave out 
             :changes [{:change_code "CHANGE_CODE" ; not req per description, will leave out for now
                        :attribute "string"
                        :from "string"
                        :to "string"}]
             :notes {:public "string"  ; also optional I assume 
                     :private "string"}}
   :urls {:source "https://curationn.clinicalgenome.org/curation-central/6e14e6fb-aef7-4c97-9e06-a60e6ffcf64b/"
          :url {:preferred "url"}}})

(def trimmed-message
  {:schema_version "1.0"
   :event_type "PUBLISH | UNPUBLISH" ;req
   :event_subtype "CURATION" ;req 
   :workflow {:classification_date "ISO8601" ;req 
              :publish_date "ISO8601" ;req -- publish 
              :unpublish_date "ISO8601" ;req -- unpublish
              } 
   :source "GENEGRAPH"  ;req 
   :activity "VALIDITY" ;req 
   :references {:source_uuid "string" ; Genegraph URL for 'is-version-of'
                :dx_location "string" ; gene-validity-sepio
                }
   :affiliation {:affiliate_id "number" ; req , must be 40series integer 
                 :affiliate_name "string" ; may leave out 
                 }
   :version {:display "decimal string" ; req
             :internal "decimal string" ; req -- same as display
             :reasons "array" ; req
             :description "string" ; req -- think in reasons 
             :additional_properties [{:key "value"}] ; probably leave out 
}})

;; Comments from call 2-13

;; Unpublish events need a target ID, double check event type
;; Include GCI GDM id and Snapshot ID in addiitonal properties.
;; Include Genegraph versioned and unversioned identifier.

(def activity-query
  (rdf/create-query "
select ?act where {
?act :cg/role ?activity .
}
"))

(defn activity-date [curation-model activity]
  (if-let [activity (first (activity-query curation-model
                                          {:activity activity}))]
    (rdf/ld1-> activity [:cg/date])))



(do

  (defn version-string [version-map]
    (str (:major version-map "0")
         "."
         (:minor version-map "0")
         "."
         (:patch version-map "0")))
  
  (def assertion-query
    (rdf/create-query "select ?x where { ?x a :cg/EvidenceStrengthAssertion }"))

  (def genegraph-reason->website-reason
    {:cg/NewCuration "NEW_CURATION"
     :cg/DiseaseNameUpdate "ADMIN_UPDATE_DISEASE_NAME"
     :cg/ErrorClarification "ADMIN_UPDATE_OTHER"
     :cg/RecurationCommunityRequest "RECURATION_COMMUNITY_REQUEST"
     :cg/RecurationTiming "RECURATION_TIMING"
     :cg/RecurationNewEvidence "RECURATION_NEW_EVIDENCE"
     :cg/RecurationFrameworkChange "RECURATION_FRAMEWORK"
     :cg/RecurationErrorAffectingScoreorClassification "RECURATION_ERROR_SCORE_CLASS"})

  (defn curation-reasons [assertion version]
    (let [gci-reasons (rdf/ld-> assertion [:cg/curationReasons])]
      (if (seq gci-reasons)
        (mapv #(-> % rdf/->kw (genegraph-reason->website-reason "ADMIN_UPDATE_OTHER"))
              gci-reasons)
        (cond
          (= 1 (:major version)) "NEW_CURATION"
          (= 0 (:minor version)) "RECURATION_GENEGRAPH_CALCULATED"
          :else "ADMIN_UPDATE_GENEGRAPH_CALCULATED" ))))

  (defn affiliation-number [curation-model]
    (if-let [approval (first (activity-query curation-model {:activity :cg/Approver}))]
      #_(re-find #"\d+" (str approval))
      (->> (rdf/ld1-> approval [:cg/agent])
           str
           (re-find #"\d+$")
           Integer/parseInt
           (+ 30000))))

  (def proposition-query
    (rdf/create-query "
select ?x where {
 ?x a :cg/GeneValidityProposition .
}"))

  (defn get-previous-version [event db]
    (storage/read
     db
     (-> event
         :gene-validity/model
         proposition-query
         first
         str)))

  (defn key-for-version [event]
    (let [assertion-iri (some-> event
                                :gene-validity/model
                                assertion-query
                                first
                                str)
          {:keys [major minor]} (:gene-validity/version event)]
      (str assertion-iri "v" major "." minor)))
  
  ;;
  (defn get-previous-version-key [event db]
    (let [previous-event (storage/read
                          db
                          (-> event
                              :gene-validity/model
                              proposition-query
                              first
                              str))
          assertion-iri (some-> previous-event
                                :gene-validity/model
                                assertion-query
                                first
                                str)
          {:keys [major minor]} (:gene-validity/version previous-event)]
      (str assertion-iri "v" major "." minor)))

  (defn publish-event->website-event [event])

  (defn event->website-event [event]
    (let [curation-model (:gene-validity/model event)
          assertion (first (assertion-query curation-model))
          unpublish-date (activity-date curation-model :cg/Unpublisher)
          version (version-string (:gene-validity/version event))
          snapshot-id (str (rdf/ld1-> assertion [:dc/isVersionOf]))]
      {:schema_version "1.0"
       :event_type (if unpublish-date "UNPUBLISH" "PUBLISH")
       :event_subtype "CURATION"
       :workflow {:classification_date (activity-date curation-model
                                                      :cg/Approver)
                  :publish_date (activity-date curation-model
                                               :cg/Publisher)
                  :unpublish_date unpublish-date} 
       :source "GENEGRAPH"
       :activity "VALIDITY"
       :references {:source_uuid snapshot-id
                    :alternate_uuid (str assertion)
                    :dx_location "gene-validity-sepio"
                    :additional_properties
                    {:gci_snapshot_id (if (seq snapshot-id)
                                        (subs snapshot-id 43)
                                        nil)}}
       :affiliation {:affiliate_id (affiliation-number curation-model)}
       :version {:display version
                 :internal version
                 :reasons (curation-reasons assertion
                                            (:gene-validity/version event))
                 :description (rdf/ld1-> assertion [:cg/curationReasonDescription])}}))


  (tap> (event->website-event unpublish)))


(defn add-website-event [e]
  (assoc
   e
   :gene-validity/website-event
   (event->website-event e)))


(comment
  (tap> (:gene-validity/change-type test-event))
  (def test-event
    (genegraph.framework.event.store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2025-03-24.edn.gz"]
      (->> (genegraph.framework.event.store/event-seq r)
           (take 1)
           (map #(-> %
                     genegraph.user/transform-curation
                     (dissoc :gene-validity/gci-model)
                     add-website-event))
           first)))

  (genegraph.framework.event.store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2025-01-06.edn.gz"]
    (->> (genegraph.framework.event.store/event-seq r)
         (take 1)
         (map #(-> %
                   genegraph.user/transform-curation
                   (dissoc :gene-validity/gci-model)
                   add-website-event))
         first
         tap>))

  (def recent-event
    (genegraph.framework.event.store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2025-01-06.edn.gz"]
      (->> (genegraph.framework.event.store/event-seq r)
           (take-last 1)
           (map #(-> %
                     genegraph.user/transform-curation
                     (dissoc :gene-validity/gci-model)
                     add-website-event))
           first)))

  (def recuration
    (genegraph.framework.event.store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2025-01-06.edn.gz"]
      (->> (genegraph.framework.event.store/event-seq r)
           (take-last 50)
           (map #(-> %
                     genegraph.user/transform-curation
                     (dissoc :gene-validity/gci-model)
                     add-website-event))
           (remove #(-> %
                        :gene-validity/website-event
                        :version
                        :reasons
                        set
                        (clojure.set/intersection #{"NEW_CURATION"})
                        seq))
           (take 1)
           first)))

  (def unpublish
    (genegraph.framework.event.store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2025-03-24.edn.gz"]
      (->> (genegraph.framework.event.store/event-seq r)
           (take-last 50)
           (map #(-> %
                     genegraph.user/transform-curation
                     (dissoc :gene-validity/gci-model)
                     add-website-event))
           (filter #(get-in % [:gene-validity/website-event
                               :workflow
                               :unpublish_date]))
           (take 1)
           first)))

  (tap> (select-keys unpublish
                     [:gene-validity/version
                      :genegraph.framework.event/iri
                      :gene-validity/website-event]
                     ))
  (-> unpublish
      :gene-validity/model
      rdf/pp-model)

  (do
    (def proposition-query
      (rdf/create-query "
select ?x where {
 ?x a :cg/GeneValidityProposition .
}"))

    (defn get-previous-version-key [event db]
      (let [previous-event (storage/read
                            db
                            (-> event
                                :gene-validity/model
                                proposition-query
                                first
                                str))
            assertion-iri (some-> previous-event
                                  :gene-validity/model
                                  assertion-query
                                  first
                                  str)
            {:keys [major minor]} (:gene-validity/version previous-event)]
        (str assertion-iri "v" major "." minor)
))
    (let [db @(get-in genegraph.user/test-app [:storage :gene-validity-version-store :instance])]
      (tap> (get-previous-version-key unpublish db)))
)
  

  (-> recuration genegraph.user/transform-curation :gene-validity/model rdf/pp-model)

  (tap> (:genegraph.framework.event/data recuration))
  
  (rdf/pp-model recent-model)

  (with-open [w (clojure.java.io/writer "/Users/tristan/Desktop/curation-events-sample.ndjson")]
    (genegraph.framework.event.store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2025-03-24.edn.gz"]
      (->> (genegraph.framework.event.store/event-seq r)
           (take 5)
           (map #(-> %
                     genegraph.user/transform-curation
                     (dissoc :gene-validity/gci-model)
                     add-website-event
                     :gene-validity/website-event
                     clojure.data.json/write-str
                     (str "\n")))
           (run! #(.write w %)))))

  )
