# 티켓 관계

상태 (2026-10-28): **구현됨** (D-230). 티켓끼리 **막고 있음 / 막혀 있음 / 중복 / 관련** 관계를 맺는다 (`01-product.md` §2).

- 한 줄 `issue_relations(issue_id, related_id, type)` 이 "issue_id 가 related_id 를 …"를 말한다: `BLOCKS`(막는다), `DUPLICATES`(중복이다: 앞이 중복, 뒤가 원본), `RELATED`(관련: 한 번만, id 가 작은 쪽이 앞). 같은 (앞, 뒤, 종류) 는 한 줄뿐(409 `RELATION_EXISTS`). 서로 막는 관계는 못 만든다(409 `RELATION_CONFLICT`). 자기 자신과는 못 맺는다.
- 티켓 창 본문의 하위 티켓 아래 **관계** 구역: 종류별로 "막혀 있음 · 막고 있음 · 다음 티켓의 중복 · 이 티켓의 중복 · 관련" 목록(상태 아이콘, 키, 제목), 편집 역할이면 + 로 관계를 더하고 ✕ 로 끊는다. 더할 때는 "이 티켓이 막혀 있음/막고 있음/다음의 중복/관련 있음" 중 하나를 고르고 번호나 제목으로 티켓을 찾는다(검색 API). 양쪽 티켓에 모두 보인다(반대 방향의 이름으로).
- **새 티켓 창에서 맺기**(D-237): 새 티켓 창 오른쪽 속성 아래 **관계**(`new-issue-relations.tsx`)에서 같은 선택기(`RelationPicker`)로 종류와 상대 티켓을 고른다. 고른 것은 창에 담아 두고(✕ 로 뺌, 같은 것 두 번과 같은 티켓을 막으면서 막히는 것은 고를 때 거절, 20개까지), 티켓을 만들 때 요청의 `relations: [{kind, targetNumber}]` 로 함께 보낸다. 서버는 **티켓을 저장하기 전에** 상대 티켓이 볼 수 있는 삭제되지 않은 티켓인지 확인하고(`IssueRelations.check`, 아니면 422 로 티켓도 만들어지지 않는다) 같은 트랜잭션에서 맺는다(`attach`). "계속 만들기"로 이어 만들면 관계는 비운다.
- **막힘 표시**: 이 티켓을 막는 티켓 중 끝나지 않은 것(삭제되지 않은, 완료·취소가 아닌)이 하나라도 있으면 `openBlockers` > 0 이고, 보드 카드에 "막힘"이 붙는다. 끝나면 저절로 사라진다.
- 권한: 관계를 만들고 끊는 것은 **시작하는 티켓을 고칠 수 있는 사람**. 다른 쪽 티켓은 볼 수 있는 삭제되지 않은 티켓이면 된다. 목록은 내가 볼 수 없는 티켓과의 관계를 뺀다. 티켓이 삭제되면 목록에서 빠지고(복원하면 돌아옴), 영구 삭제되면 관계도 지워진다(`ON DELETE CASCADE`).
- 트리아지의 "중복"은 이 관계(`DUPLICATE_OF`)를 맺고 티켓을 취소한다([triage.md](triage.md)).
- API: `GET·POST /api/v1/issues/{번호}/relations`(`{kind: BLOCKS|BLOCKED_BY|DUPLICATE_OF|RELATED, targetNumber}`, 기준은 주소의 티켓), `DELETE …/relations/{id}`. 티켓 만들기 요청(`POST /api/v1/issues`, `POST /api/v1/projects/{id}/issues`)의 `relations`(같은 모양, 기준은 새 티켓).
- 아직 없는 것: 관계 변경의 활동 기록·알림, 타임라인의 의존선, 막힘 목록 필터, 중복 시 자동 닫기(트리아지에서만).

표 마이그레이션 `V9`. 코드: `issue/application/IssueRelations.kt`, `issue/api/IssueRelationController.kt`, `features/issues/components/issue-relations.tsx`. 테스트 `IssueRelationsIntegrationTest`.
