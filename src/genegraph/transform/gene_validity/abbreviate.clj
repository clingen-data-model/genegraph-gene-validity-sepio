(ns genegraph.transform.gene-validity.abbreviate
  (:require [genegraph.framework.event :as event]
            [genegraph.framework.storage.rdf :as rdf]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.log :as log])
  (:import [net.openhft.hashing LongHashFunction LongTupleHashFunction] ; included with framework
           [com.google.common.primitives Longs]
           [java.nio ByteBuffer]
           [java.util Base64]))

;; this probably deserves to be part of the framework


(defn xxhash [s]
  (let [hash-tuple (.hashChars (LongTupleHashFunction/xx128) s)
        bb (ByteBuffer/allocate 16)]
    (.putLong bb (aget hash-tuple 0))
    (.putLong bb (aget hash-tuple 1))
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) (.array bb))))

(defn add-initial-attributes-fn [event]
  event
  #_(assoc event ::event/value-hash (xxhash (::event/value event))))

(def add-initial-attributes
  (interceptor/interceptor
   {:name ::add-initial-attributes
    :enter (fn [e] (add-initial-attributes-fn e))}))

(defn activity-type [event]
  (let [publish-action-query
        (rdf/create-query "prefix gci: <https://genegraph.clinicalgenome.org/r/>
                      select ?classification where {
                      ?classification gci:publishClassification true }" )]
    (if (seq (publish-action-query (:gene-validity/gci-model event)))
      :cg/Submitted
      :cg/Unpublished)))

(defn gdm-id [e]
  (or (get-in e [::event/data :resourceParent :gdm :PK])
      (get-in e [::event/data :resourceParent :gdm :uuid])
      (get-in e [::event/data :properties :resourceParent :gdm :uuid])))

(defn gdm-id->iri [gdm]
  (str "https://genegraph.clinicalgenome.org/r/" gdm))

#_(defn gdm [event]
  (let [q (rdf/create-query "select ?gdm where { ?gdm a :gg/gdm }")]
    (-> event :gene-validity/gci-model q first str)))

(defn gdm [event]
  (-> event gdm-id gdm-id->iri))

(defn add-gci-model-attributes-fn [event]
  (assoc event
         :cg/activityType (activity-type event)
         :gene-validity/gdm (gdm event)))

(def add-gci-model-attributes
  (interceptor/interceptor
   {:name ::add-gci-model-attributes
    :enter (fn [e] (add-gci-model-attributes-fn e))}))

(defn add-query-result [event key query]
  (let [q (rdf/create-query query)
        result (-> event :gene-validity/model q first str)]
    (assoc event key result)))

(def model-attribute-queries
  [[:gene-validity/gene
    "select ?g where { ?s a :cg/GeneValidityProposition ; :cg/subject ?g }"]
   [:gene-validity/disease
    "select ?g where { ?s a :cg/GeneValidityProposition ; :cg/object ?g }"]
   [:gene-validity/gcep
    "select ?gcep where 
{ ?s a :cg/Statement ; :cg/contributions ?contrib .
  ?contrib :cg/activityType ?activityType ;
  :cg/contributor ?gcep .
  values ?activityType {
  :cg/Submitted
  :cg/Unpublished
  }}"]
   [:gene-validity/classification
    "
select ?class where {
?s a :cg/Statement ;
 :cg/proposition ?prop ;
 :cg/classification ?class .
  ?prop a :cg/GeneValidityProposition . }"]])

(defn approval-date [model]
  (let [q (rdf/create-query "select ?activity where
{ ?activity :cg/activityType ?role }")]
       (some-> (q model {:role :cg/Evaluated})
               first
               (rdf/ld1-> [:cg/date]))))

(defn curation-reasons [model]
  (let [q (rdf/create-query "select ?reasons where
{ ?curation :cg/curationReasons ?reasons }")]
    (->> (q model) (map rdf/->kw) set)))

(defn add-model-attributes-fn [{:gene-validity/keys [model] :as event}]
  (let [activities (rdf/create-query "
select ?role where {
?s a :cg/Statement ;
 :cg/contributions ?contrib .
?contrib :cg/activityType ?role } ")]
    (assoc (reduce (fn [e [k q]]
                     (add-query-result e k q))
                   event
                   model-attribute-queries)
           :gene-validity/curation-reasons (curation-reasons model)
           :gene-validity/approval-date (approval-date model)
           :gene-validity/activity (->> (activities model)
                                        (map rdf/->kw)
                                        set))))

(def add-model-attributes
  (interceptor/interceptor
   {:name ::add-model-attributes
    :enter (fn [e] (add-model-attributes-fn e))}))

(def abbreviated-keys
  [::event/offset
   ::event/key
   ::event/kafka-topic
   ::event/value-hash
   :gene-validity/last-outcome
   :gene-validity/curation-reasons
   :gene-validity/activity
   :gene-validity/activity-type
   :gene-validity/change-type
   :gene-validity/change-records
   :gene-validity/approval-date
   :gene-validity/version
   #_:genegraph.transform.gene-validity.versioning/proposition-iri
   :gene-validity/validation
   ::event/iri
   :gene-validity/passed-tests
   :gene-validity/failed-tests
   :gene-validity/valid
   :gene-validity/gdm
   :gene-validity/gene
   :gene-validity/disease
   :gene-validity/gcep
   :gene-validity/classification
   #_:genegraph.transform.gene-validity.event-recorder/retrieved-from-store])

(defn abbreviate [event]
  (select-keys event abbreviated-keys))
