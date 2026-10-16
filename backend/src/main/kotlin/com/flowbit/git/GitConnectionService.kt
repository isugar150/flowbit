package com.flowbit.git

import com.flowbit.common.UuidV7
import com.flowbit.common.audit.AuditEventType
import com.flowbit.common.audit.AuditService
import com.flowbit.common.error.FieldError
import com.flowbit.common.error.NotFoundException
import com.flowbit.common.error.ValidationException
import com.flowbit.common.web.ClientInfo
import com.flowbit.issue.domain.IssueStatus
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
    /** What each stage moves an issue to (D-158); stages left out keep their defaults. */
    val statusRules: Map<GitStage, IssueStatus?>? = null,
    /** POLL only for GitHub, when Flowbit cannot be reached from the internet (D-146). */
    val receiveMode: GitReceiveMode = GitReceiveMode.WEBHOOK,
    /** The GitHub organization the app is made for and installed on; GitHub only. */
    val githubOrganization: String? = null,
    /** The switches connections had before [statusRules]; used only when it is missing (old backups). */
    val autoInProgress: Boolean? = null,
    val autoDone: Boolean? = null,
) {
    fun rules(): GitStatusRules = statusRules?.let(::GitStatusRules) ?: GitStatusRules.of(autoInProgress ?: true, autoDone ?: true)
}

