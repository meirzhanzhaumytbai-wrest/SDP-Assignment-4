package kz.aitu.registration.facade;

/** What a client gets back after a fully successful registration. */
public record RegistrationReceipt(String chainId, String studentId, String courseId, String term,
                                  long tuitionKzt, ChainStatus status) {
}
