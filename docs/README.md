# Flowbit 문서

이 폴더는 Flowbit을 **어떻게 만들지**를 기록한다. 사람과 AI 에이전트가 모두 이 문서만 보고 작업을 이어갈 수 있어야 한다.

## 문서 목록

| 문서 | 내용 | 언제 읽나 |
|---|---|---|
| [PROGRESS.md](PROGRESS.md) | 현재 상태, 다음 작업, 열린 질문 | 매 작업 시작/종료 시 |
| [01-product.md](01-product.md) | **제품과 UX의 기준** (Linear식 재설계): 철학, 개념, 내비게이션, 화면별 설계, 디자인 시스템, 사용자 플로우, 한국 사용자 특화 | 처음 한 번, 그리고 화면이나 기능을 만들 때마다 |
| [02-architecture.md](02-architecture.md) | 기술 스택과 버전, 저장소 구조, 시스템 구성, 실시간 동기화, 환경 변수 | 처음 한 번, 구조를 건드릴 때 |
| [03-backend-guide.md](03-backend-guide.md) | WebFlux·코루틴·R2DBC 규칙, 패키지 구조, 테스트 | 백엔드 작업 시 |
| [04-frontend-guide.md](04-frontend-guide.md) | Next.js/shadcn 코딩 규칙, 속도·키보드 원칙, 문구, 접근성 | 프론트엔드 작업 시 |
| [05-data-model.md](05-data-model.md) | 테이블 설계와 DB 규칙 | 스키마를 건드릴 때 |
| [06-api-conventions.md](06-api-conventions.md) | REST 규칙, 오류 형식, 인증/CSRF | API를 만들거나 호출할 때 |
| [07-skills-guide.md](07-skills-guide.md) | `agent-skills/` 의 스킬을 어떤 작업에 쓰는지 | 작업 종류가 정해졌을 때 |
| [08-roadmap.md](08-roadmap.md) | 마일스톤 순서와 완료 기준 | 다음 할 일을 고를 때 |
| [09-decisions.md](09-decisions.md) | 결정 기록(ADR)과 미결정 사항 | 왜 이렇게 했는지 궁금할 때, 결정을 바꿀 때 |
| [10-install.md](10-install.md) | **설치 가이드**: DB 준비, 이미지 올리기, 서버 설치, 환경 변수, S3, HTTPS, 운영과 문제 해결 | 서버에 설치·업데이트·백업할 때 |
| [features/](features/) | 마일스톤별 기능 명세 | 해당 기능을 구현할 때 |

## 문서 작성 규칙

- 한 문서는 한 가지 주제만 다룬다. 같은 내용을 두 곳에 쓰지 않고 링크한다.
- "확정"과 "초안"을 구분한다. 사용자가 확정하지 않은 내용에는 **(초안)** 을 붙인다.
- 기능 명세는 `features/M<번호>-<이름>.md` 로 만들고, [08-roadmap.md](08-roadmap.md) 에서 링크한다.
- 결정을 바꾸면 [09-decisions.md](09-decisions.md) 에 새 항목을 추가한다. 기존 항목은 지우지 않고 "대체됨"으로 표시한다.
- 구현이 끝난 기능의 명세는 지우지 않는다. 실제 구현과 달라진 부분만 고친다.

- [프로젝트 자동화와 리뷰 상태](features/project-automations.md): 상태 변경 조건, 끌어 놓는 실행 파이프라인, 알림·담당자·라벨과 실행 기록.
