# 08. 로드맵

마일스톤은 위에서부터 순서대로 진행한다. 현재 위치는 [PROGRESS.md](PROGRESS.md) 에 있다. 제품의 전체 모습은 [01-product.md](01-product.md) (Linear식 재설계).

| 마일스톤 | 내용 | 상태 | 명세 |
|---|---|---|---|
| M0 | 프로젝트 뼈대 | 대부분 완료 (CI, 프론트 테스트 남음) | 아래 |
| M1 | 로그인 및 초기 설정 | 구현됨 (프론트 자동 테스트 남음) | [features/M1-auth-and-setup.md](features/M1-auth-and-setup.md) |
| M2a | 설정 화면과 이미지 업로드 (M2 의 일부를 먼저) | 구현됨 (화면 확인 남음) | [features/M2a-settings-and-images.md](features/M2a-settings-and-images.md) |
| M2b | LDAP 연동, SSO(OpenID Connect) 연동, 이메일 아이디 | 구현됨 (화면·실제 서버 확인 남음) | [features/M2b-ldap-and-sso.md](features/M2b-ldap-and-sso.md) |
| M2 | 구성원과 워크스페이스 설정 (나머지: 내 비밀번호 변경, 언어·시간대) | 초안 | 미작성 |
| M3 | 프로젝트와 티켓 (구성원, 프로젝트 권한, 프리셋, 문서형 티켓, 목록, 댓글) | **1단계 구현됨, 2·3단계 핵심 구현됨**(티켓·편집기), 4단계는 댓글·`@` 태그 구현됨 | [features/M3-projects-and-issues.md](features/M3-projects-and-issues.md) |
| M4 | 커맨드 팔레트와 단축키, 실행 취소 | 초안 | 미작성 |
| M5 | 보드 | 초안 | 미작성 |
| M6 | 사이클 | 초안 | 미작성 |
| M7 | 수신함과 트리아지 | 초안 | 미작성 |
| M7a | 알림 통로: 메일(SMTP), 웹 푸시(Firebase), API 알림 (M7 의 일부를 먼저) | 구현됨 (화면·실제 서비스 확인 남음) | [features/M7a-notification-channels.md](features/M7a-notification-channels.md) |
| (마일스톤 밖) | 관리자 설정의 백업 및 복원: 워크스페이스·로그인 연동·알림 설정과 구성원을 파일로 (사용자 지정, D-112) | 구현됨 | [features/admin-backup.md](features/admin-backup.md) |
| (마일스톤 밖) | 티켓·문서 검색: `Ctrl/⌘+K` 검색 창과 `/search` 결과 페이지 (사용자 지정, D-133) | 구현됨 | [features/search.md](features/search.md) |
| (마일스톤 밖) | 관리자 설정의 데이터 보관: 삭제한 티켓·문서·댓글을 항목별로 정한 기간 뒤 영구 삭제 (사용자 지정, D-132) | 구현됨 | [features/admin-retention.md](features/admin-retention.md) |
| (마일스톤 밖) | 온보딩 투어: 처음 로그인하면 뜨는 둘러보기, 예시 티켓으로 연습, 건너뛰기, 내 설정에서 다시 보기 (사용자 지정, D-120) | 구현됨 | [features/onboarding-tour.md](features/onboarding-tour.md) |
| (마일스톤 밖) | Git 연동: Gitea·GitLab·GitHub 웹훅으로 티켓에 PR·브랜치·커밋 연결, 상태 자동 변경 (사용자 요청, D-141) | 구현됨 (Gitea·GitLab 웹훅, GitHub App, D-145~D-159) | [features/git-integration.md](features/git-integration.md) |
| M8 | 프로젝트 | 초안 | 미작성 |
| M9 | 검색과 저장한 뷰 | 초안 | 미작성 |
| M10 | 모바일 경험 다듬기 | 초안 | 미작성 |
| M11 | 카카오·네이버 로그인, 이메일·카카오톡 알림 | 초안 | 미작성 |

