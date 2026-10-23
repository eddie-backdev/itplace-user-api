# PostgreSQL 통합·챗봇 개선 실행 준비

## 로컬 Qwen 임베딩 완료 및 챗봇 확인 (2026-10-23)

**전체 활성 문서 임베딩을 2026-10-23 03:23:49 KST에 완료했다.** 실제 DB `postgresql:5432/itplace`에서 4,893건 중 활성 3,179건 모두가 벡터를 가지며, 비활성 1,714건 벡터는 0건이다. 결과 artifact는 `complete=true`, content hash 일치 3,179/3,179, stale 0, 실패 policy 0을 기록한다. DB를 별도 read-only SQL로 확인해 3,179개 활성 벡터 모두 모델·차원·pgvector 차원이 일치하고 잘못된 벡터가 0건임을 확인했다.

- 완료 snapshot hash: `edc34f4d1109796876c9f2cc0b4f1134eea3b3c5052310d81b0fee575a3f3cab`. 크롤러 실행 중이던 첫 시도 뒤 원천 변경을 반영해 다시 audit했고, 크롤링 완료 후 같은 hash가 두 번 관측됐다. 첫 시도의 stale는 원천 갱신과 겹쳐 발생했으며, 완료 시도는 stale/실패가 모두 0이다. 진단 중 `src/`와 `scripts/` 코드는 수정하지 않았다.
- DB 백업 및 산출물 기준 디렉터리: `/Users/eddie/.local/state/itplace/qwen-embedding-20261023`. `V20261023_0001__allow_variable_embedding_dimensions.sql`이 적용되어 기존 벡터를 보존하면서 pgvector 차원별 저장을 허용한다.
- 임베딩: loopback Ollama 0.40.8, `http://127.0.0.1:11434`, cloud disabled. 모델 `qwen3-embedding:4b-q8_0`, digest `357d756ba8e5a3f26040d5e61c78f889c9f81f75b1d8b087855daa4432a5d9d3`, identity `ollama:qwen3-embedding:4b-q8_0`, dimension 2,560. 문서와 검색 질문은 같은 로컬 모델을 사용하며 검색 질문에는 instruction prefix가 적용된다.
- 완료 산출물: `/Users/eddie/.local/state/itplace/qwen-embedding-20261023/embedding/embed-result.json`, `embedding-verification.json`, `preparation.log`. 완료 실행은 문서 4,893건을 저장하고 벡터 2,852건을 재사용했다. 비활성 문서는 임베딩하지 않는다.
- 크롤링에서 LGU 정책 8건이 거절되어 기존 정책 원문은 보존됐다. 이 8건은 원천 품질 확인 항목이다.

검토된 hash로 다시 실행하거나 재개할 때는 같은 명령을 사용한다. 기존 모델 identity·차원·버전·content hash가 모두 맞는 벡터는 재사용된다.

```bash
BASE=/Users/eddie/.local/state/itplace/qwen-embedding-20261023
OUT="$BASE/embedding"
python3 scripts/prepare-postgres-search.py embed \
  --expected-hash edc34f4d1109796876c9f2cc0b4f1134eea3b3c5052310d81b0fee575a3f3cab \
  --output-dir "$OUT"
```

기존 `verify` 모드는 준비 단계의 벡터 0건을 전제로 하므로 임베딩 완료 후에는 사용하지 않는다. 최종 코드 검증은 backend 831 tests / 126 reports, 0 skips 및 frontend 94 tests와 lint·typecheck·build 통과로 확인했다.

