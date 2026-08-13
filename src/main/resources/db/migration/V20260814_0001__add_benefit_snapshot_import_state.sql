-- 통신사별 마지막 적용 시각을 잠금 행으로 관리해 중복·역순 스냅샷 적용을 방지한다.

CREATE TABLE benefitSnapshotImportState (
    carrier VARCHAR(16) PRIMARY KEY,
    lastAppliedCrawledAt TIMESTAMP
);

INSERT INTO benefitSnapshotImportState (carrier, lastAppliedCrawledAt)
SELECT seed.carrier, MAX(policy.lastCrawledAt)
FROM (VALUES ('SKT'), ('KT'), ('LGU')) AS seed(carrier)
LEFT JOIN benefitCarrierPolicy policy ON policy.carrier = seed.carrier
GROUP BY seed.carrier;
