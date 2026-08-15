-- 기존 수동 SQL과 local 전용 초기화 코드로 관리하던 사용자/혜택 스키마를
-- Flyway 이력으로 편입한다. 운영 DB에 이미 일부 객체가 있어도 재조정할 수 있게
-- 모든 구조 변경과 백필은 멱등하게 작성한다.

SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '180s';

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS nickname VARCHAR(30),
    ADD COLUMN IF NOT EXISTS carrier VARCHAR(10),
    ADD COLUMN IF NOT EXISTS "membershipGradeCode" VARCHAR(30),
    ADD COLUMN IF NOT EXISTS "membershipVerified" BOOLEAN NOT NULL DEFAULT FALSE;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'users'
          AND column_name = 'name'
    ) THEN
        EXECUTE 'UPDATE users SET nickname = name WHERE nickname IS NULL AND name IS NOT NULL';
    END IF;
END
$$;

CREATE TABLE IF NOT EXISTS authCredential (
    authCredentialId BIGSERIAL PRIMARY KEY,
    userId BIGINT NOT NULL REFERENCES users(userId) ON DELETE CASCADE,
    type VARCHAR(30) NOT NULL,
    provider VARCHAR(30),
    providerUserId VARCHAR(255),
    passwordHash VARCHAR(255),
    createdDate TIMESTAMP,
    lastModifiedDate TIMESTAMP
);

ALTER TABLE authCredential
    ADD COLUMN IF NOT EXISTS provider VARCHAR(30),
    ADD COLUMN IF NOT EXISTS providerUserId VARCHAR(255),
    ADD COLUMN IF NOT EXISTS passwordHash VARCHAR(255),
    ADD COLUMN IF NOT EXISTS createdDate TIMESTAMP,
    ADD COLUMN IF NOT EXISTS lastModifiedDate TIMESTAMP;

CREATE UNIQUE INDEX IF NOT EXISTS uq_auth_credential_local_password
    ON authCredential (userId, type)
    WHERE type = 'LOCAL_PASSWORD';

CREATE UNIQUE INDEX IF NOT EXISTS uq_auth_credential_oauth_provider
    ON authCredential (provider, providerUserId)
    WHERE type = 'OAUTH';

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'users'
          AND column_name = 'password'
    ) THEN
        EXECUTE $backfill$
            INSERT INTO authCredential (userId, type, passwordHash, createdDate, lastModifiedDate)
            SELECT u.userId, 'LOCAL_PASSWORD', u.password, NOW(), NOW()
            FROM users u
            WHERE u.password IS NOT NULL
              AND NOT EXISTS (
                  SELECT 1
                  FROM authCredential c
                  WHERE c.userId = u.userId
                    AND c.type = 'LOCAL_PASSWORD'
              )
        $backfill$;
    END IF;

    IF to_regclass('public.socialaccount') IS NOT NULL THEN
        EXECUTE $backfill$
            INSERT INTO authCredential (userId, type, provider, providerUserId, createdDate, lastModifiedDate)
            SELECT s.userId, 'OAUTH', s.provider, s.providerId, NOW(), NOW()
            FROM socialAccount s
            WHERE NOT EXISTS (
                SELECT 1
                FROM authCredential c
                WHERE c.type = 'OAUTH'
                  AND c.provider = s.provider
                  AND c.providerUserId = s.providerId
            )
        $backfill$;
    END IF;
END
$$;

ALTER TABLE benefit
    ADD COLUMN IF NOT EXISTS canonicalkey VARCHAR(512);

