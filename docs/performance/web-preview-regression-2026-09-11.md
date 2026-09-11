# 웹 지도 속도 회귀 수정 후 재검증

수정판 `df4ff46`은 같은 조건에서 새로 측정한 기준 `b9401d8` 대비 **TPS 1,014.43→1,140.37(+12.42%), 평균 488.88→435.71ms(-10.88%)**를 기록했다. 전후 3쌍 모두 기준보다 빨랐고, 294,697개 측정 요청에서 오류와 각 측정 구간 내 URI 재사용은 0이었다.

앞선 `7cc730f` 변경은 응답 크기와 후보 누락을 개선했지만, 속도 목표를 충족하지 못했다. 웹 전용 500개 동시 요청 3쌍에서 기준 `b9401d8` 대비 TPS가 975.22→949.09(-2.68%), 평균 응답이 508.08→522.42ms(+2.82%)로 나왔다. 이 결과를 속도 개선 완료로 취급하면 안 된다. [당시 원본 결과](web-preview-renewal-2026-09-11.md)는 그대로 보존한다.

이번 후속 검증은 회귀 원인을 분리해 수정한 JAR를 기준판과 다시 비교한다. 불리했던 기존 결과를 삭제하거나 수정판 결과로 덮어쓰지 않는다. 모바일은 계속 부하에서 제외한다.

## 수정·검증 범위

키워드 후보 SQL의 MATERIALIZED UNION에 추가된 넓은 정규화 문자열과 중복 제거 작업을 조사했다. 동일 DB의 교대 EXPLAIN으로 temp spill과 실행 시간 증가를 확인하고, streaming `NOT MATERIALIZED`와 서로 중복되지 않는 `UNION ALL`로 수정했다. `IS NOT TRUE`를 사용해 NULL인 조건도 이전 UNION의 포함 의미와 맞춘다.

이어 PostgreSQL이 반복 실행 후 generic plan을 선택하면서 검색어별 성능 차이가 생기는 것을 확인했다. 키워드 검색의 짧은 DB transaction에서만 `SET LOCAL plan_cache_mode = force_custom_plan`을 적용한다. 전역 설정을 바꾸지 않고 transaction이 끝나면 설정이 해제된다. 이 판단의 독립 SQL 실행계획·반환 ID 증거는 [SQL 회귀 조사](web-preview-sql-regression-2026-09-11.md)에 정리한다.

정확한 우선순위와 거리가 같을 때에는 storeId ASC로 순서를 고정한다. 따라서 과거의 비결정적 LIMIT 경계에서 다른 동일 거리 지점이 선택될 수 있다. 실제 HTTP 비교에서 차이가 발생하면 추가·제거된 지점의 모든 필드를 저장하고, 우선순위·거리·좌표와 경계를 확인한 뒤에만 동률 차이로 분류한다. 일반적인 정렬 오류나 정보 손실을 동률로 설명하지 않는다.

웹의 상세 지도 limit 300과 level 5 이상 클러스터 전환은 유지한다. 다만 응답이 요청 limit에 도달하면 확장 영역 전체를 받은 것으로 볼 수 없으므로, 그 응답을 영역 전체의 coverage로 재사용하지 않도록 수정했다. `storeResponse.data.stores.length < limit`인 경우에만 기존 coverage를 보관하고, 상한에 도달하면 다음 영역 조회가 서버를 다시 확인한다. 별도 프론트 회귀 테스트가 300개 미만의 재사용과 300개 도달 시 재조회, limit 300 유지를 확인한다. 프론트는 계약·회귀 4개와 ESLint/build를 통과했다. 이 UI 요청 재사용 보정은 서버 부하 발생기에 포함되지 않으므로 아래 API 속도 결과의 원인으로 계산하지 않는다.

## 기준·환경·재현 조건

