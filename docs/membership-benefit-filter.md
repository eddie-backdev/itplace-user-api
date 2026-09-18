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
  - 통신사·등급 필터 없이 `mainCategory`를 생략한 기존 호출은 `BASIC_BENEFIT`을 유지한다. 통신사 또는 등급 필터가 있고 `mainCategory`를 생략하면 VIP콕을 포함한 모든 main category를 조회한다.
  - 제휴처의 활성 혜택은 있으나 요청 등급에 맞는 정책이 없으면 200과 빈 `carrierGroups`를 반환한다.

등급은 정확히 일치해야 한다. 예외로 LG U+ VIP·VVIP에는 `VIP콕` tier도 포함한다. 상위 등급이 하위 등급 혜택을 자동 상속한다고 추정하지 않으며, tier가 없는 혜택은 등급 필터 결과에 포함하지 않는다. 이용 가능 횟수·기간·매장 예외는 이 필터가 보증하지 않는다.

혜택 데이터는 기존처럼 공개 GET이다. 클라이언트가 프로필에서 조회 조건을 선택하며, 서버가 사용자 신원이나 통신사 실인증을 이 파라미터로 판단하지 않는다.

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
