package kz.aitu.registration.facade;

public record RecoveryReport(String chainId, ChainStatus statusBefore, ChainStatus statusAfter, String action) {
}
