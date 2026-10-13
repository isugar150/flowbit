package com.flowbit.backup

import com.flowbit.auth.application.LdapSettingsInput
import com.flowbit.auth.application.SsoSettingsInput
import com.flowbit.notification.PushInput
import com.flowbit.notification.SmtpInput
import com.flowbit.notification.WebhookInput
import com.flowbit.user.domain.AuthSource
import com.flowbit.user.domain.UserRole
import com.flowbit.user.domain.UserStatus
import java.time.Instant

/** The parts of the installation's settings that can be backed up and restored on their own. */
enum class BackupSection { WORKSPACE, AUTH, NOTIFICATIONS, MEMBERS }

data class WorkspaceBackup(
    val name: String,
    val workspaceKey: String,
)

/** The settings as an administrator would type them, passwords and secrets included. */
data class AuthBackup(
    val ldap: LdapSettingsInput,
    val sso: SsoSettingsInput,
)

data class NotificationsBackup(
    val smtp: SmtpInput,
    val push: PushInput,
    val webhooks: List<WebhookInput> = emptyList(),
)

/** One account. The profile image is a file and is not part of a backup. */
data class MemberBackup(
    val loginId: String,
    val email: String? = null,
    val displayName: String? = null,
    val role: UserRole,
    val status: UserStatus = UserStatus.ACTIVE,
    val authSource: AuthSource = AuthSource.LOCAL,
    val externalId: String? = null,
    /** The stored hash, never the password. Only local accounts have one. */
    val passwordHash: String? = null,
    val notifyEmail: Boolean = true,
    val locale: String? = null,
    val timezone: String? = null,
)

/** A section that was not chosen is null. */
data class BackupData(
    val workspace: WorkspaceBackup? = null,
    val auth: AuthBackup? = null,
    val notifications: NotificationsBackup? = null,
    val members: List<MemberBackup>? = null,
)

/** [BackupData] as JSON, encrypted with a key made from the password the administrator chose. */
data class EncryptedBackup(
    val kdf: String,
    val iterations: Int,
    val salt: String,
    val iv: String,
    val ciphertext: String,
)

/**
 * The file an administrator downloads (docs/features/admin-backup.md). Exactly one of [data] and
 * [encrypted] is present; [sections] says what is inside either way, so a browser can list it
 * without the password.
 */
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val createdAt: Instant,
    val sections: List<BackupSection>,
    val data: BackupData? = null,
    val encrypted: EncryptedBackup? = null,
) {
    companion object {
        const val FORMAT = "flowbit-backup"
        const val VERSION = 1
    }
}

data class RestoreResult(
    val restored: List<BackupSection>,
    /** Accounts that were created. */
    val membersAdded: Int,
    /** Accounts left alone because someone already signs in with that id or e-mail address. */
    val membersSkipped: Int,
    val webhooks: Int,
)