/** A connection as a backup carries it: with its id (the webhook address holds it) and its secret. */
data class GitConnectionBackup(
    val id: UUID,
    val name: String,
    val enabled: Boolean = true,
    val provider: GitProviderType,
    val baseUrl: String,
    val secret: String,
    /** Missing in backups made before D-158, which have the two switches below. */
    val statusRules: Map<GitStage, IssueStatus?>? = null,
    val autoInProgress: Boolean? = null,
    val autoDone: Boolean? = null,
    val receiveMode: GitReceiveMode = GitReceiveMode.WEBHOOK,
    // A GitHub App and where it is installed, with its keys (D-146).
    val githubOrganization: String? = null,
    val githubAppId: Long? = null,
    val githubAppSlug: String? = null,
    val githubClientId: String? = null,
    val githubClientSecret: String? = null,
    val githubPrivateKey: String? = null,
    val githubInstallationId: Long? = null,
    val githubRepositorySelection: String? = null,
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

    suspend fun list(): List<GitConnection> = repository.findAllOrdered().toList()

    /** The connection, or null when there is none with this id. */
    suspend fun find(id: UUID): GitConnection? = repository.findById(id)

    /**
     * Whether Git is in use: a connection is switched on and can receive something (a GitHub one
     * once its app is installed). Without one, people see no Git buttons (D-145).
     */
    suspend fun inUse(): Boolean = repository.findAllOrdered().toList().any { it.enabled && (it.provider != GitProviderType.GITHUB || it.githubReady()) }

    /** Adds a connection with a new secret. The secret is in the returned connection and shown this once. */
    suspend fun create(
        input: GitConnectionInput,
        actorId: UUID,
        client: ClientInfo,
    ): GitConnection {
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
                statusRules = checked.rules().toJson(),
                createdAt = now,
                updatedAt = now,
                receiveMode = checked.receiveMode,
                githubOrganization = checked.githubOrganization,
            )
        transactionalOperator.executeAndAwait {
            entityTemplate.insert(connection).awaitSingle()
            auditService.record(AuditEventType.GIT_CONNECTION_CREATED, actorId, client, auditDetail(connection))
        }
        return connection
    }

    suspend fun update(
        id: UUID,
        input: GitConnectionInput,
        actorId: UUID,
        client: ClientInfo,
    ): GitConnection {
        val connection = repository.findById(id) ?: throw notFound()
        val checked = checked(input)
        // A GitHub App belongs to the organization it was made for; another one needs a new connection.
        if (connection.githubAppId != null &&
            (checked.provider != GitProviderType.GITHUB || !checked.githubOrganization.equals(connection.githubOrganization, ignoreCase = true))
        ) {
            throw ValidationException("githubOrganization", "LOCKED", "GitHub 앱을 만든 뒤에는 종류와 조직을 바꿀 수 없습니다. 연결을 새로 만드세요")
        }
        connection.apply {
            name = checked.name
            enabled = checked.enabled
            provider = checkNotNull(checked.provider)
            baseUrl = checked.baseUrl
            statusRules = checked.rules().toJson()
            receiveMode = checked.receiveMode
            githubOrganization = checked.githubOrganization
            updatedAt = clock.instant()
        }
        transactionalOperator.executeAndAwait {
            repository.save(connection)
            auditService.record(AuditEventType.GIT_CONNECTION_CHANGED, actorId, client, auditDetail(connection))
        }
        return connection
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
        // GitHub keeps the app's webhook secret in the app; it is changed there.
        if (connection.provider == GitProviderType.GITHUB) throw ValidationException("secret", "UNSUPPORTED", "GitHub 앱의 웹훅 비밀 값은 GitHub 의 앱 설정에서 바꿉니다")
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
        list().map { connection ->
            GitConnectionBackup(
                id = connection.id,
                name = connection.name,
                enabled = connection.enabled,
                provider = connection.provider,
                baseUrl = connection.baseUrl,
                secret = connection.secret,
                statusRules = connection.rules().all(),
                receiveMode = connection.receiveMode,
                githubOrganization = connection.githubOrganization,
                githubAppId = connection.githubAppId,
                githubAppSlug = connection.githubAppSlug,
                githubClientId = connection.githubClientId,
                githubClientSecret = connection.githubClientSecret,
                githubPrivateKey = connection.githubPrivateKey,
                githubInstallationId = connection.githubInstallationId,
                githubRepositorySelection = connection.githubRepositorySelection,
            )
        }

    /**
     * Makes the connections what the backup says: the ones in it are added or updated under their
     * own ids (so the Git servers' webhook addresses keep working), the others are removed.
     * Call inside the restore's transaction.
     */
    suspend fun restore(backups: List<GitConnectionBackup>) {
        val keep = backups.map { it.id }.toSet()
        repository
            .findAllOrdered()
            .toList()
            .filter { it.id !in keep }
            .forEach { repository.deleteById(it.id) }
        for (backup in backups) {
            val checked = checked(backup.toInput())
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
                    statusRules = checked.rules().toJson(),
                    lastReceivedAt = stored?.lastReceivedAt,
                    lastEvent = stored?.lastEvent,
                    lastResult = stored?.lastResult,
                    lastError = stored?.lastError,
                    createdAt = stored?.createdAt ?: now,
                    updatedAt = now,
                    receiveMode = checked.receiveMode,
                    githubOrganization = checked.githubOrganization,
                    githubAppId = backup.githubAppId,
                    githubAppSlug = backup.githubAppSlug,
                    githubClientId = backup.githubClientId,
                    githubClientSecret = backup.githubClientSecret,
                    githubPrivateKey = backup.githubPrivateKey,
                    githubInstallationId = backup.githubInstallationId,
                    githubRepositorySelection = backup.githubRepositorySelection,
                    lastSyncedAt = stored?.lastSyncedAt,
                    syncError = stored?.syncError,
                    rateLimitRemaining = stored?.rateLimitRemaining,
                )
            if (stored == null) entityTemplate.insert(connection).awaitSingle() else repository.save(connection)
        }
    }

    private fun GitConnectionBackup.toInput() =
        GitConnectionInput(name, enabled, provider, baseUrl, statusRules, receiveMode, githubOrganization, autoInProgress, autoDone)

    // --- rules ----------------------------------------------------------------------------------

    /** [input] trimmed and checked; refuses it with every problem at once. */
    private fun checked(input: GitConnectionInput): GitConnectionInput {
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
        val organization = input.githubOrganization?.trim()?.takeIf { it.isNotEmpty() }
        if (provider == GitProviderType.GITHUB) {
            when {
                organization == null -> errors += FieldError("githubOrganization", "REQUIRED", "GitHub 조직 이름을 입력하세요")
                !GITHUB_ORGANIZATION.matches(organization) -> errors += FieldError("githubOrganization", "INVALID", "GitHub 조직 이름은 영문, 숫자, - 로 된 39자 이하여야 합니다")
            }
        } else if (input.receiveMode == GitReceiveMode.POLL) {
            errors += FieldError("receiveMode", "UNSUPPORTED", "API 로 가져오기는 GitHub 에서만 됩니다")
        }
        if (input.statusRules
                .orEmpty()
                .values
                .any { it != null && it !in GitStatusRules.TARGETS }
        ) {
            errors += FieldError("statusRules", "INVALID", "Git 이 바꿀 수 있는 상태는 준비됨·진행 중·완료·취소입니다")
        }
        if (errors.isNotEmpty()) throw ValidationException(errors)
        return input.copy(
            name = name,
            baseUrl = baseUrl,
            githubOrganization = organization.takeIf { provider == GitProviderType.GITHUB },
        )
    }

    private fun isHttpUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
    }

    /** 32 random bytes, as 64 hex digits: safe to paste anywhere. */
    private fun newSecret(): String = HexFormat.of().formatHex(ByteArray(SECRET_BYTES).also(random::nextBytes))

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
        val SUPPORTED_PROVIDERS = setOf(GitProviderType.GITEA, GitProviderType.GITLAB, GitProviderType.GITHUB)
        private val GITHUB_ORGANIZATION = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$")
        const val GITHUB_URL = "https://github.com"
        private const val SECRET_BYTES = 32
        private const val SECRET_MAX_LENGTH = 256
        private const val EVENT_MAX_LENGTH = 100
        private const val ERROR_MAX_LENGTH = 500
    }
}
