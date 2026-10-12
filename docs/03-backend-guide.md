# 03. 백엔드 가이드 (Spring Boot WebFlux + Kotlin)

참고 스킬은 [07-skills-guide.md](07-skills-guide.md) 의 "백엔드" 항목을 본다. 참고 스킬 중 Kotlin·WebFlux 전용 자료는 없으므로, 일반 패턴을 이 문서의 규칙에 맞춰 적용한다.

## 리액티브 스택 규칙 (가장 중요)

백엔드는 Spring MVC 가 아니라 **WebFlux** 다 (D-019). 요청은 소수의 이벤트 루프 스레드에서 처리되므로, 스레드를 막는 코드 하나가 서버 전체를 느리게 만든다.

- 컨트롤러와 서비스의 메서드는 `suspend fun` 으로 쓴다. `Mono`/`Flux` 를 직접 다루는 것은 프레임워크 인터페이스를 구현할 때뿐이다(예: `WebFilter`, `ServerSecurityContextRepository`). 그때는 `mono { }` 로 코루틴을 감싼다.
- **블로킹 호출 금지**: JDBC, JPA, `Thread.sleep`, `.block()`, 블로킹 HTTP 클라이언트를 쓰지 않는다. DB 는 R2DBC 로만 접근한다.
- 블로킹 라이브러리를 피할 수 없으면(LDAP SDK, 메일 전송, 파일 입출력, 이미지 변환) 그 호출을 한 클래스에 모으고 `withContext(Dispatchers.IO)` 안에서만 부른다. DB 트랜잭션 안에서 부르지 않는다(`LdapDirectory`, `AuthService.login`, `Mailer`).
- 바깥 서버를 부르는 HTTP 는 `WebClient` 를 쓰고 시간 제한을 건다(`OidcClient`, `FcmClient`, `WebhookSender`).
- 요청과 상관없이 뒤에서 도는 일(알림 보내기)은 자체 `CoroutineScope` 에서 돌리고 빈이 사라질 때 취소한다(`NotificationDispatcher`). 통합 테스트는 결과가 나타날 때까지 기다린다.
- CPU 를 오래 쓰는 일(Argon2 해시 등)은 `withContext(Dispatchers.Default)` 로 이벤트 루프 밖에서 돌린다. `PasswordHasher` 가 그렇게 한다.
- 요청 정보는 `ServerWebExchange` 에서 얻는다. 서블릿 API(`HttpServletRequest`, `HttpSession`)는 없다.
- `ThreadLocal` 에 기대지 않는다(MDC 포함). 요청 ID 는 exchange 속성에 둔다(`RequestIdFilter`).
- `WebSession` 을 만들지 않는다. 로그인 세션은 `user_sessions` 테이블로 직접 관리한다.
- `@AuthenticationPrincipal` 로 받는 타입(`AuthenticatedUser`)은 `java.security.Principal` 을 구현하면 안 된다. 구현하면 WebFlux 가 그 자리에 `Authentication` 전체를 넣어 타입 오류가 난다.

## 패키지 구조

기본 패키지는 `com.flowbit` 이다. 기능(모듈) 단위로 먼저 나누고, 그 안에서 계층을 나눈다.

```
com.flowbit
├─ FlowbitApplication.kt
├─ common/            여러 모듈이 쓰는 것
│   ├─ error/           ApiException, 문제 문서(Problems), GlobalExceptionHandler
│   ├─ web/             RequestIdFilter, ClientInfo, HealthController, PublicUrl(접속 주소)
│   ├─ ratelimit/       RateLimiter (메모리, IP 기준)
│   ├─ audit/           AuditService (감사 로그)
│   └─ richtext/        RichText: 편집기 문서의 검증, 저장, 글자 추출
├─ instance/          인스턴스 설정, 초기 설정 완료 여부
├─ user/              사용자, 아이디·비밀번호 규칙, /me
├─ auth/              로그인, 로그아웃, 세션, 보안 설정
│   ├─ api/             AuthController, SessionCookies, SsoController, AdminAuthController
│   ├─ application/     AuthService, SessionService, PasswordHasher,
│   │                    LdapDirectory, OidcClient, OAuth2Client, SsoService, ExternalAccountService, ExternalAuthSettingsService
│   ├─ domain/          UserSession, LdapSettings, SsoSettings
│   └─ config/          SecurityConfig, SessionSecurityContextRepository
├─ project/           프로젝트, 프로젝트 구성원과 역할 (접근 판단은 ProjectService 가 한다)
├─ issue/             이슈 (IssueService, 조회용 IssueQueries)
├─ notification/      알림 통로(메일, 웹 푸시, API 알림)와 그 설정. 계층을 나누지 않은 작은 모듈
├─ setup/             초기 설정
└─ media/             이미지 변환(ImageProcessor), 파일 저장(ImageStorage), 파일 제공, 업로드 읽기
```

