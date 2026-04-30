# IT:PLACE Refactoring Plan

## Database

### MySQL → PostgreSQL + PostGIS 전환

**목적:** MySQL의 공간 정보 처리 능력 부족으로 인한 전환. PostGIS를 통해 더 강력한 GIS 쿼리와 공간 인덱싱 확보.

**현재 상황:**
- 모든 데이터(Embedding 제외)가 MySQL에 저장되어 있음
- `Store` 엔티티가 `POINT SRID 4326` 타입의 `location` 컬럼 사용 중
- `StoreRepository`에 MySQL 전용 함수 사용 (`ST_Distance_Sphere`, `RAND()`, `MATCH...AGAINST`)

---

### 변경 파일 목록

| 파일 | 변경 내용 |
|------|----------|
| `build.gradle` | `mysql-connector-j` → `postgresql` 드라이버 교체 |
| `src/main/resources/application.yml` | 다이얼렉트, 드라이버, 환경변수명 변경 |
| `map/entity/Store.java` | `columnDefinition` 변경 |
| `map/repository/StoreRepository.java` | 네이티브 쿼리 전체 수정 (핵심) |
| `history/repository/MembershipHistoryRepository.java` | `YEAR()`, `MONTH()` JPQL 함수 수정 |
| `recommend/repository/RecommendationRepository.java` | `DATE()` JPQL 함수 확인 및 수정 |
| `benefit/repository/BenefitRepository.java` | `CHAR(13)`, `CHAR(10)` JPQL 함수 수정 |

---

### 단계별 구현 계획

#### Step 1: build.gradle 의존성 교체

```gradle
// 제거
runtimeOnly 'com.mysql:mysql-connector-j'

// 추가
runtimeOnly 'org.postgresql:postgresql'
```

`hibernate-spatial`, `jts-core`는 PostGIS 지원하므로 유지.

---

#### Step 2: application.yml 수정

```yaml
spring:
  jpa:
    database-platform: org.hibernate.dialect.PostgreSQLDialect

  datasource:
    source:
      driver-class-name: org.postgresql.Driver
      jdbc-url: ${PG_URL}
      username: ${PG_USERNAME}
      password: ${PG_PASSWORD}
    replica:
      driver-class-name: org.postgresql.Driver
      jdbc-url: ${PG_REPLICA_URL:${PG_URL}}
      username: ${PG_REPLICA_USERNAME:${PG_USERNAME}}
      password: ${PG_REPLICA_PASSWORD:${PG_PASSWORD}}
```

---

#### Step 3: Store 엔티티 컬럼 정의 변경

`Store.java:71`

```java
// Before
@Column(name = "location", columnDefinition = "POINT SRID 4326", nullable = false)

// After
@Column(name = "location", columnDefinition = "geometry(Point, 4326)", nullable = false)
```

---

#### Step 4: StoreRepository 네이티브 쿼리 수정

**MySQL → PostgreSQL 함수 매핑:**

| MySQL | PostgreSQL (PostGIS) |
|-------|---------------------|
| `ST_Distance_Sphere(loc, ST_SRID(POINT(lng, lat), 4326))` | `ST_DistanceSphere(loc::geometry, ST_SetSRID(ST_MakePoint(lng, lat), 4326))` |
| `ORDER BY RAND()` | `ORDER BY RANDOM()` |
| `MATCH(col) AGAINST(kw IN NATURAL LANGUAGE MODE)` | `ts_rank(to_tsvector('simple', col), plainto_tsquery('simple', kw))` |
| `MATCH(col) AGAINST(CONCAT('+', kw, '*') IN BOOLEAN MODE)` | `to_tsvector('simple', col) @@ to_tsquery('simple', kw || ':*')` |

**findStoreIdsInRadius:**
```sql
ST_DistanceSphere(location::geometry, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)) <= :radiusMeters
```

**findRandomStoreIdsInBounds:**
```sql
ORDER BY RANDOM()
```

**findRandomStoresByCategory:**
```sql
ST_DistanceSphere(s.location::geometry, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)) <= :radiusMeters
ORDER BY RANDOM()
```

