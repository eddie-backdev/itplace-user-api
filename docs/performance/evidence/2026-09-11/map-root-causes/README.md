# 잔여 지연 진단 근거

실행 코드 `df4ff46`, 문서 조사 시작 HEAD `30d3ada`. [통합 보고서](../../../map-root-causes-2026-09-11.md)의 근거다. 새 최적화의 전후 비교가 아니다.

- `existing-500-summary.json`: 기존 corrected 3회 원시 telemetry를 다시 집계한 결과. gauge의 전체 표본 배열은 생략했으며 기간·최소/최대·시간 가중 평균·counter 차이를 보존한다.
- `code-findings.md`: 현재 코드의 경로, 논리적 DB/ES/Redis 호출 수, 후보 규모, 정합성 경계와 파일 위치.
- `small-summary.json`, `small-web-{1,5,10}.json`, `small-cluster-1.json`: 사용자 후속 지시에 따라 각 5초만 실행한 소규모 진단. 각 측정은 URI 재사용·순환 0, 오류 0이다. 단회·짧은 예열이며 같은 API 상태를 이어받으므로 운영 용량이나 정식 전후 비교로 쓰지 않는다.
- `execution-log.json`, `cleanup.json`: 소규모 전환 당시 완료/중단/미실행 구간, 실제 소비 API 비율과 전용 환경 정리. 새 500명 시험과 thread dump는 실행하지 않았다.
- `analyze-existing.py`: 기존 원시 결과를 읽는 표준 라이브러리 스크립트. 경로별 클라이언트/Spring 요청 수가 일치하지 않으면 assertion으로 중단한다. 서비스나 DB에 접속하지 않는다.

재분석 예시(user-api 루트):

```sh
python3 docs/performance/evidence/2026-09-11/map-root-causes/analyze-existing.py \
  output/web-preview-regression-2026-09-11 \
  output/map-root-cause-reanalysis
```

입력은 `corrected-{1,2,3}.json`, `corrected-{1,2,3}-metrics.jsonl`, 각 회차 `before.prom`/`after.prom`이다. 큰 원본은 로컬 output에 보존한다. 재실행 산출물은 표본 배열을 포함하므로 여기의 축약 JSON보다 크다.

측정 내부는 발생기에 정확한 phase wall timestamp가 없어 `started_at + warmup wall`에서 양 끝 1초를 제외하고 선택한 표본 구간이다. 기존 45초 전체 측정이나 예열+측정 전체의 HTTP 집계와 같은 창으로 표현하지 않는다. 클라이언트/Spring 평균 차이는 관측 경계 밖 시간이며 직접 측정한 Tomcat queue 시간이 아니다.