API는 `127.0.0.1:8080`, Next.js는 `localhost:5173`에서 AI 활성 상태로 실행한다. 질문 해석·답변 생성에는 OpenAI `gpt-5-nano`를 쓰고, 문서와 검색 질문 임베딩은 모두 로컬 Ollama를 사용한다. seed와 자동 sync는 비활성화되어 있다. 브라우저 QA에서는 할인 직접 질문이 HTTP 200으로 이마트24 LGU BASIC 50원/1,000원 구조의 답을 냈고(약 9.1초), CGV 제외 조건이 적용됐다. 일반 검색은 짧은 안내와 카드를 반환했다. 최종 실제 API 질의 “편의점”도 HTTP 200 `ANSWER`와 카드 8개를 반환했고, trace `beade841-3d9d-485c-bbe5-1759da58e33e`에서 query embedding Ollama 98ms, PostgreSQL `vectorHits=56` / `lexicalHits=56`, retrieval 93ms를 확인했다. 총 응답 19.3초로 답변 속도는 개선 여지가 있다. 미등록 브랜드명 “코덱스달빛편의점”은 편의점 카테고리로 넓혀 기존 브랜드를 반환하는 한계가 남았다. QA 원본은 [chat-runtime-smoke-20261023.json](../../.playwright-mcp/chat-runtime-smoke-20261023.json), [chat-final-after-embedding-20261023.json](../../.playwright-mcp/chat-final-after-embedding-20261023.json), [final-verification.json](/Users/eddie/.local/state/itplace/qwen-embedding-20261023/final-verification.json)에서 확인한다. 이는 일부 HTTP 동작 확인이며 전체 챗봇 정확도 평가 완료를 뜻하지 않는다. API DTO는 유지되고, 1,536차원 Elasticsearch 벡터는 PG 검색에 섞이지 않도록 필터링된다.

## 임베딩 전 준비 기록 (2026-10-23; 10/22 시작)

당시 준비 시점에 확인한 개발 DB는 PostgreSQL 18.4 / pgvector 0.8.7이며, 검색 스키마 migration과 text-only stage를 마쳤다. 아래 수량과 검증 결과는 그 임베딩 전 준비 기록이다. 검색 문서는 4,893건(활성 3,191, 비활성 1,702), source benefits 973건, policies 1,112건(활성 723건)이다. 활성 문서는 tier 3,129건과 offer-grade 62건으로 구성되고, 전체 활성 3,191건은 source URL·수집 시각·evidence 연결을 갖춘다. snapshot hash는 `519cc3d994fd9fb7cd0485e3712dcf16556071a1aa362745f10c95a30f442292`, policy identity mismatch 0이다.

검증 산출물은 `/Users/eddie/.local/state/itplace/pg-preparation-20261022/development/verification.json`, `http-verification.json`, `token-audit.json`이다. CLI verify는 8개 검색 질의 모두 결과를 반환했고 8개 채팅 질의는 모두 `ANSWER`였으며 vectors와 paid embedding calls는 0이다. 새 HTTP 검증은 PID 37539로 실행된 `127.0.0.1:8080` runtime에서 `local,postgres`, AI disabled, seed/sync false로 수행했다. HTTP 목록 편의점/씨유/학원/FLO 4건은 모두 200, 8/3/6/2건을 반환했고 인증 채팅 편의점/학원/FLO 3건은 모두 200 `ANSWER`였다. test login은 200이었다. 모델 호출이 필요한 recommend route는 `AI_DISABLED` 503으로 유지한다. WebConfig는 채팅에만 no-model fallback을 허용하고 인증/CSRF는 유지한다.

당시 토큰 감사는 `cl100k_base` 기준 활성 입력 합계 4,013,685 tokens, 최대 6,065, 32개 입력 batch 최대 84,295, 입력 한도 오류 0이었다. 당시 `text-embedding-3-small`과 1,536차원을 사용했다. 이 단계에서 임베딩 전 문서 적재·접근 준비를 검증했고, 실제 벡터 적재와 자유 질문 답변 품질 또는 운영 전환 평가는 별도 단계로 남겼다. 4,315 warnings는 sourceCategory 미제공 2,563개와 `OTHER` 1,752문서/397 policy였다. sourceCategory는 SKT/KT 공식 자료에서 제공되지 않은 경우가 있어 원문 URL/evidence 누락이 아니며, `OTHER`는 금융·카드 및 classifier 지원 범위의 분류 품질 잔여 항목이다. cafe/bakery 11개 및 card 5개 policy 분류 원인은 수정했다.

