(ns genegraph.transform.gene-validity.types
  (:require [genegraph.framework.id :as id]))

(id/register-type {:type :cg/GeneDiseaseValidityProposition
                   :defining-attributes
                   [:cg/subjectGene
                    :cg/objectCondition
                    :cg/modeOfInheritanceQualifier
                    :cg/predicate]})


(id/register-type {:type :cg/Container
                   :defining-attributes
                   [:cg/items]})
