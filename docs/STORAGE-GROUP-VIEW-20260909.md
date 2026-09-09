# Storage groups in settings

The Saved page now shows the legacy tomato containers as one group with a paginated member list. Named commodity stores are visible even when their containers are not individual POIs, including the ancient-fruit warehouse. Rows identify the name, permitted items and member count. Existing tomato-member edit/unregister controls remain available; unregister still works for an unloaded or removed chest and rolls back on save failure.

This is a display model, not a migration: storage permissions, item classification, crop schedules, POIs and wine-age/cohort assignments are unchanged. Stale details cannot act on a replaced profile or edited registration.

The harvest interval field now says tomato explicitly. Ancient-fruit's actual crop-definition interval is displayed separately as read-only rather than presenting the tomato interval as a global crop setting.

## Requested wine-production group merge

At 09:47:14 UTC, the currently connected client saved one presentation group named `와인통 구역`, containing the exact 384 previously registered wine kegs (legacy groups of 288 and 96). This was the user's requested merge, not a world scan. No unregistered neighbouring machines were added. A subsequent comparison with the atomic pre-save backup found no changed profile fields other than `machineGroups`; registered wine-keg count remained 384.

No game action, restart, deletion, production-date change or action-fence change was performed by that metadata merge. The existing production schedule and every wine storage cohort are preserved.
