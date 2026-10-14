package kz.aitu.registration.facade;


public record ChainInspection(ChainRecord chain, String clearanceStatus, String seatStatus,
                              String invoiceStatus, String transcriptStatus) {
}