**searchNearbyStores (가장 복잡한 변경):**
```sql
SELECT s.*,
  CASE WHEN s.storeName = :keyword THEN 1 ELSE 0 END AS is_exact,
  (ts_rank(to_tsvector('simple', COALESCE(s.storeName,'') || ' ' || COALESCE(s.business,'')),
           plainto_tsquery('simple', :keyword))
  + ts_rank(to_tsvector('simple', COALESCE(p.partnerName,'') || ' ' || COALESCE(p.category,'')),
            plainto_tsquery('simple', :keyword))) AS relevance,
  ST_DistanceSphere(s.location::geometry, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)) AS distance
FROM store s
JOIN partner p ON s.partnerId = p.partnerId
WHERE s.location IS NOT NULL
  AND (:category IS NULL OR p.category = :category)
  AND (
    to_tsvector('simple', COALESCE(s.storeName,'') || ' ' || COALESCE(s.business,''))
      @@ to_tsquery('simple', :keyword || ':*')
    OR to_tsvector('simple', COALESCE(p.partnerName,'') || ' ' || COALESCE(p.category,''))
      @@ to_tsquery('simple', :keyword || ':*')
  )
ORDER BY is_exact DESC, distance ASC, relevance DESC
LIMIT 30
```

**searchNearbyStoresByPartnerId:**
```sql
ORDER BY ST_DistanceSphere(location::geometry, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)) ASC
```

---

#### Step 5: JPQL 함수 호환성 수정

**MembershipHistoryRepository.java:41-42**

```jpql
-- Before
AND YEAR(mh.usedAt) = :year
AND MONTH(mh.usedAt) = :month

-- After
AND EXTRACT(YEAR FROM mh.usedAt) = :year
AND EXTRACT(MONTH FROM mh.usedAt) = :month
```

**RecommendationRepository.java:13,16**
- Hibernate 6는 `DATE()` → `CAST(x AS date)` 로 변환 지원하므로 동작 확인 후 필요 시 수정.

**BenefitRepository.java:32-33, 51-52**

```jpql
-- Before
REPLACE(REPLACE(p.category, CHAR(13), ''), CHAR(10), '')

-- After
REPLACE(REPLACE(p.category, FUNCTION('CHR', 13), ''), FUNCTION('CHR', 10), '')
```

---

#### Step 6: 데이터 마이그레이션

PostgreSQL에 PostGIS 확장 설치:
```sql
CREATE EXTENSION IF NOT EXISTS postgis;
```

`pgloader`로 대부분의 테이블 이전 (`store` 테이블 제외):
```
LOAD DATABASE
  FROM mysql://user:pass@host/dbname
  INTO postgresql://user:pass@host/dbname
  WITH data only, workers = 4
  EXCLUDING TABLE NAMES MATCHING 'store'
;
```

`store` 테이블 별도 처리 (geometry 컬럼):
1. MySQL에서 `location` 제외하고 나머지 컬럼 CSV 내보내기
2. PostgreSQL `COPY`로 임포트
3. geometry 컬럼 복원:

```sql
UPDATE store
SET location = ST_SetSRID(ST_MakePoint(longitude::float, latitude::float), 4326)
WHERE location IS NULL;
```

---

### 검증 방법

1. `./gradlew build` — 컴파일 오류 확인
2. 로컬 PostgreSQL + PostGIS 컨테이너로 실행 후 API 호출:
   - 반경 검색: `GET /map/stores?lat=37.5&lng=127.0&radius=1000`
   - 전문 검색: `GET /map/stores/search?keyword=카페&lat=37.5&lng=127.0`
   - 카테고리 검색: `GET /map/stores/category?category=카페&lat=37.5&lng=127.0`
   - 히스토리 (YEAR/MONTH 쿼리): `GET /history?membershipId=xxx`
   - 혜택 목록 (CHAR() 쿼리): `GET /benefits?mainCategory=xxx`
3. 데이터 정합성 확인: store 건수, `location IS NOT NULL` 비율
