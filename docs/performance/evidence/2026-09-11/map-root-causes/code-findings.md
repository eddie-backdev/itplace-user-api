# 현재 웹 지도 API의 남은 구조적 비용

조사 기준: user-api 현재 HEAD `30d3ada`, 실행 코드 `df4ff46`. 파일과 이미 보존한 최종 실행계획만 읽었다. 추가 벤치마크·DB 조회·코드 수정은 하지 않았다. 이미 해결한 넓은 UNION temp spill과 keyword generic-plan 고착은 현재 미해결 원인에서 제외한다.

핵심은 **혜택 캐시 hit가 매장 검색을 생략해 주지 않는다는 것**이다. 웹 상세 viewport는 여전히 요청마다 매장 DB를 조회한다. 키워드는 ES 검색이 성공해도 전국 DB 검색을 추가하고, 합쳐진 ID로 DB를 다시 읽는다. 느리게 바뀌는 매장/제휴사/혜택 정보를 각 요청에서 읽고 합치는 비용이 남아 있다.

## 1. 정상 cache-hit 요청의 논리적 왕복

다음 표는 정상·비어 있지 않은 결과, Redis generation marker와 모든 요청 제휴사 혜택이 존재하는 경우다. SQL statement/외부 호출 수이며 JDBC BEGIN/COMMIT, 연결 검증, 프로토콜 메시지와 retry 횟수까지 센 물리적 패킷 수는 아니다.

| 웹 경로 | ES | DB | Redis | 후보/응답 상한 |
|---|---|---|---|---|
| viewport compact | 없음 | SELECT 1회, 짧은 read-only transaction 1개 | MGET 1회 | 프론트 기본 요청 300, API 허용 최대 2,000 |
| nearby/category compact | 없음 | 반경 후보 ID SELECT 1회 → 최종 Store+Partner SELECT 1회, 별도 transaction 2개 | MGET 1회 | DB ID 후보 900 → Java에서 최대 300개 선택 → 최종 응답 최대 300 |
| keyword compact, ES 성공 | `_msearch` HTTP 1회 안에 검색 2개 | SET LOCAL 1회 + DB keyword SELECT 1회, 이후 Store+Partner SELECT 1회. DB SELECT는 2회/transaction은 2개 | MGET 1회 | ES 브랜드 최대 100 + 이름/업종 최대 200 + DB 최대 30, 중복 제거 전 합계 330 |
| keyword compact, ES empty/실패 | ES 호출 또는 실패 시도 1회 | 동일 keyword DB 단계 후, ID가 있으면 최종 Store+Partner SELECT | 결과 제휴사가 있으면 MGET 1회 | DB fallback 후보 최대 30 |
| 지역 cluster, 유효 snapshot hit | 없음 | 없음 | 요청별 조회 없음 | 행정구역 snapshot 선택; 위 상세 매장 조회와 다른 경로 |

근거:

- [viewport DB→혜택 순서](/Users/eddie/dev/ITPLACE/itplace-user-api/src/main/java/com/itplace/userapi/map/service/StoreServiceImpl.java:247)
- [nearby 후보→샘플→최종 로드](/Users/eddie/dev/ITPLACE/itplace-user-api/src/main/java/com/itplace/userapi/map/service/StoreServiceImpl.java:310)
- [keyword ES→DB 보충→ID 합집합→최종 로드](/Users/eddie/dev/ITPLACE/itplace-user-api/src/main/java/com/itplace/userapi/map/service/StoreServiceImpl.java:644)
- [ES 100/200 상한과 실제 msearch](/Users/eddie/dev/ITPLACE/itplace-user-api/src/main/java/com/itplace/userapi/map/service/StoreSearchServiceImpl.java:44)
- [keyword 전용 SET LOCAL](/Users/eddie/dev/ITPLACE/itplace-user-api/src/main/java/com/itplace/userapi/map/service/StorePreviewQueryService.java:18)
- [최대 300 샘플링](/Users/eddie/dev/ITPLACE/itplace-user-api/src/main/java/com/itplace/userapi/map/service/StoreServiceImpl.java:510)
- [유효 snapshot의 직접 반환](/Users/eddie/dev/ITPLACE/itplace-user-api/src/main/java/com/itplace/userapi/map/service/MapClusterSnapshotCache.java:130)

