package com.flowbit

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommentInboxMigrationTest {
    private fun id(n: Long) = UUID(0, n).toString()

    @Test
    fun `기존 댓글 알림은 유일한 원문만 연결하고 해결 알림은 읽음을 보존해 완료한다`() {
        PostgreSQLContainer(DockerImageName.parse("postgres:18")).use { postgres ->
            postgres.start()

            fun migrate(target: String) =
                Flyway
                    .configure()
                    .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                    .target(target)
                    .load()
                    .migrate()
            migrate("26")
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        insert into users(id, login_id, display_name, password_hash, role, status) values
                          ('${id(1)}', 'author', '작성자', 'hash', 'ADMIN', 'ACTIVE'),
                          ('${id(2)}', 'receiver', '수신자', 'hash', 'MEMBER', 'ACTIVE');
                        insert into projects(id, name, created_by) values ('${id(10)}', '프로젝트', '${id(1)}');
                        insert into documents(id, project_id, title, created_by) values ('${id(11)}', '${id(10)}', '문서', '${id(1)}');
                        insert into issues(id, number, title, status, status_id, created_by)
                        select '${id(20)}', 1, '티켓', 'BACKLOG', id, '${id(1)}' from issue_statuses where category = 'BACKLOG' limit 1;
                        insert into issue_comments(id, issue_id, author_id, parent_id, content, body, created_at, updated_at, deleted_at, resolved_at, resolved_by) values
                          ('${id(101)}', '${id(20)}', '${id(1)}', null, '{}', '고유 원 댓글', now(), now(), null, now(), '${id(1)}'),
                          ('${id(102)}', '${id(20)}', '${id(1)}', '${id(101)}', '{}', '고유 답글', now(), now(), null, null, null),
                          ('${id(103)}', '${id(20)}', '${id(1)}', null, '{}', '중복 댓글', now(), now(), null, null, null),
                          ('${id(104)}', '${id(20)}', '${id(1)}', null, '{}', '중복 댓글', now(), now(), null, null, null),
                          ('${id(105)}', '${id(20)}', '${id(1)}', null, '{}', '수정 후', now(), now(), null, null, null),
                          ('${id(106)}', '${id(20)}', '${id(1)}', null, '{}', '삭제 댓글', now(), now(), now(), null, null),
                          ('${id(107)}', '${id(20)}', '${id(1)}', null, '{}', '오래된 댓글', now() - interval '1 hour', now(), null, null, null),
                          ('${id(108)}', '${id(20)}', '${id(1)}', null, '{}', '', now(), now(), null, null, null);
                        insert into issue_attachments(id, issue_id, comment_id, file_key, file_name, size_bytes, uploaded_by)
                          values ('${id(501)}', '${id(20)}', '${id(108)}', 'file', '검토.txt', 1, '${id(1)}');
                        insert into content_comments(id, target_type, target_id, author_id, parent_id, content, body, resolved_at, resolved_by) values
                          ('${id(201)}', 'DOCUMENT', '${id(11)}', '${id(1)}', null, '{}', '문서 원 댓글', now(), '${id(1)}'),
                          ('${id(202)}', 'DOCUMENT', '${id(11)}', '${id(1)}', '${id(201)}', '{}', '문서 답글', null, null),
                          ('${id(203)}', 'PROJECT', '${id(10)}', '${id(1)}', null, '{}', '프로젝트 원 댓글', null, null),
                          ('${id(204)}', 'DOCUMENT', '${id(11)}', '${id(1)}', null, '{}', '', null, null);
                        insert into content_files(id, target_type, target_id, comment_id, file_key, file_name, size_bytes, uploaded_by)
                          values ('${id(502)}', 'DOCUMENT', '${id(11)}', '${id(204)}', 'file', '첨부.txt', 1, '${id(1)}');
                        insert into content_threads(id, target_type, target_id, block_id, quote, created_by, resolved_at, resolved_by) values
                          ('${id(301)}', 'ISSUE', '${id(20)}', 'block-one', '본문 인용', '${id(1)}', now(), '${id(1)}'),
                          ('${id(302)}', 'DOCUMENT', '${id(11)}', 'block-two', '문서 인용', '${id(1)}', null, null),
                          ('${id(303)}', 'PROJECT', '${id(10)}', 'block-three', '프로젝트 인용', '${id(1)}', null, null);
                        insert into content_thread_comments(id, thread_id, author_id, content, body) values
                          ('${id(401)}', '${id(301)}', '${id(1)}', '{}', '블록 댓글'),
                          ('${id(402)}', '${id(301)}', '${id(1)}', '{}', '블록 답글'),
                          ('${id(403)}', '${id(302)}', '${id(1)}', '{}', '문서 블록'),
                          ('${id(404)}', '${id(303)}', '${id(1)}', '{}', '프로젝트 블록');
                        """.trimIndent(),
                    )
                }

                data class Case(
                    val path: String,
                    val body: String,
                    val comment: Long? = null,
                    val reply: Boolean = false,
                    val completed: Boolean = false,
                )
                val cases =
                    listOf(
                        Case("/issue/FB-1", "고유 원 댓글", 101, completed = true),
                        Case("/issue/OLD-1", "고유 답글", 102, reply = true, completed = true),
                        Case("/issue/FB-1", "검토.txt", 108),
                        Case("/doc/${id(11)}", "문서 원 댓글\n언급: 수신자", 201, completed = true),
                        Case("/doc/${id(11)}", "문서 답글", 202, reply = true, completed = true),
                        Case("/project/${id(10)}", "프로젝트 원 댓글", 203),
                        Case("/doc/${id(11)}", "첨부.txt", 204),
                        Case("/issue/FB-1", "블록: 본문 인용\n블록 댓글", 401, completed = true),
                        Case("/issue/FB-1", "블록: 본문 인용\n블록 답글", 402, reply = true, completed = true),
                        Case("/doc/${id(11)}", "블록: 문서 인용\n문서 블록", 403),
                        Case("/project/${id(10)}", "블록: 프로젝트 인용\n프로젝트 블록", 404),
                        Case("/issue/FB-1", "중복 댓글"),
                        Case("/issue/FB-1", "수정 전"),
                        Case("/issue/FB-1", "삭제 댓글"),
                        Case("/issue/FB-1", "오래된 댓글"),
                        Case("/issue/FB-2", "고유 원 댓글"),
                        Case("/issue/FB-1#comment-${id(101)}", "고유 원 댓글", completed = true),
                    )
                connection
                    .prepareStatement(
                        """
                        insert into inbox_notifications(id, user_id, title, headline, body, path, created_at, read_at)
                        values (?, ?, '알림', ?, ?, ?, now() + interval '1 second', case when ? then now() end)
                        """.trimIndent(),
                    ).use { insert ->
                        cases.forEachIndexed { index, case ->
                            insert.setObject(1, UUID.fromString(id(1000L + index)))
                            insert.setObject(2, UUID.fromString(id(2)))
                            insert.setString(3, if (case.reply) "작성자 님이 댓글에 답글을 남겼습니다" else "작성자 님이 댓글을 남겼습니다")
                            insert.setString(4, case.body)
                            insert.setString(5, case.path)
                            insert.setBoolean(6, index % 2 == 0)
                            insert.executeUpdate()
                        }
                    }
                assertEquals(1, migrate("27").migrationsExecuted)
                connection.prepareStatement("select path, body, read_at, completed_at from inbox_notifications where id = ?").use { query ->
                    cases.forEachIndexed { index, case ->
                        query.setObject(1, UUID.fromString(id(1000L + index)))
                        query.executeQuery().use { row ->
                            assertTrue(row.next())
                            assertEquals(case.path + (case.comment?.let { "#comment-${id(it)}" } ?: ""), row.getString("path"))
                            assertEquals(case.body, row.getString("body"))
                            assertEquals(index % 2 == 0, row.getObject("read_at") != null)
                            assertEquals(case.completed, row.getObject("completed_at") != null)
                        }
                    }
                }
                assertEquals(0, migrate("27").migrationsExecuted)
            }
        }
    }
}
