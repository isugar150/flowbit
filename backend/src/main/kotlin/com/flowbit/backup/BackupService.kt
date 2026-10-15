package com.flowbit.backup

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.auth.application.ExternalAuthSettingsService
import com.flowbit.auth.application.LdapSettingsInput
import com.flowbit.auth.application.SsoSettingsInput
import com.flowbit.auth.domain.LdapSettings
import com.flowbit.auth.domain.SsoSettings
import com.flowbit.common.UuidV7
import com.flowbit.common.audit.AuditEventType
import com.flowbit.common.audit.AuditService
import com.flowbit.common.error.ApiException
import com.flowbit.common.error.ConflictException
import com.flowbit.common.error.ValidationException
import com.flowbit.common.web.ClientInfo
import com.flowbit.git.GitConnectionBackup
import com.flowbit.git.GitConnectionService
import com.flowbit.instance.application.InstanceService
import com.flowbit.notification.NotificationSettings
import com.flowbit.notification.NotificationSettingsService
import com.flowbit.notification.PushInput
import com.flowbit.notification.SmtpInput
import com.flowbit.notification.Webhook
import com.flowbit.notification.WebhookHeaders
import com.flowbit.notification.WebhookInput
import com.flowbit.user.domain.AuthSource
import com.flowbit.user.domain.CredentialPolicy
import com.flowbit.user.domain.User
import com.flowbit.user.domain.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withContext
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

/**
 * Backing up and restoring the installation's settings as one file
 * (docs/features/admin-backup.md). Callers are already known to be administrators: both endpoints
 * sit under `/api/v1/admin`. The file carries passwords, secrets and password hashes as they are
 * stored, which is why it can be encrypted with a password of the administrator's choosing.
 */
