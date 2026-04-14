-- 근방 검색 성능 개선용 인덱스
-- 실행 조건: PostgreSQL + PostGIS 설치 필요

-- 1. 위경도 복합 B-tree 인덱스 (BETWEEN 필터 가속)
--    로컬(ddl-auto=update) 환경에서는 Store 엔티티의 @Index 어노테이션으로 자동 생성됨.
--    prod 환경에서 수동 실행 필요.
CREATE INDEX IF NOT EXISTS idx_store_lat_lng ON store (latitude, longitude);

-- 2. PostGIS GiST 공간 인덱스 (ST_DistanceSphere / ST_DWithin 가속)
--    JPA @Index로 생성 불가 (GiST 타입). 모든 환경에서 수동 실행 필요.
CREATE INDEX IF NOT EXISTS idx_store_location ON store USING GIST (location);
