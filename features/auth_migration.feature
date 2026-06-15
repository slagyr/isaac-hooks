Feature: Hooks auth config migration
  Webhook auth moved from `:hooks :auth :token` to server-wide
  `:server :auth :token`. The hooks schema keeps the old slot as a
  retired field so migrated configs get a clear validation error.

  Background:
    Given an Isaac root at "isaac-state"

  Scenario: Old :hooks :auth :token slot fails validation pointing to the new slot
    Given config file "isaac.edn" containing:
      """
      {:hooks {:auth {:token "leftover"}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key              | value                               |
      | hooks.auth.token | retired.*use :server :auth :token.* |