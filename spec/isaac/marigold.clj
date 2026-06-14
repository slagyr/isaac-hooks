(ns isaac.marigold
  "Test data for the spaceship Marigold and her crew. Use these defs and
   builders in place of real-name fixtures so tests read like scenes
   aboard the ship.

   Themed api strings (helm, sky, groves, anvil) all route to the
   grover test stub — tests do not make real HTTP calls. Use real api
   names (messages, chat-completions, etc.) only when a test needs to
   exercise wire-format-specific behavior.

   A spec opts in by calling (marigold/with-apis) inside its describe;
   that wires a before-all that registers the themed api aliases.

   Example:

     (describe \"my spec\"
       (marigold/with-apis)
       (it \"...\" ...))"
  (:require
    [c3kit.apron.env :as c3env]
    [clojure.string :as str]
    [isaac.config.loader :as loader]
    [isaac.config.check-contributions :as check-contributions]
    [isaac.config.schema-compose :as schema-compose]
    [isaac.config.schema.root :as config-schema]
    [isaac.fs :as fs]
    [isaac.llm.api.protocol :as api]
    ;; Grover is the only impl namespace we need — all themed apis
    ;; route to its factory.
    [isaac.llm.api.grover]
    [isaac.module.loader :as module-loader]
    [isaac.slash.registry :as slash-registry]
    [isaac.nexus :as nexus]
    [isaac.tool.registry :as tool-registry]
    [speclj.core :as speclj]))

;; ----- Crew --------------------------------------------------------

;; Captain Atticus — sets direction, decides. Default :main in baselines.
(def captain "atticus")
;; First Mate Cordelia — second-in-command; for multi-crew scenarios.
(def first-mate "cordelia")
;; Chief Engineer Bartholomew — fixer; use when tests need a tools-heavy crew.
(def engineer "bartholomew")
;; Navigator Mavis — router; use when tests need a slash-command-heavy crew.
(def navigator "mavis")
;; Botanist Hieronymus — the turtle. Tends lettuce. Canonical in hooks.feature.
(def botanist "hieronymus")
;; Apprentice Periwinkle — junior crew; minimal-config / newcomer scenarios.
(def apprentice "periwinkle")
;; Cook Wormwood — background extra; a third crew that doesn't carry plot.
(def cook "wormwood")
;; The Loom — ship's AI. Opt-in test character.
(def ship-ai "the-loom")

;; ----- API wire protocols (themed aliases, all route to grover) ----

;; Helm protocol — flavor name for "Helm Systems' wire format." Stub via grover.
(def helm-api "helm")
;; Sky protocol — flavor name for "Starcore's wire format." Stub via grover.
(def sky-api "sky")
;; Groves protocol — flavor name for "Flicker Labs' wire format." Stub via grover.
(def groves-api "groves")
;; Anvil protocol — flavor name for "Quantum Anvil's wire format." Stub via grover.
(def anvil-api "anvil")
;; Grover — canonical test stub, available directly under its real name.
(def grover-api "grover")

;; ----- Provider corporations ---------------------------------------

;; Helm Systems — mainstream, reliable. The Captain's workhorse provider.
(def helm-systems "helm-systems")
;; Starcore — premium / expensive thinking.
(def starcore "starcore")
;; Flicker Labs — experimental / open-weights vibe.
(def flicker-labs "flicker-labs")
;; Quantum Anvil — heavy-reasoning, OAuth-bound.
(def quantum-anvil "quantum-anvil")
;; Grover stub — the test stand-in.
(def grover-stub "grover-stub")

;; ----- Model designations ------------------------------------------

(def helm-mark-i     "helm-mark-i")      ;; everyday workhorse
(def helm-mark-iii   "helm-mark-iii")    ;; flagship
(def helm-spark      "helm-spark")       ;; fast/cheap
(def starcore-7      "starcore-7")       ;; premium flagship
(def starcore-7-mini "starcore-7-mini")  ;; premium small
(def flicker-13b     "flicker-13b")      ;; open weights
(def anvil-x         "anvil-x")          ;; reasoning-heavy

;; ----- Comm channels (themed names for tests) ----------------------

(def longwave "longwave")   ;; broadcast — Discord analog
(def skybeam  "skybeam")    ;; direct/streaming — ACP analog
(def logbook  "logbook")    ;; persisted-local — memory comm analog

;; ----- Hooks (inbound webhooks the crew receives) ------------------

(def lettuce-hook    "lettuce")     ;; Hieronymus's garden status. Existing.
(def heartbeat-hook  "heartbeat")   ;; Crew health reports.
(def trajectory-hook "trajectory")  ;; Navigation updates.
(def dispatch-hook   "dispatch")    ;; External mission orders.

;; ----- Tools (themed names mapped to real factory symbols) ---------
;;
;; The names are intentionally minimal and ship-themed. The factory
;; symbols point at real builtins so the tools actually execute, but
;; tests assert against the themed names — proving the manifest is the
;; system's contract, not the specific built-in tool catalog.

(def spyglass-tool    "spyglass")     ;; look at a file / read
(def sextant-tool     "sextant")      ;; pattern-find / grep
(def signal-flare     "signal-flare") ;; web search

;; ----- Slash commands (themed) -------------------------------------

(def heading-command  "heading")  ;; where are we? / status
(def bearing-command  "bearing")  ;; what model is steering / model
(def muster-command   "muster")   ;; assemble the crew / crew

;; Themed slash-command factories. Slash-command registration names the
;; command after its berth key, so the factory only supplies description
;; and handler. Each handler is a no-op stub appropriate for tests.
(defn heading-slash-factory []
  {:description "Where are we?"
   :handler     (fn [_] {:type :command :command :status :message "steady on course"})})

(defn bearing-slash-factory []
  {:description "Bearing on the helm"
   :handler     (fn [_] {:type :command :command :model :message "helm-mk-3-1.0"})})

(defn muster-slash-factory []
  {:description "Call the crew to muster"
   :handler     (fn [_] {:type :command :command :crew :message "all hands"})})

;; ----- API alias registration --------------------------------------

(defn register-apis!
  "Register Marigold's themed api aliases. All themed apis route to
   the grover test stub. Idempotent. Most specs should prefer
   (marigold/with-apis) which wires this into a before-all hook."
  []
  (let [grover-factory (api/factory-for :grover)]
    (api/register! (keyword helm-api)   grover-factory)
    (api/register! (keyword sky-api)    grover-factory)
    (api/register! (keyword groves-api) grover-factory)
    (api/register! (keyword anvil-api)  grover-factory)))

(defn with-apis
  "Inside a `(describe ...)` block, registers a before-all hook that
   wires Marigold's themed api aliases. Place once near the top of
   the describe."
  []
  (speclj/before-all (register-apis!)))

;; ----- Provider templates ------------------------------------------

(def helm-provider
  {:api helm-api :base-url "https://api.helm-systems.test" :auth "api-key"})

(def starcore-provider
  {:api sky-api :base-url "https://api.starcore.test/v1" :auth "api-key"})

(def flicker-provider
  {:api groves-api :base-url "http://localhost:11434" :auth "none"})

(def quantum-provider
  {:api anvil-api :base-url "https://anvil.quantum.test/codex" :auth "oauth-device"})

;; ----- Builders ----------------------------------------------------

(defn provider-cfg
  "Merge overrides into a provider template (e.g., add :api-key)."
  [base & {:as overrides}]
  (merge base overrides))

(defn crew-cfg
  "Build a crew config map with a sensible default :soul derived from the name."
  [name & {:as overrides}]
  (merge {:soul (str "You are " (str/capitalize name) ".")} overrides))

(defn model-cfg
  "Build a model config map."
  [provider model & {:as overrides}]
  (merge {:model model :provider provider} overrides))

;; ----- Baseline isaac.edn ------------------------------------------

(def baseline-config
  "A fully-valid baseline isaac.edn map. Tests start from this and
   merge in their own overrides."
  {:defaults  {:crew captain :model helm-mark-iii}
   :providers {(keyword helm-systems) (provider-cfg helm-provider :api-key "helm-test-key")}
   :models    {(keyword helm-mark-iii) (model-cfg (keyword helm-systems) "helm-mk-3-1.0")}
   :crew      {(keyword captain) (crew-cfg captain :model helm-mark-iii)}})

;; ----- Themed foundation manifest ----------------------------------

(def baseline-foundation-manifest
  "A stand-in for src/isaac-manifest.edn — foundation only."
  {:id      :isaac.foundation
   :version "0.1.0"
   :factory 'isaac.foundation.module/create-module
   :berths  {:isaac/cli {:description "CLI commands."
                   :schema      {:type       :map
                                 :key-spec   {:type :keyword}
                                 :value-spec {:type    :map
                                              :factory 'isaac.cli.registry/register-cli-command!
                                              :schema  {:desc {:type :string}}}}}
             :isaac.config/schema {:description "Top-level config schema fragments."
                                   :schema      {:type :map
                                                 :key-spec {:type :keyword}
                                                 :value-spec {:type :map
                                                              :schema {:schema   {:type :map :validations [:present?]}
                                                                       :entity-dir {:type :string}
                                                                       :frontmatter? {:type :boolean}
                                                                       :merge-root-entity? {:type :boolean}
                                                                       :companion {:type :map
                                                                                   :schema {:field {:type :keyword}
                                                                                            :mode {:type :keyword}}}}}}}
             :isaac.config/check {:description "Post-load config validation checks."
                                 :schema      {:type       :map
                                               :key-spec   {:type :keyword}
                                               :value-spec {:type :map
                                                            :schema {:fn {:type :symbol :validations [:present?]}}}}}}})

(def baseline-server-manifest
  "A stand-in for resources/isaac-manifest.edn. Themed server
   contributions so a describe that calls (marigold/with-manifest) gets a
   complete world: themed providers/apis (helm instead of anthropic, etc.)
   plus the full set of capability names capability tests still need."
  {:id       :isaac.server
   :version  "0.1.0"
   :builtin? true
   :factory  'isaac.server.module/create-module

   :berths  {:isaac.agent/tools             {:description "LLM tool factories."
                                              :schema      {:type       :map
                                                            :key-spec   {:type :keyword}
                                                            :value-spec {:type    :map
                                                                         :factory 'isaac.tool.registry/register-tool-entry!
                                                                         :schema  {:factory {:type :symbol :validations [:present?]}}}}}
             :isaac.agent/llm-api           {:description "LLM API factories."
                                              :schema      {:type       :map
                                                            :key-spec   {:type :keyword}
                                                            :value-spec {:type    :map
                                                                         :factory 'isaac.llm.api.protocol/register-api-entry!
                                                                         :schema  {:factory {:type :symbol :validations [:present?]}}}}}
             :isaac.agent/slash-commands    {:description "Slash commands."
                                              :schema      {:type       :map
                                                            :key-spec   {:type :keyword}
                                                            :value-spec {:type    :map
                                                                         :factory 'isaac.slash.registry/register-slash-entry!
                                                                         :schema  {:factory {:type :symbol :validations [:present?]}}}}}
             :isaac.agent/provider-template {:description "Provider templates."
                                              :schema      {:type       :map
                                                            :key-spec   {:type :keyword}
                                                            :value-spec {:type   :map
                                                                         :schema {:template {:type :map}}}}}
             :isaac.agent/provider          {:description "Materialized providers."
                                              :schema      {:type       :map
                                                            :key-spec   {:type :keyword}
                                                            :value-spec {:type :map}}}
             :isaac.agent/comm              {:description "Communication channels."
                                              :schema      {:type       :map
                                                            :key-spec   {:type :keyword}
                                                            :value-spec {:type    :map
                                                                         :schema  {:namespace     {:type :symbol :validations [:present?]}
                                                                                   :extra-schema  {:type :schema-map}
                                                                                   :configurable? {:type :boolean}}}}}}

   :isaac.agent/llm-api {(keyword helm-api)   {:factory 'isaac.llm.api.grover/make}
                          (keyword sky-api)    {:factory 'isaac.llm.api.grover/make}
                          (keyword groves-api) {:factory 'isaac.llm.api.grover/make}
                          (keyword anvil-api)  {:factory 'isaac.llm.api.grover/make}
                          (keyword grover-api) {:factory 'isaac.llm.api.grover/make}}

   :isaac.agent/provider-template {(keyword helm-systems)  {:template (dissoc helm-provider :api-key)}
                                    (keyword starcore)      {:template (dissoc starcore-provider :api-key)}
                                    (keyword flicker-labs)  {:template flicker-provider}
                                    (keyword quantum-anvil) {:template quantum-provider}
                                    (keyword grover-stub)   {:template {:api grover-api :auth "none"}}}

   :isaac.agent/tools {(keyword spyglass-tool) {:factory 'isaac.tool.builtin/read-tool-factory}
                        (keyword sextant-tool)  {:factory 'isaac.tool.builtin/grep-tool-factory}
                        (keyword signal-flare)  {:factory 'isaac.tool.builtin/web-search-tool-factory}}

   :isaac.agent/slash-commands {(keyword heading-command) {:factory 'isaac.marigold/heading-slash-factory}
                                 (keyword bearing-command) {:factory 'isaac.marigold/bearing-slash-factory}
                                 (keyword muster-command)  {:factory 'isaac.marigold/muster-slash-factory}}

   :isaac.agent/comm {(keyword longwave) {:namespace 'isaac.marigold-comms}    ;; broadcast / cli-like
                       (keyword skybeam)  {:namespace 'isaac.marigold-comms}    ;; no-op / null-like
                       (keyword logbook)  {:namespace 'isaac.marigold-comms}}   ;; persisted / memory-like

   :isaac.config/schema config-schema/contributions
   :isaac.config/check  check-contributions/server})

(def baseline-manifest baseline-server-manifest)

(def ^:private baseline-foundation-index
  {:isaac.foundation {:coord {} :manifest baseline-foundation-manifest :path nil}
   :isaac.server {:coord {} :manifest baseline-server-manifest :path nil}})

(defn- reset-extension-registries! []
  ;; Each registry (api factories, comm registry, tool registry, slash
  ;; registry) is a global atom that activate! mutates. To make a manifest
  ;; rebind take real effect, we drain all four before and after each
  ;; example. clear-activations! handles api/comm and the activated-modules
  ;; bookkeeping; the slash and tool registries need their own clear!.
  (slash-registry/clear!)
  (tool-registry/clear!)
  (module-loader/clear-activations!))

(defn with-manifest
  "Inside a `(describe ...)` block, swaps the builtin manifests for
   Marigold's themed foundation + server manifests for the duration of
   each example in the describe. Tests assert against themed provider/api
   names (e.g. `helm-systems`, `helm`) instead of `anthropic`, `messages`, etc.

   Clears all extension registries on entry and exit so the api/comm/
   tool/slash-command registrations reflect the currently-bound manifest
   — required because activate! is idempotent and registry atoms persist
   across tests, so without this one manifest's registrations would leak
   into a sibling test using a different manifest (e.g. one that rebinds
   override to nil to exercise the real built-in catalog)."
  []
  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (speclj/around [example]
    (binding [module-loader/*foundation-index-override* baseline-foundation-index]
      (schema-compose/clear-cache!)
      (reset-extension-registries!)
      (try
        (example)
        (finally
          (schema-compose/clear-cache!)
          (reset-extension-registries!))))))

(defn with-real-manifest*
  "Implementation detail of `with-real-manifest`; tests should use the
   macro instead."
  [thunk]
  (binding [module-loader/*foundation-index-override* nil]
    (reset-extension-registries!)
    (module-loader/activate-foundation!)
    (thunk)))

(defmacro with-real-manifest
  "Escape the marigold themed world for the body's scope. Use sparingly
   when a test specifically exercises a production extension that
   marigold's test data deliberately omits (real HTTP, real exec tool,
   real slash command behavior). Inside a `(with-manifest)` describe,
   the enclosing fixture resets extension registries before the next
   example so re-entry into marigold's world is clean."
  [& body]
  `(with-real-manifest* (fn [] ~@body)))

;; ----- Aboard the Marigold -----------------------------------------
;;
;; The "aboard" pattern sets the scene: a fresh mem-fs, the themed
;; manifest bound, env-var caches cleared. Tests can then call the
;; write-X! helpers to add wrinkles and (load-config) to run the loader
;; against the resulting world.

(def home
  "Canonical user-home path used by all aboard-style tests."
  "/marigold")

(def root
  "Canonical state directory (home/.isaac); config lives at <root>/config."
  (str home "/.isaac"))

(defn- config-path [suffix]
  (str root "/config/" suffix))

(defn aboard
  "Inside a `(describe ...)` block, sets the scene aboard the Marigold:
   per-example mem-fs, themed manifest bound, c3env + loader caches
   cleared. Tests write entity files with the write-X! helpers and load
   via (marigold/load-config)."
  []
  (speclj/around [example]
    (let [mem (fs/mem-fs)]
      (nexus/-with-nested-nexus {:fs mem}
        (binding [module-loader/*foundation-index-override* baseline-foundation-index]
          (reset! c3env/-overrides {})
          (loader/clear-env-overrides!)
          (schema-compose/clear-cache!)
          (example))))))

(defn- local-module-manifest-path [id]
  (let [root (str home "/.isaac/modules/" (name id))]
    (some #(when (fs/exists? (nexus/get :fs) %) %)
          [(str root "/resources/isaac-manifest.edn")
           (str root "/src/isaac-manifest.edn")])))

(defn load-config
  "Load the configuration from the Marigold's home. Optional opts merge
   into the loader call (e.g. {:raw-parse-errors? true})."
  ([] (load-config nil))
  ([opts]
   ;; Marigold module fixtures live on mem-fs, so emulate the classpath lookup
   ;; seam instead of trying to add an in-memory local/root to the real JVM classpath.
   (with-redefs [isaac.module.loader/add-module-deps! (fn [_ _])
                 isaac.module.loader/manifest-resource local-module-manifest-path]
     (loader/load-config-result (merge {:root root} opts)))))

(defn write-config!
  "Write isaac.edn at the Marigold home, replacing any prior contents."
  [data]
  (fs/spit (nexus/get :fs) (config-path "isaac.edn") (pr-str data)))

(defn write-baseline!
  "Write the baseline-config as isaac.edn — Marigold's standard wiring,
   ready for tests to perturb."
  []
  (write-config! baseline-config))

(defn write-provider!
  "Write a per-provider entity file. `provider-id` may be a keyword or
   string. `cfg` is the provider config map (use provider-cfg + a
   marigold provider template to build it)."
  [provider-id cfg]
  (fs/spit (nexus/get :fs) (config-path (str "providers/" (name provider-id) ".edn"))
           (pr-str cfg)))

(defn write-crew!
  "Write a per-crew entity file. Pass :soul to also write the companion
   markdown soul file."
  [crew-id cfg & {:keys [soul]}]
  (fs/spit (nexus/get :fs) (config-path (str "crew/" (name crew-id) ".edn")) (pr-str cfg))
  (when soul
    (fs/spit (nexus/get :fs) (config-path (str "crew/" (name crew-id) ".md")) soul)))

(defn write-crew-md!
  "Write a single-file crew markdown (frontmatter + soul body) or a
   companion-only markdown for a crew id."
  [crew-id body]
  (fs/spit (nexus/get :fs) (config-path (str "crew/" (name crew-id) ".md")) body))

(defn write-model!
  "Write a per-model entity file."
  [model-id cfg]
  (fs/spit (nexus/get :fs) (config-path (str "models/" (name model-id) ".edn")) (pr-str cfg)))

(defn write-cron!
  "Write a per-cron entity file. Pass :prompt to also write the
   companion markdown prompt file."
  [cron-id cfg & {:keys [prompt]}]
  (fs/spit (nexus/get :fs) (config-path (str "cron/" (name cron-id) ".edn")) (pr-str cfg))
  (when prompt
    (fs/spit (nexus/get :fs) (config-path (str "cron/" (name cron-id) ".md")) prompt)))

(defn write-cron-md!
  "Write a single-file cron markdown (or companion-only markdown)."
  [cron-id body]
  (fs/spit (nexus/get :fs) (config-path (str "cron/" (name cron-id) ".md")) body))

(defn write-hook!
  "Write a per-hook entity file."
  [hook-id cfg]
  (fs/spit (nexus/get :fs) (config-path (str "hooks/" (name hook-id) ".edn")) (pr-str cfg)))

(defn write-hook-md!
  "Write a single-file hook markdown (frontmatter + template body)."
  [hook-id body]
  (fs/spit (nexus/get :fs) (config-path (str "hooks/" (name hook-id) ".md")) body))

(defn write-env-file!
  "Write the .env file in the Marigold state directory."
  [content]
  (fs/spit (nexus/get :fs) (str root "/.env") content))

(defn write-raw!
  "Write arbitrary text at a path relative to .isaac/config/. Used by
   low-level tests that need to scribble malformed bytes (EDN syntax
   errors, etc.)."
  [relative content]
  (fs/spit (nexus/get :fs) (config-path relative) content))
