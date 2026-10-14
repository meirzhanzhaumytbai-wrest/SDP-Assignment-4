package kz.aitu.registration.seating;

import kz.aitu.registration.seating.SeatRepository.Section;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;


public class SeatingService implements AutoCloseable {

    @SuppressWarnings("serial")

    public static class NoSeatException extends RuntimeException {
        public enum Reason { UNKNOWN_SECTION, SECTION_CLOSED, SECTION_FULL, ALREADY_SEATED }

        private final Reason reason;

        public NoSeatException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    private final SeatRepository repository;
    private final SeatAllocationPolicy policy;

    public SeatingService(SeatRepository repository) {
        this(repository, new SeatAllocationPolicy());
    }

    public SeatingService(SeatRepository repository, SeatAllocationPolicy policy) {
        this.repository = repository;
        this.policy = policy;
    }

    public static SeatingService openAt(Path databaseFile) {
        return new SeatingService(new SeatRepository(databaseFile));
    }

    // ---------------------------------------------------------------- the operations of the subsystem

    /**
     * Holds one seat for the student. Counting the seats and storing the reservation happen in a single
     * transaction, so a failed allocation leaves nothing behind.
     */
    public SeatReservation allocate(String reference, String studentId, String courseId, String term) {
        Optional<SeatReservation> existing = repository.findReservationByReference(reference);
        if (existing.isPresent()) {
            return existing.get();
        }
        return repository.inTransaction(() -> {
            Section section = repository.findSection(courseId, term).orElseThrow(() ->
                    new NoSeatException(NoSeatException.Reason.UNKNOWN_SECTION,
                            "No section " + courseId + " in " + term));
            if (repository.studentHoldsSeat(studentId, courseId, term)) {
                throw new NoSeatException(NoSeatException.Reason.ALREADY_SEATED,
                        studentId + " already holds a seat in " + courseId + " / " + term);
            }
            int inUse = repository.countSeatsInUse(courseId, term);
            switch (policy.decide(section.open(), section.capacity(), inUse)) {
                case SECTION_CLOSED -> throw new NoSeatException(NoSeatException.Reason.SECTION_CLOSED,
                        "Section " + courseId + " / " + term + " is closed");
                case SECTION_FULL -> throw new NoSeatException(NoSeatException.Reason.SECTION_FULL,
                        "Section " + courseId + " / " + term + " is full (" + inUse + "/" + section.capacity() + ")");
                case ALLOCATE -> {
                    // fall through to creating the reservation
                }
            }
            SeatReservation reservation = SeatReservation.hold(reference, studentId, courseId, term);
            repository.insertReservation(reservation);
            return reservation;
        });
    }

    /** HELD -> CONFIRMED. Idempotent. */
    public SeatReservation confirm(String reference) {
        SeatReservation reservation = repository.findReservationByReference(reference).orElseThrow(() ->
                new IllegalStateException("No seat for reference " + reference));
        if (reservation.status() == SeatReservation.Status.CONFIRMED) {
            return reservation;
        }
        reservation.confirm();
        repository.updateReservationStatus(reservation);
        return reservation;
    }

    /**
     * Gives the seat back (compensation). Idempotent.
     *
     * @return true if a seat was actually released, false if there was nothing to undo
     */
    public boolean release(String reference) {
        Optional<SeatReservation> found = repository.findReservationByReference(reference);
        if (found.isEmpty() || !found.get().occupiesSeat()) {
            return false;
        }
        SeatReservation reservation = found.get();
        reservation.release();
        repository.updateReservationStatus(reservation);
        return true;
    }

    // ---------------------------------------------------------------- queries / administration

    public Optional<SeatReservation> findByReference(String reference) {
        return repository.findReservationByReference(reference);
    }

    public List<SeatReservation> findAll() {
        return repository.findAllReservations();
    }

    public int seatsLeft(String courseId, String term) {
        Section section = repository.findSection(courseId, term).orElseThrow(() ->
                new IllegalArgumentException("No section " + courseId + " in " + term));
        return policy.seatsLeft(section.capacity(), repository.countSeatsInUse(courseId, term));
    }

    public void defineSection(String courseId, String term, int capacity, boolean open) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity must not be negative");
        }
        repository.saveSection(new Section(courseId, term, capacity, open));
    }

    public void setCapacity(String courseId, String term, int capacity) {
        Section section = requireSection(courseId, term);
        defineSection(courseId, term, capacity, section.open());
    }

    public void setOpen(String courseId, String term, boolean open) {
        Section section = requireSection(courseId, term);
        defineSection(courseId, term, section.capacity(), open);
    }

    public Optional<Section> findSection(String courseId, String term) {
        return repository.findSection(courseId, term);
    }

    public List<Section> allSections() {
        return repository.findAllSections();
    }

    public int seatsInUse(String courseId, String term) {
        return repository.countSeatsInUse(courseId, term);
    }

    public void resetAllData() {
        repository.clearAll();
    }

    private Section requireSection(String courseId, String term) {
        return repository.findSection(courseId, term).orElseThrow(() ->
                new IllegalArgumentException("No section " + courseId + " in " + term));
    }

    @Override
    public void close() {
        repository.close();
    }
}
