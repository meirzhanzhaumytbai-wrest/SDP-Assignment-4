package kz.aitu.registration.facade;

import kz.aitu.registration.eligibility.EligibilityService;
import kz.aitu.registration.finalization.FinalizationService;
import kz.aitu.registration.seating.SeatingService;

import java.util.ArrayList;
import java.util.List;


final class ChainCompensator {

    private final EligibilityService eligibility;
    private final SeatingService seating;
    private final FinalizationService finalization;

    ChainCompensator(EligibilityService eligibility, SeatingService seating, FinalizationService finalization) {
        this.eligibility = eligibility;
        this.seating = seating;
        this.finalization = finalization;
    }

    /** @return the steps that really had something to undo, in the order they were undone */
    List<Stage> compensate(String reference) {
        List<Stage> undone = new ArrayList<>();
        if (finalization.voidRegistration(reference)) {
            undone.add(Stage.C);
        }
        if (seating.release(reference)) {
            undone.add(Stage.B);
        }
        if (eligibility.revoke(reference)) {
            undone.add(Stage.A);
        }
        return undone;
    }
}
