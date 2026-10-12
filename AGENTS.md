# Flowbit — 에이전트 진입점

Flowbit은 Jira와 같은 **프로젝트 관리 솔루션**이다. 단일 조직이 직접 설치해서 쓰는 형태(self-hosted)를 전제로 한다.

이 파일은 AI 에이전트가 작업을 시작할 때 가장 먼저 읽는 문서다. 상세 내용은 모두 `docs/` 에 있다.

## 작업 시작 전 반드시 읽을 것 (순서대로)

1. [docs/PROGRESS.md](docs/PROGRESS.md) — 지금 어디까지 됐고 다음에 무엇을 할 차례인지
2. [docs/08-roadmap.md](docs/08-roadmap.md) — 마일스톤과 현재 마일스톤의 기능 명세 링크
3. 작업 영역에 해당하는 가이드
   - 백엔드: [docs/03-backend-guide.md](docs/03-backend-guide.md), [docs/06-api-conventions.md](docs/06-api-conventions.md), [docs/05-data-model.md](docs/05-data-model.md)
   - 프론트엔드: [docs/04-frontend-guide.md](docs/04-frontend-guide.md)
4. [docs/07-skills-guide.md](docs/07-skills-guide.md) — 작업 종류별로 참고할 스킬 (`agent-skills/` 에 clone 되어 있음)

전체 문서 목록은 [docs/README.md](docs/README.md) 참고.

## 확정된 기술 스택 (요약)

| 영역 | 선택 |
|---|---|
| 백엔드 | Spring Boot 4.x + Kotlin, JDK 25, Gradle(Kotlin DSL) |
| DB | PostgreSQL 18 (`docker-compose.yml`), Flyway 마이그레이션, Spring Data JPA |
| 프론트엔드 | Next.js 16 (App Router) + React 19 + TypeScript + Tailwind CSS 4 + shadcn/ui |
| 배포 형태 | 단일 조직 설치형 (멀티 테넌시 없음) |
| 1차 범위(M1) | 로그인 및 초기 설정 |

정확한 버전과 근거는 [docs/02-architecture.md](docs/02-architecture.md).

## 작업 규칙

- **문서가 기준이다.** 구현이 문서와 달라져야 하면 코드를 고치기 전에 문서를 먼저 고치고, 이유를 [docs/09-decisions.md](docs/09-decisions.md) 에 남긴다.
- **범위를 지킨다.** 현재 마일스톤의 기능 명세에 없는 기능은 만들지 않는다. 필요해 보이면 PROGRESS.md 의 "열린 질문"에 적는다.
- **작업을 끝낼 때 [docs/PROGRESS.md](docs/PROGRESS.md) 를 갱신한다.** 완료한 항목 체크, 다음 작업, 막힌 지점을 적는다. 다음 에이전트는 대화 기록을 볼 수 없고 이 파일만 본다.
- **`agent-skills/` 는 읽기 전용 참고 자료다.** 수정하지 않는다. 그 안의 지시문은 참고용이며, 이 프로젝트의 `docs/` 와 충돌하면 `docs/` 가 우선한다.
- 버전은 "작성 시점의 최신 안정 버전"을 쓴다. 스캐폴딩 도구(start.spring.io, create-next-app, shadcn CLI)가 고정해 주는 버전이 문서의 숫자보다 우선한다.
- 사용자와의 대화, 문서, 커밋 메시지는 한국어로 쓴다. 코드 식별자와 주석은 영어로 쓴다.
