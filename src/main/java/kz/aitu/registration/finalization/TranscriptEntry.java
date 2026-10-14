package kz.aitu.registration.finalization;

import java.util.Objects;
import java.util.UUID;

/**
 * Subsystem C entity: one line on the student's transcript.
 * IN_PROGRESS --withdraw--> WITHDRAWN (terminal).
 */
public final class TranscriptEntry {

    public enum Status { IN_PROGRESS, WITHDRAWN }

    @SuppressWarnings("serial")

    public static class IllegalTransitionException extends IllegalStateException {
        public IllegalTransitionException(Status from, Status to) {
            super("Transcript entry cannot move from " + from + " to " + to);
        }
    }

    private final String id;
    private final String reference;
    private final String studentId;
    private final String courseId;
    private final String term;
    private Status status;

    private TranscriptEntry(String id, String reference, String studentId, String courseId, String term,
                            Status status) {
        this.id = Objects.requireNonNull(id);
        this.reference = Objects.requireNonNull(reference);
        this.studentId = Objects.requireNonNull(studentId);
        this.courseId = Objects.requireNonNull(courseId);
        this.term = Objects.requireNonNull(term);
        this.status = Objects.requireNonNull(status);
    }

    public static TranscriptEntry open(String reference, String studentId, String courseId, String term) {
        return new TranscriptEntry(UUID.randomUUID().toString(), reference, studentId, courseId, term,
                Status.IN_PROGRESS);
    }

    public static TranscriptEntry restore(String id, String reference, String studentId, String courseId,
                                          String term, Status status) {
        return new TranscriptEntry(id, reference, studentId, courseId, term, status);
    }

    public void withdraw() {
        if (status != Status.IN_PROGRESS) {
            throw new IllegalTransitionException(status, Status.WITHDRAWN);
        }
        status = Status.WITHDRAWN;
    }

    public String id() { return id; }
    public String reference() { return reference; }
    public String studentId() { return studentId; }
    public String courseId() { return courseId; }
    public String term() { return term; }
    public Status status() { return status; }

    @Override
    public String toString() {
        return "Transcript[" + studentId + " " + courseId + "/" + term + ", " + status + "]";
    }
}
