ALTER TABLE carrierTierBenefit DROP CONSTRAINT IF EXISTS carrierTierBenefit_grade_check;
ALTER TABLE carrierTierBenefit
    ADD CONSTRAINT carrierTierBenefit_grade_check CHECK (grade IN (
        'BASIC', 'VIP', 'VVIP', 'DIAMOND', 'VIP콕',
        'SKT_LITE', 'SKT_SILVER', 'SKT_GOLD', 'SKT_VIP',
        'KT_GENERAL', 'KT_WHITE', 'KT_SILVER', 'KT_GOLD', 'KT_VIP', 'KT_VVIP'
    ));

ALTER TABLE IF EXISTS tierBenefit DROP CONSTRAINT IF EXISTS tierBenefit_grade_check;
ALTER TABLE IF EXISTS tierBenefit
    ADD CONSTRAINT tierBenefit_grade_check CHECK (grade IN (
        'BASIC', 'VIP', 'VVIP', 'DIAMOND', 'VIP콕',
        'SKT_LITE', 'SKT_SILVER', 'SKT_GOLD', 'SKT_VIP',
        'KT_GENERAL', 'KT_WHITE', 'KT_SILVER', 'KT_GOLD', 'KT_VIP', 'KT_VVIP'
    ));
