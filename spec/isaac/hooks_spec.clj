(ns isaac.hooks-spec
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.charge :as charge]
    [isaac.config.api :as config]
    [isaac.config.runtime :as runtime]
    [isaac.fs :as fs]
    [isaac.hooks :as sut]
    [isaac.logger :as log]
    [isaac.marigold :as marigold]
    [isaac.session.store.spi :as store]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(defn- post-request [path body headers]
  {:request-method :post
   :uri            path
   :headers        (merge {"content-type" "application/json"} headers)
   :body           body})

(defn- get-request [path headers]
  {:request-method :get
   :uri            path
   :headers        headers})

(def ^:private test-cfg
  {:hooks {marigold/lettuce-hook {:crew        marigold/captain
                                   :session-key (str "hook:" marigold/lettuce-hook)
                                   :template    "Report: {{count}} items, freshness {{level}}/10."}}
   :crew   {marigold/captain {:soul (:soul (marigold/crew-cfg marigold/captain))}}
   :models {"grover" {:model "echo" :provider marigold/grover-api :context-window 32768}}})

(describe "build-frequencies-from-hook"

  (it "folds legacy session-key into :session"
    (should= {:session ["hook:lettuce"] :crew "main" :reach :one :create :if-missing :prefer :recent}
             (sut/build-frequencies-from-hook "lettuce" {:crew "main" :session-key "hook:lettuce"})))

  (it "uses describe selectors without defaulting to hook:<name>"
    (should= {:crew "main" :reach :one :create :if-missing :prefer :recent}
             (sut/build-frequencies-from-hook "garden" {:crew "main"})))

  (it "maps legacy :model to :with-model"
    (should= {:session ["hook:ping"] :reach :one :create :if-missing :prefer :recent :with-model "grover2"}
             (sut/build-frequencies-from-hook "ping" {:model "grover2"}))))

