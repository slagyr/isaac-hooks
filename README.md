# 🍏 Isaac Hooks 🪝

<img align="left" width="200" src="https://raw.githubusercontent.com/slagyr/isaac-hooks/main/isaac-hooks.png" alt="isaac-hooks" style="margin-right: 20px; margin-bottom: 10px;">

Webhook ingress for the Isaac platform. Receives HTTP webhooks at `/hooks/*` and dispatches them to crews and prompts using configurable templates.

Depends on [isaac-foundation](https://github.com/slagyr/isaac-foundation) for module machinery. Contributes the `/hooks/*` route to the server (when present) and the `:isaac.hooks/hook` berth.

<br>

[![Hooks](https://github.com/slagyr/isaac-hooks/actions/workflows/ci-tests.yml/badge.svg)](https://github.com/slagyr/isaac-hooks/actions/workflows/ci-tests.yml) 
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Clojure](https://img.shields.io/badge/Clojure-1.11%2B-blue?logo=clojure)](https://clojure.org)
[![Babashka](https://img.shields.io/badge/Babashka-1.3%2B-red?logo=clojure)](https://babashka.org)
[![Java](https://img.shields.io/badge/Java-21%2B-orange?logo=openjdk)](https://openjdk.org/)

<br clear="left">

## What's here

- Webhook handler and route registration (`/hooks/*`).
- Hook registry and configuration schema for webhooks (in `config/hooks/`).
- Template rendering for webhook payloads.
- Integration with crews, sessions, and comm delivery.

## Development

Sibling checkouts expected:

```
plan/
  isaac-foundation/
  isaac-hooks/   # this repo
```

```sh
bb spec
bb features
bb ci
```

From the JVM, compose `:test` with a runner alias (shared test deps live in `:test` only):

```sh
clj -M:test:spec
clj -M:test:features
```

## Consumer coordinate

```clojure
io.github.slagyr/isaac-hooks {:local/root "../isaac-hooks"}
;; or {:git/url "https://github.com/slagyr/isaac-hooks.git" :git/sha "..."}
```
