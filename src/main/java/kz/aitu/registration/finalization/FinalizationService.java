package kz.aitu.registration.finalization;

import kz.aitu.registration.finalization.FinalizationRepository.Account;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;


public class FinalizationService implements AutoCloseable {

    @SuppressWarnings("serial")

    public static class BillingRejectedException extends RuntimeException {
        public enum Reason { BILLING_CLOSED, UNKNOWN_ACCOUNT, UNKNOWN_COURSE, LIMIT_EXCEEDED }

        private final Reason reason;

        public BillingRejectedException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    private final FinalizationRepository repository;
    private final TuitionCalculator calculator;

    public FinalizationService(FinalizationRepository repository) {
        this(repository, new TuitionCalculator());
    }

    public FinalizationService(FinalizationRepository repository, TuitionCalculator calculator) {
        this.repository = repository;
        this.calculator = calculator;
    }

    public static FinalizationService openAt(Path databaseFile) {
        return new FinalizationService(new FinalizationRepository(databaseFile));
    }


    public Invoice finalizeRegistration(String reference, String studentId, String courseId, String term) {
        Optional<Invoice> existing = repository.findInvoiceByReference(reference);
        if (existing.isPresent()) {
            return existing.get();
        }
        return repository.inTransaction(() -> {
            if (!repository.isBillingWindowOpen()) {
                throw new BillingRejectedException(BillingRejectedException.Reason.BILLING_CLOSED,
                        "The billing office is closed, no invoices can be issued right now");
            }
            Account account = repository.findAccount(studentId).orElseThrow(() ->
                    new BillingRejectedException(BillingRejectedException.Reason.UNKNOWN_ACCOUNT,
                            "No billing account for " + studentId));
            int credits = repository.findCredits(courseId).orElseThrow(() ->
                    new BillingRejectedException(BillingRejectedException.Reason.UNKNOWN_COURSE,
                            "No fee schedule for course " + courseId));
            long tuition = calculator.tuition(credits, account.scholarshipPercent());
            long outstanding = repository.sumOutstanding(studentId);
            if (!calculator.fitsLimit(account.creditLimit(), outstanding, tuition)) {
                throw new BillingRejectedException(BillingRejectedException.Reason.LIMIT_EXCEEDED,
                        "Credit limit exceeded: owes " + outstanding + " + new " + tuition
                                + " > limit " + account.creditLimit() + " KZT");
            }
            Invoice invoice = Invoice.issue(reference, studentId, courseId, term, tuition);
            repository.insertInvoice(invoice);
            repository.insertTranscriptEntry(TranscriptEntry.open(reference, studentId, courseId, term));
            return invoice;
        });
    }

    public Invoice markPaid(String reference) {
        Invoice invoice = repository.findInvoiceByReference(reference).orElseThrow(() ->
                new IllegalStateException("No invoice for reference " + reference));
        if (invoice.status() == Invoice.Status.PAID) {
            return invoice;
        }
        invoice.pay();
        repository.updateInvoiceStatus(invoice);
        return invoice;
    }


    public boolean voidRegistration(String reference) {
        return repository.inTransaction(() -> {
            boolean changed = false;
            Optional<Invoice> invoice = repository.findInvoiceByReference(reference);
            if (invoice.isPresent() && invoice.get().isActive()) {
                invoice.get().voidInvoice();
                repository.updateInvoiceStatus(invoice.get());
                changed = true;
            }
            Optional<TranscriptEntry> entry = repository.findTranscriptByReference(reference);
            if (entry.isPresent() && entry.get().status() == TranscriptEntry.Status.IN_PROGRESS) {
                entry.get().withdraw();
                repository.updateTranscriptStatus(entry.get());
                changed = true;
            }
            return changed;
        });
    }

    // ---------------------------------------------------------------- queries / administration

    public Optional<Invoice> findInvoiceByReference(String reference) {
        return repository.findInvoiceByReference(reference);
    }

    public Optional<TranscriptEntry> findTranscriptByReference(String reference) {
        return repository.findTranscriptByReference(reference);
    }

    public List<Invoice> allInvoices() {
        return repository.findAllInvoices();
    }

    public List<TranscriptEntry> transcriptOf(String studentId) {
        return repository.findTranscriptOf(studentId);
    }

    public long outstandingOf(String studentId) {
        return repository.sumOutstanding(studentId);
    }

    public void defineAccount(String studentId, long creditLimit, int scholarshipPercent) {
        if (creditLimit < 0) {
            throw new IllegalArgumentException("credit limit must not be negative");
        }
        if (scholarshipPercent < 0 || scholarshipPercent > 100) {
            throw new IllegalArgumentException("scholarship percent must be between 0 and 100");
        }
        repository.saveAccount(new Account(studentId, creditLimit, scholarshipPercent));
    }

    public void setCreditLimit(String studentId, long creditLimit) {
        Account account = repository.findAccount(studentId).orElseThrow(() ->
                new IllegalArgumentException("No billing account for " + studentId));
        defineAccount(studentId, creditLimit, account.scholarshipPercent());
    }

    public Optional<Account> findAccount(String studentId) {
        return repository.findAccount(studentId);
    }

    public List<Account> allAccounts() {
        return repository.findAllAccounts();
    }

    public void defineCourseCredits(String courseId, int credits) {
        if (credits <= 0) {
            throw new IllegalArgumentException("credits must be positive");
        }
        repository.saveCourseCredits(courseId, credits);
    }

    public void setBillingWindowOpen(boolean open) {
        repository.setBillingWindowOpen(open);
    }

    public boolean isBillingWindowOpen() {
        return repository.isBillingWindowOpen();
    }

    public void resetAllData() {
        repository.clearAll();
    }

    @Override
    public void close() {
        repository.close();
    }
}
