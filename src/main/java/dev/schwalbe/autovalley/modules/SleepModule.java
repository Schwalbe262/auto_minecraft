package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.util.List;

/** Runs last: blocked work suppresses this module in the scheduler. */
public final class SleepModule implements AutomationModule {
    private long ticket = -1, attemptDay = Long.MIN_VALUE, startedDay, retryAt, sleepingSince;
    private int attempts;
    private boolean trying, observedSleeping, closing;
    @Override public Feature feature() { return Feature.SLEEP; }
    @Override public int priority() { return 100; }
    @Override public WorkResult tick(Context c) {
        long time = c.world().dayTime(), day = Math.floorDiv(time,24000);
        if (attemptDay != day && !trying) { attempts = 0; attemptDay = day; }
        if (trying && c.world().player().sleeping()) {
            if (!observedSleeping) sleepingSince = c.world().tick();
            observedSleeping = true;
        }
        if (trying && observedSleeping && day > startedDay) { reset(); return WorkResult.idle(); }
        if (ticket >= 0) {
            ActionOutcome outcome = c.actions().outcome(ticket);
            if (!outcome.done()) return WorkResult.busy("Waiting for bed interaction");
            ticket = -1;
            if (closing) {
                closing = false;
                if (!outcome.success()) return WorkResult.blocked("Cannot close the current container before sleeping");
            } else if (!outcome.success()) {
                trying = false; retryAt = c.world().tick() + 100;
                if (attempts >= 3) return WorkResult.blocked("Sleep was rejected three times; inspect the bed or server sleep conditions");
                return WorkResult.busy("Sleep was rejected; waiting before retry");
            }
        }
        if (trying) {
            if (observedSleeping) {
                if (c.world().tick() - sleepingSince > 1200) return WorkResult.blocked("Waiting for other players or the server to advance the day");
                if (!c.world().player().sleeping()) { trying = false; retryAt = c.world().tick() + 100; }
                else return WorkResult.busy("Sleeping; waiting for the next day");
            } else if (c.world().tick() - sleepingSince > c.profile().interactionTimeoutTicks) {
                trying = false; retryAt = c.world().tick() + 100;
            } else return WorkResult.busy("Waiting for actual sleeping state");
        }
        if (Math.floorMod(time,24000) < Math.max(12584,c.profile().sleepAtTick)) return WorkResult.idle();
        if (attempts >= 3) return WorkResult.blocked("Sleep retry limit reached for this day");
        if (c.world().tick() < retryAt) return WorkResult.busy("Waiting before another sleep attempt");
        List<Poi> beds = ModuleSupport.nearest(c,c.profile().pois(PoiKind.BED));
        if (beds.isEmpty()) return WorkResult.blocked("Register a bed for automatic sleep");
        if (c.world().menu().container()) {
            closing = true; ticket = c.actions().submit(new Action.CloseContainer(c.world().menu().id()));
            return WorkResult.busy("Closing container before sleeping");
        }
        Navigation.Result nav = c.navigation().moveTo(beds.get(0).pos(),2.5,c);
        if (nav == Navigation.Result.BLOCKED) return WorkResult.blocked("Registered bed cannot be reached");
        if (nav == Navigation.Result.ARRIVED) {
            attempts++; attemptDay = day; startedDay = day; trying = true; observedSleeping = false; sleepingSince = c.world().tick();
            ticket = c.actions().submit(new Action.UseBlock(beds.get(0).pos(),Action.Use.SLEEP));
        }
        return WorkResult.busy("Going to sleep");
    }
    @Override public void reset() { ticket = -1; attempts = 0; attemptDay = Long.MIN_VALUE; trying = false; observedSleeping = false; closing = false; retryAt = 0; sleepingSince = 0; }
}
