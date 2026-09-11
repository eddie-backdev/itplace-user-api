# 웹 지도 매장·제휴사 일치 검증을 읽기 쿼리로 통합

## 문제와 변경

웹 상세 지도는 SQL에서 가까운 후보를 `LIMIT 300`으로 자른 뒤 Java에서 매장명·제휴사명 정규화, 편의점 별칭, 다락 업종 조건을 검사했다. 가까운 잘못 연결된 매장이 후보를 차지하면 뒤의 정상 매장을 보충하지 못했다. 검색도 DB 후보의 `LIMIT 30` 뒤에 동일한 문제가 있었다. 요청마다 원본 문자열을 소문자화하고 정규식을 적용하는 비용도 남았다.

`V20260911_0002__store_map_read_normalization.sql`은 PostgreSQL stored generated column을 추가한다. `store.mapNormalizedName`, `store.mapNormalizedBusiness`, `partner.mapNormalizedName`은 해당 행의 쓰기 시 갱신된다. 원본 문자열이나 제휴사 연결을 변경하지 않는다. 이름 변경은 다음 조회에서 현재 두 행을 조인해 판단하므로 별도 동기화 작업·교차 테이블 boolean 플래그·트리거가 필요 없다.

조회는 정규화 컬럼을 받는 immutable SQL 함수 `map_store_partner_matches`로 이름 포함 관계, GS25/지에스25·CU/씨유·세븐일레븐/7eleven 별칭, 다락/미니창고다락의 보관·저장 업종을 판단한다. 함수는 정규식/소문자화 없이 `strpos`와 `CASE`만 사용한다. 판정 자체를 없애는 것이 아니라, 정규화는 쓰기로 이동하고 판정을 SQL 후보 제한 전에 수행한다.

## 적용 범위와 기존 계약

| 경로 | 저장소 메서드 | 보장 |
|---|---|---|
| 웹 화면 안 상세/compact 지도 | `findStorePreviewsInView` | 이름·업종 조건 이후 기존 좌표 경계, 중심 거리 순서, LIMIT 적용 |
| 웹 주변/카테고리 preview | `findEligibleStoreIdsWithinRadius` | radius/카테고리/활성 오프라인 혜택/이름·업종 조건 이후 기존 LIMIT 적용 |
| 웹 키워드 preview | `searchEligibleNearbyStoreIds` | 기존 부분 일치·wildcard·정확 일치 우선·거리 정렬 유지, 적격 매장을 LIMIT 30으로 제한 |
| 웹 ES/DB 후보 최종 엔티티 조회 | `findEligibleByStoreIdInWithPartner` | 현재 DB 값으로 활성 혜택·이름·업종 재확인, JOIN FETCH로 제휴사까지 조회 |

마지막 ID 조회도 현재 값을 확인하므로 ES의 오래된 ID나 후보 조회 이후 이름·활성 상태가 바뀐 행을 검증 없이 응답하지 않는다. 이 네 경로를 받은 웹 서비스는 기존 Java 이름·업종 재검증이 필요 없다. 키워드의 브랜드 우선순위 검사는 별도의 검색 계약이므로 유지한다.

기존 `findStoreIdsInRadius`, `findStoreIdsByCategoryWithinRadius`, `searchNearbyStoreIds`, `findAllByStoreIdInWithPartner`, 분산/파트너 후보 메서드는 바꾸지 않았다. 모바일/기존 full-detail은 기존 경로를 사용한다. 900개 후보 중 300개 무작위 선택 정책, 반경, 클러스터 집계, 결과 DTO 기본 계약은 이번 쿼리 변경 대상이 아니다. 신규 컬럼은 JPA `insertable=false, updatable=false` 및 `JsonIgnore`로 설정했다.

기존 preview SQL의 다락 업종 검사는 원본 `business LIKE '%보관%'`이었다. 새 조건은 Java와 같이 정규화하여 `보-관`, `저 장`도 인정한다. 이는 기존 Java 업무 규칙에 맞춘 의도적인 차이다.

## 검증 근거

`StoreReadEligibilityIntegrationTest`는 실제 PostgreSQL/PostGIS에서 다음을 확인한다.

