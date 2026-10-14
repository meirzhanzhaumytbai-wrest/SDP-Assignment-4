package kz.aitu.registration.facade;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainRecordAndLogTest {

    @TempDir
    Path dir;

    private ChainLogRepository log;

    private static ChainRecord newChain() {
        return ChainRecord.start(new RegistrationRequest(" S1 ", "CS301", "2026-FALL"));
    }

    @AfterEach
    void close() {
        if (log != null) {
            log.close();
        }
    }

    @Test
    void requestTrimsItsFieldsAndKnowsWhenItIsComplete() {
        RegistrationRequest request = new RegistrationRequest(" S1 ", " CS301", "2026-FALL ");
        assertEquals("S1", request.studentId());
        assertEquals("CS301", request.courseId());
        assertEquals("2026-FALL", request.term());
        assertTrue(request.isComplete());
        assertFalse(new RegistrationRequest(null, "C", "T").isComplete());
    }

    @Test
    void onlyInterruptedStatusesNeedRecovery() {
        assertTrue(ChainStatus.STARTED.isInterrupted());
        assertTrue(ChainStatus.PENDING_B.isInterrupted());
        assertTrue(ChainStatus.PENDING_C.isInterrupted());
        assertTrue(ChainStatus.COMPENSATING.isInterrupted());
        assertFalse(ChainStatus.COMPLETED.isInterrupted());
        assertFalse(ChainStatus.COMPENSATED.isInterrupted());
        assertFalse(ChainStatus.REJECTED.isInterrupted());
    }

    @Test
    @DisplayName("the chain walks STARTED -> PENDING_B -> PENDING_C -> COMPLETED and refuses shortcuts")
    void happyPathTransitionsAndGuards() {
        ChainRecord chain = newChain();
        assertEquals(ChainStatus.STARTED, chain.status());
        assertThrows(IllegalStateException.class, () -> chain.markSeatHeld("seat"));       // B before A
        chain.markClearanceHeld("clearance");
        assertEquals(ChainStatus.PENDING_B, chain.status());
        assertThrows(IllegalStateException.class, () -> chain.markClearanceHeld("again"));
        chain.markSeatHeld("seat");
        assertEquals(ChainStatus.PENDING_C, chain.status());
        chain.markCompleted("invoice");
        assertEquals(ChainStatus.COMPLETED, chain.status());
        assertEquals("invoice", chain.invoiceId());
        assertThrows(IllegalStateException.class, () -> chain.markCompleted("again"));
    }

    @Test
    void rejectedOnlyFromStartedAndNothingCanBeCompensatedAfterwards() {
        ChainRecord chain = newChain();
        chain.markRejected(Stage.A, "no");
        assertEquals(ChainStatus.REJECTED, chain.status());
        assertThrows(IllegalStateException.class, () -> chain.beginCompensation(null, "x"));

        ChainRecord later = newChain();
        later.markClearanceHeld("c");
        assertThrows(IllegalStateException.class, () -> later.markRejected(Stage.A, "late"));
    }

    @Test
    void compensationKeepsTheFirstFailureReasonWhenResumed() {
        ChainRecord chain = newChain();
        chain.markClearanceHeld("c");
        chain.beginCompensation(Stage.B, "first reason");
        chain.beginCompensation(null, "second reason");           // resumed after a crash
        assertEquals(Stage.B, chain.failedStage());
        assertEquals("first reason", chain.failureReason());
        chain.markCompensated(List.of(Stage.A));
        assertEquals(ChainStatus.COMPENSATED, chain.status());
        assertThrows(IllegalStateException.class, () -> chain.beginCompensation(null, "x"));
        assertThrows(IllegalStateException.class, () -> chain.markCompensated(List.of()));
    }

    @Test
    void completedChainsCanStillBeCompensatedButNotCompletedFromCompensating() {
        ChainRecord chain = newChain();
        chain.markClearanceHeld("c");
        chain.markSeatHeld("s");
        chain.markCompleted("i");
        chain.beginCompensation(null, "cancel");
        assertThrows(IllegalStateException.class, () -> chain.markCompleted("i"));
        assertTrue(chain.toString().contains("COMPENSATING"));
    }

    @Test
    @DisplayName("the chain log stores every field and gives it back after the store is reopened")
    void logRoundTripSurvivesReopen() {
        log = new ChainLogRepository(dir.resolve("facade"));
        ChainRecord chain = newChain();
        log.save(chain);
        chain.markClearanceHeld("clearance-1");
        chain.markSeatHeld("seat-1");
        chain.beginCompensation(Stage.C, "LIMIT_EXCEEDED: too much");
        chain.markCompensated(List.of(Stage.B, Stage.A));
        log.save(chain);
        log.close();

        log = new ChainLogRepository(dir.resolve("facade"));
        ChainRecord loaded = log.find(chain.id()).orElseThrow();
        assertEquals(ChainStatus.COMPENSATED, loaded.status());
        assertEquals("S1", loaded.studentId());
        assertEquals("clearance-1", loaded.clearanceId());
        assertEquals("seat-1", loaded.reservationId());
        assertNull(loaded.invoiceId());
        assertEquals(Stage.C, loaded.failedStage());
        assertEquals("LIMIT_EXCEEDED: too much", loaded.failureReason());
        assertEquals(List.of(Stage.B, Stage.A), loaded.undoneSteps());
        assertEquals(chain.createdAt(), loaded.createdAt());
        assertEquals(1, log.findAll().size());
    }

    @Test
    void logListsInsertionOrderAndFindsOnlyInterruptedChains() {
        log = new ChainLogRepository(dir.resolve("facade"));
        ChainRecord first = newChain();
        ChainRecord second = newChain();
        ChainRecord third = newChain();
        log.save(first);
        log.save(second);
        log.save(third);
        second.markRejected(Stage.A, "no");
        log.save(second);

        assertEquals(List.of(first.id(), second.id(), third.id()),
                log.findAll().stream().map(ChainRecord::id).toList());
        assertEquals(List.of(first.id(), third.id()),
                log.findInterrupted().stream().map(ChainRecord::id).toList());
        assertTrue(log.find("nope").isEmpty());
    }

    @Test
    void veryLongFailureReasonsAreTruncatedNotRejected() {
        log = new ChainLogRepository(dir.resolve("facade"));
        ChainRecord chain = newChain();
        chain.markClearanceHeld("c");
        chain.beginCompensation(Stage.B, "x".repeat(5000));
        log.save(chain);
        assertEquals(1000, log.find(chain.id()).orElseThrow().failureReason().length());
    }

    @Test
    void clearAllEmptiesTheLog() {
        log = new ChainLogRepository(dir.resolve("facade"));
        log.save(newChain());
        log.clearAll();
        assertTrue(log.findAll().isEmpty());
    }
}