- `baseline.jar`: `b9401d8fe1d61ac6ca25684a41847fa389844698`, SHA256 `6985b8059031d285a8fee7800ef76216324f45f83d89d2b9429a10dd52d40bf2`.
- `rejected.jar`: `7cc730f73567d083e43b105f4b3e0a64d7b0e88a`, SHA256 `3e85ddb2014dc6116b80462b65bbd4111596e39eb1323a5e239a3e1fec9be77b`. 원본을 보존하고 후속 비교의 수정판으로 사용하지 않는다.
- 같은 로컬 dump를 두 임시 DB에 각각 복원하고 ANALYZE했다. store73,715 / partner425 일치. 기준 DB에는 기준 JAR migration0001, 비교 DB에는 migration0002까지 적용했다. 원본·운영 DB는 변경하지 않았다.
- 임시 DB: `itplace_web_reg_base_20260911`, `itplace_web_reg_test_20260911`. 전용 Redis 최대256MiB/allkeys-lru. API18090, Redis16389는 localhost 전용 실험 자원이다.
- API SDKMAN Java17/heap512MiB~1GiB, 발생기 SDKMAN Java11/heap256MiB~1GiB, source pool10/replica pool20. HTTP identity/HTTP1.1 keep-alive. 같은 로컬 호스트에서 실행한다.
- 기존 웹 전용 입력200,000개를 바이트 단위로 보존했다. preview50%, cluster level5/7/10 각10%, keyword10%, nearby10%, 모바일0%. 80% 매장 좌표 주변,20% 대한민국 포함 사각 영역 균등 난수의 합성 부하다.
- 기준/수정 입력은 같은 좌표·query parameter·순서이고 keyword/nearby endpoint의 list/compact 형식만 다르다. 각 JAR는 고정 복사하고 source 변경·Gradle·프론트 빌드와 부하를 겹치지 않는다.
- 같은 seed의 전후3쌍,500개 closed-loop 동시 요청,warmup20초/측정45초. 순서는 before1→corrected1→corrected2→before2→before3→corrected3. 각 측정 중 corpus 순환0과 issued=completed=고유URI를 검사한다.
- TPS는 완료 수/합산 drain 포함 시간, 평균은 완료 요청 가중 평균, p95/p99는 회차별 범위다. p95/p99는 발생기의 최대 약1% 폭 histogram 버킷 상한값이다. 고정 도착률 또는 클라우드 운영 수용량 검증이 아니다.

단독 rejected JFR 1회도 검토했지만 실행하지 않았다. 동일 DB의 교대 EXPLAIN으로 원인을 분리할 수 있었고, rejected만 프로파일링하면 기준 대비 회귀를 분리할 수 없으므로 수정판 검증과 전후 비교를 우선했다. 준비된 JFR 스크립트를 실행 결과로 인용하지 않는다.

원시 JAR·TSV·로그·실행계획과 요약은 `output/web-preview-regression-2026-09-11/`에 보존한다.

## 실제 HTTP 계약 확인

수정 source commit은 `df4ff46`, 프론트 coverage 수정은 `eb48e13`이다. 검증된 수정 JAR의 SHA256은 `6ad52161d346a5945cfd01cd1fb8f21f52a908b73dc0d63d75b73f3b29abbdeb`다. 전체 `./gradlew test build`에서 423 tests/85 suites, 실패·오류·skip 0을 확인한 JAR를 고정 복사했다.

보존한 `7cc730f` 실제 HTTP 응답과 같은 171개 입력(keyword 32개, viewport 139개)을 비교했다. 모든 요청이 200이고, 모든 지점 집합 및 공통 5,273개 지점의 모든 복원 필드가 일치했다. viewport 139개와 keyword 30개는 순서도 같았다.

