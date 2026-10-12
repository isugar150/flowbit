# 02. 아키텍처

## 기술 스택과 버전

"최신 안정 버전"이 원칙이다. 아래 숫자는 **2026-10-12 에 레지스트리(npm, Maven Central)에서 조회한 값**이다. 스캐폴딩할 때 다시 확인하고, 달라졌으면 이 표를 고친다.

### 백엔드

| 항목 | 버전 | 비고 |
|---|---|---|
| Spring Boot | 4.1.1 | 4.2.0 은 아직 마일스톤(M2)이라 쓰지 않는다 |
| Kotlin | 2.4.21 | Spring Boot 가 관리하는 Kotlin 버전과 다르면 Boot 쪽을 따른다 |
| JDK | 25 (LTS) | 개발 PC에는 현재 JDK 11 만 설치되어 있다. **JDK 25 설치가 필요하다** |
| 빌드 | Gradle (Kotlin DSL), Gradle Wrapper 사용 | |
| DB | PostgreSQL 18 | 사용자 지정. `docker-compose.yml` 의 `postgres:18` 이미지 |
| 마이그레이션 | Flyway | 사용자 지정. 스키마 변경은 Flyway 로만 한다 |
| 영속성 | Spring Data JPA (Hibernate) | |
| 인증 | Spring Security + Spring Session JDBC | 세션 쿠키 방식. [06-api-conventions.md](06-api-conventions.md) |
| API 문서 | springdoc-openapi | Boot 4 호환 버전 확인 필요 |
| 테스트 | JUnit 5, MockK, Testcontainers(PostgreSQL) | |

### 프론트엔드

| 항목 | 버전 | 비고 |
|---|---|---|
| Node.js | 22 LTS 이상 | 개발 PC: v22.23.2 |
| 패키지 매니저 | pnpm | |
| Next.js | 16.4.0 | App Router |
| React | 19.3.0 | |
| TypeScript | 7.0.2 | create-next-app 이 다른 버전을 고정하면 그쪽을 따른다 |
| Tailwind CSS | 4.3.3 | CSS 기반 설정(`@theme`) |
| shadcn (CLI) | 4.21.4 | 컴포넌트는 CLI로 추가해 저장소에 포함 |
| 서버 상태 | TanStack Query 5.104.2 | |
| 폼/검증 | react-hook-form 7.90.0, zod 4.6.6 | |
| 테스트 | Vitest 5.0.3, Playwright 1.64.0 | |

## 저장소 구조

하나의 저장소에 백엔드와 프론트엔드를 함께 둔다.

```
flowbit/
├─ AGENTS.md              에이전트 진입점
├─ CLAUDE.md              AGENTS.md 를 불러온다
├─ docs/                  설계 문서 (이 폴더)
├─ agent-skills/          참고용 스킬 저장소 (git 추적 안 함)
├─ backend/               Spring Boot 애플리케이션
├─ frontend/              Next.js 애플리케이션
├─ docker-compose.yml     로컬 개발용 PostgreSQL 18
└─ .env.example           환경 변수 예시 (작성됨)
```

`backend/` 와 `frontend/` 는 아직 없다. M0 에서 만든다 ([08-roadmap.md](08-roadmap.md)).

## 시스템 구성

```
브라우저
   │  (같은 출처, 쿠키)
   ▼
Next.js (frontend)  ── 화면 렌더링, /api/* 는 백엔드로 전달(rewrite)
   │
   ▼
Spring Boot (backend) ── REST API, 인증, 비즈니스 규칙
   │
   ▼
PostgreSQL ── 데이터, 세션 저장
```

- 브라우저는 Next.js 하나만 본다. `/api/**` 요청은 Next.js 가 백엔드로 넘긴다. 같은 출처가 되므로 CORS 설정이 필요 없고 세션 쿠키를 그대로 쓸 수 있다.
- 서버 컴포넌트에서 백엔드를 부를 때는 들어온 요청의 쿠키를 그대로 전달한다.
- **인증과 권한의 판단은 항상 백엔드가 한다.** Next.js 쪽의 리다이렉트는 사용 편의를 위한 것이고 보안 경계가 아니다.
- 세션은 PostgreSQL 에 저장한다. 설치형 제품이라 Redis 같은 추가 구성요소를 요구하지 않는다.
- 백엔드는 모듈형 모놀리스다. 마이크로서비스로 나누지 않는다.

## 환경과 설정

| 변수 | 사용처 | 설명 |
|---|---|---|
| `FLOWBIT_DB_NAME`, `FLOWBIT_DB_USER`, `FLOWBIT_DB_PASSWORD`, `FLOWBIT_DB_PORT` | docker-compose, backend | DB 이름, 계정, 호스트에 노출할 포트. 기본값은 모두 `flowbit` / `5432` (로컬 개발용) |
| `FLOWBIT_DB_URL` | backend | JDBC 주소. 기본 `jdbc:postgresql://localhost:5432/flowbit` |
| `FLOWBIT_BASE_URL` | backend | 외부에서 접근하는 주소. 쿠키 `Secure` 여부 판단 등에 사용 |
| `BACKEND_URL` | frontend | Next.js 가 `/api` 를 넘길 백엔드 주소 (기본 `http://localhost:8080`) |

- 비밀 값은 저장소에 넣지 않는다. `.env.example` 에는 이름과 예시 값만 둔다.
- Spring 프로필: `local`(개발), `test`(테스트), `prod`(운영).

## 로컬 실행 방식

1번은 지금 바로 쓸 수 있다. 2, 3번은 M0 에서 구성한다.

1. `docker compose up -d` — PostgreSQL 18 (`127.0.0.1:5432` 에만 열린다, DB/계정/비밀번호 기본값 `flowbit`). 데이터는 `postgres-data` 볼륨에 남는다. PostgreSQL 18 이미지는 볼륨을 `/var/lib/postgresql` 에 마운트한다 (17 이하의 `/var/lib/postgresql/data` 와 다르다)
2. `backend/` 에서 `./gradlew bootRun` — 8080
3. `frontend/` 에서 `pnpm dev` — 3000

운영 배포 방식(Docker 이미지, 단일 compose 파일 등)은 미결정이다. [09-decisions.md](09-decisions.md) 참고.