실행 백업 dump는 보존하고 임시 복원 DB는 삭제했다. cleanup record는 `/Users/eddie/.local/state/itplace/pg-preparation-20261022/cleanup.json`에 있다. chat HTTP fallback 변경을 포함한 최종 전체 테스트는 815 tests / 125 reports, 0 failures, 0 skipped로 통과했고 Python 검사 10/10 및 `./gradlew build -x test --no-daemon`도 통과했다. 상세 결과는 [2026-10-23 준비 결과](evaluation/pgvector-preparation-2026-10-23.json)에서 확인한다.

개발 DB 재현 순서는 아래와 같다. `itplace-user-api` 프로젝트 루트에서 실행하며 CLI는 기본 `workspace/userapi-local.env`를 사용하고 `local,postgres` 프로필에서 AI·Flyway·seed·sync·scheduler를 비활성화한다. `audit`, `stage`, `verify`는 no-paid-call 경로다. 아래 hash는 10/23 확인값이며 원천이 바뀌면 새 audit hash를 검토한다.

```bash
OUT=/Users/eddie/.local/state/itplace/pg-preparation-20261022/development
python3 scripts/prepare-postgres-search.py audit --output-dir "$OUT"
TOKENIZER=/Users/eddie/.local/state/itplace/pg-preparation-20261022/tokenizer-venv/bin/python
"$TOKENIZER" scripts/audit-embedding-inputs.py "$OUT/source-snapshot.json" --output "$OUT/token-audit.json"
# token-audit.json 확인 후 snapshotHash를 검토한다. 모델 API는 호출하지 않는다.
python3 scripts/prepare-postgres-search.py stage \
  --expected-hash '519cc3d994fd9fb7cd0485e3712dcf16556071a1aa362745f10c95a30f442292' --output-dir "$OUT"
python3 scripts/prepare-postgres-search.py verify --output-dir "$OUT"
```

당시 통과 기준은 원천 audit 오류 0, stage의 모든 문서 write·stale 0·실패 policy 0, verify의 저장 column 및 JSON metadata identity/hash 일치, 8개 검색 질의 모두 결과 있음, 8개 chat 질의 모두 `ANSWER`, 벡터 0 및 embedding call 0이다. NO_RESULTS 자체는 장애가 아니며 질의별 결과와 grade/offer coverage를 artifact로 검토했다. 당시 문서 stage·verify에는 개발 DB용 durable schema migration command가 없었다. 이 준비 기록 이후의 Qwen 임베딩 완료 상태와 일부 실제 HTTP 확인은 위 섹션을 참조한다.

아래 설계와 수치는 2026-10-20 준비 조사 당시 기록이다. 당시의 로컬 현황 스냅샷은 이후 PostgreSQL/pgvector stage보다 앞선 상태이므로 최신 상태의 기준으로 사용하지 않는다. **문서 적재와 접근 검증은 완료했지만 임베딩과 운영 전환은 별도 단계다.**

기준일: 2026-10-20. 이 문서는 IT:PLACE의 검색 저장소 통합과 혜택 챗봇 개선을 함께 진행하기 위한 진입점이다. 당시 준비 작업에서는 런타임 코드·DB·검색 인덱스·모델·운영 배포를 변경하지 않았다.

## 목표와 작업 순서

목표는 혜택 원장, 혜택 검색·벡터 검색, 매장 공간 검색을 PostgreSQL에서 관리하면서 짧은 질문에도 정확하고 유용한 답변을 제공하는 것이다. “편의점”이나 “영화 할인”에 긴 조건 입력을 요구하지 않는다. 확인된 대표 후보를 먼저 보여주고 적용 통신사·등급·제약을 구분한다.

두 작업은 병행한다. 다만 **저장소 변경의 품질 비교에는 같은 문서·같은 임베딩·같은 필터를 사용**한다. 이후 분류·검색·답변 개선을 별도 비교한다. 저장소, 임베딩 모델, 문서 구성, 프롬프트를 동시에 바꾸면 회귀 원인을 찾기 어렵다.

1. 데이터 상태와 현재 검색 경로를 고정하고 평가 질문·정답 근거를 준비한다.
2. 격리된 PostgreSQL 환경에 pgvector를 준비하면서 데이터 분류·조건·출처 보강을 병행한다.
3. 검색 계약을 유지한 PostgreSQL 구현을 기존 검색과 비교한다.
4. 검증된 데이터에 공통 분류, 짧은 질문 처리, 후보 선택과 답변 검증을 적용한다.
5. 실제 API·장애·부하·동기화·롤백 검증 후 제한된 운영 트래픽부터 전환한다.

