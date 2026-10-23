# PostgreSQL 검색·RAG 통합 전환 설계

## 최신 개발 DB 및 임베딩 준비 상태 (2026-10-23; 10/22 시작)

2026-10-23 현재 기존 `localhost:5432/itplace` 개발 DB에 PostgreSQL 검색용 두 migration을 적용하고 개발 DB의 준비 문서를 stage했다. PostgreSQL 18.4 / pgvector 0.8.7이며, `benefit_search_document`에 총 4,893건(활성 3,191, 비활성 1,702)이 있다. 원천은 benefit 973건, policy 1,112건(활성 723건)이다. 저장 문서의 policy identity와 source snapshot 비교 불일치는 0이다. 활성 문서 3,191건은 source URL, 수집 시각, evidence 연결을 모두 보유한다.

분류 검토를 거쳐 카페/베이커리 11 policy와 카드 5 policy의 기존 분류 원인을 수정했다. 현재 audit warning 4,315건은 오류 건수가 아니다. sourceCategory가 제공되지 않은 2,563건(SKT/KT 공식 자료 미제공)과 `OTHER` 1,752문서/397 policy를 포함한다. `OTHER`는 금융·카드·classifier 지원 범위에 남은 분류 품질 검토이며 원문 텍스트나 출처가 없다는 뜻이 아니다. 마지막 audit 기준 errors는 0이다.

저장 벡터와 유료 embedding API 호출은 모두 0이다. 토큰 감사 파일은 `/Users/eddie/.local/state/itplace/pg-preparation-20261022/development/token-audit.json`이며 `cl100k_base` 기준 활성 입력 합계 4,013,685 tokens, 최대 입력 6,065 tokens, 32개 입력 batch 최대 84,295 tokens로 한도 오류 0이다. 1,536차원과 `text-embedding-3-small` 설정은 유지한다. 이는 입력 길이와 적재 준비 검사이며 vector 또는 임베딩·자유 질문 답변 품질 검증이 아니다. 로컬 임베딩 모델을 선택한 것도 아니다.

검증 산출물은 `/Users/eddie/.local/state/itplace/pg-preparation-20261022/development/verification.json`과 `http-verification.json`이다. CLI `verify`의 8개 검색 질의 모두 결과가 있었고, 8개 채팅 질의 모두 `ANSWER`였다. policy identity mismatch와 vectors는 0이다. 새 검증은 PID 37539로 실행된 `127.0.0.1:8080` runtime에서 `local,postgres`, AI 호출 disabled, seed/sync false로 이뤄졌다. HTTP 목록 검색 편의점/씨유/학원/FLO 4건은 모두 200이며 각각 8/3/6/2건을 반환했고, 인증 채팅 편의점/학원/FLO 3건은 모두 200 `ANSWER`였다. 로그인은 200이고 답변 모델이 필수인 추천 API는 `AI_DISABLED` 503을 유지한다. WebConfig는 채팅만 모델 없는 fallback을 허용하며 인증과 CSRF는 유지한다.

백업 dump는 보존했고 복원 확인용 임시 DB는 삭제했다. 처리 및 cleanup 기록은 `/Users/eddie/.local/state/itplace/pg-preparation-20261022/cleanup.json`에 있다. 개발 컨테이너는 기존 DB 하나를 사용한다. 스키마 적용을 위한 durable project command는 아직 없고, 실제 migration은 private one-off 절차로 실행했다.

chat HTTP fallback 변경을 포함한 최종 전체 테스트는 815 tests / 125 reports, 0 failures, 0 skipped로 통과했고 Python 검사 10/10 및 `./gradlew build -x test --no-daemon`도 통과했다. 검증 결과는 [2026-10-23 준비 결과](evaluation/pgvector-preparation-2026-10-23.json)에 모았다. 현재 완료 범위는 임베딩 전 문서 적재·접근 준비 검증이다. 실제 벡터, 자유 질문 답변 품질, 운영 전환 평가는 남아 있다.

### 재현 순서와 통과 기준

`itplace-user-api` 프로젝트 루트에서 아래 순서로 실행한다. 기본 env 파일은 workspace의 `userapi-local.env`이며, 각 실행은 `local,postgres` 프로필을 사용하고 Flyway·AI·seed·sync·scheduler를 끈다. audit/stage/verify는 임베딩 API를 호출하지 않는다. 아래 hash는 2026-10-23 개발 DB 확인값이며, 원천 변경 후에는 새 audit hash를 검토한다.

```bash
OUT=/Users/eddie/.local/state/itplace/pg-preparation-20261022/development
python3 scripts/prepare-postgres-search.py audit --output-dir "$OUT"
TOKENIZER=/Users/eddie/.local/state/itplace/pg-preparation-20261022/tokenizer-venv/bin/python
"$TOKENIZER" scripts/audit-embedding-inputs.py "$OUT/source-snapshot.json" --output "$OUT/token-audit.json"
# token audit가 source snapshot hash 및 입력·batch 제한을 통과한 뒤 hash를 검토한다.
python3 scripts/prepare-postgres-search.py stage \
  --expected-hash '519cc3d994fd9fb7cd0485e3712dcf16556071a1aa362745f10c95a30f442292' --output-dir "$OUT"
python3 scripts/prepare-postgres-search.py verify --output-dir "$OUT"
```

먼저 `V20261023_0001__allow_variable_embedding_dimensions.sql`을 적용해야 한다. 이 migration은 기존 vector column을 dimension 가변 `vector`로 바꾸며 저장 벡터와 PK/FK/index를 유지하고, `embedding_dimensions`와 실제 vector 길이를 검사한다. 실제 임베딩은 snapshot을 검토한 뒤 별도 `embed` 실행으로 시작한다. 이 모드는 `EMBEDDING_PROVIDER=local`, 고정된 KURE 모델 revision과 1024차원, HTTP loopback 또는 `EMBEDDING_TRUSTED_HOSTS`에 정확히 등록된 `EMBEDDING_BASE_URL`, 그리고 audit에서 확인한 SHA-256을 모두 요구한다. 실행 중 진행률·policy별 성공/실패 원인은 원문 입력을 기록하지 않는 권한 `0600`의 `$OUT/preparation.log`에 즉시 기록된다. 실패한 policy는 해당 policy 트랜잭션만 롤백하고 다음 policy를 이어서 처리하며, 재실행은 모델·차원·버전·내용 해시가 모두 맞는 기존 vector를 재사용한다. 완료 후 `embedding-verification.json`의 active coverage, model identity, dimensions, snapshot/content hash 검증이 모두 맞는지 확인한다.

```bash
tail -f "$OUT/preparation.log"
python3 scripts/prepare-postgres-search.py embed \
  --expected-hash "$(jq -r .snapshotHash "$OUT/source-audit.json")" \
  --output-dir "$OUT"
```

통과 기준은 원천 audit 오류 0, stage `written == documents`, `stale == 0`, 실패 policy 0, verify의 policy identity/hash 일치, 8개 검색 질의 모두 결과 있음, 8개 chat 질의 모두 `ANSWER`, 저장 벡터 0, 임베딩 호출 0이다. NO_RESULTS는 그 자체로 장애가 아니며 질의별 결과는 리포트에서 검토한다. verify는 현재 원천과 저장 문서의 policy/tier/offer/carrier/grade/active/content hash 및 metadata identity를 비교한다. 개발 DB의 최종 snapshot hash는 `519cc3d994fd9fb7cd0485e3712dcf16556071a1aa362745f10c95a30f442292`다. 현재 개발 DB 결과는 `/Users/eddie/.local/state/itplace/pg-preparation-20261022/development`에 보관한다.

