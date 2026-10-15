package com.flowbit.git

import com.flowbit.common.UuidV7
import com.flowbit.common.audit.AuditEventType
import com.flowbit.common.audit.AuditService
import com.flowbit.common.error.FieldError
import com.flowbit.common.error.NotFoundException
import com.flowbit.common.error.ValidationException
import com.flowbit.common.web.ClientInfo
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.net.URI
import java.security.SecureRandom
import java.time.Clock
import java.util.HexFormat
import java.util.UUID

/** What an administrator sets on a connection. The secret is never part of it: Flowbit makes it. */
data class GitConnectionInput(
    val name: String,
    val enabled: Boolean = true,
    val provider: GitProviderType?,
    /** Blank for GitHub means https://github.com. */
    val baseUrl: String,
    val allProjects: Boolean = true,
    /** The projects whose issues the connection may link and change; used when [allProjects] is false. */
    val projectIds: List<UUID> = emptyList(),
    val autoInProgress: Boolean = true,
    val autoDone: Boolean = true,
)

/** A connection with the projects it is limited to (empty when it covers every project). */
data class GitConnectionView(
    val connection: GitConnection,
    val projectIds: List<UUID>,
)

/** A connection as a backup carries it: with its id (the webhook address holds it) and its secret. */
data class GitConnectionBackup(
    val id: UUID,
    val name: String,
    val enabled: Boolean = true,
    val provider: GitProviderType,
    val baseUrl: String,
    val secret: String,
    val allProjects: Boolean = true,
    val projectIds: List<UUID> = emptyList(),
    val autoInProgress: Boolean = true,
    val autoDone: Boolean = true,
)

/**
 * The Git servers an administrator connected (docs/features/git-integration.md "관리자 설정").
 * Callers are already known to be administrators: the endpoints sit under `/api/v1/admin`.
 */