**현재 keyword compact에 최종 500개 제한은 없다.** ES 응답 자체가 두 검색 합계 최대 300개이고 DB 30개를 합치므로 최대 330개다. `findKeywordStores`는 마지막에 브랜드/이름 그룹을 합쳐 `toList()`를 반환하고, `toPreviewBatch`에도 별도 300/500개 절단이 없다. 실제 수는 중복·적격성·검색 결과에 따라 더 작다. 반면 nearby의 `FINAL_LIMIT=300`과 후보 배수 3은 그대로 있다. [상수](/Users/eddie/dev/ITPLACE/itplace-user-api/src/main/java/com/itplace/userapi/map/service/StoreServiceImpl.java:56), [keyword 최종 결합](/Users/eddie/dev/ITPLACE/itplace-user-api/src/main/java/com/itplace/userapi/map/service/StoreServiceImpl.java:695).

DB 커넥션을 ES/Redis 처리 내내 잡고 있는 과거 구조는 이미 수정됐다. 현재 서비스는 NOT_SUPPORTED이며 repository/query service의 짧은 transaction에서만 DB 연결을 점유한다. 다만 keyword/nearby는 별도 최종 로드 때문에 DB 연결 획득을 두 번 기다릴 수 있다. [서비스 경계](/Users/eddie/dev/ITPLACE/itplace-user-api/src/main/java/com/itplace/userapi/map/service/StoreServiceImpl.java:40).

## 2. 혜택 cache miss는 무엇을 더 하는가

운영 RedisCache의 정상 hit는 generation marker와 모든 partner key를 한 번의 MGET으로 읽는다. 제휴사마다 개별 GET을 하는 구조가 아니다. [MGET 구현](/Users/eddie/dev/ITPLACE/itplace-user-api/src/main/java/com/itplace/userapi/map/service/PartnerBenefitCacheService.java:134).

- 일부 제휴사가 miss이고 해당 요청이 load를 소유하면, source DB의 별도 REQUIRES_NEW transaction에서 혜택 → 정책 → 등급 혜택을 **최대 3개 batch SELECT**로 읽고, Redis Lua EVAL 1회로 묶어서 저장한다. 앞 단계가 비어 있으면 뒤 SELECT는 생략된다.
- 다른 요청이 이미 같은 generation/partner를 load 중이면 해당 JVM의 future를 기다린다. 이 경우 기다리는 요청의 추가 DB load는 없다.
- generation marker도 없으면 최초 MGET 뒤 SETNX와 MGET이 추가된다. 즉 이 초기화 경우에는 Redis 읽기·초기화가 3개 command가 된다.
- cache miss와 in-flight 대기는 cache-hit 평균과 별도로 다뤄야 한다. in-flight registry는 완료 후 제거되는 최대 1,024개 load 조정 구조이며, 완성된 혜택 객체를 계속 재사용하는 L1은 아니다.

[miss 소유/대기](/Users/eddie/dev/ITPLACE/itplace-user-api/src/main/java/com/itplace/userapi/map/service/PartnerBenefitCacheService.java:74), [source transaction](/Users/eddie/dev/ITPLACE/itplace-user-api/src/main/java/com/itplace/userapi/map/service/PartnerBenefitCacheService.java:161), [3개 batch SELECT](/Users/eddie/dev/ITPLACE/itplace-user-api/src/main/java/com/itplace/userapi/map/service/PartnerBenefitCacheService.java:257), [Redis fill EVAL](/Users/eddie/dev/ITPLACE/itplace-user-api/src/main/java/com/itplace/userapi/map/service/PartnerBenefitCacheService.java:176).

## 3. 가장 큰 구조적 문제: 전국 키워드 후보를 만든 다음 거리순으로 자른다

### ES는 사용자 위치를 후보 선정에 쓰지 않는다

`searchByKeyword` 시그니처는 keyword/category뿐이다. `_msearch`의 브랜드 검색은 `match(partnerName)`, 다른 검색은 `multi_match(storeName,business)`이며 선택적으로 category term만 붙인다. 좌표·반경·viewport·geo sort는 없다. 따라서 전국 텍스트 순위 상위 100/200개가 가까운 매장을 보장하지 않는다. 이 한계를 보완하기 위해 서비스가 정상 ES 응답에도 DB keyword 검색을 실행한다.

