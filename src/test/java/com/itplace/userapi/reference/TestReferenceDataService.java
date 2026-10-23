package com.itplace.userapi.reference;

import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import java.util.List;
import java.util.Map;

/** Small catalog fixture for unit tests; production always reads the database. */
public final class TestReferenceDataService extends ReferenceDataService {
    private static final Map<Carrier, List<Grade>> GRADES = Map.of(
            Carrier.SKT, List.of(Grade.SKT_LITE, Grade.SKT_SILVER, Grade.SKT_GOLD, Grade.SKT_VIP),
            Carrier.KT, List.of(Grade.KT_GENERAL, Grade.KT_WHITE, Grade.KT_SILVER, Grade.KT_GOLD, Grade.KT_VIP, Grade.KT_VVIP),
            Carrier.LGU, List.of(Grade.BASIC, Grade.VIP, Grade.VVIP, Grade.DIAMOND, Grade.VIP콕));

    public TestReferenceDataService() { super(null); }
    @Override public ReferenceData getReferenceData() {
        List<ReferenceData.Grade> grades = GRADES.entrySet().stream().flatMap(entry -> entry.getValue().stream()
                .map(grade -> new ReferenceData.Grade(entry.getKey().name(), grade.getCode(), gradeLabel(entry.getKey(), grade),
                        0, true, isSelectableProfile(entry.getKey(), grade), isBenefitGrade(entry.getKey(), grade),
                        !Grade.VIP콕.equals(grade)))).toList();
        return new ReferenceData(grades,
                List.of(new ReferenceData.Program("BASIC_BENEFIT", "기본 혜택", 10, true), new ReferenceData.Program("VIP_COCK", "VIP 콕", 20, true)),
                List.of(), List.of(new ReferenceData.GradeMatch("LGU", "VIP", "VIP콕"), new ReferenceData.GradeMatch("LGU", "VVIP", "VIP콕")));
    }
    @Override public boolean belongsToCarrier(Carrier carrier, Grade grade) { return carrier != null && grade != null && GRADES.getOrDefault(carrier, List.of()).contains(grade); }
    @Override public boolean isSelectableProfile(Carrier carrier, Grade grade) {
        return belongsToCarrier(carrier, grade) && !Grade.DIAMOND.equals(grade) && !Grade.VIP콕.equals(grade);
    }
    @Override public boolean isBenefitGrade(Carrier carrier, Grade grade) {
        return belongsToCarrier(carrier, grade) && !Grade.VIP콕.equals(grade);
    }
    @Override public List<Grade> allGradeMembers(Carrier carrier) {
        return carrier == null ? List.of() : GRADES.getOrDefault(carrier, List.of()).stream().filter(g -> !Grade.VIP콕.equals(g)).toList();
    }
    @Override public List<Grade> gradesForProfile(Carrier carrier, Grade grade) {
        if (!isBenefitGrade(carrier, grade)) return List.of();
        if (carrier == Carrier.LGU && (Grade.VIP.equals(grade) || Grade.VVIP.equals(grade))) return List.of(grade, Grade.VIP콕);
        return List.of(grade);
    }
    @Override public Grade findGradeMention(Carrier carrier, String text) {
        if (carrier == null || text == null) return null;
        String s = text.toUpperCase(java.util.Locale.ROOT);
        if (carrier == Carrier.SKT) {
            if (s.contains("LITE") || s.contains("라이트")) return Grade.SKT_LITE;
            if (s.contains("SILVER") || s.contains("실버")) return Grade.SKT_SILVER;
            if (s.contains("GOLD") || s.contains("골드")) return Grade.SKT_GOLD;
            if (s.matches(".*(?<![A-Z0-9_])VIP(?![A-Z0-9_]).*")) return Grade.SKT_VIP;
        }
        if (carrier == Carrier.KT) {
            if (s.contains("VVIP")) return Grade.KT_VVIP;
            if (s.matches(".*(?<![A-Z0-9_])VIP(?![A-Z0-9_]).*")) return Grade.KT_VIP;
            if (s.contains("GOLD") || s.contains("골드")) return Grade.KT_GOLD;
            if (s.contains("SILVER") || s.contains("실버")) return Grade.KT_SILVER;
            if (s.contains("WHITE") || s.contains("화이트")) return Grade.KT_WHITE;
            if (s.contains("GENERAL") || s.contains("일반")) return Grade.KT_GENERAL;
        }
        if (carrier == Carrier.LGU) {
            if (s.contains("DIAMOND") || s.contains("다이아몬드")) return Grade.DIAMOND;
            if (s.contains("VVIP")) return Grade.VVIP;
            if (s.contains("VIP콕")) return Grade.VIP콕;
            if (s.matches(".*(?<![A-Z0-9_])VIP(?![A-Z0-9_]).*")) return Grade.VIP;
            if (s.contains("BASIC") || s.contains("베이직") || s.contains("우수")) return Grade.BASIC;
        }
        return null;
    }
    @Override public String gradeLabel(Carrier carrier, Grade grade) {
        if (grade == null) return "";
        return switch (grade.getCode()) {
            case "BASIC" -> "우수"; case "SKT_LITE" -> "Lite"; case "SKT_SILVER", "KT_SILVER" -> "Silver";
            case "SKT_GOLD", "KT_GOLD" -> "Gold"; case "KT_GENERAL" -> "일반"; case "KT_WHITE" -> "White";
            case "DIAMOND" -> "다이아몬드"; default -> grade.getCode().replace("SKT_", "").replace("KT_", "");
        };
    }
    @Override public boolean isActiveProgram(String code) { return code != null && (code.equals("BASIC_BENEFIT") || code.equals("VIP_COCK")); }
    @Override public boolean isActiveCategory(String code) { return code != null; }
}
