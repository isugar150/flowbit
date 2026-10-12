package com.flowbit.auth.application

import com.flowbit.auth.domain.EXTERNAL_AUTH_SETTINGS_ID
import com.flowbit.auth.domain.LdapSettings
import com.flowbit.auth.domain.LdapSettingsRepository
import com.flowbit.auth.domain.SsoButtonStyle
import com.flowbit.auth.domain.SsoProtocol
import com.flowbit.auth.domain.SsoSettings
import com.flowbit.auth.domain.SsoSettingsRepository
import com.flowbit.auth.domain.TokenAuth
import com.flowbit.auth.domain.TokenFormat
import com.flowbit.common.audit.AuditEventType
import com.flowbit.common.audit.AuditService
import com.flowbit.common.error.FieldError
import com.flowbit.common.error.ValidationException
import com.flowbit.common.web.ClientInfo
import com.unboundid.ldap.sdk.Filter
import com.unboundid.ldap.sdk.LDAPException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.net.URI
import java.time.Clock

/** Every field as an administrator typed it. A null password or secret keeps the stored one. */
data class LdapSettingsInput(
    val enabled: Boolean,
    val url: String,
    val startTls: Boolean,
    val bindDn: String,
    val bindPassword: String?,
    val baseDn: String,
    val userFilter: String,
    val loginIdAttribute: String,
    val emailAttribute: String,
    val displayNameAttribute: String,
    val autoProvision: Boolean,
)

data class SsoSettingsInput(
    val enabled: Boolean,
    val displayName: String,
    val issuer: String,
    val clientId: String,
    val clientSecret: String?,
    val scopes: String,
    val loginIdClaim: String,
    val autoProvision: Boolean,
    val protocol: SsoProtocol = SsoProtocol.OIDC,
    val authorizeUrl: String = "",
    val tokenUrl: String = "",
    val userinfoUrl: String = "",
    val tokenAuth: TokenAuth = TokenAuth.BASIC,
    val tokenFormat: TokenFormat = TokenFormat.FORM,
    val tokenBody: String = "",
    val subjectField: String = "sub",
    val emailField: String = "email",
    val nameField: String = "name",
    val buttonStyle: SsoButtonStyle = SsoButtonStyle.DEFAULT,
)

data class LdapProbeResult(
    val ok: Boolean,
    val message: String,
    /** The person that was looked up, when a login id was given and found. */
    val identity: ExternalIdentity? = null,
)

/**
 * LDAP and single sign-on settings (docs/features/M2b-ldap-and-sso.md). Callers are already known
 * to be administrators: every endpoint that changes these sits under `/api/v1/admin`.
 * The bind password and the client secret are stored as they are and never returned to a client.
 */
