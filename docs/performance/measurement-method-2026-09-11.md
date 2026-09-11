# 전국 이동 검색 비교 방법

## 현재 테스트 범위: 웹 전용

2026-09-11 후속 지시에 따라 앞으로 모바일 API를 성능 테스트에서 제외한다. 생성기는 기본적으로 preview 50%, cluster level 5/7/10 각 10%, keyword 10%, nearby 10%의 웹 전용 입력을 만든다. 기존 모바일 5% 슬롯은 웹 nearby로 대체한다.

아래는 이미 완료한 **모바일 포함 혼합 시험의 재현 기록**이다. 기존 JSON·TSV·수치를 변경하지 않으며 이를 재현할 때만 `--include-mobile`을 사용한다. 후속 변경의 [웹 전용 중간 실험](web-preview-renewal-2026-09-11.md)과 [회귀 수정 후 재검증](web-preview-regression-2026-09-11.md)은 별도로 기록했다. 웹 기본 입력은 현재 compact 경로이며 `--legacy-previews`로 같은 좌표의 이전 list 경로를 재현한다. 기존 지도 전용 시험은 처음부터 모바일을 포함하지 않았으므로 그 범위의 근거로 계속 사용한다.

## 검증하려는 주장

좌표가 바뀌는 요청에서도 SQL·JPA 결과 변환·혜택 캐시·응답 조립을 개선하면 HTTP 응답이 빨라지는지 확인한다. 과거 고정 좌표 적중 시험의 TPS를 대표값으로 사용하지 않는다. 이전 자료에는 서울 5개 중심 주변에 무작위 변위를 준 시험도 있으므로 모든 과거 실험이 고정 좌표였다고 설명하지도 않는다.

이번 기준점은 이미 compact 응답, cluster JVM snapshot, typed Redis 역직렬화 개선을 포함한 `86cdc70`이다. 최초 프로젝트 코드와 비교한 결과가 아니다. 최초 개발부터 현재까지의 전체 배수를 만들지 않는다.

## 환경 통제

| 항목 | 동일하게 맞춘 조건 |
|---|---|
| 호스트 | Apple M3 Pro, 12 logical CPU, RAM 36GiB, macOS 15.7.7 |
| API | SDKMAN Java 17.0.19, packaged JAR, `-Xms512m -Xmx1g` |
| 발생기 | SDKMAN Java 11.0.27, 표준 HTTP1.1 client, `-Xms256m -Xmx1g` |
| DB | 동일 local source의 독립 복제본 2개, PostgreSQL 18.4 / PostGIS 3.6.4, 매장 73,715개 |
| 인덱스 | baseline DB는 기존 schema, improved DB만 신규 Flyway migration 적용; 양쪽 restore 후 ANALYZE |
| Redis | 전용 standalone Redis 8.4, 256MiB 상한, allkeys-lru, 매 실행 전 전용 DB 초기화 |
| API 설정 | `local,loadtest`, source pool 10 / replica pool 20, keep-alive 최대 100,000회 |
| 배경 작업 | AI seed/sync와 MV refresh 비활성화, cluster snapshot 동기화/재검증 활성화 |
| 측정 | 동시 요청 500, think time 0, warmup 20초 + 발행 60초 + 남은 요청 완료 |
| 본 비교 압축 | `Accept-Encoding: identity`; gzip 전송 크기는 별도 확인 |

API·DB·Redis·Elasticsearch·발생기가 같은 컴퓨터의 자원을 공유한다. 원격 네트워크, 클라우드 사양, 브라우저 JSON 해석·렌더링 시간은 포함하지 않는다. **500개 동시 요청의 로컬 비교이며 운영에서 500명의 실제 행동이나 최대 수용량을 입증하지 않는다.**

## 입력 분포

[`generate-map-corpus.py`](../../scripts/loadtest/generate-map-corpus.py)에 `--include-mobile`을 지정하면 당시와 같은 seed `20260911`로 200,000개의 서로 다른 중심 좌표와 URI를 만든다.

