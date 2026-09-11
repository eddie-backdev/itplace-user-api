# 웹 지도 표시 적격성·혜택 응답 중복 개선 검증

이번 변경은 2026-09-11의 앞선 병목 개선이 적용된 `b9401d8`을 기준으로, 웹 지도 읽기 경로의 정규화 반복과 응답 중복을 줄인다. 매장·제휴사명 정규화와 업종 검증을 DB의 쓰기 시 계산 및 조회 조건으로 이동해 후보 LIMIT 이전에 적용하고, 주변·카테고리·검색에도 제휴사·혜택을 한 번만 보내는 compact 응답을 제공한다.

모바일은 이번 부하 시험에서 제외한다. 기존 [전국 성능 검증](README.md)의 mobile 5% 혼합 결과를 웹 결과로 바꾸어 쓰지 않는다. 주변 조회의 무작위 샘플 정책과 요청 전체의 과부하 제한은 이번 변경 범위가 아니다.

## 구현과 계약

표시 적격성은 완전히 저장된 boolean flag가 아니다. 매장·제휴사 이름과 업종의 정규화 문자열을 generated column으로 저장해 쓰기 시 갱신하고, 현재 제휴사와의 관계 조건은 읽기 SQL에서 검사한다. 후보 LIMIT 이전에 부적격 매장을 제외하므로, 과거처럼 LIMIT을 채운 뒤 Java에서 탈락시켜 표시 수가 불필요하게 줄어드는 문제도 함께 줄인다. 매장명 변경이나 제휴사 변경을 영구 JVM 캐시의 별도 무효화에 의존하지 않는다.

새 웹 경로는 `/nearby/previews/compact`, `/nearby/category/previews/compact`, `/nearby/search/previews/compact`다. 각 응답의 `stores`는 지점별 좌표·주소·거리 등을 담고, `partners`는 제휴사별 이름·이미지·공통 혜택을 한 번씩 담는다. 요청 내부 `PreviewBenefits`로 공통 등급 혜택을 제휴사마다 한 번 준비하며, 장기 L1 캐시를 추가하지 않는다.

기존 혜택이 3개 이상일 때 매장명과 같은 혜택명을 우선 선택하는 정책도 유지한다. 필요한 매장에만 `tierBenefit` override를 넣고, 속성이 없으면 제휴사 공통 혜택을 사용한다. **빈 배열 override도 명시적 값**이므로 공통 혜택으로 대체하지 않는다. 검색의 브랜드 우선 순서, 사용자 좌표가 0일 때의 거리 0, 계산된 실제 거리도 전송 계약에 남긴다.

기존 list endpoint는 모바일·구버전 클라이언트 호환을 위해 유지한다. 웹은 compact를 복원해 기존 화면 모델로 사용하고, 서버에 새 endpoint가 없는 404/405에만 기존 endpoint로 fallback한다. 5xx 또는 잘못된 응답을 숨기는 fallback이 아니다.

## 300개 샘플링 문제의 우선순위

현재 웹의 일반 viewport 조회는 level 1~4에서 limit 300을 요청하고, level 5 이상에서는 서버 클러스터 분기로 즉시 전환한다. 상세 화면의 bounds에는 20% padding이 붙는다. 따라서 모든 지도 조회에서 대량의 주변 매장 900개를 무작위로 300개 추리는 것으로 설명하면 부정확하다. 그 경로는 주변·카테고리 및 bounds를 사용할 수 없는 fallback에 남아 있다.

앞선 로컬 데이터에서 보존한 active·오프라인 혜택 매장 좌표 73,083개로 대표 도심의 **반경 1km** 후보를 계산했다.

| 중심 | 좌표 후보 수 |
|---|---:|
| 강남역 | 219 |
| 홍대입구역 | 168 |
| 명동역 | 180 |
| 부산 서면역 | 189 |
| 대구 반월당역 | 138 |
| 대전 시청역 | 169 |
| 광주 상무역 | 101 |
| 제주 시청 | 80 |

이는 WGS84 구면 반경 계산의 참고값이다. 실제 Kakao viewport 크기, padding 후 사각 영역 또는 매칭 검증 후 표시 개수의 측정값이 아니다. 모든 위치에서 300개 미만이라는 보장은 없지만, 이번에는 조회 적격성·혜택 조립을 먼저 개선하는 판단을 뒷받침한다.

