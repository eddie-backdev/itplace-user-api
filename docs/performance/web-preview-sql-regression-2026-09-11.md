# 웹 검색 SQL 회귀 원인과 최소 수정

`b9401d8` 이후 웹 compact/적격성 변경 `7cc730f`는 응답 body를 줄였지만 웹 혼합 500개 동시 요청에서 TPS가 975.22→949.09로 낮아졌다. 원인을 확정하지 않은 상태에서 이 결과를 성능 개선 완료로 취급하지 않고, 동일 데이터의 임시 DB에서 SQL 비용을 분리했다. 이 문서의 수치는 API TPS가 아닌 SQL 진단 수치다.

## 확인한 원인

키워드 후보 쿼리는 매장명/업종 일치 행과 제휴사명/카테고리 일치 행을 `UNION`으로 합친 뒤 `MATERIALIZED` CTE에 저장했다. 생성 정규화 컬럼 2개를 추가하면서 중복 제거 대상이 `storeId, partnerId, storeName, location` 4개 컬럼에서 6개로 늘었다. `편의점`에서는 두 분기의 44,086개 입력을 36,898개 후보로 줄이는 hash aggregate가 디스크로 넘쳤다.

| 동일 DB·검색어 `편의점` | 기존 SQL | 정규화 추가 SQL |
|---|---:|---:|
| UNION 중복 제거 컬럼 수 | 4 | 6 |
| hash aggregate disk usage | 3,528 KiB | 7,000 KiB |
| 쿼리 전체 temp written blocks | 678 | 1,814 |
| custom plan 실행 중앙값 | 92.395ms | 101.499ms |
| generic plan 실행 중앙값 | 110.563ms | 116.563ms |

위 최초 비교는 두 쿼리를 모두 같은 `20260911.0002` 임시 DB에서 실행하여 테이블 크기/통계/데이터 차이를 분리했다. 3회씩의 SQL 실행 중앙값이며 초기 실험은 기존→변경 순서였다. 이후 최종 수정 비교는 3회 교대 순서를 사용했다. 전체 부하 시험의 -2.68%를 이 SQL 하나의 원인 비중으로 단정하지 않는다.

viewport는 이번 조건에서 회귀가 아니었다. 작은 화면 custom 2.145→1.980ms, 넓은 화면 custom 20.472→15.312ms, 넓은 화면 generic 31.315→26.914ms였다. 모든 조사 계획에 JIT는 없었다. 넓은 화면의 변경 쿼리에서 CTE scan 종료 시각 13.284ms와 top-N sort 종료 시각 15.155ms의 차이는 약 1.9ms였다. 이는 해당 sort 단계의 참고 비용이며, LIMIT 제거가 지도 API를 빠르게 만든다는 근거는 아니다. LIMIT을 제거해도 앞선 공간 후보 조회·적격성 검사 비용은 사라지지 않는다.

## 최소 변경

웹 전용 `searchEligibleNearbyStoreIds`의 두 분기를 겹치지 않게 만들었다.

- 첫 분기는 기존 매장명/업종 조건 그대로다.
- 두 번째 제휴사명/카테고리 분기는 첫 분기의 조건이 `IS NOT TRUE`인 행만 가져온다. 원본 이름·업종이 null이어도 누락을 유발하는 3값 논리를 피한다.
- 분기 사이에 중복이 없으므로 `UNION ALL`로 합치고, `AS NOT MATERIALIZED`로 강제 임시 저장을 제거한다.
- 활성 매장/혜택, 오프라인 정책, 현재 이름·별칭·업종 적격성, 정확 일치 우선, 거리 정렬, `LIMIT 30`을 유지한다.
- 완전히 같은 우선순위·거리의 매장은 `storeId ASC`로 순서를 안정화한다. 기존에 미정이던 LIMIT 경계의 동순위 선택은 바뀔 수 있다.

다른 후보로 ID만 UNION한 뒤 store를 다시 조인하는 방법도 조사했다. temp spill은 없어졌지만 `다` custom 64.177→87.215ms, 스타벅스 9.925→35.256ms 등 재조인 비용이 더 커져 채택하지 않았다. materialized 상태에서 `UNION ALL`만 적용한 방법은 common 검색에 효과가 있었지만 임시 쓰기 553 blocks가 남았다.

migration·주변 샘플 정책·반경·viewport LIMIT·모바일 쿼리는 변경하지 않았다. 조회 결과 수를 줄여 얻은 성능 수치가 아니다.

## 최종 SQL 비교

최종 `IS NOT TRUE`와 `storeId ASC`까지 포함한 쿼리를 거부된 `7cc730f` 쿼리와 동일 DB에서 전후 교대로 3회씩 실행했다. HTTP 부하는 없었다.

