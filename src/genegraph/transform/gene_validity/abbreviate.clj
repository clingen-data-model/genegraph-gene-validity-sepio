(ns genegraph.transform.gene-validity.abbreviate
  (:require [genegraph.framework.event :as event]
            [genegraph.framework.storage.rdf :as rdf]
            [io.pedestal.interceptor :as interceptor])
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
  (assoc event ::event/value-hash (xxhash (::event/value event))))

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

(defn add-gci-model-attributes-fn [event]
  (assoc event :cg/activityType (activity-type event)))

(def add-gci-model-attributes
  (interceptor/interceptor
   {:name ::add-gci-model-attributes
    :enter (fn [e] (add-gci-model-attributes-fn e))}))

(defn add-model-attributes-fn [event]
  event)

(def add-model-attributes
  (interceptor/interceptor
   {:name ::add-model-attributes
    :enter (fn [e] (add-gci-model-attributes-fn e))}))

(def abbreviated-keys
  [::event/offset
   ::event/key
   ::event/kafka-topic
   ::event/value-hash
   :gene-validity/change-type
   :gene-validity/approval-date
   :gene-validity/version
   :genegraph.transform.gene-validity.versioning/proposition-iri
   :gene-validity/validation
   ::event/iri
   :gene-validity/passed-tests
   :gene-validity/failed-tests
   :gene-validity/valid])

(defn abbreviate [event]
  (select-keys event abbreviated-keys))