모듈 안의 계층: `api`(Controller, 요청/응답 DTO) → `application`(Service, 트랜잭션 경계) → `domain`(엔티티, 규칙, Repository).

의존 규칙:

- `api → application → domain` 방향으로만 의존한다. `domain` 은 웹 계층이나 DTO 를 모른다.
- 모듈끼리는 상대 모듈의 `application` 계층(Service)을 부른다. 예외로 `auth` 는 로그인 처리를 위해 `user` 의 `UserRepository` 를 직접 쓴다(행 잠금이 필요해서).
- `common` 은 어떤 기능 모듈에도 의존하지 않는다.

## Kotlin 규칙

- `!!` 를 쓰지 않는다. null 이면 명시적으로 처리하거나 `checkNotNull`, 도메인 예외를 쓴다.
- DTO 는 `data class` 로 만든다.
- 의존성은 생성자 주입만 쓴다.
- 현재 시각은 `Clock` 을 주입받아 얻는다. `Instant.now()` 를 직접 부르지 않는다 (테스트가 시간을 움직인다).
- 설정 값은 `@ConfigurationProperties` 클래스로 묶는다 (`AuthProperties`).
- 코드 스타일은 ktlint 로 강제한다. 고칠 때는 `./gradlew ktlintFormat`. 테스트 이름은 한글 문장이라 함수 이름 규칙만 꺼 두었다(`backend/.editorconfig`).

## 계층별 규칙

**Controller (`api`)**
- 요청 받기, 요청 제한 확인, Service 호출, 쿠키 쓰기, 응답 변환만 한다. 비즈니스 규칙을 넣지 않는다.
- 엔티티를 그대로 응답으로 내보내지 않는다. 항상 응답 DTO 로 바꾼다.

**Service (`application`)**
- 유스케이스 하나가 public 메서드 하나다. 트랜잭션 경계는 여기에 둔다.
- 트랜잭션은 `suspend fun` 에 `@Transactional` 을 붙이거나 `TransactionalOperator.executeAndAwait { }` 를 쓴다. 느린 일(해시)은 트랜잭션 밖에서 먼저 한다.
- 실패를 기록해야 하는 흐름(로그인 실패 횟수, 감사 로그)은 예외를 던지지 않고 결과 값을 돌려준다. 예외를 던지면 트랜잭션이 롤백되어 기록이 사라진다(`AuthService.login` 의 `LoginResult`).

**Domain**
- 엔티티는 Spring Data R2DBC 매핑 클래스다(`@Table`, `@Id`). JPA 가 아니므로 지연 로딩, 영속성 컨텍스트, 변경 감지가 **없다**. 값을 바꿨으면 `repository.save(entity)` 를 직접 부른다.
- ID 는 애플리케이션이 만든다(`UuidV7`). ID 가 있는 객체에 `save` 를 부르면 UPDATE 가 나가므로, 새 행은 `R2dbcEntityTemplate.insert(entity)` 로 넣는다.
- 상태 변경은 의미 있는 이름의 메서드로 한다 (`user.recordFailedLogin(...)`).
- 연관 관계 매핑이 없다. 다른 테이블이 필요하면 따로 조회하거나 SQL 조인을 쓴다.
- 행 잠금이 필요하면 `@Query("select ... for update")` 를 쓰고 반드시 트랜잭션 안에서 부른다.
- 엔티티에 매핑하지 않는 타입(`inet`, `jsonb`)은 `DatabaseClient` 로 SQL 을 직접 쓰고 `cast(:x as inet)` 처럼 변환한다. null 은 `bindNull` 로 넣는다.

## 오류 처리

- 클라이언트가 처리할 실패는 `ApiException` 과 그 하위 클래스로 던진다. 각 예외는 HTTP 상태와 안정적인 `code` 를 가진다.
- `GlobalExceptionHandler` 가 컨트롤러에서 난 예외를 RFC 9457 문제 문서로 바꾼다. 형식은 [06-api-conventions.md](06-api-conventions.md).
- 보안 필터에서 나는 실패(401, 403, CSRF)는 컨트롤러 전에 일어나므로 `SecurityConfig` 가 `ProblemResponseWriter` 로 같은 형식을 직접 쓴다.
- 예상하지 못한 예외는 500 으로 응답하고, 내부 메시지나 스택 트레이스를 응답에 넣지 않는다. 로그에는 `traceId` 와 함께 남긴다.

## DB와 마이그레이션

