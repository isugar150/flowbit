package com.flowbit.common.crypto

import io.r2dbc.spi.ConnectionFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions
import org.springframework.data.r2dbc.dialect.DialectResolver

/**
 * A secret as the program holds it: the plain text, which the database only ever sees encrypted
 * ([SecretCipher]). A column mapped to this type is converted on every read and write, whether the
 * row is read through a repository method, a `@Query` or the entity template, so no code path can
 * forget to. `toString` hides the value so it never lands in a log by accident.
 */
class Secret(
    val value: String,
) {
    override fun equals(other: Any?): Boolean = other is Secret && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "Secret(***)"
}

@WritingConverter
class SecretWritingConverter(
    private val cipher: SecretCipher,
) : Converter<Secret, String> {
    override fun convert(source: Secret): String = cipher.encrypt(source.value)
}

@ReadingConverter
class SecretReadingConverter(
    private val cipher: SecretCipher,
) : Converter<String, Secret> {
    override fun convert(source: String): Secret = Secret(cipher.decrypt(source))
}

@Configuration(proxyBeanMethods = false)
class SecretConversions {
    /** Replaces the auto-configured conversions with the same ones plus the secret converters. */
    @Bean
    fun r2dbcCustomConversions(
        connectionFactory: ConnectionFactory,
        cipher: SecretCipher,
    ): R2dbcCustomConversions =
        R2dbcCustomConversions.of(
            DialectResolver.getDialect(connectionFactory),
            listOf(SecretWritingConverter(cipher), SecretReadingConverter(cipher)),
        )
}