나머지 keyword 2개는 `다` 검색에서 24/25번째의 GS25다정제일점(ID 13666)과 파리바게뜨 세종다정점(ID 5199)이 5199→13666 순으로 고정된 차이였다. 두 지점은 **정확한 좌표 36.495773/127.243605가 같고**, 둘 다 strict 브랜드 일치가 아닌 매장명 일치 그룹이므로 우선순위도 같다. 지점 추가·삭제나 필드 변경은 없었다. 같은 좌표로 반올림 이전 거리도 같음을 확인했으며, 단순히 표시 거리가 같은 것만 보고 동률로 분류하지 않았다. 이는 LIMIT 경계 교체가 아닌 내부 두 항목의 동률 정렬 보정이다.

`corrected-parity-summary.json`, `corrected-order-differences.json`, `corrected-tie-validation.json`에 결과와 개별 증거를 보존했다. 정확성 요청은 TPS 분자·분모에 포함하지 않는다.

## 새 기준과 수정판의 속도 비교

이번 새 측정에서 기준 138,842건, 수정판 155,855건을 완료했다. **합계 294,697건, 오류 0건**이다. 각 회차에서 `issued = completed = distinct_request_paths_issued`, corpus 순환 0을 확인했다. 전후 API source/JAR와 입력을 고정했고, 측정 후에도 source main tree 해시가 같음을 확인했다.

| 지표 | 새 기준 `b9401d8` | 수정 `df4ff46` | 변화 |
|---|---:|---:|---:|
| 성공 TPS | 1,014.43 | 1,140.37 | +12.42% |
| 평균 응답 | 488.88ms | 435.71ms | -10.88% |
| 회차별 p95 범위 | 913.98~923.12ms | 803.08~819.22ms | 모든 회차 감소 |
| 회차별 p99 범위 | 1,160.51~1,160.51ms | 1,009.60~1,040.20ms | 모든 회차 감소 |
| 요청당 평균 body | 34,804 B | 21,712 B | -37.62% |
| 완료 요청 | 138,842 | 155,855 | 오류 각각 0 |

| 회차 | 기준 TPS / 평균 | 수정 TPS / 평균 |
|---|---:|---:|
| 1 | 1,011.96 / 490.37ms | 1,144.39 / 434.31ms |
| 2 | 1,008.50 / 491.58ms | 1,132.64 / 438.68ms |
| 3 | 1,022.84 / 484.75ms | 1,144.10 / 434.19ms |

기존 실패 실험의 기준 975.22TPS와 이번 수정 1,140.37TPS를 합쳐 개선율을 계산하지 않는다. 이전 실험의 949.09TPS보다 회복했다는 것만으로 성공을 판정하지도 않는다. 위 개선율은 **이번에 새로 실행한 기준 3회와 수정판 3회**만 사용한 결과다.

| endpoint 그룹 | 기준 평균 | 수정 평균 | 기준 / 수정 평균 body |
|---|---:|---:|---:|
| viewport compact | 500.66ms | 448.24ms | 26,962 / 27,151 B |
| keyword | 813.73ms | 710.98ms | 43,913 / 13,535 B |
| nearby | 692.40ms | 619.81ms | 162,903 / 60,522 B |
| cluster 5 | 293.50ms | 261.44ms | 2,423 / 2,430 B |
| cluster 7 | 293.34ms | 261.67ms | 4,689 / 4,681 B |
| cluster 10 | 293.19ms | 262.02ms | 769 / 769 B |

그룹별 수치는 별도로 단일 endpoint만 부하를 준 결과가 아닌, 같은 혼합 부하 안에서 측정한 평균이다. 변경하지 않은 클러스터의 시간도 함께 줄었으므로 전체 개선을 클러스터 내부 로직이 빨라진 것으로 설명하지 않는다. 또한 처리량이 달라 실제 소비한 corpus prefix 길이도 다르다. 평균 body 감소는 혼합 부하의 관찰값이며, 동일 입력 자체의 크기 비교는 앞선 104개 list/compact 복원 검증을 함께 본다.

## 연결 대기 관찰과 남은 한계

