package kz.aitu.registration.seating;

import java.util.Objects;
import java.util.UUID;

/**
 * Subsystem B entity: one seat in one course section, held for one student.
 *
 * <pre>
 *   HELD --confirm--> CONFIRMED
 *   HELD --release--> RELEASED
 *   CONFIRMED --release--> RELEASED   (the student dropped the course)
 *   RELEASED is terminal
 * </pre>
 * The entity enforces these transitions itself; callers cannot bypass them.
 */
public final class SeatReservation {

    public enum Status { HELD, CONFIRMED, RELEASED }

    @SuppressWarnings("serial")

    public static class IllegalTransitionException extends IllegalStateException {
        public IllegalTransitionException(Status from, Status to) {
            super("Seat cannot move from " + from + " to " + to);
        }
    }

    private final String id;
    private final String reference;
    private final String studentId;
    private final String courseId;
    private final String term;
    private Status status;

    private SeatReservation(String id, String reference, String studentId, String courseId, String term,
                            Status status) {
        this.id = Objects.requireNonNull(id);
        this.reference = Objects.requireNonNull(reference);
        this.studentId = Objects.requireNonNull(studentId);
        this.courseId = Objects.requireNonNull(courseId);
        this.term = Objects.requireNonNull(term);
        this.status = Objects.requireNonNull(status);
    }

    public static SeatReservation hold(String reference, String studentId, String courseId, String term) {
        return new SeatReservation(UUID.randomUUID().toString(), reference, studentId, courseId, term, Status.HELD);
    }

    public static SeatReservation restore(String id, String reference, String studentId, String courseId,
                                          String term, Status status) {
        return new SeatReservation(id, reference, studentId, courseId, term, status);
    }

    public void confirm() {
        moveTo(Status.CONFIRMED);
    }

    public void release() {
        moveTo(Status.RELEASED);
    }

    public boolean canMoveTo(Status target) {
        return isLegal(status, target);
    }

    /** A seat that is HELD or CONFIRMED counts against the capacity of the section. */
    public boolean occupiesSeat() {
        return status != Status.RELEASED;
    }

    private void moveTo(Status target) {
        if (!isLegal(status, target)) {
            throw new IllegalTransitionException(status, target);
        }
        status = target;
    }

    private static boolean isLegal(Status from, Status to) {
        return switch (from) {
            case HELD -> to == Status.CONFIRMED || to == Status.RELEASED;
            case CONFIRMED -> to == Status.RELEASED;
            case RELEASED -> false;
        };
    }

    public String id() { return id; }
    public String reference() { return reference; }
    public String studentId() { return studentId; }
    public String courseId() { return courseId; }
    public String term() { return term; }
    public Status status() { return status; }

    @Override
    public String toString() {
        return "Seat[" + studentId + " in " + courseId + " / " + term + ", " + status + "]";
    }
}
