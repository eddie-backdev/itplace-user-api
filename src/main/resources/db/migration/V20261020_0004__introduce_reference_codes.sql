-- Values and display metadata are data; Carrier and behavioral enums remain code.
-- This migration requires the matching application release (mainCategory now stores codes).
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '180s';

CREATE TABLE membership_grade (
    code VARCHAR(30) PRIMARY KEY CHECK (code = btrim(code) AND code <> ''),
    carrier_code VARCHAR(10) NOT NULL CHECK (carrier_code IN ('SKT', 'KT', 'LGU')),
    label TEXT NOT NULL CHECK (btrim(label) <> ''),
    sort_order INTEGER NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    profile_selectable BOOLEAN NOT NULL DEFAULT FALSE,
    benefit_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    all_grade_member BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (carrier_code, code)
);
INSERT INTO membership_grade
    (carrier_code, code, label, sort_order, profile_selectable, all_grade_member) VALUES
    ('LGU', 'BASIC', '우수', 10, TRUE, TRUE),
    ('LGU', 'VIP', 'VIP', 20, TRUE, TRUE),
    ('LGU', 'VVIP', 'VVIP', 30, TRUE, TRUE),
    ('LGU', 'DIAMOND', '다이아몬드', 40, FALSE, TRUE),
    ('LGU', 'VIP콕', 'VIP 콕', 50, FALSE, FALSE),
    ('SKT', 'SKT_SILVER', 'Silver', 10, TRUE, TRUE),
    ('SKT', 'SKT_GOLD', 'Gold', 20, TRUE, TRUE),
    ('SKT', 'SKT_VIP', 'VIP', 30, TRUE, TRUE),
    ('SKT', 'SKT_LITE', 'Lite', 40, TRUE, TRUE),
    ('KT', 'KT_GENERAL', '일반', 10, TRUE, TRUE),
    ('KT', 'KT_WHITE', 'White', 20, TRUE, TRUE),
    ('KT', 'KT_SILVER', 'Silver', 30, TRUE, TRUE),
    ('KT', 'KT_GOLD', 'Gold', 40, TRUE, TRUE),
    ('KT', 'KT_VIP', 'VIP', 50, TRUE, TRUE),
    ('KT', 'KT_VVIP', 'VVIP', 60, TRUE, TRUE);

CREATE TABLE membership_program (
    code VARCHAR(50) PRIMARY KEY CHECK (code = btrim(code) AND code <> ''),
    label TEXT NOT NULL CHECK (btrim(label) <> ''),
    sort_order INTEGER NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE
);
INSERT INTO membership_program (code, label, sort_order) VALUES
    ('BASIC_BENEFIT', '기본 혜택', 10), ('VIP_COCK', 'VIP 콕', 20);
UPDATE benefit SET maincategory = CASE maincategory
    WHEN '기본 혜택' THEN 'BASIC_BENEFIT' WHEN 'VIP 콕' THEN 'VIP_COCK' ELSE maincategory END;
-- Preserve historical unknown values as retired references. Never auto-register at runtime.
INSERT INTO membership_program (code, label, active)
SELECT DISTINCT maincategory, maincategory, FALSE FROM benefit
WHERE maincategory IS NOT NULL AND btrim(maincategory) <> ''
ON CONFLICT (code) DO NOTHING;
UPDATE benefit SET maincategory = NULL WHERE maincategory IS NOT NULL AND btrim(maincategory) = '';
ALTER TABLE benefit ADD CONSTRAINT fk_benefit_program
    FOREIGN KEY (maincategory) REFERENCES membership_program(code);

CREATE TABLE partner_category (
    code VARCHAR(50) PRIMARY KEY CHECK (code = btrim(code) AND code <> ''),
    label TEXT NOT NULL CHECK (btrim(label) <> ''),
    sort_order INTEGER NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE
);
INSERT INTO partner_category (code, label, sort_order) VALUES
    ('엑티비티', '여가/체험', 10), ('뷰티/건강', '뷰티·건강', 20),
    ('쇼핑', '쇼핑', 30), ('생활/편의', '생활·편의', 40),
    ('푸드', '음식·카페', 50), ('문화/여가', '문화·여가', 60),
    ('교육', '교육', 70), ('여행/교통', '여행·교통', 80);
