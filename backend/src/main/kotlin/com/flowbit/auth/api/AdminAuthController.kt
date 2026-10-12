package com.flowbit.auth.api

import com.flowbit.auth.application.AuthenticatedUser
import com.flowbit.auth.application.ExternalAuthSettingsService
import com.flowbit.auth.application.LdapProbeResult
import com.flowbit.auth.application.LdapSettingsInput
import com.flowbit.auth.application.SsoSettingsInput
import com.flowbit.auth.domain.LdapSettings
import com.flowbit.auth.domain.SsoButtonStyle
import com.flowbit.auth.domain.SsoProtocol
import com.flowbit.auth.domain.SsoSettings
import com.flowbit.auth.domain.TokenAuth
import com.flowbit.auth.domain.TokenFormat
import com.flowbit.common.web.ClientInfo
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

/** The bind password itself is never sent back; only whether one is stored. */
data class LdapSettingsResponse(
    val enabled: Boolean,
    val url: String,
    val startTls: Boolean,
    val bindDn: String,
    val bindPasswordSet: Boolean,
    val baseDn: String,
    val userFilter: String,
    val loginIdAttribute: String,
    val emailAttribute: String,
    val displayNameAttribute: String,
    val autoProvision: Boolean,
) {
    companion object {
        fun from(settings: LdapSettings) =
            LdapSettingsResponse(
                enabled = settings.enabled,
                url = settings.url,
                startTls = settings.startTls,
                bindDn = settings.bindDn,
                bindPasswordSet = settings.bindPassword.isNotEmpty(),
                baseDn = settings.baseDn,
                userFilter = settings.userFilter,
                loginIdAttribute = settings.loginIdAttribute,
                emailAttribute = settings.emailAttribute,
                displayNameAttribute = settings.displayNameAttribute,
                autoProvision = settings.autoProvision,
            )
    }
}

/** `bindPassword`: absent or null keeps the stored one, a string replaces it (empty clears it). */
data class LdapSettingsRequest(
    val enabled: Boolean = false,
    val url: String = "",
    val startTls: Boolean = false,
    val bindDn: String = "",
    val bindPassword: String? = null,
    val baseDn: String = "",
    val userFilter: String = "",
    val loginIdAttribute: String = "",
    val emailAttribute: String = "",
    val displayNameAttribute: String = "",
    val autoProvision: Boolean = true,
    /** Only read by the test endpoint: a person to look up. */
    val testLoginId: String? = null,
) {
    fun toInput() =
        LdapSettingsInput(
            enabled = enabled,
            url = url,
            startTls = startTls,
            bindDn = bindDn,
            bindPassword = bindPassword,
            baseDn = baseDn,
            userFilter = userFilter,
            loginIdAttribute = loginIdAttribute,
            emailAttribute = emailAttribute,
            displayNameAttribute = displayNameAttribute,
            autoProvision = autoProvision,
        )
}

data class LdapTestResponse(
    val ok: Boolean,
    val message: String,
    val loginId: String?,
    val email: String?,
    val displayName: String?,
) {
    companion object {
        fun from(result: LdapProbeResult) =
            LdapTestResponse(result.ok, result.message, result.identity?.loginId, result.identity?.email, result.identity?.displayName)
    }
}

/** The client secret itself is never sent back; only whether one is stored. */
data class SsoSettingsResponse(
    val enabled: Boolean,
    val displayName: String,
    val issuer: String,
    val clientId: String,
    val clientSecretSet: Boolean,
    val scopes: String,
    val loginIdClaim: String,
    val protocol: SsoProtocol,
    val authorizeUrl: String,
    val tokenUrl: String,
    val userinfoUrl: String,
    val tokenAuth: TokenAuth,
    val tokenFormat: TokenFormat,
    val tokenBody: String,
    val subjectField: String,
    val emailField: String,
    val nameField: String,
    val buttonStyle: SsoButtonStyle,
    val autoProvision: Boolean,
    /** The address to register at the identity provider as the allowed redirect URI. */
    val redirectUri: String,
) {
    companion object {
        fun from(
            settings: SsoSettings,
            redirectUri: String,
        ) = SsoSettingsResponse(
            enabled = settings.enabled,
            displayName = settings.displayName,
            issuer = settings.issuer,
            clientId = settings.clientId,
            clientSecretSet = settings.clientSecret.isNotEmpty(),
            scopes = settings.scopes,
            loginIdClaim = settings.loginIdClaim,
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
            autoProvision = settings.autoProvision,
            redirectUri = redirectUri,
        )
    }
}

/** `clientSecret`: absent or null keeps the stored one. */
data class SsoSettingsRequest(
    val enabled: Boolean = false,
    val displayName: String = "",
    val issuer: String = "",
    val clientId: String = "",
    val clientSecret: String? = null,
    val scopes: String = "",
    val loginIdClaim: String = "",
    val autoProvision: Boolean = true,
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
) {
    fun toInput() =
        SsoSettingsInput(
            enabled = enabled,
            displayName = displayName,
            issuer = issuer,
            clientId = clientId,
            clientSecret = clientSecret,
            scopes = scopes,
            loginIdClaim = loginIdClaim,
            autoProvision = autoProvision,
            protocol = protocol,
            authorizeUrl = authorizeUrl,
            tokenUrl = tokenUrl,
            userinfoUrl = userinfoUrl,
            tokenAuth = tokenAuth,
            tokenFormat = tokenFormat,
            tokenBody = tokenBody,
            subjectField = subjectField,
            emailField = emailField,
            nameField = nameField,
            buttonStyle = buttonStyle,
        )
}

/** Everything under `/api/v1/admin` requires the ADMIN role (SecurityConfig). */
@RestController
@RequestMapping("/api/v1/admin/auth")
class AdminAuthController(
    private val settingsService: ExternalAuthSettingsService,
    private val redirectUri: SsoRedirectUri,
) {
    @GetMapping("/ldap")
    suspend fun ldap(): LdapSettingsResponse = LdapSettingsResponse.from(settingsService.ldap())

    @PutMapping("/ldap")
    suspend fun updateLdap(
        @AuthenticationPrincipal actor: AuthenticatedUser,
        @RequestBody body: LdapSettingsRequest,
        exchange: ServerWebExchange,
    ): LdapSettingsResponse = LdapSettingsResponse.from(settingsService.updateLdap(actor, body.toInput(), ClientInfo.from(exchange)))

    /** Tries the settings in the body without saving them. Always 200; `ok` says how it went. */
    @PostMapping("/ldap/test")
    suspend fun testLdap(
        @RequestBody body: LdapSettingsRequest,
    ): LdapTestResponse = LdapTestResponse.from(settingsService.probeLdap(body.toInput(), body.testLoginId))

    @GetMapping("/sso")
    suspend fun sso(exchange: ServerWebExchange): SsoSettingsResponse = SsoSettingsResponse.from(settingsService.sso(), redirectUri.of(exchange))

    @PutMapping("/sso")
    suspend fun updateSso(
        @AuthenticationPrincipal actor: AuthenticatedUser,
        @RequestBody body: SsoSettingsRequest,
        exchange: ServerWebExchange,
    ): SsoSettingsResponse = SsoSettingsResponse.from(settingsService.updateSso(actor, body.toInput(), ClientInfo.from(exchange)), redirectUri.of(exchange))
}
