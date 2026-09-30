<!--
Lint convention (isaac.hooks.handbook-chapter-lint spec, following
isaac.foundation's own): a backtick `config:<dotted.path>` reference (no
angle-bracket placeholder inside the path) is checked against the composed
config schema, and the word right after `isaac ` in `isaac <command>` is
checked against the registered top-level CLI commands. Keep both literal
and real when you write one — the lint fails the build once either drifts
from what Isaac actually exposes. `<placeholder>` shapes are skipped.
-->

# isaac.hooks — inbound webhooks

You are a crew running inside Isaac. This chapter covers what
**isaac-hooks** owns: `POST /hooks/<name>`, the inbound webhook receiver
that turns an external HTTP call into a crew turn. Foundation's own
chapter (`handbook__read` topic `isaac.foundation`) covers config
mechanics, the vocabulary table, and hot reload — read it first if you
haven't. This chapter uses "crew", "session", and "turn" the way
`isaac.agent` defines them; that chapter owns session targeting
(frequencies: matching or creating a session by crew/tags/id) and the
turn dispatch mechanics themselves — this chapter names them once and
moves on. Everything about *who* is allowed to call a hook at all —
bearer tokens, principals, scopes — belongs to `isaac.http`; hooks
contributes only the route, not the auth check.

Hooks has no CLI commands and no comms of its own. It ships one HTTP
route, one config table, and a small in-memory registry that the route
handler reads from.

## Webhook entities

**What it is.** Each hook is a single config entity under
`config/hooks/<name>.md`: YAML frontmatter for its fields, plus a
required markdown body that is the hook's message **template**. The
entity's file name is the hook's name and the URL it answers on
(`/hooks/<name>`). A hook with an `:id` field must set it to that same
name — it exists so the id survives a `config get`/`config validate`
round-trip, not as a second name.