INSERT INTO partner_category (code, label, active)
SELECT DISTINCT category, category, FALSE FROM partner
WHERE category IS NOT NULL AND btrim(category) <> ''
ON CONFLICT (code) DO NOTHING;
UPDATE partner SET category = NULL WHERE category IS NOT NULL AND btrim(category) = '';
ALTER TABLE partner ADD CONSTRAINT fk_partner_category
    FOREIGN KEY (category) REFERENCES partner_category(code);

CREATE TABLE membership_grade_alias (
    carrier_code VARCHAR(10) NOT NULL,
    source_label VARCHAR(100) NOT NULL CHECK (source_label = upper(btrim(source_label)) AND source_label <> ''),
    grade_code VARCHAR(30) NOT NULL,
    PRIMARY KEY (carrier_code, source_label),
    FOREIGN KEY (carrier_code, grade_code) REFERENCES membership_grade(carrier_code, code)
);
INSERT INTO membership_grade_alias (carrier_code, source_label, grade_code)
SELECT carrier_code, upper(code), code FROM membership_grade;
INSERT INTO membership_grade_alias (carrier_code, source_label, grade_code) VALUES
    ('LGU', '우수', 'BASIC'), ('LGU', '베이직', 'BASIC'),
    ('LGU', '다이아몬드', 'DIAMOND'), ('LGU', 'VIP 콕', 'VIP콕'),
    ('SKT', 'LITE', 'SKT_LITE'), ('SKT', '라이트', 'SKT_LITE'),
    ('SKT', 'SILVER', 'SKT_SILVER'), ('SKT', '실버', 'SKT_SILVER'),
    ('SKT', 'GOLD', 'SKT_GOLD'), ('SKT', '골드', 'SKT_GOLD'), ('SKT', 'VIP', 'SKT_VIP'),
    ('KT', 'GENERAL', 'KT_GENERAL'), ('KT', '일반', 'KT_GENERAL'),
    ('KT', 'WHITE', 'KT_WHITE'), ('KT', '화이트', 'KT_WHITE'),
    ('KT', 'SILVER', 'KT_SILVER'), ('KT', '실버', 'KT_SILVER'),
    ('KT', 'GOLD', 'KT_GOLD'), ('KT', '골드', 'KT_GOLD'),
    ('KT', 'VIP', 'KT_VIP'), ('KT', 'VVIP', 'KT_VVIP'),
    ('KT', 'COLOR-GOLD', 'KT_GOLD'), ('KT', 'COLOR-VIP', 'KT_VIP'),
    ('KT', 'COLOR-VVIP', 'KT_VVIP');

CREATE TABLE partner_category_alias (
    carrier_code VARCHAR(10) NOT NULL CHECK (carrier_code IN ('SKT', 'KT', 'LGU')),
    source_label VARCHAR(100) NOT NULL CHECK (btrim(source_label) <> ''),
    category_code VARCHAR(50) NOT NULL REFERENCES partner_category(code),
    PRIMARY KEY (carrier_code, source_label)
);
INSERT INTO partner_category_alias (carrier_code, source_label, category_code)
SELECT c.carrier, a.source_label, a.category_code
FROM (VALUES ('SKT'), ('KT'), ('LGU')) AS c(carrier)
CROSS JOIN (VALUES
    ('액티비티', '엑티비티'), ('놀이공원', '엑티비티'), ('테마파크', '엑티비티'), ('레저', '엑티비티'),
    ('영화', '문화/여가'), ('공연', '문화/여가'), ('OTT', '문화/여가'),
    ('카페', '푸드'), ('디저트', '푸드'), ('베이커리', '푸드'), ('패스트푸드', '푸드'), ('외식', '푸드'),
    ('마트', '쇼핑'), ('온라인쇼핑', '쇼핑'), ('편의점', '생활/편의'), ('결제', '생활/편의'),
    ('택시', '여행/교통'), ('교통', '여행/교통'), ('화장품', '뷰티/건강'), ('도서', '교육'), ('어학', '교육')
) AS a(source_label, category_code);
INSERT INTO partner_category_alias (carrier_code, source_label, category_code)
SELECT c.carrier, p.code, p.code FROM partner_category p
CROSS JOIN (VALUES ('SKT'), ('KT'), ('LGU')) AS c(carrier) WHERE p.active;