개발 DB 스키마 적용을 위한 durable project command는 아직 없다. 실제 migration은 private one-off 절차로 실행하고 개발 DB 확인을 마쳤다. 백업 dump는 보존하고 복원용 임시 DB는 삭제했다. 다음 schema 작업도 backup, migration 검증, rollback 계획을 별도 확인한 뒤 수행한다.

SKT/KT의 sourceCategory 미제공은 원문 분류가 확인되지 않은 상태이지 source URL/evidence 누락이 아니다. `OTHER`의 현재 수는 위 최신 audit 결과를 따른다.

아래의 2026-10-21 상태 문단과 2026-10-20 복제 DB 결과는 당시 이력이다. 최신 개발 DB 상태의 대체 근거로 사용하지 않는다.

> **2026-10-21 이력:** 당시 API `localhost:8080`과 UI `localhost:5173`은 `localhost:5432/itplace`를 사용했고, 검색 provider는 Elasticsearch였다. 검색 마이그레이션과 `benefit_search_document`는 아직 적용하지 않았다. 그 시점에는 pgvector가 설치됐으나 원천 스냅샷과 문서는 적재하지 않았고 임베딩 API 호출 및 저장 벡터는 0개였다. 자세한 당시 이용 방식 교정 결과는 다음 문단을 참조한다.
> **2026-10-21 후속 이력:** 레거시 membership 정리 마이그레이션 `20261021.0002`까지 적용했다. 이후 이용 방식 교정은 스키마 변경 없이 원본 정책 1,052건 중 1,015건의 `usagetype`만 갱신했다. 전체 ONLINE 198 / OFFLINE 241 / BOTH 37 / UNKNOWN 576, 활성 정책은 각각 189 / 239 / 37 / 233건이다. 공식 원문 보관본과 식별자가 일치한 665건 및 기존 이용 안내 387건을 사용했고, 활성 UNKNOWN 233건은 추가 근거 확보 대상으로 분리했다. 모든 BOTH에 양쪽의 실제 혜택 적용 근거를 확인했으며 발급·조회·바코드 표시를 사용 경로로 취급하지 않는다. 백업 복원 리허설, 비채널 데이터 불변 검증, 6개 실제 API 필터와 상세 UI 검증을 통과했다. 근거와 백업은 `/Users/eddie/.local/state/itplace/usage-channel-repair-20261021`에 있다.

> **격리 복원 리허설 정리 (2026-10-23):** 복구 검증용 임시 database를 삭제하고 백업 dump를 보존했다. cleanup 결과는 `/Users/eddie/.local/state/itplace/pg-preparation-20261022/cleanup.json`에 기록했다.

> **전환 범위:** Elasticsearch 검색/인덱싱을 PostgreSQL + pgvector로 옮기고, 질문형 추천·혜택 챗봇 흐름을 같은 원천 데이터 위에서 운영한다. 기존 API 경로·요청·응답과 화면은 유지한다.

## 복제 DB 검증 이력 (2026-10-20; 현재 기본 대상 아님)

- 별도 컨테이너 `itplace-pgvector-postgres-search-1`의 `127.0.0.1:15432`에 원본 로컬 DB를 복제했다. PostgreSQL 18.4, PostGIS 3.6.4, pg_trgm 1.6, pgvector 0.8.7 조합이며 실행 정의는 `infra/pgvector`에 있다. 기존 DB 컨테이너와 ES는 전환하지 않았다.
- `postgres` 프로필에서 `app.search.provider=postgres`를 사용한다. 혜택 RAG, 혜택 목록의 AI 사용/미사용 경로와 지도 키워드 검색을 PostgreSQL로 분기했다. 기존 API 계약과 현재 원천 데이터로 후보를 다시 검증하는 처리는 유지한다. 과거 관리자 전용 질문 메모리 API와 ES 의존성은 아직 남아 있으므로 ES 완전 제거 완료를 의미하지 않는다.
- `benefit_search_document`는 정책·등급의 복합 FK, 문서 ID 제약, nullable `vector(1536)`, 모델/버전/내용 해시, JSON 메타데이터와 텍스트 인덱스를 사용한다. 초기 벡터 검색은 필터를 적용한 exact cosine 검색이다.
- `PostgresBenefitRagSyncService.dryRun()`은 원천 primary의 일관된 snapshot으로 문서를 검사한다. `stageValidatedSnapshot(hash)`는 검토한 해시가 맞을 때 텍스트와 메타데이터를 저장하며 임베딩 API를 호출하지 않는다. 원천이 바뀌면 이전 snapshot을 게시하지 않는다. 자동 PG 임베딩 스케줄은 연결하지 않았고, PG 모드의 기존 ES seed/sync도 차단했다.
- 임베딩 실행 코드에는 실행 단위의 `(model, embeddingVersion, contentHash)` 캐시를 둔다. 같은 본문은 문서 ID와 정책이 달라도 하나의 임베딩 입력으로 합치며, 차원·유한 값·영벡터 검사를 통과한 결과만 재사용한다. 이는 향후 실행 시 중복 비용을 줄이는 준비이며 실제 API를 호출했다는 뜻은 아니다.
- 원문 사실을 중심으로 작성한 문서 버전은 `benefit-carrier-grade-rag-v3`이다. 임의의 이용 목적이나 제외 조건을 임베딩 본문에 추가하지 않는다. 원문 이용 안내와 구조화 조건은 메타데이터로 보존해 답변 근거에 사용한다.
- 복제 DB에는 SKT 362개, KT 148개, LGU 219개의 원문 기반 정책을 반영했다. 다이아몬드 원문 등급을 `DIAMOND`로 추가해 해당 28개를 반영했고, 전자랜드·베베쿡 2개는 혜택별 적용 등급이 미해결이라 비활성 보류 상태다. 검색 문서 전체 4,360개 중 활성 2,665개, **실제 벡터 0개**다. 활성 문서의 본문 해시는 2,033종이다.
- 원본 로컬 DB는 benefits 913 / policies 1,052 / tiers 4,405 / stores 73,715로 유지된다. 실행 중인 기본 백엔드나 운영 환경은 복제 DB로 바꾸지 않았다.
- AI를 끈 복제 DB의 실제 채팅 경로로 검색어 14개를 점검했다. `편의점`은 원문에 한글 카테고리 단어가 없어도 영문 업종 메타데이터로 검색되며, `CU`·`씨유`·`지에스25`·`학원` 같은 짧은 입력도 검색 전에 거절하지 않는다. 이 검증은 검색 결과 반환 여부를 확인한 것이며 의미 검색의 정답률이나 생성 답변 품질 평가를 대체하지 않는다.
- 공식 자료의 전체 검토 결과는 [원문 준비 점검](../../docs/pgvector-source-audit.md)에 기록한다. LGU 28개 항목의 `다이아몬드`는 원문 혜택 등급으로 저장·표시하며, 프로필 선택지는 늘리지 않았다. 현재 미해결 항목은 전자랜드·베베쿡의 혜택별 적용 등급 2건이다. 보류한 2개를 제외한 문서 audit 오류가 0이라는 결과를 전체 자료 준비 완료로 해석하면 안 된다.
- **사용자 결정:** 모든 자료가 준비되면 검증 결과를 먼저 알린다. 그 전에는 일부 자료만 임베딩하지 않는다. 검증용 프로그램은 AI 기능을 끄고 실행하며 유료 임베딩 실행 모드도 제거했다.
- 원문 캡처, 해시 manifest, import preview, 검증 결과와 원본 DB 백업은 `/Users/eddie/.local/state/itplace/pgvector-migration-20261020`에 보관한다. DB 백업은 Git에 넣지 않으며 해당 디렉터리/파일 접근 권한은 각각 0700/0600이다.
- DIAMOND 추가 전 검증: `./scripts/test-required.sh --no-daemon`의 전체 755개 테스트가 실패·생략 없이 통과했고, `./gradlew build -x test --no-daemon`도 통과했다. 최신 변경의 검증 결과는 아래 추가 기록 및 준비 결과 JSON을 따른다. 범위, 건수, 미해결 사항은 [준비 결과 JSON](evaluation/pgvector-preparation-2026-10-20.json)에 기록했다. 의미 검색과 생성 답변 품질 평가는 실제 API를 사용하지 않았으므로 아직 미완료다.

