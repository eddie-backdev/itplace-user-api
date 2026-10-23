# KURE OCI 연동 설정

같은 private Docker network에 있는 KURE 임베딩 컨테이너를 **user API 런타임**에서 호출할 때 다음 설정을 사용합니다.

```sh
EMBEDDING_PROVIDER=local
EMBEDDING_MODEL=nlpai-lab/KURE-v1@8b418a58414668e75532ed045c22d9ca018ae2b2
EMBEDDING_DIMENSIONS=1024
EMBEDDING_BASE_URL=http://kure-embedding:18081
EMBEDDING_TRUSTED_HOSTS=kure-embedding
BENEFIT_RAG_SYNC_ENABLED=true
```

`EMBEDDING_TRUSTED_HOSTS` 기본값은 빈 값입니다. 기존 HTTP loopback 주소는 계속 허용됩니다. private Docker DNS 주소를 쓰려면 URL의 호스트가 쉼표로 구분한 허용 목록의 항목과 정확히 일치해야 합니다. 와일드카드는 지원하지 않습니다. URL은 HTTP여야 하며, 선택 포트가 유효하고, 인증 정보·쿼리·fragment·루트가 아닌 경로가 없어야 합니다.

이 허용 목록은 user API의 쿼리 임베딩과 로컬 인덱싱 준비에 적용됩니다. `scripts/prepare-postgres-search.py`는 DB 대상은 계속 local host로 제한하면서, 임베딩 주소는 loopback 또는 정확히 등록한 trusted host를 허용합니다. PostgreSQL 프로필의 주기 색인은 `BENEFIT_RAG_SYNC_ENABLED` 또는 `AI_INDEXING_ENABLED`가 true일 때만 켜집니다. 시작 후 5분 뒤 실행하고 이후 한 시간 간격으로 원천 audit 후 검증된 snapshot을 KURE로 색인합니다. provider가 local/ollama가 아니면 색인 실행이 거부됩니다.

이 변경은 OCI에서 API가 KURE를 호출할 수 있도록 준비하는 설정이며 배포나 DB 변경을 수행하지 않습니다. 운영 RAG 인덱스로 전환하기 전에는 PostgreSQL vector 확장과 스키마, 저장된 문서의 임베딩 모델·차원 정합성을 확인하고, 그 인덱스와 일치하는 설정으로 user API를 배포해야 합니다.
