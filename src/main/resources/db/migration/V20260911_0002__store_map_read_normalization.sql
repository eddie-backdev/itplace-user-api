-- 정규화는 매장/제휴사 쓰기 때 한 번 계산한다. 제휴사 연결 판단은 현재 두 행으로 읽는다.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';

-- Java Locale.ROOT 소문자화 후 [^가-힣a-z0-9] 제거와 동일한 검색 문자 집합.
-- İ의 i+combining-dot, K의 k 변환도 포함하며 DB/OS locale에 의존하지 않는다.
CREATE FUNCTION map_normalize_name(value text) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT regexp_replace(
        translate(COALESCE(value, ''), 'ABCDEFGHIJKLMNOPQRSTUVWXYZİK', 'abcdefghijklmnopqrstuvwxyzik') COLLATE "C",
        '[^가-힣a-z0-9]+', '', 'g')
$$;

ALTER TABLE store
    ADD COLUMN mapNormalizedName text GENERATED ALWAYS AS (map_normalize_name(storeName)) STORED,
    ADD COLUMN mapNormalizedBusiness text GENERATED ALWAYS AS (map_normalize_name(business)) STORED;
ALTER TABLE partner
    ADD COLUMN mapNormalizedName text GENERATED ALWAYS AS (map_normalize_name(partnerName)) STORED;

-- 이름/업종은 정규화된 컬럼만 받는다. 정규식/원본 문자열 정규화는 조회에서 실행하지 않는다.
CREATE FUNCTION map_store_partner_matches(store_name text, business_name text, partner_name text)
RETURNS boolean LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT COALESCE(partner_name <> '' AND
        CASE partner_name
            WHEN 'gs25' THEN strpos(store_name, 'gs25') > 0 OR strpos(store_name, '지에스25') > 0
            WHEN '지에스25' THEN strpos(store_name, 'gs25') > 0 OR strpos(store_name, '지에스25') > 0
            WHEN 'cu' THEN strpos(store_name, 'cu') > 0 OR strpos(store_name, '씨유') > 0
            WHEN '씨유' THEN strpos(store_name, 'cu') > 0 OR strpos(store_name, '씨유') > 0
            WHEN '세븐일레븐' THEN strpos(store_name, '세븐일레븐') > 0 OR strpos(store_name, '7eleven') > 0
            WHEN '7eleven' THEN strpos(store_name, '세븐일레븐') > 0 OR strpos(store_name, '7eleven') > 0
            ELSE strpos(store_name, partner_name) > 0
        END AND
        (partner_name NOT IN ('다락', '미니창고다락')
            OR strpos(business_name, '보관') > 0 OR strpos(business_name, '저장') > 0), FALSE)
$$;

ANALYZE store;
ANALYZE partner;
