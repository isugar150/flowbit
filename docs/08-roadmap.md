# 08. 로드맵

마일스톤은 위에서부터 순서대로 진행한다. 현재 위치는 [PROGRESS.md](PROGRESS.md) 에 있다. 제품의 전체 모습은 [01-product.md](01-product.md) (Linear식 재설계).

| 마일스톤 | 내용 | 상태 | 명세 |
|---|---|---|---|
| M0 | 프로젝트 뼈대 | 완료 (CI 는 D-226) | 아래 |
| M1 | 로그인 및 초기 설정 | 구현됨 | [features/M1-auth-and-setup.md](features/M1-auth-and-setup.md) |
| M2a | 설정 화면과 이미지 업로드 (M2 의 일부를 먼저) | 구현됨 | [features/M2a-settings-and-images.md](features/M2a-settings-and-images.md) |
| M2b | LDAP 연동, SSO(OpenID Connect) 연동, 이메일 아이디 | 구현됨 | [features/M2b-ldap-and-sso.md](features/M2b-ldap-and-sso.md) |
| M2 | 구성원과 워크스페이스 설정 (나머지: 내 비밀번호 변경, 언어·시간대) | 초안 | 미작성 |
| M3 | 프로젝트와 티켓 (구성원, 프로젝트 권한, 프리셋, 문서형 티켓, 목록, 댓글) | **1단계 구현됨, 2·3단계 핵심 구현됨**(티켓·편집기), 4단계는 댓글·`@` 태그 구현됨 | [features/M3-projects-and-issues.md](features/M3-projects-and-issues.md) |
| M4 | 커맨드 팔레트와 단축키, 실행 취소 | 구현됨 (첫 판: 실행 취소는 티켓 속성 변경만, D-233) | [features/command-palette.md](features/command-palette.md) |
| M5 | 보드 | 구현됨 (목록 화면의 보기 방식, D-234) | [features/board.md](features/board.md) |
| M6 | 사이클 | 구현됨 (프로젝트별 자동 반복, D-232) | [features/cycles.md](features/cycles.md) |
| M7 | 수신함과 트리아지 | 알림 수신함은 D-216 으로, 트리아지는 구현됨 (D-231) | [features/triage.md](features/triage.md) |
| M7a | 알림 통로: 메일(SMTP), 웹 푸시(Firebase), API 알림 (M7 의 일부를 먼저) | 구현됨 | [features/M7a-notification-channels.md](features/M7a-notification-channels.md) |
| (마일스톤 밖) | 관리자 설정의 백업 및 복원: 워크스페이스·로그인 연동·알림 설정과 구성원을 파일로 (사용자 지정, D-112) | 구현됨 | [features/admin-backup.md](features/admin-backup.md) |
| (마일스톤 밖) | 티켓·문서 검색: `Ctrl/⌘+K` 검색 창과 `/search` 결과 페이지 (사용자 지정, D-133) | 구현됨 | [features/search.md](features/search.md) |
| (마일스톤 밖) | 공유 링크: "내 티켓" 캘린더의 한 달, 타임라인의 고른 기간(몇 월부터 몇 월까지), 티켓(하위 티켓·문서로 이동)을 로그인 없이 볼 수 있는 읽기 전용 링크(임베드 가능. 접속 기록은 D-208 에서 없앴다). 다른 화면에도 쓰도록 공통 구조 (사용자 지정, D-197·D-198) | 구현됨, 화면·통합 테스트 확인 안 함 | [features/sharing.md](features/sharing.md) |
| (마일스톤 밖) | 관리자 설정의 데이터 보관: 삭제한 티켓·문서·댓글을 항목별로 정한 기간 뒤 영구 삭제 (사용자 지정, D-132) | 구현됨 | [features/admin-retention.md](features/admin-retention.md) |
| (마일스톤 밖) | 관리자 설정의 파일 관리: 저장소 통계, 저장소와 DB 점검, 쓰지 않는 파일 정리, 영구 삭제 뒤 사진·원본 정리, 파일은 커밋 뒤에 지움 (사용자 요청, D-171·D-172) | 구현됨 (화면·실제 S3 확인 남음) | [features/admin-files.md](features/admin-files.md) |
| (마일스톤 밖) | 온보딩 투어: 처음 로그인하면 뜨는 둘러보기, 예시 티켓으로 연습, 건너뛰기, 내 설정에서 다시 보기 (사용자 지정, D-120) | 구현됨 | [features/onboarding-tour.md](features/onboarding-tour.md) |
| (마일스톤 밖) | Git 연동: Gitea·GitLab·GitHub 웹훅으로 티켓에 PR·브랜치·커밋 연결, 상태 자동 변경 (사용자 요청, D-141) | 구현됨 (Gitea·GitLab 웹훅, GitHub App, D-145~D-162) | [features/git-integration.md](features/git-integration.md) |
| (마일스톤 밖) | 블록 댓글과 활동: 프로젝트 개요·문서·티켓 본문의 블록마다 댓글(답글·해결), 프로젝트 개요·문서에 티켓 같은 활동 (사용자 요청, D-165. 알림·구독 규칙, 문서 구독, 본문 검색은 D-166) | 구현됨 (새 백엔드·화면 확인 남음) | [features/content-comments.md](features/content-comments.md) |
| (마일스톤 밖) | 계정과 운영 보안: 내 비밀번호 변경, 관리자 감사 로그 탭, DB 비밀 값 암호화와 기존 데이터 이전 (사용자 지시, D-226) | 구현됨 | [features/account-security.md](features/account-security.md) |
| (마일스톤 밖) | 상태 추가(범주 안, 관리자 설정 > 상태)와 추정치(프로젝트가 켬, 기본 꺼짐) (사용자 지시, D-228) | 구현됨 | [features/statuses-and-estimates.md](features/statuses-and-estimates.md) |
| (마일스톤 밖) | 티켓 관계: 막고 있음·막혀 있음·중복·관련 (사용자 지시, D-230) | 구현됨 | [features/issue-relations.md](features/issue-relations.md) |
| (마일스톤 밖) | 리뷰 준비됨·리뷰 중, 프로젝트별 상태 변경 자동화(알림·담당자·라벨, 끌어 놓는 파이프라인·실행 기록) | 구현됨 | [project-automations.md](features/project-automations.md) |
| M8 | 프로젝트 | 초안 | 미작성 |
| M9 | 검색과 저장한 뷰 | 검색(D-133)과 저장한 뷰(D-229) 구현됨 | [features/saved-views.md](features/saved-views.md) |
| M10 | 모바일 경험 다듬기 | 초안 | 미작성 |
| M11 | 카카오·네이버 로그인, 이메일·카카오톡 알림 | 초안 | 미작성 |

