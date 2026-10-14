package kz.aitu.registration.facade;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The facade's own durable chain log (its own H2 file). After a restart this is where the facade
 * learns which chains were interrupted and in which state.
 */
public class ChainLogRepository implements AutoCloseable {

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

    private static final String SELECT = "SELECT id, student_id, course_id, term_code, created_at, status, "
            + "clearance_id, reservation_id, invoice_id, failed_stage, failure_reason, undone_steps, updated_at "
            + "FROM chain_log";

    private static final RowMapper<ChainRecord> MAPPER = rs -> ChainRecord.restore(
            rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), Instant.parse(rs.getString(5)),
            ChainStatus.valueOf(rs.getString(6)), rs.getString(7), rs.getString(8), rs.getString(9),
            rs.getString(10) == null ? null : Stage.valueOf(rs.getString(10)), rs.getString(11),
            parseSteps(rs.getString(12)), Instant.parse(rs.getString(13)));

    private final Connection connection;

    public ChainLogRepository(Path databaseFile) {
        this.connection = open(databaseFile);
        update("CREATE TABLE IF NOT EXISTS chain_log ("
                + "seq BIGINT AUTO_INCREMENT NOT NULL, id VARCHAR(40) PRIMARY KEY, "
                + "student_id VARCHAR(64) NOT NULL, course_id VARCHAR(64) NOT NULL, term_code VARCHAR(64) NOT NULL, "
                + "status VARCHAR(16) NOT NULL, clearance_id VARCHAR(40), reservation_id VARCHAR(40), "
                + "invoice_id VARCHAR(40), failed_stage VARCHAR(4), failure_reason VARCHAR(1000), "
                + "undone_steps VARCHAR(16) NOT NULL, created_at VARCHAR(40) NOT NULL, updated_at VARCHAR(40) NOT NULL)");
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
            throw new StorageException("Cannot open chain log " + databaseFile, e);
        }
    }

    /** Inserts the chain, or updates it if it is already in the log. */
    public void save(ChainRecord chain) {
        int updated = update("UPDATE chain_log SET status = ?, clearance_id = ?, reservation_id = ?, invoice_id = ?, "
                        + "failed_stage = ?, failure_reason = ?, undone_steps = ?, updated_at = ? WHERE id = ?",
                chain.status().name(), chain.clearanceId(), chain.reservationId(), chain.invoiceId(),
                chain.failedStage() == null ? null : chain.failedStage().name(), truncate(chain.failureReason()),
                formatSteps(chain.undoneSteps()), chain.updatedAt().toString(), chain.id());
        if (updated == 0) {
            update("INSERT INTO chain_log (id, student_id, course_id, term_code, status, clearance_id, "
                            + "reservation_id, invoice_id, failed_stage, failure_reason, undone_steps, created_at, "
                            + "updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    chain.id(), chain.studentId(), chain.courseId(), chain.term(), chain.status().name(),
                    chain.clearanceId(), chain.reservationId(), chain.invoiceId(),
                    chain.failedStage() == null ? null : chain.failedStage().name(),
                    truncate(chain.failureReason()), formatSteps(chain.undoneSteps()),
                    chain.createdAt().toString(), chain.updatedAt().toString());
        }
    }

    public Optional<ChainRecord> find(String id) {
        return queryOne(SELECT + " WHERE id = ?", id);
    }

    public List<ChainRecord> findAll() {
        return query(SELECT + " ORDER BY seq");
    }

    /** Chains that were interrupted by a crash or restart and still need a decision. */
    public List<ChainRecord> findInterrupted() {
        return query(SELECT + " WHERE status IN ('STARTED', 'PENDING_B', 'PENDING_C', 'COMPENSATING') ORDER BY seq");
    }

    public void clearAll() {
        update("DELETE FROM chain_log");
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 1000 ? text : text.substring(0, 1000);
    }

    private static String formatSteps(List<Stage> steps) {
        return steps.stream().map(Stage::name).collect(Collectors.joining(","));
    }

    private static List<Stage> parseSteps(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.split(",")).map(Stage::valueOf).collect(Collectors.toList());
    }

    private int update(String sql, Object... params) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("SQL failed: " + sql, e);
        }
    }

    private List<ChainRecord> query(String sql, Object... params) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet rs = statement.executeQuery()) {
                List<ChainRecord> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(MAPPER.map(rs));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new StorageException("SQL failed: " + sql, e);
        }
    }

    private Optional<ChainRecord> queryOne(String sql, Object... params) {
        List<ChainRecord> rows = query(sql, params);
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
            throw new StorageException("Cannot close chain log", e);
        }
    }
}