"초안" 마일스톤은 사용자가 범위와 순서를 확정하지 않았다. 시작하기 전에 사용자에게 확인하고 `features/` 에 명세를 먼저 작성한다.

## M0. 프로젝트 뼈대

- [x] 개발 환경: JDK 25 는 Gradle toolchain 이 자동으로 받는다. Node 22, Docker.
- [x] `docker-compose.yml` (PostgreSQL 18), `.env.example`
- [x] `backend/`: Spring Boot 4.1.1 WebFlux + R2DBC + Flyway, 공통 구성(오류 형식, 요청 ID, `Clock`), ktlint, `GET /api/v1/health`, Testcontainers 통합 테스트
- [x] `frontend/`: Next.js 16 + shadcn/ui(base-nova), `/api` rewrite, API 클라이언트, 디자인 토큰, Pretendard, 라이트/다크
- [x] 루트 `README.md` (로컬 실행 방법)
- [ ] 프론트엔드 테스트: Vitest 단위 테스트, Playwright E2E (도구만 설치됨)
- [ ] CI (백엔드 `./gradlew check`, 프론트엔드 lint/typecheck/test)

## M1. 로그인 및 초기 설정

[features/M1-auth-and-setup.md](features/M1-auth-and-setup.md) 참고.

## M2 이후 (초안)

각 마일스톤의 대략적인 내용이다. 화면과 동작의 기준은 [01-product.md](01-product.md) 의 해당 절.

- **M2 구성원과 워크스페이스 설정**: **내 비밀번호 변경**(관리자가 준 임시 비밀번호를 스스로 바꿀 방법이 아직 없다. 가장 먼저 채울 빈 곳). 워크스페이스 언어·시간대. (프로필 사진·이름, 워크스페이스 로고·이름은 M2a 에서 만들었다. 설정 화면 `/settings/*` 에 탭을 더하는 식으로 이어 간다.)
- **M3 프로젝트와 티켓**: 명세는 [features/M3-projects-and-issues.md](features/M3-projects-and-issues.md). 1단계(구성원 관리, 프로젝트, 권한)는 구현됐다. 남은 것은 2단계(프리셋과 문서형 티켓 쓰기), 3단계(티켓 목록과 상태 변경, 실시간), 4단계(댓글과 변경 이력). 목록과 상세 화면은 4.2, 4.4 를 따른다. 낙관적 업데이트와 **실시간 동기화(WebSocket)의 기반**을 여기서 만든다 ([02-architecture.md](02-architecture.md) "실시간 동기화"). 이후 마일스톤의 화면(보드, 사이클, 수신함)은 모두 이 기반 위에서 실시간으로 갱신된다.
- **M4 커맨드 팔레트와 단축키, 실행 취소**: `Ctrl/⌘+K`, 전역·목록 단축키, 단축키 도움말, `Ctrl/⌘+Z` (4.6, 3장).
- **M5 보드**: 상태 열, 끌어서 이동, 키보드로 이동 (4.3).
- **M6 사이클**: 팀별 자동 반복 사이클, 요약 띠, 번다운, 자동 넘김 (4.5).
- **M7 수신함과 트리아지**: 알림 생성 규칙, 수신함, 미루기, 트리아지 대기열 (4.1).
- **M8 프로젝트**: 프로젝트, 진행률, 업데이트 (4.7). 프로젝트 속성(상태·우선순위·리드·기간·라벨)과 마일스톤(단계별 진행률)은 M3 에서 먼저 만들었다 (D-042, D-045).
- **M9 검색과 저장한 뷰**: 한글 검색(초성, 조사), 필터, 뷰 저장과 공유 (4.8).
- **M10 모바일 경험 다듬기**: 하단 탭, 밀어서 처리, 시트 (3장 "모바일"). 각 마일스톤에서 반응형은 기본으로 지키고, 여기서는 모바일 전용 상호작용을 더한다.
- **M11 외부 연동**: 카카오·네이버 로그인, 이메일 알림, 카카오톡 알림 (7장). 외부 서비스의 제약을 먼저 조사한다 (D-022).
