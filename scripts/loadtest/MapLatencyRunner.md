# 지도 응답시간 측정기

Java11 표준 라이브러리만 사용하는 **closed-loop HTTP 부하 발생기**다. 고정 좌표 반복 대신 TSV의 다양한 요청을 seed로 섞어 순환한다. 캐시·DB·API·직렬화가 포함된 실제 HTTP 응답을 끝까지 읽으며, JSON 전체를 byte 배열로 만들거나 성공 요청을 매번 기록하지 않는다.

## 준비와 실행

현재 성능 테스트는 웹 API만 대상으로 하며 모바일 aggregate는 제외한다. 기존 `mixed.tsv`에는 모바일 5%가 있으므로 새 웹 전용 입력을 생성해 사용한다. 기본 비중은 preview 50%, cluster level 5/7/10 각 10%, keyword 10%, nearby 10%다.

```sh
python3 scripts/loadtest/generate-map-corpus.py --anchors output/performance-renewal-2026-09-11/anchors.json --output output/web-map-corpus.tsv
```

`--include-mobile`은 과거 실험의 입력 재현용이다. 원본 결과에서 mobile 행만 빼서 웹 전용 재측정 결과로 해석하지 않는다. 같은 서버 자원을 공유하던 부하 자체가 달라지기 때문이다.

API는 SDKMAN Java17, 이 발생기는 SDKMAN Java11을 사용한다. 전후 비교 시 같은 JAR 실행 방법·API JVM/DB 설정·발생기 JVM·corpus·seed·HTTP 압축·warmup·concurrency·duration을 유지한다.

```sh
JAVA_HOME=/Users/eddie/.sdkman/candidates/java/11.0.27-tem
"$JAVA_HOME/bin/javac" -d output/map-latency-runner scripts/loadtest/MapLatencyRunner.java
"$JAVA_HOME/bin/java" -cp output/map-latency-runner MapLatencyRunner --self-test
JAVA_HOME="$JAVA_HOME" python3 scripts/loadtest/MapLatencyRunnerSelfTest.py

"$JAVA_HOME/bin/java" -Xms256m -Xmx1g -cp output/map-latency-runner MapLatencyRunner \
  --base-url http://127.0.0.1:18080 \
  --corpus output/web-map-corpus.tsv \
  --concurrency 500 --duration 60 --warmup 20 \
  --seed 20260911 --timeout 10 --accept-encoding identity \
  --output output/map-result.json
```

첫 검사는 shuffle/histogram/집계/원격 주소 보호를 검사한다. 두 번째 검사는 임시 localhost HTTP 서버에 concurrency2만 사용해 정상/503/headers 지연/body 지연/keep-alive/전체 요청 집계를 확인하고 종료한다. 실제 서비스 부하는 보내지 않는다.

`--base-url`, `--corpus`, `--output`이 필수다. 나머지 위 값은 기본값이다. `--warmup 0`은 warmup을 생략한다. `--accept-encoding gzip`도 지원하며 전후에 동일하게 지정한다. localhost/127.0.0.1/::1 외 주소는 명시적인 `--allow-remote` 없이는 거부한다. redirect는 따라가지 않는다. base-url은 경로·인증정보·query가 없는 origin이어야 한다.

TSV에는 헤더 없이 `group<TAB>request-path`를 기록한다. `#` 시작 행과 빈 행은 무시한다. 요청은 GET이며 경로는 `/`로 시작해야 한다. query의 한글·예약 문자는 일반 URL 규칙대로 인코딩한다. 인증 token을 corpus에 넣지 않는다.

```text
preview	/api/v1/maps/stores/in-view/previews/compact?minLat=37.49&maxLat=37.51&minLng=126.99&maxLng=127.01&limit=300
cluster5	/api/v1/maps/stores/in-view/clusters?minLat=37.4&maxLat=37.7&minLng=126.8&maxLng=127.2&mapLevel=5
```

