package kz.aitu.registration.seating;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public class SeatRepository implements AutoCloseable {

    public record Section(String courseId, String term, int capacity, boolean open) {
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

    public SeatRepository(Path databaseFile) {
        this.connection = open(databaseFile);
        createSchema();
    }

    private static Connection open(Path databaseFile) {
        try {
            Path absolute = databaseFile.toAbsolutePath();
            if (absolute.getParent() != null) {
                Files.createDirectories(absolute.getParent());
            }
            String url = "jdbc:h2:file:" + absolute.toString().replace('\\', '/') + ";WRITE_DELAY=0";
            return DriverManager.getConnection(url, "sa", "");
        } catch (SQLException | IOException e) {
            throw new StorageException("Cannot open seating store " + databaseFile, e);
        }
    }

    private void createSchema() {
        update("CREATE TABLE IF NOT EXISTS section ("
                + "course_id VARCHAR(32) NOT NULL, term_code VARCHAR(16) NOT NULL, "
                + "capacity INT NOT NULL, is_open BOOLEAN NOT NULL, PRIMARY KEY (course_id, term_code))");
        update("CREATE TABLE IF NOT EXISTS seat_reservation ("
                + "id VARCHAR(40) PRIMARY KEY, request_ref VARCHAR(64) NOT NULL UNIQUE, "
                + "student_id VARCHAR(32) NOT NULL, course_id VARCHAR(32) NOT NULL, "
                + "term_code VARCHAR(16) NOT NULL, status VARCHAR(16) NOT NULL)");
    }

    // ---------------------------------------------------------------- sections

    public void saveSection(Section section) {
        update("MERGE INTO section (course_id, term_code, capacity, is_open) KEY (course_id, term_code) "
                        + "VALUES (?, ?, ?, ?)",
                section.courseId(), section.term(), section.capacity(), section.open());
    }

    public Optional<Section> findSection(String courseId, String term) {
        return queryOne(SECTION_SELECT + " WHERE course_id = ? AND term_code = ?", SECTION_MAPPER, courseId, term);
    }

    public List<Section> findAllSections() {
        return query(SECTION_SELECT + " ORDER BY course_id, term_code", SECTION_MAPPER);
    }

    private static final String SECTION_SELECT = "SELECT course_id, term_code, capacity, is_open FROM section";

    private static final RowMapper<Section> SECTION_MAPPER = rs ->
            new Section(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getBoolean(4));

    // ---------------------------------------------------------------- reservations

    public void insertReservation(SeatReservation reservation) {
        update("INSERT INTO seat_reservation (id, request_ref, student_id, course_id, term_code, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                reservation.id(), reservation.reference(), reservation.studentId(), reservation.courseId(),
                reservation.term(), reservation.status().name());
    }

    public void updateReservationStatus(SeatReservation reservation) {
        update("UPDATE seat_reservation SET status = ? WHERE id = ?", reservation.status().name(), reservation.id());
    }

    public Optional<SeatReservation> findReservationByReference(String reference) {
        return queryOne(RESERVATION_SELECT + " WHERE request_ref = ?", RESERVATION_MAPPER, reference);
    }

    public int countSeatsInUse(String courseId, String term) {
        return queryOne("SELECT COUNT(*) FROM seat_reservation WHERE course_id = ? AND term_code = ? "
                        + "AND status <> 'RELEASED'", rs -> rs.getInt(1), courseId, term).orElse(0);
    }

    public boolean studentHoldsSeat(String studentId, String courseId, String term) {
        return queryOne("SELECT id FROM seat_reservation WHERE student_id = ? AND course_id = ? AND term_code = ? "
                        + "AND status <> 'RELEASED'", rs -> rs.getString(1), studentId, courseId, term).isPresent();
    }

    public List<SeatReservation> findAllReservations() {
        return query(RESERVATION_SELECT + " ORDER BY student_id, course_id, term_code", RESERVATION_MAPPER);
    }

    private static final String RESERVATION_SELECT =
            "SELECT id, request_ref, student_id, course_id, term_code, status FROM seat_reservation";

    private static final RowMapper<SeatReservation> RESERVATION_MAPPER = rs ->
            SeatReservation.restore(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), SeatReservation.Status.valueOf(rs.getString(6)));

    public void clearAll() {
        inTransaction(() -> {
            update("DELETE FROM seat_reservation");
            update("DELETE FROM section");
            return null;
        });
    }


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
            throw new StorageException("Cannot close seating store", e);
        }
    }
}
