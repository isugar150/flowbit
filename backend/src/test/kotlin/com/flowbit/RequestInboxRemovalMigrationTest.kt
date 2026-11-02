package com.flowbit

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestInboxRemovalMigrationTest {
    @Test
    fun `기존 대기는 백로그로 옮기고 거절 내용과 삭제 표시 및 연결 데이터를 보존한다`() {
        PostgreSQLContainer(DockerImageName.parse("postgres:18")).use { postgres ->
            postgres.start()

            fun flyway(target: String) =
                Flyway
                    .configure()
                    .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                    .target(target)
                    .load()
            flyway("23").migrate()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        insert into users (id, login_id, password_hash, role, status)
                        values ('00000000-0000-7000-8000-000000000001', 'test', 'hash', 'ADMIN', 'ACTIVE');
                        insert into projects (id, name, created_by, triage_enabled)
                        values ('00000000-0000-7000-8000-000000000002', '이전', '00000000-0000-7000-8000-000000000001', true);
                        insert into issues (id, number, project_id, title, description, content, status, status_id, created_by, triage, triage_rejected, triage_rejected_at, deleted_at)
                        select ('00000000-0000-7000-8000-' || lpad(n::text, 12, '0'))::uuid, n,
                               '00000000-0000-7000-8000-000000000002', '티켓 ' || n, '보존할 본문', '{"type":"doc"}',
                               case when n = 12 then 'CANCELED' else 'IN_PROGRESS' end,
                               case when n = 12 then '00000000-0000-7000-8000-0000000000a5' else '00000000-0000-7000-8000-0000000000a3' end::uuid,
                               '00000000-0000-7000-8000-000000000001', n in (10, 11), n = 12,
                               case when n = 12 then now() end, case when n = 11 then now() end
                        from generate_series(10, 13) n;
                        insert into issue_assignees (issue_id, user_id)
                        values ('00000000-0000-7000-8000-000000000010', '00000000-0000-7000-8000-000000000001');
                        insert into issue_comments (id, issue_id, author_id, content, body)
                        values (gen_random_uuid(), '00000000-0000-7000-8000-000000000012', '00000000-0000-7000-8000-000000000001', '{}', '거절 이유');
                        insert into issue_attachments (id, issue_id, file_key, file_name, size_bytes, uploaded_by)
                        values (gen_random_uuid(), '00000000-0000-7000-8000-000000000010', 'preserved', '자료.txt', 10, '00000000-0000-7000-8000-000000000001');
                        insert into issue_relations (id, issue_id, related_id, type)
                        values (gen_random_uuid(), '00000000-0000-7000-8000-000000000012', '00000000-0000-7000-8000-000000000010', 'DUPLICATES');
                        insert into retention_policies (item, amount, unit) values ('REJECTED_REQUESTS', 1, 'DAYS'), ('COMMENTS', 30, 'DAYS');
                        insert into webhooks (id, name, url, event_types, created_at, updated_at)
                        values (gen_random_uuid(), 'exclusive', 'https://example.com', 'TRIAGE_REQUESTED', now(), now()),
                               (gen_random_uuid(), 'mixed', 'https://example.com', 'CREATED,TRIAGE_REQUESTED,COMMENT_ADDED', now(), now()),
                               (gen_random_uuid(), 'all', 'https://example.com', '', now(), now());
                        """.trimIndent(),
                    )
                    assertEquals(1, flyway("24").migrate().migrationsExecuted)
                    statement.executeQuery("select number, status, version, deleted_at, description, content from issues order by number").use { rows ->
                        for (number in 10..13) {
                            assertTrue(rows.next())
                            assertEquals(number, rows.getInt("number"))
                            assertEquals(
                                if (number < 12) {
                                    "BACKLOG"
                                } else if (number == 12) {
                                    "CANCELED"
                                } else {
                                    "IN_PROGRESS"
                                },
                                rows.getString("status"),
                            )
                            assertEquals(if (number < 12) 2 else 1, rows.getInt("version"))
                            assertEquals(number == 11, rows.getObject("deleted_at") != null)
                            assertEquals("보존할 본문", rows.getString("description"))
                            assertTrue(rows.getString("content").contains("doc"))
                        }
                        assertFalse(rows.next())
                    }

                    fun count(sql: String): Int =
                        statement.executeQuery(sql).use { rows ->
                            rows.next()
                            rows.getInt(1)
                        }
                    assertEquals(
                        2,
                        count("select count(*) from issues i join issue_statuses s on s.id = i.status_id where i.number in (10,11) and s.category = 'BACKLOG'"),
                    )
                    for (table in listOf("issue_assignees", "issue_comments", "issue_attachments", "issue_relations")) {
                        assertEquals(1, count("select count(*) from $table"))
                    }
                    assertEquals(1, count("select count(*) from retention_policies where item = 'COMMENTS'"))
                    assertEquals(0, count("select count(*) from retention_policies where item = 'REJECTED_REQUESTS'"))
                    assertEquals(1, count("select count(*) from webhooks where name = 'exclusive' and not enabled and event_types = ''"))
                    assertEquals(1, count("select count(*) from webhooks where name = 'mixed' and enabled and event_types = 'CREATED,COMMENT_ADDED'"))
                    assertEquals(1, count("select count(*) from webhooks where name = 'all' and enabled and event_types = ''"))
                    assertEquals(
                        0,
                        count(
                            "select count(*) from information_schema.columns where table_schema = 'public' and column_name in ('triage','triage_enabled','triage_rejected','triage_rejected_at')",
                        ),
                    )
                    assertEquals(0, flyway("24").migrate().migrationsExecuted)
                }
            }
        }
    }
}