더 근본적으로 현재 user-api의 [StoreDocument](/Users/eddie/dev/ITPLACE/itplace-user-api/src/main/java/com/itplace/userapi/ai/rag/document/StoreDocument.java:14)와 [store 인덱스 생성 매핑](/Users/eddie/dev/ITPLACE/itplace-user-api/src/main/java/com/itplace/userapi/ai/rag/service/ElasticServiceImpl.java:131)에는 좌표/geo_point가 없다. 매핑은 ID·매장명·업종·제휴사명·카테고리·city·town뿐이다. 이 코드가 만든 문서만으로 ES 지리 검색을 완결할 수 없다.

또한 user-api의 기본 [StoreIndexer](/Users/eddie/dev/ITPLACE/itplace-user-api/src/main/java/com/itplace/userapi/ai/rag/index/StoreIndexer.java:23)는 seed=false이고, seed를 켜도 기존 ID가 있으면 쓰기를 건너뛴다. 이 파일은 실시간 변경 동기화 경로가 아니다. 다만 다른 서비스/운영 작업이 ES를 쓰거나 추가 매핑을 관리할 수 있으므로, 이번 user-api 코드 조사만으로 운영 ES가 오래됐다고 단정하지 않는다.

### DB도 공간 범위로 먼저 줄이지 않는다

현재 [웹 keyword SQL](/Users/eddie/dev/ITPLACE/itplace-user-api/src/main/java/com/itplace/userapi/map/repository/StoreRepository.java:494)에는 `ST_DWithin`, bbox 조건 또는 공간 KNN `<->`가 없다. 좌표는 마지막 `ORDER BY ST_DistanceSphere(...)`에만 쓰인다. 즉 검색어에 맞는 전국 후보를 읽고 적격성을 확인한 뒤 거리 우선순위를 계산해 30개를 반환한다.

이는 **공간 인덱스가 없다는 뜻이 아니다.** 저장된 인덱스 목록에는 geometry GiST `idx_store_location`, geography expression GiST `idx_store_location_geo`, 좌표 B-tree 및 partner 인덱스가 있다. 기존 radius 실행계획은 `idx_store_location_geo`를 사용한다. 현 keyword 계획은 이름/업종 GIN을 실제 사용한다. 문제는 keyword의 현재 조건에 공간 후보 축소 연산이 없다는 것이다.

최종 수정 SQL의 보존된 `force_custom_plan` 실행계획은 다음과 같다. 추가 실행한 수치가 아니다.

| 검색어 | 실제 후보 처리 | 최종 SQL 실행 중앙값 |
|---|---|---:|
| 편의점 | 이름/업종 GIN 사용. business index에서 36,892행, name index에서 20행. 분기 합계 36,898 후보 → 적격성 통과 29,709행 → top-N 30개 | 79.064ms |
| 다 | `idx_store_last_seen_run`의 partner/active 조건에서 73,083행을 후보로 가져와 짧은 문자 조건을 검사. 병렬 bitmap heap scan의 반환은 약 403.33행 × 3 loops, 적격성 통과 약 266행 × 3 loops → 최종 30개 | 34.193ms |
| 스타벅스 | 이름/업종 GIN 사용. 1,675 후보가 적격성 통과 후 거리 정렬 대상 → 최종 30개 | 9.878ms |

`편의점`의 GIN index scan 자체는 약 1ms였고, 해당 bitmap heap scan은 약 45.849ms였다. 인덱스 이름을 하나 더 추가한다고 3만 개 지점의 본문 확인·적격성·거리 계산이 없어지는 것은 아니다. `다`는 짧은 검색어라 기존 partner/active 인덱스로 가져온 넓은 집합에 텍스트 조건을 적용하는 계획이다. 이를 “인덱스를 전혀 타지 않는다”라고 설명하면 틀린다.

위 숫자는 PostgreSQL 단독 SQL 실행 시간이며 HTTP 평균 711ms의 구성 비율을 뜻하지 않는다. 근거 파일: `output/web-preview-regression-2026-09-11/eligibility-final-keyword-explains.json`의 `corrected_plans`, 인덱스 확인은 `output/map-latency-audit-2026-09-11/indexes.json`과 기존 `radius-1000.json`이다. 인덱스 스냅샷은 당시 로컬 DB 기준이며 현재 운영 DB 상태를 대신하지 않는다.

## 4. 후보를 고른 뒤 전체 ORM 엔티티를 다시 구성한다