- Java 17이 정의한 모든 유효 Unicode code point를 연결한 입력의 정규화와 SQL 결과 일치. null 입력은 빈 문자열. `Locale.ROOT`에서 ASCII 검색 문자로 변환되는 `İ`와 `K`도 명시적으로 포함한다. DB/OS locale 영향을 피하도록 SQL은 ASCII 대소문자 변환과 두 문자를 `translate`, 문자 제거는 `COLLATE "C"` 정규식으로 처리한다.
- 대소문자·문장부호·한글·편의점 양방향 별칭·다락 업종·null·무관 브랜드 20개 판정 fixture가 기존 Java 규칙과 일치.
- 매장명/업종/제휴사명 SQL UPDATE 뒤 생성 컬럼과 조회 결과가 즉시 갱신됨.
- 가까운 잘못 연결된 매장 305개 뒤에 정상 매장 305개를 둔 viewport에서 정상 매장 300개를 반환하고, `LIMIT 2`에서도 정상 우선순위를 유지함.
- 반경/카테고리 후보가 이름·업종을 LIMIT 전에 거르고 기존 모바일용 후보 메서드는 이전 집합을 반환함.
- 35개 잘못 연결된 가까운 매장 뒤의 정상 키워드 매장이 새 LIMIT 30 결과에 포함됨. 정확/부분 일치, `%`, `_`, escaped `_`, 카테고리 계약 유지.
- 실제 Hibernate JPQL 실행에서 신규 `JOIN FETCH`가 무관 브랜드/다락 요양/중지 혜택/없는 ES ID를 제외하며 EntityManager 종료 후 제휴사 접근에 lazy 조회가 필요하지 않음. 기존 ID 조회는 이전 집합 유지.

기존 `StorePreviewQueryIntegrationTest`의 활성/오프라인/중복 방지/좌표 경계/카테고리/정렬/정책 조인 1회 검사도 유지한다. preview에 쓰는 fixture 이름만 실제 제휴사와 일치하도록 보완하여 새 판정 때문에 기존 경계 검사가 우연히 통과하지 않게 했다. 기존 키워드·반경·분산 메서드의 기대 결과는 변경하지 않았다.

수행 환경: SDKMAN Java 17.0.19, Testcontainers `postgis/postgis:16-3.4-alpine`. Docker 29 대응을 위해 `JAVA_TOOL_OPTIONS=-Dapi.version=1.44`, 로컬 OrbStack `DOCKER_HOST`를 사용한다. **Scoped 30개 테스트 통과, 실패/오류/skip 0건**. [실행 요약](evidence/2026-09-11/map-read-eligibility-tests.json)에 결과를 남겼다. 전체 서비스 검증은 통합 검증 기록에 함께 남긴다. 이 문서는 별도 API TPS 상승이나 운영 서버 성능을 주장하지 않는다.

## 배포와 되돌리기

운영 DB에는 적용하지 않았다. 생성 컬럼 추가는 기존 행의 정규화 계산과 테이블 rewrite/배타 잠금·WAL을 발생시킬 수 있다. migration은 `lock_timeout=5s`, `statement_timeout=120s`로 긴 잠금 대기를 제한한다. 실제 테이블 크기·복제 지연·배포 시간을 별도로 검증한 뒤 migration 성공 후 앱을 배포해야 한다. migration 실패를 무시하고 새 앱을 올리면 없는 컬럼/함수 조회가 실패한다.

문제가 있으면 앱을 이전 버전으로 먼저 되돌린다. 추가 컬럼/함수는 이전 앱이 사용하지 않으므로 남겨둘 수 있다. 컬럼 제거는 새 앱 인스턴스가 모두 내려간 것을 확인한 뒤 별도 승인된 migration으로 수행한다. generated expression 함수의 의미를 바꾸어도 기존 stored 값이 저절로 재계산되지는 않으므로 향후 정규화 규칙 변경에는 명시적인 컬럼 재계산 migration이 필요하다.

한계: 문자열 포함 기반의 기존 매장·브랜드 관계 판단은 유지된다. 잘못 수집된 관계의 원천 정정과 실제 브랜드 식별자 관리까지 해결한 변경은 아니다. 장시간/운영 부하는 별도 검증 대상이다.
