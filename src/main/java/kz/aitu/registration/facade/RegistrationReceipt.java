package kz.aitu.registration.facade;

public record RegistrationReceipt(String chainId, String studentId, String courseId, String term,
                                  long tuitionKzt, ChainStatus status) {
}
