package kz.aitu.registration.finalization;

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

public class FinalizationRepository implements AutoCloseable {

    public record Account(String studentId, long creditLimit, int scholarshipPercent) {
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

    public FinalizationRepository(Path databaseFile) {
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
            throw new StorageException("Cannot open finalization store " + databaseFile, e);
        }
    }

    private void createSchema() {
        update("CREATE TABLE IF NOT EXISTS student_account ("
                + "student_id VARCHAR(32) PRIMARY KEY, credit_limit BIGINT NOT NULL, "
                + "scholarship_percent INT NOT NULL)");
        update("CREATE TABLE IF NOT EXISTS course_credits ("
                + "course_id VARCHAR(32) PRIMARY KEY, credits INT NOT NULL)");
        update("CREATE TABLE IF NOT EXISTS billing_window (id INT PRIMARY KEY, is_open BOOLEAN NOT NULL)");
        update("CREATE TABLE IF NOT EXISTS invoice ("
                + "id VARCHAR(40) PRIMARY KEY, request_ref VARCHAR(64) NOT NULL UNIQUE, "
                + "student_id VARCHAR(32) NOT NULL, course_id VARCHAR(32) NOT NULL, "
                + "term_code VARCHAR(16) NOT NULL, amount BIGINT NOT NULL, status VARCHAR(16) NOT NULL)");
        update("CREATE TABLE IF NOT EXISTS transcript_entry ("
                + "id VARCHAR(40) PRIMARY KEY, request_ref VARCHAR(64) NOT NULL UNIQUE, "
                + "student_id VARCHAR(32) NOT NULL, course_id VARCHAR(32) NOT NULL, "
                + "term_code VARCHAR(16) NOT NULL, status VARCHAR(16) NOT NULL)");
    }

    // ---------------------------------------------------------------- reference data

    public void saveAccount(Account account) {
        update("MERGE INTO student_account (student_id, credit_limit, scholarship_percent) KEY (student_id) "
                        + "VALUES (?, ?, ?)",
                account.studentId(), account.creditLimit(), account.scholarshipPercent());
    }

    public Optional<Account> findAccount(String studentId) {
        return queryOne(ACCOUNT_SELECT + " WHERE student_id = ?", ACCOUNT_MAPPER, studentId);
    }

    public List<Account> findAllAccounts() {
        return query(ACCOUNT_SELECT + " ORDER BY student_id", ACCOUNT_MAPPER);
    }

    private static final String ACCOUNT_SELECT =
            "SELECT student_id, credit_limit, scholarship_percent FROM student_account";

    private static final RowMapper<Account> ACCOUNT_MAPPER = rs ->
            new Account(rs.getString(1), rs.getLong(2), rs.getInt(3));

    public void saveCourseCredits(String courseId, int credits) {
        update("MERGE INTO course_credits (course_id, credits) KEY (course_id) VALUES (?, ?)", courseId, credits);
    }

    public Optional<Integer> findCredits(String courseId) {
        return queryOne("SELECT credits FROM course_credits WHERE course_id = ?", rs -> rs.getInt(1), courseId);
    }

    /** The billing window is open unless somebody explicitly closed it. */
    public boolean isBillingWindowOpen() {
        return queryOne("SELECT is_open FROM billing_window WHERE id = 1", rs -> rs.getBoolean(1)).orElse(true);
    }

    public void setBillingWindowOpen(boolean open) {
        update("MERGE INTO billing_window (id, is_open) KEY (id) VALUES (1, ?)", open);
    }

    // ---------------------------------------------------------------- invoices

    public void insertInvoice(Invoice invoice) {
        update("INSERT INTO invoice (id, request_ref, student_id, course_id, term_code, amount, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                invoice.id(), invoice.reference(), invoice.studentId(), invoice.courseId(), invoice.term(),
                invoice.amount(), invoice.status().name());
    }

    public void updateInvoiceStatus(Invoice invoice) {
        update("UPDATE invoice SET status = ? WHERE id = ?", invoice.status().name(), invoice.id());
    }

    public Optional<Invoice> findInvoiceByReference(String reference) {
        return queryOne(INVOICE_SELECT + " WHERE request_ref = ?", INVOICE_MAPPER, reference);
    }

    public long sumOutstanding(String studentId) {
        return queryOne("SELECT COALESCE(SUM(amount), 0) FROM invoice WHERE student_id = ? AND status = 'ISSUED'",
                rs -> rs.getLong(1), studentId).orElse(0L);
    }

    public List<Invoice> findAllInvoices() {
        return query(INVOICE_SELECT + " ORDER BY student_id, course_id, term_code", INVOICE_MAPPER);
    }

    private static final String INVOICE_SELECT =
            "SELECT id, request_ref, student_id, course_id, term_code, amount, status FROM invoice";

    private static final RowMapper<Invoice> INVOICE_MAPPER = rs ->
            Invoice.restore(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                    rs.getLong(6), Invoice.Status.valueOf(rs.getString(7)));

    // ---------------------------------------------------------------- transcript

    public void insertTranscriptEntry(TranscriptEntry entry) {
        update("INSERT INTO transcript_entry (id, request_ref, student_id, course_id, term_code, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                entry.id(), entry.reference(), entry.studentId(), entry.courseId(), entry.term(),
                entry.status().name());
    }

    public void updateTranscriptStatus(TranscriptEntry entry) {
        update("UPDATE transcript_entry SET status = ? WHERE id = ?", entry.status().name(), entry.id());
    }

    public Optional<TranscriptEntry> findTranscriptByReference(String reference) {
        return queryOne(TRANSCRIPT_SELECT + " WHERE request_ref = ?", TRANSCRIPT_MAPPER, reference);
    }

    public List<TranscriptEntry> findTranscriptOf(String studentId) {
        return query(TRANSCRIPT_SELECT + " WHERE student_id = ? ORDER BY term_code, course_id",
                TRANSCRIPT_MAPPER, studentId);
    }

    private static final String TRANSCRIPT_SELECT =
            "SELECT id, request_ref, student_id, course_id, term_code, status FROM transcript_entry";

    private static final RowMapper<TranscriptEntry> TRANSCRIPT_MAPPER = rs ->
            TranscriptEntry.restore(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), TranscriptEntry.Status.valueOf(rs.getString(6)));

    public void clearAll() {
        inTransaction(() -> {
            update("DELETE FROM transcript_entry");
            update("DELETE FROM invoice");
            update("DELETE FROM billing_window");
            update("DELETE FROM course_credits");
            update("DELETE FROM student_account");
            return null;
        });
    }

    // ---------------------------------------------------------------- plumbing

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
            throw new StorageException("Cannot close finalization store", e);
        }
    }
}
