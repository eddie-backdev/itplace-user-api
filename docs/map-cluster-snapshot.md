# 지도 클러스터 공유 snapshot

## 조회와 갱신

`/api/v1/maps/stores/in-view/clusters`는 JVM에 준비된 불변 집계에서 고정 공간 구획을 선택하고 원래 viewport 경계로 대표점을 필터링한다. 집계가 없거나 유효 시간이 지났으면 `StoreClusterQueryService`의 기존 custom-plan DB 조회를 직접 사용한다. 정상 요청은 Redis/DB 조회나 트랜잭션을 시작하지 않는다.

프런트 mapLevel 1~4의 compact preview 조회는 기존 경로를 사용한다. cluster endpoint의 bounds 정규화, 넓은 범위의 집계 단계 승격, category 정규화, clusterId와 targetMapLevel, 정렬과 응답 필드는 유지한다. 구역별 전체 매장 수를 반환하는 계약이며 화면 내부의 개별 매장 수를 다시 세지 않는다.

- 원본: PostgreSQL `map_region_store_summary` + `map_region_anchor`.
- 공용 저장: Redis Hash `map-cluster-snapshot:v1` 한 개의 `version`, `createdAt`, `data` 필드.
- 각 JVM: 전체 집계 한 벌과 LEGAL_DONG 0.1도 / TOWN 0.25도 / CITY 한 묶음의 조회 인덱스. 인덱스는 같은 구역 객체를 참조한다.
- 요청마다 결과를 만들고 반환한다. 사용자·viewport·category별 결과나 과거 세대를 JVM 컬렉션에 쌓지 않는다.

각 인스턴스는 기본 5초 간격으로 Redis metadata를 읽고 버전이 바뀌었을 때만 완성 payload를 내려받는다. payload는 전송 전에 Redis의 HSTRLEN으로 상한을 검사한다. 새 자료구조를 전부 만들고 검증한 다음 현재 참조를 교체한다.

공유 snapshot이 없거나 생성 후 30초가 지났으면 한 인스턴스가 DB 읽기 전용 트랜잭션에서 전체 집계를 만든다. 기존 MV refresh와 같은 PostgreSQL advisory lock을 사용하고 Redis 게시 완료까지 유지한다. 다른 인스턴스가 먼저 게시했는지 lock 획득 후 재확인한다. Redis Lua는 생성 시각을 비교하고 metadata/payload를 하나의 HSET으로 교체하여 지연 도착한 이전 게시가 새 데이터를 덮어쓰는 것을 막는다. 인스턴스 시간 동기화를 전제로 하며 로컬 시계보다 5초 넘게 미래인 snapshot은 사용하지 않는다.

MV 자체의 기본 5분 갱신 주기는 그대로다. snapshot은 이미 계산된 MV를 약 30초마다 복사한다. `createdAt`은 이 복사본의 생성 시작 시각이며 원본 매장 데이터의 최종 변경 시각이 아니다. 갱신 작업이 지연되면 각 JVM은 생성 후 최대 60초까지 기존 복사본을 사용하고 이후 DB로 fallback한다. 이 제한이 원본 MV의 최신성을 보장하는 것은 아니다.

공유 키는 계속 덮어쓰는 한 개이므로 TTL에 메모리 상한을 의존하지 않는다. 비활성화 후에도 마지막 Redis payload 한 개는 남을 수 있다. schema를 바꾸면 key의 버전을 바꾸고 이전 key의 정리도 계획한다.

## 장애와 메모리 경계

