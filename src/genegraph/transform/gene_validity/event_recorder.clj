(ns genegraph.transform.gene-validity.event-recorder
  (:require [genegraph.transform.gene-validity.abbreviate :as abbrev]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.event :as event]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.log :as log]
            [clojure.set :as set]))

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

(defn saved-data [{::event/keys [offset]
                   :keys [versions]
                   :as event}
                  data-key]
  (let [result (storage/read
                (get-in event [::storage/storage :gene-validity-version-store])
                [:transforms data-key offset (get versions data-key)])]
    (when-not (= ::storage/miss result)
      result)))

(defn retrieve-saved-data
  "Retrieve data stored from processing, unless specifically requested not to,
  as defined by the :force-reload key."
  [event data-keys]
  (if (and (::event/offset event)
           (:versions event)
           (get-in event [::storage/storage :gene-validity-version-store]))
    (do
      (reduce
       (fn [e k]
         (-> (assoc e k (saved-data e k))))
       event
       (set/difference (set data-keys)
                       (set (:force-reload event)))))
    event))

(defn mark-retrieved-data [event data-keys]
  (assoc event
         ::retrieved-from-store
         (->> (select-keys event data-keys)
              (filter val)
              (map key)
              set)))

(defn add-saved-data-fn [event data-keys]
  (-> event
      (retrieve-saved-data data-keys)
      (mark-retrieved-data data-keys)))

(defn store-generated-data-fn
  "Store data generated through processing. Specifically exclude data retrieved
  from storage rather than being processed."
  [event data-keys]
  (reduce
   (fn [e k]
     (event/store e
                  :gene-validity-version-store
                  [:transforms
                   k
                   (::event/offset e)
                   (get (:versions e) k)]
                  (get event k)))
   event
   (set/difference (set data-keys)
                   (::retrieved-from-store event))))

;; come back here after reliable method of getting GDM
;; ID
(defn store-event-outcome [event]
  (if-let [gdm (:gene-validity/gdm event)]
    (event/store event
                 :gene-validity-version-store
                 [:outcomes
                  gdm
                  (::event/offset event)
                  (get-in event [:versions :gene-validity/model])]
                 (abbrev/abbreviate event))
    (do
      (log/warn :fn :store-event-outcome
                :error :no-gdm)
      event)))

(defn store-results [event data-keys]
  (-> event
      (store-generated-data-fn data-keys)
      store-event-outcome))

(defn add-saved-data [data-keys]
  (interceptor/interceptor
   {:name ::add-saved-data
    :enter (fn [e] (add-saved-data-fn e data-keys))
    :leave (fn [e] (store-results e data-keys))}))