CREATE TABLE membership_grade_match (
    carrier_code VARCHAR(10) NOT NULL,
    profile_grade VARCHAR(30) NOT NULL,
    benefit_grade VARCHAR(30) NOT NULL,
    PRIMARY KEY (carrier_code, profile_grade, benefit_grade),
    FOREIGN KEY (carrier_code, profile_grade) REFERENCES membership_grade(carrier_code, code),
    FOREIGN KEY (carrier_code, benefit_grade) REFERENCES membership_grade(carrier_code, code),
    CHECK (profile_grade <> benefit_grade)
);
INSERT INTO membership_grade_match VALUES ('LGU', 'VIP', 'VIP콕'), ('LGU', 'VVIP', 'VIP콕');

ALTER TABLE carrierTierBenefit DROP CONSTRAINT IF EXISTS carrierTierBenefit_grade_check;
ALTER TABLE carrierTierBenefit ADD COLUMN carrier_code VARCHAR(10);
UPDATE carrierTierBenefit t SET carrier_code = p.carrier
FROM benefitCarrierPolicy p WHERE p.benefitCarrierPolicyId = t.benefitCarrierPolicyId;
ALTER TABLE carrierTierBenefit ALTER COLUMN carrier_code SET NOT NULL;
ALTER TABLE benefitCarrierPolicy ADD CONSTRAINT uq_policy_id_carrier
    UNIQUE (benefitCarrierPolicyId, carrier);
ALTER TABLE carrierTierBenefit ADD CONSTRAINT fk_tier_policy_carrier
    FOREIGN KEY (benefitCarrierPolicyId, carrier_code)
    REFERENCES benefitCarrierPolicy(benefitCarrierPolicyId, carrier);
ALTER TABLE carrierTierBenefit ADD CONSTRAINT fk_tier_carrier_grade
    FOREIGN KEY (carrier_code, grade) REFERENCES membership_grade(carrier_code, code);

-- Derive carrier at the database boundary so old and new writers cannot omit this protection.
CREATE FUNCTION assign_tier_reference_carrier() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE parent_carrier VARCHAR(10);
BEGIN
    SELECT carrier INTO parent_carrier FROM benefitCarrierPolicy
    WHERE benefitCarrierPolicyId = NEW.benefitCarrierPolicyId;
    IF parent_carrier IS NULL THEN
        RAISE EXCEPTION 'Missing carrier policy' USING ERRCODE = '23503';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.carrier_code IS NOT NULL AND NEW.carrier_code <> parent_carrier THEN
            RAISE EXCEPTION 'Tier carrier does not match policy' USING ERRCODE = '23503';
        END IF;
    ELSIF NEW.carrier_code IS DISTINCT FROM OLD.carrier_code AND NEW.carrier_code <> parent_carrier THEN
        RAISE EXCEPTION 'Tier carrier does not match policy' USING ERRCODE = '23503';
    END IF;
    NEW.carrier_code := parent_carrier;
    IF TG_OP = 'INSERT' OR NEW.grade IS DISTINCT FROM OLD.grade
        OR NEW.benefitCarrierPolicyId IS DISTINCT FROM OLD.benefitCarrierPolicyId THEN
        IF NOT EXISTS (SELECT 1 FROM membership_grade
            WHERE carrier_code = parent_carrier AND code = NEW.grade AND active AND benefit_enabled) THEN
            RAISE EXCEPTION 'Grade is unavailable for this carrier benefit' USING ERRCODE = '23503';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER tr_tier_reference_carrier BEFORE INSERT OR UPDATE ON carrierTierBenefit
    FOR EACH ROW EXECUTE FUNCTION assign_tier_reference_carrier();