"초안" 마일스톤은 사용자가 범위와 순서를 확정하지 않았다. 시작하기 전에 사용자에게 확인하고 `features/` 에 명세를 먼저 작성한다.

## M0. 프로젝트 뼈대

- [x] 개발 환경: JDK 25 는 Gradle toolchain 이 자동으로 받는다. Node 22, Docker.
- [x] `docker-compose.yml` (PostgreSQL 18), `.env.example`
- [x] `backend/`: Spring Boot 4.1.1 WebFlux + R2DBC + Flyway, 공통 구성(오류 형식, 요청 ID, `Clock`), ktlint, `GET /api/v1/health`, Testcontainers 통합 테스트
- [x] `frontend/`: Next.js 16 + shadcn/ui(base-nova), `/api` rewrite, API 클라이언트, 디자인 토큰, Pretendard, 라이트/다크
- [x] 루트 `README.md` (로컬 실행 방법)
- [x] 프론트엔드 테스트: Vitest 단위 테스트(14개 파일), Playwright E2E(6개 파일)
- [x] CI (백엔드 `./gradlew check`, 프론트엔드 lint/typecheck/test/build): `.github/workflows/ci.yml` (D-226)

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

티켓 구독 범위: [해당 티켓만·하위 티켓 포함과 개별 제외](features/issue-subscriptions.md) (2026-10-28 사용자 요청).

## 프로젝트 공통 티켓 속성

2026-10-28 사용자 요청: [공통 속성과 설정 목차](features/project-issue-fields.md) — 모든 티켓 공통 항목, 사람 속성 자동화 알림, 설정 구역 이동.
