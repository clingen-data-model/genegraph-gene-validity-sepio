(ns build
  "Build this thing."
  (:require [clojure.tools.build.api :as b]
            [clojure.java.process :as process]
            #_[clojure.data.json :as json]
            [charred.api :as charred]
            [clojure.java.io :as io]))

(def defaults
  "The defaults to configure a build."
  {:class-dir  "target/classes"
   :java-opts  ["-Dclojure.main.report=stderr"]
   :main       'genegraph.transform.gene-validity
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
                          :ns-compile ['genegraph.transform.gene-validity]))
    (b/uber        project)))

(def app-name "genegraph-gene-validity-sepio")

(def env-config
  {:prod  {:registry "genegraph-prod"  :platform "prod"  :deployment-name app-name}
   :stage {:registry "genegraph-stage" :platform "stage" :deployment-name (str app-name "-stage")}})

(defn image-tag [env]
  (let [{:keys [registry]} (env-config env)]
    (str
     "us-east1-docker.pkg.dev/"
     "clingen-dx/"
     registry "/"
     app-name
     ":v"
     (b/git-count-revs {}))))

(defn kubernetes-deployment [env]
  (let [{:keys [platform deployment-name]} (env-config env)]
    {:apiVersion "apps/v1"
     :kind "Deployment"
     :metadata {:name deployment-name}
     :spec
     {:selector {:matchLabels {:app deployment-name}}
      :template
      {:metadata {:labels {:app deployment-name}}
       :spec
       {:containers
        [{:name deployment-name
          :image (image-tag env)
          :env [{:name "GENEGRAPH_PLATFORM" :value platform}]
          :ports [{:name "genegraph-port" :containerPort 8888}]
          :readinessProbe {:httpGet {:path "/ready" :port "genegraph-port"}}
          :volumeMounts [{:mountPath "/data" :name "local-volume"}]
          :resources {:requests {:memory "4Gi" :cpu "500m"}
                      :limits {:memory "4Gi"}}}]
        :tolerations [{:key "kubernetes.io/arch"
                       :operator "Equal"
                       :value "arm64"
                       :effect "NoSchedule"}]
        :volumes [{:name "local-volume" :emptyDir {:sizeLimit "50Gi"}}]
        :affinity {:nodeAffinity {:requiredDuringSchedulingIgnoredDuringExecution
                                  {:nodeSelectorTerms
                                   [{:matchExpressions
                                     [{:key "kubernetes.io/arch"
                                       :operator "In"
                                       :values ["arm64"]}]}]}}}}}}}))

(defn docker-push [env]
  (process/exec
   {:err :stdout}
   "docker"
   "buildx"
   "build"
   "."
   "--platform"
   "linux/arm64"
   "-t"
   (image-tag env)
   "--push"))

(defn kubernetes-apply [env]
  (let [p (process/start {:err :inherit} "kubectl" "apply" "-f" "-")
        captured (process/io-task #(slurp (process/stdout p)))]
    (with-open [w (io/writer (process/stdin p))]
      (run! #(charred/write-json w %)
            [(kubernetes-deployment env)]))
    (if (zero? @(process/exit-ref p))
      (println @captured)
      (println "non-zero exit code"))))

(defn kubernetes-apply-stage [_]
  (kubernetes-apply :stage))

(defn deploy-env [env]
  (uber nil)
  (docker-push env)
  (kubernetes-apply env))

(defn deploy
  "Deploy to prod (default)."
  [_]
  (deploy-env :prod))

(defn deploy-prod
  "Deploy to the prod environment."
  [_]
  (deploy-env :prod))

(defn deploy-stage
  "Deploy to the stage environment."
  [_]
  (deploy-env :stage))

(defn destroy-prod
  [_]
  (process/exec {:err :stdout} "kubectl" "delete" "deployment" (:deployment-name (env-config :prod))))

(defn destroy-stage
  [_]
  (process/exec {:err :stdout} "kubectl" "delete" "deployment" (:deployment-name (env-config :stage))))

(defn destroy
  "Destroy prod deployment (legacy)."
  [_]
  (destroy-prod nil))
