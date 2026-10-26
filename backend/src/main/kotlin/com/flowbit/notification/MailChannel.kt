package com.flowbit.notification

import com.flowbit.user.domain.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Component
import org.springframework.web.util.HtmlUtils

/**
 * Sends mail through the configured SMTP server. Jakarta Mail blocks, so every send runs on
 * [Dispatchers.IO]; a connection is opened per mail (no pool).
 */
@Component
class Mailer {
    /** Throws when the server cannot be reached or refuses the mail. */
    suspend fun send(
        settings: NotificationSettings,
        to: String,
        subject: String,
        text: String,
        html: String,
    ) {
        withContext(Dispatchers.IO) {
            val sender = senderFor(settings)
            val message = sender.createMimeMessage()
            MimeMessageHelper(message, true, "UTF-8").apply {
                setFrom(settings.smtpFromAddress, settings.smtpFromName.ifBlank { "Flowbit" })
                setTo(to)
                setSubject(subject)
                setText(text, html)
            }
            sender.send(message)
        }
    }

    private fun senderFor(settings: NotificationSettings): JavaMailSenderImpl =
        JavaMailSenderImpl().apply {
            host = settings.smtpHost
            port = settings.smtpPort
            defaultEncoding = "UTF-8"
            if (settings.smtpUsername.isNotEmpty()) {
                username = settings.smtpUsername
                password = settings.smtpPassword
            }
            javaMailProperties.apply {
                setProperty("mail.smtp.auth", settings.smtpUsername.isNotEmpty().toString())
                setProperty("mail.smtp.connectiontimeout", TIMEOUT_MILLIS)
                setProperty("mail.smtp.timeout", TIMEOUT_MILLIS)
                setProperty("mail.smtp.writetimeout", TIMEOUT_MILLIS)
                when (settings.smtpSecurity) {
                    SmtpSecurity.STARTTLS -> {
                        setProperty("mail.smtp.starttls.enable", "true")
                        // Never fall back to sending the password and the mail in the clear.
                        setProperty("mail.smtp.starttls.required", "true")
                    }

                    SmtpSecurity.SSL -> {
                        setProperty("mail.smtp.ssl.enable", "true")
                    }

                    SmtpSecurity.NONE -> {}
                }
            }
        }

    private companion object {
        const val TIMEOUT_MILLIS = "10000"
    }
}

/** Mails each subscriber who has an address and has not switched mail off. One mail per person. */
@Component
class MailNotificationChannel(
    private val settingsService: NotificationSettingsService,
    private val userRepository: UserRepository,
    private val mailer: Mailer,
    private val texts: NotificationText,
) : NotificationChannel {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun send(notification: IssueNotification) {
        val shown = notification.forEveryChannel() ?: return
        mail(shown.recipientIds, texts.of(shown), shown.issueKey)
    }

    override suspend fun sendAccessRequest(request: ProjectAccessRequestNotification) {
        mail(request.recipientIds, texts.of(request), "the access request to ${request.projectId}")
    }

    override suspend fun sendContent(notification: ContentNotification) {
        mail(notification.recipientIds, texts.of(notification), notification.title)
    }

    /** Mails the message to each of these people who has an address and has not switched mail off. */
    suspend fun mail(
        recipientIds: List<java.util.UUID>,
        message: NotificationMessage,
        about: String,
    ) {
        if (recipientIds.isEmpty()) return
        val settings = settingsService.settings()
        if (!settings.smtpEnabled) return
        val addresses =
            userRepository
                .findActiveByIds(recipientIds)
                .toList()
                .filter { it.notifyEmail }
                .mapNotNull { it.email }
        for (address in addresses) {
            try {
                mailer.send(settings, address, message.title, textOf(message), htmlOf(message))
            } catch (e: Exception) {
                // One bad address must not keep the mail from everyone after it.
                log.warn("Could not mail the notification for {}: {}", about, e.message)
            }
        }
    }

    private fun textOf(message: NotificationMessage): String = (listOf(message.headline) + message.lines + listOf("", message.url)).joinToString("\n")

    private fun htmlOf(message: NotificationMessage): String {
        val lines = message.lines.joinToString("") { "<li>${HtmlUtils.htmlEscape(it)}</li>" }
        // Without a public address there is nothing a mail client could open.
        val link =
            if (message.url.startsWith("http")) {
                """<p><a href="${HtmlUtils.htmlEscape(message.url)}">${HtmlUtils.htmlEscape(message.linkLabel)}</a></p>"""
            } else {
                ""
            }
        return "<p><strong>${HtmlUtils.htmlEscape(message.title)}</strong></p><p>${HtmlUtils.htmlEscape(message.headline)}</p>" +
            (if (lines.isEmpty()) "" else "<ul>$lines</ul>") + link
    }
}