CREATE TABLE IF NOT EXISTS benefitCarrierPolicy (
    benefitCarrierPolicyId BIGSERIAL PRIMARY KEY,
    benefitId BIGINT NOT NULL REFERENCES benefit(benefitId),
    carrier VARCHAR(10) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    sourceKey VARCHAR(512),
    sourceUrl VARCHAR(512),
    sourceCategory VARCHAR(100),
    lastCrawledAt TIMESTAMP,
    benefitLimit BIGINT REFERENCES benefitPolicy(benefitPolicyId),
    carrierBenefitName VARCHAR(512),
    type VARCHAR(50),
    description TEXT,
    manual TEXT,
    usageType VARCHAR(50),
    url VARCHAR(512),
    createdDate TIMESTAMP,
    lastModifiedDate TIMESTAMP
);

ALTER TABLE benefitCarrierPolicy
    ADD COLUMN IF NOT EXISTS benefitCarrierPolicyId BIGSERIAL,
    ADD COLUMN IF NOT EXISTS benefitId BIGINT,
    ADD COLUMN IF NOT EXISTS carrier VARCHAR(10),
    ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS sourceKey VARCHAR(512),
    ADD COLUMN IF NOT EXISTS sourceUrl VARCHAR(512),
    ADD COLUMN IF NOT EXISTS sourceCategory VARCHAR(100),
    ADD COLUMN IF NOT EXISTS lastCrawledAt TIMESTAMP,
    ADD COLUMN IF NOT EXISTS benefitLimit BIGINT,
    ADD COLUMN IF NOT EXISTS carrierBenefitName VARCHAR(512),
    ADD COLUMN IF NOT EXISTS type VARCHAR(50),
    ADD COLUMN IF NOT EXISTS description TEXT,
    ADD COLUMN IF NOT EXISTS manual TEXT,
    ADD COLUMN IF NOT EXISTS usageType VARCHAR(50),
    ADD COLUMN IF NOT EXISTS url VARCHAR(512),
    ADD COLUMN IF NOT EXISTS createdDate TIMESTAMP,
    ADD COLUMN IF NOT EXISTS lastModifiedDate TIMESTAMP;

CREATE UNIQUE INDEX IF NOT EXISTS uq_benefit_carrier_policy_id
    ON benefitCarrierPolicy (benefitCarrierPolicyId);

CREATE UNIQUE INDEX IF NOT EXISTS uq_benefit_carrier_policy_source
    ON benefitCarrierPolicy (carrier, sourceKey)
    WHERE sourceKey IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_benefit_carrier_policy_benefit_carrier
    ON benefitCarrierPolicy (benefitId, carrier);

CREATE TABLE IF NOT EXISTS carrierTierBenefit (
    carrierTierBenefitId BIGSERIAL PRIMARY KEY,
    benefitCarrierPolicyId BIGINT NOT NULL
        REFERENCES benefitCarrierPolicy(benefitCarrierPolicyId) ON DELETE CASCADE,
    grade VARCHAR(30) NOT NULL,
    context TEXT NOT NULL,
    isAll BOOLEAN,
    discountValue INTEGER
);

ALTER TABLE carrierTierBenefit
    ADD COLUMN IF NOT EXISTS carrierTierBenefitId BIGSERIAL,
    ADD COLUMN IF NOT EXISTS benefitCarrierPolicyId BIGINT,
    ADD COLUMN IF NOT EXISTS grade VARCHAR(30),
    ADD COLUMN IF NOT EXISTS context TEXT,
    ADD COLUMN IF NOT EXISTS isAll BOOLEAN,
    ADD COLUMN IF NOT EXISTS discountValue INTEGER;

CREATE INDEX IF NOT EXISTS idx_carrier_tier_benefit_policy
    ON carrierTierBenefit (benefitCarrierPolicyId, grade);

UPDATE benefit
SET canonicalkey = LOWER(REGEXP_REPLACE(
        COALESCE(partnerId::TEXT, '') || ':' || COALESCE(benefitName, ''),
        '[^가-힣a-zA-Z0-9]+',
        '',
        'g'
    ))
