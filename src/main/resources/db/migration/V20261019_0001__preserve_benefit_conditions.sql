ALTER TABLE benefitCarrierPolicy
    ADD COLUMN IF NOT EXISTS sourceEvidenceId BIGINT,
    ADD COLUMN IF NOT EXISTS sourceCapturedAt TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS sourceLimitText TEXT,
    ADD COLUMN IF NOT EXISTS conditions JSONB;

ALTER TABLE benefitPolicy
    DROP CONSTRAINT IF EXISTS benefitpolicy_code_check;

ALTER TABLE benefitPolicy
    ADD CONSTRAINT benefitpolicy_code_check
    CHECK (code IN ('MONTHLY_ONCE', 'DAILY_ONCE', 'UNLIMITED', 'ONCE', 'UNKNOWN'));

DO $$
DECLARE
    policy_sequence REGCLASS;
    sequence_value BIGINT;
    highest_policy_id BIGINT;
BEGIN
    policy_sequence := pg_get_serial_sequence('public.benefitpolicy', 'benefitpolicyid')::REGCLASS;
    IF policy_sequence IS NOT NULL THEN
        EXECUTE format('SELECT last_value FROM %s', policy_sequence) INTO sequence_value;
        SELECT COALESCE(MAX(benefitpolicyid), 0) INTO highest_policy_id FROM benefitPolicy;
        PERFORM setval(policy_sequence, GREATEST(sequence_value, highest_policy_id), TRUE);
    END IF;
END
$$;

INSERT INTO benefitPolicy (code, name)
SELECT 'UNKNOWN', '이용 조건 확인 필요'
WHERE NOT EXISTS (SELECT 1 FROM benefitPolicy WHERE code = 'UNKNOWN');
