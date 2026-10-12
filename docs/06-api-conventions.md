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

## 목록 (M2 이후)

- 커서 기반 페이지네이션: `?limit=50&cursor=...`
- 응답: `{ "items": [...], "nextCursor": "..." | null }`
- `limit` 기본 50, 최대 100.

## 오류 형식

RFC 9457 Problem Details (`Content-Type: application/problem+json`) 를 쓴다.

```json
{
  "type": "https://flowbit.dev/problems/validation-failed",
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

- 세션 쿠키 방식이다. 토큰(JWT)을 브라우저 저장소에 두지 않는다.
- 세션 쿠키 속성: `HttpOnly`, `SameSite=Lax`, HTTPS 일 때 `Secure`, `Path=/`.
- 로그인 성공 시 세션 ID 를 새로 발급한다 (세션 고정 공격 방지).
- 세션 수명: 마지막 사용 후 7일간 유효(사용할 때마다 연장), 최대 30일. (초안)
- 인증이 필요 없는 엔드포인트는 다음뿐이다. 추가하려면 기능 명세에 명시한다.
  - `GET /api/v1/setup/status`
  - `POST /api/v1/setup`
  - `POST /api/v1/auth/login`
  - `GET /api/v1/auth/csrf`

## CSRF

- 쿠키-헤더 이중 제출 방식이다. 백엔드가 `XSRF-TOKEN` 쿠키(자바스크립트로 읽을 수 있음)를 내려주고, 프론트엔드는 `GET` 이외의 모든 요청에 그 값을 `X-XSRF-TOKEN` 헤더로 보낸다.
- 로그인과 초기 설정 요청에도 적용한다. 프론트엔드는 첫 변경 요청 전에 `GET /api/v1/auth/csrf` 로 토큰을 받아 둔다.
- 이 처리는 `lib/api` 클라이언트가 자동으로 한다.

## 요청 횟수 제한

로그인과 초기 설정 엔드포인트에는 IP 기준 제한을 건다. 초과 시 429 와 `Retry-After` 헤더를 돌려준다. 구체적인 수치는 기능 명세에서 정한다.

## API 문서

- springdoc-openapi 로 OpenAPI 문서를 자동 생성한다 (`/api/v1/openapi.json`, `local` 프로필에서만 Swagger UI 노출).
- 프론트엔드 타입은 이 문서에서 생성한다. API 를 바꾸면 타입을 다시 생성해 같은 변경에 포함한다.
