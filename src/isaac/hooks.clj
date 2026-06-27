(ns isaac.hooks
  (:require
    [cheshire.core :as json]
    [clojure.set :as set]
    [clojure.string :as str]
    [isaac.bridge.core :as bridge]
    [isaac.charge :as charge]
    [isaac.comm.null :as null-comm]
    [isaac.config.loader :as loader]
    [isaac.config.runtime :as runtime]
    [isaac.reconfigurable :as reconfigurable]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.session.context :as session-ctx]
    [isaac.session.frequencies :as session-frequencies]
    [isaac.session.store.spi :as store]
    [isaac.session.store.sidecar :as sidecar-store]
    [isaac.nexus :as nexus]
    [isaac.prompt.template :as tpl]))

;; Holds the future for the most recently dispatched hook turn so test
;; harnesses can await completion via (deref (last-turn-future)).
(defonce last-turn-future* (atom nil))

(defn last-turn-future [] @last-turn-future*)

;; Registry: name → {:source :config|:module, :entry hook-config-or-fn}
(defonce ^:private registry* (atom {}))

(defn- hook-entries [hooks]
  (into {}
        (keep (fn [[name entry]]
                (when (and (not= :auth name) (map? entry))
                  [(runtime/->name name) entry])))
        hooks))

(defn reset-registry!
  "Clear the registry. For test isolation."
  []
  (reset! registry* {}))

(defn lookup-hook
  "Return the registry entry for hook name, or nil."
  [name]
  (get @registry* name))

(defn register-hook!
  "Register a hook by name. source is :config or :module.
   Throws on collision (module vs config with same name)."
  [name entry source]
  (let [existing (get @registry* name)]
    (when (and existing (not= (:source existing) source))
      (log/error :hook/collision :name name :existing-source (:source existing) :new-source source)
      (throw (ex-info (str "hook name collision: " name) {:name name}))))
  (swap! registry* assoc name {:source source :entry entry})
  (log/info :hook/registered :name name :source source))

(defn register-hook-entry!
  "Per-entry factory for the :isaac.hooks/hook berth (phase 7 of the
   berth epic). Receives `[hook-id entry]`; resolves the entry's
   symbol-valued :factory and registers the returned spec as a module-
   sourced hook."
  [[hook-id entry]]
  (let [hook-name (clojure.core/name hook-id)
        factory   (some-> (:factory entry) requiring-resolve var-get)
        spec      (factory)]
    (register-hook! hook-name spec :module)))

(defn deregister-hook!
  "Remove a hook by name from the registry."
  [name]
  (when (contains? @registry* name)
    (let [entry (get @registry* name)]
      (swap! registry* dissoc name)
      (log/info :hook/deregistered :name name :source (:source entry)))))

(defn- reconcile-config-hooks [old-hooks new-hooks]
  (let [old-hooks (hook-entries old-hooks)
        new-hooks (hook-entries new-hooks)
        old-names (set (keys old-hooks))
        new-names (set (keys new-hooks))]
    (doseq [name (set/difference old-names new-names)]
      (when (= :config (:source (get @registry* name)))
        (deregister-hook! name)))
    (doseq [name (set/intersection old-names new-names)]
      (let [old-hook (get old-hooks name)
            new-hook (get new-hooks name)]
        (when (and (map? new-hook)
                   (= :config (:source (get @registry* name)))
                   (not= old-hook new-hook))
          (deregister-hook! name)
          (register-hook! name new-hook :config))))
    (doseq [name (set/difference new-names old-names)]
      (when-let [hook-cfg (get new-hooks name)]
        (when (map? hook-cfg)
          (register-hook! name hook-cfg :config))))))

;; Reconfigurable implementation

(deftype HooksModule []
  reconfigurable/Reconfigurable
  (on-load [_ slice]
    (reconcile-config-hooks nil slice))
  (on-config-change! [_ old-slice new-slice]
    (reconcile-config-hooks old-slice new-slice))
  (on-unload [_ slice]
    (reconcile-config-hooks slice nil)))

(defn make
  "Factory: creates a HooksModule instance."
  [_host]
  (HooksModule.))

(def registry
  {:kind    :component
   :path    [:hooks]
   :impl    "hooks"
   :factory make})

;; Handler

(defn- hook-name [uri]
  (when (str/starts-with? uri "/hooks/")
    (let [name (subs uri (count "/hooks/"))]
      (when-not (str/blank? name) name))))

(defn- read-body [request]
  (let [body (:body request)]
    (cond
      (nil? body)    ""
      (string? body) body
      :else          (slurp body))))

(defn- json-content-type? [request]
  (let [ct (get-in request [:headers "content-type"] "")]
    (str/includes? ct "application/json")))

(defn- dispatch-turn! [charge*]
  (let [fut (future
              (try
                (bridge/dispatch! charge*)
                (catch Exception e
                  (log/error :hook/dispatch-error
                             :session (:session-key charge*)
                             :error (.getMessage e)))))]
    (reset! last-turn-future* fut)
    fut))

(defn- runtime-fs! [runtime]
  (or (fs/instance runtime) (throw (ex-info "hooks require :fs in system" {}))))

(defn- coerce-keyword [value]
  (cond
    (keyword? value) value
    (string? value)  (keyword value)
    :else            value))

(defn- normalize-session-ids [value]
  (cond
    (nil? value)       nil
    (sequential? value) (mapv str value)
    :else              [(str value)]))

