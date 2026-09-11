package dev.schwalbe.autovalley.ui;

import dev.schwalbe.autovalley.core.*;
import java.util.Objects;

/** Read-only UI selection and settings boundary; only the core confirmation can write an audit. */
public final class ManualWorkHotbarConfirmation {
    private ManualWorkHotbarConfirmation() { }
    public record Selection(Profile owner,HotbarLease lease,String key) {
        public boolean matches(Profile current) {
            return current!=null && current==owner && lease!=null && lease.valid()
                && Objects.equals(lease,current.workHotbarLease)
                && Objects.equals(key,ManualWorkHotbarResolution.confirmationKey(lease));
        }
    }
    public static Selection capture(Profile profile) {
        HotbarLease lease=profile==null?null:profile.workHotbarLease;
        return lease==null || !lease.valid()?null:new Selection(profile,lease,ManualWorkHotbarResolution.confirmationKey(lease));
    }
    public static boolean editable(Profile profile,LoggingLeafSettings.Boundary boundary) {
        return LoggingLeafSettings.editable(profile,boundary) && profile.workHotbarLease!=null && profile.workHotbarLease.valid();
    }
}
