package kz.aitu.registration.facade;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One row of the facade's durable chain log. The id doubles as the opaque {@code reference} handed to
 * every subsystem, which is how the facade later finds "its" records inside A, B and C.
 * Clients can read it; only the facade package can change it.
 */
public final class ChainRecord {

    private final String id;
    private final String studentId;
    private final String courseId;
    private final String term;
    private final Instant createdAt;
    private ChainStatus status;
    private String clearanceId;
    private String reservationId;
    private String invoiceId;
    private Stage failedStage;
    private String failureReason;
    private List<Stage> undoneSteps = new ArrayList<>();
    private Instant updatedAt;

    private ChainRecord(String id, String studentId, String courseId, String term, Instant createdAt) {
        this.id = id;
        this.studentId = studentId;
        this.courseId = courseId;
        this.term = term;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    static ChainRecord start(RegistrationRequest request) {
        ChainRecord chain = new ChainRecord(UUID.randomUUID().toString(), request.studentId(), request.courseId(),
                request.term(), Instant.now());
        chain.status = ChainStatus.STARTED;
        return chain;
    }

    static ChainRecord restore(String id, String studentId, String courseId, String term, Instant createdAt,
                               ChainStatus status, String clearanceId, String reservationId, String invoiceId,
                               Stage failedStage, String failureReason, List<Stage> undoneSteps,
                               Instant updatedAt) {
        ChainRecord chain = new ChainRecord(id, studentId, courseId, term, createdAt);
        chain.status = status;
        chain.clearanceId = clearanceId;
        chain.reservationId = reservationId;
        chain.invoiceId = invoiceId;
        chain.failedStage = failedStage;
        chain.failureReason = failureReason;
        chain.undoneSteps = new ArrayList<>(undoneSteps);
        chain.updatedAt = updatedAt;
        return chain;
    }

    // ---------------------------------------------------------------- transitions (facade only)

    void recordClearance(String clearanceId) {
        this.clearanceId = clearanceId;
    }

    void recordSeat(String reservationId) {
        this.reservationId = reservationId;
    }

    void recordInvoice(String invoiceId) {
        this.invoiceId = invoiceId;
    }

    void markClearanceHeld(String clearanceId) {
        require(ChainStatus.STARTED);
        recordClearance(clearanceId);
        moveTo(ChainStatus.PENDING_B);
    }

    void markSeatHeld(String reservationId) {
        require(ChainStatus.PENDING_B);
        recordSeat(reservationId);
        moveTo(ChainStatus.PENDING_C);
    }

    void markCompleted(String invoiceId) {
        if (!status.isInterrupted() || status == ChainStatus.COMPENSATING) {
            throw new IllegalStateException("Chain " + id + " cannot complete from " + status);
        }
        recordInvoice(invoiceId);
        moveTo(ChainStatus.COMPLETED);
    }

    void markRejected(Stage stage, String reason) {
        require(ChainStatus.STARTED);
        this.failedStage = stage;
        this.failureReason = reason;
        moveTo(ChainStatus.REJECTED);
    }

    /** Starts (or, after a crash, resumes) the undo. The first failure reason is kept. */
    void beginCompensation(Stage stage, String reason) {
        if (status == ChainStatus.REJECTED || status == ChainStatus.COMPENSATED) {
            throw new IllegalStateException("Chain " + id + " is " + status + ", there is nothing to compensate");
        }
        if (status != ChainStatus.COMPENSATING) {
            this.failedStage = stage;
            this.failureReason = reason;
        }
        moveTo(ChainStatus.COMPENSATING);
    }

    void markCompensated(List<Stage> undone) {
        require(ChainStatus.COMPENSATING);
        this.undoneSteps = new ArrayList<>(undone);
        moveTo(ChainStatus.COMPENSATED);
    }

    private void require(ChainStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Chain " + id + " is " + status + ", expected " + expected);
        }
    }

    private void moveTo(ChainStatus next) {
        this.status = next;
        this.updatedAt = Instant.now();
    }

    // ---------------------------------------------------------------- read access

    public String id() { return id; }
    public String studentId() { return studentId; }
    public String courseId() { return courseId; }
    public String term() { return term; }
    public Instant createdAt() { return createdAt; }
    public ChainStatus status() { return status; }
    public String clearanceId() { return clearanceId; }
    public String reservationId() { return reservationId; }
    public String invoiceId() { return invoiceId; }
    public Stage failedStage() { return failedStage; }
    public String failureReason() { return failureReason; }
    public List<Stage> undoneSteps() { return List.copyOf(undoneSteps); }
    public Instant updatedAt() { return updatedAt; }

    @Override
    public String toString() {
        return "Chain[" + id.substring(0, 8) + " " + studentId + " -> " + courseId + "/" + term + ", " + status + "]";
    }
}
