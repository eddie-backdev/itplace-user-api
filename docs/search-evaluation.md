# 검색·챗봇 평가 준비

이 자료는 PostgreSQL/pgvector 전환 전후 검색 품질과 챗봇 동작을 비교할 평가 코퍼스 및 결과 형식을 준비한다. 현재 운영 검색을 바꾸거나 DB를 마이그레이션하지 않는다. 60개 시나리오는 API 요청, 통제된 의존성 fixture가 필요한 장애·만료 조건, 사람이 relevance/evidence를 판정해야 하는 항목으로 나뉜다. 자세한 요청은 [케이스 코퍼스](evaluation/search-chat-cases.json)에 있다.

## 현재 baseline의 한계

`/tmp/itplace-chat-category-final.json`은 서비스 harness로 실행한 질문 9건의 plan과 DTO 결과다. HTTP 상태, 인증 동작, API 지연시간을 측정한 자료가 아니며 전체 정답 집합도 아니다. 관측 케이스 ID는 코퍼스의 `chat-convenience-001`, `chat-topic-switch-001`, `chat-convenience-joined-001`, `chat-carrier-kt-001`, `chat-movie-001`, `chat-cafe-001`, `chat-kids-cafe-001`, `chat-study-cafe-001`, `chat-multitopic-001`이다. 실행 재현용 export는 [chat-service-baseline-2026-10-20.json](evaluation/chat-service-baseline-2026-10-20.json)이다. 코퍼스의 영화·카페·편의점 `required_brands`와 KT `carriers`는 이 export에서 관측한 일부 positive 후보/조건을 회귀 보호한다. 이는 완전한 관련 브랜드 gold label이나 현재 혜택의 유효성 주장이 아니다. `스터디카페`, `편의점 카페`처럼 결과가 나왔다는 사실만으로 성공으로 채점하지 않는다. 검색 라벨은 검토 전까지 provisional이다.

읽기 전용 스냅샷에서 Elasticsearch store 인덱스 문서 수는 0이고 질문 검색용 `questions` 인덱스도 없다. 지도 매장 검색은 현재 Elasticsearch 임베딩 조회가 아닌 DB/공간·키워드 경로이므로 store embedding recall을 이 자료에서 평가하지 않는다. PostgreSQL `pg_available_extensions`에서도 pgvector를 사용할 수 없음을 확인했다. 따라서 question-memory 비교와 pgvector 실행 성능을 이 baseline으로 주장할 수 없다. 현재 애플리케이션이 실제로 사용하는 Benefit 검색 경로와 지도 검색 경로를 구분하고, ES/질문 인덱스 결손은 별도 데이터 준비 이슈로 기록한다.

같은 날의 [local-only readiness snapshot](evaluation/local-readiness-snapshot-2026-10-20.json)은 production 검증이 아닌 로컬 읽기 전용 관측이다. 기록 당시 활성 policy 698건 중 조건은 0건, sourceCapturedAt도 0건이었다. 따라서 조건 해석이나 최신성 질문의 결과를 이 데이터에서 정답으로 라벨링하지 않는다. 코퍼스에는 값이 없어도 되는 안전성 검토 케이스만 두고, 조건이 있는 fixture/수집본을 만들면 snapshot ID와 기준 시각을 고정해 별도 라벨링한다.

## 실행 구분