@Service
class BackupService(
    private val instanceService: InstanceService,
    private val externalAuth: ExternalAuthSettingsService,
    private val notifications: NotificationSettingsService,
    private val gitConnections: GitConnectionService,
    private val userRepository: UserRepository,
    private val entityTemplate: R2dbcEntityTemplate,
    private val transactionalOperator: TransactionalOperator,
    private val auditService: AuditService,
    private val jsonMapper: JsonMapper,
    private val clock: Clock,
) {
    suspend fun export(
        actor: AuthenticatedUser,
        sections: List<BackupSection>,
        password: String?,
        client: ClientInfo,
    ): BackupFile {
        val chosen = chosen(sections)
        val secret = checkedPassword(password)
        val data =
            BackupData(
                workspace = if (BackupSection.WORKSPACE in chosen) instanceService.workspace().let { WorkspaceBackup(it.name, it.workspaceKey) } else null,
                auth = if (BackupSection.AUTH in chosen) AuthBackup(ldapOf(externalAuth.ldap()), ssoOf(externalAuth.sso())) else null,
                notifications =
                    if (BackupSection.NOTIFICATIONS in chosen) {
                        val settings = notifications.settings()
                        NotificationsBackup(smtpOf(settings), pushOf(settings), notifications.webhooks().map(::webhookOf))
                    } else {
                        null
                    },
                members = if (BackupSection.MEMBERS in chosen) userRepository.findAllOrdered().toList().map(::memberOf) else null,
                git = if (BackupSection.GIT in chosen) gitConnections.backups() else null,
            )
        val file =
            if (secret == null) {
                BackupFile(createdAt = clock.instant(), sections = chosen, data = data)
            } else {
                val encrypted = withContext(Dispatchers.Default) { BackupCrypto.encrypt(jsonMapper.writeValueAsBytes(data), secret) }
                BackupFile(createdAt = clock.instant(), sections = chosen, encrypted = encrypted)
            }
        auditService.record(AuditEventType.BACKUP_EXPORTED, actor.id, client, detailOf(chosen, secret != null))
        return file
    }

    /**
     * Puts the chosen sections of [rawFile] in place of what is stored. All of it or none of it:
     * a value the settings screens would refuse stops the restore and nothing is changed.
     */
    suspend fun restore(
        actor: AuthenticatedUser,
        rawFile: JsonNode,
        sections: List<BackupSection>,
        password: String?,
        client: ClientInfo,
    ): RestoreResult {
        val chosen = chosen(sections)
        val file = read(rawFile)
        val missing = chosen.filter { it !in file.sections }
        if (missing.isNotEmpty()) throw ValidationException("sections", "NOT_IN_FILE", "백업 파일에 없는 항목입니다: ${missing.joinToString { LABELS.getValue(it) }}")
        val data = dataOf(file, password)
        return try {
            transactionalOperator.executeAndAwait {
                var added = 0
                var skipped = 0
                var webhooks = 0
                var git = 0
                for (section in chosen) {
                    when (section) {
                        BackupSection.WORKSPACE -> restoreWorkspace(data.workspace ?: throw broken())
                        BackupSection.AUTH -> restoreAuth(actor, data.auth ?: throw broken(), client)
                        BackupSection.NOTIFICATIONS -> webhooks = restoreNotifications(data.notifications ?: throw broken())
                        BackupSection.MEMBERS -> {
                            val counts = restoreMembers(data.members ?: throw broken())
                            added = counts.first
                            skipped = counts.second
                        }
                        BackupSection.GIT -> git = restoreGit(data.git ?: throw broken())
                    }
                }
                auditService.record(AuditEventType.BACKUP_RESTORED, actor.id, client, detailOf(chosen, file.encrypted != null))
                RestoreResult(chosen, added, skipped, webhooks, git)
            }
        } catch (_: DuplicateKeyException) {
            // Someone was added between the check and the insert; nothing of the restore was kept.
            throw ConflictException("RESTORE_CONFLICT", "복원하는 동안 같은 아이디의 구성원이 추가됐습니다. 다시 시도하세요")
        }
    }

    // --- reading the file -----------------------------------------------------------------------

    private fun read(rawFile: JsonNode): BackupFile {
        val file =
            try {
                jsonMapper.treeToValue(rawFile, BackupFile::class.java)
            } catch (_: JacksonException) {
                throw broken()
            }
        if (file.format != BackupFile.FORMAT) throw ValidationException("file", "INVALID", "Flowbit 백업 파일이 아닙니다")
        if (file.version != BackupFile.VERSION) {
            throw ValidationException("file", "UNSUPPORTED_VERSION", "이 버전의 Flowbit 에서 읽을 수 없는 백업 파일입니다 (형식 버전 ${file.version})")
        }
        return file
    }

    private suspend fun dataOf(
        file: BackupFile,
        password: String?,
    ): BackupData {
        file.data?.let { return it }
        val encrypted = file.encrypted ?: throw broken()
        if (password.isNullOrEmpty()) throw ValidationException("password", "REQUIRED", "암호로 보호된 백업 파일입니다. 백업할 때 정한 암호를 입력하세요")
        val plain =
            withContext(Dispatchers.Default) { BackupCrypto.decrypt(encrypted, password) }
                ?: throw ValidationException("password", "WRONG_PASSWORD", "암호가 맞지 않거나 파일이 손상됐습니다")
        return try {
            jsonMapper.readValue(plain, BackupData::class.java)
        } catch (_: JacksonException) {
            throw broken()
        }
    }

    // --- restoring ------------------------------------------------------------------------------

    private suspend fun restoreWorkspace(workspace: WorkspaceBackup) {
        section(BackupSection.WORKSPACE) {
            instanceService.rename(workspace.name)
            instanceService.setWorkspaceKey(workspace.workspaceKey)
        }
    }

    private suspend fun restoreAuth(
        actor: AuthenticatedUser,
        auth: AuthBackup,
        client: ClientInfo,
    ) {
        section(BackupSection.AUTH) {
            // A null secret means "keep the stored one" to the settings service; a restore replaces it.
            externalAuth.updateLdap(actor, auth.ldap.copy(bindPassword = auth.ldap.bindPassword.orEmpty()), client)
            externalAuth.updateSso(actor, auth.sso.copy(clientSecret = auth.sso.clientSecret.orEmpty()), client)
        }
    }

    /** The mail and push settings are replaced, and so is the whole list of API notifications. */
    private suspend fun restoreNotifications(backup: NotificationsBackup): Int {
        section(BackupSection.NOTIFICATIONS) {
            notifications.updateSmtp(backup.smtp.copy(password = backup.smtp.password.orEmpty()))
            notifications.updatePush(backup.push.copy(serviceAccount = backup.push.serviceAccount.orEmpty()))
            notifications.webhooks().forEach { notifications.deleteWebhook(it.id) }
            backup.webhooks.forEach { notifications.createWebhook(it.copy(signingSecret = it.signingSecret.orEmpty())) }
        }
        return backup.webhooks.size
    }

    /** The connections are made what the backup says, keeping their ids (and so their webhook addresses). */
    private suspend fun restoreGit(backup: List<GitConnectionBackup>): Int {
        section(BackupSection.GIT) { gitConnections.restore(backup) }
        return backup.size
    }

    /**
     * Adds the accounts nobody has yet. An account that exists is left exactly as it is, so a
     * restore can never lock the administrator who runs it out or undo a password change.
     */
    private suspend fun restoreMembers(members: List<MemberBackup>): Pair<Int, Int> {
        var added = 0
        var skipped = 0
        val taken = mutableSetOf<String>()
        for (raw in members) {
            val member = checked(raw)
            val names = listOfNotNull(member.loginId, member.email)
            if (names.any { it in taken || userRepository.existsByLoginIdOrEmail(it) }) {
                skipped += 1
                continue
            }
            taken += names
            val now = clock.instant()
            val user =
                User.external(
                    id = UuidV7.generate(now),
                    loginId = member.loginId,
                    authSource = member.authSource,
                    externalId = member.externalId,
                    email = member.email,
                    role = member.role,
                    displayName = member.displayName,
                    now = now,
                    passwordHash = member.passwordHash,
                )
            user.status = member.status
            user.notifyEmail = member.notifyEmail
            user.locale = member.locale
            user.timezone = member.timezone
            entityTemplate.insert(user).awaitSingle()
            added += 1
        }
        return added to skipped
    }

    /** [raw] with its id and address normalized, or a refusal naming the account. */
    private fun checked(raw: MemberBackup): MemberBackup {
        val member =
            raw.copy(
                loginId = CredentialPolicy.normalizeLoginId(raw.loginId),
                email = raw.email?.let(CredentialPolicy::normalizeLoginId)?.takeIf { it.isNotEmpty() },
                displayName = raw.displayName?.trim()?.takeIf { it.isNotEmpty() },
                externalId = raw.externalId?.takeIf { it.isNotEmpty() },
                passwordHash = raw.passwordHash?.takeIf { it.isNotBlank() },
            )
        val ok =
            CredentialPolicy.isAcceptableExternalLoginId(member.loginId) &&
                (member.email == null || CredentialPolicy.isEmail(member.email)) &&
                (member.displayName == null || member.displayName.length <= User.DISPLAY_NAME_MAX_LENGTH) &&
                // Only local accounts keep a password here (users_password_hash_check).
                (member.authSource == AuthSource.LOCAL) == (member.passwordHash != null) &&
                (member.passwordHash == null || member.passwordHash.length <= HASH_MAX_LENGTH) &&
                (member.locale == null || member.locale in LOCALES)
        if (!ok) throw ValidationException("file", "INVALID", "구성원 '${raw.loginId.take(NAME_PREVIEW_LENGTH)}' 의 값이 올바르지 않습니다")
        return member
    }

    /** Runs the same checks as the settings screens and names the section when one of them refuses. */
    private suspend fun section(
        section: BackupSection,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (ex: ApiException) {
            val reason = ex.fieldErrors.firstOrNull()?.message ?: ex.message.orEmpty()
            throw ValidationException("file", "INVALID", "${LABELS.getValue(section)} 값이 올바르지 않아 복원하지 않았습니다: $reason")
        }
    }

    // --- shapes ---------------------------------------------------------------------------------

    private fun chosen(sections: List<BackupSection>): List<BackupSection> =
        BackupSection.entries.filter { it in sections }.ifEmpty { throw ValidationException("sections", "REQUIRED", "항목을 하나 이상 고르세요") }

    private fun checkedPassword(password: String?): String? {
        if (password.isNullOrEmpty()) return null
        if (password.length !in PASSWORD_MIN_LENGTH..PASSWORD_MAX_LENGTH) {
            throw ValidationException("password", "INVALID", "암호는 $PASSWORD_MIN_LENGTH~${PASSWORD_MAX_LENGTH}자여야 합니다")
        }
        return password
    }

    private fun broken() = ValidationException("file", "INVALID", "백업 파일을 읽을 수 없습니다. 파일이 손상됐거나 Flowbit 백업 파일이 아닙니다")

    private fun detailOf(
        sections: List<BackupSection>,
        encrypted: Boolean,
    ) = mapOf("sections" to sections.joinToString(","), "encrypted" to encrypted.toString())

    private fun ldapOf(settings: LdapSettings) =
        LdapSettingsInput(
            enabled = settings.enabled,
            url = settings.url,
            startTls = settings.startTls,
            bindDn = settings.bindDn,
            bindPassword = settings.bindPassword,
            baseDn = settings.baseDn,
            userFilter = settings.userFilter,
            loginIdAttribute = settings.loginIdAttribute,
            emailAttribute = settings.emailAttribute,
            displayNameAttribute = settings.displayNameAttribute,
            autoProvision = settings.autoProvision,
        )

    private fun ssoOf(settings: SsoSettings) =
        SsoSettingsInput(
            enabled = settings.enabled,
            displayName = settings.displayName,
            issuer = settings.issuer,
            clientId = settings.clientId,
            clientSecret = settings.clientSecret,
            scopes = settings.scopes,
            loginIdClaim = settings.loginIdClaim,
            autoProvision = settings.autoProvision,
            protocol = settings.protocol,
            authorizeUrl = settings.authorizeUrl,
            tokenUrl = settings.tokenUrl,
            userinfoUrl = settings.userinfoUrl,
            tokenAuth = settings.tokenAuth,
            tokenFormat = settings.tokenFormat,
            tokenBody = settings.tokenBody,
            subjectField = settings.subjectField,
            emailField = settings.emailField,
            nameField = settings.nameField,
            buttonStyle = settings.buttonStyle,
        )

    private fun smtpOf(settings: NotificationSettings) =
        SmtpInput(
            enabled = settings.smtpEnabled,
            host = settings.smtpHost,
            port = settings.smtpPort,
            security = settings.smtpSecurity,
            username = settings.smtpUsername,
            password = settings.smtpPassword,
            fromAddress = settings.smtpFromAddress,
            fromName = settings.smtpFromName,
        )

    private fun pushOf(settings: NotificationSettings) =
        PushInput(
            enabled = settings.pushEnabled,
            serviceAccount = settings.fcmServiceAccount,
            apiKey = settings.fcmApiKey,
            messagingSenderId = settings.fcmMessagingSenderId,
            appId = settings.fcmAppId,
            vapidKey = settings.fcmVapidKey,
        )

    private fun webhookOf(webhook: Webhook) =
        WebhookInput(
            name = webhook.name,
            enabled = webhook.enabled,
            url = webhook.url,
            method = webhook.method,
            contentType = webhook.contentType,
            headers = WebhookHeaders.parse(webhook.headers, jsonMapper),
            bodyTemplate = webhook.bodyTemplate,
            signingSecret = webhook.signingSecret,
            eventTypes = webhook.eventTypeSet.toList(),
        )

    private fun memberOf(user: User) =
        MemberBackup(
            loginId = user.loginId,
            email = user.email,
            displayName = user.displayName,
            role = user.role,
            status = user.status,
            authSource = user.authSource,
            externalId = user.externalId,
            passwordHash = user.passwordHash,
            notifyEmail = user.notifyEmail,
            locale = user.locale,
            timezone = user.timezone,
        )

    private companion object {
        const val PASSWORD_MIN_LENGTH = 8
        const val PASSWORD_MAX_LENGTH = 128
        const val HASH_MAX_LENGTH = 500
        const val NAME_PREVIEW_LENGTH = 40
        val LOCALES = setOf("ko", "en")
        val LABELS =
            mapOf(
                BackupSection.WORKSPACE to "워크스페이스",
                BackupSection.AUTH to "로그인 연동",
                BackupSection.NOTIFICATIONS to "알림",
                BackupSection.MEMBERS to "구성원",
                BackupSection.GIT to "Git 연동",
            )
    }
}