DO $$ BEGIN
    IF to_regclass('tierbenefit') IS NOT NULL THEN
        ALTER TABLE tierBenefit DROP CONSTRAINT IF EXISTS tierBenefit_grade_check;
        ALTER TABLE tierBenefit ADD CONSTRAINT fk_legacy_tier_grade
            FOREIGN KEY (grade) REFERENCES membership_grade(code);
    END IF;
END $$;

-- Reconcile the legacy quoted column with Hibernate's actual lowercase column without losing conflicts.
ALTER TABLE users ADD COLUMN IF NOT EXISTS membershipgradecode VARCHAR(30);
ALTER TABLE users ADD COLUMN IF NOT EXISTS "membershipGradeCode" VARCHAR(30);
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM users WHERE membershipgradecode IS NOT NULL
        AND "membershipGradeCode" IS NOT NULL AND membershipgradecode <> "membershipGradeCode") THEN
        RAISE EXCEPTION 'Conflicting membership grade columns; review users before migrating';
    END IF;
END $$;
UPDATE users SET membershipgradecode = COALESCE(membershipgradecode, "membershipGradeCode"),
    "membershipGradeCode" = COALESCE(membershipgradecode, "membershipGradeCode");
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_membershipgradecode_check;
ALTER TABLE users DROP CONSTRAINT IF EXISTS "users_membershipGradeCode_check";
ALTER TABLE users DROP CONSTRAINT IF EXISTS ck_users_grade_lowercase;
ALTER TABLE users DROP CONSTRAINT IF EXISTS ck_users_grade_quoted;
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_carrier_check;
ALTER TABLE users ADD CONSTRAINT users_carrier_check CHECK (carrier IN ('SKT', 'KT', 'LGU', 'MVNO'));
ALTER TABLE users ADD CONSTRAINT ck_users_grade_requires_carrier
    CHECK (membershipgradecode IS NULL OR carrier IS NOT NULL);
ALTER TABLE users ADD CONSTRAINT fk_users_membership_grade
    FOREIGN KEY (carrier, membershipgradecode) REFERENCES membership_grade(carrier_code, code);
ALTER TABLE users ADD CONSTRAINT fk_users_membership_grade_legacy
    FOREIGN KEY (carrier, "membershipGradeCode") REFERENCES membership_grade(carrier_code, code);

CREATE FUNCTION validate_profile_reference_grade() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE changed BOOLEAN;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.membershipgradecode IS NOT NULL AND NEW."membershipGradeCode" IS NOT NULL
            AND NEW.membershipgradecode <> NEW."membershipGradeCode" THEN
            RAISE EXCEPTION 'Conflicting membership grade columns' USING ERRCODE = '23514';
        END IF;
        NEW.membershipgradecode := COALESCE(NEW.membershipgradecode, NEW."membershipGradeCode");
        changed := TRUE;
    ELSE
        IF NEW.membershipgradecode IS DISTINCT FROM OLD.membershipgradecode THEN
            IF NEW."membershipGradeCode" IS DISTINCT FROM OLD."membershipGradeCode"
                AND NEW.membershipgradecode IS DISTINCT FROM NEW."membershipGradeCode" THEN
                RAISE EXCEPTION 'Conflicting membership grade columns' USING ERRCODE = '23514';
            END IF;
        ELSIF NEW."membershipGradeCode" IS DISTINCT FROM OLD."membershipGradeCode" THEN
            NEW.membershipgradecode := NEW."membershipGradeCode";
        END IF;
        changed := NEW.carrier IS DISTINCT FROM OLD.carrier
            OR NEW.membershipgradecode IS DISTINCT FROM OLD.membershipgradecode;
    END IF;
    NEW."membershipGradeCode" := NEW.membershipgradecode;
    IF changed AND NEW.membershipgradecode IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM membership_grade WHERE carrier_code = NEW.carrier
            AND code = NEW.membershipgradecode AND active AND profile_selectable
    ) THEN
        RAISE EXCEPTION 'Grade is unavailable for this carrier profile' USING ERRCODE = '23503';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER tr_profile_reference_grade
    BEFORE INSERT OR UPDATE OF carrier, membershipgradecode, "membershipGradeCode" ON users
    FOR EACH ROW EXECUTE FUNCTION validate_profile_reference_grade();
