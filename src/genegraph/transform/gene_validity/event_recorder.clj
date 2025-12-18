(ns genegraph.transform.gene-validity.event-recorder
  (:require [genegraph.framework.storage :as storage]
            [genegraph.framework.event :as event]
            [io.pedestal.interceptor :as interceptor]))

(defn enter-record-event-fn [event]
  event)

(defn record [event]
  (select-keys event
               [:gene-validity/gci-model
                :gene-validity/model
                :gene-validity/change-records
                ::event/data
                ::event/format
                ::event/offset
                ::event/timestamp
                ::event/source
                ::event/value]))

(defn leave-record-event-fn [{::event/keys [offset kafka-topic] :as event}]
  (if (and offset kafka-topic)
    (event/store event
                 :gene-validity-version-store
                 [::event kafka-topic offset]
                 (record event))
    event))

(defn error-record-event-fn [event]
  (println "error")
  event)

(def record-event
  (interceptor/interceptor
   {:name ::record-event
    :enter (fn [e] (enter-record-event-fn e))
    :leave (fn [e] (leave-record-event-fn e))
    :error (fn [e] (error-record-event-fn e))}))


