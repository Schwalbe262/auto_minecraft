# Auto Valley 0.1.1 verification

Tested on 2026-09-07 with the existing Society Sunlit Valley 4.1.4 instance, Minecraft 1.20.1, Forge 47.4.0 and Java 17. No separate development client was used for this verification.

## Passed

- `test build`: 80 JUnit tests, zero failures/errors; production JAR reobfuscated successfully.
- The existing game was normally closed and restarted, preserving its instance and launch settings. No duplicate game remained.
- The real client loaded `autovalley 0.1.1` and joined the same existing server.
- Korean settings and connected-field candidate search opened in the real modpack. The earlier misleading block count is now distinguished from grouped field count; scan-edge groups are marked partial.
- The real game reported `connected=true`, `focused=false`, `running=true` across successive local snapshots. The visible HUD also showed ON while the window was inactive.
- Explicit local start/pause requests were acknowledged without operating-system keyboard/mouse input. Normal movement/click takeover remains implemented; unit tests cover Alt/Tab classification, foreground-only opt-out, background navigation, and disconnection.
- Scheduler integration tests reproduce and fix full-storage starvation, missing-wine-metadata starvation, and inventory relief between two wine batches.
- Downloaded GitHub release JAR and installed JAR match SHA256 `C0E0FBEC5EA23E4670B600E7C9F7D04C4029E1E395D06C45FF5FEEB7D890D0E5`.

## Not yet verified end to end

The local profile still has no registered farms or destinations. ON therefore waits safely for configuration. Automatic harvest, disposal, grade/year storage, refilling, shipping and sleeping have not completed an unattended cycle on this real server. Unit tests are not a substitute for that remaining verification. Farm bounds, container contents/classifications, disposal direction and connecting paths must be confirmed first.

Private addresses, coordinates, inventories and screenshots are intentionally excluded from this report and repository.
