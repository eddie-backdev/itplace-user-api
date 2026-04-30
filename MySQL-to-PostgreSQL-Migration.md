# MySQL → PostgreSQL (PostGIS) 마이그레이션 가이드

## 배경

MySQL의 공간 정보 처리 한계로 인해 PostgreSQL + PostGIS로 전환.
`Store` 엔티티가 `POINT SRID 4326` 타입의 `location` 컬럼을 사용하고 있었고,
`StoreRepository`의 네이티브 쿼리들이 MySQL 전용 함수를 사용 중이었음.

---

## 1. Docker Compose 설정

`postgis/postgis:16-3.4` 이미지로 변경 (PostGIS 내장).
Apple Silicon(M1/M2)에서는 ARM64 빌드가 없어 Rosetta 2 에뮬레이션으로 실행됨 (경고는 무해).

```yaml
services:
  postgresql:
    image: postgis/postgis:16-3.4
    environment:
      POSTGRES_DB: itplace
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: !Password1127
    ports:
      - "5432:5432"
```

---

## 2. PostgreSQL 초기 설정

컨테이너 접속 후 PostGIS 확장 설치:

```sql
CREATE EXTENSION IF NOT EXISTS postgis;
```

---

## 3. 코드 변경

### 3-1. build.gradle

```gradle
// 제거
runtimeOnly 'com.mysql:mysql-connector-j'

// 추가
runtimeOnly 'org.postgresql:postgresql'
```

`hibernate-spatial`, `jts-core`는 PostGIS도 지원하므로 그대로 유지.

### 3-2. application.yml

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

### 3-3. Store 엔티티

```java
// Before
@Column(name = "location", columnDefinition = "POINT SRID 4326", nullable = false)

// After
@Column(name = "location", columnDefinition = "geometry(Point, 4326)", nullable = false)
```

### 3-4. StoreRepository 네이티브 쿼리

| MySQL | PostgreSQL (PostGIS) |
|-------|---------------------|
| `ST_Distance_Sphere(loc, ST_SRID(POINT(lng, lat), 4326))` | `ST_DistanceSphere(loc::geometry, ST_SetSRID(ST_MakePoint(lng, lat), 4326))` |
| `ORDER BY RAND()` | `ORDER BY RANDOM()` |
| `MATCH(col) AGAINST(kw IN NATURAL LANGUAGE MODE)` | `ts_rank(to_tsvector('simple', col), plainto_tsquery('simple', kw))` |
| `MATCH(col) AGAINST('+kw*' IN BOOLEAN MODE)` | `to_tsvector('simple', col) @@ to_tsquery('simple', kw \|\| ':*')` |

searchNearbyStores 쿼리 예시:

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

### 3-5. MembershipHistoryRepository

```jpql
-- Before (MySQL 전용)
YEAR(mh.usedAt) = :year AND MONTH(mh.usedAt) = :month

-- After
EXTRACT(YEAR FROM mh.usedAt) = :year AND EXTRACT(MONTH FROM mh.usedAt) = :month
```

### 3-6. RecommendationRepository

```jpql
-- Before
SELECT MAX(DATE(r.createdDate)) ...
DATE(r.createdDate) = :createdDate

-- After
SELECT MAX(CAST(r.createdDate AS LocalDate)) ...
CAST(r.createdDate AS LocalDate) = :createdDate
```

### 3-7. Benefit 엔티티

PostgreSQL에서 `@Lob`은 `oid` 타입으로 매핑되어 pgloader 마이그레이션 실패 및 JPQL 쿼리에서 `lower(bytea)` 오류 발생.

```java
// Before
@Lob
private String description;

// After
@JdbcTypeCode(SqlTypes.LONGVARCHAR)
private String description;
```

`findFilteredBenefits` 쿼리는 `lower(bytea)` 오류 우회를 위해 JPQL → native SQL로 전환하고,
`mainCategory` 파라미터 타입을 `MainCategory` enum → `String`으로 변경.
서비스에서 `mainCategory.getLabel()`로 전달.

### 3-8. User 엔티티

`user`는 PostgreSQL 예약어이므로 테이블명 변경:

```java
// Before
@Table(name = "user")

// After
@Table(name = "users")
```

### 3-9. User / UplusData gender 컬럼

MySQL의 "FEMALE" (6자)가 `length=5` 컬럼에 저장되어 있어 마이그레이션 실패:

```java
// Before
@Column(name = "gender", length = 5)

// After
@Column(name = "gender", length = 6)
```

PostgreSQL 컬럼도 직접 ALTER:
```sql
ALTER TABLE users ALTER COLUMN gender TYPE VARCHAR(6);
ALTER TABLE uplusdata ALTER COLUMN gender TYPE VARCHAR(6);
```

### 3-10. Membership 엔티티

`membershipId`는 varchar PK (외부에서 할당)인데 `@GeneratedValue(IDENTITY)`가 붙어 있어
PostgreSQL DDL에서 `IDENTITY column must be integer` 오류 발생. 해당 어노테이션 제거.

---

## 4. 데이터 마이그레이션

### 4-1. pgloader 설치

```bash
brew install pgloader
```

### 4-2. 일반 테이블 마이그레이션 (pgloader)