| replica 연결 작업 1회 기준 | 새 기준 | 수정 |
|---|---:|---:|
| 획득 대기 평균 | 206.58ms | 184.60ms |
| 연결 보유 평균 | 23.27ms | 20.86ms |
| 연결 timeout | 0 | 0 |

Prometheus counter 전후 차이의 합계에서 `sum / count`로 계산했다. 이 범위는 **발생기 준비, warmup 20초, 측정 45초, drain, 배경 작업을 포함**한다. 순수 측정 요청만의 SQL 지연이 아니고, 연결 보유 시간도 순수 DB 실행 시간과 같지 않다. 요청마다 연결 획득 횟수가 다를 수 있으므로 API 평균 중 DB가 차지하는 비율로 환산하지 않는다.

SQL 독립 실험의 spill·plan 개선과, 실제 HTTP 혼합 부하의 속도·연결 대기 감소가 같은 방향으로 관찰됐다. 다만 코드 변경별 TPS 기여도를 하나씩 분해한 대조 실험은 아니므로 `UNION ALL만으로 +12.42%` 또는 `force_custom_plan만으로 +12.42%`라고 쓰지 않는다.

로컬 45초 측정 3쌍에서 재현한 개선이다. 클라우드 사양·장시간 부하·실제 사용자 요청 분포·브라우저 렌더링·고정 도착률 시험은 포함하지 않는다. 요청마다 달라지는 좌표를 사용했지만 synthetic workload이므로 운영 500명 수용 보장으로 해석하지 않는다. 키워드 평균 약711ms 등 남은 지연도 있으므로 모든 핵심 API의 속도 문제가 해결됐다고 주장하지 않는다.

## 증거와 정리

저장소에 보존한 [전체 비교](evidence/2026-09-11/web-preview-regression/comparison-summary.json), [HTTP 동등성](evidence/2026-09-11/web-preview-regression/corrected-parity-summary.json), [동률 검증](evidence/2026-09-11/web-preview-regression/corrected-tie-validation.json), [pool 지표](evidence/2026-09-11/web-preview-regression/pool-summary.json), [423개 테스트](evidence/2026-09-11/web-preview-regression/tests.json), [JAR·source 근거](evidence/2026-09-11/web-preview-regression/corrected-manifest.json), [자원 정리](evidence/2026-09-11/web-preview-regression/cleanup.json)를 연결한다. 아래 파일의 원본은 `output/web-preview-regression-2026-09-11/`에 있다.

- `baseline-manifest.json`, `rejected-manifest.json`, `corrected-manifest.json`: 각 JAR와 source commit·해시.
- `baseline-summary.json`, `corrected-summary.json`, `comparison-summary.json`: 전후 3쌍의 가중 결과와 그룹·회차 상세.
- `baseline-1/2/3.json`, `corrected-1/2/3.json`: 발생기 원시 결과와 각 측정의 고유 URI·오류 집계.
- `corrected-parity-summary.json`, `corrected-order-differences.json`, `corrected-tie-validation.json`: 171개 실제 HTTP 계약 검증과 정확한 동률 근거.
- `pool-summary.json`: warmup 포함 범위를 명시한 연결 획득·보유 집계.
- `web-baseline.json`, `web-corrected.json`, `environment.json`, `initialized-clones.json`, `setup.json`: 입력·환경·데이터 준비 근거.
- `diagnostic-decision.json`: JFR 생략과 formal 비교 완료 상태. 실행하지 않은 프로파일을 결과로 인용하지 않는다.
- `cleanup.json`: API 18090 정지, 소유한 임시 DB 두 개와 전용 Redis 제거 확인. 원본 DB·공유 Redis·운영 서버는 보존했다.

원시 JAR·DB dump·대형 TSV·HTTP 응답·로그는 git에 넣지 않는다. 작은 요약·재현 메타데이터와 문서는 저장소에 보존해 리뷰와 포트폴리오 수치 추적에 사용한다. 앞선 실패 3쌍도 삭제하지 않는다.
