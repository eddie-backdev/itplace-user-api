-- 이전 브랜드명 기반 목록 키와 상세 페이지 기반 키가 동시에 활성화된 경우만 정리한다.
-- 동일 benefitId에 더 최근의 상세 정책이 확인된 예전 목록 정책을 비활성화한다.
-- 서로 다른 이벤트/채널/혜택은 합치지 않으며, ID/원문/등급 정보는 삭제하지 않는다.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '180s';

UPDATE benefitCarrierPolicy legacy
SET active = false
WHERE legacy.carrier = 'KT'
  AND legacy.active = true
  AND legacy.sourceKey LIKE 'KT:%'
  AND legacy.sourceKey !~ '^KT:c[0-9]+$'
  AND split_part(legacy.sourceUrl, '?', 1) = 'https://membership.kt.com/discount/partner/PartnerList.do'
  AND EXISTS (
      SELECT 1 FROM benefitCarrierPolicy current
      WHERE current.benefitId = legacy.benefitId
        AND current.carrier = legacy.carrier
        AND current.active = true
        AND current.sourceKey ~ '^KT:c[0-9]+$'
        AND current.sourceUrl ~ '^https://membership[.]kt[.]com/discount/partner/[^/]+/[^/]+/PartnerDetail[.]do([?].*)?$'
        AND current.carrierBenefitName = legacy.carrierBenefitName
        AND current.usageType IS NOT DISTINCT FROM legacy.usageType
        AND current.lastCrawledAt > legacy.lastCrawledAt
  );
