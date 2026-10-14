package kz.aitu.registration.facade;

/** What recovery decided for one interrupted chain after a restart. */
public record RecoveryReport(String chainId, ChainStatus statusBefore, ChainStatus statusAfter, String action) {
}