- `execution=api`: 현재 HTTP API 계약으로 재현 가능한 입력. 채팅은 `POST /api/v1/questions/chat`의 `question`, `messages`, `previousCandidateKeys`, 선택 `lat/lng`, `carrier`, `grade`를 보낸다. 혜택 목록은 `GET /api/v1/benefits?keyword=...`; 지도 keyword 검색은 `GET /api/v1/maps/nearby/search/previews/compact`의 `lat/lng/keyword/userLat/userLng`; 추천은 `GET /api/v1/questions/recommend`의 필수 `question/lat/lng`와 선택 `carrier/grade`가 계약이다. 코퍼스의 서울 좌표는 요청 형식 확인용이며 특정 매장 존재 기대가 아니다. 이 endpoint는 질문 기반 recommendation이며 keyword/autocomplete endpoint가 아니다. 별도 autocomplete route는 현재 확인하지 못했으며 필요한 경우 향후 계약으로 계획한다. 배포 환경 인증·HTTP 실행 여부는 이 코퍼스의 서비스 baseline에서 확인된 것이 아니다.
- `execution=controlled_fixture`: timeout, 빈 retrieval, 비활성 policy, 악성 검색 문서, 동적 candidate key처럼 운영 데이터 상태나 외부 의존성 주입이 필요한 경우다. 각 케이스의 `fixture_setup`에 격리 데이터·stub·clock 동작을 기록했다. 런너는 fixture별 revision, 주입한 clock, dependency timeout 설정을 manifest에 남기고 외부 모델/네트워크 대신 stub 또는 명시적으로 허가된 isolated endpoint를 사용한다. candidate key ID는 매 실행마다 fixture row에서 얻고 하드코딩하지 않는다. 원본 corpus와 binding manifest를 보존하고, 실행 전 key를 채운 별도 run-specific corpus를 만들어 그 파일을 `--cases`에 전달한다. 결과 input은 materialized corpus request와 exact-match한다.
- `execution=human_review`: retrieval 결과를 관련성·순위로 평가하거나 근거 출처와 답변 사실성을 검토해야 하는 경우다. 현재 코퍼스는 모두 `needs_review=true`, `label_status=provisional`이다. 전문가가 판정하고 근거/버전을 기록하기 전에는 정답으로 간주하지 않는다.

`surface`는 API 종류(chat/benefit/map/recommend)이며, `tags`는 의도 범주다. chat 요청은 DTO에 맞춘다. 타 API는 해당 컨트롤러의 query parameter로 기록했으며 실제 adapter는 값/좌표를 전달한다. 인위적인 기대 할인율·등급·현재 제휴 여부는 없다. 영화·카페·편의점 `required_brands`는 위 baseline에서 관측한 일부 positive 후보이며 완전한 gold set이 아니다. KT carrier assertion도 해당 요청에서 관측한 조건이다. 코퍼스에 선언된 `required_brands`, `forbidden_brands`, `carriers`만 명시적 hard check다. 기대 브랜드를 추가하려면 기준일이 있는 실제 baseline 관측 또는 고정 fixture와 검토자 근거를 먼저 기록한다. 문자열 검사기는 사실적 근거, 혜택의 유효 기간, 답변의 진실성을 자동으로 검증하지 않는다.

## 결과 내보내기 계약

adapter는 API/service/fixture 결과를 다음 중립 JSON 구조로 변환한다. 배열 필드는 응답에서 발견한 고유 브랜드/통신사 이름을 쓴다. `http_status`와 유한한 양수인 `latency_ms`는 실제 HTTP 측정 시에만 포함한다. service harness에는 HTTP 코드나 지연시간을 꾸며 넣지 않는다. 평가기는 `case_id` 누락·중복·미등록을 분리하고 corpus 전체를 `missing`으로 남긴다.

```json
{
  "run_id": "baseline-2026-10-20",
  "results": [
    {
      "case_id": "chat-convenience-001",
      "transport": "service",
      "input": {"question": "편의점", "messages": [], "previousCandidateKeys": []},
      "status": "ANSWER",
      "carriers": ["KT", "SKT"],
      "brands": ["GS25", "CU"],
      "answer_text": "응답 문장 전체",
      "evidence_ids": [],
      "error": null
    }
  ]
}
```

새 결과는 `case_id`와 실제 전송한 `input`을 반드시 포함해야 한다. dynamic `input_bindings`가 있는 원본 fixture는 바로 실행하지 않는다. fixture에서 키를 생성한 뒤 run-specific corpus의 `request.previousCandidateKeys`에 넣고 그 materialized 요청과 input을 exact-match한다. 원본 템플릿과 binding manifest는 재현을 위해 함께 보존한다. legacy 예외 없이 새 export의 모든 결과에 실제 요청 input이 필요하다. 이는 케이스 ID와 실제 실행 입력이 뒤바뀌지 않았는지 확인하는 parity check일 뿐 품질 점수는 아니다. 선언된 hard check를 실행하려면 결과에 해당 필드(`carriers`, `brands`, `status` 또는 expected `http_status`)도 있어야 한다. 선언하지 않은 필드는 생략 가능하다. `complete`는 전 케이스 실행 여부이고 `ready`는 이 evaluator에 선언된 입력/상태/브랜드/통신사 검사가 통과하고 사람 검토 플래그가 해제됐다는 뜻만 가진다. `ready=true`도 release readiness나 전체 relevance 품질을 보증하지 않으며 별도의 출시 승인 기준을 대체하지 않는다. 출력의 `evaluated`는 구조가 읽혀 검사를 실행한 케이스일 뿐 합격률이 아니다. `hard_checks`는 실제 실행한 명시적 assertion 및 input parity 검사 개수이며 정밀도나 품질 지표가 아니다. `failures`는 명시한 hard check만 나타낸다. `missing`은 미실행 케이스, `needs_review`는 사람이 검토해야 하는 evaluated 케이스다. 점수, nDCG, recall은 라벨이 검토·고정되기 전에는 산출하지 않는다.

