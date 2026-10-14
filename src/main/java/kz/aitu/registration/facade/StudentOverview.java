package kz.aitu.registration.facade;

public record StudentOverview(String studentId, String fullName, String completedCourses, long overdueDebt,
                              long creditLimit, int scholarshipPercent, long outstanding) {
}