“모든 예외를 완벽히 처리한다”는 완료 조건은 두지 않는다. 명시한 사례와 장애에서 동작을 검증하고, 모르는 정보와 장애를 사용자에게 정확히 표현하며, 새로운 실패를 회귀 평가에 추가하는 구조를 만든다.

## 준비 산출물

| 자료 | 포함 내용 |
| --- | --- |
| [PostgreSQL 검색 전환 설계](postgres-search-transition.md) | ES 호출 경로, 제안 스키마·키·검색 방식, 백필·증분 동기화, 단계별 전환·롤백 |
| [챗봇 개선 설계](chatbot-readiness.md) | 현재 코드 흐름과 위험, 짧은 질문·다중 주제·후속 질문, 자격·근거·장애 처리, API 호환 |
| [평가 방법과 결과 계약](search-evaluation.md) | 서비스/HTTP/fixture 구분, 라벨링·단계별 평가, 재현 조건 |
| [평가 사례](evaluation/search-chat-cases.json) | 짧은 질의·오탈자·자격·위치·대화·오류 사례의 초기 코퍼스. 현재 라벨은 검토 전 상태 |
| [로컬 현황 스냅샷](evaluation/local-readiness-snapshot-2026-10-20.json) | 읽기 전용 DB/ES 관측. 운영 환경 검증 자료가 아님 |
| [기존 서비스 응답 9건](evaluation/chat-service-baseline-2026-10-20.json) | 입력과 실제 서비스 응답. HTTP 인증·지연·토큰 사용량 측정은 포함하지 않음 |
| [기존 응답의 평가 결과](evaluation/chat-baseline-evaluation-2026-10-20.json) | 실행 9건, 미실행 51건, 검토 대기 9건. 명시적 검사 15건 실패 없음; 품질 승인 상태는 아님 |
| [평가 도구](../scripts/evaluate-search-readiness.py) | 코퍼스와 export의 형식·입력 일치·명시적 assertion 검사. 네트워크/모델 호출 없음 |
| [평가 도구 검사](../scripts/evaluate_search_readiness_test.py) | 잘못된 입력, 결과 누락, 조건 위반, 미검토 자료를 통과로 처리하지 않는지 검증 |

## 초기 로컬 현황 스냅샷 (2026-10-20 이력)

다음 수치는 2026-10-20 로컬 스냅샷의 당시 관측값이다. 최신 개발 DB 상태는 위 2026-10-23 섹션을 따른다. 운영 서버의 수량·설정·성능으로 일반화하지 않는다.

| 항목 | 확인 결과 | 구현에 주는 의미 |
| --- | --- | --- |
| PostgreSQL | 18.4, pg_trgm 1.6, PostGIS 3.6.4 | 문자·공간 검색 기반은 있음 |
| pgvector | `pg_available_extensions`에 `vector` 없음 | 패키지/이미지 준비가 선행되어야 함. `CREATE EXTENSION`만으로 해결되지 않음 |
| 혜택/통신사 정책/등급 행 | 전체 913/1,052/4,405; 활성 758/698/2,894 | 혜택 개수와 검색 문서 개수를 혼동하지 않음 |
| 브랜드/매장 | 443/73,715 | 작은 혜택 후보와 큰 공간 검색의 부하를 따로 측정 |
| 활성 정책의 구조화 조건/수집 시각 | 각각 0/698, 0/698 | 상세 조건·최신성을 기계적으로 보장할 수 없음 |
| 활성 정책의 원문 URL/이용 채널 | URL 698/698, 저장된 채널은 모두 `both` | URL 보유와 검증 완료는 다름. 일괄 채널값을 이용 가능 사실로 단정하지 않음 |
| 브랜드 카테고리 누락 | 224/443 | 원문·혜택 목적을 확인한 분류 보강이 필요 |
| Elasticsearch | 서버 8.14.1; benefit 4,405건, store 0건 | ES 매장 인덱스가 정상 채워졌다고 가정한 비교 금지 |
| 질문 메모리 | 실제 코드의 `questions` 인덱스 404 | 질문 추천 API와 과거 질문 메모리 검색을 구분. `question_embeddings`는 코드의 오래된 주석에도 등장하며 현재 역시 404 |
| 임베딩 | 현재 ES 매핑 1,536차원 cosine; 문서별 모델명 필드 없음 | 차원이 같다는 이유로 벡터를 재사용하지 않음. 모델·문서 버전의 생성 이력을 확인 |

