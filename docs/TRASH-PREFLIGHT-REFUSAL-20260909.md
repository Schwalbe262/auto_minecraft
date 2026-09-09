# TrashSlot preflight refusal

The installed client stopped twice with `Game rejected the action: IllegalStateException` at 09:33:49 and 09:34:16 UTC. A bounded read-only observation of the paused client found 64 cobblestone in TrashSlot's recovery buffer. Its installed protected-item predicate rejects that buffer. The failed ticket retained zero confirmed deletion; all 86 rotten tomatoes were unchanged and no pending/late deletion object remained. This is present-state evidence consistent with the constructor refusal, not a recovered historical exception stack.

The constructor previously threw a useful explanation, but MinecraftActions discarded that explanation and set a global deletion failure even before sending. Repeated manual start therefore encountered the same prerequisite again.

Changes:

- Read the recovery buffer before a disposal action is submitted and again during native construction.
- Report the protected registry ID/count and explicitly state that no deletion was sent.
- Keep the disposal job blocked, without creating a global uncertain-send fence for an unsent request. Other continuous jobs can run; unfinished disposal still suppresses normal automatic sleep.
- Preserve every post-send timeout, cancellation, late-reply fence and exact native deletion acknowledgement. Do not clear, replace, recover or delete the retained foreign item automatically.

The user was asked separately whether the retained cobblestone may be discarded. This change itself grants no such permission.

The pinned TrashSlot 15.1.3 server login handler explicitly sets the recovery item to EMPTY and sends that content to the client. Consequently a reconnect/restart would also discard this retained item. Deployment must wait for its recovery or the user's explicit discard decision; restarting is not a safe workaround for this preflight refusal.

Tests cover blocked preflight with no submitted action, unchanged rotten stock, normal resumption after the prerequisite clears, and the protected-item allowlist. Native constructor/send exception branches were additionally reviewed, but are not directly instantiated by those unit tests.

The focused integration run passed 274 tests across nine suites, including the normal-cooldown and storage-view changes. No live deletion was performed for this investigation.
