# Validation for 0.1.2

## Automated coverage

Java 17 / ForgeGradle `test build` completed successfully: **192 tests, zero failures or errors**.

- One-shot selection, saved-toggle preservation, temporary permission revocation, day cooldown, terminal completion and no blocked retry loop.
- UTF-8 named recording journals, event ordering, bounded payload and metadata, disconnected-session naming, real file-move failures and safe retry without losing captured gameplay.
- Strict scoped control-request parsing: once/record commands, duplicate/unknown fields, invalid UTF-8 and oversized payloads.
- Magnet overflow ledger, confirmed deposits with instant pickup, lost ground items, production/sleep gating and no duplicate ACK accounting.
- Immutable eight-snapshot menu ACK history, reconnect/marker invalidation, wine sale freshness/cohort/registration/budget checks.
- Multi-field persistence and existing destination preservation, connected machine registration, native-clock wine cohorts, traversal and mature-crop behavior.

## Real-client scope

The preceding 0.1.1 installation verified background ON/OFF using the actual Society instance, without injecting OS input. That does not establish real execution of the new 0.1.2 features. Record installation and runtime smoke-test results below after verification.

- Installed and loaded 0.1.2 in the same Society 4.1.4 instance after a normal shutdown; the old JAR is recoverable in the instance's local backup directory.
- The first Quick Play attempt failed with Unknown Host. Windows and a read-only probe in that same JVM subsequently both resolved the host and connected to the requested TCP port. A normal in-client rejoin succeeded. The exact cause of the initial transient address-resolution failure is not established; a remaining error screen is not evidence that DNS is still failing.
- A fresh client snapshot confirmed `connected=true`, automation OFF, and a non-null native Vinery calendar. Registered farms and destinations compare equal before/after the restart. Private hostnames, coordinates and inventory contents are omitted here.

The existing user profile is preserved rather than replaced. Exact warehouse classifications, rack/array registrations and connecting routes still need confirmation. No complete unattended harvest-to-shipping/sleep cycle is claimed by the unit tests or by a successful recorder smoke test.
