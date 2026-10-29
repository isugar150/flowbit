# AI 도구와 MCP 연결

## 사진·파일 읽기와 첨부 (2026-10-29 사용자 확정)

- 티켓 본문·댓글·티켓 첨부·티켓에 연결된 문서의 이미지와 파일을 list_issue_files로 열거한다. get_issue에도 파일 목록과 읽는 방법을 포함한다. get_document는 연결 문서 원본 JSON·Markdown·블록 댓글을 읽는다. 외부 embed 사이트의 인증을 대신하거나 원격 URL을 가져오지는 않는다.
- read_file은 사진을 실제 MCP image 블록으로 반환하고 텍스트·JSON·CSV·소스·Office 문서 등은 추출한 내용을 구간별로 반환한다. PDF는 페이지 단위 글·전체 페이지 수를 반환하며 render_file_page로 스캔·그림·표도 이미지로 볼 수 있다. 원본은 Bearer 인증 다운로드 API와 BASE64 바이트 구간 조회로 모두 접근 가능하다. 암호화·손상·미지원 파일의 내용을 읽었다고 표시하지 않으며 원본과 제한을 안내한다. 파싱과 파일 I/O는 Dispatchers.IO에서 수행하고 외부 OCR/원격 요청/매크로 실행은 하지 않는다.
- 파일 주소를 그대로 신뢰하지 않는다. 읽을 수 있는 티켓과 현재 본문·댓글·직접 연결 문서에 실제로 참조된 파일만 식별해 조회한다. 삭제된 댓글의 파일·타인 초안·다른 티켓의 파일·임의 저장 키·외부 주소·경로 탈출을 거절한다. READ 토큰도 읽을 수 있다.
- attach_file(작은 파일은 Base64)과 POST `/api/v1/integrations/mcp/issues/{number}/files`(multipart file)는 티켓 편집 권한·WRITE 토큰·기존 업로드 제한을 확인한다. placement는 ATTACHMENT(기본), BODY(본문 끝에 파일 블록 또는 image=true 사진), COMMENT(새 댓글에 파일 또는 사진). 선택적 markdown은 새 댓글 글이다. 본문 추가는 행 잠금 후 최신 원본에 붙여 다른 수정을 보존한다. 댓글·활동·알림·실시간은 기존 경로를 쓴다.
- GET `/api/v1/integrations/mcp/issues/{number}/files/{fileId}`는 권한 검사 뒤 원본을 내려준다. fileId는 목록에서 받은 값이며 임의 URL·저장소 키로 접근할 수 없다. 파일을 삭제하거나 참조가 제거되면 이후 요청부터 거절한다. URL에 토큰을 넣지 않는다.
- 큰 파일은 multipart 업로드/원본 다운로드를 사용한다. JSON 도구 업로드는 128KiB, 읽기 처리 메모리는 파일당 32MiB, 텍스트 추출은 200만 자, 한 응답은 최대 20000자 또는 원본 48KiB. 다음 구간·페이지와 잘림 여부를 명시한다. PDF 이미지는 페이지별 최대 2000px로 제한한다. 임의 형식의 의미 해석은 연결 AI가 처리하며 서버는 원본 접근과 지원 형식의 추출을 제공한다.
- MCP 연결 화면·도움말·공개 AI 연결 가이드에 사진 읽기·문서 페이지·원본 다운로드·multipart 업로드와 권한/제한/실패 처리 방법을 함께 설명한다. 파일 업로드는 화면에서만 가능하다는 이전 설명을 대체한다. DB 스키마 변경 없음.

2026-10-29 사용자 확정. Codex·Claude Code에서 티켓 번호로 조회하고 티켓·댓글 CRUD, 연결된 티켓과 속성 변경을 수행한다. 화면과 같은 사용자 권한·요청함·검증·기록·알림·자동화·실시간 전파를 적용한다.

## 연결과 화면

- 2026-10-29 추가 확정: MCP 연결 화면에 **AI 연결 가이드 링크 복사**를 제공한다. 공개 GET·HEAD `/api/v1/mcp/setup`은 로그인 없이 읽는 Markdown 가이드이며 해당 설치의 MCP 주소·토큰 발급 화면·Codex/Claude Code 설정 예시를 포함한다. 실제 토큰·사용자·티켓 데이터는 포함하지 않는다. AI에게 링크만 전달하면 환경·기존 설정 확인 → Flowbit 개인 토큰(API 키) 요청 → 사용자 전용 MCP 설정 병합 → 읽기 요청 검증을 진행하도록 안내한다. OpenAI/Anthropic 키나 비밀번호를 요구하지 않는다.
- 자동 설정은 사용자 전용 설정 파일에 인증을 저장한다(Codex config.toml의 http_headers, Claude Code ~/.claude.json의 mcpServers). 이미 준비된 환경 변수 방식도 지원한다. 실제 토큰은 프로젝트 파일·명령행 인자·로그·응답에 재출력하지 않으며 다른 MCP 설정은 보존한다. 환경 변수나 MCP 설정 변경의 반영에 재시작이 필요하면 그 단계를 명시하고 실제 연결 전에는 완료로 보고하지 않는다. 가이드는 로컬 설정 권한을 가진 AI를 위한 절차이며 링크 자체가 프로그램을 실행하거나 토큰을 발급하지 않는다.

