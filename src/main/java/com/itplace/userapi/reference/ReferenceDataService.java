package com.itplace.userapi.reference;

import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ReferenceDataService {
    private final JdbcTemplate jdbc;
    public ReferenceDataService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public ReferenceData getReferenceData() {
        var grades = jdbc.query("SELECT carrier_code, code, label, sort_order, active, profile_selectable, benefit_enabled, all_grade_member FROM membership_grade ORDER BY carrier_code, sort_order, code",
                (rs, n) -> new ReferenceData.Grade(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getBoolean(5), rs.getBoolean(6), rs.getBoolean(7), rs.getBoolean(8)));
        var programs = jdbc.query("SELECT code, label, sort_order, active FROM membership_program ORDER BY sort_order, code",
                (rs, n) -> new ReferenceData.Program(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getBoolean(4)));
        var categories = jdbc.query("SELECT code, label, sort_order, active FROM partner_category ORDER BY sort_order, code",
                (rs, n) -> new ReferenceData.Category(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getBoolean(4)));
        var matches = jdbc.query("SELECT carrier_code, profile_grade, benefit_grade FROM membership_grade_match ORDER BY carrier_code, profile_grade, benefit_grade",
                (rs, n) -> new ReferenceData.GradeMatch(rs.getString(1), rs.getString(2), rs.getString(3)));
        return new ReferenceData(grades, programs, categories, matches);
    }

    public boolean isSelectableProfile(Carrier carrier, Grade grade) {
        return lookupGradeFlag(carrier, grade, "profile_selectable");
    }
    public boolean isBenefitGrade(Carrier carrier, Grade grade) {
        return lookupGradeFlag(carrier, grade, "benefit_enabled");
    }
    private boolean lookupGradeFlag(Carrier carrier, Grade grade, String flag) {
        if (carrier == null || grade == null) return false;
        return jdbc.query("SELECT active AND " + flag + " FROM membership_grade WHERE carrier_code=? AND code=?",
                rs -> rs.next() && rs.getBoolean(1), carrier.name(), grade.getCode());
    }
    public List<Grade> allGradeMembers(Carrier carrier) {
        if (carrier == null) return List.of();
        return jdbc.query("SELECT code FROM membership_grade WHERE carrier_code=? AND active AND benefit_enabled AND all_grade_member ORDER BY sort_order, code",
                (rs, n) -> Grade.of(rs.getString(1)), carrier.name());
    }
    public Map<String, String> gradeLabels(Carrier carrier) {
        if (carrier == null) return Map.of();
        return jdbc.query("SELECT code, label FROM membership_grade WHERE carrier_code=?",
                rs -> { Map<String, String> labels = new java.util.HashMap<>(); while (rs.next()) labels.put(rs.getString(1), rs.getString(2)); return Map.copyOf(labels); }, carrier.name());
    }
    public List<Grade> gradesForProfile(Carrier carrier, Grade profileGrade) {
        if (carrier == null || profileGrade == null) return List.of();
        LinkedHashSet<Grade> grades = new LinkedHashSet<>();
        if (isBenefitGrade(carrier, profileGrade)) grades.add(profileGrade);
        grades.addAll(jdbc.query("SELECT m.benefit_grade FROM membership_grade_match m JOIN membership_grade g ON g.carrier_code=m.carrier_code AND g.code=m.benefit_grade WHERE m.carrier_code=? AND m.profile_grade=? AND g.active AND g.benefit_enabled ORDER BY m.benefit_grade",
                (rs, n) -> Grade.of(rs.getString(1)), carrier.name(), profileGrade.getCode()));
        return List.copyOf(grades);
    }
    public boolean belongsToCarrier(Carrier carrier, Grade grade) {
        if (carrier == null || grade == null) return false;
        return jdbc.query("SELECT EXISTS (SELECT 1 FROM membership_grade WHERE carrier_code=? AND code=? AND active)",
                rs -> rs.next() && rs.getBoolean(1), carrier.name(), grade.getCode());
    }
    public Grade findGradeMention(Carrier carrier, String text) {
        if (carrier == null || text == null || text.isBlank()) return null;
        String normalized = text.toUpperCase(Locale.ROOT);
        List<String> aliases = jdbc.query("SELECT a.source_label FROM membership_grade_alias a JOIN membership_grade g ON g.carrier_code=a.carrier_code AND g.code=a.grade_code WHERE a.carrier_code=? AND g.active ORDER BY length(a.source_label) DESC, a.source_label",
                (rs, n) -> rs.getString(1), carrier.name());
        for (String alias : aliases) {
            String token = alias.toUpperCase(Locale.ROOT);
            boolean match = token.matches(".*[A-Z0-9_].*")
                    ? java.util.regex.Pattern.compile("(?<![A-Z0-9_])" + java.util.regex.Pattern.quote(token) + "(?![A-Z0-9_])")
                        .matcher(normalized).find()
                    : normalized.contains(token);
            if (match) return resolveGradeAlias(carrier, token);
        }
        return null;
    }
    public Grade resolveGradeAlias(Carrier carrier, String label) {
        if (carrier == null || label == null || label.isBlank()) return null;
        String normalized = label.trim().toUpperCase(Locale.ROOT);
        return jdbc.query("SELECT g.code FROM membership_grade_alias a JOIN membership_grade g ON g.carrier_code=a.carrier_code AND g.code=a.grade_code WHERE a.carrier_code=? AND upper(trim(a.source_label))=? AND g.active",
                rs -> rs.next() ? Grade.of(rs.getString(1)) : null, carrier.name(), normalized);
    }
    public String gradeLabel(Carrier carrier, Grade grade) {
        if (grade == null) return "";
        if (carrier == null) return grade.getCode();
        return jdbc.query("SELECT label FROM membership_grade WHERE carrier_code=? AND code=?",
                rs -> rs.next() ? rs.getString(1) : grade.getCode(), carrier.name(), grade.getCode());
    }
    public boolean isActiveProgram(String code) {
        return code != null && jdbc.query("SELECT active FROM membership_program WHERE code=?", rs -> rs.next() && rs.getBoolean(1), code);
    }
    public boolean isActiveCategory(String code) {
        return code != null && jdbc.query("SELECT active FROM partner_category WHERE code=?", rs -> rs.next() && rs.getBoolean(1), code);
    }
}
