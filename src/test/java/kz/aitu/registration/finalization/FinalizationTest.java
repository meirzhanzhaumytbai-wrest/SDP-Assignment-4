package kz.aitu.registration.finalization;

import kz.aitu.registration.finalization.FinalizationService.BillingRejectedException;
import kz.aitu.registration.finalization.FinalizationService.BillingRejectedException.Reason;
import kz.aitu.registration.finalization.Invoice.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinalizationTest {

    @Nested
    class InvoiceLifecycle {
        private Invoice fresh() {
            return Invoice.issue("ref", "S1", "CS301", "2026-FALL", 160_000);
        }

        @Test
        void startsIssuedAndOutstanding() {
            Invoice invoice = fresh();
            assertEquals(Status.ISSUED, invoice.status());
            assertTrue(invoice.isOutstanding());
            assertTrue(invoice.isActive());
        }

        @Test
        void issuedToPaidIsLegalAndPaidIsNoLongerOutstanding() {
            Invoice invoice = fresh();
            invoice.pay();
            assertEquals(Status.PAID, invoice.status());
            assertFalse(invoice.isOutstanding());
            assertTrue(invoice.isActive());
        }

        @Test
        void issuedAndPaidCanBothBeVoided() {
            Invoice a = fresh();
            a.voidInvoice();
            Invoice b = fresh();
            b.pay();
            b.voidInvoice();
            assertEquals(Status.VOIDED, a.status());
            assertEquals(Status.VOIDED, b.status());
            assertFalse(a.isActive());
        }

        @Test
        void voidedIsTerminalAndPaidCannotBePaidAgain() {
            Invoice voided = fresh();
            voided.voidInvoice();
            assertThrows(Invoice.IllegalTransitionException.class, voided::pay);
            assertThrows(Invoice.IllegalTransitionException.class, voided::voidInvoice);

            Invoice paid = fresh();
            paid.pay();
            assertThrows(Invoice.IllegalTransitionException.class, paid::pay);
            assertFalse(paid.canMoveTo(Status.ISSUED));
        }

        @Test
        void negativeAmountIsRefusedAndRestoreWorks() {
            assertThrows(IllegalArgumentException.class, () -> Invoice.issue("r", "S", "C", "T", -1));
            Invoice restored = Invoice.restore("id", "ref", "S1", "CS1", "T", 5, Status.PAID);
            assertEquals(Status.PAID, restored.status());
            assertTrue(restored.toString().contains("PAID"));
        }
    }

    @Nested
    class TranscriptLifecycle {
        @Test
        void inProgressCanBeWithdrawnOnlyOnce() {
            TranscriptEntry entry = TranscriptEntry.open("ref", "S1", "CS301", "2026-FALL");
            assertEquals(TranscriptEntry.Status.IN_PROGRESS, entry.status());
            entry.withdraw();
            assertEquals(TranscriptEntry.Status.WITHDRAWN, entry.status());
            assertThrows(TranscriptEntry.IllegalTransitionException.class, entry::withdraw);
            assertTrue(entry.toString().contains("WITHDRAWN"));
        }
    }

    @Nested
    class Calculator {
        private final TuitionCalculator calculator = new TuitionCalculator(40_000);

        @Test
        void tuitionIsCreditsTimesRateMinusScholarship() {
            assertEquals(160_000, calculator.tuition(4, 0));
            assertEquals(100_000, calculator.tuition(5, 50));
            assertEquals(0, calculator.tuition(3, 100));
        }

        @Test
        void limitCheckCountsWhatIsAlreadyOwed() {
            assertTrue(calculator.fitsLimit(200_000, 40_000, 160_000));
            assertFalse(calculator.fitsLimit(200_000, 40_001, 160_000));
        }

        @Test
        void invalidInputsAreRefused() {
            assertThrows(IllegalArgumentException.class, () -> calculator.tuition(0, 0));
            assertThrows(IllegalArgumentException.class, () -> calculator.tuition(3, -1));
            assertThrows(IllegalArgumentException.class, () -> calculator.tuition(3, 101));
            assertThrows(IllegalArgumentException.class, () -> new TuitionCalculator(0));
            assertEquals(TuitionCalculator.DEFAULT_RATE_PER_CREDIT, new TuitionCalculator().ratePerCredit());
        }
    }

    @Nested
    class Service {
        @TempDir
        Path dir;

        private FinalizationService finalization;

        @BeforeEach
        void openFreshStore() {
            finalization = FinalizationService.openAt(dir.resolve("c"));
            finalization.defineCourseCredits("CS301", 4);
            finalization.defineAccount("S1", 500_000, 0);
        }

        @AfterEach
        void closeStore() {
            finalization.close();
        }

        private Reason reasonOf(Runnable action) {
            return assertThrows(BillingRejectedException.class, action::run).reason();
        }

        @Test
        @DisplayName("finalizing issues the invoice and the transcript entry together")
        void issuesInvoiceAndTranscriptEntry() {
            Invoice invoice = finalization.finalizeRegistration("r1", "S1", "CS301", "2026-FALL");
            assertEquals(160_000, invoice.amount());
            assertEquals(Status.ISSUED, invoice.status());
            assertEquals(160_000, finalization.outstandingOf("S1"));
            assertEquals(TranscriptEntry.Status.IN_PROGRESS,
                    finalization.findTranscriptByReference("r1").orElseThrow().status());
            assertEquals(1, finalization.transcriptOf("S1").size());
            assertEquals(1, finalization.allInvoices().size());
        }

        @Test
        void scholarshipReducesTheInvoice() {
            finalization.defineAccount("S2", 500_000, 50);
            assertEquals(80_000, finalization.finalizeRegistration("r1", "S2", "CS301", "2026-FALL").amount());
        }

        @Test
        @DisplayName("exceeding the credit limit is refused and NOTHING is stored")
        void limitExceededStoresNothing() {
            finalization.setCreditLimit("S1", 100_000);
            assertEquals(Reason.LIMIT_EXCEEDED,
                    reasonOf(() -> finalization.finalizeRegistration("r1", "S1", "CS301", "2026-FALL")));
            assertTrue(finalization.allInvoices().isEmpty());
            assertTrue(finalization.transcriptOf("S1").isEmpty());
        }

        @Test
        void whatIsAlreadyOwedCountsAgainstTheLimit() {
            finalization.defineCourseCredits("CS350", 5);
            finalization.setCreditLimit("S1", 300_000);
            finalization.finalizeRegistration("r1", "S1", "CS301", "2026-FALL");      // 160 000 owed
            assertEquals(Reason.LIMIT_EXCEEDED,
                    reasonOf(() -> finalization.finalizeRegistration("r2", "S1", "CS350", "2026-FALL"))); // +200 000
            finalization.markPaid("r1");                                              // paying frees the limit
            assertEquals(200_000, finalization.finalizeRegistration("r2", "S1", "CS350", "2026-FALL").amount());
        }

        @Test
        void closedBillingOfficeUnknownAccountAndUnknownCourseAreRefused() {
            finalization.setBillingWindowOpen(false);
            assertFalse(finalization.isBillingWindowOpen());
            assertEquals(Reason.BILLING_CLOSED,
                    reasonOf(() -> finalization.finalizeRegistration("r1", "S1", "CS301", "2026-FALL")));
            finalization.setBillingWindowOpen(true);
            assertEquals(Reason.UNKNOWN_ACCOUNT,
                    reasonOf(() -> finalization.finalizeRegistration("r2", "NOBODY", "CS301", "2026-FALL")));
            assertEquals(Reason.UNKNOWN_COURSE,
                    reasonOf(() -> finalization.finalizeRegistration("r3", "S1", "NOPE", "2026-FALL")));
        }

        @Test
        void finalizeIsIdempotentPerReference() {
            Invoice first = finalization.finalizeRegistration("r1", "S1", "CS301", "2026-FALL");
            Invoice again = finalization.finalizeRegistration("r1", "S1", "CS301", "2026-FALL");
            assertEquals(first.id(), again.id());
            assertEquals(1, finalization.allInvoices().size());
        }

        @Test
        void markPaidIsIdempotentAndNeedsAnInvoice() {
            finalization.finalizeRegistration("r1", "S1", "CS301", "2026-FALL");
            assertEquals(Status.PAID, finalization.markPaid("r1").status());
            assertEquals(Status.PAID, finalization.markPaid("r1").status());
            assertThrows(IllegalStateException.class, () -> finalization.markPaid("missing"));
        }

        @Test
        @DisplayName("voidRegistration() voids the invoice AND withdraws the transcript entry, and is safe to repeat")
        void voidRegistrationUndoesBothAndIsIdempotent() {
            finalization.finalizeRegistration("r1", "S1", "CS301", "2026-FALL");
            assertTrue(finalization.voidRegistration("r1"));
            assertEquals(Status.VOIDED, finalization.findInvoiceByReference("r1").orElseThrow().status());
            assertEquals(TranscriptEntry.Status.WITHDRAWN,
                    finalization.findTranscriptByReference("r1").orElseThrow().status());
            assertEquals(0, finalization.outstandingOf("S1"));
            assertFalse(finalization.voidRegistration("r1"));
            assertFalse(finalization.voidRegistration("never-existed"));
        }

        @Test
        void aPaidInvoiceCanBeVoidedToo() {
            finalization.finalizeRegistration("r1", "S1", "CS301", "2026-FALL");
            finalization.markPaid("r1");
            assertTrue(finalization.voidRegistration("r1"));
        }

        @Test
        @DisplayName("invoices, accounts and the closed billing office survive closing and reopening the store")
        void stateIsReadBackFromDiskAfterReopen() {
            finalization.finalizeRegistration("r1", "S1", "CS301", "2026-FALL");
            finalization.setBillingWindowOpen(false);
            finalization.close();

            finalization = FinalizationService.openAt(dir.resolve("c"));
            assertEquals(Status.ISSUED, finalization.findInvoiceByReference("r1").orElseThrow().status());
            assertEquals(160_000, finalization.outstandingOf("S1"));
            assertEquals(500_000, finalization.findAccount("S1").orElseThrow().creditLimit());
            assertEquals(1, finalization.allAccounts().size());
            assertFalse(finalization.isBillingWindowOpen());
        }

        @Test
        void invalidAdministrationIsRefused() {
            assertThrows(IllegalArgumentException.class, () -> finalization.defineAccount("S9", -1, 0));
            assertThrows(IllegalArgumentException.class, () -> finalization.defineAccount("S9", 1, 101));
            assertThrows(IllegalArgumentException.class, () -> finalization.defineCourseCredits("X", 0));
            assertThrows(IllegalArgumentException.class, () -> finalization.setCreditLimit("NOBODY", 5));
        }

        @Test
        void resetAllDataEmptiesTheStore() {
            finalization.finalizeRegistration("r1", "S1", "CS301", "2026-FALL");
            finalization.resetAllData();
            assertTrue(finalization.allInvoices().isEmpty());
            assertTrue(finalization.allAccounts().isEmpty());
            assertTrue(finalization.isBillingWindowOpen());
        }
    }
}
