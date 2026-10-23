# OCI KURE HTTP probe

이 디렉터리는 기존 `serve.py`의 핀된 KURE 모델을 OCI CPU에서 HTTP로 점검하는 준비물입니다. `probe.py`는 `/health`의 모델 ID와 1024차원을 확인하고, 짧은 한국어 혜택 질의 5개를 임베딩합니다. 기본 `--batch-size 1`은 Java 클라이언트처럼 한 요청에 한 질의를 보냅니다. `--batch-size 4`는 bulk 요청을 측정합니다. 응답 모델, 개수, 인덱스 순서, 유한한 1024차원 값, 벡터 정규화를 검사한 뒤 warmup 이후 HTTP 요청 지연 p50/p95를 JSON으로 기록합니다. 결과에는 요청 시도·성공·실패 수와 batch size가 포함됩니다. 검색·랭킹 지연이나 end-to-end API 성능은 측정하지 않습니다. 오류 응답은 표본에서 제외하지 않고 전체 probe를 실패 처리하며, `--out` 지정 시 실패 수를 포함한 실패 JSON도 기록합니다. 출력에는 고정 테스트 질의만 포함하며 벡터는 저장하거나 출력하지 않습니다.

## OCI에서 실행

현재 compose는 `infra/kure`을 build context로 사용하고 부모 컨테이너 저장소의 외부 Docker network `app-network`에 연결합니다. OCI 호스트에서 저장소 루트로 이동한 다음 실행하세요. 호스트가 4 OCPU/24 GB RAM으로 구성된 경우 KURE 컨테이너 제한은 CPU 2개/메모리 8 GB, PyTorch thread는 2개로 둡니다. 기존 compose와 Dockerfile의 모델 핀은 `serve.py`와 동일합니다.

```sh
docker network inspect app-network
docker compose -f infra/kure/oci/compose.yml build kure
docker compose -f infra/kure/oci/compose.yml up -d kure
docker compose -f infra/kure/oci/compose.yml ps
docker compose -f infra/kure/oci/compose.yml logs --tail=100 kure
curl --fail http://127.0.0.1:18081/health
python3 infra/kure/oci/probe.py --base-url http://127.0.0.1:18081 --repeats 5 --warmup 1 --out /tmp/kure-oci-probe.json
# Optional bulk sample: batches of at most four queries.
python3 infra/kure/oci/probe.py --base-url http://127.0.0.1:18081 --batch-size 4 --timeout 60 --repeats 5 --warmup 1 --out /tmp/kure-oci-bulk.json
docker stats --no-stream itplace-kure
cat /tmp/kure-oci-probe.json
```

최초 기동은 pinned Hugging Face 모델을 내려받으므로 완료까지 기다립니다. 내부 user API 컨테이너에서는 같은 `app-network`의 별칭 DNS `kure-embedding`과 `http://kure-embedding:18081`을 사용합니다. OCI 호스트의 포트 publish는 `127.0.0.1`에만 바인딩됩니다. 기본 요청 제한시간 8초는 Java 단일 질의 클라이언트와 맞춥니다. bulk 예시의 60초는 배치 요청용입니다.

## 중단 및 범위

롤백은 새 KURE 서비스만 중단하면 됩니다. compose 프로젝트를 지정했다면 같은 `-p <project>` 옵션을 모든 명령에 붙이세요. `down -v`를 쓰지 않아 모델 캐시 볼륨을 보존합니다.

```sh
docker compose -f infra/kure/oci/compose.yml stop kure
docker compose -f infra/kure/oci/compose.yml rm -f kure
```

## 2026-10-24 배포 확인

현재 OCI에서 실행 중인 이미지는 `itplace-kure:20261024-arm64-fp32`입니다. 공식 KURE-v1 revision `8b418a58414668e75532ed045c22d9ca018ae2b2`와 1024차원 FP32 출력을 사용하며, 이미지 의존성은 개발 환경과 같은 sentence-transformers 6.2.0, PyTorch 2.14.1+cpu, Transformers 5.20.2, NumPy 2.5.4입니다.

OCI HTTP probe는 30/30 요청에 성공했습니다. 질의 임베딩 HTTP 요청 지연은 p50 266.26 ms, p95 287.02 ms였습니다. 이 값은 임베딩 서비스 요청 지연이며 검색·랭킹 또는 end-to-end API 응답 지연이 아닙니다. 원시 결과와 로컬 이미지 검증 자료는 `infra/kure/oci/verification-20261024/`에 보관합니다.

이 확인은 임베딩 서비스의 현재 동작만 기록하며 production API cutover, DB 변경, 응답 품질 승인을 뜻하지 않습니다. 모델 다운로드가 필요 없는 서버 및 probe 계약 검사는 저장소 루트에서 `python3 infra/kure/serve_test.py`와 `python3 infra/kure/oci/probe_test.py`로 실행합니다.
