package kz.aitu.registration.eligibility;

import kz.aitu.registration.eligibility.EligibilityClearance.IllegalTransitionException;
import kz.aitu.registration.eligibility.EligibilityClearance.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EligibilityClearanceTest {

    private EligibilityClearance fresh() {
        return EligibilityClearance.issue("ref-1", "S1", "CS301", "2026-FALL");
    }

    @Test
    @DisplayName("a new clearance starts as CLEARED and is active")
    void startsCleared() {
        EligibilityClearance clearance = fresh();
        assertEquals(Status.CLEARED, clearance.status());
        assertTrue(clearance.isActive());
    }

    @Test
    @DisplayName("CLEARED -> CONSUMED is legal")
    void clearedToConsumed() {
        EligibilityClearance clearance = fresh();
        clearance.consume();
        assertEquals(Status.CONSUMED, clearance.status());
        assertTrue(clearance.isActive());
    }

    @Test
    @DisplayName("CLEARED -> REVOKED and CONSUMED -> REVOKED are legal")
    void revokeFromBothActiveStates() {
        EligibilityClearance a = fresh();
        a.revoke();
        EligibilityClearance b = fresh();
        b.consume();
        b.revoke();
        assertEquals(Status.REVOKED, a.status());
        assertEquals(Status.REVOKED, b.status());
        assertFalse(a.isActive());
    }

    @Test
    @DisplayName("REVOKED is terminal: it can neither be consumed nor revoked again")
    void revokedIsTerminal() {
        EligibilityClearance clearance = fresh();
        clearance.revoke();
        assertThrows(IllegalTransitionException.class, clearance::consume);
        assertThrows(IllegalTransitionException.class, clearance::revoke);
        assertFalse(clearance.canMoveTo(Status.CLEARED));
    }

    @Test
    @DisplayName("CONSUMED cannot be consumed twice and cannot go back to CLEARED")
    void consumedCannotRepeat() {
        EligibilityClearance clearance = fresh();
        clearance.consume();
        assertThrows(IllegalTransitionException.class, clearance::consume);
        assertFalse(clearance.canMoveTo(Status.CLEARED));
    }

    @Test
    void restoreKeepsStoredStateWithoutApplyingRules() {
        EligibilityClearance restored = EligibilityClearance.restore("id", "ref", "S1", "CS1", "T", Status.REVOKED);
        assertEquals(Status.REVOKED, restored.status());
        assertTrue(restored.toString().contains("REVOKED"));
    }
}
