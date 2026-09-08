# Shared crops, commodity stores and artisan jobs

These are development-build additions, not claims about the public 0.1.2 JAR.
They have not yet been installed or exercised in the user's current game.

## Model and workflow

- `CropDefinition` separates item identity, allowed blocks, exact maturity
  predicates and game-day interval from `Farm` coordinates. Legacy three-argument
  farms remain tomatoes and retain their schedule keys. Tomato and ancient-fruit
  farms run the same optimized `HarvestModule` and native area-safety checks.
- `CommodityStore` names an allowed item set and a group of ordinary containers.
  It does not split storage by quality. Native stack identities and conservation
  checks still control each merge; incompatible NBT is never forcibly combined.
- `cropStores` explicitly links a crop to its destination. The harvest wrapper
  includes only those linked commodities in its one-shot cleanup. Existing
  tomato/wine storage and wine-cohort rules are preserved.
- `ArtisanRecipe` contains the exact machine, input cost, output identity/count,
  and day interval. `ArtisanJob` binds that recipe to machine positions and input /
  output groups. The current recipes are ancient-fruit seeds and jade crystals.
  Ancient-fruit wine is not implemented.
- `FruitPatch` registers exact starfruit blocks. `StarfruitModule` starts at a
  normal scheduler boundary, selects one ripe registered fruit within six blocks,
  and stores it. It does not interrupt an active operation or patrol every fruit.

Default intervals are ancient-fruit harvest 10 days, seeds 1 day, jade 5 days.
The jade interval comes from the installed recipe, not the tentative three-day
estimate. At execution time, all routines still verify actual maturity / working
state rather than clicking solely because a timer elapsed. The recording of
ancient fruit contained immature clicks, not a demonstrated ripe harvest.

The four additional feature switches default OFF and each supports one-shot
execution. Existing switches are not silently enabled or disabled. Inventory
ingredients are used before another source survey; a survey chooses the largest
available quality batch rather than continuously rebalancing stock levels.

## Native authority and acknowledgements

Harvest remains hoe / right-click only. Fruit collection allows empty hand or its
own fruit, never arbitrary tool/food borrowing. Artisan input must match the
registered recipe and its per-click cost. All operations remain loaded, reachable,
native-menu-owned and confined to the current feature / one-shot scope.

An artisan feed requires the latest target block receipt and the latest relevant
selected-slot server receipt after dispatch. Native identity compatibility remains
mandatory for any ingredient remainder. Same-ID jade output can arrive with input
consumption, so an explicit bounded net change plus machine state is used; hidden
consumption is not claimed as directly observed. When the last ingredient is used,
a known previous-batch output with different NBT may legitimately occupy the slot.

Sent artisan attempts have a bounded target-specific RAM fence. Timeout or cancel
does not permit another send to that target in the same connection generation.
Fresh matching server evidence releases the fence; ending the connection discards
old-channel uncertainty without marking the old attempt successful. This is not a
new persistent output-debt counter and does not globally stop other routines.

After its held items are stored and actions/menu/cursor are clean, an artisan
material or target-receipt wait explicitly allows the scheduler to sleep. The
default for other modules and navigation deferrals remains sleep-blocking; the
current clean boundary is rechecked before sleep. A one-shot never acquires
permission to run the sleep module or another feature.

Generic container access cannot override reserved legacy roles, shipping bins or
shops. Actual reciprocal double-chest halves are both checked at OPEN and transfer;
registering only the other half cannot evade a wine-cohort restriction. Generic
storage is deposit-only. Ingredient withdrawal is separately restricted to a
registered artisan job's exact input item and source group.

## Add-only bulk import

`WorkRegistrationImport` accepts only crops, farms, stores, crop/store links,
artisan jobs, fruit patches and a limited explicit enable set. It deep-copies and
validates the candidate before saving or changing the live context. Conflicting
definitions fail as a whole; identical imports are idempotent. Schedules and
unrelated flags cannot be injected through an import.

The runtime accepts only its fixed `config/autovalley/work-import.json`, with
bounded strict UTF-8 reads and no symlink target. Automation / recording must be
OFF with no unresolved start fence. The settings button and strict local
`import_work` command use the same path. This imports reviewed definitions, not
raw recordings or click replay. Profile schema 5 protects these fields from older
clients that would otherwise lose them on save.

A private candidate extracted from the user's recordings was validated against
a read-only snapshot of the real profile: it adds one ancient-fruit farm, four
storage groups, two artisan jobs and one fruit patch; enables no switches; retains
all existing registrations, flags and schedules; and passes repeat-import equality.
Only the detached candidate upgrades schema 4 to 5. The real profile is byte-
identical after this check. Candidate coordinates and recordings remain private.

## Native acceptance still required

After an authorized same-instance restart, import the reviewed definitions with
automation OFF. Check each one-shot for actual target acknowledgement, inventory
receipt and correct destination, then enable continuous routines as desired.
Verify a mature ancient-fruit harvest, seed collect/refill, jade collect/refill,
nearby starfruit collection and a safe handoff to bedtime. Test separate-day
eligibility without clearing existing schedules or replaying unconfirmed clicks.