(defn- normalize-session-tags [value]
  (when value
    (if (set? value)
      value
      (into #{} (map keyword) (if (sequential? value) value [value])))))

(defn build-frequencies-from-hook
  "Build a frequencies map from hook frontmatter. Legacy :session-key and :model
   fold into :session and :with-model; omit the hook:<name> default when describe
   selectors are present."
  [hook-name hook]
  (let [has-describe? (or (:crew hook) (:session-tags hook) (:session hook))
        session       (cond
                        (:session hook)     (normalize-session-ids (:session hook))
                        (:session-key hook) (normalize-session-ids (:session-key hook))
                        has-describe?       nil
                        :else               [(str "hook:" hook-name)])]
    (cond-> {:reach  :one
             :create (or (coerce-keyword (:create hook)) :if-missing)
             :prefer (or (coerce-keyword (:prefer hook)) :recent)}
      (or (:crew hook) (not session))
      (assoc :crew (str (or (:crew hook) "main")))

      session
      (assoc :session session)

      (:session-tags hook)
      (assoc :session-tags (normalize-session-tags (:session-tags hook)))

      (:with-model hook)
      (assoc :with-model (str (:with-model hook)))

      (:model hook)
      (assoc :with-model (str (:model hook)))

      (:with-crew hook)
      (assoc :with-crew (str (:with-crew hook)))

      (:with-effort hook)
      (assoc :with-effort (:with-effort hook))

      (:with-context-mode hook)
      (assoc :with-context-mode (coerce-keyword (:with-context-mode hook))))))

(defn- crew-quarters [root crew-id]
  (str root "/crew/" crew-id))

(defn- ensure-hook-session! [target frequencies cfg session-store root origin]
  (if (:create? target)
    (let [crew-id     (str (or (:crew (:create-identity target))
                               (:with-crew frequencies)
                               (:crew frequencies)
                               "main"))
          quarters    (crew-quarters root crew-id)
          create-opts (merge {:cwd           quarters
                              :config        cfg
                              :origin        origin
                              :session-store session-store}
                             (:create-identity target)
                             (session-frequencies/behavioral-override frequencies))]
      (session-ctx/create-with-resolved-behavior!
        (:session-key target) create-opts)
      (:session-key target))
    (:session-key target)))

(defn handler
  ([request]
   (handler (nexus/necho) request))
  ([runtime request]
   (let [cfg          (loader/snapshot "hook dispatch entry — ambient config for hook handler")
         root    (or (:root cfg) (:root runtime))
         name         (hook-name (:uri request))]
     (cond
       ;; 1. Method check
       (not= :post (:request-method request))
       {:status 405 :headers {"Content-Type" "text/plain"} :body "Method Not Allowed"}

       ;; 2. Path lookup — from registry
       (nil? (lookup-hook name))
       {:status 404 :headers {"Content-Type" "text/plain"} :body "Not Found"}

       :else
       (let [hook (:entry (lookup-hook name))]
         (cond
           ;; 3. Content-type check
           (not (json-content-type? request))
           {:status 415 :headers {"Content-Type" "text/plain"} :body "Unsupported Media Type"}

           :else
           (let [body-str (read-body request)
                 body     (try (json/parse-string body-str true)
                               (catch Exception _ ::parse-error))]
             (if (= ::parse-error body)
               ;; 4. Body parse error
               {:status 400 :headers {"Content-Type" "text/plain"} :body "Bad Request"}

               ;; 5. Render and dispatch
                 (let [fs*           (runtime-fs! runtime)
                       session-store (or (nexus/get-in [:sessions :store])
                                         (:session-store runtime)
                                         (some-> root (sidecar-store/create-store fs*))
                                         (throw (ex-info "hook handler requires :root or :session-store" {})))
                       frequencies   (build-frequencies-from-hook name hook)
                       target        (session-frequencies/resolve-session-targets frequencies session-store)
                       origin        {:kind :webhook :name name}
                       cfg*          (assoc cfg :root root)]
                   (if (:error target)
                     {:status 422
                      :headers {"Content-Type" "text/plain"}
                      :body    (:message target)}

                     (let [crew-id          (str (or (:with-crew frequencies)
                                                     (:crew frequencies)
                                                     "main"))
                           hook-template    (:template hook)
                           message          (tpl/render hook-template body {:on-missing :marker})
                           existing-session (when (:session-key target)
                                              (store/get-session session-store (:session-key target)))
                           _                (when (:create? target)
                                              (fs/mkdirs fs* (crew-quarters root crew-id)))
                           session-key      (ensure-hook-session! target frequencies cfg* session-store root origin)
                           session          (store/get-session session-store session-key)
                           override         (session-frequencies/behavioral-override frequencies)
                           charge*          (charge/build {:session-key    session-key
                                                           :input          message
                                                           :comm           null-comm/channel
                                                           :config         cfg*
                                                           :crew           (or (:crew override) (:crew session))
                                                           :model-override (:model override)
                                                           :origin         origin})]
                       (log/info :hook/dispatch-planned
                                 :hook name
                                 :session session-key
                                 :crew crew-id
                                 :cwd (:cwd session)
                                 :existing-session? (boolean existing-session)
                                 :message-chars (count message)
                                 :has-model-override? (some? (:with-model frequencies)))
                       (dispatch-turn! charge*)
                       {:status 202 :headers {"Content-Type" "text/plain"} :body "Accepted"})))))))))))
