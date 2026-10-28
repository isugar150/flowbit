package com.flowbit.auth.domain

import com.flowbit.common.crypto.Secret
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Transient
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant

/** How to reach the LDAP directory people sign in against. Exactly one row exists (id = 1). */
@Table("ldap_settings")
class LdapSettings(
    @Id
    val id: Short,
    var enabled: Boolean,
    /** `ldap://host:389` or `ldaps://host:636`. */
    var url: String,
    var startTls: Boolean,
    /** The account used to look people up; empty for an anonymous search. */
    var bindDn: String,
    /** Encrypted in the database; use [bindPassword] for the plain text (D-226). */
    @Column("bind_password")
    var bindPasswordStored: Secret,
    var baseDn: String,
    /** `{0}` is replaced by what the person typed as their id. */
    var userFilter: String,
    var loginIdAttribute: String,
    var emailAttribute: String,
    var displayNameAttribute: String,
    /** Create the account at the first successful sign-in. */
    var autoProvision: Boolean,
    var updatedAt: Instant,
) {
    @get:Transient
    var bindPassword: String
        get() = bindPasswordStored.value
        set(value) {
            bindPasswordStored = Secret(value)
        }
}

/** OIDC reads everything from the issuer; OAUTH2 has its addresses and exchange spelled out in the settings. */
enum class SsoProtocol { OIDC, OAUTH2 }

/** How the client secret reaches the token address. */
enum class TokenAuth { BASIC, BEARER, BODY }

enum class TokenFormat { FORM, JSON }

enum class SsoButtonStyle { DEFAULT, NAVER_WORKS }

/** The provider behind "single sign-on". Exactly one row exists (id = 1). */
@Table("sso_settings")
class SsoSettings(
    @Id
    val id: Short,
    var enabled: Boolean,
    /** Label of the button on the sign-in page. */
    var displayName: String,
    var issuer: String,
    var clientId: String,
    /** Encrypted in the database; use [clientSecret] for the plain text (D-226). */
    @Column("client_secret")
    var clientSecretStored: Secret,
    var scopes: String,
    /** The claim that becomes the login id; `email` is used when it is missing. */
    var loginIdClaim: String,
    var autoProvision: Boolean,
    var protocol: SsoProtocol,
    var authorizeUrl: String,
    var tokenUrl: String,
    /** Empty: the answer of the code exchange is the profile. */
    var userinfoUrl: String,
    var tokenAuth: TokenAuth,
    var tokenFormat: TokenFormat,
    /** Empty: the standard OAuth 2.0 parameters. Otherwise a body with `{{placeholders}}`. */
    var tokenBody: String,
    /** Where in the profile the values are (OAUTH2 only; OIDC uses `sub`, `email`, `name`). */
    var subjectField: String,
    var emailField: String,
    var nameField: String,
    var buttonStyle: SsoButtonStyle,
    var updatedAt: Instant,
) {
    @get:Transient
    var clientSecret: String
        get() = clientSecretStored.value
        set(value) {
            clientSecretStored = Secret(value)
        }
}

const val EXTERNAL_AUTH_SETTINGS_ID: Short = 1

interface LdapSettingsRepository : CoroutineCrudRepository<LdapSettings, Short>

interface SsoSettingsRepository : CoroutineCrudRepository<SsoSettings, Short>