### DIAMOND 원문 등급 반영 검증 (2026-10-20)

- LGU+ 공식 원문의 `다이아몬드`를 `DIAMOND`로 저장·표시한다. 가입·프로필 검증은 기존 등급만 허용하며, `BASIC`과 동일하게 취급하지 않는다. 새 마이그레이션은 혜택 등급 제약만 확장한다.
- 기존에 보류했던 28개를 복제 DB에 반영했고, 원문 혜택별 대상이 불명확한 전자랜드·베베쿡 2개는 보류한다. 알려진 등급과 미지원 원문 등급이 섞인 경우도 원문을 보존하며, 명확한 공통 혜택만 알려진 등급에 연결한다.
- 사용자 API 전체 761개와 관리자 API 167개 테스트가 모두 생략 없이 통과했고, 양쪽 API 빌드도 통과했다. 관리자 검증에는 실제 Docker PostgreSQL 마이그레이션과 3사 전체 원문 아카이브 재생을 포함했다. 실제 복제 DB 상세 HTTP 응답과 PostgreSQL 텍스트 검색에서 VIP/VVIP/DIAMOND를 각각 조회하고 BASIC에는 해당 혜택이 노출되지 않음을 확인했다. 사용자 프론트 테스트 77개와 양쪽 프론트 빌드·린트, 사용자 프론트 타입 검사가 통과했다.
- 브라우저에서는 복제 DB API 응답을 재생해 기존 혜택 미리보기의 `VVIP · VIP · 다이아몬드` 표시를 확인했다. 기존 실행 서비스의 DB나 운영 환경은 바꾸지 않았다. 임베딩 API 호출 및 저장 벡터는 0건이다.
- 백업, 원문 재처리 결과, 실제 import/audit, HTTP·검색 검증 결과 및 화면 캡처: `/Users/eddie/.local/state/itplace/diamond-grade-20261020`.

아래는 구현 전에 작성한 조사·설계 기록이다. “현재”라는 표현과 건수는 당시 원본 로컬 snapshot을 뜻한다. 아래의 ES shadow 비교·자동 동기화·운영 전환은 실행 완료 사실이 아니다. 실제 구현 상태와 임베딩 보류 결정은 위 내용을 우선한다.

## 근거와 상태 표기

- **코드에서 확인:** `/Users/eddie/dev/itplace/itplace-user-api`의 현재 작업 트리. 기존 미커밋 변경이 포함되어 있으므로 아래 경로는 브랜치 상태가 바뀌면 재확인한다. 재현용 Gradle 실행은 하지 않았다.
- **로컬 readiness snapshot (2026-10-20, 읽기 전용·운영 미검증):** [`docs/evaluation/local-readiness-snapshot-2026-10-20.json`](evaluation/local-readiness-snapshot-2026-10-20.json)에 PostgreSQL 18.4 (`develop_server-postgresql`), 설치된 pg_trgm 1.6/PostGIS 3.6.4, vector 미제공, ES 8.14.1 및 인덱스 확인을 기록했다. ES `benefit=4405`, `store=0`; 코드가 실제 사용하는 `questions`와 오래된 주석의 `question_embeddings` 모두 404다. DB counts는 benefits 913 (active 758), policies 1052 (active 698), tier rows 4405 (active 2894), partners 443, stores/geocoded active stores 73715. 따라서 ES benefit 문서 수는 benefit 엔티티 수와 다르며, 현재 ES store/questions 결과는 비교용 golden baseline이 아니다. 이는 local-only snapshot이며 운영 인벤토리가 아니다.
- **제안:** 이하 `제안` 표기를 붙인 스키마·플래그·임계값은 구현 목표이며 운영 측정값이 아니다.
- **운영 미확인:** 운영 PostgreSQL 버전/확장 권한, 운영 DB 및 검색엔진 실측, 배포 토폴로지·복제 수, 트래픽/지연 SLO, 프로덕션 데이터 건수, OpenAI 호출비와 토큰·rate-limit, 운영 백업/복구 절차는 아직 확인되지 않았다. 로컬 환경을 운영 사실로 간주하지 않는다.

## 현재 호출 경로와 Elasticsearch 소유자

| 기능/호출 | 현재 경로와 저장소 | 전환 시 동작 |
| --- | --- | --- |
| 혜택 문서 생성 | `BenefitRagSourceQueryService.loadSourceSnapshot()`은 read-only transaction에서 source datasource가 아니라 `DataSourceRouter` 규칙상 replica를 선택한다. `benefit`, `partner`, `benefitCarrierPolicy`, `carrierTierBenefit`을 batch 조회하고 기존 `BenefitRagDocumentBuilder`로 pending 문서/text 구성 | builder/문서 의미를 그대로 재사용. DB 저장 포트만 추가 |
| 혜택 RAG 임베딩·동기화 | `BenefitIndexer`(기동 시 비동기)와 `BenefitRagSyncService`(설정된 주기)가 snapshot 외부에서 임베딩 호출, 기존 임베딩 재사용, ES `benefit`에 저장·누락 문서 tombstone | ES 대신 PostgreSQL 문서 테이블. MVP는 scheduled full-source snapshot + batched hash comparison으로 변경 문서만 embedding하고 complete-run reconciliation한다. outbox 증분은 측정 후 필요할 때만 추가 |
| 개인화 추천 | `RecommendationController` → `RecommendationServiceImpl` → `OpenAIServiceImpl.vectorSearch()` → `BenefitSearchService.queryVector()` → ES `benefit`; candidate가 현재 관계형 DB의 엔티티/정책으로 hydrate된 다음 LLM rerank/explain | 기존 `Candidate`·`BenefitSearchService` 계약 보존, SQL 벡터/lexical 결과를 같은 hydrator에 넘김. LLM은 기존 한 번의 rerank 경로만 실행 |
| 혜택 채팅 | 최신 user-api의 `BenefitChatController`(`/api/v1/questions/chat`, 코드에서 확인) → `BenefitChatService`/planner·answerer → `BenefitSearchService.queryChat()`/후보 resolve; 대화 요청의 검색에 ES 사용 | 동일 계약의 PG adapter. 채팅 답변 LLM은 검색 shadow에서 호출하지 않음 |
| 질문형 추천 | `GET /api/v1/questions/recommend` → `QuestionSearchController` → `QuestionRecommendationServiceImpl`: 금칙어 → `QueryIntentExtractor` → embedding → `BenefitSearchService.queryHybrid()` → candidate guard → 선택 LLM → 매장/혜택 DB hydrate. **현재 운영 추천은 질문 CSV/`questions` 인덱스를 사용하지 않는다.** | 기존 파이프라인에서 Benefit 검색 구현만 바꿈. DB 내 현재 활성/만료/등급 조건을 반영 |
| 혜택 목록 키워드 | `BenefitController` → `BenefitServiceImpl` → `BenefitHybridSearchServiceImpl`; AI enabled면 ES lexical + embedding kNN RRF, off여도 `searchLexical()`이 ES를 직접 사용한다. 따라서 ES 완전 제거 전에 feature-off 경로도 PG lexical adapter로 옮겨야 한다. hit benefit ID를 DB에서 조회 | API 계약·페이징 유지. PG full-text/substring + optional embedding의 rank fuse; exact-total semantics 기존 제한 확인 필요 |
| 지도 매장 키워드 | `StoreController /nearby/search[ /previews]` → `StoreServiceImpl` → `StoreSearchServiceImpl`; ES `store`에서 partnerName 100개, storeName/business 200개 다중 검색 후 DB에서 거리·상세를 계산 | 검색 hit 대신 DB의 `store`·`partner` 조회. 지도 위치/거리 계산은 기존 PostGIS/JPA 서비스에 남김 |
| 과거 질문 메모리 | `POST /api/v1/questions/save`는 질문마다 랜덤 UUID, embedding 외부 호출 후 ES `questions`에 질문·카테고리·벡터 저장. `GET /api/v1/questions/search?query=`는 embedding 후 top-1 ES 검색. CSV `QuestionIndexer`는 `(question|category)` SHA-256 키를 쓰며 인덱스에 문서 하나라도 있으면 seeding 전체 skip. 두 API는 deprecated이며 `SecurityConfig.adminOnlyEndpoints()`에서 ADMIN 전용으로 실제 제한된다. 반면 `/questions/chat`은 일반 인증 필요(`anyRequest().authenticated()`) 경로다. | 제안: 기존 경로·응답을 유지하는 DB 저장소로 옮기고 CSV는 hash upsert로 재실행 안전하게 한다. Search는 cosine top-1. UUID save는 idempotency key가 아니므로 보존 시 중복 저장 동작도 보존 |
| 인덱스/설정/의존성 | `ElasticsearchConfig`는 mandatory `elasticsearch.host/port/username/password`를 주입하고 공유 client 생성. local/prod yml 및 `build.gradle` ES Java client 8.13.2. `/actuator/health` ES health 꺼짐. Seed 설정: `app.ai.enabled`, `app.ai.benefits.seed/sync.enabled`, `app.ai.questions.seed.enabled`, `app.ai.stores.seed.enabled` | 전 기능 이관 후 config/프로퍼티/Gradle dependency 및 ES 전용 빈 제거. 배포 환경의 mandatory ES 변수를 제거하고 DB 장애·health를 별도 관리 |

