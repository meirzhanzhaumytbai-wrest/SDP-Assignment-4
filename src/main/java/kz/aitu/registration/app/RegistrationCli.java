package kz.aitu.registration.app;

import kz.aitu.registration.facade.ChainInspection;
import kz.aitu.registration.facade.ChainRecord;
import kz.aitu.registration.facade.RecoveryReport;
import kz.aitu.registration.facade.RegistrationAdminFacade;
import kz.aitu.registration.facade.RegistrationException;
import kz.aitu.registration.facade.RegistrationFacade;
import kz.aitu.registration.facade.RegistrationReceipt;
import kz.aitu.registration.facade.RegistrationRequest;
import kz.aitu.registration.facade.SectionOverview;
import kz.aitu.registration.facade.StudentOverview;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * The demo application. It is a CLIENT of the two facades and nothing else: it imports no subsystem class.
 *
 * <pre>
 *   mvn -q compile exec:java                      (reuses the data in ./data)
 *   mvn -q compile exec:java -Dexec.args="--reset"  (fresh demo data)
 * </pre>
 */
public final class RegistrationCli {

    private final RegistrationSystem system;
    private final RegistrationFacade registration;
    private final RegistrationAdminFacade admin;
    private final BufferedReader in;
    private final PrintStream out;

    public RegistrationCli(RegistrationSystem system, InputStream in, PrintStream out) {
        this.system = system;
        this.registration = system.registration();
        this.admin = system.admin();
        this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = out;
    }