- 80%: 활성 오프라인 혜택을 가진 전국 매장 73,083곳 중 선택한 좌표에서 위·경도 각각 ±0.004도 이동한다. 실제 선택된 서로 다른 매장은 64,791곳이다.
- 20%: 위도 33.1~38.5, 경도 126~129.6 직사각형에서 균등 추출한다. 바다·매장 없는 위치를 포함한다.
- 입력은 운영 로그로 추정한 사용자 분포가 아닌 합성 분포다. 그룹별 빈 응답 비중은 별도 HTTP 300개 표본에서 측정한다. 이 표본 비율을 전체 부하의 정확한 비율로 대체하지 않는다.

| 요청 그룹 | 입력 비중 | 조건 |
|---|---:|---|
| compact preview | 50% | bounds 반높이 0.003/0.006/0.012/0.022도, 반너비 1.25배, limit 300 |
| cluster level 5 / 7 / 10 | 각 10% | bounds 반높이 0.035 / 0.15 / 0.65도 |
| keyword preview | 10% | 스타벅스/GS25/CU/편의점/카페/없는 검색어/한 글자/이디야 |
| nearby preview | 5% | 반경 400/800/1,500/3,000m |
| mobile aggregate | 5% | 같은 반경 + SKT/KT/LGU |

Preview/cluster는 전체·푸드·생활/편의·쇼핑·문화/여가를 섞는다. 키워드는 ES와 DB 보충 경로 모두 포함한다. 외부 Kakao 주소 조회나 인증·AI 요청은 부하에 포함하지 않는다. bounds는 합성 시험의 크기이며 실제 화면 픽셀 크기에서 계산한 Kakao level의 정확한 viewport라고 주장하지 않는다.

## 반복·계측

1. 응답 300개를 전후 각 API에서 실제 조회한다. timestamp만 제외한 envelope와 data를 비교한다. 무작위 후보 추출·동일 거리 정렬의 기존 비결정성은 별도 설명한다.
2. 전/후 → 후/전 → 전/후 순서로 실행한다. 각 실행마다 API를 재기동하고 전용 Redis를 초기화한다. DB OS page cache를 강제로 비우지는 않으며 순서를 교대해 순서 효과를 줄인다.
3. 3회 seed는 `20260911`, `20260912`, `20260913`이다. 각 쌍에서 동일 입력과 seed, 예열을 사용한다. 발생기는 전체 그룹이 공유하는 섞인 입력 순서를 소비하므로 빠른 cluster 전용 worker가 혼합 처리량을 독점하지 않는다.
4. warmup과 measurement 통계를 분리하고, 측정 중 **발행 수 = 완료 수 = 서로 다른 URI 수**, corpus 순환 0을 확인한다. 각 실행이 사용한 순서 prefix 길이는 처리량에 따라 다르다. 전후가 정확히 같은 개수의 요청을 처리한 실험은 아니다.
5. 응답 body를 끝까지 읽는다. 오류·timeout도 지연 통계에 포함하고 HTTP 오류율과 성공 TPS를 함께 기록한다. 별도 업무 오류 검사는 앞의 JSON 정확성 검사로 수행한다.
6. 전체·그룹별 평균, p50/p95/p99, 성공 TPS, 완료 수, body bytes, 실제 입력 coverage를 저장한다. p95/p99는 10µs 최소/약 1% 폭 histogram 상한이다. 서로 다른 실행의 p95를 평균해 전체 p95로 부르지 않는다.
7. Prometheus를 2초마다 관측하고 전후 counter와 Redis commandstats도 보존한다. counter 구간은 warmup과 계측용 HTTP도 포함하므로 측정 window의 요청 수와 같다고 간주하지 않는다.

발생기는 closed-loop다. 완료 후 다음 요청을 보내므로 응답이 느려지면 도착률도 낮아진다. 수치는 특정 고정 도착률의 open-loop SLO나 coordinated-omission 보정된 사용자 tail이 아니다. 프로파일러를 켠 실행은 headline TPS 비교에서 분리한다.