| Field | Type | Purpose |
|---|---|---|
| `:crew` | id | Sessions whose `:crew` matches are candidates. |
| `:session` | seq of strings | Explicit session id(s) — skips crew/tag matching. |
| `:session-key` | string | Legacy single explicit session id; prefer `:session`. |
| `:session-tags` | seq of keywords | Sessions must carry every tag listed (AND). |
| `:create` | keyword | `:never`, `:if-missing`, or `:always` — whether a missing target session gets created. |
| `:prefer` | keyword | `:recent` or `:oldest` — tiebreak when more than one session matches. |
| `:with-crew` | id | Overrides `:crew` for this turn only. |
| `:with-model` | id | Overrides the model for this turn only. |
| `:model` | id | Legacy alias for `:with-model`. |
| `:with-effort` | int | Overrides effort for this turn only. |
| `:with-context-mode` | keyword | Overrides context mode for this turn only. |
| `:template` | string | The rendered message body (the entity's markdown body, not a frontmatter field). |
| `:id` | id | Optional; must equal the filename when present. |

A hook that sets none of `:crew`, `:session`, `:session-tags`, or
`:session` falls back to an implicit session named `hook:<name>` — this
is the only case where the hook name itself picks the session. Setting
any describe selector (`:crew`, `:session-tags`) or an explicit
`:session`/`:session-key` turns that default off; you get exactly the
session your selectors resolve to, full stop.

**How to change it.** Write or edit the entity file directly, or set
individual fields:

```
config set hooks.lettuce.crew main
config set hooks.lettuce.create if-missing
config set hooks.lettuce.prefer recent
config set hooks.lettuce.template "Report: {{count}} items."
```

Session targeting itself (`:crew`, `:session-tags`, `:create`,
`:prefer`, and the `:with-*` turn overrides) is the same frequencies
shape `isaac.agent` uses elsewhere — read that chapter for how matching,
creation, and tiebreaking actually work; this table only says which
hook field maps to which frequency.

**How to verify.** `isaac config validate` reports a hook entity that
names an undefined `:crew` or `:model`/`:with-model` — the error names
the file (`config/hooks/<name>.edn` or `.md`) and lists the valid ids.
`handbook__read` topic `config:hooks.lettuce.template` (or any other
hook field) shows the live value and its schema.

### Troubleshooting

- **A hook config key you just set doesn't show up in `config get
  hooks.<name>`.** Confirm it's spelled as one of the fields above — an
  unrecognized key under a hook entity is rejected at validate time, not
  silently dropped, so if `config validate` is clean the key is
  genuinely unset.
- **`:model`/`:session-key` keep working but look deprecated.** They do
  work — they fold into `:with-model`/`:session` at dispatch time — but
  prefer the non-legacy field in new hooks; both names controlling the
  same behavior on the same entity is a config smell, not a hard error.
- **Renaming a hook's file doesn't rename the hook.** The URL and the
  registry key both come from the filename; there is no separate rename
  operation — write the new file and remove the old one (see Entity
  lifecycle, below, for what removal does to in-flight requests).

## Request pipeline

**What it is.** `POST /hooks/<name>` is the one route this module
contributes, to the server's `:isaac.http/route` berth, with `:scope
:hooks`. It is inert data until an HTTP host module is loaded — hooks
itself has no code dependency on `isaac-http`. Every request to
`/hooks/*` is checked in this order:

1. **Auth** (owned entirely by `isaac.http`, scope `:hooks`) — runs
   before path lookup, so a bad token gets 401 even for a hook name
   that doesn't exist. `403` means the token is valid but its scopes
   don't include `hooks`.
2. **Method** — only `POST` is accepted; anything else is `405`, even
   for a hook name that doesn't exist.
3. **Path lookup** — the name after `/hooks/` is looked up in the
   in-registry (see Entity lifecycle, below); no match is `404`.
4. **Content-type** — the request must be `application/json`; anything
   else is `415`.
5. **Body parse** — the body must parse as a JSON *object*; a parse
   failure or a top-level array/string/number is `400`.
6. **Dispatch** — a turn is started and the response is `202 Accepted`
   immediately; the turn itself runs asynchronously, after the response
   is already on the wire.

**How to change it.** The path and scope are fixed by this module (not
config); what you configure is *auth*, on the `isaac.http` side:

```
config set http.auth.token secret123
isaac http auth mint iphone --scopes hooks
```

A principal minted with `--scopes hooks` can call every hook; one
without it gets `403` and no turn starts, even for a hook that would
otherwise match.

**How to verify.** `curl -X POST .../hooks/<name> -H 'Content-Type:
application/json' -H 'Authorization: Bearer <token>' -d '{...}'` and
read the status code against the list above. `isaac logs server` shows
nothing for a rejected request below dispatch (auth/method/path/
content-type/parse failures aren't hook events) — a `401`/`403`/`404`/
`415`/`400` with no corresponding `:hook/*` log line is expected, not a
sign of a swallowed error.

### Troubleshooting

- **A hook returns 404 even though the entity file is right there.**
  Config was loaded but the hooks registry wasn't reconciled from it, or
  the file was added after the process last reloaded config — see Entity
  lifecycle, below.
- **A hook returns 403 for what looks like a valid token.** The
  principal's scopes don't include `hooks` — `isaac.http`'s chapter
  covers principals and scopes generally; `isaac http auth mint --help`
  lists a principal's current scopes.
- **A request that should be 400 (bad JSON) is instead accepted or
  hangs.** Confirm the request actually sets `Content-Type:
  application/json` — a non-JSON content-type short-circuits to `415`
  *before* the body is ever parsed, so a body-parsing bug can't be the
  cause of an unexpected 415.

## Session targeting and turn dispatch

**What it is.** Once a request clears the pipeline above, the hook's
frontmatter is turned into a frequencies map (`isaac.agent`'s session
targeting shape) and resolved against existing sessions:

- A **new** session created for a hook gets its `cwd` set to that crew's
  quarters (`<root>/crew/<crew-id>`) — the same quarters any other
  session for that crew would use.
- An **existing** matching session keeps whatever `cwd` it already has;
  a hook never moves a session's working directory.
- The turn's **origin** is recorded as a webhook kind carrying the hook
  name — visible in the session's own record, useful for telling a
  webhook-triggered turn apart from one started by a live comm.

The template body is rendered against the parsed JSON request: every
`{{field}}` present in the body is substituted; a `{{field}}` **not**
present in the body renders as the literal text `(missing)` rather than
failing the request — hooks have no contract schema over the inbound
JSON, so a caller sending a partial payload still gets a turn started,
just with gaps visibly marked for the crew (and any human reading the
transcript) to notice.

**How to change it.** Edit the template body or the selector fields (see
Webhook entities, above) — there's no separate config for rendering or
dispatch behavior itself.

**How to verify.** `isaac logs server` (or `cli`) logs
`:hook/dispatch-planned` on every accepted request — hook name, resolved
session id, crew, cwd, whether the session already existed, template
length, and whether a model override was in play — before the turn
itself starts running. A turn that fails inside dispatch logs
`:hook/dispatch-error` with the session key and the exception message;
this happens *after* the `202` has already been returned, so a caller
never sees it — only the log does.

### Troubleshooting

- **The response was 202 but no turn ever seems to have run.** Check
  `isaac logs server` for `:hook/dispatch-error` on that session — the
  turn is dispatched from a background future, so a failure inside it
  can't change the already-sent HTTP response.
- **A template field is rendering as `(missing)` even though the caller
  swears they sent it.** Match the `{{name}}` in the template against
  the exact top-level JSON key in the body — rendering is a flat
  substitution with no nested-path lookup, so `{{user.name}}` will never
  resolve against `{"user":{"name":...}}`; only a matching top-level key
  substitutes.
- **A hook keeps creating a brand-new session instead of reusing the one
  I expect.** Check `:create`/`:prefer` and whichever selector
  (`:crew`, `:session-tags`, explicit `:session`) the hook declares —
  this is `isaac.agent` frequency-resolution behavior, not something
  hooks decides on its own; multiple candidate sessions with `:prefer`
  unset defaults to the most recently updated one.

## Entity lifecycle and hot reload

**What it is.** Hooks maintains an in-memory registry of name → hook
spec, separate from the config tree itself, so a request never re-reads
config on the hot path. Two kinds of entries can be registered:

- **Config-sourced** — one entry per `config/hooks/<name>.md` (or
  `.edn`) entity. These are what this chapter has covered throughout.
- **Module-sourced** — a module can contribute to the
  `isaac.hooks/hook` berth with a `:factory` symbol; hooks resolves and
  calls it once, at load time, and registers whatever spec it returns.
  `[verify: no builtin module currently contributes a module-sourced
  hook — this path exists for module authors, not for an operator
  editing config]`.

A config-sourced and a module-sourced entry can never share a name —
that's a collision, logged as `:hook/collision` and raised as an error
at config-load time rather than silently letting one shadow the other.

The registry is kept in sync with config automatically: adding a hook
file registers it, editing one re-registers it with the new content,
and removing one deregisters it — a request to a name that was just
removed gets `404` on the very next request after the config reload
that dropped it. A module-sourced hook is never touched by a config
reload; only config-sourced ones are added, changed, or removed this
way.

**How to change it.** There's no separate on/off switch — writing,
editing, or deleting a `config/hooks/<name>.md` file *is* the lifecycle
operation. Hot reload picks it up the same way any other config change
does; no restart is needed (see foundation's chapter, Runtime → hot
reload).

**How to verify.** `isaac logs server` shows `:hook/registered` and
`:hook/deregistered` (each with the hook name and its source, `:config`
or `:module`) whenever the registry changes. A quick way to confirm a
file change actually took: POST to the hook immediately after editing
and check the status code (`404` before it's registered, `202` once it
is).

### Troubleshooting

- **Deleting a hook file doesn't 404 the route.** Config has to actually
  reload for the registry to notice — if hot reload is disabled for this
  install (`config:hot-reload` explicitly `false`), the old entry keeps
  answering until a restart, which is CLI-only (foundation's chapter).
- **"hook name collision" error on config load.** A config entity and a
  module-contributed hook (or two modules) both registered the same
  name — rename one of them; hooks refuses to pick a winner.
- **A module's contributed hook never shows up in the registry.** Its
  `:factory` symbol under the `isaac.hooks/hook` berth entry must
  resolve to a zero-arg function that returns the hook spec — an
  unresolvable symbol or a factory that throws prevents that one entry
  from registering `[verify: exact failure-mode surfacing for this case]`.

## Retired: per-hook auth token

**What it is.** Hooks used to carry its own auth slot,
`:hooks :auth :token`. That's retired — all inbound HTTP auth, hooks
included, now goes through the server-wide `:http :auth :token` (or
scoped principals) that `isaac.http` owns. The old slot is kept in the
schema only so a config still setting it fails loudly and points at the
replacement, instead of silently doing nothing.

**How to change it.** Remove `:hooks :auth :token` from config entirely
and set the replacement instead:

```
config unset hooks.auth.token
config set http.auth.token secret123
```

**How to verify.** `isaac config validate` (or plain config load)
reports a validation error naming `hooks.auth.token` as retired, with
the replacement path in the message, whenever that slot is still set.

### Troubleshooting

- **Config fails to load with a "retired" error mentioning
  `hooks.auth.token`.** That's this migration — the fix is deletion, not
  a value change: unset the old slot and confirm `:http :auth :token`
  (or a scoped principal) is configured instead.
