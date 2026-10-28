package com.flowbit.common.crypto

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component

/**
 * Encrypts the secrets that were stored as plain text, once a key is set (docs/features/account-security.md).
 * It runs after every start and is safe to repeat: a value that already reads `enc:v1:` is left alone, an
 * empty one stays empty, and a row changed by someone else in the meantime is skipped until the next start.
 * It works on the columns directly, so the converters of [Secret] are not in the way.
 */
@Component
class SecretMigration(
    private val databaseClient: DatabaseClient,
    private val cipher: SecretCipher,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        scope.launch {
            try {
                checkSecrets()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Encrypting the stored secrets failed; it is tried again at the next start", e)
            }
        }
    }

    @PreDestroy
    fun stop() {
        scope.cancel()
    }

    private suspend fun checkSecrets() {
        if (cipher.enabled) {
            val encrypted = migrate()
            if (encrypted > 0) log.info("Encrypted {} stored secret(s) that were plain text", encrypted)
        } else {
            val locked = countEncrypted()
            if (locked > 0) {
                log.error("{} stored secret(s) are encrypted but FLOWBIT_SECRET_KEY is not set: they cannot be read until the key is set", locked)
            }
        }
    }

    /** Encrypts every plain-text secret that is left. Returns how many values were encrypted. */
    suspend fun migrate(): Int {
        if (!cipher.enabled) return 0
        var encrypted = 0
        for (column in COLUMNS) {
            val rows =
                databaseClient
                    .sql(
                        "select ${column.key}::text as pk, ${column.name} as value from ${column.table} where ${column.name} <> '' and ${column.name} not like :prefix",
                    ).bind("prefix", "${SecretCipher.PREFIX}%")
                    .map { row, _ -> checkNotNull(row.get("pk", String::class.java)) to checkNotNull(row.get("value", String::class.java)) }
                    .all()
                    .collectList()
                    .awaitSingle()
            for ((pk, plain) in rows) {
                encrypted +=
                    databaseClient
                        .sql("update ${column.table} set ${column.name} = :encrypted where ${column.key}::text = :pk and ${column.name} = :plain")
                        .bind("encrypted", cipher.encrypt(plain))
                        .bind("pk", pk)
                        .bind("plain", plain)
                        .fetch()
                        .rowsUpdated()
                        .awaitSingle()
                        .toInt()
            }
        }
        return encrypted
    }

    private suspend fun countEncrypted(): Int =
        COLUMNS.sumOf { column ->
            databaseClient
                .sql("select count(*) from ${column.table} where ${column.name} like :prefix")
                .bind("prefix", "${SecretCipher.PREFIX}%")
                .map { row, _ -> (row.get(0) as Number).toInt() }
                .one()
                .awaitSingle()
        }

    private data class SecretColumn(
        val table: String,
        val key: String,
        val name: String,
    )

    private companion object {
        /** Every column that holds a secret. A new one is added here and mapped as a [Secret] in its entity. */
        val COLUMNS =
            listOf(
                SecretColumn("ldap_settings", "id", "bind_password"),
                SecretColumn("sso_settings", "id", "client_secret"),
                SecretColumn("notification_settings", "id", "smtp_password"),
                SecretColumn("notification_settings", "id", "fcm_service_account"),
                SecretColumn("webhooks", "id", "headers"),
                SecretColumn("webhooks", "id", "signing_secret"),
                SecretColumn("git_connections", "id", "secret"),
                SecretColumn("git_connections", "id", "github_client_secret"),
                SecretColumn("git_connections", "id", "github_private_key"),
            )
    }
}