nearby는 ID 후보 쿼리에서 이미 `active`, 오프라인 혜택, 매장·제휴사 일치 조건을 검사한다. 최종 [findEligibleByStoreIdInWithPartner](/Users/eddie/dev/ITPLACE/itplace-user-api/src/main/java/com/itplace/userapi/map/repository/StoreRepository.java:551)에서 같은 의미의 `active`, `map_store_partner_matches`, 혜택 EXISTS를 다시 확인하면서 Store+Partner 엔티티를 fetch join한다. keyword도 DB 후보와 ES 후보 합집합에 이 최종 로드를 수행한다.

이 재검증은 오래된 ES ID와 두 DB 조회 사이의 변경을 막는 이유가 있으므로, 조건만 지우면 동등한 최적화가 아니다. 다만 후보 선정과 응답용 scalar 로드를 하나의 읽기 쿼리/일관된 읽기 데이터로 합치면 별도 DB 왕복과 ORM 조립을 줄일 수 있다.

현재 [Store 엔티티](/Users/eddie/dev/ITPLACE/itplace-user-api/src/main/java/com/itplace/userapi/map/entity/Store.java:32)는 FK를 포함해 24개 매핑 필드를 갖고, [Partner](/Users/eddie/dev/ITPLACE/itplace-user-api/src/main/java/com/itplace/userapi/partner/entity/Partner.java:15)는 5개다. JOIN FETCH는 요청별 store identity/partner identity를 해석하고 JTS Point, BigDecimal 좌표, 정규화 문자열, 수집 이력 등의 필드까지 엔티티로 구성한다. 지도 compact 응답에는 수집 시각·run ID·miss count·source place ID·원본 수치 좌표 등 여러 필드가 쓰이지 않는다. 정확한 hydrate CPU 비중은 이 최종 버전에서 프로파일링하지 않았으므로 수치로 단정하지 않는다.

반대로 viewport는 이미 14개 scalar projection과 불변 snapshot으로 매핑한다. 이를 현재 ORM 인터페이스 프록시 병목이라고 다시 설명하면 안 된다. [viewport scalar 매핑](/Users/eddie/dev/ITPLACE/itplace-user-api/src/main/java/com/itplace/userapi/map/service/StorePreviewQueryService.java:42).

## 5. 생성 컬럼은 적격 여부를 저장한 것이 아니다

[생성 컬럼 migration](/Users/eddie/dev/ITPLACE/itplace-user-api/src/main/resources/db/migration/V20260911_0002__store_map_read_normalization.sql:14)은 정규화된 매장명·업종·제휴사명 문자열만 저장한다. `eligible=true`나 해당 제휴사가 현재 오프라인 혜택을 가지는지는 저장하지 않는다.

따라서 현재 각 DB 후보 조회에서 다음 계산이 남는다.

- benefit/benefitCarrierPolicy로 현재 오프라인 혜택이 있는 제휴사 집합을 생성한다. 이 부분은 이전처럼 매장마다 정책을 재조회하지 않고 요청 쿼리 안에서 한 번 계산하지만, 요청 사이에 결과를 공유하지는 않는다. 최종 keyword 계획에서 정책 685행과 혜택 711행을 읽어 제휴사 345개를 만드는 aggregate가 약 0.35ms였다. 정적 반복은 맞지만 이것만을 큰 병목으로 과장하면 안 된다.
- 각 후보의 정규화 문자열로 alias CASE와 `strpos`를 실행한다. 이 함수는 이미 인라인되어 있고 요청 시 정규식은 실행하지 않는다. [함수](/Users/eddie/dev/ITPLACE/itplace-user-api/src/main/resources/db/migration/V20260911_0002__store_map_read_normalization.sql:21).
- nearby/keyword 최종 ID fetch에서 적격성과 오프라인 혜택 존재를 다시 확인한다. EXISTS가 있다는 이유만으로 per-store SQL N+1이라고 말할 수는 없다. 현재 한 번의 fetch-join SQL이며 그 내부 계획의 별도 CPU/loops는 추가 측정하지 않았다.

## 6. Redis hit 뒤에도 느리게 바뀌는 혜택을 요청마다 해석한다

혜택 캐시에 저장된 것은 완성된 공유 JVM 객체나 이미 직렬화된 최종 HTTP 응답이 아니라 `List<BenefitCacheDto>`의 JSON이다. normal hit마다 Redis bytes를 받아 요청 스레드에서 역직렬화한다. [역직렬화](/Users/eddie/dev/ITPLACE/itplace-user-api/src/main/java/com/itplace/userapi/map/service/PartnerBenefitCacheService.java:145).