## 실행 방법

API 호출은 기존 서버·테스트 데이터 설정을 따로 준비한 뒤 runner에서 수행한다. 평가기는 결과를 호출하지 않으며 네트워크·모델 호출도 하지 않는다.

```bash
cd itplace-user-api
python3 scripts/evaluate-search-readiness.py
python3 scripts/evaluate_search_readiness_test.py
python3 scripts/evaluate-search-readiness.py --results /path/to/export.json
python3 scripts/evaluate-search-readiness.py --results /path/to/export.json --strict
python3 scripts/evaluate-search-readiness.py --results docs/evaluation/chat-service-baseline-2026-10-20.json --strict
```

마지막 명령은 현재 9개 service baseline을 코퍼스에 대조한다. 51개 미실행 케이스와 provisional label 검토 대기가 남아 `complete=false`, `ready=false`이며 종료 코드는 non-zero다. 이는 예상된 준비 상태 결과이며 baseline 품질 불합격을 뜻하지 않는다. 현재 baseline은 input parity와 관측 브랜드·KT carrier를 포함해 15개 hard check를 실행하고 failure 0건이지만, 이는 relevance 정답률이 아니다. 일반 모드에서 미실행 케이스는 `missing`으로 분리된다. strict 모드는 missing 또는 사람 검토 대기가 남으면 non-zero로 종료한다. malformed export, 명시적 결과 error, 중복/알 수 없는 case ID, hard assertion 위반은 항상 non-zero다.

## 품질·성능 판정 프로토콜

1. 같은 케이스·동일 snapshot·동일 profile 조건으로 현행과 후보 인덱스를 실행하고 run ID, 앱/DB revision, 인덱스 문서 수, 수집 시각, carrier/grade, 좌표, embedding model과 dimension, chunk/tokenization 설정을 manifest에 기록한다. 초기에는 같은 입력을 반복해 캐시 warm/cold 상태를 분리한다.
2. retrieval 단계는 gold label 확정 후 top-k 관련 문서/benefit ID, Recall@k, MRR 또는 nDCG를 산출한다. 여러 판단자가 일부를 독립 라벨링하고 불일치 규칙을 정한 후 라벨셋 버전을 고정한다. 평가 대상에서 빠진 문서가 있으면 recall을 완전한 corpus recall이라고 부르지 않는다.
3. planner는 질문/대화 이력에서 추출한 category, partner, exclusions, carrier, grade, channel, nearby/location, mode를 별도 비교한다. answerer는 후보 집합을 고정해 답변 품질을 분리 측정한다. 운영 API end-to-end 결과도 별도로 측정해 retrieval·planner·answerer 각각의 개선이 total에 미치는 영향을 혼동하지 않는다.
4. 성능은 검색·embedding·planner·answerer 구간과 API 총시간을 각각 p50/p95 및 오류율로 기록한다. 부하/동시성은 사전 수치가 아니라 후보 시스템에서 동일한 부하, 동시성 단계, warm-up, 반복 횟수로 실제 측정한다. 동시성 목표는 트래픽 근거로 별도 결정하고 결과에 측정값과 환경을 함께 쓴다. latency가 없는 기존 서비스 응답 JSON에 시간값을 역산하거나 덧붙이지 않는다.
5. 출시 기준은 사전에 합의한 언어 품질·hard safety assertions·latency 목표로 정한다. 최소 기준: 전체 required cases가 실행되거나 명시적 제외 이유가 있고, hard failures가 0이며, relevance 라벨셋/데이터 manifest가 재현 가능하고, 모델·스냅샷 변경 시 회귀 케이스가 설명되어야 한다. provisional label로 계산한 숫자는 승인 게이트로 쓰지 않는다.
