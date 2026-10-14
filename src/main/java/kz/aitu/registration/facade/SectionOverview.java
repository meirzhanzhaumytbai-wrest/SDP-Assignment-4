package kz.aitu.registration.facade;

/** Administrative snapshot of a course section, from subsystem B. */
public record SectionOverview(String courseId, String term, int capacity, int seatsInUse, boolean open) {
}