**클라이언트 점검:** 원본 `itplace-user-front`와 `itplace-user-app`은 `/api/v1/questions/recommend`, `/api/v1/recommendations`, 지도 검색 등 user API만 호출한다. `itplace-admin-front`/`itplace-admin-api`에는 ES SDK나 직접 ES 호출이 발견되지 않았다. 모바일·웹 API DTO를 바꾸지 않는다. release/type-compat 복사본은 검색 대상에서 제외했다.

**현재 API 계약 유지:** 추천은 `GET /api/v1/recommendations`의 기존 `ApiResponse<Recommendations>`와 `GET /api/v1/questions/recommend?question&lat&lng&carrier&grade`의 `ApiResponse<RecommendationResponse>`를 유지한다. 채팅 `POST /api/v1/questions/chat`은 질문(최대 500), 최근 메시지(최대 12), `previousCandidateKeys`(최대 8), 선택 좌표·통신사·등급을 받고, 같은 envelope 안의 기존 `BenefitChatResponse`(reason, partners, status, suggestedQuestions, traceId)를 반환한다. 후보 키는 기존 `benefit:{benefitId}:policy:{policyId}:tier:{tierId|policy}`여서 DB 전환 중에도 유지해야 한다. `/questions/save` 성공 body 문자열과 `/questions/search`의 `matchedQuestion/category/score` 또는 no-hit message를 요청 DTO 변경 없이 보존한다. 지도 검색 API의 `StoreDetailResponse`/preview DTO·순서는 서비스 계약으로 고정하고, 새 ranking이 현재 기본 fallback 결과 순서를 바꾸지 않도록 golden sample을 만든다.

## 데이터 계약과 제안 스키마

### 안정적인 문서 정체성

현재 `BenefitRagDocumentBuilder.documentId()`는 아래 identity를 만들고 `contentHash()`는 `SHA-256(embeddingVersion + "|" + searchableText)`를 만든다. 기존 해시 계약을 바꾸지 않고 그대로 재사용 판정에 써야 한다.

```text
benefit:{benefitId}:policy:{benefitCarrierPolicyId}:tier:{carrierTierBenefitId|policy}
```

한 benefit/policy에 등급 row가 없으면 `tier:policy` 한 건, 하나 이상 있으면 각 `carrierTierBenefit`별 문서다. `benefitId`, `policyId`는 현재 builder에서 null이 아니며, `tierBenefitId`만 null일 수 있다. 전체 등급 의미는 별도 `isAllGrade`이고 실제 grade null과 혼동하지 않는다. 기존 PK/FK가 정수형이므로 제안 저장소에서도 자연키 세 컬럼을 유지하고 다음으로 identity를 강제한다.

```sql
UNIQUE NULLS NOT DISTINCT (benefitid, benefitcarrierpolicyid, carriertierbenefitid)
```

