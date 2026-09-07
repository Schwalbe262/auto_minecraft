package dev.schwalbe.autovalley.ui;

import dev.schwalbe.autovalley.core.Feature;
import dev.schwalbe.autovalley.core.MachineOutputLedger.Resolution;
import dev.schwalbe.autovalley.core.PendingMachineOutput;
import dev.schwalbe.autovalley.core.Pos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PendingOutputReviewTest {
    private static PendingMachineOutput output(String id, int baseline, PendingMachineOutput.Phase phase) {
        return new PendingMachineOutput(id, Feature.PRESERVES, new Pos(1, 64, 2), 7, null, baseline, phase);
    }

    @Test void explicitRecoveryAndLossAreTheOnlyUiResolutions() {
        assertTrue(PendingOutputReview.manualResolution(Resolution.RECOVERED_AND_HANDLED));
        assertTrue(PendingOutputReview.manualResolution(Resolution.CONFIRMED_LOST));
        assertFalse(PendingOutputReview.manualResolution(Resolution.AUTOMATIC_PICKUP));
        assertFalse(PendingOutputReview.manualResolution(null));
    }

    @Test void identicalSnapshotCanBeAcknowledgedForEitherManualReason() {
        var selected = output("item-a", 128, PendingMachineOutput.Phase.AWAITING_PICKUP);
        var current = output("item-a", 128, PendingMachineOutput.Phase.AWAITING_PICKUP);
        assertTrue(PendingOutputReview.canConfirm(selected, current, Resolution.RECOVERED_AND_HANDLED));
        assertTrue(PendingOutputReview.canConfirm(selected, current, Resolution.CONFIRMED_LOST));
    }

    @Test void missingOrAlreadyResolvedItemCannotBeAcknowledged() {
        var selected = output("item-a", 128, PendingMachineOutput.Phase.AWAITING_PICKUP);
        assertFalse(PendingOutputReview.canConfirm(selected, null, Resolution.CONFIRMED_LOST));
        assertFalse(PendingOutputReview.canConfirm(null, selected, Resolution.CONFIRMED_LOST));
        assertFalse(PendingOutputReview.canConfirm(null, null, Resolution.CONFIRMED_LOST));
    }

    @Test void anotherIdCannotReplaceTheReviewedItem() {
        var selected = output("item-a", 128, PendingMachineOutput.Phase.AWAITING_PICKUP);
        var other = output("item-b", 128, PendingMachineOutput.Phase.AWAITING_PICKUP);
        assertFalse(PendingOutputReview.canConfirm(selected, other, Resolution.RECOVERED_AND_HANDLED));
    }

    @Test void changedPhaseOrBaselineRequiresReviewAgain() {
        var selected = output("item-a", 128, PendingMachineOutput.Phase.AWAITING_MACHINE_CONFIRMATION);
        assertFalse(PendingOutputReview.canConfirm(selected,
                output("item-a", 128, PendingMachineOutput.Phase.AWAITING_PICKUP), Resolution.RECOVERED_AND_HANDLED));
        assertFalse(PendingOutputReview.canConfirm(selected,
                output("item-a", 129, PendingMachineOutput.Phase.AWAITING_MACHINE_CONFIRMATION), Resolution.CONFIRMED_LOST));
    }

    @Test void automaticPickupOrUnselectedReasonNeverPassesFinalConfirmation() {
        var selected = output("item-a", 128, PendingMachineOutput.Phase.AWAITING_PICKUP);
        assertFalse(PendingOutputReview.canConfirm(selected, selected, Resolution.AUTOMATIC_PICKUP));
        assertFalse(PendingOutputReview.canConfirm(selected, selected, null));
    }
}
