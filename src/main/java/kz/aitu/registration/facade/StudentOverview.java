package kz.aitu.registration.facade;

/** Administrative snapshot of a student, assembled from subsystems A and C. */
public record StudentOverview(String studentId, String fullName, String completedCourses, long overdueDebt,
                              long creditLimit, int scholarshipPercent, long outstanding) {
}
