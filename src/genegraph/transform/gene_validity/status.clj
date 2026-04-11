(ns genegraph.transform.gene-validity.status
  (:require [genegraph.transform.gene-validity.snapshot :as snapshot]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.event :as event]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.http.response :as response]
            [charred.api :as charred])
  (:import [java.time Instant]))

(defn unique-published-records [outcomes]
  (->> outcomes
       (reduce (fn [m e] (assoc m (:gene-validity/gdm e) e)) {})
       vals
       (filter #(get (:gene-validity/activity %) :cg/Submitted))
       count))

(defn last-record-time [outcomes]
  (some-> (last outcomes)
          ::event/timestamp
          Instant/ofEpochMilli
          str))

(defn status-data [db]
  (let [outcomes (storage/scan db [:outcomes])]
    {:unique-published-records (unique-published-records outcomes)
     :records-processed (count outcomes)
     :last-record-time (last-record-time outcomes)}))

(defn report-status-fn [request]
  (let [db (get-in request [::storage/storage :gene-validity-version-store])]
    (response/respond-with
     request
     200
     (charred/write-json-str (status-data db)))))

(def report-status
  (interceptor/interceptor
   {:name ::report-status
    :enter (fn [e] (report-status-fn e))}))