@Service
class ExternalAuthSettingsService(
    private val ldapRepository: LdapSettingsRepository,
    private val ssoRepository: SsoSettingsRepository,
    private val ldapDirectory: LdapDirectory,
    private val oidcClient: OidcClient,
    private val auditService: AuditService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun ldap(): LdapSettings = checkNotNull(ldapRepository.findById(EXTERNAL_AUTH_SETTINGS_ID))

    suspend fun sso(): SsoSettings = checkNotNull(ssoRepository.findById(EXTERNAL_AUTH_SETTINGS_ID))

    suspend fun updateLdap(
        actor: AuthenticatedUser,
        input: LdapSettingsInput,
        client: ClientInfo,
    ): LdapSettings {
        val settings = ldap().apply { apply(input) }
        if (settings.enabled) ldapErrors(settings).takeIf { it.isNotEmpty() }?.let { throw ValidationException(it) }
        settings.updatedAt = clock.instant()
        val saved = ldapRepository.save(settings)
        recordChange(actor, client, "LDAP", saved.enabled)
        return saved
    }

    /** Tries [input] against the directory without saving it. */
    suspend fun probeLdap(
        input: LdapSettingsInput,
        rawLoginId: String?,
    ): LdapProbeResult {
        val settings = ldap().apply { apply(input) }
        ldapErrors(settings).firstOrNull()?.let { return LdapProbeResult(false, it.message) }
        val loginId = rawLoginId?.trim()?.takeIf { it.isNotEmpty() }
        return try {
            val identity = ldapDirectory.probe(settings, loginId)
            when {
                loginId == null -> LdapProbeResult(true, "LDAP 서버에 연결했습니다")
                identity == null -> LdapProbeResult(false, "연결은 됐지만 그 아이디에 해당하는 사용자를 한 명으로 찾지 못했습니다")
                else -> LdapProbeResult(true, "사용자를 찾았습니다", identity)
            }
        } catch (ex: LdapUnavailableException) {
            LdapProbeResult(false, ex.message.orEmpty())
        }
    }

    suspend fun updateSso(
        actor: AuthenticatedUser,
        input: SsoSettingsInput,
        client: ClientInfo,
    ): SsoSettings {
        val settings =
            sso().apply {
                enabled = input.enabled
                displayName = input.displayName.trim().ifEmpty { DEFAULT_SSO_NAME }
                issuer = input.issuer.trim().trimEnd('/')
                clientId = input.clientId.trim()
                input.clientSecret?.let { clientSecret = it }
                scopes =
                    input.scopes
                        .trim()
                        .split(WHITESPACE)
                        .filter { it.isNotEmpty() }
                        .joinToString(" ")
                loginIdClaim = input.loginIdClaim.trim()
                autoProvision = input.autoProvision
                protocol = input.protocol
                authorizeUrl = input.authorizeUrl.trim()
                tokenUrl = input.tokenUrl.trim()
                userinfoUrl = input.userinfoUrl.trim()
                tokenAuth = input.tokenAuth
                tokenFormat = input.tokenFormat
                tokenBody = input.tokenBody.trim()
                subjectField = input.subjectField.trim()
                emailField = input.emailField.trim()
                nameField = input.nameField.trim()
                buttonStyle = input.buttonStyle
            }
        val errors = mutableListOf<FieldError>()
        if (settings.displayName.length > SSO_NAME_MAX_LENGTH) {
            errors += FieldError("displayName", "TOO_LONG", "버튼 이름은 ${SSO_NAME_MAX_LENGTH}자 이하여야 합니다")
        }
        if (settings.enabled) errors += ssoErrors(settings)
        if (errors.isNotEmpty()) throw ValidationException(errors)
        if (settings.enabled && settings.protocol == SsoProtocol.OIDC) {
            // Saving an issuer nobody can sign in through would only be found out at the sign-in page.
            try {
                oidcClient.provider(settings.issuer)
            } catch (ex: OidcException) {
                log.warn("OIDC discovery failed for {}: {}", settings.issuer, ex.message)
                throw ValidationException("issuer", "UNREACHABLE", "이 주소에서 OpenID Connect 설정 문서(/.well-known/openid-configuration)를 읽지 못했습니다")
            }
        }
        settings.updatedAt = clock.instant()
        val saved = ssoRepository.save(settings)
        recordChange(actor, client, "SSO", saved.enabled)
        return saved
    }

    private fun LdapSettings.apply(input: LdapSettingsInput) {
        enabled = input.enabled
        url = input.url.trim()
        startTls = input.startTls
        bindDn = input.bindDn.trim()
        input.bindPassword?.let { bindPassword = it }
        baseDn = input.baseDn.trim()
        userFilter = input.userFilter.trim()
        loginIdAttribute = input.loginIdAttribute.trim()
        emailAttribute = input.emailAttribute.trim()
        displayNameAttribute = input.displayNameAttribute.trim()
        autoProvision = input.autoProvision
    }

    private fun ldapErrors(settings: LdapSettings): List<FieldError> {
        val errors = mutableListOf<FieldError>()
        val endpoint = LdapEndpoint.parse(settings.url)
        when {
            endpoint == null -> errors += FieldError("url", "INVALID", "ldap://호스트:포트 또는 ldaps://호스트:포트 형식으로 입력하세요")
            endpoint.secure && settings.startTls -> errors += FieldError("startTls", "INVALID", "ldaps:// 주소에는 StartTLS 를 함께 쓸 수 없습니다")
        }
        if (settings.baseDn.isEmpty()) errors += FieldError("baseDn", "REQUIRED", "기준 DN 을 입력하세요")
        if (LdapDirectory.PLACEHOLDER !in settings.userFilter) {
            errors += FieldError("userFilter", "INVALID", "사용자 필터에는 입력한 아이디가 들어갈 자리 {0} 이 있어야 합니다")
        } else {
            try {
                Filter.create(settings.userFilter.replace(LdapDirectory.PLACEHOLDER, "x"))
            } catch (_: LDAPException) {
                errors += FieldError("userFilter", "INVALID", "사용자 필터의 형식이 올바르지 않습니다")
            }
        }
        listOf(
            "loginIdAttribute" to settings.loginIdAttribute,
            "emailAttribute" to settings.emailAttribute,
            "displayNameAttribute" to settings.displayNameAttribute,
        ).forEach { (field, value) ->
            if (!ATTRIBUTE_NAME.matches(value)) errors += FieldError(field, "INVALID", "LDAP 속성 이름을 입력하세요")
        }
        return errors
    }

    private fun ssoErrors(settings: SsoSettings): List<FieldError> {
        val errors = mutableListOf<FieldError>()
        if (settings.clientId.isEmpty()) errors += FieldError("clientId", "REQUIRED", "클라이언트 ID 를 입력하세요")
        if (settings.clientSecret.isEmpty()) errors += FieldError("clientSecret", "REQUIRED", "클라이언트 시크릿(API 키)을 입력하세요")
        if (settings.loginIdClaim.isEmpty()) errors += FieldError("loginIdClaim", "REQUIRED", "아이디로 쓸 값의 이름을 입력하세요")
        when (settings.protocol) {
            SsoProtocol.OIDC -> {
                if (!isHttpUrl(settings.issuer)) errors += FieldError("issuer", "INVALID", "https:// 로 시작하는 발급자(issuer) 주소를 입력하세요")
                if ("openid" !in settings.scopes.split(' ')) errors += FieldError("scopes", "INVALID", "범위에는 openid 가 들어 있어야 합니다")
            }

            SsoProtocol.OAUTH2 -> {
                if (!isHttpUrl(settings.authorizeUrl)) errors += FieldError("authorizeUrl", "INVALID", "로그인 시작 주소를 https:// 로 시작하는 주소로 입력하세요")
                if (!isHttpUrl(settings.tokenUrl)) errors += FieldError("tokenUrl", "INVALID", "코드 교환 주소를 https:// 로 시작하는 주소로 입력하세요")
                if (settings.userinfoUrl.isNotEmpty() && !isHttpUrl(settings.userinfoUrl)) {
                    errors += FieldError("userinfoUrl", "INVALID", "사용자 정보 주소의 형식이 올바르지 않습니다")
                }
                if (settings.tokenBody.length > TOKEN_BODY_MAX_LENGTH) errors += FieldError("tokenBody", "TOO_LONG", "요청 본문이 너무 깁니다")
                if (settings.subjectField.isEmpty()) errors += FieldError("subjectField", "REQUIRED", "사용자를 구분하는 값의 이름을 입력하세요")
            }
        }
        return errors
    }

    private fun isHttpUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return !uri.host.isNullOrBlank() && uri.scheme?.lowercase() in setOf("https", "http")
    }

    private suspend fun recordChange(
        actor: AuthenticatedUser,
        client: ClientInfo,
        target: String,
        enabled: Boolean,
    ) {
        auditService.record(AuditEventType.AUTH_SETTINGS_CHANGED, actor.id, client, mapOf("target" to target, "enabled" to enabled.toString()))
    }

    private companion object {
        const val DEFAULT_SSO_NAME = "SSO"
        const val SSO_NAME_MAX_LENGTH = 40
        const val TOKEN_BODY_MAX_LENGTH = 4000
        val WHITESPACE = Regex("\\s+")
        val ATTRIBUTE_NAME = Regex("^[A-Za-z][A-Za-z0-9;.-]*$")
    }
}