WHERE canonicalkey IS NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'benefit'
          AND column_name = 'carrier'
    ) THEN
        ALTER TABLE benefit
            ADD COLUMN IF NOT EXISTS sourcekey VARCHAR(512),
            ADD COLUMN IF NOT EXISTS sourceurl VARCHAR(512),
            ADD COLUMN IF NOT EXISTS sourcecategory VARCHAR(100),
            ADD COLUMN IF NOT EXISTS lastcrawledat TIMESTAMP,
            ADD COLUMN IF NOT EXISTS benefitlimit BIGINT,
            ADD COLUMN IF NOT EXISTS usagetype VARCHAR(50);

        IF EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = 'benefit'
              AND column_name = 'sourceKey'
        ) THEN
            EXECUTE 'UPDATE benefit SET sourcekey = COALESCE(sourcekey, "sourceKey")';
        END IF;
        IF EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = 'benefit'
              AND column_name = 'sourceUrl'
        ) THEN
            EXECUTE 'UPDATE benefit SET sourceurl = COALESCE(sourceurl, "sourceUrl")';
        END IF;
        IF EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = 'benefit'
              AND column_name = 'sourceCategory'
        ) THEN
            EXECUTE 'UPDATE benefit SET sourcecategory = COALESCE(sourcecategory, "sourceCategory")';
        END IF;
        IF EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = 'benefit'
              AND column_name = 'lastCrawledAt'
        ) THEN
            EXECUTE 'UPDATE benefit SET lastcrawledat = COALESCE(lastcrawledat, "lastCrawledAt")';
        END IF;
        IF EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = 'benefit'
              AND column_name = 'benefitLimit'
        ) THEN
            EXECUTE 'UPDATE benefit SET benefitlimit = COALESCE(benefitlimit, "benefitLimit")';
        END IF;
        IF EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = 'benefit'
              AND column_name = 'usageType'
        ) THEN
            EXECUTE 'UPDATE benefit SET usagetype = COALESCE(usagetype, "usageType")';
        END IF;
        IF EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = 'benefit'
              AND column_name = 'canonicalKey'
        ) THEN
            EXECUTE 'UPDATE benefit SET canonicalkey = COALESCE(canonicalkey, "canonicalKey")';
        END IF;

        UPDATE benefitCarrierPolicy p
        SET sourceKey = COALESCE(p.sourceKey, b.sourcekey),
            sourceUrl = COALESCE(p.sourceUrl, b.sourceurl),
            sourceCategory = COALESCE(p.sourceCategory, b.sourcecategory),
            lastCrawledAt = COALESCE(p.lastCrawledAt, b.lastcrawledat),
            benefitLimit = COALESCE(p.benefitLimit, b.benefitlimit),
            carrierBenefitName = COALESCE(p.carrierBenefitName, b.benefitName),
            type = COALESCE(p.type, b.type),
            description = COALESCE(p.description, b.description),
            manual = COALESCE(p.manual, b.manual),
            usageType = COALESCE(p.usageType, b.usagetype),
            url = COALESCE(p.url, b.url)
        FROM benefit b
        WHERE p.benefitId = b.benefitId
          AND p.carrier = b.carrier;

        INSERT INTO benefitCarrierPolicy (
            benefitId,
            carrier,
            active,
            sourceKey,
            sourceUrl,
            sourceCategory,
            lastCrawledAt,
            benefitLimit,
            carrierBenefitName,
            type,
            description,
            manual,
            usageType,
            url,
            createdDate,
            lastModifiedDate
        )
        SELECT
            b.benefitId,
            b.carrier,
            COALESCE(b.active, TRUE),
            b.sourcekey,
            b.sourceurl,
            b.sourcecategory,
            b.lastcrawledat,
            b.benefitlimit,
            b.benefitName,
            b.type,
            b.description,
            b.manual,
            b.usagetype,
            b.url,
            b.createdDate,
            b.lastModifiedDate
        FROM benefit b
        WHERE b.carrier IS NOT NULL
          AND NOT EXISTS (
              SELECT 1
              FROM benefitCarrierPolicy p
              WHERE p.benefitId = b.benefitId
                AND p.carrier = b.carrier
                AND COALESCE(p.sourceKey, '') = COALESCE(b.sourcekey, '')
          );

        UPDATE benefitCarrierPolicy p
        SET sourceKey = COALESCE(p.sourceKey, b.sourcekey),
            sourceUrl = COALESCE(p.sourceUrl, b.sourceurl),
            sourceCategory = COALESCE(p.sourceCategory, b.sourcecategory),
            lastCrawledAt = COALESCE(p.lastCrawledAt, b.lastcrawledat),
            benefitLimit = COALESCE(p.benefitLimit, b.benefitlimit),
            carrierBenefitName = COALESCE(p.carrierBenefitName, b.benefitName),
            type = COALESCE(p.type, b.type),
            description = COALESCE(p.description, b.description),
            manual = COALESCE(p.manual, b.manual),
            usageType = COALESCE(p.usageType, b.usagetype),
            url = COALESCE(p.url, b.url)
        FROM benefit b
        WHERE p.benefitId = b.benefitId
          AND p.carrier = b.carrier;

        IF to_regclass('public.tierbenefit') IS NOT NULL THEN
            INSERT INTO carrierTierBenefit (
                benefitCarrierPolicyId,
                grade,
                context,
                isAll,
                discountValue
            )
            SELECT
                p.benefitCarrierPolicyId,
                tb.grade,
                tb.context,
                tb.isAll,
                tb.discountValue
            FROM tierBenefit tb
            JOIN benefit b ON b.benefitId = tb.benefitId
            JOIN benefitCarrierPolicy p
              ON p.benefitId = b.benefitId
             AND p.carrier = b.carrier
             AND COALESCE(p.sourceKey, '') = COALESCE(b.sourcekey, '')
            WHERE NOT EXISTS (
                SELECT 1
                FROM carrierTierBenefit ctb
                WHERE ctb.benefitCarrierPolicyId = p.benefitCarrierPolicyId
                  AND ctb.grade = tb.grade
                  AND ctb.context = tb.context
            );
        END IF;
    END IF;
