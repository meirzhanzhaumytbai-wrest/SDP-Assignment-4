package kz.aitu.registration.facade;

/** What a client asks for: one student wants one course in one term. */
public record RegistrationRequest(String studentId, String courseId, String term) {

    public RegistrationRequest {
        studentId = studentId == null ? "" : studentId.trim();
        courseId = courseId == null ? "" : courseId.trim();
        term = term == null ? "" : term.trim();
    }

    boolean isComplete() {
        return !studentId.isEmpty() && !courseId.isEmpty() && !term.isEmpty();
    }
}
