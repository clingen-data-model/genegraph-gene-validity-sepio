(ns genegraph.framework.storage.rocksdb-mixed-key-test
  "Tests for RocksDB range scans with sequence keys that mix hash-based
  elements (keywords, strings) and Long elements.

  Hash-based elements (keyword, string) encode to 16 bytes via XX3-128.
  Long elements encode to 8 bytes (big-endian) to preserve ordering.

  The concern: prefix-range-end always treats the last 8 bytes of the
  supplied key as a Long, increments it, and uses that as the exclusive
  upper bound. When the prefix is entirely hash-based (e.g. [:kw1 :kw2],
  32 bytes), those last 8 bytes are the tail of a hash, not an intentional
  Long — does the resulting range still cover keys whose suffix is an
  actual Long (e.g. [:kw1 :kw2 offset])?"
  (:require [genegraph.framework.storage.rocksdb :as rocksdb]
            [genegraph.framework.storage :as storage]
            [clojure.test :refer [deftest testing is]])
  (:import [java.io File]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn temp-path []
  (str (System/getProperty "java.io.tmpdir")
       "/rocksdb-mixed-key-test-" (random-uuid)))

(defmacro with-db
  "Open a fresh RocksDB at a unique temp path, bind it to `sym`,
  execute `body`, then close and destroy the database."
  [[sym] & body]
  `(let [path# (temp-path)]
     (try
       (with-open [~sym (rocksdb/open path#)]
         ~@body)
       (finally
         (rocksdb/destroy path#)))))

;; ---------------------------------------------------------------------------
;; Scan: [keyword long] keys read back via keyword prefix
;;
;; Pattern from gene_validity.clj:
;;   write key: [:events :gene-validity-complete offset]    ; offset = Long
;;   scan prefix: [:events :gene-validity-complete]
;; ---------------------------------------------------------------------------

(deftest scan-two-hash-one-long-test
  (testing "all [kw1 kw2 long] entries are found when scanning by [kw1 kw2] prefix"
    (with-db [db]
      (doseq [offset [0 1 100 999 Long/MAX_VALUE]]
        (storage/write db [:events :gene-validity-complete offset] {:offset offset}))
      ;; Write a decoy under a different prefix — must not appear in results
      (storage/write db [:events :other-topic 0] :decoy)
      (let [results (rocksdb/range-get db {:prefix [:events :gene-validity-complete]})]
        (is (= 5 (count results))
            "all five entries should be returned")
        (is (every? map? results)
            "each returned value should be the map written")
        (is (= (set (map :offset results)) #{0 1 100 999 Long/MAX_VALUE})
            "the full set of offsets should be present"))))

  (testing "results are returned in ascending Long (offset) order"
    (with-db [db]
      ;; Write in non-sequential order to confirm RocksDB ordering, not insertion order
      (doseq [offset [500 1 999 0 100]]
        (storage/write db [:events :gene-validity-complete offset] {:offset offset}))
      (let [results (rocksdb/range-get db {:prefix [:events :gene-validity-complete]})]
        (is (= [0 1 100 500 999] (mapv :offset results))
            "entries should come back ordered by the Long offset value"))))

  (testing "prefix scan returns empty when no matching keys exist"
    (with-db [db]
      (storage/write db [:events :other-topic 42] :other)
      (let [results (rocksdb/range-get db {:prefix [:events :gene-validity-complete]})]
        (is (= [] results))))))

;; ---------------------------------------------------------------------------
;; Scan: [keyword keyword long long] keys read back via two-keyword prefix
;; and via three-part [keyword keyword long] prefix.
;;
;; Pattern from event_recorder.clj:
;;   write key: [:transforms data-key offset version]   ; offset, version = Long
;;   scan prefix: [:transforms data-key]                ; hash-only prefix
;;   point-read prefix: [:transforms data-key offset]  ; hash + Long prefix
;; ---------------------------------------------------------------------------

(deftest scan-two-hash-two-long-test
  (testing "all [:transforms kw offset version] entries found via [:transforms kw] prefix"
    (with-db [db]
      (doseq [[offset version] [[0 1] [0 2] [1 1] [100 3]]]
        (storage/write db [:transforms :gene-validity/model offset version]
                       {:offset offset :version version}))
      ;; Decoy under a different data-key
      (storage/write db [:transforms :gene-validity/other 0 1] :decoy)
      (let [results (rocksdb/range-get db {:prefix [:transforms :gene-validity/model]})]
        (is (= 4 (count results)))
        (is (every? map? results)))))

  (testing "scanning by [:transforms kw offset] returns only entries for that offset"
    (with-db [db]
      (doseq [[offset version] [[10 1] [10 2] [20 1]]]
        (storage/write db [:transforms :gene-validity/model offset version]
                       {:offset offset :version version}))
      (let [results (rocksdb/range-get db {:prefix [:transforms :gene-validity/model 10]})]
        (is (= 2 (count results))
            "only the two entries with offset=10 should match")
        (is (every? #(= 10 (:offset %)) results)))))

  (testing "ordering: [:transforms kw offset version] sorts by offset then version"
    (with-db [db]
      ;; Write in scrambled order
      (doseq [[offset version] [[5 2] [1 1] [5 1] [1 2]]]
        (storage/write db [:transforms :gene-validity/model offset version]
                       {:offset offset :version version}))
      (let [results (rocksdb/range-get db {:prefix [:transforms :gene-validity/model]})]
        (is (= [[1 1] [1 2] [5 1] [5 2]]
               (mapv (juxt :offset :version) results))
            "entries should sort by offset first, then version")))))

;; ---------------------------------------------------------------------------
;; range-delete: verify that range-delete with a hash-only prefix removes
;; all mixed-type keys under that prefix and leaves other keys intact.
;; ---------------------------------------------------------------------------

(deftest range-delete-mixed-key-test
  (testing "range-delete on hash-only prefix removes all [kw1 kw2 long] entries"
    (with-db [db]
      (doseq [offset [0 1 100]]
        (storage/write db [:events :gene-validity-complete offset] {:offset offset}))
      (storage/write db [:events :other-topic 0] :keep-me)
      (storage/range-delete db [:events :gene-validity-complete])
      (is (= [] (rocksdb/range-get db {:prefix [:events :gene-validity-complete]}))
          "all deleted entries should be gone")
      (is (= :keep-me (storage/read db [:events :other-topic 0]))
          "entries under a different prefix should survive")))

  (testing "range-delete on [kw1 kw2 offset] prefix removes only entries for that offset"
    (with-db [db]
      (doseq [[offset version] [[10 1] [10 2] [20 1]]]
        (storage/write db [:transforms :gene-validity/model offset version]
                       {:offset offset :version version}))
      (storage/range-delete db [:transforms :gene-validity/model 10])
      (is (= [] (rocksdb/range-get db {:prefix [:transforms :gene-validity/model 10]}))
          "entries for offset 10 should be gone")
      (is (= [{:offset 20 :version 1}]
             (rocksdb/range-get db {:prefix [:transforms :gene-validity/model 20]}))
          "entries for offset 20 should still be present"))))

;; ---------------------------------------------------------------------------
;; Key encoding: verify byte lengths and that Longs sort correctly
;; within a mixed-type sequence.
;; ---------------------------------------------------------------------------

(deftest mixed-key-encoding-test
  (testing "[:kw long] encodes to exactly 24 bytes (16-byte hash + 8-byte Long)"
    (is (= 24 (alength (rocksdb/k->bytes [:events 42])))))

  (testing "[:kw1 :kw2 long] encodes to exactly 40 bytes"
    (is (= 40 (alength (rocksdb/k->bytes [:events :gene-validity-complete 42])))))

  (testing "[:kw1 :kw2 long1 long2] encodes to exactly 48 bytes"
    (is (= 48 (alength (rocksdb/k->bytes [:transforms :gene-validity/model 10 1])))))

  (testing "Long suffix ordering is preserved within mixed keys: smaller Long -> smaller bytes"
    (let [k1 (rocksdb/k->bytes [:events :gene-validity-complete 1])
          k2 (rocksdb/k->bytes [:events :gene-validity-complete 2])
          k3 (rocksdb/k->bytes [:events :gene-validity-complete 100])]
      (is (neg? (java.util.Arrays/compare k1 k2)) "offset 1 < offset 2")
      (is (neg? (java.util.Arrays/compare k2 k3)) "offset 2 < offset 100")))

  (testing "keys with different keyword prefixes differ in their first bytes"
    (let [ka (rocksdb/k->bytes [:events :gene-validity-complete 0])
          kb (rocksdb/k->bytes [:events :other-topic 0])]
      (is (not (java.util.Arrays/equals ka kb))
          "different second element should yield different overall key bytes"))))