END
$$;

DROP INDEX IF EXISTS uq_benefit_carrier_source_key;
DROP INDEX IF EXISTS idx_benefit_active_carrier;

ALTER TABLE benefit
    DROP COLUMN IF EXISTS carrier,
    DROP COLUMN IF EXISTS type,
    DROP COLUMN IF EXISTS description,
    DROP COLUMN IF EXISTS manual,
    DROP COLUMN IF EXISTS usagetype,
    DROP COLUMN IF EXISTS "usageType",
    DROP COLUMN IF EXISTS url,
    DROP COLUMN IF EXISTS sourcekey,
    DROP COLUMN IF EXISTS "sourceKey",
    DROP COLUMN IF EXISTS sourceurl,
    DROP COLUMN IF EXISTS "sourceUrl",
    DROP COLUMN IF EXISTS sourcecategory,
    DROP COLUMN IF EXISTS "sourceCategory",
    DROP COLUMN IF EXISTS lastcrawledat,
    DROP COLUMN IF EXISTS "lastCrawledAt",
    DROP COLUMN IF EXISTS benefitlimit,
    DROP COLUMN IF EXISTS "benefitLimit",
    DROP COLUMN IF EXISTS "canonicalKey";

CREATE INDEX IF NOT EXISTS idx_benefit_active_main_category
    ON benefit (active, maincategory);

CREATE INDEX IF NOT EXISTS idx_benefit_canonicalkey
    ON benefit (canonicalkey);

CREATE TABLE IF NOT EXISTS inquiries (
    id BIGSERIAL PRIMARY KEY,
    category VARCHAR(50) NOT NULL,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'UNREAD',
    createdDate TIMESTAMP NOT NULL,
    lastModifiedDate TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_inquiries_status_created
    ON inquiries (status, createdDate DESC);
