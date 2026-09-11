# 지도 혜택 캐시와 모바일 후보 처리 개선 — 2026-09-11

지도 요청마다 여러 제휴사의 혜택을 Redis에서 읽는 비용을 줄이고, 혜택 변경 후 오래된 데이터가 다시 캐싱되는 경쟁 조건을 정리했다. 이 문서는 baseline `86cdc70` 이후의 **구현·정합성·Redis 명령 수 검증**을 기록한다. HTTP TPS·응답시간 개선율이나 클라우드 처리량의 측정 자료로 사용하지 않는다. 과거 동일 좌표 반복 시험의 수치도 이 변경의 성과로 재사용하지 않는다.

## 1. 제휴사별 비동기 GET을 MGET으로 묶기

기존 [PartnerBenefitCacheService](../../src/main/java/com/itplace/userapi/map/service/PartnerBenefitCacheService.java)는 제휴사마다 Redis GET을 비동기로 발행한 다음, 요청 스레드에서 raw bytes를 JSON으로 해석했다. 따라서 제휴사 N개를 조회하는 비용을 **N회 순차 네트워크 왕복**으로 설명하면 틀리다.

개선 후 standalone Redis에서는 세대 marker와 제휴사 key들을 **MGET 한 번**으로 읽는다. Redis command와 future 수를 줄이며, JSON 해석은 계속 요청 스레드에서 수행한다. 같은 제휴사의 혜택 JSON을 요청마다 역직렬화하는 비용은 남아 있다.

- 기존 `partner-benefits::<partnerId>` key와 설정된 prefix를 유지한다. 단일 제휴사 조회도 같은 batch 경로를 쓴다.
- 기존 typed JSON bytes와 Spring Cache의 binary null 표현을 읽을 수 있다. 빈 목록과 cached null은 혜택 없는 cache hit로 처리한다.
- marker가 없는 최초 요청은 UUID를 SETNX로 초기화하고 MGET을 다시 수행한다. 항상 전체 Redis 명령이 한 번이라는 뜻은 아니다.
- miss는 DB에서 읽은 뒤 generation fence가 있는 Lua로 한 batch를 적재한다. 정상 hit 경로에는 이 fill 명령이 없다.

### 명령 수와 캐시 통계의 구분

[CacheConfig](../../src/main/java/com/itplace/userapi/common/redis/CacheConfig.java)의 `CacheStatisticsCollector`를 RedisCacheWriter와 batch 조회 코드가 공유한다. hit/miss/put/delete는 계속 **제휴사 key 단위**로 기록하며, marker는 혜택 조회 통계에 포함하지 않는다.

실제 Redis 8.4에서 제휴사 4개를 조회한 회귀 테스트의 결과는 다음과 같다. 입력의 중복 ID는 먼저 제거했다.

| 항목 | 결과 |
|---|---:|
| 조회하는 제휴사 | 4개: 값 있음 / 빈 목록 / cached null / miss |
| Redis MGET | 1회, marker를 포함한 key 5개 |
| 제휴사별 GET | 0회 |
| miss fill의 Lua 내부 GET | 1회, 세대 확인용 |
| cache get / hit / miss / put | 4 / 3 / 1 / 1 |

Redis `commandstats`에서 읽은 명령 수와 애플리케이션의 `cache.gets`가 서로 다른 단위를 나타낸다는 점을 유지해야 한다. 이 테스트는 처리량 개선 배수를 산출하는 부하 시험이 아니다.

## 2. 동시 miss와 만료 분산

혜택 TTL은 기존 1시간 상한을 늘리지 않고 **55~60분** 범위에서 분산한다. 한 번에 적재한 여러 제휴사가 같은 시각에 만료되는 현상을 줄이기 위한 정책이며, 지도 좌표별 응답 캐시의 TTL과는 별개다.

같은 JVM 안에서는 진행 중인 `(generation, partnerId)` load만 최대 **1,024개** 공유한다. 먼저 자신이 소유한 partner들을 batch로 읽고 완료시킨 뒤 다른 요청의 future를 기다린다. 예를 들어 `[1,2]`를 읽는 요청과 `[2,1,3]`을 읽는 요청이 겹쳐도 두 번째 요청은 자신이 소유한 `[3]`을 먼저 완료하므로 상호 대기를 만들지 않는다.

완료 또는 실패한 항목은 즉시 제거한다. 완료된 혜택을 계속 보관하는 L1 cache를 추가하지 않았으며, registry 상한을 넘는 miss는 직접 읽는다. 이는 진행 중인 중복 작업을 줄이는 장치다. 분산 lock, 전체 DB 동시 실행 수 제한, 모든 cold miss의 중복 제거를 보장하지 않는다.

