# user-api 코드 품질 개선 감사 기록

## 목적
반복되는 코드, 일관적이지 않은 코드, 비효율적인 코드, 종료 기능 잔여 코드를 우선순위별로 기록하고 개선 추적 기준으로 사용한다.

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
