(ns isaac.hooks-feature-bootstrap
  "Loaded after isaac.**-steps. Drops colliding step templates so hooks
   features can share the foundation/session/server step surface without
   ambiguous matches (isaac-iz35)."
  (:require [isaac.logger :as log]))

(log/set-output! :memory)

(def ^:private session-ns 'isaac.session.session-steps)
(def ^:private configurator-ns 'isaac.configurator-steps)
(def ^:private harness-ns 'isaac.foundation.harness-config-steps)
(def ^:private server-ns 'isaac.http.server-steps)

(defn- without-templates [entries templates]
  (let [drop? (set (or templates []))]
    (vec (remove #(contains? drop? (:template %)) entries))))

(defn- server-owns-config? [registry]
  (some (fn [[ns-sym entries]]
          (when (= ns-sym server-ns)
            (some #(= "config:" (:template %)) entries)))
        registry))

(when-let [registry-var (some-> (find-ns 'gherclj.core) ns-interns (get 'registry))]
  (swap! @registry-var
         (fn [m]
           (into {}
                 (map (fn [[ns-sym entries]]
                        [ns-sym
                         (cond
                           ;; Prefer session's Grover setup over server configurator.
                           (= ns-sym configurator-ns)
                           (without-templates entries ["default Grover setup"])

                           ;; Prefer server's config: over session/harness when present.
                           (= ns-sym session-ns)
                           (without-templates entries
                                              (cond-> []
                                                (server-owns-config? m) (conj "config:")))

                           (= ns-sym harness-ns)
                           (without-templates entries
                                              (when (server-owns-config? m) ["config:"]))

                           :else entries)]))
                 m))))