## 비교 방법

- 기준 JAR: 앞선 검증의 완성본 `output/performance-renewal-2026-09-11/improved.jar`, source commit `b9401d8fe1d61ac6ca25684a41847fa389844698`, SHA-256 `6985b8059031d285a8fee7800ef76216324f45f83d89d2b9429a10dd52d40bf2`.
- 기준/개선 DB는 보존한 동일 dump를 각각 복원한 임시 DB다. 기존 로컬 DB의 테이블·인덱스·데이터를 변경하지 않는다. 해당 JAR의 Flyway를 복제본에만 적용한다.
- 요청 200,000개: viewport compact 50%, cluster level 5/7/10 각 10%, keyword 10%, nearby 10%. 모바일 0%. 80%는 매장 좌표 주변 ±0.004도, 20%는 대한민국을 포함하는 사각 영역의 균등 난수다. 바다·빈 지역도 포함하는 합성 부하이며 실제 운영 요청 분포가 아니다.
- 두 입력은 좌표·순서·query parameter가 같고, 개선 입력에서 keyword/nearby의 endpoint에만 `/compact`를 붙인다. 따라서 개선 전과 후의 JSON 구조는 의도적으로 다르다. 공통 필드 복원 검증으로 정보 손실 여부를 따로 확인한다.
- API SDKMAN Java 17, heap 512MiB~1GiB; 발생기 SDKMAN Java 11, heap 256MiB~1GiB. PostgreSQL source pool 10, replica pool 20. 별도 Redis 최대 256MiB, allkeys-lru. HTTP identity, keep-alive. API/DB/발생기는 같은 로컬 호스트에서 실행한다.
- 각 회차마다 API를 새로 띄우고 전용 Redis를 비운다. 500개 closed-loop 동시 요청, warmup 20초, 측정 45초. 최종 비교는 전후 3회씩 교대 순서로 수행한다. 각 측정 구간에서 corpus 순환 0, 발행 개수와 고유 URI 개수의 일치를 검사한다.
- TPS는 완료 수 / drain을 포함한 실제 시간, 평균은 완료 요청 가중 평균이다. p95는 회차별 범위로 표시한다. 고정 도착률 시험이 아니므로 운영 500명 수용 또는 SLO 보장으로 해석하지 않는다.
- 300개 기준 HTTP smoke와 별도 정확성 요청은 TPS의 분자·분모에 포함하지 않는다.

원본 증거와 재현 스크립트는 서비스의 `output/web-preview-renewal-2026-09-11/`에 보존한다. DB dump·JAR·원시 요청 응답은 git에 넣지 않는다.

## 실제 응답 계약·크기 검증

개선 JAR `7cc730f`의 실제 HTTP endpoint에 같은 입력을 순차로 보내 list를 compact에서 복원했다. 작은 반경 nearby 50개, keyword 50개, category 4개로 총 104개 질의를 검사했고, **공통 지점 2,089개의 모든 필드가 일치**했다. nearby 50개와 category 4개는 집합과 순서도 모두 같았다.

검색은 50개 중 32개가 완전히 같고, 18개는 기존 9~29개 결과에 유효한 지점을 더해 30개를 채웠다. 이 18개에서 기존 지점이 사라진 사례는 없었다. 예를 들어 `다`는 9→30개, `편의점`은 20→30개, `카페`는 29→30개였다. 이는 후보 LIMIT 뒤에 자르던 매칭 검사를 앞으로 이동한 실제 동작 차이이며, 모든 응답 집합이 동일했다고 설명하지 않는다.

| 실제 동일 입력 | legacy body 합계 | compact body 합계 | 감소 |
|---|---:|---:|---:|
| keyword 50개 | 1,934,040 B | 735,784 B | 62.0% |
| nearby 50개 | 2,358,577 B | 1,445,743 B | 38.7% |
| category 4개 | 284,343 B | 106,407 B | 62.6% |
| 전체 104개 | 4,576,960 B | 2,287,934 B | 50.0% |

HTTP identity의 실제 body 길이다. 헤더·gzip·브라우저 파싱·렌더링 비용은 포함하지 않는다. 검색의 유효 지점 수가 늘어난 사례도 위 합계에 포함했다.

