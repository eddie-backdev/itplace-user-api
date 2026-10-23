ALTER TABLE carrierTierBenefit DROP CONSTRAINT IF EXISTS carrierTierBenefit_grade_check;
ALTER TABLE carrierTierBenefit
    ADD CONSTRAINT carrierTierBenefit_grade_check CHECK (grade IN (
        'BASIC', 'VIP', 'VVIP', 'VIP콕',
        'SKT_LITE', 'SKT_SILVER', 'SKT_GOLD', 'SKT_VIP',
        'KT_GENERAL', 'KT_WHITE', 'KT_SILVER', 'KT_GOLD', 'KT_VIP', 'KT_VVIP'
    ));

ALTER TABLE IF EXISTS tierBenefit DROP CONSTRAINT IF EXISTS tierBenefit_grade_check;
ALTER TABLE IF EXISTS tierBenefit
    ADD CONSTRAINT tierBenefit_grade_check CHECK (grade IN (
        'BASIC', 'VIP', 'VVIP', 'VIP콕',
        'SKT_LITE', 'SKT_SILVER', 'SKT_GOLD', 'SKT_VIP',
        'KT_GENERAL', 'KT_WHITE', 'KT_SILVER', 'KT_GOLD', 'KT_VIP', 'KT_VVIP'
    ));

DO $$
DECLARE
    grade_column text;
    constraint_suffix text;
BEGIN
    FOR grade_column IN
        SELECT column_name
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'users'
          AND column_name IN ('membershipgradecode', 'membershipGradeCode')
    LOOP
        IF grade_column = 'membershipgradecode' THEN
            ALTER TABLE users DROP CONSTRAINT IF EXISTS users_membershipgradecode_check;
            constraint_suffix := 'lowercase';
        ELSE
            ALTER TABLE users DROP CONSTRAINT IF EXISTS "users_membershipGradeCode_check";
            constraint_suffix := 'quoted';
        END IF;

        EXECUTE format('ALTER TABLE users DROP CONSTRAINT IF EXISTS %I', 'ck_users_grade_' || constraint_suffix);
        EXECUTE format(
            'ALTER TABLE users ADD CONSTRAINT %I CHECK (%I IS NULL OR %I IN (' ||
            '''BASIC'', ''VIP'', ''VVIP'', ''VIP콕'', ''SKT_LITE'', ''SKT_SILVER'', ''SKT_GOLD'', ''SKT_VIP'', ' ||
            '''KT_GENERAL'', ''KT_WHITE'', ''KT_SILVER'', ''KT_GOLD'', ''KT_VIP'', ''KT_VVIP''))',
            'ck_users_grade_' || constraint_suffix, grade_column, grade_column
        );
    END LOOP;
END $$;
