package kz.aitu.registration.eligibility;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Subsystem A persistence gateway. It talks to its own embedded H2 database file directly
 * (nobody else, and in particular not the facade, ever sees SQL).
 */
public class EligibilityRepository implements AutoCloseable {

    /** What subsystem A knows about a student: finished courses and money owed. */
    public record StudentStanding(String studentId, String fullName, Set<String> completedCourses,
                                  long overdueDebt) {
        public StudentStanding {
            completedCourses = Collections.unmodifiableSet(new TreeSet<>(completedCourses));
        }
    }

    @SuppressWarnings("serial")

    public static class StorageException extends RuntimeException {
        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private final Connection connection;

    public EligibilityRepository(Path databaseFile) {
        this.connection = open(databaseFile);
        createSchema();
    }

    private static Connection open(Path databaseFile) {
        try {
            Path absolute = databaseFile.toAbsolutePath();
            if (absolute.getParent() != null) {
                Files.createDirectories(absolute.getParent());
            }
            // WRITE_DELAY=0: every commit is flushed to disk immediately, so a killed process loses nothing.
            String url = "jdbc:h2:file:" + absolute.toString().replace('\\', '/') + ";WRITE_DELAY=0";
            return DriverManager.getConnection(url, "sa", "");
        } catch (SQLException | IOException e) {
            throw new StorageException("Cannot open eligibility store " + databaseFile, e);
        }
    }

    private void createSchema() {
        update("CREATE TABLE IF NOT EXISTS student_standing ("
                + "student_id VARCHAR(32) PRIMARY KEY, full_name VARCHAR(100) NOT NULL, overdue_debt BIGINT NOT NULL)");
        update("CREATE TABLE IF NOT EXISTS completed_course ("
                + "student_id VARCHAR(32) NOT NULL, course_id VARCHAR(32) NOT NULL, PRIMARY KEY (student_id, course_id))");
        update("CREATE TABLE IF NOT EXISTS course (course_id VARCHAR(32) PRIMARY KEY)");
        update("CREATE TABLE IF NOT EXISTS course_prerequisite ("
                + "course_id VARCHAR(32) NOT NULL, prerequisite_id VARCHAR(32) NOT NULL, "
                + "PRIMARY KEY (course_id, prerequisite_id))");
        update("CREATE TABLE IF NOT EXISTS clearance ("
                + "id VARCHAR(40) PRIMARY KEY, request_ref VARCHAR(64) NOT NULL UNIQUE, "
                + "student_id VARCHAR(32) NOT NULL, course_id VARCHAR(32) NOT NULL, "
                + "term_code VARCHAR(16) NOT NULL, status VARCHAR(16) NOT NULL)");
    }

    // ---------------------------------------------------------------- reference data

    public void saveStanding(StudentStanding standing) {
        inTransaction(() -> {
            update("MERGE INTO student_standing (student_id, full_name, overdue_debt) KEY (student_id) VALUES (?, ?, ?)",
                    standing.studentId(), standing.fullName(), standing.overdueDebt());
            update("DELETE FROM completed_course WHERE student_id = ?", standing.studentId());
            for (String course : standing.completedCourses()) {
                update("INSERT INTO completed_course (student_id, course_id) VALUES (?, ?)",
                        standing.studentId(), course);
            }
            return null;
        });
    }

    public Optional<StudentStanding> findStanding(String studentId) {
        return queryOne("SELECT student_id, full_name, overdue_debt FROM student_standing WHERE student_id = ?",
                rs -> new StudentStanding(rs.getString(1), rs.getString(2),
                        completedCourses(rs.getString(1)), rs.getLong(3)), studentId);
    }

    public List<StudentStanding> findAllStandings() {
        return query("SELECT student_id, full_name, overdue_debt FROM student_standing ORDER BY student_id",
                rs -> new StudentStanding(rs.getString(1), rs.getString(2),
                        completedCourses(rs.getString(1)), rs.getLong(3)));
    }

    private Set<String> completedCourses(String studentId) {
        return new TreeSet<>(query("SELECT course_id FROM completed_course WHERE student_id = ?",
                rs -> rs.getString(1), studentId));
    }