@Service
class GitConnectionService(
    private val repository: GitConnectionRepository,
    private val databaseClient: DatabaseClient,
    private val entityTemplate: R2dbcEntityTemplate,
    private val transactionalOperator: TransactionalOperator,
    private val auditService: AuditService,
    private val clock: Clock,
) {
    private val random = SecureRandom()

    suspend fun list(): List<GitConnectionView> {
        val connections = repository.findAllOrdered().toList()
        val projects = allProjectIds()
        return connections.map { GitConnectionView(it, projects[it.id].orEmpty()) }
    }

    /** The connection, or null when there is none with this id. */
    suspend fun find(id: UUID): GitConnectionView? = repository.findById(id)?.let { GitConnectionView(it, projectIds(it.id)) }

    /** Whether any connection exists, enabled or not. */
    suspend fun any(): Boolean = repository.count() > 0

    /** Adds a connection with a new secret. The secret is in the returned connection and shown this once. */
    suspend fun create(
        input: GitConnectionInput,
        actorId: UUID,
        client: ClientInfo,
    ): GitConnectionView {
        val checked = checked(input)
        val now = clock.instant()
        val connection =
            GitConnection(
                id = UuidV7.generate(now),
                name = checked.name,
                provider = checkNotNull(checked.provider),
                baseUrl = checked.baseUrl,
                secret = newSecret(),
                enabled = checked.enabled,
                allProjects = checked.allProjects,
                autoInProgress = checked.autoInProgress,
                autoDone = checked.autoDone,
                createdAt = now,
                updatedAt = now,
            )
        transactionalOperator.executeAndAwait {
            entityTemplate.insert(connection).awaitSingle()
            replaceProjects(connection.id, checked.projectIds)
            auditService.record(AuditEventType.GIT_CONNECTION_CREATED, actorId, client, auditDetail(connection))
        }
        return GitConnectionView(connection, checked.projectIds)
    }

    suspend fun update(
        id: UUID,
        input: GitConnectionInput,
        actorId: UUID,
        client: ClientInfo,
    ): GitConnectionView {
        val connection = repository.findById(id) ?: throw notFound()
        val checked = checked(input)
        connection.apply {
            name = checked.name
            enabled = checked.enabled
            provider = checkNotNull(checked.provider)
            baseUrl = checked.baseUrl
            allProjects = checked.allProjects
            autoInProgress = checked.autoInProgress
            autoDone = checked.autoDone
            updatedAt = clock.instant()
        }
        transactionalOperator.executeAndAwait {
            repository.save(connection)
            replaceProjects(connection.id, checked.projectIds)
            auditService.record(AuditEventType.GIT_CONNECTION_CHANGED, actorId, client, auditDetail(connection))
        }
        return GitConnectionView(connection, checked.projectIds)
    }

    /** Removes the connection with every branch, pull request and commit it linked. The history stays. */
    suspend fun delete(
        id: UUID,
        actorId: UUID,
        client: ClientInfo,
    ) {
        val connection = repository.findById(id) ?: throw notFound()
        transactionalOperator.executeAndAwait {
            repository.deleteById(id)
            auditService.record(AuditEventType.GIT_CONNECTION_DELETED, actorId, client, auditDetail(connection))
        }
    }

    /** Makes a new secret; the old one stops working at once. */
    suspend fun regenerateSecret(
        id: UUID,
        actorId: UUID,
        client: ClientInfo,
    ): String {
        val connection = repository.findById(id) ?: throw notFound()
        connection.secret = newSecret()
        connection.updatedAt = clock.instant()
        transactionalOperator.executeAndAwait {
            repository.save(connection)
            auditService.record(AuditEventType.GIT_SECRET_REGENERATED, actorId, client, auditDetail(connection))
        }
        return connection.secret
    }

    /** Remembers the last call from the Git server, for the administrator to check the setup. */
    suspend fun recordReceipt(
        id: UUID,
        event: String?,
        result: String?,
        error: String?,
    ) {
        databaseClient
            .sql("update git_connections set last_received_at = :now, last_event = :event, last_result = :result, last_error = :error where id = :id")
            .bind("now", clock.instant())
            .bindNullable("event", event?.take(EVENT_MAX_LENGTH))
            .bindNullable("result", result)
            .bindNullable("error", error?.take(ERROR_MAX_LENGTH))
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    // --- backup ---------------------------------------------------------------------------------

    suspend fun backups(): List<GitConnectionBackup> =
        list().map { (connection, projectIds) ->
            GitConnectionBackup(
                id = connection.id,
                name = connection.name,
                enabled = connection.enabled,
                provider = connection.provider,
                baseUrl = connection.baseUrl,
                secret = connection.secret,
                allProjects = connection.allProjects,
                projectIds = projectIds,
                autoInProgress = connection.autoInProgress,
                autoDone = connection.autoDone,
            )
        }

    /**
     * Makes the connections what the backup says: the ones in it are added or updated under their
     * own ids (so the Git servers' webhook addresses keep working), the others are removed.
     * Projects that do not exist here are left out. Call inside the restore's transaction.
     */
    suspend fun restore(backups: List<GitConnectionBackup>) {
        val keep = backups.map { it.id }.toSet()
        repository
            .findAllOrdered()
            .toList()
            .filter { it.id !in keep }
            .forEach { repository.deleteById(it.id) }
        val existingProjects = projectsThatExist(backups.flatMap { it.projectIds }.toSet())
        for (backup in backups) {
            val checked = checked(backup.toInput(), requireProjects = false)
            if (backup.secret.isBlank() || backup.secret.length > SECRET_MAX_LENGTH) {
                throw ValidationException("secret", "INVALID", "비밀 값이 올바르지 않습니다")
            }
            val now = clock.instant()
            val stored = repository.findById(backup.id)
            val connection =
                GitConnection(
                    id = backup.id,
                    name = checked.name,
                    provider = checkNotNull(checked.provider),
                    baseUrl = checked.baseUrl,
                    secret = backup.secret,
                    enabled = checked.enabled,
                    allProjects = checked.allProjects,
                    autoInProgress = checked.autoInProgress,
                    autoDone = checked.autoDone,
                    lastReceivedAt = stored?.lastReceivedAt,
                    lastEvent = stored?.lastEvent,
                    lastResult = stored?.lastResult,
                    lastError = stored?.lastError,
                    createdAt = stored?.createdAt ?: now,
                    updatedAt = now,
                )
            if (stored == null) entityTemplate.insert(connection).awaitSingle() else repository.save(connection)
            replaceProjects(connection.id, if (checked.allProjects) emptyList() else checked.projectIds.filter { it in existingProjects })
        }
    }

    private fun GitConnectionBackup.toInput() = GitConnectionInput(name, enabled, provider, baseUrl, allProjects, projectIds, autoInProgress, autoDone)

    // --- rules ----------------------------------------------------------------------------------

    /** [input] trimmed and checked; refuses it with every problem at once. */
    private suspend fun checked(
        input: GitConnectionInput,
        requireProjects: Boolean = true,
    ): GitConnectionInput {
        val errors = mutableListOf<FieldError>()
        val name = input.name.trim()
        when {
            name.isEmpty() -> errors += FieldError("name", "REQUIRED", "이름을 입력하세요")
            name.length > GitConnection.NAME_MAX_LENGTH -> errors += FieldError("name", "TOO_LONG", "이름은 ${GitConnection.NAME_MAX_LENGTH}자 이하여야 합니다")
        }
        val provider = input.provider
        when {
            provider == null -> errors += FieldError("provider", "REQUIRED", "종류를 고르세요")
            provider !in SUPPORTED_PROVIDERS -> errors += FieldError("provider", "UNSUPPORTED", "아직 연결할 수 없는 종류입니다")
        }
        val baseUrl =
            input.baseUrl
                .trim()
                .trimEnd('/')
                .ifEmpty { if (provider == GitProviderType.GITHUB) GITHUB_URL else "" }
        if (baseUrl.isEmpty()) {
            errors += FieldError("baseUrl", "REQUIRED", "서버 주소를 입력하세요")
        } else if (!isHttpUrl(baseUrl)) {
            errors += FieldError("baseUrl", "INVALID_URL", "http:// 또는 https:// 로 시작하는 주소를 입력하세요")
        }
        val projectIds = if (input.allProjects) emptyList() else input.projectIds.distinct()
        if (!input.allProjects && requireProjects) {
            if (projectIds.isEmpty()) {
                errors += FieldError("projectIds", "REQUIRED", "프로젝트를 하나 이상 고르세요")
            } else if (projectsThatExist(projectIds.toSet()).size != projectIds.size) {
                errors += FieldError("projectIds", "UNKNOWN_PROJECT", "없는 프로젝트입니다")
            }
        }
        if (errors.isNotEmpty()) throw ValidationException(errors)
        return input.copy(name = name, baseUrl = baseUrl, projectIds = projectIds)
    }

    private fun isHttpUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
    }

    /** 32 random bytes, as 64 hex digits: safe to paste anywhere. */
    private fun newSecret(): String = HexFormat.of().formatHex(ByteArray(SECRET_BYTES).also(random::nextBytes))

    private suspend fun replaceProjects(
        connectionId: UUID,
        projectIds: List<UUID>,
    ) {
        databaseClient
            .sql("delete from git_connection_projects where connection_id = :id")
            .bind("id", connectionId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        for (projectId in projectIds) {
            databaseClient
                .sql("insert into git_connection_projects (connection_id, project_id) values (:connectionId, :projectId)")
                .bind("connectionId", connectionId)
                .bind("projectId", projectId)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }

    suspend fun projectIds(connectionId: UUID): List<UUID> =
        databaseClient
            .sql("select project_id from git_connection_projects where connection_id = :id order by project_id")
            .bind("id", connectionId)
            .map { row -> checkNotNull(row.get("project_id", UUID::class.java)) }
            .all()
            .collectList()
            .awaitSingle()

    private suspend fun allProjectIds(): Map<UUID, List<UUID>> =
        databaseClient
            .sql("select connection_id, project_id from git_connection_projects order by project_id")
            .map { row -> checkNotNull(row.get("connection_id", UUID::class.java)) to checkNotNull(row.get("project_id", UUID::class.java)) }
            .all()
            .collectList()
            .awaitSingle()
            .groupBy({ it.first }, { it.second })

    private suspend fun projectsThatExist(ids: Set<UUID>): Set<UUID> {
        if (ids.isEmpty()) return emptySet()
        return databaseClient
            .sql("select id from projects where id in (:ids)")
            .bind("ids", ids)
            .map { row -> checkNotNull(row.get("id", UUID::class.java)) }
            .all()
            .collectList()
            .awaitSingle()
            .toSet()
    }

    private fun auditDetail(connection: GitConnection) =
        mapOf(
            "id" to connection.id.toString(),
            "name" to connection.name,
            "provider" to connection.provider.name,
        )

    private fun notFound() = NotFoundException("GIT_CONNECTION_NOT_FOUND", "Git 연결을 찾을 수 없습니다")

    private fun DatabaseClient.GenericExecuteSpec.bindNullable(
        name: String,
        value: String?,
    ) = if (value == null) bindNull(name, String::class.java) else bind(name, value)

    companion object {
        /** The kinds of server this version can receive events from (docs/features/git-integration.md "구현 순서"). */
        val SUPPORTED_PROVIDERS = setOf(GitProviderType.GITEA)
        const val GITHUB_URL = "https://github.com"
        private const val SECRET_BYTES = 32
        private const val SECRET_MAX_LENGTH = 256
        private const val EVENT_MAX_LENGTH = 100
        private const val ERROR_MAX_LENGTH = 500
    }
}