이 수치는 모든 원문 내용이 틀렸다는 뜻이 아니다. **현재 구조화 필드만으로 정확성·최신성·사용 자격을 입증할 수 없다는 뜻**이다. 검색엔진을 바꾸는 것으로 해결되지 않는다.

현재 서비스 응답에는 `스터디카페 → 파고다어학원`처럼 분류를 재검토해야 할 사례가 있다. 혼합 질문의 카드·카페 혜택은 이름만 보고 오답으로 단정하지 않고 원문과 사용 목적을 대조한다. 9건의 응답을 모두 성공 사례로 보관하지 않는다.

## 구현 기준으로 사용할 결정안

- **저장소 범위:** PostgreSQL 원장 + pgvector 혜택 벡터 + pg_trgm/정확 일치·별칭 검색 + PostGIS 매장 검색. Redis 세션/캐시와 MongoDB 로그의 제거는 이번 ES 통합 범위에 포함하지 않는다.
- **최초 벡터 검색:** 현재 문서 규모에서는 exact cosine 검색부터 실측한다. HNSW는 exact 검색이 목표 지연을 충족하지 못할 때 추가하고, 필터 적용 후 recall을 다시 검증한다. 이는 아직 성능이 입증된 선택이 아니다.
- **문서 단위:** `benefit:{benefitId}:policy:{policyId}:tier:{tierId|policy}`를 보존한다. 통신사·등급별 조건을 하나의 브랜드 문서로 합치지 않는다. 화면에서는 필요에 따라 브랜드·통신사별로 묶는다.
- **벡터 재사용:** 동일한 모델·차원·문서 구성 버전·내용 해시가 확인된 경우만 허용한다. 현재 builder의 해시는 `SHA256(embeddingVersion + "|" + searchableText)`다. 모델이 다르면 같은 차원이어도 섞지 않는다.
- **최초 동기화:** 기존 주기 snapshot/reconciliation 흐름을 재사용하고, PG에서 문서 해시를 묶어 비교해 바뀐 문서만 임베딩한다. 현재 규모에서 새 메시지 큐나 outbox 기반 worker를 필수로 만들지 않는다. 전체 비교의 부하나 최신성 목표를 충족하지 못한다는 측정 근거가 생기면 전환 설계의 dirty-key/outbox 방식을 적용한다. 어느 경로든 재시도·동시 실행 제한·게시 직전 원천 검증·정책별 문서 교체의 정합성은 필요하다.
- **정확한 조건 우선:** active, 통신사, 등급, 제외 브랜드, 검증된 이용 채널·기간을 적용한다. `전 등급`은 해당 통신사의 전 등급이며 다른 통신사까지 허용하지 않는다. 조건을 모르면 임의로 채우지 않는다.
- **한국어 검색:** PostgreSQL의 기본 검색이 ES Nori/BM25와 같다는 전제는 두지 않는다. 짧은 브랜드·카테고리·별칭은 명시적 매핑으로 처리하고, 실제 한국어 질의로 누락과 순위를 비교한다. `편의점 → GS25/CU`는 카테고리-브랜드 관계로 구현할 수 있으며, 벡터 유사도만으로 결정하지 않는다.
- **매장 검색:** 매장명·브랜드·주소/지역·거리·실제 제휴 매장 관계를 사용한다. 73,715개 매장에 벡터를 무조건 생성하지 않는다. `근처`와 명시 지역을 구분하며, 이용 가능 매장·영업 여부는 확인된 데이터 범위에서만 표현한다.
- **챗봇:** 분명한 단일 주제·브랜드는 규칙과 동일 taxonomy로 해석하고, 복잡한 의도에서만 추가 모델 해석을 사용하도록 비교한다. 후보 자격과 수치 계산은 코드에서 검증하고, 모델은 허용된 근거로 표현을 돕는다.
- **배포:** 기존 API/status/candidate key와 인증을 보존한다. PG shadow에서는 동일 query embedding을 재사용하고 답변 LLM을 중복 호출하지 않는다. 되돌릴 기간에는 ES도 최신으로 유지한다.

