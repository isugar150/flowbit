# 진행 상황

작업을 시작할 때 읽고, 끝낼 때 갱신한다. 다음 에이전트는 이전 대화를 볼 수 없으므로, 이어서 작업하는 데 필요한 것은 모두 여기에 적는다.

## 현재 상태

- **단계: 설계 문서 작성 완료, 코드 없음.**
- 저장소에는 `docs/`, `AGENTS.md`, `CLAUDE.md`, `.gitignore`, `docker-compose.yml`(PostgreSQL 18), `.env.example` 이 있다. `backend/`, `frontend/` 는 아직 만들지 않았다.
- 참고 스킬 저장소 9개가 `agent-skills/` 에 clone 되어 있다 (git 추적 안 함).
- 아직 커밋이 하나도 없다.

## 다음 작업

**사용자가 구현을 시작하라고 하기 전에는 코드를 작성하지 않는다.** 지금까지 사용자의 지시는 "문서로만 저장"이었다.

구현 지시를 받으면:

1. [09-decisions.md](09-decisions.md) 의 "미결정" 중 "M0 시작 전" 항목을 사용자에게 확인한다.
2. [08-roadmap.md](08-roadmap.md) 의 M0 를 진행한다.
3. M1 을 시작하기 전에 [features/M1-auth-and-setup.md](features/M1-auth-and-setup.md) 의 "확인이 필요한 것"(세부 수치)을 사용자에게 확인한다. 답이 없으면 제안값으로 진행한다.

## 마일스톤 체크리스트

### M0. 프로젝트 뼈대

- [ ] 개발 환경 확인 (JDK 25 설치 필요 — 현재 PC 는 JDK 11)
- [ ] 버전 표 재확인 (Flyway 가 PostgreSQL 18 을 지원하는 버전인지 포함)
- [x] `docker-compose.yml` (PostgreSQL 18), `.env.example`
- [ ] `backend/` 스캐폴딩과 `common` 구성, health 엔드포인트와 통합 테스트
- [ ] `frontend/` 스캐폴딩, shadcn 초기화, `/api` rewrite, 디자인 토큰
- [ ] 테스트 도구 설정 (Vitest, Playwright)
- [ ] CI
- [ ] 루트 `README.md`

### M1. 로그인 및 초기 설정

- [ ] 세부 수치 사용자 확인
- [ ] 백엔드: 마이그레이션
- [ ] 백엔드: 보안 설정
- [ ] 백엔드: `setup` 모듈
- [ ] 백엔드: `auth`, `user` 모듈
- [ ] 백엔드: 감사 로그
- [ ] 프론트엔드: API 클라이언트, 타입 생성
- [ ] 프론트엔드: `/setup`
- [ ] 프론트엔드: `/login`, 리다이렉트, 앱 레이아웃
- [ ] E2E, 디자인 리뷰, 접근성 점검
- [ ] 인수 기준 전부 통과

## 열린 질문

[09-decisions.md](09-decisions.md) 의 "미결정" 표 참고. 작업 중 새로 생긴 질문은 아래에 적는다.

- (없음)

## 작업 기록

최신 항목을 위에 추가한다. 한 번의 작업 세션마다 한 항목.

### 2026-10-12 — 초기 설정 방식 확정, PostgreSQL 18 compose

- 사용자 확정: 초기 설정은 관리자 아이디와 비밀번호만 받고 그 계정으로 로그인한다 (D-015, D-016). DB 는 PostgreSQL 18 (D-017). 스키마 관리는 Flyway (D-008).
- 한 일: M1 명세를 다시 작성 (설정 토큰·조직 정보 단계 제거, 이메일 → 아이디). 데이터 모델·API 규칙·아키텍처 문서 반영. `docker-compose.yml`, `.env.example` 작성.
- 검증: `docker compose config` 로 파일 문법만 확인했다. Docker Desktop 이 꺼져 있어 컨테이너를 실제로 띄워 보지는 못했다. **다음 작업자는 `docker compose up -d --wait` 로 기동을 확인할 것.**

### 2026-10-12 — 설계 문서 초안

- 한 일: `docs/` 문서 세트 작성, `AGENTS.md`/`CLAUDE.md` 작성, 스킬 저장소 9개 clone, `.gitignore` 작성.
- 사용자 확정 사항: Spring Boot + Kotlin, Next.js + shadcn/ui (모두 최신 안정 버전), 단일 조직 설치형, 1차 범위는 로그인 및 초기 설정.
- 그 외 설계(세션 인증, 저장소 구조 등)는 에이전트의 제안이며 사용자가 아직 검토하지 않았다.
- 버전은 2026-10-12 에 npm 과 Maven Central 에서 조회했다. springdoc 버전은 조회하지 않았다.
