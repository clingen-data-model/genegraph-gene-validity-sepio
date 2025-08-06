(ns genegraph.gv-setup
  "Functions and procedures generally intended to be run from the REPL
  for the purpose of configuring and maintaining a genegraph gene validity
  instance."
  (:require [genegraph.transform.gene-validity :as gv]
            [genegraph.framework.app :as app]
            [genegraph.framework.protocol :as p]
            [genegraph.framework.event.store :as event-store]
            [genegraph.framework.event :as event]
            [genegraph.framework.kafka.admin :as kafka-admin]
            [genegraph.framework.storage :as storage]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.log :as log]
            [clojure.set :as set]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.java.shell :as shell :refer [sh]])
  (:import [java.time Instant OffsetDateTime Duration]
           [java.io PushbackReader]))

;; Step 1
;; Select the environment to configure

;; The gv/admin-env var specifies the platform to target and the
;; data exchange credentials to use. Set at least the :platform
;; and :dataexchange-genegraph keys for the correct environment
;; and kafka instance. This should be done in genegraph/gene_validity.clj

;; Step 2
;; Set up kafka topics, configure permissions

;; The kafka-admin/configure-kafka-for-app! function accepts an
;; (initialized) Genegraph app and creates the topics and necessary
;; permissions for those topics.

(comment
  (kafka-admin/configure-kafka-for-app! (p/init gv/gv-transformer-def))


  (p/reset (p/init gv/gv-transformer-def))
  ;; Delete all (or some) Genegraph-created topics
  ;; Use this to fix mistakes.
  (with-open [admin-client (kafka-admin/create-admin-client gv/data-exchange)]
    (run! #(try
             (kafka-admin/delete-topic admin-client (:kafka-topic %))
             (catch Exception e
               (log/info :msg "Exception deleting topic "
                         :topic (:kafka-topic %))))
          [#_gv/fetch-base-events-topic
           #_gv/base-data-topic
           #_gv/gene-validity-complete-topic
           #_gv/gene-validity-legacy-complete-topic
           #_gv/gene-validity-sepio-topic
           #_gv/api-log-topic]))
  )

(comment
  (sh "echo" "$PATH")
  (sh "kubectl" "get" "deployments"
      :env (into {} (System/getenv)))

  (sh "cat" :in "hi" :env {"PATH" nil})

  (System/getenv)
  (sh "/Users/tristan/google-cloud-sdk/bin/kubectl" "get" "deployments"
      :env {"PATH" "/Users/tristan/google-cloud-sdk/bin"})

  )