    public void defineCourse(String courseId, Set<String> prerequisites) {
        inTransaction(() -> {
            update("MERGE INTO course (course_id) KEY (course_id) VALUES (?)", courseId);
            update("DELETE FROM course_prerequisite WHERE course_id = ?", courseId);
            for (String prerequisite : prerequisites) {
                update("INSERT INTO course_prerequisite (course_id, prerequisite_id) VALUES (?, ?)",
                        courseId, prerequisite);
            }
            return null;
        });
    }

    /** @return empty when the course is unknown; an empty set when it is known but has no prerequisites */
    public Optional<Set<String>> findPrerequisites(String courseId) {
        boolean known = queryOne("SELECT course_id FROM course WHERE course_id = ?",
                rs -> rs.getString(1), courseId).isPresent();
        if (!known) {
            return Optional.empty();
        }
        return Optional.of(new TreeSet<>(query(
                "SELECT prerequisite_id FROM course_prerequisite WHERE course_id = ?",
                rs -> rs.getString(1), courseId)));
    }

    // ---------------------------------------------------------------- clearances

    public void insertClearance(EligibilityClearance clearance) {
        update("INSERT INTO clearance (id, request_ref, student_id, course_id, term_code, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                clearance.id(), clearance.reference(), clearance.studentId(), clearance.courseId(),
                clearance.term(), clearance.status().name());
    }

    public void updateClearanceStatus(EligibilityClearance clearance) {
        update("UPDATE clearance SET status = ? WHERE id = ?", clearance.status().name(), clearance.id());
    }

    public Optional<EligibilityClearance> findClearanceByReference(String reference) {
        return queryOne(CLEARANCE_SELECT + " WHERE request_ref = ?", CLEARANCE_MAPPER, reference);
    }

    public Optional<EligibilityClearance> findClearanceById(String id) {
        return queryOne(CLEARANCE_SELECT + " WHERE id = ?", CLEARANCE_MAPPER, id);
    }

    public boolean existsActiveClearance(String studentId, String courseId, String term) {
        return queryOne("SELECT id FROM clearance WHERE student_id = ? AND course_id = ? AND term_code = ? "
                        + "AND status <> 'REVOKED'", rs -> rs.getString(1), studentId, courseId, term).isPresent();
    }

    public List<EligibilityClearance> findAllClearances() {
        return query(CLEARANCE_SELECT + " ORDER BY student_id, course_id, term_code", CLEARANCE_MAPPER);
    }

    private static final String CLEARANCE_SELECT =
            "SELECT id, request_ref, student_id, course_id, term_code, status FROM clearance";

    private static final RowMapper<EligibilityClearance> CLEARANCE_MAPPER = rs ->
            EligibilityClearance.restore(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), EligibilityClearance.Status.valueOf(rs.getString(6)));

    /** Empties every table (used by the demo "reset" and by tests). */
    public void clearAll() {
        inTransaction(() -> {
            update("DELETE FROM clearance");
            update("DELETE FROM completed_course");
            update("DELETE FROM student_standing");
            update("DELETE FROM course_prerequisite");
            update("DELETE FROM course");
            return null;
        });
    }

    // ---------------------------------------------------------------- plumbing

    /** Runs the work as one atomic unit: either everything inside is stored, or nothing. */
    public <T> T inTransaction(Supplier<T> work) {
        try {
            connection.setAutoCommit(false);
            try {
                T result = work.get();
                connection.commit();
                return result;
            } catch (RuntimeException | Error e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new StorageException("Transaction failed", e);
        }
    }

    private int update(String sql, Object... params) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("SQL failed: " + sql, e);
        }
    }

    private <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet rs = statement.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapper.map(rs));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new StorageException("SQL failed: " + sql, e);
        }
    }

    private <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        List<T> rows = query(sql, mapper, params);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static void bind(PreparedStatement statement, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            statement.setObject(i + 1, params[i]);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new StorageException("Cannot close eligibility store", e);
        }
    }
}
