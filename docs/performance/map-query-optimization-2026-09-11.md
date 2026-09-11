# 지도 DB 조회·결과 매핑 개선 — 2026-09-11

지도 미리보기의 결과 변환 비용과 넓은 영역 조회의 잘못된 실행 계획을 줄이고, 키워드 검색이 모든 매장을 반복 검사하는 경로를 정리했다. 이 문서는 **baseline `86cdc70`과 개선 작업 트리의 SQL 비교 및 정확성 검사**를 기록한다. 새 전국 좌표 HTTP 부하 시험의 TPS·응답시간 결과는 아직 확정되지 않았다.

과거 동일 좌표를 반복한 캐시 적중 시험은 이 개선의 포트폴리오 성과로 재사용하지 않는다. 아래 SQL 수치는 DB 내부 원인과 변경 효과를 보여주는 근거이며, 사용자 응답시간·동시 사용자 수·클라우드 처리량으로 환산할 수 없다.

## 1. 미리보기 결과를 프록시 없이 매핑

기존 query는 Spring Data의 interface projection을 반환했다. 서비스는 이 프록시의 getter를 행마다 14번 호출해 별도 불변 객체로 복사했다. 300행 요청이면 최대 4,200번의 프록시 getter 호출이 발생하고, 복사를 마칠 때까지 읽기 트랜잭션도 유지됐다.

[StoreRepository](../../src/main/java/com/itplace/userapi/map/repository/StoreRepository.java)의 native 결과를 `List<Object[]>`로 바꾸고, [StorePreviewQueryService](../../src/main/java/com/itplace/userapi/map/service/StorePreviewQueryService.java)에서 scalar 값으로 불변 record를 바로 만든다. 서비스 밖으로 반환하는 `List<StorePreviewProjection>` 계약은 유지한다. 이 변경은 JDBC를 사용하는 JPA native query의 결과 매핑 변경이며, JPA 전체를 제거하거나 별도의 영구 캐시를 추가한 작업은 아니다.

프록시 getter 호출을 제거했다는 것은 코드로 확인한 사실이다. 이 변경만의 API 지연 감소율이나 TPS 증가율은 이 문서에서 주장하지 않는다. 새 HTTP 전후 비교와 프로파일링으로 별도 검증해야 한다.

## 2. 좌표 경계 계약을 유지하면서 실행 계획 개선

기존 미리보기는 geometry bbox와 numeric 위·경도 범위를 함께 적용했다. 두 조건의 상관관계를 고려하지 못한 넓은 영역의 custom plan은 후보 수를 지나치게 작게 예상하고 제휴사를 반복 조회했다.

개선 query는 geometry bbox와 제휴사 조인을 `MATERIALIZED` CTE에서 먼저 실행하고, 그 뒤 기존 numeric 범위·중심 거리·`storeId` 정렬·limit을 적용한다. geometry와 숫자 좌표는 정밀도가 다를 수 있으므로 numeric 조건을 삭제하지 않았다. 두 좌표가 다를 때 기존에 제외되던 매장을 포함시키지 않고, 포함 경계도 유지한다. 좌표를 대량 수정하거나 새 동기화 trigger를 추가할 필요가 없었다.

### 측정 조건

- 로컬 Docker의 PostgreSQL 18.4 / PostGIS 3.6.4, 매장 73,715건.
- category 없음, limit 300. 각 viewport의 중심점을 거리 정렬 기준으로 사용.
- 중앙 viewport: 위도 `37.5556~37.5776`, 경도 `126.9641~126.9921`.
- 넓은 서울 viewport: 위도 `37.4~37.7`, 경도 `126.8~127.2`.
- 실제 Java 인자와 같이 좌표를 `float8`, category를 `varchar`, limit을 `int`로 `PREPARE`했다.
- `force_custom_plan`과 `force_generic_plan`을 별도 읽기 전용 트랜잭션에서 비교했다. 서비스의 전역 plan 설정을 바꾸지 않았다.
- 입력·plan마다 이전/이후 교대 3회 실행한 **SQL 실행시간 중앙값**이다. API 시간이나 planning time을 합친 값이 아니다.

