# user-api 코드 품질 개선 감사 기록

## 목적
반복되는 코드, 일관적이지 않은 코드, 비효율적인 코드, 종료 기능 잔여 코드를 우선순위별로 기록하고 개선 추적 기준으로 사용한다.

## 2026-09-09 후속 재점검 개선

`7c93522` 이후 재점검한 15개 항목의 반영 결과다. 하단 1차 기록은 당시 후보와 처리 이력이다.

| 항목 | 반영 결과 |
|---|---|
| 1. 클릭 로그 실패 전파 | 기존 bounded 비동기 batch logger로 통합. Mongo 저장·로그 제출 실패를 상세 요청과 격리 |
| 2. ES runtime 오류 | `queryVector`의 ES 404/503에도 DB fallback. DB hydration 오류는 그대로 전파 |
| 3. 오래된 정책·등급 | ES policy/tier ID의 소유 관계·활성·통신사·등급을 DB와 대조하고 현재 DB 내용으로 후보 구성 |
| 4. 대표 정책 | 목록·상세·즐겨찾기의 활성 정책 선택 공통화. 목록은 요청 통신사·이용 방식 반영 |
| 5. 검색 페이지 | 활성 후보를 확인한 뒤 페이지를 나누고 실제 후보 수로 total/hasNext 계산 |
| 6. 금칙어 갱신 | 조회를 모두 마친 불변 규칙을 원자 교체. 조회 실패 시 마지막 정상 규칙 유지 |
| 7. 인기순 과다 집계 | 정책 조인 개수와 무관하게 즐겨찾기 사용자 수를 DISTINCT 집계 |
| 8. 즐겨찾기 이벤트 | 실제 즐겨찾기 행을 잠근 뒤 삭제. 중복 ID·재시도 중복 로그 방지, 추가/삭제 로그는 커밋 후 제출 |
| 9. 불필요한 재임베딩 | 해시는 embedding version과 검색 텍스트만 사용. 메타데이터 변경은 벡터 재사용, 동일한 등급 구성은 DB ID 유지 |
| 10. 가까운 매장 누락 | 전국 ES 후보와 DB 지리 키워드 검색 후보를 합쳐 중복 제거·브랜드 검증·거리 정렬 |
| 11. ES executor | 정적 executor와 Future 대기를 제거하고 ES msearch 한 요청으로 통합 |
| 12. 반복 조회 | 행동 집계 11회 → facet 1회, RAG policy/tier는 혜택 200개 단위 조회, import 정책 코드는 요청 안에서 재사용 |
| 13. 빈 추천 호출 | 후보가 없으면 LLM 호출 없이 빈 결과 반환 |
| 14. JWT 중복 검증 | access/temp 토큰의 Claims를 한 번 검증. 후속 필터 예외를 JWT 오류로 처리해 재실행하지 않음 |
| 15. 미사용 코드 | trie, SMS OTP wrapper, UserFeature embedding helper, temp JWT overload, 집계 DTO 제거 |

### 동작 경계와 적용 시 참고

- 키워드 검색은 최초 최대 500개 기준 혜택과 같은 제휴처의 활성 혜택을 합친 후보 집합을 페이지로 제공한다. `totalElements`는 이 집합의 크기이며 전체 DB 일치 건수를 의미하지 않는다. 전체 검색이 필요하면 DB 기준 페이지/검색 계약으로 확장해야 한다.
- 지도 키워드 검색은 ES 브랜드 100개·매장명/업종 200개에 기존 PostGIS 문자열 검색 최대 30개를 보충한다. ES 지리 필드 추가나 재색인 없이 가까운 명시적 키워드 일치를 보충하는 범위이며, 전국의 모든 형태소 유사 매장을 포괄하지 않는다.
- `active=null`은 기존 SQL의 `COALESCE(active, true)`와 일치하도록 활성으로 취급한다.
- RAG content hash 식이 변경되어 이전 식으로 색인된 문서는 첫 동기화에서 한 번 새로 임베딩한다. 이후 날짜·URL 등 메타데이터만 변경되면 벡터를 재사용한다. ES 기존 문서 조회 자체가 실패하면 임베딩을 호출하지 않고 실패 건수에 반영한다.
- snapshot 캐시 TTL과 세션 회수 정책은 별도 운영 정책 검토 대상이다. 이번 변경으로 기존 캐시가 즉시 무효화되지는 않는다.

