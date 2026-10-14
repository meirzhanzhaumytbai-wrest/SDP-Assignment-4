package kz.aitu.registration.seating;

import kz.aitu.registration.seating.SeatAllocationPolicy.Decision;
import kz.aitu.registration.seating.SeatReservation.IllegalTransitionException;
import kz.aitu.registration.seating.SeatReservation.Status;
import kz.aitu.registration.seating.SeatingService.NoSeatException;
import kz.aitu.registration.seating.SeatingService.NoSeatException.Reason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Subsystem B on its own: entity rules, policy, and the service over a real store. */
class SeatingTest {

    @Nested
    class ReservationLifecycle {
        private SeatReservation fresh() {
            return SeatReservation.hold("ref", "S1", "CS301", "2026-FALL");
        }

        @Test
        void startsHeldAndOccupiesASeat() {
            assertEquals(Status.HELD, fresh().status());
            assertTrue(fresh().occupiesSeat());
        }

        @Test
        void heldToConfirmedToReleasedIsLegal() {
            SeatReservation seat = fresh();
            seat.confirm();
            assertEquals(Status.CONFIRMED, seat.status());
            seat.release();
            assertEquals(Status.RELEASED, seat.status());
            assertFalse(seat.occupiesSeat());
        }

        @Test
        void heldCanBeReleasedDirectly() {
            SeatReservation seat = fresh();
            seat.release();
            assertEquals(Status.RELEASED, seat.status());
        }

        @Test
        void releasedIsTerminalAndConfirmedCannotGoBack() {
            SeatReservation released = fresh();
            released.release();
            assertThrows(IllegalTransitionException.class, released::confirm);
            assertThrows(IllegalTransitionException.class, released::release);

            SeatReservation confirmed = fresh();
            confirmed.confirm();
            assertThrows(IllegalTransitionException.class, confirmed::confirm);
            assertFalse(confirmed.canMoveTo(Status.HELD));
        }

        @Test
        void restoreAndToString() {
            SeatReservation seat = SeatReservation.restore("id", "ref", "S1", "CS1", "T", Status.CONFIRMED);
            assertEquals(Status.CONFIRMED, seat.status());
            assertTrue(seat.toString().contains("CONFIRMED"));
        }
    }

    @Nested
    class Policy {
        private final SeatAllocationPolicy policy = new SeatAllocationPolicy();

        @Test
        void decisionsFollowOpennessAndCapacity() {
            assertEquals(Decision.ALLOCATE, policy.decide(true, 2, 1));
            assertEquals(Decision.SECTION_FULL, policy.decide(true, 2, 2));
            assertEquals(Decision.SECTION_FULL, policy.decide(true, 0, 0));
            assertEquals(Decision.SECTION_CLOSED, policy.decide(false, 10, 0));
        }

        @Test
        void seatsLeftNeverGoesNegative() {
            assertEquals(1, policy.seatsLeft(2, 1));
            assertEquals(0, policy.seatsLeft(2, 5));
        }
    }

    @Nested
    class Service {
        @TempDir
        Path dir;

        private SeatingService seating;

        @BeforeEach
        void openFreshStore() {
            seating = SeatingService.openAt(dir.resolve("b"));
            seating.defineSection("CS301", "2026-FALL", 2, true);
        }

        @AfterEach
        void closeStore() {
            seating.close();
        }

        private Reason reasonOf(Runnable action) {
            return assertThrows(NoSeatException.class, action::run).reason();
        }

        @Test
        void allocatesSeatsUntilTheSectionIsFull() {
            seating.allocate("r1", "S1", "CS301", "2026-FALL");
            seating.allocate("r2", "S2", "CS301", "2026-FALL");
            assertEquals(0, seating.seatsLeft("CS301", "2026-FALL"));
            assertEquals(Reason.SECTION_FULL, reasonOf(() -> seating.allocate("r3", "S3", "CS301", "2026-FALL")));
            assertEquals(2, seating.findAll().size());
        }

        @Test
        @DisplayName("a refused allocation leaves nothing behind")
        void refusedAllocationStoresNothing() {
            seating.setCapacity("CS301", "2026-FALL", 0);
            reasonOf(() -> seating.allocate("r1", "S1", "CS301", "2026-FALL"));
            assertTrue(seating.findAll().isEmpty());
            assertTrue(seating.findByReference("r1").isEmpty());
        }

