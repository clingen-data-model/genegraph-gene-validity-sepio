(ns genegraph.transform.gene-validity.proposition
  (:require [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.id :as id]
            [genegraph.framework.event :as event]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.log :as log]))

(id/register-type {:type :cg/GeneDiseaseValidityProposition
                   :defining-attributes
                   [:cg/subjectGene :cg/objectCondition :cg/modeOfInheritanceQualifier :cg/predicate]})

(def prop-query
  (rdf/create-query "select ?x where { ?x a :cg/GeneDiseaseValidityProposition }"))

(defn proposition-id [model]
  (when-let [prop (first (prop-query model))]
    (->{:type :cg/GeneDiseaseValidityProposition
        :cg/subjectGene (str (rdf/ld1-> prop [:cg/subjectGene]))
        :cg/objectCondition (str (rdf/ld1-> prop [:cg/objectCondition]))
        :cg/modeOfInheritanceQualifier (str (rdf/ld1-> prop [:cg/modeOfInheritanceQualifier]))
        :cg/predicate (str (rdf/ld1-> prop [:cg/predicate]))}
       id/iri
       rdf/resource)))

(def rename-proposition-query
  (rdf/create-query "
construct {
  ?s ?p ?o .
  ?propIRI ?p1 ?o1 .
  ?s2 ?p2 ?propIRI .
} where {
 { ?s ?p ?o .
   FILTER NOT EXISTS { ?s a :cg/GeneDiseaseValidityProposition . }
   FILTER NOT EXISTS { ?o a :cg/GeneDiseaseValidityProposition . }
 }
 union
 {
  ?s1 a :cg/GeneDiseaseValidityProposition .
  ?s1 ?p1 ?o1 .
  ?s2 ?p2 ?s1 .
 }
}
"))

(defn rename-proposition [model]
  (if-let [id (proposition-id model)]
    (rename-proposition-query
     model
     {:propIRI (rdf/resource (proposition-id model))})
    model))

(def rename-proposition-interceptor
  (interceptor/interceptor
   {:name ::rename-proposition
    :enter (fn [e]
             (let [model (:gene-validity/model e)]
               (assoc e :gene-validity/model (rename-proposition model))))}))
