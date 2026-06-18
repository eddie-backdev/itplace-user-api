# User API Agent Rules

Applies to `itplace-user-api/`.

## Stack
- Spring Boot 3.4
- Java 17
- Gradle wrapper (`./gradlew`)

## Default workflow
- Keep edits inside this repo unless the task explicitly spans multiple repositories.
- Avoid reverting unrelated local work already present in this repository.
- Prefer small, reviewable changes and reuse existing patterns before adding new abstractions.

## Verification
Run from this directory:
- `./gradlew test`
- `./gradlew build -x test`

## Commit / push
- Commit from this repo, not the workspace root.
- Inspect recent commit history before committing and match the repository style.
- Use scope-less Conventional Commit headers by default, e.g. `fix: 한글 요약`, `feat: 한글 요약`, `refactor: 한글 요약`.
- Write commit subjects and bodies in Korean by default; keep technical identifiers in English where appropriate.
- 커밋 본문에는 필요한 설명만 간결하게 남기고, `Constraint:`, `Tested:`, `Confidence:` 같은 Lore trailer는 사용자가 요청하지 않는 한 넣지 않는다.
- Do not add `Co-authored-by` unless explicitly requested.
- Prefer `../scripts/commit-ready-check.sh user-api` before commit.
- Prefer `../scripts/safe-push.sh user-api` before push.
