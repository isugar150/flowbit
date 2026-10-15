package com.flowbit.support

import com.flowbit.TestcontainersConfiguration
import com.flowbit.common.ratelimit.RateLimiter
import com.flowbit.notification.NotificationDispatcher
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.r2dbc.core.DatabaseClient

/** Starts the whole application on a random port against a PostgreSQL 18 container. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // Uploads made by tests stay inside the build directory.
    properties = ["flowbit.storage.path=build/test-uploads", "flowbit.retention.enabled=false", "flowbit.git.sync-enabled=false"],
)
@Import(TestcontainersConfiguration::class, TestClockConfiguration::class)
abstract class IntegrationTest {
    @Value("\${local.server.port}")
    protected var port: Int = 0

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    @Autowired
    protected lateinit var clock: MutableClock

    @Autowired
    private lateinit var rateLimiter: RateLimiter

    @Autowired
    private lateinit var notificationDispatcher: NotificationDispatcher

    /** Ids of the projects a test made, by the short handle the test gave them ("PAY"). */
    private val projectIds = mutableMapOf<String, String>()

    protected fun remember(
        handle: String,
        projectId: Any?,
    ) {
        projectIds[handle] = projectId as String
    }

    /** The id of the project a test made under this handle, for addresses and request bodies. */
    protected fun p(handle: String): String = projectIds.getValue(handle)

    @BeforeEach
    fun resetState() {
        projectIds.clear()
        execute("truncate table audit_events, user_sessions, issues, project_members, projects, users cascade")
        // The three labels every installation starts with stay; the ones tests add go.
        execute("delete from labels where name not in ('버그', '기능', '중요')")
        execute(
            "update instance_settings set setup_completed_at = null, organization_name = 'Flowbit', logo_key = null, workspace_key = 'FB', next_issue_number = 1",
        )
        execute("delete from retention_policies")
        execute("delete from webhooks")
        execute("delete from git_connections")
        execute("delete from notification_settings")
        execute("insert into notification_settings (id) values (1)")
        execute("delete from ldap_settings")
        execute("insert into ldap_settings (id) values (1)")
        execute("delete from sso_settings")
        execute("insert into sso_settings (id) values (1)")
        clock.reset()
        rateLimiter.reset()
        notificationDispatcher.reset()
    }

    protected fun client(): ApiClient = ApiClient("http://localhost:$port")

    protected fun execute(sql: String) {
        databaseClient
            .sql(sql)
            .fetch()
            .rowsUpdated()
            .block()
    }

    protected fun count(sql: String): Int =
        checkNotNull(
            databaseClient
                .sql(sql)
                .map { row, _ -> (row.get(0) as Number).toInt() }
                .one()
                .block(),
        )

    protected fun text(sql: String): String =
        checkNotNull(
            databaseClient
                .sql(sql)
                .map { row, _ -> row.get(0) as String }
                .one()
                .block(),
        )
}
