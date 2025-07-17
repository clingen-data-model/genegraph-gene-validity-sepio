(ns genegraph.transform.gene-validity.sepio-model
  (:require [clojure.edn :as edn]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.event :as event]
            [genegraph.transform.gene-validity.names]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [io.pedestal.interceptor :as interceptor])
  (:import [java.time Instant]))

(def construct-params
  {:gcibase "https://genegraph.clinicalgenome.org/r/gci/"
   :legacy_report_base "https://genegraph.clinicalgenome.org/r/gci/legacy-report_"
   :arbase "http://reg.genome.network/allele/"
   :cvbase "https://www.ncbi.nlm.nih.gov/clinvar/variation/"
   :scvbase "https://identifiers.org/clinvar.submission:"
   :pmbase "https://pubmed.ncbi.nlm.nih.gov/"
   :affbase "https://genegraph.clinicalgenome.org/r/agent/"})

(def gdm-sepio-relationships (rdf/read-rdf (str (io/resource "genegraph/transform/gene_validity/sepio_model/gdm_sepio_relationships.ttl")) :turtle))

(rdf/declare-query construct-proposition ;done
                   construct-evidence-level-assertion ;done
                   construct-experimental-evidence-assertions ;done
                   construct-genetic-evidence-assertion
                   construct-ad-variant-assertions
                   construct-ar-variant-assertions
                   construct-cc-and-seg-assertions
                   construct-proband-score
                   construct-model-systems-evidence
                   construct-functional-alteration-evidence
                   construct-functional-evidence
                   construct-rescue-evidence
                   construct-case-control-evidence
                   construct-proband-segregation-evidence
                   construct-family-segregation-evidence
                   construct-evidence-connections
                   construct-alleles
                   construct-articles
                   construct-earliest-articles
                   construct-secondary-contributions
                   construct-variant-score
                   construct-ar-variant-score
                   construct-unscoreable-evidence
                   unlink-variant-scores-when-proband-scores-exist
                   unlink-segregations-when-no-proband-and-lod-scores
                   add-legacy-website-id
                   unpublish-evidence-level-assertion
                   prune-empty-evidence-lines
                   prune-empty-evidence-ids
                   construct-scv)

