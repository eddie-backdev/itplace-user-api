# 지도 배경 작업과 외부 호출의 지연 제한

> 2026-09-11, user-api 변경. 운영 배포 결과나 TPS 개선 수치가 아닌 구현·회귀 검증 기록이다.

## 문제와 변경

### 동일 집계의 반복 게시

기존 snapshot은 30초마다 DB 집계와 대표점을 읽고 새 UUID, 전체 JSON, 타일 인덱스를 만들었다. 데이터가 같아도 Redis 전체 payload를 다시 쓰고 다른 인스턴스도 새 세대를 내려받았다. 기존 로컬 데이터의 payload 약 2.50MB는 진단 당시 데이터 크기이며, 새로운 성능 측정값은 아니다.

이제 SQL 결과의 순서를 고정하고 현재의 immutable region 목록과 **전체 값**을 비교한다. 같으면 기존 graph와 `version`·`createdAt`·`data`를 그대로 두고 Redis의 `verifiedAt`, `verifiedVersion`만 갱신한다. 달라졌을 때만 전체 JSON과 타일 인덱스를 만든다. 값 비교에 집계·카테고리·대표점 좌표가 모두 들어가므로 MV 갱신 시각이나 `max(updated_at)`만 보는 방식의 변경 누락을 피한다.

- Redis 키는 `map-cluster-snapshot:v1`을 유지한다. 새 Hash 필드는 `verifiedAt`, `verifiedVersion` 두 개다.
- `createdAt`은 실제 데이터 세대 생성 시각, `verifiedAt`은 그 버전의 내용을 DB와 비교한 시각이다.
- Lua는 현재 version과 검증 대상 version이 같고 시각이 전진할 때만 heartbeat를 받아들인다. 새 전체 게시 역시 마지막 유효 검증 시각보다 이전이면 거부한다.
- 읽기는 version·생성 시각·검증 시각·검증 version·payload를 원자적으로 가져온다. heartbeat의 version이 현재 version과 다르면 생성 시각을 사용한다.
- 구버전은 세 필드만 쓰므로 혼합 배포 때 이전 heartbeat를 새 데이터의 검증 근거로 재사용하지 않는다. 구버전이 주기적으로 전체 게시를 계속하면 반복 전송 절감 효과는 제한된다.
- 새 인스턴스는 한 번 전체 payload를 받고 같은 version의 검증 시각만 추적한다. graph와 검증 시각은 하나의 volatile 상태로 교체해 요청에서 서로 다른 세대가 섞이지 않는다.
- Redis가 재시작되거나 키가 evict되면 동일한 로컬 데이터도 다시 게시한다. 검증이 멈추면 60초 최대 유효 기간 이후 DB fallback을 유지한다.
- `map.cluster.snapshot.revalidations`는 전체 재빌드 없이 검증만 끝낸 횟수다. 기존 `map.cluster.snapshot.age.seconds`는 마지막 유효 검증 이후의 경과 시간이다.

**남는 비용:** 30초마다 DB SQL 실행·행 읽기·category JSON 해석과 비교는 여전히 수행한다. 이 변경은 동일 graph 재생성, 전체 JSON 직렬화, Redis 재전송, 다른 인스턴스의 반복 설치를 줄인다. 요청 TPS 또는 SQL 실행 횟수가 그만큼 개선됐다고 주장하지 않는다. DB 변경 버전 테이블/트리거는 추가하지 않았다.

### 배경 작업의 스케줄러와 DB 제한

기본 단일 scheduler를 공유하면 긴 MV 갱신이 5초 snapshot 동기화를 막을 수 있었다. `mapSummaryScheduler`, `mapSnapshotScheduler`, 나머지 작업의 `taskScheduler`를 각각 한 스레드로 분리했다. 스레드 수는 고정되어 있고 요청별 실행기나 대기열을 추가하지 않는다.

- MV refresh statement: 기본 45초 query timeout. 메타데이터·advisory lock 조회는 최대 5초.
- MV 작업 connection: 기본 50초 network timeout. 사용 후 원래 값 복원.
- Snapshot SQL: 기존 5초 query timeout에 10초 connection network timeout 추가. 사용 후 복원.
- MV 실패 시 갱신 완료 시각을 기록하지 않고 advisory lock을 해제한다. 해제 SQL 자체가 실패하면 session lock이 남은 connection을 pool에 되돌리지 않도록 abort한다.

타임아웃은 statement/connection 작업의 제한이며 전체 job의 정확한 벽시계 상한은 아니다. 커넥션 획득 대기, 여러 statement의 실행 시간은 별도다. MV 작업과 snapshot 재검증은 동일 advisory lock을 유지한다. 장기 refresh 동안 scheduler가 살아 있어도 검증 lock을 얻지 못하면 기존 최대 유효 기간 이후 fallback할 수 있다.

### 외부 조회

