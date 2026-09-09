# Storage grouping and TrashSlot fix deployment

The storage display change (`bc9dbf8`) and unsent-disposal refusal fix (`8599763`, with regression coverage in `98220b0`) had been built but were not yet loaded in the running client. A fresh saved-profile inspection found 32 tomato container registrations with 32 distinct positions, and the named ancient-fruit warehouse with 16 containers. No physical container registration was removed to simplify the list.

## Preserving the existing recovery item

Before restart, a private, one-use native recovery operation preserved the 64 cobblestone retained in TrashSlot. The procedure used the pinned TrashSlot recovery API once with an empty cursor, then one ordinary inventory placement through the existing client adapter. It did not delete the retained item, replay a request, write inventory state, fabricate an acknowledgement, or clear the old failed-action fence.

At 10:28:03 UTC the operation observed a raw server cursor packet containing the exact native stack, followed by a separate actual full-menu response proving that stack in an originally empty normal inventory slot. The cursor was empty and all other 45 menu slots matched the baseline exactly. The temporary passive observer was removed. These are separate proofs; applied-client menu state was not presented as a full server response.

## Installation

The existing Society client then shut down normally. Only after its exit did the restart script back up the old Auto Valley JAR, install the verified replacement, and launch the same instance with its existing authentication arguments kept private. No duplicate game or forced process termination was used.

Installed JAR SHA-256:

`9CB589A5B5A19F7DD1435C50F731C4EF7968DBA3B736F40FE7DCFE381C49194A`

The full build's test reports contain 1,800 tests across 136 suites with zero failures or errors. Restart/deployment alone is not proof of successful in-game disposal or visually rendered settings; live acceptance results are recorded separately below.

## Live acceptance

At 10:30:55 UTC a passive inspection of the reconnected client verified the expected active profile and the loaded JAR location. Both new trash-preflight entry points were present in the loaded classes. The actual storage display model used by settings contained:

- One tomato group: 32 members, 32 distinct positions, zero standalone tomato rows.
- One ancient-fruit warehouse group: 16 members, 16 distinct positions, the expected ancient-fruit item restriction.
- The existing seed, jade and starfruit stores, without changing their registrations.

The inspection preserved all registrations and eligibility dates. It checked the actual loaded display model, not a screenshot or synthetic profile fixture. The recovered 64 cobblestone were still in inventory, 98 rotten tomatoes remained, and no trash pending/in-flight/late/failure state was present in the new connection.

The player had opened settings after reconnect. A disposal-only live test was therefore held pending another brief control handoff; this inspection does not claim those 98 rotten tomatoes were deleted.
