# Existing TrashSlot contents — 2026-09-09

The user explicitly stated that anything already placed in TrashSlot is meant
to be discarded. Automation no longer reads that GUI recovery buffer or blocks
because it contains a different item, a valuable item or an unavailable GUI slot.
The next normal, single-source disposal request may overwrite that retained item;
there is no separate delete-all or buffer-clearing request.

This does not allow selecting arbitrary inventory items for disposal. Rotten
tomato disposal still selects only an exactly matching rotten-tomato source;
logging waste still selects only the existing allowed logging waste. Tools,
diamonds, wood, berries, tomatoes and wine remain ineligible as new sources.
Server endpoint availability, exact current source, normal inventory, empty
cursor, full-inventory preservation evidence and late-reply/resend fences remain.

The old installed build completed a logging cycle after two obstruction leaves
were removed, but stopped at waste cleanup because one icicle occupied TrashSlot.
That was an unsent preflight rejection, not a server error or a failed chop.
This change removes that specific prerequisite.

Integrated offline Java 17 `test build` passed **1,976 tests / 147 suites**, with
zero failures, errors or skipped tests, including the cache and leaf-stance retry
changes. Live installation and post-restart checks are separate from that result.