- 내 설정 > MCP 연결(`/settings/mcp`). 서버 주소, Codex TOML·Claude Code JSON 설정 복사, 개인 토큰 발급·목록·폐기, 사용 예시와 도움말 링크를 제공한다.
- 토큰 이름(1~50자), READ 또는 WRITE(읽기도 포함), 유효 기간 1~365일. 원문은 발급 응답에서 한 번만 표시하고 브라우저 영구 저장소에 저장하지 않는다. DB는 SHA-256 해시만 저장한다. 마지막 사용 시각·만료일 표시. 폐기와 사용자 비활성화는 다음 요청부터 반영한다.
- 토큰 관리 API는 세션·CSRF 인증만 허용한다. Bearer 토큰은 MCP와 연동 API(`/api/v1/integrations/mcp/**`)만 접근하며 관리자 API·계정·토큰 발급에는 사용할 수 없다.
- MCP 주소는 `/api/v1/mcp`. 기존 `/api` 프록시를 사용한다. 무상태 Streamable HTTP JSON 응답이며 initialize, ping, tools/list, tools/call, notifications/initialized를 지원한다. GET 스트림은 제공하지 않아 405를 반환한다. Origin이 있으면 설치 주소와 비교한다.

## API와 도구

- GET `/api/v1/me/mcp`: 연결 주소·프로토콜 정보.
- GET·POST `/api/v1/me/access-tokens`, DELETE `…/{id}`: 내 토큰 관리.
- GET `/api/v1/integrations/mcp/tools`: 토큰 권한에 맞는 도구 정의와 JSON Schema 조회.
- POST `/api/v1/integrations/mcp/tools/{name}`: MCP와 같은 도구를 JSON으로 실행하는 API. READ는 조회 도구만, WRITE는 사용자 권한 안에서 변경 가능.
- 도구: get_issue, list_issues, search_issues, create_issue, update_issue, delete_issue, restore_issue; list_comments, create_comment, update_comment, delete_comment; list_projects, get_issue_options, list_people, list_statuses; list_custom_properties, create_custom_property, update_custom_property, delete_custom_property, set_project_field; list_relations, add_relation, remove_relation.
- 티켓 식별은 번호 또는 FB-123 형태. 생성·수정 데이터는 기존 REST와 같은 필드다. `markdown`은 편집기 문서로 변환하며 `content`와 동시 지정 불가. 조회는 원본 content와 Markdown을 함께 제공한다. 복잡한 문서는 content를 사용하면 기존 블록·이미지 등을 보존할 수 있다. 마크다운 지원 범위를 연결 화면에 설명한다.
- get_issue_options는 선택한 프로젝트(생략 시 프로젝트 없음)의 역할·요청함·추정치, 선택 가능한 사람·상태·라벨·사이클·공통 속성 정의(형식·단일/복수·기본값), 우선순위와 사용자 속성 형식을 반환한다. 사람·프로젝트·티켓 목록은 검색·페이지 크기 제한을 제공한다. 없는 선택지를 임의로 만들거나 동명이인을 자동 선택하지 않는다.
- 프로젝트 공통 속성은 생성 시 projectFields, 변경 시 set_project_field. 개별 속성은 별도 CRUD 도구를 제공한다. 생략은 유지, null은 해당 속성에서 허용되는 경우에만 비움이다.
- 요청함을 켠 프로젝트의 관리 역할이 아닌 사용자는 API·MCP에서도 자동으로 대기 티켓을 만든다. 수락 우회 불가. 생성 결과의 triage를 AI에 반환한다. 프로젝트가 없거나 요청함이 꺼져 있으면 일반 티켓이다.
- 삭제는 기존 복원 가능한 삭제. 댓글은 작성자만 수정, 삭제는 작성자 또는 기존 관리자 규칙을 따른다. 변경을 재시도하거나 여러 저장을 하나의 성공으로 가장하지 않는다.

## 인수 기준

- 세션으로 토큰 발급·목록·폐기, 타인 토큰 폐기 불가, DB/목록에 원문 없음. 만료·비활성 사용자·폐기·읽기 권한의 변경 차단.
- 실제 HTTP MCP initialize → tools/list → tools/call, 알림 202, 잘못된 메서드·인자·Origin 처리.
- 티켓과 댓글 CRUD·복원·변경 기록·알림 경로·속성 조회/저장, 요청함 자동 진입 및 수락 우회 차단, 비공개 프로젝트 접근 차단.
- 내 설정 탭·토큰 발급/폐기·설정 예시·도움말, 좁은 화면 레이아웃과 오류 표시.

공식 참고: [MCP 전송](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports), [도구](https://modelcontextprotocol.io/specification/2025-11-25/server/tools), [Codex](https://learn.chatgpt.com/docs/extend/mcp), [Claude Code](https://code.claude.com/docs/en/mcp).


## 티켓 검색 (2026-10-29 사용자 추가 요청)

search_issues의 q로 티켓 키·제목·본문·댓글을 검색하고, 한글 초성 검색은 기존 화면 검색과 같은 규칙을 적용한다. 결과는 items(키·제목·상태·우선순위·프로젝트·일치 위치·요약)와 hasMore이다. limit은 1~100(기본 50), 실제 검색 서비스가 적용하는 상한은 화면과 같다. 접근 권한이 있는 요청함 티켓도 검색되며 비공개 프로젝트의 티켓은 제외한다. 결과의 key로 get_issue를 이어서 호출한다. 연결 화면·도움말에 검색 예시를 제공한다.
