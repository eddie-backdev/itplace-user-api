-- 지도 화면 이동마다 원본 매장을 다시 훑지 않고 행정구역 사전 집계와 고정 앵커만 조회한다.
CREATE INDEX IF NOT EXISTS idx_map_region_anchor_viewport
    ON map_region_anchor (latitude, longitude)
    INCLUDE (region_type, region_key, region_name);

CREATE INDEX IF NOT EXISTS idx_map_region_store_summary_cluster_lookup
    ON map_region_store_summary (aggregation_unit, category, region_type, region_key)
    INCLUDE (region_hash, region_name, store_count);

ANALYZE map_region_anchor;
ANALYZE map_region_store_summary;