pgvector는 기본 exact 검색과 근사 인덱스를 제공하지만, 근사 인덱스의 필터는 결과 수와 recall에 영향을 줄 수 있다. [pgvector 공식 문서](https://github.com/pgvector/pgvector#filtering). pg_trgm의 `LIKE/ILIKE`는 인덱스를 사용할 수 있으나 추출 가능한 trigram이 없는 패턴은 인덱스 전체 스캔으로 퇴화할 수 있다. 이것이 2글자 검색 결과가 반드시 틀린다는 뜻은 아니다. [PostgreSQL 공식 문서](https://www.postgresql.org/docs/current/pgtrgm.html#PGTRGM-INDEX).

## 화면과 동작, 데이터 계약

이번 준비 단계에서는 화면을 변경하지 않는다. 구현 1차 범위도 기존 웹 채팅·혜택 목록·지도 화면을 그대로 사용하고 결과 정확성·응답 속도·오류 의미를 개선하는 것으로 잡는다. 새 자격/근거 표시가 기존 필드로 불가능한 경우에만 응답 필드를 선택적으로 추가하고 웹 소비를 함께 변경한다.

웹의 `POST /api/v1/questions/chat`과 앱의 기존 `GET /api/v1/questions/recommend`는 모두 회귀 대상이다. 앱을 웹 채팅 API로 옮기는 일은 별도 작업이다. 질문 저장/메모리 검색의 ADMIN 권한과 채팅 인증을 보존한다.

| 계약 | 유지하거나 추가로 명세할 사항 |
| --- | --- |
| 원장 → 검색 문서 | 문서 키, parent FK, active, carrier/grade/all-grade, topic, channel, 원문·구조화 조건, source version |
| 문서 → 임베딩 | model, dimensions, embedding version, content hash, 생성 시각, 성공/실패 상태. 외부 모델 호출 동안 DB 트랜잭션을 열어두지 않음 |
| 질의 → 후보 | 현재 질문 우선, 명시 제외, 복수 주제, profile 적용 여부, 키·순위·근거 식별자. 동점 순서 안정화 |
| 후보 → 답변 | 허용된 candidate key만 반환, 수치·등급·횟수·채널·유효성의 근거, 모르면 미확인으로 표현 |
| 답변 → 웹/앱 | 기존 envelope와 `ANSWER/CLARIFICATION/NO_RESULTS/UNAVAILABLE/OUT_OF_SCOPE` 의미 유지. 검색 장애를 “혜택 없음”으로 처리하지 않음 |
| 변경 → 검색 반영 | 정책/등급 수정·삭제·비활성·브랜드 분류 변경 모두 재계산. 오래된 embedding 응답의 역전 덮어쓰기 방지 |

## 파일 소유권과 의존성이 있는 구현 작업

각 단계는 독립적인 변경 단위로 검토한다. 공유 파일은 한 작업만 수정하고 다른 작업은 명시한 계약을 소비한다. 모델/인프라 전환과 무관한 기존 작업 변경은 함께 정리하거나 되돌리지 않는다.

| 작업 | 소유 영역 | 선행 조건과 검증 |
| --- | --- | --- |
| A. 원천·분류·조건 점검 | 수집/정제 계약, `BenefitConditions`, `BenefitRagMetadataClassifier`, 문서 builder | 전체 활성 정책의 누락·분류 근거 보고서. 채널/조건을 원문과 대조, 미확인 값 보존, 세 통신사 표본 검토 |
| B. PG 기반과 동기화 | 개발 DB 이미지, 신규 Flyway migration, 새 repository와 기존 sync service | 격리 DB에 vector 설치. FK/NULL tier/차원/중복/삭제/재시도/경합 검증. 기존 읽기 유지. 별도 worker/outbox는 측정 후 필요할 때 추가 |
| C. 평가·관측 | `docs/evaluation`, 평가 도구, 서비스 metrics | A/B와 병행. 고정 snapshot·검토 라벨·구간 시간·token usage. 개인 질문 원문을 운영 로그에 추가하지 않음 |
| D. 검색 adapter 교체 | Benefit/Store/Question 검색 service·adapter | B와 C 이후. 같은 문서·벡터로 exact/lexical/hybrid 비교, SQL 실행 계획·필터·페이지·지도 회귀 |
| E. 챗봇 해석·답변 | `BenefitChatPlanner`, `BenefitChatService`, `BenefitChatAnswerer` | A와 C 이후. D의 계약을 사용하며 테스트 대역으로 병행 가능. 공통 taxonomy 정의는 A가 소유 |
| F. 웹 계약 확인·운영 통합 | 필요한 웹 타입/소비부, 설정·배포·모니터링 | D/E 통합 후. 인증·상태·후속 key·재시도 확인. 제한적 전환과 롤백 리허설 |

DB 기반 준비와 데이터/평가 준비는 병렬 가능하다. 준비되지 않은 데이터로 retrieval 정답을 확정하거나, 고정하지 않은 라벨로 저장소 전환 성공을 선언하지 않는다.

## 구현과 운영 전환의 통과 기준

아래 수치는 **초기 검증 목표 제안**이며 측정 결과나 이미 합의된 운영 SLO가 아니다. 현재 환경·실제 트래픽을 확인한 뒤 실행 전에 버전을 고정한다. 결과를 본 뒤 목표를 조용히 낮추지 않는다.

| 영역 | 제안 기준과 측정 조건 |
| --- | --- |
| 데이터·동기화 | 대상 문서의 키/FK/model/dimension/hash 검증 100%; 원인 불명 누락·중복·구버전 덮어쓰기 0건. 미확인 조건은 명시하며 자동 사실 확정 금지 |
| 자격·근거·API | 검토된 필수 사례에서 통신사/등급/명시 제외/허용 후보 키/상태 의미 위반 0건. 원문에 없는 할인율·횟수·채널·현재 유효성 단정 0건 |
| 검색 품질 | 완전한 관련 후보 집합을 라벨링한 고정 평가 집합에서 macro Precision@5 ≥ 0.90, Recall@20 ≥ 0.95를 초기 목표로 사용. Precision@5는 적격 정답이 5개 이상인 질의에 적용하고 분모는 5로 고정; 적격 정답이 1~4개면 반환 후보 precision과 Recall@20을 별도 보고. 정답 0개는 NO_RESULTS/안전한 안내로 평가. 정답이 있는데 결과가 없으면 precision/recall 모두 0. 정답을 완전히 라벨링하지 못한 경우 recall 통과 여부를 보류 |
| 그룹별 품질 | 짧은 한국어·별칭/오탈자·통신사/등급·다중 주제·지역/위치 그룹별 회귀 별도 보고. 복수 주제에 각각 적격 후보가 있으면 각각 노출되는지 확인. ES 결과 일치율은 진단값이며 정답률로 사용하지 않음 |
| 검색 지연 | 같은 환경, 고정 snapshot, warm-up 후 동시 요청 10개에서 검색 단계 p95 ≤ 300ms, 지도 키워드 API p95 ≤ 500ms를 초기 목표로 측정. AI 단계 제외 여부를 명시. 저장소 전환만 비교할 때 현행 대비 p95 10% 초과 악화가 있으면 원인과 개선을 재검토 |
| 챗봇 지연 | 정상 AI 경로 HTTP p50 ≤ 4초, p95 ≤ 8초를 초기 목표로 측정. planner/embedding/retrieval/answerer/store 시간을 분리. 오류/timeout은 분포에서 제외하지 말고 별도 건수와 전체 사용자 지연에 포함 |
| 장애와 복구 | embedding/LLM/검색/DB/store 각각 실패·timeout·429, 잘못된 모델 JSON, 오래된/변조된 후보 키, 삭제·재색인 중 요청 검증. 정상 검색 0건과 장애를 구분하고 전체 요청 deadline 안에서 응답 |
| 비용 | 실제 호출 수·token usage·재시도·백필량으로 1회/일별 비용 계산. 저장소 shadow 비교 때문에 답변 LLM이나 query embedding을 중복 호출하지 않음 |
| 출시 평가 범위 | 초기 코퍼스만으로 종료하지 않음. 실제 사용을 반영한 100개 이상 검토 사례와 별도 held-out 집합, 핵심 그룹별 사례 확보. 모델 단계는 반복 실행해 변동성 보고 |
| 운영 | 운영 DB 지원·여유 자원·replica lag 확인, 증분 반영 목표와 rollback 기간 고정, 동시 쓰기/읽기 전환·복구 리허설 통과 후 ES 제거 |

전역 검색 목표 300ms와 전환 문서의 초기 shadow timeout 150ms는 목적이 다르다. shadow는 보호 예산을 넘으면 취소/누락으로 기록하며, 이를 빈 검색 결과나 품질 실패로 채점하지 않는다. 본 성능 평가는 동일한 정해진 timeout 조건에서 실행한다.

검색 문서 평가의 식별 단위는 `candidateKey`로 고정하고, 통신사·등급·시각·요청 조건에 맞는 정답 집합을 사용한다. 화면에서 같은 브랜드/정책이 반복되는 문제는 별도의 카드 중복·주제 coverage 지표로 평가한다. 문서 단위 recall과 브랜드 노출률을 같은 숫자로 섞지 않는다.

현재 평가 도구는 export의 명시적 검사만 수행한다. `ready`가 true가 되더라도 위 표의 데이터·정답 품질·HTTP·성능·운영 기준을 모두 검증했다는 뜻은 아니다. Precision/Recall, 근거 사실성, 시간 분해, 부하 실행 adapter는 구현 단계의 남은 작업이다. 미실행·미검토를 성공으로 계산하지 않는다.

## 운영 확인이 필요한 항목

다음은 아직 확인하지 않은 배포 전 조건이다. 준비 문서 작성을 위해 사용자의 답을 기다릴 필요는 없으며, 운영 접근이 가능한 다음 단계에서 실제 설정과 지표로 채운다.

- 운영 PostgreSQL/pgvector 지원 버전과 설치 권한, 실제 이미지·확장 버전, primary/replica 구조와 read routing.
- 운영 문서/매장 수, ES 인덱스 상태·실제 사용 경로, 현재 모델 생성 이력, 클라우드 DB CPU/메모리/연결·IO 여유.
- 검색·채팅 트래픽과 피크 동시성, 현재 단계별 지연/오류율, 데이터 갱신 주기와 허용 지연.
- 모든 원장 수정 경로와 동기화 책임, 질문 메모리의 사용 여부·보관/삭제 기준.
- 검증된 백업·복원 절차, 전환 중 ES 동기화 유지 방법, 되돌릴 기간과 즉시 중단 조건.

## 준비 자료 검증 방법

프로젝트 루트 `itplace-user-api`에서 실행한다. 평가 도구는 외부 서비스에 접근하지 않는다.

```bash
python3 scripts/evaluate_search_readiness_test.py
python3 scripts/evaluate-search-readiness.py
python3 scripts/evaluate-search-readiness.py \
  --results docs/evaluation/chat-service-baseline-2026-10-20.json
python3 scripts/evaluate-search-readiness.py \
  --results docs/evaluation/chat-service-baseline-2026-10-20.json --strict
```

마지막 strict 실행은 아직 미실행·미검토 사례가 있어 non-zero가 정상이다. 현재 응답 9건은 회귀 보호와 오류 탐색 자료이며, 운영 품질·성능 합격 근거가 아니다.

2026-10-20 검증에서 평가 도구 자체 검사 15개와 초기 코퍼스 형식 검사가 통과했다. 프로젝트 필수 명령 `./scripts/test-required.sh --no-daemon`은 713개 테스트를 누락 없이 통과했고, 후속 `./gradlew build -x test --no-daemon`도 성공했다. 이는 현재 작업 트리와 준비 도구의 검증이며 아직 구현하지 않은 PG 검색이나 챗봇 개선의 검증이 아니다. [검증 기록과 파일 해시](evaluation/preparation-validation-2026-10-20.json).