### 검증

- `./gradlew test`: 341개 중 340개 통과, Docker가 필요한 Flyway 통합 테스트 1개 건너뜀.
- `./gradlew build -x test`: 패키징 검증.
- `python3 src/test/python/test_benefit_popularity.py`: 실제 repository SQL을 SQLite 조인 fixture로 실행해 인기순 검증. PostgreSQL 실행 계획·행 잠금·PostGIS 및 실제 ES/Mongo 통합 동작은 이 로컬 검증에 포함되지 않는다.
- 검증은 로컬 코드 기준이며 운영 배포·재색인을 실행한 기록이 아니다.

## 우선순위 개선 후보

| 우선순위 | 항목 | 근거 | 처리 방향 |
|---:|---|---|---|
| 1 | 인증 설정과 컨트롤러 기대값 불일치 | `SecurityConfig` 인증 경로와 `MyPageController`, `MembershipUsageController`의 principal 사용 방식 불일치 | 인증 경로 보강 및 principal null 방어 |
| 2 | 종료/삭제된 기능의 호환 코드 잔여 | 쿠폰, 멤버십 사용 내역, Uplus 문서/코드 잔여 | 명백히 죽은 API/문서부터 제거 |
| 3 | 컨트롤러 응답 생성 반복 | `ApiResponse` 생성 후 `ResponseEntity` 반환 패턴 반복 | 공통 응답 헬퍼 도입 후 점진 적용 |
| 4 | 지도 서비스 DTO 변환 흐름 반복 | `StoreServiceImpl`의 partner/benefit/tier 변환 로직 반복 | 후속 회차에서 mapper/private helper 추출 |
| 5 | OpenAI 호출 구조 분산 | 추천/질문/임베딩 호출 방식이 각각 다름 | 후속 회차에서 OpenAI client boundary 통합 |
| 6 | SMS 인증 문자열 원문 로그 노출 | 인증 문자열 발급/확인/Octomo 조회 로그에 원문 포함 | 로그 마스킹, 응답 필드 제거는 클라이언트 영향 확인 후 진행 |
| 7 | 금칙어 Trie와 실제 검사 방식 불일치 | Trie를 구성하지만 실제 검사는 Set 순회 중심 | 후속 회차에서 검사 경로 단일화 |
| 8 | 정적 Swagger와 실제 컨트롤러 불일치 | 제거된 Uplus/findEmail 등 API 문서 잔여 | 정적 문서 정리 |
| 9 | `sort` 파라미터 미사용 | BenefitController가 sort를 받지만 서비스에 전달하지 않음 | 후속 회차에서 구현 또는 API 제거 결정 |
| 10 | 질문 추천 주변 매장 반복 조회 | 후보 제휴처마다 storeService 호출 | 후속 회차에서 batch 조회 검토 |

## 1차 Ralph 개선 결과
- 처리 완료: 인증 경로 보강, principal null 방어, 종료된 쿠폰/멤버십 사용 내역 API 제거, 정적 Swagger 잔여 정리, `ApiResponse.toResponseEntity()` 도입/적용, SMS 인증 원문 로그 제거.
- 처리 완료: Uplus/findEmail/멤버십 사용 내역/쿠폰 관련 정적 Swagger API 표면과 미사용 코드 제거.
- 보류: 지도 서비스 DTO 변환 중복, OpenAI 호출 구조 통합, 금칙어 Trie 검사 경로 단일화, 혜택 `sort` 파라미터 정리, 질문 추천 batch 조회 최적화.
- SMS 응답의 `verificationText`는 현재 클라이언트가 문자 앱 자동 작성에 사용할 수 있어 이번 범위에서는 제거하지 않는다.