별도 전국 입력 300개 전후 비교에서도 모든 공통 지점의 필드가 일치했다. cluster 95개는 전체 데이터가 같았고, viewport 139개 중 135개와 keyword 32개 중 25개는 집합·순서까지 같았다. 나머지 viewport/keyword에서는 기존 지점을 유지하면서 각각 90개/74개를 추가했다. nearby 34개 중 27개는 동일했고, 나머지 7개는 기존 무작위 샘플 정책과 후보 단계 변경으로 집합이 달랐다(추가 502개, 제거 424개, 공통 필드 차이 0). 이 비교를 모든 300개 응답의 완전 동일성 증명으로 사용하지 않는다.

실행 중 생성된 `/v3/api-docs`에서 새 compact GET 3개와 `StorePreview`의 optional `distance`, `roadName`, `tierBenefit`을 확인했다. 별도로 backend 420 tests/85 suites에서 실패·오류·skip 0, `./gradlew test build`가 통과했다. 프론트는 compact 복원 계약 테스트 3개, ESLint/build 및 mocked React 흐름 검증을 통과했다.

## 웹 전용 500개 동시 요청 결과

전후 3회씩 교대 실행한 합계는 **263,715개 측정 요청, 오류 0건**이다. 모든 회차에서 측정 중 URI 재사용과 corpus 순환은 0이다. 이번에는 **TPS 향상을 입증하지 못했고**, 로컬 가중 평균은 소폭 낮아졌다. 데이터 정확성·응답 크기 개선과 처리량 개선을 구분해야 한다.

| 지표 | 기존 `b9401d8` | 변경 `7cc730f` | 변화 |
|---|---:|---:|---:|
| 성공 TPS | 975.22 | 949.09 | -2.68% |
| 평균 응답 | 508.08ms | 522.42ms | +2.82% |
| 회차별 p95 범위 | 923.12~970.21ms | 932.35~1,029.90ms | 범위 그대로 보고 |
| 회차별 p99 범위 | 1,172.12~1,231.91ms | 1,149.02~1,360.79ms | 범위 그대로 보고 |
| 요청당 평균 body | 34,846 B | 21,754 B | -37.57% |
| 측정 완료 | 133,750 | 129,965 | 오류 각각 0 |

| 회차 | 기존 TPS / 평균 | 변경 TPS / 평균 |
|---|---:|---:|
| 1 | 962.40 / 514.70ms | 909.62 / 544.71ms |
| 2 | 1,007.74 / 492.18ms | 987.06 / 502.35ms |
| 3 | 955.60 / 518.13ms | 950.58 / 521.94ms |

실행 순서는 before1→after1→after2→before2→before3→after3이다. 모든 쌍에서 TPS가 조금씩 낮았으므로 변화를 숨기거나 단순히 잡음이라고 확정하지 않는다. 반대로 같은 호스트의 짧은 closed-loop 시험 3쌍만으로 특정 SQL의 단독 회귀 또는 운영 처리량 감소율을 확정하지도 않는다.

| endpoint 그룹 | 기존 평균 | 변경 평균 | 기존 / 변경 평균 body |
|---|---:|---:|---:|
| viewport compact | 520.31ms | 534.04ms | 27,006 / 27,206 B |
| keyword | 842.81ms | 872.16ms | 43,948 / 13,543 B |
| nearby | 721.64ms | 742.07ms | 162,819 / 60,619 B |
| cluster 5 | 304.75ms | 313.27ms | 2,417 / 2,414 B |
| cluster 7 | 304.61ms | 313.53ms | 4,686 / 4,688 B |
| cluster 10 | 305.05ms | 313.44ms | 769 / 768 B |

집계 body 크기는 각 구현이 측정 window에 완료한 입력의 평균이다. 두 구현의 처리량이 다르므로 실제 소비한 corpus prefix 길이도 다르다. 동일 입력의 응답량 감소는 앞 절의 104개 순차 비교를 사용한다. viewport에서 적격성 검사를 LIMIT 앞으로 이동해 유효 결과를 더 채운 사례도 있어, 같은 수의 지점만 출력하도록 줄인 시험이 아니다.

## 남아 있는 대기 비용과 해석

전후 Prometheus counter 차이를 합쳐 `sum / count`로 계산한 replica pool 지표는 다음과 같다.

