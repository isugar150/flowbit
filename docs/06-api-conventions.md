# 06. API 규칙

## 기본

- 모든 API 는 `/api/v1` 아래에 둔다.
- JSON 만 주고받는다. 필드 이름은 `camelCase`.
- 시각은 ISO 8601 UTC 문자열 (`2026-10-12T04:30:00Z`).
- ID 는 UUID 문자열.
- 리소스 이름은 복수 명사 (`/users`, `/projects/{projectId}/issues`). 리소스로 표현하기 어색한 동작은 동사 하위 경로를 쓴다 (`/auth/login`).

| 동작 | 메서드 | 성공 응답 |
|---|---|---|
| 조회 | `GET` | 200 |
| 생성 | `POST` | 201 + 생성된 리소스 |
| 부분 수정 | `PATCH` | 200 + 수정된 리소스 |
| 삭제 | `DELETE` | 204 |
| 본문 없는 동작 | `POST` | 204 |

## 파일 업로드

- `multipart/form-data` 로 보내고 파일은 `file` 파트에 담는다. 한 요청에 파일 하나.
- 이미지는 서버가 WebP 로 바꾸고 크기를 줄여 저장한다(올린 원본도 복구용으로 따로 저장한다, D-169). 응답에는 저장된 이미지의 주소(`/api/v1/files/<key>`)가 들어 있다.
- 오류: 413 `FILE_TOO_LARGE`, 422 `UNSUPPORTED_IMAGE`, 422 `IMAGE_DIMENSIONS_TOO_LARGE`.
- 자세한 규칙은 [features/M2a-settings-and-images.md](features/M2a-settings-and-images.md).

## 관리자 전용

관리자만 쓸 수 있는 API 는 `/api/v1/admin/` 아래에 둔다. 보안 설정이 이 경로 전체에 관리자 역할을 요구하므로, 엔드포인트마다 권한 확인을 따로 쓰지 않아도 빠뜨릴 수 없다. 관리자가 아니면 403 `FORBIDDEN`.

## 목록 (M2 이후)

- 커서 기반 페이지네이션: `?limit=50&cursor=...`
- 응답: `{ "items": [...], "nextCursor": "..." | null }`
- `limit` 기본 50, 최대 100.

## 오류 형식

RFC 9457 Problem Details (`Content-Type: application/problem+json`) 를 쓴다.

```json
{
  "type": "urn:flowbit:problem:validation-failed",
  "title": "입력값이 올바르지 않습니다",
  "status": 422,
  "code": "VALIDATION_FAILED",
  "traceId": "0f8c...",
  "errors": [
    { "field": "password", "code": "TOO_SHORT", "message": "비밀번호는 10자 이상이어야 합니다" }
  ]
}
```

- `code` 는 프론트엔드가 분기에 쓰는 안정적인 식별자다. `title`/`message` 문구는 바뀔 수 있으므로 분기에 쓰지 않는다.
- `errors` 는 입력 검증 실패일 때만 있다.

| 상태 | 쓰는 경우 |
|---|---|
| 400 | 요청 형식 자체가 잘못됨 (JSON 파싱 실패 등) |
| 401 | 로그인하지 않음, 세션 만료, 로그인 실패 |
| 403 | 로그인했지만 권한 없음, CSRF 토큰 불일치 |
| 404 | 리소스 없음 (권한이 없어 존재를 숨겨야 할 때도 404) |
| 409 | 현재 상태와 충돌 (이미 초기 설정 완료, 아이디 중복 등) |
| 422 | 입력값 검증 실패 |
| 429 | 요청 횟수 제한 초과 |
| 500 | 예상하지 못한 오류 |

## 인증

- 세션 쿠키 방식이다. 토큰(JWT)을 브라우저 저장소에 두지 않는다. 쿠키 이름은 `FLOWBIT_SESSION`, 값은 서버가 만든 무작위 토큰이고 서버는 그 해시를 `user_sessions` 테이블에서 찾는다.
- 세션 쿠키 속성: `HttpOnly`, `SameSite=Lax`, HTTPS 일 때 `Secure`, `Path=/`.
- 로그인 성공 시 항상 새 세션을 만들고, 요청에 들어 있던 이전 세션은 끝낸다 (세션 고정 공격 방지).
- 세션은 요청마다 DB 에서 확인한다. 로그아웃하거나 사용자가 비활성화되면 그 즉시 쓸 수 없다.
- 세션 수명: 마지막 사용 후 7일간 유효(사용할 때마다 연장), 로그인 후 최대 30일. (수치는 제안값, `flowbit.auth.*`)
- 인증이 필요 없는 엔드포인트는 다음뿐이다. 추가하려면 기능 명세에 명시한다.
  - `GET /api/v1/health`
  - `GET /api/v1/setup/status`
  - `POST /api/v1/setup`
  - `POST /api/v1/auth/login`
  - `GET /api/v1/auth/csrf`
  - `GET /api/v1/auth/methods`, `GET /api/v1/auth/sso/start`, `GET /api/v1/auth/sso/callback` (SSO, [features/M2b-ldap-and-sso.md](features/M2b-ldap-and-sso.md))
  - `POST /api/v1/integrations/git/{id}/webhook` (Git 서버가 부른다. 세션 대신 연결의 비밀 값으로 만든 서명을 확인하고, CSRF 검사에서 뺀다. [features/git-integration.md](features/git-integration.md))
- SSO 의 `start`·`callback` 은 JSON API 가 아니라 브라우저가 직접 여는 주소다. 항상 302 로 답하고, 실패는 문제 문서가 아니라 `/login?error=sso_…` 로 알린다.
- 로그인 방법은 세 가지다: Flowbit 의 아이디·비밀번호, LDAP(같은 로그인 폼), SSO(OpenID Connect). 어느 쪽이든 결과는 같은 세션 쿠키다.

## CSRF

- 쿠키-헤더 이중 제출 방식이다. 백엔드가 `XSRF-TOKEN` 쿠키(자바스크립트로 읽을 수 있음)를 내려주고, 프론트엔드는 `GET` 이외의 모든 요청에 그 값을 `X-XSRF-TOKEN` 헤더로 보낸다.
- 로그인과 초기 설정 요청에도 적용한다. 프론트엔드는 첫 변경 요청 전에 `GET /api/v1/auth/csrf` 로 토큰을 받아 둔다.
- 헤더에는 쿠키 값을 그대로 넣는다. 토큰이 없거나 틀리면 403 `CSRF_TOKEN_INVALID`.
- 이 처리는 `lib/api` 클라이언트가 자동으로 한다.

## 요청 횟수 제한

로그인과 초기 설정 엔드포인트에는 IP 기준 제한을 건다. 초과 시 429 와 `Retry-After` 헤더를 돌려준다. 구체적인 수치는 기능 명세에서 정한다. 횟수는 서버 메모리에 있어서 재시작하면 초기화된다.

## 실시간 (M3 부터)

변경은 REST 로 하고, 다른 사용자에게 전파하는 것은 WebSocket `GET /api/v1/realtime` 으로 한다. 설계는 [02-architecture.md](02-architecture.md) "실시간 동기화".

## API 문서

- 아직 OpenAPI 문서를 만들지 않는다. 엔드포인트의 기준은 각 기능 명세의 "API" 절이고, 프론트엔드 타입은 `features/<기능>/types.ts` 에 직접 적는다.
- 엔드포인트가 늘어나는 M3 에서 OpenAPI 생성(springdoc 의 WebFlux 지원 버전)과 타입 생성을 도입한다 (D-023).