위는 형식 예시이며 실제 endpoint·parameter는 현재 OpenAPI와 호출 결과로 확인한 corpus를 사용한다. 위 코드 블록의 `\t`는 실제 파일에서 TAB 문자다. 동등한 행을 여러 번 넣으면 해당 요청의 가중치가 올라간다. 그룹 비율은 행 수 비율이며 모든 slot은 같은 전체 순서표를 소비한다.

## 결과 해석

- HTTP1.1 keep-alive, 동시에 최대 concurrency개를 발행한다. 완료한 slot이 다음 요청을 보낸다. 같은 seed는 Fisher–Yates 순서표를 재현하며 전체 corpus를 끝내면 같은 순서로 순환한다. 실제 도착 순서는 응답 완료 시점에 따라 달라진다.
- warmup은 별도 seed(`seed XOR 0x5DEECE66D`)와 별도 통계를 사용한다. measurement는 seed 순서의 처음부터 다시 시작한다. HTTP connection pool은 두 단계가 공유한다.
- latency는 `sendAsync` 직전부터 **응답 body 전체 완료 또는 실패**까지다. 요청 timeout과 별도의 전체 body deadline을 적용하며, body가 중단되면 subscriber를 취소한다. connect/header/body timeout도 errors·latency에 포함한다. JSON 안의 업무 오류는 파싱하지 않으므로 별도 정확성 검사가 필요하다.
- `outcomes`는 HTTP 상태 또는 예외 종류다. 2xx 이외 상태를 오류로 집계한다. 전체·그룹의 `completed`, `errors`, `successful_tps_including_drain`을 함께 본다. 실패를 빨리 반환해 올라간 TPS를 성공 처리량으로 해석하지 않는다.
- 발행 window가 끝나면 새 요청을 멈추고 이미 발행한 요청을 모두 집계한다. `completed_tps_including_drain`/`successful_tps_including_drain`의 분모는 **drain까지 포함한 실제 경과시간**이다. `completed_tps_within_window`는 window 안에 완료한 수/window 시간이며 drain 결과를 포함하지 않는다.
- p50/p95/p99는 10µs부터 시작하는 로그 histogram의 bucket 상한이다. 10µs 초과에서 bucket 폭은 최대 약1%; 예를 들어 실제100ms가 최대 약101ms로 표시될 수 있다. 평균/min/max는 실제 nanosecond 측정값으로 계산한다. percentiles는 오류/timeout도 포함한다. `completed=0`인 그룹의0ms 값은 측정값이 없다.
- `body_bytes_completed`는 끝까지 읽은 응답 body bytes이며 HTTP header와 실패한 응답의 일부 body는 포함하지 않는다. gzip이면 압축된 body bytes다. 압축 해제/JSON 파싱/브라우저 렌더링 시간은 포함하지 않는다.
- `corpus_sha256`, seed, `corpus_entries`, `corpus_group_entries`와 실제 `distinct_corpus_indices_issued`, `distinct_request_paths_issued`, 전체 순환 수를 저장한다. corpus의 행 수와 실제 다양한 좌표를 읽은 수를 혼동하지 않는다. 서로 다른 path가 같은 좌표를 뜻할 수 있으므로 **좌표 다양성은 corpus 생성기의 별도 통계로 증명**한다.
- 발생기는 고정 크기 executor(max32 threads)와 HTTP selector를 사용한다. 입력별 HttpRequest를 미리 만들어 보관하므로 메모리는 corpus 크기에 비례한다. 최대 heap/Java 버전/CPU 수/thread 수를 JSON에 기록한다.

이 도구는 **fixed arrival/open-loop 부하를 만들지 않는다**. 응답이 느려지면 요청 발행률도 낮아지므로 측정 tail을 임의의 실제 사용자 도착률에 대한 p95/SLO로 설명할 수 없다. 공유 호스트 CPU/네트워크/발생기 한계도 서버 한계와 분리해 분석해야 한다. portfolio에는 원본 corpus 생성 규칙, 전국/인구 밀집/희소 지역 비중, category·검색·모바일 비중, 적중/미적중 구분, 반복 전후 결과와 오류율을 함께 남긴다.
