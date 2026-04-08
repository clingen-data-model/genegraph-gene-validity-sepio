(ns genegraph.transform.gene-validity.snapshot
  (:require [clojure.java.io :as io]
            [clojure.string :as s]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.event :as event]
            [genegraph.framework.storage.rdf.names :as names]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage.rocksdb :as rocksdb]
            [genegraph.framework.storage :as storage]
            [io.pedestal.log :as log])
  (:import [java.io BufferedOutputStream]
           [org.apache.commons.compress.archivers.tar
            TarArchiveEntry TarArchiveOutputStream]
           [org.apache.commons.compress.archivers ArchiveEntry]
           [org.apache.commons.compress.compressors.gzip
            GzipCompressorOutputStream]
           [org.rocksdb RocksDB]))


(do
  (defn record->filename [record extension]
    (let [{:keys [major minor patch]} (:gene-validity/version record)]
      (str
       (s/replace (:gene-validity/gdm record)
                  #"https://genegraph.clinicalgenome.org/r/"
                  "")
       "--" major "." minor "." patch "." extension)))  
  
  (defn latest-records [db]
    (->> (storage/scan db [:outcomes])
         (reduce (fn [m e] (assoc m (:gene-validity/gdm e) e)) {})
         vals
         (filter #(get (:gene-validity/activity %) :cg/Submitted))))

  (defn record->serialized-model
    "Use the data serialization interface in genegraph.framework.event
     to serialize the RDF data as triples."
    [r db]
    (-> {::event/data (storage/read db
                                    [:transforms
                                     :gene-validity/model
                                     (::event/offset r)
                                     (get-in r [:versions :gene-validity/model])])
         ::event/format ::rdf/n-triples}
        event/serialize
        ::event/value))

  (defn record->json-ld
    "JSON-LD stored as serialized string by default--no need to process
  further"
    [r db]
    (storage/read db
                  [:transforms
                   :gene-validity/json-ld
                   (::event/offset r)
                   (get-in r [:versions :gene-validity/json-ld])]))

  (defn handle->output-stream [storage-handle]
    (-> storage-handle
        storage/as-handle
        io/output-stream
        BufferedOutputStream.
        GzipCompressorOutputStream.
        TarArchiveOutputStream.))

  (defn records->archive [storage-handle records]
    (with-open [os (handle->output-stream storage-handle)]
      (run! (fn [{:keys [data-bytes filename]}]
              (.putArchiveEntry
               os
               (doto (TarArchiveEntry. filename)
                 (.setSize (alength data-bytes))))
              (.write os data-bytes)
              (.closeArchiveEntry os))
            records)))

  (defn write-nt [db handle records]
    (->> records
         (mapv (fn [r] {:data-bytes (.getBytes (record->serialized-model r db))
                        :filename (record->filename r "nt")}))
         (records->archive handle)))

  (defn write-json [db handle records]
    (->> records
         (mapv (fn [r] {:data-bytes (.getBytes (record->json-ld r db))
                        :filename (record->filename r "json")}))
         (records->archive handle)))

  (let [db @(get-in test-harness/test-app [:storage
                                           :gene-validity-version-store
                                           :instance])
        handle-base  {:type :file
                      :base "data/public/"}]
    (->> (latest-records db)
         (take 1)
         #_(mapv #(record->serialized-model %  db))
         #_(mapv #(record->json-ld %  db))
         #_(mapv #(record->filename % ".nt"))
         #_(write-nt db (assoc handle-base :path "gene-validity-nt.tar.gz"))
         (write-json db (assoc handle-base :path "gene-validity-json.tar.gz")))))

(comment
  

  
  )
