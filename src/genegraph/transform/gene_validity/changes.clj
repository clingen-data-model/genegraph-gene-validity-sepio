(ns genegraph.transform.gene-validity.changes
  (:require [genegraph.framework.event :as event]
            [genegraph.framework.storage.rdf :as rdf]
            [io.pedestal.interceptor :as interceptor]))

(defn as-query [query]
  (if (string? query) (rdf/create-query query) query))

(defn new-evidence-change-record [old-model new-model]
  (let [q (rdf/create-query "
select ?o where {
 ?e :dc/source ?o . } ")
        old-evidence (->> (q old-model) (map str) set)
        new-evidence (->> (q new-model) (map str) set)]
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

(defn no-change? [event prior-event]
  (rdf/is-isomorphic? (:gene-validity/model event)
                      (:gene-validity/model prior-event)))

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

#_(defn change-record->statements
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

#_(defn change-records->model [assertion change-records]
  (try
    (->> change-records
         (mapcat #(change-record->statements % assertion))
         (into [])
         rdf/statements->model)
    (catch Exception e
      (tap> {:change-records change-records
             :exception e})
      (rdf/statements->model []))))

(defn add-changes-fn [{:gene-validity/keys [previous-model model activity] :as event}]
  (if (and previous-model (get activity :cg/Submitted))
    (assoc event
           :gene-validity/change-records (change-records
                                          (:gene-validity/previous-model event)
                                          (:gene-validity/model event)))
    event))


(def add-changes
  (interceptor/interceptor
   {:name ::add-changes
    :enter (fn [e] (add-changes-fn e))}))
