# 혜택 목록 favorite fanout 제거 검증

## 검증 대상과 결론

`BenefitRepository.findFilteredBenefits`의 data query에서 `LEFT JOIN favorite`와 `COUNT(DISTINCT f.userId)`를 제거하고, 정렬용 상관 `COUNT(*)`를 사용한 변경이다. count query에서는 favorite join을 완전히 제거했다. 운영 source 추가 수정이나 shared DB 쓰기는 하지 않았다.

- 기존 원본: `86cdc70c5e198e3547fef677f7816239316b0205`의 repository SQL. [baseline](evidence/2026-09-11/benefit-fanout/baseline.json)에 source SHA-256을 보관했다.
- 변경 후: 실행 당시 repository의 `@Query.value/countQuery`를 직접 읽어 PostgreSQL에서 실행했다.
- 실제 PostgreSQL fixture에서 **105개 필터 조합 × 4개 정렬 = 420개 조건**의 전체 데이터, total count, LIMIT/OFFSET 페이지와 정확한 순서가 일치했다.
- 정렬은 기존 `benefitId ASC` tie break까지 보존하므로 동률을 임의로 허용하지 않고 전체 목록 순서를 직접 비교했다.
- `favorite`의 `(benefitId,userId)` PK는 동일 혜택·사용자 중복을 막는다. 중복 insert가 거부되는 것도 확인했다. 따라서 favorite 자체의 `COUNT(*)`와 원래 join 결과의 `COUNT(DISTINCT userId)`가 같은 인기 순서 기준이 된다.

## 회귀 fixture

혜택 12개, 정책 15개, favorite 15개를 사용했다. 여러 통신사/정책을 가진 혜택, favorite 수 동률, 이름/대소문자 동률, favorite 없는 혜택, 정책 없는 혜택, 비활성 혜택·정책, 역사적 null active, null 이름·카테고리, 누락 partner, 여러 등급 context를 포함한다. null active는 현행 NOT NULL 운영 schema를 주장하는 것이 아니라 기존 COALESCE의 동작 보존을 확인하기 위한 fixture다.

필터는 mainCategory/category, ONLINE/OFFLINE, carrier filter ON/OFF와 단일/복수/없는 carrier, 설명/manual/tier context 검색, 빈 문자열·없는 검색어·SQL wildcard를 포함한다. 난수 seed는 `20260911`이며 정렬은 POPULARITY, NAME_ASC, NAME_DESC, LATEST다. [420조건 결과](evidence/2026-09-11/benefit-fanout/equivalence.json) 참조.

## 성장 fixture의 실행 계획

PostgreSQL 16.4 x86_64 Alpine Testcontainers, 단일 connection, `jit=off`, `work_mem=4MB` 조건이다. 실제 로컬 운영 DB(PostgreSQL 18)와 다르며 같은 개발 머신의 컨테이너 실행이다. 400개 혜택마다 정책 3개, favorite 250개를 넣었다. 전체 정책 1,200개, favorite 100,000개다. 데이터 조회 상한은 20개이고 인기순을 사용한다.

각 SQL을 한 번 예열한 뒤 `EXPLAIN (ANALYZE, BUFFERS, TIMING OFF, FORMAT JSON)`을 5회 실행했다. 라운드마다 전/후 실행 순서를 바꿨다. 시간은 PostgreSQL `Execution Time`이며 HTTP/JPA 변환/직렬화/네트워크 지연은 포함하지 않는다. 시간 수치에는 테스트 통과 기준을 걸지 않았다.

| 항목 | 변경 전 | 변경 후 |
|---|---:|---:|
| Data 최대 join 출력 행 | 300,000 | 1,200 |
| Count 최대 join 출력 행 | 300,000 | 1,200 |
| Data 실행 중앙값 | 114.632ms | 44.927ms |
| Count 실행 중앙값 | 47.499ms | 0.396ms |
| Data 첫 반복 Shared Hit Blocks | 102,591 | 102,591 |
| Count 첫 반복 Shared Hit Blocks | 101,397 | 16 |

첫 반복 네 계획의 Shared Read/Temp Read/Temp Written Blocks는 모두 0이었다. **Data의 250배 감소는 집계 전 join 중간 행 수이지 DB 읽기량이나 API 응답속도 배수가 아니다.** 인기순 계산은 여전히 favorite를 읽는다. count query는 favorite가 필터에 쓰이지 않으므로 그 읽기를 완전히 제거했다. [실행 계획 요약](evidence/2026-09-11/benefit-fanout/fanout-summary.json)과 전/후 data/count 각각 5개의 plan JSON에 원본을 보관했다.

## 재현·산출물

추가한 독립 테스트와 baseline SQL:

- [BenefitFilterFanoutIntegrationTest.java](../../src/test/java/com/itplace/userapi/benefit/repository/BenefitFilterFanoutIntegrationTest.java)
- [filter-before-fanout-data.sql](../../src/test/resources/benefit/filter-before-fanout-data.sql)
- [filter-before-fanout-count.sql](../../src/test/resources/benefit/filter-before-fanout-count.sql)

격리된 Gradle build directory에서 해당 테스트만 실행했다. 테스트 2개, 실패/오류/skip 0이다. [검증 합계](evidence/2026-09-11/benefit-fanout/verification.json) 참조. 원본 log/XML/plan은 로컬 `output/performance-renewal-2026-09-11/benefit-fanout/`에 보관한다. 테스트는 기본 실행 때 evidence 파일을 만들지 않으며 `benefit.fanout.evidence` JVM 속성을 지정하면 JSON을 남긴다.

이 자료는 **SQL 정합성과 favorite 성장 시 비용 구조**의 근거다. 현재 운영 favorite 양에서 같은 개선률을 기대하거나 지도 TPS/API latency의 개선 결과로 사용하면 안 된다. 현재 작은 운영 데이터에서의 효과와 서비스 전체 성능은 별도 통합 자료로 확인한다.