## 3. source 읽기와 import 이후 무효화

캐시 miss는 source DB의 짧은 `REQUIRES_NEW` transaction에서 혜택·통신사 정책·등급 혜택을 **최대 3개 batch query**로 읽고 DTO까지 만든다. 현재 [DataSourceRouter](../../src/main/java/com/itplace/userapi/common/db/DataSourceRouter.java)는 `readOnly=false`일 때 source를 선택한다. import 직후 replica가 아직 이전 데이터를 가지고 있어도 그 데이터를 새 혜택 캐시에 적재하지 않도록 source를 사용한다.

Redis 조회·역직렬화·fill과 다른 요청의 future 대기는 이 DB transaction 밖에서 수행한다. transaction timeout은 5초, follower wait는 10초다. 커넥션 획득 대기와 여러 외부 작업의 시간은 별도이므로 이를 API 전체의 5초 또는 10초 상한으로 설명하지 않는다.

[BenefitImportServiceImpl](../../src/main/java/com/itplace/userapi/benefit/service/BenefitImportServiceImpl.java)은 변경된 제휴사를 수집해 **DB commit 이후** 캐시를 무효화한다. 혜택의 이전·새 partner와 전체 스냅샷에서 누락되어 비활성화된 policy의 partner도 포함한다. rollback이면 무효화하지 않는다.

무효화와 fill의 경쟁은 Redis Lua로 처리한다.

1. 요청이 MGET으로 현재 UUID 세대와 제휴사 값을 함께 읽는다.
2. miss는 source DB에서 읽는다.
3. import commit 이후 무효화 Lua가 새 UUID를 설정하고 변경 partner key를 함께 삭제한다. 변경 없는 partner key는 유지한다.
4. fill Lua는 요청이 읽었던 UUID와 현재 UUID가 같을 때만 적재한다. 구세대 load가 늦게 끝나도 새 캐시를 덮어쓰지 못한다.

다른 API 인스턴스도 같은 Redis marker를 읽는다. 같은 JVM의 새 세대 요청 역시 진행 중인 구세대 future에 합류하지 않는다. import와 겹쳐 이미 시작된 구세대 요청 자체는 기존 데이터를 반환할 수 있으며, fence는 그 결과가 최신 캐시를 오염시키는 것을 방지한다.

이미 적용된 스냅샷의 `DUPLICATE` 재시도에서도 전체 partner를 다시 무효화한다. DB commit 뒤 Redis 단계만 실패한 경우와, 제휴사 이동으로 현재 carrier에서 사라진 이전 partner의 캐시까지 복구하기 위한 처리다. `STALE` 요청은 추가 무효화하지 않는다.

## 4. Redis 설정을 실제 연결에 반영

host/port/SSL만 전달하던 사용자 정의 factory를 제거하고 [RedisConfig](../../src/main/java/com/itplace/userapi/common/redis/RedisConfig.java)에서 Spring Boot가 생성한 factory를 주입받는다. username/password/database/SSL과 Lettuce의 timeout·pool 등 설정이 표준 경로로 적용된다.

[application.yml](../../src/main/resources/application.yml)의 기본 command timeout은 `REDIS_TIMEOUT=2s`, connect timeout은 `REDIS_CONNECT_TIMEOUT=2s`다. 환경 변수로 조정할 수 있다. 개별 연결·명령의 제한이며 전체 요청의 절대 시간 상한은 아니다.

## 5. 검증과 재현

SDKMAN Java 17과 로컬 Docker를 사용했다. Testcontainers를 위한 `JAVA_TOOL_OPTIONS=-Dapi.version=1.44` 설정은 테스트 실행 환경에만 적용했다.

```sh
./gradlew test --tests '*PartnerBenefitCache*Test' --tests '*CacheConfigTest' --tests '*RedisConfigTest' --tests '*BenefitImportServiceImplTest'
```

캐시 관련 **24개 테스트가 통과했고 failure/error/skipped는 모두 0**이다. 실행 시각·클래스별 결과는 [캐시 검증 JSON](evidence/2026-09-11/cache/results.json)에 보관했다.

이 scoped 실행 뒤 최종 검토에서, partner 또는 partnerId가 없는 과거 혜택의 누락 policy도 정상 비활성화하고 캐시 무효화 대상에서만 제외하도록 보완했다. 해당 회귀 테스트 1개를 추가했고 최종 전체 398개 검사에 포함해 통과했다. [최종 통합 검증](evidence/2026-09-11/final-tests.json).