`store` 테이블은 geometry 컬럼 때문에 별도 처리. 나머지는 pgloader로 일괄 마이그레이션.

`migrate.load` 파일:
```
LOAD DATABASE
  FROM mysql://root:password@localhost:3306/itplace
  INTO postgresql://postgres:password@localhost:5432/itplace

WITH data only, workers = 4, concurrency = 1

ALTER SCHEMA 'itplace' RENAME TO 'public'

INCLUDING ONLY TABLE NAMES MATCHING
  'user', 'benefit', 'uplusData', 'partner', 'membership', ...

ALTER TABLE NAMES MATCHING 'user' RENAME TO 'users'
;
```

```bash
pgloader migrate.load
```

### 4-3. store 테이블 마이그레이션 (Python 스크립트)

MySQL의 `POINT` 타입을 pgloader가 처리하지 못해 Python으로 직접 마이그레이션:

```python
import pymysql
import psycopg2
import psycopg2.extras

mysql_conn = pymysql.connect(host='127.0.0.1', port=3306, user='root',
                             password='password', db='itplace', charset='utf8mb4')
pg_conn = psycopg2.connect("postgresql://postgres:password@localhost:5432/itplace")

mysql_cur = mysql_conn.cursor()
pg_cur = pg_conn.cursor()

mysql_cur.execute("""
    SELECT storeId, partnerId, storeName, business,
           city, town, legalDong, address, roadName, roadAddress,
           postCode, longitude, latitude, hasCoupon
    FROM store
""")

rows_raw = mysql_cur.fetchall()
# MySQL TINYINT(0/1) → PostgreSQL boolean
rows = [r[:-1] + (bool(r[-1]),) for r in rows_raw]

insert_sql = """
    INSERT INTO store (
        storeId, partnerId, storeName, business,
        city, town, legalDong, address, roadName, roadAddress,
        postCode, longitude, latitude, hasCoupon
    ) VALUES %s
    ON CONFLICT (storeId) DO NOTHING
"""
psycopg2.extras.execute_values(pg_cur, insert_sql, rows, page_size=500)
pg_conn.commit()

# location 컬럼 복원 (longitude/latitude → geometry)
pg_cur.execute("""
    UPDATE store
    SET location = ST_SetSRID(ST_MakePoint(longitude::float, latitude::float), 4326)
    WHERE location IS NULL AND longitude IS NOT NULL AND latitude IS NOT NULL
""")
pg_conn.commit()

mysql_conn.close()
pg_conn.close()
```

```bash
pip install pymysql psycopg2-binary
python3 migrate_store.py
```

---

## 5. 발생한 오류 및 해결

| 오류 | 원인 | 해결 |
|------|------|------|
| `IDENTITY column must be smallint/integer/bigint` | varchar PK에 `@GeneratedValue(IDENTITY)` | 어노테이션 제거 |
| `relation "user" already exists` / 예약어 충돌 | `user`는 PostgreSQL 예약어 | `@Table(name = "users")` |
| pgloader `failed to find schema 'itplace'` | schema 이름 불일치 | pgloader에 `ALTER SCHEMA 'itplace' RENAME TO 'public'` 추가 |
| pgloader `users` 마이그레이션 실패 | gender VARCHAR(5) → "FEMALE" 6자 | `length=6`으로 변경 후 `ALTER TABLE` |
| pgloader `benefit` 마이그레이션 실패 | `@Lob` → PostgreSQL `oid` 타입 불일치 | `@JdbcTypeCode(SqlTypes.LONGVARCHAR)` 로 변경 |
| pgloader `store` 마이그레이션 불가 | MySQL `POINT` 타입 변환 불가 | Python 스크립트로 직접 마이그레이션 |
| `function lower(bytea) does not exist` | `@JdbcTypeCode(LONGVARCHAR)`가 JPQL 파라미터를 bytea로 추론 | `findFilteredBenefits`를 native SQL로 전환 |
| `MAX(DATE(...))` 타입 오류 | Hibernate 6가 DATE() 반환 타입 추론 실패 | `CAST(x AS LocalDate)` 로 변경 |
| `hasCoupon` 타입 불일치 | MySQL TINYINT(0/1) vs PostgreSQL boolean | Python 스크립트에서 `bool()` 변환 |

---

## 6. 마이그레이션 후 검증

```bash
# 반경 검색
curl "http://localhost:8080/api/v1/map/stores?lat=37.5&lng=127.0&radius=1000"

# 전문검색
curl "http://localhost:8080/api/v1/map/stores/search?keyword=카페&lat=37.5&lng=127.0"

# 카테고리 검색
curl "http://localhost:8080/api/v1/map/stores/category?category=카페&lat=37.5&lng=127.0"

# 혜택 조회
curl "http://localhost:8080/api/v1/benefit?mainCategory=BASIC_BENEFIT&page=0&size=5"
curl "http://localhost:8080/api/v1/benefit?mainCategory=VIP_COCK&page=0&size=5"
```

PostgreSQL에서 데이터 확인:
```sql
SELECT COUNT(*), COUNT(location) FROM store;
SELECT COUNT(*) FROM users;
SELECT COUNT(*) FROM benefit;
```