Elasticsearch의 기존 `connectTimeoutMs`와 `socketTimeoutMs`가 실제 RestClient 설정에 연결되지 않았던 문제를 수정했다. 연결 풀 대여 대기도 connect timeout을 사용한다. transport를 Spring bean으로 등록해 종료 때 닫는다. 지도 검색은 같은 transport/연결 pool을 재사용하는 별도 client wrapper에 request options를 지정해 기본 socket timeout을 3초로 제한한다. 공유 indexing/RAG client의 30초 제한은 유지한다. 지도 msearch는 검색 필드와 후보 수(100/200)를 유지하면서 `_source`를 `storeId`만 받도록 제한한다. source 누락/유효하지 않은 ID는 버리고 두 검색 결과 간 중복 제거·실패 시 기존 DB fallback을 유지한다.

Kakao 역지오코딩은 설치된 Spring `JdkClientHttpRequestFactory`와 Java 17 HttpClient를 사용한다. 기본 연결 2초, 응답 3초 제한을 적용하며 시간 초과는 기존 빈 주소 fallback으로 처리한다. 현재 Spring 6.2.8의 factory는 응답 헤더 뒤 body가 멈춰도 timeout에 body를 닫는다. 별도 무제한 주소 캐시는 추가하지 않았다.

## 설정

| 환경 변수 | 기본값 | 범위 |
|---|---:|---|
| `ELASTICSEARCH_CONNECT_TIMEOUT_MS` | 2000 | ES 연결·연결 풀 대여 대기 |
| `ELASTICSEARCH_SOCKET_TIMEOUT_MS` | 30000 | 공유 ES client socket 대기; 기존 YAML의 값 유지 |
| `MAP_SEARCH_SOCKET_TIMEOUT_MS` | 3000 | 지도 msearch 전용 socket 대기; 공유 pool/transport 재사용 |
| `KAKAO_LOCAL_CONNECT_TIMEOUT` | 2s | 역지오코딩 연결 |
| `KAKAO_LOCAL_READ_TIMEOUT` | 3s | 역지오코딩 응답 수신 |
| `MAP_CLUSTER_SUMMARY_REFRESH_QUERY_TIMEOUT_SECONDS` | 45 | MV 갱신 statement |
| `MAP_CLUSTER_SUMMARY_REFRESH_NETWORK_TIMEOUT_MS` | 50000 | MV 작업 connection |
| `MAP_CLUSTER_SNAPSHOT_NETWORK_TIMEOUT_MS` | 10000 | snapshot 작업 connection |
| `REDIS_TIMEOUT` | 2s | 공통 Redis command; Redis 설정 수정 lane과 함께 적용 |
| `REDIS_CONNECT_TIMEOUT` | 2s | 공통 Redis 연결 |

지도 msearch socket 기본값은 3초, 공유 ES socket 기본값은 30초다. socket 대기 제한은 커넥션 획득/연결/DB fallback까지 포함한 검색 전체 응답의 정확한 3초 상한은 아니다. 운영에서 이 제한에 자주 걸리면 성공 요청의 실제 지연과 ES 상태를 보고 조정한다. 압축은 별도 public 지도 응답 범위에서 적용하며 이 작업에서 전역 JSON 압축은 켜지 않았다.

## 재현 가능한 검증

관련 테스트:

- `MapClusterSnapshotCacheTest`: 기존 SQL과 525개 level/category/bounds·임의 이동 조합 동등성, 다중 인스턴스, 동시 reader, 변경된 대표점, Redis 장애·eviction, 크기 상한, 미래 시각 거부, DB lock 해제, 구버전 세 필드 호환성, version이 다른 heartbeat 거부.
- 동일 데이터 네 번 재검증 후 `builds=1`, `installs=1`, `revalidations=4`, Redis `data`·`version` 불변을 확인한다. 새 인스턴스도 생성 시각이 오래된 동일 payload를 유효한 검증 시각으로 읽는다.
- `MapRegionStoreSummaryRefreshServiceTest`: timeout 설정, 실패 시 완료 시각 미기록, lock 해제와 connection timeout 복원.
- `StoreSearchServiceImplTest`: source 필터, ID 후보 순서/중복, null source, 잘못된 ID, 하위 검색 실패 처리.
- `ElasticsearchConfigTest`: 로컬 HTTP 서버 응답을 멈춰 설정한 200ms socket timeout이 실제 client에 적용되는지 확인. 추가로 지도 msearch만 200ms, 공유 client는 5초로 설정해 지도 호출이 먼저 실패하고 공유 client는 1.5초 늦은 정상 응답을 계속 받는지 검증한다. 두 client가 동일 transport를 쓰고 원래 옵션이 바뀌지 않는지도 확인한다.
- `KakaoLocalAddressClientTest`: 응답 헤더 전과 body 수신 중 각각 멈춘 로컬 HTTP 서버에서 200ms 제한으로 fallback하는지 확인.

Java 17 + Docker Testcontainers에서 위 scoped suite를 실행한다. 실행 로그와 XML은 `output/map-latency-improvements-2026-09-11/boundaries/`에 보관한다. 200ms는 테스트를 위한 값이며 운영 권장값이나 실측 p95가 아니다. 전체 서비스 검증·무작위 좌표 전후 부하 수치는 통합 검증 자료에서 별도로 다룬다.