(def has-affiliation-query
  "Query that returns a curations full affiliation IRI as a Resource.
  Expects affiliations to have been preprocessed to IRIs from string form."
  (rdf/create-query "prefix gci: <https://genegraph.clinicalgenome.org/r/gci/>
                   select ?affiliationIRI where {
                     ?proposition a gci:gdm .
                     OPTIONAL {
                      ?proposition gci:affiliation ?gdmAffiliationIRI .
                     }
                     OPTIONAL {
                      ?classification a gci:provisionalClassification .
                      ?classification gci:affiliation ?classificationAffiliationIRI .
                      ?classification gci:last_modified ?date .
                     }
                     BIND(COALESCE(?classificationAffiliationIRI, ?gdmAffiliationIRI) AS ?affiliationIRI) }
                     ORDER BY DESC(?date) LIMIT 1"))

(def is-publish-action-query
  (rdf/create-query "prefix gci: <https://genegraph.clinicalgenome.org/r/gci/>
                      select ?classification where {
                      ?classification gci:publishClassification true }" ))

(def initial-construct-queries
  [construct-proposition
   construct-evidence-level-assertion
   construct-experimental-evidence-assertions
   construct-genetic-evidence-assertion
   construct-ad-variant-assertions
   construct-ar-variant-assertions
   construct-cc-and-seg-assertions
   construct-proband-score
   construct-model-systems-evidence
   construct-functional-alteration-evidence
   construct-functional-evidence
   construct-rescue-evidence
   construct-case-control-evidence
   construct-proband-segregation-evidence
   construct-family-segregation-evidence
   construct-alleles
   construct-articles
   construct-earliest-articles
   construct-secondary-contributions
   construct-variant-score
   construct-ar-variant-score
   construct-unscoreable-evidence
   construct-scv
   unpublish-evidence-level-assertion])


(def approval-activity-query
  (rdf/create-query "select ?activity where
 { ?activity :bfo/realizes  :cg/Approver }"))

(def assertion-query
  (rdf/create-query
   "select ?assertion where
 { ?assertion a :cg/EvidenceStrengthAssertion }"))

(defn legacy-website-id
  "The website uses a version of the assertion ID that incorporates
  the approval date. Annotate the curation with this ID to retain
  backward compatibility with the legacy schema."
  [model]
  (let [approval-date (some-> (approval-activity-query model)
                              first
                              (rdf/ld1-> [:cg/date])
                              (s/replace #":" ""))
        
        [_
         assertion-base
         assertion-id]
        (some->> (assertion-query model)                                
                 first
                 str
                 (re-find #"^(.*/)([a-z0-9-]*)$"))]
    (rdf/resource (str assertion-base "assertion_" assertion-id "-" approval-date))))

(defn publish-or-unpublish-role [event]
  (let [res
        (if (seq (is-publish-action-query (:gene-validity/gci-model event)))
          :cg/Publisher
          :cg/Unpublisher)]
    res))



(defn params-for-construct [event]
  (assoc construct-params
         :affiliation
         (first (has-affiliation-query (:gene-validity/gci-model event)))
         :publishRole
         (publish-or-unpublish-role event)
         :publishTime
         (or (some-> event ::event/timestamp Instant/ofEpochMilli str)
             "2020-05-01")))


(def gdm-query
  (rdf/create-query "
select ?gdm where 
{ ?gdm a <https://genegraph.clinicalgenome.org/r/gci/gdm> } "))

(defn unpublish-action [gci-data params]
  (let [gdm-id (first (gdm-query gci-data))
        unpublish-contribution-iri (rdf/resource
                                    (str gdm-id
                                         "_unpublish_"
                                         (:publishTime params)))
        affiliation (first (has-affiliation-query gci-data))]
    (rdf/statements->model
     [[gdm-id :rdf/type :cg/EvidenceStrengthAssertion]
      [gdm-id :cg/contributions unpublish-contribution-iri]
      [unpublish-contribution-iri :cg/role (:publishRole params)]
      [unpublish-contribution-iri :dc/date (:publishTime params)]
      [unpublish-contribution-iri :cg/agent affiliation]
      [unpublish-contribution-iri :cg/gdm gdm-id]])))

(def proband-score-cap-query
  (rdf/create-query "select ?x where 
{ ?x :cg/specifiedBy :cg/GeneValidityMaximumProbandScoreCriteria }"))

(defn add-proband-scores
  "Return model contributing the evidence line scores for proband scores
  when needed in SOPv8 + autosomal recessive variants. May need a mechanism
  to feed a new cap in, should that change."
  [model]
  (let [proband-evidence-lines (proband-score-cap-query model)]
    (rdf/union
     model
     (rdf/statements->model
      (map
       #(vector 
         %
         :cg/strengthScore
         (min 3                ; current cap on sop v8+ proband scores
              (reduce
               + 
               (rdf/ld-> % [:cg/evidence
                            :cg/strengthScore]))))
       proband-evidence-lines)))))

(defn publish-action [gci-data params]
  (let [gci-model (rdf/union gci-data gdm-sepio-relationships)
        unlinked-model (apply
                        rdf/union
                        (map #(% gci-model params)
                             initial-construct-queries))
        linked-model (rdf/union unlinked-model
                                (construct-evidence-connections
                                 (rdf/union
                                  unlinked-model
                                  gdm-sepio-relationships))
                                (add-legacy-website-id
                                 unlinked-model
                                 {:legacy_id (legacy-website-id unlinked-model)}))]
    (-> linked-model
        add-proband-scores
        unlink-variant-scores-when-proband-scores-exist
        unlink-segregations-when-no-proband-and-lod-scores
        prune-empty-evidence-lines
        prune-empty-evidence-ids)))


(defn gci-data->sepio-model [gci-data params]
  (if (= :cg/Publisher (:publishRole params))
    (publish-action gci-data params)
    (unpublish-action gci-data params)))

#_(defn gci-data->sepio-model [gci-data params]
  (publish-action gci-data params))

(defn add-model-fn [event]
  (assoc event
         :gene-validity/model
         (gci-data->sepio-model (:gene-validity/gci-model event)
                                (params-for-construct event))))

(def add-model
  (interceptor/interceptor
   {:name ::add-model
    :enter (fn [e] (add-model-fn e))}))
