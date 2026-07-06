(ns gcep_report
  (:require [test-harness :as harness]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.event :as event]
            [genegraph.framework.storage.rdf :as rdf]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [charred.api :as charred])
  (:import [java.time Instant LocalDateTime ZoneOffset LocalDate]))

(defn event->approval-ms [event]
  (-> event
      :gene-validity/approval-date
      Instant/parse
      .toEpochMilli))

(defn date->epoch [iso-date]
  (-> (LocalDateTime/parse iso-date)
      (.toInstant ZoneOffset/UTC)
      (.toEpochMilli)))

(defn gcep-map->csv [m]
  (->> m
       (filter #(or (:first-curation (val %)) (:gci-recuration (val %))))
       (map (fn [[k v]] [k (:first-curation v 0) (:gci-recuration v 0)]))
       (sort-by first)
       (into [["GCEP" "New Curations" "Recurations"]])))

(defn summary-records->gcep-map [group-key records]
  (-> (group-by group-key records)
      (dissoc nil)
      (update-keys gcep-labels)
      (update-vals (fn [v] (->> v (map :gene-validity/change-type) frequencies)))))

(defn events-in-interval [app start-date end-date]
  (let [start-epoch (date->epoch start-date)
        end-epoch (date->epoch end-date)
        store @(get-in app [:storage :gene-validity-version-store :instance])]
    (->> (storage/scan store [:outcomes])
         (filter #(and (< start-epoch (::event/timestamp %))
                       (< (::event/timestamp %) end-epoch)
                       #_(get (:gene-validity/activity %) :cg/Submitted))))))

(defn ep-id->iri [id]
  (str "https://genegraph.clinicalgenome.org/agent/" id))

(comment
  (def gcep-labels
    (with-open [r (io/reader "/Users/tristan/data/genegraph-base/affils.json")]
      (->> (charred/read-json r :key-fn keyword)
           (mapcat (fn [x] [[(ep-id->iri (:affiliation_id x))
                             (:affiliation_fullname x)]
                            [(ep-id->iri (get-in x [:subgroups :gcep :id]))
                             (get-in x [:subgroups :gcep :fullname])]
                            [(ep-id->iri (get-in x [:subgroups :vcep :id]))
                             (get-in x [:subgroups :vcep :fullname])]]))
           (into {}))))

  (with-open [r (io/reader "/Users/tristan/data/genegraph-base/affils.json")]
    (->> (charred/read-json r :key-fn keyword)
         first))

  (tap> gcep-labels)

  (def genes
    (with-open [r (io/reader "/Users/tristan/data/genegraph-base/hgnc.json")]
      (->> (get-in (charred/read-json r :key-fn keyword) [:response :docs])
           (map (fn [d] [(s/replace (:hgnc_id d) #"HGNC" "https://identifiers.org/hgnc")
                         (:symbol d)]))
           (into {}))))

  (tap> genes)

  (->> (events-in-interval harness/test-app "2026-04-01T00:00" "2026-07-01T00:00")
       #_(summary-records->gcep-map :gene-validity/gcep)
       (take 5)
       tap>)

  (with-open [w (io/writer "/Users/tristan/Desktop/gcep-primary.csv")]
    (->> (events-in-interval harness/test-app "2026-04-01T00:00" "2026-07-01T00:00")
         (summary-records->gcep-map :gene-validity/gcep) 
         gcep-map->csv
         (charred/write-csv w)))

  (with-open [w (io/writer "/Users/tristan/Desktop/gcep-secondary.csv")]
    (->> (events-in-interval harness/test-app "2026-04-01T00:00" "2026-07-01T00:00")
         (summary-records->gcep-map :gene-validity/secondary-contributor)
         #_tap>
         gcep-map->csv
         (charred/write-csv w)))
  
  (with-open [w (io/writer "/Users/tristan/Desktop/mito-curations.csv")]
    (->> (events-in-interval harness/test-app "2026-04-01T00:00" "2026-07-01T00:00")
         (filter #(= "https://genegraph.clinicalgenome.org/agent/40027" (:gene-validity/gcep %)))
         (map #(assoc %
                      :symbol (get genes (:gene-validity/gene %))
                      :publish-string (str (Instant/ofEpochMilli (::event/timestamp %)))))
         (map (fn [e] [(:symbol e)
                       (:publish-string e)
                       (:gene-validity/approval-date e)
                       (-> e :gene-validity/classification rdf/resource rdf/->kw)
                       (:gene-validity/curation-reasons e)]))
         (concat [["Gene" "Published" "Approved" "Reason"]])
         (charred/write-csv w)))

  (->> (events-in-interval harness/test-app "2026-01-01T00:00" "2026-04-01T00:00")
       (filter #(= "https://genegraph.clinicalgenome.org/agent/10027" (:gene-validity/gcep %)))
       (map :gene-validity/gdm)
       (map #(storage/scan @(get-in harness/test-app [:storage :gene-validity-version-store :instance])
                           [:outcomes %]))
       (map count)
       frequencies
       tap>)
  


  
  (tap> gcep-labels)
  

  )
