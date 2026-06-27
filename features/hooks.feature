Feature: Webhook receiver
  Isaac exposes POST /hooks/<name> for inbound webhooks. Each hook is
  declared as a single markdown config entity at config/hooks/<name>.md
  with YAML frontmatter and a markdown template body. A successful POST
  substitutes JSON body fields into the template and dispatches a turn
  on the configured session and crew.

  Auth runs before path lookup: missing/wrong token returns 401 even
  for unknown paths. After auth, method is checked (only POST is
  allowed), then path lookup (404 if no entity), then content-type
  (only application/json), then body parse (400 if unparseable).
  Successful dispatches return 202 immediately — the turn runs
  asynchronously.

  Webhooks have no contract schema. Every {{var}} reference in the
  template is best-effort: present in the body → substituted; absent
  → rendered as "(missing)" so the LLM and any human triaging the
  transcript can see what wasn't provided.

  Background:
    Given default Grover setup
    And the isaac file "config/hooks/lettuce.md" exists with:
      """
      ---
      crew: main
      session-key: hook:lettuce
      ---

      Hieronymus's emergency lettuce report — {{leaves}} leaves remaining, freshness {{freshness}}/10, expires in {{daysToExpiry}} days.
      """
    And config:
      | key               | value     |
      | bind-server-port  | false     |
      | server.host       | 0.0.0.0   |
      | server.auth.token | secret123 |
      | server.port       | 0         |
    And the Isaac server is started

  Scenario: new hook session defaults cwd to the crew quarters
    When a POST request is made to "/hooks/lettuce":
      | key                  | value            |
      | body                 | {}               |
      | header.Authorization | Bearer secret123 |
    Then the response status is 202
    And session "hook:lettuce" matches:
      | key | value                              |
      | cwd | #".*/target/test-state/crew/main$" |

  Scenario: existing hook session keeps its own cwd
    Given the following sessions exist:
      | name         | crew | cwd           |
      | hook:lettuce | main | /tmp/my-place |
    When a POST request is made to "/hooks/lettuce":
      | key                  | value            |
      | body                 | {}               |
      | header.Authorization | Bearer secret123 |
    Then the response status is 202
    And session "hook:lettuce" matches:
      | key | value         |
      | cwd | /tmp/my-place |

  Scenario: configured hook fires a turn with template substitution
    When a POST request is made to "/hooks/lettuce":
      | key                  | value                                        |
      | body                 | {"leaves":12,"freshness":7,"daysToExpiry":4} |
      | header.Authorization | Bearer secret123                             |
    Then the response status is 202
    And session "hook:lettuce" has transcript matching:
      | type    | message.role | message.content                                                                                  |
      | message | user         | Hieronymus's emergency lettuce report — 12 leaves remaining, freshness 7/10, expires in 4 days.  |

  Scenario: missing bearer token returns 401 even for unknown paths
    When a POST request is made to "/hooks/does-not-exist":
      | key  | value |
      | body | {}    |
    Then the response status is 401

  Scenario: valid token on unknown path returns 404
    When a POST request is made to "/hooks/does-not-exist":
      | key                  | value            |
      | body                 | {}               |
      | header.Authorization | Bearer secret123 |
    Then the response status is 404

  Scenario: GET on a configured hook returns 405
    When a GET request is made to "/hooks/lettuce":
      | key                  | value            |
      | header.Authorization | Bearer secret123 |
    Then the response status is 405

  Scenario: non-JSON content-type returns 415
    When a POST request is made to "/hooks/lettuce":
      | key                  | value            |
      | body                 | crisp and ready  |
      | header.Authorization | Bearer secret123 |
      | header.Content-Type  | text/plain       |
    Then the response status is 415

  Scenario: malformed JSON body returns 400
    When a POST request is made to "/hooks/lettuce":
      | key                  | value            |
      | body                 | not valid json   |
      | header.Authorization | Bearer secret123 |
    Then the response status is 400

  Scenario: missing template field renders (missing)
    When a POST request is made to "/hooks/lettuce":
      | key                  | value                          |
      | body                 | {"leaves":12,"daysToExpiry":4} |
      | header.Authorization | Bearer secret123               |
    Then the response status is 202
    And session "hook:lettuce" has transcript matching:
      | type    | message.role | message.content                                                                                          |
      | message | user         | Hieronymus's emergency lettuce report — 12 leaves remaining, freshness (missing)/10, expires in 4 days.  |

  Scenario: hook frequencies resolve the most recent matching crew session
    Given the following sessions exist:
      | name       | crew | updated-at          |
      | morning    | main | 2026-04-10T08:00:00 |
      | last-night | main | 2026-04-12T22:00:00 |
    And session "last-night" has transcript:
      | type    | message.role | message.content |
      | message | user         | prior note      |
    And the isaac file "config/hooks/garden.md" exists with:
      """
      ---
      crew: main
      create: if-missing
      prefer: recent
      ---

      Garden report: {{leaves}} leaves fallen.
      """
    When a POST request is made to "/hooks/garden":
      | key                  | value        |
      | body                 | {"leaves":3} |
      | header.Authorization | Bearer secret123 |
    Then the response status is 202
    And session "last-night" has transcript matching:
      | type    | message.role | message.content                 |
      | message | user         | Garden report: 3 leaves fallen. |

  Scenario: hook with-model override dispatches on the overridden model
    Given the isaac EDN file "config/models/grover2.edn" exists with:
      | path           | value  |
      | model          | echo-alt |
      | provider       | grover |
      | context-window | 16384  |
    And the following sessions exist:
      | name      | crew |
      | garden-wk | main |
    And the following model responses are queued:
      | model    | type | content |
      | echo-alt | text | Raked.  |
    And the isaac file "config/hooks/garden.md" exists with:
      """
      ---
      crew: main
      with-model: grover2
      ---

      Garden report: {{leaves}} leaves fallen.
      """
    When a POST request is made to "/hooks/garden":
      | key                  | value        |
      | body                 | {"leaves":3} |
      | header.Authorization | Bearer secret123 |
    Then the response status is 202
    And session "garden-wk" has transcript matching:
      | type    | message.model | message.content |
      | message | echo-alt      | Raked.          |

  Scenario: Removing a hook config file returns 404 on the next POST
    When a POST request is made to "/hooks/lettuce":
      | key                  | value                                        |
      | body                 | {"leaves":12,"freshness":7,"daysToExpiry":4} |
      | header.Authorization | Bearer secret123                             |
    Then the response status is 202
    When the isaac file "config/hooks/lettuce.md" is removed
    And the isaac config is reloaded
    And a POST request is made to "/hooks/lettuce":
      | key                  | value            |
      | body                 | {}               |
      | header.Authorization | Bearer secret123 |
    Then the response status is 404