- 스키마 변경은 Flyway 마이그레이션으로만 한다 (사용자 지정, D-008).
- 파일 위치: `backend/src/main/resources/db/migration/V<번호>__<설명>.sql`
- **이미 배포된 마이그레이션 파일은 고치지 않는다.** 새 파일을 추가한다.
- **`V1__schema.sql` 은 더 고치지 않는다** (D-084). 첫 운영 배포의 기준 스키마다: 개발 중의 `V1`~`V6` 을 2026-10-13 에 한 번 합쳤고(D-104), 다시 합치지 않는다. 운영 DB 에 한 번 적용된 뒤에 이 파일을 고치면 Flyway 가 체크섬 불일치로 시작을 막는다. 그 뒤의 변경은 `V2__….sql`, `V3__….sql` … 처럼 새 파일로 추가하고, 데이터가 들어 있는 DB 에 그대로 적용되게 쓴다(기본값, 기존 행 채우기).
- 애플리케이션은 R2DBC(`spring.r2dbc.*`)로, Flyway 는 JDBC(`spring.flyway.*`)로 같은 DB 에 접속한다. 접속 정보를 바꿀 때 둘 다 바꾼다.
- 엔티티와 테이블이 맞는지 자동으로 검증해 주는 장치가 없다(JPA 의 `ddl-auto=validate` 에 해당하는 것이 없음). 통합 테스트가 실제 PostgreSQL 에서 읽고 쓰는 것으로 확인한다.
- 테이블 규칙은 [05-data-model.md](05-data-model.md).

## 보안

- 비밀번호는 Argon2id 로 해시한다 (`DelegatingPasswordEncoder` 로 감싸 알고리즘 교체가 가능하다).
- 비밀번호, 세션 토큰을 로그나 감사 기록에 남기지 않는다. 세션 토큰은 DB 에도 해시로만 저장한다.
- 모든 엔드포인트는 기본적으로 인증이 필요하다. 공개 엔드포인트는 `SecurityConfig` 에 명시적으로 나열한다.
- 관리자 전용 API 는 `/api/v1/admin/` 아래에 둔다. `SecurityConfig` 가 그 경로 전체에 `ADMIN` 역할을 요구한다.
- **프로젝트에 속한 것을 다루는 코드는 반드시 `ProjectService.access(user, key)` 를 먼저 거친다.** 이 함수가 프로젝트와 호출자의 역할을 돌려주고, 들어올 수 없는 사람에게는 "없음"(404)으로 답한다. 이슈·댓글 같은 하위 리소스도 프로젝트 ID 를 직접 받아 조회하지 말고 이 결과에서 얻는다. 쓰기는 `role.canEdit`, 설정은 `role.canManage` 를 확인한다.
- 업로드한 파일은 내용으로 판단한다. 파일 이름과 `Content-Type` 을 믿지 않고, 저장 파일 이름은 서버가 만든다. 이미지는 반드시 `ImageProcessor` 를 거쳐 다시 인코딩한 것만 저장한다(원본을 그대로 저장하거나 내보내지 않는다).
- 파일 입출력과 이미지 변환은 블로킹이므로 `Dispatchers.IO` 에서 한다 (`ImageStorage`, `ImageProcessor`).
- 상태를 바꾸는 요청에는 CSRF 보호가 적용된다 ([06-api-conventions.md](06-api-conventions.md)).
- 보안상 의미 있는 사건은 `AuditService` 로 기록한다.

## 테스트

| 종류 | 대상 | 방식 |
|---|---|---|
| 단위 테스트 | 도메인 규칙, 순수 로직 | JUnit 5 + kotlin-test |
| 통합 테스트 | HTTP 요청부터 DB 까지 전체 흐름 | `@SpringBootTest(RANDOM_PORT)` + Testcontainers(PostgreSQL 18) |

- 통합 테스트는 `IntegrationTest` 를 상속한다. 실제 포트에 서버를 띄우고 `support/ApiClient` 가 브라우저처럼 쿠키와 CSRF 헤더를 다루며 HTTP 로 호출한다. 컨트롤러를 직접 부르거나 보안 필터를 건너뛰지 않는다.
- 각 테스트 전에 테이블을 비우고, 시계(`MutableClock`)와 요청 제한을 초기화한다. 시간이 지나야 하는 경우 `clock.advance(...)` 를 쓴다.
- 기능 명세의 "인수 기준" 하나마다 대응하는 테스트가 있어야 한다.
- 테스트 이름은 백틱으로 감싼 한국어 문장으로 쓴다: `` `5회 연속 실패하면 올바른 비밀번호로도 로그인할 수 없다` ``
- 작업을 끝내기 전에 `./gradlew check` (ktlint + 테스트)가 통과해야 한다. Docker 가 켜져 있어야 한다.

## 로깅

- SLF4J 를 쓴다.
- 요청마다 요청 ID 를 만들어 응답 헤더 `X-Request-Id` 와 오류 응답의 `traceId` 로 내려준다. 오류 로그에도 같은 값을 남긴다.
