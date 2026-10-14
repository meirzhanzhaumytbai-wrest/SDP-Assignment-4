package kz.aitu.registration.app;

import kz.aitu.registration.facade.RegistrationRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistrationSystemTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("data written by one run is still there when the system is opened again")
    void dataSurvivesReopeningTheSystem() {
        try (RegistrationSystem system = RegistrationSystem.open(dir)) {
            system.seedDemoDataIfEmpty();
            system.registration().register(new RegistrationRequest("S1001", "CS301", DemoData.TERM));
        }
        try (RegistrationSystem system = RegistrationSystem.open(dir)) {
            system.seedDemoDataIfEmpty();                      // must not duplicate or overwrite anything
            assertEquals(1, system.admin().listChains().size());
            assertEquals(5, system.admin().listStudents().size());
            assertEquals(24, system.admin().listSections().stream()
                    .filter(s -> s.courseId().equals("CS301")).findFirst().orElseThrow().capacity() - 1);
            assertTrue(system.registration().recoverInterruptedChains().isEmpty());
        }
    }

    @Test
    void resetRestoresTheDemoUniversityAndEmptiesTheLog() {
        try (RegistrationSystem system = RegistrationSystem.open(dir)) {
            system.seedDemoDataIfEmpty();
            system.registration().register(new RegistrationRequest("S1001", "CS301", DemoData.TERM));
            system.admin().setCreditLimit("S1001", 1);

            system.resetToDemoData();

            assertTrue(system.admin().listChains().isEmpty());
            assertEquals(1_000_000, system.admin().listStudents().get(0).creditLimit());
            assertEquals(0, system.admin().listSections().stream()
                    .filter(s -> s.courseId().equals("CS301")).findFirst().orElseThrow().seatsInUse());
        }
    }

    @Test
    @DisplayName("the demo data makes every scenario of the assignment reachable")
    void demoDataCoversTheScenarios() {
        try (RegistrationSystem system = RegistrationSystem.open(dir)) {
            system.seedDemoDataIfEmpty();
            var facade = system.registration();
            facade.register(new RegistrationRequest("S1001", "CS350", DemoData.TERM));        // takes the single seat

            var full = assertThrowsRegistration(() ->
                    facade.register(new RegistrationRequest("S1005", "CS350", DemoData.TERM)));
            assertEquals("[A]", full.undone().toString());                                  // B failed, A undone
            var limit = assertThrowsRegistration(() ->
                    facade.register(new RegistrationRequest("S1002", "CS301", DemoData.TERM)));
            assertEquals("[B, A]", limit.undone().toString());                              // C failed
            assertThrowsRegistration(() -> facade.register(new RegistrationRequest("S1003", "CS301", DemoData.TERM)));
            assertThrowsRegistration(() -> facade.register(new RegistrationRequest("S1004", "CS301", DemoData.TERM)));
        }
    }

    private static kz.aitu.registration.facade.RegistrationException assertThrowsRegistration(Runnable action) {
        return org.junit.jupiter.api.Assertions.assertThrows(kz.aitu.registration.facade.RegistrationException.class,
                action::run);
    }

    @Test
    void everySubsystemAndTheFacadeKeepTheirOwnDatabaseFile() throws IOException {
        try (RegistrationSystem system = RegistrationSystem.open(dir)) {
            system.seedDemoDataIfEmpty();
        }
        for (String name : new String[]{"eligibility", "seating", "finalization", "facade"}) {
            assertTrue(Files.exists(dir.resolve(name + ".mv.db")), name + ".mv.db is missing");
        }
        assertFalse(Files.exists(dir.resolve("registration.mv.db")));
    }

    @Test
    void standaloneDemoRunsEachSubsystemWithoutTheFacade() throws IOException {
        StandaloneSubsystemsDemo.main(new String[0]);
    }
}
