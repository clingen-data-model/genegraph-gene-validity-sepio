(ns build
  "Build this thing."
  (:require [clojure.tools.build.api :as b]))

(def defaults
  "The defaults to configure a build."
  {:class-dir  "target/classes"
   :java-opts  ["-Dclojure.main.report=stderr"]
   :main       'genegraph.gene-validity
   :path       "target"
   :project    "deps.edn"
   :target-dir "target/classes"
   :uber-file  "target/app.jar"
   :exclude [#"META-INF/license.*"]})

(defn uber
  "Throw or make an uberjar from source."
  [_]
  (let [{:keys [paths] :as basis} (b/create-basis defaults)
        project                   (assoc defaults :basis basis)]
    (b/delete      project)
    (b/copy-dir    (assoc project :src-dirs paths))
    (b/compile-clj (assoc project
                          :src-dirs ["src"]
                          :ns-compile ['genegraph.gene-validity]))
    (b/uber        project)))
