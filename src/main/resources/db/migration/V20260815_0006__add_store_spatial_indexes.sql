-- 근방 검색 성능 개선용 인덱스
-- 실행 조건: PostgreSQL + PostGIS 설치 필요

-- 1. 위경도 복합 B-tree 인덱스 (BETWEEN 필터 가속)
CREATE INDEX IF NOT EXISTS idx_store_lat_lng ON store (latitude, longitude);

-- 2. PostGIS GiST 공간 인덱스 (ST_DistanceSphere / ST_DWithin 가속)
CREATE INDEX IF NOT EXISTS idx_store_location ON store USING GIST (location);
