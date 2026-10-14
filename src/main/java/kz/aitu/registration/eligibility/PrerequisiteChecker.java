package kz.aitu.registration.eligibility;

import java.util.Set;
import java.util.TreeSet;

public class PrerequisiteChecker {

    public Set<String> missing(Set<String> completedCourses, Set<String> requiredCourses) {
        Set<String> missing = new TreeSet<>(requiredCourses);
        missing.removeAll(completedCourses);
        return missing;
    }

    public boolean isSatisfied(Set<String> completedCourses, Set<String> requiredCourses) {
        return missing(completedCourses, requiredCourses).isEmpty();
    }
}
