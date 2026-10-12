# 08. 로드맵

마일스톤은 위에서부터 순서대로 진행한다. 현재 위치는 [PROGRESS.md](PROGRESS.md) 에 있다.

| 마일스톤 | 내용 | 상태 | 명세 |
|---|---|---|---|
| M0 | 프로젝트 뼈대 | 확정 | 아래 |
| M1 | 로그인 및 초기 설정 | 확정 | [features/M1-auth-and-setup.md](features/M1-auth-and-setup.md) |
| M2 | 사용자·역할 관리 | 초안 | 미작성 |
| M3 | 프로젝트와 이슈 | 초안 | 미작성 |
| M4 | 워크플로와 칸반 보드 | 초안 | 미작성 |
| M5 | 백로그와 스프린트 | 초안 | 미작성 |
| M6 | 검색과 저장된 필터 | 초안 | 미작성 |
| M7 | 알림과 실시간 갱신 | 초안 | 미작성 |
| M8 | 리포트와 대시보드 | 초안 | 미작성 |

"초안" 마일스톤은 사용자가 범위를 확정하지 않았다. 시작하기 전에 사용자에게 범위를 확인하고 `features/` 에 명세를 먼저 작성한다.

## M0. 프로젝트 뼈대

기능 없이, 이후 작업이 올라갈 바탕을 만든다.

작업:

1. **개발 환경 확인** — JDK 25 설치 여부 (현재 PC 는 JDK 11), Node 22+, pnpm, Docker.
2. **버전 재확인** — [02-architecture.md](02-architecture.md) 의 버전 표를 최신 안정 버전으로 갱신.
3. **`docker-compose.yml`** — PostgreSQL 18. `.env.example`. (완료)
4. **`backend/`** — start.spring.io 기준으로 생성 (Kotlin, Gradle Kotlin DSL, JDK 25). 의존성: Web, Security, Data JPA, Validation, Flyway, PostgreSQL, Session JDBC, Actuator, Testcontainers.
   - [03-backend-guide.md](03-backend-guide.md) 의 패키지 구조와 `common` (오류 처리, 요청 ID, `Clock` 빈) 구성
   - ktlint 설정
   - `GET /api/v1/health` 하나와 그 통합 테스트 (Testcontainers 로 DB 연결까지 확인)
5. **`frontend/`** — create-next-app (TypeScript, App Router, Tailwind, `src/` 사용) 후 shadcn 초기화.
   - [04-frontend-guide.md](04-frontend-guide.md) 의 폴더 구조
   - `/api` → 백엔드 rewrite 설정, `lib/api` 클라이언트 뼈대
   - 디자인 토큰(색, 글꼴, 반경)과 라이트/다크 테마 결정 — 이 단계에서 `agent-skills` 의 디자인 스킬을 참고해 방향을 정하고 [09-decisions.md](09-decisions.md) 에 기록
   - Vitest, Playwright 설정과 예시 테스트 하나씩
6. **CI** — 백엔드 `./gradlew check`, 프론트엔드 lint/typecheck/test 를 돌리는 워크플로.
7. **루트 `README.md`** — 로컬 실행 방법.

완료 기준:

- 새로 clone 한 뒤 README 대로 따라 하면 DB, 백엔드, 프론트엔드가 뜨고, 브라우저에서 프론트엔드가 백엔드의 health 응답을 보여준다.
- 백엔드와 프론트엔드의 검사 명령이 모두 통과한다.

## M1. 로그인 및 초기 설정

[features/M1-auth-and-setup.md](features/M1-auth-and-setup.md) 참고.

## M2 이후 (초안)

각 마일스톤의 대략적인 내용이다. 확정이 아니다.

- **M2 사용자·역할 관리** — 관리자가 사용자 생성/비활성화, 역할 변경, 비밀번호 재설정. 내 프로필(이메일, 표시 이름) 수정, 비밀번호 변경. 인스턴스 설정 화면(조직 이름, 기본 언어, 시간대).
- **M3 프로젝트와 이슈** — 프로젝트 생성(키 포함), 프로젝트 구성원, 이슈 생성/조회/수정, 유형·우선순위·담당자·라벨, 댓글, 변경 이력.
- **M4 워크플로와 칸반 보드** — 상태와 이동 규칙, 보드 화면, 드래그 앤 드롭으로 상태·순서 변경.
- **M5 백로그와 스프린트** — 백로그 정렬, 스프린트 생성/시작/종료, 스토리 포인트.
- **M6 검색과 저장된 필터** — 조건 조합 검색, 필터 저장과 공유.
- **M7 알림과 실시간 갱신** — 멘션, 인앱 알림, 이메일(SMTP 설정), 보드 실시간 반영.
- **M8 리포트와 대시보드** — 번다운, 처리량 등 프로젝트 지표.
