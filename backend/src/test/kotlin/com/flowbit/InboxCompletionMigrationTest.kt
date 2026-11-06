package com.flowbit

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InboxCompletionMigrationTest {
    @Test
    fun `기존 읽음을 보존하고 단일 상태 변경은 처리 대상에서 제외한다`() {
        PostgreSQLContainer(DockerImageName.parse("postgres:18")).use { postgres ->
            postgres.start()

            fun migrate(target: String) =
                Flyway
                    .configure()
                    .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                    .target(target)
                    .load()
                    .migrate()
            migrate("24")
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        insert into users (id, login_id, password_hash, role, status)
                        values ('00000000-0000-7000-8000-000000000001', 'test', 'hash', 'ADMIN', 'ACTIVE');
                        insert into inbox_notifications (id, user_id, title, headline, body, path, created_at, read_at)
                        values
                        ('00000000-0000-7000-8000-000000000002', '00000000-0000-7000-8000-000000000001', '상태', '변경', '상태: 백로그 → 진행 중', '/issue/FB-1', now(), now()),
                        ('00000000-0000-7000-8000-000000000003', '00000000-0000-7000-8000-000000000001', '댓글', '댓글', '확인 요청', '/issue/FB-1', now(), null),
                        ('00000000-0000-7000-8000-000000000004', '00000000-0000-7000-8000-000000000001', '복합', '변경', E'상태: 백로그 → 진행 중\n담당자: 김민수', '/issue/FB-1', now(), null);
                        """.trimIndent(),
                    )
                    assertEquals(1, migrate("25").migrationsExecuted)
                    statement.executeQuery("select title, actionable, read_at, completed_at from inbox_notifications order by id").use { rows ->
                        assertTrue(rows.next())
                        assertEquals("상태", rows.getString("title"))
                        assertFalse(rows.getBoolean("actionable"))
                        assertTrue(rows.getObject("read_at") != null)
                        assertNull(rows.getObject("completed_at"))
                        repeat(2) {
                            assertTrue(rows.next())
                            assertTrue(rows.getBoolean("actionable"))
                            assertNull(rows.getObject("read_at"))
                            assertNull(rows.getObject("completed_at"))
                        }
                    }
                    statement.execute(
                        """
                        insert into inbox_notifications (id, user_id, title, headline, body, path, created_at, read_at, completed_at)
                        values
                        ('00000000-0000-7000-8000-000000000005', '00000000-0000-7000-8000-000000000001', '수정', '김민수 님이 티켓을 수정했습니다', '우선순위: 낮음 → 높음', '/issue/FB-1', now(), now(), now()),
                        ('00000000-0000-7000-8000-000000000006', '00000000-0000-7000-8000-000000000001', '댓글 보존', '김민수 님이 댓글을 남겼습니다', '댓글', '/issue/FB-1#comment-00000000-0000-7000-8000-000000000003', now(), now(), now()),
                        ('00000000-0000-7000-8000-000000000007', '00000000-0000-7000-8000-000000000001', '새 티켓', '김민수 님이 티켓을 만들었습니다', '', '/issue/FB-1', now(), null, null);
                        """.trimIndent(),
                    )
                    assertEquals(1, migrate("26").migrationsExecuted)
                    statement.executeQuery("select actionable, read_at, completed_at from inbox_notifications where title = '수정'").use { rows ->
                        assertTrue(rows.next())
                        assertFalse(rows.getBoolean("actionable"))
                        assertTrue(rows.getObject("read_at") != null)
                        assertNull(rows.getObject("completed_at"))
                    }
                    statement.executeQuery("select actionable, completed_at from inbox_notifications where title = '댓글 보존'").use { rows ->
                        assertTrue(rows.next())
                        assertTrue(rows.getBoolean("actionable"))
                        assertTrue(rows.getObject("completed_at") != null)
                    }
                    statement.executeQuery("select actionable from inbox_notifications where title = '새 티켓'").use { rows ->
                        assertTrue(rows.next())
                        assertTrue(rows.getBoolean("actionable"))
                    }
                }
            }
        }
    }
}
