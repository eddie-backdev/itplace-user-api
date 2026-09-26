-- 공유 DB의 모든 쓰기 경로에서 변경 기록과 혜택 변경이 함께 commit/rollback된다.
-- FK를 두지 않아 혜택/제휴처 삭제 후에도 이전 캐시를 제거할 수 있다.
CREATE TABLE benefit_cache_refresh (
    partner_id BIGINT PRIMARY KEY,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE FUNCTION queue_benefit_cache_refresh(target_partner_id BIGINT) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    IF target_partner_id IS NOT NULL THEN
        INSERT INTO benefit_cache_refresh(partner_id) VALUES (target_partner_id)
        ON CONFLICT (partner_id) DO UPDATE SET changed_at = clock_timestamp();
    END IF;
END;
$$;

CREATE FUNCTION track_benefit_cache_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    -- 수집 시각/수정 시각만 달라진 재수집은 혜택 변경으로 취급하지 않는다.
    IF TG_OP = 'UPDATE' AND
       (to_jsonb(OLD) - ARRAY['lastcrawledat', 'lastmodifieddate', 'createddate']) =
       (to_jsonb(NEW) - ARRAY['lastcrawledat', 'lastmodifieddate', 'createddate']) THEN
        RETURN NEW;
    END IF;
    IF TG_TABLE_NAME = 'benefit' THEN
        IF TG_OP <> 'INSERT' THEN PERFORM queue_benefit_cache_refresh(OLD.partnerId); END IF;
        IF TG_OP <> 'DELETE' THEN PERFORM queue_benefit_cache_refresh(NEW.partnerId); END IF;
    ELSIF TG_TABLE_NAME = 'benefitcarrierpolicy' THEN
        IF TG_OP <> 'INSERT' THEN
            PERFORM queue_benefit_cache_refresh(partnerId) FROM benefit WHERE benefitId = OLD.benefitId;
        END IF;
        IF TG_OP <> 'DELETE' THEN
            PERFORM queue_benefit_cache_refresh(partnerId) FROM benefit WHERE benefitId = NEW.benefitId;
        END IF;
    ELSE
        IF TG_OP <> 'INSERT' THEN
            PERFORM queue_benefit_cache_refresh(b.partnerId) FROM benefit b
            JOIN benefitCarrierPolicy p ON p.benefitId = b.benefitId
            WHERE p.benefitCarrierPolicyId = OLD.benefitCarrierPolicyId;
        END IF;
        IF TG_OP <> 'DELETE' THEN
            PERFORM queue_benefit_cache_refresh(b.partnerId) FROM benefit b
            JOIN benefitCarrierPolicy p ON p.benefitId = b.benefitId
            WHERE p.benefitCarrierPolicyId = NEW.benefitCarrierPolicyId;
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER benefit_cache_change AFTER INSERT OR UPDATE OR DELETE ON benefit
FOR EACH ROW EXECUTE FUNCTION track_benefit_cache_change();
CREATE TRIGGER benefit_policy_cache_change AFTER INSERT OR UPDATE OR DELETE ON benefitCarrierPolicy
FOR EACH ROW EXECUTE FUNCTION track_benefit_cache_change();
CREATE TRIGGER benefit_tier_cache_change AFTER INSERT OR UPDATE OR DELETE ON carrierTierBenefit
FOR EACH ROW EXECUTE FUNCTION track_benefit_cache_change();

-- 도입 전 존재하던 캐시도 현재 DB 값과 7일 TTL로 교체한다.
INSERT INTO benefit_cache_refresh(partner_id) SELECT partnerId FROM partner;
