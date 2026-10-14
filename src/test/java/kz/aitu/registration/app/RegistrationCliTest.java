package kz.aitu.registration.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives the real demo application with scripted keystrokes against a fresh data directory. */
class RegistrationCliTest {

    @TempDir
    Path dir;

    private String run(String... lines) {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (RegistrationSystem system = RegistrationSystem.open(dir);
             PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            system.seedDemoDataIfEmpty();
            String script = String.join("\n", lines) + "\n";
            new RegistrationCli(system, new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)), out).run(dir);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("menu 8: B fails after A committed and only A is undone")
    void failureAtBIsShownWithCompensationOfA() {
        String output = run("8", "0");
        assertTrue(output.contains("[COMPENSATED]"));
        assertTrue(output.contains("failed at step B"));
        assertTrue(output.contains("undone in this order: [A]"));
        assertTrue(output.contains("persisted in A (clearance):  REVOKED"));
    }

    @Test
    @DisplayName("menu 9: C fails after A and B and B, then A are undone")
    void failureAtCIsShownWithCompensationOfBThenA() {
        String output = run("9", "0");
        assertTrue(output.contains("failed at step C"));
        assertTrue(output.contains("undone in this order: [B, A]"));
        assertTrue(output.contains("persisted in B (seat):       RELEASED"));
    }

    @Test
    void menu10ShowsARejectionWithNothingToUndo() {
        String output = run("10", "0");
        assertTrue(output.contains("[REJECTED]"));
        assertTrue(output.contains("PREREQUISITE_MISSING"));
    }

    @Test
    @DisplayName("a full session: register, list, inspect, transcript, cancel by hand, overview")
    void fullSession() {
        String output = run(
                "1", "S1001", "CS301", "",            // register (default term)
                "2",                                  // list chains
                "5", "S1001",                         // transcript
                "4",                                  // students and sections
                "3", "", "0");                        // inspect with an empty id -> error message, then exit
        assertTrue(output.contains("OK  registered. Tuition 160 000 KZT"));
        assertTrue(output.contains("COMPLETED"));
        assertTrue(output.contains("IN_PROGRESS"));
        assertTrue(output.contains("Aibek Nurlanov"));
        assertTrue(output.contains("CS301"));
        assertTrue(output.contains("A chain id is required"));
    }

    @Test
    void manualCompensationAndRecoveryFromTheMenu() {
        // register, then compensate the only chain by its 8-character prefix (read from the list output)
        String firstRun = run("1", "S1001", "CS301", "", "2", "0");
        java.util.regex.Matcher listed = java.util.regex.Pattern.compile("([0-9a-f]{8})\\s+S1001\\s+->").matcher(firstRun);
        assertTrue(listed.find(), firstRun);
        String shortId = listed.group(1);
        String output = run("6", shortId, "7", "0");
        assertTrue(output.contains("Compensated by hand. Undone in this order: [C, B, A]"), output);
        assertTrue(output.contains("No interrupted chains."));
    }

    @Test
    void stagingMenusChangeTheWorld() {
        String output = run(
                "11", "CS301", "", "1", "0",         // capacity 0
                "8", "11", "CS301", "", "2",         // reopen (no-op update message)
                "12", "S1001", "1", "5000",          // credit limit
                "12", "S1001", "2", "999999",        // overdue debt
                "13", "2", "13", "1",                // billing office closed, then open
                "11", "CS301", "", "9",              // unknown action
                "12", "S1001", "9",                  // unknown action
                "13", "9",                           // unknown action
                "0");
        assertTrue(output.contains("Section updated."));
        assertTrue(output.contains("Student updated."));
        assertTrue(output.contains("Billing office is now CLOSED."));
        assertTrue(output.contains("Billing office is now open."));
        assertTrue(output.contains("nothing changed"));
    }

    @Test
    void resetNeedsConfirmationAndInputErrorsDoNotCrashTheLoop() {
        String output = run("14", "no", "14", "YES", "11", "CS301", "", "1", "abc", "zzz", "m", "0");
        assertTrue(output.contains("Cancelled."));
        assertTrue(output.contains("All stores reset to the demo defaults."));
        assertTrue(output.contains("is not a whole number"));
        assertTrue(output.contains("Unknown choice 'zzz'"));
    }

    @Test
    void endOfInputEndsTheSessionCleanly() {
        String output = run("2");        // one command, then the stream ends
        assertTrue(output.contains("(no chains yet)"));
    }

    @Test
    void restartRecoveryIsReportedAtStartup() throws IOException {
        // leave an interrupted chain behind, then start the application again
        try (RegistrationSystem system = RegistrationSystem.open(dir)) {
            system.seedDemoDataIfEmpty();
            var chain = new kz.aitu.registration.facade.RegistrationRequest("S1001", "CS301", DemoData.TERM);
            system.eligibility().clear("manual-ref", chain.studentId(), chain.courseId(), chain.term());
        }
        // the clearance has no chain in the log, so recovery has nothing to do: output must still be sane
        String output = run("0");
        assertTrue(output.contains("Recovery: no interrupted chains found."));
    }
}