| 검색어 | 계획 | 거부된 SQL | 최종 SQL |
|---|---|---:|---:|
| 편의점 | custom | 97.587ms | 79.064ms |
| 편의점 | generic | 116.723ms | 95.096ms |
| 다 | custom | 62.097ms | 34.193ms |
| 다 | generic | 92.332ms | 95.384ms |
| 스타벅스 | custom | 9.817ms | 9.878ms |
| 스타벅스 | generic | 16.507ms | 16.306ms |
| 없는매장xyz | custom | 0.435ms | 0.890ms |
| 없는매장xyz | generic | 6.486ms | 6.955ms |

`편의점`의 전체 temp written blocks는 1,814→0이다. 짧은 검색어의 generic plan과 빈 검색은 모든 측정에서 빨라진 것이 아니다. 빈 검색은 새 계획에서 혜택 hash 준비가 먼저 실행되어 약 0.45ms 증가했다. 따라서 이 SQL 수정만으로 모든 API가 빨라졌다고 주장하지 않는다.

## auto plan 재현과 제한된 custom plan 적용 근거

서로 다른 좌표/검색어의 실제 corpus 첫 24개 keyword 입력을 **하나의 PostgreSQL prepared statement/연결**에서 순서대로 실행했다. 다른 시나리오에서는 `푸드`/`생활/편의`/null 카테고리도 섞었다. 두 SQL, 두 시나리오 모두 24회 이후 `custom_plans=5`, `generic_plans=19`였다.

최종 SQL의 다음 `다` 요청은 auto에서 84.824ms(카테고리 혼합 시 81.814ms)였고 선택된 예상 cost는 generic 3,113.94였다. 별도 강제 custom 진단에서는 34.193ms였다. 앞선 함수 자체가 느린 것으로 단정할 상황이 아니라, 바인딩별 선택도가 다른 검색에서 generic 계획이 실제로 선택되는 문제가 있었다.

이에 웹 keyword DB 조회 트랜잭션에만 기존 `SET LOCAL plan_cache_mode=force_custom_plan` 패턴을 적용한다. 실제 서비스 wrapper와 commit/rollback 시 설정 복구 검증은 통합 변경에 포함한다. 전역 PostgreSQL 설정이나 모바일/viewport 계획을 바꾸지 않는다. 위 prepared-statement 검사는 DB의 자동 선택을 재현한 실험이며 실행 중인 Hikari 연결에서 counter를 수집한 자료는 아니다. 전체 앱의 개선 정도는 별도 최종 부하 시험으로 확인한다.

## 결과 계약 검증과 근거 보존

실제 corpus 24개 keyword SQL을 비교했다. 23개는 기존 결과/순서와 그대로 일치했다. 나머지 GS25 질의는 광주대의 동일 좌표 지점들이 LIMIT 끝에 걸렸고, 미정이던 동순위 선택을 `storeId`로 고정하면서 11867 대신 11860을 선택했다. 두 지점은 동일 브랜드/좌표 `(126.896139, 35.105985)`와 같은 거리 `7978.34881721m`다. **기존 쿼리에도 같은 `storeId` tie-break를 적용하면 24개 모두 ID·순서가 일치**한다. 이 차이를 숨겨서 원본 24개 전체가 그대로 같다고 기록하지 않는다.

PostGIS 회귀 테스트에 null 이름/업종, 별칭 기반 partner-only 후보, 두 분기 중복, `%`/`_`와 escaped wildcard, 카테고리, 같은 좌표의 ID tie-break를 추가했다. 기존 이름·업종/LIMIT/공간 경계/JPA 최종 조회 테스트를 유지했다. 전체 `./gradlew test build`에서 423개 테스트가 통과했고 실패·오류·skip은 0이었다. [실행 요약](evidence/2026-09-11/web-preview-regression/tests.json). 추가한 keyword transaction의 같은 JPA 연결에서 custom plan 적용과 commit/rollback 뒤 auto 복구를 모두 확인했다.

[정리된 SQL 증거](evidence/2026-09-11/web-preview-regression/sql-summary.json)에 최종 쿼리 hash, 계획/시간 요약, auto counter, 동일성 및 원본 파일 SHA-256을 보존한다. 원본은 `output/web-preview-regression-2026-09-11/`에 있다.

- `eligibility-keyword-explains.json`: 5개 대안 × 4개 검색어 × 2개 계획 × 3회 = 120개 원본 EXPLAIN.
- `eligibility-final-keyword-explains.json`: 최종 쿼리와 거부된 쿼리의 48개 교대 EXPLAIN.
- `eligibility-auto-plans.json`: 4개 prepared-statement 실험의 24회 counter와 후속 원본 EXPLAIN.
- `eligibility-corpus-parity.json`: 원본/최종/동일 tie-break 원본의 24개 실제 반환 ID.
- `eligibility-initial-plan-summaries.json`: 초기 viewport/radius/keyword의 추출 계획 요약. 이 파일은 전체 원본 plan이 아닌 진단용 요약이다.
- `eligibility-sql-diagnostic.py`, `eligibility-final-diagnostic.py`: 해당 임시 DB에 읽기 전용 트랜잭션으로 재현하는 스크립트.
