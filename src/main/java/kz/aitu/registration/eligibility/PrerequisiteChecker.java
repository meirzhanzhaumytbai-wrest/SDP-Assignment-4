package kz.aitu.registration.eligibility;

import java.util.Set;
import java.util.TreeSet;

/** Pure business logic: no state, no storage. Decides whether the prerequisites of a course are covered. */
public class PrerequisiteChecker {

    /** @return the required courses the student has NOT completed (sorted, empty when all are covered) */
    public Set<String> missing(Set<String> completedCourses, Set<String> requiredCourses) {
        Set<String> missing = new TreeSet<>(requiredCourses);
        missing.removeAll(completedCourses);
        return missing;
    }

    public boolean isSatisfied(Set<String> completedCourses, Set<String> requiredCourses) {
        return missing(completedCourses, requiredCourses).isEmpty();
    }
}
