Feature: Hook config hot reload
  A running config harness updates hook registry entries when the
  hook slice changes at runtime.

  Background:
    Given default Grover hook setup

  @wip
  Scenario: Hook template content change is picked up at runtime
    Given the hook config path "hooks.cage-check.template" is "Brain checks the lock: {{status}}"
    And the hook config path "hooks.cage-check.session-key" is "hook:cage-check"
    And the hook config path "hooks.cage-check.crew" is "main"
    And the Isaac config harness is started
    And the hook "cage-check" registry entry has:
      | path     | value                             |
      | template | Brain checks the lock: {{status}} |
    When the hook config path "hooks.cage-check.template" is "Pinky checks the lock: {{status}}. Narf!"
    Then the hook "cage-check" registry entry has:
      | path     | value                                    |
      | template | Pinky checks the lock: {{status}}. Narf! |
