package kz.aitu.registration.app;

import kz.aitu.registration.eligibility.EligibilityService;
import kz.aitu.registration.finalization.FinalizationService;
import kz.aitu.registration.finalization.Invoice;
import kz.aitu.registration.seating.SeatingService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;


public final class StandaloneSubsystemsDemo {

    private StandaloneSubsystemsDemo() {
    }

    public static void main(String[] args) throws IOException {
        Path temp = Files.createTempDirectory("standalone-demo");
        try {
            eligibilityAlone(temp);
            seatingAlone(temp);
            finalizationAlone(temp);
        } finally {
            try (Stream<Path> files = Files.walk(temp)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static void eligibilityAlone(Path temp) {
        System.out.println("== A alone: eligibility ==");
        try (EligibilityService a = EligibilityService.openAt(temp.resolve("a"))) {
            a.defineCourse("CS201", Set.of("CS101"));
            a.registerStudent("S1", "Standalone Student", Set.of("CS101"), 0);
            System.out.println(a.clear("ref-1", "S1", "CS201", "2026-FALL"));
            System.out.println("revoked: " + a.revoke("ref-1") + ", again: " + a.revoke("ref-1"));
        }
    }

    private static void seatingAlone(Path temp) {
        System.out.println("== B alone: seating ==");
        try (SeatingService b = SeatingService.openAt(temp.resolve("b"))) {
            b.defineSection("CS201", "2026-FALL", 1, true);
            System.out.println(b.allocate("ref-1", "S1", "CS201", "2026-FALL"));
            try {
                b.allocate("ref-2", "S2", "CS201", "2026-FALL");
            } catch (SeatingService.NoSeatException e) {
                System.out.println("second student: " + e.reason());
            }
        }
    }

    private static void finalizationAlone(Path temp) {
        System.out.println("== C alone: finalization ==");
        try (FinalizationService c = FinalizationService.openAt(temp.resolve("c"))) {
            c.defineCourseCredits("CS201", 3);
            c.defineAccount("S1", 500_000, 0);
            Invoice invoice = c.finalizeRegistration("ref-1", "S1", "CS201", "2026-FALL");
            System.out.println(invoice + " / transcript " + c.transcriptOf("S1"));
            System.out.println("voided: " + c.voidRegistration("ref-1"));
        }
    }
}
