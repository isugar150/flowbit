# 03. 백엔드 가이드 (Spring Boot + Kotlin)

참고 스킬은 [07-skills-guide.md](07-skills-guide.md) 의 "백엔드" 항목을 본다. 참고 스킬 중 Kotlin 전용 자료는 없으므로, Java/일반 패턴을 Kotlin 관용구로 옮겨 적용한다.

## 패키지 구조

기본 패키지는 `com.flowbit` 이다. (초안 — 사용자가 다른 이름을 원하면 스캐폴딩 전에 바꾼다)

기능(모듈) 단위로 먼저 나누고, 그 안에서 계층을 나눈다.

```
com.flowbit
├─ FlowbitApplication.kt
├─ common/            여러 모듈이 쓰는 것: 오류 처리, 보안 설정, 감사 로그, 공용 타입
├─ setup/             초기 설정
├─ auth/              로그인, 로그아웃, 세션
└─ user/              사용자
     ├─ api/              Controller, 요청/응답 DTO
     ├─ application/      Service (유스케이스, 트랜잭션 경계)
     ├─ domain/           Entity, 도메인 규칙, Repository 인터페이스
     └─ infrastructure/   외부 연동 구현 (필요할 때만)
```

의존 규칙:

- `api → application → domain` 방향으로만 의존한다. `domain` 은 Spring MVC 나 DTO 를 모른다.
- 모듈끼리는 상대 모듈의 `application` 계층(Service)만 부른다. 다른 모듈의 Repository 나 Entity 를 직접 쓰지 않는다.
- `common` 은 어떤 기능 모듈에도 의존하지 않는다.

## Kotlin 규칙

- `!!` 를 쓰지 않는다. null 이면 명시적으로 처리하거나 도메인 예외를 던진다.
- DTO 는 `data class` 로 만든다. JPA Entity 는 `data class` 로 만들지 않는다 (`equals`/`hashCode` 는 id 기준으로 직접 구현).
- Entity 는 `kotlin("plugin.jpa")` 와 `allOpen` 설정을 쓴다. 필드는 가능한 한 `val` 이나 `protected set` 으로 두고, 상태 변경은 의미 있는 이름의 메서드로 한다 (예: `user.recordFailedLogin(now)`).
- 의존성은 생성자 주입만 쓴다.
- 현재 시각은 `Clock` 을 주입받아 얻는다. `Instant.now()` 를 직접 부르지 않는다 (테스트 가능성).
- 설정 값은 `@ConfigurationProperties` 클래스로 묶는다. `@Value` 를 흩어 쓰지 않는다.
- 코드 스타일은 ktlint 로 강제한다.

## 계층별 규칙

**Controller (`api`)**
- 요청 검증(Bean Validation), Service 호출, 응답 변환만 한다. 비즈니스 규칙을 넣지 않는다.
- Entity 를 그대로 응답으로 내보내지 않는다. 항상 응답 DTO 로 바꾼다.

**Service (`application`)**
- 유스케이스 하나가 public 메서드 하나다. 트랜잭션 경계는 여기에 둔다 (`@Transactional`, 조회는 `readOnly = true`).
- 권한 확인은 Service 진입 지점에서 한다.

**Domain**
- 불변식은 Entity 안에서 지킨다. 잘못된 상태를 만들 수 없게 한다.
- 실패는 `common` 의 도메인 예외 계층으로 표현한다 (`NotFoundException`, `ConflictException`, `ValidationException`, `ForbiddenException` 등). 각 예외는 오류 코드를 가진다.

## 오류 처리

- 전역 `@RestControllerAdvice` 하나가 모든 예외를 RFC 9457 Problem Details 로 바꾼다. 형식은 [06-api-conventions.md](06-api-conventions.md).
- 예상하지 못한 예외는 500 으로 응답하고, 내부 메시지나 스택 트레이스를 응답에 넣지 않는다. 로그에는 남긴다.

## DB와 마이그레이션

- 스키마 변경은 Flyway 마이그레이션으로만 한다. `spring.jpa.hibernate.ddl-auto` 는 `validate` 로 둔다.
- 파일 위치: `backend/src/main/resources/db/migration/V<번호>__<설명>.sql`
- 이미 병합된 마이그레이션 파일은 고치지 않는다. 새 파일을 추가한다.
- 테이블 규칙은 [05-data-model.md](05-data-model.md).
- N+1 조회를 만들지 않는다. 연관 관계는 기본 `LAZY`, 필요한 곳에서 fetch join 이나 전용 조회 쿼리를 쓴다.

## 보안

- 비밀번호는 Argon2id 로 해시한다 (`DelegatingPasswordEncoder` 로 감싸 알고리즘 교체가 가능하게 한다).
- 비밀번호, 토큰, 세션 ID 를 로그에 남기지 않는다.
- 모든 엔드포인트는 기본적으로 인증이 필요하다. 공개 엔드포인트는 보안 설정에 명시적으로 나열한다.
- 상태를 바꾸는 요청에는 CSRF 보호가 적용된다 ([06-api-conventions.md](06-api-conventions.md)).
- 보안상 의미 있는 사건(로그인 성공/실패, 초기 설정 완료, 권한 변경)은 감사 로그에 기록한다.

## 테스트

| 종류 | 대상 | 도구 |
|---|---|---|
| 단위 테스트 | 도메인 규칙, Service 로직 | JUnit 5, MockK |
| 통합 테스트 | Controller ~ DB 전체 흐름, 보안 설정 | `@SpringBootTest`, MockMvc, Testcontainers(PostgreSQL) |

- DB 가 관여하는 테스트는 H2 가 아니라 실제 PostgreSQL(Testcontainers)로 돌린다.
- 기능 명세의 "인수 기준" 하나마다 대응하는 통합 테스트가 있어야 한다.
- 테스트 이름은 백틱으로 감싼 문장으로 쓴다: `` `잠긴 계정은 올바른 비밀번호로도 로그인할 수 없다` ``
- 작업을 끝내기 전에 `./gradlew check` 가 통과해야 한다.

## 로깅

- SLF4J 를 쓴다. 운영 프로필에서는 JSON 형식으로 출력한다.
- 요청마다 요청 ID 를 만들어 MDC 에 넣고, 오류 응답의 `traceId` 로도 내려준다.
