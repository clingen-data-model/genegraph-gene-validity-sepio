(ns genegraph.transform.gene-validity.types
  (:require [genegraph.framework.id :as id]))

(id/register-type {:type :cg/GeneValidityProposition
                   :defining-attributes
                   [:cg/subjectGene
                    :cg/objectCondition
                    :cg/qualifierModeOfInheritance
                    :cg/predicate]})


(id/register-type {:type :cg/Container
                   :defining-attributes
                   [:cg/items]})
