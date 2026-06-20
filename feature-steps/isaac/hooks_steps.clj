(ns isaac.hooks-steps
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defthen helper!]]
    [isaac.config.api :as config]
    [isaac.config.loader :as loader]
    [isaac.config.runtime :as runtime]
    [isaac.foundation.fs-steps :as ffs]
    [isaac.foundation.root-steps :as froot]
    [isaac.fs :as fs]
    [isaac.hooks :as hooks]
    [isaac.module.loader :as module-loader]
    [isaac.nexus :as nexus]
    [isaac.spec-helper :as helper]))

(helper! isaac.hooks-steps)

(defn- root-dir []
  (or (g/get :runtime-root-dir) (g/get :root)))

(defn- mem-fs []
  (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs)))

(defn- with-feature-fs [f]
  (nexus/-with-nested-nexus {:fs (mem-fs)} (f)))

(defn- isaac-edn-path []
  (str (root-dir) "/config/isaac.edn"))

(defn- parse-state-value [value]
  (cond
    (re-matches #"-?\d+" value)        (parse-long value)
    (= "true" (str/lower-case value))  true
    (= "false" (str/lower-case value)) false
    (str/starts-with? value "[")       (edn/read-string value)
    (str/starts-with? value "{")       (edn/read-string value)
    (str/starts-with? value ":")       (edn/read-string value)
    (str/starts-with? value "\"")      (edn/read-string value)
    :else                              value))

(defn- get-by-dotted-path [m path]
  (get-in m (mapv keyword (str/split path #"\."))))

(defn- write-grover-defaults! []
  (with-feature-fs
    (fn []
      (let [root     (root-dir)
            cfg-root (str root "/config")
            fs*      (mem-fs)]
        (fs/mkdirs fs* cfg-root)
        (fs/mkdirs fs* (str cfg-root "/models"))
        (fs/mkdirs fs* (str cfg-root "/providers"))
        (fs/mkdirs fs* (str cfg-root "/crew"))
        (fs/spit   fs* (str cfg-root "/isaac.edn")
                        (pr-str {:defaults {:crew "main" :model "grover"}}))
        (fs/spit   fs* (str cfg-root "/models/grover.edn")
                        (pr-str {:model "echo" :provider :grover :context-window 32768}))
        (fs/spit   fs* (str cfg-root "/providers/grover.edn") (pr-str {}))
        (fs/spit   fs* (str cfg-root "/crew/main.edn")
                        (pr-str {:model :grover :soul "You are Atticus."}))))))

(defn- harness-host []
  {:module-index (module-loader/builtin-index)})

(defn- reload-hooks-config! [_path]
  (let [root    (g/get :server-root)
        fs*     (mem-fs)
        old-cfg (loader/snapshot "hooks-steps: reload old-config")
        new-cfg (:config (loader/load-config-result {:root root :fs fs*}))
        host    (harness-host)]
    (config/dangerously-install-config! new-cfg "hooks-steps: config rewrite")
    (runtime/reconcile! host old-cfg new-cfg [hooks/registry])))

(defn- persist-hook-config-path! [path value]
  (let [fs*      (mem-fs)
        cfg-path (isaac-edn-path)
        current  (if (fs/exists? fs* cfg-path)
                   (edn/read-string (fs/slurp fs* cfg-path))
                   {})
        keys     (mapv keyword (str/split path #"\."))
        updated  (assoc-in current keys (parse-state-value value))]
    (fs/mkdirs fs* (fs/parent cfg-path))
    (fs/spit   fs* cfg-path (pr-str updated))
    (when (g/get :hooks-harness-active?)
      (reload-hooks-config! cfg-path))))

(ffs/register-post-write-hook!
  (fn [path]
    (when (g/get :hooks-harness-active?)
      (when-let [source (g/get :config-change-source)]
        (runtime/notify-path! source path)
        (loop []
          (when-let [rel (runtime/poll! source)]
            (reload-hooks-config! rel)
            (recur)))))))

(defn default-grover-hook-setup []
  ;; Grover is a config-driven test provider now (models/grover.edn :provider
  ;; :grover); the removed install-test-fixture! only reset the response queue.
  ((requiring-resolve 'isaac.llm.api.grover/reset-queue!))
  (hooks/reset-registry!)
  (froot/initialize-root! "target/test-state" true)
  (write-grover-defaults!))

(defn hook-config-path-is [path value]
  (persist-hook-config-path! path value))

(defn config-harness-started []
  (let [root (root-dir)
        fs*  (mem-fs)
        cfg  (:config (loader/load-config-result {:root root :fs fs*}))]
    (config/dangerously-install-config! cfg "feature: hooks config harness started")
    (g/assoc! :server-root root)
    (g/assoc! :config-change-source (runtime/memory-source root))
    (g/assoc! :hooks-harness-active? true)
    (runtime/install! {:config cfg
                       :registries [hooks/registry]
                       :host       (harness-host)})))

(defn hook-registry-entry-has [name table]
  (helper/await-condition
    #(when-let [entry (hooks/lookup-hook name)]
       (every? (fn [row]
                 (let [row-map  (zipmap (:headers table) row)
                       path     (get row-map "path")
                       expected (parse-state-value (get row-map "value"))]
                   (= expected (get-by-dotted-path (:entry entry) path))))
               (:rows table))))
  (let [entry (hooks/lookup-hook name)]
    (g/should-not-be-nil entry)
    (doseq [row (:rows table)]
      (let [row-map  (zipmap (:headers table) row)
            path     (get row-map "path")
            expected (parse-state-value (get row-map "value"))
            actual   (get-by-dotted-path (:entry entry) path)]
        (g/should= expected actual)))))

(g/after-scenario
  (fn []
    (g/assoc! :hooks-harness-active? false)
    (hooks/reset-registry!)))

(defgiven "default Grover hook setup" isaac.hooks-steps/default-grover-hook-setup)

(defgiven "the hook config path {path:string} is {value:string}" isaac.hooks-steps/hook-config-path-is)

(defgiven "the Isaac config harness is started" isaac.hooks-steps/config-harness-started)

(defthen "the hook {name:string} registry entry has:" isaac.hooks-steps/hook-registry-entry-has)