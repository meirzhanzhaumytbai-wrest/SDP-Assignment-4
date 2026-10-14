package kz.aitu.registration.facade;

public record SectionOverview(String courseId, String term, int capacity, int seatsInUse, boolean open) {
}
