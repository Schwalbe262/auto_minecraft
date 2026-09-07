package dev.schwalbe.autovalley.ui;

import dev.schwalbe.autovalley.core.MachineOutputLedger;
import dev.schwalbe.autovalley.core.PendingMachineOutput;

/** Pure final-confirmation guard: UI acknowledgement applies only to the exact item reviewed. */
final class PendingOutputReview {
    private PendingOutputReview() {}

    static boolean manualResolution(MachineOutputLedger.Resolution reason) {
        return reason == MachineOutputLedger.Resolution.RECOVERED_AND_HANDLED
                || reason == MachineOutputLedger.Resolution.CONFIRMED_LOST;
    }

    static boolean canConfirm(PendingMachineOutput selected, PendingMachineOutput current, MachineOutputLedger.Resolution reason) {
        return selected != null && selected.equals(current) && manualResolution(reason);
    }
}
