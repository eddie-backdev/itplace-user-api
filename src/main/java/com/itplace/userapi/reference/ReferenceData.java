package com.itplace.userapi.reference;

import java.util.List;

public record ReferenceData(List<Grade> grades, List<Program> programs, List<Category> categories,
                            List<GradeMatch> gradeMatches) {
    public record Grade(String carrier, String code, String label, int sortOrder, boolean active,
                        boolean profileSelectable, boolean benefitEnabled, boolean allGradeMember) {}
    public record Program(String code, String label, int sortOrder, boolean active) {}
    public record Category(String code, String label, int sortOrder, boolean active) {}
    public record GradeMatch(String carrier, String profileGrade, String benefitGrade) {}
}
