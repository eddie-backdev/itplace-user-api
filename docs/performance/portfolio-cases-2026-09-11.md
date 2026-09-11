# 지도 검색 성능 개선 — 포트폴리오 사례 원고

이 자료는 최초 개발부터의 누적 속도 배수를 만드는 자료가 아니다. 이미 여러 최적화가 적용된 `86cdc70`에서 출발해, 남은 병목을 조사하고 수정한 **2026-09-11 후속 개선**을 설명한다. 전체 HTTP 결과는 [측정 방법](measurement-method-2026-09-11.md)에 따른 전국 이동 입력으로 교체한다. 각 개선의 단독 효과와 여러 변경을 합친 효과를 구분한다.

현재 성능 테스트 대상은 웹 API다. 모바일 구현·정합성 검사 기록은 남기되 향후 부하에서 제외한다. 모바일 5%가 포함된 기존 혼합 609→1,043 TPS를 웹 전용 성능으로 인용하지 않는다. 현재 웹 지도 대표 근거는 모바일이 없었던 상세·클러스터 4,357→5,497 TPS, 평균 114→91ms이며 키워드·주변까지 포함한 웹 전용 혼합은 재측정 전이다.

## 사례 1. SQL 다음에 숨어 있던 JPA 변환 비용

**문제:** 조회 SQL을 줄이고 Redis 적중률을 높였는데도 상세 지도의 응답이 느렸다. SQL이 끝난 이후 connection을 반환하기 전까지의 작업도 조사했다.

**근거:** 개선 전 진단 JFR의 execution sample 1,062개 중 486개(45.8%)에 `StorePreviewSnapshot.from`이 포함됐다. interface projection의 getter 14개를 각 매장마다 호출해 다시 record로 복사하는 경로였다. 300행에서 최대 4,200회 proxy getter를 호출했다. 이 비율은 inclusive CPU sampling 비율이지 응답시간의 45.8%나 보장되는 개선률이 아니다. [진단 조건·샘플 수](evidence/2026-09-11/diagnostic-jfr.json).

**선택:** 이 query만 native scalar `Object[]`를 받아 불변 record로 직접 매핑했다. 전체 매장·응답을 저장하는 새 메모리 캐시는 추가하지 않았다. 기존 `StorePreviewProjection` 소비자 계약과 짧은 read-only transaction은 유지했다. 정규화 Pattern은 정적으로 재사용하고 제휴사명/alias는 요청 안에서 한 번만 정규화했다.

**입증:** 실제 PostGIS 좌표 경계·불일치 fixture 및 전국 60개 bounds의 9,147행/14개 컬럼 동등성으로 필드와 순서를 검증했다. HTTP 성능은 같은 패키지 실행 조건의 전후 비교로 따로 확인한다. proxy 제거만으로 전체 TPS가 몇 배 올랐다고 분리 귀속하지 않는다.

전국 viewport 입력으로 새로 수집한 JFR에서는 `StorePreviewSnapshot.from` 포함 sample이 **93/2,089(4.45%)→6/2,053(0.29%)**, `ProjectingMethodInterceptor`는 **74→0**이었다. 초기 서울 진단의 45.8%와 입력 밀도·실행 조건이 다르다. 이 차이도 원래 sample 비율을 모든 지도 요청에 일반화하면 안 되는 이유다. [새 프로파일 근거](evidence/2026-09-11/jfr-comparison.json).

**면접 원고:** “DB가 빠르다는 사실만으로 조회가 빠르다고 판단하지 않았습니다. JFR로 connection 반환 전의 projection 복사를 확인했고, 조회 전용 scalar 결과를 바로 불변 객체로 변환했습니다. 다른 캐시 계층을 추가하기 전에 요청마다 반복하던 작업부터 제거했습니다.”

[구현·SQL 검증](map-query-optimization-2026-09-11.md)

## 사례 2. LIMIT 300인데 12,829번 반복하던 조인

**문제:** 공간 GiST 인덱스가 있어도 넓은 상세 bounds에서 잘못된 row 추정으로 partner 탐색이 반복됐다. `LIMIT 300`은 앞선 후보 검사·조인을 줄여 주지 못했다.

**근거:** geometry 범위와 numeric 위·경도 조건의 상관관계를 과소 추정해 실제 후보 12,829건을 1건으로 판단한 custom plan이었다. 디스크 읽기·temporary spill은 없었으므로 메모리 증설을 먼저 선택할 근거가 없었다.

**선택:** geometry 후보와 제휴사 조인을 먼저 실행하고, 기존 numeric bounds를 다음 단계에서 검사하도록 SQL 단계를 나눴다. 두 좌표 표현의 일치를 강제하는 constraint가 없으므로 numeric 조건을 지우는 빠른 대안을 채택하지 않았다. 불필요한 전역 planner 설정 변경도 피했다.