- [실제 Redis 회귀 테스트](../../src/test/java/com/itplace/userapi/map/service/PartnerBenefitCacheRedisTest.java) 5개: MGET commandstats, 기존 JSON/null/prefix, 요청 스레드의 역직렬화, key 단위 통계, 신규 key의 TTL, commit/rollback, 다른 인스턴스의 무효화, 늦은 fill 거절, 겹치는 cold batch, 실패 후 재시도를 검증한다.
- [설정 테스트](../../src/test/java/com/itplace/userapi/common/redis/RedisConfigTest.java)는 Boot가 실제 생성한 factory의 인증·DB 번호·SSL·1.8초 command/0.9초 connect timeout 적용을 검증한다. 이 두 시간은 검증용 입력이며 배포 기본값이 아니다.
- [import 테스트](../../src/test/java/com/itplace/userapi/benefit/service/BenefitImportServiceImplTest.java)는 누락 policy의 partner 포함과 DUPLICATE 재시도의 전체 partner 무효화를 검증한다.

## 6. 모바일에서 늦은 필터와 중복 계산 제거

[StoreServiceImpl.findNearbyForMobile](../../src/main/java/com/itplace/userapi/map/service/StoreServiceImpl.java)은 반경 밖 후보를 **혜택 캐시 조회와 상세 응답 생성 전에** 제외한다. 통신사에 맞는 tier만 남기고, 유효한 통신사를 지정했을 때 혜택이 없는 후보도 제외한다. [MobileMapServiceImpl](../../src/main/java/com/itplace/userapi/mobile/map/service/MobileMapServiceImpl.java)은 이 결과를 마커로 변환하며, 반경 계산과 통신사 파싱·후보 필터를 다시 실행하지 않는다.

관련 **66개 테스트가 통과했고 failure/error/skipped는 모두 0**이다. 이 숫자는 `StoreServiceImplTest` 58개와 `MobileMapServiceImplTest` 8개의 합이며 새로 추가한 테스트 66개를 뜻하지 않는다. [모바일 검증 JSON](evidence/2026-09-11/cache/mobile-filter-results.json)에 결과를 보관했다.

검증한 경계는 null/empty/unknown/`ALL` 통신사의 기존 전체 조회 동작, 공백·소문자 통신사 입력, 통신사 공통인 null-carrier tier, 비일치·무혜택 후보 제외, 799.95m/800.05m 반경 경계다. 표시용 거리의 반올림이나 사용자 위치가 지도 중심의 반경 필터를 대신하지 않는 것도 확인했다. partner 검색 분기의 유효한 `userLat=0` 또는 `userLng=0` 입력은 기존 실제 거리 계산 결과를 유지한다.

## 운영과 포트폴리오에서 명시할 한계

- **여러 API 서버:** Redis 세대는 공유하지만 single-flight는 JVM별이다. 여러 JVM의 동시 cold miss, 서로 다른 세대, registry 상한 초과와 load 완료 직후 경쟁에서는 중복 DB 읽기가 가능하다.
- **Redis Cluster:** 현재 MGET/Lua는 standalone Redis를 전제로 한다. Redis를 shard로 나누면 multi-key Lua의 hash-slot 제약을 고려한 namespace·배치 설계가 필요하다. API 서버 수를 늘리는 것과 Redis Cluster 전환은 구분한다.
- **DB commit과 Redis 사이의 장애:** 두 시스템 사이에 원자 transaction이나 durable outbox를 추가하지 않았다. commit 직후 프로세스가 죽거나 Redis 무효화가 실패한 뒤 재시도가 없으면 TTL까지 오래된 key가 남을 수 있다. 실패를 숨기지 않고 동일 import 재시도로 복구할 수 있게 했다.
- **혼합 배포:** 구버전 API의 unfenced writer는 새 세대 검사를 하지 않는다. 구버전 인스턴스가 남아 있는 동안 그 writer의 재삽입까지 막을 수 없다. 전체 인스턴스 전환 후 무효화·재적재를 확인해야 한다.
- **캐시 방식:** generation fence 보장은 현재 standalone/fixed-TTL 경로에 해당한다. 대체 non-Redis cache와 TTI fallback은 기본 Cache 동작을 유지한다.
- **성과 수치:** 이 문서의 테스트는 계약과 구현 메커니즘의 증거다. MGET으로 줄인 명령 수를 곧바로 같은 비율의 TPS 증가 또는 지연 감소로 환산하지 않는다. 다양한 좌표·검색 입력을 사용한 동일 조건 HTTP 전후 시험으로 별도 평가한다.