| 입력·plan | 이전 | 이후 |
|---|---:|---:|
| 중앙 viewport / custom | 2.494ms | 1.914ms |
| 중앙 viewport / generic | 1.532ms | 1.354ms |
| 넓은 서울 viewport / custom | 147.988ms | 19.778ms |
| 넓은 서울 viewport / generic | 30.699ms | 31.755ms |

넓은 custom 입력에서는 크게 개선됐지만, 넓은 generic 입력은 약 1ms 증가했다. 따라서 모든 입력에 같은 개선 배수를 적용할 수 없다. 적용하지 않은 geometry-only 대안의 더 빠른 수치를 최종 구현 성과로 사용하지 않는다. [개별 실행 결과 24건](evidence/2026-09-11/query/final-preview-summary.json)에 중앙값 계산의 원본 숫자를 보관했다.

## 3. 키워드 후보를 나누고 이미 찾은 행 재사용

기존 검색은 매장명·업종·제휴사명·분류의 부분 일치 조건을 서로 다른 테이블에 걸친 OR로 처리했다. 없는 검색어도 매장과 제휴사를 조인한 뒤 문자열을 검사했다. ES 검색 성공 시에도 가까운 매장 보충을 위해 이 DB query를 사용하므로, ES 장애 때만 발생하는 비용이 아니었다.

개선 query는 다음 두 후보 집합을 만든다.

1. 매장명 또는 업종이 일치하는 매장. 해당 표현식에 활성·좌표 보유 매장용 GIN trigram 인덱스를 추가했다.
2. 제휴사명 또는 분류가 일치하는 제휴사의 매장. 제휴사 ID를 먼저 구하고 기존 제휴사 인덱스를 활용한다.

두 집합을 UNION해 중복을 제거하고, ID·제휴사 ID·매장명·geometry를 보관한 후보 행을 최종 정렬에서도 재사용한다. 검색 후 전체 store를 다시 읽는 비용을 줄였다. 활성 오프라인 혜택, category 조건, 정확 일치 우선, 거리 순서, 최대 30개, LIKE의 `%`·`_`·escape 의미는 유지한다.

### 측정 결과와 한계

새 migration을 로컬 트랜잭션에서 적용한 뒤 이전·신규 query를 각각 3회 실행하고 모두 ROLLBACK했다. 두 query는 같은 데이터·인덱스 상태를 사용했다. 위치는 위도 `37.5665`, 경도 `126.978`, category 없음이다. 아래는 SQL 실행시간 중앙값이며 planning time은 제외했다.

| 검색어 | 이전 | 이후 |
|---|---:|---:|
| 스타벅스 | 107.722ms | 5.549ms |
| 존재하지않는지점xyz | 118.924ms | 0.371ms |
| 편의점 | 108.742ms | 86.460ms |
| 다 | 59.571ms | 58.304ms |
| 스타% | 63.266ms | 63.269ms |
| 스타_ | 63.131ms | 63.348ms |

희소한 검색어와 없는 검색어가 크게 개선됐다. 한 글자나 wildcard 때문에 trigram 선택성이 낮은 입력은 여전히 많은 후보를 검사한다. 광범위 업종·분류 검색에도 수만 후보의 거리 계산과 정렬 비용이 남는다. 이 실험은 이전 3회 뒤 신규 3회를 실행했으므로, 교대 반복·독립 호스트 HTTP 부하 시험과 같은 증거 수준으로 해석하지 않는다. [개별 실행 결과 36건](evidence/2026-09-11/query/keyword-experiment.json)에 execution/planning time과 반환 행 수를 보관했다.

## 4. 주변·분산 조회와 DB 경계 정리

주변·분산·제휴사 검색의 상관 EXISTS를 활성 오프라인 제휴사 ID를 한 번 계산하는 ARRAY 조건으로 바꿨다. category를 거르는 용도로만 쓰던 partner join도 이 집합 계산 안으로 이동해 매장마다 제휴사를 조회하지 않게 했다. 큰 반경의 실행 계획에서 JIT 비용이 없어지는 것을 로컬 진단으로 확인했지만, 단일 진단 시간을 이 문서의 정량 개선 성과로 채택하지 않는다. 전역·세션 JIT 설정도 변경하지 않았다.