    public static void main(String[] args) {
        Path dataDirectory = Path.of("data");
        boolean reset = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--reset")) {
                reset = true;
            } else if (args[i].equals("--data") && i + 1 < args.length) {
                dataDirectory = Path.of(args[++i]);
            } else {
                System.err.println("Usage: [--reset] [--data <directory>]");
                return;
            }
        }
        try (RegistrationSystem system = RegistrationSystem.open(dataDirectory)) {
            if (reset) {
                system.resetToDemoData();
                System.out.println("Persisted data reset to the demo defaults.");
            } else {
                system.seedDemoDataIfEmpty();
            }
            new RegistrationCli(system, System.in, System.out).run(dataDirectory);
        } catch (RuntimeException e) {
            System.err.println("Cannot start: " + e.getMessage());
            if (e.getCause() != null) {
                System.err.println("Cause: " + e.getCause().getMessage());
            }
            System.err.println("(Is another copy of the application still running on the same data directory?)");
        }
    }

    public void run(Path dataDirectory) {
        out.println();
        out.println("University Registration - Facade over A (eligibility) -> B (seating) -> C (finalization)");
        out.println("Persisted in: " + dataDirectory.toAbsolutePath());
        recoverAtStartup();

        printMenu();
        while (true) {
            String choice = prompt("Choose (m = menu)");
            if (choice == null) {
                return;
            }
            try {
                if (!handle(choice.trim())) {
                    return;
                }
            } catch (RegistrationException e) {
                reportFailure(e);
            } catch (RuntimeException e) {
                out.println("  ! " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------------------------ menu

    private void printMenu() {
        out.println();
        out.println("  1  Register a student for a course        (the whole chain A -> B -> C)");
        out.println("  2  List all chains                         (the facade's persisted log)");
        out.println("  3  Inspect a chain                         (what A, B and C really hold on disk)");
        out.println("  4  Show students and sections");
        out.println("  5  Show a student's transcript");
        out.println("  6  Compensate a chain by hand              (admin: C, then B, then A)");
        out.println("  7  Run restart recovery now");
        out.println("  -- provoke failures on demand --");
        out.println("  8  B fails after A committed   (S1001 -> CS399, a section with no seats)  => A undone");
        out.println("  9  C fails after A and B       (S1002 -> CS301, credit limit too low)     => B, then A undone");
        out.println(" 10  A rejects the request       (S1003 -> CS301, prerequisite missing)     => nothing to undo");
        out.println("  -- stage your own scenario --");
        out.println(" 11  Set a section's capacity, or open / close it");
        out.println(" 12  Set a student's credit limit or overdue debt");
        out.println(" 13  Open / close the billing office");
        out.println(" 14  Reset ALL persisted data to the demo defaults");
        out.println("  0  Exit");
    }

    private boolean handle(String choice) {
        switch (choice) {
            case "1" -> registerInteractively();
            case "2" -> listChains();
            case "3" -> inspectInteractively();
            case "4" -> showStudentsAndSections();
            case "5" -> showTranscript();
            case "6" -> compensateInteractively();
            case "7" -> recoverNow();
            case "8" -> attempt("S1001", "CS399", DemoData.TERM);
            case "9" -> attempt("S1002", "CS301", DemoData.TERM);
            case "10" -> attempt("S1003", "CS301", DemoData.TERM);
            case "11" -> stageSection();
            case "12" -> stageStudent();
            case "13" -> stageBillingOffice();
            case "14" -> resetData();
            case "m", "M", "?" -> printMenu();
            case "0" -> {
                out.println("Bye. Everything stays on disk; start again to see it.");
                return false;
            }
            default -> out.println("  ? Unknown choice '" + choice + "' (type m for the menu)");
        }
        return true;
    }

    // ------------------------------------------------------------------------------------ actions

    private void registerInteractively() {
        String student = prompt("Student id (e.g. S1001)");
        String course = prompt("Course id (e.g. CS301)");
        String term = prompt("Term [" + DemoData.TERM + "]");
        if (term == null || term.isBlank()) {
            term = DemoData.TERM;
        }
        attempt(student, course, term);
    }

    private void attempt(String studentId, String courseId, String term) {
        out.println();
        out.println("  >> register(" + studentId + ", " + courseId + ", " + term + ")");
        RegistrationReceipt receipt = registration.register(new RegistrationRequest(studentId, courseId, term));
        out.println("  OK  registered. Tuition " + money(receipt.tuitionKzt()) + " KZT, chain "
                + shortId(receipt.chainId()) + " is " + receipt.status());
        printInspection(admin.inspect(receipt.chainId()));
    }

    private void reportFailure(RegistrationException e) {
        out.println("  FAIL [" + e.kind() + "] " + e.getMessage());
        if (e.chainId() != null) {
            printInspection(admin.inspect(e.chainId()));
        }
    }

    private void listChains() {
        List<ChainRecord> chains = admin.listChains();
        if (chains.isEmpty()) {
            out.println("  (no chains yet)");
            return;
        }
        for (ChainRecord chain : chains) {
            out.println("  " + shortId(chain.id()) + "  " + pad(chain.studentId(), 6) + " -> "
                    + pad(chain.courseId(), 6) + " " + pad(chain.term(), 9) + " " + chain.status()
                    + (chain.undoneSteps().isEmpty() ? "" : "  undone " + chain.undoneSteps()));
        }
    }

    private void inspectInteractively() {
        listChains();
        String id = prompt("Chain id (first 8 characters are enough)");
        printInspection(admin.inspect(id));
    }

    private void printInspection(ChainInspection view) {
        ChainRecord chain = view.chain();
        out.println("  --- chain " + shortId(chain.id()) + ": " + chain.studentId() + " -> " + chain.courseId()
                + " / " + chain.term() + "  [" + chain.status() + "]");
        if (chain.failedStage() != null) {
            out.println("      failed at step " + chain.failedStage() + ": " + chain.failureReason());
        }
        if (!chain.undoneSteps().isEmpty()) {
            out.println("      undone in this order: " + chain.undoneSteps());
        }
        out.println("      persisted in A (clearance):  " + view.clearanceStatus());
        out.println("      persisted in B (seat):       " + view.seatStatus());
        out.println("      persisted in C (invoice):    " + view.invoiceStatus()
                + "   transcript: " + view.transcriptStatus());
    }

    private void showStudentsAndSections() {
        out.println("  Students:");
        for (StudentOverview s : admin.listStudents()) {
            out.println("   " + s.studentId() + "  " + pad(s.fullName(), 18) + " passed [" + s.completedCourses()
                    + "]  debt " + money(s.overdueDebt()) + "  limit " + money(s.creditLimit())
                    + "  scholarship " + s.scholarshipPercent() + "%  owes " + money(s.outstanding()));
        }
        out.println("  Sections:");
        for (SectionOverview s : admin.listSections()) {
            out.println("   " + pad(s.courseId(), 6) + " " + pad(s.term(), 9) + " seats " + s.seatsInUse() + "/"
                    + s.capacity() + (s.open() ? "" : "  CLOSED"));
        }
        out.println("  Billing office: " + (admin.isBillingWindowOpen() ? "open" : "CLOSED"));
    }

    private void showTranscript() {
        String student = prompt("Student id");
        List<String> lines = admin.transcriptOf(student == null ? "" : student.trim());
        if (lines.isEmpty()) {
            out.println("  (empty transcript)");
        }
        lines.forEach(line -> out.println("  " + line));
    }

    private void compensateInteractively() {
        listChains();
        String id = prompt("Chain id to compensate");
        ChainRecord chain = admin.compensate(id);
        out.println("  Compensated by hand. Undone in this order: "
                + (chain.undoneSteps().isEmpty() ? "(nothing)" : chain.undoneSteps()));
        printInspection(admin.inspect(chain.id()));
    }

    private void recoverAtStartup() {
        List<RecoveryReport> reports = registration.recoverInterruptedChains();
        if (reports.isEmpty()) {
            out.println("Recovery: no interrupted chains found.");
            return;
        }
        out.println("Recovery found interrupted chains from the previous run:");
        reports.forEach(r -> out.println("  " + shortId(r.chainId()) + ": " + r.statusBefore() + " -> "
                + r.statusAfter() + "  (" + r.action() + ")"));
    }

    private void recoverNow() {
        List<RecoveryReport> reports = registration.recoverInterruptedChains();
        if (reports.isEmpty()) {
            out.println("  No interrupted chains.");
        }
        reports.forEach(r -> out.println("  " + shortId(r.chainId()) + ": " + r.statusBefore() + " -> "
                + r.statusAfter() + "  (" + r.action() + ")"));
    }

    private void stageSection() {
        String course = prompt("Course id");
        String term = prompt("Term [" + DemoData.TERM + "]");
        if (term == null || term.isBlank()) {
            term = DemoData.TERM;
        }
        String what = prompt("1 = set capacity, 2 = open, 3 = close");
        if ("1".equals(what == null ? "" : what.trim())) {
            admin.setSectionCapacity(course.trim(), term.trim(), parseInt(prompt("New capacity")));
        } else if ("2".equals(what == null ? "" : what.trim())) {
            admin.setSectionOpen(course.trim(), term.trim(), true);
        } else if ("3".equals(what == null ? "" : what.trim())) {
            admin.setSectionOpen(course.trim(), term.trim(), false);
        } else {
            out.println("  ? nothing changed");
            return;
        }
        out.println("  Section updated.");
    }

    private void stageStudent() {
        String student = prompt("Student id");
        String what = prompt("1 = set credit limit (KZT), 2 = set overdue debt (KZT)");
        if ("1".equals(what == null ? "" : what.trim())) {
            admin.setCreditLimit(student.trim(), parseLong(prompt("New credit limit")));
        } else if ("2".equals(what == null ? "" : what.trim())) {
            admin.setOverdueDebt(student.trim(), parseLong(prompt("New overdue debt")));
        } else {
            out.println("  ? nothing changed");
            return;
        }
        out.println("  Student updated.");
    }

    private void stageBillingOffice() {
        String what = prompt("1 = open, 2 = close");
        if ("1".equals(what == null ? "" : what.trim())) {
            admin.setBillingWindowOpen(true);
        } else if ("2".equals(what == null ? "" : what.trim())) {
            admin.setBillingWindowOpen(false);
        } else {
            out.println("  ? nothing changed");
            return;
        }
        out.println("  Billing office is now " + (admin.isBillingWindowOpen() ? "open" : "CLOSED") + ".");
    }

    private void resetData() {
        String answer = prompt("This deletes every persisted record. Type YES to continue");
        if (answer != null && answer.trim().equals("YES")) {
            system.resetToDemoData();
            out.println("  All stores reset to the demo defaults.");
        } else {
            out.println("  Cancelled.");
        }
    }

    // ------------------------------------------------------------------------------------ helpers

    private String prompt(String label) {
        out.print("  " + label + " > ");
        out.flush();
        try {
            return in.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private static int parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("'" + text + "' is not a whole number");
        }
    }

    private static long parseLong(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("'" + text + "' is not a whole number");
        }
    }

    private static String shortId(String id) {
        return id.substring(0, 8);
    }

    private static String pad(String text, int width) {
        return String.format(Locale.ROOT, "%-" + width + "s", text);
    }

    private static String money(long amount) {
        return String.format(Locale.ROOT, "%,d", amount).replace(',', ' ');
    }
}
