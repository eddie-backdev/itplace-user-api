# itplace 검색 아키텍처 설계

## 현재 상태 분석

| 기능 | 현재 구현 | 문제점 |
|------|----------|--------|
| 매장 텍스트 검색 | PostgreSQL `to_tsvector` | 한국어 형태소 분석 없음, 오타 허용 안됨 |
| 반경/위치 검색 | PostGIS `ST_DistanceSphere` | 문제 없음 (PostGIS가 최적) |
| 혜택 AI 추천 | ES 벡터 검색 (KNN) | 이미 구현됨, ES 인프라 존재 |
| 혜택 목록/필터 | PostgreSQL native SQL | 문제 없음 |

ES 인프라가 이미 존재하므로 추가 비용 없이 텍스트 검색에도 활용 가능.

---

## 최적 아키텍처

```

┌─────────────────────────────────────────────────────────┐
│                        클라이언트                          │
└──────────────────────────┬──────────────────────────────┘
                           │
              ┌────────────▼────────────┐
              │      Spring Boot API    │
              └──┬────────────────┬─────┘
                 │                │
    ┌────────────▼────┐    ┌──────▼───────────────┐
    │  PostgreSQL     │    │   Elasticsearch      │
    │  (PostGIS)      │    │                      │
    │                 │    │  • 매장 텍스트 검색  │
    │  • 원본 데이터  │    │    (nori 형태소)     │
    │  • 위치 검색    │    │  • 혜택 벡터 검색    │
    │  • CRUD         │    │    (AI 추천/RAG)     │
    │  • 혜택 필터    │    │                      │
    └─────────────────┘    └──────────────────────┘
    
```

### 역할 분담

| 쿼리 종류 | 담당 |
|-----------|------|
| 반경 내 매장 조회 | PostGIS |
| 카테고리별 랜덤 매장 | PostGIS |
| **매장 키워드 검색** | **ES (nori) → PostGIS 거리 정렬** |
| 혜택 목록/필터 | PostgreSQL |
| AI 혜택 추천 | ES 벡터 검색 (현재 구현) |
| 혜택 상세 조회 | PostgreSQL |

---

## 핵심: 매장 텍스트 검색 2단계 전략

`searchNearbyStores`의 현재 방식(`to_tsvector`)을 아래로 개선:

```
1단계: ES (nori 형태소 분석)
       키워드로 매장 ID 목록 추출 (텍스트 관련성)

2단계: PostGIS
       1단계 ID 목록 중 반경 내 매장만 필터링 + 거리순 정렬
```

```java
// StoreSearchService
public List<StoreResponse> searchNearbyStores(String keyword, double lat, double lng) {
    // 1단계: ES에서 텍스트 매칭되는 storeId 목록
    List<Long> matchedIds = storeSearchRepository.searchByKeyword(keyword);

    // 2단계: PostGIS로 반경 필터 + 거리 정렬
    return storeRepository.findByIdsWithinRadius(matchedIds, lat, lng, radiusMeters);
}
```

이렇게 하면 ES는 "무엇이 관련있는가"만 판단하고,
PostGIS는 "얼마나 가까운가"만 판단.
각자 가장 잘하는 일에 집중.

---

## ES 인덱스 설계

### store 인덱스

```json
{
  "settings": {
    "analysis": {
      "analyzer": {
        "korean": {
          "type": "custom",
          "tokenizer": "nori_tokenizer",
          "filter": ["nori_part_of_speech"]
        }
      }
    }
  },
  "mappings": {
    "properties": {
      "storeId":     { "type": "long" },
      "storeName":   { "type": "text", "analyzer": "korean" },
      "business":    { "type": "text", "analyzer": "korean" },
      "partnerName": { "type": "text", "analyzer": "korean" },
      "category":    { "type": "keyword" },
      "city":        { "type": "keyword" },
      "town":        { "type": "keyword" }
    }
  }
}
```

### benefit 인덱스 (현재 구조 유지 + nori 추가)

```json
{
  "mappings": {
    "properties": {
      "benefitId":   { "type": "keyword" },
      "benefitName": { "type": "text", "analyzer": "korean" },
      "partnerName": { "type": "text", "analyzer": "korean" },
      "category":    { "type": "keyword" },
      "mainCategory":{ "type": "keyword" },
      "description": { "type": "text", "analyzer": "korean" },
      "embedding":   { "type": "dense_vector", "dims": 1536 }
    }
  }
}
```

benefit 인덱스는 현재 벡터 검색과 텍스트 검색을 동시에 지원하도록 확장.

---

## 동기화 전략

### 쓰기 시 동기화 (@TransactionalEventListener)

```java
// StoreService
@Transactional
public void saveStore(Store store) {
    storeRepository.save(store);
    eventPublisher.publishEvent(new StoreChangedEvent(store));
}

// StoreIndexEventListener
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onStoreChanged(StoreChangedEvent event) {
    storeSearchRepository.index(toDocument(event.getStore()));
}
```

`AFTER_COMMIT`을 쓰는 이유: DB 트랜잭션이 커밋된 후에만 ES에 반영.
DB 롤백 시 ES 인덱싱이 실행되지 않아 불일치 방지.

### 초기 전체 인덱싱

앱 시작 시 또는 수동 트리거로 전체 재인덱싱:

```java
// 현재 ElasticsearchIndexer 패턴을 store에도 동일하게 적용
storeRepository.findAll().forEach(store -> {
    storeSearchRepository.index(toDocument(store));
});
```

---

## 구현 우선순위

### Phase 1 (즉시 적용 가능)
- [ ] store 인덱스 생성 (nori 분석기 설정)
- [ ] `StoreDocument` 클래스 작성
- [ ] 기존 store 전체 초기 인덱싱
- [ ] `searchNearbyStores` 2단계 검색으로 교체

### Phase 2
- [ ] benefit 인덱스에 nori 분석기 추가
- [ ] `@TransactionalEventListener` 기반 실시간 동기화
- [ ] store 저장/수정/삭제 시 ES 자동 반영

### Phase 3 (선택)
- [ ] ES → PostGIS 2단계 이후 성능 모니터링
- [ ] 필요 시 캐싱 레이어 추가 (Redis)

---

## 정리

| | PostGIS | Elasticsearch |
|--|---------|--------------|
| 강점 | 정밀한 거리 계산, 공간 연산 | 한국어 형태소, 관련성 랭킹, 벡터 검색 |
| 담당 | 위치 기반 필터/정렬 | 텍스트 매칭, AI 추천 |
| 데이터 | 원본 (단일 저장소) | 검색용 복사본 (파생 데이터) |

ES 인프라가 이미 존재하므로 store 인덱스 추가 비용이 낮음.
한국어 검색 품질과 위치 정확도를 동시에 확보할 수 있는 구조.
