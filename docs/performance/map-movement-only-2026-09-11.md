# 지도 이동 전용 소규모 진단 · 2026-09-11

## 범위 정정

사용자가 확인하려던 것은 지도 이동의 매장 조회 성능이다. 이전 [웹 혼합 진단](map-root-causes-2026-09-11.md)에 키워드·주변 검색을 포함하고, 그 결과로 키워드 개선을 우선 제시한 판단은 범위를 벗어났다. 혼합 시험의 수치는 당시 조건의 기록으로 보존하며 지도 이동만의 병목이나 개선 우선순위로 사용하지 않는다.

현재 대상은 정상 지도 이동 시 호출하는 다음 두 API다.

| 지도 동작 | 허용 API |
|---|---|
| level 1~4의 개별 매장 | `/api/v1/maps/stores/in-view/previews/compact` |
| level 5 이상의 지역 집계 | `/api/v1/maps/stores/in-view/clusters` |

키워드·주변 검색·모바일 API는 0건이어야 한다. 프론트의 `searchInMapBounds`가 이 분기를 사용한다. 실제 브라우저의 드래그·주소 라벨 조회·마커 렌더링·coverage 재사용 시간을 측정한 것은 아니며, 서로 다른 화면 영역의 매장 API를 직접 호출한 진단이다. [프론트 호출 경로](/Users/eddie/dev/ITPLACE/itplace-user-front/src/features/mainPage/hooks/useStoreData.ts:629).

## 재발 방지 변경

- `generate-map-corpus.py`의 기본 입력을 preview와 cluster만 생성하도록 변경했다. 기본 비율은 preview 62.5%, cluster level 5/7/10 각 12.5%다. 실제 사용자 줌 분포를 주장하는 비율은 아니다.
- 기존 웹 혼합은 `--historical-mixed`를 명시해야 생성한다. 과거 모바일 입력 재현 옵션도 현재 진단에서는 사용하지 않는다.
- `MapLatencyRunner`의 기본값은 동시 요청 1개·측정 5초·예열 1초다. 500명 시험은 개선 구현과 소규모 확인 이후에 수행한다.
- 기본 입력의 허용 URL·cluster level·키워드/반경 파라미터 부재·고유 좌표·seed 재현성을 검사하는 작은 회귀 검사를 추가했다.

## 실행 조건

실행 서비스는 이전과 동일한 `df4ff46` corrected JAR다. 서비스 구현이나 SQL을 변경한 시험이 아니다. API SDKMAN Java 17·발생기 SDKMAN Java 11, API heap 512MiB~1GiB, replica 풀 20, HTTP identity, 전용 로컬 DB·Redis를 사용한다.

동시 요청 **1·5·10개**, 각 예열 1초·측정 5초, 단회 3개만 실행한다. 같은 API 프로세스 상태를 이어받으며 50/200/500명 시험과 프로파일링은 추가하지 않는다.

이번 재확인은 기존 web corpus에서 두 지도 API만 골라낸 160,000개 URL을 사용한다. preview 62.5%·cluster 37.5%이며 SHA-256은 `35d9ad7f66d294875a6020178d6bca78c7902fe45de7daa57e5cf4b5ff97f070`이다. 원래 20슬롯 중 균등 좌표 슬롯 4개가 모두 지도 그룹에 있어, 필터 후 분포는 매장 anchor 주변 75%·균등 직사각형 25%다. 새 생성기의 기본 80%/20%와 같다고 표현하지 않는다.

API 비중과 공간 분포가 이전 혼합 시험과 다르므로 두 시험의 TPS 차이를 코드 개선률로 계산하지 않는다. 또한 전체 corpus 안의 비율과 각 5초 동안 실제 소비한 비율을 구분한다.

## 결과와 해석

| 동시 요청 | 완료 / 오류 | TPS | 평균 | p95 |
|---|---:|---:|---:|---:|
| 1개 | 1,343 / 0 | 268.59 | 3.701ms | 9.033ms |
| 5개 | 10,291 / 0 | 2,056.70 | 2.410ms | 5.772ms |
| 10개 | 23,507 / 0 | 4,698.40 | 2.106ms | 5.226ms |

측정 합계 **35,141건**, 오류 0, 각 측정 내 URI 재사용·corpus 순환 0이다. 예열·회차 사이에는 같은 URI가 다시 등장할 수 있다. 1→5→10 순서로 같은 API를 사용해 뒤 회차에 캐시/JIT 예열 효과가 포함될 수 있으며, 서로 다른 동시성의 TPS 차이를 개선 배수로 설명하지 않는다.

10개 동시 요청에서 preview 평균은 3.039ms, cluster level 5/7/10 평균은 약 0.554/0.573/0.554ms다. 클러스터만 높은 비율로 완료된 결과가 아니라 실제 preview 비율도 62.34%다. 이 소규모 구간에서는 과거 혼합 부하에서 봤던 수백 ms 지연과 큰 DB 연결 대기를 재현하지 못했다. 따라서 그 혼합 결과를 근거로 현재 지도 이동이 느리다고 결론내리지 않는다. 5초 단회가 운영 용량·최대 TPS·지속 부하 성능을 증명하는 것은 아니다.

실제 Spring HTTP counter에는 **두 허용 지도 URI만** 증가했다. 키워드·주변 검색·모바일은 모두 0건이며, 예열을 포함한 HTTP 39,391건이 발생기 집계와 정확히 일치했다. [원본 결과와 경로별 평균](evidence/2026-09-11/map-movement-only/summary.json), [서버 HTTP·DB 풀 검증](evidence/2026-09-11/map-movement-only/verification.json).

Replica 평균 연결 획득 대기는 회차별 0.0023/0.0034/0.0017ms, 보유는 2.998/2.080/1.876ms, timeout 0이었다. 이는 예열·측정·drain을 포함한 연결 작업당 counter다. 짧은 대기는 계측의 ms 해상도에 영향을 받으며 시작/종료 pending 0만으로 구간 최대값이 0이었다고 주장하지 않는다.

## 검증

`GenerateMapCorpusSelfTest.py`가 기본 지도 전용 경로·level·고유성·seed·과거 입력 옵션을 통과했다. 별도 동일 seed/anchor 80개 표본에서 과거 web/legacy/mobile 모드의 TSV가 수정 전 생성기와 byte 단위로 같았다. SDKMAN Java 11의 `MapLatencyRunnerSelfTest.py`도 localhost stub에서 집계·timeout·keep-alive를 통과했다. 이 검사는 실제 서비스에 추가 부하를 보내지 않는다.

서비스 실행 코드 변경이 없어 Gradle 전체 테스트·build를 다시 실행한 것으로 보고하지 않는다. 현재 변경 대상은 측정 도구와 진단 문서다.

## 정리

전용 API 18090·임시 DB `itplace_map_movement_20260911`·전용 Redis를 정리하고 기존 컨테이너 보존을 확인했다. [정리 증거](evidence/2026-09-11/map-movement-only/cleanup.json). 운영 DB·배포·push는 수행하지 않았다. 큰 원본 corpus·prom·재현 스크립트는 `output/map-movement-only-2026-09-11/`에 보존한다.
