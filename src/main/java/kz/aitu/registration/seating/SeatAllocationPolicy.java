package kz.aitu.registration.seating;

/** Pure business logic: may another seat be handed out in a section? No state, no storage. */
public class SeatAllocationPolicy {

    public enum Decision { ALLOCATE, SECTION_CLOSED, SECTION_FULL }

    public Decision decide(boolean sectionOpen, int capacity, int seatsInUse) {
        if (!sectionOpen) {
            return Decision.SECTION_CLOSED;
        }
        if (seatsInUse >= capacity) {
            return Decision.SECTION_FULL;
        }
        return Decision.ALLOCATE;
    }

    public int seatsLeft(int capacity, int seatsInUse) {
        return Math.max(0, capacity - seatsInUse);
    }
}
