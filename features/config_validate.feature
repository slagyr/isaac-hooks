Feature: Config Validate (hooks entities)
  `isaac config validate` checks hook entity files under config/hooks/ against
  the :hooks schema contributed by this module, reporting references to models
  that are not defined in the resolved config.

  Background:
    Given an Isaac root at "isaac-state"

  Scenario: validate reports unknown model refs with file and valid set
    Given config file "isaac.edn" containing:
      """
      {:defaults  {:crew :main :model :local}
       :crew      {:main {}}
       :models    {:local {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {}}}
      """
    And config file "hooks/webhook.edn" containing:
      """
      {:crew :main :model :ghost-model :template "Hello"}
      """
    When isaac is run with "config validate"
    Then the stderr matches:
      | pattern                                      |
      | hooks\.webhook\.model                      |
      | references undefined model                 |
      | file: config/hooks/webhook\.edn             |
      | bad value: ghost-model                      |
      | valid: .*local.*                            |
    And the exit code is 1