- 초기 기동, Redis 장애, 파싱/크기/검증 실패: 아직 유효한 JVM 집계는 계속 사용하고 만료 후 DB 직접 조회.
- 빈 정상 집계: 캐시 hit로 빈 결과를 반환. cache miss와 구별한다.
- 갱신 실패/중복: 이전 완성본 유지. fixed-delay 동기 작업으로 실행하며 별도 Future/재시도 대기열을 만들지 않는다.
- 데이터 크기: 기본 구역 20,000개, category count 100,000개, JSON UTF-8 16MiB 상한. 초과한 집계를 일부만 게시하지 않는다.
- DB 생성 쿼리: 기본 timeout 5초, 읽기 전용 transaction, 종료 시 commit/rollback으로 transaction advisory lock 해제.
- Redis Lua 게시가 성공한 세대만 로컬에 설치한다. 다른 게시자가 이겼으면 공용 완성본을 읽는다.
- 이전 집계를 쓰던 요청이 끝나고 다른 참조가 없어져야 GC 대상이 된다. 교체 즉시 삭제나 항상 두 세대 이하의 실메모리를 보장하지 않는다. JSON/DB 결과/응답 생성 중 임시 할당도 별도다.
- Spring의 기존 scheduler를 사용한다. 다른 긴 scheduled 작업으로 동기화가 지연돼도 요청은 만료 검사 후 DB로 돌아간다. scheduler 분리는 지연 지표가 이를 요구할 때 검토한다.

## 설정과 되돌리기

| 환경 변수 | 기본값 | 의미 |
|---|---:|---|
| `MAP_CLUSTER_SNAPSHOT_ENABLED` | `true` | JVM/shared snapshot 경로 활성화 |
| `MAP_CLUSTER_SNAPSHOT_POLL_INTERVAL_MS` | `5000` | 인스턴스 동기화 간격 |
| `MAP_CLUSTER_SNAPSHOT_REFRESH_INTERVAL_MS` | `30000` | 공유 복사본 재생성 간격 |
| `MAP_CLUSTER_SNAPSHOT_MAXIMUM_AGE_MS` | `60000` | 복사본 최대 사용 나이 |
| `MAP_CLUSTER_SNAPSHOT_MAX_REGIONS` | `20000` | 구역 레코드 상한 |
| `MAP_CLUSTER_SNAPSHOT_MAX_CATEGORY_COUNTS` | `100000` | category count 항목 상한 |
| `MAP_CLUSTER_SNAPSHOT_MAX_PAYLOAD_BYTES` | `16777216` | JSON byte 상한 |
| `MAP_CLUSTER_SNAPSHOT_QUERY_TIMEOUT_SECONDS` | `5` | DB 생성 쿼리 제한 |

`maximum-age-ms >= refresh-interval-ms + poll-interval-ms`를 만족해야 한다.

**되돌리기:** `MAP_CLUSTER_SNAPSHOT_ENABLED=false`로 설정하고 API를 재기동하면 기존 exact viewport Redis + DB 경로로 돌아간다. DB migration/데이터 원복은 필요 없다. 설정 변경은 재기동 시 적용되며 실시간 토글 endpoint는 제공하지 않는다.

## 관측과 검증

- `map_cluster_snapshot_requests_total{result="hit|fallback"}`: 정상 읽기와 DB fallback.
- `map_cluster_snapshot_builds_total`, `installs_total`, `failures_total`: 재생성/설치/실패.
- `map_cluster_snapshot_regions`, `json_bytes`, `age_seconds`: 현재 한 세대의 레코드 수, 직렬화 크기, 나이. JSON 크기는 실제 heap 사용량이 아니다.
- 지표의 label에 viewport나 버전을 넣지 않아 metric 자체의 항목 수도 늘어나지 않는다.

`MapClusterSnapshotCacheTest`는 실제 PostgreSQL/Redis에서 원래 repository SQL과 525개 조합을 비교하고, 두 JVM을 나타내는 별도 cache 객체의 버전 동기화, 늦은 게시 차단, MV lock 경합, 실패 시 lock 해제, 제한 초과, Redis 장애, 만료/미래 시각, 동시 읽기/교체와 정상 빈 snapshot을 검증한다. `StoreServiceImplTest`는 hit 시 DB/기존 Redis 경로 생략과 fallback 응답 계약을 검증한다.

성능은 기존 `random` 분리 VUser 방식과 `random-balanced` 순환 방식을 구분한다. 빠른 cluster가 완료 요청 대부분을 차지하는 혼합 TPS를 모든 API의 속도 배수로 해석하지 않는다. 장기 메모리는 `random-paced`의 3초 탐색 간격과 여러 실제 갱신 주기로 확인한다. 자세한 조건과 결과는 [지도 부하 테스트 가이드](map-load-testing.md)에 기록한다.