이후 [preparePreviewBenefits](/Users/eddie/dev/ITPLACE/itplace-user-api/src/main/java/com/itplace/userapi/map/service/StoreServiceImpl.java:375)가 요청에 포함된 제휴사마다 오프라인 우선 선택, 매장별 override 분류, 등급 혜택 중복 제거를 수행한다. `toDistinctTierBenefits`는 carrier/grade/context 문자열 key로 LinkedHashMap을 만든다. [중복 제거](/Users/eddie/dev/ITPLACE/itplace-user-api/src/main/java/com/itplace/userapi/map/service/StoreServiceImpl.java:1106).

이전의 “매장마다 같은 혜택을 다시 준비·전송”하는 비용은 상당 부분 이미 없앴다. **현재는 한 요청 안에서 제휴사당 한 번**이고 응답도 partners에서 공통으로 보낸다. 남은 반복은 여러 요청이 같은 제휴사의 동일 세대 혜택을 다시 JSON 해석하고 파생하는 비용이다. 혜택 generation에 연결된 파생 읽기 데이터/객체 재사용이 가능한 부분이지만, 실제 기여도·메모리·다중 인스턴스 무효화는 별도 검증 대상이다.

keyword ES 성공 경로에는 작은 중복 CPU도 남아 있다. 후보 거리 Map을 만들어 정렬한 뒤 `toPreviewBatch`에서 같은 사용자 좌표 거리 계산을 다시 한다. strict 브랜드 우선순위 검사도 각 ID마다 동일 keyword와 partnerName을 다시 정규화한다. [첫 거리 계산](/Users/eddie/dev/ITPLACE/itplace-user-api/src/main/java/com/itplace/userapi/map/service/StoreServiceImpl.java:673), [두 번째 거리 계산](/Users/eddie/dev/ITPLACE/itplace-user-api/src/main/java/com/itplace/userapi/map/service/StoreServiceImpl.java:349), [strict 정규화](/Users/eddie/dev/ITPLACE/itplace-user-api/src/main/java/com/itplace/userapi/map/service/StoreServiceImpl.java:735). 이는 전국 후보 SQL보다 작은 개선 후보이며 현재 성능의 주원인으로 단정하지 않는다. DB 거리는 검색 중심 lat/lng, Java 거리는 사용자 userLat/userLng와 0 좌표 계약을 쓰므로 서로 무조건 합칠 수는 없다.

## 우선순위와 검증 한계

1. **위치를 포함한 검색 후보 선정의 책임을 정리하는 것이 가장 크다.** 현재 ES는 전국 텍스트 후보를 주고 DB가 전국에서 다시 보충한다. 위치를 포함한 최신 검색용 읽기 데이터로 한 단계에서 올바른 후보를 고를 수 있어야 두 시스템의 정상 중복 검색을 줄일 수 있다. ES geo 필터만 급히 추가하거나 DB 보충만 제거하면 가까운 매장 누락/정렬 계약을 깨뜨릴 수 있다.
2. **nearby/keyword의 최종 응답 로드를 scalar/전용 읽기 데이터로 만들 여지가 있다.** 별도 전체 ORM 로드 및 중복 적격성 계산을 줄이되 오래된 ES 후보·변경 중 정합성은 보존해야 한다. 기존 무작위 샘플링 정책과 과부하 제어는 보류 상태다.
3. **혜택의 세대별 파생 데이터를 재사용할 여지가 있다.** 지금은 Redis hit 뒤에도 역직렬화와 등급 조립을 요청마다 한다. 효과 측정 없이 큰 전역 캐시를 추가할 근거까지 확보된 것은 아니다.

최종 HTTP 기록의 keyword 평균 710.98ms, nearby 619.81ms, viewport 448.24ms는 500개 동시 요청 혼합 부하 안의 값이다. replica 연결 획득 평균 184.60ms도 warmup/측정/drain/배경 작업을 포함한 연결 작업당 counter다. 단독 SQL 실행 시간·ES 시간·ORM 시간·대기를 합산해 각 API 시간을 재구성한 자료는 아직 없다. 현재 확인한 것은 **코드상 직렬 의존성과 이미 측정된 전역 후보 규모**이며, 각 항목의 TPS 기여 비율은 추가 프로파일링 없이 확정하지 않는다.