**입증:** 같은 float8 바인딩으로 custom/generic을 각각 3회 비교했다. 넓은 서울 custom plan 중앙값은 **147.988→19.778ms**였다. 넓은 generic은 **30.699→31.755ms**로 소폭 느려져 함께 공개한다. 작은 bounds와 경계 정합성도 검증했다.

**면접 원고:** “인덱스 존재 여부보다 실제 cardinality와 반복 횟수를 봤습니다. 조건을 단순 삭제하면 좌표 경계 의미가 달라질 수 있어, 기존 필터를 보존하면서 조인 순서가 잘못 잡히는 지점을 분리했습니다. 모든 실행 계획이 빨라졌다고 주장하지 않고 generic의 작은 회귀도 기록했습니다.”

[원본 요약·한계](map-query-optimization-2026-09-11.md)

## 사례 3. 검색 결과가 없어도 발생하던 전국 전체 검사

**문제:** 매장명/업종과 제휴사명/카테고리를 서로 다른 테이블에 걸친 문자열 OR로 검사했다. 없는 검색어도 전체 store를 읽었고, ES 검색이 성공해도 가까운 매장 보충용 DB query가 실행됐다.

**선택:** 매장 후보와 제휴사 후보를 나누고 작은 후보 결과를 재사용했다. 실제 표현식과 일치하는 부분 GIN trigram 인덱스를 추가했다. ES 후보 수 제한 때문에 가까운 매장이 빠지는 문제를 피하기 위해 DB 보충은 유지했다. ES는 사용하는 `storeId`만 받게 했다. 추출 가능한 trigram이 없는 패턴의 한계는 [PostgreSQL 공식 문서](https://www.postgresql.org/docs/18/pgtrgm.html)와 실제 계획을 함께 확인했다.

**입증:** SQL 3회 중앙값은 스타벅스 **107.722→5.549ms**, 없는 검색어 **118.924→0.371ms**, 편의점 **108.742→86.460ms**였다. 한 글자와 wildcard는 거의 개선되지 않았다. 부분 일치/정확 일치 우선/LIKE wildcard/escape/거리 계약을 검사했다.

**함께 수정:** 주변/분산 query의 활성 제휴사를 한 번 계산하는 ARRAY로 바꿔 반복 상관 조회를 제거했다. 400km 입력에서 JIT 238ms가 붙던 계획이 자연스럽게 바뀌었다. 293.901→16.708ms는 단일 SQL 진단이며 반복 HTTP 결과처럼 제시하지 않는다.

**면접 원고:** “Elasticsearch가 있으니 DB fallback을 지우자는 접근은 검색 정확도 문제를 되살릴 수 있었습니다. DB 보충 계약을 유지한 상태에서 테이블을 가로지르는 OR를 분해하고 인덱스가 적용되는 표현식으로 맞췄습니다. 선택성이 낮은 검색어가 여전히 비싸다는 한계도 남겼습니다.”

[쿼리와 migration](map-query-optimization-2026-09-11.md)

## 사례 4. 캐시 적중 비용과 갱신 정합성을 함께 개선

**문제:** 혜택은 잘 재사용되지만 요청마다 제휴사별 Redis command/future가 생겼다. 동시에 만료되면 중복 DB load가 발생할 수 있었고 import가 끝나도 관련 key를 무효화하는 연결이 없었다.

**선택:** 기존 비동기 GET 묶음을 standalone Redis MGET 한 번으로 바꾸고 typed JSON은 요청 스레드에서 해석한다. “64회 순차 왕복을 한 번으로 줄였다”는 설명은 사용하지 않는다. TTL은 55~60분으로 분산하고 `(generation, partnerId)`별 진행 중 load만 JVM 안에서 최대 1,024개 합친다. 완료/실패 후 제거하므로 혜택 데이터를 영구적으로 보관하는 L1은 아니다.

**정합성:** miss는 source DB에서 읽어 replica lag로 오래된 값을 재적재하는 일을 피한다. import commit 뒤 UUID 세대 변경과 변경 partner 삭제를 함께 수행하고, fill은 읽었던 세대가 아직 유효할 때만 허용한다. 서로 겹친 batch의 소유 데이터를 먼저 load한 뒤 기다려 교착을 막는다.

**입증:** 실제 Redis에서 MGET command 수, 기존 typed JSON/null/빈 값, key별 hit/miss/put, TTL, rollback, 다른 인스턴스의 무효화, 구세대 fill 거절, 겹친 batch·실패 후 재시도를 검사했다. 24개 scoped test 통과. HTTP TPS는 통합 비교 결과로 제시한다.

**한계:** single-flight는 JVM별이다. DB commit과 Redis 변경이 crash까지 원자적인 것은 아니다. 재시도/TTL로 회복하며 durable outbox는 추가하지 않았다. Redis Cluster로 shard를 나누려면 multi-key hash slot 구조를 다시 설계해야 한다. 여러 API 인스턴스와 Redis Cluster는 서로 다른 확장 문제다.

**면접 원고:** “적중률만 높이는 것보다 적중 한 번의 비용과 갱신 이후 어떤 값이 보이는지를 함께 봤습니다. MGET과 짧은 source transaction을 쓰고, 늦게 끝난 이전 load가 새 값을 덮지 못하도록 세대 검사를 넣었습니다. 분산 lock이나 무제한 L1을 추가하지 않고 실제 필요한 보장 범위를 명시했습니다.”

[캐시 근거·운영 경계](map-benefit-cache-2026-09-11.md)

## 사례 5. connection을 놓는 시점과 탈락 후보를 거르는 시점

**문제:** 주변/검색/mobile은 DB 조회 후 Redis·DTO 조립까지 transaction을 유지했다. 모바일은 상세 DTO를 만든 다음 반경/통신사에서 탈락시켰고 keyword preview는 full detail DTO를 한 번 더 거쳤다.

**선택:** orchestration을 transaction 밖으로 옮기고 실제 repository 조회만 read-only 5초로 제한했다. 필요한 관계는 fetch join으로 가져온다. 모바일 반경은 혜택 조회 전에, 통신사는 최종 상세 DTO 생성 전에 적용한다. keyword preview는 선택된 Store에서 직접 preview를 만든다. 후보 수/지역 분산/거리·통신사 의미는 유지한다.

**입증:** 모바일와 공통 StoreService 66개 scoped test에서 통신사 null/unknown/공통 혜택, 반경 799.95m/800.05m, 검색 중심과 사용자 위치 분리, 0 좌표 거리 계약을 검사했다. 실제 HTTP 모바일 표본도 기존 data와 같았다.

**별도 성장 문제:** 혜택 목록은 favorite를 policy에 직접 조인해 같은 혜택을 부풀린 뒤 DISTINCT로 복구했다. 이를 정렬용 상관 count로 바꾸고 countQuery의 favorite join을 제거했다. 420조건 동등성, 10만 favorite fixture의 중간 join 행 **300,000→1,200**을 확인했다. 이 수치를 현재 운영 지도 TPS나 DB 읽기량 250배 감소로 설명하지 않는다.

[모바일 검증](map-benefit-cache-2026-09-11.md) · [favorite 성장 검증](benefit-favorite-fanout-2026-09-11.md)

## 사례 6. 정상 경로 외에 긴 대기와 반복 게시도 제한

**선택:** 동일 snapshot이면 전체 2.50MB JSON/객체 graph를 재생성하지 않고 version에 연결된 검증 시각만 갱신한다. 바뀐 값·대표점과 Redis eviction은 전체 게시한다. snapshot/MV/일반 scheduler를 분리하고 query/network timeout을 적용한다. Redis/ES의 YAML 설정이 실제 client에 연결되지 않았던 부분을 고치고 지도 ES socket 3초, Kakao 연결/응답 2초/3초 제한을 적용했다. 공개 지도 JSON은 gzip을 지원한다.

**입증:** SQL 525개 조합의 snapshot 동등성, 동일 값 4회 재검증에도 builds/installs 각 1회, 다중 인스턴스·version 역전·eviction·만료·응답 body stall을 검사했다. 단기 timeout 테스트 수치는 운영 p95가 아니며 background 작업마다 정확한 전체 wall-time 상한을 보장하는 것도 아니다.

**면접 원고:** “요청이 빠른 상황만 측정하지 않고 외부 응답이 멈추거나 갱신 작업이 길어질 때의 대기도 확인했습니다. snapshot 검증 시각과 데이터 변경 시각을 나눠 동일 데이터의 재전송을 줄였고, 원본 집계가 언제 최신인지와는 구분했습니다.”

[배경 작업·타임아웃 검증](map-background-latency-boundaries-2026-09-11.md)

## 자료 교체 원칙

- 기존 고정 좌표·hot-cache TPS, 다른 인원/반경/응답 상한/실행기로 얻은 수치를 한 개선 그래프에 연결하지 않는다.
- 제한된 서울 random 시험은 그 범위의 회귀 근거로 보존하고, 현재 대표 자료는 전국 입력을 사용한 동일 조건 전후 비교로 교체한다.
- 과거 SQL·정합성·메모리 보유 구조 검증은 각각의 질문에 답하는 근거다. 좌표 성능 시험의 한계 때문에 다른 검증까지 폐기하지 않는다.
- “캐시로 전국 모든 요청이 DB를 안 탄다”는 표현은 쓰지 않는다. 정상 cluster snapshot은 DB/Redis 없이 계산하지만 preview는 위치 후보 SQL을 실행하고 혜택만 Redis에서 재사용한다.
- 검증 조건과 개선되지 않은 경우를 각 수치 옆에 둔다. 현 로컬 성능을 클라우드 운영 수치로 표현하지 않는다.
