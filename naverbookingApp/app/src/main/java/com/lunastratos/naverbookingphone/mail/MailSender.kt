package com.lunastratos.naverbookingphone.mail

import com.lunastratos.naverbookingphone.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

data class MailConfig(
    val host: String,
    val port: Int,
    val ssl: Boolean,
    val user: String,
    val password: String,
    val from: String,
    val to: String,
) {
    val effectiveFrom: String get() = from.ifBlank { user }

    companion object {
        fun fromSettings(s: Settings) = MailConfig(
            host = s.smtpHost,
            port = s.smtpPort,
            ssl = s.smtpSsl,
            user = s.smtpUser,
            password = s.smtpPassword,
            from = s.mailFrom,
            to = s.mailTo,
        )
    }
}

object MailSender {

    suspend fun send(cfg: MailConfig, subject: String, body: String) {
        require(cfg.host.isNotBlank()) { "SMTP 호스트가 비어 있습니다." }
        require(cfg.to.isNotBlank()) { "받는 사람이 비어 있습니다." }
        require(cfg.effectiveFrom.isNotBlank()) { "보내는 주소가 비어 있습니다." }

        withContext(Dispatchers.IO) {
            val props = Properties().apply {
                put("mail.smtp.host", cfg.host)
                put("mail.smtp.port", cfg.port.toString())
                put("mail.smtp.connectiontimeout", "20000")
                put("mail.smtp.timeout", "20000")
                put("mail.smtp.writetimeout", "20000")
                if (cfg.ssl) {
                    put("mail.smtp.ssl.enable", "true")
                } else {
                    put("mail.smtp.starttls.enable", "true")
                }
                if (cfg.user.isNotBlank()) {
                    put("mail.smtp.auth", "true")
                }
            }

            val session = if (cfg.user.isNotBlank()) {
                Session.getInstance(props, object : Authenticator() {
                    override fun getPasswordAuthentication() =
                        PasswordAuthentication(cfg.user, cfg.password)
                })
            } else {
                Session.getInstance(props)
            }

            val msg = MimeMessage(session).apply {
                setFrom(InternetAddress(cfg.effectiveFrom))
                setRecipients(
                    Message.RecipientType.TO,
                    InternetAddress.parse(cfg.to)
                )
                this.subject = subject
                setText(body, "UTF-8")
            }
            Transport.send(msg)
        }
    }
}