모든 선언 `StoreRepository` query에는 read-only 및 5초 트랜잭션 제한을 적용했다. 서비스·모바일의 외부 트랜잭션 분리와 함께 DB 조회 및 필요한 값 추출 이후 Redis·응답 조립이 connection을 계속 점유하지 않도록 한다. 상속받은 save/delete의 트랜잭션 설정을 변경한 것은 아니다.

반경 query의 기존 LIMIT 앞에는 ORDER BY가 없다. 반환 한도에 걸린 후보가 항상 같은 ID 집합이라는 계약은 원래 없으므로, 실행 계획 변경 전후 임의 후보 ID의 동일성을 검증 근거로 사용하지 않는다. 분산 조회의 셀별 거리·`storeId` 순서는 별도로 유지한다.

## 5. Migration과 호환성

[V20260911_0001 migration](../../src/main/resources/db/migration/V20260911_0001__index_store_keyword_and_normalize_active.sql)은 다음을 적용한다.

- benefit/policy의 NULL active를 기존 `COALESCE(active, TRUE)` 의미대로 TRUE로 정규화하고 NOT NULL을 보장한다. 새 `active = TRUE` 조건이 기존 partial index와 일치한다.
- `pg_trgm`과 활성·좌표 보유 매장의 매장명/업종 표현식 GIN 인덱스를 추가한다.
- 사용자별 관심 혜택 목록·최근 변경 조회를 위한 `favorite(userId, createdDate DESC)` 인덱스를 추가한다. 이 작은 테이블의 현재 성능 개선 배수는 측정하지 않았다.
- policy의 중복 unique 인덱스는 같은 컬럼의 PK가 존재하고 FK/constraint/replica identity 참조가 없을 때만 제거한다. 오래된 스키마에서 해당 unique 인덱스를 FK가 참조하면 유지한다.

Migration이 새 query보다 먼저 적용돼야 한다. `pg_trgm` 설치 권한이 필요하며, lock timeout 5초 또는 statement timeout 120초를 넘으면 실패한다. 이 문서의 진단 과정에서는 운영 DB에 적용하지 않았고 원본 로컬 DB의 임시 schema 변경도 rollback했다. 이후 배포·복제 DB 적용 여부는 해당 작업 기록으로 확인한다.

## 6. 정확성 검사와 포트폴리오 사용 범위

변경 구간 검증은 [25개 test 결과](evidence/2026-09-11/query/test-summary.json)에서 실패·오류·skip이 모두 0이었다. 이는 전체 저장소 최종 통합 test 수가 아니다. [PostGIS 통합 검사](../../src/test/java/com/itplace/userapi/map/repository/StorePreviewQueryIntegrationTest.java)는 실제 migration, legacy NULL, unique 인덱스를 참조하는 FK, numeric/geometry 좌표 불일치와 경계, category, keyword의 대소문자·부분 일치·wildcard·escape·정확 일치 우선, 반경·분산 셀 정렬을 검증한다.

추가로 전국의 실제 매장 60개를 중심으로 다음 미리보기 SQL 동등성 검사를 수행했다.

- `md5(storeId || '20260911')` 순서로 활성·좌표 보유 매장 60개를 선정했다.
- seed `20260911`의 난수로 중심을 위·경도 각각 ±0.005도 이동하고, 반폭을 0.005/0.011/0.15도 중 선택했다.
- category 없음, limit 300, 같은 `float8` 바인딩으로 이전·이후 SELECT를 실행했다.
- 두 SELECT의 14열 텍스트 결과 9,147행이 일치했다. [60개 좌표·크기·행 수·검사 결과](evidence/2026-09-11/query/preview-random-parity.json)를 보관했다. 원본 행 payload를 보관한 자료는 아니다.

이 검사는 다양한 위치에서 결과를 보존했다는 근거다. 60개 위치의 HTTP 응답시간 부하 시험 또는 전국 균등 표본 성능 시험이 아니다. 코드 변경→원인에 맞춘 SQL 실험→경계·정확성 검사까지는 이 문서로 설명할 수 있다. **새 전국 좌표 HTTP 결과가 확정되기 전에는 전체 TPS 증가율, API p95 감소율, 운영 서버 수용량을 이 문서의 SQL 숫자로 대체하지 않는다.**
