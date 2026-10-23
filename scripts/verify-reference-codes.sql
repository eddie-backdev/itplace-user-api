-- Run after V20261021_0001 on a disposable restored DB, with psql -v ON_ERROR_STOP=1.
-- All test records and changes roll back. Requires at least one LGU policy.
BEGIN;
CREATE FUNCTION pg_temp.expect_reference_failure(statement TEXT, expected_state TEXT)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE actual_state TEXT;
BEGIN
    BEGIN
        EXECUTE statement;
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS actual_state = RETURNED_SQLSTATE;
        IF actual_state = expected_state THEN RETURN; END IF;
        RAISE EXCEPTION 'Expected SQLSTATE %, received %: %', expected_state, actual_state, SQLERRM;
    END;
    RAISE EXCEPTION 'Invalid reference was accepted: %', statement;
END $$;

INSERT INTO membership_grade
    (code, carrier_code, label, profile_selectable, all_grade_member)
VALUES ('LGU_REFERENCE_TEST', 'LGU', '새 등급 검증', TRUE, TRUE);
INSERT INTO membership_grade_alias VALUES ('LGU', '새 등급 검증', 'LGU_REFERENCE_TEST');
INSERT INTO partner_category VALUES ('REFERENCE_TEST', '새 카테고리 검증', 1000, TRUE);
INSERT INTO membership_program VALUES ('REFERENCE_TEST', '새 프로그램 검증', 1000, TRUE);
INSERT INTO partner(partnerid, partnername, category)
VALUES (-2026102004, '참조 코드 검증', 'REFERENCE_TEST');
INSERT INTO benefit(benefitid, partnerid, maincategory, benefitname, active)
VALUES (-2026102004, -2026102004, 'REFERENCE_TEST', '참조 코드 검증', TRUE);
INSERT INTO users(userid, role, carrier, membershipgradecode)
VALUES (-2026102004, 'USER', 'LGU', 'LGU_REFERENCE_TEST');
INSERT INTO carriertierbenefit(carriertierbenefitid, benefitcarrierpolicyid, grade, context)
SELECT -2026102004, min(benefitcarrierpolicyid), 'LGU_REFERENCE_TEST', '참조 코드 검증'
FROM benefitcarrierpolicy WHERE carrier = 'LGU';

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM users WHERE userid = -2026102004
        AND membershipgradecode = 'LGU_REFERENCE_TEST' AND "membershipGradeCode" = membershipgradecode) THEN
        RAISE EXCEPTION 'Profile grade columns were not synchronized';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM carriertierbenefit WHERE carriertierbenefitid = -2026102004
        AND carrier_code = 'LGU' AND grade = 'LGU_REFERENCE_TEST') THEN
        RAISE EXCEPTION 'Tier carrier was not derived from its policy';
    END IF;
END $$;

SELECT pg_temp.expect_reference_failure(
    $$UPDATE carriertierbenefit SET grade = 'NOT_A_GRADE' WHERE carriertierbenefitid = -2026102004$$, '23503');
SELECT pg_temp.expect_reference_failure(
    $$UPDATE carriertierbenefit SET grade = 'KT_VIP' WHERE carriertierbenefitid = -2026102004$$, '23503');
SELECT pg_temp.expect_reference_failure(
    $$UPDATE carriertierbenefit SET carrier_code = 'SKT' WHERE carriertierbenefitid = -2026102004$$, '23503');
SELECT pg_temp.expect_reference_failure(
    $$UPDATE users SET membershipgradecode = 'DIAMOND' WHERE userid = -2026102004$$, '23503');
SELECT pg_temp.expect_reference_failure(
    $$UPDATE users SET carrier = 'KT' WHERE userid = -2026102004$$, '23503');
SELECT pg_temp.expect_reference_failure(
    $$UPDATE users SET membershipgradecode = 'VIP', "membershipGradeCode" = 'VVIP' WHERE userid = -2026102004$$, '23514');
SELECT pg_temp.expect_reference_failure(
    $$UPDATE partner SET category = 'NOT_A_CATEGORY' WHERE partnerid = -2026102004$$, '23503');
SELECT pg_temp.expect_reference_failure(
    $$UPDATE benefit SET maincategory = 'NOT_A_PROGRAM' WHERE benefitid = -2026102004$$, '23503');
SELECT pg_temp.expect_reference_failure(
    $$INSERT INTO membership_grade_alias VALUES ('SKT', '잘못된 통신사', 'LGU_REFERENCE_TEST')$$, '23503');
SELECT pg_temp.expect_reference_failure(
    $$INSERT INTO partner_category_alias VALUES ('LGU', ' ott ', '쇼핑')$$, '23505');
SELECT pg_temp.expect_reference_failure(
    $$DELETE FROM membership_grade WHERE code = 'LGU_REFERENCE_TEST'$$, '23503');

-- Retiring a code preserves history but prohibits new assignments.
UPDATE membership_grade SET active = FALSE WHERE code = 'LGU_REFERENCE_TEST';
UPDATE users SET membershipgradecode = membershipgradecode WHERE userid = -2026102004;
UPDATE carriertierbenefit SET context = '역사 보존 검증' WHERE carriertierbenefitid = -2026102004;
SELECT pg_temp.expect_reference_failure(
    $$INSERT INTO users(userid, role, carrier, membershipgradecode)
      VALUES (-2026102005, 'USER', 'LGU', 'LGU_REFERENCE_TEST')$$, '23503');
SELECT pg_temp.expect_reference_failure(
    $$INSERT INTO carriertierbenefit(carriertierbenefitid, benefitcarrierpolicyid, grade, context)
      SELECT -2026102005, benefitcarrierpolicyid, 'LGU_REFERENCE_TEST', '비활성 등급'
      FROM carriertierbenefit WHERE carriertierbenefitid = -2026102004$$, '23503');

-- Legacy-column callers get the same validation and synchronization.
UPDATE users SET "membershipGradeCode" = 'VIP' WHERE userid = -2026102004;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM users WHERE userid = -2026102004
        AND membershipgradecode = 'VIP' AND "membershipGradeCode" = 'VIP') THEN
        RAISE EXCEPTION 'Legacy grade writer bypassed synchronization';
    END IF;
END $$;
SELECT 'reference-code database checks passed' AS result;
ROLLBACK;