        @Test
        void closedAndUnknownSectionsAreRefused() {
            seating.setOpen("CS301", "2026-FALL", false);
            assertEquals(Reason.SECTION_CLOSED, reasonOf(() -> seating.allocate("r1", "S1", "CS301", "2026-FALL")));
            assertEquals(Reason.UNKNOWN_SECTION, reasonOf(() -> seating.allocate("r2", "S1", "CS999", "2026-FALL")));
        }

        @Test
        void sameStudentCannotHoldTwoSeatsInOneSection() {
            seating.allocate("r1", "S1", "CS301", "2026-FALL");
            assertEquals(Reason.ALREADY_SEATED, reasonOf(() -> seating.allocate("r2", "S1", "CS301", "2026-FALL")));
        }

        @Test
        void allocateIsIdempotentPerReference() {
            SeatReservation first = seating.allocate("r1", "S1", "CS301", "2026-FALL");
            SeatReservation again = seating.allocate("r1", "S1", "CS301", "2026-FALL");
            assertEquals(first.id(), again.id());
            assertEquals(1, seating.seatsInUse("CS301", "2026-FALL"));
        }

        @Test
        void confirmIsIdempotentAndNeedsAnExistingSeat() {
            seating.allocate("r1", "S1", "CS301", "2026-FALL");
            assertEquals(Status.CONFIRMED, seating.confirm("r1").status());
            assertEquals(Status.CONFIRMED, seating.confirm("r1").status());
            assertThrows(IllegalStateException.class, () -> seating.confirm("missing"));
        }

        @Test
        @DisplayName("release() frees the seat, reports whether it changed anything, and is safe to repeat")
        void releaseIsIdempotent() {
            seating.allocate("r1", "S1", "CS301", "2026-FALL");
            assertTrue(seating.release("r1"));
            assertFalse(seating.release("r1"));
            assertFalse(seating.release("never-existed"));
            assertEquals(2, seating.seatsLeft("CS301", "2026-FALL"));
        }

        @Test
        void releasedSeatCanBeTakenByAnotherStudentOrTheSameOne() {
            seating.setCapacity("CS301", "2026-FALL", 1);
            seating.allocate("r1", "S1", "CS301", "2026-FALL");
            seating.release("r1");
            assertEquals(Status.HELD, seating.allocate("r2", "S1", "CS301", "2026-FALL").status());
        }

        @Test
        void releaseWorksOnAConfirmedSeat() {
            seating.allocate("r1", "S1", "CS301", "2026-FALL");
            seating.confirm("r1");
            assertTrue(seating.release("r1"));
        }

        @Test
        @DisplayName("sections and reservations survive closing and reopening the store")
        void stateIsReadBackFromDiskAfterReopen() {
            seating.allocate("r1", "S1", "CS301", "2026-FALL");
            seating.confirm("r1");
            seating.setCapacity("CS301", "2026-FALL", 7);
            seating.close();

            seating = SeatingService.openAt(dir.resolve("b"));
            assertEquals(Status.CONFIRMED, seating.findByReference("r1").orElseThrow().status());
            assertEquals(7, seating.findSection("CS301", "2026-FALL").orElseThrow().capacity());
            assertEquals(6, seating.seatsLeft("CS301", "2026-FALL"));
            assertEquals(1, seating.allSections().size());
        }

        @Test
        void invalidAdministrationIsRefused() {
            assertThrows(IllegalArgumentException.class, () -> seating.defineSection("X", "T", -1, true));
            assertThrows(IllegalArgumentException.class, () -> seating.setCapacity("NOPE", "2026-FALL", 3));
            assertThrows(IllegalArgumentException.class, () -> seating.setOpen("NOPE", "2026-FALL", true));
            assertThrows(IllegalArgumentException.class, () -> seating.seatsLeft("NOPE", "2026-FALL"));
        }

        @Test
        void resetAllDataEmptiesTheStore() {
            seating.allocate("r1", "S1", "CS301", "2026-FALL");
            seating.resetAllData();
            assertTrue(seating.findAll().isEmpty());
            assertTrue(seating.allSections().isEmpty());
        }
    }
}