## 재현

### 현재 웹 지도만 분리한 보조 비교

혼합 입력에서 keyword/nearby/mobile을 제외한 viewport endpoint만 별도로 비교한다. 같은 생성기로 500,000개를 생성한 뒤 preview/cluster를 선택한 **400,000개 고유 좌표**를 사용한다. preview 62.5%, cluster level 5/7/10 각각 12.5%다. API/JVM/DB/Redis 조건은 같고 발생기 heap만 양쪽 모두 2GiB, warmup 15초 + 발행 30초로 지정한다. 세 쌍의 실행 순서와 seed는 혼합 시험과 같다.

초기 160,000개 지도 입력은 improved 두 번째 실행에서 한 바퀴를 소진했다. 검사기가 이 실행을 거부했으며 초기 지도 시험 **전부**를 탐색 결과로 보존하고 최종 비교에서 제외했다. 입력 수를 늘린 뒤 전후 세 쌍을 모두 다시 측정했다. 보조 비교의 짧은 구간과 실행 편차는 각 trial 수치로 공개한다. 혼합 시나리오와 작업 비율·시간·발생기 heap이 다르므로 두 시나리오의 TPS를 전후 개선률로 연결하지 않는다.

혼합 trial의 개선 JAR에서 이후 gzip bulk write와 import의 누락 partner 무효화 처리만 추가 보완했다. identity 부하 경로에는 영향이 없다. 각 측정에 사용한 JAR hash와 후속 보완 범위는 evidence에 따로 기록하고, 최종 JAR은 추가 정확성/압축 검사에 사용한다.

발생기 코드와 자체 검사: [MapLatencyRunner](../../scripts/loadtest/MapLatencyRunner.md). 기존 nGrinder 결과와 발생기가 다르므로 절대 TPS를 직접 연결하지 않는다.

전용 복제 DB에서 다음 읽기 전용 SQL로 `anchors.json`을 내보낸다. 원본 좌표 export의 hash가 다르면 같은 seed여도 같은 corpus가 아니므로 새 실험으로 표시한다.

```sql
SELECT json_agg(t) FROM (
  SELECT s.storeid AS id, s.latitude::float8 AS lat, s.longitude::float8 AS lng
  FROM store s
  WHERE s.active = TRUE AND s.latitude BETWEEN 33 AND 39
    AND s.longitude BETWEEN 124 AND 132
    AND EXISTS (
      SELECT 1 FROM benefit b JOIN benefitcarrierpolicy p ON p.benefitid = b.benefitid
      WHERE b.partnerid = s.partnerid AND COALESCE(b.active, TRUE)
        AND COALESCE(p.active, TRUE) AND p.usagetype IN ('offline', 'both')
    )
  ORDER BY s.storeid
) t;
```

```sh
python3 scripts/loadtest/generate-map-corpus.py --anchors anchors.json --output mixed.tsv --include-mobile
/Users/eddie/.sdkman/candidates/java/11.0.27-tem/bin/javac -d output/runner scripts/loadtest/MapLatencyRunner.java
/Users/eddie/.sdkman/candidates/java/11.0.27-tem/bin/java -Xms256m -Xmx1g \
  -cp output/runner MapLatencyRunner --base-url http://127.0.0.1:18080 \
  --corpus mixed.tsv --concurrency 500 --warmup 20 --duration 60 --seed 20260911 \
  --timeout 10 --accept-encoding identity --output result.json
```

API에 연결한 DB·Redis 주소를 실행 전 확인하고, 공유/운영 Redis를 초기화하지 않는다. 신규 인덱스를 baseline DB에 적용하면 비교를 오염시킨다. DB 덤프·환경 변수·API 비밀값을 포트폴리오에 배포하지 않는다. 저장소에는 작은 비식별 결과 JSON만 보존하며 JAR·corpus·원본 프로파일은 로컬 `output/performance-renewal-2026-09-11/`에 둔다.
