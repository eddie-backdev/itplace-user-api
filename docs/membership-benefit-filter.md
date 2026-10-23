# 내 멤버십 혜택 필터

구현일: 2026-09-18. 운영 반영에는 user-api 배포 후 사용자 웹 배포가 필요하다. DB migration은 없다.

## API

- `GET /api/v1/benefits/partners?carrier=SKT&grade=SKT_GOLD`
  - 기존 공개 목록에 optional `grade`를 추가한다. `carrier` 또는 기존 `carriers`로 통신사 하나를 함께 전달한다.
  - 통신사 미지정·복수 지정·등급과 통신사 불일치는 HTTP 400 `INVALID_MEMBERSHIP_FILTER`다. 알 수 없는 enum은 기존 요청 파라미터 오류다.
  - 등급 조건은 활성 혜택의 동일한 활성 통신사 정책에 적용한다. SQL 조회와 count 모두 적용하므로 페이지 수·검색·카테고리가 일치한다.
  - 필터 사용 시 응답 `carriers`에는 선택한 통신사만 포함한다. 등급을 생략한 기존 호출은 기존 표시 규칙을 유지한다.
- `GET /api/v1/benefits/partners/{partnerId}?carrier=SKT&grade=SKT_GOLD`
  - optional `carrier`, `grade`를 추가한다. 해당 통신사·등급의 정책과 tier만 반환한다.
  - `grade`를 생략하고 `carrier`만 전달하면 해당 통신사의 모든 등급을 비교한다.
  - `mainCategory`를 생략하면 통신사·등급 필터 유무와 관계없이 모든 활성 main category를 조회한다. `BASIC_BENEFIT` 또는 `VIP_COCK`을 지정하면 해당 카테고리만 조회한다.
  - 제휴처의 활성 혜택은 있으나 요청 등급에 맞는 정책이 없으면 200과 빈 `carrierGroups`를 반환한다.

등급은 정확히 일치해야 한다. 예외로 LG U+ VIP·VVIP에는 `VIP콕` tier도 포함한다. 상위 등급이 하위 등급 혜택을 자동 상속한다고 추정하지 않으며, tier가 없는 혜택은 등급 필터 결과에 포함하지 않는다. 이용 가능 횟수·기간·매장 예외는 이 필터가 보증하지 않는다.

조건 JSONB에 구조화된 offer가 있으면 같은 offer가 선택 등급과 요청 채널을 모두 만족할 때만 반환한다. tier의 부가 조건과 RAG 문서는 저장된 `offerkey`로 해당 offer에 연결하며, 같은 등급이라는 이유만으로 다른 offer의 조건을 합치지 않는다. `UNRESOLVED`는 확인된 tier 사실을 통한 등급 탐색에는 남기고 불확실성을 표시하지만, 특정 채널 조회에는 포함하지 않는다. 레거시 조건은 기존 정책 동작을 유지한다.

제휴 매장 존재 여부는 활성·업종 적합 store-policy 연결로 판단하며 할인 사용 채널과 독립적이다. 지도 상세는 요청한 carrier만 조회한다. `partner-benefits-v3` 캐시 namespace로 이전 조건 DTO를 저장한 프로세스와 분리한다. offer 범위 변경은 데이터 준비 단계에 한정되며 유료 embedding 호출이나 Elasticsearch 색인 갱신은 하지 않는다.

혜택 데이터는 기존처럼 공개 GET이다. 클라이언트가 프로필에서 조회 조건을 선택하며, 서버가 사용자 신원이나 통신사 실인증을 이 파라미터로 판단하지 않는다.

## 2026-10-21 공개 상태와 목록 요약

후속 migration `0005`는 정책의 `publicationstatus`, `holdreason`, `publicationreviewedat`와 tier의 `offerkey`를 추가한다. `HELD` 정책은 DB 제약으로 비활성 상태를 유지한다. 재수집이나 일반 snapshot import로 기존 보류가 자동 해제되지 않는다. 출처 검증은 실제 archive의 ID·통신사·URL·수집 시각을 대조한다. `0006`은 새 DB에도 기존 `partneralias` 검색 테이블을 보장한다.

목록과 관심 혜택의 preview는 `summary`를 제공한다. `version="1"`, `offerKey`, `title`, `condition`, `limit`, `usage`, `grades`, `allGrades`가 계약이며, 안전한 대표 혜택을 정할 수 없으면 null이다. 여러 개의 확인된 혜택이 있는 경우 등급·채널을 만족하는 한 개를 선택한다. 원문 offer 배열과 이용 방법을 목록 카드 문구로 합치지 않는다. 한도 요약은 해당 offer의 구조화된 수치만 사용하며, 포인트 전환 절차나 쿠폰 법적 안내 문장은 짧은 조건으로 표시하지 않는다. 전체 원문은 상세에서 유지한다.

제휴처 검색은 브랜드·저장된 별칭, 정식 카테고리명, 원문 세부 업종, 혜택명·설명과 같은 등급·채널에 해당하는 tier 문구를 조회한다. `편의점`을 생활·편의 전체로 확장하지 않는다. `편의점`, `영화`, `영화관`은 확인된 대표 브랜드명으로도 조회하며, 영화와 영화관의 브랜드 집합을 일치시킨다. 이 범위는 관리되는 검색 규칙이며 모든 자유로운 표현을 이해하는 의미 검색을 뜻하지 않는다. SQL 목록과 count에 같은 조건을 적용한다.

## 웹 흐름

대상은 `itplace-user-front`의 `/benefits` PC·모바일 웹이다. Next.js 전환본과 Android 앱은 이번 변경에 포함하지 않는다.

- `내 멤버십 혜택만`을 켜면 저장한 통신사·등급으로 검색한다. `membership=mine`을 URL에 유지해 상세에서 돌아와도 적용한다.
- 로그인·유효한 프로필이 없으면 체크박스를 비활성화하고 로그인 또는 멤버십 설정 링크를 제공한다.
- 내 멤버십 모드에서 통신사 버튼을 누르면 해당 통신사 하나로 전환하고 등급 필터를 해제한다.
- 상세 링크에는 `carrier`, `grade`를 전달한다. ‘모든 등급 보기’는 같은 통신사에서 등급 조건만 제거한다.
- 필터 변경 시 이전 목록을 지우고, 빈 결과·조회 실패·초기화 경로를 구분한다.

## 검증

- `PartnerBenefitControllerTest`, `PartnerBenefitBrowseServiceTest`: 기존 기본값, 등급 전달·유효성, VIP콕, 상세 tier 필터.
- `PartnerMembershipFilterIntegrationTest`: 실제 PostgreSQL에서 활성 정책·등급 일치, 중복 tier, count·페이지, 통신사·채널·검색·카테고리 조합과 기존 호출 회귀.
- 전체 Gradle test 및 build, 웹 ESLint·build·SEO 검사.
- 로컬 응답 fixture를 사용한 실제 브라우저 PC·모바일 검증: 로그인/프로필 미설정, 키보드 토글, 필터 전환, 상세·비교, 빈 결과, 오류 후 이전 목록 제거. 운영 로그인이나 운영 쓰기 검증은 수행하지 않는다.