(describe "Webhook handler"

  (defn- startup-hooks! [slice]
    (runtime/on-load (sut/make nil) slice))

  #_{:clj-kondo/ignore [:invalid-arity]}
  (around [it]
    (sut/reset-registry!)
    (startup-hooks! (:hooks test-cfg))
    (it)
    (sut/reset-registry!))

  (describe "HooksModule lifecycle"

    (it "registers config hooks on startup"
      (sut/reset-registry!)
      (startup-hooks! {marigold/lettuce-hook {:template "A"}})
      (should= "A" (get-in (sut/lookup-hook marigold/lettuce-hook) [:entry :template])))

    (it "updates hook content when the slice changes"
      (sut/reset-registry!)
      (let [module (sut/make nil)]
        (runtime/on-load module {marigold/lettuce-hook {:template "A"}})
        (runtime/on-config-change! module
                                        {marigold/lettuce-hook {:template "A"}}
                                        {marigold/lettuce-hook {:template "B"}})
        (should= "B" (get-in (sut/lookup-hook marigold/lettuce-hook) [:entry :template]))))

    (it "deregisters removed hooks when the slice changes"
      (sut/reset-registry!)
      (let [module (sut/make nil)]
        (runtime/on-load module {marigold/lettuce-hook {:template "A"}})
        (runtime/on-config-change! module
                                        {marigold/lettuce-hook {:template "A"}}
                                        {})
        (should-be-nil (sut/lookup-hook marigold/lettuce-hook))))

    (it "does nothing when the slice is unchanged"
      (sut/reset-registry!)
      (let [module  (sut/make nil)
            before  (atom nil)
            payload {marigold/lettuce-hook {:template "A"}}]
        (runtime/on-load module payload)
        (reset! before (sut/lookup-hook marigold/lettuce-hook))
        (runtime/on-config-change! module payload payload)
        (should= @before (sut/lookup-hook marigold/lettuce-hook)))))

  (describe "hook template rendering"
    (it "substitutes present vars"
      (let [result (isaac.prompt.template/render "Hello {{name}}, you have {{count}} items."
                                                 {:name "Zane" :count 3}
                                                 {:on-missing :marker})]
        (should= "Hello Zane, you have 3 items." result)))

    (it "renders (missing) for absent vars"
      (let [result (isaac.prompt.template/render "Hello {{name}}, you have {{count}} items."
                                                 {:name "Zane"}
                                                 {:on-missing :marker})]
        (should= "Hello Zane, you have (missing) items." result))))

  (describe "unauthenticated handler"
    (it "checks the path before unknown-hook requests fail"
      (nexus/-with-nexus {:root "/test"}
        (config/dangerously-install-config! test-cfg "spec")
        (let [resp (sut/handler (post-request "/hooks/unknown" "{}" {}))]
          (should= 404 (:status resp))))))

  (describe "method check"
    (it "returns 405 for GET requests"
      (nexus/-with-nexus {:root "/test"}
        (config/dangerously-install-config! test-cfg "spec")
        (let [resp (sut/handler (get-request (str "/hooks/" marigold/lettuce-hook) {}))]
          (should= 405 (:status resp))))))

  (describe "path lookup"
    (it "returns 404 for unknown hook name"
      (nexus/-with-nexus {:root "/test"}
        (config/dangerously-install-config! test-cfg "spec")
        (let [resp (sut/handler (post-request "/hooks/unknown" "{}" {}))]
          (should= 404 (:status resp))))))

  (describe "content-type check"
    (it "returns 415 for non-JSON content-type"
      (nexus/-with-nexus {:root "/test"}
        (config/dangerously-install-config! test-cfg "spec")
        (let [resp (sut/handler {:request-method :post
                                 :uri            (str "/hooks/" marigold/lettuce-hook)
                                 :headers        {"content-type"   "text/plain"}
                                 :body           "not json"})]
          (should= 415 (:status resp))))))

  (describe "body parse"
    (it "returns 400 for malformed JSON"
      (nexus/-with-nexus {:root "/test"}
        (config/dangerously-install-config! test-cfg "spec")
        (let [resp (sut/handler (post-request (str "/hooks/" marigold/lettuce-hook) "not-json" {}))]
          (should= 400 (:status resp))))))

  (describe "state dir"

    (it "does not depend on isaac.comm.acp"
      (let [ns-form (->> (slurp "src/isaac/hooks.clj")
                         str/split-lines
                         (take 20)
                         (str/join "\n"))]
        (should-not-contain "isaac.comm.acp" ns-form)))

    (it "passes the root on :config to charge/build"
      (let [captured-root (atom nil)
            mem                (fs/mem-fs)]
        (with-redefs [charge/build              (fn [input]
                                                  (reset! captured-root (get-in input [:config :root]))
                                                  {:charge/type :charge})
                      isaac.hooks/dispatch-turn! (fn [_] nil)]
          (nexus/-with-nexus {:root     "/tmp/hooks-home/.isaac"
                               :session-store (store/create nil :memory)
                               :fs            mem}
            (config/dangerously-install-config! test-cfg "spec")
            (let [response (sut/handler (post-request (str "/hooks/" marigold/lettuce-hook)
                                                      (json/generate-string {:count 3 :level 8})
                                                      {"authorization" "Bearer secret123"}))]
              (should= 202 (:status response))
              (should= "/tmp/hooks-home/.isaac" @captured-root))))))

    (it "uses the hook model's provider when dispatching"
      (let [captured (atom nil)
            mem      (fs/mem-fs)
            hook-cfg {:defaults {:crew marigold/captain :model "spark"}
                       :hooks    {marigold/lettuce-hook {:crew        marigold/captain
                                                         :session-key (str "hook:" marigold/lettuce-hook)
                                                         :model       marigold/starcore
                                                         :template    "Report: {{count}} items, freshness {{level}}/10."}}
                       :crew     {marigold/captain {:soul (:soul (marigold/crew-cfg marigold/captain)) :model "spark"}}
                       :models   {"spark"           {:model "helm-spark-1.0"  :provider marigold/quantum-anvil :context-window 32768}
                                  marigold/starcore {:model "starcore-7-fast" :provider marigold/starcore     :context-window 278528}}}]
        (sut/reset-registry!)
        (startup-hooks! (:hooks hook-cfg))
        (with-redefs [charge/build              (fn [input]
                                                  (reset! captured input)
                                                  {:charge/type :charge})
                       isaac.hooks/dispatch-turn! (fn [_] nil)]
          (nexus/-with-nexus {:root     "/tmp/hooks-home/.isaac"
                               :session-store (store/create nil :memory)
                               :fs            mem}
            (config/dangerously-install-config! hook-cfg "spec")
            (let [response (sut/handler (post-request (str "/hooks/" marigold/lettuce-hook)
                                                      (json/generate-string {:count 3 :level 8})
                                                      {}))]
              (should= 202 (:status response))
              (should= marigold/starcore (:model-override @captured)))))))

    (it "passes the crew quarters cwd and webhook origin into dispatch"
      (let [hook-cfg  {:defaults {:crew marigold/captain :model "spark"}
                       :hooks    {marigold/lettuce-hook {:crew        marigold/captain
                                                         :session-key (str "hook:" marigold/lettuce-hook)
                                                         :template    "Report: {{count}} items, freshness {{level}}/10."}}
                       :crew     {marigold/captain {:soul (:soul (marigold/crew-cfg marigold/captain)) :model "spark"}}
                       :models   {"spark" {:model "helm-spark-1.0" :provider marigold/quantum-anvil :context-window 32768}}}
             captured  (atom nil)
             mem       (fs/mem-fs)
             mem-store (store/create nil :memory)]
        (sut/reset-registry!)
        (startup-hooks! (:hooks hook-cfg))
        (with-redefs [charge/build              (fn [input]
                                                  (reset! captured input)
                                                  {:charge/type :charge})
                      isaac.hooks/dispatch-turn! (fn [_] nil)]
          (nexus/-with-nexus {:root     "/tmp/hooks-home/.isaac"
                               :session-store mem-store
                               :fs            mem}
            (config/dangerously-install-config! hook-cfg "spec")
            (let [response (sut/handler (post-request (str "/hooks/" marigold/lettuce-hook)
                                                      (json/generate-string {:count 3 :level 8})
                                                      {}))]
              (should= 202 (:status response))
              (should= marigold/captain (:crew @captured))
              (should= {:kind :webhook :name marigold/lettuce-hook} (:origin @captured)))))))

    (it "logs hook dispatch planning details"
      (let [hook-cfg {:defaults {:crew marigold/captain :model "spark"}
                       :hooks    {marigold/lettuce-hook {:crew        marigold/captain
                                                         :session-key (str "hook:" marigold/lettuce-hook)
                                                         :model       marigold/starcore
                                                         :template    "Report: {{count}} items, freshness {{level}}/10."}}
                       :crew     {marigold/captain {:soul (:soul (marigold/crew-cfg marigold/captain)) :model "spark"}}
                       :models   {"spark"           {:model "helm-spark-1.0"  :provider marigold/quantum-anvil :context-window 32768}
                                  marigold/starcore {:model "starcore-7-fast" :provider marigold/starcore     :context-window 278528}}}
             mem      (fs/mem-fs)]
        (sut/reset-registry!)
        (startup-hooks! (:hooks hook-cfg))
        (with-redefs [isaac.hooks/dispatch-turn! (fn [_] nil)]
          (nexus/-with-nexus {:root     "/tmp/hooks-home/.isaac"
                               :session-store (store/create nil :memory)
                               :fs            mem}
            (config/dangerously-install-config! hook-cfg "spec")
            (log/capture-logs
              (let [response (sut/handler (post-request (str "/hooks/" marigold/lettuce-hook)
                                                        (json/generate-string {:count 3 :level 8})
                                                        {}))
                    entry    (first (filter #(= :hook/dispatch-planned (:event %)) @log/captured-logs))]
                (should= 202 (:status response))
                (should-not-be-nil entry)
                (should= marigold/lettuce-hook (:hook entry))
                (should= (str "hook:" marigold/lettuce-hook) (:session entry))
                (should= marigold/captain (:crew entry))
                (should= false (:existing-session? entry))
                (should= true (:has-model-override? entry))))))))

    (it "creates hook quarters through the installed runtime fs without binding a thread-local fs"
      (let [mem       (fs/mem-fs)
            mem-store (store/create nil :memory)]
        (with-redefs [isaac.hooks/dispatch-turn! (fn [_] nil)]
          (nexus/-with-nexus {:root     "/tmp/hooks-home/.isaac"
                               :session-store mem-store
                               :fs            mem}
            (config/dangerously-install-config! test-cfg "spec")
            (let [response (sut/handler (post-request (str "/hooks/" marigold/lettuce-hook)
                                                      (json/generate-string {:count 3 :level 8})
                                                      {}))]
              (should= 202 (:status response))
              #_{:clj-kondo/ignore [:invalid-arity]}
              (should (fs/exists? mem (str "/tmp/hooks-home/.isaac/crew/" marigold/captain)))))))))

  (describe "hook registry"

    #_{:clj-kondo/ignore [:invalid-arity]}
    (around [it]
      (sut/reset-registry!)
      (it)
      (sut/reset-registry!))

    (it "register-hook! adds a hook to the registry"
      (sut/register-hook! "ping" {:template "hello"} :module)
      (should-not-be-nil (sut/lookup-hook "ping")))

    (it "lookup-hook returns nil for unknown name"
      (should-be-nil (sut/lookup-hook "nope")))

    (it "deregister-hook! removes a registered hook"
      (sut/register-hook! "ping" {:template "hello"} :module)
      (sut/deregister-hook! "ping")
      (should-be-nil (sut/lookup-hook "ping")))

    (it "register-hook! logs :hook/registered"
      (log/capture-logs
        (sut/register-hook! "ping" {:template "hello"} :module)
        (should-not-be-nil (first (filter #(= :hook/registered (:event %)) @log/captured-logs)))))

    (it "deregister-hook! logs :hook/deregistered"
      (log/capture-logs
        (sut/register-hook! "ping" {:template "hello"} :module)
        (sut/deregister-hook! "ping")
        (should-not-be-nil (first (filter #(= :hook/deregistered (:event %)) @log/captured-logs)))))

    (it "throws on collision when module hook matches config hook name"
      (sut/register-hook! "ping" {:template "hello"} :config)
      (should-throw clojure.lang.ExceptionInfo
                    (sut/register-hook! "ping" (fn [_] nil) :module)))))