| 연결 작업 1회 기준 | 기존 | 변경 |
|---|---:|---:|
| 연결 획득 대기 평균 | 213.90ms | 222.78ms |
| 연결 보유 평균 | 24.15ms | 25.17ms |
| 연결 timeout | 0 | 0 |

이 counter는 **발생기 준비, warmup 20초, 측정 45초, drain과 배경 작업을 포함**한다. 측정 요청만 분리한 SQL 지연도 아니고, 연결 보유 시간이 순수 SQL 실행 시간인 것도 아니다. 요청마다 연결 획득 횟수가 다를 수 있으므로 `223ms / 522ms`를 API에서 DB가 차지하는 비율로 계산하면 안 된다.

연결 획득 대기가 여전히 크고, 변경하지 않은 클러스터의 평균도 함께 늘었다. 이것은 응답 중복을 줄여도 혼합 부하에서 DB 접근과 공유 자원 대기가 남는다는 근거다. 특정 새 SQL의 단독 회귀를 입증한 EXPLAIN 비교는 이번에 수행하지 않았으므로, 원인을 확정하지 않는다. 요청 전체 제한과 무작위 샘플 정책은 사용자가 이번 범위에서 보류했다.

포트폴리오에는 **유효 후보 누락 개선 + 동일 입력 응답 body 50.0% 감소**를 이번 변경의 검증된 결과로 사용한다. “이 변경으로 TPS도 증가했다”, “모바일 제외 후 500명을 운영에서 수용한다”는 주장은 사용하지 않는다. 앞선 609→1,043 TPS 수치는 별도의 mobile 포함 과거 혼합 부하이며 이번 웹 전용 결과와 연결해 단계별 누적 개선율을 만들지 않는다.

## 재현 입력과 보존 파일

현재 `generate-map-corpus.py`의 기본값은 새 compact 경로다. `--legacy-previews`를 붙이면 이번 기준 입력을 재현한다. seed 20260911, count 200000, 앞선 `anchors.json`을 사용한다. `--include-mobile`은 과거 mobile 포함 입력을 재현하는 별도 옵션이다.

현재 생성기로 웹 두 모드의 20만 입력을 각각 바이트 단위로 동일하게 재현했다. 모바일 과거 입력도 오프라인 생성만 수행해 기존 SHA-256과 일치함을 확인했으며 모바일 API에 부하를 보내지 않았다. [입력 재현 검사](evidence/2026-09-11/web-preview/corpus-reproduction.json).

Git에는 요약 증거를 보존한다: [전체·회차별 결과](evidence/2026-09-11/web-preview/comparison-summary.json), [pool 지표](evidence/2026-09-11/web-preview/pool-summary.json), [104개 응답 비교](evidence/2026-09-11/web-preview/compact-parity-summary.json), [검색 추가 결과](evidence/2026-09-11/web-preview/compact-candidate-differences.json), [300개 전후 비교](evidence/2026-09-11/web-preview/smoke-parity-summary.json), [OpenAPI](evidence/2026-09-11/web-preview/openapi-summary.json), [환경](evidence/2026-09-11/web-preview/environment.json), [정리 결과](evidence/2026-09-11/web-preview/cleanup.json). 아래 원시 파일은 서비스의 `output/web-preview-renewal-2026-09-11/`에 보존한다.

- 기준 corpus: `output/web-preview-renewal-2026-09-11/web-baseline.tsv`.
- 변경 corpus: `output/web-preview-renewal-2026-09-11/web-improved.tsv`.
- `comparison-summary.json`, `baseline-summary.json`, `improved-summary.json`: 3쌍 가중 결과·endpoint·회차 상세.
- `compact-parity-summary.json`, `compact-candidate-differences.json`, `smoke-parity-summary.json`: 동일 입력 복원 및 후보 증가/샘플 차이.
- `pool-summary.json`: counter 범위가 명시된 연결 대기·보유 집계.
- `baseline-manifest.json`, `improved-manifest.json`, `environment.json`, `setup.json`, `corpus-reproduction.json`: JAR·데이터·환경·입력 재현 근거.
- `openapi-summary.json`: 실행된 새 경로 3개와 optional 필드 계약.
- `cleanup.json`: 소유한 임시 API/DB/Redis 정리 결과. 기존 로컬 DB·공유 Redis·운영 서버는 변경하지 않는다.
