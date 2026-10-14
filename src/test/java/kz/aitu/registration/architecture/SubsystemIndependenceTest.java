package kz.aitu.registration.architecture;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the structural rules of the design by reading the source files:
 * A, B and C know nothing about each other or about the facade, and the demo client only knows the facades.
 */
class SubsystemIndependenceTest {

    private static final String BASE = "kz.aitu.registration.";
    private static final Path SOURCE_ROOT = Path.of("src/main/java/kz/aitu/registration");
    private static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+)", Pattern.MULTILINE);
    private static final List<String> SUBSYSTEMS = List.of("eligibility", "seating", "finalization");

    private static List<Path> javaFilesIn(String packageName) throws IOException {
        Path directory = SOURCE_ROOT.resolve(packageName);
        Assumptions.assumeTrue(Files.isDirectory(directory), "sources not visible from the working directory");
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static List<String> importsOf(Path file) throws IOException {
        String text = Files.readString(file);
        Matcher matcher = IMPORT.matcher(text);
        java.util.ArrayList<String> imports = new java.util.ArrayList<>();
        while (matcher.find()) {
            imports.add(matcher.group(1));
        }
        return imports;
    }

    @Test
    @DisplayName("each subsystem imports only the JDK and its own package - never another subsystem, the facade or the app")
    void subsystemsAreIndependent() throws IOException {
        for (String subsystem : SUBSYSTEMS) {
            List<Path> files = javaFilesIn(subsystem);
            assertTrue(files.size() >= 3 && files.size() <= 5, subsystem + " must have 3-5 classes, has " + files.size());
            for (Path file : files) {
                for (String imported : importsOf(file)) {
                    boolean allowed = imported.startsWith("java.") || imported.startsWith(BASE + subsystem + ".");
                    assertTrue(allowed, file.getFileName() + " imports " + imported);
                }
                String text = Files.readString(file);
                for (String other : SUBSYSTEMS) {
                    if (!other.equals(subsystem)) {
                        assertFalse(text.contains(BASE + other), file.getFileName() + " mentions " + other);
                    }
                }
                assertFalse(text.contains(BASE + "facade"), file.getFileName() + " mentions the facade");
            }
        }
    }

    @Test
    @DisplayName("the three subsystems together have between 9 and 15 classes")
    void totalClassCountIsWithinTheAssignmentLimits() throws IOException {
        int total = 0;
        for (String subsystem : SUBSYSTEMS) {
            total += javaFilesIn(subsystem).size();
        }
        assertTrue(total >= 9 && total <= 15, "total classes: " + total);
    }

    @Test
    @DisplayName("every subsystem has a pure-logic class, an entity with a status, and a persistence gateway")
    void everySubsystemHasLogicEntityAndGateway() throws IOException {
        for (String subsystem : SUBSYSTEMS) {
            List<Path> files = javaFilesIn(subsystem);
            long gateways = files.stream().filter(f -> f.getFileName().toString().endsWith("Repository.java")).count();
            assertEquals(1, gateways, subsystem + " needs exactly one gateway");
            boolean hasStatusEntity = false;
            boolean hasPureLogic = false;
            for (Path file : files) {
                String text = Files.readString(file);
                if (text.contains("enum Status") && text.contains("IllegalTransitionException")) {
                    hasStatusEntity = true;
                }
                if (!text.contains("java.sql") && !text.contains("java.nio.file")
                        && (file.getFileName().toString().contains("Checker")
                        || file.getFileName().toString().contains("Policy")
                        || file.getFileName().toString().contains("Calculator"))) {
                    hasPureLogic = true;
                }
            }
            assertTrue(hasStatusEntity, subsystem + " needs an entity with status and guarded transitions");
            assertTrue(hasPureLogic, subsystem + " needs a pure business-logic class without any storage code");
        }
    }

    @Test
    @DisplayName("the demo client (CLI) imports the facade package only - no subsystem class")
    void clientOnlyKnowsTheFacades() throws IOException {
        Path cli = SOURCE_ROOT.resolve("app/RegistrationCli.java");
        Assumptions.assumeTrue(Files.exists(cli));
        for (String imported : importsOf(cli)) {
            for (String subsystem : SUBSYSTEMS) {
                assertFalse(imported.startsWith(BASE + subsystem), "CLI imports " + imported);
            }
        }
    }

    @Test
    void facadePackageDoesNotDependOnTheClient() throws IOException {
        for (Path file : javaFilesIn("facade")) {
            assertFalse(Files.readString(file).contains(BASE + "app"), file.getFileName() + " mentions the app package");
        }
    }
}
