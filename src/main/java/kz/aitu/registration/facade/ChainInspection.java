package kz.aitu.registration.facade;

/**
 * Administrative view of one chain: what the facade's log says, next to what each subsystem actually
 * has on disk ("-" means the subsystem holds nothing for this chain).
 */
public record ChainInspection(ChainRecord chain, String clearanceStatus, String seatStatus,
                              String invoiceStatus, String transcriptStatus) {
}
