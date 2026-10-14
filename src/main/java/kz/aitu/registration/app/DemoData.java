package kz.aitu.registration.app;

import kz.aitu.registration.eligibility.EligibilityService;
import kz.aitu.registration.finalization.FinalizationService;
import kz.aitu.registration.seating.SeatingService;

import java.util.Set;


public final class DemoData {

    public static final String TERM = "2026-FALL";

    private DemoData() {
    }

    public static void seed(EligibilityService eligibility, SeatingService seating, FinalizationService finalization) {
        // ---- A: who may take what
        eligibility.defineCourse("CS101", Set.of());
        eligibility.defineCourse("MATH101", Set.of());
        eligibility.defineCourse("CS201", Set.of("CS101"));
        eligibility.defineCourse("CS301", Set.of("CS201"));
        eligibility.defineCourse("CS350", Set.of("CS201"));
        eligibility.defineCourse("CS399", Set.of("CS201"));

        eligibility.registerStudent("S1001", "Aibek Nurlanov", Set.of("CS101", "MATH101", "CS201"), 0);
        eligibility.registerStudent("S1002", "Dana Sarsenova", Set.of("CS101", "MATH101", "CS201"), 0);
        eligibility.registerStudent("S1003", "Ermek Zhaksylykov", Set.of("CS101", "MATH101"), 0);
        eligibility.registerStudent("S1004", "Aigerim Bekova", Set.of("CS101", "MATH101", "CS201"), 150_000);
        eligibility.registerStudent("S1005", "Timur Abenov", Set.of("CS101", "MATH101", "CS201"), 0);

        // ---- B: seats
        seating.defineSection("CS201", TERM, 40, true);
        seating.defineSection("CS301", TERM, 25, true);
        seating.defineSection("CS350", TERM, 1, true);
        seating.defineSection("CS399", TERM, 0, true);

        // ---- C: fees and accounts
        finalization.defineCourseCredits("CS101", 3);
        finalization.defineCourseCredits("MATH101", 3);
        finalization.defineCourseCredits("CS201", 3);
        finalization.defineCourseCredits("CS301", 4);
        finalization.defineCourseCredits("CS350", 5);
        finalization.defineCourseCredits("CS399", 3);

        finalization.defineAccount("S1001", 1_000_000, 0);
        finalization.defineAccount("S1002", 100_000, 0);
        finalization.defineAccount("S1003", 1_000_000, 0);
        finalization.defineAccount("S1004", 1_000_000, 0);
        finalization.defineAccount("S1005", 1_000_000, 50);
        finalization.setBillingWindowOpen(true);
    }
}
