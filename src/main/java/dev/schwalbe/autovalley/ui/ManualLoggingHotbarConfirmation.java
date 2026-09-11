package dev.schwalbe.autovalley.ui;

import dev.schwalbe.autovalley.core.*;
import java.util.Objects;

/** Read-only selection of one logging custody obligation; never waives unfinished planting. */
public final class ManualLoggingHotbarConfirmation {
    private ManualLoggingHotbarConfirmation() { }
    public record Selection(Profile owner,LoggingHotbarLease lease,String key) {
        public boolean matches(Profile current) {
            return current!=null && current==owner && ManualLoggingHotbarResolution.validLease(lease)
                && Objects.equals(lease,current.loggingHotbarLease)
                && Objects.equals(key,ManualLoggingHotbarResolution.confirmationKey(lease));
        }
    }
    public static Selection capture(Profile profile) {
        LoggingHotbarLease lease=profile==null?null:profile.loggingHotbarLease;
        return !ManualLoggingHotbarResolution.validLease(lease)?null
            :new Selection(profile,lease,ManualLoggingHotbarResolution.confirmationKey(lease));
    }
    public static boolean editable(Profile profile,LoggingLeafSettings.Boundary boundary) {
        // Unlike a settings edit, this workflow expects the logging lease itself.
        // All other native, connection, persistence and inventory boundaries stay quiet.
        return profile!=null && boundary!=null && !boundary.running() && !boundary.recording()
            && boundary.connected() && boundary.grounded() && boundary.normalMenu() && boundary.emptyCursor()
            && boundary.nativeQuiet() && boundary.persistenceReady()
            && ManualLoggingHotbarResolution.validLease(profile.loggingHotbarLease)
            && profile.workHotbarLease==null && profile.pendingMachineOutputs!=null
            && profile.pendingMachineOutputs.isEmpty();
    }
}