PostgreSQL 15+의 `UNIQUE NULLS NOT DISTINCT`는 null tier 행도 한 policy에 최대 하나만 보장한다. 현 로컬 18.4에서는 지원된다. 운영 버전이 15 미만이면 null sentinel을 쓰지 않고 partial unique indexes로 `tier IS NULL` 1행, `tier IS NOT NULL` 복합 key를 각각 보장한다. DDL에는 FK를 독립적으로 거는 것만으로 막지 못하는 policy-benefit 또는 tier-policy mismatch를 composite FK로 차단했다. composite FK를 위해 원천 테이블의 `(id, parent_id)` unique constraint를 추가한다. 원천 테이블 제약을 늘리는 것이 허용되지 않으면 검색 테이블에서 `benefitid`를 중복 저장하지 말고 policy/tier join으로 유도하는 대안을 택한다. PostgreSQL 인덱스 문서: [Unique Indexes](https://www.postgresql.org/docs/current/indexes-unique.html).

제안 테이블 이름 `benefit_search_document` (신규, JPA 소유 원천 테이블과 분리):

```sql
-- 실제 로컬 스키마는 소문자화된 public.benefit, benefitcarrierpolicy,
-- carriertierbenefit 및 benefitid/benefitcarrierpolicyid/carriertierbenefitid 컬럼이다.
-- 상위 관계를 composite FK로 강제하기 위해 원천 부모의 composite unique가 필요하다.
ALTER TABLE benefitcarrierpolicy
  ADD CONSTRAINT uq_bcp_id_benefit UNIQUE (benefitcarrierpolicyid, benefitid);
ALTER TABLE carriertierbenefit
  ADD CONSTRAINT uq_ctb_id_policy UNIQUE (carriertierbenefitid, benefitcarrierpolicyid);

CREATE TABLE benefit_search_document (
  document_id text PRIMARY KEY,
  benefitid bigint NOT NULL,
  benefitcarrierpolicyid bigint NOT NULL,
  carriertierbenefitid bigint,
  carrier text NOT NULL,
  grade text,
  is_all_grade boolean NOT NULL DEFAULT false,
  usage_type text,
  active boolean NOT NULL,
  searchable_text text NOT NULL,
  metadata jsonb NOT NULL,
  embedding vector(1536),
  embedding_model text NOT NULL,
  embedding_dimensions smallint NOT NULL CHECK (embedding_dimensions = 1536),
  embedding_version text NOT NULL,
  content_hash char(64) NOT NULL,
  source_updated_at timestamptz,
  indexed_at timestamptz NOT NULL DEFAULT now(),
  deleted_at timestamptz,
  CONSTRAINT fk_bsd_benefit FOREIGN KEY (benefitid)
    REFERENCES benefit(benefitid) ON DELETE CASCADE,
  CONSTRAINT fk_bsd_policy_benefit FOREIGN KEY (benefitcarrierpolicyid, benefitid)
    REFERENCES benefitcarrierpolicy(benefitcarrierpolicyid, benefitid) ON DELETE CASCADE,
  CONSTRAINT fk_bsd_tier_policy FOREIGN KEY (carriertierbenefitid, benefitcarrierpolicyid)
    REFERENCES carriertierbenefit(carriertierbenefitid, benefitcarrierpolicyid) ON DELETE CASCADE,
  CONSTRAINT uq_bsd_identity UNIQUE NULLS NOT DISTINCT
    (benefitid, benefitcarrierpolicyid, carriertierbenefitid),
  CONSTRAINT ck_bsd_document_identity CHECK (
    document_id = 'benefit:' || benefitid::text || ':policy:' || benefitcarrierpolicyid::text ||
      ':tier:' || coalesce(carriertierbenefitid::text, 'policy')
  ),
  CONSTRAINT ck_bsd_policy_tier_grade CHECK (
    carriertierbenefitid IS NOT NULL OR grade IS NULL
  )
);
```

아래 DDL은 제안 예시이며 실행하거나 검증한 migration이 아니다. 로컬 snapshot의 lowercase 테이블/컬럼명을 기준으로 썼고, 운영 스키마와 Flyway conventions 및 복합 unique 추가 영향을 실제 적용 전에 확인한다. `metadata`에는 현재 builder가 제공하는 표시/검색 근거(source fields, description/manual/context, online/offline split, discount, businessType/useCases/negativeUseCases/tags)를 보존한다. 검색·필터 빈도가 높은 carrier/grade/is_all_grade/usage_type/active는 typed column으로 유지하고, JSONB만으로 eligibility 필터를 수행하지 않는다. `benefitid`를 포함한 composite FK로 policy-benefit 및 tier-policy 부모 관계를 검증한다. partner 이름/category는 benefit→partner join에서 현재값을 사용한다. text snapshot은 검색 후보 근거 및 LLM에 제공되는 당시 내용을 보존하지만, API 응답용 최신 혜택/매장 상세는 기존 DB hydrator가 다시 읽는다.

검색은 다음 원칙으로 최신성을 지킨다. typed `carrier`, `active`, `grade`, `usage_type`는 builder snapshot 값을 사전 필터에 활용할 수 있지만, DB filter 결과는 항상 원천 policy/tier join 값과 대조해 eligibility를 확정한다. 실제 converter 값 casing을 보존하며, local snapshot에서 active policy의 channel이 전부 BOTH인 사실은 online/offline 구분 품질의 증거가 아니다.

1. 임베딩 검색에서만 `embedding IS NOT NULL`을 조건으로 둔다. 임베딩 없는 행은 lexical 경로에 남겨 둔다.
2. 현재 builder는 `benefit.active`/`policy.active`가 명시적으로 `false`일 때만 비활성으로 본다. 기존 eligibility의 null 허용 semantics를 보존해 `document.active = true AND benefit.active IS NOT FALSE AND policy.active IS NOT FALSE`로 검색한다. 데이터 정리와 `NOT NULL` 강화는 별도 migration 없이는 하지 않는다. tier가 null이면 policy 단위 문서이고, 값이 있으면 실제 해당 policy의 tier row와 일치해야 한다. 요청 carrier가 있을 때 정책 carrier가 일치해야 하며, carrier 생략 시 기존 cross-carrier query semantics를 보존한다. `isAllGrade=true`는 같은 carrier 범위 안에서 grade 제한만 면제한다. 일반 grade 문서는 요청 grade와 일치해야 한다. 현재 local snapshot 기준 active 698개 policy의 UsageType은 모두 BOTH로 저장돼 있다. 검색 schema는 기존 enum converter/실제 lowercase 값 semantics와 맞추고, online/offline 필터 결과는 현재 데이터에서 통과 여부를 검증할 수 없으므로 upstream 데이터가 준비될 때까지 acceptance gap으로 남긴다. local snapshot 기준 443 partners 중 224개는 category가 비어 있어 category 기반 recall도 별도 data-readiness 조건이다.
3. 혜택 만료일 컬럼은 현재 확인한 `Benefit`/`BenefitCarrierPolicy`/`BenefitPolicy` 엔티티에서 발견되지 않았고, readiness snapshot에서 active 698개 정책 모두 `sourceCapturedAt`가 null이며 `conditions`는 비어 있다. 따라서 현재 local snapshot만으로 만료·구조화 조건 기반 제외를 검증할 수 없다. 운영 검색에서 해당 제외를 약속하기 전에 upstream source가 필드를 제공하는지 별도 확인/보강해야 한다. 향후/원천 데이터에 기간 컬럼이 있으면 실제 정책 semantics를 합의한 뒤 `valid_from <= now AND (valid_to IS NULL OR valid_to > now)`를 원천 JOIN에 더한다. 문서 `indexed_at`을 만료 기준으로 쓰지 않는다.
4. FK cascade는 원천 삭제 때 검색행 고아를 막는다. 논리 비활성화는 cascade하지 않으므로 검색 SQL의 원천 active join이 stale `active=true` snapshot을 차단한다. 정기 reconciliation은 물리 정합성/누락 복구를 위해 별도로 수행한다.

### 질문 메모리 테이블

제안 `question_memory`: `id text primary key`, `source_key char(64) unique null`, `question text not null`, `category text not null`, `embedding vector(1536) not null`, `embedding_model`, `embedding_dimensions`, `embedding_version`, `content_hash char(64)`, `created_at timestamptz`. `id`는 API의 UUID 문자열 또는 CSV의 현재 결정 ID(hash(question + "|" + category))를 그대로 받아 기존 identity를 보존하고, CSV ID는 `source_key`에도 저장하여 `INSERT ... ON CONFLICT(source_key) DO UPDATE`로 재실행한다. API `/save`의 랜덤 UUID는 유지한다. delete/archive API가 현재 없으므로 우선 삭제 기능은 새로 만들지 않고, 운영 보존 정책은 배포 전 결정한다. 질문 검색은 질문 테이블이 작더라도 `ORDER BY embedding <=> :query LIMIT 1` exact로 시작한다. 현재 ES kNN 응답은 raw ES `_score`를 `score`로 반환한다. PostgreSQL cosine similarity/distance는 같은 수치 의미라고 가정하지 않는다. 이관 전 실제 ES mapping/version의 `_score` 변환 semantics를 fixture query/vector로 측정하고, PG score mapping을 명시적으로 정해 `score` 값의 순서·범위·동점·no-hit 응답 계약을 테스트한 다음 endpoint adapter를 승인한다.

현재 임베딩 모델 설정 기본값은 `EmbeddingServiceImpl`의 `spring.ai.openai.embedding.model=text-embedding-3-small`; 질문 mapping은 1536 dims, 혜택 ES mapping은 1536 dims, builder 버전은 `benefit-carrier-grade-rag-v2`다. builder의 content hash는 모델명/차원이 아니라 version+searchableText만 포함한다. readiness snapshot에서 ES 4405개 혜택 문서에 개별 embedding-model 필드가 없음을 확인했다. 따라서 구 ES 벡터를 자동 재사용하지 말고, 배포/인덱스 생성 manifest·설정 이력 등으로 실제 모델이 증명된 경우에만 import manifest에 모델 provenance를 붙인다. provenance 없는 기존 vector는 비용·정확성 리스크를 피하려고 재임베딩 대상으로 둔다. 배포 전 실제 운영 모델과 차원을 확인하고 모든 embed 응답에 `size == configured dimensions`를 검증한다. 모델·차원이 바뀌면 모델/version을 올려 재임베딩한다. 새 DB row의 기존 벡터 재사용은 `(embedding_model, dimensions, embedding_version, content_hash)`가 모두 일치할 때만 허용한다. 현재 builder의 `content_hash`는 version+searchableText의 SHA-256이므로 이를 임의로 모델까지 포함하는 형태로 변경하지 않는다. 검색 text를 바꾸면 기존 builder hash가 달라져 재임베딩된다. 모델은 이름이 같아도 provider 변경이나 의미 변경이 있었는지 확인한다.

## 검색 방식: 안전한 기본값부터

### 벡터

제안 초기 쿼리는 **필터를 포함한 exact cosine distance**다. 이관 대상 로컬 benefit 문서가 4,405행이라는 전달된 관측치에선 HNSW 구축·메모리 비용을 먼저 질 이유가 적다. 쿼리는 `WHERE`로 eligibility/carrier/grade 조건을 먼저 적용하고 `ORDER BY embedding <=> :vector LIMIT :candidateWindow`; cosine distance를 similarity로 변환할 때 기존 점수 스케일을 함부로 동일하다고 가정하지 않는다. 기존 RRF는 lexical/vector 개별 순위 기반으로 유지해 거리점수 calibration을 피한다. PostgreSQL `EXPLAIN (ANALYZE, BUFFERS)` 및 같은 query corpus에서 recall@k와 p95를 측정한 뒤 정확 검색이 목표 SLO를 넘을 때만 ANN을 추가한다. 현재 local application이 primary/replica routing을 사용한다. 읽기 전환 후 replica lag로 인한 문서 freshness는 원천 테이블과 검색 문서 양쪽의 lag 영향을 받으며, shadow도 양쪽에서 같은 consistency point를 읽지 않으면 false mismatch가 날 수 있다. 비교/worker는 필요 시 source를 직접 조회하고, 사용자 검색은 source/replica routing 정책을 명시적으로 정한다.

ANN을 도입하는 경우, 같은 필터 조건으로 exact 결과를 ground truth 삼아 recall·p95·CPU/메모리/인덱스 크기를 비교한다. pgvector HNSW는 필터가 인덱스 검색 뒤 적용되어 selective filter에서 반환 수가 줄 수 있으므로 iterative scan (`strict_order`)과 `hnsw.ef_search` 조정을 포함해 평가한다. carrier/grade별 부분 인덱스나 partition은 데이터 분포와 쿼리 빈도가 증명한 뒤 선택한다. IVF는 충분한 행 수 및 재학습 가능한 운영 경로가 필요하므로 초기 선택에서 제외한다. 근거: [pgvector 검색·필터·iterative scan 문서](https://github.com/pgvector/pgvector#filtering).

### 한국어 lexical/짧은 키워드

PostgreSQL 기본 full-text parser는 공백이 없는 한국어에서 형태소 기반 ES analyzer와 같은 recall을 보장하지 않는다. `pg_trgm`은 제휴처명/오탈자/부분 문자열에 GIN/GiST 인덱스를 제공한다. `LIKE '%q%'`는 index가 지원하는 정확한 substring 매칭이므로 1~2자라고 올바른 일치를 잃는다고 단정하면 안 된다. 다만 매우 짧은 패턴은 추출 가능한 trigram이 적거나 없어 index가 전체를 훑을 수 있고, similarity threshold 기반 fuzzy 결과가 줄 수 있다. 이는 정확한 substring 결과가 사라진다는 뜻이 아니다. latency와 threshold-recall을 분리 측정한다. PostgreSQL 공식 문서: [pg_trgm](https://www.postgresql.org/docs/current/pgtrgm.html), [Full Text Search](https://www.postgresql.org/docs/current/textsearch.html). 초안 기본안은 새 형태소 라이브러리 설치 없이 아래 순서를 조합하고, 로컬/운영 corpus로 측정한다.

- 제휴처명·혜택명·통신사 혜택명은 정규화 equality/prefix 우선 (B-tree, `lower`/현재 normalized 컬럼 재사용 가능 여부 확인).
- 길이 3자 이상은 `pg_trgm` similarity/`LIKE '%q%'` 후보를 이용하고, name/title field weight를 높인다. 이름이 짧은 케이스는 `pg_trgm` threshold에 의존하지 않고 substring/정규화 exact 경로를 먼저 둔다.
- 일반 문장/긴 질문은 `to_tsvector` 기반 FTS를 보조로 시험하되, 현재 기본 parser의 한국어 형태소 미지원 한계를 문서화하고 trigram/vectors와 RRF로 결합한다. 운영에서 실제 한국어 형태소 품질이 요구치를 못 넘으면 다음 선택지를 별도 benchmark: (a) 운영이 승인한 substring 검색 확장을 별도 benchmark하고 운영 설치/관리 가능성 확인, (b) 형태소 분석 결과를 애플리케이션에서 생성해 별도 검색 text 컬럼에 저장(라이브러리/라이선스/버전 고정 필요), (c) 외부 검색엔진 유지. trigram/bigram 인덱싱은 형태소 분석을 대체하지 않으므로 두 품질 문제를 혼동하지 않는다. 설치는 이 승인 범위 밖이고 여기서 하지 않는다.
- `pg_trgm` similarity threshold는 session-global 설정 변경이 동시 요청에 새지 않게 쿼리 범위 local 설정 또는 명시적 operator를 사용한다. 검색식/가중치/threshold는 ES 매칭률을 보고 조정한다. 단, 기존 `docs/performance/README.md`는 historical map 키워드 실험에서 짧은 검색어가 거의 개선되지 않았고, 대상이 이번 전환 corpus와 다르다고 명시한다. 이는 현재 baseline이 아니라 “짧은 한국어 검색어를 별도 KPI로 두어야 한다”는 위험 근거다. ES `store`가 현재 0이므로 지도 경로의 과거 ES benchmark를 만들 수 없다.

## 전체 snapshot MVP 동기화·백필·경쟁 조건

로컬 snapshot에서 ES 혜택 문서는 4,405건이다. 이 규모만으로 새 outbox/dirty queue 인프라를 출시 전제조건으로 두지 않는다. **MVP는 기존 scheduled full-source snapshot + PostgreSQL hash 비교 + 변경된 문서만 embedding + complete-run reconciliation**을 사용한다. 검색 문서 builder와 현재 `BenefitRagSyncService`의 snapshot → builder → transaction 종료 → 외부 embedding 경계를 재사용한다. embedding HTTP 호출이나 대량 작업을 DB transaction 안에서 하지 않는다. MVP 성능·최신성 측정에서 이 경로가 목표를 충족하지 못할 때만 아래 선택적 outbox/worker로 승격한다.

제안 MVP 순서:

1. **패키지/이미지 선행:** 2026-10-20 조사 당시 로컬 이미지에는 vector가 없었다. 현재 개발용 `develop_server`에는 pgvector 0.8.7 확장이 설치되어 있다. 다른 대상에서는 이미지/서버 패키지에 extension 파일이 있는지 확인하고, 승인된 경우에만 `CREATE EXTENSION vector`를 적용한다. 패키지 설치 방식에 따라 DB 재기동이 필요할 수 있으므로 대상 환경별로 확인한다. managed 운영 DB 지원 및 extension 권한은 별도 확인한다.
2. 새 검색 테이블과 B-tree/FK/filter/text 인덱스를 만든다. 먼저 MVP를 exact vector query로 시작하므로 ANN 인덱스는 만들지 않는다.
3. full sync 시작 시 policy·benefit·tier를 짧은 bounded `REPEATABLE READ, READ ONLY` transaction으로 source primary에서 읽고 transaction을 닫는다. 단일 READ COMMITTED read-only transaction의 여러 SELECT는 동일 snapshot을 보장한다고 가정하지 않는다. 고정 snapshot이 불가능한 환경은 source revision/cursor를 기록하고 publish 직전 최신 revision/hash를 재검증해 변경된 key를 버린 뒤 다음 run에서 따라잡는다. 외부 embedding은 snapshot transaction 밖에서만 한다. 현재 local snapshot 관측량은 913 benefits/1,052 policies/4,405 tier rows이며, 운영 수량은 별도 재확인한다. checkpoint가 필요한 backfill은 ordered composite key `(benefit_id, policy_id, tier_id NULLS FIRST)`를 저장해 재시작한다.
4. snapshot을 기존 builder로 문서화한 뒤 DB의 동일 stable identity를 읽어 `content_hash`, `embedding_model`, dimensions, version을 비교한다. 동일하고 모델 provenance가 검증된 DB 벡터만 재사용한다. 최초 ES import는 개별 model provenance가 없으므로 별도 배포 manifest가 증명하지 않는 한 재임베딩한다. API timeout/429/5xx는 bounded concurrency와 rate limit으로 retry+jitter하고, permanent error/차원 오류는 실패 row와 운영 요약으로 남긴다.
5. 외부 embedding이 끝나면 source primary에서 같은 identity의 현재 revision/hash를 batch 확인한다. snapshot 뒤 원천이 바뀌었거나 사라진 문서는 오래된 payload로 publish하지 않는다. 한 benefit-policy의 no-tier `tier:policy` 문서가 tier row 문서들로(또는 반대로) 전환될 때 새 형제 문서 upsert와 사라진 identity tombstone을 같은 publish transaction으로 처리해 두 variant가 함께 active가 되지 않게 한다. 외부 API 호출은 이 transaction 밖이다.
6. complete-run reconciliation은 이번 snapshot의 전체 current identity와 검색 테이블을 비교해 원천에서 사라진 문서를 `active=false, deleted_at=now()` tombstone한다. 원천의 soft inactive는 query-time join으로 즉시 제외한다. hard delete는 FK cascade가 검색 행을 제거한다. sync는 advisory lock/lease로 인스턴스 간 중복 실행을 막고, run ID·snapshot 시각·문서 수·재사용/임베딩/실패/tombstone·elapsed·max source lag를 기록한다.
7. sync 도중 snapshot 이후 발생한 변경은 same-key publish validation으로 과거 내용이 새 값을 덮지 못하게 한다. 변경이 validation 이후 생겼거나 새 key가 생긴 경우 다음 fixed-delay full run이 수렴시킨다. 그 전까지 동일 active identity의 이전 검색 text가 남을 수 있어 recall 최신성은 sync cadence에 좌우되지만, hydrate된 응답은 원천 DB의 최신 필드를 사용한다. Canary/read cutover 전에 연속 complete runs와 catch-up reconciliation이 drift 0 및 freshness target을 충족해야 한다. freshness 허용 지연이 full-run cadence보다 짧아야 한다는 측정 결과가 나오면 outbox 확장으로 승격한다.
8. DB 저장 실패 후 embedding API를 다시 호출하면 비용이 중복될 수 있다. at-least-once로 취급하고, 필요할 때만 `document key + content_hash`로 dedupe 가능한 durable embedding staging을 추가한다. stale publish validation, bounded retries, failed-document report, scheduler lock, full reconciliation은 outbox 유무와 무관하게 필수다.

### 측정 후 선택할 outbox/dirty-key 확장

full scan 쿼리 부하, 동기화 시간, replica/source read lag, 임베딩 호출량 또는 운영 freshness가 **미리 정한 목표를 반복해서 못 맞추는 것이 관측될 때** transactional outbox/dirty-key worker를 설계한다. 이는 4,405건으로 시작하는 현재 MVP의 release gate가 아니다. 승격 시에도 ID 변경 이벤트만 import transaction에서 기록하고 외부 embedding은 transaction 밖에서 한다. delete 및 tier 형제 identity 전환 이벤트, event retention, monotonic watermark, source revision, 재시도/경합/lock, complete-run reconciliation을 함께 해결해야 한다. queue는 full reconcile을 대체하는 유일한 정합성 장치가 아니라 빠른 catch-up 경로로 두고, source+PG 상태의 주기 비교는 유지한다.

## 전환 단계와 트래픽 제어

| 단계 | 배포/동작 | 통과 조건 |
| --- | --- | --- |
| 0. 사전검증 | 운영 DB/extension 권한·버전 확인, baseline/검색 corpus 고정, 현재 ES 응답과 API contracts 저장. 현재 문서 builder 및 검색 port를 재사용하는 구조 확정 | 운영 확장 승인지, DB/ES/API latency·에러·비용 baseline. 현재 비교 corpus 60개는 provisional smoke/coverage set이며 정답 라벨도 검토 대기다. 정식 release gate는 최소 100개 검토 사례와 별도 held-out query 집합, 짧은 한국어 및 핵심 그룹 사례를 추가해 고정 |
| 1. DB 준비 | vector 확장 사전 설치, additive migration + empty search tables, 인덱스는 PK/FK/filter/text 위주 | 기존 앱이 테이블을 무시해 정상 구동, Flyway rollback/forward 전략 검토, extension version 확인 |
| 2. PG backfill | 기존 scheduled full snapshot/reconcile 경로를 PG 저장소에 연결해 bounded embedding batches로 최초 적재. ES 읽기 지속 | source/검색 identity count·FK·tier 변형·active semantics 일치, 실패 0 또는 승인 예외, hash/model/dim 검증. API 호출은 DB tx 밖 |
| 3. MVP sync catch-up | same-key revision/hash validation + complete-run reconciliation, scheduled full sync를 반복해 DB와 source 수렴 | lock 적용, stale publish 없음, 연속 full run에서 drift 0, freshness target 통과. 이 시점에 scan 비용/lag 측정 |
| 4. Shadow 비교 | PG 후보 검색 결과를 같은 요청의 기존 ES 결과와 비교·기록. **질문형 추천/챗봇의 query embedding은 기존 호출 결과를 한 번만 재사용**하고 각 검색 엔진에 동일 embedding과 lexical 검색에 필요한 원 질문 문자열을 PG 쪽에도 전달한다; logs에는 원문 query나 vector를 저장하지 않는다. 검색 이후 LLM·매장 hydrate·응답 조립은 ES 쪽만 실행하여 중복 LLM 비용 0 | cohort별 Recall@K/overlap/NDCG·빈 결과·latency 보고서. 응답 데이터/PII·원문 질문/벡터를 로그에 쓰지 않고 trace ID, engine, IDs/hash, 점수 요약만 제한적으로 기록 |
| 5. Canary PG read | 안정된 cohort로 PG 검색을 실제 응답에 사용, ES는 요청 복제하지 않고 같은 embedding만 활용 가능한 검색-only shadow. LLM은 선택된 실제 경로에서 한 번만 호출 | 별도 API 계약 변경 없이 오류율, p95, 검색 품질/매장 유효성, freshness 목표 통과 |
| 6. 확대/ES 종료 | cohort 확대, rollback 기간 동안 기존 ES 쓰기/full sync를 계속 유지. 안정화 후 읽기 복제 종료 → ES 쓰기·bootstrap 끄기 → ES properties/dependency 제거 → cluster 폐기 | 운영 기간 동안 drift·장애·비용/성능 목표 통과, 백업/복구와 과거 문서 유실 대응 승인, ES 잔존 caller/secret/모니터링 없음 |

### Shadow·cohort의 구체 규칙

제안 feature flags: `app.search.postgres.enabled`, `app.search.shadow.enabled`, `app.search.read-cohort-percent` (초기 0), `app.search.cohort-salt-version`; backfill/sync on-off는 별도 플래그. read 선택은 인증 사용자는 `hash(userId + fixed salt) % 100`, 비로그인은 안정적인 클라이언트가 없는 경우 `hash(request correlation key)`가 안정적이지 않으므로 **인스턴스/요청 랜덤 배정은 피한다**. 우선 인증 사용자 cohort만 올리고 비회원은 운영 기준을 정하기 전 기존 ES. 선택은 요청마다 무작위 하지 말고 버전이 있는 고정 해시로 배포간 동일해야 한다. 사용자 식별자는 로그에 원문으로 남기지 않는다.

Shadow 검색은 LLM을 호출하지 않으므로 운영 ES 결과와 PG 결과의 비교 비용은 DB 쿼리만 추가한다. 제안 안전 한도는 feature-flag 샘플 1%부터, 별도 bounded executor 인스턴스 전체 동시 최대 2개, DB statement/search timeout 150ms, queue 최대 100개(초과 시 drop), primary read는 해당 shadow DB budget을 둔다. 작업은 요청 thread에서 기다리지 않고, timeout/exception/queue-full은 사용자 응답을 차단하지 않고 metric만 남긴다. 이 값은 성능 측정값이 아닌 초기 보호 설정 제안이며, 운영 baseline 후 조정한다. endpoint에서 검색 메서드를 두 번 불러 각각 embedding하지 않도록 query embedding을 service boundary에서 한 번 생성해 두 구현에 전달한다. 단 API token과 사용자 요청 context를 shadow payload에 중복 저장하지 않고, 기존 request에서 얻은 query text/vector/filter를 재사용한다. recommendation/BenefitChat의 LLM은 실제 응답 경로 한 번만 실행하고, shadow는 후보 결과까지만 평가한다. search endpoints도 controller 전체를 두 번 실행하지 않는다.

## 구현 작업 경계와 검증

1. `BenefitSearchService`, `BenefitHybridSearchService`, `StoreSearchService`, 질문 메모리 포트는 기존 호출자 계약을 유지하고 저장소 adapter를 교체한다. `/questions/save`와 `/questions/search`의 ADMIN-only 정책을 바꾸지 않으며, `/questions/chat`의 인증 정책도 보존한다.
2. 새 Flyway migration으로 사전 설치된 extension 확인/문서 테이블/질문 테이블/유니크·FK·검색 인덱스를 추가한다. 운영 확장 설치가 승인되기 전 migration에서 자동 설치를 가정하지 않는다.
3. Builder 단위 검증: 같은 입력 ID/hash 안정성, no-tier/tier-per-row, all-grade carrier-boundary, null/false/true active semantics, 비활성 row, 검색가능 텍스트 변화가 hash에 반영됨. DB 통합 검증: NULL uniqueness, FK cascade, stale active join 제외, FK orphan 없음, vector 1536 차원·모델 검증.
4. 검색 검증: `BenefitSearchService.queryVector/queryHybrid/queryChat`, recommendation search contract, 혜택 목록 `search/searchLexical`, 지도 `searchByKeyword` 및 질문 top-1. 질문 `score`는 기존 ES fixture와 explicit mapped PG score가 contract test를 통과해야 한다. 등급/carrier/channel/active 조건, 빈/null embedding, 1~2자·3자+·한국어 오탈자, 영문/혼합명, 동점 정렬 안정성, page count를 ES golden corpus와 비교한다.
5. 운영 SQL 측정: exact vector p50/p95, filter 선택도 별 latency, DB CPU/buffer/cache, connection pool, `EXPLAIN (ANALYZE, BUFFERS)`; lexical/combined Recall@K, overlap@K, NDCG@K, 빈 결과율, category/grade별 오류, scheduled full sync elapsed/freshness 및 dirty queue lag(확장 도입 시). HNSW는 exact가 SLO 초과라는 증거 이후 별도 migration으로 추가하고 recall regression을 막는다.
6. 동기화 검증: 재실행/중단 후 재시작, batch 한 건 실패/429 후복구, source 수정 중 오래된 응답 늦게 도착, 삭제·비활성, 동일 full run 재실행, 앱 다중 인스턴스 scheduler 경합, snapshot 뒤 변경 및 no-tier↔tier 전환. External embedding mock을 사용하고 DB transaction이 API 호출 동안 열려 있지 않은지 계측한다.

## 롤백과 제거 기준

- 읽기 cohort를 0으로 내리는 것만으로 즉시 ES fallback이 가능하려면 **롤백 관찰 기간에는 ES 문서가 신선해야 한다**. 따라서 단계 5까지 기존 ES scheduled sync를 유지하거나, PG 전용 읽기 전환 전에 ES를 신선하게 유지할 수 있는 검증된 동기화 경로를 둔다. DB writer/ES writer를 같은 DB transaction에 묶지 않는다.
- PG 읽기 장애/품질 악화: flag로 ES 읽기 복귀. ES/PG write lag는 별도 수치로 확인하고 ES가 최신이 아니면 즉시 API의 대체 동작(현재 코드의 fallback/에러 계약) 적용, 신규 사용자 cohort 확대 금지.
- PG 스키마 오류: additive migration은 앱 버전과 호환되게 두고 읽기 플래그 off. 컬럼/extension을 rollback하면서 데이터 손실을 강제하지 않는다. 벡터 재생성이 필요한 모델 전환은 새 version/column/table에 shadow backfill 후 원자적으로 읽기 version을 바꾼다.
- ES 제거 조건은 모든 user API 검색 caller·bootstrap·properties가 없어지고, scheduled full-sync freshness SLA와 복구 절차가 운영에서 안정적으로 입증되며, rollback window가 끝난 뒤다. 확인 전 ES cluster/index/기존 secret을 폐기하지 않는다.

다른 선행 데이터 계약과 chatbot 원천 준비 항목은 canonical readiness/release gate 문서인 [검색·채팅 준비 점검](search-chat-readiness.md)과 함께 관리한다.

## 배포 전에 답할 질문

- 운영 DB PostgreSQL 버전/pgvector와 pg_trgm 설치 권한, RDS/managed 서비스 지원 버전, extension version pin·업그레이드 책임자는 누구인가?
- 실제 운영 benefit/tier/store/question/ES 문서 수, null-tier·dangling/inactive 비율, ES mapping/analyzer·embedding model/dimension은 무엇인가?
- ES 호출 빈도·p95/오류율·OpenAI embedding/chat 비용/limit, DB 연결 수와 CPU 여유, 목표 freshness/rollback 기간은?
- MVP scheduled full sync의 실행 위치·source/replica 연결·replica lag·중복 실행 lock은 무엇인가? full scan/freshness 측정이 기준을 반복해서 못 넘을 때 outbox/dirty-key route의 watermark·운영 책임자는 누구인가?
- 질문 save/search deprecated ADMIN-only endpoint를 계속 지원해야 하는가? API 계약·권한을 그대로 유지할지, 운영 allowlist/retention/deletion policy는?
- AI off/DB extension 미지원 환경에서 API startup 및 검색 실패 계약은 무엇인가?
- MVP 전체 sync가 포착해야 할 benefit 변경 경로가 snapshot import 외에 있는가? outbox 승격이 필요해질 때만 모든 변경 경로의 dirty-key event coverage를 확정한다.
